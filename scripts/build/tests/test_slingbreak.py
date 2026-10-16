import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.build.lib import doctor


class SlingBreakSourceTest(unittest.TestCase):
    def setUp(self):
        scratch = Path(__file__).resolve().parents[3] / "agent-tmp"
        scratch.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.values = {}
        root_patch = patch.object(doctor, "repo_root", return_value=self.root)
        properties_patch = patch.object(doctor, "_parse_gradle_properties", return_value=self.values)
        root_patch.start()
        properties_patch.start()
        self.addCleanup(root_patch.stop)
        self.addCleanup(properties_patch.stop)

    def bundle(self, root, launcher="src/ui/launcher-mode.js"):
        root.mkdir(parents=True, exist_ok=True)
        (root / "index.html").write_text("<html></html>", encoding="utf-8")
        script = root / launcher
        script.parent.mkdir(parents=True, exist_ok=True)
        script.write_text("// launcher bridge", encoding="utf-8")
        return root

    def test_current_repository_override(self):
        runtime = self.bundle(self.root / "checkout/game")
        self.values["slingBreak.sourceDir"] = "checkout"
        state = doctor._sling_break_state()
        self.assertEqual("local", state.mode)
        self.assertTrue(state.valid)
        self.assertIn(str(runtime), state.detail)

    def test_direct_game_override(self):
        runtime = self.bundle(self.root / "checkout/game")
        self.values["slingBreak.sourceDir"] = str(runtime)
        self.assertTrue(doctor._sling_break_state().valid)

    def test_legacy_override(self):
        self.bundle(self.root / "legacy", "launcher-mode.js")
        self.values["slingBreak.sourceDir"] = "legacy"
        self.assertEqual(doctor.OK, doctor.check_slingbreak().status)

    def test_prefers_game_over_valid_root(self):
        source = self.bundle(self.root / "checkout", "launcher-mode.js")
        runtime = self.bundle(source / "game")
        self.assertEqual(runtime, doctor._sling_break_bundle_dir(source))

    def test_incomplete_game_falls_back_to_root(self):
        source = self.bundle(self.root / "checkout", "launcher-mode.js")
        (source / "game").mkdir()
        (source / "game/index.html").touch()
        self.assertEqual(source, doctor._sling_break_bundle_dir(source))

    def test_incomplete_runtime_is_rejected(self):
        for relative in ("index.html", "src/ui/launcher-mode.js"):
            with self.subTest(relative=relative):
                source = self.root / relative.replace("/", "-")
                file = source / relative
                file.parent.mkdir(parents=True)
                file.touch()
                self.assertIsNone(doctor._sling_break_bundle_dir(source))

    def test_invalid_override_does_not_fall_back_to_cache(self):
        self.bundle(doctor._sling_break_cache_dir())
        self.values["slingBreak.sourceDir"] = "missing"
        check = doctor.check_slingbreak()
        self.assertEqual(doctor.FAIL, check.status)
        self.assertIn("src/ui/launcher-mode.js", check.findings[0].text)

    def test_current_and_legacy_caches_are_recognized(self):
        cache = doctor._sling_break_cache_dir()
        for launcher in ("src/ui/launcher-mode.js", "launcher-mode.js"):
            with self.subTest(launcher=launcher):
                self.bundle(cache, launcher)
                self.assertEqual("cached", doctor._sling_break_state().mode)
                (cache / launcher).unlink()

    def test_cache_can_contain_repository_layout(self):
        self.bundle(doctor._sling_break_cache_dir() / "game")
        self.assertEqual("cached", doctor._sling_break_state().mode)

    def test_missing_cache_requires_remote_fetch(self):
        state = doctor._sling_break_state()
        self.assertEqual("remote", state.mode)
        self.assertTrue(state.valid)
        self.assertIn("/archive/refs/heads/master.tar.gz", state.url)


if __name__ == "__main__":
    unittest.main()
