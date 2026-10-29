package br.com.deroyque.activation.request.application;

/**
 * Representa indisponibilidade do identificador global, sem revelar dados de pedidos de outros
 * consumidores.
 */
public class RequestConflictException extends RuntimeException {

    /**
     * Cria conflito genérico sem revelar conteúdo ou propriedade do registro existente.
     */
    public RequestConflictException() {
        super("The externalId is not available for this request.");
    }
}
