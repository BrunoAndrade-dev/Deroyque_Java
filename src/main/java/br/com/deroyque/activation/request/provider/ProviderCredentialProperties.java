package br.com.deroyque.activation.request.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("activation.provider.authentication")
public record ProviderCredentialProperties(Path tokenFile) {
}
