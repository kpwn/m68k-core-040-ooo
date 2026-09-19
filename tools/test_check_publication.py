import unittest
from pathlib import Path
import subprocess
import sys
import tempfile
from check_publication import reason


class PublicationTest(unittest.TestCase):
    def test_reject_artifacts(self):
        for path in ("fpga.bit", "files/apple.rom", "files/image.bin", "synth/run.dcp",
                     "x.o", "x.a", "run.log", "waves.fst", "target/foo.scala",
                     "tools/musashi/.build/m68kcpu.c", ".agent-reservation", ".env.local",
                     "run_old_experiment.sh", "synth/probe_example/ladder.txt"):
            with self.subTest(path=path):
                self.assertIsNotNone(reason(path))

    def test_preserve_source_and_evidence(self):
        for path in ("src/test/resources/example.s", "src/test/resources/example.timeout",
                     "docs/BUG_example.md", "docs/superpowers/specs/design.md",
                     "synth/probe_example/whatif_summary.txt", "synth/gate.summary",
                     "tools/socket/fullcore_ports.golden", "tools/run_microbench.sh",
                     "tools/musashi/patches/lockstep.patch"):
            with self.subTest(path=path):
                self.assertIsNone(reason(path))

    def test_explicit_submodule_boundary(self):
        self.assertIsNone(reason("tools/musashi/musashi", "160000"))
        self.assertIsNotNone(reason("other/dependency", "160000"))

    def test_cli_checks_index_not_untracked_outputs(self):
        checker = Path(__file__).with_name("check_publication.py").resolve()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "-q", directory], check=True)
            (root / "local.log").write_text("local build output\n")
            result = subprocess.run([sys.executable, str(checker)], cwd=root,
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            subprocess.run(["git", "add", "local.log"], cwd=root, check=True)
            result = subprocess.run([sys.executable, str(checker)], cwd=root,
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn("local.log", result.stderr)


if __name__ == "__main__":
    unittest.main()
