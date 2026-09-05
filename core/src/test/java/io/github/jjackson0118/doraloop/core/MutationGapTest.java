package io.github.jjackson0118.doraloop.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static io.github.jjackson0118.doraloop.core.TestEvents.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** The core-side gaps a mutation pass found. Each test names the mutation it kills. */
class MutationGapTest {

    private static final Duration WINDOW = Duration.ofDays(30);

    private final DoraCalculator calc =
            new DoraCalculator(Clock.fixed(NOW, ZoneOffset.UTC), WINDOW);

    /** Kills M01: median() with the sort deleted. */
    @Test
    @DisplayName("the median sorts: observations do not arrive in order")
    void medianSortsBeforeSelectingTheMiddle() {
        // Lead times 10, 2, 6 in that order. The median is 6; the middle
        // element of the UNSORTED list is 2. Every existing fixture happens to
        // list its lead times ascending, so the sort was never exercised.
        Metric m = calc.calculate("dora-loop",
                List.of(TestEvents.prodDeployWith("multi", NOW.minus(Duration.ofDays(1)),
                        Outcome.SUCCESS, 10.0, 2.0, 6.0)),
                List.of()).leadTimeForChanges();

        assertThat(m.value()).as("median of {10,2,6} is 6, not the middle of the input order").isEqualTo(6.0);
    }

    /** Kills M01 from the other metric, where the input order is the caller's. */
    @Test
    @DisplayName("time to restore sorts its observations too")
    void timeToRestoreSortsBeforeSelectingTheMiddle() {
        Metric m = calc.calculate("dora-loop", List.of(),
                List.of(TestEvents.resolved("i1", NOW.minus(Duration.ofDays(1)), 9),
                        TestEvents.resolved("i2", NOW.minus(Duration.ofDays(2)), 1),
                        TestEvents.resolved("i3", NOW.minus(Duration.ofDays(3)), 5)))
                .timeToRestore();

        assertThat(m.value()).isEqualTo(5.0);
    }

    /** Kills M02: isProduction() narrowed to a case-sensitive match. */
    @Test
    @DisplayName("isProduction is case-insensitive, and the report agrees")
    void productionIsMatchedRegardlessOfCase() {
        DeploymentEvent shouty = new DeploymentEvent("d1", "dora-loop", "PRODUCTION",
                List.of(new Change("sha", NOW.minus(Duration.ofDays(2)))),
                NOW.minus(Duration.ofDays(1)), Outcome.SUCCESS);
        DeploymentEvent titled = new DeploymentEvent("d2", "dora-loop", "Production",
                List.of(new Change("sha2", NOW.minus(Duration.ofDays(2)))),
                NOW.minus(Duration.ofDays(1)), Outcome.SUCCESS);

        assertThat(shouty.isProduction()).isTrue();
        assertThat(titled.isProduction()).isTrue();
        assertThat(calc.calculate("dora-loop", List.of(shouty, titled), List.of())
                .deploymentFrequency().observedN())
                .as("a differently-cased production environment still counts")
                .isEqualTo(2);
    }

    /**
     * Kills M03: carries() weakened from anyMatch to allMatch.
     *
     * <p>Every existing change-failure fixture uses deployments carrying exactly
     * one change, and for a single-element list anyMatch and allMatch are the
     * same function.
     */
    @Test
    @DisplayName("a deployment carries a commit if ANY of its changes match, not only if all do")
    void carriesIsSatisfiedByOneMatchingCommitAmongMany() {
        DeploymentEvent multi = TestEvents.prodDeployWith(
                "multi", NOW.minus(Duration.ofDays(1)), Outcome.SUCCESS, 2.0, 4.0);

        assertThat(multi.carries("sha-multi-1")).isTrue();
        assertThat(multi.carries("sha-multi-0")).isTrue();
        assertThat(multi.carries("nope")).isFalse();

        Metric cfr = calc.calculate("dora-loop", List.of(multi),
                List.of(TestEvents.blaming("i", "sha-multi-1", NOW.minus(Duration.ofDays(1)))))
                .changeFailureRate();

        assertThat(cfr.value())
                .as("an incident blaming one commit of a two-commit deploy is a change failure")
                .isEqualTo(100.0);
    }

    /** Also kills M03: allMatch is vacuously true for a redeploy carrying nothing. */
    @Test
    @DisplayName("a redeploy carrying no changes carries no commit")
    void aRedeployCarriesNothing() {
        DeploymentEvent redeploy = TestEvents.redeploy("re", NOW.minus(Duration.ofDays(1)));

        assertThat(redeploy.carries("anything")).isFalse();

        Metric cfr = calc.calculate("dora-loop", List.of(redeploy),
                List.of(TestEvents.blaming("i", "anything", NOW.minus(Duration.ofDays(1)))))
                .changeFailureRate();

        assertThat(cfr.value())
                .as("a redeploy carrying no commits cannot be blamed for an incident")
                .isZero();
    }

    /** Kills M22: deployment frequency's threshold comparison relaxed to <=. */
    @Test
    @DisplayName("deployment frequency exactly on the minimum is not degraded")
    void deployFrequencyExactlyOnTheThresholdIsNotDegraded() {
        // defaults(): deployFrequencyMinPerDay = 1.0. Thirty deploys over P30D
        // is exactly 1.0/day, the only value that distinguishes < from <=.
        List<DeploymentEvent> deploys = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            deploys.add(TestEvents.prodDeploy("d" + i, NOW.minus(Duration.ofHours(1 + i * 12)), 1, Outcome.SUCCESS));
        }

        Metric m = calc.calculate("dora-loop", deploys, List.of()).deploymentFrequency();

        assertThat(m.value()).isEqualTo(1.0);
        assertThat(m.state()).as("exactly at the minimum is not yet wrong").isEqualTo(SignalState.OK);
    }

    /** Kills M23: change failure rate's threshold comparison relaxed to >=. */
    @Test
    @DisplayName("change failure rate exactly on the threshold is not degraded")
    void changeFailureRateExactlyOnTheThresholdIsNotDegraded() {
        // defaults(): changeFailureMaxPercent = 15.0. Three rollbacks in twenty
        // deployments is exactly 15.0 percent.
        List<DeploymentEvent> deploys = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            deploys.add(TestEvents.prodDeploy("d" + i, NOW.minus(Duration.ofHours(1 + i * 12)), 1,
                    i < 3 ? Outcome.ROLLED_BACK : Outcome.SUCCESS));
        }

        Metric m = calc.calculate("dora-loop", deploys, List.of()).changeFailureRate();

        assertThat(m.value()).isEqualTo(15.0);
        assertThat(m.state()).isEqualTo(SignalState.OK);
    }

    /** Kills M24: time to restore's threshold comparison relaxed to >=. */
    @Test
    @DisplayName("time to restore exactly on the threshold is not degraded")
    void timeToRestoreExactlyOnTheThresholdIsNotDegraded() {
        // defaults(): timeToRestoreMaxHours = 24.0.
        Metric m = calc.calculate("dora-loop", List.of(),
                List.of(TestEvents.resolved("i1", NOW.minus(Duration.ofDays(2)), 24.0)))
                .timeToRestore();

        assertThat(m.value()).isEqualTo(24.0);
        assertThat(m.state()).isEqualTo(SignalState.OK);
    }

    /**
     * Kills M60: suspect_incidents' threshold comparison relaxed to >=.
     *
     * <p>{@code suspectMax} is 0.0, so every clean report sits exactly on this
     * boundary. The twin assertion exists for suspect_changes
     * ({@code cleanInputIsOk}) and did not exist for suspect_incidents.
     */
    @Test
    @DisplayName("clean incident input reports a real zero, not a degradation")
    void suspectIncidentsWithCleanInputIsARealZero() {
        Metric m = calc.calculate("dora-loop", List.of(),
                List.of(TestEvents.resolved("i1", NOW.minus(Duration.ofDays(1)), 4)))
                .suspectIncidents();

        assertThat(m.value()).isZero();
        assertThat(m.state()).isEqualTo(SignalState.OK);
        assertThat(m.alerting()).isFalse();
    }
}
