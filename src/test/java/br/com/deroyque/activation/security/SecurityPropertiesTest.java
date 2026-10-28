package br.com.deroyque.activation.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SecurityPropertiesTest {

    @Test
    void shouldFailBindingWhenTrustedIssuerConfigurationIsMissing() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://issuer.example.test", "https://user@issuer.example.test", "/relative",
            "https://issuer.example.test?access_token=not-allowed", "https://issuer.example.test#fragment"})
    void shouldRejectUnsafeIssuerOrJwkAddress(String address) {
        assertThatIllegalArgumentException().isThrownBy(() -> new SecurityProperties(URI.create(address),
                URI.create("https://issuer.example.test/jwks"), "activation-api"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecurityProperties(
                URI.create("https://issuer.example.test"), URI.create(address), "activation-api"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties.class)
    static class Binding {
    }
}
