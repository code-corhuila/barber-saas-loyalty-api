package co.edu.corhuila.barbersaas.loyalty.app;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * appointment-api on a local port, as getAppointmentById answers: staff see the appointments of the
 * token's barbershop, a client only their own; anything else is 404.
 */
final class AppointmentApiStub {

    record Appointment(UUID barbershopId, UUID clientId) { }

    final Map<UUID, Appointment> appointments = new ConcurrentHashMap<>();
    private static final Pattern SHOP = Pattern.compile("\"barbershopId\":\"([0-9a-f-]{36})\"");
    private static final Pattern SUB = Pattern.compile("\"sub\":\"([^\"]+)\"");
    private static final Pattern ROLE = Pattern.compile("\"role\":\"([A-Z_]+)\"");
    private final HttpServer server;

    AppointmentApiStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/api/v1/appointments/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            UUID id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
            String claims = claims(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            Appointment a = appointments.get(id);
            boolean visible = a != null && a.barbershopId().toString().equals(claim(SHOP, claims))
                    && (!"CLIENT".equals(claim(ROLE, claims)) || String.valueOf(a.clientId()).equals(claim(SUB, claims)));
            byte[] body = (visible ? "{\"id\":\"" + id + "\",\"clientId\":"
                    + (a.clientId() == null ? "null" : "\"" + a.clientId() + "\"") + "}" : "{}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(visible ? 200 : 404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private static String claims(String authorization) {
        String[] parts = authorization.replace("Bearer ", "").split("\\.");
        return parts.length < 2 ? "" : new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }

    private static String claim(Pattern pattern, String claims) {
        Matcher m = pattern.matcher(claims);
        return m.find() ? m.group(1) : null;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
