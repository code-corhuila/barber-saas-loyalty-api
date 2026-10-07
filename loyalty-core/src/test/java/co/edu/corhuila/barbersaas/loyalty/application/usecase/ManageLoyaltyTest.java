package co.edu.corhuila.barbersaas.loyalty.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.ApplicationException.IdempotencyKeyReused;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Created;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.LoyaltyUseCases.ConfigCommand;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.LoyaltyUseCases.RedemptionResult;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.LoyaltyUseCases.StickerResult;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Page;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.loyalty.domain.model.CouponStatus;
import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.InvalidStatusTransition;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardCoupon;
import co.edu.corhuila.barbersaas.loyalty.domain.model.TransactionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-LOY-001 #9 and HU-TENANT-001 #13, with doubles of every outbound port. */
class ManageLoyaltyTest {

    static final UUID SHOP = UUID.randomUUID();
    static final Page.Request FIRST = new Page.Request(1, 20);

    final UUID ownerId = UUID.randomUUID();
    final UUID clientId = UUID.randomUUID();
    final Caller owner = new Caller(ownerId.toString(), Role.ADMIN_BARBERSHOP, SHOP, "t");
    final Caller barber = new Caller(UUID.randomUUID().toString(), Role.BARBER, SHOP, "t");
    final Caller client = new Caller(clientId.toString(), Role.CLIENT, SHOP, "t");
    final Caller otherClient = new Caller(UUID.randomUUID().toString(), Role.CLIENT, SHOP, "t");
    final Caller otherOwner = new Caller(UUID.randomUUID().toString(), Role.ADMIN_BARBERSHOP, UUID.randomUUID(), "t");
    final Fakes.Repository repository = new Fakes.Repository();
    final Fakes.Appointments appointments = new Fakes.Appointments();
    final ManageLoyalty loyalty = new ManageLoyalty(repository, appointments,
            () -> Instant.parse("2026-10-07T14:00:00Z"), UUID::randomUUID);

    void program(int required) {
        loyalty.setConfig(owner, new ConfigCommand(required, "Free classic haircut", true));
    }

    StickerResult sticker(UUID appointmentId) {
        return loyalty.grantSticker(barber, clientId, appointmentId, "key-" + UUID.randomUUID()).value();
    }

    @Test
    void theOwnerConfiguresTheRuleAndEveryoneOfTheBarbershopReadsIt() {
        assertThrows(NotFound.class, () -> loyalty.config(client));
        program(8);
        loyalty.setConfig(owner, new ConfigCommand(10, "Free beard", true));

        assertEquals(10, loyalty.config(client).stickersRequired());
        assertEquals("Free beard", loyalty.config(barber).rewardDescription());
        assertThrows(Forbidden.class, () -> loyalty.setConfig(barber, new ConfigCommand(1, "x", true)));
        assertThrows(NotFound.class, () -> loyalty.config(otherOwner));
    }

    @Test
    void theFirstStickerCreatesTheCardAndEachWritesStickerGranted() {
        program(3);
        UUID appointment = UUID.randomUUID();
        appointments.rows.put(appointment, new Fakes.Appointments.Stored(SHOP, clientId));

        StickerResult first = sticker(appointment);
        StickerResult second = sticker(null);

        assertEquals(2, second.card().card().stickersCount());
        assertEquals(first.card().card().id(), second.card().card().id());
        assertEquals(UUID.fromString(barber.subject()), first.transaction().grantedByUserId());
        assertEquals(appointment, first.transaction().appointmentId());
        OutboxEvent event = repository.outbox.get(0);
        assertEquals("StickerGranted", event.type());
        assertEquals(appointment.toString(), event.payload().get("appointmentId"));
        assertEquals(3, event.payload().get("stickersRequired"));
        assertNull(repository.outbox.get(1).payload().get("appointmentId"));
    }

    @Test
    void aStickerForAnUnknownAppointmentIs404AndTheSameAppointmentTwiceIs422() {
        UUID appointment = UUID.randomUUID();
        appointments.rows.put(appointment, new Fakes.Appointments.Stored(SHOP, clientId));
        sticker(appointment);

        assertThrows(NotFound.class, () -> sticker(UUID.randomUUID()));
        assertThrows(BusinessRuleViolation.class, () -> sticker(appointment));
        assertEquals(1, repository.transactions.size());
    }

    @Test
    void aRetriedGrantDoesNotCreditTwice() {
        Created<StickerResult> first = loyalty.grantSticker(barber, clientId, null, "key-00000001");

        Created<StickerResult> again = loyalty.grantSticker(barber, clientId, null, "key-00000001");

        assertFalse(again.created());
        assertEquals(first.value().transaction().id(), again.value().transaction().id());
        assertEquals(1, again.value().card().card().stickersCount());
        assertThrows(IdempotencyKeyReused.class,
                () -> loyalty.grantSticker(barber, UUID.randomUUID(), null, "key-00000001"));
    }

    @Test
    void onlyStaffGrantsAndRedeems() {
        assertThrows(Forbidden.class, () -> loyalty.grantSticker(client, clientId, null, "key-00000002"));
        assertThrows(Forbidden.class, () -> loyalty.redeem(client, clientId, "key-00000003"));
        assertTrue(repository.cards.isEmpty());
    }

    @Test
    void aRedemptionSubtractsTheThresholdIssuesOneCouponAndWritesRewardRedeemed() {
        program(2);
        sticker(null);
        sticker(null);
        sticker(null);

        Created<RedemptionResult> r = loyalty.redeem(owner, clientId, "key-00000004");

        assertTrue(r.created());
        assertEquals(1, r.value().card().card().stickersCount());
        assertEquals(1, r.value().card().card().totalRewardsRedeemed());
        assertEquals(CouponStatus.ACTIVE, r.value().coupon().status());
        assertEquals(TransactionType.REWARD_REDEEMED, r.value().transaction().type());
        OutboxEvent event = repository.outbox.get(3);
        assertEquals("RewardRedeemed", event.type());
        assertEquals(r.value().coupon().id().toString(), event.payload().get("couponId"));
        assertEquals("Free classic haircut", event.payload().get("rewardDescription"));

        Created<RedemptionResult> again = loyalty.redeem(owner, clientId, "key-00000004");
        assertFalse(again.created());
        assertEquals(r.value().coupon().id(), again.value().coupon().id());
        assertEquals(r.value().transaction().id(), again.value().transaction().id());
        assertEquals(1, repository.coupons.size());
    }

    @Test
    void aRedemptionWithoutEnoughStickersOrProgramOrCardIsRefused() {
        assertThrows(NotFound.class, () -> loyalty.redeem(owner, clientId, "key-00000005"));
        sticker(null);
        assertThrows(BusinessRuleViolation.class, () -> loyalty.redeem(owner, clientId, "key-00000006"));
        program(2);
        BusinessRuleViolation e = assertThrows(BusinessRuleViolation.class,
                () -> loyalty.redeem(owner, clientId, "key-00000007"));
        assertEquals("The client has 1 of 2 stickers and cannot redeem yet", e.getMessage());
        sticker(null);
        repository.loseTheRedemptionRace = true;
        assertThrows(BusinessRuleViolation.class, () -> loyalty.redeem(owner, clientId, "key-00000008"));
        assertTrue(repository.coupons.isEmpty());
    }

    @Test
    void aClientSeesOnlyTheirCardTheirHistoryAndTheirCoupons() {
        program(1);
        UUID cardId = sticker(null).card().card().id();
        loyalty.redeem(owner, clientId, "key-00000009");

        assertEquals(cardId, loyalty.myCard(client).card().id());
        assertTrue(loyalty.myCard(client).config().active());
        assertEquals(2, loyalty.transactions(client, cardId, null, FIRST).total());
        assertEquals(1, loyalty.coupons(client, UUID.randomUUID(), null, FIRST).total(), "the client's filter is ignored");
        assertThrows(NotFound.class, () -> loyalty.card(otherClient, cardId));
        assertThrows(NotFound.class, () -> loyalty.transactions(otherClient, cardId, null, FIRST));
        assertThrows(NotFound.class, () -> loyalty.myCard(otherClient));
        assertEquals(0, loyalty.coupons(otherClient, null, null, FIRST).total());
    }

    @Test
    void staffListsCardsThatCanRedeem() {
        program(2);
        sticker(null);
        sticker(null);
        loyalty.grantSticker(barber, UUID.randomUUID(), null, "key-00000010");

        assertEquals(2, loyalty.cards(owner, null, null, FIRST).total());
        assertEquals(1, loyalty.cards(owner, null, true, FIRST).total());
        assertEquals(1, loyalty.cards(barber, clientId, null, FIRST).total());
        assertThrows(Forbidden.class, () -> loyalty.cards(client, null, null, FIRST));
    }

    @Test
    void aCouponIsUsedOnceOnAnAppointmentOfItsClient() {
        program(1);
        sticker(null);
        RewardCoupon coupon = loyalty.redeem(owner, clientId, "key-00000011").value().coupon();
        UUID mine = UUID.randomUUID();
        UUID someoneElses = UUID.randomUUID();
        appointments.rows.put(mine, new Fakes.Appointments.Stored(SHOP, clientId));
        appointments.rows.put(someoneElses, new Fakes.Appointments.Stored(SHOP, UUID.randomUUID()));

        assertThrows(NotFound.class, () -> loyalty.useCoupon(barber, coupon.id(), someoneElses));
        RewardCoupon used = loyalty.useCoupon(client, coupon.id(), mine);

        assertEquals(CouponStatus.USED, used.status());
        assertEquals(mine, used.appointmentId());
        assertThrows(InvalidStatusTransition.class, () -> loyalty.useCoupon(barber, coupon.id(), mine));
        assertThrows(NotFound.class, () -> loyalty.useCoupon(otherClient, coupon.id(), mine));
    }

    /** HU-TENANT-001: another barbershop never sees nor changes anything of this one. */
    @Test
    void anotherBarbershopSeesNothing() {
        program(1);
        UUID cardId = sticker(null).card().card().id();
        RewardCoupon coupon = loyalty.redeem(owner, clientId, "key-00000012").value().coupon();

        assertThrows(NotFound.class, () -> loyalty.card(otherOwner, cardId));
        assertThrows(NotFound.class, () -> loyalty.coupon(otherOwner, coupon.id()));
        assertThrows(NotFound.class, () -> loyalty.redeem(otherOwner, clientId, "key-00000013"));
        assertEquals(0, loyalty.cards(otherOwner, null, null, FIRST).total());
        assertEquals(0, loyalty.coupons(otherOwner, null, null, FIRST).total());
        assertEquals(List.of(SHOP), repository.cards.values().stream().map(c -> c.barbershopId()).toList());
    }
}
