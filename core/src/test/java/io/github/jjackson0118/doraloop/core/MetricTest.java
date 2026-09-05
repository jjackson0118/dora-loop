package io.github.jjackson0118.doraloop.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The signal contract, tested from both directions.
 *
 * <p>Each guard has a negative control: a test proving the guard rejects the
 * bad case, not merely that the good case passes. A constraint never observed
 * refusing anything is not known to work.
 */
class MetricTest {

    @Test
    @DisplayName("zero observations render UNOBSERVED with no value")
    void unobservedHasNoValue() {
        Metric m = Metric.unobserved("x", "hours", "> 24 hours");

        assertThat(m.state()).isEqualTo(SignalState.UNOBSERVED);
        assertThat(m.value()).isNull();
        assertThat(m.observedN()).isZero();
        assertThat(m.alerting()).isFalse();
    }

    @Test
    @DisplayName("NEGATIVE CONTROL: zero observations cannot claim OK")
    void zeroObservationsCannotBeOk() {
        assertThatThrownBy(() ->
                new Metric("x", 0.0, "hours", 0, SignalState.OK, "> 24 hours"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero observations render UNOBSERVED");
    }

    @Test
    @DisplayName("NEGATIVE CONTROL: a metric without a definition of wrong is refused")
    void definitionOfWrongIsMandatory() {
        assertThatThrownBy(() ->
                new Metric("x", 1.0, "hours", 1, SignalState.OK, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot alert");
    }

    @Test
    @DisplayName("NEGATIVE CONTROL: UNOBSERVED cannot carry a value")
    void unobservedCannotCarryValue() {
        assertThatThrownBy(() ->
                new Metric("x", 3.0, "hours", 0, SignalState.UNOBSERVED, "> 24 hours"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries a value");
    }

    @Test
    @DisplayName("NEGATIVE CONTROL: an observed metric cannot omit its value")
    void observedMustCarryValue() {
        assertThatThrownBy(() ->
                new Metric("x", null, "hours", 3, SignalState.OK, "> 24 hours"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries no value");
    }

    @Test
    @DisplayName("a breaching metric alerts")
    void degradedAlerts() {
        Metric m = Metric.observed("x", 48.0, "hours", 5, true, "> 24 hours");

        assertThat(m.state()).isEqualTo(SignalState.DEGRADED);
        assertThat(m.alerting()).isTrue();
    }

    /**
     * The fourth invariant, which was the only one without a negative control.
     *
     * <p>A negative observation count would sail past the zero-observations
     * check -- the guard that carries this project's central claim -- because
     * that one asks {@code == 0}. A constraint never observed refusing anything
     * is not known to work, which is this repository's own argument about
     * gates, applied to itself.
     */
    @Test
    void aNegativeObservationCountIsRefused() {
        assertThatThrownBy(() -> new Metric(
                "lead_time_for_changes", 1.0, "hours", -1, SignalState.OK, "> 24 hours"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
    }
}
