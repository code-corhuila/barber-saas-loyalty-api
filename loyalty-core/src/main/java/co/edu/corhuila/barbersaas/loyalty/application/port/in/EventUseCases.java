package co.edu.corhuila.barbersaas.loyalty.application.port.in;

import java.util.Map;
import java.util.UUID;

/**
 * POST /internal/v1/events (ADR-016, DEC-LOY-01/05/06): the worker delivers one event at a time, at least
 * once. Only the service token of barber-saas-worker.
 */
public interface EventUseCases {

    /** The fields of the EventEnvelope that loyalty reads; the tenant is the envelope's barbershopId. */
    record IncomingEvent(UUID id, String type, int version, UUID barbershopId, Map<String, Object> payload) { }

    /** EventReceipt of _shared.yaml: every outcome answers 200, so the worker confirms the delivery. */
    enum Outcome { PROCESSED, DUPLICATE, IGNORED }

    record Receipt(UUID eventId, Outcome outcome) { }

    /**
     * AppointmentCompleted grants one sticker to its client in its barbershop, in the name of whoever
     * completed it. IGNORED without an active program or for a walk-in; DUPLICATE when already processed
     * or the appointment already has its sticker. AppointmentCreated with a couponId marks that coupon
     * USED (DEC-LOY-06); IGNORED without one. Any other type, a payload without completedBy, or a coupon
     * that is unknown, another client's or used elsewhere, is a BusinessRuleViolation (422) and the
     * worker marks it failed.
     */
    Receipt receive(Caller caller, IncomingEvent event);
}
