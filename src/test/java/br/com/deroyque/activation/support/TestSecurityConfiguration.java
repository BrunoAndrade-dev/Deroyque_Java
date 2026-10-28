package br.com.deroyque.activation.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistrar;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    @Bean(destroyMethod = "stop")
    WireMockServer testJwkServer() {
        var server = new WireMockServer(0);
        server.start();
        server.stubFor(get(urlEqualTo("/jwks"))
                .willReturn(okJson(TestTokens.jwks(TestTokens.KEY))));
        return server;
    }

    @Bean
    DynamicPropertyRegistrar testJwtProperties(WireMockServer testJwkServer) {
        return registry -> {
            registry.add("activation.security.issuer-uri", () -> TestTokens.ISSUER);
            registry.add("activation.security.jwk-set-uri", () -> testJwkServer.baseUrl() + "/jwks");
            registry.add("activation.security.audience", () -> TestTokens.AUDIENCE);
        };
    }

    @Bean
    RestTemplateBuilder authenticatedTestClientBuilder() {
        return new RestTemplateBuilder().additionalInterceptors((request, body, execution) -> {
            if (request.getURI().getPath().startsWith("/api/")
                    && !request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                request.getHeaders().setBearerAuth(TestTokens.token("requests:read requests:write"));
            }
            return execution.execute(request, body);
        });
    }
}
