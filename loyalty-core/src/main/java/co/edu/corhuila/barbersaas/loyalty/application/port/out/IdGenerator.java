package co.edu.corhuila.barbersaas.loyalty.application.port.out;

import java.util.UUID;

public interface IdGenerator {

    UUID next();
}
