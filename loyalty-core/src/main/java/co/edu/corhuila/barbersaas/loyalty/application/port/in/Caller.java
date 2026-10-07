package co.edu.corhuila.barbersaas.loyalty.application.port.in;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.ApplicationException.Forbidden;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who calls, taken ONLY from the validated token (authentication.md, "Multi-tenancy"): the
 * subject, the role and the tenant. {@code credential} is the token itself, opaque to the use
 * cases: the adapter passes it on to appointment-api, which validates it again and
 * applies its tenant itself. It is never logged.
 */
public record Caller(String subject, Role role, UUID barbershopId, String credential) {

    /** The four user roles of identity-auth plus SERVICE, for the tokens of worker and workflow. */
    public enum Role { SUPER_ADMIN, ADMIN_BARBERSHOP, BARBER, CLIENT, SERVICE }

    public Caller {
        Objects.requireNonNull(subject);
        Objects.requireNonNull(role);
    }

    /** The tenant every scoped read and write filters by; SUPER_ADMIN and a client without one are refused. */
    public UUID tenant() {
        if (barbershopId == null) {
            throw new Forbidden("This operation requires a barbershop");
        }
        return barbershopId;
    }

    public void require(Role... allowed) {
        if (!Set.of(allowed).contains(role)) {
            throw new Forbidden("The role " + role + " cannot do this");
        }
    }

    public boolean is(Role r) {
        return role == r;
    }

    /**
     * Internal operations accept only the service token of the one service they exist for
     * (authentication.md, "Internal operations"): a user's token, or another service's, is refused.
     */
    public void requireService(String service) {
        if (role != Role.SERVICE || !subject.equals(service)) {
            throw new Forbidden("Only " + service + " can do this");
        }
    }

    /** The user id of the token; a service token has a service name instead. */
    public UUID userId() {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new Forbidden("This operation requires a user");
        }
    }

    @Override
    public String toString() {
        return "Caller[subject=" + subject + ", role=" + role + ", barbershopId=" + barbershopId + "]";
    }
}
