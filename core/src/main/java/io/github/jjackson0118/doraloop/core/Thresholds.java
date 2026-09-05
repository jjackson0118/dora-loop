package io.github.jjackson0118.doraloop.core;

/**
 * Where each metric stops being acceptable.
 *
 * <p>An instance, not a set of constants. The previous version was
 * {@code public static final} fields read directly by {@link DoraCalculator},
 * while its own documentation claimed they were "defaults only... the fallback
 * when no per-service threshold is configured" -- and no configuration
 * mechanism existed, so a per-service threshold was impossible without editing
 * this file. A payment authorization service and an internal wiki do not share
 * a restore objective.
 *
 * <p>These values are rendered into every metric's
 * {@link Metric#definitionOfWrong()}, so they travel with every report a
 * caller stores or serves. That is why this became configurable before anything
 * persisted a report: afterwards it is a change visible on the wire.
 *
 * <p>{@link #defaults()} carries the <strong>Elite</strong> band from the DORA
 * 2023 State of DevOps report, with one deliberate exception: time to restore
 * sits at the High boundary of one day rather than Elite's one hour, because a
 * one-hour restore target is not credible without a paging rotation. Naming the
 * band and the report year matters -- the bands move annually, and "high
 * performer" applied to a mix of Elite and High boundaries is the kind of label
 * that survives review because nobody checks it.
 *
 * @param deployFrequencyMinPerDay  fewer production deploys per day than this is degraded
 * @param leadTimeMaxHours          median commit-to-production above this is degraded
 * @param changeFailureMaxPercent   share of changes degrading production above this is degraded
 * @param timeToRestoreMaxHours     median detect-to-resolve above this is degraded
 * @param suspectMax                any quarantined record is one too many
 */
public record Thresholds(
        double deployFrequencyMinPerDay,
        double leadTimeMaxHours,
        double changeFailureMaxPercent,
        double timeToRestoreMaxHours,
        double suspectMax
) {
    public Thresholds {
        if (deployFrequencyMinPerDay < 0 || leadTimeMaxHours < 0
                || changeFailureMaxPercent < 0 || timeToRestoreMaxHours < 0
                || suspectMax < 0) {
            throw new IllegalArgumentException("thresholds cannot be negative");
        }
        if (changeFailureMaxPercent > 100) {
            throw new IllegalArgumentException(
                    "changeFailureMaxPercent above 100 can never be exceeded -- a threshold that cannot trip is worse than none, because it reads as coverage");
        }
    }

    /** DORA 2023 Elite band, except time to restore, which is the High boundary. */
    public static Thresholds defaults() {
        return new Thresholds(1.0, 24.0, 15.0, 24.0, 0.0);
    }
}
