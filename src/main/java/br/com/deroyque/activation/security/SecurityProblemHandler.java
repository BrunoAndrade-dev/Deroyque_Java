package br.com.deroyque.activation.security;

import br.com.deroyque.activation.shared.web.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/**
 * Padroniza falhas de autenticação e autorização como ProblemDetail, com correlação e controle de
 * cache, sem serializar exceções de segurança.
 */
@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    /**
     * Recebe o serializador usado nas respostas de segurança.
     *
     * @param mapper serializador JSON configurado.
     */
    public SecurityProblemHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Escreve 401 para ausência ou invalidade de autenticação sem expor a exceção recebida.
     *
     * @param request requisição HTTP recebida pelo filtro de segurança.
     * @param response resposta HTTP que receberá o resultado.
     * @param exception falha recebida, cujo conteúdo não deve ser exposto ao consumidor.
     * @throws IOException se não for possível ler ou escrever a resposta HTTP.
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(request, response, HttpStatus.UNAUTHORIZED,
                "Authentication is required or the access token is invalid.");
    }

    /**
     * Escreve 403 quando o consumidor autenticado não tem permissão para o recurso.
     *
     * @param request requisição HTTP recebida pelo filtro de segurança.
     * @param response resposta HTTP que receberá o resultado.
     * @param exception falha recebida, cujo conteúdo não deve ser exposto ao consumidor.
     * @throws IOException se não for possível ler ou escrever a resposta HTTP.
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
        write(request, response, HttpStatus.FORBIDDEN, "Insufficient permission to access this resource.");
    }

    /**
     * Serializa ProblemDetail com correlação e controle de cache, sem incluir tokens ou mensagens
     * internas.
     *
     * @param request requisição HTTP recebida pelo filtro de segurança.
     * @param response resposta HTTP que receberá o resultado.
     * @param status status HTTP da resposta pública.
     * @param detail mensagem pública sanitizada.
     * @throws IOException se não for possível ler ou escrever a resposta HTTP.
     */
    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("traceId", request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        // Authentication exception messages may contain parser details. Never serialize or log them.
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
