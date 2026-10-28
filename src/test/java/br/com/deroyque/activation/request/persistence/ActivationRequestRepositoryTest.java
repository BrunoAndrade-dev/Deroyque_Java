package br.com.deroyque.activation.request.persistence;

import br.com.deroyque.activation.request.domain.ActivationRequest;
import br.com.deroyque.activation.request.domain.RequestStatus;
import br.com.deroyque.activation.support.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class ActivationRequestRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    @Autowired
    private ActivationRequestRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void shouldPersistAndReadRequestFromPostgres() {
        var request = newRequest("EXT-1");
        repository.saveAndFlush(request);

        var stored = repository.findByExternalId("EXT-1").orElseThrow();

        assertThat(stored.getId()).isEqualTo(request.getId());
        assertThat(stored.getCustomerId()).isEqualTo("CUSTOMER-1");
        assertThat(stored.getProductCode()).isEqualTo("INTERNET-500");
        assertThat(stored.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(stored.getCreatedAt()).isEqualTo(NOW);
        assertThat(stored.getUpdatedAt()).isEqualTo(NOW);
        assertThat(stored.getVersion()).isZero();
    }

    @Test
    void shouldRejectDuplicateExternalIdWithoutLosingOriginalRequest() {
        var original = repository.saveAndFlush(newRequest("EXT-DUPLICATE"));

        assertThatThrownBy(() -> repository.saveAndFlush(newRequest("EXT-DUPLICATE")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_activation_requests_external_id");

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByExternalId("EXT-DUPLICATE").orElseThrow().getId())
                .isEqualTo(original.getId());
    }

    @Test
    void shouldPersistExactlyOneRequestWhenFourConcurrentInsertsUseSameExternalId() throws Exception {
        int workers = 4;
        var barrier = new CyclicBarrier(workers);
        List<Future<Boolean>> results = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int i = 0; i < workers; i++) {
                results.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        repository.saveAndFlush(newRequest("EXT-CONCURRENT"));
                        return true;
                    } catch (DataIntegrityViolationException exception) {
                        assertThat(exception.getMostSpecificCause().getMessage())
                                .contains("uk_activation_requests_external_id");
                        return false;
                    }
                }));
            }

            int successfulInserts = 0;
            for (var result : results) {
                if (result.get(20, TimeUnit.SECONDS)) {
                    successfulInserts++;
                }
            }
            assertThat(successfulInserts).isEqualTo(1);
        }

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByExternalId("EXT-CONCURRENT")).isPresent();
    }

    @Test
    void shouldRejectStaleUpdateUsingOptimisticLock() {
        var original = repository.saveAndFlush(newRequest("EXT-VERSION"));
        var firstReader = repository.findById(original.getId()).orElseThrow();
        var staleReader = repository.findById(original.getId()).orElseThrow();

        firstReader.startProcessing(NOW.plusSeconds(1));
        repository.saveAndFlush(firstReader);
        staleReader.startProcessing(NOW.plusSeconds(2));

        assertThatThrownBy(() -> repository.saveAndFlush(staleReader))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        var stored = repository.findById(original.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(stored.getVersion()).isEqualTo(1);
        assertThat(stored.getCreatedAt()).isEqualTo(NOW);
        assertThat(stored.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    private ActivationRequest newRequest(String externalId) {
        return ActivationRequest.create(externalId, "CUSTOMER-1", "INTERNET-500", NOW);
    }
}
