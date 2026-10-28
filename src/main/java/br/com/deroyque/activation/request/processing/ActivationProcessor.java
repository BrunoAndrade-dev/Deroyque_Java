package br.com.deroyque.activation.request.processing;

import br.com.deroyque.activation.request.provider.ActivationProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ActivationProcessor {

    private static final Logger log = LoggerFactory.getLogger(ActivationProcessor.class);
    private final ProcessingTransactions transactions;
    private final ActivationProviderClient provider;

    public ActivationProcessor(ProcessingTransactions transactions, ActivationProviderClient provider) {
        this.transactions = transactions;
        this.provider = provider;
    }

    @Transactional(propagation = Propagation.NEVER)
    public boolean processNext() {
        var claimed = transactions.claimNext();
        if (claimed.isEmpty()) {
            return false;
        }
        var work = claimed.get();
        String previous = MDC.get("traceId");
        MDC.put("traceId", work.correlationId());
        try {
            var outcome = provider.activate(work);
            boolean applied = transactions.finish(work, outcome);
            log.info("Activation attempt: requestId={}, attempt={}, outcome={}, applied={}",
                    work.id(), work.attemptCount(), outcome.kind(), applied);
        } finally {
            if (previous == null) {
                MDC.remove("traceId");
            } else {
                MDC.put("traceId", previous);
            }
        }
        return true;
    }
}
