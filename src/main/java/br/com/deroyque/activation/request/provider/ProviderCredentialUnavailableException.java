package br.com.deroyque.activation.request.provider;

/**
 * Falha sanitizada de leitura da credencial; não transporta conteúdo do arquivo ou causas que possam
 * expor segredos.
 */
public class ProviderCredentialUnavailableException extends RuntimeException {

    /**
     * Cria falha sanitizada sem anexar o conteúdo do segredo ou a exceção original.
     */
    public ProviderCredentialUnavailableException() {
        super("Provider credential unavailable.");
    }
}
