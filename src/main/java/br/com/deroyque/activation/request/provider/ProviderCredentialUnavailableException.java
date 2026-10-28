package br.com.deroyque.activation.request.provider;

public class ProviderCredentialUnavailableException extends RuntimeException {

    public ProviderCredentialUnavailableException() {
        super("Provider credential unavailable.");
    }
}
