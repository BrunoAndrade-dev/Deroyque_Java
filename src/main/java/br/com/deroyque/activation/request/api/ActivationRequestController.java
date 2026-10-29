package br.com.deroyque.activation.request.api;

import br.com.deroyque.activation.request.application.ActivationRequestService;
import br.com.deroyque.activation.request.domain.RequestStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ProblemDetail;
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
@Tag(name = "Solicitações", description = "Criação e consulta de ativações assíncronas")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Dados ou parâmetros inválidos",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "Token ausente, inválido ou impossível de validar",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Token válido sem o escopo exigido",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Erro interno sanitizado",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class ActivationRequestController {

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Criar uma solicitação", description = "Exige requests:write. Persiste PENDING e responde antes da ativação. "
            + "externalId é globalmente único. Um retry idêntico do mesmo consumidor retorna o registro existente.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Criada; Location contém a URL de consulta",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ActivationRequestResponse.class))),
            @ApiResponse(responseCode = "200", description = "Retry idêntico do mesmo consumidor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ActivationRequestResponse.class))),
            @ApiResponse(responseCode = "409", description = "externalId indisponível para este pedido",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest input) {
        var result = service.create(input.externalId(), input.customerId(), input.productCode());
        var response = ActivationRequestResponse.from(result.request());
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/requests/" + response.id())).body(response)
                : ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar uma solicitação",
            description = "Exige requests:read. Retorna o estado atual de um pedido do próprio consumidor.")
    @ApiResponse(responseCode = "404", description = "Pedido inexistente ou de outro consumidor",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ActivationRequestResponse get(@PathVariable UUID id) {
        return ActivationRequestResponse.from(service.get(id));
    }

    @GetMapping
    @Operation(summary = "Listar solicitações", description = "Exige requests:read. Lista somente pedidos do consumidor, "
            + "do mais recente para o mais antigo. A primeira página é 0; size aceita de 1 a 100. Filtros são opcionais.")
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
