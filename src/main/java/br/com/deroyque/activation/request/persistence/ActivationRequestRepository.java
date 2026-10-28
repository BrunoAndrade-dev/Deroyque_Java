package br.com.deroyque.activation.request.persistence;

import br.com.deroyque.activation.request.domain.ActivationRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID> {

    Optional<ActivationRequest> findByExternalId(String externalId);
}
