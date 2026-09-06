package io.github.jjackson0118.doraloop.api;

import io.github.jjackson0118.doraloop.core.IncidentEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ingest-side gaps a mutation pass found: correction predicates whose
 * comparisons no fixture ever varied, a surrogate screen that had only ever
 * been shown one character in one field, and a repository guard unreachable
 * from every route the HTTP tests take.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dora.ingest.token=ingestgaptest-secret")
@Testcontainers
class IngestGapTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcClient db;
    @Autowired EventRepository repo;

    // --- the correction predicates -----------------------------------------

    /**
     * Kills M14: {@code differsOnlyByOutcome} with the service comparison
     * deleted.
     *
     * <p>Every existing correction fixture varies environment or the commit
     * range. None varies the service, so the service comparison could be
     * deleted and a rollback reported against a different service would be
     * applied as a correction -- writing only the outcome and the hash, leaving
     * a row whose payload_hash describes a payload the row does not contain,
     * and moving a change failure onto the wrong service's report.
     */
    @Test
    @DisplayName("a correction may not rewrite which service owns the deployment")
    void aCorrectionMayNotRewriteTheService() {
        String mine = svc();
        String theirs = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        String changes = """
                    {"commitSha":"aaa","authoredAt":"%s"}
                """.formatted(deployedAt.minus(1, ChronoUnit.HOURS));

        assertThat(postDeployment(eventId, mine, "production", deployedAt, "SUCCESS", changes)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(postDeployment(eventId, theirs, "production", deployedAt, "ROLLED_BACK", changes)
                .getStatusCode())
                .as("a rollback naming a different service is a conflict, not a correction")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(db.sql("SELECT service FROM deployment_event WHERE id = ?")
                .param(eventId).query(String.class).single()).isEqualTo(mine);
        assertThat(db.sql("SELECT outcome FROM deployment_event WHERE id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("SUCCESS");
    }

    /**
     * Kills M15: {@code differsOnlyByOutcome} with the deployedAt comparison
     * deleted. A rollback claiming a different deploy time is a different
     * deployment, and accepting it silently moves the event in or out of the
     * reporting window.
     */
    @Test
    @DisplayName("a correction may not rewrite when the deployment happened")
    void aCorrectionMayNotRewriteTheDeployTime() {
        String service = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        String changes = """
                    {"commitSha":"aaa","authoredAt":"%s"}
                """.formatted(deployedAt.minus(1, ChronoUnit.HOURS));

        postDeployment(eventId, service, "production", deployedAt, "SUCCESS", changes);

        assertThat(postDeployment(eventId, service, "production",
                deployedAt.minus(40, ChronoUnit.DAYS), "ROLLED_BACK", changes)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * Kills M17: the {@code SUCCESS -> ROLLED_BACK} rule with the
     * "stored is SUCCESS" half deleted.
     *
     * <p>{@code FAILED_ROLLOUT} means the change never reached users. Promoting
     * it to {@code ROLLED_BACK} moves the deployment into both the numerator
     * AND the denominator of change failure rate: a rollout the pipeline
     * correctly blocked is recounted as a change that degraded production. The
     * existing test only walks SUCCESS -> FAILED_ROLLOUT, which the surviving
     * half of the condition still refuses.
     */
    @Test
    @DisplayName("a failed rollout cannot later become a rollback")
    void aFailedRolloutCannotBecomeARollback() {
        String service = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        String changes = """
                    {"commitSha":"aaa","authoredAt":"%s"}
                """.formatted(deployedAt.minus(1, ChronoUnit.HOURS));

        postDeployment(eventId, service, "production", deployedAt, "FAILED_ROLLOUT", changes);

        assertThat(postDeployment(eventId, service, "production", deployedAt, "ROLLED_BACK", changes)
                .getStatusCode())
                .as("a change that never reached users cannot be withdrawn from users")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(db.sql("SELECT outcome FROM deployment_event WHERE id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("FAILED_ROLLOUT");
    }

    /** Kills M17 from the other end: a rollback is terminal. */
    @Test
    @DisplayName("a rollback cannot later become a success")
    void aRollbackCannotBecomeASuccess() {
        String service = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        String changes = """
                    {"commitSha":"aaa","authoredAt":"%s"}
                """.formatted(deployedAt.minus(1, ChronoUnit.HOURS));

        postDeployment(eventId, service, "production", deployedAt, "SUCCESS", changes);
        postDeployment(eventId, service, "production", deployedAt, "ROLLED_BACK", changes);

        assertThat(postDeployment(eventId, service, "production", deployedAt, "SUCCESS", changes)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * Kills M16: {@code differsOnlyByResolution} with the service comparison
     * deleted. Under that mutation the resolution is applied, and because
     * {@code resolveIncident} writes only resolved_at and the hash, the row
     * keeps the old service while its hash describes the new one -- and the
     * restore observation lands on whichever service the row still names.
     */
    @Test
    @DisplayName("a resolution may not rewrite which service owns the incident")
    void aResolutionMayNotRewriteTheService() {
        String mine = svc();
        String theirs = svc();
        String incidentId = id();
        Instant detected = Instant.now().minus(4, ChronoUnit.HOURS);

        assertThat(postIncident(incidentId, mine, "abc", detected, null)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(postIncident(incidentId, theirs, "abc", detected,
                detected.plus(30, ChronoUnit.MINUTES)).getStatusCode())
                .as("a resolution naming a different service is a conflict")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(db.sql("SELECT service FROM incident_event WHERE id = ?")
                .param(incidentId).query(String.class).single()).isEqualTo(mine);
        assertThat(db.sql("SELECT resolved_at IS NULL FROM incident_event WHERE id = ?")
                .param(incidentId).query(Boolean.class).single()).isTrue();
    }

    // --- the surrogate screen ----------------------------------------------

    /**
     * Kills M09 (the {@code i++} that steps over a valid low surrogate) and M10
     * (the {@code i + 1 >= length} bound).
     *
     * <p>The only text this screen had ever been shown was a lone HIGH
     * surrogate at the end of a service name. A well-formed surrogate pair -- an
     * emoji, which is ordinary in a service description or a commit message --
     * had never been through it. Both mutations turn a legal payload into a
     * 400, which loses a real deployment.
     */
    @Test
    @DisplayName("a well-formed surrogate pair is legal text and is stored")
    void aValidSurrogatePairIsAccepted() {
        // Written as JSON escapes so the request body stays pure ASCII and the
        // property under test is the surrogate loop, not the wire charset.
        String service = "it-rocket-\ud83d\ude80";
        String eventId = id();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);

        ResponseEntity<String> res = post("/api/v1/deployments", """
                {"id":"%s","service":"it-rocket-\\ud83d\\ude80","environment":"production",
                 "deployedAt":"%s","outcome":"SUCCESS",
                 "changes":[{"commitSha":"a\\ud83d\\ude80a","authoredAt":"%s"}]}
                """.formatted(eventId, deployedAt, deployedAt.minus(2, ChronoUnit.HOURS)));

        assertThat(res.getStatusCode())
                .as("an emoji is a well-formed surrogate pair, not malformed encoding")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(db.sql("SELECT service FROM deployment_event WHERE id = ?")
                .param(eventId).query(String.class).single()).isEqualTo(service);
    }

    /**
     * Kills M08: the low-surrogate branch deleted.
     *
     * <p>A lone LOW surrogate is just as unstorable as a lone high one, and the
     * suite had only ever posted a high one. Under the mutation this is
     * accepted and answered {@code 201 STORED} while Postgres substitutes a
     * replacement character -- the service reporting that it recorded something
     * other than what was sent.
     */
    @Test
    @DisplayName("an unpaired LOW surrogate is refused, like an unpaired high one")
    void anUnpairedLowSurrogateIsRefused() {
        String eventId = id();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);

        ResponseEntity<String> res = post("/api/v1/deployments", """
                {"id":"%s","service":"lowprobe-\\udc00","environment":"production",
                 "deployedAt":"%s","outcome":"SUCCESS","changes":[]}
                """.formatted(eventId, deployedAt));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("unpaired surrogate");
        assertThat(db.sql("SELECT count(*) FROM deployment_event WHERE id = ?")
                .param(eventId).query(Integer.class).single()).isZero();
    }

    /**
     * Kills M11, M12 and M13: the screen applied to only one of the four fields
     * that reach it. Each field is checked separately, because an assertion
     * satisfied by {@code service} says nothing about {@code id},
     * {@code environment} or a commit sha.
     */
    @Test
    @DisplayName("every text field is screened, not just the service")
    void everyTextFieldIsScreenedForIllFormedText() {
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        String authoredAt = deployedAt.minus(2, ChronoUnit.HOURS).toString();

        // id
        assertThat(post("/api/v1/deployments", """
                {"id":"bad-\\ud800","service":"%s","environment":"production",
                 "deployedAt":"%s","outcome":"SUCCESS","changes":[]}
                """.formatted(svc(), deployedAt)).getStatusCode())
                .as("id").isEqualTo(HttpStatus.BAD_REQUEST);

        // environment
        assertThat(post("/api/v1/deployments", """
                {"id":"%s","service":"%s","environment":"prod-\\ud800",
                 "deployedAt":"%s","outcome":"SUCCESS","changes":[]}
                """.formatted(id(), svc(), deployedAt)).getStatusCode())
                .as("environment").isEqualTo(HttpStatus.BAD_REQUEST);

        // a commit sha
        assertThat(post("/api/v1/deployments", """
                {"id":"%s","service":"%s","environment":"production","deployedAt":"%s",
                 "outcome":"SUCCESS","changes":[{"commitSha":"sha-\\ud800","authoredAt":"%s"}]}
                """.formatted(id(), svc(), deployedAt, authoredAt)).getStatusCode())
                .as("changes[].commitSha").isEqualTo(HttpStatus.BAD_REQUEST);

        // an incident's id and its blame commit
        assertThat(post("/api/v1/incidents", """
                {"id":"bad-\\ud800","service":"%s","causedByCommitSha":null,
                 "detectedAt":"%s","resolvedAt":null}
                """.formatted(svc(), deployedAt)).getStatusCode())
                .as("incident id").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/v1/incidents", """
                {"id":"%s","service":"%s","causedByCommitSha":"blame-\\ud800",
                 "detectedAt":"%s","resolvedAt":null}
                """.formatted(id(), svc(), deployedAt)).getStatusCode())
                .as("causedByCommitSha").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- the guard the HTTP routes cannot reach ----------------------------

    /**
     * Kills M05 ({@code AND resolved_at IS NULL} deleted) and M34 (the row-count
     * comparison relaxed).
     *
     * <p>Asked of the repository directly, for the same reason
     * {@code correctingAnAbsentDeploymentUpdatesNothingAndSaysSo} is: the
     * service refuses a second resolution before the SQL is ever reached, so no
     * route through the API can exercise this predicate. It is only load-bearing
     * when two resolutions race -- the loser's UPDATE must match zero rows so
     * the caller re-reads instead of overwriting a restore time that is already
     * published. The existing concurrency test cannot see the difference,
     * because both of its racers write the SAME timestamp.
     */
    @Test
    @DisplayName("resolving an already-resolved incident updates nothing and says so")
    void resolveIncidentRefusesAnAlreadyResolvedIncident() {
        String service = svc();
        String incidentId = id();
        Instant detected = Instant.now().minus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant firstResolution = detected.plus(30, ChronoUnit.MINUTES);
        Instant secondResolution = detected.plus(5, ChronoUnit.MINUTES);

        repo.insertIncidentIfAbsent(
                new IncidentEvent(incidentId, service, "abc", detected, null), "hash-open");
        assertThat(repo.resolveIncident(incidentId, firstResolution, "hash-resolved"))
                .as("the first resolution of an open incident succeeds").isTrue();

        assertThat(repo.resolveIncident(incidentId, secondResolution, "hash-moved"))
                .as("a published restore time cannot be moved by a second UPDATE")
                .isFalse();

        assertThat(db.sql("SELECT resolved_at FROM incident_event WHERE id = ?")
                .param(incidentId).query(Instant.class).single())
                .isEqualTo(firstResolution);
        assertThat(db.sql("SELECT payload_hash FROM incident_event WHERE id = ?")
                .param(incidentId).query(String.class).single())
                .as("and the hash still describes the row")
                .isEqualTo("hash-resolved");
    }

    // --- helpers -----------------------------------------------------------

    private static String svc() { return "it-" + UUID.randomUUID(); }
    private static String id()  { return "e-" + UUID.randomUUID(); }

    private ResponseEntity<String> postDeployment(String id, String service, String environment,
                                                  Instant deployedAt, String outcome, String changesJson) {
        return post("/api/v1/deployments", """
                {"id":"%s","service":"%s","environment":"%s","deployedAt":"%s","outcome":"%s",
                 "changes":[%s]}
                """.formatted(id, service, environment, deployedAt, outcome, changesJson));
    }

    private ResponseEntity<String> postIncident(String id, String service, String causedBy,
                                                Instant detectedAt, Instant resolvedAt) {
        return post("/api/v1/incidents", """
                {"id":"%s","service":"%s","causedByCommitSha":%s,"detectedAt":"%s","resolvedAt":%s}
                """.formatted(id, service,
                causedBy == null ? "null" : "\"" + causedBy + "\"",
                detectedAt,
                resolvedAt == null ? "null" : "\"" + resolvedAt + "\""));
    }

    private ResponseEntity<String> post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(IngestAuthFilter.HEADER, "ingestgaptest-secret");
        return http.postForEntity(path, new HttpEntity<>(json, headers), String.class);
    }
}
