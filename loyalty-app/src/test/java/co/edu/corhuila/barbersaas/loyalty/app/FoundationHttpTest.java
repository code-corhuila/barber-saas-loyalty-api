package co.edu.corhuila.barbersaas.loyalty.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

class FoundationHttpTest extends HttpTest {

    @Test
    void the_liveness_probe_needs_no_token() throws Exception {
        http.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void a_protected_route_without_a_token_answers_401_with_the_envelope_and_the_correlation_id() throws Exception {
        http.perform(get("/api/v1/loyalty/cards").header("X-Correlation-Id", "corr-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", "corr-123"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").value("corr-123"));
    }

    @Test
    void a_forged_token_answers_401() throws Exception {
        http.perform(get("/api/v1/loyalty/cards").header("Authorization", "Bearer a.b.c"))
                .andExpect(status().isUnauthorized());
    }
}
