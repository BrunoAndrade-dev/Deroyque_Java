package br.com.deroyque.activation.request.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("activation.provider")
public record ProviderProperties(@DefaultValue("http://localhost:8081") URI baseUrl,
                                 @DefaultValue("2s") Duration connectTimeout,
                                 @DefaultValue("5s") Duration readTimeout) {

    public ProviderProperties {
        if (baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid activation provider configuration");
        }
    }
}
