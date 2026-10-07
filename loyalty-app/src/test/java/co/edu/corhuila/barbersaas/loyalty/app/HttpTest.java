package co.edu.corhuila.barbersaas.loyalty.app;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract with the in-memory repositories (DATABASE_URL empty): the whole service starts,
 * and tokens are signed with a throwaway key pair, exactly as identity-auth-api signs them.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class HttpTest {

    static final KeyPair KEYS = generate();
    static final AppointmentApiStub APPOINTMENT_API = new AppointmentApiStub();

    @Autowired
    MockMvc http;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("loyalty.appointment-api-url", APPOINTMENT_API::url);
        registry.add("JWT_PUBLIC_KEY", () -> "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** "Bearer ..." for a user with that role; {@code barbershopId} null for CLIENT and SUPER_ADMIN. */
    static String bearer(UUID userId, String role, UUID barbershopId) {
        return signed(userId.toString(), role, barbershopId);
    }

    /** "Bearer ..." for a service token: role SERVICE, the service's name as sub, no barbershop. */
    static String serviceBearer(String service) {
        return signed(service, "SERVICE", null);
    }

    private static String signed(String subject, String role, UUID barbershopId) {
        long now = Instant.now().getEpochSecond();
        String claims = "{\"iss\":\"barber-saas-identity-auth-api\",\"sub\":\"" + subject + "\",\"role\":\"" + role + "\""
                + (barbershopId == null ? "" : ",\"barbershopId\":\"" + barbershopId + "\"")
                + ",\"iat\":" + now + ",\"exp\":" + (now + 600) + "}";
        try {
            String input = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"dev-1\"}") + "." + b64(claims);
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(KEYS.getPrivate());
            rsa.update(input.getBytes(StandardCharsets.US_ASCII));
            return "Bearer " + input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(rsa.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String bearer(String role, UUID barbershopId) {
        return bearer(UUID.randomUUID(), role, barbershopId);
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
