package dev.novaproject.doraloop.core;

/**
 * Where each metric stops being acceptable.
 *
 * <p>Values track the DORA "high performer" band. They are constants in one
 * place rather than literals at each call site so that the definition of wrong
 * is reviewable as a unit -- and so changing a threshold is a visible diff.
 */
public final class Thresholds {

    /** Fewer than one production deploy per day is degraded. */
    public static final double DEPLOY_FREQ_MIN_PER_DAY = 1.0;

    /** Commit to production taking longer than a day is degraded. */
    public static final double LEAD_TIME_MAX_HOURS = 24.0;

    /** More than 15% of deploys causing a failure is degraded. */
    public static final double CHANGE_FAILURE_MAX_PERCENT = 15.0;

    /** Taking longer than a day to restore service is degraded. */
    public static final double TIME_TO_RESTORE_MAX_HOURS = 24.0;

    private Thresholds() {
    }
}
