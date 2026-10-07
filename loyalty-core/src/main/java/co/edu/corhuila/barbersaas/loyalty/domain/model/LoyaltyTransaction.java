package co.edu.corhuila.barbersaas.loyalty.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One row of the card's append-only history (loyalty_transaction); never edited nor deleted. */
public record LoyaltyTransaction(UUID id, UUID cardId, UUID appointmentId, TransactionType type, UUID grantedByUserId,
                                 Instant createdAt) {

    public LoyaltyTransaction {
        Objects.requireNonNull(id);
        Objects.requireNonNull(cardId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(grantedByUserId);
        Objects.requireNonNull(createdAt);
    }
}
