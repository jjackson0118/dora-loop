package io.github.jjackson0118.doraloop.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Negative controls for the domain guards outside {@link Metric}.
 *
 * <p>These existed and were untested. The README claimed every guard had a
 * negative control; that was true of the signal contract and not of the rest,
 * which is the same overclaim this project exists to argue about, made about
 * its own test suite.
 */
class DomainGuardTest {

    @Test
    @DisplayName("NEGATIVE CONTROL: an incident resolved before it was detected is refused")
    void incidentOrderingRefused() {
        assertThatThrownBy(() -> new IncidentEvent(
                "i1", "svc", null,
                TestEvents.NOW,
                TestEvents.NOW.minusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolvedAt precedes detectedAt");
    }

    @Test
    @DisplayName("NEGATIVE CONTROL: a zero-length window is refused")
    void zeroWindowRefused() {
        assertThatThrownBy(() -> new DoraCalculator(
                Clock.fixed(TestEvents.NOW, ZoneOffset.UTC), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window must be positive");
    }

    @Test
    @DisplayName("NEGATIVE CONTROL: a negative window is refused")
    void negativeWindowRefused() {
        assertThatThrownBy(() -> new DoraCalculator(
                Clock.fixed(TestEvents.NOW, ZoneOffset.UTC), Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window must be positive");
    }

    @Test
    @DisplayName("NEGATIVE CONTROL: an observed metric with no observations is refused")
    void observedWithoutObservationsRefused() {
        assertThatThrownBy(() -> Metric.observed("x", 1.0, "hours", 0, false, "> 24 hours"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires at least one observation");
    }

    @Test
    @DisplayName("a deployment may legitimately carry no changes, and is immutable")
    void emptyChangesAllowedAndDefensive() {
        List<Change> mutable = new java.util.ArrayList<>();
        DeploymentEvent d = new DeploymentEvent(
                "d1", "svc", "production", mutable, TestEvents.NOW, Outcome.SUCCESS);

        mutable.add(new Change("sneaky", TestEvents.NOW));

        // List.copyOf in the compact constructor means the event did not change
        // underneath us. A redeploy carrying no changes stays a redeploy.
        assertThatThrownBy(() -> d.changes().add(new Change("x", TestEvents.NOW)))
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThat(d.changes()).isEmpty();
    }
}
