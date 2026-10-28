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

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RequestNotFoundException.class)
    ResponseEntity<Object> notFound(RequestNotFoundException exception, WebRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), HttpHeaders.EMPTY, request, null);
    }

    @ExceptionHandler(RequestConflictException.class)
    ResponseEntity<Object> conflict(RequestConflictException exception, WebRequest request) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), HttpHeaders.EMPTY, request, null);
    }

    @ExceptionHandler(InvalidQueryParameterException.class)
    ResponseEntity<Object> invalidQuery(InvalidQueryParameterException exception, WebRequest request) {
        return response(HttpStatus.BAD_REQUEST, "Invalid query parameters.", HttpHeaders.EMPTY, request,
                List.of(new ValidationError("customerId", exception.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, WebRequest request) {
        // Log the exception type and correlation ID without exposing SQL, payloads or credentials.
        log.error("Unexpected failure: type={}, traceId={}", exception.getClass().getSimpleName(),
                request.getAttribute(CorrelationIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.",
                HttpHeaders.EMPTY, request, null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ValidationError(error.getField(), error.getDefaultMessage())).toList();
        return response(status, "One or more fields are invalid.", headers, request, errors);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        var errors = exception.getParameterValidationResults().stream().flatMap(result ->
                result.getResolvableErrors().stream().map(error -> new ValidationError(
                        result.getMethodParameter().getParameterName(), error.getDefaultMessage()))).toList();
        return response(status, "One or more parameters are invalid.", headers, request, errors);
    }

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

    public record ValidationError(String field, String message) {
    }
}
