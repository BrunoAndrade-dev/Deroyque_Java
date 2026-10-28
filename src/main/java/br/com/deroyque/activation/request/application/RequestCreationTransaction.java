package br.com.deroyque.activation.request.application;

import br.com.deroyque.activation.request.domain.ActivationRequest;
import br.com.deroyque.activation.request.persistence.ActivationRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Component
public class RequestCreationTransaction {

    private final ActivationRequestRepository repository;
    private final Clock clock;

    public RequestCreationTransaction(ActivationRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ActivationRequest insert(String externalId, String customerId, String productCode, String ownerId) {
        // Match PostgreSQL timestamp precision so first creation and retries expose the same dates.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String correlationId = MDC.get("traceId");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        return repository.saveAndFlush(ActivationRequest.create(externalId, customerId, productCode, now,
                correlationId, ownerId));
    }
}
