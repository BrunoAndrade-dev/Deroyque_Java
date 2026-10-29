package br.com.deroyque.activation.request.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deriva uma chave estável para retries externos. O hash não substitui autorização nem a garantia de
 * idempotência do provedor.
 */
public final class IdempotencyKey {

    /**
     * Impede instanciação do utilitário de chaves de idempotência.
     */
    private IdempotencyKey() {
    }

    /**
     * Calcula SHA-256 do identificador normalizado para reutilizar a mesma chave em todas as
     * tentativas.
     *
     * @param externalId identificador global normalizado do pedido.
     * @return hash hexadecimal estável do externalId.
     */
    public static String from(String externalId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(externalId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
