package co.edu.corhuila.barbersaas.loyalty.application.usecase;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.OutboxRelayUseCases;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.Clock;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.OutboxStore;
import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.InvalidValue;
import java.util.List;
import java.util.UUID;

/**
 * Hands the outbox to barber-saas-worker and records what it could deliver (ADR-016). The relay works
 * across barbershops; the tenant of each event travels in its envelope, taken from its own payload.
 */
public class RelayOutbox implements OutboxRelayUseCases {

    static final String WORKER = "barber-saas-worker";
    /** Every loyalty event is at version 1; it grows only when a payload gains a field. */
    static final int VERSION = 1;
    static final int REASON_MAX = 500;

    private final OutboxStore outbox;
    private final Clock clock;

    public RelayOutbox(OutboxStore outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public List<EventEnvelope> pending(Caller caller, int limit) {
        caller.requireService(WORKER);
        return outbox.pending(limit).stream().map(RelayOutbox::envelope).toList();
    }

    @Override
    public void published(Caller caller, UUID eventId) {
        caller.requireService(WORKER);
        if (!outbox.markPublished(eventId, clock.now())) {
            throw new NotFound("Outbox event");
        }
    }

    @Override
    public void failed(Caller caller, UUID eventId, String reason) {
        caller.requireService(WORKER);
        if (reason == null || reason.isBlank() || reason.length() > REASON_MAX) {
            throw new InvalidValue("reason must have between 1 and " + REASON_MAX + " characters");
        }
        if (!outbox.markFailed(eventId, reason, clock.now())) {
            throw new NotFound("Outbox event");
        }
    }

    private static EventEnvelope envelope(OutboxStore.Stored s) {
        OutboxEvent e = s.event();
        Object shop = e.payload().get("barbershopId");
        return new EventEnvelope(e.id(), e.type(), VERSION, e.occurredAt(), OutboxEvent.AGGREGATE_TYPE, e.aggregateId(),
                shop == null ? null : UUID.fromString(shop.toString()), s.correlationId(), e.payload());
    }
}
