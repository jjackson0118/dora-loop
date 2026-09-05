package io.github.jjackson0118.doraloop.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The idempotency digest decides whether two payloads are the same event. If
 * two different events can encode identically, the second is answered
 * {@code 200 DUPLICATE} and silently discarded -- a deployment lost while its
 * producer is told it was already recorded.
 *
 * <p>This class exists because the test that was supposed to cover that did
 * not. {@code aSeparatorInsideAValueCannotForgeAPayloadMatch} forges
 * {@code ", environment="}, which is the separator of the <em>previous</em>
 * implementation ({@code record.toString()}). No pipe-delimited encoding ever
 * emits that sequence, so that test returns 409 under any encoding at all --
 * including one with no injectivity whatsoever. Deleting the length prefix
 * entirely left it green. It pinned the bug that was fixed instead of the
 * property that replaced it, which makes it a test that certifies "not the old
 * bug" and nothing more.
 *
 * <p>So the property is asserted directly here: every distinct payload in an
 * adversarial corpus must produce a distinct encoding. A corpus is checked
 * pairwise rather than a handful of hand-picked cases, because the failure mode
 * is a collision nobody thought to look for.
 */
class CanonicalEncodingTest {

    private static final Instant T = Instant.parse("2026-09-05T12:00:00Z");
    private static final Instant A = Instant.parse("2026-09-05T10:00:00Z");

    // --- deployments -------------------------------------------------------

    @Test
    @DisplayName("no two distinct deployments share an encoding")
    void deploymentEncodingIsInjective() {
        Map<String, IngestDtos.DeploymentDto> corpus = new LinkedHashMap<>();

        // The delimiter itself, in every field that is concatenated. This is
        // the family the length prefix exists to defeat, and the family the
        // old test never touched.
        corpus.put("pipe-split-service", deployment("e1", "pay|3|abc", "zz", "SUCCESS", changes("aaa", A)));
        corpus.put("pipe-split-env", deployment("e1", "pay", "3|abc|zz", "SUCCESS", changes("aaa", A)));
        corpus.put("pipe-in-sha", deployment("e1", "pay", "zz", "SUCCESS", changes("aa|1|a", A)));

        // A length prefix is a decimal run, so values that are themselves
        // digits can imitate one.
        corpus.put("digits-a", deployment("e2", "12", "3", "SUCCESS", List.of()));
        corpus.put("digits-b", deployment("e2", "1", "23", "SUCCESS", List.of()));
        corpus.put("digits-c", deployment("e2", "123", "", "SUCCESS", List.of()));
        corpus.put("digits-d", deployment("e2", "", "123", "SUCCESS", List.of()));

        // Empty is not absent.
        corpus.put("empty-service", deployment("e3", "", "prod", "SUCCESS", List.of()));
        corpus.put("empty-env", deployment("e3", "prod", "", "SUCCESS", List.of()));

        // The literal text a null is encoded as must not be forgeable by a
        // value that happens to spell it.
        corpus.put("literal-null-service", deployment("e4", "null", "prod", "SUCCESS", List.of()));
        corpus.put("literal-null-env", deployment("e4", "prod", "null", "SUCCESS", List.of()));

        // The changes list is a payload dimension in its own right. Dropping it
        // from the encoding entirely used to leave the whole suite green.
        corpus.put("no-changes", deployment("e5", "s", "prod", "SUCCESS", List.of()));
        corpus.put("one-change", deployment("e5", "s", "prod", "SUCCESS", changes("aaa", A)));
        corpus.put("other-change", deployment("e5", "s", "prod", "SUCCESS", changes("bbb", A)));
        corpus.put("two-changes", deployment("e5", "s", "prod", "SUCCESS", changes("aaa", A, "bbb", A)));
        corpus.put("two-reversed", deployment("e5", "s", "prod", "SUCCESS", changes("bbb", A, "aaa", A)));
        corpus.put("same-sha-twice", deployment("e5", "s", "prod", "SUCCESS", changes("aaa", A, "aaa", A)));

        // A change count marker must not be confusable with a field, and the
        // boundary between one long change and two short ones must be visible.
        corpus.put("concat-shas", deployment("e6", "s", "prod", "SUCCESS", changes("aaabbb", A)));
        corpus.put("split-shas", deployment("e6", "s", "prod", "SUCCESS", changes("aaa", A, "bbb", A)));

        // Fields that differ only by which field they are.
        corpus.put("swap-a", deployment("e7", "alpha", "beta", "SUCCESS", List.of()));
        corpus.put("swap-b", deployment("e7", "beta", "alpha", "SUCCESS", List.of()));

        // Every remaining scalar must participate.
        corpus.put("outcome-differs", deployment("e8", "s", "prod", "ROLLED_BACK", List.of()));
        corpus.put("outcome-base", deployment("e8", "s", "prod", "SUCCESS", List.of()));
        corpus.put("id-differs", deployment("e9", "s", "prod", "SUCCESS", List.of()));
        corpus.put("deployedAt-differs", new IngestDtos.DeploymentDto(
                "e8", "s", "prod", T.plusSeconds(1), "SUCCESS", List.of()));
        corpus.put("authoredAt-differs", deployment("e10", "s", "prod", "SUCCESS", changes("aaa", A.plusSeconds(1))));
        corpus.put("authoredAt-base", deployment("e10", "s", "prod", "SUCCESS", changes("aaa", A)));

        assertNoCollisions(corpus, IngestService::canonical);
    }

    // --- incidents ---------------------------------------------------------

    @Test
    @DisplayName("no two distinct incidents share an encoding")
    void incidentEncodingIsInjective() {
        Map<String, IngestDtos.IncidentDto> corpus = new LinkedHashMap<>();

        corpus.put("pipe-split-service", incident("i1", "pay|3|abc", "zz", T, null));
        corpus.put("pipe-split-blame", incident("i1", "pay", "3|abc|zz", T, null));

        // An absent blame commit and one whose text is "null" are different
        // events, and the encoding writes both.
        corpus.put("null-blame", incident("i2", "s", null, T, null));
        corpus.put("literal-null-blame", incident("i2", "s", "null", T, null));
        corpus.put("empty-blame", incident("i2", "s", "", T, null));

        // Open and resolved are the transition the whole correction path turns
        // on; they must never share a digest.
        corpus.put("open", incident("i3", "s", "abc", T, null));
        corpus.put("resolved", incident("i3", "s", "abc", T, T.plusSeconds(1800)));
        corpus.put("resolved-later", incident("i3", "s", "abc", T, T.plusSeconds(3600)));

        corpus.put("detected-differs", incident("i4", "s", "abc", T.plusSeconds(1), null));
        corpus.put("detected-base", incident("i4", "s", "abc", T, null));
        corpus.put("id-differs", incident("i5", "s", "abc", T, null));

        assertNoCollisions(corpus, IngestService::canonical);
    }

    /**
     * A deployment and an incident that happen to line up field-for-field must
     * not share a digest, because both are looked up by id in their own table
     * but the encoding is a single namespace.
     */
    @Test
    void deploymentsAndIncidentsDoNotShareAnEncodingSpace() {
        String d = IngestService.canonical(deployment("x", "s", "prod", "SUCCESS", List.of()));
        String i = IngestService.canonical(incident("x", "s", "prod", T, null));
        assertThat(d).isNotEqualTo(i);
    }

    /**
     * The corpus is only evidence if the encoding is actually capable of
     * colliding when it is wrong.
     *
     * <p>This is the negative control for the test above. It reimplements the
     * defective encoding -- a bare separator with no length prefix -- and
     * asserts the corpus DOES collide under it. Without this, a
     * {@code canonical} that returned a fresh UUID every call would satisfy
     * every other assertion in this class.
     */
    @Test
    void theCorpusCanDetectALostLengthPrefix() {
        List<String> encoded = new ArrayList<>();
        for (IngestDtos.DeploymentDto d : List.of(
                deployment("e1", "pay|3|abc", "zz", "SUCCESS", changes("aaa", A)),
                deployment("e1", "pay", "3|abc|zz", "SUCCESS", changes("aaa", A)))) {
            StringBuilder sb = new StringBuilder("deployment/v1");
            for (String v : List.of(d.id(), d.service(), d.environment(),
                    d.deployedAt().toString(), d.outcome())) {
                sb.append('|').append(v);          // no length prefix -- the defect
            }
            sb.append("|n=").append(d.changes().size());
            for (IngestDtos.ChangeDto c : d.changes()) {
                sb.append('|').append(c.commitSha()).append('|').append(c.authoredAt());
            }
            encoded.add(sb.toString());
        }
        assertThat(encoded.get(0))
                .as("without the length prefix these two distinct deployments collide")
                .isEqualTo(encoded.get(1));

        // And the real encoding keeps them apart.
        assertThat(IngestService.canonical(deployment("e1", "pay|3|abc", "zz", "SUCCESS", changes("aaa", A))))
                .isNotEqualTo(IngestService.canonical(
                        deployment("e1", "pay", "3|abc|zz", "SUCCESS", changes("aaa", A))));
    }

    // --- helpers -----------------------------------------------------------

    private static <T> void assertNoCollisions(Map<String, T> corpus,
                                               java.util.function.Function<T, String> encode) {
        // A corpus that accidentally contains two equal payloads would report a
        // collision that is not one, so the inputs are checked distinct first.
        List<String> names = List.copyOf(corpus.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                T a = corpus.get(names.get(i));
                T b = corpus.get(names.get(j));
                assertThat(a).as("corpus entries %s and %s must be different payloads",
                        names.get(i), names.get(j)).isNotEqualTo(b);
            }
        }

        Map<String, String> seen = new LinkedHashMap<>();
        List<String> collisions = new ArrayList<>();
        for (Map.Entry<String, T> e : corpus.entrySet()) {
            String enc = encode.apply(e.getValue());
            String previous = seen.putIfAbsent(enc, e.getKey());
            if (previous != null) {
                collisions.add(previous + " collides with " + e.getKey() + "  ->  " + enc);
            }
        }
        assertThat(corpus).as("an empty corpus proves nothing").isNotEmpty();
        assertThat(collisions).as("distinct payloads sharing an encoding").isEmpty();
    }

    private static IngestDtos.DeploymentDto deployment(
            String id, String service, String environment, String outcome,
            List<IngestDtos.ChangeDto> changes) {
        return new IngestDtos.DeploymentDto(id, service, environment, T, outcome, changes);
    }

    private static IngestDtos.IncidentDto incident(
            String id, String service, String blame, Instant detected, Instant resolved) {
        return new IngestDtos.IncidentDto(id, service, blame, detected, resolved);
    }

    private static List<IngestDtos.ChangeDto> changes(Object... shaThenInstant) {
        List<IngestDtos.ChangeDto> out = new ArrayList<>();
        for (int i = 0; i < shaThenInstant.length; i += 2) {
            out.add(new IngestDtos.ChangeDto(
                    (String) shaThenInstant[i], (Instant) shaThenInstant[i + 1]));
        }
        return out;
    }
}
