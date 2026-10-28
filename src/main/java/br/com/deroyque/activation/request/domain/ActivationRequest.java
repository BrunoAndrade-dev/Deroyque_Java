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

    protected ActivationRequest() {
        // Required by JPA.
    }

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

    public static ActivationRequest create(String externalId, String customerId, String productCode,
                                           Instant now, String ownerId) {
        return create(externalId, customerId, productCode, now, UUID.randomUUID().toString(), ownerId);
    }

    public static ActivationRequest create(String externalId, String customerId, String productCode,
                                           Instant now, String correlationId, String ownerId) {
        return new ActivationRequest(externalId, customerId, productCode, now, correlationId, ownerId);
    }

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

    public void complete(Instant now) {
        transition(RequestStatus.PROCESSING, RequestStatus.COMPLETED, now);
        clearProcessing();
        lastError = null;
    }

    public void fail(Instant now) {
        fail("PERMANENT_FAILURE", now);
    }

    public void fail(String errorCode, Instant now) {
        String safeCode = validateErrorCode(errorCode);
        transition(RequestStatus.PROCESSING, RequestStatus.FAILED, now);
        clearProcessing();
        lastError = safeCode;
    }

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

    private void clearProcessing() {
        processingStartedAt = null;
        processingToken = null;
        nextAttemptAt = null;
    }

    private String validateErrorCode(String code) {
        if (code == null || !code.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Error must be a sanitized code");
        }
        return code;
    }

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

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public UUID getProcessingToken() {
        return processingToken;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getOwnerId() {
        return ownerId;
    }
}
