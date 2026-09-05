package io.github.jjackson0118.doraloop.api;

import io.github.jjackson0118.doraloop.core.Thresholds;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }

    /** Injected so tests can pin time; core is never aware of Spring. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Per-service thresholds are not wired to configuration yet, but ADR 0004
     * made them an instance so they can be without touching core.
     */
    @Bean
    Thresholds thresholds() {
        return Thresholds.defaults();
    }
}
