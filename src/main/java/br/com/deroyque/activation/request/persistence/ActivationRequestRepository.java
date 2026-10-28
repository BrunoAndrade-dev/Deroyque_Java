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

public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID>,
        JpaSpecificationExecutor<ActivationRequest> {

    Optional<ActivationRequest> findByExternalId(String externalId);

    @Query(value = """
            SELECT * FROM activation_requests
            WHERE status = 'PENDING' AND next_attempt_at <= :now AND attempt_count < :maxAttempts
            ORDER BY next_attempt_at, created_at, id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ActivationRequest> findNextForProcessing(@Param("now") Instant now,
                                                     @Param("maxAttempts") int maxAttempts);

    @Query(value = "SELECT * FROM activation_requests WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<ActivationRequest> findForUpdate(@Param("id") UUID id);

    @Query(value = """
            SELECT * FROM activation_requests
            WHERE status = 'PROCESSING' AND processing_started_at <= :cutoff
            ORDER BY processing_started_at, id
            LIMIT 100 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ActivationRequest> findAbandonedForUpdate(@Param("cutoff") Instant cutoff);
}
