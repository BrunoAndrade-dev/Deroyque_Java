package br.com.deroyque.activation.request.api;

import br.com.deroyque.activation.request.application.ActivationRequestService;
import br.com.deroyque.activation.request.domain.RequestStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController {

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest input) {
        var result = service.create(input.externalId(), input.customerId(), input.productCode());
        var response = ActivationRequestResponse.from(result.request());
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/requests/" + response.id())).body(response)
                : ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ActivationRequestResponse get(@PathVariable UUID id) {
        return ActivationRequestResponse.from(service.get(id));
    }

    @GetMapping
    public RequestPageResponse list(@RequestParam(required = false) RequestStatus status,
                                    @RequestParam(required = false) String customerId,
                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        if (customerId != null) {
            customerId = customerId.strip();
            if (customerId.isBlank() || customerId.length() > 100) {
                throw new InvalidQueryParameterException();
            }
        }
        var results = service.list(status, customerId, page, size);
        return new RequestPageResponse(results.getContent().stream().map(ActivationRequestResponse::from).toList(),
                results.getNumber(), results.getSize(), results.getTotalElements(), results.getTotalPages());
    }
}
