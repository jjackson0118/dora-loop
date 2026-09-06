"""Failure injection around the actual rollback's filesystem publication."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(os.environ.get("ROLLBACK_SCRIPT", Path(__file__).with_name("remote-rollback.sh")))


class AtomicRollbackTest(unittest.TestCase):
    def run_case(self, fault):
        with tempfile.TemporaryDirectory(prefix="rollback-atomic-") as temporary:
            root = Path(temporary)
            app = root / "app"
            previous = app / "releases/aaaa"
            current = app / "releases/bbbb"
            previous.mkdir(parents=True)
            current.mkdir()
            (previous / "app.jar").write_bytes(b"previous")
            (current / "app.jar").write_bytes(b"current")
            for name in ("current", "last-good"):
                (app / name).symlink_to(current)
            script = root / "rollback.sh"
            source = SCRIPT.read_text()
            self.assertEqual(source.count("APP_DIR=/opt/dora-loop"), 1)
            script.write_text(source.replace("APP_DIR=/opt/dora-loop", "APP_DIR=" + str(app)))
            bins = root / "bin"
            bins.mkdir()
            (bins / "sudo").write_text('#!/bin/sh\nprintf "called\\n" >> "$SERVICE_CALLS"\n')
            (bins / "curl").write_text('#!/bin/sh\nprintf \'{"build":{"sha":"aaaa"}}\\n\'\n')
            # A staging failure after ln created its link must not publish it.
            # A failed rename must leave the currently selected release intact.
            (bins / "ln").write_text('''#!/bin/bash
"$REAL_LN" "$@" || exit $?
[ "$FAULT" != stage ] || exit 73
''')
            (bins / "mv").write_text('''#!/bin/bash
[ "$FAULT" != publish ] || exit 74
exec "$REAL_MV" "$@"
''')
            for path in bins.iterdir():
                path.chmod(0o755)
            calls = root / "calls"
            env = dict(os.environ, PATH=str(bins) + os.pathsep + os.environ["PATH"],
                       REAL_LN=shutil.which("ln"), REAL_MV=shutil.which("mv"),
                       FAULT=fault, SERVICE_CALLS=str(calls))
            result = subprocess.run(["bash", str(script), str(previous), "", str(current)],
                                    env=env, capture_output=True, text=True, timeout=10)
            if fault:
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual((app / "current").readlink(), current,
                                 "failed publication changed the serving release")
                self.assertEqual((app / "last-good").readlink(), current)
                self.assertFalse(calls.exists(), "failed publication restarted the service")
            else:
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual((app / "current").readlink(), previous)
                self.assertEqual((app / "last-good").readlink(), previous)
                self.assertTrue(calls.exists())

    def test_failed_staging_preserves_current(self):
        self.run_case("stage")

    def test_failed_publication_preserves_current(self):
        self.run_case("publish")

    def test_success_publishes_and_restarts(self):
        self.run_case("")


if __name__ == "__main__":
    unittest.main()
