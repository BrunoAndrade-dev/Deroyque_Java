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

/**
 * Isola a inserção em uma nova transação. Em conflito, o rollback termina antes da consulta ao registro
 * vencedor pelo serviço.
 */
@Component
public class RequestCreationTransaction {

    private final ActivationRequestRepository repository;
    private final Clock clock;

    /**
     * Recebe persistência e relógio para criar registros com precisão consistente.
     *
     * @param repository repositório de solicitações.
     * @param clock relógio utilizado na avaliação temporal.
     */
    public RequestCreationTransaction(ActivationRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Insere e efetiva o flush em transação própria, usando UTC com precisão de microssegundos e
     * correlação persistida.
     *
     * @param externalId identificador global normalizado do pedido.
     * @param customerId identificador do cliente.
     * @param productCode código do produto solicitado.
     * @param ownerId identidade interna do consumidor validado.
     * @return solicitação persistida em PENDING.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ActivationRequest insert(String externalId, String customerId, String productCode, String ownerId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String correlationId = MDC.get("traceId");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        return repository.saveAndFlush(ActivationRequest.create(externalId, customerId, productCode, now,
                correlationId, ownerId));
    }
}
