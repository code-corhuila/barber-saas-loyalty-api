package co.edu.corhuila.barbersaas.loyalty.adapter.out.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.DependencyFailure;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** The client against a stub of appointment-api on a local port. */
class AppointmentApiClientTest {

    private HttpServer server;
    private AppointmentApiClient client;
    /** Status per path; anything else answers 404. */
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, String> seen = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final Caller owner = new Caller(UUID.randomUUID().toString(), Role.ADMIN_BARBERSHOP, UUID.randomUUID(),
            "the-token");

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            seen.put("Authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            seen.put("X-Correlation-Id", String.valueOf(exchange.getRequestHeaders().getFirst("X-Correlation-Id")));
            int status = statuses.getOrDefault(exchange.getRequestURI().toString(), 404);
            byte[] body = bodies.getOrDefault(exchange.getRequestURI().toString(), "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new AppointmentApiClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
        MDC.clear();
    }

    @Test
    void anAppointmentOfTheBarbershopExistsAndIsAskedWithTheOwnersTokenAndCorrelationId() {
        UUID id = UUID.randomUUID();
        statuses.put("/api/v1/appointments/" + id, 200);
        MDC.put("correlationId", "corr-1");

        bodies.put("/api/v1/appointments/" + id, "{\"id\":\"" + id + "\",\"clientId\":\"" + owner.subject() + "\"}");

        assertEquals(UUID.fromString(owner.subject()), client.find(owner, id).orElseThrow().clientId());
        assertEquals("Bearer the-token", seen.get("Authorization"));
        assertEquals("corr-1", seen.get("X-Correlation-Id"));
    }

    @Test
    void notFoundMeansItDoesNotExistAndAWalkInHasNoClient() {
        assertTrue(client.find(owner, UUID.randomUUID()).isEmpty());
        UUID walkIn = UUID.randomUUID();
        statuses.put("/api/v1/appointments/" + walkIn, 200);
        bodies.put("/api/v1/appointments/" + walkIn, "{\"id\":\"" + walkIn + "\",\"clientId\":null}");
        assertNull(client.find(owner, walkIn).orElseThrow().clientId());
    }

    @Test
    void anUnavailableAppointmentApiIsRetriedOnceThenFails() {
        UUID id = UUID.randomUUID();
        statuses.put("/api/v1/appointments/" + id, 503);

        assertThrows(DependencyFailure.class, () -> client.find(owner, id));
        assertEquals(2, calls.get());
    }

    @Test
    void aForbiddenAnswerIsAFailureNotAnAbsence() {
        UUID id = UUID.randomUUID();
        statuses.put("/api/v1/appointments/" + id, 403);

        assertThrows(DependencyFailure.class, () -> client.find(owner, id));
    }
}
