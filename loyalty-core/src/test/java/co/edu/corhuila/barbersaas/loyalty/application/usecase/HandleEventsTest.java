package co.edu.corhuila.barbersaas.loyalty.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.EventUseCases.IncomingEvent;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.EventUseCases.Outcome;
import co.edu.corhuila.barbersaas.loyalty.domain.model.CouponStatus;
import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyTransaction;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardCoupon;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardsConfig;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** DEC-LOY-01/05 and ADR-016: one sticker per completed appointment, however many times it arrives. */
class HandleEventsTest {

    static final UUID SHOP = UUID.randomUUID();
    final UUID client = UUID.randomUUID();
    final UUID barber = UUID.randomUUID();
    final Caller worker = new Caller("barber-saas-worker", Role.SERVICE, null, "t");
    final Fakes.Repository repository = new Fakes.Repository();
    final HandleEvents events = new HandleEvents(repository, repository, () -> Instant.parse("2026-10-07T14:00:00Z"),
            UUID::randomUUID);

    void program(boolean active) {
        repository.saveConfig(new RewardsConfig(UUID.randomUUID(), SHOP, 8, "Corte gratis", active));
    }

    IncomingEvent completed(UUID appointment, UUID clientId, UUID completedBy) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("appointmentId", appointment.toString());
        payload.put("barbershopId", SHOP.toString());
        payload.put("clientId", clientId == null ? null : clientId.toString());
        if (completedBy != null) {
            payload.put("completedBy", completedBy.toString());
        }
        return new IncomingEvent(UUID.randomUUID(), "AppointmentCompleted", completedBy == null ? 1 : 2, SHOP, payload);
    }

    /** AppointmentCreated version 2 (DEC-APPT-09); {@code coupon} null when booked without one. */
    IncomingEvent created(UUID appointment, UUID clientId, UUID coupon) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("appointmentId", appointment.toString());
        payload.put("barbershopId", SHOP.toString());
        payload.put("clientId", clientId == null ? null : clientId.toString());
        payload.put("couponId", coupon == null ? null : coupon.toString());
        return new IncomingEvent(UUID.randomUUID(), "AppointmentCreated", 2, SHOP, payload);
    }

    UUID activeCoupon(UUID owner) {
        UUID id = UUID.randomUUID();
        repository.coupons.put(id, RewardCoupon.restore(id, SHOP, owner, CouponStatus.ACTIVE, null,
                Instant.parse("2026-10-01T14:00:00Z"), null));
        return id;
    }

    // --- DEC-LOY-06: the coupon applied at booking ----------------------------------------------

    @Test
    void theCouponAppliedAtBookingBecomesUsedWithThatAppointment() {
        UUID coupon = activeCoupon(client);
        UUID appointment = UUID.randomUUID();
        IncomingEvent e = created(appointment, client, coupon);

        assertEquals(Outcome.PROCESSED, events.receive(worker, e).outcome());

        RewardCoupon used = repository.coupons.get(coupon);
        assertEquals(CouponStatus.USED, used.status());
        assertEquals(appointment, used.appointmentId());
        assertTrue(repository.processed.contains(e.id()));
        assertEquals(Outcome.DUPLICATE, events.receive(worker, e).outcome(), "a redelivery changes nothing");
    }

    @Test
    void anotherEventOfTheSameBookingFindsTheCouponAlreadyUsedByItAndIsADuplicate() {
        UUID coupon = activeCoupon(client);
        UUID appointment = UUID.randomUUID();
        events.receive(worker, created(appointment, client, coupon));

        assertEquals(Outcome.DUPLICATE, events.receive(worker, created(appointment, client, coupon)).outcome());
    }

    @Test
    void aBookingWithoutACouponIsIgnored() {
        IncomingEvent withoutCoupon = created(UUID.randomUUID(), client, null);
        IncomingEvent walkIn = created(UUID.randomUUID(), null, null);

        assertEquals(Outcome.IGNORED, events.receive(worker, withoutCoupon).outcome());
        assertEquals(Outcome.IGNORED, events.receive(worker, walkIn).outcome());
        assertEquals(Outcome.DUPLICATE, events.receive(worker, withoutCoupon).outcome());
    }

    @Test
    void aCouponUsedOnAnotherAppointmentUnknownOrOfAnotherClientNeedsAPerson() {
        UUID coupon = activeCoupon(client);
        events.receive(worker, created(UUID.randomUUID(), client, coupon));

        assertThrows(BusinessRuleViolation.class,
                () -> events.receive(worker, created(UUID.randomUUID(), client, coupon)));
        assertThrows(BusinessRuleViolation.class,
                () -> events.receive(worker, created(UUID.randomUUID(), client, UUID.randomUUID())));
        UUID someoneElses = activeCoupon(UUID.randomUUID());
        assertThrows(BusinessRuleViolation.class,
                () -> events.receive(worker, created(UUID.randomUUID(), client, someoneElses)));
        assertEquals(CouponStatus.ACTIVE, repository.coupons.get(someoneElses).status());
    }

    @Test
    void aCompletedAppointmentGrantsOneStickerInTheNameOfWhoCompletedIt() {
        program(true);
        UUID appointment = UUID.randomUUID();

        assertEquals(Outcome.PROCESSED, events.receive(worker, completed(appointment, client, barber)).outcome());

        LoyaltyTransaction t = repository.transactions.get(0);
        assertEquals(appointment, t.appointmentId());
        assertEquals(barber, t.grantedByUserId());
        assertEquals(1, repository.cardOf(SHOP, client).orElseThrow().stickersCount());
        assertEquals("StickerGranted", repository.outbox.get(0).type());
    }

    @Test
    void theSameEventTwiceOrAnotherEventOfTheSameAppointmentIsADuplicate() {
        program(true);
        UUID appointment = UUID.randomUUID();
        IncomingEvent e = completed(appointment, client, barber);

        events.receive(worker, e);

        assertEquals(Outcome.DUPLICATE, events.receive(worker, e).outcome());
        assertEquals(Outcome.DUPLICATE, events.receive(worker, completed(appointment, client, barber)).outcome());
        assertEquals(1, repository.cardOf(SHOP, client).orElseThrow().stickersCount());
        assertEquals(1, repository.transactions.size());
    }

    @Test
    void aWalkInOrABarbershopWithoutAnActiveProgramIsIgnoredAndStaysIgnored() {
        IncomingEvent noProgram = completed(UUID.randomUUID(), client, barber);
        assertEquals(Outcome.IGNORED, events.receive(worker, noProgram).outcome());
        program(false);
        assertEquals(Outcome.IGNORED, events.receive(worker, completed(UUID.randomUUID(), client, barber)).outcome());
        program(true);
        assertEquals(Outcome.IGNORED, events.receive(worker, completed(UUID.randomUUID(), null, barber)).outcome());

        assertEquals(Outcome.DUPLICATE, events.receive(worker, noProgram).outcome(), "a redelivery is a duplicate");
        assertTrue(repository.transactions.isEmpty());
    }

    @Test
    void anotherEventTypeOrAnEventWithoutCompletedByIs422() {
        program(true);
        IncomingEvent other = new IncomingEvent(UUID.randomUUID(), "AppointmentCancelled", 1, SHOP, Map.of());

        assertThrows(BusinessRuleViolation.class, () -> events.receive(worker, other));
        assertThrows(BusinessRuleViolation.class, () -> events.receive(worker, completed(UUID.randomUUID(), client, null)));
        assertTrue(repository.transactions.isEmpty());
    }

    @Test
    void onlyTheWorkersServiceTokenIsAccepted() {
        program(true);
        for (Caller caller : List.of(new Caller(UUID.randomUUID().toString(), Role.ADMIN_BARBERSHOP, SHOP, "t"),
                new Caller("barber-saas-workflow", Role.SERVICE, null, "t"))) {
            assertThrows(Forbidden.class, () -> events.receive(caller, completed(UUID.randomUUID(), client, barber)));
        }
        assertTrue(repository.transactions.isEmpty());
    }
}
