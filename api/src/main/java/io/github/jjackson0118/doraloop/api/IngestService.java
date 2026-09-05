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

    /** Thrown when an id is reused with a different payload. */
    static final class ConflictingReplay extends RuntimeException {
        ConflictingReplay(String message) { super(message); }
    }

    @Transactional
    IngestDtos.IngestAccepted accept(IngestDtos.DeploymentDto dto) {
        Outcome outcome;
        try {
            outcome = Outcome.valueOf(dto.outcome());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "outcome must be one of SUCCESS, FAILED_ROLLOUT, ROLLED_BACK");
        }

        List<Change> changes = dto.changes().stream()
                .map(c -> new Change(c.commitSha(), c.authoredAt())).toList();
        DeploymentEvent event = new DeploymentEvent(
                dto.id(), dto.service(), dto.environment(), changes, dto.deployedAt(), outcome);

        String hash = hash(dto.toString());
        Optional<String> existing = repo.existingDeploymentHash(dto.id());
        if (existing.isPresent()) {
            // Same id, same payload: a retry. Same id, different payload: two
            // different claims about one deployment, and silently accepting
            // either one loses information.
            if (existing.get().equals(hash)) {
                return new IngestDtos.IngestAccepted(dto.id(), true, warningsFor(event));
            }
            throw new ConflictingReplay(
                    "id " + dto.id() + " already exists with a different payload");
        }
        repo.insertDeployment(event, hash);
        return new IngestDtos.IngestAccepted(dto.id(), true, warningsFor(event));
    }

    @Transactional
    IngestDtos.IngestAccepted accept(IngestDtos.IncidentDto dto) {
        IncidentEvent event = new IncidentEvent(
                dto.id(), dto.service(), dto.causedByCommitSha(),
                dto.detectedAt(), dto.resolvedAt());
        repo.upsertIncident(event, hash(dto.toString()));

        List<IngestDtos.Warning> warnings = new ArrayList<>();
        if (!event.isPlausible()) {
            warnings.add(new IngestDtos.Warning(
                    "suspect-incident-ordering", "resolvedAt",
                    "resolvedAt " + dto.resolvedAt() + " precedes detectedAt " + dto.detectedAt()
                            + "; excluded from time to restore, counted by data_quality.suspect_incidents"));
        }
        return new IngestDtos.IngestAccepted(dto.id(), true, warnings);
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
