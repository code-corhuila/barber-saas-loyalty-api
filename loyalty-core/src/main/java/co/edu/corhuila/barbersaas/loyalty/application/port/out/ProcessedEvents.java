package co.edu.corhuila.barbersaas.loyalty.application.port.out;

import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyTransaction;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardCoupon;
import java.util.UUID;

/**
 * loyalty.processed_event (06-data/models.md §10): the ids of the events already handled. The row is
 * written in the same transaction as the effect, so a redelivered event finds it and does nothing.
 */
public interface ProcessedEvents {

    /** Another delivery of the same event was processed first: pk_processed_event. */
    class AlreadyProcessed extends RuntimeException {
        public AlreadyProcessed() {
            super("The event was already processed");
        }
    }

    boolean isProcessed(UUID eventId);

    /** An event that needed no change (IGNORED, or a sticker that already existed); idempotent. */
    void markProcessed(UUID eventId, String eventType);

    /**
     * The sticker of an event: the card (inserted when new, otherwise +1), its transaction, its
     * StickerGranted and the processed_event row, all in ONE transaction. Returns the card as stored.
     */
    LoyaltyCard saveEventSticker(LoyaltyCard card, boolean newCard, LoyaltyTransaction transaction, OutboxEvent event,
                                 UUID eventId, String eventType);

    /**
     * The coupon applied at booking (DEC-LOY-06): ACTIVE → USED and the processed_event row in ONE
     * transaction. {@link LoyaltyRepository.CouponTaken} when it is no longer ACTIVE.
     */
    void saveEventCouponUse(RewardCoupon coupon, UUID eventId, String eventType);
}
