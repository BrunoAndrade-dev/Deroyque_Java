package br.com.deroyque.activation.shared.web;

import br.com.deroyque.activation.request.api.InvalidQueryParameterException;
import br.com.deroyque.activation.request.application.RequestConflictException;
import br.com.deroyque.activation.request.application.RequestNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;

/**
 * Traduz falhas da API para ProblemDetail e erros de validação, preservando correlação e ocultando
 * detalhes internos.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Converte ausência ou propriedade incompatível na mesma resposta pública.
     *
     * @param exception falha recebida, cujo conteúdo não deve ser exposto ao consumidor.
     * @param request contexto HTTP com a correlação da chamada.
     * @return ProblemDetail HTTP 404.
     */
    @ExceptionHandler(RequestNotFoundException.class)
    ResponseEntity<Object> notFound(RequestNotFoundException exception, WebRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), HttpHeaders.EMPTY, request, null);
    }

    /**
     * Traduz indisponibilidade do identificador global sem revelar o registro existente.
     *
     * @param exception falha recebida, cujo conteúdo não deve ser exposto ao consumidor.
     * @param request contexto HTTP com a correlação da chamada.
     * @return ProblemDetail HTTP 409.
     */
    @ExceptionHandler(RequestConflictException.class)
    ResponseEntity<Object> conflict(RequestConflictException exception, WebRequest request) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), HttpHeaders.EMPTY, request, null);
    }

    /**
     * Descreve falha no filtro customerId sem devolver o valor rejeitado.
     *
     * @param exception falha recebida, cujo conteúdo não deve ser exposto ao consumidor.
     * @param request contexto HTTP com a correlação da chamada.
     * @return ProblemDetail HTTP 400 com erro de campo.
     */
    @ExceptionHandler(InvalidQueryParameterException.class)
    ResponseEntity<Object> invalidQuery(InvalidQueryParameterException exception, WebRequest request) {
        return response(HttpStatus.BAD_REQUEST, "Invalid query parameters.", HttpHeaders.EMPTY, request,
                List.of(new ValidationError("customerId", exception.getMessage())));
    }

    /**
     * Registra tipo e correlação da falha, mantendo detalhes internos fora da resposta.
     *
     * @param exception falha recebida, cujo conteúdo não deve ser exposto ao consumidor.
     * @param request contexto HTTP com a correlação da chamada.
     * @return ProblemDetail HTTP 500 genérico.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, WebRequest request) {
        // Log the exception type and correlation ID without exposing SQL, payloads or credentials.
        log.error("Unexpected failure: type={}, traceId={}", exception.getClass().getSimpleName(),
                request.getAttribute(CorrelationIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.",
                HttpHeaders.EMPTY, request, null);
    }

    /**
     * Traduz violações Bean Validation do corpo sem reproduzir os valores rejeitados.
     *
     * @param exception falha recebida, cujo conteúdo não deve ser exposto ao consumidor.
     * @param headers headers MVC a preservar.
     * @param status status HTTP da resposta pública.
     * @param request contexto HTTP com a correlação da chamada.
     * @return ProblemDetail com erros de campos.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ValidationError(error.getField(), error.getDefaultMessage())).toList();
        return response(status, "One or more fields are invalid.", headers, request, errors);
    }

    /**
     * Traduz violações de parâmetros de métodos do controller.
     *
     * @param exception falha recebida, cujo conteúdo não deve ser exposto ao consumidor.
     * @param headers headers MVC a preservar.
     * @param status status HTTP da resposta pública.
     * @param request contexto HTTP com a correlação da chamada.
     * @return ProblemDetail com erros de parâmetros.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        var errors = exception.getParameterValidationResults().stream().flatMap(result ->
                result.getResolvableErrors().stream().map(error -> new ValidationError(
                        result.getMethodParameter().getParameterName(), error.getDefaultMessage()))).toList();
        return response(status, "One or more parameters are invalid.", headers, request, errors);
    }

    /**
     * Padroniza falhas de infraestrutura MVC, incluindo formato, método e Content-Type.
     *
     * @param exception falha recebida, cujo conteúdo não deve ser exposto ao consumidor.
     * @param body corpo recebido ou representação a enviar.
     * @param headers headers MVC a preservar.
     * @param status status HTTP da resposta pública.
     * @param request contexto HTTP com a correlação da chamada.
     * @return ProblemDetail sanitizado no status recebido.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = switch (status.value()) {
            case 400 -> "The request format or parameter value is invalid.";
            case 404 -> "Resource not found.";
            case 405 -> "HTTP method not allowed for this resource.";
            case 415 -> "Unsupported request content type.";
            default -> "The request could not be processed.";
        };
        return response(status, detail, headers, request, null);
    }

    /**
     * Monta a representação comum dos erros com URI, correlação e validações opcionais.
     *
     * @param status status HTTP da resposta pública.
     * @param detail mensagem pública sanitizada.
     * @param headers headers MVC a preservar.
     * @param request contexto HTTP com a correlação da chamada.
     * @param errors lista opcional de violações de validação.
     * @return resposta com Content-Type application/problem+json.
     */
    private ResponseEntity<Object> response(HttpStatusCode status, String detail, HttpHeaders headers,
            WebRequest request, List<ValidationError> errors) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(((ServletWebRequest) request).getRequest().getRequestURI()));
        problem.setProperty("traceId", request.getAttribute(CorrelationIdFilter.ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST));
        if (errors != null) {
            problem.setProperty("errors", errors);
        }
        var responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, responseHeaders, status);
    }

    /**
     * Descrição pública de uma falha de validação, sem o valor rejeitado.
     *
     * @param field nome do campo ou parâmetro rejeitado.
     * @param message motivo público da rejeição.
     */
    public record ValidationError(String field, String message) {
    }
}
