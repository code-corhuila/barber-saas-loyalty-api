package co.edu.corhuila.barbersaas.loyalty.adapter.out.persistence;

import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs against a real PostgreSQL migrated by barber-saas-loyalty-db, connected as loyalty_app (never the
 * administrator). It does not create the schema: set TEST_DATABASE_URL, TEST_DATABASE_USER and
 * TEST_DATABASE_PASSWORD to run it; without them it is skipped.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcLoyaltyRepositoryTest extends RepositoryContract {

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"),
            System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD")));
    private final JdbcLoyaltyRepository repository = new JdbcLoyaltyRepository(jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), new ObjectMapper());

    @Override
    LoyaltyRepository repository() {
        return repository;
    }
}
