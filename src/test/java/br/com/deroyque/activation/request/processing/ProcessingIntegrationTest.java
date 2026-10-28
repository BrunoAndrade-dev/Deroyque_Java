package br.com.deroyque.activation.request.processing;

import br.com.deroyque.activation.request.domain.ActivationRequest;
import br.com.deroyque.activation.request.domain.RequestStatus;
import br.com.deroyque.activation.request.persistence.ActivationRequestRepository;
import br.com.deroyque.activation.request.provider.ActivationProviderClient;
import br.com.deroyque.activation.request.provider.IdempotencyKey;
import br.com.deroyque.activation.request.provider.ProviderOutcome;
import br.com.deroyque.activation.support.IdempotentProviderMock;
import br.com.deroyque.activation.support.MutableClock;
import br.com.deroyque.activation.support.PostgresTestConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "activation.provider.connect-timeout=100ms", "activation.provider.read-timeout=250ms"})
@Import({PostgresTestConfiguration.class, ProcessingIntegrationTest.ClockConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProcessingIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final IdempotentProviderMock MOCK = new IdempotentProviderMock(1000);
    private static final WireMockServer PROVIDER = startProvider();

    private static WireMockServer startProvider() {
        var server = new WireMockServer(wireMockConfig().dynamicPort().extensions(MOCK));
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("activation.provider.base-url", PROVIDER::baseUrl);
    }

    @Autowired private ActivationRequestRepository repository;
    @Autowired private ActivationProcessor processor;
    @Autowired private ProcessingTransactions transactions;
    @Autowired private MutableClock clock;
    @Autowired private TestRestTemplate client;
    @MockitoSpyBean private ActivationProviderClient providerClient;

    @BeforeEach
    void prepare() {
        repository.deleteAll();
        clock.set(NOW);
        PROVIDER.resetAll();
        MOCK.reset();
        PROVIDER.stubFor(post(urlEqualTo("/provider/v1/activate"))
                .willReturn(aResponse().withStatus(204).withTransformers(MOCK.getName())));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(providerClient).activate(any());
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @Test
    void shouldCompleteAndPropagateOriginalCorrelationIdWithoutOpenDatabaseTransaction() {
        var headers = new HttpHeaders();
        headers.set("X-Correlation-ID", "activation-review-4");
        var input = Map.of("externalId", "EXT-HTTP", "customerId", "CUSTOMER", "productCode", "INTERNET-500");
        var response = client.postForEntity("/api/v1/requests", new HttpEntity<>(input, headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        UUID id = UUID.fromString(response.getBody().path("id").asText());

        assertThat(processor.processNext()).isTrue();
        assertThat(stored(id).getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(stored(id).getAttemptCount()).isEqualTo(1);
        PROVIDER.verify(1, postRequestedFor(urlEqualTo("/provider/v1/activate"))
                .withHeader("X-Correlation-ID", equalTo("activation-review-4"))
                .withHeader("Idempotency-Key", equalTo(IdempotencyKey.from("EXT-HTTP"))));
        assertThat(MOCK.getActivationCount()).isEqualTo(1);
    }

    @Test
    void shouldFailPermanentErrorWithoutRetry() {
        var request = create("PERMANENT_FAILURE");

        processor.processNext();

        assertThat(stored(request.getId()).getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(stored(request.getId()).getLastError()).isEqualTo("HTTP_400");
        assertThat(processor.processNext()).isFalse();
        assertThat(PROVIDER.getAllServeEvents()).hasSize(1);
    }

    @Test
    void shouldUseOneAndTwoSecondBackoffsAndCompleteOnThirdAttempt() {
        var request = create("TEMPORARY_FAILURE");
        processor.processNext();
        assertThat(stored(request.getId()).getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(processor.processNext()).isFalse();

        clock.set(NOW.plusSeconds(1));
        processor.processNext();
        assertThat(stored(request.getId()).getNextAttemptAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(processor.processNext()).isFalse();

        clock.set(NOW.plusSeconds(3));
        processor.processNext();
        assertThat(stored(request.getId()).getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(stored(request.getId()).getAttemptCount()).isEqualTo(3);
        assertThat(stored(request.getId()).getLastError()).isNull();
        assertThat(MOCK.getActivationCount()).isEqualTo(1);
        PROVIDER.verify(3, postRequestedFor(urlEqualTo("/provider/v1/activate"))
                .withHeader("Idempotency-Key", equalTo(IdempotencyKey.from(request.getExternalId()))));
    }

    @Test
    void shouldExhaustTemporaryFailuresWithoutStoringProviderBody() {
        PROVIDER.stubFor(post(urlEqualTo("/provider/v1/activate")).atPriority(1)
                .willReturn(aResponse().withStatus(500).withBody("internal provider details")));
        var request = create("ALWAYS_FAILS");
        for (int i = 0; i < 3; i++) {
            assertThat(processor.processNext()).isTrue();
            var next = stored(request.getId()).getNextAttemptAt();
            if (next != null) clock.set(next);
        }
        assertThat(stored(request.getId()).getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(stored(request.getId()).getAttemptCount()).isEqualTo(3);
        assertThat(stored(request.getId()).getLastError()).isEqualTo("HTTP_500");
        assertThat(processor.processNext()).isFalse();
        assertThat(PROVIDER.getAllServeEvents()).hasSize(3);
    }

    @Test
    void shouldRetryAmbiguousTimeoutWithoutActivatingTwice() {
        var request = create("TIMEOUT_AFTER_ACTIVATION");
        processor.processNext();

        assertThat(stored(request.getId()).getLastError()).isEqualTo("TIMEOUT");
        assertThat(stored(request.getId()).getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(MOCK.getActivationCount()).isEqualTo(1);

        clock.set(stored(request.getId()).getNextAttemptAt());
        processor.processNext();

        assertThat(stored(request.getId()).getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(stored(request.getId()).getAttemptCount()).isEqualTo(2);
        assertThat(MOCK.getActivationCount()).isEqualTo(1);
        PROVIDER.verify(2, postRequestedFor(urlEqualTo("/provider/v1/activate"))
                .withHeader("Idempotency-Key", equalTo(IdempotencyKey.from(request.getExternalId()))));
    }

    @Test
    void shouldRespectRetryAfterBeforeRetryingRateLimitedRequest() {
        var request = create("RATE_LIMITED");
        processor.processNext();
        assertThat(stored(request.getId()).getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        clock.set(NOW.plusSeconds(1));
        assertThat(processor.processNext()).isFalse();
        clock.set(NOW.plusSeconds(2));
        processor.processNext();
        assertThat(stored(request.getId()).getStatus()).isEqualTo(RequestStatus.COMPLETED);
    }

    @Test
    void shouldRecoverAbandonedRequestAndIgnoreLateResultOfOldAttempt() {
        var request = create("INTERNET-500");
        var oldAttempt = transactions.claimNext().orElseThrow();
        clock.set(NOW.plusSeconds(59));
        assertThat(transactions.recoverAbandoned()).isZero();
        clock.set(NOW.plusSeconds(60));
        assertThat(transactions.recoverAbandoned()).isEqualTo(1);
        assertThat(stored(request.getId()).getLastError()).isEqualTo("PROCESSING_EXPIRED");

        clock.set(stored(request.getId()).getNextAttemptAt());
        var currentAttempt = transactions.claimNext().orElseThrow();
        assertThat(currentAttempt.token()).isNotEqualTo(oldAttempt.token());
        assertThat(transactions.finish(oldAttempt, ProviderOutcome.success())).isFalse();
        assertThat(stored(request.getId()).getStatus()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(transactions.finish(currentAttempt, ProviderOutcome.success())).isTrue();
        assertThat(stored(request.getId()).getStatus()).isEqualTo(RequestStatus.COMPLETED);
    }

    @Test
    void shouldStopRecoveringAfterThirdAbandonedAttempt() {
        var request = create("INTERNET-500");
        for (int i = 0; i < 3; i++) {
            assertThat(transactions.claimNext()).isPresent();
            clock.set(clock.instant().plusSeconds(60));
            assertThat(transactions.recoverAbandoned()).isEqualTo(1);
            var next = stored(request.getId()).getNextAttemptAt();
            if (next != null) clock.set(next);
        }
        assertThat(stored(request.getId()).getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(stored(request.getId()).getAttemptCount()).isEqualTo(3);
        assertThat(transactions.claimNext()).isEmpty();
        assertThat(PROVIDER.getAllServeEvents()).isEmpty();
    }

    @Test
    void shouldAllowOnlyOneOfFourWorkersToClaimSameRequest() throws Exception {
        create("INTERNET-500");
        var barrier = new CyclicBarrier(4);
        List<Future<Optional<WorkItem>>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                results.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return transactions.claimNext();
                }));
            }
            int claimed = 0;
            for (var result : results) if (result.get(20, TimeUnit.SECONDS).isPresent()) claimed++;
            assertThat(claimed).isEqualTo(1);
        }
        assertThat(repository.findAll().getFirst().getAttemptCount()).isEqualTo(1);
    }

    private ActivationRequest create(String product) {
        return repository.saveAndFlush(ActivationRequest.create("EXT-" + UUID.randomUUID(), "CUSTOMER", product, NOW));
    }

    private ActivationRequest stored(UUID id) {
        return repository.findById(id).orElseThrow();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
}
