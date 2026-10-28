package br.com.deroyque.activation.request.provider;

import br.com.deroyque.activation.request.processing.WorkItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@ExtendWith(OutputCaptureExtension.class)
class ProviderCredentialsTest {

    private static final WireMockServer SERVER = new WireMockServer(0);
    static { SERVER.start(); }
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    @TempDir Path directory;
    private final WorkItem work = new WorkItem(UUID.randomUUID(), UUID.randomUUID(), "EXT-CREDENTIAL", "CUSTOMER",
            "PRODUCT", "provider-credential-test", 1);

    @BeforeEach void reset() {
        SERVER.resetAll();
        SERVER.stubFor(post(urlEqualTo("/provider/v1/activate")).willReturn(aResponse().withStatus(204)));
    }

    @AfterAll static void stop() { SERVER.stop(); HTTP.close(); }

    @Test
    void shouldReadRotatedSecretOnEveryCallWithoutLoggingIt(CapturedOutput output) throws Exception {
        Path secret = directory.resolve("provider.token");
        String first = "test-" + UUID.randomUUID();
        String second = "test-" + UUID.randomUUID();
        Files.writeString(secret, first + "\n");
        var client = client(secret);
        assertThat(client.activate(work).kind()).isEqualTo(ProviderOutcome.Kind.SUCCESS);
        var firstRequest = SERVER.getAllServeEvents().getFirst().getRequest();
        assertThat(firstRequest.getHeader("Authorization").equals("Bearer " + first)).isTrue();

        Path replacement = directory.resolve("replacement.token");
        Files.writeString(replacement, second);
        Files.move(replacement, secret, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        SERVER.resetRequests();
        assertThat(client.activate(work).kind()).isEqualTo(ProviderOutcome.Kind.SUCCESS);
        var secondRequest = SERVER.getAllServeEvents().getFirst().getRequest();
        assertThat(secondRequest.getHeader("Authorization").equals("Bearer " + second)).isTrue();
        assertThat(output.getAll().contains(first)).isFalse();
        assertThat(output.getAll().contains(second)).isFalse();
    }

    @Test
    void shouldNotSendUnauthenticatedRequestWhenConfiguredSecretIsMissing() {
        var result = client(directory.resolve("missing.token")).activate(work);
        assertThat(result.kind()).isEqualTo(ProviderOutcome.Kind.RETRYABLE_FAILURE);
        assertThat(result.errorCode()).isEqualTo("CREDENTIAL_UNAVAILABLE");
        assertThat(SERVER.getAllServeEvents()).isEmpty();
    }

    @Test
    void shouldRejectEmptySecretAndHeaderInjectionWithoutSendingRequest() throws Exception {
        Path secret = directory.resolve("provider.token");
        for (String content : new String[]{"", "   ", "token\r\nX-Injected: value", "x".repeat(8193)}) {
            Files.writeString(secret, content);
            assertThat(client(secret).activate(work).errorCode()).isEqualTo("CREDENTIAL_UNAVAILABLE");
        }
        assertThat(SERVER.getAllServeEvents()).isEmpty();
    }

    @Test
    void shouldKeepMockWithoutCredentialsWhenSecretFileIsNotConfigured() {
        assertThat(client(null).activate(work).kind()).isEqualTo(ProviderOutcome.Kind.SUCCESS);
        assertThat(SERVER.getAllServeEvents().getFirst().getRequest().containsHeader("Authorization")).isFalse();
    }

    @Test
    void shouldRejectCleartextExternalTransportForConfiguredCredential() {
        var properties = new ProviderProperties(URI.create("http://provider.example.test"),
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderCredentials(
                new ProviderCredentialProperties(directory.resolve("provider.token")), properties));
    }

    private ActivationProviderClient client(Path secret) {
        var properties = new ProviderProperties(URI.create(SERVER.baseUrl()), Duration.ofSeconds(1), Duration.ofSeconds(2));
        return new ActivationProviderClient(HTTP, new ObjectMapper(), properties, Clock.systemUTC(),
                new ProviderCredentials(new ProviderCredentialProperties(secret), properties));
    }
}
