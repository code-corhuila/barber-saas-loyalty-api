package co.edu.corhuila.barbersaas.loyalty.adapter.in.http;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.OutboxRelayUseCases;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.OutboxRelayUseCases.EventEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The outbox relay (tag Internal, DEC-LOY-04, ADR-016), for barber-saas-worker on the internal
 * network: the api-gateway never routes /internal/v1. The use case checks the service token.
 */
@RestController
@RequestMapping("/internal/v1/outbox-events")
public class InternalOutboxController {

    record PendingView(List<EventEnvelope> data) { }

    private static final Set<String> FAILURE_FIELDS = Set.of("reason");

    private final OutboxRelayUseCases relay;

    public InternalOutboxController(OutboxRelayUseCases relay) {
        this.relay = relay;
    }

    /** listPendingOutboxEvents: at most {@code limit} (1–100, default 20), oldest first. */
    @GetMapping
    public PendingView pending(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                               @RequestParam(required = false) Integer limit) {
        return new PendingView(relay.pending(caller, Requests.page(1, limit).limit()));
    }

    @PostMapping("/{id}/published")
    public ResponseEntity<Void> published(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                          @PathVariable UUID id) {
        relay.published(caller, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/failed")
    public ResponseEntity<Void> failed(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                       @PathVariable UUID id, @RequestBody(required = false) JsonNode body) {
        JsonBody b = JsonBody.of(body, FAILURE_FIELDS);
        String reason = b.optionalText("reason", 500);
        b.validate();                                   // a missing or blank reason is the use case's 400
        relay.failed(caller, id, reason);
        return ResponseEntity.noContent().build();
    }
}
