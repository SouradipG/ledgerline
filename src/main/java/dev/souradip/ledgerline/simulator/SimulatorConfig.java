package dev.souradip.ledgerline.simulator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Runs the simulated provider inside the app for local demos: {@code --spring.profiles.active=demo}. */
@Configuration
@Profile("demo")
class SimulatorConfig {

    @Bean(destroyMethod = "stop")
    PaymentProviderSimulator paymentProviderSimulator(@Value("${ledger.simulator.port:8089}") int port) {
        return new PaymentProviderSimulator().start(port);
    }
}
