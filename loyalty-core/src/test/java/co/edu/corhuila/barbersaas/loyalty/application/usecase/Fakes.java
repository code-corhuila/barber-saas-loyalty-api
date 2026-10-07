package co.edu.corhuila.barbersaas.loyalty.application.usecase;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Page;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.AppointmentLookup;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.ProcessedEvents;
import co.edu.corhuila.barbersaas.loyalty.domain.model.CouponStatus;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyTransaction;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardCoupon;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardsConfig;
import co.edu.corhuila.barbersaas.loyalty.domain.model.TransactionType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Doubles of every outbound port, in memory. Reads return copies, as a database would. */
final class Fakes {

    private Fakes() {
    }

    static final class Repository implements LoyaltyRepository, ProcessedEvents {
        final Map<UUID, RewardsConfig> configs = new HashMap<>();
        final Map<UUID, LoyaltyCard> cards = new LinkedHashMap<>();
        final List<LoyaltyTransaction> transactions = new ArrayList<>();
        final Map<UUID, RewardCoupon> coupons = new LinkedHashMap<>();
        final Map<String, Idempotency.Stored> keys = new HashMap<>();
        final List<OutboxEvent> outbox = new ArrayList<>();
        final Set<UUID> processed = new HashSet<>();
        /** Simulates a concurrent redemption that took the stickers after the card was read. */
        boolean loseTheRedemptionRace;

        @Override
        public Optional<RewardsConfig> config(UUID tenant) {
            return Optional.ofNullable(configs.get(tenant));
        }

        @Override
        public void saveConfig(RewardsConfig config) {
            configs.put(config.barbershopId(), config);
        }

        @Override
        public Optional<LoyaltyCard> card(UUID tenant, UUID cardId) {
            return Optional.ofNullable(cards.get(cardId)).filter(c -> c.barbershopId().equals(tenant)).map(Fakes::copy);
        }

        @Override
        public Optional<LoyaltyCard> cardOf(UUID tenant, UUID clientId) {
            return cards.values().stream().filter(c -> c.barbershopId().equals(tenant) && c.clientId().equals(clientId))
                    .findFirst().map(Fakes::copy);
        }

        @Override
        public Page<LoyaltyCard> cards(UUID tenant, UUID clientId, Boolean canRedeem, Integer required, Page.Request page) {
            return Page.of(cards.values().stream().filter(c -> c.barbershopId().equals(tenant))
                    .filter(c -> clientId == null || clientId.equals(c.clientId()))
                    .filter(c -> canRedeem == null
                            || canRedeem == (required != null && c.stickersCount() >= required))
                    .map(Fakes::copy).toList(), page);
        }

        @Override
        public Page<LoyaltyTransaction> transactions(UUID cardId, TransactionType type, Page.Request page) {
            return Page.of(transactions.stream().filter(t -> t.cardId().equals(cardId))
                    .filter(t -> type == null || type == t.type()).toList().reversed(), page);
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
                    .filter(t -> cards.get(t.cardId()).clientId().equals(coupon.clientId())).findFirst();
        }

        @Override
        public Optional<RewardCoupon> coupon(UUID tenant, UUID couponId) {
            return Optional.ofNullable(coupons.get(couponId)).filter(c -> c.barbershopId().equals(tenant))
                    .map(c -> RewardCoupon.restore(c.id(), c.barbershopId(), c.clientId(), c.status(),
                            c.appointmentId(), c.createdAt(), c.usedAt()));
        }

        @Override
        public Page<RewardCoupon> coupons(UUID tenant, UUID clientId, CouponStatus status, Page.Request page) {
            return Page.of(coupons.values().stream().filter(c -> c.barbershopId().equals(tenant))
                    .filter(c -> clientId == null || clientId.equals(c.clientId()))
                    .filter(c -> status == null || status == c.status())
                    .sorted(Comparator.comparing(RewardCoupon::createdAt).reversed()).toList(), page);
        }

        @Override
        public Optional<Idempotency.Stored> findKey(String key, String operation) {
            return Optional.ofNullable(keys.get(key + " " + operation));
        }

        @Override
        public LoyaltyCard saveSticker(LoyaltyCard card, boolean newCard, LoyaltyTransaction t, Idempotency.Key key,
                                       OutboxEvent event) {
            if (t.appointmentId() != null && transactions.stream().anyMatch(x -> x.type() == TransactionType.STICKER_EARNED
                    && t.appointmentId().equals(x.appointmentId()))) {
                throw new StickerAlreadyGranted();
            }
            cards.put(card.id(), copy(card));
            transactions.add(t);
            keys.put(key.key() + " " + key.operation(), new Idempotency.Stored(t.id(), key.requestHash()));
            outbox.add(event);
            return copy(card);
        }

        @Override
        public LoyaltyCard saveRedemption(LoyaltyCard card, int required, LoyaltyTransaction t, RewardCoupon coupon,
                                          Idempotency.Key key, OutboxEvent event) {
            if (loseTheRedemptionRace) {
                throw new NotEnoughStickers();
            }
            cards.put(card.id(), copy(card));
            transactions.add(t);
            coupons.put(coupon.id(), coupon);
            keys.put(key.key() + " " + key.operation(), new Idempotency.Stored(coupon.id(), key.requestHash()));
            outbox.add(event);
            return copy(card);
        }

        @Override
        public boolean isProcessed(UUID eventId) {
            return processed.contains(eventId);
        }

        @Override
        public void markProcessed(UUID eventId, String eventType) {
            processed.add(eventId);
        }

        @Override
        public LoyaltyCard saveEventSticker(LoyaltyCard card, boolean newCard, LoyaltyTransaction t, OutboxEvent event,
                                           UUID eventId, String eventType) {
            if (processed.contains(eventId)) {
                throw new AlreadyProcessed();
            }
            LoyaltyCard stored = saveSticker(card, newCard, t, new Idempotency.Key("event-" + eventId, "event", "-"), event);
            processed.add(eventId);
            return stored;
        }

        @Override
        public void saveCouponUse(RewardCoupon coupon) {
            if (coupons.get(coupon.id()).status() == CouponStatus.USED) {
                throw new CouponTaken();
            }
            coupons.put(coupon.id(), coupon);
        }
    }

    static LoyaltyCard copy(LoyaltyCard c) {
        return LoyaltyCard.restore(c.id(), c.barbershopId(), c.clientId(), c.stickersCount(), c.totalRewardsRedeemed(),
                c.lastUpdated());
    }

    /** appointment-api: an appointment is seen by staff of its barbershop and by its own client. */
    static final class Appointments implements AppointmentLookup {
        record Stored(UUID barbershopId, UUID clientId) { }

        final Map<UUID, Stored> rows = new HashMap<>();

        @Override
        public Optional<AppointmentRef> find(Caller caller, UUID appointmentId) {
            return Optional.ofNullable(rows.get(appointmentId))
                    .filter(a -> a.barbershopId().equals(caller.barbershopId()))
                    .filter(a -> caller.role() != Caller.Role.CLIENT || caller.subject().equals(String.valueOf(a.clientId())))
                    .map(a -> new AppointmentRef(appointmentId, a.clientId()));
        }
    }
}
