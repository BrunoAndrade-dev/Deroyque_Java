package br.com.deroyque.activation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Inicializa o serviço de solicitações de ativação e sua configuração Spring Boot.
 */
@SpringBootApplication
public class ActivationApplication {

    /**
     * Inicializa o contexto Spring e aceita argumentos de configuração do processo.
     *
     * @param args argumentos da linha de comando.
     */
    public static void main(String[] args) {
        SpringApplication.run(ActivationApplication.class, args);
    }
}
