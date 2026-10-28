package br.com.deroyque.activation.request.processing;

import br.com.deroyque.activation.request.domain.ActivationRequest;

import java.util.UUID;

public record WorkItem(UUID id, UUID token, String externalId, String customerId, String productCode,
                       String correlationId, int attemptCount) {

    static WorkItem from(ActivationRequest request) {
        return new WorkItem(request.getId(), request.getProcessingToken(), request.getExternalId(),
                request.getCustomerId(), request.getProductCode(), request.getCorrelationId(), request.getAttemptCount());
    }
}
