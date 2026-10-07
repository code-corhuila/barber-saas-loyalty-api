package co.edu.corhuila.barbersaas.loyalty.adapter.out.http;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.DependencyFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.slf4j.MDC;

/**
 * GET against another domain's API with explicit limits (norm 5.3.10): 2 s to connect, 3 s per
 * attempt, and at most one retry after 200 ms — only when the other service could not be reached or
 * answered 502/503/504, never after a timeout, so a request waits 6.2 s at worst. It passes on the
 * caller's token, which the other service validates again and takes its tenant from, and the
 * X-Correlation-Id, so one request is traced across services.
 */
final class JsonApi {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    static final int MAX_ATTEMPTS = 2;
    static final Duration RETRY_DELAY = Duration.ofMillis(200);
    private static final Set<Integer> RETRYABLE = Set.of(502, 503, 504);

    private final String name;
    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    JsonApi(String name, String baseUrl) {
        this.name = name;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** 200: the body. 404: empty (absent, or another barbershop's). Anything else: DependencyFailure. */
    Optional<JsonNode> get(Caller caller, String pathAndQuery) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + caller.credential())
                .GET();
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        HttpResponse<byte[]> response = send(request.build(), pathAndQuery);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new DependencyFailure(name, "answered " + response.statusCode() + " to GET " + pathAndQuery);
        }
        try {
            return Optional.of(json.readTree(response.body()));
        } catch (IOException e) {
            throw new DependencyFailure(name, "answered a body that is not JSON");
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request, String pathAndQuery) {
        for (int attempt = 1; ; attempt++) {
            boolean last = attempt == MAX_ATTEMPTS;
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (last || !RETRYABLE.contains(response.statusCode())) {
                    return response;
                }
            } catch (HttpTimeoutException e) {
                throw new DependencyFailure(name, "too slow to answer GET " + pathAndQuery);
            } catch (ConnectException e) {
                if (last) {
                    throw new DependencyFailure(name, "unreachable");
                }
            } catch (IOException e) {
                throw new DependencyFailure(name, "failed (" + e.getClass().getSimpleName() + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DependencyFailure(name, "interrupted");
            }
            pause();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
