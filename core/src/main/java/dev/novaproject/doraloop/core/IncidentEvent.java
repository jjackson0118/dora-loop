package dev.novaproject.doraloop.core;

import java.time.Instant;
import java.util.Objects;

/**
 * A production incident. {@code resolvedAt} is null while the incident is open.
 *
 * <p>Open incidents are deliberately excluded from time-to-restore. An unresolved
 * incident has no restore duration; counting it as zero would improve the metric.
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
            throw new IllegalArgumentException(
                    "resolvedAt precedes detectedAt for " + id);
        }
    }

    public boolean isResolved() {
        return resolvedAt != null;
    }
}
