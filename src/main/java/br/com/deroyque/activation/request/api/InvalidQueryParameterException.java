package br.com.deroyque.activation.request.api;

public class InvalidQueryParameterException extends RuntimeException {

    public InvalidQueryParameterException() {
        super("customerId must contain between 1 and 100 characters after trimming.");
    }
}
