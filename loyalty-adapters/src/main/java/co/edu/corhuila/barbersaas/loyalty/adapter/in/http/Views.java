package co.edu.corhuila.barbersaas.loyalty.adapter.in.http;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.LoyaltyUseCases.CardView;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.LoyaltyUseCases.RedemptionResult;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.LoyaltyUseCases.StickerResult;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Page;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyTransaction;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardCoupon;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardsConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** The response schemas of loyalty-service.yaml, never the entity itself (norm 5.3.4); no barbershopId. */
final class Views {

    private Views() {
    }

    record Meta(int page, int limit, long total, long totalPages) { }

    record PageView<T>(List<T> data, Meta meta) {
        static <D, T> PageView<T> of(Page<D> page, Function<D, T> view) {
            return new PageView<>(page.items().stream().map(view).toList(),
                    new Meta(page.page(), page.limit(), page.total(), page.totalPages()));
        }
    }

    record ConfigView(UUID id, int stickersRequired, String rewardDescription, boolean isActive) {
        static ConfigView of(RewardsConfig c) {
            return new ConfigView(c.id(), c.stickersRequired(), c.rewardDescription(), c.active());
        }
    }

    /** LoyaltyCard: stickersRequired, canRedeem and rewardDescription come from the active rule, or are null/false. */
    record LoyaltyCardView(UUID id, UUID clientId, int stickersCount, int totalRewardsRedeemed, Instant lastUpdated,
                           Integer stickersRequired, boolean canRedeem, String rewardDescription) {
        static LoyaltyCardView of(CardView v) {
            LoyaltyCard card = v.card();
            RewardsConfig rule = v.config() != null && v.config().active() ? v.config() : null;
            return new LoyaltyCardView(card.id(), card.clientId(), card.stickersCount(), card.totalRewardsRedeemed(),
                    card.lastUpdated(), rule == null ? null : rule.stickersRequired(), card.canRedeem(rule),
                    rule == null ? null : rule.rewardDescription());
        }
    }

    record TransactionView(UUID id, UUID loyaltyCardId, UUID appointmentId, String type, UUID grantedByUserId,
                           Instant createdAt) {
        static TransactionView of(LoyaltyTransaction t) {
            return new TransactionView(t.id(), t.cardId(), t.appointmentId(), t.type().name(), t.grantedByUserId(),
                    t.createdAt());
        }
    }

    record CouponView(UUID id, UUID clientId, String status, UUID appointmentId, Instant createdAt, Instant usedAt) {
        static CouponView of(RewardCoupon c) {
            return new CouponView(c.id(), c.clientId(), c.status().name(), c.appointmentId(), c.createdAt(), c.usedAt());
        }
    }

    record StickerResultView(TransactionView transaction, LoyaltyCardView card) {
        static StickerResultView of(StickerResult r) {
            return new StickerResultView(TransactionView.of(r.transaction()), LoyaltyCardView.of(r.card()));
        }
    }

    record RedemptionResultView(TransactionView transaction, LoyaltyCardView card, CouponView coupon) {
        static RedemptionResultView of(RedemptionResult r) {
            return new RedemptionResultView(TransactionView.of(r.transaction()), LoyaltyCardView.of(r.card()),
                    CouponView.of(r.coupon()));
        }
    }
}
