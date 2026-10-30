package br.com.deroyque.activation.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Propaga um identificador de correlação seguro pelo header, request e MDC, restaurando o contexto
 * anterior ao terminar a chamada.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Header de entrada e saída usado na propagação de correlação. */
    public static final String HEADER = "X-Correlation-ID";
    /** Nome compartilhado pelo atributo da requisição e pela chave do MDC. */
    public static final String ATTRIBUTE = "traceId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    /**
     * Valida ou gera correlação, propaga o header e restaura o MDC ao terminar, inclusive quando a
     * cadeia falha.
     *
     * @param request requisição HTTP recebida pelo filtro de segurança.
     * @param response resposta HTTP que receberá o resultado.
     * @param chain cadeia de filtros HTTP.
     * @throws ServletException se a cadeia de filtros falhar ao processar a requisição.
     * @throws IOException se não for possível ler ou escrever a resposta HTTP.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String received = request.getHeader(HEADER);
        String traceId = received != null && SAFE_ID.matcher(received).matches()
                ? received : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(HEADER, traceId);
        String previous = MDC.get(ATTRIBUTE);
        MDC.put(ATTRIBUTE, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(ATTRIBUTE);
            } else {
                MDC.put(ATTRIBUTE, previous);
            }
        }
    }
}
