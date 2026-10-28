package br.com.deroyque.activation.request.provider;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
public class ProviderCredentials {

    private final ProviderCredentialProperties properties;

    public ProviderCredentials(ProviderCredentialProperties properties, ProviderProperties provider) {
        this.properties = properties;
        String host = provider.baseUrl().getHost();
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (properties.tokenFile() != null && !"https".equals(provider.baseUrl().getScheme()) && !loopback) {
            throw new IllegalArgumentException("Provider credentials require HTTPS outside loopback development");
        }
    }

    public Optional<String> bearerToken() {
        if (properties.tokenFile() == null) {
            return Optional.empty();
        }
        try {
            if (!Files.isRegularFile(properties.tokenFile())) {
                throw new IOException();
            }
            byte[] bytes;
            try (var input = Files.newInputStream(properties.tokenFile())) {
                bytes = input.readNBytes(8193);
            }
            if (bytes.length > 8192) {
                throw new IOException();
            }
            String token = new String(bytes, StandardCharsets.UTF_8).strip();
            if (token.isEmpty() || token.length() > 8192 || !token.matches("[A-Za-z0-9\\-._~+/]+=*")) {
                throw new IOException();
            }
            return Optional.of(token);
        } catch (IOException | SecurityException exception) {
            // Never attach file content, headers or the underlying exception to this failure.
            throw new ProviderCredentialUnavailableException();
        }
    }
}
