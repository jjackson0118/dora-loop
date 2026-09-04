package dev.novaproject.doraloop.core;

import java.time.Instant;

/** Builders for readable test fixtures. */
final class TestEvents {

    static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    static DeploymentEvent prodDeploy(String id, Instant deployedAt, double leadHours, Outcome outcome) {
        return new DeploymentEvent(
                id,
                "dora-loop",
                "sha-" + id,
                "production",
                deployedAt.minusSeconds((long) (leadHours * 3600)),
                deployedAt,
                outcome);
    }

    static DeploymentEvent stagingDeploy(String id, Instant deployedAt) {
        return new DeploymentEvent(
                id, "dora-loop", "sha-" + id, "staging",
                deployedAt.minusSeconds(3600), deployedAt, Outcome.SUCCESS);
    }

    static IncidentEvent resolved(String id, Instant detectedAt, double restoreHours) {
        return new IncidentEvent(
                id, "dora-loop", "sha-x", detectedAt,
                detectedAt.plusSeconds((long) (restoreHours * 3600)));
    }

    static IncidentEvent open(String id, Instant detectedAt) {
        return new IncidentEvent(id, "dora-loop", "sha-x", detectedAt, null);
    }

    private TestEvents() {
    }
}
