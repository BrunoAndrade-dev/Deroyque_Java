package br.com.deroyque.activation.support;

import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import java.util.HashMap;
import java.util.Map;

/** Development-only WireMock extension, also packaged separately for the standalone mock. */
public class IdempotentProviderMock implements ResponseDefinitionTransformerV2 {

    private final Map<String, Entry> requests = new HashMap<>();
    private final int timeoutDelayMillis;
    private int activationCount;

    public IdempotentProviderMock() {
        this(6000);
    }

    public IdempotentProviderMock(int timeoutDelayMillis) {
        this.timeoutDelayMillis = timeoutDelayMillis;
    }

    @Override
    public String getName() {
        return "idempotent-activation";
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }

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

    public synchronized int getActivationCount() {
        return activationCount;
    }

    public synchronized void reset() {
        requests.clear();
        activationCount = 0;
    }

    private ResponseDefinition response(int status) {
        return ResponseDefinitionBuilder.responseDefinition().withStatus(status).build();
    }

    private static class Entry {
        private final Map<?, ?> payload;
        private int calls;
        private boolean activated;

        private Entry(Map<?, ?> payload) {
            this.payload = payload;
        }
    }
}
