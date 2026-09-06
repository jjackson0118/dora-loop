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
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Modifier;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dora.ingest.token=apiintegrationtest-secret")
@Testcontainers
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcClient db;
    @Autowired TransactionAttributeSource transactionAttributes;
    @Autowired org.springframework.boot.actuate.health.HealthEndpointGroups healthGroups;
    @Autowired EventRepository repo;

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

    // --- the write boundary ------------------------------------------------

    /**
     * A write that fails partway through leaves nothing behind.
     *
     * <p>{@code insertDeployment} issues 1 + N statements. Autocommitted
     * individually, a failure after the parent insert would leave a deployment
     * row with some or none of its changes -- permanently. That deployment
     * counts toward deployment frequency and contributes no lead-time
     * observation, which is exactly what a legal redeploy looks like. The
     * corruption would render as fewer observations, never as an error, which
     * is why it is worth a fault-injection test rather than a code reading.
     *
     * <p>The failure is injected with a trigger rather than a mock, because the
     * thing under test is the boundary between this JVM and Postgres; a mocked
     * repository would prove only that the mock was called.
     */
    @Test
    void aFailedWriteLeavesNoPartialDeployment() {
        String service = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);

        db.sql("""
                CREATE OR REPLACE FUNCTION fail_on_marked_change() RETURNS trigger AS $$
                BEGIN
                    IF NEW.commit_sha = 'INJECTED-FAILURE' THEN
                        RAISE EXCEPTION 'injected mid-write failure';
                    END IF;
                    RETURN NEW;
                END; $$ LANGUAGE plpgsql
                """).update();
        db.sql("""
                CREATE TRIGGER fail_on_marked_change_trigger
                BEFORE INSERT ON deployment_change
                FOR EACH ROW EXECUTE FUNCTION fail_on_marked_change()
                """).update();
        try {
            ResponseEntity<String> res = postDeployment(eventId, service, "production",
                    deployedAt, "SUCCESS", """
                        {"commitSha":"written-first","authoredAt":"%s"},
                        {"commitSha":"INJECTED-FAILURE","authoredAt":"%s"}
                    """.formatted(deployedAt.minus(3, ChronoUnit.HOURS),
                                  deployedAt.minus(2, ChronoUnit.HOURS)));

            assertThat(res.getStatusCode().is5xxServerError())
                    .as("an injected database failure is a server error")
                    .isTrue();

            assertThat(db.sql("SELECT count(*) FROM deployment_event WHERE id = ?")
                    .param(eventId).query(Integer.class).single())
                    .as("the parent row must not survive a failed write")
                    .isZero();
            assertThat(db.sql("SELECT count(*) FROM deployment_change WHERE deployment_id = ?")
                    .param(eventId).query(Integer.class).single())
                    .as("no change row may survive either")
                    .isZero();

            assertThat(metric(report(service), "deployment_frequency").get("state").asText())
                    .as("and the metric never saw it")
                    .isEqualTo("UNOBSERVED");
        } finally {
            db.sql("DROP TRIGGER IF EXISTS fail_on_marked_change_trigger ON deployment_change").update();
            db.sql("DROP FUNCTION IF EXISTS fail_on_marked_change()").update();
        }
    }

    /**
     * Every {@code @Transactional} method in this package resolves a real
     * transaction attribute, asked of the container's own bean.
     *
     * <p>This pins a framework default the code depends on and is easy to get
     * wrong in both directions. Spring's long-standing guidance is that
     * proxy-based transactions apply to public methods only, and a
     * hand-constructed {@code AnnotationTransactionAttributeSource} does default
     * to {@code publicMethodsOnly = true} -- but the bean Spring Boot actually
     * configures for CGLIB proxies is constructed with {@code false}, and the
     * package-private ingest methods are advised correctly. A review that reads
     * the annotation source in isolation concludes the opposite and is wrong.
     *
     * <p>So the assertion is made against {@code ctx.getBean(...)}, never
     * against a new instance: the question is what this application resolves,
     * not what a default constructor would. If a future Spring version or a
     * configuration change flips that default, this fails loudly instead of the
     * boundary quietly disappearing.
     */
    @Test
    void everyTransactionalMethodResolvesARealAttribute() throws Exception {
        List<String> examined = new ArrayList<>();
        List<String> inert = new ArrayList<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        for (BeanDefinition bd : scanner.findCandidateComponents(
                "io.github.jjackson0118.doraloop.api")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            for (Method m : type.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Transactional.class)) continue;
                examined.add(type.getSimpleName() + "." + m.getName());
                if (transactionAttributes.getTransactionAttribute(m, type) == null) {
                    inert.add(type.getSimpleName() + "." + m.getName()
                            + " (" + Modifier.toString(m.getModifiers()) + ")");
                }
            }
        }

        // An empty scan is not a pass -- the vacuity rule, inside a test.
        assertThat(examined).as("@Transactional methods examined").isNotEmpty();
        assertThat(inert).as("@Transactional methods Spring would not advise").isEmpty();
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

        ResponseEntity<String> first =
                postDeployment(eventId, service, "production", deployedAt, "SUCCESS", changes);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(parse(first.getBody()).get("disposition").asText()).isEqualTo("STORED");

        // 200, not 201. A retry created nothing, and a client counting 201s to
        // know how many deployments it recorded would be counting retries.
        ResponseEntity<String> retry =
                postDeployment(eventId, service, "production", deployedAt, "SUCCESS", changes);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(retry.getBody()).get("disposition").asText()).isEqualTo("DUPLICATE");

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

    /**
     * Two different deployments cannot be made to collide by putting the
     * separator inside a field value.
     *
     * <p>This is a regression test for one historical defect, and nothing more.
     * The digest used to be taken over {@code dto.toString()}, so
     * {@code ", environment="} inside a value forged a field boundary. It does
     * <strong>not</strong> test that the current encoding is injective: no
     * pipe-delimited scheme ever emits that sequence, so this passes under any
     * encoding at all, including one with no length prefix. That was measured,
     * not assumed. The invariant itself lives in
     * {@link CanonicalEncodingTest}, and the wire-level case is
     * {@link #aPipeInsideAValueCannotForgeAPayloadMatch()}.
     */
    @Test
    void aSeparatorInsideAValueCannotForgeAPayloadMatch() {
        String eventId = id();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        String change = """
                    {"commitSha":"aaa","authoredAt":"%s"}
                """.formatted(deployedAt.minus(2, ChronoUnit.HOURS));

        assertThat(postDeployment(eventId, "payments, environment=production", "staging",
                deployedAt, "SUCCESS", change).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(postDeployment(eventId, "payments", "production, environment=staging",
                deployedAt, "SUCCESS", change).getStatusCode())
                .as("a different deployment reusing the id is a conflict, not a retry")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(db.sql("SELECT environment FROM deployment_event WHERE id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("staging");
    }

    // --- the readiness probe -----------------------------------------------

    /**
     * The readiness group actually contains the database.
     *
     * <p>{@code management.health.db.enabled} adds the indicator to the
     * aggregate endpoint only. Enabling probes installs
     * {@code AvailabilityProbesHealthEndpointGroups}, whose readiness group is
     * {@code readinessState} and nothing else -- so a readiness probe returned
     * UP while Postgres was unreachable, and every request would have failed.
     * The group membership is asserted rather than the response body, because
     * {@code show-details: never} means a healthy body is identical either way:
     * the response cannot distinguish "the database was checked and is up" from
     * "the database was never checked."
     */
    @Test
    void readinessIncludesTheDatabaseAndLivenessDoesNot() {
        var readiness = healthGroups.get("readiness");
        assertThat(readiness).isNotNull();
        assertThat(readiness.isMember("db"))
                .as("readiness must fail when the database is unreachable")
                .isTrue();
        assertThat(readiness.isMember("readinessState")).isTrue();

        // Liveness must NOT include it: a database outage should stop traffic
        // being routed here, not have the process killed and restarted, which
        // cannot fix an outage in a different process and turns a degradation
        // into a crash loop.
        var liveness = healthGroups.get("liveness");
        assertThat(liveness).isNotNull();
        assertThat(liveness.isMember("db"))
                .as("a database outage must not restart the process")
                .isFalse();
    }

    /**
     * The delimiter of the encoding actually in use, at the HTTP boundary.
     *
     * <p>Deleting the length prefix from {@code field()} makes these two
     * payloads encode identically, and the second is then answered
     * {@code 200 DUPLICATE} with nothing written -- a production deployment
     * lost while the producer is told it was already recorded.
     */
    @Test
    void aPipeInsideAValueCannotForgeAPayloadMatch() {
        String eventId = id();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        String change = """
                    {"commitSha":"aaa","authoredAt":"%s"}
                """.formatted(deployedAt.minus(2, ChronoUnit.HOURS));

        assertThat(postDeployment(eventId, "pay|3|abc", "zz", deployedAt, "SUCCESS", change)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(postDeployment(eventId, "pay", "3|abc|zz", deployedAt, "SUCCESS", change)
                .getStatusCode())
                .as("a pipe in a value must not forge a field boundary")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(db.sql("SELECT environment FROM deployment_event WHERE id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("zz");
    }

    /**
     * The commit range is part of the payload.
     *
     * <p>Every other id-reuse test varies service, environment or outcome. The
     * changes list was the one dimension never varied under a reused id, so
     * dropping it from the canonical encoding entirely left the whole suite
     * green -- two deployments carrying completely different commits hashing
     * the same, and the second discarded as a retry.
     */
    @Test
    void sameIdWithDifferentCommitsIs409AndDoesNotOverwrite() {
        String service = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant authoredAt = deployedAt.minus(2, ChronoUnit.HOURS);

        assertThat(postDeployment(eventId, service, "production", deployedAt, "SUCCESS",
                """
                    {"commitSha":"aaa","authoredAt":"%s"}
                """.formatted(authoredAt)).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(postDeployment(eventId, service, "production", deployedAt, "SUCCESS",
                """
                    {"commitSha":"bbb","authoredAt":"%s"}
                """.formatted(authoredAt)).getStatusCode())
                .as("a different commit range under one id is a conflict, not a retry")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(db.sql("SELECT commit_sha FROM deployment_change WHERE deployment_id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("aaa");
    }

    /**
     * Text that cannot be stored faithfully is refused rather than accepted and
     * silently altered.
     *
     * <p>An unpaired surrogate is not representable in UTF-8: both the digest
     * and PostgreSQL replace it, so two payloads differing only there would
     * hash the same AND store the same. Answering {@code STORED} would report
     * having recorded something other than what was sent.
     */
    @Test
    void textThatCannotBeStoredFaithfullyIsRefused() {
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        String body = """
                {"id":"%s","service":"probe-\\ud800","environment":"production",
                 "deployedAt":"%s","outcome":"SUCCESS","changes":[]}
                """.formatted(id(), deployedAt);

        ResponseEntity<String> res = post("/api/v1/deployments", body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("unpaired surrogate");

        assertThat(db.sql("SELECT count(*) FROM deployment_event WHERE service LIKE 'probe-%'")
                .query(Integer.class).single()).isZero();
    }

    // --- corrections that arrive later --------------------------------------

    /**
     * A deployment that succeeds and is later rolled back is one deployment
     * with a corrected outcome.
     *
     * <p>This was a 409, and the rollback was dropped. {@code ROLLED_BACK} is
     * one of only two numerator terms in change failure rate, so refusing the
     * correction understates CFR -- the service looked better the more often it
     * had to be rolled back. The bias is always toward flattering the metric,
     * which is the direction that gets believed.
     */
    @Test
    void aRollbackReportedLaterCorrectsTheDeploymentItUndoes() {
        String service = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(3, ChronoUnit.HOURS);
        String changes = """
                    {"commitSha":"abc123","authoredAt":"%s"}
                """.formatted(deployedAt.minus(2, ChronoUnit.HOURS));

        assertThat(postDeployment(eventId, service, "production", deployedAt, "SUCCESS", changes)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> corrected =
                postDeployment(eventId, service, "production", deployedAt, "ROLLED_BACK", changes);
        assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(corrected.getBody()).get("disposition").asText()).isEqualTo("UPDATED");

        assertThat(db.sql("SELECT outcome FROM deployment_event WHERE id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("ROLLED_BACK");
        assertThat(db.sql("SELECT count(*) FROM deployment_event WHERE id = ?")
                .param(eventId).query(Integer.class).single())
                .as("a correction is not a second deployment")
                .isEqualTo(1);

        // And it reaches the metric it exists to feed.
        JsonNode report = report(service);
        assertThat(metric(report, "deployment_frequency").get("observedN").asInt()).isEqualTo(1);
        assertThat(metric(report, "change_failure_rate").get("value").asDouble()).isEqualTo(100.0);
    }

    @Test
    void anOutcomeTransitionThatIsNotACorrectionIs409() {
        String service = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        String changes = """
                    {"commitSha":"def456","authoredAt":"%s"}
                """.formatted(deployedAt.minus(1, ChronoUnit.HOURS));

        postDeployment(eventId, service, "production", deployedAt, "SUCCESS", changes);

        // FAILED_ROLLOUT means the change never reached production. It cannot
        // follow a success: a retry that then failed is a different deployment.
        assertThat(postDeployment(eventId, service, "production", deployedAt, "FAILED_ROLLOUT", changes)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(db.sql("SELECT outcome FROM deployment_event WHERE id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("SUCCESS");
    }

    @Test
    void resolvingAnIncidentLaterIsAnUpdateNotACreate() {
        String service = svc();
        String incidentId = id();
        Instant detected = Instant.now().minus(4, ChronoUnit.HOURS);

        assertThat(postIncident(incidentId, service, "abc", detected, null)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(metric(report(service), "time_to_restore").get("state").asText())
                .as("an open incident contributes no restore observation")
                .isEqualTo("UNOBSERVED");

        ResponseEntity<String> resolved = postIncident(
                incidentId, service, "abc", detected, detected.plus(45, ChronoUnit.MINUTES));
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(resolved.getBody()).get("disposition").asText()).isEqualTo("UPDATED");

        assertThat(metric(report(service), "time_to_restore").get("value").asDouble())
                .isBetween(0.7, 0.8);
    }

    /**
     * A retry of the original open-incident payload cannot un-resolve it.
     *
     * <p>The write was an unconditional upsert of every column, so re-POSTing
     * the event as first sent -- an ordinary thing for a pipeline to do on
     * retry -- set {@code resolved_at} back to NULL. That silently deleted a
     * time-to-restore observation and answered 201 with no warning: the metric
     * went down because the measurement disappeared, not because anything
     * improved.
     */
    @Test
    void aRetryOfTheOpenPayloadCannotUnResolveAnIncident() {
        String service = svc();
        String incidentId = id();
        Instant detected = Instant.now().minus(4, ChronoUnit.HOURS);
        Instant resolvedAt = detected.plus(30, ChronoUnit.MINUTES);

        postIncident(incidentId, service, "abc", detected, null);
        assertThat(postIncident(incidentId, service, "abc", detected, resolvedAt)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(postIncident(incidentId, service, "abc", detected, null)
                .getStatusCode())
                .as("a payload omitting resolvedAt must not clear it")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(db.sql("SELECT resolved_at IS NOT NULL FROM incident_event WHERE id = ?")
                .param(incidentId).query(Boolean.class).single()).isTrue();
        assertThat(metric(report(service), "time_to_restore").get("observedN").asInt()).isEqualTo(1);
    }

    @Test
    void anIncidentResolutionCannotBeMovedOnceSet() {
        String service = svc();
        String incidentId = id();
        Instant detected = Instant.now().minus(4, ChronoUnit.HOURS);

        postIncident(incidentId, service, "abc", detected, null);
        postIncident(incidentId, service, "abc", detected, detected.plus(30, ChronoUnit.MINUTES));

        assertThat(postIncident(incidentId, service, "abc", detected, detected.plus(5, ChronoUnit.MINUTES))
                .getStatusCode())
                .as("a published restore time cannot be rewritten")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void changingAnIncidentsDetectionTimeIs409NotASilentOverwrite() {
        String service = svc();
        String incidentId = id();
        Instant detected = Instant.now().minus(4, ChronoUnit.HOURS);

        postIncident(incidentId, service, "abc", detected, null);
        assertThat(postIncident(incidentId, service, "abc", detected.minus(1, ChronoUnit.HOURS), null)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * A correction must match the deployment it corrects in every field but the
     * outcome.
     *
     * <p>{@code differsOnlyByOutcome} could be replaced with {@code return true}
     * and the suite stayed green, because no test varied a non-outcome field
     * <em>and</em> the outcome together. Under that mutation a POST changing
     * the environment while flipping to ROLLED_BACK is applied as a
     * "correction", and since only the outcome and hash are written, the row's
     * payload_hash then describes a payload the row does not contain.
     */
    @Test
    void aCorrectionCarryingADifferentPayloadIs409() {
        String service = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant authoredAt = deployedAt.minus(1, ChronoUnit.HOURS);
        String aaa = """
                    {"commitSha":"aaa","authoredAt":"%s"}
                """.formatted(authoredAt);
        String bbb = """
                    {"commitSha":"bbb","authoredAt":"%s"}
                """.formatted(authoredAt);

        postDeployment(eventId, service, "production", deployedAt, "SUCCESS", aaa);

        // Right transition, wrong commits.
        assertThat(postDeployment(eventId, service, "production", deployedAt, "ROLLED_BACK", bbb)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Right transition, wrong environment.
        assertThat(postDeployment(eventId, service, "staging", deployedAt, "ROLLED_BACK", aaa)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(db.sql("SELECT outcome FROM deployment_event WHERE id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("SUCCESS");
        assertThat(db.sql("SELECT commit_sha FROM deployment_change WHERE deployment_id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("aaa");
    }

    /**
     * A correction to a multi-commit deployment, which is what a real rollback
     * is.
     *
     * <p>The replay comparison uses an order-sensitive {@code List.equals}
     * against rows read back {@code ORDER BY ordinal}. Reversing that ordering
     * turns a legal rollback of any multi-commit deployment into a 409 -- a
     * dropped ROLLED_BACK and an understated change failure rate -- and no test
     * performed a correction on more than one change, so the ordering was right
     * by luck rather than by assertion.
     */
    @Test
    void aCorrectionToAMultiCommitDeploymentIsAccepted() {
        String service = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        String changes = """
                    {"commitSha":"aaa","authoredAt":"%s"},
                    {"commitSha":"bbb","authoredAt":"%s"},
                    {"commitSha":"ccc","authoredAt":"%s"}
                """.formatted(deployedAt.minus(3, ChronoUnit.HOURS),
                              deployedAt.minus(2, ChronoUnit.HOURS),
                              deployedAt.minus(1, ChronoUnit.HOURS));

        assertThat(postDeployment(eventId, service, "production", deployedAt, "SUCCESS", changes)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> corrected =
                postDeployment(eventId, service, "production", deployedAt, "ROLLED_BACK", changes);
        assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(corrected.getBody()).get("disposition").asText()).isEqualTo("UPDATED");

        assertThat(db.sql("SELECT string_agg(commit_sha, ',' ORDER BY ordinal) "
                + "FROM deployment_change WHERE deployment_id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("aaa,bbb,ccc");

        // Re-posting the corrected payload is now the retry, which pins that
        // the correction rewrote payload_hash to match the row.
        ResponseEntity<String> retry =
                postDeployment(eventId, service, "production", deployedAt, "ROLLED_BACK", changes);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(retry.getBody()).get("disposition").asText()).isEqualTo("DUPLICATE");
    }

    @Test
    void anIdenticalIncidentReplayIsARetryNotACreate() {
        String service = svc();
        String incidentId = id();
        Instant detected = Instant.now().minus(4, ChronoUnit.HOURS);

        assertThat(postIncident(incidentId, service, "abc", detected, null)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> retry = postIncident(incidentId, service, "abc", detected, null);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(retry.getBody()).get("disposition").asText()).isEqualTo("DUPLICATE");

        assertThat(db.sql("SELECT count(*) FROM incident_event WHERE id = ?")
                .param(incidentId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aResolutionCarryingADifferentBlameCommitIs409() {
        String service = svc();
        String incidentId = id();
        Instant detected = Instant.now().minus(4, ChronoUnit.HOURS);

        postIncident(incidentId, service, "abc", detected, null);
        assertThat(postIncident(incidentId, service, "def", detected, detected.plus(30, ChronoUnit.MINUTES))
                .getStatusCode())
                .as("a resolution must not silently rewrite which commit is blamed")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(db.sql("SELECT caused_by_commit_sha FROM incident_event WHERE id = ?")
                .param(incidentId).query(String.class).single()).isEqualTo("abc");
    }

    /**
     * The repository's own filter, asked directly.
     *
     * <p>Separate from the end-to-end test below on purpose. The service filter
     * exists in the SQL and again in {@code DoraCalculator}, and an end-to-end
     * test cannot tell which one did the work: deleting either half leaves the
     * other screening the rows out, so both mutations survived a suite that
     * contained the end-to-end test. A redundantly implemented rule needs one
     * test per layer, or it has none.
     */
    @Test
    void theRepositoryReturnsOnlyTheRequestedServicesRows() {
        String mine = svc();
        String theirs = svc();
        Instant at = Instant.now().minus(1, ChronoUnit.HOURS);

        postDeployment(id(), theirs, "production", at, "SUCCESS", """
                    {"commitSha":"other","authoredAt":"%s"}
                """.formatted(at.minus(1, ChronoUnit.HOURS)));
        postIncident(id(), theirs, "other", at, at.plus(10, ChronoUnit.MINUTES));

        assertThat(repo.deploymentsFor(mine))
                .as("SQL must not return another service's deployments").isEmpty();
        assertThat(repo.incidentsFor(mine))
                .as("SQL must not return another service's incidents").isEmpty();

        // And the rows really are there to be wrongly returned, so an empty
        // result cannot pass by the table being empty.
        assertThat(repo.deploymentsFor(theirs)).hasSize(1);
        assertThat(repo.incidentsFor(theirs)).hasSize(1);
    }

    /**
     * One service's events never appear in another's report.
     *
     * <p>The filter is implemented twice -- in SQL and again in
     * {@code DoraCalculator} -- and each half covered for the other, so all
     * five mutations removing one of them individually left the suite green.
     * Removing the deployments SQL filter and the calculator's deployment
     * filter together fails 14 tests (12 api, 2 core), measured. This said
     * "seven" until a reviewer counted -- a number about how much coverage the
     * pair has, written from memory, in a comment whose subject is that the
     * pair was never separately exercised. The suite proved the pair worked and had
     * never exercised either member.
     */
    @Test
    void oneServicesEventsNeverAppearInAnothersReport() {
        String mine = svc();
        String theirs = svc();
        Instant at = Instant.now().minus(1, ChronoUnit.HOURS);

        postDeployment(id(), theirs, "production", at, "SUCCESS", """
                    {"commitSha":"other","authoredAt":"%s"}
                """.formatted(at.minus(1, ChronoUnit.HOURS)));
        postIncident(id(), theirs, "other", at, at.plus(10, ChronoUnit.MINUTES));

        JsonNode report = report(mine);
        assertThat(metric(report, "deployment_frequency").get("state").asText()).isEqualTo("UNOBSERVED");
        assertThat(metric(report, "time_to_restore").get("state").asText()).isEqualTo("UNOBSERVED");
        assertThat(metric(report, "lead_time_for_changes").get("state").asText()).isEqualTo("UNOBSERVED");
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

    // --- concurrency --------------------------------------------------------




    /**
     * A report is one measurement, not several taken at different moments.
     *
     * <p>The deployments and their changes are separate queries. Read outside a
     * transaction, a deployment committed between them appears with an empty
     * changes list -- indistinguishable from a legal redeploy, so it renders as
     * a deployment contributing no lead-time observation rather than as an
     * error. Measured under four concurrent writers: 348 of 385 reads
     * disagreed, always in the direction of fewer changes.
     *
     * <p>{@code readOnly = true} alone did NOT fix it, which is worth stating
     * because it is the obvious fix: at READ COMMITTED every statement takes a
     * fresh snapshot even inside one transaction, and the measurement was
     * unchanged at 335 of 375. It takes REPEATABLE_READ, and after that the
     * same probe reported 0 of 360.
     */
    @Test
    void theReportIsReadConsistentUnderConcurrentWrites() throws Exception {
        String service = svc();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicBoolean stop = new AtomicBoolean(false);
        List<Future<?>> writers = new ArrayList<>();
        for (int w = 0; w < 3; w++) {
            writers.add(pool.submit(() -> {
                while (!stop.get()) {
                    Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
                    postDeployment(id(), service, "production", t, "SUCCESS", """
                                {"commitSha":"%s","authoredAt":"%s"}
                            """.formatted(id(), t.minus(2, ChronoUnit.HOURS)));
                }
                return null;
            }));
        }

        // A fixed number of reads, not a fixed slice of wall-clock time.
        //
        // This asserted "more than 20 reads in 4 seconds" and passed everywhere
        // I ran it. What that assertion actually measures is how fast the
        // machine is, and a test whose verdict depends on the runner is a test
        // that will eventually fail for a reason unrelated to the code. Counting
        // reads instead makes the work deterministic; the deadline below only
        // stops a genuinely stuck run from hanging the suite.
        int reads = 0;
        int inconsistent = 0;
        final int targetReads = 40;
        try {
            long deadline = System.currentTimeMillis() + 60_000;
            while (reads < targetReads && System.currentTimeMillis() < deadline) {
                JsonNode report = report(service);
                int deploys = metric(report, "deployment_frequency").get("observedN").asInt();
                int leads = metric(report, "lead_time_for_changes").get("observedN").asInt();
                reads++;
                if (deploys != leads) inconsistent++;
            }
        } finally {
            stop.set(true);
            for (Future<?> f : writers) f.get();
            pool.shutdownNow();
        }

        // Every deployment here carries exactly one change, so a consistent read
        // must report the two counts equal.
        assertThat(reads)
                .as("every read must have happened; a short count means the run was stuck, not that the code is fine")
                .isEqualTo(targetReads);
        assertThat(inconsistent)
                .as("%d of %d reads saw a deployment without its changes", inconsistent, reads)
                .isZero();
    }

    private List<ResponseEntity<String>> race(Callable<ResponseEntity<String>> a,
                                              Callable<ResponseEntity<String>> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier gate = new CyclicBarrier(2);
        try {
            List<Future<ResponseEntity<String>>> futures = pool.invokeAll(List.of(
                    (Callable<ResponseEntity<String>>) () -> { gate.await(); return a.call(); },
                    (Callable<ResponseEntity<String>>) () -> { gate.await(); return b.call(); }));
            List<ResponseEntity<String>> out = new ArrayList<>();
            for (Future<ResponseEntity<String>> f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // --- contention, constructed rather than hoped for ----------------------

    /**
     * The barrier tests that used to be here released two threads and hoped they
     * collide. This block does not hope.
     *
     * <p>The hole in a barrier race is that the barrier releases both threads
     * <em>before</em> they build a request, cross Tomcat, run Jackson and take a
     * JDBC connection. If they do not overlap at the contended statement the
     * round is a sequential replay, and a sequential replay produces exactly the
     * observable result a correctly-handled race produces. Nothing distinguishes
     * "the conflict path handled a race" from "there was no race", so the test
     * can pass having proved nothing.
     *
     * <p>Measured, 200 barrier rounds of two conflicting resolutions, classified
     * by which branch the loser actually took:
     *
     * <pre>
     *   36 cores, warm:   194 overlapped at the UPDATE,   6 did not
     *   pinned to 1 core: 198 overlapped,                 2 did not
     *   second sample:    188 overlapped,                12 did not,
     *                     and the degenerate rounds were 0, 1, 3, 13, 38, ...
     * </pre>
     *
     * <p>The last line is the one that matters: the misses cluster in the first
     * few rounds, while the JIT is cold and the connection pool is still filling
     * -- which is exactly the six-round window the barrier tests occupy.
     *
     * <p>So instead of racing and hoping, these tests <em>construct</em> the
     * interleaving. A second JDBC connection, outside the application's pool,
     * performs the winner's write and holds the transaction open. The request
     * under test then reaches the contended statement, finds the row locked, and
     * blocks -- and that blocking is asserted through {@code pg_blocking_pids}
     * before the winner is allowed to commit. A round in which the request never
     * blocked fails instead of passing.
     *
     * <p>What this costs, stated plainly: the winner's write is the test's own
     * SQL rather than a request through {@code IngestService}. The path under
     * test is the loser's, which is entirely production code reached over a real
     * socket; the winner is a fixture. {@link #anHttpRaceStillReachesTheLoserPath}
     * keeps a real two-request race as a canary that this model is reachable.
     */
    @Test
    void theLoserOfADeploymentInsertRaceRefusesAConflictingPayload() throws Exception {
        String service = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant authoredAt = deployedAt.minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);

        IngestDtos.DeploymentDto winner =
                deploymentDto(eventId, service, "production", deployedAt, authoredAt);
        IngestDtos.DeploymentDto loserPayload =
                deploymentDto(eventId, service, "staging", deployedAt, authoredAt);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection held = openHeldConnection()) {
            insertDeploymentOn(held, winner);

            Future<ResponseEntity<String>> loser = pool.submit(
                    () -> post("/api/v1/deployments", deploymentJson(loserPayload)));

            // Asserted, not assumed: the request is inside
            // INSERT ... ON CONFLICT DO NOTHING, waiting on the uncommitted row.
            awaitBlockedOn("INSERT INTO deployment_event");
            held.commit();

            ResponseEntity<String> res = loser.get(30, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(parse(res.getBody()).get("detail").asText())
                    .contains("already exists with a different payload");
        } finally {
            pool.shutdownNow();
        }

        // The winner's payload survived, and the loser wrote no change rows.
        assertThat(db.sql("SELECT environment FROM deployment_event WHERE id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("production");
        assertThat(db.sql("SELECT count(*) FROM deployment_change WHERE deployment_id = ?")
                .param(eventId).query(Integer.class).single()).isEqualTo(1);
    }

    /**
     * The same interleaving with an identical payload: a retry, not a conflict.
     *
     * <p>This is the deterministic form of
     * {@link #identicalConcurrentPostsAreNeverAServerError}. Without
     * {@code ON CONFLICT DO NOTHING} the blocked insert raises a duplicate-key
     * violation the moment the winner commits, and this answers 500.
     */
    @Test
    void theLoserOfADeploymentInsertRaceRecognisesAnIdenticalPayloadAsARetry() throws Exception {
        String service = svc();
        String eventId = id();
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant authoredAt = deployedAt.minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        IngestDtos.DeploymentDto payload =
                deploymentDto(eventId, service, "production", deployedAt, authoredAt);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection held = openHeldConnection()) {
            insertDeploymentOn(held, payload);
            Future<ResponseEntity<String>> loser = pool.submit(
                    () -> post("/api/v1/deployments", deploymentJson(payload)));
            awaitBlockedOn("INSERT INTO deployment_event");
            held.commit();

            ResponseEntity<String> res = loser.get(30, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(parse(res.getBody()).get("disposition").asText()).isEqualTo("DUPLICATE");
        } finally {
            pool.shutdownNow();
        }

        assertThat(db.sql("SELECT count(*) FROM deployment_event WHERE id = ?")
                .param(eventId).query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM deployment_change WHERE deployment_id = ?")
                .param(eventId).query(Integer.class).single()).isEqualTo(1);
    }

    /**
     * Two <em>conflicting</em> resolutions, which is the case the identical
     * race cannot see.
     *
     * <p>The deleted barrier test on this path raced two
     * copies of the same resolution, so the loser's behaviour leaves no trace: a
     * correct no-op and an illegal overwrite produce the same row and the same
     * 200. Two mutations survive it for that reason -- deleting
     * {@code AND resolved_at IS NULL} from {@code resolveIncident}, and turning
     * the loser's re-read comparison into {@code if (true)}.
     *
     * <p>With different times the loser's behaviour becomes visible in three
     * independent places: the status must be 409 rather than 200, the detail
     * must be the concurrent-loss message rather than the settled-replay one,
     * and the stored time must still be the winner's.
     *
     * <p>The detail string is the load-bearing assertion. Reaching
     * "was resolved concurrently at" requires {@code resolveIncident} to have
     * matched zero rows, which requires {@code resolved_at} to have been NULL
     * when the request read it and non-NULL when it wrote -- something no
     * sequential replay can produce. It is a positive witness that two
     * transactions overlapped on this row, and it is production behaviour
     * already, not a test hook.
     */
    @Test
    void theLoserOfAResolveRaceRefusesToMoveAPublishedRestoreTime() throws Exception {
        String service = svc();
        String incidentId = id();
        Instant detected = Instant.now().minus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant winnersTime = detected.plus(30, ChronoUnit.MINUTES);
        Instant losersTime = detected.plus(45, ChronoUnit.MINUTES);

        assertThat(post("/api/v1/incidents", incidentJson(
                incidentDto(incidentId, service, "abc", detected, null))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection held = openHeldConnection()) {
            resolveOn(held, incidentDto(incidentId, service, "abc", detected, winnersTime));

            Future<ResponseEntity<String>> loser = pool.submit(() -> post("/api/v1/incidents",
                    incidentJson(incidentDto(incidentId, service, "abc", detected, losersTime))));

            // The request has read resolved_at as NULL and is now blocked on the
            // winner's row lock. Everything below is about what it does when the
            // lock is released and its UPDATE matches nothing.
            awaitBlockedOn("UPDATE incident_event");
            held.commit();

            ResponseEntity<String> res = loser.get(30, TimeUnit.SECONDS);
            assertThat(res.getStatusCode())
                    .as("a second, different restore time is a conflict, not an update")
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(parse(res.getBody()).get("detail").asText())
                    .as("the loser lost at the UPDATE; a settled-replay message here would mean "
                            + "the round never contended and proved nothing")
                    .contains("was resolved concurrently at");
        } finally {
            pool.shutdownNow();
        }

        assertThat(db.sql("SELECT resolved_at FROM incident_event WHERE id = ?")
                .param(incidentId).query(Instant.class).single())
                .as("the published restore time is the winner's, and cannot be moved by the loser")
                .isEqualTo(winnersTime);
    }

    /**
     * The same interleaving with an identical resolution: a retry, not a
     * conflict. The deterministic form of
     * the deleted barrier test on this path.
     *
     * <p>{@code disposition} is asserted, not just the status, because that is
     * what separates "recognised the retry" from "overwrote a published restore
     * time with the same value and called it an update" -- two answers that are
     * both 200 and both leave the same row.
     */
    @Test
    void theLoserOfAResolveRaceRecognisesAnIdenticalResolutionAsARetry() throws Exception {
        String service = svc();
        String incidentId = id();
        Instant detected = Instant.now().minus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant resolvedAt = detected.plus(30, ChronoUnit.MINUTES);

        assertThat(post("/api/v1/incidents", incidentJson(
                incidentDto(incidentId, service, "abc", detected, null))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection held = openHeldConnection()) {
            resolveOn(held, incidentDto(incidentId, service, "abc", detected, resolvedAt));

            Future<ResponseEntity<String>> loser = pool.submit(() -> post("/api/v1/incidents",
                    incidentJson(incidentDto(incidentId, service, "abc", detected, resolvedAt))));

            awaitBlockedOn("UPDATE incident_event");
            held.commit();

            ResponseEntity<String> res = loser.get(30, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(parse(res.getBody()).get("disposition").asText())
                    .as("the row was already resolved by the winner; this request wrote nothing")
                    .isEqualTo("DUPLICATE");
        } finally {
            pool.shutdownNow();
        }

        assertThat(db.sql("SELECT resolved_at FROM incident_event WHERE id = ?")
                .param(incidentId).query(Instant.class).single()).isEqualTo(resolvedAt);
    }

    /**
     * The incident insert race, constructed. The deterministic form of
     * {@link #identicalConcurrentIncidentCreationsAreNeverAServerError}.
     *
     * <p>A different code path from the resolve race above: this one contends
     * on {@code insertIncidentIfAbsent}'s ON CONFLICT DO NOTHING rather than on
     * the conditional UPDATE, and it is the path a duplicate delivery of an
     * <em>open</em> incident takes. Without ON CONFLICT DO NOTHING the blocked
     * insert raises a duplicate-key violation the instant the winner commits,
     * and this answers 500.
     */
    @Test
    void theLoserOfAnIncidentInsertRaceRecognisesAnIdenticalPayloadAsARetry() throws Exception {
        String service = svc();
        String incidentId = id();
        Instant detected = Instant.now().minus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        IngestDtos.IncidentDto payload = incidentDto(incidentId, service, "abc", detected, null);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection held = openHeldConnection()) {
            insertIncidentOn(held, payload);
            Future<ResponseEntity<String>> loser = pool.submit(
                    () -> post("/api/v1/incidents", incidentJson(payload)));
            awaitBlockedOn("INSERT INTO incident_event");
            held.commit();

            ResponseEntity<String> res = loser.get(30, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(parse(res.getBody()).get("disposition").asText()).isEqualTo("DUPLICATE");
        } finally {
            pool.shutdownNow();
        }

        assertThat(db.sql("SELECT count(*) FROM incident_event WHERE id = ?")
                .param(incidentId).query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT resolved_at FROM incident_event WHERE id = ?")
                .param(incidentId).query(Instant.class).optional())
                .as("a losing creation must not resolve anything").isEmpty();
    }

    /**
     * The same insert race with a conflicting payload: the loser must refuse,
     * and the winner's row must survive untouched.
     */
    @Test
    void theLoserOfAnIncidentInsertRaceRefusesAConflictingPayload() throws Exception {
        String service = svc();
        String incidentId = id();
        Instant detected = Instant.now().minus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        IngestDtos.IncidentDto winner = incidentDto(incidentId, service, "abc", detected, null);
        IngestDtos.IncidentDto loserPayload =
                incidentDto(incidentId, service, "deadbeef", detected, null);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection held = openHeldConnection()) {
            insertIncidentOn(held, winner);
            Future<ResponseEntity<String>> loser = pool.submit(
                    () -> post("/api/v1/incidents", incidentJson(loserPayload)));
            awaitBlockedOn("INSERT INTO incident_event");
            held.commit();

            ResponseEntity<String> res = loser.get(30, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }

        assertThat(db.sql("SELECT caused_by_commit_sha FROM incident_event WHERE id = ?")
                .param(incidentId).query(String.class).single()).isEqualTo("abc");
    }

    /**
     * A real two-request race, kept as a canary rather than as the proof.
     *
     * <p>The constructed tests above assert what happens at an interleaving the
     * test creates. This one asserts that the interleaving is still reachable
     * through the whole stack -- two sockets, two Tomcat threads, two
     * transactions -- because a model of a race that reality no longer produces
     * is a model, not a test.
     *
     * <p>Two things are asserted per round. The invariant holds whether or not
     * the round contended: exactly one 200 and one 409, and the stored time is
     * the one the 200 carried. Note that the test does not decide who wins and
     * does not need to -- the database decides, and the test reads the verdict
     * off the responses and then checks the row against it.
     *
     * <p>The loop then stops at the first round that reached the loser's
     * UPDATE, and fails if no round in {@code MAX_ROUNDS} ever did. That is the
     * difference from the barrier tests: a run in which no contention occurred
     * fails here instead of passing quietly.
     */
    @Test
    void anHttpRaceStillReachesTheLoserPath() throws Exception {
        final int maxRounds = 50;
        int rounds = 0;
        boolean reachedTheLoserPath = false;

        while (rounds < maxRounds && !reachedTheLoserPath) {
            rounds++;
            String service = svc();
            String incidentId = id();
            Instant detected = Instant.now().minus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
            List<Instant> times = List.of(detected.plus(30, ChronoUnit.MINUTES),
                                          detected.plus(45, ChronoUnit.MINUTES));
            postIncident(incidentId, service, "abc", detected, null);

            List<ResponseEntity<String>> both = race(
                    () -> postIncident(incidentId, service, "abc", detected, times.get(0)),
                    () -> postIncident(incidentId, service, "abc", detected, times.get(1)));

            assertThat(both.stream().map(r -> r.getStatusCode().value()).sorted().toList())
                    .as("round %d: one restore time is published, the other is refused", rounds)
                    .containsExactly(200, 409);

            int winner = both.get(0).getStatusCode() == HttpStatus.OK ? 0 : 1;
            assertThat(db.sql("SELECT resolved_at FROM incident_event WHERE id = ?")
                    .param(incidentId).query(Instant.class).single())
                    .as("round %d: the stored time is the one the 200 carried", rounds)
                    .isEqualTo(times.get(winner));

            String detail = parse(both.get(1 - winner).getBody()).get("detail").asText();
            if (detail.contains("was resolved concurrently at")) {
                reachedTheLoserPath = true;
            }
        }

        assertThat(reachedTheLoserPath)
                .as("%d rounds and not one reached the loser's UPDATE -- every round was a "
                        + "sequential replay, so nothing about concurrent behaviour was tested. "
                        + "Fix the harness or delete it; do not let it pass.", rounds)
                .isTrue();
    }

    // --- contention helpers -------------------------------------------------

    /**
     * A connection outside the application's pool, so holding a transaction
     * open on it cannot starve the request being tested.
     */
    private static Connection openHeldConnection() throws Exception {
        Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        c.setAutoCommit(false);
        return c;
    }

    /**
     * Blocks until some backend is waiting on a lock held by another while
     * running a statement containing {@code sqlFragment}.
     *
     * <p>This is a reliable witness, not a sample, and the difference is the
     * whole point. The caller holds the blocking transaction open, so the
     * waiting state persists until the caller commits and any poll inside that
     * window observes it. Polling for the <em>transient</em> block a barrier
     * race produces does not work: measured over 200 barrier rounds of which
     * 194 genuinely overlapped, a poller of this same query saw blocking in 59.
     * An absence of observation there means nothing; here it means the request
     * never reached the statement, which is a failure.
     */
    private void awaitBlockedOn(String sqlFragment) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            List<String> waiting = db.sql("""
                    SELECT left(regexp_replace(query, '\\s+', ' ', 'g'), 200)
                    FROM pg_stat_activity
                    WHERE cardinality(pg_blocking_pids(pid)) > 0""")
                    .query(String.class).list();
            if (waiting.stream().anyMatch(q -> q != null && q.contains(sqlFragment))) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("no backend ever blocked on a statement containing \""
                + sqlFragment + "\": the request under test never reached the contended "
                + "statement, so this round proved nothing");
    }

    private static IngestDtos.DeploymentDto deploymentDto(String id, String service, String environment,
                                                          Instant deployedAt, Instant authoredAt) {
        return new IngestDtos.DeploymentDto(id, service, environment, deployedAt, "SUCCESS",
                List.of(new IngestDtos.ChangeDto("aaa", authoredAt)));
    }

    /** The wire form of the same deployment, derived from it so the two cannot drift. */
    private static String deploymentJson(IngestDtos.DeploymentDto d) {
        return """
                {"id":"%s","service":"%s","environment":"%s","deployedAt":"%s","outcome":"%s",
                 "changes":[{"commitSha":"%s","authoredAt":"%s"}]}
                """.formatted(d.id(), d.service(), d.environment(), d.deployedAt(), d.outcome(),
                d.changes().get(0).commitSha(), d.changes().get(0).authoredAt());
    }

    private static IngestDtos.IncidentDto incidentDto(String id, String service, String causedBy,
                                                      Instant detectedAt, Instant resolvedAt) {
        return new IngestDtos.IncidentDto(id, service, causedBy, detectedAt, resolvedAt);
    }

    private static String incidentJson(IngestDtos.IncidentDto d) {
        return """
                {"id":"%s","service":"%s","causedByCommitSha":%s,"detectedAt":"%s","resolvedAt":%s}
                """.formatted(d.id(), d.service(),
                d.causedByCommitSha() == null ? "null" : "\"" + d.causedByCommitSha() + "\"",
                d.detectedAt(),
                d.resolvedAt() == null ? "null" : "\"" + d.resolvedAt() + "\"");
    }

    /**
     * The winner's write, on a connection the test holds open.
     *
     * <p>Deliberately raw SQL rather than {@link EventRepository}: the
     * repository's methods run in autocommit and would commit before the
     * request under test could reach the row, which is the entire thing being
     * arranged. The statements mirror {@code insertDeployment} and
     * {@code resolveIncident}, and the payload hash is computed with
     * {@link IngestService#canonical} itself, so the row left behind is the row
     * a real winner leaves rather than an approximation that could drift from
     * it.
     *
     * <p>The cost is that the winner here is a fixture rather than production
     * code. The path under test is the loser's, and that one is production code
     * end to end over a real socket.
     */
    private static void insertDeploymentOn(Connection c, IngestDtos.DeploymentDto d) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO deployment_event (id, service, environment, deployed_at, outcome, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, d.id());
            ps.setString(2, d.service());
            ps.setString(3, d.environment());
            ps.setTimestamp(4, Timestamp.from(d.deployedAt().truncatedTo(ChronoUnit.MICROS)));
            ps.setString(5, d.outcome());
            ps.setString(6, payloadHash(IngestService.canonical(d)));
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        int ordinal = 0;
        for (IngestDtos.ChangeDto ch : d.changes()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO deployment_change (deployment_id, ordinal, commit_sha, authored_at)
                    VALUES (?, ?, ?, ?)""")) {
                ps.setString(1, d.id());
                ps.setInt(2, ordinal++);
                ps.setString(3, ch.commitSha());
                ps.setTimestamp(4, Timestamp.from(ch.authoredAt().truncatedTo(ChronoUnit.MICROS)));
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        }
    }

    private static void insertIncidentOn(Connection c, IngestDtos.IncidentDto d) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO incident_event (id, service, caused_by_commit_sha, detected_at, resolved_at, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, d.id());
            ps.setString(2, d.service());
            ps.setString(3, d.causedByCommitSha());
            ps.setTimestamp(4, Timestamp.from(d.detectedAt().truncatedTo(ChronoUnit.MICROS)));
            ps.setTimestamp(5, d.resolvedAt() == null ? null
                    : Timestamp.from(d.resolvedAt().truncatedTo(ChronoUnit.MICROS)));
            ps.setString(6, payloadHash(IngestService.canonical(d)));
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private static void resolveOn(Connection c, IngestDtos.IncidentDto resolution) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE incident_event SET resolved_at = ?, payload_hash = ?
                WHERE id = ? AND resolved_at IS NULL""")) {
            ps.setTimestamp(1, Timestamp.from(resolution.resolvedAt().truncatedTo(ChronoUnit.MICROS)));
            ps.setString(2, payloadHash(IngestService.canonical(resolution)));
            ps.setString(3, resolution.id());
            assertThat(ps.executeUpdate())
                    .as("the held-open winner must have taken the row")
                    .isEqualTo(1);
        }
    }

    private static String payloadHash(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    // The three barrier tests that used to live here are gone, and the reason is
    // the point of the ones above.
    //
    // They released two threads from a CyclicBarrier and asserted on the result.
    // If the writes did not actually overlap, the round degenerated into a
    // sequential replay -- which produces the same observable outcome as a
    // correctly handled race, so the test could not tell the two apart, and
    // nothing recorded which had happened.
    //
    // Measured: two IDENTICAL resolutions produce [200, 200] in 200 of 200
    // rounds whether they contend or not, so that test was not weak, it was
    // blind. And the degenerate rounds are not a slow-machine hypothetical --
    // in six fresh JVMs, rounds 0 and 1 never contended and usually round 2 did
    // not either, because the path is still cold. The deleted test did six
    // rounds. One fresh JVM contended in exactly one of them.
    //
    // The proof they were subsumed rather than merely replaced: with the
    // resolve guard deleted, and again with the loser's re-read forced to
    // DUPLICATE, the tests above fail and all three of the deleted tests
    // PASSED.

    // --- addressing a service by name ---------------------------------------

    /**
     * A service whose name needs percent-encoding is readable.
     *
     * <p>Measured before the fix: the row was written, and the report came back
     * 200 with every metric UNOBSERVED and the service echoed as
     * {@code sp%20ace-...}. The lookup was for the encoded literal, which
     * matches nothing. That is not "no data" -- the data is in the table, and
     * the endpoint reported a clean absence of it.
     *
     * <p>All 39 other call sites here use {@code svc()}, which is
     * {@code "it-" + UUID} and never needs encoding, so the suite could not see
     * it. Three shapes are checked rather than one, because they fail
     * differently: a space is the ordinary case, {@code +} is the one a
     * form-decoder would silently turn into a space, and a non-ASCII character
     * is the one that only works if the decode is UTF-8 aware.
     */
    @Test
    void aServiceNameNeedingEncodingIsReadable() {
        for (String name : List.of("sp ace-" + UUID.randomUUID(),
                                   "pl+us-" + UUID.randomUUID(),
                                   "caf\u00e9-" + UUID.randomUUID())) {
            Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);
            assertThat(postDeployment(id(), name, "production", deployedAt, "SUCCESS", """
                        {"commitSha":"aaa","authoredAt":"%s"}
                    """.formatted(deployedAt.minus(2, ChronoUnit.HOURS)))
                    .getStatusCode()).as(name).isEqualTo(HttpStatus.CREATED);

            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            ResponseEntity<String> res = http.getForEntity(
                    "/api/v1/services/" + encoded + "/report?window=P30D", String.class);
            assertThat(res.getStatusCode()).as(name).isEqualTo(HttpStatus.OK);

            JsonNode report = parse(res.getBody());
            assertThat(report.get("service").asText())
                    .as("the report names the service that was asked for, decoded")
                    .isEqualTo(name);
            assertThat(metric(report, "deployment_frequency").get("observedN").asInt())
                    .as("%s: the deployment is in the table and must be in the report", name)
                    .isEqualTo(1);
        }
    }

    /**
     * A service name carrying a slash is refused at ingest rather than stored
     * and then unreadable.
     *
     * <p>A {@code /} cannot survive a path segment: it either splits the path
     * or, encoded, arrives as a literal matching no row. Storing the events and
     * answering UNOBSERVED forever is the worse of the two options, and the
     * producer is the only party who can still fix it.
     */
    @Test
    void aServiceNameWithASlashIsRefused() {
        Instant deployedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        ResponseEntity<String> res = postDeployment(
                id(), "team/payments", "production", deployedAt, "SUCCESS", "");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("path segment");

        assertThat(db.sql("SELECT count(*) FROM deployment_event WHERE service LIKE 'team/%'")
                .query(Integer.class).single())
                .as("refused means not stored")
                .isZero();
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

    /**
     * The upper bound on the window is enforced, not merely written.
     *
     * <p>Deleting the whole bounds check left the suite green: the parse test
     * below covers only unparseable input, and a negative window is caught
     * further down by DoraCalculator's own guard, so the 365-day cap was the
     * one clause nothing exercised. It is what stops a request loading a
     * service's entire history into heap on an unauthenticated endpoint.
     */
    @Test
    void aWindowBeyondTheCapIs400() {
        assertThat(http.getForEntity("/api/v1/services/" + svc() + "/report?window=P400D", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // And the boundary itself is accepted.
        assertThat(http.getForEntity("/api/v1/services/" + svc() + "/report?window=P365D", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The correction UPDATE must actually match a row.
     *
     * <p>Asked of the repository directly, because the service checks the same
     * thing first and so the guard is unreachable from every route through the
     * API -- the same shape as the resolve predicate. A guard nothing can reach
     * is a guard nothing has tested.
     */
    @Test
    void correctingAnAbsentDeploymentUpdatesNothingAndSaysSo() {
        assertThatThrownBy(() -> repo.updateDeploymentOutcome(
                "no-such-deployment", io.github.jjackson0118.doraloop.core.Outcome.ROLLED_BACK, "h"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrected 0");
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
        headers.set(IngestAuthFilter.HEADER, "apiintegrationtest-secret");
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
