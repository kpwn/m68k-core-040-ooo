# Verification Harness & Musashi Lock-Step — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the instruction-level lock-step verification harness — a Musashi reference oracle (final-state *and* per-instruction trace), a pure-Scala lock-step comparator, and a SpinalSim CommitTrace capture path — fully self-tested against a synthetic trace producer, ready to plug into the first real commit boundary.

**Architecture:** Reuse the proven `m68k-core-030-inorder` oracle pattern: a standalone C++ `musashi_run` binary drives the Musashi ISS and dumps state as text; a Scala `Musashi` object spawns it as a subprocess and parses the output (no JNI). We *extend* `musashi_run` with a `--trace` mode that emits one record per retired instruction (via `MusashiRef::step_one()` + `get_reg()`). The `LockStep` comparator is pure Scala over `CommitObservation` (DUT side, captured from the `CommitTrace` SpinalHDL port via SpinalSim) vs `OracleStep` (Musashi side). Because no pipeline exists yet, a minimal `TraceReplay` SpinalHDL component (CommitTrace passthrough, poked per-cycle in sim) stands in for the real core so the capture+compare plumbing is validated end-to-end against Musashi now.

**Tech Stack:** Scala 2.13.16 / SpinalHDL 1.14.1 / ScalaTest 3.2.19; SpinalSim + Verilator 5.032; C++ (g++ 15) Musashi ISS; GNU m68k cross-toolchain (`m68k-linux-gnu-as/-ld/-objcopy`, confirmed present at `/usr/bin`).

**References:**
- Design doc ch 11 (verification) + invariant #5 (CommitTrace observability port): `docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md`.
- Source to reuse, in the sibling repo `/home/qwertyoruiop/m68k-core-030-inorder`:
  - `tools/musashi/` — full Musashi + `musashi_run.cpp` + `m68k_ref.{h,cpp}` (has `step_one()`, `get_reg(Reg)`, `final_mem_write_events()`) + `Makefile`. Builds via `make -C tools/musashi` → `musashi_run`.
  - `src/test/scala/m68k030/oracle/{Musashi,ProgramAssembler,OracleState}.scala` — the subprocess bridge, the GNU-as assembler wrapper, and the result types.
- Existing 040 foundation: `m68k040.types.CommitTrace` (fields: `fire, pc, opword, archRegId, archRegWrite, archRegValid, ccr, ccrValid, memAddr, memData, memWrite, excTaken, excVector`), `M68kParams`, Makefile, `tools/musashi/README.md` (vendoring point), `m68k040.{SlowTest,VerilatorTest,BoardTest}` tags.

**Toolchain note:** `sbt` is at `~/sbt/bin/sbt` (NOT on PATH). Run suites as `~/sbt/bin/sbt "testOnly <spec>"`; allow timeouts up to 600000 ms. Verilator-backed sim tests are slower (compile step) — tag them `m68k040.VerilatorTest` so `fastTest` excludes them.

**Package:** oracle/harness code lives under `src/test/scala/m68k040/oracle/` and `src/test/scala/m68k040/lockstep/`; the synthetic `TraceReplay` component (real RTL) lives under `src/main/scala/m68k040/verif/`.

---

## File structure (created by this plan)

| File | Responsibility |
|---|---|
| `tools/musashi/*` (vendored) | Musashi ISS + `musashi_run` C++ runner (extended with `--trace`) |
| `Makefile` (modify) | add `musashi` + `test-verilator` targets |
| `src/test/scala/m68k040/oracle/OracleState.scala` | `OracleState`, `OracleError`, `MemoryWriteEvent` |
| `src/test/scala/m68k040/oracle/ProgramAssembler.scala` | m68k asm source → flat binary image (GNU as, `-m68040`) |
| `src/test/scala/m68k040/oracle/Musashi.scala` | subprocess bridge: assemble+run → `OracleState`; assemble+trace → `Seq[OracleStep]` |
| `src/test/scala/m68k040/oracle/OracleStep.scala` | per-instruction trace record + parser |
| `src/test/scala/m68k040/lockstep/CommitObservation.scala` | DUT-side per-commit record (mirror of CommitTrace) |
| `src/test/scala/m68k040/lockstep/LockStep.scala` | pure-Scala comparator: DUT commits vs Musashi steps |
| `src/main/scala/m68k040/verif/TraceReplay.scala` | synthetic CommitTrace producer (passthrough RTL) for harness self-test |
| `src/test/scala/m68k040/lockstep/CommitTraceCapture.scala` | SpinalSim helper: sample CommitTrace each cycle `fire` is high |
| `src/test/scala/m68k040/lockstep/*Spec.scala` | unit + end-to-end harness tests |

---

## Task 1: Vendor Musashi + Makefile targets

**Files:**
- Create: `tools/musashi/*` (copied from 030; replaces the README-only placeholder)
- Modify: `Makefile`

- [ ] **Step 1: Vendor the Musashi tree and rebuild it from source**

Run:
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
# preserve our vendoring note, bring in the full proven tree
cp tools/musashi/README.md /tmp/musashi-vendor-note.md
rm -rf tools/musashi
cp -r /home/qwertyoruiop/m68k-core-030-inorder/tools/musashi tools/musashi
cp /tmp/musashi-vendor-note.md tools/musashi/VENDORING.md
# rebuild from source so we don't ship stale 030 objects
make -C tools/musashi clean
make -C tools/musashi
ls -la tools/musashi/musashi_run
```
Expected: `tools/musashi/musashi_run` is freshly built and executable.

- [ ] **Step 2: Confirm the runner works on a tiny program**

Run (assemble a 1-instruction program with the cross toolchain, run it through Musashi to final state):
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
printf 'moveq #7,%%d0\n move.l %%d0,0xFFFF0000\n' > /tmp/t.s
m68k-linux-gnu-as -m68040 -o /tmp/t.o /tmp/t.s
m68k-linux-gnu-ld -Ttext 0x40800000 -o /tmp/t.elf /tmp/t.o
m68k-linux-gnu-objcopy -O binary /tmp/t.elf /tmp/t.bin
tools/musashi/musashi_run --bin /tmp/t.bin --out /tmp/t.out \
  --load-addr 0x40800000 --initial-sp 0x00100000 --sentinel 0xFFFF0000 --max-cycles 50000
grep -E '^(pass|pc|d0|sentinel_hit)=' /tmp/t.out
```
Expected: `/tmp/t.out` contains `sentinel_hit=1`, `d0=0x00000007` (the move to the sentinel address triggers the stop). If the runner's CLI flags differ, read `tools/musashi/musashi_run.cpp`'s arg parser and adjust the smoke command — do NOT change the binary.

- [ ] **Step 3: Add `musashi` and `test-verilator` targets to the Makefile**

Modify `Makefile` — add these targets (TAB-indented recipes), and add them to `.PHONY`:
```makefile
musashi:
	$(MAKE) -C tools/musashi

test-verilator:
	$(SBT) "testOnly * -- -n m68k040.VerilatorTest"
```
Update the `.PHONY` line to include `musashi test-verilator`. (`test-verilator` runs ONLY VerilatorTest-tagged specs via scalatest's `-n` include filter. This also resolves the dangling `make test-verilator` reference in `tools/pm_gate.sh`.)

- [ ] **Step 4: Verify the new targets**

Run: `make SBT=~/sbt/bin/sbt musashi` → rebuilds musashi_run cleanly. `make SBT=~/sbt/bin/sbt test-verilator` → runs (zero VerilatorTest specs exist yet, so it should complete with "No tests were executed" or 0 run — that's fine; it must not error on the target itself).

- [ ] **Step 5: Commit**

`tools/musashi/musashi_run` and build artifacts (`*.o`, `*.a`) are build outputs — confirm `tools/musashi/.gitignore` (vendored from 030) ignores them; only source + Makefile should be tracked.
```bash
git add tools/musashi Makefile && git status --short
# verify no compiled binaries staged (no musashi_run, *.o, *.a):
git diff --cached --name-only | grep -E 'musashi_run$|\.(o|a)$' && echo "STOP: binaries staged" || echo "clean"
git commit -m "verif: vendor Musashi ISS + musashi_run; add musashi/test-verilator make targets"
```
If compiled binaries are staged, add them to `tools/musashi/.gitignore` and unstage before committing.

---

## Task 2: Port oracle types + assembler + final-state bridge

**Files:**
- Create: `src/test/scala/m68k040/oracle/OracleState.scala`
- Create: `src/test/scala/m68k040/oracle/ProgramAssembler.scala`
- Create: `src/test/scala/m68k040/oracle/Musashi.scala`
- Test: `src/test/scala/m68k040/oracle/MusashiOracleSpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package m68k040.oracle

import org.scalatest.funsuite.AnyFunSuite

class MusashiOracleSpec extends AnyFunSuite {
  test("assemble and run a tiny program to final state") {
    val src =
      """    moveq #7,%d0
        |    move.l %d0,0xFFFF0000
        |""".stripMargin
    Musashi.assembleAndRun(src) match {
      case Right(st) =>
        assert(st.sentinelHit, "expected the move to the sentinel address to stop the run")
        assert(st.d(0) == 7L, s"D0 should be 7, got ${st.d(0)}")
      case Left(err) => fail(s"oracle error: ${err.reason}")
    }
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** — `not found: value Musashi`. Run: `~/sbt/bin/sbt "testOnly m68k040.oracle.MusashiOracleSpec"`.

- [ ] **Step 3: Port `OracleState.scala`** — copy the file verbatim from `/home/qwertyoruiop/m68k-core-030-inorder/src/test/scala/m68k030/oracle/OracleState.scala`, then change ONLY the package line to `package m68k040.oracle`.

Run:
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
mkdir -p src/test/scala/m68k040/oracle
sed 's/^package m68k030.oracle/package m68k040.oracle/' \
  /home/qwertyoruiop/m68k-core-030-inorder/src/test/scala/m68k030/oracle/OracleState.scala \
  > src/test/scala/m68k040/oracle/OracleState.scala
```
Then READ the resulting file and confirm it defines `MemoryWriteEvent`, `OracleState` (fields incl. `d: Vector[Long]`, `a: Vector[Long]`, `pc`, `sr`, `ccr`, `sentinelHit`, memory writes), and `OracleError`. If it references any `m68k030`-specific symbol in the body, report BLOCKED (it should be self-contained).

- [ ] **Step 4: Port `ProgramAssembler.scala`** — copy from 030 and retarget to 68040.

Run:
```bash
sed -e 's/^package m68k030.oracle/package m68k040.oracle/' \
    -e 's/-m68030/-m68040/' \
    -e 's/m68k030-program-/m68k040-program-/' \
  /home/qwertyoruiop/m68k-core-030-inorder/src/test/scala/m68k030/oracle/ProgramAssembler.scala \
  > src/test/scala/m68k040/oracle/ProgramAssembler.scala
```
READ the result: confirm it shells to `m68k-linux-gnu-as -m68040`, `-ld -Ttext`, `-objcopy -O binary`, and returns `Either[OracleError, Image]` with `Image(bytes, loadAddress)` and `DefaultLoadAddress`.

- [ ] **Step 5: Port `Musashi.scala`** — copy from 030, retarget the package, the cache path, and the runner-not-found hint.

Run:
```bash
sed -e 's/^package m68k030.oracle/package m68k040.oracle/' \
    -e 's#m68k-core-030-inorder#m68k-core-040-ooo#g' \
    -e 's/m68k030-musashi-/m68k040-musashi-/' \
  /home/qwertyoruiop/m68k-core-030-inorder/src/test/scala/m68k030/oracle/Musashi.scala \
  > src/test/scala/m68k040/oracle/Musashi.scala
```
READ the result. Confirm `runnerPath` resolves to `tools/musashi/musashi_run` (in-tree) or `~/.cache/m68k-core-040-ooo/musashi_run` or `$MUSASHI_RUN`, that `assembleAndRun(...)` exists and returns `Either[OracleError, OracleState]`, and that no `m68k030` strings remain: `grep -n "m68k030" src/test/scala/m68k040/oracle/Musashi.scala` MUST be empty.

- [ ] **Step 6: Run the test, expect PASS.** Run: `~/sbt/bin/sbt "testOnly m68k040.oracle.MusashiOracleSpec"`. The `musashi_run` binary built in Task 1 must exist. Expected: 1 test passes (D0==7, sentinel hit). If `assembleAndRun` returns `Left` complaining the runner is missing, confirm `tools/musashi/musashi_run` exists (rerun `make musashi`).

- [ ] **Step 7: Commit**
```bash
git add src/test/scala/m68k040/oracle/OracleState.scala src/test/scala/m68k040/oracle/ProgramAssembler.scala src/test/scala/m68k040/oracle/Musashi.scala src/test/scala/m68k040/oracle/MusashiOracleSpec.scala
git commit -m "verif: port Musashi oracle bridge + ProgramAssembler (68040 final-state)"
```

---

## Task 3: Per-instruction trace mode in musashi_run + Scala parser

**Files:**
- Modify: `tools/musashi/musashi_run.cpp`
- Create: `src/test/scala/m68k040/oracle/OracleStep.scala`
- Modify: `src/test/scala/m68k040/oracle/Musashi.scala` (add `assembleAndTrace`)
- Test: `src/test/scala/m68k040/oracle/MusashiTraceSpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package m68k040.oracle

import org.scalatest.funsuite.AnyFunSuite

class MusashiTraceSpec extends AnyFunSuite {
  test("per-instruction trace yields one step per executed instruction with post-state") {
    val src =
      """    moveq #1,%d0
        |    moveq #2,%d1
        |    add.l %d1,%d0
        |    move.l %d0,0xFFFF0000
        |""".stripMargin
    Musashi.assembleAndTrace(src) match {
      case Right(steps) =>
        // at least the 3 register-affecting instructions before the sentinel store
        assert(steps.size >= 3, s"expected >=3 steps, got ${steps.size}")
        // after the first moveq, D0 == 1
        assert(steps(0).d(0) == 1L, s"step0 D0 should be 1, got ${steps(0).d(0)}")
        // after the add, D0 == 3
        val afterAdd = steps.find(s => s.d(0) == 3L)
        assert(afterAdd.isDefined, s"no step shows D0==3 after the add; steps=${steps.map(_.d(0))}")
        // PCs strictly advance through the straight-line code
        assert(steps.map(_.pc).distinct.size == steps.size, "PCs should be distinct in straight-line code")
      case Left(err) => fail(s"oracle error: ${err.reason}")
    }
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** — `assembleAndTrace` / `OracleStep` undefined.

- [ ] **Step 3: Add a `--trace <file>` mode to `tools/musashi/musashi_run.cpp`.**

READ `tools/musashi/musashi_run.cpp` and `tools/musashi/m68k_ref.h` first. Implement: when `--trace <path>` is passed, instead of the chunked `run_until_sentinel*` path, drive the CPU one instruction at a time and append one trace record per instruction to `<path>`. Use the existing `MusashiRef` API: `step_one()` to advance a single instruction, `get_reg(MusashiRef::REG_*)` for D0–D7/A0–A7/PC/SR, and the existing sentinel-write detection (the same condition the run loop already uses — reuse `hit_sentinel()` / the sentinel address compare already in the file). Stop the loop on: sentinel hit, optional `--stop-pc`, or `--max-cycles` exhausted (count instructions or accumulate `step_one()`'s returned cycles).

Trace line format (one line per instruction, AFTER it executes — i.e. post-instruction architectural state), written to the `--trace` file:
```
step pc=0x........ sr=0x.... d0=0x........ d1=0x........ ... d7=0x........ a0=0x........ ... a7=0x........
```
Use lowercase hex, fixed `0x%08x` for 32-bit regs and `0x%04x` for SR. CCR is the low 5 bits of SR; the Scala side derives it (`ccr = sr & 0x1f`), so it need not be emitted separately. Keep the existing final-state `--out` behavior intact and unchanged when `--trace` is absent. `--trace` may be combined with `--out` (emit both); the trace file is the new artifact.

After implementing, rebuild: `make -C tools/musashi`. Smoke it:
```bash
tools/musashi/musashi_run --bin /tmp/t.bin --out /tmp/t.out --trace /tmp/t.trace \
  --load-addr 0x40800000 --initial-sp 0x00100000 --sentinel 0xFFFF0000 --max-cycles 50000
head /tmp/t.trace
```
Expected: `/tmp/t.trace` has one `step pc=... d0=...` line per executed instruction.

- [ ] **Step 4: Write `OracleStep.scala` (record + parser)**

```scala
package m68k040.oracle

import scala.io.Source
import java.nio.file.Path

/** One retired-instruction record from the Musashi per-instruction trace.
  * Post-instruction architectural state (the lock-step compare granularity). */
final case class OracleStep(pc: Long, sr: Int, d: Vector[Long], a: Vector[Long]) {
  def ccr: Int = sr & 0x1f
}

object OracleStep {
  private val Hex = raw"0x([0-9a-fA-F]+)".r
  private def hex(s: String): Long = java.lang.Long.parseLong(s.stripPrefix("0x"), 16)

  /** Parse a trace file written by musashi_run --trace. One OracleStep per line. */
  def parseTrace(path: Path): Either[OracleError, Vector[OracleStep]] = {
    val src = Source.fromFile(path.toFile)
    try {
      val steps = src.getLines().filter(_.startsWith("step ")).map(parseLine).toVector
      sequence(steps)
    } catch {
      case e: Exception => Left(OracleError(s"failed to parse trace ${path}: ${e.getMessage}"))
    } finally src.close()
  }

  private def parseLine(line: String): Either[OracleError, OracleStep] = {
    val kv = line.split("\\s+").flatMap { tok =>
      val i = tok.indexOf('=')
      if (i > 0) Some(tok.substring(0, i) -> tok.substring(i + 1)) else None
    }.toMap
    def get(k: String): Either[OracleError, Long] =
      kv.get(k).map(hex).toRight(OracleError(s"trace line missing $k: $line"))
    for {
      pc <- get("pc")
      sr <- get("sr")
      d  <- sequence((0 until 8).map(i => get(s"d$i")).toVector)
      a  <- sequence((0 until 8).map(i => get(s"a$i")).toVector)
    } yield OracleStep(pc, sr.toInt & 0xffff, d, a)
  }

  private def sequence[A](xs: Vector[Either[OracleError, A]]): Either[OracleError, Vector[A]] =
    xs.foldRight(Right(Vector.empty): Either[OracleError, Vector[A]]) { (e, acc) =>
      for { x <- e; rest <- acc } yield x +: rest
    }
}
```

- [ ] **Step 5: Add `assembleAndTrace` to `Musashi.scala`.**

READ the current `Musashi.scala`. Add a public method `assembleAndTrace` that mirrors `assembleAndRun` but also passes `--trace <tracefile>` to `musashi_run` and returns the parsed steps. Concretely: reuse `ProgramAssembler.assemble`, write the temp `.bin`, build the command with the SAME flags `assembleAndRun` uses PLUS `Seq("--trace", traceFile.toString)`, run it, then `OracleStep.parseTrace(traceFile)`. Signature:
```scala
def assembleAndTrace(
    source: String,
    loadAddress: Long = ProgramAssembler.DefaultLoadAddress,
    initialSp: Long = 0x00100000L,
    stopPc: Option[Long] = None,
    maxCycles: Int = 50000): Either[OracleError, Vector[OracleStep]]
```
Implement it by following the existing `assembleAndRun` structure (temp files in `finally`-deleted, ProcessBuilder, error handling). Delete the trace temp file in the `finally`. Do not break `assembleAndRun`.

- [ ] **Step 6: Run the test, expect PASS.** `~/sbt/bin/sbt "testOnly m68k040.oracle.MusashiTraceSpec"`. If steps are off-by-one (e.g. trace includes a pre-reset record), adjust the C loop to emit only post-instruction records — do NOT weaken the test's D0==1 / D0==3 assertions.

- [ ] **Step 7: Commit**
```bash
git add tools/musashi/musashi_run.cpp src/test/scala/m68k040/oracle/OracleStep.scala src/test/scala/m68k040/oracle/Musashi.scala src/test/scala/m68k040/oracle/MusashiTraceSpec.scala
git commit -m "verif: musashi_run --trace per-instruction mode + OracleStep parser"
```

---

## Task 4: Pure-Scala LockStep comparator

**Files:**
- Create: `src/test/scala/m68k040/lockstep/CommitObservation.scala`
- Create: `src/test/scala/m68k040/lockstep/LockStep.scala`
- Test: `src/test/scala/m68k040/lockstep/LockStepSpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package m68k040.lockstep

import m68k040.oracle.OracleStep
import org.scalatest.funsuite.AnyFunSuite

class LockStepSpec extends AnyFunSuite {
  // Musashi steps: D0 goes 1, then 3; a7 unchanged; PCs advance.
  private val steps = Vector(
    OracleStep(0x40800002L, 0x2700, Vector(1,0,0,0,0,0,0,0), Vector.fill(8)(0x100000L)),
    OracleStep(0x40800004L, 0x2700, Vector(3,0,0,0,0,0,0,0), Vector.fill(8)(0x100000L))
  )

  private def commitFrom(s: OracleStep, regId: Int, regVal: Long): CommitObservation =
    CommitObservation(pc = s.pc, archRegId = regId, archRegWrite = regVal,
      archRegValid = true, ccr = s.ccr, memAddr = 0, memData = 0, memWrite = false)

  test("matching DUT commits pass lock-step") {
    val dut = Vector(commitFrom(steps(0), 0, 1L), commitFrom(steps(1), 0, 3L))
    val r = LockStep.compare(dut, steps)
    assert(r.ok, s"expected match, got: ${r.firstDivergence}")
  }

  test("a wrong register value is pinpointed at the right index") {
    val dut = Vector(commitFrom(steps(0), 0, 1L), commitFrom(steps(1), 0, 99L)) // wrong: 99 != 3
    val r = LockStep.compare(dut, steps)
    assert(!r.ok)
    assert(r.firstDivergence.exists(_.index == 1), s"divergence should be at index 1: ${r.firstDivergence}")
    assert(r.firstDivergence.exists(_.detail.contains("reg")), s"detail should mention reg: ${r.firstDivergence}")
  }

  test("a PC divergence is detected") {
    val dut = Vector(commitFrom(steps(0), 0, 1L), commitFrom(steps(1).copy(pc = 0xdeadL), 0, 3L))
    val r = LockStep.compare(dut, steps)
    assert(!r.ok)
    assert(r.firstDivergence.exists(d => d.index == 1 && d.detail.toLowerCase.contains("pc")))
  }

  test("length mismatch is reported") {
    val dut = Vector(commitFrom(steps(0), 0, 1L)) // only one commit vs two steps
    val r = LockStep.compare(dut, steps)
    assert(!r.ok)
    assert(r.firstDivergence.exists(_.detail.toLowerCase.contains("count") ||
                                    _.detail.toLowerCase.contains("length")))
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** — `CommitObservation`/`LockStep` undefined.

- [ ] **Step 3: Write `CommitObservation.scala`**

```scala
package m68k040.lockstep

/** DUT-side per-retired-instruction observation, captured from the CommitTrace
  * SpinalHDL port. One per cycle where CommitTrace.fire is high. Mirrors the
  * lock-step-relevant CommitTrace fields. */
final case class CommitObservation(
    pc:           Long,
    archRegId:    Int,
    archRegWrite: Long,
    archRegValid: Boolean,
    ccr:          Int,
    memAddr:      Long,
    memData:      Long,
    memWrite:     Boolean
)
```

- [ ] **Step 4: Write `LockStep.scala`**

```scala
package m68k040.lockstep

import m68k040.oracle.OracleStep

final case class Divergence(index: Long, detail: String)
final case class LockStepResult(ok: Boolean, firstDivergence: Option[Divergence], matched: Long)

/** Pure-Scala instruction-level lock-step comparator. Aligns the DUT's retired
  * commits with Musashi's per-instruction steps positionally and reports the
  * first divergence. Granularity = one retired instruction (spec invariant #5). */
object LockStep {
  def compare(dut: Seq[CommitObservation], oracle: Seq[OracleStep]): LockStepResult = {
    val n = math.min(dut.size, oracle.size)
    var i = 0
    while (i < n) {
      val c = dut(i)
      val s = oracle(i)
      val diff: Option[String] =
        if (c.pc != s.pc)
          Some(f"pc: dut=0x${c.pc}%08x oracle=0x${s.pc}%08x")
        else if (c.ccr != s.ccr)
          Some(f"ccr: dut=0x${c.ccr}%02x oracle=0x${s.ccr}%02x")
        else if (c.archRegValid && {
                   val id = c.archRegId
                   val expected = if (id < 8) s.d(id) else s.a(id - 8)
                   (c.archRegWrite & 0xffffffffL) != (expected & 0xffffffffL)
                 }) {
          val id = c.archRegId
          val expected = if (id < 8) s.d(id) else s.a(id - 8)
          val name = if (id < 8) s"D$id" else s"A${id - 8}"
          Some(f"reg $name: dut=0x${c.archRegWrite & 0xffffffffL}%08x oracle=0x${expected & 0xffffffffL}%08x")
        } else None
      diff match {
        case Some(d) => return LockStepResult(ok = false, Some(Divergence(i.toLong, d)), matched = i.toLong)
        case None    => i += 1
      }
    }
    if (dut.size != oracle.size)
      LockStepResult(ok = false,
        Some(Divergence(n.toLong, s"commit count mismatch: dut=${dut.size} oracle=${oracle.size}")),
        matched = n.toLong)
    else
      LockStepResult(ok = true, None, matched = n.toLong)
  }
}
```

- [ ] **Step 5: Run the test, expect PASS** (4 tests). `~/sbt/bin/sbt "testOnly m68k040.lockstep.LockStepSpec"`.

- [ ] **Step 6: Commit**
```bash
git add src/test/scala/m68k040/lockstep/CommitObservation.scala src/test/scala/m68k040/lockstep/LockStep.scala src/test/scala/m68k040/lockstep/LockStepSpec.scala
git commit -m "verif: pure-Scala instruction-level lock-step comparator"
```

---

## Task 5: SpinalSim CommitTrace capture + synthetic producer + end-to-end self-test

**Files:**
- Create: `src/main/scala/m68k040/verif/TraceReplay.scala`
- Create: `src/test/scala/m68k040/lockstep/CommitTraceCapture.scala`
- Test: `src/test/scala/m68k040/lockstep/EndToEndLockStepSpec.scala`

- [ ] **Step 1: Write the failing test (tagged VerilatorTest — it compiles RTL)**

```scala
package m68k040.lockstep

import m68k040.{M68kParams, VerilatorTest}
import m68k040.oracle.{Musashi, OracleStep}
import m68k040.verif.TraceReplay
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class EndToEndLockStepSpec extends AnyFunSuite {
  private val src =
    """    moveq #1,%d0
      |    moveq #2,%d1
      |    add.l %d1,%d0
      |    move.l %d0,0xFFFF0000
      |""".stripMargin

  /** Drive a sequence of CommitObservations through the TraceReplay DUT and
    * capture what comes out of its CommitTrace port. */
  private def driveAndCapture(commits: Seq[CommitObservation]): Seq[CommitObservation] =
    SimConfig.withVerilator.compile(TraceReplay()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val captured = CommitTraceCapture.start(dut.io.out, dut.clockDomain)
      dut.io.in.fire #= false
      dut.clockDomain.waitSampling()
      for (c <- commits) {
        dut.io.in.fire         #= true
        dut.io.in.pc           #= c.pc
        dut.io.in.archRegId    #= c.archRegId
        dut.io.in.archRegWrite #= c.archRegWrite
        dut.io.in.archRegValid #= c.archRegValid
        dut.io.in.ccr          #= c.ccr
        dut.io.in.memWrite     #= c.memWrite
        dut.clockDomain.waitSampling()
      }
      dut.io.in.fire #= false
      dut.clockDomain.waitSampling(3)
      captured.result()
    }

  private def commitsFromOracle(steps: Seq[OracleStep]): Seq[CommitObservation] =
    steps.map(s => CommitObservation(s.pc, archRegId = 0, archRegWrite = s.d(0),
      archRegValid = true, ccr = s.ccr, memAddr = 0, memData = 0, memWrite = false))

  test("end-to-end: replayed correct trace passes lock-step against Musashi", VerilatorTest) {
    val steps = Musashi.assembleAndTrace(src).fold(e => fail(e.reason), identity)
      .take(3) // the three register-affecting instructions
    val captured = driveAndCapture(commitsFromOracle(steps))
    val r = LockStep.compare(captured, steps)
    assert(r.ok, s"expected match; divergence=${r.firstDivergence}")
  }

  test("end-to-end: a corrupted replayed commit is pinpointed", VerilatorTest) {
    val steps = Musashi.assembleAndTrace(src).fold(e => fail(e.reason), identity).take(3)
    val good = commitsFromOracle(steps).toVector
    val corrupted = good.updated(2, good(2).copy(archRegWrite = 0x999L)) // wrong D0 after add
    val captured = driveAndCapture(corrupted)
    val r = LockStep.compare(captured, steps)
    assert(!r.ok && r.firstDivergence.exists(_.index == 2),
      s"expected divergence at index 2, got ${r.firstDivergence}")
  }
}
```

- [ ] **Step 2: Run it, expect FAIL** — `TraceReplay` / `CommitTraceCapture` undefined. Run: `~/sbt/bin/sbt "testOnly m68k040.lockstep.EndToEndLockStepSpec"`.

- [ ] **Step 3: Write `TraceReplay.scala` (synthetic CommitTrace producer — real RTL)**

```scala
package m68k040.verif

import m68k040.types.CommitTrace
import spinal.core._

/** Minimal stand-in for the (not-yet-built) commit stage: a registered
  * passthrough of a CommitTrace stream. Lets the verification harness exercise
  * the CommitTrace port + SpinalSim capture path end-to-end before the real
  * pipeline exists. Replace with the real commit plugin's trace port later. */
case class TraceReplay() extends Component {
  val io = new Bundle {
    val in  = in(CommitTrace())
    val out = out(CommitTrace())
  }
  io.out := RegNext(io.in)
}
```

- [ ] **Step 4: Write `CommitTraceCapture.scala` (SpinalSim sampling helper)**

```scala
package m68k040.lockstep

import m68k040.types.CommitTrace
import spinal.core.ClockDomain
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer

/** Samples a CommitTrace port once per clock and records a CommitObservation on
  * every cycle where `fire` is high. Use inside a doSim block:
  *   val cap = CommitTraceCapture.start(dut.io.out, dut.clockDomain)
  *   ... run sim ...
  *   val observed = cap.result() */
object CommitTraceCapture {
  final class Handle(buf: ArrayBuffer[CommitObservation]) {
    def result(): Seq[CommitObservation] = buf.toVector
  }

  def start(port: CommitTrace, cd: ClockDomain): Handle = {
    val buf = ArrayBuffer.empty[CommitObservation]
    cd.onSamplings {
      if (port.fire.toBoolean) {
        buf += CommitObservation(
          pc           = port.pc.toLong,
          archRegId    = port.archRegId.toInt,
          archRegWrite = port.archRegWrite.toLong,
          archRegValid = port.archRegValid.toBoolean,
          ccr          = port.ccr.toInt,
          memAddr      = port.memAddr.toLong,
          memData      = port.memData.toLong,
          memWrite     = port.memWrite.toBoolean
        )
      }
    }
    new Handle(buf)
  }
}
```

- [ ] **Step 5: Run the test, expect PASS** (2 VerilatorTest tests). Run: `~/sbt/bin/sbt "testOnly m68k040.lockstep.EndToEndLockStepSpec"`. This compiles RTL via Verilator (slower; allow up to 600000 ms).
  - If capture is off-by-one (the `RegNext` delays `out` one cycle relative to the poked `in`), that is expected and fine — the capture samples whatever cycles `fire` is high on `out`; the count of fired commits still equals the number of driven commits. If the counts differ, adjust the testbench's trailing `waitSampling(3)` so all registered commits drain, rather than changing the comparator.
  - If `withVerilator` is unavailable in the environment, fall back to `SimConfig.compile(...)` (default backend) — but Verilator is installed (5.032), so prefer it.

- [ ] **Step 6: Confirm fast gate still excludes these.** Run: `make SBT=~/sbt/bin/sbt test-fast` — the two new VerilatorTest tests must NOT run in the fast gate (still 10 + the new non-Verilator tests from Tasks 2-4; the Verilator ones are excluded). Then `make SBT=~/sbt/bin/sbt test-verilator` runs the 2 end-to-end tests.

- [ ] **Step 7: Commit**
```bash
git add src/main/scala/m68k040/verif/TraceReplay.scala src/test/scala/m68k040/lockstep/CommitTraceCapture.scala src/test/scala/m68k040/lockstep/EndToEndLockStepSpec.scala
git commit -m "verif: SpinalSim CommitTrace capture + synthetic replay + end-to-end lock-step self-test"
```

---

## Self-review notes (author-completed)

- **Spec coverage (ch 11 + invariant #5):** lock-step vs reference model → Tasks 2–5 (Musashi oracle + per-instruction trace + comparator + capture); compare granularity = retired instruction (OracleStep ↔ CommitObservation, Task 4); designed-in CommitTrace port consumed by the harness → Task 5 capture. Directed-test plumbing (assemble arbitrary asm) → Task 2 ProgramAssembler. The randomized generator and the "boot real software" acceptance path are explicitly deferred to a later plan (they need either the real core or a fuzz driver) — noted here so they aren't assumed delivered.
- **No pipeline dependency:** the harness is validated now via `TraceReplay` (synthetic producer) using Musashi's own trace as the known-good DUT stand-in. When the real commit stage lands, swap `TraceReplay` for the core and the same `CommitTraceCapture` + `LockStep` run unchanged.
- **Type consistency:** `CommitObservation` field names/types match the peeks in `CommitTraceCapture` and the `CommitTrace` bundle fields; `OracleStep.d/a` indexing matches `LockStep`'s `id<8 ? d(id) : a(id-8)`; `assembleAndTrace` signature matches its test call.
- **Placeholder scan:** the only non-verbatim task is the C `--trace` extension (Task 3 Step 3), which gives the exact line format, the precise `MusashiRef` API to use (`step_one`/`get_reg`), the stop conditions, and a gating test — the implementer wires it against the readable existing `musashi_run.cpp`. Not a placeholder: it's a bounded extension with a hard acceptance test.
- **pm_gate fix:** Task 1 adds the `test-verilator` Makefile target that `tools/pm_gate.sh` already references (foundation follow-up #2, resolved).

## Downstream (not this plan)
Randomized instruction-stream generator with self-checking; wiring `CommitTraceCapture` to the real commit plugin (first backend plan that produces commits); boot-real-software acceptance harness; memory-write lock-step (extend OracleStep with per-step write events — the C side already exposes `final_mem_write_events()`).
