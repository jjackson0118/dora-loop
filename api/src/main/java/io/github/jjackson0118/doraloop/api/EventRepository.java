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

    void insertDeployment(DeploymentEvent e, String payloadHash) {
        db.sql("""
                INSERT INTO deployment_event (id, service, environment, deployed_at, outcome, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?)""")
                .params(e.id(), e.service(), e.environment(),
                        Timestamp.from(e.deployedAt()), e.outcome().name(), payloadHash)
                .update();
        int i = 0;
        for (Change c : e.changes()) {
            db.sql("""
                    INSERT INTO deployment_change (deployment_id, ordinal, commit_sha, authored_at)
                    VALUES (?, ?, ?, ?)""")
                    .params(e.id(), i++, c.commitSha(), Timestamp.from(c.authoredAt()))
                    .update();
        }
    }

    /**
     * Incidents upsert on id; deployments do not.
     *
     * <p>{@code resolvedAt} arrives later, so resolving an incident is a
     * re-POST of the same id with the field set. That makes retry semantics and
     * resolution semantics the same code path, and avoids a PATCH endpoint
     * whose only job is to set one field.
     */
    void upsertIncident(IncidentEvent e, String payloadHash) {
        db.sql("""
                INSERT INTO incident_event (id, service, caused_by_commit_sha, detected_at, resolved_at, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    service = EXCLUDED.service,
                    caused_by_commit_sha = EXCLUDED.caused_by_commit_sha,
                    detected_at = EXCLUDED.detected_at,
                    resolved_at = EXCLUDED.resolved_at,
                    payload_hash = EXCLUDED.payload_hash""")
                .params(e.id(), e.service(), e.causedByCommitSha(),
                        Timestamp.from(e.detectedAt()),
                        e.resolvedAt() == null ? null : Timestamp.from(e.resolvedAt()),
                        payloadHash)
                .update();
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
