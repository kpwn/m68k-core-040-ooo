import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("prepare", Path(__file__).with_name("prepare.py"))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class PreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        prepare.HERE = Path(self.temp.name)
        self.upstream = prepare.HERE / "musashi"
        self.upstream.mkdir()
        self.git("init", "-q")
        (self.upstream / "sample").write_text("old\n")
        self.git("add", "sample")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "-qm", "fixture")
        prepare.PIN = self.git("rev-parse", "HEAD").strip()
        patches = prepare.HERE / "patches"
        patches.mkdir()
        (patches / "lockstep.patch").write_text(
            "--- a/sample\n+++ b/sample\n@@ -1 +1 @@\n-old\n+new\n")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.upstream), *args], text=True)

    def test_build_copy_and_idempotence(self):
        prepare.prepare()
        self.assertEqual((prepare.HERE / ".build/sample").read_text(), "new\n")
        self.assertEqual((self.upstream / "sample").read_text(), "old\n")
        self.assertEqual(self.git("status", "--porcelain"), "")
        stamp = prepare.HERE / ".build/.prepared"
        before = stamp.stat().st_mtime_ns
        prepare.prepare()
        self.assertEqual(stamp.stat().st_mtime_ns, before)

    def test_dirty_rejected(self):
        (self.upstream / "sample").write_text("user edit\n")
        with self.assertRaisesRegex(SystemExit, "dirty"):
            prepare.prepare()

    def test_patch_change_rebuilds_copy(self):
        prepare.prepare()
        patch = prepare.HERE / "patches/lockstep.patch"
        patch.write_text(patch.read_text().replace("+new", "+updated"))
        prepare.prepare()
        self.assertEqual((prepare.HERE / ".build/sample").read_text(), "updated\n")

    def test_bad_patch_leaves_no_success_stamp(self):
        patch = prepare.HERE / "patches/lockstep.patch"
        patch.write_text(patch.read_text().replace("-old", "-not present"))
        with self.assertRaises(subprocess.CalledProcessError):
            prepare.prepare()
        self.assertFalse((prepare.HERE / ".build/.prepared").exists())

    def test_wrong_revision_rejected(self):
        prepare.PIN = "0" * 40
        with self.assertRaisesRegex(SystemExit, "Expected Musashi"):
            prepare.prepare()

    def test_missing_submodule_rejected(self):
        prepare.HERE = prepare.HERE / "missing"
        with self.assertRaisesRegex(SystemExit, "submodule update"):
            prepare.prepare()


if __name__ == "__main__":
    unittest.main()
