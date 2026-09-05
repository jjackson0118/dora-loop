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

    record IngestAccepted(String id, boolean stored, List<Warning> warnings) {}

    private IngestDtos() {}
}
