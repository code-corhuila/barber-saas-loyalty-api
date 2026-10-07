package co.edu.corhuila.barbersaas.loyalty.adapter.out.http;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.AppointmentLookup;
import java.util.Optional;
import java.util.UUID;

/** AppointmentLookup over appointment-service.yaml; appointment-api applies the token's tenant itself. */
public class AppointmentApiClient implements AppointmentLookup {

    private final JsonApi api;

    public AppointmentApiClient(String baseUrl) {
        this.api = new JsonApi("appointment-api", baseUrl);
    }

    /**
     * getAppointmentById with the caller's token: staff see their barbershop's, a client only theirs, so a
     * 404 there is a 404 here. clientId is null for a walk-in.
     */
    @Override
    public Optional<AppointmentRef> find(Caller caller, UUID appointmentId) {
        return api.get(caller, "/api/v1/appointments/" + appointmentId).map(a -> new AppointmentRef(appointmentId,
                a.path("clientId").isTextual() ? UUID.fromString(a.get("clientId").asText()) : null));
    }
}
