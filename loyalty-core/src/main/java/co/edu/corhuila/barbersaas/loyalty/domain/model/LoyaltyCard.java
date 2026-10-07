package co.edu.corhuila.barbersaas.loyalty.domain.model;

import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.BusinessRuleViolation;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate of the loyalty domain (02-domain/entities-and-rules.md): a client's stickers in one
 * barbershop and its history. Every change returns the transaction it adds, so stickersCount is always
 * the net sum of the log (AGGR-INV-LOYAL-001). The card is created with the first sticker (DEC-LOY-03).
 */
public final class LoyaltyCard {

    private final UUID id;
    private final UUID barbershopId;
    private final UUID clientId;
    private int stickersCount;
    private int totalRewardsRedeemed;
    private Instant lastUpdated;

    private LoyaltyCard(UUID id, UUID barbershopId, UUID clientId, int stickersCount, int totalRewardsRedeemed,
                        Instant lastUpdated) {
        this.id = Objects.requireNonNull(id);
        this.barbershopId = Objects.requireNonNull(barbershopId);
        this.clientId = Objects.requireNonNull(clientId);
        this.stickersCount = stickersCount;
        this.totalRewardsRedeemed = totalRewardsRedeemed;
        this.lastUpdated = Objects.requireNonNull(lastUpdated);
    }

    /** An empty card, about to receive its first sticker. */
    public static LoyaltyCard open(UUID id, UUID barbershopId, UUID clientId, Instant now) {
        return new LoyaltyCard(id, barbershopId, clientId, 0, 0, now);
    }

    /** Rebuilds a stored card; no rule is checked again. */
    public static LoyaltyCard restore(UUID id, UUID barbershopId, UUID clientId, int stickersCount,
                                      int totalRewardsRedeemed, Instant lastUpdated) {
        return new LoyaltyCard(id, barbershopId, clientId, stickersCount, totalRewardsRedeemed, lastUpdated);
    }

    /** FR-013: one more sticker. Who may grant it is the use case's check (INV-LOYAL-002). */
    public LoyaltyTransaction grantSticker(UUID transactionId, UUID appointmentId, UUID grantedBy, Instant now) {
        stickersCount++;
        lastUpdated = now;
        return new LoyaltyTransaction(transactionId, id, appointmentId, TransactionType.STICKER_EARNED, grantedBy, now);
    }

    /**
     * INV-LOYAL-001 and INV-LOYAL-003: only an active program, and only with enough stickers. Subtracts the
     * barbershop's threshold and issues exactly one ACTIVE coupon in the same operation.
     */
    public Redemption redeem(RewardsConfig config, UUID transactionId, UUID couponId, UUID redeemedBy, Instant now) {
        if (config == null || !config.active()) {
            throw new BusinessRuleViolation("The barbershop has no active loyalty program");
        }
        if (stickersCount < config.stickersRequired()) {
            throw new BusinessRuleViolation("The client has " + stickersCount + " of " + config.stickersRequired()
                    + " stickers and cannot redeem yet");
        }
        stickersCount -= config.stickersRequired();
        totalRewardsRedeemed++;
        lastUpdated = now;
        LoyaltyTransaction transaction = new LoyaltyTransaction(transactionId, id, null,
                TransactionType.REWARD_REDEEMED, redeemedBy, now);
        return new Redemption(transaction, RewardCoupon.issue(couponId, barbershopId, clientId, now));
    }

    /** What a redemption produced, written together (DEC-LOY-02). */
    public record Redemption(LoyaltyTransaction transaction, RewardCoupon coupon) { }

    /** canRedeem of the contract: false when the barbershop has no active program. */
    public boolean canRedeem(RewardsConfig config) {
        return config != null && config.active() && stickersCount >= config.stickersRequired();
    }

    public UUID id() { return id; }
    public UUID barbershopId() { return barbershopId; }
    public UUID clientId() { return clientId; }
    public int stickersCount() { return stickersCount; }
    public int totalRewardsRedeemed() { return totalRewardsRedeemed; }
    public Instant lastUpdated() { return lastUpdated; }
}
