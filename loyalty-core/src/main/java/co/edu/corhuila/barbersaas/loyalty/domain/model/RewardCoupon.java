package co.edu.corhuila.barbersaas.loyalty.domain.model;

import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.InvalidStatusTransition;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The coupon a redemption issues (FR-015). It moves ACTIVE → USED only once, on an appointment of the
 * same barbershop and client (RewardCoupon rule, chk_reward_coupon_used).
 */
public final class RewardCoupon {

    private final UUID id;
    private final UUID barbershopId;
    private final UUID clientId;
    private final Instant createdAt;
    private CouponStatus status;
    private UUID appointmentId;
    private Instant usedAt;

    private RewardCoupon(UUID id, UUID barbershopId, UUID clientId, CouponStatus status, UUID appointmentId,
                         Instant createdAt, Instant usedAt) {
        this.id = Objects.requireNonNull(id);
        this.barbershopId = Objects.requireNonNull(barbershopId);
        this.clientId = Objects.requireNonNull(clientId);
        this.status = Objects.requireNonNull(status);
        this.appointmentId = appointmentId;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.usedAt = usedAt;
    }

    static RewardCoupon issue(UUID id, UUID barbershopId, UUID clientId, Instant now) {
        return new RewardCoupon(id, barbershopId, clientId, CouponStatus.ACTIVE, null, now, null);
    }

    public static RewardCoupon restore(UUID id, UUID barbershopId, UUID clientId, CouponStatus status,
                                       UUID appointmentId, Instant createdAt, Instant usedAt) {
        return new RewardCoupon(id, barbershopId, clientId, status, appointmentId, createdAt, usedAt);
    }

    public void use(UUID appointmentId, Instant now) {
        if (status == CouponStatus.USED) {
            throw new InvalidStatusTransition("The coupon has already been used");
        }
        this.status = CouponStatus.USED;
        this.appointmentId = Objects.requireNonNull(appointmentId);
        this.usedAt = now;
    }

    public UUID id() { return id; }
    public UUID barbershopId() { return barbershopId; }
    public UUID clientId() { return clientId; }
    public CouponStatus status() { return status; }
    public UUID appointmentId() { return appointmentId; }
    public Instant createdAt() { return createdAt; }
    public Instant usedAt() { return usedAt; }
}
