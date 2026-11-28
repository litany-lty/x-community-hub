import importlib.util
import io
import os
from contextlib import redirect_stdout
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("run_environment", ROOT / "scripts/run-environment.py")
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)


class RunEnvironmentTest(unittest.TestCase):
    def test_production_respects_both_controls_and_preview_disables_publishing(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            jar = root / "fixture.jar"
            jar.write_bytes(b"launcher argument fixture")
            runtime = root / "production"
            runtime.mkdir()
            for mode, configured in (("production", "false"), ("production", "true"), ("preview", "true"), ("local", "true")):
                with self.subTest(mode=mode, configured=configured):
                    argv = ["run-environment.py", mode, "--jar", str(jar)]
                    if mode == "production":
                        argv += ["--runtime-dir", str(runtime)]
                    env = {"X_SCHEDULER_ENABLED": configured, "X_AUTO_REPOST_ENABLED": configured,
                           "X_OAUTH_CLIENT_ID": "test-fixture"}
                    with patch.object(sys, "argv", argv), patch.dict(os.environ, env, clear=True), \
                            patch.object(launcher, "PROJECT", root), \
                            patch.object(launcher, "RUNTIMES", root / "runtimes"), \
                            patch.object(launcher, "java17", return_value="fixture-java"), \
                            patch.object(os, "chdir"), patch.object(os, "execve") as execute:
                        with redirect_stdout(io.StringIO()):
                            launcher.main()
                    _, command, passed_env = execute.call_args.args
                    for key in ("xops.scheduler-enabled", "xops.auto-repost-enabled", "xops.top10-silent-history-enabled",
                                "xops.content-assistant-background-collection-enabled"):
                        flags = [arg for arg in command if arg.startswith("--" + key + "=")]
                        self.assertEqual([] if mode == "production" else ["--" + key + "=false"], flags)
                    self.assertEqual(configured, passed_env["X_SCHEDULER_ENABLED"])
                    self.assertEqual(configured, passed_env["X_AUTO_REPOST_ENABLED"])


if __name__ == "__main__":
    unittest.main()
