package br.com.deroyque.activation.request.processing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Política de tentativas, backoff e detecção de processamento abandonado.
 *
 * @param maxAttempts total de tentativas permitido, de 1 a 10; padrão 3.
 * @param retryDelay base positiva do backoff exponencial; padrão 1 segundo.
 * @param processingTimeout prazo positivo para recuperar tentativas abandonadas; padrão 60 segundos.
 */
@ConfigurationProperties("activation.processing")
public record ProcessingProperties(@DefaultValue("3") int maxAttempts,
                                   @DefaultValue("1s") Duration retryDelay,
                                   @DefaultValue("60s") Duration processingTimeout) {

    /**
     * Rejeita orçamento fora de 1 a 10 tentativas e durações não positivas.
     *
     * @param maxAttempts total de tentativas permitido, de 1 a 10; padrão 3.
     * @param retryDelay base positiva do backoff exponencial; padrão 1 segundo.
     * @param processingTimeout prazo positivo para recuperar tentativas abandonadas; padrão 60
     *     segundos.
     * @throws IllegalArgumentException se os valores informados violarem o contrato descrito.
     */
    public ProcessingProperties {
        if (maxAttempts < 1 || maxAttempts > 10 || retryDelay.isNegative() || retryDelay.isZero()
                || processingTimeout.isNegative() || processingTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid activation processing configuration");
        }
    }
}
