package br.com.deroyque.activation.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties("activation.security")
public record SecurityProperties(URI issuerUri, URI jwkSetUri, String audience) {

    public SecurityProperties {
        if (!validEndpoint(issuerUri) || !validEndpoint(jwkSetUri)
                || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("A trusted issuer, JWK Set URI and audience must be configured");
        }
    }

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
