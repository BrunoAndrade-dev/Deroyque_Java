package br.com.deroyque.activation.request.api;

import br.com.deroyque.activation.request.domain.ActivationRequest;
import br.com.deroyque.activation.request.persistence.ActivationRequestRepository;
import br.com.deroyque.activation.support.PostgresTestConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class ActivationRequestApiTest {

    private static final String URL = "/api/v1/requests";

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private ActivationRequestRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void shouldCreatePersistedPendingRequestWithLocationAndUtcDates() {
        var response = post(" EXT-1 ", " CUSTOMER-1 ", " Internet-500 ");
        var body = response.getBody();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString(body.path("id").asText());
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo(URL + "/" + id);
        assertThat(body.path("externalId").asText()).isEqualTo("EXT-1");
        assertThat(body.path("customerId").asText()).isEqualTo("CUSTOMER-1");
        assertThat(body.path("productCode").asText()).isEqualTo("Internet-500");
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("createdAt").asText()).endsWith("Z");
        assertThat(body.path("updatedAt")).isEqualTo(body.path("createdAt"));
        assertThat(body.has("version")).isFalse();
        assertThat(repository.findById(id)).isPresent();
        assertThat(repository.findById(id).orElseThrow().getCreatedAt())
                .isEqualTo(Instant.parse(body.path("createdAt").asText()));
    }

    @Test
    void shouldAcceptOneHundredCharactersAfterTrimming() {
        var response = post(" " + "e".repeat(100) + " ", "c".repeat(100), "p".repeat(100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().path("externalId").asText()).hasSize(100);
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void shouldRejectInvalidFieldsWithoutPersisting(String externalId, String customerId, String productCode) {
        var response = post(externalId, customerId, productCode);

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("errors").isArray()).isTrue();
        assertThat(response.getBody().path("errors").size()).isPositive();
        assertThat(repository.count()).isZero();
    }

    static Stream<Arguments> invalidInputs() {
        return Stream.of(null, "", "   ", "\u2003", "x".repeat(101)).flatMap(invalid -> Stream.of(
                Arguments.of(invalid, "CUSTOMER", "PRODUCT"),
                Arguments.of("EXTERNAL", invalid, "PRODUCT"),
                Arguments.of("EXTERNAL", "CUSTOMER", invalid)));
    }

    @Test
    void shouldRejectMissingFields() {
        var response = client.postForEntity(URL, Map.of("externalId", "EXT"), JsonNode.class);

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat(repository.count()).isZero();
    }

    @Test
    void shouldReturnSameRepresentationForNormalizedIdenticalRetry() {
        var first = post("EXT-RETRY", "CUSTOMER", "PRODUCT");
        var retry = post(" EXT-RETRY ", " CUSTOMER ", " PRODUCT ");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(repository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"customerId", "productCode"})
    void shouldRejectSameExternalIdWithDifferentContent(String changedField) {
        post("EXT-CONFLICT", "CUSTOMER", "PRODUCT");
        var response = post("EXT-CONFLICT", changedField.equals("customerId") ? "OTHER" : "CUSTOMER",
                changedField.equals("productCode") ? "OTHER" : "PRODUCT");

        assertProblem(response, HttpStatus.CONFLICT);
        assertThat(repository.count()).isEqualTo(1);
        var original = repository.findByExternalId("EXT-CONFLICT").orElseThrow();
        assertThat(original.getCustomerId()).isEqualTo("CUSTOMER");
        assertThat(original.getProductCode()).isEqualTo("PRODUCT");
    }

    @Test
    void shouldReturnCurrentStateOnGetAndRetry() {
        var created = post("EXT-STATE", "CUSTOMER", "PRODUCT");
        UUID id = UUID.fromString(created.getBody().path("id").asText());
        var stored = repository.findById(id).orElseThrow();
        stored.startProcessing(stored.getUpdatedAt().plusSeconds(1));
        repository.saveAndFlush(stored);

        var consulted = client.getForEntity(URL + "/" + id, JsonNode.class);
        var retry = post("EXT-STATE", "CUSTOMER", "PRODUCT");

        assertThat(consulted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(consulted.getBody().path("status").asText()).isEqualTo("PROCESSING");
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody()).isEqualTo(consulted.getBody());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnNotFoundForUnknownRequest() {
        assertProblem(client.getForEntity(URL + "/" + UUID.randomUUID(), JsonNode.class), HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldReturnBadRequestForInvalidUuid() {
        assertProblem(client.getForEntity(URL + "/invalid-uuid", JsonNode.class), HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldListWithDefaultPaginationAndAcceptMaximumSize() {
        var response = client.getForEntity(URL, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("content").size()).isZero();
        assertThat(response.getBody().path("page").asInt()).isZero();
        assertThat(response.getBody().path("size").asInt()).isEqualTo(20);
        assertThat(response.getBody().path("totalElements").asLong()).isZero();
        assertThat(client.getForEntity(URL + "?size=100", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldApplyCombinedFiltersAndPaginateNewestRequestsFirst() {
        var oldest = persist("EXT-OLD", "CUSTOMER-A", 0);
        var completed = persist("EXT-COMPLETED", "CUSTOMER-A", 1);
        completed.startProcessing(completed.getCreatedAt());
        completed.complete(completed.getCreatedAt());
        repository.saveAndFlush(completed);
        persist("EXT-OTHER", "CUSTOMER-B", 2);
        var newest = persist("EXT-NEW", "CUSTOMER-A", 3);
        String query = URL + "?status=PENDING&customerId=CUSTOMER-A&size=1&page=";

        var first = client.getForEntity(query + "0", JsonNode.class).getBody();
        var second = client.getForEntity(query + "1", JsonNode.class).getBody();
        var beyond = client.getForEntity(query + "2", JsonNode.class).getBody();

        assertThat(first.path("totalElements").asLong()).isEqualTo(2);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.path("content").get(0).path("id").asText()).isEqualTo(newest.getId().toString());
        assertThat(second.path("content").get(0).path("id").asText()).isEqualTo(oldest.getId().toString());
        assertThat(beyond.path("content").size()).isZero();
        assertThat(beyond.path("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void shouldSupportEachFilterIndependently() {
        persist("EXT-A", "CUSTOMER-A", 0);
        var completed = persist("EXT-B", "CUSTOMER-B", 1);
        completed.startProcessing(completed.getCreatedAt());
        completed.complete(completed.getCreatedAt());
        repository.saveAndFlush(completed);

        var byCustomer = client.getForEntity(URL + "?customerId=CUSTOMER-A", JsonNode.class).getBody();
        var byStatus = client.getForEntity(URL + "?status=COMPLETED", JsonNode.class).getBody();

        assertThat(byCustomer.path("totalElements").asLong()).isEqualTo(1);
        assertThat(byCustomer.path("content").get(0).path("externalId").asText()).isEqualTo("EXT-A");
        assertThat(byStatus.path("totalElements").asLong()).isEqualTo(1);
        assertThat(byStatus.path("content").get(0).path("externalId").asText()).isEqualTo("EXT-B");
    }

    @Test
    void shouldKeepPaginationStableWhenCreationDatesAreEqual() {
        var first = persist("EXT-TIE-A", "CUSTOMER", 0);
        var second = persist("EXT-TIE-B", "CUSTOMER", 0);

        var page0 = client.getForEntity(URL + "?page=0&size=1", JsonNode.class).getBody();
        var page1 = client.getForEntity(URL + "?page=1&size=1", JsonNode.class).getBody();
        var repeated = client.getForEntity(URL + "?page=0&size=1", JsonNode.class).getBody();

        assertThat(List.of(page0.path("content").get(0).path("id").asText(),
                page1.path("content").get(0).path("id").asText()))
                .containsExactlyInAnyOrder(first.getId().toString(), second.getId().toString());
        assertThat(repeated).isEqualTo(page0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=abc", "size=0", "size=101", "status=UNKNOWN", "customerId=   "})
    void shouldRejectInvalidQueryParameters(String query) {
        assertProblem(client.getForEntity(URL + "?" + query, JsonNode.class), HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldRejectCustomerFilterLongerThanOneHundredCharacters() {
        assertProblem(client.getForEntity(URL + "?customerId=" + "x".repeat(101), JsonNode.class),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldReturnOneCreatedAndThreeSuccessfulRetriesForConcurrentIdenticalPosts() throws Exception {
        var results = concurrentPosts(List.of("CUSTOMER", "CUSTOMER", "CUSTOMER", "CUSTOMER"));

        assertThat(results.stream().map(ResponseEntity::getStatusCode).toList())
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.OK, HttpStatus.OK, HttpStatus.OK);
        assertThat(results.stream().map(result -> result.getBody().path("id").asText()).distinct().count())
                .isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnOneCreatedAndOneConflictForConcurrentDifferentContent() throws Exception {
        var results = concurrentPosts(List.of("CUSTOMER-A", "CUSTOMER-B"));

        assertThat(results.stream().map(ResponseEntity::getStatusCode).toList())
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(repository.count()).isEqualTo(1);
    }

    private List<ResponseEntity<JsonNode>> concurrentPosts(List<String> customers) throws Exception {
        var barrier = new CyclicBarrier(customers.size());
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        List<ResponseEntity<JsonNode>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(customers.size())) {
            for (String customer : customers) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return post("EXT-CONCURRENT-API", customer, "PRODUCT");
                }));
            }
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        }
        return results;
    }

    private ResponseEntity<JsonNode> post(String externalId, String customerId, String productCode) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("externalId", externalId);
        input.put("customerId", customerId);
        input.put("productCode", productCode);
        return client.postForEntity(URL, input, JsonNode.class);
    }

    private ActivationRequest persist(String externalId, String customerId, int seconds) {
        return repository.saveAndFlush(ActivationRequest.create(externalId, customerId, "PRODUCT",
                Instant.parse("2026-10-27T12:00:00Z").plusSeconds(seconds)));
    }

    private void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody().path("status").asInt()).isEqualTo(status.value());
        assertThat(response.getBody().path("traceId").asText())
                .isNotBlank().isEqualTo(response.getHeaders().getFirst("X-Correlation-ID"));
        assertThat(response.getBody().has("stackTrace")).isFalse();
        assertThat(response.getBody().has("exception")).isFalse();
    }
}
