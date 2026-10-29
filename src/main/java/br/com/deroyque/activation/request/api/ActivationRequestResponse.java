package br.com.deroyque.activation.request.api;

import br.com.deroyque.activation.request.domain.ActivationRequest;
import br.com.deroyque.activation.request.domain.RequestStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Representação pública de uma solicitação, sem versão JPA, credenciais ou metadados internos de
 * processamento.
 *
 * @param id UUID interno para consulta.
 * @param externalId identificador global do sistema de origem.
 * @param customerId cliente associado.
 * @param productCode produto solicitado.
 * @param status estado atual da solicitação.
 * @param createdAt criação imutável em UTC.
 * @param updatedAt última alteração em UTC.
 */
public record ActivationRequestResponse(
        @Schema(description = "UUID interno utilizado na consulta por identificador.") UUID id,
        @Schema(description = "Identificador único do pedido no sistema de origem.") String externalId,
        @Schema(description = "Identificador do cliente associado à solicitação.") String customerId,
        @Schema(description = "Código do produto solicitado para ativação.") String productCode,
        @Schema(description = "Estado atual: PENDING, PROCESSING, COMPLETED ou FAILED.") RequestStatus status,
        @Schema(description = "Data de criação em UTC, no formato ISO-8601. Permanece imutável.") Instant createdAt,
        @Schema(description = "Data da última atualização em UTC, no formato ISO-8601.") Instant updatedAt) {

    /**
     * Copia somente os campos públicos da entidade para o DTO.
     *
     * @param request entidade da solicitação utilizada nesta operação.
     * @return representação sem metadados internos ou credenciais.
     */
    public static ActivationRequestResponse from(ActivationRequest request) {
        return new ActivationRequestResponse(request.getId(), request.getExternalId(), request.getCustomerId(),
                request.getProductCode(), request.getStatus(), request.getCreatedAt(), request.getUpdatedAt());
    }
}
