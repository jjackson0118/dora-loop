package io.github.jjackson0118.doraloop.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One deployment of a set of changes to one environment.
 *
 * <p>A deployment carries every commit in the range since the last deployment,
 * not just the head commit. Lead time is defined per <em>change</em>, so
 * measuring from the head commit alone collapses a two-week branch into the
 * few minutes since its final "fix typo" commit -- an error that biases lead
 * time downward, which is to say in the flattering direction.
 *
 * <p>{@code changes} may legitimately be empty: redeploying an already-deployed
 * commit is a deployment that carries no new change. It counts toward
 * deployment frequency and contributes no lead time observations.
 *
 * <p>No validation here rejects an event for implausible timestamps. Author
 * dates are client-supplied, and dropping the event would lose a real
 * deployment in order to protect a derived metric -- silently reducing
 * deployment frequency. Implausible values are quarantined during calculation
 * and surfaced as their own signal instead.
 */
public record DeploymentEvent(
        String id,
        String service,
        String environment,
        List<Change> changes,
        Instant deployedAt,
        Outcome outcome
) {
    public DeploymentEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(deployedAt, "deployedAt");
        Objects.requireNonNull(outcome, "outcome");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }

    public boolean isProduction() {
        return "production".equalsIgnoreCase(environment);
    }

    public boolean carries(String commitSha) {
        return changes.stream().anyMatch(c -> c.commitSha().equals(commitSha));
    }
}
