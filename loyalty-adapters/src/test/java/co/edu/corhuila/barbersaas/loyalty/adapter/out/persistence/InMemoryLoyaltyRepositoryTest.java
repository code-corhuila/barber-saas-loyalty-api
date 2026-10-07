package co.edu.corhuila.barbersaas.loyalty.adapter.out.persistence;

import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository;

class InMemoryLoyaltyRepositoryTest extends RepositoryContract {

    private final InMemoryLoyaltyRepository repository = new InMemoryLoyaltyRepository();

    @Override
    LoyaltyRepository repository() {
        return repository;
    }
}
