package br.com.deroyque.activation.request.api;

/**
 * Indica filtro de cliente inválido e permite sua tradução para HTTP 400 sem detalhes internos.
 */
public class InvalidQueryParameterException extends RuntimeException {

    /**
     * Cria a falha pública do filtro customerId.
     */
    public InvalidQueryParameterException() {
        super("customerId must contain between 1 and 100 characters after trimming.");
    }
}
