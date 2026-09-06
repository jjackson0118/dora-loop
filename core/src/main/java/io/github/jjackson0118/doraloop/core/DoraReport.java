package io.github.jjackson0118.doraloop.core;

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
        Metric timeToRestore,
        Metric suspectChanges,
        Metric suspectIncidents,
        Metric unverifiedDeployments
) {
    public DoraReport {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        Objects.requireNonNull(deploymentFrequency, "deploymentFrequency");
        Objects.requireNonNull(leadTimeForChanges, "leadTimeForChanges");
        Objects.requireNonNull(changeFailureRate, "changeFailureRate");
        Objects.requireNonNull(timeToRestore, "timeToRestore");
        Objects.requireNonNull(suspectChanges, "suspectChanges");
        Objects.requireNonNull(suspectIncidents, "suspectIncidents");
        Objects.requireNonNull(unverifiedDeployments, "unverifiedDeployments");
    }

    /** The four DORA metrics. */
    public List<Metric> metrics() {
        return List.of(deploymentFrequency, leadTimeForChanges, changeFailureRate, timeToRestore);
    }

    /**
     * The four metrics plus the data-quality signals.
     *
     * <p>{@code suspectChanges} is not a DORA metric, but it governs whether the
     * DORA metrics can be believed. Ingest quality that is not itself a signal
     * is a blind spot in exactly the place a blind spot is most expensive.
     */
    public List<Metric> allSignals() {
        List<Metric> all = new java.util.ArrayList<>(metrics());
        all.add(suspectChanges);
        all.add(suspectIncidents);
        all.add(unverifiedDeployments);
        return List.copyOf(all);
    }

    public Duration window() {
        return Duration.between(windowStart, windowEnd);
    }

    /** Metrics currently in a DEGRADED state. Never includes UNOBSERVED. */
    public List<Metric> alerting() {
        return allSignals().stream().filter(Metric::alerting).toList();
    }

    /**
     * Metrics with no observations. Surfaced separately and deliberately: an
     * unobserved metric is a coverage gap, not a healthy result, and it should
     * never be counted alongside the ones that are green.
     */
    public List<Metric> unobserved() {
        return allSignals().stream()
                .filter(m -> m.state() == SignalState.UNOBSERVED)
                .toList();
    }
}
