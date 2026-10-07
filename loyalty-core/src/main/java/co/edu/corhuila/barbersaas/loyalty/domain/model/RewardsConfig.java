package co.edu.corhuila.barbersaas.loyalty.domain.model;

import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.InvalidValue;
import java.util.Objects;
import java.util.UUID;

/**
 * The reward a barbershop gives after {@code stickersRequired} stickers (loyalty_rewards_config, one per
 * barbershop). The threshold is the barbershop's own: never assume 10 (data-dictionary.md).
 */
public record RewardsConfig(UUID id, UUID barbershopId, int stickersRequired, String rewardDescription,
                            boolean active) {

    static final int DESCRIPTION_MAX = 255;

    public RewardsConfig {
        Objects.requireNonNull(id);
        Objects.requireNonNull(barbershopId);
        if (stickersRequired < 1) {
            throw new InvalidValue("stickersRequired must be at least 1");
        }
        rewardDescription = Text.required("rewardDescription", rewardDescription, DESCRIPTION_MAX);
    }

    /**
     * Creates or replaces the rule, keeping its id. A new threshold applies from the next redemption:
     * stickers already earned are not touched.
     */
    public static RewardsConfig set(RewardsConfig current, UUID newId, UUID barbershopId, int stickersRequired,
                                    String rewardDescription, boolean active) {
        return new RewardsConfig(current == null ? newId : current.id(), barbershopId, stickersRequired,
                rewardDescription, active);
    }
}
