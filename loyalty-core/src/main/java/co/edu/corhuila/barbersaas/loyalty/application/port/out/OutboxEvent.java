package co.edu.corhuila.barbersaas.loyalty.application.port.out;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A row of loyalty.outbox_event, written with the change that caused it and relayed later by
 * barber-saas-worker (norm 5.3.11, ADR-016). The adapter adds the correlation id of the request.
 * StickerGranted and RewardRedeemed, payloads of 02-domain/domain-events.md.
 */
public record OutboxEvent(UUID id, UUID aggregateId, String type, Map<String, Object> payload, Instant occurredAt) {

    public static final String AGGREGATE_TYPE = "loyalty_card";

    /** A copy that keeps the order and allows null values: a manual sticker has no appointmentId. */
    public OutboxEvent {
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
