package co.edu.corhuila.barbersaas.loyalty.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Rs256VerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-02T15:00:00Z");
    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();
    private final Rs256Verifier verifier = new Rs256Verifier(pem(KEYS));

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(KeyPair keys) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** The same shape identity-auth-api and barber-saas-infra-postgres/scripts/dev-token.sh sign. */
    static String token(KeyPair keys, String alg, String claims) {
        try {
            String input = b64("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\",\"kid\":\"dev-1\"}") + "." + b64(claims);
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(keys.getPrivate());
            rsa.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(rsa.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String claims(String role, String tenant, long exp) {
        return "{\"iss\":\"barber-saas-identity-auth-api\",\"sub\":\"" + UUID.randomUUID() + "\",\"role\":\"" + role
                + "\"" + (tenant == null ? "" : ",\"barbershopId\":\"" + tenant + "\"") + ",\"iat\":"
                + NOW.getEpochSecond() + ",\"exp\":" + exp + "}";
    }

    @Test
    void a_valid_token_gives_the_role_and_the_tenant() throws Exception {
        UUID shop = UUID.randomUUID();

        Caller caller = verifier.verify(token(KEYS, "RS256",
                claims("ADMIN_BARBERSHOP", shop.toString(), NOW.getEpochSecond() + 60)), NOW);

        assertEquals(Caller.Role.ADMIN_BARBERSHOP, caller.role());
        assertEquals(shop, caller.barbershopId());
    }

    @Test
    void the_caller_carries_the_token_to_pass_on_to_other_services() throws Exception {
        String token = token(KEYS, "RS256", claims("BARBER", UUID.randomUUID().toString(), NOW.getEpochSecond() + 60));

        assertEquals(token, verifier.verify(token, NOW).credential());
    }

    @Test
    void a_client_token_carries_no_tenant() throws Exception {
        Caller caller = verifier.verify(token(KEYS, "RS256", claims("CLIENT", null, NOW.getEpochSecond() + 60)), NOW);

        assertNull(caller.barbershopId());
    }

    @Test
    void expired_foreign_or_downgraded_tokens_are_rejected() {
        long valid = NOW.getEpochSecond() + 60;
        assertThrows(Rs256Verifier.InvalidTokenException.class, () -> verifier.verify(
                token(KEYS, "RS256", claims("CLIENT", null, NOW.getEpochSecond() - 120)), NOW));
        assertThrows(Rs256Verifier.InvalidTokenException.class, () -> verifier.verify(
                token(OTHER_KEYS, "RS256", claims("CLIENT", null, valid)), NOW));
        assertThrows(Rs256Verifier.InvalidTokenException.class, () -> verifier.verify(
                token(KEYS, "none", claims("CLIENT", null, valid)), NOW));
        assertThrows(Rs256Verifier.InvalidTokenException.class, () -> verifier.verify(
                token(KEYS, "RS256", claims("ROOT", null, valid)), NOW));
        assertThrows(Rs256Verifier.InvalidTokenException.class, () -> verifier.verify(
                token(KEYS, "RS256", claims("CLIENT", null, valid).replace("identity-auth", "evil")), NOW));
        assertThrows(Rs256Verifier.InvalidTokenException.class, () -> verifier.verify("not.a-token", NOW));
    }
}
