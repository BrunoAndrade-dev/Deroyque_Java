package br.com.deroyque.activation.request.application;

public class RequestConflictException extends RuntimeException {

    public RequestConflictException() {
        super("externalId is already associated with different request data.");
    }
}
