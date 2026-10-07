package co.edu.corhuila.barbersaas.loyalty.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The relay side of loyalty.outbox_event (ADR-016, DEC-LOY-04): what barber-saas-worker reads and
 * confirms. It works across barbershops, as the platform's relay, never for a user.
 */
public interface OutboxStore {

    /** A pending row with the correlation id of the request that wrote it. */
    record Stored(OutboxEvent event, String correlationId) { }

    /** Rows with published_at and failed_at both null, oldest occurred_at first, at most {@code limit}. */
    List<Stored> pending(int limit);

    /** Sets published_at once; false when the id is unknown. Confirming again changes nothing. */
    boolean markPublished(UUID id, Instant now);

    /** Sets failed_at once and keeps the last reason; false when the id is unknown. */
    boolean markFailed(UUID id, String reason, Instant now);
}
