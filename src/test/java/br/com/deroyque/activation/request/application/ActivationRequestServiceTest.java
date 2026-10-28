package br.com.deroyque.activation.request.application;

import br.com.deroyque.activation.request.domain.ActivationRequest;
import br.com.deroyque.activation.request.persistence.ActivationRequestRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import br.com.deroyque.activation.security.CurrentConsumer;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationRequestServiceTest {

    @Mock
    private ActivationRequestRepository repository;

    @Mock
    private RequestCreationTransaction creationTransaction;

    @Mock
    private CurrentConsumer consumer;

    @BeforeEach
    void authenticateConsumer() {
        when(consumer.ownerId()).thenReturn("a".repeat(64));
    }

    @InjectMocks
    private ActivationRequestService service;

    @Test
    void shouldReadWinningInsertAfterUniqueConstraintFailure() {
        var winner = ActivationRequest.create("EXT", "CUSTOMER", "PRODUCT", Instant.now(), "a".repeat(64));
        when(repository.findByExternalId("EXT")).thenReturn(Optional.empty(), Optional.of(winner));
        when(creationTransaction.insert("EXT", "CUSTOMER", "PRODUCT", "a".repeat(64)))
                .thenThrow(violation("uk_activation_requests_external_id"));

        var result = service.create("EXT", "CUSTOMER", "PRODUCT");

        assertThat(result.created()).isFalse();
        assertThat(result.request().getId()).isEqualTo(winner.getId());
    }

    @Test
    void shouldRejectDifferentContentAfterConcurrentInsertWins() {
        var winner = ActivationRequest.create("EXT", "OTHER-CUSTOMER", "PRODUCT", Instant.now(), "a".repeat(64));
        when(repository.findByExternalId("EXT")).thenReturn(Optional.empty(), Optional.of(winner));
        when(creationTransaction.insert("EXT", "CUSTOMER", "PRODUCT", "a".repeat(64)))
                .thenThrow(violation("uk_activation_requests_external_id"));

        assertThatThrownBy(() -> service.create("EXT", "CUSTOMER", "PRODUCT"))
                .isInstanceOf(RequestConflictException.class);
    }

    @Test
    void shouldNotTreatUnrelatedIntegrityFailureAsDuplicateExternalId() {
        var failure = violation("different_constraint");
        when(repository.findByExternalId("EXT")).thenReturn(Optional.empty());
        when(creationTransaction.insert("EXT", "CUSTOMER", "PRODUCT", "a".repeat(64))).thenThrow(failure);

        assertThatThrownBy(() -> service.create("EXT", "CUSTOMER", "PRODUCT")).isSameAs(failure);
    }

    private DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("Insert rejected",
                new ConstraintViolationException("Constraint rejected", new SQLException(), constraint));
    }
}
