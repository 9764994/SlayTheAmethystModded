import subprocess
import unittest
from unittest.mock import MagicMock, patch

from scripts.build.lib import cli, commands


class ReleaseBuildTest(unittest.TestCase):
    def test_release_reuses_native_cache_unless_explicitly_cleaned(self):
        for command in ("release", "release-fast", "release-full"):
            for clean in (False, True):
                with self.subTest(command=command, clean=clean):
                    args = [command] + (["--clean-native-cache"] if clean else [])
                    with patch.object(cli, "release_build") as build:
                        self.assertEqual(0, cli.main(args))
                    self.assertEqual(not clean, build.call_args.kwargs["skip_native_cache_cleanup"])

    def test_preflight_does_not_delete_shared_native_cache(self):
        with patch.object(commands, "release_build") as build:
            commands.local_release_check("", "")
        self.assertEqual(2, build.call_count)
        self.assertTrue(all(call.kwargs["skip_native_cache_cleanup"] for call in build.call_args_list))

    def test_gradle_reports_waiting_and_enables_profile(self):
        process = MagicMock()
        process.wait.side_effect = [subprocess.TimeoutExpired("gradle", 60), 0]
        with patch.object(commands.subprocess, "Popen") as popen, patch("builtins.print") as output:
            popen.return_value.__enter__.return_value = process
            commands.run_gradle([":app:assembleRelease"])
        self.assertIn("--profile", popen.call_args.args[0])
        self.assertTrue(any("Still running" in call.args[0] for call in output.call_args_list))

    def test_gradle_failure_is_not_swallowed(self):
        process = MagicMock()
        process.wait.return_value = 1
        with patch.object(commands.subprocess, "Popen") as popen:
            popen.return_value.__enter__.return_value = process
            with self.assertRaisesRegex(RuntimeError, "release failed"):
                commands.run_gradle([":app:assembleRelease"], failure_message="release failed")
