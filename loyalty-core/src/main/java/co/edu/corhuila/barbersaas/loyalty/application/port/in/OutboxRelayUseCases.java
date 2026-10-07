package co.edu.corhuila.barbersaas.loyalty.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The outbox relay of DEC-LOY-04: only the service token of barber-saas-worker (ADR-016). */
public interface OutboxRelayUseCases {

    /** EventEnvelope of _shared.yaml: what every consumer receives on POST /internal/v1/events. */
    record EventEnvelope(UUID id, String type, int version, Instant occurredAt, String aggregateType,
                         UUID aggregateId, UUID barbershopId, String correlationId, Map<String, Object> payload) { }

    /** The oldest pending events, already as envelopes. */
    List<EventEnvelope> pending(Caller caller, int limit);

    /** NotFound for an unknown id; confirming again is accepted and changes nothing. */
    void published(Caller caller, UUID eventId);

    /** After a consumer answered 4xx or after 8 attempts; the event leaves the pending list. */
    void failed(Caller caller, UUID eventId, String reason);
}
