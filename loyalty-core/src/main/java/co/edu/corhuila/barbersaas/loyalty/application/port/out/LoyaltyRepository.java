package co.edu.corhuila.barbersaas.loyalty.application.port.out;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.Page;
import co.edu.corhuila.barbersaas.loyalty.domain.model.CouponStatus;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyTransaction;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardCoupon;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardsConfig;
import co.edu.corhuila.barbersaas.loyalty.domain.model.TransactionType;
import java.util.Optional;
import java.util.UUID;

/**
 * The loyalty schema (06-data/models.md §6 and §10). Every read is scoped by the tenant; a transaction
 * reaches it through its card. Counts change by deltas, so concurrent requests never lose a sticker
 * nor redeem the same ones twice.
 */
public interface LoyaltyRepository {

    /** The appointment already has its sticker: uq_loyalty_transaction_sticker_per_appointment. */
    class StickerAlreadyGranted extends RuntimeException {
        public StickerAlreadyGranted() {
            super("The appointment already has its sticker");
        }
    }

    /** A concurrent redemption took the stickers first: chk_loyalty_card_counts refused the change. */
    class NotEnoughStickers extends RuntimeException {
        public NotEnoughStickers() {
            super("A concurrent redemption took the stickers first");
        }
    }

    /** A concurrent first sticker created the client's card first: uq_loyalty_card_client_barbershop. */
    class CardTaken extends RuntimeException {
        public CardTaken() {
            super("The client's card was created by a concurrent request");
        }
    }

    /** The coupon was used by a concurrent request. */
    class CouponTaken extends RuntimeException {
        public CouponTaken() {
            super("The coupon has already been used");
        }
    }

    Optional<RewardsConfig> config(UUID tenant);

    void saveConfig(RewardsConfig config);

    Optional<LoyaltyCard> card(UUID tenant, UUID cardId);

    Optional<LoyaltyCard> cardOf(UUID tenant, UUID clientId);

    /**
     * Last updated first. {@code canRedeem} compares with {@code stickersRequired}; with no active
     * threshold (null) no card can redeem.
     */
    Page<LoyaltyCard> cards(UUID tenant, UUID clientId, Boolean canRedeem, Integer stickersRequired, Page.Request page);

    /** Of a card already checked against the tenant; most recent first. */
    Page<LoyaltyTransaction> transactions(UUID cardId, TransactionType type, Page.Request page);

    Optional<LoyaltyTransaction> transaction(UUID tenant, UUID transactionId);

    /** The REWARD_REDEEMED transaction written with this coupon (same card and instant). */
    Optional<LoyaltyTransaction> redemptionOf(RewardCoupon coupon);

    Optional<RewardCoupon> coupon(UUID tenant, UUID couponId);

    /** Most recent first; null filters do not filter. */
    Page<RewardCoupon> coupons(UUID tenant, UUID clientId, CouponStatus status, Page.Request page);

    Optional<Idempotency.Stored> findKey(String key, String operation);

    /**
     * The card (inserted when new, otherwise +1), its transaction, its key and its event in ONE
     * transaction. Returns the card as stored.
     */
    LoyaltyCard saveSticker(LoyaltyCard card, boolean newCard, LoyaltyTransaction transaction, Idempotency.Key key,
                            OutboxEvent event);

    /** The card (−threshold, +1 redemption), the transaction, the coupon, the key and the event in ONE transaction. */
    LoyaltyCard saveRedemption(LoyaltyCard card, int stickersRequired, LoyaltyTransaction transaction,
                               RewardCoupon coupon, Idempotency.Key key, OutboxEvent event);

    /** ACTIVE → USED only if still ACTIVE; otherwise {@link CouponTaken}. */
    void saveCouponUse(RewardCoupon coupon);
}
