package dev.novaproject.doraloop.core;

import java.time.Instant;
import java.util.Objects;

/**
 * One deployment of one commit to one environment.
 *
 * @param commitAuthoredAt when the change was authored -- the start of lead time.
 *                         Deploy time alone cannot produce lead time for changes.
 */
public record DeploymentEvent(
        String id,
        String service,
        String commitSha,
        String environment,
        Instant commitAuthoredAt,
        Instant deployedAt,
        Outcome outcome
) {
    public DeploymentEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(commitSha, "commitSha");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(commitAuthoredAt, "commitAuthoredAt");
        Objects.requireNonNull(deployedAt, "deployedAt");
        Objects.requireNonNull(outcome, "outcome");
        if (deployedAt.isBefore(commitAuthoredAt)) {
            throw new IllegalArgumentException(
                    "deployedAt precedes commitAuthoredAt for " + id + " -- lead time would be negative");
        }
    }

    public boolean isProduction() {
        return "production".equalsIgnoreCase(environment);
    }
}
