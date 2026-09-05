package io.github.jjackson0118.doraloop.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The tests the module shipped without.
 *
 * <p>Everything else in {@code api/src/test} is a {@code @JsonTest} calling
 * {@code ObjectMapper} directly. Before this class existed, {@code V1__events.sql}
 * had never been executed by anything in this repository, none of
 * {@link EventRepository}'s six statements had ever run, {@link IngestService} was
 * never instantiated, and no HTTP status code was asserted anywhere. Replacing
 * {@code insertDeployment} with an empty method left the suite green. A README
 * claiming the service was tested is the failure this project is about, so the
 * claim is now backed by a real database, a real migration, and a real socket.
 *
 * <p>Each test uses a service name unique to itself rather than truncating between
 * tests. Isolation by identity rather than by cleanup means a leaked row cannot
 * make a later test pass, and it matches how the deploy smoke test will address
 * its own synthetic service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcClient db;

    private static final ObjectMapper JSON = new ObjectMapper();

    // --- the migration -----------------------------------------------------

    /**
     * Flyway ran, and it is the source of the schema.
     *
     * <p>Asserting the tables exist would also pass under {@code ddl-auto},
     * which would mean the checked-in SQL was decorative. The version row is
     * what distinguishes "the migration produced this schema" from "something
     * produced this schema."
     */
    @Test
    void flywayAppliedTheCheckedInMigration() {
        String version = db.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).single();
        assertThat(version).isEqualTo("1");

        assertThat(db.sql("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN ('deployment_event','deployment_change','incident_event')
                """).query(Integer.class).single()).isEqualTo(3);
    }

    // --- the round trip ----------------------------------------------------

    @Test
    void deploymentRoundTripsThroughPostgresAndReachesTheMetrics() {
        String service = svc();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);

        ResponseEntity<String> posted = postDeployment(id(), service, "production", deployedAt, "SUCCESS", """
                    {"commitSha":"aaa1","authoredAt":"%s"},
                    {"commitSha":"bbb2","authoredAt":"%s"}
                """.formatted(deployedAt.minus(4, ChronoUnit.HOURS),
                              deployedAt.minus(2, ChronoUnit.HOURS)));
        assertThat(posted.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode report = report(service);
        assertThat(metric(report, "deployment_frequency").get("observedN").asInt()).isEqualTo(1);

        // Lead time is per change, not per deployment. Two commits in the
        // deployed range are two observations -- the whole argument of ADR 0002,
        // and the number that proves deployment_change round-tripped.
        assertThat(metric(report, "lead_time_for_changes").get("observedN").asInt()).isEqualTo(2);
        assertThat(metric(report, "lead_time_for_changes").get("state").asText()).isNotEqualTo("UNOBSERVED");
        assertThat(metric(report, "lead_time_for_changes").get("value").asDouble()).isBetween(2.0, 4.0);
    }

    @Test
    void incidentRoundTripsAndYieldsTimeToRestore() {
        String service = svc();
        Instant deployedAt = Instant.now().minus(6, ChronoUnit.HOURS);
        postDeployment(id(), service, "production", deployedAt, "SUCCESS",
                """
                    {"commitSha":"c0ffee","authoredAt":"%s"}
                """.formatted(deployedAt.minus(1, ChronoUnit.HOURS)));

        Instant detected = deployedAt.plus(10, ChronoUnit.MINUTES);
        ResponseEntity<String> posted = postIncident(id(), service, "c0ffee",
                detected, detected.plus(30, ChronoUnit.MINUTES));
        assertThat(posted.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode report = report(service);
        assertThat(metric(report, "time_to_restore").get("observedN").asInt()).isEqualTo(1);
        assertThat(metric(report, "time_to_restore").get("value").asDouble()).isBetween(0.4, 0.6);
        assertThat(metric(report, "change_failure_rate").get("observedN").asInt()).isEqualTo(1);
    }

    // --- the central claim, through the wire -------------------------------

    /**
     * A service with no events: 200, four metrics, every one UNOBSERVED, and the
     * {@code value} key present and null.
     *
     * <p>Key presence and null-ness are asserted separately because
     * {@code node.get("value").isNull()} and "the key is missing" are the same
     * thing to most typed clients -- which is the confusion the whole read
     * contract exists to prevent. Every metric is checked, not one: an assertion
     * satisfied by one metric out of four would pass a report that rendered the
     * other three as zero.
     */
    @Test
    void unobservedIsExplicitNullOnEveryMetricNotZeroAndNot404() {
        ResponseEntity<String> res = http.getForEntity(reportUrl(svc()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode report = parse(res.getBody());
        JsonNode metrics = report.get("metrics");
        assertThat(metrics).hasSize(4);

        for (JsonNode m : metrics) {
            String name = m.get("name").asText();
            assertThat(m.get("state").asText()).as(name + " state").isEqualTo("UNOBSERVED");
            assertThat(m.has("value")).as(name + " has an explicit value key").isTrue();
            assertThat(m.get("value").isNull()).as(name + " value is null").isTrue();
            assertThat(m.get("observedN").asInt()).as(name + " observedN").isZero();
            assertThat(m.get("definitionOfWrong").asText()).as(name + " wrong").isNotBlank();
        }

        // No boolean health field survived the trip to the wire.
        assertThat(res.getBody()).doesNotContain("\"ok\"").doesNotContain("\"healthy\"");
        assertThat(report.get("summary").get("unobserved")).hasSize(6);
    }

    /**
     * A deployment to a non-production environment contributes to no metric, and
     * ingest says nothing about it.
     *
     * <p>{@code DeploymentEvent.isProduction()} matches only the literal string.
     * A pipeline configured with {@code prod} or {@code demo} therefore produces
     * a report where everything is UNOBSERVED forever while rows accumulate in
     * the table -- a silently empty measurement, which is the failure this
     * project is named for. This test pins the behaviour so that closing the
     * deploy loop cannot walk into it unnoticed; the missing warning is tracked
     * separately.
     */
    @Test
    void nonProductionDeploymentReachesTheDatabaseAndNoMetric() {
        String service = svc();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        String eventId = id();

        assertThat(postDeployment(eventId, service, "staging", deployedAt, "SUCCESS",
                """
                    {"commitSha":"dddd","authoredAt":"%s"}
                """.formatted(deployedAt.minus(1, ChronoUnit.HOURS)))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(db.sql("SELECT count(*) FROM deployment_event WHERE id = ?")
                .param(eventId).query(Integer.class).single()).isEqualTo(1);

        JsonNode report = report(service);
        assertThat(metric(report, "deployment_frequency").get("state").asText()).isEqualTo("UNOBSERVED");
        assertThat(metric(report, "deployment_frequency").get("observedN").asInt()).isZero();
    }

    /**
     * A redeploy carries no new change, and it is still a deployment.
     *
     * <p>Added because a perturbation exposed the gap: adding {@code @NotEmpty}
     * to {@code changes} was caught only incidentally, by a test that counts
     * validation errors. The endpoint-level claim that an empty range is
     * accepted -- and counts toward deployment frequency while contributing no
     * lead-time observation -- was asserted nowhere. It is the natural instinct
     * to reject this, and doing so under-counts the metric.
     */
    @Test
    void aRedeployCarryingNoChangesIsAcceptedAndCounted() {
        String service = svc();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);

        assertThat(postDeployment(id(), service, "production", deployedAt, "SUCCESS", "")
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode report = report(service);
        assertThat(metric(report, "deployment_frequency").get("observedN").asInt()).isEqualTo(1);
        assertThat(metric(report, "lead_time_for_changes").get("state").asText()).isEqualTo("UNOBSERVED");
    }

    // --- idempotency and conflict ------------------------------------------

    @Test
    void identicalReplayIsARetryAndIsNotCountedTwice() {
        String service = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        String changes = """
                    {"commitSha":"eeee","authoredAt":"%s"}
                """.formatted(deployedAt.minus(1, ChronoUnit.HOURS));

        assertThat(postDeployment(eventId, service, "production", deployedAt, "SUCCESS", changes)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(postDeployment(eventId, service, "production", deployedAt, "SUCCESS", changes)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(db.sql("SELECT count(*) FROM deployment_event WHERE id = ?")
                .param(eventId).query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM deployment_change WHERE deployment_id = ?")
                .param(eventId).query(Integer.class).single()).isEqualTo(1);
        assertThat(metric(report(service), "deployment_frequency").get("observedN").asInt()).isEqualTo(1);
    }

    @Test
    void sameIdWithADifferentPayloadIs409AndDoesNotOverwrite() {
        String service = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        String changes = """
                    {"commitSha":"ffff","authoredAt":"%s"}
                """.formatted(deployedAt.minus(1, ChronoUnit.HOURS));

        postDeployment(eventId, service, "production", deployedAt, "SUCCESS", changes);

        ResponseEntity<String> conflict =
                postDeployment(eventId, service, "staging", deployedAt, "SUCCESS", changes);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(db.sql("SELECT environment FROM deployment_event WHERE id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("production");
    }

    // --- strictness survives the framework ---------------------------------

    /**
     * The pre-ADR-0002 field is rejected at the endpoint, not merely by an
     * ObjectMapper in a unit test.
     *
     * <p>{@code fail-on-unknown-properties} is a property, and a property can be
     * lost to a profile or an environment override without any JVM test
     * noticing. The status is asserted here; the body it comes back with is
     * asserted separately, because a 400 that does not name the offending field
     * defeats the reason the strictness exists.
     */
    @Test
    void aPreAdr0002FieldIsRejectedByTheEndpoint() {
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        String body = """
                {"id":"%s","service":"%s","environment":"production",
                 "deployedAt":"%s","outcome":"SUCCESS","commitAuthoredAt":"%s","changes":[]}
                """.formatted(id(), svc(), deployedAt, deployedAt.minus(1, ChronoUnit.HOURS));

        ResponseEntity<String> res = post("/api/v1/deployments", body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aMissingRequiredFieldNamesEveryViolationNotTheFirst() {
        String body = """
                {"id":"","service":"","environment":"production",
                 "deployedAt":"%s","outcome":"SUCCESS","changes":[]}
                """.formatted(Instant.now().minus(1, ChronoUnit.HOURS));

        ResponseEntity<String> res = post("/api/v1/deployments", body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode errors = parse(res.getBody()).get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors.toString()).contains("id").contains("service");
    }

    // --- ADR 0003 at the boundary ------------------------------------------

    /**
     * A change authored after its own deployment is stored, warned about, and
     * excluded from lead time -- never rejected.
     *
     * <p>A 4xx here would lose a real deployment and deployment frequency would
     * silently fall: the validation written to protect a metric becoming the
     * thing that corrupts it. This asserts all three halves at once -- the 201,
     * the warning on the response, and the data-quality signal that counts it --
     * because any one of them alone can be satisfied while the other two are
     * broken.
     */
    @Test
    void anImplausibleChangeIsStoredWarnedAndQuarantined() {
        String service = svc();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);

        ResponseEntity<String> res = postDeployment(id(), service, "production", deployedAt, "SUCCESS", """
                    {"commitSha":"good","authoredAt":"%s"},
                    {"commitSha":"fromthefuture","authoredAt":"%s"}
                """.formatted(deployedAt.minus(3, ChronoUnit.HOURS),
                              deployedAt.plus(2, ChronoUnit.HOURS)));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode warnings = parse(res.getBody()).get("warnings");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).get("code").asText()).isEqualTo("suspect-author-date");
        assertThat(warnings.get(0).get("path").asText()).isEqualTo("changes[1]");

        JsonNode report = report(service);
        // Stored: both changes are in the table.
        assertThat(metric(report, "data_quality.suspect_changes").get("value").asDouble()).isEqualTo(1.0);
        // Quarantined: only the plausible one is an observation.
        assertThat(metric(report, "lead_time_for_changes").get("observedN").asInt()).isEqualTo(1);
    }

    @Test
    void anImplausibleIncidentIsStoredWarnedAndQuarantined() {
        String service = svc();
        Instant detected = Instant.now().minus(2, ChronoUnit.HOURS);

        ResponseEntity<String> res = postIncident(id(), service, null,
                detected, detected.minus(30, ChronoUnit.MINUTES));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode warnings = parse(res.getBody()).get("warnings");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).get("code").asText()).isEqualTo("suspect-incident-ordering");

        JsonNode report = report(service);
        assertThat(metric(report, "data_quality.suspect_incidents").get("value").asDouble()).isEqualTo(1.0);
        assertThat(metric(report, "time_to_restore").get("state").asText()).isEqualTo("UNOBSERVED");
    }

    // --- the window --------------------------------------------------------

    @Test
    void aDeploymentOutsideTheWindowIsNotAnObservation() {
        String service = svc();
        Instant old = Instant.now().minus(90, ChronoUnit.DAYS);
        postDeployment(id(), service, "production", old, "SUCCESS", """
                    {"commitSha":"ancient","authoredAt":"%s"}
                """.formatted(old.minus(1, ChronoUnit.HOURS)));

        assertThat(metric(report(service), "deployment_frequency").get("state").asText())
                .isEqualTo("UNOBSERVED");
        assertThat(metric(reportWindow(service, "P180D"), "deployment_frequency").get("observedN").asInt())
                .isEqualTo(1);
    }

    @Test
    void anUnparseableWindowIs400() {
        assertThat(http.getForEntity(reportUrl(svc()) + "&window=thirty-days", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
        return http.postForEntity(path, new HttpEntity<>(json, headers), String.class);
    }

    private String reportUrl(String service) {
        return "/api/v1/services/" + service + "/report?x=1";
    }

    private JsonNode report(String service) {
        return reportWindow(service, "P30D");
    }

    private JsonNode reportWindow(String service, String window) {
        ResponseEntity<String> res = http.getForEntity(
                "/api/v1/services/" + service + "/report?window=" + window, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(res.getBody());
    }

    /** Looks in both metric lists; a caller asking by name should not have to know which. */
    private static JsonNode metric(JsonNode report, String name) {
        for (String list : new String[]{"metrics", "dataQuality"}) {
            for (JsonNode m : report.get(list)) {
                if (m.get("name").asText().equals(name)) return m;
            }
        }
        throw new AssertionError("no metric named " + name + " in " + report);
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("response was not JSON: " + body, e);
        }
    }
}
