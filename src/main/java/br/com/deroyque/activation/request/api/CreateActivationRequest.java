package br.com.deroyque.activation.request.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Entrada da criação. Remove espaços nas extremidades antes da validação, preservando maiúsculas e
 * minúsculas.
 *
 * @param externalId identificador global do pedido, obrigatório, de 1 a 100 caracteres.
 * @param customerId identificador do cliente, obrigatório, de 1 a 100 caracteres.
 * @param productCode código do produto, obrigatório, de 1 a 100 caracteres.
 */
public record CreateActivationRequest(
        @Schema(description = "Identificador do pedido no sistema de origem, globalmente único. Utilizado para reconhecer repetições da mesma solicitação.", example = "EXT-12345")
        @NotBlank @Size(min = 1, max = 100) String externalId,
        @Schema(description = "Identificador do cliente para o qual o produto será ativado.", example = "CUST-10001")
        @NotBlank @Size(min = 1, max = 100) String customerId,
        @Schema(description = "Código do produto a ser enviado ao provedor de ativação.", example = "INTERNET-500")
        @NotBlank @Size(min = 1, max = 100) String productCode) {

    /**
     * Normaliza os identificadores antes da avaliação das restrições Bean Validation.
     *
     * @param externalId identificador global do pedido, obrigatório, de 1 a 100 caracteres.
     * @param customerId identificador do cliente, obrigatório, de 1 a 100 caracteres.
     * @param productCode código do produto, obrigatório, de 1 a 100 caracteres.
     */
    public CreateActivationRequest {
        externalId = strip(externalId);
        customerId = strip(customerId);
        productCode = strip(productCode);
    }

    /**
     * Remove espaços das extremidades sem transformar null em texto.
     *
     * @param value valor a examinar.
     * @return valor normalizado, ou null para validação posterior.
     */
    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
