package io.github.jjackson0118.doraloop.core;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class VerificationTest {
    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private final DoraCalculator calculator = new DoraCalculator(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(30));
    private DeploymentEvent event(String id, String service, String env, Instant at, Outcome outcome, Verification v) {
        return new DeploymentEvent(id, service, env, List.of(new Change("sha", at.minusSeconds(3600))), at, outcome, v);
    }
    @Test void missingEvidenceIsConservativeAndNullIsRejected() {
        assertThat(new DeploymentEvent("d", "svc", "production", List.of(), NOW, Outcome.SUCCESS).verification())
                .isEqualTo(Verification.UNVERIFIED);
        assertThatThrownBy(() -> event("d", "svc", "production", NOW, Outcome.SUCCESS, null))
                .isInstanceOf(NullPointerException.class);
    }
    @Test void noDeploymentsIsUnobservedAndVerifiedDeploymentsAreRealZero() {
        Metric empty = calculator.calculate("svc", List.of(), List.of()).unverifiedDeployments();
        assertThat(empty.state()).isEqualTo(SignalState.UNOBSERVED);
        assertThat(empty.value()).isNull();
        assertThat(empty.observedN()).isZero();
        Metric verified = calculator.calculate("svc", List.of(event("v", "svc", "production", NOW.minusSeconds(1), Outcome.SUCCESS, Verification.VERIFIED)), List.of()).unverifiedDeployments();
        assertThat(verified.state()).isEqualTo(SignalState.OK);
        assertThat(verified.value()).isZero();
        assertThat(verified.observedN()).isEqualTo(1);
    }
    @Test void countsAllOutcomesWithinServiceProductionAndHalfOpenWindowOnly() {
        Instant at = NOW.minusSeconds(1);
        DoraReport r = calculator.calculate("svc", List.of(
                event("s", "svc", "production", at, Outcome.SUCCESS, Verification.UNVERIFIED),
                event("f", "svc", "production", at, Outcome.FAILED_ROLLOUT, Verification.UNVERIFIED),
                event("r", "svc", "production", NOW.minus(Duration.ofDays(30)), Outcome.ROLLED_BACK, Verification.UNVERIFIED),
                event("v", "svc", "production", at, Outcome.SUCCESS, Verification.VERIFIED),
                event("other", "else", "production", at, Outcome.SUCCESS, Verification.UNVERIFIED),
                event("stage", "svc", "staging", at, Outcome.SUCCESS, Verification.UNVERIFIED),
                event("end", "svc", "production", NOW, Outcome.SUCCESS, Verification.UNVERIFIED),
                event("old", "svc", "production", NOW.minus(Duration.ofDays(30)).minusSeconds(1), Outcome.SUCCESS, Verification.UNVERIFIED)), List.of());
        assertThat(r.unverifiedDeployments().value()).isEqualTo(3);
        assertThat(r.unverifiedDeployments().observedN()).isEqualTo(4);
        assertThat(r.unverifiedDeployments().state()).isEqualTo(SignalState.DEGRADED);
        assertThat(r.alerting()).contains(r.unverifiedDeployments());
        assertThat(r.metrics()).hasSize(4).doesNotContain(r.unverifiedDeployments());
    }
    @Test void verificationDoesNotChangeTheDoraFormulas() {
        DeploymentEvent u = event("d", "svc", "production", NOW.minusSeconds(1), Outcome.SUCCESS, Verification.UNVERIFIED);
        DeploymentEvent v = event("d", "svc", "production", NOW.minusSeconds(1), Outcome.SUCCESS, Verification.VERIFIED);
        assertThat(calculator.calculate("svc", List.of(u), List.of()).metrics())
                .isEqualTo(calculator.calculate("svc", List.of(v), List.of()).metrics());
    }
}
