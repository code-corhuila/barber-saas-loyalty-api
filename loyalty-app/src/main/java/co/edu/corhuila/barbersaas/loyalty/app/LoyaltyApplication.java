package co.edu.corhuila.barbersaas.loyalty.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "co.edu.corhuila.barbersaas.loyalty")
public class LoyaltyApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoyaltyApplication.class, args);
    }
}
