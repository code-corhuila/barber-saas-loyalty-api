package co.edu.corhuila.barbersaas.loyalty.application.usecase;

import co.edu.corhuila.barbersaas.loyalty.application.port.out.IdGenerator;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyTransaction;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardCoupon;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardsConfig;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * StickerGranted and RewardRedeemed with the payloads of 02-domain/domain-events.md (DEC-LOY-04),
 * built from the card as it is after the change, so notifications can act without calling back.
 * RewardRedeemed carries couponId and no couponCode: the coupon has no code in the contract nor in the
 * table (asked in barber-saas-docs#87).
 */
final class Events {

    static final String STICKER_GRANTED = "StickerGranted";
    static final String REWARD_REDEEMED = "RewardRedeemed";

    private Events() {
    }

    static OutboxEvent stickerGranted(LoyaltyCard card, LoyaltyTransaction t, RewardsConfig config, IdGenerator ids,
                                      Instant now) {
        Map<String, Object> payload = card(card, t);
        payload.put("appointmentId", t.appointmentId() == null ? null : t.appointmentId().toString());
        payload.put("stickersCount", card.stickersCount());
        payload.put("stickersRequired", config == null || !config.active() ? null : config.stickersRequired());
        return new OutboxEvent(ids.next(), card.id(), STICKER_GRANTED, payload, now);
    }

    static OutboxEvent rewardRedeemed(LoyaltyCard card, LoyaltyTransaction t, RewardCoupon coupon, RewardsConfig config,
                                      IdGenerator ids, Instant now) {
        Map<String, Object> payload = card(card, t);
        payload.put("couponId", coupon.id().toString());
        payload.put("rewardDescription", config.rewardDescription());
        payload.put("stickersCount", card.stickersCount());
        return new OutboxEvent(ids.next(), card.id(), REWARD_REDEEMED, payload, now);
    }

    private static Map<String, Object> card(LoyaltyCard card, LoyaltyTransaction t) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardId", card.id().toString());
        payload.put("barbershopId", card.barbershopId().toString());
        payload.put("clientId", card.clientId().toString());
        payload.put("transactionId", t.id().toString());
        return payload;
    }
}
