package br.com.deroyque.activation.demo;

import br.com.deroyque.activation.support.TestTokens;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.IntSupplier;

/** Loopback-only token helper. Never included in the production application. */
final class DemoTokenIssuer implements AutoCloseable {
    private static final Map<String, Consumer> CONSUMERS = Map.of(
            "a-full", new Consumer("demo-consumer-a", "requests:read requests:write"),
            "a-read", new Consumer("demo-consumer-a", "requests:read"),
            "a-write", new Consumer("demo-consumer-a", "requests:write"),
            "b-full", new Consumer("demo-consumer-b", "requests:read requests:write"));

    private final RSAKey key = TestTokens.newKey();
    private final HttpServer server;

    DemoTokenIssuer(IntSupplier applicationPort) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, applicationPort));
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange, IntSupplier applicationPort) throws IOException {
        try (exchange) {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("Pragma", "no-cache");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; "
                    + "style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");
            if (!("127.0.0.1:" + server.getAddress().getPort()).equals(exchange.getRequestHeaders().getFirst("Host"))) {
                send(exchange, 403, "text/plain", "Host não permitido.");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                send(exchange, 200, "text/html", page(applicationPort.getAsInt()));
            } else if ("/jwks".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                send(exchange, 200, "application/json", TestTokens.jwks(key));
            } else if ("/token".equals(path)) {
                issue(exchange);
            } else {
                send(exchange, 404, "text/plain", "Recurso inexistente.");
            }
        }
    }

    private void issue(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            send(exchange, 405, "text/plain", "Use a página local para gerar tokens.");
            return;
        }
        if (!baseUrl().equals(exchange.getRequestHeaders().getFirst("Origin"))) {
            send(exchange, 403, "text/plain", "Origem não permitida.");
            return;
        }
        byte[] input = exchange.getRequestBody().readNBytes(65);
        Consumer consumer = input.length <= 64
                ? CONSUMERS.get(new String(input, StandardCharsets.UTF_8)) : null;
        if (consumer == null) {
            send(exchange, 400, "text/plain", "Selecione um consumidor válido.");
            return;
        }
        String token = TestTokens.token(consumer.scope(), builder -> builder.subject(consumer.subject()),
                key, JWSAlgorithm.RS256);
        send(exchange, 200, "text/plain", token);
    }

    private void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private String page(int port) {
        return """
                <!doctype html><html lang="pt-BR"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Demonstração local — Deroyque</title>
                <style>body{font:18px system-ui;max-width:760px;margin:48px auto;padding:0 24px}
                button,select,textarea{font:inherit;margin:8px 0;padding:10px}textarea{width:95%%}
                button{cursor:pointer}p{line-height:1.6}</style>
                <h1>Tokens temporários de demonstração</h1>
                <p>Escolha o consumidor, gere o token e copie somente seu valor para
                <strong>Authorize</strong> no <a href="http://127.0.0.1:%d/swagger-ui.html"
                target="_blank" rel="noopener noreferrer">Swagger desta demonstração</a>.</p>
                <label for="consumer">Consumidor e permissões</label><br>
                <select id="consumer">
                  <option value="a-full">Consumidor A — leitura e escrita</option>
                  <option value="a-read">Consumidor A — somente leitura</option>
                  <option value="a-write">Consumidor A — somente escrita</option>
                  <option value="b-full">Consumidor B — leitura e escrita</option>
                </select><br>
                <button id="generate" type="button">Gerar token</button>
                <button id="copy" type="button" disabled>Copiar token</button><br>
                <label for="token">Token de teste (válido por 5 minutos)</label><br>
                <textarea id="token" rows="5" readonly autocomplete="off" spellcheck="false"></textarea>
                <p id="message" role="status" aria-live="polite"></p>
                <p>A API mantém as mesmas regras de JWT, escopos e isolamento.
                Os dados pertencem a um banco temporário e são apagados ao encerrar.
                Não use dados reais nem compartilhe o token. Recarregar esta página limpa o campo.</p>
                <script>
                const token = document.getElementById('token');
                const message = document.getElementById('message');
                const copy = document.getElementById('copy');
                document.getElementById('consumer').onchange = () => {
                  token.value = ''; copy.disabled = true; message.textContent = '';
                };
                document.getElementById('generate').onclick = async () => {
                  token.value = ''; copy.disabled = true;
                  try {
                    const response = await fetch('/token', {method: 'POST', cache: 'no-store',
                      headers: {'Content-Type': 'text/plain'},
                      body: document.getElementById('consumer').value});
                    if (!response.ok) throw new Error();
                    token.value = await response.text(); copy.disabled = false;
                    message.textContent = 'Token gerado. Copie para Authorize, sem o prefixo Bearer.';
                  } catch { message.textContent = 'Não foi possível gerar o token. Tente novamente.'; }
                };
                copy.onclick = async () => {
                  try { await navigator.clipboard.writeText(token.value);
                    message.textContent = 'Token copiado. Cole em Authorize no Swagger.';
                  } catch { token.focus(); token.select();
                    message.textContent = 'Copie o texto selecionado com Ctrl+C.'; }
                };
                </script></html>
                """.formatted(port);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private record Consumer(String subject, String scope) { }
}
