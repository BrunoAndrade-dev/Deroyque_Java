package br.com.deroyque.activation.request.application;

import br.com.deroyque.activation.request.domain.ActivationRequest;
import br.com.deroyque.activation.request.persistence.ActivationRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class RequestCreationTransaction {

    private final ActivationRequestRepository repository;
    private final Clock clock;

    public RequestCreationTransaction(ActivationRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ActivationRequest insert(String externalId, String customerId, String productCode) {
        // Match PostgreSQL timestamp precision so first creation and retries expose the same dates.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return repository.saveAndFlush(ActivationRequest.create(externalId, customerId, productCode, now));
    }
}
