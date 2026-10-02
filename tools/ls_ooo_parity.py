#!/usr/bin/env python3
"""Same-source LS-OoO OFF/ON corpus and lock-step parity gate.

`--plan` is read-only. `--run` needs an expected clean commit and a new output
directory, then wraps the whole compiled/sharded run in the shared heavy lock.
The script deliberately reports existing failing tests; parity is by exact name,
not by a misleading aggregate pass count.
"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
ASM = ROOT / "src/test/resources/m68kooo-ported-tests/asm"
RESOURCES = ASM.parent
HEAVY = Path("/home/qwertyoruiop/tmp/claude-1000/-home-qwertyoruiop-m68k-core-040-ooo/"
             "9c9d6e9b-868c-4e6e-bc8a-0fc3e3ef0587/scratchpad/heavy_run.sh")
ARMS = {"off": "0", "on": "1"}
MANIFEST_TESTS = {
    "ported cache-mode sweep manifest is valid",
    "ported MMU-walk sweep manifest is valid",
}
SUMMARY_RE = re.compile(r"Tests: succeeded (\d+), failed (\d+), canceled (\d+), ignored (\d+), pending (\d+)")
ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()


def source_check(expected):
    head = git("rev-parse", "HEAD")
    if expected != head or git("status", "--porcelain"):
        raise RuntimeError(f"source changed/dirty: expected {expected}, got {head}, "
                           f"status={git('status', '--short')!r}")
    return head


def list_file(name):
    return [line.strip() for line in (RESOURCES / name).read_text().splitlines() if line.strip()]


def corpus_manifest():
    names = sorted(p.stem for p in ASM.glob("*.s"))
    cache = list_file("full-cache-sweep-list.txt")
    mmu = list_file("mmu-walk-sweep-list.txt")
    if len(names) != len(set(names)) or any(len(x) != len(set(x)) for x in (cache, mmu)):
        raise RuntimeError("duplicate corpus or sweep name")
    if not set(cache + mmu) <= set(names):
        raise RuntimeError("a sweep name is absent from the canonical asm corpus")
    expected = {f"ported: {name}" for name in names}
    expected |= {f"ported-sweep-copyback: {name}" for name in cache}
    expected |= {f"ported-sweep-mmuwalk: {name}" for name in mmu}
    expected |= MANIFEST_TESTS
    return names, cache, mmu, expected


def base_env(arm, suite):
    ls_ooo = ARMS[arm]
    env = {
        "HOME": "/home/qwertyoruiop", "USER": "qwertyoruiop",
        "LOGNAME": "qwertyoruiop", "LANG": "C.UTF-8",
        "PATH": "/home/qwertyoruiop/sbt/bin:/usr/local/bin:/usr/bin:/bin",
        "CPU_IPC_PROFILE": "throughput-v2", "CPU_DEBUG_PROFILE": "full",
        "LS_OOO_ISSUE": ls_ooo, "FUZZ_LS_OOO": ls_ooo,
        "LOCKSTEP_LS_OOO": ls_ooo, "SPEC_LOAD_WAKEUP": "0",
    }
    env["FUZZ_SHIPPING" if suite == "corpus" else "LOCKSTEP_SHIPPING"] = "1"
    return env


def classpath(out):
    log = out / "classpath.log"
    env = base_env("off", "corpus")
    env["SBT_OPTS"] = "-Xmx6g -Xss16m"
    with log.open("w") as f:
        p = subprocess.run(["/home/qwertyoruiop/sbt/bin/sbt", "-batch", "Test/compile",
                            "export Test/fullClasspath"], cwd=ROOT, env=env,
                           stdout=f, stderr=subprocess.STDOUT, check=False)
    if p.returncode:
        raise RuntimeError(f"Test/compile or classpath export failed: {log}")
    lines = log.read_text(errors="replace").splitlines()
    candidates = [line.strip() for line in lines
                  if "scala-library" in line and "test-classes" in line and ":" in line]
    if not candidates:
        raise RuntimeError(f"no exported test classpath in {log}")
    cp = candidates[-1]
    if not all(Path(entry).exists() for entry in cp.split(":")):
        raise RuntimeError("exported classpath has missing entries")
    (out / "classpath.txt").write_text(cp + "\n")
    return cp


def lockstep_names(cp, out):
    log = out / "lockstep-manifest.log"
    with log.open("w") as f:
        p = subprocess.run(["java", "-Xmx2g", "-cp", cp,
                            "m68k040.bench.LsOooParityManifest"], cwd=ROOT,
                           env=base_env("off", "lockstep"), stdout=f,
                           stderr=subprocess.STDOUT, check=False)
    if p.returncode:
        raise RuntimeError(f"lock-step manifest failed: {log}")
    names = [line.split("\t", 1)[1] for line in log.read_text().splitlines()
             if line.startswith("LS_OOO_LOCKSTEP_NAME\t")]
    if not names or len(names) != len(set(names)):
        raise RuntimeError(f"empty/duplicate lock-step manifest: {log}")
    (out / "lockstep-names.txt").write_text("\n".join(names) + "\n")
    return names


def stage_cwd(cwd):
    cwd.mkdir(parents=True)
    for item in ROOT.iterdir():
        if item.name in {".git", ".agent-reservation", "target", "simWorkspace"}:
            continue
        (cwd / item.name).symlink_to(item)


def stage_corpus(arm_dir, shards, names, cache, mmu):
    assigned = [[] for _ in range(shards)]
    for i, name in enumerate(names):
        assigned[i % shards].append(name)
    cache_set, mmu_set = set(cache), set(mmu)
    for i, subset in enumerate(assigned):
        shard = arm_dir / f"shard{i}"
        stage_cwd(shard / "cwd")
        asm = shard / "asm"
        asm.mkdir()
        for name in subset:
            (asm / f"{name}.s").symlink_to(ASM / f"{name}.s")
            timeout = ASM / f"{name}.timeout"
            if timeout.exists():
                (asm / timeout.name).symlink_to(timeout)
        for filename, members in (("cache-sweep.txt", cache_set),
                                  ("mmu-sweep.txt", mmu_set)):
            selected = sorted(members & set(subset))
            if not selected:
                raise RuntimeError(f"shard {i} has empty {filename}")
            (shard / filename).write_text("\n".join(selected) + "\n")


def parse_tests(log, expected):
    results = {}
    for raw in log.read_text(errors="replace").splitlines():
        line = ANSI_RE.sub("", raw)
        if not line.startswith("- "):
            continue
        for name in expected:
            if not line.startswith("- " + name):
                continue
            tail = line[len(name) + 2:]
            if tail and not (tail.startswith(" (") or tail.startswith(" ***") or
                             tail.startswith(" !!!")):
                continue
            status = ("FAIL" if "*** FAILED ***" in tail else
                      "CANCEL" if "!!! CANCELED !!!" in tail else
                      "IGNORE" if "!!! IGNORED !!!" in tail else "PASS")
            if name in results and results[name] != status:
                raise RuntimeError(f"conflicting status for {name} in {log}")
            results[name] = status
            break
    summaries = SUMMARY_RE.findall(log.read_text(errors="replace"))
    if not summaries or "RUN ABORTED" in log.read_text(errors="replace"):
        raise RuntimeError(f"missing/aborted ScalaTest summary: {log}")
    summary = tuple(map(int, summaries[-1]))
    if len(results) != sum(summary) or sum(status == "IGNORE" for status in results.values()) != summary[3]:
        raise RuntimeError(f"test-name/summary mismatch in {log}: names={len(results)}, "
                           f"summary={summary}")
    return results, summary


def run_arm_suite(out, cp, arm, suite, names, cache, mmu, ls_names, shards):
    arm_dir = out / arm / suite
    arm_dir.mkdir(parents=True)
    if suite == "corpus":
        stage_corpus(arm_dir, shards, names, cache, mmu)
        expected = ({f"ported: {n}" for n in names} |
                    {f"ported-sweep-copyback: {n}" for n in cache} |
                    {f"ported-sweep-mmuwalk: {n}" for n in mmu} | MANIFEST_TESTS)
    else:
        expected = set(ls_names)
        for i in range(shards):
            stage_cwd(arm_dir / f"shard{i}" / "cwd")
    processes = []
    for i in range(shards):
        shard = arm_dir / f"shard{i}"
        env = base_env(arm, suite)
        if suite == "corpus":
            env.update(PORTED_TEST_DIR=str(shard / "asm"),
                       PORTED_CACHE_SWEEP_LIST=str(shard / "cache-sweep.txt"),
                       PORTED_MMU_SWEEP_LIST=str(shard / "mmu-sweep.txt"))
            suite_name = "m68k040.fuzz.PortedM68kOooSpec"
            args = []
        else:
            suite_name = "m68k040.lockstep.ExecuteLockStepSpec"
            subset = ls_names[i::shards]
            (shard / "names.txt").write_text("\n".join(subset) + "\n")
            args = [word for name in subset for word in ("-t", name)]
        command = ["java", "-Xmx4g", "-Xss16m", "-cp", cp,
                   "org.scalatest.tools.Runner", "-oW", "-s", suite_name, *args]
        (shard / "command.json").write_text(json.dumps({"command": command,
            "env": env, "source": git("rev-parse", "HEAD")}, indent=2) + "\n")
        stream = (shard / "run.log").open("w")
        process = subprocess.Popen(command, cwd=shard / "cwd", env=env,
                                   stdout=stream, stderr=subprocess.STDOUT)
        processes.append((process, stream, shard / "run.log"))
    for process, stream, _ in processes:
        process.wait()
        stream.close()
    combined = {}
    for process, _, log in processes:
        parsed, summary = parse_tests(log, expected)
        if summary[2] or summary[4]:
            raise RuntimeError(f"canceled/pending test in {log}: {summary}")
        if process.returncode != 0 and summary[1] == 0:
            raise RuntimeError(f"JVM/shard exited {process.returncode} with no test failures: {log}")
        for name, status in parsed.items():
            if name in combined and combined[name] != status:
                raise RuntimeError(f"inconsistent repeated manifest test: {name}")
            combined[name] = status
    if set(combined) != expected:
        raise RuntimeError(f"{arm}/{suite}: missing {sorted(expected-set(combined))[:8]}, "
                           f"extra {sorted(set(combined)-expected)[:8]}")
    (arm_dir / "results.json").write_text(json.dumps(combined, indent=2, sort_keys=True) + "\n")
    return combined


def child_run(args):
    source_check(args.expect_sha)
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    (out / "source.json").write_text(json.dumps({"source": args.expect_sha,
        "repo": str(ROOT), "arms": list(ARMS), "suites": args.suites,
        "corpus_shards": args.corpus_shards,
        "lockstep_shards": args.lockstep_shards}, indent=2) + "\n")
    cp = classpath(out)
    names, cache, mmu, _ = corpus_manifest()
    ls_names = lockstep_names(cp, out) if "lockstep" in args.suites else []
    arm_results = {}
    for arm in ARMS:
        source_check(args.expect_sha)
        arm_results[arm] = {}
        for suite in args.suites:
            print(f"LS-OoO parity START {arm}/{suite}", flush=True)
            arm_results[arm][suite] = run_arm_suite(
                out, cp, arm, suite, names, cache, mmu, ls_names,
                args.corpus_shards if suite == "corpus" else args.lockstep_shards)
            print(f"LS-OoO parity DONE {arm}/{suite}", flush=True)
    diffs = {}
    for suite in args.suites:
        off = arm_results["off"][suite]
        diffs[suite] = {}
        for arm in ("on",):
            changed = {name: [off[name], status] for name, status in arm_results[arm][suite].items()
                       if status != off[name]}
            diffs[suite][arm] = changed
    (out / "parity.json").write_text(json.dumps(diffs, indent=2, sort_keys=True) + "\n")
    source_check(args.expect_sha)
    print(json.dumps({suite: {arm: len(v) for arm, v in arms.items()}
                      for suite, arms in diffs.items()}, sort_keys=True), flush=True)
    return 0 if all(not v for arms in diffs.values() for v in arms.values()) else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", action="store_true", help="read-only plan (default)")
    parser.add_argument("--run", action="store_true", help="execute under heavy_run")
    parser.add_argument("--_child", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--expect-sha", help="required exact clean source commit for --run")
    parser.add_argument("--out", type=Path, help="new output directory for --run")
    parser.add_argument("--suites", nargs="+", choices=("corpus", "lockstep"),
                        default=["corpus", "lockstep"])
    parser.add_argument("--corpus-shards", type=int, default=4)
    parser.add_argument("--lockstep-shards", type=int, default=3)
    args = parser.parse_args()
    names, cache, mmu, expected = corpus_manifest()
    if args.corpus_shards < 1 or args.lockstep_shards < 1:
        parser.error("shard counts must be positive")
    if not args.run and not args._child:
        print(json.dumps({"source": git("rev-parse", "HEAD"), "clean": not git("status", "--porcelain"),
            "arms": ARMS, "suites": args.suites, "corpus_cases": len(expected),
            "corpus_asm": len(names), "copyback_sweep": len(cache), "mmu_sweep": len(mmu),
            "lockstep_cases": "discovered from current compiled suite at run time",
            "run_example": "python3 tools/ls_ooo_parity.py --run --expect-sha " +
                git("rev-parse", "HEAD") + " --out /tmp/codex-ls-ooo-parity-<unique>"}, indent=2))
        return 0
    if not args.expect_sha or not args.out:
        parser.error("--run requires --expect-sha and --out")
    if args._child:
        if os.environ.get("LS_OOO_PARITY_LOCKED") != "1":
            parser.error("internal child must be launched through the heavy wrapper")
        return child_run(args)
    source_check(args.expect_sha)
    if args.out.exists():
        parser.error("--out must be a new directory")
    command = [str(HEAVY), sys.executable, str(Path(__file__).resolve()), "--_child",
               "--expect-sha", args.expect_sha, "--out", str(args.out), "--suites",
               *args.suites, "--corpus-shards", str(args.corpus_shards),
               "--lockstep-shards", str(args.lockstep_shards)]
    env = os.environ.copy()
    env.update(LS_OOO_PARITY_LOCKED="1", HEAVY_SLOTS="3", HEAVY_POLL_S="5",
               HEAVY_TIMEOUT_MIN="300", MIN_FREE_GB="8")
    return subprocess.call(command, cwd=ROOT, env=env)


if __name__ == "__main__":
    sys.exit(main())
