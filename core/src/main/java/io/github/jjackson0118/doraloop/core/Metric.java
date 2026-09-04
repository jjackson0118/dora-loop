package io.github.jjackson0118.doraloop.core;

import java.util.Objects;

/**
 * One measured signal.
 *
 * <p>Two invariants are enforced in the constructor rather than left to callers,
 * because both failures are silent and both read as health:
 *
 * <ul>
 *   <li>Zero observations must render UNOBSERVED with a null value. A metric
 *       cannot be OK on the strength of no data.</li>
 *   <li>Every metric must carry {@code definitionOfWrong}. A signal that cannot
 *       say what wrong looks like cannot alert, and a signal that cannot alert
 *       is decoration.</li>
 * </ul>
 *
 * @param definitionOfWrong the threshold this metric is judged against, stated
 *                          in the metric's own units
 */
public record Metric(
        String name,
        Double value,
        String unit,
        int observedN,
        SignalState state,
        String definitionOfWrong
) {
    public Metric {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(state, "state");
        if (definitionOfWrong == null || definitionOfWrong.isBlank()) {
            throw new IllegalArgumentException(
                    "metric '" + name + "' carries no definition of wrong and therefore cannot alert");
        }
        if (observedN < 0) {
            throw new IllegalArgumentException("observedN cannot be negative for '" + name + "'");
        }
        if (observedN == 0 && state != SignalState.UNOBSERVED) {
            throw new IllegalArgumentException(
                    "metric '" + name + "' has zero observations but claims state " + state
                            + " -- zero observations render UNOBSERVED, never OK");
        }
        if (state == SignalState.UNOBSERVED && value != null) {
            throw new IllegalArgumentException(
                    "metric '" + name + "' is UNOBSERVED but carries a value");
        }
        if (state != SignalState.UNOBSERVED && value == null) {
            throw new IllegalArgumentException(
                    "metric '" + name + "' claims state " + state + " but carries no value");
        }
    }

    public static Metric unobserved(String name, String unit, String definitionOfWrong) {
        return new Metric(name, null, unit, 0, SignalState.UNOBSERVED, definitionOfWrong);
    }

    public static Metric observed(
            String name, double value, String unit, int observedN,
            boolean degraded, String definitionOfWrong) {
        if (observedN <= 0) {
            throw new IllegalArgumentException(
                    "observed metric '" + name + "' requires at least one observation");
        }
        return new Metric(name, value, unit, observedN,
                degraded ? SignalState.DEGRADED : SignalState.OK, definitionOfWrong);
    }

    public boolean alerting() {
        return state == SignalState.DEGRADED;
    }
}
