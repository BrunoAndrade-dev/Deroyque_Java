package br.com.deroyque.activation.request.provider;

import java.time.Instant;

public record ProviderOutcome(Kind kind, String errorCode, Instant retryAfter) {

    public enum Kind { SUCCESS, PERMANENT_FAILURE, RETRYABLE_FAILURE }

    public static ProviderOutcome success() {
        return new ProviderOutcome(Kind.SUCCESS, null, null);
    }

    public static ProviderOutcome permanent(String errorCode) {
        return new ProviderOutcome(Kind.PERMANENT_FAILURE, errorCode, null);
    }

    public static ProviderOutcome retryable(String errorCode, Instant retryAfter) {
        return new ProviderOutcome(Kind.RETRYABLE_FAILURE, errorCode, retryAfter);
    }
}
