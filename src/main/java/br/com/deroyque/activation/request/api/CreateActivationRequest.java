package br.com.deroyque.activation.request.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateActivationRequest(
        @NotBlank @Size(min = 1, max = 100) String externalId,
        @NotBlank @Size(min = 1, max = 100) String customerId,
        @NotBlank @Size(min = 1, max = 100) String productCode) {

    public CreateActivationRequest {
        externalId = strip(externalId);
        customerId = strip(customerId);
        productCode = strip(productCode);
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
