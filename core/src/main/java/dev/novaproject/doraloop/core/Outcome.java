package dev.novaproject.doraloop.core;

/**
 * What became of a deployment.
 *
 * <p>The three-way split matters for change failure rate. A rollout that fails
 * and never reaches users is the pipeline working, not a change failure. A
 * rollout that succeeds and is then withdrawn is a change failure by
 * definition, and it needs to be distinguishable from both.
 */
public enum Outcome {

    /** Reached production and stayed. */
    SUCCESS,

    /** The rollout itself failed. The change never reached users. */
    FAILED_ROLLOUT,

    /** Reached production and was subsequently withdrawn. */
    ROLLED_BACK;

    /** Whether the change actually reached users, which is the CFR denominator. */
    public boolean reachedProduction() {
        return this != FAILED_ROLLOUT;
    }
}
