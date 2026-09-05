package io.github.jjackson0118.doraloop.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * The wire contract. Deliberately separate types from the core records.
 *
 * <p>Serializing the core records directly would make their component names a
 * wire contract that any refactor silently breaks, and it is the first step
 * toward Jackson and jakarta.validation annotations appearing on domain types.
 * The mapping costs about forty lines and keeps {@code core} free of both.
 */
final class IngestDtos {

    record ChangeDto(
            @NotBlank String commitSha,
            @NotNull Instant authoredAt
    ) {}

    /**
     * @param changes every commit in the range since the previous deployment.
     *                <strong>May legitimately be empty</strong> -- redeploying an
     *                already-deployed commit is a real deployment carrying no new
     *                change. A {@code @NotEmpty} here is the natural instinct and
     *                it is wrong: it would reject redeploys and under-count
     *                deployment frequency.
     */
    record DeploymentDto(
            @NotBlank String id,
            @NotBlank String service,
            @NotBlank String environment,
            @NotNull Instant deployedAt,
            @NotBlank String outcome,
            @NotNull @Valid List<ChangeDto> changes
    ) {}

    record IncidentDto(
            @NotBlank String id,
            @NotBlank String service,
            String causedByCommitSha,
            @NotNull Instant detectedAt,
            Instant resolvedAt
    ) {}

    /** A warning is not a rejection. See {@link IngestService}. */
    record Warning(String code, String path, String detail) {}

    /**
     * What actually happened to this event.
     *
     * <p>This was a {@code boolean stored}, and it was the literal {@code true}
     * at every construction site -- including the branch that detected a
     * duplicate and wrote nothing. A field whose only job is to report whether
     * a write happened, and which cannot report that one did not, is
     * decoration: the same argument {@code Metric} makes about a signal with no
     * definition of wrong.
     *
     * <p>Three states rather than two, because a correction is neither a new
     * record nor a no-op and a caller reconciling its own view needs to tell
     * them apart.
     */
    enum Disposition { STORED, DUPLICATE, UPDATED }

    record IngestAccepted(String id, Disposition disposition, List<Warning> warnings) {}

    private IngestDtos() {}
}
