package io.github.jjackson0118.doraloop.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static io.github.jjackson0118.doraloop.core.TestEvents.NOW;
import static io.github.jjackson0118.doraloop.core.TestEvents.blaming;
import static io.github.jjackson0118.doraloop.core.TestEvents.open;
import static io.github.jjackson0118.doraloop.core.TestEvents.otherServiceDeploy;
import static io.github.jjackson0118.doraloop.core.TestEvents.otherServiceIncident;
import static io.github.jjackson0118.doraloop.core.TestEvents.prodDeploy;
import static io.github.jjackson0118.doraloop.core.TestEvents.prodDeployWith;
import static io.github.jjackson0118.doraloop.core.TestEvents.redeploy;
import static io.github.jjackson0118.doraloop.core.TestEvents.resolved;
import static io.github.jjackson0118.doraloop.core.TestEvents.skewedDeploy;
import static io.github.jjackson0118.doraloop.core.TestEvents.stagingDeploy;
import static org.assertj.core.api.Assertions.assertThat;

class DoraCalculatorTest {

    private static final Duration WINDOW = Duration.ofDays(30);

    private final DoraCalculator calc =
            new DoraCalculator(Clock.fixed(NOW, ZoneOffset.UTC), WINDOW);


    @Nested
    @DisplayName("service scoping")
    class ServiceScoping {

        /**
         * Another service's events are not this service's metrics.
         *
         * <p>The filter is implemented twice -- here and again in the api
         * module's SQL -- and each half covered for the other, so either could
         * be deleted with the whole suite green. Every other fixture in this
         * class belongs to "dora-loop", so this filter had never been shown a
         * row it was supposed to reject. A guard never observed refusing
         * anything is the thing this project argues against.
         */
        @Test
        void anotherServicesEventsAreExcludedFromEveryMetric() {
            DoraReport r = calc.calculate("dora-loop",
                    List.of(prodDeploy("mine", NOW.minus(Duration.ofDays(1)), 2, Outcome.SUCCESS),
                            otherServiceDeploy("theirs", NOW.minus(Duration.ofDays(1)))),
                    List.of(otherServiceIncident("theirs-i", NOW.minus(Duration.ofDays(1)))));

            assertThat(r.deploymentFrequency().observedN())
                    .as("only this service's deployment is counted").isEqualTo(1);
            assertThat(r.leadTimeForChanges().observedN())
                    .as("only this service's change is an observation").isEqualTo(1);
            assertThat(r.timeToRestore().state())
                    .as("another service's incident is not a restore here")
                    .isEqualTo(SignalState.UNOBSERVED);
            assertThat(r.changeFailureRate().observedN()).isEqualTo(1);
        }

        /** And with only foreign events, everything is UNOBSERVED, not zero. */
        @Test
        void onlyForeignEventsMeansUnobservedNotZero() {
            DoraReport r = calc.calculate("dora-loop",
                    List.of(otherServiceDeploy("theirs", NOW.minus(Duration.ofDays(1)))),
                    List.of(otherServiceIncident("theirs-i", NOW.minus(Duration.ofDays(1)))));

            assertThat(r.metrics()).allSatisfy(m ->
                    assertThat(m.state()).isEqualTo(SignalState.UNOBSERVED));
        }
    }

    @Nested
    @DisplayName("with no data at all")
    class EmptyInput {

        @Test
        @DisplayName("every signal is UNOBSERVED -- none is a green zero")
        void allUnobserved() {
            DoraReport r = calc.calculate("dora-loop", List.of(), List.of());

            assertThat(r.allSignals()).allSatisfy(m -> {
                assertThat(m.state()).isEqualTo(SignalState.UNOBSERVED);
                assertThat(m.value()).isNull();
            });
            assertThat(r.unobserved()).hasSize(6);
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
            assertThat(m.value()).isEqualTo(0.1);
            assertThat(m.state()).isEqualTo(SignalState.DEGRADED);
        }

        @Test
        @DisplayName("ignores non-production environments")
        void ignoresStaging() {
            List<DeploymentEvent> deploys = List.of(
                    stagingDeploy("s1", NOW.minus(Duration.ofDays(1))),
                    stagingDeploy("s2", NOW.minus(Duration.ofDays(2))));

            assertThat(calc.calculate("dora-loop", deploys, List.of())
                    .deploymentFrequency().state()).isEqualTo(SignalState.UNOBSERVED);
        }

        @Test
        @DisplayName("ignores deploys outside the window")
        void ignoresStaleDeploys() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("old", NOW.minus(Duration.ofDays(45)), 2, Outcome.SUCCESS));

            assertThat(calc.calculate("dora-loop", deploys, List.of())
                    .deploymentFrequency().state()).isEqualTo(SignalState.UNOBSERVED);
        }

        @Test
        @DisplayName("the window is half-open, so an event exactly at the end is excluded")
        void windowIsHalfOpen() {
            List<DeploymentEvent> atEnd = List.of(prodDeploy("edge", NOW, 2, Outcome.SUCCESS));
            List<DeploymentEvent> atStart = List.of(
                    prodDeploy("edge", NOW.minus(WINDOW), 2, Outcome.SUCCESS));

            assertThat(calc.calculate("dora-loop", atEnd, List.of())
                    .deploymentFrequency().state()).isEqualTo(SignalState.UNOBSERVED);
            assertThat(calc.calculate("dora-loop", atStart, List.of())
                    .deploymentFrequency().observedN()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("lead time for changes")
    class LeadTime {

        @Test
        @DisplayName("emits one observation per change, not one per deployment")
        void perChangeNotPerDeploy() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeployWith("multi", NOW.minus(Duration.ofDays(1)), Outcome.SUCCESS, 2, 6, 10));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).leadTimeForChanges();

            assertThat(m.observedN()).isEqualTo(3);
            assertThat(m.value()).isEqualTo(6.0);
        }

        @Test
        @DisplayName("a two-week branch is not collapsed to its final commit")
        void doesNotCollapseToHeadCommit() {
            // A branch whose last commit was minutes before deploy but whose
            // work began two weeks earlier. Measuring the head commit alone
            // would report ~0.1h; the correct median across both is ~168h.
            List<DeploymentEvent> deploys = List.of(
                    prodDeployWith("branch", NOW.minus(Duration.ofDays(1)),
                            Outcome.SUCCESS, 336.0, 0.1));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).leadTimeForChanges();

            assertThat(m.value()).isEqualTo(168.05);
            assertThat(m.state()).isEqualTo(SignalState.DEGRADED);
        }

        @Test
        @DisplayName("a redeploy carries no change and contributes no observation")
        void redeployContributesNothing() {
            List<DeploymentEvent> deploys = List.of(redeploy("re", NOW.minus(Duration.ofDays(1))));

            DoraReport r = calc.calculate("dora-loop", deploys, List.of());

            assertThat(r.leadTimeForChanges().state()).isEqualTo(SignalState.UNOBSERVED);
            assertThat(r.deploymentFrequency().observedN()).isEqualTo(1);
        }

        @Test
        @DisplayName("a failed rollout contributes no lead time")
        void excludesFailedRollouts() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("ok", NOW.minus(Duration.ofDays(1)), 4, Outcome.SUCCESS),
                    prodDeploy("bad", NOW.minus(Duration.ofDays(2)), 100, Outcome.FAILED_ROLLOUT));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).leadTimeForChanges();

            assertThat(m.value()).isEqualTo(4.0);
            assertThat(m.observedN()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("change failure rate")
    class ChangeFailureRate {

        @Test
        @DisplayName("counts changes that degraded production, joined by commit")
        void joinsIncidentsToDeploys() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("a", NOW.minus(Duration.ofDays(1)), 2, Outcome.SUCCESS),
                    prodDeploy("b", NOW.minus(Duration.ofDays(2)), 2, Outcome.SUCCESS),
                    prodDeploy("c", NOW.minus(Duration.ofDays(3)), 2, Outcome.SUCCESS),
                    prodDeploy("d", NOW.minus(Duration.ofDays(4)), 2, Outcome.SUCCESS));
            List<IncidentEvent> incidents = List.of(
                    blaming("i1", "sha-d", NOW.minus(Duration.ofDays(4))));

            Metric m = calc.calculate("dora-loop", deploys, incidents).changeFailureRate();

            assertThat(m.value()).isEqualTo(25.0);
            assertThat(m.state()).isEqualTo(SignalState.DEGRADED);
        }

        @Test
        @DisplayName("a failed rollout is not a change failure -- it never reached users")
        void failedRolloutIsNotAChangeFailure() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("ok", NOW.minus(Duration.ofDays(1)), 2, Outcome.SUCCESS),
                    prodDeploy("blocked", NOW.minus(Duration.ofDays(2)), 2, Outcome.FAILED_ROLLOUT));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).changeFailureRate();

            assertThat(m.value()).isZero();
            assertThat(m.observedN()).isEqualTo(1); // the blocked rollout leaves the denominator too
            assertThat(m.state()).isEqualTo(SignalState.OK);
        }

        @Test
        @DisplayName("a rollback is a change failure without needing an incident record")
        void rollbackCountsDirectly() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("a", NOW.minus(Duration.ofDays(1)), 2, Outcome.SUCCESS),
                    prodDeploy("b", NOW.minus(Duration.ofDays(2)), 2, Outcome.ROLLED_BACK));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).changeFailureRate();

            assertThat(m.value()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("an incident detected after the window still blames its deploy")
        void incidentOutsideWindowStillCounts() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("a", NOW.minus(Duration.ofDays(1)), 2, Outcome.SUCCESS));
            // detected 12 hours in the "future" relative to the window end
            List<IncidentEvent> incidents = List.of(
                    blaming("late", "sha-a", NOW.plus(Duration.ofHours(12))));

            Metric m = calc.calculate("dora-loop", deploys, incidents).changeFailureRate();

            assertThat(m.value()).isEqualTo(100.0);
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

    @Nested
    @DisplayName("thresholds")
    class ConfigurableThresholds {

        @Test
        @DisplayName("a stricter per-service threshold changes the verdict on the same data")
        void thresholdsAreInjectable() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("a", NOW.minus(Duration.ofDays(1)), 6, Outcome.SUCCESS));

            // 6h lead time: OK against the 24h default, degraded against a
            // service that promises four. Impossible before Thresholds became
            // an instance, because the values were compiled into the calculator.
            Metric lenient = new DoraCalculator(Clock.fixed(NOW, ZoneOffset.UTC), WINDOW)
                    .calculate("dora-loop", deploys, List.of()).leadTimeForChanges();
            Metric strict = new DoraCalculator(Clock.fixed(NOW, ZoneOffset.UTC), WINDOW,
                    new Thresholds(1.0, 4.0, 15.0, 24.0, 0.0))
                    .calculate("dora-loop", deploys, List.of()).leadTimeForChanges();

            assertThat(lenient.state()).isEqualTo(SignalState.OK);
            assertThat(strict.state()).isEqualTo(SignalState.DEGRADED);
            assertThat(strict.definitionOfWrong()).contains("4.0");
        }
    }

    @Nested
    @DisplayName("data quality")
    class DataQuality {

        @Test
        @DisplayName("an incident resolved before it was detected is quarantined, not silently dropped")
        void skewedIncidentIsSurfaced() {
            List<IncidentEvent> incidents = List.of(
                    resolved("good", NOW.minus(Duration.ofDays(1)), 4),
                    new IncidentEvent("skewed", "dora-loop", null,
                            NOW.minus(Duration.ofDays(2)),
                            NOW.minus(Duration.ofDays(3))));

            DoraReport r = calc.calculate("dora-loop", List.of(), incidents);

            assertThat(r.suspectIncidents().value()).isEqualTo(1.0);
            assertThat(r.suspectIncidents().observedN()).isEqualTo(2);
            assertThat(r.suspectIncidents().alerting()).isTrue();

            // excluded from the median, but the incident was not lost
            assertThat(r.timeToRestore().value()).isEqualTo(4.0);
            assertThat(r.timeToRestore().observedN()).isEqualTo(1);
        }

        @Test
        @DisplayName("a change authored after its deploy is quarantined, not silently dropped")
        void skewIsSurfaced() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("good", NOW.minus(Duration.ofDays(1)), 4, Outcome.SUCCESS),
                    skewedDeploy("skewed", NOW.minus(Duration.ofDays(2)), 6));

            DoraReport r = calc.calculate("dora-loop", deploys, List.of());

            assertThat(r.suspectChanges().value()).isEqualTo(1.0);
            assertThat(r.suspectChanges().observedN()).isEqualTo(2);
            assertThat(r.suspectChanges().alerting()).isTrue();
            assertThat(r.alerting()).contains(r.suspectChanges());

            // the skewed change is excluded from lead time but the deploy still counts
            assertThat(r.leadTimeForChanges().observedN()).isEqualTo(1);
            assertThat(r.deploymentFrequency().observedN()).isEqualTo(2);
        }

        @Test
        @DisplayName("clean input reports a real zero, because changes were observed")
        void cleanInputIsOk() {
            List<DeploymentEvent> deploys = List.of(
                    prodDeploy("a", NOW.minus(Duration.ofDays(1)), 4, Outcome.SUCCESS));

            Metric m = calc.calculate("dora-loop", deploys, List.of()).suspectChanges();

            assertThat(m.value()).isZero();
            assertThat(m.state()).isEqualTo(SignalState.OK);
        }
    }
}
