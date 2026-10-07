package co.edu.corhuila.barbersaas.loyalty.application.port.out;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import java.util.Optional;
import java.util.UUID;

/**
 * What loyalty needs from the appointment domain, asked through appointment-api and never its
 * database (golden rule 8, Annex J J.3.3). appointment-api applies the caller's tenant itself.
 */
public interface AppointmentLookup {

    /** The appointment and its client ({@code null} for a walk-in). */
    record AppointmentRef(UUID id, UUID clientId) { }

    /** Empty when it does not exist or belongs to another barbershop (or, for a client, to another client). */
    Optional<AppointmentRef> find(Caller caller, UUID appointmentId);
}
