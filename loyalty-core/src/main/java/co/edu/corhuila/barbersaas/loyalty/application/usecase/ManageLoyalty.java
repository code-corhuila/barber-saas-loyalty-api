package co.edu.corhuila.barbersaas.loyalty.application.usecase;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.ApplicationException.IdempotencyKeyReused;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Created;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.LoyaltyUseCases;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Page;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.AppointmentLookup;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.Clock;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.IdGenerator;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.Idempotency.KeyTaken;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository.CardTaken;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository.CouponTaken;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository.NotEnoughStickers;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository.StickerAlreadyGranted;
import co.edu.corhuila.barbersaas.loyalty.domain.model.CouponStatus;
import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.InvalidStatusTransition;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard.Redemption;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyTransaction;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardCoupon;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardsConfig;
import co.edu.corhuila.barbersaas.loyalty.domain.model.TransactionType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** The loyalty use cases (HU-LOY-001). They orchestrate; the card decides its rules. */
public class ManageLoyalty implements LoyaltyUseCases {

    static final String STICKER_OPERATION = "POST /api/v1/loyalty/stickers";
    static final String REDEEM_OPERATION = "POST /api/v1/loyalty/redemptions";

    private final LoyaltyRepository loyalty;
    private final AppointmentLookup appointments;
    private final Clock clock;
    private final IdGenerator ids;

    public ManageLoyalty(LoyaltyRepository loyalty, AppointmentLookup appointments, Clock clock, IdGenerator ids) {
        this.loyalty = loyalty;
        this.appointments = appointments;
        this.clock = clock;
        this.ids = ids;
    }

    // --- rule ------------------------------------------------------------------------------------

    @Override
    public RewardsConfig config(Caller caller) {
        caller.require(Role.ADMIN_BARBERSHOP, Role.BARBER, Role.CLIENT);
        return loyalty.config(caller.tenant()).orElseThrow(() -> new NotFound("Loyalty configuration"));
    }

    @Override
    public RewardsConfig setConfig(Caller caller, ConfigCommand c) {
        caller.require(Role.ADMIN_BARBERSHOP);
        UUID tenant = caller.tenant();
        RewardsConfig config = RewardsConfig.set(loyalty.config(tenant).orElse(null), ids.next(), tenant,
                c.stickersRequired(), c.rewardDescription(), c.active());
        loyalty.saveConfig(config);
        return config;
    }

    // --- cards -----------------------------------------------------------------------------------

    @Override
    public Page<CardView> cards(Caller caller, UUID clientId, Boolean canRedeem, Page.Request page) {
        caller.require(Role.ADMIN_BARBERSHOP, Role.BARBER);
        UUID tenant = caller.tenant();
        RewardsConfig config = loyalty.config(tenant).orElse(null);
        Integer threshold = config != null && config.active() ? config.stickersRequired() : null;
        return loyalty.cards(tenant, clientId, canRedeem, threshold, page).map(card -> new CardView(card, config));
    }

    @Override
    public CardView myCard(Caller caller) {
        caller.require(Role.CLIENT);
        UUID tenant = caller.tenant();
        LoyaltyCard card = loyalty.cardOf(tenant, caller.userId()).orElseThrow(() -> new NotFound("Loyalty card"));
        return view(tenant, card);
    }

    @Override
    public CardView card(Caller caller, UUID cardId) {
        return view(caller.tenant(), visibleCard(caller, cardId));
    }

    @Override
    public Page<LoyaltyTransaction> transactions(Caller caller, UUID cardId, TransactionType type, Page.Request page) {
        return loyalty.transactions(visibleCard(caller, cardId).id(), type, page);
    }

    /** Staff see every card of the barbershop; a client only theirs. Anything else is NotFound. */
    private LoyaltyCard visibleCard(Caller caller, UUID cardId) {
        caller.require(Role.ADMIN_BARBERSHOP, Role.BARBER, Role.CLIENT);
        return loyalty.card(caller.tenant(), cardId)
                .filter(card -> !caller.is(Role.CLIENT) || card.clientId().equals(caller.userId()))
                .orElseThrow(() -> new NotFound("Loyalty card"));
    }

    private CardView view(UUID tenant, LoyaltyCard card) {
        return new CardView(card, loyalty.config(tenant).orElse(null));
    }

    // --- stickers and redemptions ----------------------------------------------------------------

    @Override
    public Created<StickerResult> grantSticker(Caller caller, UUID clientId, UUID appointmentId, String key) {
        caller.require(Role.ADMIN_BARBERSHOP, Role.BARBER);              // INV-LOYAL-002
        UUID tenant = caller.tenant();
        UUID grantedBy = caller.userId();
        String hash = RequestHash.of(tenant, clientId, appointmentId);
        Function<UUID, Optional<StickerResult>> find = transactionId -> loyalty.transaction(tenant, transactionId)
                .map(t -> new StickerResult(t, view(tenant, loyalty.card(tenant, t.cardId()).orElseThrow())));
        Optional<Created<StickerResult>> retried = retry(key, STICKER_OPERATION, hash, find);
        if (retried.isPresent()) {
            return retried.get();
        }
        if (appointmentId != null && appointments.find(caller, appointmentId).isEmpty()) {
            throw new NotFound("Appointment");
        }
        try {
            return new Created<>(sticker(tenant, clientId, appointmentId, grantedBy, key, hash), true);
        } catch (CardTaken race) {
            return new Created<>(sticker(tenant, clientId, appointmentId, grantedBy, key, hash), true);
        } catch (StickerAlreadyGranted e) {
            throw new BusinessRuleViolation(e.getMessage());
        } catch (KeyTaken race) {
            return retry(key, STICKER_OPERATION, hash, find).orElseThrow(IdempotencyKeyReused::new);
        }
    }

    private StickerResult sticker(UUID tenant, UUID clientId, UUID appointmentId, UUID grantedBy, String key,
                                  String hash) {
        Instant now = clock.now();
        Optional<LoyaltyCard> existing = loyalty.cardOf(tenant, clientId);
        LoyaltyCard card = existing.orElseGet(() -> LoyaltyCard.open(ids.next(), tenant, clientId, now));
        LoyaltyTransaction t = card.grantSticker(ids.next(), appointmentId, grantedBy, now);
        RewardsConfig config = loyalty.config(tenant).orElse(null);
        LoyaltyCard stored = loyalty.saveSticker(card, existing.isEmpty(), t,
                new Idempotency.Key(key, STICKER_OPERATION, hash), Events.stickerGranted(card, t, config, ids, now));
        return new StickerResult(t, new CardView(stored, config));
    }

    @Override
    public Created<RedemptionResult> redeem(Caller caller, UUID clientId, String key) {
        caller.require(Role.ADMIN_BARBERSHOP, Role.BARBER);
        UUID tenant = caller.tenant();
        UUID redeemedBy = caller.userId();
        String hash = RequestHash.of(tenant, clientId);
        Function<UUID, Optional<RedemptionResult>> find = couponId -> loyalty.coupon(tenant, couponId)
                .flatMap(coupon -> loyalty.redemptionOf(coupon).map(t -> new RedemptionResult(t,
                        view(tenant, loyalty.card(tenant, t.cardId()).orElseThrow()), coupon)));
        Optional<Created<RedemptionResult>> retried = retry(key, REDEEM_OPERATION, hash, find);
        if (retried.isPresent()) {
            return retried.get();
        }
        LoyaltyCard card = loyalty.cardOf(tenant, clientId).orElseThrow(() -> new NotFound("Loyalty card"));
        RewardsConfig config = loyalty.config(tenant).orElse(null);
        Instant now = clock.now();
        Redemption r = card.redeem(config, ids.next(), ids.next(), redeemedBy, now);   // INV-LOYAL-001/003
        try {
            LoyaltyCard stored = loyalty.saveRedemption(card, config.stickersRequired(), r.transaction(), r.coupon(),
                    new Idempotency.Key(key, REDEEM_OPERATION, hash),
                    Events.rewardRedeemed(card, r.transaction(), r.coupon(), config, ids, now));
            return new Created<>(new RedemptionResult(r.transaction(), new CardView(stored, config), r.coupon()), true);
        } catch (NotEnoughStickers race) {
            throw new BusinessRuleViolation("The client no longer has enough stickers: another redemption took them");
        } catch (KeyTaken race) {
            return retry(key, REDEEM_OPERATION, hash, find).orElseThrow(IdempotencyKeyReused::new);
        }
    }

    // --- coupons ---------------------------------------------------------------------------------

    @Override
    public Page<RewardCoupon> coupons(Caller caller, UUID clientId, CouponStatus status, Page.Request page) {
        caller.require(Role.ADMIN_BARBERSHOP, Role.BARBER, Role.CLIENT);
        UUID onlyClient = caller.is(Role.CLIENT) ? caller.userId() : clientId;   // a client's filter is ignored
        return loyalty.coupons(caller.tenant(), onlyClient, status, page);
    }

    @Override
    public RewardCoupon coupon(Caller caller, UUID couponId) {
        caller.require(Role.ADMIN_BARBERSHOP, Role.BARBER, Role.CLIENT);
        return loyalty.coupon(caller.tenant(), couponId)
                .filter(c -> !caller.is(Role.CLIENT) || c.clientId().equals(caller.userId()))
                .orElseThrow(() -> new NotFound("Coupon"));
    }

    @Override
    public RewardCoupon useCoupon(Caller caller, UUID couponId, UUID appointmentId) {
        RewardCoupon coupon = coupon(caller, couponId);
        appointments.find(caller, appointmentId)
                .filter(a -> coupon.clientId().equals(a.clientId()))       // the same client as the coupon
                .orElseThrow(() -> new NotFound("Appointment"));
        coupon.use(appointmentId, clock.now());
        try {
            loyalty.saveCouponUse(coupon);
        } catch (CouponTaken race) {
            throw new InvalidStatusTransition(race.getMessage());
        }
        return coupon;
    }

    /** The same key and request return what was created; the same key with another request is refused. */
    private <T> Optional<Created<T>> retry(String key, String operation, String hash, Function<UUID, Optional<T>> find) {
        return loyalty.findKey(key, operation).map(stored -> {
            if (!stored.requestHash().equals(hash)) {
                throw new IdempotencyKeyReused();
            }
            return new Created<>(find.apply(stored.resourceId()).orElseThrow(IdempotencyKeyReused::new), false);
        });
    }
}
