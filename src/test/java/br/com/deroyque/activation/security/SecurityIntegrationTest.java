package br.com.deroyque.activation.security;

import br.com.deroyque.activation.request.persistence.ActivationRequestRepository;
import br.com.deroyque.activation.support.PostgresTestConfiguration;
import br.com.deroyque.activation.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SecurityIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ActivationRequestRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WireMockServer testJwkServer;
    private final TestRestTemplate rawClient = new TestRestTemplate();

    @BeforeEach
    void prepare() {
        repository.deleteAll();
        restoreJwks();
    }

    @Test
    void shouldRejectAnonymousRequestsAndKeepCorrelationId() throws Exception {
        var headers = new HttpHeaders();
        headers.set("X-Correlation-ID", "security-review");
        var response = call(HttpMethod.GET, "/api/v1/requests", headers, null);

        assertProblem(response, HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(mapper.readTree(response.getBody()).path("traceId").asText()).isEqualTo("security-review");
        assertThat(repository.count()).isZero();
    }

    @Test
    void shouldAllowReadScopeButRejectWrites() throws Exception {
        var headers = bearer(TestTokens.token("requests:read"));
        assertThat(call(HttpMethod.GET, "/api/v1/requests", headers, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertProblem(call(HttpMethod.POST, "/api/v1/requests", headers, payload()), HttpStatus.FORBIDDEN);
        assertThat(repository.count()).isZero();
    }

    @Test
    void shouldAllowWriteScopeButRejectBothReadEndpoints() throws Exception {
        var headers = bearer(TestTokens.token("requests:write"));
        var created = call(HttpMethod.POST, "/api/v1/requests", headers, payload());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = mapper.readTree(created.getBody()).path("id").asText();
        assertProblem(call(HttpMethod.GET, "/api/v1/requests", headers, null), HttpStatus.FORBIDDEN);
        assertProblem(call(HttpMethod.GET, "/api/v1/requests/" + id, headers, null), HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldAllowBothScopesWithoutPersistingOrExposingAccessToken(CapturedOutput output) throws Exception {
        String token = TestTokens.token("requests:read requests:write");
        var headers = bearer(token);
        var created = call(HttpMethod.POST, "/api/v1/requests", headers, payload());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = mapper.readTree(created.getBody()).path("id").asText();
        var queried = call(HttpMethod.GET, "/api/v1/requests/" + id, headers, null);
        assertThat(queried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/requests", headers, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (var response : List.of(created, queried)) {
            assertNoToken(response, token, output);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        }
        String row = jdbc.queryForObject("SELECT row_to_json(r)::text FROM activation_requests r WHERE id = ?",
                String.class, UUID.fromString(id));
        assertThat(row.contains(token)).isFalse();
    }

    @ParameterizedTest(name = "Reject {0}")
    @MethodSource("invalidTokens")
    void shouldRejectInvalidSignedTokensWithoutLeakingThem(String scenario, Supplier<String> supplier,
                                                          CapturedOutput output) throws Exception {
        String token = supplier.get();
        var response = call(HttpMethod.GET, "/api/v1/requests", bearer(token), null);
        assertProblem(response, HttpStatus.UNAUTHORIZED);
        assertThat(mapper.readTree(response.getBody()).path("detail").asText())
                .isEqualTo("Authentication is required or the access token is invalid.");
        assertNoToken(response, token, output);
    }

    static Stream<Arguments> invalidTokens() {
        return Stream.of(
                invalid("expired", () -> TestTokens.token("requests:read", b -> b
                        .issueTime(Date.from(Instant.now().minusSeconds(600)))
                        .expirationTime(Date.from(Instant.now().minusSeconds(120))))),
                invalid("wrong issuer", () -> TestTokens.token("requests:read", b -> b.issuer("https://other.example.test"))),
                invalid("wrong audience", () -> TestTokens.token("requests:read", b -> b.audience("another-api"))),
                invalid("future not-before", () -> TestTokens.token("requests:read", b -> b
                        .notBeforeTime(Date.from(Instant.now().plusSeconds(120))))),
                invalid("missing expiration", () -> TestTokens.token("requests:read", b -> b.expirationTime(null))),
                invalid("missing issued-at", () -> TestTokens.token("requests:read", b -> b.issueTime(null))),
                invalid("missing subject", () -> TestTokens.token("requests:read", b -> b.subject(null))),
                invalid("future issued-at", () -> TestTokens.token("requests:read", b -> b
                        .issueTime(Date.from(Instant.now().plusSeconds(120))))),
                invalid("invalid signature", () -> TestTokens.token("requests:read", b -> { }, impostor(), JWSAlgorithm.RS256)),
                invalid("RS512 not allowed", () -> TestTokens.token("requests:read", b -> { }, TestTokens.KEY, JWSAlgorithm.RS512)),
                invalid("unsigned", () -> new PlainJWT(new JWTClaimsSet.Builder().subject("test-system").build()).serialize()),
                invalid("HMAC algorithm confusion", SecurityIntegrationTest::hmacToken),
                invalid("malformed scope", () -> TestTokens.token(null, b -> b.claim("scope", Map.of("role", "admin")))),
                invalid("malformed scope array", () -> TestTokens.token(null, b -> b.claim("scp", List.of(123)))),
                invalid("malformed token", () -> "invalid.signed.token"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unrelated:scope"})
    void shouldRejectValidTokenWithInsufficientScope(String scope) throws Exception {
        assertProblem(call(HttpMethod.GET, "/api/v1/requests", bearer(TestTokens.token(scope)), null), HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldNotGrantAccessFromRolesClaim() throws Exception {
        var token = TestTokens.token(null, b -> b.claim("roles", List.of("admin", "requests:read")));
        assertProblem(call(HttpMethod.GET, "/api/v1/requests", bearer(token), null), HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldAcceptScpArrayFromTrustedIssuer() {
        var token = TestTokens.token(null, b -> b.claim("scp", List.of("requests:read")));
        assertThat(call(HttpMethod.GET, "/api/v1/requests", bearer(token), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldNotAcceptTokenInQueryOrCookieOrForm(CapturedOutput output) throws Exception {
        String token = TestTokens.token("requests:read requests:write");
        String query = "/api/v1/requests?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        var queryResponse = call(HttpMethod.GET, query, new HttpHeaders(), null);
        assertProblem(queryResponse, HttpStatus.UNAUTHORIZED);
        assertNoToken(queryResponse, token, output);
        var cookie = new HttpHeaders();
        cookie.set(HttpHeaders.COOKIE, "access_token=" + token);
        assertProblem(call(HttpMethod.GET, "/api/v1/requests", cookie, null), HttpStatus.UNAUTHORIZED);
        var form = new HttpHeaders();
        form.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        assertProblem(call(HttpMethod.POST, "/api/v1/requests", form, "access_token=" + token), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shouldKeepOnlyHealthProbesPublicAndDenyOtherResources() throws Exception {
        for (String probe : List.of("liveness", "readiness")) {
            var response = call(HttpMethod.GET, "/actuator/health/" + probe, new HttpHeaders(), null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode health = mapper.readTree(response.getBody());
            assertThat(health.size()).isEqualTo(1);
            assertThat(health.path("status").asText()).isEqualTo("UP");
        }
        assertProblem(call(HttpMethod.GET, "/actuator/env", new HttpHeaders(), null), HttpStatus.UNAUTHORIZED);
        assertProblem(call(HttpMethod.GET, "/actuator/env", bearer(TestTokens.token("requests:read")), null), HttpStatus.FORBIDDEN);
        assertProblem(call(HttpMethod.DELETE, "/api/v1/requests", bearer(TestTokens.token("requests:read requests:write")), null),
                HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldRefreshPublicKeysWhenIssuerRotatesSigningKey() {
        var replacement = TestTokens.newKey();
        testJwkServer.stubFor(get(urlEqualTo("/jwks")).willReturn(okJson(TestTokens.jwks(TestTokens.KEY, replacement))));
        try {
            String token = TestTokens.token("requests:read", b -> { }, replacement, JWSAlgorithm.RS256);
            assertThat(call(HttpMethod.GET, "/api/v1/requests", bearer(token), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            restoreJwks();
        }
    }

    @Test
    void shouldRejectUnknownKeyWhenJwkEndpointIsUnavailable() throws Exception {
        testJwkServer.stubFor(get(urlEqualTo("/jwks")).willReturn(aResponse().withStatus(503)));
        try {
            String token = TestTokens.token("requests:read", b -> { }, TestTokens.newKey(), JWSAlgorithm.RS256);
            assertProblem(call(HttpMethod.GET, "/api/v1/requests", bearer(token), null), HttpStatus.UNAUTHORIZED);
        } finally {
            restoreJwks();
        }
    }

    @Test
    void shouldIsolateGetAndFilteredPaginationBetweenConsumers() throws Exception {
        var consumerA = consumer("consumer-a");
        var consumerB = consumer("consumer-b");
        var createdA = call(HttpMethod.POST, "/api/v1/requests", consumerA, payload());
        var createdB = call(HttpMethod.POST, "/api/v1/requests", consumerB,
                Map.of("externalId", "EXT-B", "customerId", "CUSTOMER", "productCode", "PRODUCT"));
        assertThat(createdA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createdB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String idA = mapper.readTree(createdA.getBody()).path("id").asText();
        String idB = mapper.readTree(createdB.getBody()).path("id").asText();

        assertProblem(call(HttpMethod.GET, "/api/v1/requests/" + idA, consumerB, null), HttpStatus.NOT_FOUND);
        assertProblem(call(HttpMethod.GET, "/api/v1/requests/" + idB, consumerA, null), HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/requests/" + idA, consumerA, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        String query = "/api/v1/requests?customerId=CUSTOMER&status=PENDING&page=0&size=1";
        JsonNode pageA = mapper.readTree(call(HttpMethod.GET, query, consumerA, null).getBody());
        JsonNode pageB = mapper.readTree(call(HttpMethod.GET, query, consumerB, null).getBody());
        assertThat(pageA.path("totalElements").asLong()).isEqualTo(1);
        assertThat(pageB.path("totalElements").asLong()).isEqualTo(1);
        assertThat(pageA.path("content").get(0).path("id").asText()).isEqualTo(idA);
        assertThat(pageB.path("content").get(0).path("id").asText()).isEqualTo(idB);
        assertThat(pageA.path("content").get(0).has("ownerId")).isFalse();
    }

    @Test
    void shouldNotExposeAnotherConsumerRequestThroughIdenticalRetry() throws Exception {
        var original = call(HttpMethod.POST, "/api/v1/requests", consumer("consumer-a"), payload());
        String id = mapper.readTree(original.getBody()).path("id").asText();
        var other = call(HttpMethod.POST, "/api/v1/requests", consumer("consumer-b"), payload());

        assertProblem(other, HttpStatus.CONFLICT);
        assertThat(other.getBody().contains(id)).isFalse();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById(UUID.fromString(id)).orElseThrow().getOwnerId())
                .isEqualTo(CurrentConsumer.identity(TestTokens.ISSUER, "consumer-a"));
    }

    @Test
    void shouldPreserveOwnerAcrossTokenRenewalAndIgnoreSpoofedClientIdentity() throws Exception {
        var first = call(HttpMethod.POST, "/api/v1/requests", consumer("consumer-a"), payload());
        String id = mapper.readTree(first.getBody()).path("id").asText();
        var renewed = call(HttpMethod.POST, "/api/v1/requests", consumer("consumer-a"), payload());
        assertThat(renewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(renewed.getBody()).path("id").asText()).isEqualTo(id);

        var spoofed = bearer(TestTokens.token("requests:read", b -> b.subject("consumer-b")
                .claim("client_id", "consumer-a").claim("ownerId", CurrentConsumer.identity(TestTokens.ISSUER, "consumer-a"))));
        spoofed.set("X-Consumer-ID", "consumer-a");
        assertProblem(call(HttpMethod.GET, "/api/v1/requests/" + id, spoofed, null), HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldAssignOwnerFromValidatedTokenInsteadOfRequestBody() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>(payload());
        input.put("ownerId", CurrentConsumer.identity(TestTokens.ISSUER, "consumer-a"));
        var created = call(HttpMethod.POST, "/api/v1/requests", consumer("consumer-b"), input);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = mapper.readTree(created.getBody()).path("id").asText();
        assertThat(repository.findById(UUID.fromString(id)).orElseThrow().getOwnerId())
                .isEqualTo(CurrentConsumer.identity(TestTokens.ISSUER, "consumer-b"));
        assertProblem(call(HttpMethod.GET, "/api/v1/requests/" + id, consumer("consumer-a"), null), HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldKeepLegacyRequestsWithoutTrustedOwnerInaccessible() throws Exception {
        var legacy = repository.saveAndFlush(br.com.deroyque.activation.request.domain.ActivationRequest.create(
                "EXT-LEGACY", "CUSTOMER", "PRODUCT", Instant.now(), "0".repeat(64)));

        assertProblem(call(HttpMethod.GET, "/api/v1/requests/" + legacy.getId(), consumer("consumer-a"), null), HttpStatus.NOT_FOUND);
        var page = mapper.readTree(call(HttpMethod.GET, "/api/v1/requests", consumer("consumer-a"), null).getBody());
        assertThat(page.path("totalElements").asLong()).isZero();
        var retry = call(HttpMethod.POST, "/api/v1/requests", consumer("consumer-a"),
                Map.of("externalId", "EXT-LEGACY", "customerId", "CUSTOMER", "productCode", "PRODUCT"));
        assertProblem(retry, HttpStatus.CONFLICT);
        assertThat(repository.findById(legacy.getId()).orElseThrow().getOwnerId()).isEqualTo("0".repeat(64));
    }

    @Test
    void shouldNotLeakWinningRequestWhenConsumersRaceOnGlobalExternalId() throws Exception {
        var barrier = new CyclicBarrier(2);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        List<ResponseEntity<String>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (String name : List.of("consumer-a", "consumer-b")) {
                futures.add(executor.submit(() -> {
                    var headers = consumer(name);
                    barrier.await(10, TimeUnit.SECONDS);
                    return call(HttpMethod.POST, "/api/v1/requests", headers, payload());
                }));
            }
            for (var result : futures) results.add(result.get(20, TimeUnit.SECONDS));
        }
        assertThat(results.stream().map(ResponseEntity::getStatusCode).toList())
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(repository.count()).isEqualTo(1);
        String id = repository.findAll().getFirst().getId().toString();
        var conflict = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).findFirst().orElseThrow();
        assertThat(conflict.getBody().contains(id)).isFalse();
    }

    private HttpHeaders consumer(String subject) {
        return bearer(TestTokens.token("requests:read requests:write", builder -> builder.subject(subject)));
    }

    private void restoreJwks() {
        testJwkServer.stubFor(get(urlEqualTo("/jwks")).willReturn(okJson(TestTokens.jwks(TestTokens.KEY))));
    }

    private ResponseEntity<String> call(HttpMethod method, String path, HttpHeaders headers, Object body) {
        if (body instanceof Map<?, ?>) headers.setContentType(MediaType.APPLICATION_JSON);
        return rawClient.exchange(URI.create("http://127.0.0.1:" + port + path), method,
                new HttpEntity<>(body, headers), String.class);
    }

    private HttpHeaders bearer(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private Map<String, String> payload() {
        return Map.of("externalId", "EXT-SECURITY", "customerId", "CUSTOMER", "productCode", "PRODUCT");
    }

    private void assertProblem(ResponseEntity<String> response, HttpStatus status) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE)).isFalse();
        var problem = mapper.readTree(response.getBody());
        assertThat(problem.path("status").asInt()).isEqualTo(status.value());
        assertThat(problem.path("traceId").asText()).isNotBlank()
                .isEqualTo(response.getHeaders().getFirst("X-Correlation-ID"));
        assertThat(problem.has("stackTrace")).isFalse();
        assertThat(problem.has("exception")).isFalse();
    }

    private void assertNoToken(ResponseEntity<String> response, String token, CapturedOutput output) {
        // Boolean assertions avoid printing the token itself if a check fails.
        assertThat(response.getBody().contains(token)).isFalse();
        assertThat(response.getHeaders().toString().contains(token)).isFalse();
        assertThat(output.getAll().contains(token)).isFalse();
    }

    private static Arguments invalid(String name, Supplier<String> token) {
        return Arguments.of(name, token);
    }

    private static RSAKey impostor() {
        var other = TestTokens.newKey();
        try {
            return new RSAKey.Builder(other.toRSAPublicKey()).privateKey(other.toRSAPrivateKey())
                    .keyID(TestTokens.KEY.getKeyID()).build();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Cannot construct ephemeral test key");
        }
    }

    private static String hmacToken() {
        try {
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                    .issuer(TestTokens.ISSUER).audience(TestTokens.AUDIENCE).subject("test-system")
                    .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("scope", "requests:read").build());
            jwt.sign(new MACSigner(TestTokens.KEY.toRSAPublicKey().getEncoded()));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Cannot construct algorithm-confusion test token");
        }
    }
}
