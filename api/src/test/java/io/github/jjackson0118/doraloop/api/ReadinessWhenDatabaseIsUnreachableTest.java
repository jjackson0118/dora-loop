package io.github.jjackson0118.doraloop.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Readiness answers within a bounded time when the database is unreachable.
 *
 * <p>This exists because the obvious version of this test does not work. Stopping
 * a Testcontainers Postgres and probing readiness answers 503 in <em>19ms</em>
 * whether the pool timeout is 3 seconds or 30 — the container's port stops
 * being bound, the connection is refused instantly, and the pool never reaches
 * the wait this test is about. That version passed against the exact defect it
 * was written to catch, which was only discovered by putting the defect back
 * and watching it pass.
 *
 * <p>The real deploy target behaves differently: with Postgres stopped,
 * readiness answered correctly but after <strong>30.016 seconds</strong>, twice
 * running, and dropped to <strong>3.031 seconds</strong> when
 * {@code connection-timeout} was set to 3000 — tracking the configured value
 * closely enough to identify it as the cause.
 *
 * <p>So the database here is not stopped, it is <em>unreachable</em>: the
 * datasource points at {@code 192.0.2.1}, which RFC 5737 reserves for
 * documentation and which no network routes. Packets are dropped rather than
 * refused, the connect attempt hangs, and the pool has to be the thing that
 * gives up. That is the shape of the real failure — a database that is gone
 * rather than one that is politely saying no — and it is the shape a timeout
 * exists for.
 *
 * <p>Flyway is off and {@code initialization-fail-timeout} is negative because
 * otherwise the context cannot start against an absent database, and a test
 * that cannot boot proves nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "dora.ingest.token=unreachable-db-secret",
                // RFC 5737 TEST-NET-1: reserved, unrouted, drops rather than refuses.
                "spring.datasource.url=jdbc:postgresql://192.0.2.1:5432/doraloop",
                "spring.datasource.username=doraloop",
                "spring.datasource.password=irrelevant",
                "spring.flyway.enabled=false",
                "spring.datasource.hikari.initialization-fail-timeout=-1"
        })
class ReadinessWhenDatabaseIsUnreachableTest {

    /**
     * The caller's patience, against a configured pool timeout of 3s.
     *
     * <p>Wide enough not to flake on a slow runner, and far below the 30s
     * default it exists to catch. If someone removes {@code connection-timeout}
     * from {@code application.yml}, this budget is what goes red.
     */
    private static final Duration BUDGET = Duration.ofSeconds(12);

    @Autowired TestRestTemplate http;

    @Test
    void readinessRefusesWithinTheBudgetRatherThanHanging() {
        Instant start = Instant.now();
        ResponseEntity<String> res = http.getForEntity("/actuator/health/readiness", String.class);
        Duration took = Duration.between(start, Instant.now());

        assertThat(res.getStatusCode())
                .as("readiness with an unreachable database")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(took)
                .as("readiness answered in %s. A verdict that arrives after the caller has "
                        + "given up is indistinguishable from a hang, and a hang and a "
                        + "refusal are different claims", took)
                .isLessThan(BUDGET);
    }

    /**
     * Liveness must not wait on the database either.
     *
     * <p>If it did, an unreachable database would make the liveness probe time
     * out, and a supervisor would kill and restart a process that is working
     * perfectly — destroying the only component still able to report the
     * outage. The 200 is the assertion; the speed is the point.
     */
    @Test
    void livenessIsUnaffectedAndImmediate() {
        Instant start = Instant.now();
        ResponseEntity<String> res = http.getForEntity("/actuator/health/liveness", String.class);
        Duration took = Duration.between(start, Instant.now());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(took)
                .as("liveness took %s; it must not touch the database at all", took)
                .isLessThan(Duration.ofSeconds(5));
    }
}
