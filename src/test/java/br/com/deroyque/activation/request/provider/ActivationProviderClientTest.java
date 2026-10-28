package br.com.deroyque.activation.request.provider;

import br.com.deroyque.activation.request.processing.WorkItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class ActivationProviderClientTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final WireMockServer SERVER = new WireMockServer(0);
    static { SERVER.start(); }
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ActivationProviderClient client = new ActivationProviderClient(HTTP, new ObjectMapper(),
            new ProviderProperties(URI.create(SERVER.baseUrl()), Duration.ofMillis(200), Duration.ofSeconds(2)),
            Clock.fixed(NOW, ZoneOffset.UTC), credentials(URI.create(SERVER.baseUrl())));
    private final WorkItem work = new WorkItem(UUID.randomUUID(), UUID.randomUUID(), "EXT-123", "CUSTOMER",
            "PRODUCT", "provider-review", 1);

    @BeforeEach void reset() { SERVER.resetAll(); }
    @AfterAll static void stop() { SERVER.stop(); HTTP.close(); }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 204, 299})
    void shouldAcceptAnySuccessfulStatus(int status) {
        stub(status);
        assertThat(client.activate(work).kind()).isEqualTo(ProviderOutcome.Kind.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422, 501})
    void shouldNotRetryPermanentErrors(int status) {
        stub(status);
        var outcome = client.activate(work);
        assertThat(outcome.kind()).isEqualTo(ProviderOutcome.Kind.PERMANENT_FAILURE);
        assertThat(outcome.errorCode()).isEqualTo("HTTP_" + status);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503, 504})
    void shouldRetryOnlyApprovedTemporaryStatuses(int status) {
        stub(status);
        assertThat(client.activate(work).kind()).isEqualTo(ProviderOutcome.Kind.RETRYABLE_FAILURE);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void shouldReadRetryAfterAsSecondsOrHttpDate(int status) {
        SERVER.stubFor(post(urlEqualTo("/provider/v1/activate"))
                .willReturn(aResponse().withStatus(status).withHeader("Retry-After", "10")));
        assertThat(client.activate(work).retryAfter()).isEqualTo(NOW.plusSeconds(10));
        SERVER.resetAll();
        SERVER.stubFor(post(urlEqualTo("/provider/v1/activate"))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Retry-After", "Wed, 28 Oct 2026 12:00:30 GMT")));
        assertThat(client.activate(work).retryAfter()).isEqualTo(NOW.plusSeconds(30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "-1", "9999999999999999999999999999", "9223372036854775807"})
    void shouldIgnoreMalformedOrUnrepresentableRetryAfter(String value) {
        SERVER.stubFor(post(urlEqualTo("/provider/v1/activate"))
                .willReturn(aResponse().withStatus(503).withHeader("Retry-After", value)));
        assertThat(client.activate(work).retryAfter()).isNull();
    }

    @Test
    void shouldNotFollowRedirects() {
        SERVER.stubFor(post(urlEqualTo("/provider/v1/activate"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", SERVER.baseUrl() + "/another")));
        assertThat(client.activate(work).kind()).isEqualTo(ProviderOutcome.Kind.PERMANENT_FAILURE);
        SERVER.verify(0, anyRequestedFor(urlEqualTo("/another")));
    }

    @Test
    void shouldSendPayloadStableKeyAndCorrelationHeader() {
        stub(204);
        client.activate(work);
        SERVER.verify(postRequestedFor(urlEqualTo("/provider/v1/activate"))
                .withHeader("Idempotency-Key", equalTo(IdempotencyKey.from("EXT-123")))
                .withHeader("X-Correlation-ID", equalTo("provider-review"))
                .withRequestBody(equalToJson("""
                        {"externalId":"EXT-123","customerId":"CUSTOMER","productCode":"PRODUCT"}
                        """)));
        assertThat(IdempotencyKey.from("EXT-123")).hasSize(64).isNotEqualTo(IdempotencyKey.from("EXT-124"));
    }

    @Test
    void shouldClassifyConnectionFailureAsRetryable() {
        var unavailable = new WireMockServer(0);
        unavailable.start();
        URI address = URI.create(unavailable.baseUrl());
        unavailable.stop();
        var disconnected = new ActivationProviderClient(HTTP, new ObjectMapper(),
                new ProviderProperties(address, Duration.ofMillis(200), Duration.ofSeconds(1)),
                Clock.fixed(NOW, ZoneOffset.UTC), credentials(address));
        assertThat(disconnected.activate(work).errorCode()).isEqualTo("IO_FAILURE");
    }

    private void stub(int status) {
        SERVER.stubFor(post(urlEqualTo("/provider/v1/activate")).willReturn(aResponse().withStatus(status)));
    }

    private ProviderCredentials credentials(URI address) {
        return new ProviderCredentials(new ProviderCredentialProperties(null),
                new ProviderProperties(address, Duration.ofMillis(200), Duration.ofSeconds(2)));
    }
}
