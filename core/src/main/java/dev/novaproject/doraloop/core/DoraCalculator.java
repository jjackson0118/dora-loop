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
 * observations cannot be reported as OK. That is the point: the common failure
 * of a delivery dashboard is not a wrong number, it is a green one standing in
 * for a measurement that never happened.
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
        Objects.requireNonNull(deployments, "deployments");
        Objects.requireNonNull(incidents, "incidents");

        Instant end = clock.instant();
        Instant start = end.minus(window);

        List<DeploymentEvent> prodDeploys = deployments.stream()
                .filter(d -> d.service().equals(service))
                .filter(DeploymentEvent::isProduction)
                .filter(d -> within(d.deployedAt(), start, end))
                .toList();

        // Incidents are NOT window-filtered when joined to deployments. An
        // incident caused by a deploy inside the window may be detected after
        // it; excluding those would make change failure rate improve simply
        // because an outage was recent.
        List<IncidentEvent> serviceIncidents = incidents.stream()
                .filter(i -> i.service().equals(service))
                .toList();

        List<IncidentEvent> windowIncidents = serviceIncidents.stream()
                .filter(i -> within(i.detectedAt(), start, end))
                .toList();

        return new DoraReport(
                service,
                start,
                end,
                deploymentFrequency(prodDeploys),
                leadTimeForChanges(prodDeploys),
                changeFailureRate(prodDeploys, serviceIncidents),
                timeToRestore(windowIncidents),
                suspectChanges(prodDeploys));
    }

    private Metric deploymentFrequency(List<DeploymentEvent> prodDeploys) {
        String name = "deployment_frequency";
        String unit = "deploys/day";
        String wrong = "< " + Thresholds.DEPLOY_FREQ_MIN_PER_DAY + " " + unit;

        if (prodDeploys.isEmpty()) {
            return Metric.unobserved(name, unit, wrong);
        }
        double days = window.toSeconds() / 86_400.0;
        double perDay = round(prodDeploys.size() / days);
        return Metric.observed(name, perDay, unit, prodDeploys.size(),
                perDay < Thresholds.DEPLOY_FREQ_MIN_PER_DAY, wrong);
    }

    /**
     * Median lead time across every change carried to production.
     *
     * <p>One observation per <em>change</em>, not per deployment. DORA defines
     * this per change, and measuring from a deployment's head commit collapses
     * a two-week branch into the minutes since its final commit -- an error
     * biased toward looking good.
     *
     * <p>A redeploy of an already-deployed commit carries no changes and
     * therefore contributes no observations, rather than contributing an
     * enormous one.
     */
    private Metric leadTimeForChanges(List<DeploymentEvent> prodDeploys) {
        String name = "lead_time_for_changes";
        String unit = "hours";
        String wrong = "> " + Thresholds.LEAD_TIME_MAX_HOURS + " " + unit + " (median)";

        List<Double> hours = new ArrayList<>();
        for (DeploymentEvent d : prodDeploys) {
            if (d.outcome() == Outcome.FAILED_ROLLOUT) {
                continue; // never reached users; no lead time was realised
            }
            for (Change c : d.changes()) {
                if (c.authoredAt().isAfter(d.deployedAt())) {
                    continue; // quarantined; counted by suspectChanges
                }
                hours.add(Duration.between(c.authoredAt(), d.deployedAt()).toSeconds() / 3600.0);
            }
        }

        if (hours.isEmpty()) {
            return Metric.unobserved(name, unit, wrong);
        }
        double median = round(median(hours));
        return Metric.observed(name, median, unit, hours.size(),
                median > Thresholds.LEAD_TIME_MAX_HOURS, wrong);
    }

    /**
     * Share of changes reaching production that degraded it.
     *
     * <p>Not the share of deployments whose rollout failed. Those are different
     * measurements that move in opposite directions: a rollout caught and
     * failed by the pipeline is the pipeline working, while a clean rollout
     * that pages someone two hours later is the failure this metric exists to
     * count.
     *
     * <p>Denominator is deployments that reached production, so a failed
     * rollout is excluded from both halves. Numerator is deployments that were
     * rolled back, plus deployments carrying a commit an incident blames.
     */
    private Metric changeFailureRate(List<DeploymentEvent> prodDeploys, List<IncidentEvent> incidents) {
        String name = "change_failure_rate";
        String unit = "percent";
        String wrong = "> " + Thresholds.CHANGE_FAILURE_MAX_PERCENT + " " + unit;

        List<DeploymentEvent> reached = prodDeploys.stream()
                .filter(d -> d.outcome().reachedProduction())
                .toList();

        if (reached.isEmpty()) {
            return Metric.unobserved(name, unit, wrong);
        }

        List<String> blamed = incidents.stream()
                .filter(IncidentEvent::hasIdentifiedCause)
                .map(IncidentEvent::causedByCommitSha)
                .toList();

        long failures = reached.stream()
                .filter(d -> d.outcome() == Outcome.ROLLED_BACK
                        || blamed.stream().anyMatch(d::carries))
                .count();

        double pct = round((failures * 100.0) / reached.size());
        return Metric.observed(name, pct, unit, reached.size(),
                pct > Thresholds.CHANGE_FAILURE_MAX_PERCENT, wrong);
    }

    /**
     * Median restore time over resolved incidents only.
     *
     * <p>Open incidents are excluded rather than treated as zero, because a
     * zero-duration restore would let an ongoing outage improve the number.
     *
     * <p>This is right-censoring and it biases the median downward: the
     * incidents still open at the window edge are disproportionately the long
     * ones. {@link Metric#observedN()} carries the resolved count so the
     * censoring is visible next to the number rather than hidden behind it.
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
        double median = round(median(hours));
        return Metric.observed(name, median, unit, hours.size(),
                median > Thresholds.TIME_TO_RESTORE_MAX_HOURS, wrong);
    }

    /**
     * Changes whose author date falls after the deployment that carried them.
     *
     * <p>Git author dates are client-supplied -- a skewed laptop clock or an
     * explicit {@code GIT_AUTHOR_DATE} produces one. Such a change cannot yield
     * a meaningful lead time, so it is excluded from that median.
     *
     * <p>Excluding it silently would be the failure this project is about: the
     * quality of the input would degrade with nothing to show for it. So the
     * exclusion is itself a signal, with its own definition of wrong.
     */
    private Metric suspectChanges(List<DeploymentEvent> prodDeploys) {
        String name = "data_quality.suspect_changes";
        String unit = "changes";
        String wrong = "> " + Thresholds.SUSPECT_CHANGES_MAX + " " + unit;

        int total = prodDeploys.stream().mapToInt(d -> d.changes().size()).sum();
        if (total == 0) {
            return Metric.unobserved(name, unit, wrong);
        }
        long suspect = prodDeploys.stream()
                .flatMap(d -> d.changes().stream()
                        .filter(c -> c.authoredAt().isAfter(d.deployedAt())))
                .count();
        return Metric.observed(name, (double) suspect, unit, total,
                suspect > Thresholds.SUSPECT_CHANGES_MAX, wrong);
    }

    /** Half-open: {@code [start, end)}. Closed would double-count at window boundaries. */
    private static boolean within(Instant t, Instant start, Instant end) {
        return !t.isBefore(start) && t.isBefore(end);
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("median of an empty list is undefined");
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        int mid = n / 2;
        return (n % 2 == 1) ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    /** Rounded before threshold comparison, so the rendered value and the verdict agree. */
    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
