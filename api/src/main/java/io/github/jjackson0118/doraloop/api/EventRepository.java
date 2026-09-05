package io.github.jjackson0118.doraloop.api;

import io.github.jjackson0118.doraloop.core.Change;
import io.github.jjackson0118.doraloop.core.DeploymentEvent;
import io.github.jjackson0118.doraloop.core.IncidentEvent;
import io.github.jjackson0118.doraloop.core.Outcome;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
class EventRepository {

    private final JdbcClient db;

    EventRepository(JdbcClient db) {
        this.db = db;
    }

    /** @return the stored payload hash if this id already exists */
    Optional<String> existingDeploymentHash(String id) {
        return db.sql("SELECT payload_hash FROM deployment_event WHERE id = ?")
                .param(id).query(String.class).optional();
    }

    Optional<String> existingIncidentHash(String id) {
        return db.sql("SELECT payload_hash FROM incident_event WHERE id = ?")
                .param(id).query(String.class).optional();
    }

    /**
     * @return false if this id was inserted by someone else first.
     *
     * <p>ON CONFLICT DO NOTHING rather than a bare INSERT, because the caller
     * checks for an existing row and then inserts, and nothing makes those two
     * statements atomic against a concurrent identical request. Measured before
     * this changed: two identical POSTs released from a barrier returned
     * 201 and an unhandled DuplicateKeyException as a 500, in 12 of 12 rounds.
     * A retrying pipeline cannot act on that -- 500 is indistinguishable from
     * "the write did not happen" -- and a deploy job with at-least-once
     * delivery produces exactly this.
     *
     * <p>Postgres blocks on the conflicting row until the other transaction
     * ends, so a false return means it really did commit, and the caller's
     * re-read will see it.
     */
    boolean insertDeployment(DeploymentEvent e, String payloadHash) {
        int rows = db.sql("""
                INSERT INTO deployment_event (id, service, environment, deployed_at, outcome, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING""")
                .params(e.id(), e.service(), e.environment(),
                        Timestamp.from(e.deployedAt()), e.outcome().name(), payloadHash)
                .update();
        if (rows == 0) {
            return false;
        }
        int i = 0;
        for (Change c : e.changes()) {
            db.sql("""
                    INSERT INTO deployment_change (deployment_id, ordinal, commit_sha, authored_at)
                    VALUES (?, ?, ?, ?)""")
                    .params(e.id(), i++, c.commitSha(), Timestamp.from(c.authoredAt()))
                    .update();
        }
        return true;
    }

    /** @return false if this id was inserted by someone else first. */
    boolean insertIncidentIfAbsent(IncidentEvent e, String payloadHash) {
        return db.sql("""
                INSERT INTO incident_event (id, service, caused_by_commit_sha, detected_at, resolved_at, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING""")
                .params(e.id(), e.service(), e.causedByCommitSha(),
                        Timestamp.from(e.detectedAt()),
                        e.resolvedAt() == null ? null : Timestamp.from(e.resolvedAt()),
                        payloadHash)
                .update() == 1;
    }

    /**
     * Loads one deployment with its changes, for deciding whether a replay is a
     * correction or a conflict.
     */
    Optional<DeploymentEvent> findDeployment(String id) {
        List<Change> changes = db.sql("""
                SELECT commit_sha, authored_at FROM deployment_change
                WHERE deployment_id = ? ORDER BY ordinal""")
                .param(id)
                .query((rs, n) -> new Change(rs.getString(1), rs.getTimestamp(2).toInstant()))
                .list();

        return db.sql("""
                SELECT id, service, environment, deployed_at, outcome
                FROM deployment_event WHERE id = ?""")
                .param(id)
                .query((rs, n) -> new DeploymentEvent(
                        rs.getString("id"), rs.getString("service"), rs.getString("environment"),
                        changes, rs.getTimestamp("deployed_at").toInstant(),
                        Outcome.valueOf(rs.getString("outcome"))))
                .optional();
    }

    /**
     * The only field of a recorded deployment that may change.
     *
     * <p>A rollback is reported after the deployment it undoes, so the outcome
     * has to be correctable or the correction is lost. Everything else about a
     * deployment is a statement about a past event and cannot legitimately
     * change; the service decides which transitions are legal, and this only
     * carries them out.
     */
    void updateDeploymentOutcome(String id, Outcome outcome, String payloadHash) {
        int rows = db.sql("UPDATE deployment_event SET outcome = ?, payload_hash = ? WHERE id = ?")
                .params(outcome.name(), payloadHash, id)
                .update();
        if (rows != 1) {
            throw new IllegalStateException("expected to correct 1 deployment, corrected " + rows);
        }
    }

    Optional<IncidentEvent> findIncident(String id) {
        return db.sql("""
                SELECT id, service, caused_by_commit_sha, detected_at, resolved_at
                FROM incident_event WHERE id = ?""")
                .param(id)
                .query((rs, n) -> {
                    Timestamp r = rs.getTimestamp("resolved_at");
                    return new IncidentEvent(
                            rs.getString("id"), rs.getString("service"),
                            rs.getString("caused_by_commit_sha"),
                            rs.getTimestamp("detected_at").toInstant(),
                            r == null ? null : r.toInstant());
                })
                .optional();
    }

    /**
     * Resolves an open incident, and only an open one.
     *
     * <p>The {@code resolved_at IS NULL} predicate is the guard, not a comment:
     * this used to be an unconditional upsert of every column, so an ordinary
     * retry of the original open-incident payload set the column back to NULL
     * and deleted a time-to-restore observation with a 201 in reply. The
     * service checks the same thing, and the database enforces it -- if a
     * concurrent request resolved it first, this updates nothing and says so
     * rather than overwriting a resolution time that is already published.
     */
    boolean resolveIncident(String id, java.time.Instant resolvedAt, String payloadHash) {
        return db.sql("""
                UPDATE incident_event SET resolved_at = ?, payload_hash = ?
                WHERE id = ? AND resolved_at IS NULL""")
                .params(Timestamp.from(resolvedAt), payloadHash, id)
                .update() == 1;
    }

    /**
     * Loads every deployment for a service.
     *
     * <p>Not windowed here on purpose: DoraCalculator owns the window, and an
     * incident caused by a deployment inside the window is frequently detected
     * after it. Filtering in SQL would quietly change the metric.
     *
     * <p>The limit worth naming: this holds a service's whole history in
     * memory. True for a showpiece, false at scale, and honest to say so rather
     * than build a premature aggregation layer.
     */
    List<DeploymentEvent> deploymentsFor(String service) {
        Map<String, List<Change>> changes = new LinkedHashMap<>();
        db.sql("""
                SELECT c.deployment_id, c.commit_sha, c.authored_at
                FROM deployment_change c JOIN deployment_event d ON d.id = c.deployment_id
                WHERE d.service = ? ORDER BY c.deployment_id, c.ordinal""")
                .param(service)
                .query((rs, n) -> {
                    changes.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(new Change(rs.getString(2), rs.getTimestamp(3).toInstant()));
                    return null;
                }).list();

        return db.sql("""
                SELECT id, service, environment, deployed_at, outcome
                FROM deployment_event WHERE service = ?""")
                .param(service)
                .query((rs, n) -> new DeploymentEvent(
                        rs.getString("id"), rs.getString("service"), rs.getString("environment"),
                        changes.getOrDefault(rs.getString("id"), List.of()),
                        rs.getTimestamp("deployed_at").toInstant(),
                        Outcome.valueOf(rs.getString("outcome"))))
                .list();
    }

    List<IncidentEvent> incidentsFor(String service) {
        return db.sql("""
                SELECT id, service, caused_by_commit_sha, detected_at, resolved_at
                FROM incident_event WHERE service = ?""")
                .param(service)
                .query((rs, n) -> {
                    Timestamp r = rs.getTimestamp("resolved_at");
                    return new IncidentEvent(
                            rs.getString("id"), rs.getString("service"),
                            rs.getString("caused_by_commit_sha"),
                            rs.getTimestamp("detected_at").toInstant(),
                            r == null ? null : r.toInstant());
                }).list();
    }

    static Instant nowFloor() {
        return Instant.now();
    }
}
