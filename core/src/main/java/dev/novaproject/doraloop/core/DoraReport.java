package dev.novaproject.doraloop.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** The four metrics for one service over one window. */
public record DoraReport(
        String service,
        Instant windowStart,
        Instant windowEnd,
        Metric deploymentFrequency,
        Metric leadTimeForChanges,
        Metric changeFailureRate,
        Metric timeToRestore
) {
    public DoraReport {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        Objects.requireNonNull(deploymentFrequency, "deploymentFrequency");
        Objects.requireNonNull(leadTimeForChanges, "leadTimeForChanges");
        Objects.requireNonNull(changeFailureRate, "changeFailureRate");
        Objects.requireNonNull(timeToRestore, "timeToRestore");
    }

    public List<Metric> metrics() {
        return List.of(deploymentFrequency, leadTimeForChanges, changeFailureRate, timeToRestore);
    }

    public Duration window() {
        return Duration.between(windowStart, windowEnd);
    }

    /** Metrics currently in a DEGRADED state. Never includes UNOBSERVED. */
    public List<Metric> alerting() {
        return metrics().stream().filter(Metric::alerting).toList();
    }

    /**
     * Metrics with no observations. Surfaced separately and deliberately: an
     * unobserved metric is a coverage gap, not a healthy result, and it should
     * never be counted alongside the ones that are green.
     */
    public List<Metric> unobserved() {
        return metrics().stream()
                .filter(m -> m.state() == SignalState.UNOBSERVED)
                .toList();
    }
}
