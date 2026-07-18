# Port m68k-ooo's Directed ASM Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Vendor and run m68k-ooo's 766 no-interrupt-sweep directed asm tests (actual: 763) against this core, via a new sentinel-based ScalaTest suite that reuses the existing fuzz-harness infrastructure.

**Architecture:** A shared `PortedTestRunner` object (compile-once DUT, mirroring `FuzzRunner`) assembles each vendored `.s` file with the existing `ProgramAssembler`, boots the DUT with the same sequence `FuzzLockStepSpec` already uses, and polls the backing `SparseMemory` for a write to `0xFFFF0000`. A data-driven `PortedM68kOooSpec` generates one named ScalaTest case per vendored file. A shared bash script vendors files from the sibling `m68k-ooo` checkout in either `--list` (pilot) or `--all` (bulk, idempotent) mode.

**Tech Stack:** SpinalHDL/Scala 2.13, ScalaTest (`AnyFunSuite`), Verilator, `m68k-linux-gnu-as`/`ld`/`objcopy` (already installed and already used by `ProgramAssembler.scala`).

## Global Constraints

- Load address: `0x40800000` (m68k-ooo's own convention; matches this project's `ProgramAssembler.DefaultLoadAddress` exactly — no translation).
- Sentinel address: `0xFFFF0000`; PASS iff the written word equals `0xC0FFEE00`; any other write is FAIL; no write before timeout is HANG.
- Only the 766 tests needing no `+ipl=` sweep are in scope (actual: 763; see spec: `docs/superpowers/specs/2026-07-16-port-m68kooo-asm-tests-design.md`). Never vendor a test whose `.args` contains `+ipl=` or any flag beyond `+timeout=`.
- Every FAIL/HANG found is treated as a real bug in this core and triaged individually — no deferred-list import from m68k-ooo's own `deferred.txt`.
- JVM discipline: one Verilator compile per JVM run (`lazy val compiled`), matching `FuzzRunner.compiled` — never re-compile per test.

---

### Task 1: Vendoring script + pilot batch

**Files:**
- Create: `tools/fuzz/vendor-ported-tests.sh`
- Create: `tools/fuzz/pilot-test-names.txt`
- Create (via running the script): `src/test/resources/m68kooo-ported-tests/asm/*.s` (+ `.timeout` sidecars), 26 files' worth

**Interfaces:**
- Produces: the vendored directory layout `src/test/resources/m68kooo-ported-tests/asm/<name>.s` (+ optional `<name>.timeout`, bare cycle-count text) that Task 2/3 read.

- [ ] **Step 1: Write the vendoring script**

```bash
#!/usr/bin/env bash
# Vendor m68k-ooo's directed asm tests into this repo's test resources.
#
# Copies .s files that need no interrupt-timing sweep (no .args file, or a
# .args file containing ONLY +timeout=<N>) into DST_DIR, writing a bare
# <name>.timeout sidecar (just the cycle count, no plusarg syntax) for the
# latter case. Tests requiring +ipl= or any other flag are skipped.
#
# usage:
#   tools/fuzz/vendor-ported-tests.sh <src-asm-dir> <dst-dir> --list <namefile>
#   tools/fuzz/vendor-ported-tests.sh <src-asm-dir> <dst-dir> --all
set -euo pipefail
SRC=$1
DST=$2
MODE=$3
mkdir -p "$DST"

qualifies() {
  local name=$1
  local args="$SRC/$name.args"
  if [ ! -f "$args" ]; then return 0; fi
  if grep -q '+ipl=' "$args"; then return 1; fi
  # any line that ISN'T a comment, blank, or a bare +timeout=<N> disqualifies it
  if grep -vE '^\s*(#|\+timeout=[0-9]+\s*$|\s*$)' "$args" | grep -q .; then return 1; fi
  return 0
}

vendor_one() {
  local name=$1
  if [ ! -f "$SRC/$name.s" ]; then
    echo "  [MISS] $name (no .s file in $SRC)"
    return
  fi
  if ! qualifies "$name"; then
    echo "  [SKIP] $name (needs ipl-sweep or other unsupported flags)"
    return
  fi
  cp "$SRC/$name.s" "$DST/$name.s"
  local args="$SRC/$name.args"
  if [ -f "$args" ]; then
    grep -o '+timeout=[0-9]*' "$args" | cut -d= -f2 > "$DST/$name.timeout"
  fi
  echo "  [OK]   $name"
}

case "$MODE" in
  --list)
    namefile=$4
    while IFS= read -r name; do
      [ -z "$name" ] && continue
      vendor_one "$name"
    done < "$namefile"
    ;;
  --all)
    for s in "$SRC"/*.s; do
      name=$(basename "$s" .s)
      if [ -f "$DST/$name.s" ]; then continue; fi   # already vendored (e.g. by the pilot)
      vendor_one "$name"
    done
    ;;
  *)
    echo "usage: $0 <src-asm-dir> <dst-dir> --list <namefile> | --all" 1>&2
    exit 2
    ;;
esac
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x tools/fuzz/vendor-ported-tests.sh
```

- [ ] **Step 3: Write the pilot name list**

```
neg_not
lsl_lsr_basic
cmpa_abs_mem
movea_absw_split
link_long_unlk
clr_l_idx
divl_basic
bf_basic
exc_addr_error
movep_l_byte_layout
eori_bw_reg
aline_triple_nested_dbf_storm
add_indexed_mem_src
sub_mem_dest
and_indexed_mem_dest
eor_mem_rmw
cmp_branches
move_l_abs_memind_dst
bcc_all_16_conditions
dbcc_different_dn_terminates
jsr_a7_indirect
trap_vec_32_47
swap_test
ext_extb
tst_abs_mem
bsr_16bit_disp
```

Save this to `tools/fuzz/pilot-test-names.txt`. (`aline_triple_nested_dbf_storm` is the one deliberate timeout-only case — its `.args` contains exactly `+timeout=2000000`, nothing else — included specifically to exercise the `.timeout` sidecar path early.)

- [ ] **Step 4: Run the vendoring script in pilot mode**

```bash
tools/fuzz/vendor-ported-tests.sh /home/qwertyoruiop/m68k-ooo/tb/tests/asm \
  src/test/resources/m68kooo-ported-tests/asm --list tools/fuzz/pilot-test-names.txt
```

Expected: 26 lines, each `[OK]   <name>` (no `[SKIP]`/`[MISS]` — every name in the pilot list was pre-verified to qualify).

- [ ] **Step 5: Verify the vendored files**

```bash
ls src/test/resources/m68kooo-ported-tests/asm/*.s | wc -l
```

Expected: `26`

```bash
ls src/test/resources/m68kooo-ported-tests/asm/*.timeout
cat src/test/resources/m68kooo-ported-tests/asm/aline_triple_nested_dbf_storm.timeout
```

Expected: exactly one `.timeout` file, containing `2000000`.

- [ ] **Step 6: Commit**

```bash
git add tools/fuzz/vendor-ported-tests.sh tools/fuzz/pilot-test-names.txt \
  src/test/resources/m68kooo-ported-tests/
git commit -m "test: vendor pilot batch of m68k-ooo's directed asm tests"
```

---

### Task 2: PortedTestRunner — sentinel-based execution harness + smoke test

**Files:**
- Create: `src/test/scala/m68k040/fuzz/PortedTestRunner.scala`
- Create: `src/test/scala/m68k040/fuzz/PortedSmokeSpec.scala`

**Interfaces:**
- Consumes: `FuzzDut.attachProgram` (`src/test/scala/m68k040/fuzz/FuzzDut.scala`), `m68k040.ls.BehavioralMemAgent` (`src/test/scala/m68k040/ls/BehavioralMem.scala`), `m68k040.oracle.ProgramAssembler.assemble` (`src/test/scala/m68k040/oracle/ProgramAssembler.scala`), `m68k040.M68kSim` (`src/test/scala/m68k040/M68kSim.scala`).
- Produces: `PortedOutcome` (sealed trait: `PortedPass`, `PortedFail(word: Long)`, `PortedHang(cycles: Long)`, `PortedGenFail(reason: String)`) and `PortedTestRunner.run(name: String, src: String, timeoutCycles: Long, simSeed: Int = 1): PortedOutcome` — this exact signature is what Task 3's generated tests call.

- [ ] **Step 1: Write `PortedTestRunner.scala`**

```scala
package m68k040.fuzz

import m68k040.M68kSim
import m68k040.oracle.ProgramAssembler
import spinal.core.sim._

/** Outcome of running one m68k-ooo-ported directed asm test. */
sealed trait PortedOutcome
case object PortedPass extends PortedOutcome
final case class PortedFail(word: Long) extends PortedOutcome
final case class PortedHang(cycles: Long) extends PortedOutcome
final case class PortedGenFail(reason: String) extends PortedOutcome

/** Runs m68k-ooo's self-checking directed asm tests against this core.
  *
  * Each test is a standalone program that writes a sentinel word to
  * 0xFFFF0000 before halting: 0xC0FFEE00 = PASS, anything else = FAIL, no
  * write before the timeout = HANG. This mirrors m68k-ooo's own C++
  * testbench (tb/tb_top.cpp) detection exactly -- see
  * docs/superpowers/specs/2026-07-16-port-m68kooo-asm-tests-design.md.
  *
  * JVM discipline: ONE Verilator compile per JVM (lazy, shared across every
  * test), mirroring FuzzRunner.compiled -- never re-compile per test.
  */
object PortedTestRunner {
  val loadAddr: Long = ProgramAssembler.DefaultLoadAddress
  val SentinelAddr: Long = 0xFFFF0000L
  val PassWord: Long = 0xC0FFEE00L

  lazy val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)
  private var runIdx = 0

  def run(name: String, src: String, timeoutCycles: Long, simSeed: Int = 1): PortedOutcome = {
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => return PortedGenFail(s"assemble: ${err.reason}")
    }

    runIdx += 1
    var outcome: PortedOutcome = PortedHang(0)
    compiled.doSim(s"ported_$runIdx", simSeed) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)

      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)

      // The sentinel word must start at a KNOWN value, not SparseMemory's
      // random fill for never-written bytes (same reasoning as the sandbox
      // pre-fill in FuzzLockStepSpec.scala's task #143 fix) -- otherwise a
      // random nonzero value there could be misread as an immediate (wrong)
      // sentinel write before the program has even started.
      for (i <- 0 until 4) dmem.mem.write(SentinelAddr + i, 0.toByte)

      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp   #= 0
      dut.ctrl.logic.srp   #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false
      dut.intCtrl.logic.iackVector #= 0

      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid    #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)

      // Boot: supervisor (SR 0x2700), SSP/ISP 0x00100000, USP 0 -- identical
      // to FuzzRunner.run's boot sequence (FuzzLockStepSpec.scala).
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      var cyc = 0L
      var word = 0L
      while (word == 0 && cyc < timeoutCycles) {
        cd.waitSampling()
        cyc += 1
        val b0 = dmem.mem.read(SentinelAddr).toLong & 0xffL
        val b1 = dmem.mem.read(SentinelAddr + 1).toLong & 0xffL
        val b2 = dmem.mem.read(SentinelAddr + 2).toLong & 0xffL
        val b3 = dmem.mem.read(SentinelAddr + 3).toLong & 0xffL
        word = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
      }
      outcome =
        if (word == 0) PortedHang(cyc)
        else if (word == PassWord) PortedPass
        else PortedFail(word)
    }
    outcome
  }
}
```

- [ ] **Step 2: Write the smoke test**

```scala
package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}

/** Validates PortedTestRunner end-to-end against ONE known-simple vendored
  * test before Task 3 wires up the full data-driven suite. */
class PortedSmokeSpec extends AnyFunSuite {
  test("smoke: neg_not.s passes via PortedTestRunner", VerilatorTest) {
    val path = Paths.get("src/test/resources/m68kooo-ported-tests/asm/neg_not.s")
    val src = new String(Files.readAllBytes(path))
    val outcome = PortedTestRunner.run("neg_not", src, timeoutCycles = 200000)
    println(s"[smoke] neg_not.s -> $outcome")
    outcome match {
      case PortedPass         => ()
      case PortedFail(word)   => fail(f"FAIL sentinel word=0x$word%08x (expected 0x${PortedTestRunner.PassWord}%08x)")
      case PortedHang(cycles) => fail(s"HANG: no sentinel write within $cycles cycles")
      case PortedGenFail(r)   => fail(s"assemble/toolchain error: $r")
    }
  }
}
```

- [ ] **Step 3: Compile**

```bash
~/sbt/bin/sbt "test:compile"
```

Expected: `[success]`, no errors.

- [ ] **Step 4: Run the smoke test**

```bash
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedSmokeSpec"
```

Expected: `[info] All tests passed.`

**If it fails instead** (this is the one genuinely unverified assumption in this plan — flagged explicitly, not glossed over): check the printed `[smoke]` outcome.
- `PortedFail(word)` with a plausible-looking but byte-swapped value (e.g. `0x00eeffc0` instead of `0xc0ffee00`) means the byte-order reconstruction in Step 1 is wrong for this AXI write path — swap the shift order (`b3` at the top instead of `b0`) and re-run.
- `PortedHang` means the sentinel write never happened at all within 200,000 cycles — check whether `neg_not.s` genuinely needs more cycles (unlikely, it's a short linear program) or whether the DCACHE `BehavioralMemAgent`'s write path isn't reaching `0xFFFF0000` for some address-decode reason; add a temporary trace on `dmem.mem` writes to confirm.
- `PortedGenFail` means the assembler rejected `neg_not.s`'s syntax — check the error message; m68k-ooo's own directed tests use plain GNU-as syntax so this would be surprising, but the `|`-style comments in the file (visible in the design doc's background section) should already be valid GNU-as comment syntax.

- [ ] **Step 5: Commit**

```bash
git add src/test/scala/m68k040/fuzz/PortedTestRunner.scala \
  src/test/scala/m68k040/fuzz/PortedSmokeSpec.scala
git commit -m "test: PortedTestRunner sentinel-based harness + smoke test"
```

---

### Task 3: Data-driven pilot suite

**Files:**
- Create: `src/test/scala/m68k040/fuzz/PortedM68kOooSpec.scala`
- Delete: `src/test/scala/m68k040/fuzz/PortedSmokeSpec.scala` (superseded — its one case is now covered by the data-driven suite's `neg_not` test)

**Interfaces:**
- Consumes: `PortedTestRunner.run` (Task 2), `PortedOutcome` variants (Task 2).

- [ ] **Step 1: Write the data-driven suite**

```scala
package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

/** One named ScalaTest case per vendored m68k-ooo directed asm test (see
  * tools/fuzz/vendor-ported-tests.sh + PortedTestRunner.scala).
  *
  * Run everything vendored so far:
  *   ~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec"
  * Run one test:
  *   ~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec -- -z neg_not"
  */
class PortedM68kOooSpec extends AnyFunSuite {
  private val DefaultTimeoutCycles = 200000L
  // PORTED_TEST_DIR lets tools/fuzz/ported-sweep.sh point this suite at a
  // scratch subset for batching (ScalaTest's -z is a substring filter, not
  // an OR-of-names matcher, so batching by staging a directory subset is
  // the robust option -- see Task 6).
  private val dir = Paths.get(sys.env.getOrElse(
    "PORTED_TEST_DIR", "src/test/resources/m68kooo-ported-tests/asm"))
  private val names: Vector[String] =
    if (Files.isDirectory(dir)) {
      val listing = Files.list(dir)
      try {
        listing.iterator().asScala
          .map(_.getFileName.toString)
          .filter(_.endsWith(".s"))
          .map(_.stripSuffix(".s"))
          .toVector.sorted
      } finally listing.close()
    } else Vector.empty

  for (name <- names) {
    test(s"ported: $name", VerilatorTest) {
      val src = new String(Files.readAllBytes(dir.resolve(s"$name.s")))
      val timeoutPath = dir.resolve(s"$name.timeout")
      val timeout =
        if (Files.exists(timeoutPath)) new String(Files.readAllBytes(timeoutPath)).trim.toLong
        else DefaultTimeoutCycles
      val outcome = PortedTestRunner.run(name, src, timeout)
      outcome match {
        case PortedPass          => ()
        case PortedFail(word)    =>
          fail(f"FAIL sentinel word=0x$word%08x (expected 0x${PortedTestRunner.PassWord}%08x)")
        case PortedHang(cycles)  =>
          fail(s"HANG: no sentinel write within $cycles cycles (timeout=$timeout)")
        case PortedGenFail(r)    => fail(s"assemble/toolchain error: $r")
      }
    }
  }

  if (names.isEmpty) {
    test("no ported tests found (vendoring not run yet?)", VerilatorTest) {
      info(s"$dir is empty or missing -- run tools/fuzz/vendor-ported-tests.sh first")
    }
  }
}
```

- [ ] **Step 2: Delete the now-superseded smoke spec**

```bash
git rm src/test/scala/m68k040/fuzz/PortedSmokeSpec.scala
```

- [ ] **Step 3: Compile**

```bash
~/sbt/bin/sbt "test:compile"
```

Expected: `[success]`, no errors.

- [ ] **Step 4: Run the pilot suite**

```bash
free -g
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec"
```

Expected: 26 test cases run (one per vendored pilot file). Record the pass/fail/hang tally from the output — this is Task 4's input, not something to guess here.

- [ ] **Step 5: Commit**

```bash
git add src/test/scala/m68k040/fuzz/PortedM68kOooSpec.scala
git commit -m "test: data-driven suite generating one case per vendored ported test"
```

---

### Task 4: Pilot results — timeout calibration + failure triage

**Files:**
- Modify: `src/test/scala/m68k040/fuzz/PortedTestRunner.scala:DefaultTimeoutCycles` equivalent (the constant lives in `PortedM68kOooSpec.scala` — adjust there) if Task 3's run showed it needs changing.
- No other files known in advance — this task's job is to produce the *list* of what needs fixing, matching this session's own fuzz-campaign workflow (task list entries with a repro path, not speculative fixes).

**Interfaces:** none (this is an analysis/triage task, not a code-interface task).

- [ ] **Step 1: Review Task 3's pilot run output**

For each of the 26 tests, confirm PASS, or note FAIL (with the sentinel word) or HANG (with cycles-to-timeout). If `aline_triple_nested_dbf_storm` (the one `.timeout`-sidecar test, 2,000,000 cycles) took a long time to run, note the wall-clock duration printed by sbt — this is the throughput data point the design doc flagged as needed before trusting the `DefaultTimeoutCycles = 200000` value for the bulk port.

- [ ] **Step 2: If the default timeout looks wrong (too tight or wastefully loose), adjust it**

Edit `DefaultTimeoutCycles` in `src/test/scala/m68k040/fuzz/PortedM68kOooSpec.scala` based on actual observed cycle counts among the 25 non-`.timeout` pilot tests (most should complete in a few hundred to a few thousand cycles for tests this small — if several genuinely need tens of thousands, raise the default; if all comfortably finish under 10,000, consider lowering it to cut wasted HANG-detection time on any real bulk-port hangs). Re-run affected tests to confirm the new value doesn't turn a real PASS into a false HANG or vice versa.

- [ ] **Step 3: For every FAIL, create a task and investigate**

Use the same workflow as this session's fuzz-campaign findings: the vendored `.s` file is already a standalone repro (no minimization needed — it's already a small, hand-authored directed test). Reproduce via:

```bash
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec -- -z <name>"
```

Root-cause, fix, verify against that specific test, re-run the full pilot batch to confirm no regression, record the finding in project memory (mirroring the fuzz-campaign memory file's per-finding structure) and mark the task completed.

- [ ] **Step 4: For every HANG, investigate the same way**

Same workflow — a HANG in a small directed test is not expected to need genuine multi-million-cycle runtime, so a HANG here is very likely a real bug (front-end stall, deadlock, or similar), not a timeout-too-tight false positive (already ruled out in Step 2).

- [ ] **Step 5: Commit any fixes as they land**

Each fix gets its own commit, following the project's existing convention (one commit per fix, `fastTest` clean before committing) — do not batch multiple unrelated fixes into one commit.

---

### Task 5: Bulk vendoring — remaining ~740 tests

**Files:**
- Create (via running the script): the remaining `src/test/resources/m68kooo-ported-tests/asm/*.s` (+ `.timeout` sidecars) files, bringing the total to 766 (actual: 763).

**Interfaces:** none new — reuses Task 1's script and Task 3's suite (which already scans the whole directory dynamically).

- [ ] **Step 1: Run the vendoring script in bulk mode**

```bash
tools/fuzz/vendor-ported-tests.sh /home/qwertyoruiop/m68k-ooo/tb/tests/asm \
  src/test/resources/m68kooo-ported-tests/asm --all
```

Expected: ~740 `[OK]` lines (the 26 pilot names are skipped as already-vendored — the script's `--all` mode checks `if [ -f "$DST/$name.s" ]; then continue; fi`), plus `[SKIP]` lines for the 62 out-of-scope `+ipl=`/other-flag tests.

- [ ] **Step 2: Verify the final count**

```bash
ls src/test/resources/m68kooo-ported-tests/asm/*.s | wc -l
```

Expected: `766` (actual: 763)

```bash
ls src/test/resources/m68kooo-ported-tests/asm/*.timeout | wc -l
```

Expected: `36` (actual: 30)

- [ ] **Step 3: Commit**

```bash
git add src/test/resources/m68kooo-ported-tests/
git commit -m "test: bulk-vendor remaining m68k-ooo directed asm tests (766 total)"
```

---

### Task 6: Full-suite batch runner + final report

**Files:**
- Create: `tools/fuzz/ported-sweep.sh`

**Interfaces:** none new — batches Task 3's suite by test-name prefix groups, matching `tools/fuzz/sweep.sh`'s existing convention.

- [ ] **Step 1: Write the sweep script**

ScalaTest's `-z <substring>` is a substring filter, not an OR-of-names matcher — there's
no clean way to select an arbitrary batch of test names via `-z` in one invocation. Batch
instead by staging each batch's `.s`/`.timeout` files into a scratch directory and
pointing `PortedM68kOooSpec` at it via the `PORTED_TEST_DIR` env var (already wired in
Task 3, Step 1).

```bash
#!/usr/bin/env bash
# Ported-test sweep runner -- same JVM-batching discipline as
# tools/fuzz/sweep.sh: one compile per JVM (PortedTestRunner.compiled is
# lazy+shared), batches run singly. Batches by staging a directory SUBSET
# (PORTED_TEST_DIR) rather than a ScalaTest -z filter, since -z is a
# substring match, not an OR-of-names matcher.
#
# usage: tools/fuzz/ported-sweep.sh [BATCH]
#   BATCH  test files per JVM/sbt run (default 100)
set -u
BATCH=${1:-100}
SBT=${SBT:-$HOME/sbt/bin/sbt}
LOGDIR=${LOGDIR:-ported_logs}
ASMDIR=src/test/resources/m68kooo-ported-tests/asm
SCRATCH=$(mktemp -d)
trap 'rm -rf "$SCRATCH"' EXIT
mkdir -p "$LOGDIR"

mapfile -t names < <(ls "$ASMDIR"/*.s | xargs -n1 basename | sed 's/\.s$//' | sort)
total=${#names[@]}
echo "[ported-sweep] $total tests, batch=$BATCH"

fails=0
for ((i = 0; i < total; i += BATCH)); do
  batch_dir="$SCRATCH/batch_$i"
  mkdir -p "$batch_dir"
  for name in "${names[@]:i:BATCH}"; do
    cp "$ASMDIR/$name.s" "$batch_dir/"
    [ -f "$ASMDIR/$name.timeout" ] && cp "$ASMDIR/$name.timeout" "$batch_dir/"
  done
  n=$(ls "$batch_dir"/*.s | wc -l)
  log="$LOGDIR/batch_${i}.log"
  echo "[ported-sweep] batch [$i,$((i + n))) -> $log"
  PORTED_TEST_DIR="$batch_dir" timeout 1800 "$SBT" "testOnly m68k040.fuzz.PortedM68kOooSpec" \
    >"$log" 2>&1
  rc=$?
  if ((rc != 0)); then fails=$((fails + 1)); fi
  rm -rf "$batch_dir"
done

echo
echo "[ported-sweep] ===== SUMMARY ====="
grep -h "Tests:" "$LOGDIR"/batch_*.log 2>/dev/null
echo "[ported-sweep] batches with failures: $fails"
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x tools/fuzz/ported-sweep.sh
```

- [ ] **Step 3: Run the full sweep**

```bash
free -g
tools/fuzz/ported-sweep.sh 100
```

Expected: 8 batches (766/100, actual: 763), each logged to `ported_logs/batch_*.log`. Given Task 4 already found and fixed the pilot's issues, most of the remaining 740 should pass, but treat any new FAIL/HANG exactly like Task 4 Steps 3-5 (own task, own investigation, own commit).

- [ ] **Step 4: Record the final tally in project memory**

Follow this session's existing fuzz-campaign memory convention (`fuzz-campaign-divergence-2026-07-16.md`) — a new memory entry (or a new file, `ported-tests-campaign-<date>.md`) recording: total run, pass/fail/hang counts, list of fixes landed, list of any still-open findings with their repro command (`testOnly m68k040.fuzz.PortedM68kOooSpec -- -z <name>`).

- [ ] **Step 5: Commit**

```bash
git add tools/fuzz/ported-sweep.sh
git commit -m "test: full-suite ported-test sweep runner + results"
```
