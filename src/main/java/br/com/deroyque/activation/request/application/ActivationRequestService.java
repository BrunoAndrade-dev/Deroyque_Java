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

/**
 * Coordena idempotência de entrada e consultas isoladas por consumidor. A restrição única do banco é a
 * garantia definitiva contra inserções concorrentes.
 */
@Service
public class ActivationRequestService {

    private static final String EXTERNAL_ID_CONSTRAINT = "uk_activation_requests_external_id";

    private final ActivationRequestRepository repository;
    private final RequestCreationTransaction creationTransaction;
    private final CurrentConsumer consumer;

    /**
     * Recebe persistência, inserção isolada e identidade do consumidor.
     *
     * @param repository repositório de solicitações.
     * @param creationTransaction componente de inserção em transação própria.
     * @param consumer acesso à identidade autenticada.
     */
    public ActivationRequestService(ActivationRequestRepository repository,
                                    RequestCreationTransaction creationTransaction, CurrentConsumer consumer) {
        this.repository = repository;
        this.creationTransaction = creationTransaction;
        this.consumer = consumer;
    }

    /**
     * Cria ou resolve um retry do consumidor atual. A colisão na restrição única é resolvida após o
     * rollback da inserção.
     *
     * @param externalId identificador global normalizado do pedido.
     * @param customerId identificador do cliente.
     * @param productCode código do produto solicitado.
     * @return registro e indicação de criação efetiva.
     * @throws RequestConflictException se o dono ou conteúdo divergir do registro existente.
     */
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
            // A transação de inserção já sofreu rollback antes desta consulta.
            var winner = repository.findByExternalId(externalId).orElseThrow(() -> exception);
            return resolveRetry(winner, customerId, productCode, ownerId);
        }
    }

    /**
     * Restringe a busca ao dono extraído do JWT validado.
     *
     * @param id UUID interno da solicitação.
     * @return pedido pertencente ao consumidor atual.
     * @throws RequestNotFoundException se o pedido não existir ou pertencer a outro consumidor.
     */
    @Transactional(readOnly = true)
    public ActivationRequest get(UUID id) {
        return repository.findByIdAndOwnerId(id, consumer.ownerId()).orElseThrow(RequestNotFoundException::new);
    }

    /**
     * Combina obrigatoriamente o dono com filtros opcionais e ordenação estável.
     *
     * @param status estado desejado, ou null para não filtrar.
     * @param customerId identificador do cliente.
     * @param page índice da página, iniciado em zero.
     * @param size tamanho da página, de 1 a 100.
     * @return página ordenada por criação e UUID decrescentes.
     */
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

    /**
     * Aceita repetição somente quando dono e conteúdo correspondem ao registro existente.
     *
     * @param existing registro encontrado para o identificador global.
     * @param customerId identificador do cliente.
     * @param productCode código do produto solicitado.
     * @param ownerId identidade interna do consumidor validado.
     * @return resultado de repetição sem nova inserção.
     * @throws RequestConflictException se a repetição não pertencer ao mesmo consumidor e conteúdo.
     */
    private CreationResult resolveRetry(ActivationRequest existing, String customerId, String productCode, String ownerId) {
        if (!existing.getOwnerId().equals(ownerId) || !existing.getCustomerId().equals(customerId)
                || !existing.getProductCode().equals(productCode)) {
            throw new RequestConflictException();
        }
        return new CreationResult(existing, false);
    }

    /**
     * Inspeciona a cadeia de causas para distinguir a restrição de externalId de outras falhas de
     * integridade.
     *
     * @param exception falha recebida, cujo conteúdo não deve ser exposto ao consumidor.
     * @return true somente para a restrição única esperada.
     */
    private boolean isExternalIdConflict(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && EXTERNAL_ID_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Distingue uma criação efetiva de uma repetição idêntica para definir a resposta HTTP.
     *
     * @param request registro novo ou previamente existente.
     * @param created true apenas quando esta chamada realizou a inserção.
     */
    public record CreationResult(ActivationRequest request, boolean created) {
    }
}
