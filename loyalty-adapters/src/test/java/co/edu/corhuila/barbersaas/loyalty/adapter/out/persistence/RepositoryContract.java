package co.edu.corhuila.barbersaas.loyalty.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.Page;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.Idempotency.KeyTaken;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository.CardTaken;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository.CouponTaken;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository.NotEnoughStickers;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository.StickerAlreadyGranted;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.OutboxStore;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.ProcessedEvents;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.ProcessedEvents.AlreadyProcessed;
import co.edu.corhuila.barbersaas.loyalty.domain.model.CouponStatus;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard.Redemption;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyTransaction;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardCoupon;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardsConfig;
import co.edu.corhuila.barbersaas.loyalty.domain.model.TransactionType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What every LoyaltyRepository must do, in memory or in PostgreSQL. */
abstract class RepositoryContract {

    final UUID shop = UUID.randomUUID();
    final UUID client = UUID.randomUUID();
    final UUID staff = UUID.randomUUID();
    final Page.Request page = new Page.Request(1, 10);

    abstract LoyaltyRepository repository();

    Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    Idempotency.Key key(String operation) {
        return new Idempotency.Key("it-" + UUID.randomUUID(), operation, "hash");
    }

    OutboxEvent event(LoyaltyCard card, String type) {
        return new OutboxEvent(UUID.randomUUID(), card.id(), type, Map.of("cardId", card.id().toString()), now());
    }

    /** One more sticker, opening the card when the client has none yet. */
    LoyaltyCard sticker(UUID appointmentId) {
        Instant now = now();
        var existing = repository().cardOf(shop, client);
        LoyaltyCard card = existing.orElseGet(() -> LoyaltyCard.open(UUID.randomUUID(), shop, client, now));
        LoyaltyTransaction t = card.grantSticker(UUID.randomUUID(), appointmentId, staff, now);
        return repository().saveSticker(card, existing.isEmpty(), t, key("POST /api/v1/loyalty/stickers"),
                event(card, "StickerGranted"));
    }

    @Test
    void theRuleIsOnePerBarbershopAndKeepsItsId() {
        RewardsConfig rule = new RewardsConfig(UUID.randomUUID(), shop, 10, "Free cut", true);
        repository().saveConfig(rule);
        repository().saveConfig(new RewardsConfig(rule.id(), shop, 8, "Free beard", false));

        RewardsConfig read = repository().config(shop).orElseThrow();
        assertEquals(new RewardsConfig(rule.id(), shop, 8, "Free beard", false), read);
        assertTrue(repository().config(UUID.randomUUID()).isEmpty());
    }

    @Test
    void stickersAddByDeltaAndTheCardIsReadOnlyInItsBarbershop() {
        UUID appointment = UUID.randomUUID();
        LoyaltyCard first = sticker(appointment);
        LoyaltyCard second = sticker(null);

        assertEquals(1, first.stickersCount());
        assertEquals(2, second.stickersCount());
        assertEquals(first.id(), repository().cardOf(shop, client).orElseThrow().id());
        assertTrue(repository().card(UUID.randomUUID(), first.id()).isEmpty(), "another barbershop sees nothing");
        assertEquals(2, repository().transactions(first.id(), null, page).total());
        assertEquals(appointment, repository().transactions(first.id(), TransactionType.STICKER_EARNED, page)
                .items().get(1).appointmentId());
        assertThrows(StickerAlreadyGranted.class, () -> sticker(appointment));
        assertEquals(2, repository().cardOf(shop, client).orElseThrow().stickersCount(), "nothing stored");
    }

    @Test
    void aSecondCardForTheSameClientIsRefused() {
        sticker(null);
        LoyaltyCard twin = LoyaltyCard.open(UUID.randomUUID(), shop, client, now());
        LoyaltyTransaction t = twin.grantSticker(UUID.randomUUID(), null, staff, now());

        assertThrows(CardTaken.class, () -> repository().saveSticker(twin, true, t, key("op"), event(twin, "x")));
    }

    @Test
    void aRedemptionStoresTheCouponAndCannotTakeStickersTwice() {
        sticker(null);
        LoyaltyCard card = sticker(null);
        RewardsConfig rule = new RewardsConfig(UUID.randomUUID(), shop, 2, "Free cut", true);
        LoyaltyCard stale = LoyaltyCard.restore(card.id(), shop, client, card.stickersCount(), 0, card.lastUpdated());
        Redemption r = card.redeem(rule, UUID.randomUUID(), UUID.randomUUID(), staff, now());

        LoyaltyCard after = repository().saveRedemption(card, 2, r.transaction(), r.coupon(),
                key("POST /api/v1/loyalty/redemptions"), event(card, "RewardRedeemed"));

        assertEquals(0, after.stickersCount());
        assertEquals(1, after.totalRewardsRedeemed());
        assertEquals(r.transaction().id(), repository().redemptionOf(r.coupon()).orElseThrow().id());
        assertEquals(CouponStatus.ACTIVE, repository().coupon(shop, r.coupon().id()).orElseThrow().status());
        assertEquals(1, repository().coupons(shop, client, CouponStatus.ACTIVE, page).total());
        assertEquals(0, repository().coupons(UUID.randomUUID(), null, null, page).total());
        Redemption again = stale.redeem(rule, UUID.randomUUID(), UUID.randomUUID(), staff, now());   // read before
        assertThrows(NotEnoughStickers.class, () -> repository().saveRedemption(stale, 2, again.transaction(),
                again.coupon(), key("POST /api/v1/loyalty/redemptions"), event(stale, "RewardRedeemed")));
        assertEquals(1, repository().coupons(shop, null, null, page).total(), "nothing stored");
    }

    @Test
    void aCouponIsUsedOnceAndAKeyIsStoredOnce() {
        LoyaltyCard card = sticker(null);
        RewardsConfig rule = new RewardsConfig(UUID.randomUUID(), shop, 1, "Free cut", true);
        Redemption r = card.redeem(rule, UUID.randomUUID(), UUID.randomUUID(), staff, now());
        Idempotency.Key key = key("POST /api/v1/loyalty/redemptions");
        repository().saveRedemption(card, 1, r.transaction(), r.coupon(), key, event(card, "RewardRedeemed"));
        r.coupon().use(UUID.randomUUID(), now());

        repository().saveCouponUse(r.coupon());

        assertEquals(CouponStatus.USED, repository().coupon(shop, r.coupon().id()).orElseThrow().status());
        assertThrows(CouponTaken.class, () -> repository().saveCouponUse(r.coupon()));
        assertEquals(r.coupon().id(), repository().findKey(key.key(), key.operation()).orElseThrow().resourceId());
        LoyaltyTransaction t = card.grantSticker(UUID.randomUUID(), null, staff, now());
        assertThrows(KeyTaken.class, () -> repository().saveSticker(card, false, t, key, event(card, "x")));
    }

    @Test
    void cardsFilterByClientAndByTheThreshold() {
        sticker(null);
        sticker(null);

        assertEquals(1, repository().cards(shop, null, true, 2, page).total());
        assertEquals(0, repository().cards(shop, null, true, 3, page).total());
        assertEquals(1, repository().cards(shop, null, false, 3, page).total());
        assertEquals(0, repository().cards(shop, null, true, null, page).total(), "no active rule");
        assertEquals(1, repository().cards(shop, client, null, null, page).total());
        assertEquals(0, repository().cards(shop, UUID.randomUUID(), null, null, page).total());
    }

    /** DEC-LOY-04: the worker reads the events written with each change and confirms them. */
    @Test
    void theEventsOfAStickerArePendingUntilConfirmedOrFailed() {
        OutboxStore outbox = (OutboxStore) repository();
        Instant now = now();
        LoyaltyCard card = LoyaltyCard.open(UUID.randomUUID(), shop, client, now);
        LoyaltyTransaction t = card.grantSticker(UUID.randomUUID(), null, staff, now);
        OutboxEvent granted = new OutboxEvent(UUID.randomUUID(), card.id(), "StickerGranted",
                Map.of("barbershopId", shop.toString()), Instant.parse("2000-01-01T00:00:00Z"));
        repository().saveSticker(card, true, t, key("POST /api/v1/loyalty/stickers"), granted);

        OutboxStore.Stored oldest = outbox.pending(1).get(0);

        assertEquals(granted.id(), oldest.event().id());
        assertEquals(shop.toString(), oldest.event().payload().get("barbershopId"));
        assertTrue(outbox.markPublished(granted.id(), now));
        assertTrue(outbox.markPublished(granted.id(), now), "confirming again is accepted");
        assertTrue(outbox.pending(100).stream().noneMatch(st -> st.event().id().equals(granted.id())));
        assertTrue(outbox.markFailed(granted.id(), "notifications-api 422", now));
        assertFalse(outbox.markPublished(UUID.randomUUID(), now));
    }

    /** DEC-LOY-06: the coupon of a booking and its processed_event row commit together, or neither does. */
    @Test
    void theCouponOfABookingIsUsedWithItsEventOrNotAtAll() {
        ProcessedEvents processed = (ProcessedEvents) repository();
        LoyaltyCard card = sticker(null);
        RewardsConfig rule = new RewardsConfig(UUID.randomUUID(), shop, 1, "Free cut", true);
        Redemption r = card.redeem(rule, UUID.randomUUID(), UUID.randomUUID(), staff, now());
        repository().saveRedemption(card, 1, r.transaction(), r.coupon(), key("POST /api/v1/loyalty/redemptions"),
                event(card, "RewardRedeemed"));
        UUID appointment = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        r.coupon().use(appointment, now());

        processed.saveEventCouponUse(r.coupon(), eventId, "AppointmentCreated");

        RewardCoupon stored = repository().coupon(shop, r.coupon().id()).orElseThrow();
        assertEquals(CouponStatus.USED, stored.status());
        assertEquals(appointment, stored.appointmentId());
        assertTrue(processed.isProcessed(eventId));
        UUID other = UUID.randomUUID();
        assertThrows(CouponTaken.class, () -> processed.saveEventCouponUse(r.coupon(), other, "AppointmentCreated"));
        assertFalse(processed.isProcessed(other), "the processed_event row is rolled back with the coupon");
    }

    /** ADR-016: an event's sticker and its processed_event row commit together; a second delivery is refused. */
    @Test
    void anEventIsProcessedOnceWithItsSticker() {
        ProcessedEvents processed = (ProcessedEvents) repository();
        UUID eventId = UUID.randomUUID();
        Instant now = now();
        LoyaltyCard card = LoyaltyCard.open(UUID.randomUUID(), shop, client, now);
        LoyaltyTransaction t = card.grantSticker(UUID.randomUUID(), UUID.randomUUID(), staff, now);

        assertFalse(processed.isProcessed(eventId));
        LoyaltyCard stored = processed.saveEventSticker(card, true, t, event(card, "StickerGranted"), eventId,
                "AppointmentCompleted");

        assertEquals(1, stored.stickersCount());
        assertTrue(processed.isProcessed(eventId));
        LoyaltyTransaction again = card.grantSticker(UUID.randomUUID(), UUID.randomUUID(), staff, now);
        assertThrows(AlreadyProcessed.class, () -> processed.saveEventSticker(card, false, again,
                event(card, "StickerGranted"), eventId, "AppointmentCompleted"));
        assertEquals(1, repository().cardOf(shop, client).orElseThrow().stickersCount(), "nothing stored");
        UUID ignored = UUID.randomUUID();
        processed.markProcessed(ignored, "AppointmentCompleted");
        processed.markProcessed(ignored, "AppointmentCompleted");
        assertTrue(processed.isProcessed(ignored));
    }
}
