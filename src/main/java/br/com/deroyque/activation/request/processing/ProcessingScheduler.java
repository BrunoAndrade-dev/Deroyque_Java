package br.com.deroyque.activation.request.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "activation.processing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProcessingScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProcessingScheduler.class);
    private final ActivationProcessor processor;
    private final ProcessingTransactions transactions;

    public ProcessingScheduler(ActivationProcessor processor, ProcessingTransactions transactions) {
        this.processor = processor;
        this.transactions = transactions;
    }

    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("activation-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    @Scheduled(fixedDelayString = "${activation.processing.poll-interval:1s}")
    public void processPending() {
        try {
            for (int i = 0; i < 10 && processor.processNext(); i++) {
                // Each item is claimed just before its HTTP call, not as a long-lived batch reservation.
            }
        } catch (RuntimeException exception) {
            log.error("Processing cycle failed: type={}", exception.getClass().getSimpleName());
        }
    }

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
