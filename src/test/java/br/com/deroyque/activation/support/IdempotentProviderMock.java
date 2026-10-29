package br.com.deroyque.activation.support;

import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Extensão WireMock que simula idempotência e falhas por produto. Empacotada separadamente para o mock;
 * seu estado em memória é perdido ao reiniciar.
 */
public class IdempotentProviderMock implements ResponseDefinitionTransformerV2 {

    private final Map<String, Entry> requests = new HashMap<>();
    private final int timeoutDelayMillis;
    private int activationCount;

    /**
     * Configura o atraso da simulação; a construção padrão utiliza seis segundos.
     */
    public IdempotentProviderMock() {
        this(6000);
    }

    /**
     * Configura o atraso da simulação; a construção padrão utiliza seis segundos.
     *
     * @param timeoutDelayMillis atraso usado na simulação de timeout.
     */
    public IdempotentProviderMock(int timeoutDelayMillis) {
        this.timeoutDelayMillis = timeoutDelayMillis;
    }

    /**
     * Identifica o transformer referenciado pelos mappings do WireMock.
     *
     * @return nome idempotent-activation.
     */
    @Override
    public String getName() {
        return "idempotent-activation";
    }

    /**
     * Limita a transformação aos mappings que a declaram explicitamente.
     *
     * @return false.
     */
    @Override
    public boolean applyGlobally() {
        return false;
    }

    /**
     * Vincula conteúdo à chave e simula falhas por produto; uma ativação confirmada não é repetida.
     *
     * @param event chamada recebida pelo WireMock.
     * @return resposta simulada, incluindo conflito para conteúdo divergente.
     */
    @Override
    public synchronized ResponseDefinition transform(ServeEvent event) {
        String key = event.getRequest().getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            return response(400);
        }
        Map<?, ?> payload;
        try {
            payload = Json.read(event.getRequest().getBodyAsString(), Map.class);
        } catch (RuntimeException exception) {
            return response(400);
        }
        if (payload == null || !(payload.get("productCode") instanceof String product)) {
            return response(400);
        }
        Entry entry = requests.get(key);
        if (entry == null) {
            entry = new Entry(payload);
            requests.put(key, entry);
        } else if (!entry.payload.equals(payload)) {
            return response(409);
        }
        entry.calls++;
        if (entry.activated) {
            return response(204);
        }
        if (product.equals("PERMANENT_FAILURE")) {
            return response(400);
        }
        if (product.equals("TEMPORARY_FAILURE") && entry.calls < 3) {
            return response(500);
        }
        if (product.equals("RATE_LIMITED") && entry.calls == 1) {
            return ResponseDefinitionBuilder.responseDefinition().withStatus(429)
                    .withHeader("Retry-After", "2").build();
        }
        entry.activated = true;
        activationCount++;
        if (product.equals("TIMEOUT_AFTER_ACTIVATION") && entry.calls == 1) {
            return ResponseDefinitionBuilder.responseDefinition().withStatus(204)
                    .withFixedDelay(timeoutDelayMillis).build();
        }
        return response(204);
    }

    /**
     * Conta efeitos de ativação, separadamente do número de chamadas recebidas.
     *
     * @return quantidade de ativações únicas na memória do mock.
     */
    public synchronized int getActivationCount() {
        return activationCount;
    }

    /**
     * Limpa chaves, contadores e efeitos simulados entre cenários.
     */
    public synchronized void reset() {
        requests.clear();
        activationCount = 0;
    }

    /**
     * Cria uma resposta simulada sem corpo.
     *
     * @param status código HTTP da resposta simulada.
     * @return resposta com o status solicitado.
     */
    private ResponseDefinition response(int status) {
        return ResponseDefinitionBuilder.responseDefinition().withStatus(status).build();
    }

    /**
     * Conteúdo e contadores mantidos em memória para uma chave de idempotência do mock.
     */
    private static class Entry {
        private final Map<?, ?> payload;
        private int calls;
        private boolean activated;

        /**
         * Vincula o conteúdo da primeira chamada à chave do mock.
         *
         * @param payload conteúdo vinculado à chave de idempotência.
         */
        private Entry(Map<?, ?> payload) {
            this.payload = payload;
        }
    }
}
