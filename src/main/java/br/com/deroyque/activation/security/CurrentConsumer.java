package br.com.deroyque.activation.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Obtém a identidade de um JWT autenticado e deriva o proprietário estável dos pedidos, sem persistir o
 * bearer token.
 */
@Component
public class CurrentConsumer {

    /**
     * Exige autenticação JWT antes de calcular o proprietário dos pedidos.
     *
     * @return hash estável do emissor e subject autenticados.
     * @throws org.springframework.security.access.AccessDeniedException se não houver JWT autenticado
     *     no contexto.
     */
    public String ownerId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw new AccessDeniedException("Authenticated consumer required");
        }
        return identity(token.getToken().getIssuer().toString(), token.getToken().getSubject());
    }

    /**
     * Combina emissor e subject com delimitador inequívoco e deriva o hash SHA-256.
     *
     * @param issuer emissor confiável da identidade.
     * @param subject identificador estável do consumidor.
     * @return identidade hexadecimal de 64 caracteres; não é criptografia dos claims.
     */
    public static String identity(String issuer, String subject) {
        try {
            // Delimiter prevents ambiguous concatenation; only validated issuer/subject are used at runtime.
            byte[] input = (issuer + "\u0000" + subject).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
