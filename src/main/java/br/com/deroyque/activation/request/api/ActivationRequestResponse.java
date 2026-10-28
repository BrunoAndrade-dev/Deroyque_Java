package br.com.deroyque.activation.request.api;

import br.com.deroyque.activation.request.domain.ActivationRequest;
import br.com.deroyque.activation.request.domain.RequestStatus;

import java.time.Instant;
import java.util.UUID;

public record ActivationRequestResponse(UUID id, String externalId, String customerId, String productCode,
                                        RequestStatus status, Instant createdAt, Instant updatedAt) {

    public static ActivationRequestResponse from(ActivationRequest request) {
        return new ActivationRequestResponse(request.getId(), request.getExternalId(), request.getCustomerId(),
                request.getProductCode(), request.getStatus(), request.getCreatedAt(), request.getUpdatedAt());
    }
}
