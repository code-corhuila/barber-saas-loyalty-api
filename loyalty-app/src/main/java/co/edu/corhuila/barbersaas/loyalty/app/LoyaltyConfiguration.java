package co.edu.corhuila.barbersaas.loyalty.app;

import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.AuthFilter;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.Rs256Verifier;
import co.edu.corhuila.barbersaas.loyalty.adapter.out.http.AppointmentApiClient;
import co.edu.corhuila.barbersaas.loyalty.adapter.out.persistence.InMemoryLoyaltyRepository;
import co.edu.corhuila.barbersaas.loyalty.adapter.out.persistence.JdbcLoyaltyRepository;
import co.edu.corhuila.barbersaas.loyalty.adapter.out.persistence.SystemClock;
import co.edu.corhuila.barbersaas.loyalty.adapter.out.persistence.UuidGenerator;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.LoyaltyUseCases;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.AppointmentLookup;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository;
import co.edu.corhuila.barbersaas.loyalty.application.usecase.ManageLoyalty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Composition root: the only place that knows every concrete type. The pool and its limits are
 * built here explicitly (norm 5.3.10); the limits of the calls to appointment-api live in its client.
 */
@Configuration
public class LoyaltyConfiguration {

    /** JDBC over the loyalty schema as loyalty_app, or in memory when DATABASE_URL is empty. */
    @Bean
    LoyaltyRepository loyaltyRepository(@Value("${loyalty.database.url:}") String url,
                                        @Value("${loyalty.database.user:}") String user,
                                        @Value("${loyalty.database.password:}") String password,
                                        @Value("${loyalty.database.pool-max:10}") int poolMax,
                                        @Value("${loyalty.database.statement-timeout-ms:5000}") int statementTimeoutMs,
                                        ObjectMapper json) {
        if (url.isBlank()) {
            return new InMemoryLoyaltyRepository();
        }
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(url);
        pool.setUsername(user);                                       // loyalty_app, never the administrator
        pool.setPassword(password);
        pool.setMaximumPoolSize(poolMax);
        pool.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
        pool.setMaxLifetime(Duration.ofMinutes(30).toMillis());
        pool.setConnectionInitSql("SET statement_timeout = " + statementTimeoutMs);
        HikariDataSource dataSource = new HikariDataSource(pool);
        return new JdbcLoyaltyRepository(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), json);
    }

    @Bean
    AppointmentLookup appointmentLookup(@Value("${loyalty.appointment-api-url}") String url) {
        return new AppointmentApiClient(url);
    }

    @Bean
    LoyaltyUseCases loyaltyUseCases(LoyaltyRepository loyalty, AppointmentLookup appointments) {
        return new ManageLoyalty(loyalty, appointments, new SystemClock(), new UuidGenerator());
    }

    /** JWT_PUBLIC_KEY: the PEM itself; a one-line value with literal \n escapes, as an env file holds it, is accepted. */
    @Bean
    Rs256Verifier tokenVerifier(@Value("${JWT_PUBLIC_KEY:}") String pem) {
        return new Rs256Verifier(pem.replace("\\n", "\n"));
    }

    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        FilterRegistrationBean<CorrelationFilter> bean = new FilterRegistrationBean<>(new CorrelationFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    FilterRegistrationBean<AuthFilter> authFilter(Rs256Verifier verifier, ObjectMapper json) {
        FilterRegistrationBean<AuthFilter> bean = new FilterRegistrationBean<>(new AuthFilter(verifier, json));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
