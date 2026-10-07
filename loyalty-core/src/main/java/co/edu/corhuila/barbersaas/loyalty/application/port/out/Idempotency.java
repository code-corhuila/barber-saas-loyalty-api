package co.edu.corhuila.barbersaas.loyalty.application.port.out;

import java.util.UUID;

/** Rows of loyalty.idempotency_key: written in the SAME transaction as the resource (norm 5.3.8). */
public final class Idempotency {

    private Idempotency() {
    }

    public record Key(String key, String operation, String requestHash) { }

    public record Stored(UUID resourceId, String requestHash) { }

    /** Another request stored the same Idempotency-Key first; the use case answers as a retry. */
    public static class KeyTaken extends RuntimeException {
        public KeyTaken() {
            super("The Idempotency-Key was stored by a concurrent request");
        }
    }
}
