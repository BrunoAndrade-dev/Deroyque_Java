package br.com.deroyque.activation.request.processing;

import br.com.deroyque.activation.request.persistence.ActivationRequestRepository;
import br.com.deroyque.activation.support.PostgresTestConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "activation.processing.enabled=true", "activation.processing.poll-interval=50ms"})
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProcessingSchedulerTest {

    private static final CountDownLatch ENTERED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE = new CountDownLatch(1);
    private static final WireMockServer PROVIDER = startProvider();

    private static WireMockServer startProvider() {
        var transformer = new ResponseDefinitionTransformerV2() {
            @Override public String getName() { return "blocked-activation"; }
            @Override public ResponseDefinition transform(ServeEvent event) {
                ENTERED.countDown();
                try {
                    boolean released = RELEASE.await(4, TimeUnit.SECONDS);
                    return ResponseDefinitionBuilder.responseDefinition().withStatus(released ? 204 : 500).build();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return ResponseDefinitionBuilder.responseDefinition().withStatus(500).build();
                }
            }
        };
        var server = new WireMockServer(wireMockConfig().dynamicPort().extensions(transformer));
        server.start();
        server.stubFor(post(urlEqualTo("/provider/v1/activate"))
                .willReturn(aResponse().withStatus(204).withTransformers("blocked-activation")));
        return server;
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("activation.provider.base-url", PROVIDER::baseUrl);
    }

    @Autowired private TestRestTemplate client;
    @Autowired private ActivationRequestRepository repository;

    @AfterAll static void stopProvider() { RELEASE.countDown(); PROVIDER.stop(); }

    @Test
    void shouldRespondBeforeProviderCompletesAndProcessAutomaticallyInBackground() throws Exception {
        UUID id;
        try {
            var response = client.postForEntity("/api/v1/requests",
                    Map.of("externalId", "EXT-ASYNC", "customerId", "CUSTOMER", "productCode", "PRODUCT"),
                    JsonNode.class);
            assertThat(response.getStatusCode().value()).isEqualTo(201);
            assertThat(response.getBody().path("status").asText()).isEqualTo("PENDING");
            id = UUID.fromString(response.getBody().path("id").asText());
            assertThat(ENTERED.await(3, TimeUnit.SECONDS)).isTrue();
            var processing = client.getForEntity("/api/v1/requests/" + id, JsonNode.class);
            assertThat(processing.getBody().path("status").asText()).isEqualTo("PROCESSING");
            assertThat(repository.findById(id).orElseThrow().getProcessingToken()).isNotNull();
        } finally {
            RELEASE.countDown();
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var completed = client.getForEntity("/api/v1/requests/" + id, JsonNode.class);
            assertThat(completed.getBody().path("status").asText()).isEqualTo("COMPLETED");
        });
        PROVIDER.verify(1, postRequestedFor(urlEqualTo("/provider/v1/activate")));
    }
}
