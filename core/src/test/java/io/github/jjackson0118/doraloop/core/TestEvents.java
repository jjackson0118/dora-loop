package io.github.jjackson0118.doraloop.core;

import java.time.Instant;
import java.util.List;

/** Builders for readable test fixtures. */
final class TestEvents {

    static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    /** A production deploy carrying one change authored {@code leadHours} earlier. */
    static DeploymentEvent prodDeploy(String id, Instant deployedAt, double leadHours, Outcome outcome) {
        return new DeploymentEvent(id, "dora-loop", "production",
                List.of(new Change("sha-" + id, deployedAt.minusSeconds((long) (leadHours * 3600)))),
                deployedAt, outcome);
    }

    /** A production deploy carrying several changes, each with its own lead time. */
    static DeploymentEvent prodDeployWith(String id, Instant deployedAt, Outcome outcome, double... leadHours) {
        List<Change> changes = new java.util.ArrayList<>();
        for (int i = 0; i < leadHours.length; i++) {
            changes.add(new Change("sha-" + id + "-" + i,
                    deployedAt.minusSeconds((long) (leadHours[i] * 3600))));
        }
        return new DeploymentEvent(id, "dora-loop", "production", List.copyOf(changes), deployedAt, outcome);
    }

    /** A redeploy of already-shipped code: a real deployment carrying no new change. */
    static DeploymentEvent redeploy(String id, Instant deployedAt) {
        return new DeploymentEvent(id, "dora-loop", "production", List.of(), deployedAt, Outcome.SUCCESS);
    }

    /** A deploy whose change claims to have been authored after it shipped. */
    static DeploymentEvent skewedDeploy(String id, Instant deployedAt, double futureHours) {
        return new DeploymentEvent(id, "dora-loop", "production",
                List.of(new Change("sha-" + id, deployedAt.plusSeconds((long) (futureHours * 3600)))),
                deployedAt, Outcome.SUCCESS);
    }

    static DeploymentEvent stagingDeploy(String id, Instant deployedAt) {
        return new DeploymentEvent(id, "dora-loop", "staging",
                List.of(new Change("sha-" + id, deployedAt.minusSeconds(3600))),
                deployedAt, Outcome.SUCCESS);
    }

    static IncidentEvent resolved(String id, Instant detectedAt, double restoreHours) {
        return new IncidentEvent(id, "dora-loop", null, detectedAt,
                detectedAt.plusSeconds((long) (restoreHours * 3600)));
    }

    static IncidentEvent open(String id, Instant detectedAt) {
        return new IncidentEvent(id, "dora-loop", null, detectedAt, null);
    }

    /** A resolved incident blaming a specific commit. */
    static IncidentEvent blaming(String id, String commitSha, Instant detectedAt) {
        return new IncidentEvent(id, "dora-loop", commitSha, detectedAt, detectedAt.plusSeconds(3600));
    }

    private TestEvents() {
    }
}
