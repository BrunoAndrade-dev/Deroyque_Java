package br.com.deroyque.activation.request.application;

import br.com.deroyque.activation.request.domain.ActivationRequest;
import br.com.deroyque.activation.request.domain.RequestStatus;
import br.com.deroyque.activation.request.persistence.ActivationRequestRepository;
import br.com.deroyque.activation.security.CurrentConsumer;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ActivationRequestService {

    private static final String EXTERNAL_ID_CONSTRAINT = "uk_activation_requests_external_id";

    private final ActivationRequestRepository repository;
    private final RequestCreationTransaction creationTransaction;
    private final CurrentConsumer consumer;

    public ActivationRequestService(ActivationRequestRepository repository,
                                    RequestCreationTransaction creationTransaction, CurrentConsumer consumer) {
        this.repository = repository;
        this.creationTransaction = creationTransaction;
        this.consumer = consumer;
    }

    public CreationResult create(String externalId, String customerId, String productCode) {
        String ownerId = consumer.ownerId();
        var existing = repository.findByExternalId(externalId);
        if (existing.isPresent()) {
            return resolveRetry(existing.get(), customerId, productCode, ownerId);
        }

        try {
            var created = creationTransaction.insert(externalId, customerId, productCode, ownerId);
            return new CreationResult(created, true);
        } catch (DataIntegrityViolationException exception) {
            if (!isExternalIdConflict(exception)) {
                throw exception;
            }
            // The insert transaction has already rolled back before this read.
            var winner = repository.findByExternalId(externalId).orElseThrow(() -> exception);
            return resolveRetry(winner, customerId, productCode, ownerId);
        }
    }

    @Transactional(readOnly = true)
    public ActivationRequest get(UUID id) {
        return repository.findByIdAndOwnerId(id, consumer.ownerId()).orElseThrow(RequestNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Page<ActivationRequest> list(RequestStatus status, String customerId, int page, int size) {
        String ownerId = consumer.ownerId();
        Specification<ActivationRequest> filters = (root, query, builder) -> builder.equal(root.get("ownerId"), ownerId);
        if (status != null) {
            filters = filters.and((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (customerId != null) {
            filters = filters.and((root, query, builder) -> builder.equal(root.get("customerId"), customerId));
        }
        var order = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return repository.findAll(filters, PageRequest.of(page, size, order));
    }

    private CreationResult resolveRetry(ActivationRequest existing, String customerId, String productCode, String ownerId) {
        if (!existing.getOwnerId().equals(ownerId) || !existing.getCustomerId().equals(customerId)
                || !existing.getProductCode().equals(productCode)) {
            throw new RequestConflictException();
        }
        return new CreationResult(existing, false);
    }

    private boolean isExternalIdConflict(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && EXTERNAL_ID_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    public record CreationResult(ActivationRequest request, boolean created) {
    }
}
