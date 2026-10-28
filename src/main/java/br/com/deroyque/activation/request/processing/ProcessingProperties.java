package br.com.deroyque.activation.request.processing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("activation.processing")
public record ProcessingProperties(@DefaultValue("3") int maxAttempts,
                                   @DefaultValue("1s") Duration retryDelay,
                                   @DefaultValue("60s") Duration processingTimeout) {

    public ProcessingProperties {
        if (maxAttempts < 1 || maxAttempts > 10 || retryDelay.isNegative() || retryDelay.isZero()
                || processingTimeout.isNegative() || processingTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid activation processing configuration");
        }
    }
}
