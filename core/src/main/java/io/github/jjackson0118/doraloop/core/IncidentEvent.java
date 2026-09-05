package io.github.jjackson0118.doraloop.core;

import java.time.Instant;
import java.util.Objects;

/**
 * A production incident. {@code resolvedAt} is null while the incident is open.
 *
 * <p>{@code causedByCommitSha} is the join back to the deployment that caused
 * it. It is what makes change failure rate mean "changes that degraded
 * production" rather than "deployments whose rollout failed" -- two different
 * measurements that are easy to confuse and that move in opposite directions.
 *
 * <p>It is nullable: an incident with no identified cause is still an incident.
 * It counts toward time to restore and contributes to no deployment's failure.
 *
 * <p>Nothing here rejects an incident for implausible timestamps. This
 * previously threw when {@code resolvedAt} preceded {@code detectedAt}, which
 * was the same guard ADR 0003 removed from {@link DeploymentEvent} and left
 * standing here. The consequence is identical once ingest is an HTTP endpoint:
 * the guard returns 4xx, the incident is lost, and time to restore
 * <em>improves</em> because the inconvenient record disappeared. Validation
 * written to protect a metric became the thing most likely to corrupt it.
 *
 * <p>Implausible ordering is quarantined during calculation and surfaced by
 * {@code data_quality.suspect_incidents} instead.
 */
public record IncidentEvent(
        String id,
        String service,
        String causedByCommitSha,
        Instant detectedAt,
        Instant resolvedAt
) {
    public IncidentEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(detectedAt, "detectedAt");
    }

    public boolean isResolved() {
        return resolvedAt != null;
    }

    public boolean hasIdentifiedCause() {
        return causedByCommitSha != null;
    }

    /**
     * Whether this incident's timestamps are ordered plausibly.
     *
     * <p>An unresolved incident is not implausible -- it is simply open.
     */
    public boolean isPlausible() {
        return resolvedAt == null || !resolvedAt.isBefore(detectedAt);
    }
}
