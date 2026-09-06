package io.github.jjackson0118.doraloop.api;

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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read side of the exposure hazard.
 *
 * <p>{@link ExposedActuatorWriteTest} covers what happens when someone widens
 * {@code management.endpoints.web.exposure.include} and an actuator
 * <em>write</em> endpoint appears. That was only half the problem, and the
 * quieter half was the dangerous one: the token filter keys on the HTTP method
 * and lets every GET through by design, so no amount of care in that filter
 * touches a read endpoint. {@code GET /actuator/heapdump} returns a heap dump
 * containing {@code dora.ingest.token} in cleartext -- measured at seventeen
 * occurrences in 61 MB -- to a caller with no credential at all. Boot sanitizes
 * {@code /env}, which answers {@code ******}; it cannot sanitize a heap dump.
 *
 * <p>This context sets {@code exposure.include=*}, the widest possible setting
 * and the thing someone actually types at 2am. The control being tested is not
 * the exposure list -- it is {@code endpoints.enabled-by-default: false}, which
 * means a disabled endpoint does not exist to be exposed. If that line is ever
 * removed, every assertion below fails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "dora.ingest.token=actuator-exposure-secret",
                "management.endpoints.web.exposure.include=*"
        })
@Testcontainers
class ActuatorExposureTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Endpoints that disclose. {@code heapdump} is the one that leaks the
     * secret outright; the rest disclose configuration, dependencies, routes or
     * stack state that make an attacker's next question cheaper. All are GETs,
     * so none of them can be reached by the token filter.
     */
    private static final List<String> MUST_NOT_BE_REACHABLE = List.of(
            "/actuator/heapdump",
            "/actuator/env",
            "/actuator/env/dora.ingest.token",
            "/actuator/configprops",
            "/actuator/beans",
            "/actuator/threaddump",
            "/actuator/mappings",
            "/actuator/conditions",
            "/actuator/loggers",
            "/actuator/metrics",
            "/actuator/caches",
            "/actuator/scheduledtasks",
            "/actuator/sbom",
            "/actuator/shutdown");

    @Autowired TestRestTemplate http;

    @Test
    void wideningExposureCannotPublishAnEndpointThatIsDisabled() {
        List<String> reachable = new ArrayList<>();
        for (String path : MUST_NOT_BE_REACHABLE) {
            ResponseEntity<String> res = http.getForEntity(path, String.class);
            if (res.getStatusCode() != HttpStatus.NOT_FOUND) {
                reachable.add(path + " -> " + res.getStatusCode());
            }
        }
        assertThat(reachable)
                .as("actuator endpoints answering under exposure.include=*")
                .isEmpty();
    }

    /**
     * The heap dump specifically, asserted on its contents rather than its
     * status code.
     *
     * <p>A 404 is the mechanism; "the secret is not on the wire" is the
     * property. Asserting the property means this still fails if some future
     * version of Boot answers a heap dump from a different path or a different
     * status.
     */
    @Test
    void theHeapDumpDoesNotHandOutTheToken() {
        ResponseEntity<byte[]> res = http.getForEntity("/actuator/heapdump", byte[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        byte[] body = res.getBody();
        if (body != null) {
            assertThat(new String(body, java.nio.charset.StandardCharsets.ISO_8859_1))
                    .as("response body must not contain the ingest token")
                    .doesNotContain("actuator-exposure-secret");
        }
    }

    /**
     * The allow-list still works.
     *
     * <p>Without this the test above is satisfied by a service where nothing
     * responds at all -- a control that passes by breaking the thing it
     * protects. The readiness probe in particular must keep answering, because
     * a probe that starts returning 404 takes the service out of rotation.
     */
    @Test
    void whatIsMeantToBeOpenIsStillOpen() {
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/info", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * The exposure widening actually took effect in this context.
     *
     * <p>Without it, a typo in the property name would leave exposure at the
     * shipped {@code health,info}, every path above would 404 for the ordinary
     * reason, and this whole class would pass having tested nothing. The links
     * endpoint is served by the discovery mechanism rather than by an
     * {@code @Endpoint}, so it survives {@code enabled-by-default: false} and
     * makes a usable witness.
     */
    @Test
    void theExposureWideningIsInEffect() {
        ResponseEntity<String> links = http.getForEntity("/actuator", String.class);
        assertThat(links.getStatusCode())
                .as("the actuator links endpoint, proving exposure=* was applied")
                .isEqualTo(HttpStatus.OK);
        assertThat(links.getBody())
                .as("links must still advertise the endpoints that are meant to be open")
                .contains("health");
    }
}
