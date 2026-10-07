package co.edu.corhuila.barbersaas.loyalty.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.OutboxRelayUseCases.EventEnvelope;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.OutboxStore;
import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.InvalidValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** DEC-LOY-04, ADR-016: only the worker reads and confirms the outbox. */
class RelayOutboxTest {

    static final Instant NOW = Instant.parse("2026-10-06T14:00:00Z");

    final Caller worker = new Caller("barber-saas-worker", Role.SERVICE, null, "token");
    final FakeOutbox outbox = new FakeOutbox();
    final RelayOutbox relay = new RelayOutbox(outbox, () -> NOW);

    static final class FakeOutbox implements OutboxStore {
        final List<Stored> rows = new ArrayList<>();
        final Map<UUID, String> published = new HashMap<>();
        final Map<UUID, String> failed = new HashMap<>();
        int askedLimit;

        @Override
        public List<Stored> pending(int limit) {
            askedLimit = limit;
            return rows.stream().filter(s -> !published.containsKey(s.event().id()) && !failed.containsKey(s.event().id()))
                    .limit(limit).toList();
        }

        @Override
        public boolean markPublished(UUID id, Instant now) {
            boolean known = rows.stream().anyMatch(s -> s.event().id().equals(id));
            if (known) {
                published.putIfAbsent(id, now.toString());
            }
            return known;
        }

        @Override
        public boolean markFailed(UUID id, String reason, Instant now) {
            boolean known = rows.stream().anyMatch(s -> s.event().id().equals(id));
            if (known) {
                failed.put(id, reason);
            }
            return known;
        }
    }

    OutboxEvent event(UUID shop) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("cardId", UUID.randomUUID().toString());
        payload.put("barbershopId", shop == null ? null : shop.toString());
        return new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "StickerGranted", payload, NOW);
    }

    @Test
    void theWorkerGetsEnvelopesWithTheTenantAndCorrelationOfEachRow() {
        UUID shop = UUID.randomUUID();
        OutboxEvent e = event(shop);
        outbox.rows.add(new OutboxStore.Stored(e, "corr-1"));

        EventEnvelope envelope = relay.pending(worker, 50).get(0);

        assertEquals(50, outbox.askedLimit);
        assertEquals(e.id(), envelope.id());
        assertEquals("StickerGranted", envelope.type());
        assertEquals(1, envelope.version());
        assertEquals("loyalty_card", envelope.aggregateType());
        assertEquals(e.aggregateId(), envelope.aggregateId());
        assertEquals(shop, envelope.barbershopId());
        assertEquals("corr-1", envelope.correlationId());
        assertEquals(e.payload(), envelope.payload());
    }

    @Test
    void anEventWithoutATenantTravelsWithoutOne() {
        outbox.rows.add(new OutboxStore.Stored(event(null), "corr-2"));

        assertNull(relay.pending(worker, 1).get(0).barbershopId());
    }

    @Test
    void aConfirmedOrFailedEventLeavesThePendingListAndAnUnknownOneIsNotFound() {
        OutboxEvent delivered = event(UUID.randomUUID());
        OutboxEvent undeliverable = event(UUID.randomUUID());
        outbox.rows.add(new OutboxStore.Stored(delivered, "c"));
        outbox.rows.add(new OutboxStore.Stored(undeliverable, "c"));

        relay.published(worker, delivered.id());
        relay.published(worker, delivered.id());
        relay.failed(worker, undeliverable.id(), "loyalty-api 422 UNKNOWN_EVENT_TYPE");

        assertEquals(List.of(), relay.pending(worker, 10));
        assertEquals("loyalty-api 422 UNKNOWN_EVENT_TYPE", outbox.failed.get(undeliverable.id()));
        assertThrows(NotFound.class, () -> relay.published(worker, UUID.randomUUID()));
        assertThrows(NotFound.class, () -> relay.failed(worker, UUID.randomUUID(), "x"));
    }

    @Test
    void aFailureNeedsAReasonOfAtMost500Characters() {
        OutboxEvent e = event(UUID.randomUUID());
        outbox.rows.add(new OutboxStore.Stored(e, "c"));

        assertThrows(InvalidValue.class, () -> relay.failed(worker, e.id(), " "));
        assertThrows(InvalidValue.class, () -> relay.failed(worker, e.id(), "x".repeat(501)));
        assertEquals(1, relay.pending(worker, 10).size());
    }

    @Test
    void onlyTheWorkersServiceTokenIsAccepted() {
        Caller schedule = new Caller("barber-saas-schedule-api", Role.SERVICE, null, "t");
        Caller admin = new Caller(UUID.randomUUID().toString(), Role.ADMIN_BARBERSHOP, UUID.randomUUID(), "t");
        Caller impostor = new Caller("barber-saas-worker", Role.ADMIN_BARBERSHOP, UUID.randomUUID(), "t");

        for (Caller caller : List.of(schedule, admin, impostor)) {
            assertThrows(Forbidden.class, () -> relay.pending(caller, 10));
            assertThrows(Forbidden.class, () -> relay.published(caller, UUID.randomUUID()));
            assertThrows(Forbidden.class, () -> relay.failed(caller, UUID.randomUUID(), "x"));
        }
    }
}
