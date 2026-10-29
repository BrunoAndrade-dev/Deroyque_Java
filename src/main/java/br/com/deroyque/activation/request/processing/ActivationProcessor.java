package br.com.deroyque.activation.request.processing;

import br.com.deroyque.activation.request.provider.ActivationProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executa uma tentativa com reserva e conclusão transacionais separadas. A integração HTTP ocorre sem
 * transação de banco e mantém a correlação da criação.
 */
@Component
public class ActivationProcessor {

    private static final Logger log = LoggerFactory.getLogger(ActivationProcessor.class);
    private final ProcessingTransactions transactions;
    private final ActivationProviderClient provider;

    /**
     * Recebe operações transacionais e cliente remoto mantidos separados.
     *
     * @param transactions operações transacionais do processador.
     * @param provider cliente que executa a integração HTTP fora da transação de banco.
     */
    public ActivationProcessor(ProcessingTransactions transactions, ActivationProviderClient provider) {
        this.transactions = transactions;
        this.provider = provider;
    }

    /**
     * Reserva um item, chama o provedor sem transação e conclui em nova transação, preservando e
     * restaurando o MDC.
     *
     * @return false quando não há trabalho elegível; true quando uma tentativa foi executada.
     */
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
