package co.edu.corhuila.barbersaas.loyalty.app;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** loyalty-service.yaml over HTTP (annex C, HU-LOY-001 #9, HU-TENANT-001 #13). */
class LoyaltyHttpTest extends HttpTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final ObjectMapper json = new ObjectMapper();
    private final UUID shop = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private final String owner = bearer("ADMIN_BARBERSHOP", shop);
    private final String barber = bearer("BARBER", shop);
    private final String client = bearer(clientId, "CLIENT", shop);

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String key, String body)
            throws Exception {
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return http.perform(request.header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void program(int required) throws Exception {
        send(put("/api/v1/loyalty/config"), owner, null,
                "{\"stickersRequired\":" + required + ",\"rewardDescription\":\"Free classic haircut\"}")
                .andExpect(status().isOk());
    }

    private ResultActions sticker(String key, String extra) throws Exception {
        return send(post("/api/v1/loyalty/stickers"), barber, key, "{\"clientId\":\"" + clientId + "\"" + extra + "}");
    }

    private String redeemedCoupon() throws Exception {
        String body = send(post("/api/v1/loyalty/redemptions"), owner, "key-" + UUID.randomUUID(),
                "{\"clientId\":\"" + clientId + "\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("coupon").get("id").asText();
    }

    @Test
    void theOwnerSetsTheRuleAndEveryoneOfTheBarbershopReadsIt() throws Exception {
        http.perform(get("/api/v1/loyalty/config").header("Authorization", client)).andExpect(status().isNotFound());
        program(8);

        http.perform(get("/api/v1/loyalty/config").header("Authorization", client))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stickersRequired").value(8))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.barbershopId").doesNotExist());
        send(put("/api/v1/loyalty/config"), barber, null, "{\"stickersRequired\":1,\"rewardDescription\":\"x\"}")
                .andExpect(status().isForbidden());
        send(put("/api/v1/loyalty/config"), owner, null, "{\"stickersRequired\":0,\"rewardDescription\":\"\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void aStickerIs201WithLocationAndARetryIs200WithoutCreditingTwice() throws Exception {
        program(3);
        ResultActions first = sticker("key-00000001", "").andExpect(status().isCreated())
                .andExpect(jsonPath("$.transaction.id").value(matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.transaction.type").value("STICKER_EARNED"))
                .andExpect(jsonPath("$.card.stickersCount").value(1))
                .andExpect(jsonPath("$.card.stickersRequired").value(3))
                .andExpect(jsonPath("$.card.canRedeem").value(false))
                .andExpect(jsonPath("$.card.rewardDescription").value("Free classic haircut"));
        String cardId = json.readTree(first.andReturn().getResponse().getContentAsString()).get("card").get("id").asText();
        first.andExpect(header().string("Location", endsWith("/api/v1/loyalty/cards/" + cardId)));

        sticker("key-00000001", "").andExpect(status().isOk()).andExpect(jsonPath("$.card.stickersCount").value(1));
        http.perform(get("/api/v1/loyalty/cards/me").header("Authorization", client))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stickersCount").value(1));
        sticker("key-00000002", "").andExpect(jsonPath("$.card.stickersCount").value(2));
        http.perform(get("/api/v1/loyalty/cards/" + cardId + "/transactions").header("Authorization", client))
                .andExpect(jsonPath("$.meta.total").value(2));
    }

    @Test
    void aStickerNamesAnAppointmentOfTheBarbershopOnlyOnce() throws Exception {
        UUID appointment = UUID.randomUUID();
        APPOINTMENT_API.appointments.put(appointment, new AppointmentApiStub.Appointment(shop, clientId));
        String withIt = ",\"appointmentId\":\"" + appointment + "\"";

        sticker("key-00000003", withIt).andExpect(status().isCreated())
                .andExpect(jsonPath("$.transaction.appointmentId").value(appointment.toString()));
        sticker("key-00000004", withIt).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
        sticker("key-00000005", ",\"appointmentId\":\"" + UUID.randomUUID() + "\"").andExpect(status().isNotFound());
    }

    @Test
    void aRedemptionNeedsEnoughStickersAndIssuesACoupon() throws Exception {
        program(2);
        sticker("key-00000006", "");
        send(post("/api/v1/loyalty/redemptions"), owner, "key-00000007", "{\"clientId\":\"" + clientId + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("The client has 1 of 2 stickers and cannot redeem yet"));
        sticker("key-00000008", "");

        ResultActions redeemed = send(post("/api/v1/loyalty/redemptions"), owner, "key-00000009",
                "{\"clientId\":\"" + clientId + "\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.transaction.type").value("REWARD_REDEEMED"))
                .andExpect(jsonPath("$.card.stickersCount").value(0))
                .andExpect(jsonPath("$.card.totalRewardsRedeemed").value(1))
                .andExpect(jsonPath("$.coupon.status").value("ACTIVE"));
        String couponId = json.readTree(redeemed.andReturn().getResponse().getContentAsString()).get("coupon").get("id").asText();
        redeemed.andExpect(header().string("Location", endsWith("/api/v1/loyalty/coupons/" + couponId)));
        send(post("/api/v1/loyalty/redemptions"), owner, "key-00000009", "{\"clientId\":\"" + clientId + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.coupon.id").value(couponId));
        http.perform(get("/api/v1/loyalty/coupons").param("status", "ACTIVE").header("Authorization", client))
                .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    void aClientUsesTheirCouponOnceOnTheirAppointment() throws Exception {
        program(1);
        sticker("key-00000010", "");
        String couponId = redeemedCoupon();
        UUID mine = UUID.randomUUID();
        UUID someoneElses = UUID.randomUUID();
        APPOINTMENT_API.appointments.put(mine, new AppointmentApiStub.Appointment(shop, clientId));
        APPOINTMENT_API.appointments.put(someoneElses, new AppointmentApiStub.Appointment(shop, UUID.randomUUID()));

        send(post("/api/v1/loyalty/coupons/" + couponId + "/use"), barber, null, "{\"appointmentId\":\"" + someoneElses + "\"}")
                .andExpect(status().isNotFound());
        send(post("/api/v1/loyalty/coupons/" + couponId + "/use"), client, null, "{\"appointmentId\":\"" + mine + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.appointmentId").value(mine.toString()));
        send(post("/api/v1/loyalty/coupons/" + couponId + "/use"), client, null, "{\"appointmentId\":\"" + mine + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
    }

    /** HU-TENANT-001: another barbershop never sees nor changes this card or coupon. */
    @Test
    void anotherBarbershopGets404EvenWithAValidId() throws Exception {
        program(1);
        String body = sticker("key-00000011", "").andReturn().getResponse().getContentAsString();
        String cardId = json.readTree(body).get("card").get("id").asText();
        String couponId = redeemedCoupon();
        String intruder = bearer("ADMIN_BARBERSHOP", UUID.randomUUID());

        http.perform(get("/api/v1/loyalty/cards/" + cardId).header("Authorization", intruder)).andExpect(status().isNotFound());
        http.perform(get("/api/v1/loyalty/cards/" + cardId + "/transactions").header("Authorization", intruder))
                .andExpect(status().isNotFound());
        http.perform(get("/api/v1/loyalty/coupons/" + couponId).header("Authorization", intruder)).andExpect(status().isNotFound());
        http.perform(get("/api/v1/loyalty/cards").header("Authorization", intruder)).andExpect(jsonPath("$.meta.total").value(0));
        send(post("/api/v1/loyalty/redemptions"), intruder, "key-00000012", "{\"clientId\":\"" + clientId + "\"}")
                .andExpect(status().isNotFound());
        http.perform(get("/api/v1/loyalty/cards/" + cardId).header("Authorization", bearer("CLIENT", shop)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rolesBodiesAndQueryValuesAreChecked() throws Exception {
        send(post("/api/v1/loyalty/stickers"), client, "key-00000013", "{\"clientId\":\"" + clientId + "\"}")
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/loyalty/cards").header("Authorization", client)).andExpect(status().isForbidden());
        http.perform(get("/api/v1/loyalty/config").header("Authorization", bearer("SUPER_ADMIN", null)))
                .andExpect(status().isForbidden());
        send(post("/api/v1/loyalty/stickers"), barber, null, "{\"clientId\":\"" + clientId + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));
        send(post("/api/v1/loyalty/stickers"), barber, "key-00000014", "{\"barbershopId\":\"x\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.length()").value(2));
        http.perform(get("/api/v1/loyalty/coupons").param("status", "LOST").header("Authorization", owner))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/v1/loyalty/cards/not-a-uuid").header("Authorization", owner)).andExpect(status().isBadRequest());
    }
}
