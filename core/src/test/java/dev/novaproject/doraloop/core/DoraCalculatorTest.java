package dev.novaproject.doraloop.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static dev.novaproject.doraloop.core.TestEvents.NOW;
import static dev.novaproject.doraloop.core.TestEvents.open;
import static dev.novaproject.doraloop.core.TestEvents.prodDeploy;
import static dev.novaproject.doraloop.core.TestEvents.resolved;
import static dev.novaproject.doraloop.core.TestEvents.stagingDeploy;
import static org.assertj.core.api.Assertions.assertThat;

class DoraCalculatorTest {

    private static final Duration WINDOW = Duration.ofDays(30);

    private final DoraCalculator calc =
            new DoraCalculator(Clock.fixed(NOW, ZoneOffset.UTC), WINDOW);

    @Nested
    @DisplayName("with no data at all")
    class EmptyInput {

        @Test
        @DisplayName("every metric is UNOBSERVED -- none is a green zero")
        void allUnobserved() {
            DoraReport r = calc.calculate("dora-loop", List.of(), List.of());

            assertThat(r.metrics()).allSatisfy(m -> {
                assertThat(m.state()).isEqualTo(SignalState.UNOBSERVED);
                assertThat(m.value()).isNull();
            });
            assertThat(r.unobserved()).hasSize(4);
            assertThat(r.alerting()).isEmpty();
        }
    }

    @Nested
    @DisplayName("deployment frequency")
    class DeploymentFrequency {

        @Test
        @DisplayName("counts production deploys per day across the window")
        void countsPerDay() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("a", NOW.minus(Duration.ofDays(1)), 2, Outcome.SUCCESS),
                    prodDeploy("b", NOW.minus(Duration.ofDays(2)), 2, Outcome.SUCCESS),
                    prodDeploy("c", NOW.minus(Duration.ofDays(3)), 2, Outcome.SUCCESS));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).deploymentFrequency();

            assertThat(m.observedN()).isEqualTo(3);
            assertThat(m.value()).isEqualTo(0.1); // 3 deploys / 30 days
            assertThat(m.state()).isEqualTo(SignalState.DEGRADED);
        }

        @Test
        @DisplayName("ignores non-production environments")
        void ignoresStaging() {
            List<DeploymentEvent> deploys = List.of(
                    stagingDeploy("s1", NOW.minus(Duration.ofDays(1))),
                    stagingDeploy("s2", NOW.minus(Duration.ofDays(2))));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).deploymentFrequency();

            assertThat(m.state()).isEqualTo(SignalState.UNOBSERVED);
        }

        @Test
        @DisplayName("ignores deploys outside the window")
        void ignoresStaleDeploys() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("old", NOW.minus(Duration.ofDays(45)), 2, Outcome.SUCCESS));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).deploymentFrequency();

            assertThat(m.state()).isEqualTo(SignalState.UNOBSERVED);
        }
    }

    @Nested
    @DisplayName("lead time for changes")
    class LeadTime {

        @Test
        @DisplayName("is the median of commit-to-deploy across successful deploys")
        void medianOfSuccessful() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("a", NOW.minus(Duration.ofDays(1)), 2, Outcome.SUCCESS),
                    prodDeploy("b", NOW.minus(Duration.ofDays(2)), 6, Outcome.SUCCESS),
                    prodDeploy("c", NOW.minus(Duration.ofDays(3)), 10, Outcome.SUCCESS));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).leadTimeForChanges();

            assertThat(m.value()).isEqualTo(6.0);
            assertThat(m.observedN()).isEqualTo(3);
            assertThat(m.state()).isEqualTo(SignalState.OK);
        }

        @Test
        @DisplayName("averages the middle pair when the count is even")
        void medianEvenCount() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("a", NOW.minus(Duration.ofDays(1)), 2, Outcome.SUCCESS),
                    prodDeploy("b", NOW.minus(Duration.ofDays(2)), 8, Outcome.SUCCESS));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).leadTimeForChanges();

            assertThat(m.value()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("failed deploys do not contribute lead time")
        void excludesFailures() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("ok", NOW.minus(Duration.ofDays(1)), 4, Outcome.SUCCESS),
                    prodDeploy("bad", NOW.minus(Duration.ofDays(2)), 100, Outcome.FAILURE));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).leadTimeForChanges();

            assertThat(m.value()).isEqualTo(4.0);
            assertThat(m.observedN()).isEqualTo(1);
        }

        @Test
        @DisplayName("breaches at more than 24 hours")
        void degradesPastThreshold() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("slow", NOW.minus(Duration.ofDays(1)), 36, Outcome.SUCCESS));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).leadTimeForChanges();

            assertThat(m.state()).isEqualTo(SignalState.DEGRADED);
            assertThat(m.definitionOfWrong()).contains("24.0");
        }
    }

    @Nested
    @DisplayName("change failure rate")
    class ChangeFailureRate {

        @Test
        @DisplayName("is failures over total production deploys")
        void percentOfFailures() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("a", NOW.minus(Duration.ofDays(1)), 2, Outcome.SUCCESS),
                    prodDeploy("b", NOW.minus(Duration.ofDays(2)), 2, Outcome.SUCCESS),
                    prodDeploy("c", NOW.minus(Duration.ofDays(3)), 2, Outcome.SUCCESS),
                    prodDeploy("d", NOW.minus(Duration.ofDays(4)), 2, Outcome.FAILURE));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).changeFailureRate();

            assertThat(m.value()).isEqualTo(25.0);
            assertThat(m.state()).isEqualTo(SignalState.DEGRADED);
        }

        @Test
        @DisplayName("a clean window is a real zero, because deploys were observed")
        void zeroWithObservationsIsOk() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("a", NOW.minus(Duration.ofDays(1)), 2, Outcome.SUCCESS));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).changeFailureRate();

            assertThat(m.value()).isZero();
            assertThat(m.observedN()).isEqualTo(1);
            assertThat(m.state()).isEqualTo(SignalState.OK);
        }
    }

    @Nested
    @DisplayName("time to restore")
    class TimeToRestore {

        @Test
        @DisplayName("is the median across resolved incidents")
        void medianOfResolved() {
            List<IncidentEvent> incidents = List.of(
                    resolved("i1", NOW.minus(Duration.ofDays(1)), 2),
                    resolved("i2", NOW.minus(Duration.ofDays(2)), 4),
                    resolved("i3", NOW.minus(Duration.ofDays(3)), 6));

            Metric m = calc.calculate("dora-loop", List.of(), incidents).timeToRestore();

            assertThat(m.value()).isEqualTo(4.0);
            assertThat(m.observedN()).isEqualTo(3);
        }

        @Test
        @DisplayName("an open incident does not count as a zero-duration restore")
        void openIncidentsExcluded() {
            List<IncidentEvent> incidents = List.of(
                    resolved("done", NOW.minus(Duration.ofDays(1)), 10),
                    open("ongoing", NOW.minus(Duration.ofDays(2))));

            Metric m = calc.calculate("dora-loop", List.of(), incidents).timeToRestore();

            assertThat(m.value()).isEqualTo(10.0);
            assertThat(m.observedN()).isEqualTo(1);
        }

        @Test
        @DisplayName("only open incidents means UNOBSERVED, never a healthy zero")
        void allOpenIsUnobserved() {
            List<IncidentEvent> incidents = List.of(
                    open("a", NOW.minus(Duration.ofDays(1))),
                    open("b", NOW.minus(Duration.ofDays(2))));

            Metric m = calc.calculate("dora-loop", List.of(), incidents).timeToRestore();

            assertThat(m.state()).isEqualTo(SignalState.UNOBSERVED);
            assertThat(m.value()).isNull();
        }
    }
}
