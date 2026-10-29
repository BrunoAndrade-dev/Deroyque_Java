package br.com.deroyque.activation.request.provider;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Lê a credencial a cada chamada para permitir rotação. Limita o tamanho, valida o formato Bearer e
 * exige HTTPS fora de loopback quando há segredo configurado.
 */
@Component
public class ProviderCredentials {

    private final ProviderCredentialProperties properties;

    /**
     * Recebe o caminho do segredo e valida a exigência de HTTPS fora de loopback.
     *
     * @param properties configuração validada do componente.
     * @param provider endereço e timeouts configurados para o provedor.
     */
    public ProviderCredentials(ProviderCredentialProperties properties, ProviderProperties provider) {
        this.properties = properties;
        String host = provider.baseUrl().getHost();
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (properties.tokenFile() != null && !"https".equals(provider.baseUrl().getScheme()) && !loopback) {
            throw new IllegalArgumentException("Provider credentials require HTTPS outside loopback development");
        }
    }

    /**
     * Lê novamente o arquivo para permitir rotação e rejeita valores vazios, maiores que 8 KiB ou
     * incompatíveis com Bearer.
     *
     * @return credencial validada ou vazio quando nenhum arquivo foi configurado.
     * @throws ProviderCredentialUnavailableException se o arquivo configurado não puder fornecer uma
     *     credencial válida.
     */
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
