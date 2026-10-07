package co.edu.corhuila.barbersaas.loyalty.application.port.in;

import co.edu.corhuila.barbersaas.loyalty.domain.model.CouponStatus;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyTransaction;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardCoupon;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardsConfig;
import co.edu.corhuila.barbersaas.loyalty.domain.model.TransactionType;
import java.util.UUID;

/** The operations of loyalty-service.yaml under /api/v1/loyalty; the tenant is always the token's. */
public interface LoyaltyUseCases {

    record ConfigCommand(int stickersRequired, String rewardDescription, boolean active) { }

    /** A card with the barbershop's rule, which gives stickersRequired, canRedeem and the reward. */
    record CardView(LoyaltyCard card, RewardsConfig config) { }

    record StickerResult(LoyaltyTransaction transaction, CardView card) { }

    record RedemptionResult(LoyaltyTransaction transaction, CardView card, RewardCoupon coupon) { }

    /** Staff and clients; NotFound while the barbershop has not configured it. */
    RewardsConfig config(Caller caller);

    /** ADMIN_BARBERSHOP: creates or replaces it; stickers already earned are not touched. */
    RewardsConfig setConfig(Caller caller, ConfigCommand command);

    /** Staff: last updated first; {@code canRedeem} compares with the active threshold. */
    Page<CardView> cards(Caller caller, UUID clientId, Boolean canRedeem, Page.Request page);

    /** CLIENT: their card at the token's barbershop; NotFound before the first sticker. */
    CardView myCard(Caller caller);

    /** Staff, or the client who owns it; anything else is NotFound. */
    CardView card(Caller caller, UUID cardId);

    /** Most recent first; same visibility as the card. */
    Page<LoyaltyTransaction> transactions(Caller caller, UUID cardId, TransactionType type, Page.Request page);

    /** FR-013, staff only (INV-LOYAL-002). Creates the card on the first sticker. Idempotent. */
    Created<StickerResult> grantSticker(Caller caller, UUID clientId, UUID appointmentId, String idempotencyKey);

    /** FR-014/015, staff only; atomic (DEC-LOY-02). Idempotent. */
    Created<RedemptionResult> redeem(Caller caller, UUID clientId, String idempotencyKey);

    /** Staff see the barbershop's (optionally one client's); a client only theirs, whatever clientId says. */
    Page<RewardCoupon> coupons(Caller caller, UUID clientId, CouponStatus status, Page.Request page);

    RewardCoupon coupon(Caller caller, UUID couponId);

    /** FR-010: on an appointment of the same barbershop and client; only once. */
    RewardCoupon useCoupon(Caller caller, UUID couponId, UUID appointmentId);
}
