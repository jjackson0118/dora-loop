package io.github.jjackson0118.doraloop.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A write endpoint outside {@code /api/v1/} still requires the token.
 *
 * <p>This context deliberately enables and exposes {@code loggers}, which the
 * shipped configuration does neither. It took one property when this test was
 * written and takes two now, because the read-side fix
 * ({@code endpoints.enabled-by-default: false}, see {@link ActuatorExposureTest})
 * means exposing an endpoint no longer conjures it into existence. Both are the
 * whole point: the actuator's
 * {@code POST /actuator/loggers/{name}} changes the running service's log
 * levels, and someone adding it to
 * {@code management.endpoints.web.exposure.include} while chasing a production
 * problem is an ordinary Tuesday, not a hypothetical.
 *
 * <p>Against the previous filter -- which engaged only for {@code POST} whose
 * URI began {@code /api/v1/} -- that endpoint would have accepted an
 * unauthenticated write from anyone who could reach the port, and no test in
 * this repository would have gone red. This one goes red, because it asserts
 * the property (writes need the token) rather than the implementation (the
 * ingest paths are protected).
 *
 * <p>It is also the negative control for the sweep in {@link IngestAuthTest}:
 * that sweep now walks the actuator's handler mapping too, so if this endpoint
 * were ever unprotected, two independent tests would fail rather than none.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "dora.ingest.token=exposed-actuator-secret",
                "management.endpoint.loggers.enabled=true",
                "management.endpoints.web.exposure.include=health,info,loggers"
        })
@Testcontainers
class ExposedActuatorWriteTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;

    /**
     * The exposure actually took effect.
     *
     * <p>Without this, a typo in the property name above would leave the
     * endpoint unmapped, the write attempt would 404, and the refusal assertion
     * would pass having tested nothing -- the vacuous-gate failure this project
     * exists to argue against, reproduced inside its own security test.
     */
    @Test
    void theLoggersEndpointIsActuallyExposedHere() {
        assertThat(http.getForEntity("/actuator/loggers/ROOT", String.class).getStatusCode())
                .as("GET /actuator/loggers/ROOT, proving the endpoint is mapped in this context")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void writingToAnExposedActuatorEndpointRequiresTheToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> res = http.exchange("/actuator/loggers/ROOT", HttpMethod.POST,
                new HttpEntity<>("{\"configuredLevel\":\"TRACE\"}", headers), String.class);

        assertThat(res.getStatusCode())
                .as("unauthenticated POST to an exposed actuator write endpoint")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains(IngestAuthFilter.HEADER);
    }

    /**
     * Reads on the actuator are unaffected.
     *
     * <p>The filter must not be so broad that it breaks the health probe: a
     * readiness check that starts returning 403 takes the service out of
     * rotation, which is an outage caused by the security control.
     */
    @Test
    void actuatorReadsStillWorkWithoutAToken() {
        assertThat(http.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
