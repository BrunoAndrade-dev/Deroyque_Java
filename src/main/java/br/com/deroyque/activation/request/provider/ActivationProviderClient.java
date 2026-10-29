package br.com.deroyque.activation.request.provider;

import br.com.deroyque.activation.request.processing.WorkItem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class ActivationProviderClient {

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final ProviderProperties properties;
    private final Clock clock;
    private final ProviderCredentials credentials;

    public ActivationProviderClient(@Qualifier("providerHttpClient") HttpClient client, ObjectMapper mapper,
                                    ProviderProperties properties, Clock clock, ProviderCredentials credentials) {
        this.client = client;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
        this.credentials = credentials;
    }

    public ProviderOutcome activate(WorkItem work) {
        String json;
        try {
            json = mapper.writeValueAsString(new ActivationPayload(work.externalId(), work.customerId(),
                    work.productCode()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot encode activation payload", exception);
        }
        var builder = HttpRequest.newBuilder(properties.baseUrl().resolve("/provider/v1/activate"))
                .timeout(properties.readTimeout())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", IdempotencyKey.from(work.externalId()))
                .header("X-Correlation-ID", work.correlationId())
                .POST(HttpRequest.BodyPublishers.ofString(json));
        try {
            credentials.bearerToken().ifPresent(token -> builder.header("Authorization", "Bearer " + token));
        } catch (ProviderCredentialUnavailableException exception) {
            return ProviderOutcome.retryable("CREDENTIAL_UNAVAILABLE", null);
        }
        var request = builder.build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // Only status and headers are needed; close without consuming or retaining provider payloads.
            try (var ignored = response.body()) {
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return ProviderOutcome.success();
                }
                if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) {
                    Instant retryAfter = status == 429 || status == 503
                            ? parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null)) : null;
                    return ProviderOutcome.retryable("HTTP_" + status, retryAfter);
                }
                return ProviderOutcome.permanent("HTTP_" + status);
            }
        } catch (HttpTimeoutException exception) {
            return ProviderOutcome.retryable("TIMEOUT", null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ProviderOutcome.retryable("INTERRUPTED", null);
        } catch (IOException exception) {
            return ProviderOutcome.retryable("IO_FAILURE", null);
        }
    }

    private Instant parseRetryAfter(String value) {
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.strip());
            return seconds >= 0 ? clock.instant().plusSeconds(seconds) : null;
        } catch (NumberFormatException exception) {
            try {
                return ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (DateTimeException invalidDate) {
                return null;
            }
        } catch (DateTimeException | ArithmeticException exception) {
            return null;
        }
    }

    private record ActivationPayload(String externalId, String customerId, String productCode) {
    }
}
