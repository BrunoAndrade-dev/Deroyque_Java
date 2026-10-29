package br.com.deroyque.activation.request.persistence;

import br.com.deroyque.activation.request.domain.ActivationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistência e consultas da ativação. Consultas HTTP devem aplicar o dono; consultas do processador
 * usam locks dentro de transações curtas.
 */
public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID>,
        JpaSpecificationExecutor<ActivationRequest> {

    /**
     * Localiza o identificador global para reconhecer retries. O chamador deve verificar o dono antes
     * de expor o registro.
     *
     * @param externalId identificador global normalizado do pedido.
     * @return registro com o identificador, se existente.
     */
    Optional<ActivationRequest> findByExternalId(String externalId);

    /**
     * Restringe a consulta individual ao consumidor autorizado.
     *
     * @param id UUID interno da solicitação.
     * @param ownerId identidade interna do consumidor validado.
     * @return registro do dono informado, ou vazio.
     */
    Optional<ActivationRequest> findByIdAndOwnerId(UUID id, String ownerId);

    /**
     * Seleciona e bloqueia um pedido devido com tentativas disponíveis, ignorando locks concorrentes.
     * Requer transação ativa.
     *
     * @param now instante de elegibilidade da consulta.
     * @param maxAttempts total permitido de tentativas.
     * @return primeiro pedido elegível, ou vazio.
     */
    @Query(value = """
            SELECT * FROM activation_requests
            WHERE status = 'PENDING' AND next_attempt_at <= :now AND attempt_count < :maxAttempts
            ORDER BY next_attempt_at, created_at, id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ActivationRequest> findNextForProcessing(@Param("now") Instant now,
                                                     @Param("maxAttempts") int maxAttempts);

    /**
     * Obtém lock de escrita para aplicar um resultado com verificação do token de tentativa. Requer
     * transação ativa.
     *
     * @param id UUID interno da solicitação.
     * @return registro bloqueado, se existente.
     */
    @Query(value = "SELECT * FROM activation_requests WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<ActivationRequest> findForUpdate(@Param("id") UUID id);

    /**
     * Bloqueia até 100 tentativas abandonadas, ignorando registros bloqueados por outra instância.
     *
     * @param cutoff instante de corte para considerar a tentativa abandonada.
     * @return pedidos PROCESSING iniciados até o instante de corte.
     */
    @Query(value = """
            SELECT * FROM activation_requests
            WHERE status = 'PROCESSING' AND processing_started_at <= :cutoff
            ORDER BY processing_started_at, id
            LIMIT 100 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ActivationRequest> findAbandonedForUpdate(@Param("cutoff") Instant cutoff);
}
