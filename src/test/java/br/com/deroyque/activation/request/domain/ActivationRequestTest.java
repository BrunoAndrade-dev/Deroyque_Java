package br.com.deroyque.activation.request.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ActivationRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    @Test
    void shouldCreatePendingRequestAndStripIdentifiersWithoutChangingCase() {
        var request = ActivationRequest.create("  Ext-123  ", " Customer-1 ", " Internet-500 ", NOW);

        assertThat(request.getId()).isNotNull();
        assertThat(request.getExternalId()).isEqualTo("Ext-123");
        assertThat(request.getCustomerId()).isEqualTo("Customer-1");
        assertThat(request.getProductCode()).isEqualTo("Internet-500");
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getCreatedAt()).isEqualTo(NOW);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldAcceptIdentifiersAtBothLengthBoundaries() {
        var shortest = ActivationRequest.create("a", "b", "c", NOW);
        var longest = ActivationRequest.create("a".repeat(100), "b".repeat(100), "c".repeat(100), NOW);

        assertThat(shortest.getExternalId()).hasSize(1);
        assertThat(longest.getExternalId()).hasSize(100);
        assertThat(longest.getCustomerId()).hasSize(100);
        assertThat(longest.getProductCode()).hasSize(100);
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void shouldRejectInvalidIdentifierInEveryField(String externalId, String customerId, String productCode) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ActivationRequest.create(externalId, customerId, productCode, NOW));
    }

    static Stream<Arguments> invalidIdentifiers() {
        return Stream.of(null, "", " \t\n ", "\u2003", "x".repeat(101))
                .flatMap(invalid -> Stream.of(
                        Arguments.of(invalid, "CUSTOMER", "PRODUCT"),
                        Arguments.of("EXTERNAL", invalid, "PRODUCT"),
                        Arguments.of("EXTERNAL", "CUSTOMER", invalid)));
    }

    @Test
    void shouldCompleteProcessingAndPreserveCreationTime() {
        var request = newRequest();
        request.startProcessing(NOW.plusSeconds(1));
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);

        request.complete(NOW.plusSeconds(2));

        assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(request.getCreatedAt()).isEqualTo(NOW);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"COMPLETED", "FAILED"})
    void shouldRejectEveryTransitionFromTerminalState(RequestStatus terminalState) {
        var request = newRequest();
        request.startProcessing(NOW.plusSeconds(1));
        if (terminalState == RequestStatus.COMPLETED) {
            request.complete(NOW.plusSeconds(2));
        } else {
            request.fail(NOW.plusSeconds(2));
        }

        assertThatIllegalStateException().isThrownBy(() -> request.startProcessing(NOW.plusSeconds(3)));
        assertThatIllegalStateException().isThrownBy(() -> request.complete(NOW.plusSeconds(3)));
        assertThatIllegalStateException().isThrownBy(() -> request.fail(NOW.plusSeconds(3)));
        assertThat(request.getStatus()).isEqualTo(terminalState);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void shouldRequireProcessingBeforeCompletionOrFailure() {
        var request = newRequest();

        assertThatIllegalStateException().isThrownBy(() -> request.complete(NOW));
        assertThatIllegalStateException().isThrownBy(() -> request.fail(NOW));
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
    }

    @Test
    void shouldRejectStartingAnAlreadyProcessingRequest() {
        var request = newRequest();
        request.startProcessing(NOW);

        assertThatIllegalStateException().isThrownBy(() -> request.startProcessing(NOW));
    }

    @Test
    void shouldRejectTransitionWithOlderTimestampWithoutChangingState() {
        var request = newRequest();

        assertThatIllegalArgumentException().isThrownBy(() -> request.startProcessing(NOW.minusSeconds(1)));
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW);
    }

    private ActivationRequest newRequest() {
        return ActivationRequest.create("EXT-123", "CUSTOMER-1", "INTERNET-500", NOW);
    }

    @Test
    void shouldScheduleRetryAndCreateANewAttemptTokenOnlyWhenDue() {
        var request = newRequest();
        request.startProcessing(NOW);
        var oldToken = request.getProcessingToken();
        request.retryOrFail("HTTP_500", NOW.plusSeconds(1), 3, NOW);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getAttemptCount()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(request.getProcessingToken()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> request.startProcessing(NOW));

        request.startProcessing(NOW.plusSeconds(1));
        assertThat(request.getAttemptCount()).isEqualTo(2);
        assertThat(request.getProcessingToken()).isNotEqualTo(oldToken);
        assertThat(request.getProcessingStartedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void shouldStopAfterThreeAttempts() {
        var request = newRequest();
        for (int attempt = 0; attempt < 3; attempt++) {
            var now = NOW.plusSeconds(attempt);
            request.startProcessing(now);
            request.retryOrFail("TIMEOUT", now.plusSeconds(1), 3, now);
        }

        assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(request.getAttemptCount()).isEqualTo(3);
        assertThat(request.getLastError()).isEqualTo("TIMEOUT");
        assertThat(request.getNextAttemptAt()).isNull();
        assertThat(request.getProcessingStartedAt()).isNull();
    }

    @Test
    void shouldRejectUnsanitizedErrorWithoutChangingState() {
        var request = newRequest();
        request.startProcessing(NOW);

        assertThatIllegalArgumentException().isThrownBy(() -> request.fail("raw provider body", NOW));
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(request.getLastError()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"COMPLETED", "FAILED"})
    void shouldNotRetryTerminalRequest(RequestStatus terminalState) {
        var request = newRequest();
        request.startProcessing(NOW);
        if (terminalState == RequestStatus.COMPLETED) {
            request.complete(NOW);
        } else {
            request.fail(NOW);
        }
        assertThatIllegalStateException().isThrownBy(() ->
                request.retryOrFail("TIMEOUT", NOW.plusSeconds(1), 3, NOW));
    }
}
