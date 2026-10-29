package br.com.deroyque.activation.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Fornece PostgreSQL real por Testcontainers e metadados de segurança exclusivos dos testes.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestSecurityConfiguration.class)
public class PostgresTestConfiguration {

    /**
     * Cria o banco real cuja conexão será fornecida automaticamente ao contexto de testes.
     *
     * @return PostgreSQL 17.6 descartável gerenciado por Spring/Testcontainers.
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:17.6-alpine");
    }
}
