package dev.novaproject.doraloop.core;

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
        if (resolvedAt != null && resolvedAt.isBefore(detectedAt)) {
            throw new IllegalArgumentException("resolvedAt precedes detectedAt for " + id);
        }
    }

    public boolean isResolved() {
        return resolvedAt != null;
    }

    public boolean hasIdentifiedCause() {
        return causedByCommitSha != null;
    }
}
