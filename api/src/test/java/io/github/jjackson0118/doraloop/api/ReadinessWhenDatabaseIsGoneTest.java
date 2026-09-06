package io.github.jjackson0118.doraloop.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Readiness must say DOWN <em>quickly</em> when the database is gone.
 *
 * <p>The existing readiness test asserts that the database is part of the
 * readiness group, which it verifies against a database that is up. That is
 * half the property. The half it never saw is what happens when the database
 * is actually missing — and on the real deploy target the answer was 503
 * {@code DOWN} after <strong>30.016 seconds</strong>, twice running, against
 * 7ms healthy. HikariCP's default {@code connectionTimeout} is 30s, and the
 * health indicator waits the whole of it.
 *
 * <p>A correct verdict nobody waits for is not a correct verdict. Every probe
 * with a timeout under 30s sees a <em>timeout</em> instead of a <em>refusal</em>,
 * and this project's entire argument is that those are different claims: it is
 * exit 1 versus exit 2 in the gate contract next door. Worse, each hung request
 * holds a request thread, so a few concurrent probes exhaust the pool and a
 * database outage escalates into total unresponsiveness — including the report
 * endpoint, which needs no database to answer that a service is
 * {@code UNOBSERVED}.
 *
 * <p>So this stops the container and asserts the answer arrives inside a
 * budget. The budget is the assertion; the status code alone would have passed
 * against the 30-second behaviour this test exists to prevent returning.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dora.ingest.token=readiness-test-secret")
@Testcontainers
class ReadinessWhenDatabaseIsGoneTest {

    /**
     * Not shared with the other test classes. Stopping this container is the
     * point, and Spring's context cache would otherwise hand the corpse to
     * whichever class ran next.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * The caller's patience. Generous against the 3s pool timeout so a slow CI
     * runner does not make this flaky, and far below the 30s it is written to
     * catch — a threshold calibrated where it will actually run.
     */
    private static final Duration BUDGET = Duration.ofSeconds(12);

    @Autowired TestRestTemplate http;

    @AfterAll
    static void restart() {
        // Leave the container running: Testcontainers reuses and reaps by its
        // own rules, and a stopped container left behind has confused a later
        // class in this suite before.
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    @Test
    void readinessGoesDownAndDoesSoBeforeAnyoneGivesUp() {
        assertThat(http.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .as("baseline: readiness must be UP before the database is stopped, or "
                        + "this test proves nothing about stopping it")
                .isEqualTo(HttpStatus.OK);

        POSTGRES.stop();

        Instant start = Instant.now();
        ResponseEntity<String> res = http.getForEntity("/actuator/health/readiness", String.class);
        Duration took = Duration.between(start, Instant.now());

        assertThat(res.getStatusCode())
                .as("readiness with no database")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody()).contains("DOWN");
        assertThat(took)
                .as("readiness answered correctly but took %s; the caller has to be told "
                        + "before it gives up, or a refusal is indistinguishable from a hang", took)
                .isLessThan(BUDGET);
    }

    @Test
    void livenessStaysUpWhileTheDatabaseIsGone() {
        // Liveness must NOT include the database. A liveness probe that fails
        // on a database outage gets the process killed and restarted, which
        // fixes nothing and destroys the one thing still able to report the
        // outage. Asserted here rather than assumed, because the readiness
        // group was configured by hand and this is the mistake next door to it.
        POSTGRES.stop();
        assertThat(http.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
