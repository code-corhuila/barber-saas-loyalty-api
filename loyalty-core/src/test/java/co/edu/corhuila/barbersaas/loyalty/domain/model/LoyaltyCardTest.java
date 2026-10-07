package co.edu.corhuila.barbersaas.loyalty.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.InvalidStatusTransition;
import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.InvalidValue;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard.Redemption;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** LoyaltyCard and RewardCoupon: FR-013 to FR-015, INV-LOYAL-001/003 (HU-LOY-001 #9). */
class LoyaltyCardTest {

    static final Instant NOW = Instant.parse("2026-10-07T14:00:00Z");
    final UUID shop = UUID.randomUUID();
    final UUID staff = UUID.randomUUID();

    RewardsConfig config(int required, boolean active) {
        return new RewardsConfig(UUID.randomUUID(), shop, required, "Free classic haircut", active);
    }

    LoyaltyCard cardWith(int stickers) {
        LoyaltyCard card = LoyaltyCard.open(UUID.randomUUID(), shop, UUID.randomUUID(), NOW);
        for (int i = 0; i < stickers; i++) {
            card.grantSticker(UUID.randomUUID(), null, staff, NOW);
        }
        return card;
    }

    @Test
    void aStickerAddsOneAndIsRecordedWithWhoGaveItAndTheAppointment() {
        LoyaltyCard card = cardWith(0);
        UUID appointment = UUID.randomUUID();

        LoyaltyTransaction t = card.grantSticker(UUID.randomUUID(), appointment, staff, NOW);

        assertEquals(1, card.stickersCount());
        assertEquals(TransactionType.STICKER_EARNED, t.type());
        assertEquals(appointment, t.appointmentId());
        assertEquals(staff, t.grantedByUserId());
        assertEquals(card.id(), t.cardId());
    }

    @Test
    void redeemingSubtractsTheBarbershopsThresholdAndIssuesOneActiveCoupon() {
        LoyaltyCard card = cardWith(9);

        Redemption r = card.redeem(config(8, true), UUID.randomUUID(), UUID.randomUUID(), staff, NOW);

        assertEquals(1, card.stickersCount());
        assertEquals(1, card.totalRewardsRedeemed());
        assertEquals(TransactionType.REWARD_REDEEMED, r.transaction().type());
        assertNull(r.transaction().appointmentId());
        assertEquals(CouponStatus.ACTIVE, r.coupon().status());
        assertEquals(card.clientId(), r.coupon().clientId());
        assertEquals(shop, r.coupon().barbershopId());
    }

    @Test
    void notEnoughStickersOrNoActiveProgramIsRefusedAndChangesNothing() {
        LoyaltyCard card = cardWith(6);

        BusinessRuleViolation e = assertThrows(BusinessRuleViolation.class,
                () -> card.redeem(config(8, true), UUID.randomUUID(), UUID.randomUUID(), staff, NOW));
        assertThrows(BusinessRuleViolation.class,
                () -> card.redeem(config(1, false), UUID.randomUUID(), UUID.randomUUID(), staff, NOW));
        assertThrows(BusinessRuleViolation.class, () -> card.redeem(null, UUID.randomUUID(), UUID.randomUUID(), staff, NOW));

        assertEquals("The client has 6 of 8 stickers and cannot redeem yet", e.getMessage());
        assertEquals(6, card.stickersCount());
        assertEquals(0, card.totalRewardsRedeemed());
    }

    @Test
    void canRedeemFollowsTheActiveThreshold() {
        LoyaltyCard card = cardWith(8);

        assertTrue(card.canRedeem(config(8, true)));
        assertFalse(card.canRedeem(config(9, true)));
        assertFalse(card.canRedeem(config(1, false)));
        assertFalse(card.canRedeem(null));
    }

    @Test
    void aCouponIsUsedOnlyOnce() {
        RewardCoupon coupon = cardWith(1).redeem(config(1, true), UUID.randomUUID(), UUID.randomUUID(), staff, NOW).coupon();
        UUID appointment = UUID.randomUUID();

        coupon.use(appointment, NOW);

        assertEquals(CouponStatus.USED, coupon.status());
        assertEquals(appointment, coupon.appointmentId());
        assertEquals(NOW, coupon.usedAt());
        assertThrows(InvalidStatusTransition.class, () -> coupon.use(UUID.randomUUID(), NOW));
    }

    @Test
    void theRuleNeedsAThresholdAndADescription() {
        assertThrows(InvalidValue.class, () -> config(0, true));
        assertThrows(InvalidValue.class, () -> new RewardsConfig(UUID.randomUUID(), shop, 5, " ", true));
        RewardsConfig first = RewardsConfig.set(null, UUID.randomUUID(), shop, 10, " Free cut ", true);
        RewardsConfig replaced = RewardsConfig.set(first, UUID.randomUUID(), shop, 8, "Free beard", false);
        assertEquals("Free cut", first.rewardDescription());
        assertEquals(first.id(), replaced.id());
        assertEquals(8, replaced.stickersRequired());
    }
}
