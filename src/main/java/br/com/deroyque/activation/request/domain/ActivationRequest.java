package br.com.deroyque.activation.request.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Agregado persistente que controla o ciclo de vida da ativação. Identificadores de negócio, dono e
 * criação são imutáveis; versão e token de tentativa protegem atualizações concorrentes.
 */
@Entity
@Table(name = "activation_requests", uniqueConstraints =
        @UniqueConstraint(name = "uk_activation_requests_external_id", columnNames = "external_id"))
public class ActivationRequest {

    @Id
    private UUID id;

    @Column(name = "external_id", nullable = false, length = 100, updatable = false)
    private String externalId;

    @Column(name = "customer_id", nullable = false, length = 100, updatable = false)
    private String customerId;

    @Column(name = "product_code", nullable = false, length = 100, updatable = false)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RequestStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "processing_token")
    private UUID processingToken;

    @Column(name = "last_error", length = 64)
    private String lastError;

    @Column(name = "correlation_id", nullable = false, length = 100, updatable = false)
    private String correlationId;

    @Column(name = "owner_id", nullable = false, length = 64, updatable = false)
    private String ownerId;

    /**
     * Construtor protegido exigido pelo JPA para reconstituição da entidade.
     */
    protected ActivationRequest() {
    }

    /**
     * Cria uma solicitação PENDING com dados normalizados, dono e correlação validados.
     *
     * @param externalId identificador global normalizado do pedido.
     * @param customerId identificador do cliente.
     * @param productCode código do produto solicitado.
     * @param now instante de criação ou transição em UTC; transições não podem retroceder no tempo.
     * @param correlationId identificador seguro de correlação.
     * @param ownerId identidade interna do consumidor validado.
     */
    private ActivationRequest(String externalId, String customerId, String productCode, Instant now,
                              String correlationId, String ownerId) {
        this.id = UUID.randomUUID();
        this.externalId = normalizeIdentifier(externalId, "externalId");
        this.customerId = normalizeIdentifier(customerId, "customerId");
        this.productCode = normalizeIdentifier(productCode, "productCode");
        this.status = RequestStatus.PENDING;
        this.createdAt = Objects.requireNonNull(now, "now is required");
        this.updatedAt = now;
        this.nextAttemptAt = now;
        if (correlationId == null || !correlationId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
            throw new IllegalArgumentException("Invalid correlation ID");
        }
        this.correlationId = correlationId;
        if (ownerId == null || !ownerId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid consumer identity");
        }
        this.ownerId = ownerId;
    }

    /**
     * Cria um agregado PENDING com identificadores normalizados e uma correlação UUID gerada.
     *
     * @param externalId identificador global normalizado do pedido.
     * @param customerId identificador do cliente.
     * @param productCode código do produto solicitado.
     * @param now instante de criação ou transição em UTC; transições não podem retroceder no tempo.
     * @param ownerId identidade interna do consumidor validado.
     * @return nova solicitação ainda não persistida.
     */
    public static ActivationRequest create(String externalId, String customerId, String productCode,
                                           Instant now, String ownerId) {
        return create(externalId, customerId, productCode, now, UUID.randomUUID().toString(), ownerId);
    }

    /**
     * Cria um agregado PENDING com identificadores normalizados e a correlação fornecida.
     *
     * @param externalId identificador global normalizado do pedido.
     * @param customerId identificador do cliente.
     * @param productCode código do produto solicitado.
     * @param now instante de criação ou transição em UTC; transições não podem retroceder no tempo.
     * @param correlationId identificador seguro de correlação.
     * @param ownerId identidade interna do consumidor validado.
     * @return nova solicitação ainda não persistida.
     */
    public static ActivationRequest create(String externalId, String customerId, String productCode,
                                           Instant now, String correlationId, String ownerId) {
        return new ActivationRequest(externalId, customerId, productCode, now, correlationId, ownerId);
    }

    /**
     * Reserva uma tentativa de um pedido PENDING já elegível, incrementando o contador e criando um
     * token novo. O processador verifica o orçamento de tentativas antes da reserva.
     *
     * @param now instante de criação ou transição em UTC; transições não podem retroceder no tempo.
     * @throws IllegalStateException se o estado de origem não permitir a transição.
     * @throws IllegalArgumentException se a tentativa ainda não estiver disponível ou o instante
     *     anteceder a última atualização.
     */
    public void startProcessing(Instant now) {
        Objects.requireNonNull(now, "now is required");
        if (nextAttemptAt != null && now.isBefore(nextAttemptAt)) {
            throw new IllegalArgumentException("The next attempt is not due yet");
        }
        transition(RequestStatus.PENDING, RequestStatus.PROCESSING, now);
        attemptCount++;
        processingStartedAt = now;
        processingToken = UUID.randomUUID();
        nextAttemptAt = null;
    }

    /**
     * Confirma sucesso somente a partir de PROCESSING e limpa os metadados da tentativa.
     *
     * @param now instante de criação ou transição em UTC; transições não podem retroceder no tempo.
     * @throws IllegalStateException se o estado de origem não permitir a transição.
     * @throws IllegalArgumentException se o instante anteceder a última atualização.
     */
    public void complete(Instant now) {
        transition(RequestStatus.PROCESSING, RequestStatus.COMPLETED, now);
        clearProcessing();
        lastError = null;
    }

    /**
     * Encerra uma tentativa em PROCESSING como FAILED, mantendo um código de erro sanitizado.
     *
     * @param now instante de criação ou transição em UTC; transições não podem retroceder no tempo.
     * @throws IllegalStateException se o estado de origem não permitir a transição.
     * @throws IllegalArgumentException se o instante anteceder a última atualização.
     */
    public void fail(Instant now) {
        fail("PERMANENT_FAILURE", now);
    }

    /**
     * Encerra uma tentativa em PROCESSING como FAILED, mantendo um código de erro sanitizado.
     *
     * @param errorCode código sanitizado da falha.
     * @param now instante de criação ou transição em UTC; transições não podem retroceder no tempo.
     * @throws IllegalStateException se o estado de origem não permitir a transição.
     * @throws IllegalArgumentException se o código de erro for inválido ou o instante anteceder
     *     a última atualização.
     */
    public void fail(String errorCode, Instant now) {
        String safeCode = validateErrorCode(errorCode);
        transition(RequestStatus.PROCESSING, RequestStatus.FAILED, now);
        clearProcessing();
        lastError = safeCode;
    }

    /**
     * Reagenda uma tentativa em PENDING ou encerra em FAILED quando o orçamento se esgota.
     *
     * @param errorCode código sanitizado da falha.
     * @param retryAt instante mínimo da próxima tentativa.
     * @param maxAttempts total permitido de tentativas.
     * @param now instante de criação ou transição em UTC; transições não podem retroceder no tempo.
     * @throws IllegalStateException se o estado de origem não permitir a transição.
     * @throws IllegalArgumentException se o código de erro ou a política de retry forem inválidos,
     *     ou se o instante anteceder a última atualização.
     */
    public void retryOrFail(String errorCode, Instant retryAt, int maxAttempts, Instant now) {
        String safeCode = validateErrorCode(errorCode);
        Objects.requireNonNull(now, "now is required");
        Objects.requireNonNull(retryAt, "retryAt is required");
        if (maxAttempts < 1 || retryAt.isBefore(now)) {
            throw new IllegalArgumentException("Invalid retry policy");
        }
        boolean exhausted = attemptCount >= maxAttempts;
        transition(RequestStatus.PROCESSING, exhausted ? RequestStatus.FAILED : RequestStatus.PENDING, now);
        clearProcessing();
        nextAttemptAt = exhausted ? null : retryAt;
        lastError = safeCode;
    }

    /**
     * Limpa os metadados da tentativa e o agendamento; o estado é definido pelo chamador.
     */
    private void clearProcessing() {
        processingStartedAt = null;
        processingToken = null;
        nextAttemptAt = null;
    }

    /**
     * Rejeita mensagens livres para impedir persistência de payloads ou credenciais como erro.
     *
     * @param code código de erro a validar.
     * @return código sanitizado validado.
     * @throws IllegalArgumentException se os valores informados violarem o contrato descrito.
     */
    private String validateErrorCode(String code) {
        if (code == null || !code.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Error must be a sanitized code");
        }
        return code;
    }

    /**
     * Exige o estado de origem e impede que a atualização retroceda no tempo.
     *
     * @param expected estado exigido antes da transição.
     * @param next estado de destino.
     * @param now instante de criação ou transição em UTC; transições não podem retroceder no tempo.
     * @throws IllegalStateException se o estado de origem não permitir a transição.
     * @throws IllegalArgumentException se o instante anteceder a última atualização.
     */
    private void transition(RequestStatus expected, RequestStatus next, Instant now) {
        Objects.requireNonNull(now, "now is required");
        if (status != expected) {
            throw new IllegalStateException("Cannot transition from " + status + " to " + next);
        }
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Transition time cannot precede the last update");
        }
        status = next;
        updatedAt = now;
    }

    /**
     * Valida presença e limite após strip, preservando a capitalização.
     *
     * @param value valor a examinar.
     * @param field nome do campo associado à validação.
     * @return identificador normalizado de 1 a 100 unidades UTF-16.
     * @throws IllegalArgumentException se os valores informados violarem o contrato descrito.
     */
    private static String normalizeIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.strip();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException(field + " must contain between 1 and 100 characters");
        }
        return normalized;
    }

    /**
     * UUID interno da solicitação.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Identificador global imutável da origem.
     */
    public String getExternalId() {
        return externalId;
    }

    /**
     * Identificador imutável do cliente.
     */
    public String getCustomerId() {
        return customerId;
    }

    /**
     * Código imutável do produto.
     */
    public String getProductCode() {
        return productCode;
    }

    /**
     * Estado atual controlado pelo domínio.
     */
    public RequestStatus getStatus() {
        return status;
    }

    /**
     * Instante imutável da criação em UTC.
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Instante da última transição em UTC.
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Versão otimista, ou null antes da primeira persistência.
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Total de tentativas reservadas, inclusive as interrompidas.
     */
    public int getAttemptCount() {
        return attemptCount;
    }

    /**
     * Instante da próxima tentativa em PENDING, ou null nos demais estados.
     */
    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    /**
     * Início da tentativa em andamento, ou null fora de PROCESSING.
     */
    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    /**
     * Token da reserva atual, ou null sem tentativa em andamento.
     */
    public UUID getProcessingToken() {
        return processingToken;
    }

    /**
     * Último código de falha sanitizado, ou null sem erro registrado.
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Correlação preservada desde a criação.
     */
    public String getCorrelationId() {
        return correlationId;
    }

    /**
     * Hash estável do consumidor proprietário.
     */
    public String getOwnerId() {
        return ownerId;
    }
}
