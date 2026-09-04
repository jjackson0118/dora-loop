package dev.novaproject.doraloop.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Computes the four DORA metrics over a trailing window.
 *
 * <p>Every metric routes through {@link Metric}, so a metric with no supporting
 * observations cannot be reported as OK. That is the whole point: the common
 * failure of a delivery dashboard is not a wrong number, it is a green one
 * standing in for a measurement that never happened.
 */
public final class DoraCalculator {

    private final Clock clock;
    private final Duration window;

    public DoraCalculator(Clock clock, Duration window) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.window = Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive, got " + window);
        }
    }

    public DoraReport calculate(
            String service,
            Collection<DeploymentEvent> deployments,
            Collection<IncidentEvent> incidents) {

        Objects.requireNonNull(service, "service");
        Instant end = clock.instant();
        Instant start = end.minus(window);

        List<DeploymentEvent> prodDeploys = deployments.stream()
                .filter(d -> d.service().equals(service))
                .filter(DeploymentEvent::isProduction)
                .filter(d -> within(d.deployedAt(), start, end))
                .toList();

        List<IncidentEvent> serviceIncidents = incidents.stream()
                .filter(i -> i.service().equals(service))
                .filter(i -> within(i.detectedAt(), start, end))
                .toList();

        return new DoraReport(
                service,
                start,
                end,
                deploymentFrequency(prodDeploys),
                leadTimeForChanges(prodDeploys),
                changeFailureRate(prodDeploys),
                timeToRestore(serviceIncidents));
    }

    private Metric deploymentFrequency(List<DeploymentEvent> prodDeploys) {
        String name = "deployment_frequency";
        String unit = "deploys/day";
        String wrong = "< " + Thresholds.DEPLOY_FREQ_MIN_PER_DAY + " " + unit;

        if (prodDeploys.isEmpty()) {
            return Metric.unobserved(name, unit, wrong);
        }
        double days = window.toSeconds() / 86_400.0;
        double perDay = prodDeploys.size() / days;
        return Metric.observed(name, round(perDay), unit, prodDeploys.size(),
                perDay < Thresholds.DEPLOY_FREQ_MIN_PER_DAY, wrong);
    }

    private Metric leadTimeForChanges(List<DeploymentEvent> prodDeploys) {
        String name = "lead_time_for_changes";
        String unit = "hours";
        String wrong = "> " + Thresholds.LEAD_TIME_MAX_HOURS + " " + unit + " (median)";

        List<Double> hours = prodDeploys.stream()
                .filter(d -> d.outcome() == Outcome.SUCCESS)
                .map(d -> Duration.between(d.commitAuthoredAt(), d.deployedAt()).toSeconds() / 3600.0)
                .toList();

        if (hours.isEmpty()) {
            return Metric.unobserved(name, unit, wrong);
        }
        double median = median(hours);
        return Metric.observed(name, round(median), unit, hours.size(),
                median > Thresholds.LEAD_TIME_MAX_HOURS, wrong);
    }

    private Metric changeFailureRate(List<DeploymentEvent> prodDeploys) {
        String name = "change_failure_rate";
        String unit = "percent";
        String wrong = "> " + Thresholds.CHANGE_FAILURE_MAX_PERCENT + " " + unit;

        if (prodDeploys.isEmpty()) {
            return Metric.unobserved(name, unit, wrong);
        }
        long failures = prodDeploys.stream().filter(d -> d.outcome() == Outcome.FAILURE).count();
        double pct = (failures * 100.0) / prodDeploys.size();
        return Metric.observed(name, round(pct), unit, prodDeploys.size(),
                pct > Thresholds.CHANGE_FAILURE_MAX_PERCENT, wrong);
    }

    /**
     * Median restore time over resolved incidents only.
     *
     * <p>Open incidents are excluded rather than treated as zero. Counting an
     * unresolved incident as a zero-duration restore would make an ongoing
     * outage improve this number.
     */
    private Metric timeToRestore(List<IncidentEvent> incidents) {
        String name = "time_to_restore";
        String unit = "hours";
        String wrong = "> " + Thresholds.TIME_TO_RESTORE_MAX_HOURS + " " + unit + " (median)";

        List<Double> hours = incidents.stream()
                .filter(IncidentEvent::isResolved)
                .map(i -> Duration.between(i.detectedAt(), i.resolvedAt()).toSeconds() / 3600.0)
                .toList();

        if (hours.isEmpty()) {
            return Metric.unobserved(name, unit, wrong);
        }
        double median = median(hours);
        return Metric.observed(name, round(median), unit, hours.size(),
                median > Thresholds.TIME_TO_RESTORE_MAX_HOURS, wrong);
    }

    private static boolean within(Instant t, Instant start, Instant end) {
        return !t.isBefore(start) && !t.isAfter(end);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        int mid = n / 2;
        return (n % 2 == 1) ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
