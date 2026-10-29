package br.com.deroyque.activation.request.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Localização externa da credencial do provedor. A ausência de arquivo permite o mock local sem
 * autenticação.
 *
 * @param tokenFile caminho do arquivo de segredo; null indica ausência de credencial.
 */
@ConfigurationProperties("activation.provider.authentication")
public record ProviderCredentialProperties(Path tokenFile) {
}
