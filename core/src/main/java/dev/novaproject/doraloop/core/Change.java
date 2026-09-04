package dev.novaproject.doraloop.core;

import java.time.Instant;
import java.util.Objects;

/**
 * One commit carried by a deployment.
 *
 * <p>{@code authoredAt} is the git <em>author</em> date, which survives rebase
 * (rebase rewrites the committer date, not the author date). It is also
 * client-supplied and therefore untrusted -- see
 * {@link DoraCalculator} for how implausible values are handled rather than
 * rejected.
 */
public record Change(String commitSha, Instant authoredAt) {
    public Change {
        Objects.requireNonNull(commitSha, "commitSha");
        Objects.requireNonNull(authoredAt, "authoredAt");
    }
}
