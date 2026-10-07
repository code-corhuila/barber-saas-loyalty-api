package co.edu.corhuila.barbersaas.loyalty.domain.model;

/** Errors the domain raises. The HTTP adapter turns each into one status code. */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    /** A value the contract already forbids (a threshold of 0, a description too long): 400 VALIDATION_ERROR. */
    public static class InvalidValue extends DomainException {
        public InvalidValue(String message) {
            super(message);
        }
    }

    /** An input that breaks an invariant (redeeming without enough stickers): 422 BUSINESS_RULE_VIOLATION. */
    public static class BusinessRuleViolation extends DomainException {
        public BusinessRuleViolation(String message) {
            super(message);
        }
    }

    /** A coupon already USED cannot be used again: 422 INVALID_STATUS_TRANSITION. */
    public static class InvalidStatusTransition extends DomainException {
        public InvalidStatusTransition(String message) {
            super(message);
        }
    }
}
