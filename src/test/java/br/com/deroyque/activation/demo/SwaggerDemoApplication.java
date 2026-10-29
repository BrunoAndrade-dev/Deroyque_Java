package br.com.deroyque.activation.demo;

import br.com.deroyque.activation.ActivationApplication;
import br.com.deroyque.activation.support.IdempotentProviderMock;
import br.com.deroyque.activation.support.TestTokens;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/** Explicit test-classpath launcher; no demo beans or bypasses enter production. */
public final class SwaggerDemoApplication implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SwaggerDemoApplication.class);
    private PostgreSQLContainer<?> database;
    private WireMockServer provider;
    private DemoTokenIssuer issuer;
    private ConfigurableApplicationContext application;

    private SwaggerDemoApplication() { }

    public static void main(String[] args) throws InterruptedException {
        var demo = start(8090);
        Runtime.getRuntime().addShutdownHook(new Thread(demo::close, "swagger-demo-shutdown"));
        log.info("Swagger da demonstração: http://127.0.0.1:{}/swagger-ui.html", demo.port());
        log.info("Página local de tokens temporários: {}/", demo.issuer.baseUrl());
        log.info("Use somente dados fictícios. Ctrl+C encerra e remove o banco temporário.");
        try {
            new CountDownLatch(1).await();
        } finally {
            demo.close();
        }
    }

    static SwaggerDemoApplication start(int port) {
        var demo = new SwaggerDemoApplication();
        try {
            demo.database = new PostgreSQLContainer<>("postgres:17.6-alpine")
                    .withDatabaseName("swagger_demo").withUsername("demo")
                    .withPassword(UUID.randomUUID().toString())
                    .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(5432))));
            demo.database.start();
            demo.provider = new WireMockServer(wireMockConfig().bindAddress("127.0.0.1")
                    .dynamicPort().disableRequestJournal().extensions(new IdempotentProviderMock()));
            demo.provider.start();
            demo.provider.stubFor(post(urlEqualTo("/provider/v1/activate"))
                    .willReturn(aResponse().withStatus(204).withTransformers("idempotent-activation")));
            demo.issuer = new DemoTokenIssuer(demo::port);

            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("server.address", "127.0.0.1");
            settings.put("server.port", port);
            settings.put("spring.datasource.url", demo.database.getJdbcUrl());
            settings.put("spring.datasource.username", demo.database.getUsername());
            settings.put("spring.datasource.password", demo.database.getPassword());
            settings.put("spring.config.import", "");
            settings.put("activation.security.issuer-uri", TestTokens.ISSUER);
            settings.put("activation.security.jwk-set-uri", demo.issuer.baseUrl() + "/jwks");
            settings.put("activation.security.audience", TestTokens.AUDIENCE);
            settings.put("activation.provider.base-url", demo.provider.baseUrl());
            settings.put("activation.provider.authentication.token-file", "");
            settings.put("activation.processing.enabled", true);
            settings.put("springdoc.api-docs.enabled", true);
            settings.put("springdoc.swagger-ui.enabled", true);
            var launcher = new SpringApplication(ActivationApplication.class);
            launcher.setRegisterShutdownHook(false);
            launcher.addInitializers(context -> context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("swagger-demo", settings)));
            demo.application = launcher.run();
            return demo;
        } catch (IOException | RuntimeException exception) {
            demo.close();
            throw new IllegalStateException("Cannot start local Swagger demonstration", exception);
        }
    }

    int port() {
        return ((ServletWebServerApplicationContext) application).getWebServer().getPort();
    }

    String issuerUrl() {
        return issuer.baseUrl();
    }

    @Override
    public synchronized void close() {
        if (application != null) {
            closeResource(application::close);
            application = null;
        }
        if (provider != null) {
            closeResource(provider::stop);
            provider = null;
        }
        if (issuer != null) {
            closeResource(issuer::close);
            issuer = null;
        }
        if (database != null) {
            closeResource(database::stop);
            database = null;
        }
    }

    private void closeResource(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn("Demo resource shutdown failed: type={}", exception.getClass().getSimpleName());
        }
    }
}
