package br.com.deroyque.activation.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ExtendWith(OutputCaptureExtension.class)
class SwaggerDemoTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldKeepTokenHelperLocalAndRejectForeignOrigins() throws Exception {
        try (var issuer = new DemoTokenIssuer(() -> 8090); var client = HttpClient.newHttpClient()) {
            var page = client.send(HttpRequest.newBuilder(URI.create(issuer.baseUrl() + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("http://127.0.0.1:8090/swagger-ui.html");
            assertThat(page.headers().firstValue("Cache-Control")).contains("no-store");
            var denied = client.send(HttpRequest.newBuilder(URI.create(issuer.baseUrl() + "/token"))
                            .header("Origin", "https://other.example.test")
                            .POST(HttpRequest.BodyPublishers.ofString("a-full")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(403);
            var getToken = client.send(HttpRequest.newBuilder(URI.create(issuer.baseUrl() + "/token")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(getToken.statusCode()).isEqualTo(405);
            var invalidConsumer = client.send(HttpRequest.newBuilder(URI.create(issuer.baseUrl() + "/token"))
                            .header("Origin", issuer.baseUrl())
                            .POST(HttpRequest.BodyPublishers.ofString("unknown-consumer")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(invalidConsumer.statusCode()).isEqualTo(400);
            var jwks = client.send(HttpRequest.newBuilder(URI.create(issuer.baseUrl() + "/jwks")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var key = mapper.readTree(jwks.body()).path("keys").get(0);
            assertThat(key.has("n")).isTrue();
            assertThat(key.has("d")).isFalse();
            assertThat(key.has("p")).isFalse();
        }
    }

    @Test
    void shouldRunRealAuthenticatedAsyncFlowWithScopesAndIsolation(CapturedOutput output) throws Exception {
        try (var demo = SwaggerDemoApplication.start(0); var client = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + demo.port();
            assertThat(call(client, base, "GET", "/swagger-ui/index.html", null, null).statusCode()).isEqualTo(200);
            assertThat(call(client, base, "GET", "/api/v1/requests", null, null).statusCode()).isEqualTo(401);
            String full = token(client, demo.issuerUrl(), "a-full");
            String read = token(client, demo.issuerUrl(), "a-read");
            String other = token(client, demo.issuerUrl(), "b-full");
            String payload = "{\"externalId\":\"DEMO-001\",\"customerId\":\"CUSTOMER\",\"productCode\":\"TEMPORARY_FAILURE\"}";
            assertThat(call(client, base, "POST", "/api/v1/requests", payload, read).statusCode()).isEqualTo(403);
            var created = call(client, base, "POST", "/api/v1/requests", payload, full);
            assertThat(created.statusCode()).isEqualTo(201);
            var body = mapper.readTree(created.body());
            assertThat(body.path("status").asText()).isEqualTo("PENDING");
            String path = "/api/v1/requests/" + body.path("id").asText();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var result = call(client, base, "GET", path, null, read);
                assertThat(result.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(result.body()).path("status").asText()).isEqualTo("COMPLETED");
            });
            assertThat(call(client, base, "POST", "/api/v1/requests", payload, full).statusCode()).isEqualTo(200);
            assertThat(call(client, base, "POST", "/api/v1/requests", payload.replace("CUSTOMER", "OTHER"), full)
                    .statusCode()).isEqualTo(409);
            assertThat(call(client, base, "GET", path, null, other).statusCode()).isEqualTo(404);
            var list = call(client, base, "GET", "/api/v1/requests", null, other);
            assertThat(mapper.readTree(list.body()).path("totalElements").asInt()).isZero();
            assertThat(output.getAll().contains(full) || output.getAll().contains(read) || output.getAll().contains(other))
                    .as("generated tokens must not appear in logs").isFalse();
        }
    }

    private String token(HttpClient client, String issuerUrl, String consumer) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(issuerUrl + "/token"))
                        .header("Origin", issuerUrl).POST(HttpRequest.BodyPublishers.ofString(consumer)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private HttpResponse<String> call(HttpClient client, String base, String method, String path,
                                      String payload, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (payload != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
