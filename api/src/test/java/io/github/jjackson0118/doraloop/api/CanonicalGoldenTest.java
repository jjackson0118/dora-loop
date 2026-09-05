package io.github.jjackson0118.doraloop.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The encoding is pinned byte for byte, not merely asserted to be injective.
 *
 * <p>{@link CanonicalEncodingTest} proves no two distinct payloads collide.
 * That is necessary and it is not sufficient: an encoding can be perfectly
 * injective and still be a <em>different</em> encoding from the one that
 * produced every payload_hash already in the table. Injectivity is preserved by
 * changing the version tag, by dropping the {@code |n=} marker, by shifting the
 * length prefix by one, and by encoding an untruncated timestamp -- all four
 * were applied to {@code canonical()} and the whole suite stayed green, while
 * every stored hash silently stopped matching the payload it describes.
 *
 * <p>A golden value is the assertion that notices. It fails loudly on any
 * change to the scheme, which is exactly what the version tag exists to promise
 * and what nothing was checking.
 */
class CanonicalGoldenTest {

    private static final Instant T = Instant.parse("2026-09-05T12:00:00Z");
    private static final Instant A = Instant.parse("2026-09-05T10:00:00Z");

    /** Kills M06 (version tag), M07 (|n= marker), M27 (length prefix). */
    @Test
    @DisplayName("the deployment encoding is exactly this string")
    void deploymentEncodingIsPinned() {
        String encoded = IngestService.canonical(new IngestDtos.DeploymentDto(
                "e1", "pay", "prod", T, "SUCCESS",
                List.of(new IngestDtos.ChangeDto("aaa", A))));

        assertThat(encoded).isEqualTo(
                "deployment/v1|2|e1|3|pay|4|prod|20|2026-09-05T12:00:00Z|7|SUCCESS|n=1|3|aaa|20|2026-09-05T10:00:00Z");
    }

    /** Kills M06 (the two namespaces must not share a tag) and M41 (null marker). */
    @Test
    @DisplayName("the incident encoding is exactly this string, and carries its own version tag")
    void incidentEncodingIsPinned() {
        String open = IngestService.canonical(
                new IngestDtos.IncidentDto("i1", "pay", null, T, null));
        assertThat(open).isEqualTo("incident/v1|2|i1|3|pay|null||20|2026-09-05T12:00:00Z|null|");

        String resolved = IngestService.canonical(
                new IngestDtos.IncidentDto("i1", "pay", "aaa", T, T.plusSeconds(1800)));
        assertThat(resolved).isEqualTo(
                "incident/v1|2|i1|3|pay|3|aaa|20|2026-09-05T12:00:00Z|20|2026-09-05T12:30:00Z");

        assertThat(open).as("the two encodings live in different namespaces")
                .startsWith("incident/v1");
    }

    /**
     * Kills M42: the digest is taken over the timestamp as stored, not as sent.
     *
     * <p>TIMESTAMPTZ holds microseconds. If {@code canonical()} digests the
     * nanosecond value the producer sent, the hash describes a payload the row
     * cannot contain, and a retry differing only in nanoseconds is a 409.
     */
    @Test
    @DisplayName("the encoding digests the truncated timestamp, the one Postgres can hold")
    void encodingUsesTheStorableTimestamp() {
        Instant nanos = Instant.parse("2026-09-05T12:00:00.123456789Z");

        String encoded = IngestService.canonical(new IngestDtos.DeploymentDto(
                "e1", "pay", "prod", nanos, "SUCCESS", List.of()));

        assertThat(encoded).isEqualTo(
                "deployment/v1|2|e1|3|pay|4|prod|27|2026-09-05T12:00:00.123456Z|7|SUCCESS|n=0");

        // Two submissions differing only below microsecond precision are the
        // same stored event and must digest identically.
        assertThat(encoded).isEqualTo(IngestService.canonical(new IngestDtos.DeploymentDto(
                "e1", "pay", "prod", Instant.parse("2026-09-05T12:00:00.123456111Z"),
                "SUCCESS", List.of())));
    }
}
