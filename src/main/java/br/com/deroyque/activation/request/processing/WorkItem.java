package br.com.deroyque.activation.request.processing;

import br.com.deroyque.activation.request.domain.ActivationRequest;

import java.util.UUID;

/**
 * Snapshot imutável de uma tentativa reservada, utilizado fora da transação de banco.
 *
 * @param id UUID da solicitação.
 * @param token identificador exclusivo da tentativa atual.
 * @param externalId identificador global utilizado na idempotência externa.
 * @param customerId cliente associado.
 * @param productCode produto a ativar.
 * @param correlationId correlação preservada desde a criação.
 * @param attemptCount quantidade de tentativas já reservadas, incluindo esta.
 */
public record WorkItem(UUID id, UUID token, String externalId, String customerId, String productCode,
                       String correlationId, int attemptCount) {

    /**
     * Captura os dados necessários à integração antes de encerrar a transação de reserva.
     *
     * @param request entidade da solicitação utilizada nesta operação.
     * @return snapshot imutável da tentativa atual.
     */
    static WorkItem from(ActivationRequest request) {
        return new WorkItem(request.getId(), request.getProcessingToken(), request.getExternalId(),
                request.getCustomerId(), request.getProductCode(), request.getCorrelationId(), request.getAttemptCount());
    }
}
