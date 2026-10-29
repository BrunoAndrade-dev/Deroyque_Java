package br.com.deroyque.activation.request.provider;

import java.time.Instant;

/**
 * Resultado sanitizado da integração, independente do corpo retornado pelo provedor.
 *
 * @param kind classificação da resposta ou falha de transporte.
 * @param errorCode código sanitizado da falha, ou null em caso de sucesso.
 * @param retryAfter instante mínimo solicitado pelo provedor, ou null quando ausente/inválido.
 */
public record ProviderOutcome(Kind kind, String errorCode, Instant retryAfter) {

    /**
     * Classificação que determina conclusão, falha permanente ou possível retentativa.
     */
    public enum Kind {
        /** Ativação confirmada por resposta HTTP de sucesso. */
        SUCCESS,
        /** Resposta que encerra o pedido sem nova tentativa automática. */
        PERMANENT_FAILURE,
        /** Falha temporária ou ambígua que pode consumir outra tentativa. */
        RETRYABLE_FAILURE
    }

    /**
     * Representa confirmação do provedor.
     *
     * @return resultado de sucesso, sem erro ou Retry-After.
     */
    public static ProviderOutcome success() {
        return new ProviderOutcome(Kind.SUCCESS, null, null);
    }

    /**
     * Representa falha que não deve originar retry automático.
     *
     * @param errorCode código sanitizado da falha, ou null em caso de sucesso.
     * @return resultado permanente com código de erro.
     */
    public static ProviderOutcome permanent(String errorCode) {
        return new ProviderOutcome(Kind.PERMANENT_FAILURE, errorCode, null);
    }

    /**
     * Representa falha temporária ou ambígua sujeita ao orçamento de tentativas.
     *
     * @param errorCode código sanitizado da falha, ou null em caso de sucesso.
     * @param retryAfter instante mínimo solicitado pelo provedor, ou null quando ausente/inválido.
     * @return resultado temporário com prazo opcional do provedor.
     */
    public static ProviderOutcome retryable(String errorCode, Instant retryAfter) {
        return new ProviderOutcome(Kind.RETRYABLE_FAILURE, errorCode, retryAfter);
    }
}
