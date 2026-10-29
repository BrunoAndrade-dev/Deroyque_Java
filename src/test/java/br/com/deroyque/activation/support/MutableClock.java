package br.com.deroyque.activation.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Relógio controlável e thread-safe para testar backoff e recuperação sem depender da passagem do tempo
 * real.
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> current = new AtomicReference<>(Instant.EPOCH);

    /**
     * Atualiza atomicamente o instante usado pelos testes.
     *
     * @param instant instante controlado pelo teste.
     */
    public void set(Instant instant) {
        current.set(instant);
    }

    /**
     * Mantém os cenários de negócio em UTC.
     *
     * @return fuso UTC.
     */
    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /**
     * Cria um snapshot fixo do instante atual no fuso solicitado.
     *
     * @param zone fuso do snapshot desejado.
     * @return relógio fixo; não acompanha mudanças posteriores deste relógio.
     */
    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    /**
     * Obtém o instante atualmente definido pelo teste.
     *
     * @return instante controlado, inicialmente a época Unix.
     */
    @Override
    public Instant instant() {
        return current.get();
    }
}
