package br.com.deroyque.activation.request.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Endereço e timeouts do provedor. Redirecionamentos não são seguidos pelo cliente configurado.
 *
 * @param baseUrl URI HTTP ou HTTPS do provedor, sem credenciais embutidas.
 * @param connectTimeout timeout positivo de conexão; padrão 2 segundos.
 * @param readTimeout timeout positivo para obter a resposta; padrão 5 segundos.
 */
@ConfigurationProperties("activation.provider")
public record ProviderProperties(@DefaultValue("http://localhost:8081") URI baseUrl,
                                 @DefaultValue("2s") Duration connectTimeout,
                                 @DefaultValue("5s") Duration readTimeout) {

    /**
     * Valida URI sem credenciais embutidas e durações positivas para o cliente HTTP.
     *
     * @param baseUrl URI HTTP ou HTTPS do provedor, sem credenciais embutidas.
     * @param connectTimeout timeout positivo de conexão; padrão 2 segundos.
     * @param readTimeout timeout positivo para obter a resposta; padrão 5 segundos.
     * @throws IllegalArgumentException se os valores informados violarem o contrato descrito.
     */
    public ProviderProperties {
        if (baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid activation provider configuration");
        }
    }
}
