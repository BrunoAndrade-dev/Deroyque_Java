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

    protected ActivationRequest() {
        // Required by JPA.
    }

    private ActivationRequest(String externalId, String customerId, String productCode, Instant now) {
        this.id = UUID.randomUUID();
        this.externalId = normalizeIdentifier(externalId, "externalId");
        this.customerId = normalizeIdentifier(customerId, "customerId");
        this.productCode = normalizeIdentifier(productCode, "productCode");
        this.status = RequestStatus.PENDING;
        this.createdAt = Objects.requireNonNull(now, "now is required");
        this.updatedAt = now;
    }

    public static ActivationRequest create(String externalId, String customerId, String productCode, Instant now) {
        return new ActivationRequest(externalId, customerId, productCode, now);
    }

    public void startProcessing(Instant now) {
        transition(RequestStatus.PENDING, RequestStatus.PROCESSING, now);
    }

    public void complete(Instant now) {
        transition(RequestStatus.PROCESSING, RequestStatus.COMPLETED, now);
    }

    public void fail(Instant now) {
        transition(RequestStatus.PROCESSING, RequestStatus.FAILED, now);
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
}
