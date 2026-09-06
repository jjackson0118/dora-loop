#!/usr/bin/env python3
"""Negative controls for commit-range event construction; no target writes."""
import unittest
from event_payload import payload

A, B, C, D = (c * 40 for c in "abcd")
HISTORY = [
    {"sha": A, "parents": [], "authoredAt": "2026-09-01T01:00:00+00:00"},
    {"sha": B, "parents": [A], "authoredAt": "2026-09-02T01:00:00+00:00"},
    {"sha": C, "parents": [A], "authoredAt": "2026-09-03T01:00:00+00:00"},
    {"sha": D, "parents": [B, C], "authoredAt": "2026-09-04T01:00:00+00:00"},
]


class PayloadTest(unittest.TestCase):
    def build(self, history=HISTORY, previous=A[:7], head=D,
              outcome="SUCCESS", verification="VERIFIED"):
        return payload(history, previous, head, "repo:run:12:attempt:1",
                       "2026-09-06T12:00:00Z", outcome, verification)

    def test_entire_merge_range_includes_branch_changes(self):
        p = self.build()
        self.assertEqual([B, C, D], [c["commitSha"] for c in p["changes"]])
        self.assertEqual(HISTORY[1]["authoredAt"], p["changes"][0]["authoredAt"])

    def test_previous_release_excludes_its_ancestors(self):
        self.assertEqual([C, D], [c["commitSha"] for c in self.build(previous=B)["changes"]])

    def test_no_new_commits_is_legitimate(self):
        self.assertEqual([], self.build(previous=D)["changes"])

    def test_missing_baseline_is_not_a_head_only_fallback(self):
        with self.assertRaises(ValueError): self.build(previous="e" * 40)

    def test_shallow_history_is_not_accepted(self):
        with self.assertRaises(ValueError): self.build(history=HISTORY[1:], previous=B)

    def test_diverged_previous_is_not_an_empty_success(self):
        with self.assertRaises(ValueError): self.build(previous=C, head=B)

    def test_input_order_does_not_change_replay(self):
        self.assertEqual(self.build(), self.build(history=list(reversed(HISTORY))))

    def test_unverified_is_orthogonal_to_outcome(self):
        p = self.build(verification="UNVERIFIED")
        self.assertEqual("SUCCESS", p["outcome"])
        self.assertEqual("UNVERIFIED", p["verification"])

    def test_future_author_date_is_preserved_for_quality_signal(self):
        h = [dict(x) for x in HISTORY]
        h[-1]["authoredAt"] = "2030-01-01T00:00:00+00:00"
        self.assertEqual(h[-1]["authoredAt"], self.build(history=h)["changes"][-1]["authoredAt"])

    def test_bad_outcome_cannot_be_published(self):
        with self.assertRaises(ValueError): self.build(outcome="UNKNOWN")


if __name__ == "__main__":
    unittest.main()
