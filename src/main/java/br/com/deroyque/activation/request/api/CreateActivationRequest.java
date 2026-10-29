package br.com.deroyque.activation.request.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateActivationRequest(
        @Schema(description = "Identificador globalmente único; espaços nas extremidades são removidos", example = "EXT-12345")
        @NotBlank @Size(min = 1, max = 100) String externalId,
        @Schema(example = "CUST-10001") @NotBlank @Size(min = 1, max = 100) String customerId,
        @Schema(example = "INTERNET-500") @NotBlank @Size(min = 1, max = 100) String productCode) {

    public CreateActivationRequest {
        externalId = strip(externalId);
        customerId = strip(customerId);
        productCode = strip(productCode);
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
