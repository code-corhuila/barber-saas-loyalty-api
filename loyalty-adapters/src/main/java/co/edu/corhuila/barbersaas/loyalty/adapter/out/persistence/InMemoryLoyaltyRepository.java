package co.edu.corhuila.barbersaas.loyalty.adapter.out.persistence;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.Page;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.Idempotency.KeyTaken;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.OutboxStore;
import co.edu.corhuila.barbersaas.loyalty.domain.model.CouponStatus;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyTransaction;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardCoupon;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardsConfig;
import co.edu.corhuila.barbersaas.loyalty.domain.model.TransactionType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.MDC;

/**
 * Used when DATABASE_URL is empty. It stores copies and applies the same deltas and rules as
 * PostgreSQL; writes are synchronized so concurrent requests behave as with the database.
 */
public class InMemoryLoyaltyRepository implements LoyaltyRepository, OutboxStore {

    private final Map<UUID, RewardsConfig> configs = new ConcurrentHashMap<>();
    private final Map<UUID, LoyaltyCard> cards = new ConcurrentHashMap<>();
    private final List<LoyaltyTransaction> transactions = new CopyOnWriteArrayList<>();
    private final Map<UUID, RewardCoupon> coupons = new ConcurrentHashMap<>();
    private final Map<String, Idempotency.Stored> keys = new ConcurrentHashMap<>();
    private final List<OutboxEvent> outbox = new CopyOnWriteArrayList<>();
    /** Per event: its correlation id, and whether it was published or set aside as failed. */
    private final Map<UUID, Relay> relay = new ConcurrentHashMap<>();

    private record Relay(String correlationId, Instant publishedAt, Instant failedAt, String lastError) { }

    @Override
    public Optional<RewardsConfig> config(UUID tenant) {
        return Optional.ofNullable(configs.get(tenant));
    }

    @Override
    public void saveConfig(RewardsConfig config) {
        configs.merge(config.barbershopId(), config, (old, now) -> new RewardsConfig(old.id(), now.barbershopId(),
                now.stickersRequired(), now.rewardDescription(), now.active()));
    }

    @Override
    public Optional<LoyaltyCard> card(UUID tenant, UUID cardId) {
        return Optional.ofNullable(cards.get(cardId)).filter(c -> c.barbershopId().equals(tenant)).map(this::copy);
    }

    @Override
    public Optional<LoyaltyCard> cardOf(UUID tenant, UUID clientId) {
        return cards.values().stream().filter(c -> c.barbershopId().equals(tenant) && c.clientId().equals(clientId))
                .findFirst().map(this::copy);
    }

    @Override
    public Page<LoyaltyCard> cards(UUID tenant, UUID clientId, Boolean canRedeem, Integer required, Page.Request page) {
        return Page.of(cards.values().stream().filter(c -> c.barbershopId().equals(tenant))
                .filter(c -> clientId == null || clientId.equals(c.clientId()))
                .filter(c -> canRedeem == null || canRedeem == (required != null && c.stickersCount() >= required))
                .sorted(Comparator.comparing(LoyaltyCard::lastUpdated).reversed().thenComparing(LoyaltyCard::id))
                .map(this::copy).toList(), page);
    }

    @Override
    public Page<LoyaltyTransaction> transactions(UUID cardId, TransactionType type, Page.Request page) {
        return Page.of(transactions.stream().filter(t -> t.cardId().equals(cardId))
                .filter(t -> type == null || type == t.type())
                .sorted(Comparator.comparing(LoyaltyTransaction::createdAt).reversed().thenComparing(LoyaltyTransaction::id))
                .toList(), page);
    }

    @Override
    public Optional<LoyaltyTransaction> transaction(UUID tenant, UUID transactionId) {
        return transactions.stream().filter(t -> t.id().equals(transactionId))
                .filter(t -> card(tenant, t.cardId()).isPresent()).findFirst();
    }

    @Override
    public Optional<LoyaltyTransaction> redemptionOf(RewardCoupon coupon) {
        return transactions.stream().filter(t -> t.type() == TransactionType.REWARD_REDEEMED)
                .filter(t -> t.createdAt().equals(coupon.createdAt()))
                .filter(t -> cardOf(coupon.barbershopId(), coupon.clientId()).map(c -> c.id().equals(t.cardId())).orElse(false))
                .findFirst();
    }

    @Override
    public Optional<RewardCoupon> coupon(UUID tenant, UUID couponId) {
        return Optional.ofNullable(coupons.get(couponId)).filter(c -> c.barbershopId().equals(tenant)).map(this::copy);
    }

    @Override
    public Page<RewardCoupon> coupons(UUID tenant, UUID clientId, CouponStatus status, Page.Request page) {
        return Page.of(coupons.values().stream().filter(c -> c.barbershopId().equals(tenant))
                .filter(c -> clientId == null || clientId.equals(c.clientId()))
                .filter(c -> status == null || status == c.status())
                .sorted(Comparator.comparing(RewardCoupon::createdAt).reversed().thenComparing(RewardCoupon::id))
                .map(this::copy).toList(), page);
    }

    @Override
    public Optional<Idempotency.Stored> findKey(String key, String operation) {
        return Optional.ofNullable(keys.get(key + " " + operation));
    }

    @Override
    public synchronized LoyaltyCard saveSticker(LoyaltyCard card, boolean newCard, LoyaltyTransaction t,
                                                Idempotency.Key key, OutboxEvent event) {
        if (newCard && cardOf(card.barbershopId(), card.clientId()).isPresent()) {
            throw new CardTaken();
        }
        if (t.appointmentId() != null && transactions.stream()
                .anyMatch(x -> x.type() == TransactionType.STICKER_EARNED && t.appointmentId().equals(x.appointmentId()))) {
            throw new StickerAlreadyGranted();
        }
        checkKey(key);
        LoyaltyCard stored = newCard ? copy(card) : cards.get(card.id());
        LoyaltyCard updated = newCard ? stored : LoyaltyCard.restore(stored.id(), stored.barbershopId(), stored.clientId(),
                stored.stickersCount() + 1, stored.totalRewardsRedeemed(), t.createdAt());
        cards.put(updated.id(), updated);
        transactions.add(t);
        storeKey(key, t.id());
        append(event);
        return copy(updated);
    }

    @Override
    public synchronized LoyaltyCard saveRedemption(LoyaltyCard card, int required, LoyaltyTransaction t,
                                                   RewardCoupon coupon, Idempotency.Key key, OutboxEvent event) {
        LoyaltyCard stored = cards.get(card.id());
        if (stored.stickersCount() < required) {
            throw new NotEnoughStickers();
        }
        checkKey(key);
        LoyaltyCard updated = LoyaltyCard.restore(stored.id(), stored.barbershopId(), stored.clientId(),
                stored.stickersCount() - required, stored.totalRewardsRedeemed() + 1, t.createdAt());
        cards.put(updated.id(), updated);
        transactions.add(t);
        coupons.put(coupon.id(), copy(coupon));
        storeKey(key, coupon.id());
        append(event);
        return copy(updated);
    }

    @Override
    public synchronized void saveCouponUse(RewardCoupon coupon) {
        if (coupons.get(coupon.id()).status() != CouponStatus.ACTIVE) {
            throw new CouponTaken();
        }
        coupons.put(coupon.id(), copy(coupon));
    }

    private void append(OutboxEvent event) {
        relay.put(event.id(), new Relay(Optional.ofNullable(MDC.get("correlationId")).orElse("none"), null, null, null));
        outbox.add(event);
    }

    @Override
    public List<Stored> pending(int limit) {
        return outbox.stream().filter(e -> relay.get(e.id()).publishedAt() == null && relay.get(e.id()).failedAt() == null)
                .sorted(Comparator.comparing(OutboxEvent::occurredAt)).limit(limit)
                .map(e -> new Stored(e, relay.get(e.id()).correlationId())).toList();
    }

    @Override
    public synchronized boolean markPublished(UUID id, Instant now) {
        Relay r = relay.get(id);
        if (r != null && r.publishedAt() == null) {
            relay.put(id, new Relay(r.correlationId(), now, r.failedAt(), r.lastError()));
        }
        return r != null;
    }

    @Override
    public synchronized boolean markFailed(UUID id, String reason, Instant now) {
        Relay r = relay.get(id);
        if (r != null) {
            relay.put(id, new Relay(r.correlationId(), r.publishedAt(), r.failedAt() == null ? now : r.failedAt(), reason));
        }
        return r != null;
    }

    /** What would be relayed; only for tests and local runs. */
    public List<OutboxEvent> outbox() {
        return List.copyOf(outbox);
    }

    private void checkKey(Idempotency.Key key) {
        if (key != null && keys.containsKey(key.key() + " " + key.operation())) {
            throw new KeyTaken();
        }
    }

    private void storeKey(Idempotency.Key key, UUID resourceId) {
        if (key != null) {
            keys.put(key.key() + " " + key.operation(), new Idempotency.Stored(resourceId, key.requestHash()));
        }
    }

    private LoyaltyCard copy(LoyaltyCard c) {
        return LoyaltyCard.restore(c.id(), c.barbershopId(), c.clientId(), c.stickersCount(), c.totalRewardsRedeemed(),
                c.lastUpdated());
    }

    private RewardCoupon copy(RewardCoupon c) {
        return RewardCoupon.restore(c.id(), c.barbershopId(), c.clientId(), c.status(), c.appointmentId(), c.createdAt(),
                c.usedAt());
    }
}
