package co.edu.corhuila.barbersaas.loyalty.app;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** loyalty's outbox relay over HTTP (DEC-LOY-04, ADR-016), with real RS256 tokens. */
class InternalOutboxHttpTest extends HttpTest {

    private static final String OUTBOX = "/internal/v1/outbox-events";
    private final ObjectMapper json = new ObjectMapper();
    private final UUID shop = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private final String worker = serviceBearer("barber-saas-worker");

    @BeforeEach
    void empty() throws Exception {
        drainOutbox(json, worker);
    }

    /** Grants a sticker and returns its StickerGranted event, as the worker reads it. */
    private JsonNode grantedEvent() throws Exception {
        http.perform(post("/api/v1/loyalty/stickers").header("Authorization", bearer("BARBER", shop))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()).header("X-Correlation-Id", "corr-sticker")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clientId\":\"" + clientId + "\"}"))
                .andExpect(status().isCreated());
        return json.readTree(http.perform(get(OUTBOX).header("Authorization", worker))
                .andReturn().getResponse().getContentAsString()).get("data").get(0);
    }

    @Test
    void theWorkerReadsStickerGrantedAsAnEnvelope() throws Exception {
        JsonNode e = grantedEvent();

        if (!e.get("type").asText().equals("StickerGranted") || e.get("version").asInt() != 1
                || !e.get("aggregateType").asText().equals("loyalty_card")
                || !e.get("barbershopId").asText().equals(shop.toString())
                || !e.get("correlationId").asText().equals("corr-sticker")
                || !e.get("payload").get("clientId").asText().equals(clientId.toString())
                || e.get("payload").get("stickersCount").asInt() != 1) {
            throw new AssertionError("unexpected envelope: " + e);
        }
    }

    @Test
    void aConfirmedOrFailedEventLeavesThePendingList() throws Exception {
        String id = grantedEvent().get("id").asText();

        http.perform(post(OUTBOX + "/" + id + "/published").header("Authorization", worker)).andExpect(status().isNoContent());
        http.perform(post(OUTBOX + "/" + id + "/published").header("Authorization", worker)).andExpect(status().isNoContent());
        http.perform(get(OUTBOX).header("Authorization", worker)).andExpect(jsonPath("$.data[*].id", not(hasItem(id))));
        String other = grantedEvent().get("id").asText();
        http.perform(post(OUTBOX + "/" + other + "/failed").header("Authorization", worker)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"notifications-api 422\"}"))
                .andExpect(status().isNoContent());
        http.perform(get(OUTBOX).header("Authorization", worker)).andExpect(jsonPath("$.data[*].id", not(hasItem(other))));
        http.perform(post(OUTBOX + "/" + UUID.randomUUID() + "/published").header("Authorization", worker))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyTheWorkersServiceTokenGetsIn() throws Exception {
        http.perform(get(OUTBOX).header("Authorization", bearer("ADMIN_BARBERSHOP", shop))).andExpect(status().isForbidden());
        http.perform(get(OUTBOX).header("Authorization", serviceBearer("barber-saas-workflow"))).andExpect(status().isForbidden());
        http.perform(get(OUTBOX)).andExpect(status().isUnauthorized());
    }
}
