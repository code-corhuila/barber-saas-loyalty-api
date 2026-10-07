package co.edu.corhuila.barbersaas.loyalty.adapter.in.http;

import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.EventUseCases;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.EventUseCases.IncomingEvent;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.EventUseCases.Receipt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * receiveEvent (tag Internal, ADR-016, DEC-LOY-01/05), for barber-saas-worker on the internal network:
 * the api-gateway never routes /internal/v1. The body is an EventEnvelope of _shared.yaml; the use case
 * checks the service token. Every outcome answers 200 with an EventReceipt.
 */
@RestController
@RequestMapping("/internal/v1/events")
public class InternalEventsController {

    record ReceiptView(UUID eventId, String outcome) { }

    private final EventUseCases events;
    private final ObjectMapper json;

    public InternalEventsController(EventUseCases events, ObjectMapper json) {
        this.events = events;
        this.json = json;
    }

    @PostMapping
    public ReceiptView receive(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                               @RequestBody(required = false) JsonNode body) {
        Receipt receipt = events.receive(caller, envelope(body));
        return new ReceiptView(receipt.eventId(), receipt.outcome().name());
    }

    /** The required fields of EventEnvelope; others (occurredAt, aggregateId…) may come and are not read. */
    @SuppressWarnings("unchecked")
    private IncomingEvent envelope(JsonNode body) {
        List<FieldError> errors = new ArrayList<>();
        if (body == null || !body.isObject()) {
            throw new ValidationException("the body must be an EventEnvelope", List.of());
        }
        UUID id = uuid(body, "id", errors);
        String type = body.path("type").isTextual() ? body.get("type").asText() : null;
        if (type == null) {
            errors.add(new FieldError("type", "required"));
        }
        int version = body.path("version").isInt() ? body.get("version").asInt() : 0;
        if (version < 1) {
            errors.add(new FieldError("version", "an integer of at least 1"));
        }
        UUID barbershopId = uuid(body, "barbershopId", errors);
        if (!body.path("payload").isObject()) {
            errors.add(new FieldError("payload", "required, an object"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("the request is not valid", errors);
        }
        return new IncomingEvent(id, type, version, barbershopId, json.convertValue(body.get("payload"), Map.class));
    }

    private static UUID uuid(JsonNode body, String field, List<FieldError> errors) {
        try {
            return UUID.fromString(body.get(field).asText());
        } catch (RuntimeException e) {
            errors.add(new FieldError(field, body.has(field) ? "must be a UUID" : "required"));
            return null;
        }
    }
}
