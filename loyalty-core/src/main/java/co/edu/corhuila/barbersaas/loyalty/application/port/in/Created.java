package co.edu.corhuila.barbersaas.loyalty.application.port.in;

/** The result of a creation: {@code created} is false when the same Idempotency-Key was retried (200, not 201). */
public record Created<T>(T value, boolean created) { }
