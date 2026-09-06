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
 * <p>This class asserts the <em>verdict</em>: database gone means readiness 503
 * and liveness untouched. It deliberately does NOT assert how long that takes,
 * because it cannot. Stopping a Testcontainers Postgres unbinds its port, the
 * connection is refused instantly, and readiness answers in about 19ms whether
 * the pool timeout is 3 seconds or 30 — measured, by putting the defect back
 * and watching this class pass. A timing assertion here would have looked like
 * coverage and been incapable of failing.
 *
 * <p>The timing property is proven in
 * {@link ReadinessWhenDatabaseIsUnreachableTest}, which points the datasource
 * at an unrouted address so packets are dropped rather than refused and the
 * pool is forced to be the thing that gives up. That one does go red when the
 * timeout is restored to its default.
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
    void readinessGoesDownWhenTheDatabaseIsStopped() {
        assertThat(http.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .as("baseline: readiness must be UP before the database is stopped, or "
                        + "this test proves nothing about stopping it")
                .isEqualTo(HttpStatus.OK);

        POSTGRES.stop();

        ResponseEntity<String> res = http.getForEntity("/actuator/health/readiness", String.class);

        assertThat(res.getStatusCode())
                .as("readiness with no database")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody()).contains("DOWN");
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
