package br.com.deroyque.activation.request.domain;

/**
 * Estados permitidos para uma solicitação. As transições são controladas pelos métodos do agregado.
 */
public enum RequestStatus {
    /** Solicitação aguardando a primeira tentativa ou uma retentativa elegível. */
    PENDING,
    /** Tentativa reservada e em andamento, sujeita à recuperação após abandono. */
    PROCESSING,
    /** Sucesso confirmado pelo provedor; estado terminal. */
    COMPLETED,
    /** Falha permanente ou orçamento esgotado; não prova ausência de efeito após timeout. */
    FAILED
}
