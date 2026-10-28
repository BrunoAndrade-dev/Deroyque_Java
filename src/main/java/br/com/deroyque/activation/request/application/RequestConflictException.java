package br.com.deroyque.activation.request.application;

public class RequestConflictException extends RuntimeException {

    public RequestConflictException() {
        super("The externalId is not available for this request.");
    }
}
