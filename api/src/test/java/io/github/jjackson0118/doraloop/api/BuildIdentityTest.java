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

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reported build identity comes from the artifact, and nothing at deploy
 * time can change it.
 *
 * <p>It used to come from {@code ${DORA_BUILD_SHA}} — an environment variable
 * the deploy script writes into {@code app.env} and then "verified" by reading
 * {@code /actuator/info} back. That asked the deploy to confirm its own claim.
 * A reviewer proved the consequence: a release directory named
 * {@code revtest2}, containing a jar byte-identical to a different build,
 * produced {@code serving revtest2} and a passing deploy. The check the deploy
 * script called its read-back could not detect a release running code other
 * than the one it named, which was the only failure it existed to catch.
 *
 * <p>Both properties below matter, and only together. That the endpoint reports
 * the artifact's identity is useless if something else can overwrite it; that
 * nothing can overwrite it is useless if the value was never the artifact's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "dora.ingest.token=build-identity-secret",
                // The attack, expressed as configuration: a deploy-supplied
                // value trying to become the reported identity. Before this
                // change, info.env.enabled published exactly this.
                "info.build.sha=deadbeefdeadbeef",
                "DORA_BUILD_SHA=deadbeefdeadbeef"
        })
@Testcontainers
class BuildIdentityTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;

    /** The value Gradle stamped into the artifact, read the same way Boot reads it. */
    private static String shaFromArtifact() throws Exception {
        try (InputStream in = BuildIdentityTest.class
                .getResourceAsStream("/META-INF/build-info.properties")) {
            assertThat(in)
                    .as("META-INF/build-info.properties must be generated into the artifact; "
                            + "without it there is no identity that a deploy cannot forge")
                    .isNotNull();
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("build.sha");
        }
    }

    @Test
    void infoReportsTheIdentityStampedIntoTheArtifact() throws Exception {
        String expected = shaFromArtifact();
        assertThat(expected)
                .as("build.sha in build-info.properties")
                .isNotBlank();

        ResponseEntity<String> res = http.getForEntity("/actuator/info", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .as("/actuator/info must report the artifact's own identity")
                .contains(expected);
    }

    /**
     * The negative control, and the one that would have caught the original
     * defect. Two deploy-supplied values are set in this context; neither may
     * appear in the response.
     */
    @Test
    void nothingSuppliedAtDeployTimeCanChangeTheReportedIdentity() {
        ResponseEntity<String> res = http.getForEntity("/actuator/info", String.class);
        assertThat(res.getBody())
                .as("a value supplied at deploy time reached /actuator/info; the endpoint is "
                        + "reporting someone's claim about the build rather than the build")
                .doesNotContain("deadbeefdeadbeef");
    }
}
