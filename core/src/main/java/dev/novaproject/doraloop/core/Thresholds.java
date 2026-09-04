package dev.novaproject.doraloop.core;

/**
 * Where each metric stops being acceptable.
 *
 * <p>These are the <strong>Elite</strong> band boundaries from the DORA 2023
 * State of DevOps report, with one deliberate exception: time to restore is set
 * at the High boundary of one day rather than Elite's one hour, because a
 * one-hour restore target is not credible without a paging rotation.
 *
 * <p>Naming the band and the report year matters. The bands move annually, and
 * a threshold set is not self-describing -- "high performer" applied to a mix
 * of Elite and High boundaries is the kind of label that survives review
 * because nobody checks it.
 *
 * <p>Defaults only. These are the fallback when no per-service threshold is
 * configured; a payment authorization service and an internal wiki do not share
 * a restore objective. The effective value travels with each rendered metric in
 * {@link Metric#definitionOfWrong()}, so a report is interpretable without
 * access to the configuration that produced it.
 */
public final class Thresholds {

    /** Elite: on-demand, multiple deploys per day. Fewer than one per day is degraded. */
    public static final double DEPLOY_FREQ_MIN_PER_DAY = 1.0;

    /** Elite: less than one day from commit to production. */
    public static final double LEAD_TIME_MAX_HOURS = 24.0;

    /** Elite: 0-15% of changes to production degrading service. */
    public static final double CHANGE_FAILURE_MAX_PERCENT = 15.0;

    /** High band, not Elite. Elite is under one hour, which needs a paging rotation. */
    public static final double TIME_TO_RESTORE_MAX_HOURS = 24.0;

    /** Any change with an implausible author date is one too many. */
    public static final double SUSPECT_CHANGES_MAX = 0.0;

    private Thresholds() {
    }
}
