package co.edu.corhuila.barbersaas.loyalty.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** POST /internal/v1/events over HTTP (ADR-016, DEC-LOY-01/05), with real RS256 tokens. */
class InternalEventsHttpTest extends HttpTest {

    private final UUID shop = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private final UUID barberUser = UUID.randomUUID();
    private final String worker = serviceBearer("barber-saas-worker");

    private String envelope(UUID eventId, String type, UUID client, boolean withCompletedBy) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"" + type + "\",\"version\":" + (withCompletedBy ? 2 : 1)
                + ",\"occurredAt\":\"2026-10-07T14:00:00Z\",\"aggregateType\":\"appointment\",\"aggregateId\":\""
                + UUID.randomUUID() + "\",\"barbershopId\":\"" + shop + "\",\"correlationId\":\"corr-1\",\"payload\":{"
                + "\"appointmentId\":\"" + UUID.randomUUID() + "\",\"barbershopId\":\"" + shop + "\",\"clientId\":"
                + (client == null ? "null" : "\"" + client + "\"")
                + (withCompletedBy ? ",\"completedBy\":\"" + barberUser + "\"" : "") + ",\"status\":\"COMPLETED\"}}";
    }

    private ResultActions deliver(String token, String body) throws Exception {
        return http.perform(post("/internal/v1/events").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void program() throws Exception {
        http.perform(put("/api/v1/loyalty/config").header("Authorization", bearer("ADMIN_BARBERSHOP", shop))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stickersRequired\":8,\"rewardDescription\":\"Corte\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aCompletedAppointmentGivesOneStickerEvenIfDeliveredTwice() throws Exception {
        program();
        UUID eventId = UUID.randomUUID();
        String body = envelope(eventId, "AppointmentCompleted", clientId, true);

        deliver(worker, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.outcome").value("PROCESSED"));
        deliver(worker, body).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));

        http.perform(get("/api/v1/loyalty/cards/me").header("Authorization", bearer(clientId, "CLIENT", shop)))
                .andExpect(jsonPath("$.stickersCount").value(1));
        String cardId = com.jayway.jsonpath.JsonPath.read(http.perform(get("/api/v1/loyalty/cards/me")
                .header("Authorization", bearer(clientId, "CLIENT", shop))).andReturn().getResponse().getContentAsString(), "$.id");
        http.perform(get("/api/v1/loyalty/cards/" + cardId + "/transactions").header("Authorization", bearer("BARBER", shop)))
                .andExpect(jsonPath("$.data[0].grantedByUserId").value(barberUser.toString()));
    }

    @Test
    void aWalkInOrNoProgramIsIgnored() throws Exception {
        deliver(worker, envelope(UUID.randomUUID(), "AppointmentCompleted", clientId, true))
                .andExpect(jsonPath("$.outcome").value("IGNORED"));
        program();
        deliver(worker, envelope(UUID.randomUUID(), "AppointmentCompleted", null, true))
                .andExpect(jsonPath("$.outcome").value("IGNORED"));
    }

    @Test
    void anotherTypeOrNoCompletedByIs422AndABadEnvelopeIs400() throws Exception {
        program();
        deliver(worker, envelope(UUID.randomUUID(), "AppointmentCancelled", clientId, true))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
        deliver(worker, envelope(UUID.randomUUID(), "AppointmentCompleted", clientId, false))
                .andExpect(status().isUnprocessableEntity());
        deliver(worker, "{\"type\":\"AppointmentCompleted\"}").andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheWorkersServiceTokenIsAccepted() throws Exception {
        String body = envelope(UUID.randomUUID(), "AppointmentCompleted", clientId, true);
        deliver(bearer("ADMIN_BARBERSHOP", shop), body).andExpect(status().isForbidden());
        deliver(serviceBearer("barber-saas-workflow"), body).andExpect(status().isForbidden());
        http.perform(post("/internal/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }
}
