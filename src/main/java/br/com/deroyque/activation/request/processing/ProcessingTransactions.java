package br.com.deroyque.activation.request.processing;

import br.com.deroyque.activation.request.domain.ActivationRequest;
import br.com.deroyque.activation.request.domain.RequestStatus;
import br.com.deroyque.activation.request.persistence.ActivationRequestRepository;
import br.com.deroyque.activation.request.provider.ProviderOutcome;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Component
public class ProcessingTransactions {

    private final ActivationRequestRepository repository;
    private final ProcessingProperties properties;
    private final Clock clock;

    public ProcessingTransactions(ActivationRequestRepository repository, ProcessingProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<WorkItem> claimNext() {
        Instant now = now();
        return repository.findNextForProcessing(now, properties.maxAttempts()).map(request -> {
            request.startProcessing(now);
            return WorkItem.from(request);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finish(WorkItem work, ProviderOutcome outcome) {
        var found = repository.findForUpdate(work.id());
        if (found.isEmpty()) {
            return false;
        }
        var request = found.get();
        if (request.getStatus() != RequestStatus.PROCESSING || !work.token().equals(request.getProcessingToken())) {
            return false;
        }
        Instant now = now();
        switch (outcome.kind()) {
            case SUCCESS -> request.complete(now);
            case PERMANENT_FAILURE -> request.fail(outcome.errorCode(), now);
            case RETRYABLE_FAILURE -> request.retryOrFail(outcome.errorCode(),
                    retryAt(request, now, outcome.retryAfter()), properties.maxAttempts(), now);
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverAbandoned() {
        Instant now = now();
        var abandoned = repository.findAbandonedForUpdate(now.minus(properties.processingTimeout()));
        for (var request : abandoned) {
            request.retryOrFail("PROCESSING_EXPIRED", retryAt(request, now, null), properties.maxAttempts(), now);
        }
        return abandoned.size();
    }

    private Instant retryAt(ActivationRequest request, Instant now, Instant retryAfter) {
        var backoff = properties.retryDelay().multipliedBy(1L << (request.getAttemptCount() - 1));
        Instant scheduled = now.plus(backoff);
        return retryAfter != null && retryAfter.isAfter(scheduled) ? retryAfter : scheduled;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
