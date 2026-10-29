package br.com.deroyque.activation.request.api;

import br.com.deroyque.activation.request.application.ActivationRequestService;
import br.com.deroyque.activation.request.domain.RequestStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
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
@Tag(name = "Solicitações", description = "Registro, acompanhamento e consulta paginada de solicitações de ativação de produtos.")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Corpo da requisição ou parâmetros inválidos. Consulte os detalhes de validação na resposta.",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "Autenticação ausente ou token não aceito, incluindo token expirado ou assinatura não verificável.",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Consumidor autenticado sem o escopo necessário para executar a operação.",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Falha interna inesperada. A resposta não expõe informações sensíveis; utilize traceId para correlação.",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class ActivationRequestController {

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Registrar solicitação de ativação", description = """
            Registra uma solicitação para processamento assíncrono. Requer o escopo `requests:write`.

            `externalId`, `customerId` e `productCode` são obrigatórios e devem conter de 1 a 100 caracteres
            após a remoção de espaços nas extremidades. Não há conversão automática para maiúsculas.

            Uma nova solicitação é persistida em `PENDING` e retorna **201 Created**, com sua representação
            e o header `Location`. Essa resposta confirma o registro, não a conclusão da ativação.
            Consulte `GET /api/v1/requests/{id}` para acompanhar o processamento.

            O `externalId` é globalmente único. A repetição do mesmo conteúdo pelo mesmo consumidor
            retorna **200 OK**, com o registro existente e seu estado atual. Conteúdo divergente ou
            identificador já utilizado por outro consumidor retorna **409 Conflict**.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Solicitação criada em PENDING. Location indica a URL relativa de consulta.",
                    headers = @Header(name = "Location", description = "Endereço para consultar o estado da solicitação.",
                            schema = @Schema(type = "string", example = "/api/v1/requests/0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ActivationRequestResponse.class))),
            @ApiResponse(responseCode = "200", description = "Repetição idêntica do mesmo consumidor. Retorna a solicitação existente sem criar outro registro.",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ActivationRequestResponse.class))),
            @ApiResponse(responseCode = "409", description = "externalId já utilizado com conteúdo diferente ou pertencente a outro consumidor.",
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
    @Operation(summary = "Consultar solicitação por identificador", description = """
            Retorna a representação e o estado atual de uma solicitação pertencente ao consumidor
            autenticado. Requer o escopo `requests:read`. Utilize o UUID retornado na criação,
            e não o identificador de negócio `externalId`.

            Os estados possíveis são `PENDING` (aguardando processamento ou nova tentativa),
            `PROCESSING` (tentativa em andamento), `COMPLETED` (ativação confirmada) e
            `FAILED` (falha permanente ou limite de tentativas atingido).

            Após timeout, `FAILED` pode representar ausência de confirmação do resultado externo,
            sem garantir que a ativação não ocorreu. Uma nova consulta permite acompanhar a evolução
            do estado; este endpoint não inicia nem reinicia o processamento.

            Retorna **404 Not Found** tanto para um identificador inexistente quanto para uma solicitação
            de outro consumidor, preservando o isolamento dos registros.
            """)
    @ApiResponse(responseCode = "200", description = "Solicitação encontrada. Retorna seu estado atual e os dados de criação e atualização.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ActivationRequestResponse.class)))
    @ApiResponse(responseCode = "404", description = "Solicitação inexistente ou indisponível para o consumidor autenticado.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ActivationRequestResponse get(
            @Parameter(description = "UUID interno retornado na criação da solicitação.",
                    example = "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90") @PathVariable UUID id) {
        return ActivationRequestResponse.from(service.get(id));
    }

    @GetMapping
    @Operation(summary = "Listar solicitações com filtros e paginação", description = """
            Consulta exclusivamente as solicitações do consumidor autenticado. Requer o escopo
            `requests:read`. Os filtros `status` e `customerId` são opcionais; quando informados juntos,
            ambos devem ser atendidos. O filtro de cliente utiliza correspondência exata após remover
            espaços nas extremidades.

            A numeração das páginas começa em **0**. O tamanho padrão é **20**, com limite de **100**
            registros por página. A ordenação é decrescente por data de criação, com desempate pelo UUID.

            A resposta contém os registros e os metadados de paginação. Os totais consideram apenas
            os pedidos acessíveis ao consumidor e os filtros aplicados. Uma busca sem resultados
            ou uma página além do intervalo disponível retorna **200 OK**, com `content` vazio.
            """)
    @ApiResponse(responseCode = "200", description = "Página de solicitações do consumidor, incluindo os totais correspondentes aos filtros.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RequestPageResponse.class)))
    public RequestPageResponse list(
            @Parameter(description = "Estado a consultar. Omita para incluir todos os estados.", example = "COMPLETED")
            @RequestParam(required = false) RequestStatus status,
            @Parameter(description = "Identificador exato do cliente, com 1 a 100 caracteres. Omita para incluir todos os clientes.", example = "CUST-10001")
            @RequestParam(required = false) String customerId,
            @Parameter(description = "Índice da página, começando em 0.", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Quantidade máxima de registros na página, de 1 a 100.", example = "20")
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
