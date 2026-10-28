package br.com.deroyque.activation.request.application;

public class RequestNotFoundException extends RuntimeException {

    public RequestNotFoundException() {
        super("Activation request not found.");
    }
}
