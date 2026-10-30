import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Verifica a saúde em imagens JRE sem exigir curl ou outro pacote do sistema operacional. */
public final class Healthcheck {
    /**
     * Consulta o endpoint informado e encerra com código 0 apenas para HTTP 200; erros e demais status resultam em código 1.
     *
     * @param args primeiro argumento contendo a URL do health check.
     */
    public static void main(String[] args) {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var request = HttpRequest.newBuilder(URI.create(args[0]))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                System.exit(1);
            }
        } catch (Exception exception) {
            System.exit(1);
        }
    }
}
