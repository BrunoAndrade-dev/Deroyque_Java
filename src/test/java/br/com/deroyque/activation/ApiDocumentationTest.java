package br.com.deroyque.activation;

import br.com.deroyque.activation.support.PostgresTestConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@Import(PostgresTestConfiguration.class)
class ApiDocumentationTest {

    @Autowired private TestRestTemplate client;

    @Test
    void shouldDescribeBusinessEndpointsWithBearerSecurityAndResponseContracts() {
        var response = client.getForEntity("/v3/api-docs", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var document = response.getBody();
        assertThat(document.path("paths").size()).isEqualTo(2);
        var create = document.at("/paths/~1api~1v1~1requests/post");
        for (String status : new String[]{"200", "201", "400", "401", "403", "409", "500"}) {
            assertThat(create.path("responses").has(status)).as("documented response %s", status).isTrue();
        }
        assertThat(document.at("/paths/~1api~1v1~1requests~1{id}/get/responses").has("404")).isTrue();
        assertThat(document.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(document.at("/security/0").has("bearerAuth")).isTrue();
        var externalId = document.at("/components/schemas/CreateActivationRequest/properties/externalId");
        assertThat(externalId.path("maxLength").asInt()).isEqualTo(100);
        assertThat(externalId.path("example").asText()).isEqualTo("EXT-12345");
        assertThat(document.at("/servers/0/url").asText()).isEqualTo("/");
    }

    @Test
    void shouldServeSwaggerPageAndAssetsWithoutAuthentication() {
        var page = client.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("swagger-ui-bundle.js");
        assertThat(client.getForEntity("/swagger-ui/swagger-ui-bundle.js", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(client.getForEntity("/swagger-ui/swagger-initializer.js", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldDisablePersistentAuthorizationAndExternalValidation() {
        var response = client.getForEntity("/v3/api-docs/swagger-config", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("persistAuthorization").asText()).isEqualTo("false");
        assertThat(response.getBody().path("validatorUrl").asText()).isEmpty();
        assertThat(response.getBody().toString()).doesNotContain("clientSecret", "access_token");
    }
}
