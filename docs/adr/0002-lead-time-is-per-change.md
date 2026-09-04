# 2. Lead time is measured per change, not per deployment

Date: 2026-09-04
Status: Accepted

## Context

The intended pipeline integration was `git log -1 --format=%aI`, carried
through to the deployment event as a single `commitAuthoredAt`.

`-1` is the problem. On a squash or merge commit it returns the merge itself,
whose author date is merge time, producing a lead time near zero. On a
fast-forwarded branch of forty commits it returns the last one -- so a
two-week branch is measured from its final "fix typo" commit and the two weeks
are invisible.

The error is not merely wrong, it is **biased toward looking good**. A metric
that fails in the flattering direction is the failure mode this project exists
to argue against.

## Decision

A `DeploymentEvent` carries `List<Change>` -- every commit in the range since
the previous deployment, each with its own author date. Lead time emits one
observation **per change**, which is what DORA defines: lead time for
*changes*, not for deployments.

`%aI` (author date) rather than `%cI` (committer date) is retained
deliberately: rebase rewrites the committer date and preserves the author date,
so `%aI` survives the operation that would otherwise reset every measurement.

An empty `changes` list is legal. Redeploying an already-deployed commit is a
real deployment carrying no new change: it counts toward deployment frequency
and contributes no lead time, rather than contributing an enormous one.

## Consequences

The pipeline must track the previously deployed SHA to compute the range. That
is more integration work than reading one commit, and it means the pipeline
maintains deployment state -- which is release-management substance rather than
a reporting detail.

A long-lived branch rebased and merged after months yields a very large lead
time. This is correct: the change did take that long to reach production. The
median absorbs the outlier, which is why the median was chosen over the mean.
