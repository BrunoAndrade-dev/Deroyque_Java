package br.com.deroyque.activation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Disponibiliza o relógio UTC injetável utilizado pelas regras de negócio.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    /**
     * Cria o relógio UTC compartilhado pelas regras temporais.
     *
     * @return relógio de sistema em UTC, substituível nos testes.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
