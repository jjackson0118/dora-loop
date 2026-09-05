package io.github.jjackson0118.doraloop.api;

import io.github.jjackson0118.doraloop.core.Change;
import io.github.jjackson0118.doraloop.core.DeploymentEvent;
import io.github.jjackson0118.doraloop.core.IncidentEvent;
import io.github.jjackson0118.doraloop.core.Outcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
class IngestService {

    private final EventRepository repo;

    IngestService(EventRepository repo) {
        this.repo = repo;
    }

    /** Thrown when an id is reused with a payload that is not a legal correction. */
    static final class ConflictingReplay extends RuntimeException {
        ConflictingReplay(String message) { super(message); }
    }

    /**
     * A deployment, or a correction to one that was already recorded.
     *
     * <p>Three things can legitimately arrive under an id that already exists,
     * and collapsing them loses information in a direction that always
     * flatters the metrics:
     *
     * <ul>
     *   <li><b>A retry.</b> Identical payload, so nothing to do.</li>
     *   <li><b>A correction.</b> A deployment succeeds, and twenty minutes later
     *       it is rolled back. That is the same deployment with a known-later
     *       outcome, and it was previously answered 409 and dropped --
     *       {@code ROLLED_BACK} is one of only two numerator terms in change
     *       failure rate, so rejecting corrections biases CFR downward. The
     *       system looked better the more often it was wrong.</li>
     *   <li><b>A conflict.</b> Two different claims about one deployment.
     *       Accepting either silently loses the other.</li>
     * </ul>
     *
     * <p>Only {@code SUCCESS -> ROLLED_BACK} is a legal transition.
     * {@code FAILED_ROLLOUT} means the change never reached production, so it
     * cannot follow a success or be followed by one -- a retry that then
     * succeeded is a different deployment with its own id. {@code ROLLED_BACK}
     * is terminal. Everything else is a conflict.
     */
    @Transactional
    IngestDtos.IngestAccepted accept(IngestDtos.DeploymentDto dto) {
        Outcome outcome;
        try {
            outcome = Outcome.valueOf(dto.outcome());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "outcome must be one of SUCCESS, FAILED_ROLLOUT, ROLLED_BACK");
        }

        rejectIllFormed("id", dto.id());
        rejectIllFormed("service", dto.service());
        rejectIllFormed("environment", dto.environment());
        for (int i = 0; i < dto.changes().size(); i++) {
            rejectIllFormed("changes[" + i + "].commitSha", dto.changes().get(i).commitSha());
        }

        List<Change> changes = dto.changes().stream()
                .map(c -> new Change(c.commitSha(), storable(c.authoredAt()))).toList();
        DeploymentEvent event = new DeploymentEvent(
                dto.id(), dto.service(), dto.environment(), changes,
                storable(dto.deployedAt()), outcome);

        String hash = hash(canonical(dto));
        Optional<String> existing = repo.existingDeploymentHash(dto.id());

        if (existing.isEmpty()) {
            if (repo.insertDeployment(event, hash)) {
                return new IngestDtos.IngestAccepted(
                        dto.id(), IngestDtos.Disposition.STORED, warningsFor(event));
            }
            // Lost the race: an identical id was inserted between the check and
            // the insert. Re-read and fall through to exactly the same
            // comparison a sequential replay takes, so a concurrent retry gets
            // DUPLICATE and a concurrent conflict gets 409 -- rather than the
            // 500 this produced in 12 of 12 measured rounds.
            existing = repo.existingDeploymentHash(dto.id());
            if (existing.isEmpty()) {
                throw new IllegalStateException(
                        "insert conflicted on " + dto.id() + " but no row is visible");
            }
        }
        if (existing.get().equals(hash)) {
            return new IngestDtos.IngestAccepted(
                    dto.id(), IngestDtos.Disposition.DUPLICATE, warningsFor(event));
        }

        DeploymentEvent stored = repo.findDeployment(dto.id()).orElseThrow(
                () -> new IllegalStateException("hash row exists without an event: " + dto.id()));

        if (differsOnlyByOutcome(stored, event)) {
            if (stored.outcome() == Outcome.SUCCESS && outcome == Outcome.ROLLED_BACK) {
                repo.updateDeploymentOutcome(dto.id(), outcome, hash);
                return new IngestDtos.IngestAccepted(
                        dto.id(), IngestDtos.Disposition.UPDATED, warningsFor(event));
            }
            throw new ConflictingReplay("deployment " + dto.id() + " is recorded as "
                    + stored.outcome() + "; " + outcome + " is not a legal correction of that");
        }
        throw new ConflictingReplay(
                "id " + dto.id() + " already exists with a different payload");
    }

    /**
     * An incident, or its resolution.
     *
     * <p>{@code resolvedAt} arrives later, so resolving an incident is a
     * re-POST of the same id with the field set: retry semantics and resolution
     * semantics become one code path rather than a PATCH endpoint whose only
     * job is to set one field.
     *
     * <p>What that must not become is a blind overwrite, which is what it was.
     * The write was an unconditional upsert of every column from EXCLUDED, and
     * {@code existingIncidentHash} was written and never called. A producer
     * re-POSTing an incident without {@code resolvedAt} -- a retry of the
     * original open-incident event, which is a completely ordinary thing for a
     * pipeline to do -- set the column back to NULL, silently un-resolving the
     * incident and deleting a time-to-restore observation. The response was 201
     * with no warning. Only a monotonic open-to-resolved transition is accepted
     * now; anything else is a conflict.
     */
    @Transactional
    IngestDtos.IngestAccepted accept(IngestDtos.IncidentDto dto) {
        rejectIllFormed("id", dto.id());
        rejectIllFormed("service", dto.service());
        rejectIllFormed("causedByCommitSha", dto.causedByCommitSha());

        IncidentEvent event = new IncidentEvent(
                dto.id(), dto.service(), dto.causedByCommitSha(),
                storable(dto.detectedAt()), storable(dto.resolvedAt()));

        String hash = hash(canonical(dto));
        Optional<String> existing = repo.existingIncidentHash(dto.id());

        IngestDtos.Disposition disposition;
        if (existing.isEmpty() && repo.insertIncidentIfAbsent(event, hash)) {
            disposition = IngestDtos.Disposition.STORED;
        } else if (existing.isEmpty()
                && (existing = repo.existingIncidentHash(dto.id())).isEmpty()) {
            throw new IllegalStateException(
                    "insert conflicted on " + dto.id() + " but no row is visible");
        } else if (existing.get().equals(hash)) {
            disposition = IngestDtos.Disposition.DUPLICATE;
        } else {
            IncidentEvent stored = repo.findIncident(dto.id()).orElseThrow(
                    () -> new IllegalStateException("hash row exists without an event: " + dto.id()));
            if (!differsOnlyByResolution(stored, event)) {
                throw new ConflictingReplay(
                        "id " + dto.id() + " already exists with a different payload");
            }
            // Already resolved, and this payload says something else about it:
            // it either clears resolvedAt or moves it. Both are refused by the
            // same check, and there is deliberately no separate guard for the
            // clearing case -- it would be unreachable. If the stored incident
            // is still open and the incoming one is too, then every field this
            // branch compares is equal and both resolvedAt values are null, so
            // the canonical encodings are identical and the request never gets
            // past DUPLICATE above. A perturbation found that: deleting the
            // second guard broke no test, because nothing could reach it.
            if (stored.resolvedAt() != null) {
                // Already resolved with the SAME time is a retry, not a conflict.
                //
                // Found in CI and not locally, by the concurrency test: two
                // identical resolutions race, the loser re-reads after the
                // winner commits, and sees a resolved incident. Answering 409
                // there tells a producer its own successful delivery collided
                // with something -- for a duplicate delivery of a resolution,
                // which is the ordinary case this path exists to serve. The
                // hash check above cannot catch it, because it read the row
                // before the winner committed.
                if (stored.resolvedAt().equals(event.resolvedAt())) {
                    return new IngestDtos.IngestAccepted(
                            dto.id(), IngestDtos.Disposition.DUPLICATE, warningsForIncident(event, dto));
                }
                throw new ConflictingReplay("incident " + dto.id() + " is already resolved at "
                        + stored.resolvedAt() + "; resolution cannot be moved or cleared");
            }
            if (repo.resolveIncident(dto.id(), event.resolvedAt(), hash)) {
                disposition = IngestDtos.Disposition.UPDATED;
            } else {
                // Someone resolved it between the read above and this update.
                // Re-read and answer the same way a sequential replay would:
                // the identical resolution is a retry, a different one is a
                // conflict. This used to throw IllegalStateException, which
                // reached the client as a 500 in 10 of 12 measured rounds --
                // for a duplicate delivery of a resolution, which is the single
                // most ordinary thing a retrying incident pipeline does.
                IncidentEvent now = repo.findIncident(dto.id()).orElseThrow(
                        () -> new IllegalStateException("incident vanished: " + dto.id()));
                if (event.resolvedAt().equals(now.resolvedAt())) {
                    disposition = IngestDtos.Disposition.DUPLICATE;
                } else {
                    throw new ConflictingReplay("incident " + dto.id()
                            + " was resolved concurrently at " + now.resolvedAt()
                            + "; a published restore time cannot be moved");
                }
            }
        }

        return new IngestDtos.IngestAccepted(
                dto.id(), disposition, warningsForIncident(event, dto));
    }

    private static List<IngestDtos.Warning> warningsForIncident(
            IncidentEvent event, IngestDtos.IncidentDto dto) {
        List<IngestDtos.Warning> warnings = new ArrayList<>();
        if (!event.isPlausible()) {
            warnings.add(new IngestDtos.Warning(
                    "suspect-incident-ordering", "resolvedAt",
                    "resolvedAt " + dto.resolvedAt() + " precedes detectedAt " + dto.detectedAt()
                            + "; excluded from time to restore, counted by data_quality.suspect_incidents"));
        }
        return warnings;
    }

    private static boolean differsOnlyByOutcome(DeploymentEvent stored, DeploymentEvent incoming) {
        return stored.service().equals(incoming.service())
                && stored.environment().equals(incoming.environment())
                && stored.deployedAt().equals(incoming.deployedAt())
                && stored.changes().equals(incoming.changes());
    }

    private static boolean differsOnlyByResolution(IncidentEvent stored, IncidentEvent incoming) {
        return stored.service().equals(incoming.service())
                && java.util.Objects.equals(stored.causedByCommitSha(), incoming.causedByCommitSha())
                && stored.detectedAt().equals(incoming.detectedAt());
    }

    /**
     * Implausible input is stored and warned about, never rejected.
     *
     * <p>ADR 0003 at the HTTP boundary. A 4xx here would lose a real
     * deployment, and deployment frequency would silently fall -- the
     * validation written to protect a metric becoming the thing that corrupts
     * it. The warning exists so the producing pipeline sees it at the moment it
     * happens, in addition to the aggregate data-quality signal.
     */
    private List<IngestDtos.Warning> warningsFor(DeploymentEvent e) {
        List<IngestDtos.Warning> out = new ArrayList<>();
        for (int i = 0; i < e.changes().size(); i++) {
            Change c = e.changes().get(i);
            if (c.authoredAt().isAfter(e.deployedAt())) {
                out.add(new IngestDtos.Warning(
                        "suspect-author-date", "changes[" + i + "]",
                        "authoredAt " + c.authoredAt() + " is after deployedAt " + e.deployedAt()
                                + "; excluded from lead time, counted by data_quality.suspect_changes"));
            }
        }
        return out;
    }

    /**
     * Refuses text that cannot survive being stored.
     *
     * <p>An unpaired surrogate is not representable in UTF-8, and both the
     * digest and PostgreSQL replace it -- so two payloads differing only there
     * hash identically AND store identically. Accepting one and answering
     * {@code STORED} would report having recorded something other than what was
     * sent, which is the failure this module is about.
     *
     * <p>This is not in tension with ADR 0003. That decision governs input
     * whose <em>values</em> are implausible -- a commit authored after its own
     * deployment -- which is quarantined and counted rather than rejected,
     * because dropping it would lose a real deployment. This is malformed
     * <em>encoding</em>, the same class as the unknown-field rejection: there is
     * no coherent event here to preserve.
     */
    private static void rejectIllFormed(String field, String v) {
        if (v == null) {
            return;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= v.length() || !Character.isLowSurrogate(v.charAt(i + 1))) {
                    throw new IllegalArgumentException(
                            field + " contains an unpaired surrogate at index " + i
                                    + "; it cannot be stored or digested faithfully");
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(
                        field + " contains an unpaired surrogate at index " + i
                                + "; it cannot be stored or digested faithfully");
            }
        }
    }

    /**
     * Truncated to what Postgres can actually hold.
     *
     * <p>TIMESTAMPTZ is microsecond-precision, so a nanosecond-precision Instant
     * is not stored as submitted. Normalising at the boundary keeps three things
     * that must agree in agreement: the digest, the stored row, and the value
     * read back for comparison. Without it, a retry differing only in
     * nanoseconds hashes differently and then fails an equality check against a
     * row that lost them, and the warnings computed at ingest are computed
     * against different values than the data-quality signal computed on read.
     */
    private static Instant storable(Instant t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * A canonical encoding of the payload, deliberately not {@code toString()}.
     *
     * <p>A record's {@code toString()} separates fields with {@code ", name="},
     * and nothing stops that sequence appearing inside a field value. Two
     * genuinely different deployments therefore render identically:
     *
     * <pre>
     *   A: service = "payments, environment=production", environment = "staging"
     *   B: service = "payments", environment = "production, environment=staging"
     * </pre>
     *
     * <p>Both produce the same string and the same digest. Whichever arrived
     * second was treated as a retry of the first, discarded, and answered
     * {@code 201 Created} with {@code stored: true} -- a deployment to
     * production silently dropped while its producer was told it was recorded.
     * Verified against the running service before this was changed.
     *
     * <p>Every field is written as {@code |length|value}, so a value cannot
     * forge a delimiter: the encoding is injective over well-formed input, and
     * ill-formed input is refused before it gets here (see
     * {@link #rejectIllFormed}). The unqualified claim this comment used to
     * make -- "injective regardless of content" -- was false, because the
     * digest is taken over encoded bytes and every unpaired surrogate encodes
     * to the same replacement byte. {@code CanonicalEncodingTest} now asserts
     * injectivity over an adversarial corpus rather than leaving it asserted
     * here in prose.
     *
     * <p>The digest is over bytes, never over a human-readable rendering, and
     * the version tag means a future change to this encoding invalidates old
     * hashes loudly rather than silently comparing across two schemes.
     */
    static String canonical(IngestDtos.DeploymentDto d) {
        StringBuilder sb = new StringBuilder("deployment/v1");
        field(sb, d.id());
        field(sb, d.service());
        field(sb, d.environment());
        field(sb, storable(d.deployedAt()).toString());
        field(sb, d.outcome());
        sb.append("|n=").append(d.changes().size());
        for (IngestDtos.ChangeDto c : d.changes()) {
            field(sb, c.commitSha());
            field(sb, storable(c.authoredAt()).toString());
        }
        return sb.toString();
    }

    static String canonical(IngestDtos.IncidentDto d) {
        StringBuilder sb = new StringBuilder("incident/v1");
        field(sb, d.id());
        field(sb, d.service());
        field(sb, d.causedByCommitSha());
        field(sb, storable(d.detectedAt()).toString());
        Instant resolved = storable(d.resolvedAt());
        field(sb, resolved == null ? null : resolved.toString());
        return sb.toString();
    }

    /** {@code |length|value}; null is {@code |null|}, which no length can spell. */
    private static void field(StringBuilder sb, String v) {
        if (v == null) {
            sb.append("|null|");
            return;
        }
        sb.append('|').append(v.length()).append('|').append(v);
    }

    private static String hash(String canonical) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
