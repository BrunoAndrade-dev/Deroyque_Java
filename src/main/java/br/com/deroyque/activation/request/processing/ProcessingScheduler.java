package br.com.deroyque.activation.request.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Agenda ciclos sequenciais de processamento e recuperação em threads separadas, com encerramento
 * coordenado.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "activation.processing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProcessingScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProcessingScheduler.class);
    private static final int MAX_REQUESTS_PER_CYCLE = 10;
    private final ActivationProcessor processor;
    private final ProcessingTransactions transactions;

    /**
     * Recebe os executores dos ciclos de processamento e recuperação.
     *
     * @param processor executor das tentativas.
     * @param transactions operações transacionais do processador.
     */
    public ProcessingScheduler(ActivationProcessor processor, ProcessingTransactions transactions) {
        this.processor = processor;
        this.transactions = transactions;
    }

    /**
     * Separa processamento e recuperação e concede prazo para concluir tarefas no desligamento.
     *
     * @return agendador com duas threads e espera de encerramento de 10 segundos.
     */
    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("activation-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    /**
     * Processa até dez pedidos sequencialmente; cada reserva ocorre imediatamente antes da chamada
     * remota.
     */
    @Scheduled(fixedDelayString = "${activation.processing.poll-interval:1s}")
    public void processPending() {
        try {
            for (int i = 0; i < MAX_REQUESTS_PER_CYCLE; i++) {
                if (!processor.processNext()) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            log.error("Processing cycle failed: type={}", exception.getClass().getSimpleName());
        }
    }

    /**
     * Reconcilia tentativas abandonadas e registra somente a quantidade recuperada ou o tipo da falha.
     */
    @Scheduled(fixedDelayString = "${activation.processing.recovery-interval:5s}")
    public void recover() {
        try {
            int count = transactions.recoverAbandoned();
            if (count > 0) {
                log.info("Recovered abandoned activation attempts: count={}", count);
            }
        } catch (RuntimeException exception) {
            log.error("Recovery cycle failed: type={}", exception.getClass().getSimpleName());
        }
    }
}
