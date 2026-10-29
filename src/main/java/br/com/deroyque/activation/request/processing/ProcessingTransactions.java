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

/**
 * Executa reserva, conclusão e recuperação em transações próprias. O token da tentativa impede a
 * aplicação de resultados antigos.
 */
@Component
public class ProcessingTransactions {

    private final ActivationRequestRepository repository;
    private final ProcessingProperties properties;
    private final Clock clock;

    /**
     * Recebe persistência, política e relógio para coordenar tentativas duráveis.
     *
     * @param repository repositório de solicitações.
     * @param properties configuração validada do componente.
     * @param clock relógio utilizado na avaliação temporal.
     */
    public ProcessingTransactions(ActivationRequestRepository repository, ProcessingProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Reserva um pedido elegível em transação própria e devolve um snapshot da tentativa.
     *
     * @return trabalho reservado, ou vazio quando não há pedido elegível.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<WorkItem> claimNext() {
        Instant now = now();
        return repository.findNextForProcessing(now, properties.maxAttempts()).map(request -> {
            request.startProcessing(now);
            return WorkItem.from(request);
        });
    }

    /**
     * Confere estado e token sob lock antes de aplicar o resultado em transação própria.
     *
     * @param work snapshot da tentativa reservada.
     * @param outcome resultado sanitizado da integração.
     * @return true se o resultado foi aplicado; false para registro ausente ou tentativa antiga.
     */
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

    /**
     * Reagenda ou encerra tentativas que excederam o prazo, respeitando o orçamento já consumido.
     *
     * @return quantidade de tentativas reconciliadas neste ciclo.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverAbandoned() {
        Instant now = now();
        var abandoned = repository.findAbandonedForUpdate(now.minus(properties.processingTimeout()));
        for (var request : abandoned) {
            request.retryOrFail("PROCESSING_EXPIRED", retryAt(request, now, null), properties.maxAttempts(), now);
        }
        return abandoned.size();
    }

    /**
     * Calcula backoff exponencial e respeita o maior prazo entre política local e Retry-After.
     *
     * @param request entidade da solicitação utilizada nesta operação.
     * @param now instante da operação em UTC, não anterior à última alteração.
     * @param retryAfter instante mínimo solicitado pelo provedor, ou null.
     * @return instante mínimo para a próxima tentativa.
     */
    private Instant retryAt(ActivationRequest request, Instant now, Instant retryAfter) {
        var backoff = properties.retryDelay().multipliedBy(1L << (request.getAttemptCount() - 1));
        Instant scheduled = now.plus(backoff);
        return retryAfter != null && retryAfter.isAfter(scheduled) ? retryAfter : scheduled;
    }

    /**
     * Obtém UTC com a precisão utilizada pelo PostgreSQL.
     *
     * @return instante truncado em microssegundos.
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
