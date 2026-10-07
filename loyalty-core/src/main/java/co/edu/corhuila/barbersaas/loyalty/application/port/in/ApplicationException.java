package co.edu.corhuila.barbersaas.loyalty.application.port.in;

/** Errors of the use cases. The HTTP adapter turns each into one status code. */
public abstract class ApplicationException extends RuntimeException {

    protected ApplicationException(String message) {
        super(message);
    }

    /** 404: the resource does not exist OR belongs to another barbershop (DEC-APPT-01, adopted by this contract). */
    public static class NotFound extends ApplicationException {
        public NotFound(String what) {
            super(what + " not found");
        }
    }

    /** 403: the role may not do this, or the operation needs a barbershop the token lacks. */
    public static class Forbidden extends ApplicationException {
        public Forbidden(String message) {
            super(message);
        }
    }

    /** 422: the same Idempotency-Key with a different body. */
    public static class IdempotencyKeyReused extends ApplicationException {
        public IdempotencyKeyReused() {
            super("The Idempotency-Key was already used with a different request");
        }
    }
}
