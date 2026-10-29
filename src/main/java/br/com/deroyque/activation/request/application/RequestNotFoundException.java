package br.com.deroyque.activation.request.application;

/**
 * Representa pedido inexistente ou inacessível ao consumidor, com a mesma resposta pública HTTP 404.
 */
public class RequestNotFoundException extends RuntimeException {

    /**
     * Cria a resposta de ausência usada também para registros de outros consumidores.
     */
    public RequestNotFoundException() {
        super("Activation request not found.");
    }
}
