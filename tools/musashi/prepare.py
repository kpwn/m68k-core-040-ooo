"""Prepare a patched, disposable oracle without modifying the upstream submodule."""
from pathlib import Path
import hashlib
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
PIN = "313ebf1bd9f4d0d93341eb5ce21fd8a119e9dbdd"


def prepare():
    upstream = HERE / "musashi"
    if not (upstream / ".git").exists():
        raise SystemExit("Initialize Musashi first: git submodule update --init tools/musashi/musashi")
    revision = subprocess.check_output(
        ["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip()
    if revision != PIN:
        raise SystemExit(f"Expected Musashi {PIN}, found {revision}; update the submodule")
    if subprocess.check_output(
            ["git", "-C", str(upstream), "status", "--porcelain"], text=True).strip():
        raise SystemExit("Musashi submodule is dirty; preserve your changes before building")
    patch = HERE / "patches" / "lockstep.patch"
    fingerprint = hashlib.sha256(PIN.encode() + patch.read_bytes() +
                                 Path(__file__).read_bytes()).hexdigest()
    build = HERE / ".build"
    stamp = build / ".prepared"
    if stamp.exists() and stamp.read_text() == fingerprint:
        return
    # Only this script's explicitly named, disposable build directory is removed.
    if build.is_symlink():
        raise SystemExit("Refusing symlink at .build")
    if build.exists():
        shutil.rmtree(build)
    shutil.copytree(upstream, build, ignore=shutil.ignore_patterns(".git"))
    subprocess.run(["git", "apply", "--check", str(patch)], cwd=build, check=True)
    subprocess.run(["git", "apply", str(patch)], cwd=build, check=True)
    stamp.write_text(fingerprint)


if __name__ == "__main__":
    prepare()
