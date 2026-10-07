package co.edu.corhuila.barbersaas.loyalty.application.port.out;

/**
 * Another domain's API did not answer in time or answered something unexpected. The request fails
 * (500 with a neutral message, the cause in the log): an appointment is never assumed to exist.
 */
public class DependencyFailure extends RuntimeException {

    public DependencyFailure(String dependency, String reason) {
        super(dependency + ": " + reason);
    }
}
