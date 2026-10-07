package co.edu.corhuila.barbersaas.loyalty.application.usecase;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.EventUseCases;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.Clock;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.IdGenerator;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository.CardTaken;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository.StickerAlreadyGranted;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.ProcessedEvents;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.ProcessedEvents.AlreadyProcessed;
import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyTransaction;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardsConfig;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The automatic sticker (DEC-LOY-01/05): AppointmentCompleted, delivered by the worker at least once,
 * grants exactly one sticker per appointment, in the name of whoever completed it.
 */
public class HandleEvents implements EventUseCases {

    static final String APPOINTMENT_COMPLETED = "AppointmentCompleted";

    private final LoyaltyRepository loyalty;
    private final ProcessedEvents processed;
    private final Clock clock;
    private final IdGenerator ids;

    public HandleEvents(LoyaltyRepository loyalty, ProcessedEvents processed, Clock clock, IdGenerator ids) {
        this.loyalty = loyalty;
        this.processed = processed;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public Receipt receive(Caller caller, IncomingEvent e) {
        caller.requireService(RelayOutbox.WORKER);
        if (!APPOINTMENT_COMPLETED.equals(e.type())) {
            throw new BusinessRuleViolation("loyalty does not handle " + e.type());
        }
        if (processed.isProcessed(e.id())) {
            return new Receipt(e.id(), Outcome.DUPLICATE);
        }
        UUID clientId = uuid(e, "clientId");
        RewardsConfig config = loyalty.config(e.barbershopId()).orElse(null);
        if (clientId == null || config == null || !config.active()) {             // a walk-in, or no program
            processed.markProcessed(e.id(), e.type());
            return new Receipt(e.id(), Outcome.IGNORED);
        }
        UUID completedBy = uuid(e, "completedBy");
        if (completedBy == null) {
            throw new BusinessRuleViolation("AppointmentCompleted without completedBy (version " + e.version()
                    + "): nobody to grant the sticker in the name of (DEC-LOY-05)");
        }
        try {
            return sticker(e, clientId, completedBy);
        } catch (CardTaken race) {
            return sticker(e, clientId, completedBy);
        } catch (AlreadyProcessed race) {
            return new Receipt(e.id(), Outcome.DUPLICATE);
        } catch (StickerAlreadyGranted manual) {                                  // a manual grant named it first
            processed.markProcessed(e.id(), e.type());
            return new Receipt(e.id(), Outcome.DUPLICATE);
        }
    }

    private Receipt sticker(IncomingEvent e, UUID clientId, UUID completedBy) {
        Instant now = clock.now();
        Optional<LoyaltyCard> existing = loyalty.cardOf(e.barbershopId(), clientId);
        LoyaltyCard card = existing.orElseGet(() -> LoyaltyCard.open(ids.next(), e.barbershopId(), clientId, now));
        LoyaltyTransaction t = card.grantSticker(ids.next(), uuid(e, "appointmentId"), completedBy, now);
        RewardsConfig config = loyalty.config(e.barbershopId()).orElse(null);
        processed.saveEventSticker(card, existing.isEmpty(), t, Events.stickerGranted(card, t, config, ids, now),
                e.id(), e.type());
        return new Receipt(e.id(), Outcome.PROCESSED);
    }

    /** A UUID of the payload; null when absent or null (a walk-in has no clientId). */
    private static UUID uuid(IncomingEvent e, String field) {
        Object value = e.payload().get(field);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException bad) {
            throw new BusinessRuleViolation(field + " of " + e.type() + " is not a UUID");
        }
    }
}
