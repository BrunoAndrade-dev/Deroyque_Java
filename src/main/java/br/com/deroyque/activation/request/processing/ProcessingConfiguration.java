package br.com.deroyque.activation.request.processing;

import br.com.deroyque.activation.request.provider.ProviderCredentialProperties;
import br.com.deroyque.activation.request.provider.ProviderProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ProcessingProperties.class, ProviderProperties.class, ProviderCredentialProperties.class})
public class ProcessingConfiguration {

    @Bean
    HttpClient providerHttpClient(ProviderProperties provider, ProcessingProperties processing) {
        if (processing.processingTimeout().compareTo(provider.connectTimeout().plus(provider.readTimeout())) <= 0) {
            throw new IllegalArgumentException("Processing timeout must exceed provider connection and read timeouts");
        }
        return HttpClient.newBuilder().connectTimeout(provider.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
