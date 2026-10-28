package br.com.deroyque.activation.shared.web;

import br.com.deroyque.activation.request.api.ActivationRequestController;
import br.com.deroyque.activation.request.application.ActivationRequestService;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivationRequestController.class)
class ApiExceptionHandlerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ActivationRequestService service;

    @Test
    void shouldSanitizeUnexpectedErrorAndPreserveCorrelationId() throws Exception {
        var id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new RuntimeException("SQL password=SHOULD_NOT_LEAK"));

        mvc.perform(get("/api/v1/requests/{id}", id).header("X-Correlation-ID", "review-123"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("X-Correlation-ID", "review-123"))
                .andExpect(jsonPath("$.traceId").value("review-123"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("SHOULD_NOT_LEAK"))))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void shouldRejectMalformedJsonWithoutExposingParserDetails() throws Exception {
        mvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString("JsonParseException"))));
    }

    @Test
    void shouldRejectUnsupportedContentTypeWithProblemDetail() throws Exception {
        mvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_XML).content("<request/>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void shouldReplaceUnsafeCorrelationIdAndRestorePreviousLoggingContext() throws Exception {
        MDC.put("traceId", "previous-context");
        try {
            var response = mvc.perform(get("/api/v1/requests/invalid-uuid")
                            .header("X-Correlation-ID", "unsafe id with spaces"))
                    .andExpect(status().isBadRequest()).andReturn().getResponse();

            assertThat(UUID.fromString(response.getHeader("X-Correlation-ID"))).isNotNull();
            assertThat(MDC.get("traceId")).isEqualTo("previous-context");
        } finally {
            MDC.remove("traceId");
        }
    }
}
