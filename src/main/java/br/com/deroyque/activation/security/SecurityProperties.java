package br.com.deroyque.activation.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * Metadados confiáveis do emissor. Exige HTTPS, admitindo HTTP somente para endereços literais de
 * loopback usados em desenvolvimento e testes.
 *
 * @param issuerUri emissor que deve corresponder ao claim iss.
 * @param jwkSetUri origem confiável das chaves públicas, não fornecida pelo JWT.
 * @param audience audiência esperada no claim aud.
 */
@ConfigurationProperties("activation.security")
public record SecurityProperties(URI issuerUri, URI jwkSetUri, String audience) {

    /**
     * Rejeita metadados ausentes e endpoints incompatíveis com a política de transporte.
     *
     * @param issuerUri emissor que deve corresponder ao claim iss.
     * @param jwkSetUri origem confiável das chaves públicas, não fornecida pelo JWT.
     * @param audience audiência esperada no claim aud.
     * @throws IllegalArgumentException se os valores informados violarem o contrato descrito.
     */
    public SecurityProperties {
        if (!validEndpoint(issuerUri) || !validEndpoint(jwkSetUri)
                || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("A trusted issuer, JWK Set URI and audience must be configured");
        }
    }

    /**
     * Exige host e proíbe credenciais, query e fragmento na origem de confiança.
     *
     * @param uri endereço a validar.
     * @return true para HTTPS ou HTTP em loopback literal.
     */
    private static boolean validEndpoint(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            return false;
        }
        boolean loopback = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                || "[::1]".equals(uri.getHost());
        return "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback);
    }
}
