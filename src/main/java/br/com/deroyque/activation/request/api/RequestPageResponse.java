package br.com.deroyque.activation.request.api;

import java.util.List;

public record RequestPageResponse(List<ActivationRequestResponse> content, int page, int size,
                                  long totalElements, int totalPages) {
}
