#!/usr/bin/env python3
"""Check the Git index for generated/private artifacts; never scans submodules.

This is a packaging guard, not a secrets scanner or a licensing clearance.
Stage intended changes before running it. No files are changed.
"""
from pathlib import PurePosixPath
import subprocess
import sys

ARTIFACT_SUFFIXES = {
    ".bit", ".ltx", ".dcp", ".bin", ".rom", ".o", ".a", ".class", ".jar",
    ".log", ".jou", ".rpt", ".out", ".stdout", ".fst", ".vcd", ".pyc",
}
BUILD_DIRS = {
    "target", "simWorkspace", "generated", "__pycache__", ".build", ".Xil",
    ".superpowers", ".bloop", ".bsp", ".metals",
}


def reason(path, mode="100644"):
    p = PurePosixPath(path)
    if mode == "160000":
        return None if path == "tools/musashi/musashi" else "unexpected submodule"
    if any(part in BUILD_DIRS for part in p.parts):
        return "generated build/runtime directory"
    if p.suffix.lower() in ARTIFACT_SUFFIXES:
        return "binary, firmware or generated artifact"
    if p.name == ".agent-reservation" or p.name == ".env" or p.name.startswith(".env."):
        return "local runtime/configuration file"
    if len(p.parts) == 1 and p.name.startswith("run_") and p.suffix == ".sh":
        return "root-level experiment launcher; maintain reusable tools under tools/"
    if (len(p.parts) == 3 and p.parts[0] == "synth"
            and p.parts[1].startswith("probe_") and p.suffix == ".txt"
            and not p.name.endswith("summary.txt")):
        return "raw timing probe dump (retain a concise summary instead)"
    return None


def main():
    records = subprocess.check_output(["git", "ls-files", "--stage", "-z"])
    failures = []
    for record in records.split(b"\0"):
        if not record:
            continue
        metadata, raw_path = record.split(b"\t", 1)
        mode, _, stage = metadata.decode().split()
        path = raw_path.decode("utf-8", errors="surrogateescape")
        problem = "unmerged index entry" if stage != "0" else reason(path, mode)
        if problem:
            failures.append(f"{path}: {problem}")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("Publication index check passed (submodule contents excluded).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
