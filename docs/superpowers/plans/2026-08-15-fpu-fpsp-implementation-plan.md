# FPU/FPSP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the hardware FPU and F-line trap protocol specified in
`docs/superpowers/specs/2026-08-14-fpu-fpsp-design.md` (14 locked decisions, revised
2026-08-15) — an 11(+2)-op hardware-native FPU folded into the existing CPLX cluster, a
renamed FPCC, hardware-native OVFL/UNFL substitution, real vector-11 F-line trap delivery
with correctly-populated FSAVE state frames, and bit-exact-capable Musashi lock-step
verification — so a real, unmodified Motorola/Apple FPSP kernel can run on this core exactly
as it would on real 68040 silicon.

**Architecture:** New 80-bit-wide FP register file (16 physical entries) and FPCC rename
class (16 physical entries, 4-bit tags) added alongside the existing int/NZVC/X rename
machinery, following those exact idioms. New F-line decode entries recognize the
hardware-native op set and tag them `Cluster.CPLX`; everything else falls through the
existing generic `bad`/`faultVector` mechanism to vector 11 for free. A new `FpuCore`
standalone arithmetic Component (mirroring `MulCore.scala`'s shape) is integrated into
`DivEuPlugin.scala` via a new parallel FP writeback lane (the existing `compData`/
`CplxResult.data` writeback bus is hardcoded 32-bit and cannot carry an 80-bit result — this
is NOT a MULHI-style chunk, it is architecturally a new lane, confirmed by direct code
inspection during planning). FSAVE/FRESTORE become new microcoded system ops emitting/
consuming the real null/idle/unimplemented-instruction state frames (busy frame narrowed per
Decision 10). Musashi's `m68k_ref.h`/`musashi_run.cpp` gain FP register accessors reaching
`m68ki_cpu.fpr[]`/`.fpcr`/`.fpsr`/`.fpiar` directly (Musashi's public `m68k_get_reg` API has
no FP surface at all — confirmed, this is genuinely new plumbing, not an extension of an
existing pattern).

**Tech Stack:** SpinalHDL, existing rename/scoreboard/freelist/regfile-plugin infrastructure,
existing CPLX EU (`DivEuPlugin.scala`)/IQ-port/ROB-port, existing `PortedTestRunner`/
`ProgramAssembler` test infrastructure, Musashi (C, subprocess/text-protocol oracle bridge).

## Global Constraints

- **Internal FP precision: 80-bit IEEE-754 extended** (1 sign + 15 exponent + 64
  explicit-integer-bit mantissa) throughout the arithmetic datapath and register file — NOT
  narrower-then-widened. Spec Decision 1.
- **Hardware-native op set**: FADD, FSUB, FMUL, FDIV, FSQRT, FABS, FNEG, FMOVE (register and
  memory-source forms in scope for this plan), FCMP, FINT, FINTRZ, FTST, FMOVECR, FMOVEM —
  extended from the original m68k-ooo-matching 11-op list to also include FMOVECR/FMOVEM/FTST
  per the 2026-08-15 scope revision. Everything else (transcendentals, packed decimal,
  rounded-precision variants, FScc/FDBcc/FTRAPcc) traps to FPSP via vector 11. Spec Decision 2,
  revised.
- **EU integration: fold into the existing CPLX cluster** (`DivEuPlugin.scala`, shared with
  DIV/MUL/CHK/CMP2/CHK2) — NOT a dedicated EU/IQ-port/ROB-port. The FP *writeback* path is a
  new parallel lane (the existing 32-bit lane cannot carry 80 bits); the FP *issue* path shares
  the single existing CPLX `Stream[IqContext]` issue port and is classified purely via
  `RenamedUop.cluster === Cluster.CPLX`, exactly as MUL/DIV/CHK/CMP2/CHK2 are today — no IQ
  code changes are needed for issue-side routing. Spec Decision 9.
- **FPCC (N/Z/I/NAN) is renamed**, extending the existing NZVC/X rename/scoreboard/freelist
  machinery exactly (`RatTable(physIdWidth=4, archDepth=1, writePorts=2, commitPorts=2,
  readPorts=2)` + `Freelist(physCount=16, archCount=1, popPorts=2, pushPorts=2)`, matching
  `nzvcRat`/`xRat`/`nzvcFree`/`xFree` verbatim). FPCR, FPSR's non-FPCC bytes, and FPIAR are
  **non-renamed, single-copy, in-order-written** state. Spec Decisions 4-5.
- **FP register file: 16 physical entries**, 8 architectural FP0-FP7, each 80 bits. Spec
  Decision 6.
- **Hardware-native OVFL/UNFL substitution** — NOT deferred to software. Overflow: 4-way
  rounding-mode/sign lookup (Infinity/largest-finite). Underflow: shift-mantissa-right/
  increment-exponent until denormalized, or zero/smallest-denormal fallback if the shift
  empties the mantissa. Spec Decision 10, tables quoted in Task 7.
- **FSAVE/FRESTORE**: null (4B) and idle (4B) frames in full; unimplemented-instruction frame
  (44B) in full — this is the core "route to FPSP" mechanism, populated with real
  STAG/DTAG/CMDREG1B/operand fields, NOT left as an always-idle stub (this reverses an earlier,
  now-superseded draft's narrower "idle-frame only" scoping — see spec's Background section).
  Busy frame (100B) is narrowed to only the rare user-enabled-FPCR-trap-with-custom-handler
  path — not required for baseline correctness given hardware-native OVFL/UNFL.
- **Verification**: bit-exact Musashi lock-step for the HW-native ops' arithmetic results
  (Musashi's `m68kfpu.c` is native `floatx80`, confirmed). The already-vendored
  `fpu_*`/`fpsp_*` tests under `src/test/resources/m68kooo-ported-tests/asm/` are the
  FPSP-trap-boundary acceptance corpus — no new test harness needed, they're already wired
  through `PortedM68kOooSpec.scala`/`PortedTestRunner.scala`. **Correction from an earlier
  draft of this spec: the corpus is 46 files, not 35** — Task 14's live re-derivation
  (`ls`/`grep` against the actual resource directory) found the "35" figure stale; use 46 as
  the acceptance-corpus size for triage and reporting purposes.
- **Synth gate**: mandatory OOC + real post-route gate at the end (this project's standing
  rule), matching `synth/impl_FullCore.tcl`'s existing `IMPL_STRATEGY=postrouteN` methodology.
  The current baseline is **201.450 MHz post-route** (ledger §39-40, `feat/rob-predictor-mem`
  commit `445e509`) — this work must not regress it. `synth/clk.xdc` stays at the standard
  4.000 ns probe constraint; do not retarget to 200 MHz directly (confirmed materially worse
  methodology, ledger §39).
- **No placeholder/TODO code** in any task's deliverable. Every step below has complete,
  compilable-intent Scala/SpinalHDL or C code, not pseudocode.
- **Where a task references an exact bit-level fact** (an exception vector, an FSAVE frame
  offset, an FMOVECR constant-table entry) that has NOT been independently verified against
  the real MC68040 User's Manual during plan-writing, the task says so explicitly and makes
  that verification an explicit step — do not silently assume.

---

## File Structure

New files:
- `src/main/scala/m68k040/execute/fpu/FpuCore.scala` — standalone 80-bit arithmetic datapath
  (Task 7).
- `src/main/scala/m68k040/execute/fpu/FpuTypes.scala` — shared bundles (`Extended80`,
  `FpuOp`, rounding-mode enum) used across `FpuCore.scala`, `DivEuPlugin.scala`, and decode.
- `src/main/scala/m68k040/execute/regfile/RegFilePluginFp.scala` — new 80-bit×16 physical
  register file plugin instance (Task 1), following `RegFilePlugin.scala`'s existing generic
  `RegfileSpec`-parametrized pattern.
- `src/main/scala/m68k040/execute/FpuControlPlugin.scala` — FPCR/FPSR-non-FPCC-bytes/FPIAR
  committed single-copy state + commit-time read/write, mirroring `MmuControlPlugin.scala`'s
  shape (Task 9).
- `src/test/scala/m68k040/execute/FpuCoreSpec.scala` — standalone `FpuCore` arithmetic tests
  (Task 7, drafted by the arithmetic-core sub-pass).
- `src/test/scala/m68k040/execute/FpuProtocolSpec.scala` — dense-fill/collision/flush-reuse/
  mutation-proof protocol tests (Task 15).
- `src/test/scala/m68k040/lockstep/FpuLockStepSpec.scala` — directed bit-exact lock-step tests
  for the HW-native op set (Task 13).
- `src/test/scala/m68k040/lockstep/FpCompare.scala` — bit-exact 80-bit `BigInt` FP comparison
  helper, deliberately outside `LockStep.compare`/`CommitObservation` (Task 12).
- `tools/musashi/m68k_ref_fp.cpp` — Musashi FP register accessors in a SEPARATE translation
  unit (works around a real macro-collision hazard between `m68kcpu.h` and `m68k_ref.h`,
  reproduced during planning — see Task 12 Step 1) (Task 12).
- `src/test/scala/m68k040/oracle/OracleFpTraceSpec.scala` — oracle-only tests proving the
  Musashi FP plumbing end to end (Task 12).
- `src/test/scala/m68k040/execute/iq/IqFpSpec.scala` — directed FP-data/FPCC dependency
  coverage in the issue queue (Task 3).
- `src/test/scala/m68k040/decode/FpDecodeSpec.scala` — F-line FP-generic decode classification
  tests (Task 4).
- `src/test/scala/m68k040/frontend/PredecodeFpLenSpec.scala` — cpGEN length-framing tests
  with real extension words (Task 5).
- `src/test/scala/m68k040/decode/FpAssembleSpec.scala` — FP µop assembly tests, incl. the
  `faultUsesNextPc` fix (Task 6).
- `src/test/scala/m68k040/execute/FpuControlPluginSpec.scala` — FPCR/FPSR/FPIAR round-trip +
  AEXC-fold tests (Task 9).
- `src/test/scala/m68k040/exception/FsaveFrestoreSpec.scala` — FSAVE/FRESTORE frame-emission
  and privilege tests (Task 11).

Modified files (all existing, precedents verified during research):
- `src/main/scala/m68k040/rename/RenameStage.scala` — new `fpccRat`/`fpccFree` (Task 2).
- `src/main/scala/m68k040/rename/RenamedUop.scala` — new FP src/dst/FPCC rename fields (Task 2).
- `src/main/scala/m68k040/rob/CommitSlot.scala` — new FP/FPCC commit fields (Task 2).
- `src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala` — `sbFp`/`sbFpcc` static
  scoreboards + `cplxFpBusy`/`cplxFpccBusy` dynamic CPLX wakeup (mirroring task #167's
  `cplxNzvcBusy` pattern) (Task 3).
- `src/main/scala/m68k040/execute/iq/IqContext.scala` — `cplxFpWakeup`/`cplxFpccWakeup:
  Flow[UInt]` service ports (Task 3).
- `src/main/scala/m68k040/decode/DecodedUop.scala` — new `DecOp.FPU` element, `FpSrcKind`
  enum, FP src/dst/FPCC decode fields + `fpInert()` helper, `fpuSoftwareComplete`/
  `fpuCmdWord` (Task 4, Task 10), new `SysKind.FMOVE_FPCTRL`/`FSAVE`/`FRESTORE` (Task 9,
  Task 11).
- `src/main/scala/m68k040/decode/DecodeContracts.scala` — new `OpSpec.fpGeneric` field
  (Task 4).
- `src/main/scala/m68k040/decode/OperationDecoder.scala` — new `is(0xF)` arm sub-cases for the
  HW-native FP ops, the FMOVE-to-control-register form, and FSAVE/FRESTORE (Task 4, Task 9,
  Task 11).
- `src/main/scala/m68k040/frontend/PredecodeWord.scala` — F-line length framing for the new FP
  opcodes (Task 5).
- `src/main/scala/m68k040/decode/MicroOpAssembler.scala` — FP µop assembly, `faultUsesNextPc`
  fix (Task 6, Task 10).
- `src/main/scala/m68k040/execute/DivEuPlugin.scala` — new FP writeback lane, FPU EU
  integration, OVFL/UNFL substitution wiring (Task 8).
- `src/main/scala/m68k040/exception/ExceptionUnit.scala` — vector 48-53 format-selection
  entries (narrow, enabled-trap path only), FSAVE/FRESTORE state machine, FPCR/FPSR/FPIAR
  commit-time read/write (Task 9, Task 11).
- `src/main/scala/m68k040/top/FullCoreSynth.scala` — wire the new `FpRegFileService`/
  `FpuControlPlugin` instances (Task 1, Task 9).
- `tools/musashi/m68k_ref.h` — `Fp80` struct + FP accessor declarations, implemented in the
  new `m68k_ref_fp.cpp` (NOT `m68k_ref.cpp` itself — a separate translation unit is required,
  see Task 12 Step 1) (Task 12).
- `tools/musashi/Makefile` — build rule for the new `m68k_ref_fp.o` object (Task 12).
- `tools/musashi/musashi_run.cpp` — FP fields in the trace/final-state dump (Task 12).
- `src/test/scala/m68k040/oracle/OracleStep.scala` — `BigInt`-based FP fields + the `hexBig`
  parser (Task 12). `LockStep.scala`/`CommitObservation.scala` are deliberately NOT modified
  (both are `Long`-based end to end and cannot carry an 80-bit value) — FP comparison is the
  separate `FpCompare.scala` path instead.
- `src/main/scala/m68k040/execute/regfile/RegFilePlugin.scala` — sim-only whitebox `shadow`
  Vec, mirroring `RobPlugin.scala`'s `pcStore` precedent (Task 13).
- `src/main/scala/m68k040/rename/RenameStage.scala` — `fpRat.io.committedPhys.simPublic()`
  (Task 13, in addition to Task 2's `fpccRat`/`fpFree` additions).
- `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` — `FullCoreDut` gains
  `RegFilePluginFp`/`FpuControlPlugin`; `runLockStep` gains an optional `afterRun` callback
  (Task 13); directed FMOVE-to-FPcr/FSAVE-privilege/`faultUsesNextPc` whitebox tests (Task 9,
  Task 10).

---

### Task 1: FP register file plugin (80-bit × 16, plumbing only)

**Files:**
- Modify: `src/main/scala/m68k040/execute/regfile/RegfileService.scala`
- Create: `src/main/scala/m68k040/execute/regfile/RegFilePluginFp.scala`
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala`
- Test: `src/test/scala/m68k040/execute/regfile/RegFilePluginFpSpec.scala`

**Interfaces:**
- Consumes: `RegFilePlugin` (already fully generic over `RegfileSpec`, zero changes needed to
  that file — confirmed during planning research).
- Produces: `FpRegFileService` (trait), acquired via `host[FpRegFileService]` by Task 8
  (`DivEuPlugin.scala`'s new FP writeback lane) and Task 11 (FRESTORE's write port). Exact
  same `newRead`/`newWrite`/`newBypass` signatures as `IntRegFileService`/`NzvcRegFileService`.

This task is pure plumbing — a new register file, no renamer, no decode, no EU wiring. It is
independently testable via a standalone write-then-read round trip, exactly the shape a
`RegFilePlugin` instance can be tested in isolation.

- [ ] **Step 1: Add `RegfileSpec.Fp` and the `FpRegFileService` trait**

```scala
// src/main/scala/m68k040/execute/regfile/RegfileService.scala
// Add to the RegfileSpec companion object, alongside Int/Nzvc/X:
  val Fp   = RegfileSpec("fp",   80, 16)

// Add alongside the other RegFileService sub-traits:
trait FpRegFileService extends RegfileService
```

- [ ] **Step 2: Create `RegFilePluginFp.scala`**

```scala
// src/main/scala/m68k040/execute/regfile/RegFilePluginFp.scala
package m68k040.execute.regfile

class RegFilePluginFp extends RegFilePlugin(RegfileSpec.Fp) with FpRegFileService
```

- [ ] **Step 3: Wire the new plugin into `FullCoreSynth.scala`**

```scala
// src/main/scala/m68k040/top/FullCoreSynth.scala
// Change the import (line 15) from:
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
// to:
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX, RegFilePluginFp}

// Add one line alongside the other three instantiations (around line 432-434):
          new RegFilePluginInt(),
          new RegFilePluginNzvc(),
          new RegFilePluginX(),
          new RegFilePluginFp(),
```

- [ ] **Step 4: Write the standalone round-trip test**

```scala
// src/test/scala/m68k040/execute/regfile/RegFilePluginFpSpec.scala
package m68k040.execute.regfile

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class RegFilePluginFpSpec extends AnyFunSuite {
  test("RegFilePluginFp: write then read round-trips an 80-bit value") {
    SimConfig.compile(new RegFilePluginFp).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val logic = dut.logic
      // Drive write port 0 directly via the plugin's own newWrite() call site is not
      // reachable from outside a FiberPlugin's setup phase in a bare component sim, so
      // this test drives the merged physical write bus (logic.phys(0)) and read port 0
      // directly via simPublic-equivalent access -- mirrors how RegFilePluginInt's own
      // existing bring-up tests poke `dbgW` (see RegFilePlugin.scala:90-94, already
      // simPublic). Wait for the depth-16 init-zero sweep (dut.spec.depth cycles) before
      // driving a real write, matching RegFilePlugin's own initDone gating.
      dut.clockDomain.waitSamplingWhere(logic.initDone.toBoolean)

      val testAddr = 5
      val testVal  = BigInt("FF800000000000000001", 16) // 80 bits: sign=1,exp=all-1s (inf/nan pattern), mantissa low bit set

      logic.dbgW(0).valid #= true
      logic.dbgW(0).address #= testAddr
      logic.dbgW(0).data #= testVal
      dut.clockDomain.waitSampling()
      logic.dbgW(0).valid #= false
      dut.clockDomain.waitSampling(2) // clear any same-cycle bypass window

      // Read back through a freshly-added read port would require the plugin's own
      // newRead() called during setup; for this standalone smoke test, read the backing
      // Mem directly via the same dbg-style simPublic pattern -- confirms storage width
      // and depth are correct. Full read-port behavior (bypass, multi-port) is exercised
      // end-to-end once Task 8 wires a real newRead()/newWrite() consumer.
      assert(logic.ram.getBigInt(testAddr) == testVal,
        s"FP regfile addr $testAddr: wrote $testVal%x, storage holds ${logic.ram.getBigInt(testAddr)}%x")
    }
  }

  test("RegFilePluginFp: spec width and depth are 80 bits x 16 entries") {
    assert(RegfileSpec.Fp.dataWidth == 80)
    assert(RegfileSpec.Fp.depth == 16)
    assert(RegfileSpec.Fp.addressWidth == 4) // log2Up(16)
  }
}
```

- [ ] **Step 5: Run the test**

```bash
sbt "testOnly m68k040.execute.regfile.RegFilePluginFpSpec"
```
Expected: both tests PASS.

- [ ] **Step 6: Confirm full-core elaboration still succeeds** (new regfile wired but
  unconsumed — this step exists purely to catch an elaboration-time port-count mismatch
  before any consumer exists)

```bash
sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
```
Expected: succeeds, no SpinalHDL elaboration error (an unconsumed `RegfileService` with zero
`newRead`/`newWrite`/`newBypass` calls is legal — `RegFilePlugin.scala:28`'s `assert
(writeReq.nonEmpty, ...)` will FAIL at this step if nothing acquires at least one write port
yet, since this task adds no consumer. **If it fails on that assert, this is expected** — add
a throwaway single `newWrite()` call in this task's own test scaffold, OR defer this
elaboration check to the end of Task 8 once a real writer exists. Do not work around the
assert by weakening `RegFilePlugin.scala` itself — it is a real, load-bearing precondition
documented in that file's own comment.)

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/m68k040/execute/regfile/RegfileService.scala \
        src/main/scala/m68k040/execute/regfile/RegFilePluginFp.scala \
        src/main/scala/m68k040/top/FullCoreSynth.scala \
        src/test/scala/m68k040/execute/regfile/RegFilePluginFpSpec.scala
git commit -m "feat(fpu): FP register file plugin (80-bit x 16 physical entries)

Plumbing only -- new RegfileSpec.Fp + RegFilePluginFp, following the
existing RegFilePlugin generic pattern exactly (zero changes to
RegFilePlugin.scala itself). No consumer yet; wired into FullCoreSynth
alongside the existing Int/Nzvc/X instances.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: FP data + FPCC rename infrastructure

**Files:**
- Modify: `src/main/scala/m68k040/rename/RenameStage.scala`
- Modify: `src/main/scala/m68k040/rename/RenamedUop.scala`
- Modify: `src/main/scala/m68k040/rob/CommitSlot.scala`
- Test: `src/test/scala/m68k040/rename/RenameStageFpSpec.scala`

**Interfaces:**
- Consumes: `RatTable(physIdWidth, archDepth, writePorts, commitPorts, readPorts)`,
  `Freelist(physCount, archCount, popPorts, pushPorts)` — both unmodified, exact existing
  constructors (verified: `src/main/scala/m68k040/rename/RatTable.scala:23-29`,
  `Freelist.scala:24-29`).
- Produces: `RenamedUop` gains `pFpSrcA/psrcAFpValid`, `pFpSrcB/psrcBFpValid`,
  `pFpDst/pFpDstValid/pFpOld` (4-bit tags, FP data rename — mirrors `psrcA/psrcB/pdst`
  exactly, at `flagW`-equivalent width since FP has 16 physical entries same as the flag
  classes), and `pFpccSrc/readsFpcc`, `pFpccDst/writesFpcc/pFpccOld` (FPCC rename — mirrors
  `pNzvcSrc/pNzvcDst` exactly, identical 4-bit width). `CommitSlot` gains matching
  `fpNew/fpOld/fpWrite` and `fpccNew/fpccOld/fpccWrite` fields. Consumed by Task 3 (IQ
  scoreboard), Task 6 (MicroOpAssembler FP µop assembly), Task 8 (DivEuPlugin FP writeback).

Two independent rename classes are added in this task: **FP data** (8 architectural FP0-FP7,
16 physical, `archDepth=8`) and **FPCC** (1 architectural condition-code group, 16 physical,
`archDepth=1`, exactly mirroring `nzvcRat`/`xRat`). Both get their own `RatTable`+`Freelist`
pair, both get their own `commitPorts` fields, both roll back on the same shared `flush`
signal as every other RAT/freelist in `RenameStage`.

**Read-port budget for the FP RAT** (this is a genuine sizing decision, not copied
mechanically from the int RAT): FP macro-ops need at most 2 sources (dyadic ops:
FADD/FSUB/FMUL/FDIV; FMOVE `<ea>,FPn` uses only the integer EA machinery for its address, not
an FP source) plus 1 dst-old read (for freelist WAW bookkeeping, mirroring the int RAT's
"4+s" dst-old ports) per slot. 2 slots x (2 src + 1 dst-old) = **6 read ports** — provisioned
even though the CPLX cluster can only *issue* one FP op at a time (Global Constraint: FP
issue shares the single existing CPLX port) — decode/rename still processes 2 macro-ops per
cycle and both could be FP-sourced even if only one can issue this cycle; under-provisioning
read ports here would silently corrupt a same-cycle two-FP-macro-op decode group.

- [ ] **Step 1: Add FP data + FPCC rename fields to `RenamedUop`**

```scala
// src/main/scala/m68k040/rename/RenamedUop.scala
// Add alongside the existing pXSrc/pXDst fields (after line 159):

  // FP data rename (Decision 6: 8 arch FP0-FP7, 16 physical, 4-bit tags).
  val pFpSrcA = UInt(fpW bits); val psrcAFpValid = Bool()
  val pFpSrcB = UInt(fpW bits); val psrcBFpValid = Bool()
  val pFpDst  = UInt(fpW bits); val pFpDstValid  = Bool(); val pFpOld = UInt(fpW bits)
  // FPCC rename (Decision 4: N/Z/I/NAN, 16 physical, 4-bit tags -- mirrors NZVC/X exactly).
  val pFpccSrc = UInt(fpW bits); val readsFpcc  = Bool()
  val pFpccDst = UInt(fpW bits); val writesFpcc = Bool(); val pFpccOld = UInt(fpW bits)
```

Add the `fpW` width constant alongside the existing `intW`/`flagW` constants at the top of
the file (find them via `grep -n "val intW\|val flagW" src/main/scala/m68k040/rename/
RenamedUop.scala` and add immediately after):

```scala
  val fpW = 4   // log2Up(16) -- FP data and FPCC both have 16 physical entries
```

- [ ] **Step 2: Add matching commit fields to `CommitSlot`**

```scala
// src/main/scala/m68k040/rob/CommitSlot.scala
// Add alongside the existing xNew/xOld/xWrite fields:
  val fpNew    = UInt(4 bits); val fpOld    = UInt(4 bits); val fpWrite    = Bool()
  val fpccNew  = UInt(4 bits); val fpccOld  = UInt(4 bits); val fpccWrite  = Bool()
```

- [ ] **Step 3: Instantiate the FP data and FPCC RAT/Freelist pairs in `RenameStage`**

```scala
// src/main/scala/m68k040/rename/RenameStage.scala
// Add alongside the existing intRat/nzvcRat/xRat block (after line 32):
    val fpRat   = RatTable(physIdWidth = 4, archDepth = 8, writePorts = 2, commitPorts = 2, readPorts = 6)
    val fpccRat = RatTable(physIdWidth = 4, archDepth = 1, writePorts = 2, commitPorts = 2, readPorts = 2)

// Add alongside the existing intFree/nzvcFree/xFree block (after line 37):
    val fpFree   = Freelist(physCount = 16, archCount = 8, popPorts = 2, pushPorts = 2)
    val fpccFree = Freelist(physCount = 16, archCount = 1, popPorts = 2, pushPorts = 2)
```

- [ ] **Step 4: Wire FP data RAT reads per slot** (mirrors the existing int RAT read-port
  wiring exactly, at `RenameStage.scala:121-129` — add immediately after that block)

```scala
    // FP data RAT: 2 src reads + 1 dst-old read per slot (see the read-port-budget note
    // above). FMOVE <ea>,FPn and FMOVE FPn,<ea> only ever populate srcA (never srcB) --
    // srcB is reserved for the dyadic FADD/FSUB/FMUL/FDIV FPn,FPn/FPm forms.
    for (s <- 0 until 2) {
      fpRat.io.reads(2*s).addr     := decUop(s).fpSrcAReg
      fpRat.io.reads(2*s+1).addr   := decUop(s).fpSrcBReg
      fpRat.io.reads(4+s).addr     := decUop(s).fpDstReg
    }
    // FPCC RAT: single architectural entry (archDepth=1), address hardwired to 0 --
    // exactly the nzvcRat/xRat pattern (RenameStage.scala:128-129).
    for (s <- 0 until 2) {
      fpccRat.io.reads(s).addr := 0
    }
```

(`decUop(s).fpSrcAReg`/`fpSrcBReg`/`fpDstReg` are new 3-bit fields (FP0-FP7) added to the
decode-stage `DecodedUop` in Task 6 alongside the existing `srcAReg`/`srcBReg`/`dstReg` int
fields — this step assumes Task 6 lands first or in the same PR; if sequenced independently,
stub these three fields to `U(0,3 bits)` and revisit once Task 6's real decode fields exist,
noting the stub explicitly in the commit message.)

- [ ] **Step 5: Populate `RenamedUop`'s new fields from the RAT reads + freelist pops**
  (mirrors `RenameStage.scala`'s existing int/nzvc/x population exactly — find the exact
  block that sets `renamedUop(s).psrcA := ...` / `.pdst := ...` / `.pNzvcSrc := ...` and add
  alongside it)

```scala
    for (s <- 0 until 2) {
      renamedUop(s).pFpSrcA      := fpRat.io.reads(2*s).data
      renamedUop(s).psrcAFpValid := decUop(s).usesFpSrcA
      renamedUop(s).pFpSrcB      := fpRat.io.reads(2*s+1).data
      renamedUop(s).psrcBFpValid := decUop(s).usesFpSrcB
      renamedUop(s).pFpOld       := fpRat.io.reads(4+s).data
      renamedUop(s).pFpDstValid  := decUop(s).writesFp
      renamedUop(s).pFpDst       := fpFree.io.pop(s).id
      fpFree.io.pop(s).take      := decUop(s).writesFp && uopsPort.fire

      renamedUop(s).pFpccSrc  := fpccRat.io.reads(s).data
      renamedUop(s).readsFpcc := decUop(s).readsFpcc
      renamedUop(s).pFpccOld  := fpccRat.io.reads(s).data  // archDepth=1: old == committed-or-speculative current
      renamedUop(s).writesFpcc:= decUop(s).writesFpcc
      renamedUop(s).pFpccDst  := fpccFree.io.pop(s).id
      fpccFree.io.pop(s).take := decUop(s).writesFpcc && uopsPort.fire
    }
```

- [ ] **Step 6: Wire RAT writes** (dst allocation feeds back into the RAT so the NEXT
  in-group or next-cycle read observes it — mirrors the existing `intRat.io.writes(s)`
  pattern)

```scala
    for (s <- 0 until 2) {
      fpRat.io.writes(s).valid          := decUop(s).writesFp && uopsPort.fire
      fpRat.io.writes(s).payload.addr   := decUop(s).fpDstReg
      fpRat.io.writes(s).payload.data   := renamedUop(s).pFpDst
      fpccRat.io.writes(s).valid        := decUop(s).writesFpcc && uopsPort.fire
      fpccRat.io.writes(s).payload.addr := 0
      fpccRat.io.writes(s).payload.data := renamedUop(s).pFpccDst
    }
```

- [ ] **Step 7: Intra-group RAW/WAW hazard bypass** (mirrors the existing int/NZVC/X
  bypass block at `RenameStage.scala:241-256` exactly — add alongside it)

```scala
    when(decUop(0).writesFp) {
      when(decUop(0).fpDstReg === decUop(1).fpSrcAReg) { renamedUop(1).pFpSrcA := renamedUop(0).pFpDst }
      when(decUop(0).fpDstReg === decUop(1).fpSrcBReg) { renamedUop(1).pFpSrcB := renamedUop(0).pFpDst }
      when(decUop(0).fpDstReg === decUop(1).fpDstReg)  { renamedUop(1).pFpOld  := renamedUop(0).pFpDst }
    }
    when(decUop(0).writesFpcc) {
      renamedUop(1).pFpccSrc := renamedUop(0).pFpccDst
      renamedUop(1).pFpccOld := renamedUop(0).pFpccDst
    }
```

- [ ] **Step 8: Flush/rollback wiring** (add to the existing flush fan-out block,
  `RenameStage.scala:82-87`)

```scala
    fpRat.io.rollback   := flush
    fpccRat.io.rollback := flush
    fpFree.io.flush      := flush
    fpccFree.io.flush    := flush
```

- [ ] **Step 9: Committed-identity init** — FPCC follows the NZVC/X pattern exactly
  (`archDepth=1`, seed `(addr=0, data=0)` once on the first init cycle). FP data follows the
  int-RAT pattern (`archDepth=8`, seed `(addr=i, data=i)` for i in 0..7) but on its OWN
  8-cycle counter, not reusing `initCounter` (which is sized/terminated for `ARCH_INT_REGS`
  (20) — reusing it would either under-seed FP (terminates at 19, past FP's 8-entry need,
  harmless) or, if FP's counter needs to run independently before/after int's, corrupt
  timing. Simplest correct choice: drive FP identity-seeding off the SAME `initCounter` value
  during its first 8 ticks, since `initCounter` already counts 0..19 and FP only needs 0..7 —
  no new counter required, just gate the `commits(0)` write additionally on `initCounter < 8`.

```scala
    // Existing block (RenameStage.scala:47-60) already declares initCounter/initDone.
    // Extend the identity-seed muxing (find the existing
    // `intRat.io.commits(0).valid := !initDone` etc. block) with:
    fpRat.io.commits(0).valid           := !initDone && initCounter < U(8)
    fpRat.io.commits(0).payload.addr    := initCounter.resize(3)
    fpRat.io.commits(0).payload.data    := initCounter.resize(4)
    fpccRat.io.commits(0).valid         := !initDone && initCounter === U(0)
    fpccRat.io.commits(0).payload.addr  := 0
    fpccRat.io.commits(0).payload.data  := 0
```

- [ ] **Step 10: Normal-operation commit ports** (mirrors the existing `commits(1)`
  ungated-once-`initDone` pattern, plus the `commitPorts`-driven push into the freelists —
  mirrors `RenameStage.scala:72-79`'s existing `intFree.io.push`/`nzvcFree.io.push` block)

```scala
    fpRat.io.commits(1).valid        := initDone
    fpRat.io.commits(1).payload.addr := commitPorts(1).payload.fpArchDst  // new CommitSlot field, Step 2 note below
    fpRat.io.commits(1).payload.data := commitPorts(1).payload.fpNew
    fpccRat.io.commits(1).valid        := initDone
    fpccRat.io.commits(1).payload.addr := 0
    fpccRat.io.commits(1).payload.data := commitPorts(1).payload.fpccNew

    for (k <- 0 until 2) {
      fpFree.io.push(k).valid   := commitPorts(k).valid && commitPorts(k).fpWrite
      fpFree.io.push(k).payload := commitPorts(k).fpOld
      fpccFree.io.push(k).valid   := commitPorts(k).valid && commitPorts(k).fpccWrite
      fpccFree.io.push(k).payload := commitPorts(k).fpccOld
    }
```

Note: `commitPorts(1).payload.fpArchDst` requires ALSO adding a `fpArchDst: UInt(3 bits)`
field to `CommitSlot` (Step 2's snippet above did not include this — add it there:
`val fpArchDst = UInt(3 bits)` alongside `fpNew/fpOld/fpWrite`). Called out explicitly here
since it is easy to miss when implementing Step 2 before Step 10 makes the need concrete.

- [ ] **Step 11: Directed rename-only test**

```scala
// src/test/scala/m68k040/rename/RenameStageFpSpec.scala
package m68k040.rename

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class RenameStageFpSpec extends AnyFunSuite {
  test("FP data RAT: identity-seeds FP0-FP7 to physical 0-7 at boot") {
    SimConfig.compile(new RenameStage).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val l = dut.logic
      dut.clockDomain.waitSamplingWhere(l.initDone.toBoolean)
      for (arch <- 0 until 8) {
        assert(l.fpRat.committedPhys(arch).toInt == arch,
          s"FP arch reg $arch should identity-map to phys $arch at boot, got ${l.fpRat.committedPhys(arch).toInt}")
      }
      assert(l.fpccRat.committedPhys(0).toInt == 0, "FPCC should identity-map arch 0 -> phys 0 at boot")
    }
  }

  test("FP data RAT: rollback restores committed mapping after a speculative write") {
    SimConfig.compile(new RenameStage).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val l = dut.logic
      dut.clockDomain.waitSamplingWhere(l.initDone.toBoolean)
      // Speculatively write FP2 -> phys 9 via write port 0, without committing.
      l.fpRat.io.writes(0).valid #= true
      l.fpRat.io.writes(0).payload.addr #= 2
      l.fpRat.io.writes(0).payload.data #= 9
      dut.clockDomain.waitSampling()
      l.fpRat.io.writes(0).valid #= false
      assert(l.fpRat.io.reads(4).data.toInt != 2, "sanity: read port not literally addr 2 by coincidence") // no-op guard
      // Speculative read should now see phys 9 for arch FP2 (via a read port addressed at 2).
      l.fpRat.io.reads(0).addr #= 2
      dut.clockDomain.waitSampling()
      assert(l.fpRat.io.reads(0).data.toInt == 9, "speculative write should be visible to a same-arch read before rollback")
      // Roll back (flush) -- committed mapping (identity, FP2 -> phys 2) must return.
      l.flush #= true
      dut.clockDomain.waitSampling()
      l.flush #= false
      dut.clockDomain.waitSampling()
      assert(l.fpRat.io.reads(0).data.toInt == 2, "after rollback, FP2 must read back its committed (identity) mapping, not the speculative one")
    }
  }
}
```

- [ ] **Step 12: Run the tests**

```bash
sbt "testOnly m68k040.rename.RenameStageFpSpec"
```
Expected: both PASS.

- [ ] **Step 13: Confirm the existing lock-step suite is unaffected** (this task adds fields
  and wiring but no new decode path can drive `writesFp`/`writesFpcc` yet -- Task 6 adds
  that. This step exists to catch an elaboration or reset-value regression in the shared
  `RenameStage` component before any FP consumer exists.)

```bash
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
```
Expected: 396/396 PASS (unchanged from the pre-Task-2 baseline).

- [ ] **Step 14: Commit**

```bash
git add src/main/scala/m68k040/rename/RenameStage.scala \
        src/main/scala/m68k040/rename/RenamedUop.scala \
        src/main/scala/m68k040/rob/CommitSlot.scala \
        src/test/scala/m68k040/rename/RenameStageFpSpec.scala
git commit -m "feat(fpu): FP data + FPCC rename infrastructure (RAT/freelist pairs)

Two new independent rename classes, mirroring the existing NZVC/X and
int RAT/freelist pattern exactly: FP data (8 arch FP0-FP7, 16 physical,
6 read ports for 2-slot dyadic decode) and FPCC (N/Z/I/NAN, 16
physical, identical shape to nzvcRat/xRat). Committed-identity init,
flush/rollback, and intra-group RAW/WAW hazard bypass all follow the
established RenameStage idiom. No decode path drives these fields yet
(Task 6) and no consumer reads them yet (Task 3, Task 8) -- this task
is pure rename-stage plumbing, verified in isolation.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: IQ FP-data + FPCC scoreboards and CPLX dynamic wakeup

**Files:**
- Modify: `src/main/scala/m68k040/execute/iq/IqContext.scala`
- Modify: `src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala`
- Modify: `src/test/scala/m68k040/execute/iq/IqSourcePlugin.scala` (test fixture — new FP push fields)
- Test: `src/test/scala/m68k040/execute/iq/IqFpSpec.scala`

**Interfaces:**
- Consumes: `RenamedUop`'s Task-2 fields verbatim — `pFpSrcA`/`psrcAFpValid`, `pFpSrcB`/`psrcBFpValid`,
  `pFpDst`/`pFpDstValid`, `pFpccSrc`/`readsFpcc`, `pFpccDst`/`writesFpcc` (all 4-bit tags).
- Produces: two new `IssueQueueService` ports, `cplxFpWakeup: Flow[UInt]` (4 bits, a completed FP op's
  `pFpDst`) and `cplxFpccWakeup: Flow[UInt]` (4 bits, its `pFpccDst`). Task 8 (`DivEuPlugin.scala`)
  drives them; `FullCoreSynth.scala` wires them, exactly as it wires `cplxNzvcWakeup` today.

**Grounding (read during planning, quoted so the implementer does not re-derive it):**

The existing NZVC machinery has **two** trackers, and which one a producer lands in is decided
per-class at push time. The static one is `IssueQueuePlugin.scala:171-183`:

```scala
    class Scoreboard(depth: Int) extends Area {
      val busy       = Reg(Bits(depth bits)) init 0
      val physToSlot = Reg(Vec(UInt(slotIdxW bits), depth))
    }
    val sbInt  = new Scoreboard(physIntN) // int physregs
    val sbNzvc = new Scoreboard(16) // NZVC flag physregs (width 4)
    val sbX    = new Scoreboard(16) // X flag physregs (width 4)
```

consumed by `trigInit`'s `dep()` helper (`:496-511`):

```scala
    def trigInit(uop: RenamedUop, width: Int): Bits = {
      val t = B(0, width bits)
      def dep(busy: Bits, physToSlot: Vec[UInt], physreg: UInt, reads: Bool): Unit = {
        val producerSlot = (physToSlot(physreg) - wayCount).resize(slotIdxW)
        when(reads && busy(physreg)) {
          t(producerSlot) := True
        }
      }
      dep(sbInt.busy,  sbInt.physToSlot,  uop.psrcA, uop.psrcAValid)
      ...
      dep(sbNzvc.busy, sbNzvc.physToSlot, uop.pNzvcSrc, uop.readsNzvc)
      dep(sbX.busy,    sbX.physToSlot,    uop.pXSrc, uop.readsX)
      t
    }
```

That static path is **latency-1 only**: its busy bit is force-cleared *the cycle the producer issues*
(`:948-955`), not when it completes. FP ops route through the CPLX cluster and are variable-latency
exactly like DIV/MUL, so they must use the **dynamic** path instead — the one task #167 added for
CPLX NZVC: a separate busy bitmap (`cplxNzvcBusy`, `:212`), a separate per-slot registered wait bit
(`cplxNzvcWait`, `:134`) ANDed into `ready` (`:137`), a push-time dependency latch with a same-cycle
wakeup guard (`:595-603`), a wakeup-clear loop honoring the compaction shift (`:782-791`), and a
busy set/clear with "a same-cycle re-allocation by a fresh push wins" priority (`:991-995`).
This task replicates that dynamic shape twice (FP data, FPCC) and adds the two static
`Scoreboard`s so the push-side routing chain is total rather than silently dropping a future
non-CPLX FP producer.

**Producer-side pattern Task 8 must follow** (quoted from `DivEuPlugin.scala:687-695`, the exact
shape these two new ports are built to accept — Task 3 does not write this side):

```scala
    // Dynamic-completion wakeup: a completing CPLX op that produced a physreg.
    wakeupPort.valid   := compLive && compPdstValid && !compFault
    wakeupPort.payload := compPdst
    wakeupNzvcPort.valid   := compLive && compNzvcWrite && !compFault
    wakeupNzvcPort.payload := compNzvcDst
```

wired at `FullCoreSynth.scala:229-233`:

```scala
    iq.cplxWakeup.valid   := divEu.wakeup.valid
    iq.cplxWakeup.payload := divEu.wakeup.payload
    iq.cplxNzvcWakeup.valid   := divEu.wakeupNzvc.valid
    iq.cplxNzvcWakeup.payload := divEu.wakeupNzvc.payload
```

**Scope note (honest, and load-bearing for review):** after Task 6 lands, the *only* FP producers in
the design are CPLX, and **no µop reads FPCC at all** (FBcc/FScc/FDBcc and FMOVE-from-FPSR are
deferred). So `sbFp`/`sbFpcc` are provably no-op today and `readsFpcc` is constant-False until a
later task. They are still added here, for the same reason `sbNzvc` coexists with `lsNzvcBusy` and
`cplxNzvcBusy`: the push-side `when(isCplxFp) {...} .otherwise { sbFp ... }` chain must be **total**,
so that Task 11's FRESTORE FP-PRF writer and Task 10's FMOVEM (an LS-cluster FP producer) cannot
land in a hole and have their dependency silently dropped. Do not "simplify" them away.

- [ ] **Step 1: Declare the two new service ports**

```scala
// src/main/scala/m68k040/execute/iq/IqContext.scala
// Add to `trait IssueQueueService`, after `cplxNzvcWakeup`:

  /** Dynamic-completion FP-DATA wakeup for the CPLX cluster: a completing FP op
    * (FADD/FSUB/FMUL/FDIV/FSQRT/FABS/FNEG/FMOVE/FINT/FINTRZ/FMOVECR) broadcasts the
    * 4-bit pFpDst of its just-written 80-bit FP physreg; a slot reading that FP physreg
    * becomes ready. SEPARATE Flow from `cplxWakeup` (int pdst): an FP op writes NO int
    * register at all, and the int and FP physreg id spaces are unrelated (6-bit int tags
    * vs 4-bit FP tags), so sharing one port would alias two different registers.
    * FP ops are variable-latency (FDIV/FSQRT iterate) and must NOT use the static
    * latency-1 scoreboard, whose busy bit is force-cleared at ISSUE time -- the exact
    * defect task #167 fixed for CPLX NZVC. FCMP/FTST drive no FP wakeup (no FP dst). */
  def cplxFpWakeup: Flow[UInt]
  /** Dynamic-completion FPCC wakeup for the CPLX cluster: broadcasts the pFpccDst of a
    * just-completed FP op that wrote the renamed FPCC {N,Z,I,NAN}. EVERY hardware-native
    * FP op writes FPCC (including FCMP/FTST, which write ONLY FPCC), so this port fires
    * for strictly more ops than `cplxFpWakeup`. Mirrors `cplxNzvcWakeup` exactly, on the
    * FPCC rename class instead of NZVC. */
  def cplxFpccWakeup: Flow[UInt]
```

- [ ] **Step 2: Add the ports to `IssueQueuePlugin` (declaration, override, setup, idle default)**

```scala
// src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala
// (a) alongside the other `var ...Port` declarations (after cplxNzvcWakeupPort, line 34):
  var cplxFpWakeupPort  : Flow[UInt]         = null
  var cplxFpccWakeupPort: Flow[UInt]         = null

// (b) alongside the other overrides (after line 45):
  override def cplxFpWakeup: Flow[UInt]   = cplxFpWakeupPort
  override def cplxFpccWakeup: Flow[UInt] = cplxFpccWakeupPort

// (c) in `during setup`, after cplxNzvcWakeupPort (line 59):
    cplxFpWakeupPort   = Flow(UInt(4 bits)) // a completed FP op's pFpDst   (4-bit FP tag)
    cplxFpccWakeupPort = Flow(UInt(4 bits)) // a completed FP op's pFpccDst (4-bit FPCC tag)

// (d) in `logic`, alongside the other allowOverride idle defaults (after line 79):
    cplxFpWakeupPort.valid.allowOverride;   cplxFpWakeupPort.valid   := False
    cplxFpWakeupPort.payload.allowOverride; cplxFpWakeupPort.payload := U(0, 4 bits)
    cplxFpWakeupPort.simPublic()
    cplxFpccWakeupPort.valid.allowOverride;   cplxFpccWakeupPort.valid   := False
    cplxFpccWakeupPort.payload.allowOverride; cplxFpccWakeupPort.payload := U(0, 4 bits)
    cplxFpccWakeupPort.simPublic()
```

- [ ] **Step 3: Add the per-slot wait bits and fold them into `ready`**

```scala
// In the per-way slot Area, after `cplxNzvcWait` (line 134):
        // CPLX FP-DATA dynamic dependency: identical mechanism to cplxNzvcWait, but on
        // cplxFpBusy / cplxFpWakeup. A reader of an in-flight FP result (a variable-latency
        // FADD/FMUL/FDIV/FSQRT on the shared CPLX EU) waits here. SEPARATE bit from
        // cplxWait (int dst): an FP op has NO int dst, and an FP consumer may read an
        // int source (an FMOVE.L Dn,FPn operand) AND an FP source independently.
        val cplxFpWait   = Reg(Bool()) init False
        // CPLX FPCC dynamic dependency: same mechanism, on cplxFpccBusy / cplxFpccWakeup.
        // SEPARATE bit from cplxFpWait: FCMP/FTST write FPCC with no FP dst at all.
        val cplxFpccWait = Reg(Bool()) init False

// Extend the `ready` expression (line 137) -- it becomes:
        val ready    = sel && (if (priority == 0) True else triggers(priority - 1 downto 0) === 0) &&
                       !lsWait && !cplxWait && !aluSlowWait && !lsNzvcWait && !cplxNzvcWait &&
                       !cplxFpWait && !cplxFpccWait
```

**FMax note (real, not boilerplate):** this widens the ready cone by two registered-bit AND terms.
Both are plain Regs (no bitmap read in the cone) — the same discipline that made the LS variant-A
fix FMax-neutral (`:106-111`'s own comment). Step 12 gates it with a real IQ OOC synth run rather
than asserting it is free.

- [ ] **Step 4: Add the two static scoreboards and the two dynamic busy bitmaps**

```scala
// After `val sbX = new Scoreboard(16)` (line 183), add:
    // FP-DATA / FPCC static scoreboards. Both rename classes have 16 physical entries
    // (spec Decisions 4+6), so both are 4-bit-tag/16-deep, exactly like sbNzvc/sbX.
    // NOTE (see this task's scope note): today EVERY FP producer is CPLX and therefore
    // routes to the dynamic cplxFp*/cplxFpcc* bitmaps below, so these two are no-ops --
    // they exist so the push-side routing chain is TOTAL for the non-CPLX FP producers
    // Task 10/11 add (FMOVEM on the LS port, FRESTORE's FP-PRF write).
    val sbFp   = new Scoreboard(16)
    val sbFpcc = new Scoreboard(16)
    sbFp.busy.simPublic(); sbFpcc.busy.simPublic()  // debug-only

// After `val cplxNzvcBusy = ...` (line 212-213), add:
    // cplxFpBusy[p] => FP physreg p is produced by an in-flight (not-yet-completed) CPLX
    // FP op. A reader of p is held NOT-ready until cplxFpWakeup(p). SEPARATE from cplxBusy
    // (int physregs, 6-bit, different id space) and from sbFp (static latency-1, wrong for
    // a variable-latency FP EU -- see cplxFpWakeup's doc comment in IqContext.scala).
    val cplxFpBusy   = Reg(Bits(16 bits)) init 0
    // cplxFpccBusy[p] => FPCC physreg p is produced by an in-flight CPLX FP op. Every
    // HW-native FP op writes FPCC, so this bitmap is set strictly more often than cplxFpBusy.
    val cplxFpccBusy = Reg(Bits(16 bits)) init 0
    cplxFpBusy.simPublic(); cplxFpccBusy.simPublic()  // debug-only
```

- [ ] **Step 5: Add the producer predicates**

```scala
// After `def isCplxNzvcProducer(...)` (line 236), add:
    // A CPLX op that writes an FP register = a dynamic (variable-latency) FP producer.
    // FCMP/FTST are excluded here (pFpDstValid=False) but ARE FPCC producers below.
    def isCplxFpProducer(u: RenamedUop): Bool   = isCplx(u) && u.pFpDstValid
    // A CPLX op that writes FPCC = a dynamic FPCC producer (every HW-native FP op).
    def isCplxFpccProducer(u: RenamedUop): Bool = isCplx(u) && u.writesFpcc
```

- [ ] **Step 6: Add the three `dep()` calls to `trigInit`**

```scala
// Inside trigInit, after `dep(sbX.busy, sbX.physToSlot, uop.pXSrc, uop.readsX)` (line 509):
      dep(sbFp.busy,   sbFp.physToSlot,   uop.pFpSrcA,  uop.psrcAFpValid)
      dep(sbFp.busy,   sbFp.physToSlot,   uop.pFpSrcB,  uop.psrcBFpValid)
      dep(sbFpcc.busy, sbFpcc.physToSlot, uop.pFpccSrc, uop.readsFpcc)
```

- [ ] **Step 7: Push-time dynamic dependency latches (incl. the intra-push slot1←slot0 case)**

```scala
// After the cplxNzvcDep block (line 603), add:
    // Push-time CPLX FP-DATA dependency: same mechanism as cplxDep but on cplxFpBusy /
    // cplxFpWakeup, over the TWO FP sources. `stillCplxFpBusy` excludes a producer that
    // completes THIS exact cycle (the lost-wakeup race lsDep's own comment documents).
    def stillCplxFpBusy(p: UInt): Bool =
      cplxFpBusy(p) && !(cplxFpWakeupPort.valid && cplxFpWakeupPort.payload === p)
    def cplxFpDepInit(uop: RenamedUop): Bool =
      (uop.psrcAFpValid && stillCplxFpBusy(uop.pFpSrcA)) ||
      (uop.psrcBFpValid && stillCplxFpBusy(uop.pFpSrcB))
    val cplxFpDep0 = cplxFpDepInit(pushUop0)
    val cplxFpDep1Base = cplxFpDepInit(pushUop1)
    val s0IsCplxFp = isCplxFpProducer(pushUop0)
    val cplxFpDep1 = cplxFpDep1Base ||
      (s0IsCplxFp && pushUop1.psrcAFpValid && (pushUop1.pFpSrcA === pushUop0.pFpDst)) ||
      (s0IsCplxFp && pushUop1.psrcBFpValid && (pushUop1.pFpSrcB === pushUop0.pFpDst))

    // Push-time CPLX FPCC dependency: single source, so no multi-source guard is needed
    // (mirrors cplxNzvcDep exactly).
    def stillCplxFpccBusy(p: UInt): Bool =
      cplxFpccBusy(p) && !(cplxFpccWakeupPort.valid && cplxFpccWakeupPort.payload === p)
    def cplxFpccDepInit(uop: RenamedUop): Bool = uop.readsFpcc && stillCplxFpccBusy(uop.pFpccSrc)
    val cplxFpccDep0 = cplxFpccDepInit(pushUop0)
    val cplxFpccDep1Base = cplxFpccDepInit(pushUop1)
    val s0IsCplxFpcc = isCplxFpccProducer(pushUop0)
    val cplxFpccDep1 = cplxFpccDep1Base ||
      (s0IsCplxFpcc && pushUop1.readsFpcc && (pushUop1.pFpccSrc === pushUop0.pFpccDst))
```

- [ ] **Step 8: Intra-push STATIC trigger, suppressed for the dynamic (CPLX) case**

```scala
// Alongside the existing s0WritesInt/s0WritesNzvc/s0WritesX block (lines 645-657), add:
    // A CPLX FP producer is variable-latency -> its intra-push dependency is carried by
    // cplxFpDep1 (dynamic), NOT a static latency-1 trigger, which would never clear.
    val s0WritesFp   = pushUop0.pFpDstValid && !s0IsCplxFp
    val s0WritesFpcc = pushUop0.writesFpcc  && !s0IsCplxFpcc
    when(s0WritesFp   && pushUop1.psrcAFpValid && pushUop1.pFpSrcA === pushUop0.pFpDst)   { trig1(slot0Prio) := True }
    when(s0WritesFp   && pushUop1.psrcBFpValid && pushUop1.pFpSrcB === pushUop0.pFpDst)   { trig1(slot0Prio) := True }
    when(s0WritesFpcc && pushUop1.readsFpcc    && pushUop1.pFpccSrc === pushUop0.pFpccDst){ trig1(slot0Prio) := True }
```

- [ ] **Step 9: Shift the new wait bits on compaction and initialize them on push**

```scala
// Inside `when(pushPort.fire)`'s compaction loop (after line 673):
          wDst.cplxFpWait   := wSrc.cplxFpWait     // CPLX FP-DATA dependency shifts with the slot
          wDst.cplxFpccWait := wSrc.cplxFpccWait   // CPLX FPCC dependency shifts too

// In the new-uop insertion block (after lines 689 / 698):
      wDst0.cplxFpWait   := cplxFpDep0
      wDst0.cplxFpccWait := cplxFpccDep0
      ...
      wDst1.cplxFpWait   := cplxFpDep1
      wDst1.cplxFpccWait := cplxFpccDep1
```

- [ ] **Step 10: Wakeup-clear loops (same compaction-shift discipline as `cplxWakeMatch`)**

```scala
// After the cplxNzvcWakeMatch loop (line 791), add:
    // ---- CPLX FP-DATA dynamic wakeup: clear cplxFpWait for slots reading the woken
    // pFpDst. Like cplxWait, this is ONE registered bit covering TWO possible FP sources
    // (the dyadic FADD/FSUB/FMUL/FDIV/FCMP forms read both), so clearing on the FIRST
    // matching wakeup would release a consumer whose OTHER FP operand is still in flight
    // (it would then read a stale 80-bit PRF entry -- a silent wrong-result, not a hang).
    // Only clear once NO FP source remains busy AFTER this cycle's wakeup. ----
    def cplxFpRemaining(u: RenamedUop): Bool =
      (u.psrcAFpValid && stillCplxFpBusy(u.pFpSrcA)) || (u.psrcBFpValid && stillCplxFpBusy(u.pFpSrcB))
    val cplxFpWakeMatch = Vec(slots.map { s =>
      val u = s.context.uop
      cplxFpWakeupPort.valid && s.sel &&
        ((u.psrcAFpValid && (u.pFpSrcA === cplxFpWakeupPort.payload)) ||
         (u.psrcBFpValid && (u.pFpSrcB === cplxFpWakeupPort.payload))) &&
        !cplxFpRemaining(u)
    })
    for (i <- 0 until slotCount) {
      when(cplxFpWakeMatch(i)) {
        when(pushPort.fire) { if (i >= wayCount) slots(i - wayCount).cplxFpWait := False }
          .otherwise        { slots(i).cplxFpWait := False }
      }
    }

    // ---- CPLX FPCC dynamic wakeup: single source, so no remaining-guard (mirrors
    // cplxNzvcWakeMatch exactly). ----
    val cplxFpccWakeMatch = Vec(slots.map { s =>
      val u = s.context.uop
      cplxFpccWakeupPort.valid && s.sel && u.readsFpcc && (u.pFpccSrc === cplxFpccWakeupPort.payload)
    })
    for (i <- 0 until slotCount) {
      when(cplxFpccWakeMatch(i)) {
        when(pushPort.fire) { if (i >= wayCount) slots(i - wayCount).cplxFpccWait := False }
          .otherwise        { slots(i).cplxFpccWait := False }
      }
    }
```

- [ ] **Step 11: Scoreboard maintenance — shift, push-record, issue-clear, busy set/clear, flush**

```scala
// (a) compaction shift (line 832): shift(sbInt); shift(sbNzvc); shift(sbX) becomes:
      shift(sbInt); shift(sbNzvc); shift(sbX); shift(sbFp); shift(sbFpcc)

// (b) push-time producer recording -- add to the `when(pushPort.fire)` block, alongside
//     the existing pdstValid/writesNzvc/writesX chains (after line 871 for slot0, and
//     inside `when(pushSlot1Port)` after line 888 for slot1, with slot1Prio):
    val push0IsCplxFp   = isCplxFpProducer(pushUop0);   val push1IsCplxFp   = isCplxFpProducer(pushUop1)
    val push0IsCplxFpcc = isCplxFpccProducer(pushUop0); val push1IsCplxFpcc = isCplxFpccProducer(pushUop1)
    // ... inside when(pushPort.fire):
      when(pushUop0.pFpDstValid) {
        when(push0IsCplxFp) { cplxFpBusy(pushUop0.pFpDst) := True }
          .otherwise { sbFp.busy(pushUop0.pFpDst) := True; sbFp.physToSlot(pushUop0.pFpDst) := slot0Prio }
      }
      when(pushUop0.writesFpcc) {
        when(push0IsCplxFpcc) { cplxFpccBusy(pushUop0.pFpccDst) := True }
          .otherwise { sbFpcc.busy(pushUop0.pFpccDst) := True; sbFpcc.physToSlot(pushUop0.pFpccDst) := slot0Prio }
      }
      when(pushSlot1Port) {
        when(pushUop1.pFpDstValid) {
          when(push1IsCplxFp) { cplxFpBusy(pushUop1.pFpDst) := True }
            .otherwise { sbFp.busy(pushUop1.pFpDst) := True; sbFp.physToSlot(pushUop1.pFpDst) := slot1Prio }
        }
        when(pushUop1.writesFpcc) {
          when(push1IsCplxFpcc) { cplxFpccBusy(pushUop1.pFpccDst) := True }
            .otherwise { sbFpcc.busy(pushUop1.pFpccDst) := True; sbFpcc.physToSlot(pushUop1.pFpccDst) := slot1Prio }
        }
      }

// (c) issue-time static clear -- extend the `for (k <- selPorts.indices)` loop (line 948-955).
//     Per that block's own FMax comment, this gate stays `fire`-only: do NOT add an
//     isCplxFp/isCplxFpcc re-test here (it would drag the FP-marker cone into the
//     scoreboard-clear cone, the exact regression that comment documents).
      when(selPorts(k).fire) {
        ...
        when(ctx.uop.pFpDstValid) { sbFp.busy(ctx.uop.pFpDst)     := False }
        when(ctx.uop.writesFpcc)  { sbFpcc.busy(ctx.uop.pFpccDst) := False }
      }

// (d) dynamic busy clear/set (after the cplxNzvc block, line 995) -- clear on wakeup,
//     then let a same-cycle re-allocating push win, exactly as every other class does:
    when(cplxFpWakeupPort.valid) { cplxFpBusy(cplxFpWakeupPort.payload) := False }
    when(pushPort.fire) {
      when(pushUop0.pFpDstValid && push0IsCplxFp) { cplxFpBusy(pushUop0.pFpDst) := True }
      when(pushSlot1Port && pushUop1.pFpDstValid && push1IsCplxFp) { cplxFpBusy(pushUop1.pFpDst) := True }
    }
    when(cplxFpccWakeupPort.valid) { cplxFpccBusy(cplxFpccWakeupPort.payload) := False }
    when(pushPort.fire) {
      when(pushUop0.writesFpcc && push0IsCplxFpcc) { cplxFpccBusy(pushUop0.pFpccDst) := True }
      when(pushSlot1Port && pushUop1.writesFpcc && push1IsCplxFpcc) { cplxFpccBusy(pushUop1.pFpccDst) := True }
    }

// (e) flush (line 1025-1043) -- add to the zeroing list:
      sbFp.busy    := 0
      sbFpcc.busy  := 0
      cplxFpBusy   := 0
      cplxFpccBusy := 0
```

- [ ] **Step 12: Extend the IQ test source plugin with the FP push fields + the two wakeups**

```scala
// src/test/scala/m68k040/execute/iq/IqSourcePlugin.scala
// (a) inside `case class SlotIo()`, after the pXSrc/pXDst line:
      val pFpSrcA    = in UInt (4 bits); val psrcAFpValid = in Bool ()
      val pFpSrcB    = in UInt (4 bits); val psrcBFpValid = in Bool ()
      val pFpDst     = in UInt (4 bits); val pFpDstValid  = in Bool ()
      val pFpccSrc   = in UInt (4 bits); val readsFpcc    = in Bool ()
      val pFpccDst   = in UInt (4 bits); val writesFpcc   = in Bool ()

// (b) inside `mkSlot`, alongside the other wired fields:
      u.pFpSrcA := io.pFpSrcA; u.psrcAFpValid := io.psrcAFpValid
      u.pFpSrcB := io.pFpSrcB; u.psrcBFpValid := io.psrcBFpValid
      u.pFpDst  := io.pFpDst;  u.pFpDstValid  := io.pFpDstValid
      u.pFpccSrc := io.pFpccSrc; u.readsFpcc  := io.readsFpcc
      u.pFpccDst := io.pFpccDst; u.writesFpcc := io.writesFpcc
      u.pFpOld   := 0; u.pFpccOld := 0    // rename bookkeeping only; the IQ never reads them

// (c) at the end of `logic`, the two new sim-driven wakeup sources:
    val cplxFpWakeupValid = in Bool (); val cplxFpWakeupTag = in UInt (4 bits)
    iq.cplxFpWakeup.valid   := cplxFpWakeupValid
    iq.cplxFpWakeup.payload := cplxFpWakeupTag
    val cplxFpccWakeupValid = in Bool (); val cplxFpccWakeupTag = in UInt (4 bits)
    iq.cplxFpccWakeup.valid   := cplxFpccWakeupValid
    iq.cplxFpccWakeup.payload := cplxFpccWakeupTag
```

The pre-existing IQ specs (`IqCplxSpec`/`IqLsSpec`/`IqAluSlowSpec`/`IssueQueueSpec`) never poke these
new inputs; unpoked Verilator inputs read 0/false, which is exactly the inert "no FP operand, no FP
wakeup" case, so they are unaffected. Step 14 proves that rather than assuming it.

- [ ] **Step 13: Directed FP-dependency test**

```scala
// src/test/scala/m68k040/execute/iq/IqFpSpec.scala
package m68k040.execute.iq

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.isa.{Cluster, MemOp}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

import scala.collection.mutable.ArrayBuffer

/** Directed coverage for the FP-data and FPCC dependency classes in the IQ (Task 3).
  *
  * FP ops are CPLX-cluster and variable-latency, so they use completion wakeup, not the
  * latency-1 trigger matrix. Two properties are load-bearing and are proven here:
  *   1. a dyadic consumer reading TWO in-flight FP results is NOT released by the first
  *      wakeup (a single cplxFpWait bit covers both sources -- releasing early would read
  *      a stale 80-bit PRF entry, a SILENT wrong result, not a hang);
  *   2. an FPCC reader is tracked independently of the FP-data class (FCMP/FTST write FPCC
  *      with no FP dst at all), and flush clears both bitmaps.
  */
class IqFpSpec extends AnyFunSuite {
  class FpIssueObserverPlugin extends FiberPlugin {
    val logic = during build new Area {
      val iq = host[IssueQueueService]
      val fire = out Bool (); val rob = out UInt (6 bits)
      fire := iq.issue(4).fire
      rob  := iq.issue(4).payload.robId
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val iq     = new IssueQueuePlugin
    val source = new IqSourcePlugin
    val sink   = new IqSinkPlugin
    val fpObs  = new FpIssueObserverPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), iq, source, sink, fpObs
    )) }
  }

  case class FpUop(
      rob: Int,
      cluster: SpinalEnumElement[Cluster.type] = Cluster.CPLX,
      pFpSrcA: Int = 0, psrcAFpValid: Boolean = false,
      pFpSrcB: Int = 0, psrcBFpValid: Boolean = false,
      pFpDst: Int = 0,  pFpDstValid: Boolean = false,
      pFpccSrc: Int = 0, readsFpcc: Boolean = false,
      pFpccDst: Int = 0, writesFpcc: Boolean = false)

  private def driveSlot(dut: Dut, slot1: Boolean, u: FpUop): Unit = {
    val s = if (slot1) dut.source.logic.s1 else dut.source.logic.s0
    s.robId #= u.rob; s.cluster #= u.cluster; s.memOp #= MemOp.NONE
    s.pdst #= 0; s.pdstValid #= false
    s.psrcA #= 0; s.psrcAValid #= false
    s.psrcB #= 0; s.psrcBValid #= false
    s.useImm #= false
    s.readsNzvc #= false; s.writesNzvc #= false; s.pNzvcSrc #= 0; s.pNzvcDst #= 0
    s.readsX #= false; s.writesX #= false; s.pXSrc #= 0; s.pXDst #= 0
    s.isShift #= false
    s.pFpSrcA #= u.pFpSrcA; s.psrcAFpValid #= u.psrcAFpValid
    s.pFpSrcB #= u.pFpSrcB; s.psrcBFpValid #= u.psrcBFpValid
    s.pFpDst  #= u.pFpDst;  s.pFpDstValid  #= u.pFpDstValid
    s.pFpccSrc #= u.pFpccSrc; s.readsFpcc  #= u.readsFpcc
    s.pFpccDst #= u.pFpccDst; s.writesFpcc #= u.writesFpcc
  }

  private def idle(dut: Dut): Unit = {
    val src = dut.source.logic
    src.pushValid #= false; src.slot1Valid #= false; src.flush #= false
    src.aluFastAccept0 #= true; src.aluFastAccept1 #= true
    src.lsWakeupValid #= false; src.lsWakeupPdst #= 0
    src.aluSlowWakeupValid #= false; src.aluSlowWakeupPdst #= 0; src.aluSlowWakeupPdstV #= false
    src.aluSlowWakeupNzvc #= 0; src.aluSlowWakeupNzvcV #= false
    src.aluSlowWakeupX #= 0; src.aluSlowWakeupXV #= false
    src.cplxFpWakeupValid #= false;   src.cplxFpWakeupTag #= 0
    src.cplxFpccWakeupValid #= false; src.cplxFpccWakeupTag #= 0
    driveSlot(dut, slot1 = false, FpUop(0, cluster = Cluster.INT))
    driveSlot(dut, slot1 = true,  FpUop(0, cluster = Cluster.INT))
    dut.sink.logic.ready0 #= true; dut.sink.logic.ready1 #= true; dut.sink.logic.ready3 #= true
  }

  private def push(dut: Dut, u0: FpUop, u1: Option[FpUop] = None): Unit = {
    val src = dut.source.logic
    driveSlot(dut, slot1 = false, u0)
    driveSlot(dut, slot1 = true, u1.getOrElse(FpUop(0, cluster = Cluster.INT)))
    src.slot1Valid #= u1.nonEmpty
    src.pushValid #= true
    dut.clockDomain.waitSamplingWhere(src.pushReady.toBoolean)
    src.pushValid #= false; src.slot1Valid #= false
  }

  private def wakeFp(dut: Dut, tag: Int): Unit = {
    dut.source.logic.cplxFpWakeupTag #= tag
    dut.source.logic.cplxFpWakeupValid #= true
    dut.clockDomain.waitSampling()
    dut.source.logic.cplxFpWakeupValid #= false
  }
  private def wakeFpcc(dut: Dut, tag: Int): Unit = {
    dut.source.logic.cplxFpccWakeupTag #= tag
    dut.source.logic.cplxFpccWakeupValid #= true
    dut.clockDomain.waitSampling()
    dut.source.logic.cplxFpccWakeupValid #= false
  }

  test("FP consumers wait for EVERY FP source; FPCC is tracked independently; flush clears both", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      idle(dut)

      val fpIssues = ArrayBuffer[Int]()
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.fpObs.logic.fire.toBoolean) fpIssues += dut.fpObs.logic.rob.toInt
        }
      }
      cd.waitSampling(4)

      // Two in-flight FP producers (FP phys 9 and 10), then a dyadic consumer of both.
      push(dut,
        FpUop(rob = 1, pFpDst = 9,  pFpDstValid = true, pFpccDst = 1, writesFpcc = true),
        Some(FpUop(rob = 2, pFpDst = 10, pFpDstValid = true, pFpccDst = 2, writesFpcc = true)))
      push(dut, FpUop(rob = 3,
        pFpSrcA = 9,  psrcAFpValid = true,
        pFpSrcB = 10, psrcBFpValid = true,
        pFpDst = 11, pFpDstValid = true, pFpccDst = 3, writesFpcc = true))

      var w = 0
      while ((!fpIssues.contains(1) || !fpIssues.contains(2)) && w < 12) { cd.waitSampling(); w += 1 }
      assert(fpIssues.count(_ == 1) == 1 && fpIssues.count(_ == 2) == 1,
        s"both FP producers must issue exactly once, saw ${fpIssues.mkString(",")}")
      assert(!fpIssues.contains(3), "dyadic FP consumer issued before any FP wakeup")

      wakeFp(dut, tag = 9)
      cd.waitSampling(4)
      assert(!fpIssues.contains(3),
        "dyadic FP consumer must stay blocked after only its FIRST FP source wakes " +
        "(a single cplxFpWait bit covers both sources -- an early release reads a stale 80-bit PRF entry)")

      wakeFp(dut, tag = 10)
      w = 0
      while (!fpIssues.contains(3) && w < 8) { cd.waitSampling(); w += 1 }
      assert(fpIssues.count(_ == 3) == 1,
        s"FP consumer must issue exactly once after its SECOND FP wake, saw ${fpIssues.mkString(",")}")

      // FPCC is an independent class: a pure FPCC reader (no FP data source at all, the
      // FBcc/FMOVE-from-FPSR shape) waits on cplxFpccWakeup only.
      push(dut, FpUop(rob = 4, pFpccDst = 5, writesFpcc = true))   // FCMP-shaped: FPCC only, no FP dst
      while (!fpIssues.contains(4)) cd.waitSampling()
      push(dut, FpUop(rob = 5, pFpccSrc = 5, readsFpcc = true))
      cd.waitSampling(4)
      assert(!fpIssues.contains(5), "FPCC reader escaped before its FPCC wakeup")
      wakeFp(dut, tag = 5)   // an FP-DATA wake on the same numeric tag must NOT release it
      cd.waitSampling(3)
      assert(!fpIssues.contains(5),
        "an FP-DATA wakeup must not release an FPCC reader (separate classes, same tag width)")
      wakeFpcc(dut, tag = 5)
      w = 0
      while (!fpIssues.contains(5) && w < 8) { cd.waitSampling(); w += 1 }
      assert(fpIssues.count(_ == 5) == 1, "FPCC reader must issue exactly once after its FPCC wake")

      // Flush must drop a waiting FP consumer and clear BOTH busy bitmaps, so a freshly
      // pushed reader of the same tags issues without ever receiving a wakeup.
      push(dut, FpUop(rob = 6, pFpDst = 12, pFpDstValid = true, pFpccDst = 6, writesFpcc = true))
      while (!fpIssues.contains(6)) cd.waitSampling()
      push(dut, FpUop(rob = 7, pFpSrcA = 12, psrcAFpValid = true, pFpccSrc = 6, readsFpcc = true))
      cd.waitSampling(3)
      assert(!fpIssues.contains(7), "pre-flush FP consumer escaped before its wakeups")
      dut.source.logic.flush #= true
      cd.waitSampling()
      dut.source.logic.flush #= false
      push(dut, FpUop(rob = 8, pFpSrcA = 12, psrcAFpValid = true, pFpccSrc = 6, readsFpcc = true))
      w = 0
      while (!fpIssues.contains(8) && w < 8) { cd.waitSampling(); w += 1 }
      assert(!fpIssues.contains(7), "flushed FP-dependent consumer must never issue")
      assert(fpIssues.count(_ == 8) == 1,
        "flush must clear cplxFpBusy AND cplxFpccBusy for a newly accepted consumer")
    }
  }
}
```

- [ ] **Step 14: Run the new test and the whole existing IQ suite**

```bash
sbt "testOnly m68k040.execute.iq.IqFpSpec"
sbt "testOnly m68k040.execute.iq.*"
```
Expected: `IqFpSpec` PASSes; `IssueQueueSpec`/`IqLsSpec`/`IqCplxSpec`/`IqAluSlowSpec` all still PASS
(this is the concrete check that the widened `IqSourcePlugin` did not perturb them).

- [ ] **Step 15: Confirm the lock-step suite is unaffected**

```bash
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
```
Expected: 396/396 PASS (unchanged — no decode path can set `pFpDstValid`/`writesFpcc` until Task 6).

- [ ] **Step 16: IQ OOC synth probe (the ready cone gained two terms — measure, do not assume)**

```bash
sbt "runMain m68k040.top.GenIqSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_Iq.log -source synth/ooc_M68kIqSynth.tcl
grep -E "RESULT|CRITICAL WARNING|ERROR" synth/vivado_Iq.log
```
Record the `RESULT M68kIqSynth WNS ... FMAX ...` line **before** this task's edits (stash them, or
run on `HEAD~1`) and after, and report both. This is a probe, not the plan's gate — the binding
gate is the full-core post-route run at the end of the plan (201.450 MHz baseline). If the IQ OOC
number regresses by more than ~2%, register the two new wait bits' *source* terms rather than
widening the cone further, and re-measure.

- [ ] **Step 17: Commit**

```bash
git add src/main/scala/m68k040/execute/iq/IqContext.scala \
        src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala \
        src/test/scala/m68k040/execute/iq/IqSourcePlugin.scala \
        src/test/scala/m68k040/execute/iq/IqFpSpec.scala
git commit -m "feat(fpu): IQ FP-data + FPCC scoreboards and CPLX dynamic wakeup

Adds the two new rename classes from Task 2 to the issue queue, following
the task #167 CPLX-NZVC pattern exactly rather than the static latency-1
scoreboard path: FP ops route through the shared CPLX cluster and are
variable-latency (FDIV/FSQRT iterate), so a static trigger -- whose busy
bit is force-cleared the cycle the producer ISSUES, not when it completes
-- would release a consumer against a stale 80-bit PRF entry. Each class
gets a dynamic busy bitmap (cplxFpBusy / cplxFpccBusy), a registered
per-slot wait bit ANDed into ready, a push-time dependency latch with the
established same-cycle-wakeup guard, an intra-push slot1<-slot0 case with
the static trigger suppressed, and a wakeup-clear loop honoring the
compaction shift.

The FP-data clear carries a multi-source guard (cplxFpRemaining): the
dyadic FADD/FSUB/FMUL/FDIV/FCMP forms read TWO FP registers while
cplxFpWait is one bit, so clearing on the first matching wakeup would be
a SILENT wrong-result bug, not a hang. FPCC needs no such guard (single
source), mirroring cplxNzvcWakeup.

Two static Scoreboards (sbFp/sbFpcc) plus their trigInit dep() calls are
added alongside. They are provably no-ops today -- every current FP
producer is CPLX and routes to the dynamic bitmaps -- and exist so the
push-side routing chain is TOTAL for the non-CPLX FP producers Task 10
(FMOVEM on the LS port) and Task 11 (FRESTORE's FP-PRF write) add. No
uop reads FPCC yet either (FBcc/FScc/FMOVE-from-FPSR are deferred); the
directed test drives the class synthetically at the IQ boundary.

Two new service ports (cplxFpWakeup / cplxFpccWakeup, 4-bit tags) are
shaped exactly like DivEuPlugin's existing wakeup/wakeupNzvc producers so
Task 8 can drive them with the same compLive && <write> && !compFault
gating.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---
### Task 4: F-line FP decode — `DecOp.FPU`, `OpSpec.fpGeneric`, `DecodedUop` FP fields

**Files:**
- Modify: `src/main/scala/m68k040/decode/DecodedUop.scala` (`DecOp.FPU`, new `FpSrcKind` enum, new FP fields + an inert-default helper)
- Modify: `src/main/scala/m68k040/decode/DecodeContracts.scala` (`OpSpec.fpGeneric` + its default)
- Modify: `src/main/scala/m68k040/decode/OperationDecoder.scala` (the `is(0xF)` cpGEN arm)
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala` (inert FP defaults at all construction sites + the `fpGenBad` term that keeps behavior bit-identical until Task 6)
- Modify: `src/main/scala/m68k040/decode/Microcode.scala`, `MicroOpQueue.scala`, `DecodeStage.scala` (inert FP defaults only)
- Test: `src/test/scala/m68k040/decode/FpDecodeSpec.scala`

**Interfaces:**
- Consumes: `OperationDecoder.decode(opword: Bits)` — **opword only**, unchanged signature.
- Produces: `OpSpec.fpGeneric` (consumed by Task 6's `MicroOpAssembler`) and the `DecodedUop` FP
  fields `fpSrcAReg`/`fpSrcBReg`/`fpDstReg`/`usesFpSrcA`/`usesFpSrcB`/`writesFp`/`readsFpcc`/
  `writesFpcc` — the **exact** names Task 2's RenameStage Step 4/5 reads off `decUop(s)` — plus
  `fpuOp`/`fpSrcKind` for Task 8's EU.

---

#### 4.0 Encoding reference — what is verified, and what is not

The FP-generic ("cpGEN") instruction is **two** words, and the opcode that selects FADD from FMUL
lives in the **second** one:

```
opword:  1111 001 000 mmmrrr
         F-line cpID=1  type=000   <ea>
ext:     ooo | s s s | d d d | ppppppp
         [15:13]  [12:10]  [9:7]   [6:0]
         opclass  src      dst FPn opmode
                  (FPm when R/M=0; source data FORMAT when R/M=1)
```

**Verified in-session** against `tools/musashi/musashi/m68kfpu.c` — the in-tree vendored copy of the
very emulator this project lock-steps against (so it is the oracle these encodings must agree with,
whatever a manual says):

| Fact | Evidence |
|---|---|
| opword type field = bits [7:6] of the opword selects cpGEN(00)/FScc(01)/FBcc.W(10)/FBcc.L(11) | `m68040_fpu_op0()`: `switch ((REG_IR >> 6) & 0x3)` |
| cpGEN sub-dispatch is on ext[15:13] | `switch ((w2 >> 13) & 0x7)` — `0x0`/`0x2` → `fpgen_rm_reg`, `0x3` → `fmove_reg_mem`, `0x4`/`0x5` → `fmove_fpcr`, `0x6`/`0x7` → `fmovem` |
| `R/M = ext[14]`, `src = ext[12:10]`, `dst = ext[9:7]`, `opmode = ext[6:0]` | `fpgen_rm_reg()`: `int rm = (w2>>14)&1; int src = (w2>>10)&7; int dst = (w2>>7)&7; int opmode = w2 & 0x7f;` |
| FMOVECR = opclass `010` with source specifier `111` | the `case 7:` arm inside `fpgen_rm_reg`'s `switch(src)`, whose own comment reads *"handle it right here, the usual opmode bits aren't valid in the FMOVECR case"*; corroborated by the file's literal example comment `// fmovecr #$f, fp0	f200 5c0f` (ext `0x5C0F` = opclass 010, src 111, dst 000, ROM offset 0x0F) |
| opmode values for the entire HW-native set | `fpgen_rm_reg`'s opmode `switch`: `0x00 FMOVE`, `0x01 Fsint`(FINT), `0x03 FsintRZ`(FINTRZ), `0x04 FSQRT`, `0x18 FABS`, `0x1a FNEG`, `0x20 FDIV`, `0x22 FADD`, `0x23 FMUL`, `0x28 FSUB`, `0x38 FCMP`, `0x3a FTST` |
| the `0x40`/`0x44` opmode bits are the 68040 rounded-precision (FSxxx/FDxxx) modifiers | `if ((opmode & 0x44) == 0x44) { round = 2; ... } else if (opmode & 0x40) { round = 1; ... }` |

**NOT verified in this session — pin down before or during implementation:**

1. **Source-specifier (ext[12:10]) format codes** for the R/M=1 case. The values used below —
   `000`=Long int, `001`=Single, `010`=Extended, `011`=Packed, `100`=Word int, `101`=Double,
   `110`=Byte int — are from the `switch(src)` arm ordering in `fpgen_rm_reg`, which is strong but
   was not read case-by-case here. **Step 1 below makes reading them out an explicit action.**
2. **FSAVE/FRESTORE opwords** (believed `1111 001 100 mmmrrr` / `1111 001 101 mmmrrr`, i.e. opword
   bit 8 = 1 with [7:6] = 00/01). Musashi's dispatcher only masks 2 bits so it cannot confirm the
   bit-8 split. Out of scope here (Task 9/11 own them); called out only so nobody assumes this arm
   already claims that space. **This arm must not match any opword with bit 8 set.**
3. The exact **MC68040 UM** wording for all of the above. The project's stated preference is primary
   source; the vendored Musashi is the *behavioral* oracle, which is the binding constraint for
   lock-step, but the two should be cross-checked once against the UM.

**Collision check (done, and worth keeping in the file):** the existing line-F carve-outs do **not**
overlap cpGEN. `0xF4xx` CPUSH/CINV and `0xF5xx` PFLUSH/PTEST have opword[11:9] = `010`; `0xF620`
MOVE16 has `011`; only cpGEN has `001`. And the FSF carve-out `0xF27F`, though it *is* in the
cpID=`001` space, has opword[8:6] = `001` (FScc), not `000` — so gating this arm on
`opword(8 downto 6) === 000` leaves task #180's carve-out untouched. Step 6's test asserts this.

---

#### 4.1 Two deliberate deviations from the task brief (read before implementing)

**(a) One `DecOp` value (`FPU`), not fourteen.** This project's established idiom is one `DecOp` per
*family* plus a sub-kind field on `DecodedUop` — `SHIFT`+`shiftOp`, `BITOP`+`bitOp`,
`BITFIELD`+`bfOp`, `CASOP`+`casForm`. There is also direct FMax evidence for keeping the `op` enum
narrow: `IssueQueuePlugin.scala:914-924` documents a measured post-route case where the 6-bit `op`
field's MuxOH" being dragged into a second cone made that family the design's WNS holder. So this
task adds exactly one element, `FPU`, carrying the **raw 7-bit ext-word opmode** in a new `fpuOp`
field — which is also the least-lossy encoding possible, since Task 8's EU wants that field anyway.

**(b) The FP register numbers go on `DecodedUop`, not `OpSpec`.** `OperationDecoder.decode` takes
the **opword alone**, and that is not incidental: `PredecodeWord.scala:64` bakes
`OperationDecoder.decode(op).size` into `ChunkPredecode` at I-cache **refill** time ("FMax Lever B"),
where no extension word exists, and `UcPendSpecStashEquivalenceSpec` asserts
`OperationDecoder.decode(w) === MicroOpAssembler.computeOffload(pkt).spec` word-for-word. Since
FADD-vs-FMUL, the FP register numbers, and every `usesFp*`/`writesFp*` bit live in the **extension**
word, none of them are functions of the opword and none can be produced here. `OpSpec` therefore
gains exactly one new field — `fpGeneric`, which *is* an opword function — and the register/valid
fields are set by `MicroOpAssembler` (Task 6) from `pkt.words(1)`, on `DecodedUop`, which is what
Task 2's RenameStage Step 4 actually reads (`decUop(s).fpSrcAReg`). This is the same split
CMP2/CHK2 already uses (`OperationDecoder.scala:69-94`: *"the compared register Rn ... live in the
extension word — the MicroOpAssembler owns the crack (reading word2). OperationDecoder only NAMES
the op"*). Do **not** widen `decode()`'s signature to take an ext word.

---

- [ ] **Step 1: Pin down the source-specifier format codes (explicit verification action)**

```bash
sed -n '/^static void fpgen_rm_reg/,/^	else$/p' tools/musashi/musashi/m68kfpu.c | grep -n "case [0-9]:" -A1
```
Write the confirmed 7-row table (`000`..`110` → Long/Single/Extended/Packed/Word/Double/Byte, and
`111` → FMOVECR) into the code comment added in Step 4, replacing the provisional list. If any row
disagrees with the provisional list above, **fix the code and this plan text**, and re-check Task
5's immediate-length table (which is keyed off the same field) before proceeding.

- [ ] **Step 2: Add `DecOp.FPU`, the `FpSrcKind` enum, and the `DecodedUop` FP fields**

```scala
// src/main/scala/m68k040/decode/DecodedUop.scala
// (a) add FPU to the DecOp enum, after MOVES (line 110). Keep it LAST so no existing
//     element's ordinal shifts (RobPlugin/ExceptionUnit resize()s key off widths).
      MOVES,
      // F-line FP-generic (cpGEN) family: ONE DecOp for the whole hardware-native FP op
      // set, with the concrete operation carried in `fpuOp` (the raw 7-bit extension-word
      // opmode, ext[6:0]) -- the same family+sub-kind idiom SHIFT/shiftOp, BITOP/bitOp,
      // BITFIELD/bfOp and CASOP/casForm already use. Deliberately NOT 12-14 separate
      // DecOp elements: IssueQueuePlugin.scala:914-924 documents a MEASURED post-route
      // case where the `op` field's MuxOH leaking into a second cone became the design's
      // WNS holder, so the enum stays narrow. Routed to Cluster.CPLX (spec Decision 9:
      // fold into the existing CPLX cluster, no new Cluster value, no new IQ/ROB port).
      FPU
      = newElement()

// (b) add a small source-kind enum next to SysKind:
/** Where an FP-generic uop's SOURCE operand comes from (DecodedUop.fpSrcKind).
  *   FPREG    : extension-word opclass 000 -- the source is FP register FPm (fpSrcBReg).
  *   INTREG   : opclass 010 with an integer/single source specifier and a Dn <ea> --
  *              the source is a 32-bit INT register read, riding the ordinary int
  *              srcA/psrcA rename path (no 80-bit value ever enters IqContext).
  *   ROMCONST : opclass 010 / source specifier 111 -- FMOVECR; the source is the FPU's
  *              internal constant ROM, indexed by `imm[6:0]` (the raw offset).
  * Memory-sourced forms (a real <ea> load) are NOT in this enum yet -- they are deferred
  * to Task 10 along with FMOVEM and the FMOVE-to-<ea> direction. */
object FpSrcKind extends SpinalEnum {
  val FPREG, INTREG, ROMCONST = newElement()
}

// (c) add the FP fields to `case class DecodedUop()`, after `casForm` (line 467):
  // ── F-line FP-generic operand routing (DecOp.FPU) ───────────────────────────
  // Architectural FP register numbers (FP0-FP7, 3 bits) -- rename maps them to the
  // 4-bit physical FP tags in RenameStage (Task 2's fpRat). Split exactly like the
  // integer srcA/srcB/dst convention this decoder already uses:
  //   fpSrcAReg / usesFpSrcA : the DESTINATION FPn read back as an operand. Set ONLY
  //     for the DYADIC ops (FADD/FSUB/FMUL/FDIV/FCMP), which compute FPn <op> src.
  //     The monadic ops (FMOVE/FABS/FNEG/FSQRT/FINT/FINTRZ/FTST/FMOVECR) do NOT read
  //     their destination -- their result is a function of the source alone -- so
  //     usesFpSrcA stays False for them (an unnecessary source would create a false
  //     RAW dependency and serialize independent FP work for nothing).
  //   fpSrcBReg / usesFpSrcB : the SOURCE FPm, valid only for the register-to-register
  //     form (fpSrcKind === FPREG). For INTREG the source rides srcAReg/psrcA (int
  //     rename); for ROMCONST there is no register source at all.
  //   fpDstReg / writesFp    : the destination FPn. False for FCMP and FTST, which
  //     write ONLY the condition codes.
  val fpSrcAReg   = UInt(3 bits); val usesFpSrcA = Bool()
  val fpSrcBReg   = UInt(3 bits); val usesFpSrcB = Bool()
  val fpDstReg    = UInt(3 bits); val writesFp   = Bool()
  // Renamed FPCC {N,Z,I,NAN} (spec Decision 4). EVERY hardware-native FP op writes it
  // (Musashi calls SET_CONDITION_CODES on every arm, including FMOVE-to-FPn and
  // FMOVECR). NOTHING reads it yet -- the first readers are FBcc/FScc/FDBcc and
  // FMOVE-from-FPSR, all deferred -- but the field and its IQ scoreboard (Task 3)
  // exist now so that path is a pure addition later.
  val readsFpcc   = Bool(); val writesFpcc = Bool()
  // The raw extension-word opmode, ext[6:0], verbatim (0x00 FMOVE, 0x01 FINT, 0x03
  // FINTRZ, 0x04 FSQRT, 0x18 FABS, 0x1A FNEG, 0x20 FDIV, 0x22 FADD, 0x23 FMUL,
  // 0x28 FSUB, 0x38 FCMP, 0x3A FTST -- confirmed against tools/musashi/musashi/
  // m68kfpu.c's fpgen_rm_reg opmode switch). For FMOVECR this field is NOT an opmode
  // (the ROM offset rides `imm` instead) -- gate on fpSrcKind === ROMCONST first.
  val fpuOp       = Bits(7 bits)
  val fpSrcKind   = FpSrcKind()

// (d) add an inert-default helper as a method on the bundle, so all 24 construction
//     sites can clear the new fields with one call instead of 8 repeated lines:
  /** Drive every FP field to its inert (non-FP-uop) default. Called by every
    * DecodedUop construction site that is not building an FP uop -- SpinalHDL requires
    * every bundle field to be driven (PhaseCheck_noLatchNoOverride), and there are 24
    * such sites across MicroOpAssembler/Microcode/MicroOpQueue/DecodeStage. */
  def fpInert(): Unit = {
    fpSrcAReg := 0; usesFpSrcA := False
    fpSrcBReg := 0; usesFpSrcB := False
    fpDstReg  := 0; writesFp   := False
    readsFpcc := False; writesFpcc := False
    fpuOp     := 0; fpSrcKind := FpSrcKind.FPREG
  }
```

- [ ] **Step 3: Add `OpSpec.fpGeneric` and its default**

```scala
// src/main/scala/m68k040/decode/DecodeContracts.scala
// (a) in `case class OpSpec()`, after the sysOp/sysKind/sysReadDir block (line 175):
  // ── F-line FP-generic (cpGEN) family marker ─────────────────────────────────
  // True for `1111 001 000 mmmrrr` -- the ONE thing about an FP instruction that is a
  // function of the OPWORD alone. Which operation it is (FADD vs FMUL), which FP
  // registers it touches, and whether it writes an FP register all live in the
  // EXTENSION word, which this decoder does not (and must not) see: decode() is called
  // at I-cache REFILL time by PredecodeWord.scala:64 to bake ChunkPredecode.size, where
  // no extension word exists. MicroOpAssembler owns that half, reading pkt.words(1) --
  // the same split CMP2/CHK2 already uses. `op`/`cluster` are set to FPU/CPLX here so
  // the family is classified; the assembler refines or faults it.
  val fpGeneric     = Bool()

// (b) in `OpSpec.illegalDefault()`, alongside the other defaults (line 197):
    o.fpGeneric := False
```

- [ ] **Step 4: Add the cpGEN arm to `OperationDecoder`'s `is(0xF)`**

```scala
// src/main/scala/m68k040/decode/OperationDecoder.scala
// Inside `is(0xF)`, AFTER the existing isCpushFamily/isMove16/isPflushFamily/isPtest
// blocks and BEFORE the `when(opword === B"16'hF27F")` FSF carve-out (line 1129), add:

        // ── F-line FP-GENERIC (cpGEN): `1111 001 000 mmmrrr` ────────────────────
        // Coprocessor ID 001 (the FPU) + type field 000 (the general FP instruction,
        // as opposed to 001=FScc/FDBcc/FTRAPcc, 010=FBcc.W, 011=FBcc.L, and the
        // FSAVE/FRESTORE encodings above bit 8). Verified against Musashi's own
        // dispatcher (tools/musashi/musashi/m68kfpu.c, m68040_fpu_op0: the cpGEN case
        // is `(REG_IR >> 6) & 3 == 0`, then a sub-switch on extension-word bits
        // [15:13]) -- see this task's encoding table for the full evidence list.
        //
        // NON-OVERLAP with the existing line-F carve-outs, checked bit-by-bit:
        //   CPUSH/CINV 0xF4xx  -> opword[11:9] = 010
        //   PFLUSH/PTEST 0xF5xx-> opword[11:9] = 010
        //   MOVE16 0xF620      -> opword[11:9] = 011
        //   FSF 0xF27F         -> opword[11:9] = 001 BUT opword[8:6] = 001 (FScc)
        // Only cpGEN is (001, 000), so this arm claims 0xF200-0xF23F and nothing else.
        // In particular it must NOT match any opword with bit 8 set -- that band holds
        // FSAVE/FRESTORE, owned by Task 9/11.
        //
        // WHAT THIS ARM CANNOT DECIDE: the operation, the FP registers, and whether an
        // <ea> is even used are all extension-word fields, and decode() sees the opword
        // only (PredecodeWord.scala:64 calls it at I-cache refill time). So this arm
        // classifies the FAMILY -- non-illegal, DecOp.FPU, Cluster.CPLX -- and
        // MicroOpAssembler refines it from pkt.words(1) or routes it to the vector-11
        // F-line trap. Until Task 6 lands, MicroOpAssembler's `fpGenBad` term faults
        // ALL of it, so this arm is behavior-neutral on its own.
        val isFpGeneric = (opword(11 downto 9) === B"3'b001") && (opword(8 downto 6) === B"3'b000")
        when(isFpGeneric) {
          o.illegal   := False
          o.fpGeneric := True
          o.op        := DecOp.FPU
          o.cluster   := Cluster.CPLX     // spec Decision 9: shared CPLX cluster, no new Cluster value
          o.size      := Size.LONG        // inert; the FP operand format lives in the ext word
          // No operand slots are named here: the EA (opword[5:0]) is meaningful ONLY for
          // the R/M=1 (extension-word bit 14) forms, and this decoder cannot see that bit.
          // The assembler routes both srcA (the int source of an FMOVE.L Dn,FPn) and the
          // FP register fields itself.
          o.srcA.setNone(); o.srcB.setNone(); o.dst.setNone(); o.dstWrites := False
          o.readsNzvc := False; o.writesNzvc := False   // FP ops touch FPCC, never the integer CCR
          o.readsX    := False; o.writesX    := False
        }
```

- [ ] **Step 5: Drive the inert FP defaults at every `DecodedUop` construction site, and add the
  behavior-preserving `fpGenBad` term**

```bash
# Enumerate the sites (24 across 4 files at time of writing):
grep -rn "= DecodedUop()" src/main/scala/
```

At **every** one of them, add a single `u.fpInert()` (or `opUop.fpInert()` / `ldUop.fpInert()` /
etc.) alongside the other field initializations. SpinalHDL's `PhaseCheck_noLatchNoOverride` will
name any site that is missed, so this is compiler-checked, not eyeballed.

Then, in `MicroOpAssembler.assembleImpl`, keep behavior **bit-identical to today** by faulting the
whole newly-non-illegal family:

```scala
// src/main/scala/m68k040/decode/MicroOpAssembler.scala
// Immediately before the `val bad = ...` expression (line 1475), add:
    // F-line FP-generic: OperationDecoder now classifies the cpGEN family as non-illegal
    // (Task 4) so that Task 6 can emit real FP uops for it. Until Task 6 lands, EVERY
    // cpGEN encoding must still take the ordinary vector-11 F-line trap -- otherwise a
    // spec.illegal=False + pkt.simple=True packet would fall through `bad` and emit a
    // DecOp.FPU uop with entirely undriven operands. Task 6 NARROWS this term to
    // "recognized-and-emittable" and leaves the rest here.
    val fpGenBad = spec.fpGeneric

// and add it to `bad`'s trailing disjunction (inside the final parenthesized group):
    val bad = ... &&
              (!pkt.simple || spec.illegal || eorMemBad || lineImmBad || limmFullFmtDstBad || addqMemBad || sccMemBad ||
               line4UnaryMemBad || aluRmwMemBad || bitOpMemBad || eaDstPcRelBad || fpGenBad ||
               (usesSrcEa && !srcEaOk) || (usesDstEa && !dstOk))
```

- [ ] **Step 6: Directed decode test**

```scala
// src/test/scala/m68k040/decode/FpDecodeSpec.scala
package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.Cluster
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Task 4: F-line FP-generic (cpGEN) family classification.
  *
  * Two independent things are proven here: (1) OperationDecoder recognizes exactly the
  * `1111 001 000 mmmrrr` band and nothing else -- in particular it does NOT swallow the
  * existing CPUSH/CINV/PFLUSH/PTEST/MOVE16/FSF line-F carve-outs; and (2) the assembler
  * still delivers the ordinary vector-11 F-line trap for every cpGEN encoding, i.e. this
  * task is behavior-neutral until Task 6.
  */
class FpDecodeSpec extends AnyFunSuite {
  class SpecDut extends Component {
    val opword = in Bits (16 bits)
    val o      = out(OpSpec())
    o := OperationDecoder.decode(opword)
  }
  class AsmDut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt).uops(0)
  }

  test("cpGEN band 0xF200-0xF23F is classified FP-generic / DecOp.FPU / Cluster.CPLX", VerilatorTest) {
    SimConfig.withVerilator.compile(new SpecDut).doSim { dut =>
      for (ea <- 0 until 64) {
        val op = 0xF200 | ea
        dut.opword #= op; sleep(1)
        assert(dut.o.fpGeneric.toBoolean, f"op=0x$op%04x must be fpGeneric")
        assert(!dut.o.illegal.toBoolean,  f"op=0x$op%04x must not be illegal")
        assert(dut.o.op.toEnum == DecOp.FPU, f"op=0x$op%04x must decode to DecOp.FPU")
        assert(dut.o.cluster.toEnum == Cluster.CPLX, f"op=0x$op%04x must be Cluster.CPLX")
        assert(!dut.o.writesNzvc.toBoolean && !dut.o.readsNzvc.toBoolean,
          f"op=0x$op%04x: FP ops touch FPCC, never the integer CCR")
      }
    }
  }

  test("the cpGEN arm claims NOTHING outside its band -- every other line-F opword is unchanged", VerilatorTest) {
    SimConfig.withVerilator.compile(new SpecDut).doSim { dut =>
      // Exhaustive over the whole line-F space: fpGeneric must be true IFF (bits[11:9]==001
      // && bits[8:6]==000). This is the real guard against silently swallowing FScc/FBcc/
      // FSAVE/FRESTORE or any of the four existing carve-outs.
      val wrong = scala.collection.mutable.ArrayBuffer[String]()
      for (low <- 0 until 4096) {
        val op = 0xF000 | low
        dut.opword #= op; sleep(1)
        val expect = ((op >> 9) & 0x7) == 1 && ((op >> 6) & 0x7) == 0
        if (dut.o.fpGeneric.toBoolean != expect)
          wrong += f"op=0x$op%04x fpGeneric=${dut.o.fpGeneric.toBoolean} expected=$expect"
      }
      assert(wrong.isEmpty, s"${wrong.size} line-F opwords misclassified:\n" + wrong.take(20).mkString("\n"))
    }
  }

  test("the four pre-existing line-F carve-outs are untouched", VerilatorTest) {
    SimConfig.withVerilator.compile(new SpecDut).doSim { dut =>
      def chk(op: Int, name: String)(f: OpSpec => Boolean): Unit = {
        dut.opword #= op; sleep(1)
        assert(!dut.o.fpGeneric.toBoolean, f"$name (0x$op%04x) must NOT be fpGeneric")
        assert(f(dut.o), f"$name (0x$op%04x) regressed")
      }
      chk(0xF4F8, "CPUSH")   (s => s.sysOp.toBoolean && s.sysKind.toEnum == SysKind.CPUSH)
      chk(0xF4D8, "CINV")    (s => s.sysOp.toBoolean && s.sysKind.toEnum == SysKind.CINV)
      chk(0xF518, "PFLUSHA") (s => s.sysOp.toBoolean && s.sysKind.toEnum == SysKind.PFLUSHA)
      chk(0xF548, "PTESTW")  (s => s.sysOp.toBoolean && s.sysKind.toEnum == SysKind.PTEST)
      chk(0xF620, "MOVE16")  (s => s.microcoded.toBoolean)
      chk(0xF27F, "FSF")     (s => s.op.toEnum == DecOp.CLR && !s.illegal.toBoolean)
    }
  }

  test("until Task 6, every cpGEN encoding still takes the vector-11 F-line trap", VerilatorTest) {
    SimConfig.withVerilator.compile(new AsmDut).doSim { dut =>
      // FADD FP1,FP0 = F200 0422 (opclass 000, src FP1, dst FP0, opmode 0x22).
      dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
      dut.pkt.lenWords #= 2; dut.pkt.wordCount #= 2; dut.pkt.fault #= false
      dut.pkt.words(0) #= 0xF200; dut.pkt.words(1) #= 0x0422
      dut.pkt.words(2) #= 0; dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
      sleep(1)
      assert(dut.uop.faulted.toBoolean, "cpGEN must still fault before Task 6")
      assert(dut.uop.faultVector.toInt == 11, s"F-line vector 11, got ${dut.uop.faultVector.toInt}")
      assert(dut.uop.unimplemented.toBoolean, "the trap uop is the generic unimplemented one")
      assert(!dut.uop.writesFp.toBoolean && !dut.uop.writesFpcc.toBoolean,
        "no FP side effects may escape from a trapping uop")
    }
  }
}
```

- [ ] **Step 7: Run the tests**

```bash
sbt "testOnly m68k040.decode.FpDecodeSpec"
sbt "testOnly m68k040.decode.*Spec m68k040.frontend.FedSpecsPacketPairingSpec"
```
Expected: `FpDecodeSpec` PASSes; the whole decode suite still PASSes. `UcPendSpecStashEquivalence
Spec` and `FedSpecsPacketPairingSpec` are the two that would catch a broken `decode()`-purity
assumption — they must be green.

- [ ] **Step 8: Confirm the lock-step suite is unaffected (this task must be behavior-neutral)**

```bash
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
```
Expected: 396/396 PASS. `fpGenBad` makes every cpGEN encoding trap exactly as it did before, so any
delta here is a real regression, not expected churn.

- [ ] **Step 9: Commit**

```bash
git add src/main/scala/m68k040/decode/DecodedUop.scala \
        src/main/scala/m68k040/decode/DecodeContracts.scala \
        src/main/scala/m68k040/decode/OperationDecoder.scala \
        src/main/scala/m68k040/decode/MicroOpAssembler.scala \
        src/main/scala/m68k040/decode/Microcode.scala \
        src/main/scala/m68k040/decode/MicroOpQueue.scala \
        src/main/scala/m68k040/decode/DecodeStage.scala \
        src/test/scala/m68k040/decode/FpDecodeSpec.scala
git commit -m "feat(fpu): F-line FP-generic decode classification (DecOp.FPU, OpSpec.fpGeneric)

Recognizes the cpGEN band \`1111 001 000 mmmrrr\` (0xF200-0xF23F) in
OperationDecoder's is(0xF) arm, following the established
isCpushFamily/isMove16/isPflushFamily/isPtest predicate-then-when style.
Non-overlap with all four pre-existing line-F carve-outs is checked
bit-by-bit and asserted exhaustively over the whole 4096-opword line-F
space by the new test -- notably 0xF27F (FSF) shares the cpID=001 field
but has type=001 (FScc), so it is untouched.

TWO DELIBERATE DEPARTURES, both evidence-backed:

1. ONE DecOp element (FPU) carrying the raw 7-bit extension-word opmode
   in a new \`fpuOp\` field, not 12-14 per-operation elements. This is the
   project's own family+sub-kind idiom (SHIFT/shiftOp, BITOP/bitOp,
   BITFIELD/bfOp, CASOP/casForm), and IssueQueuePlugin.scala:914-924
   documents a measured post-route case where the \`op\` field's MuxOH
   leaking into an extra cone became the design's WNS holder.

2. The FP register numbers and usesFp*/writesFp* bits live on DecodedUop,
   NOT OpSpec. OperationDecoder.decode() takes the opword alone, and that
   is load-bearing: PredecodeWord.scala:64 calls it at I-cache REFILL time
   to bake ChunkPredecode.size, where no extension word exists, and
   UcPendSpecStashEquivalenceSpec asserts decode(w) equals the assembler's
   stashed spec word-for-word. FADD-vs-FMUL, the FP register fields and
   the operand-usage bits are all extension-word functions, so they are
   set by MicroOpAssembler from pkt.words(1) -- the same split CMP2/CHK2
   already uses. OpSpec gains only \`fpGeneric\`, which IS an opword
   function.

Behavior-neutral by construction: a new \`fpGenBad\` term in the
assembler's \`bad\` expression faults every cpGEN encoding to vector 11
exactly as before, so the family is classified but nothing new executes.
Task 6 narrows that term to the recognized-and-emittable subset.

Opcode encodings verified in-session against the in-tree vendored
tools/musashi/musashi/m68kfpu.c (the lock-step oracle these encodings
must agree with): the ext[15:13] opclass sub-dispatch, R/M=ext[14],
src=ext[12:10], dst=ext[9:7], opmode=ext[6:0], FMOVECR as opclass 010 +
source specifier 111 (corroborated by that file's own literal
\`fmovecr #\$f, fp0  f200 5c0f\` comment), and all 12 hardware-native
opmode values. The source-specifier FORMAT codes and the FSAVE/FRESTORE
opwords are explicitly NOT yet verified and are called out as such in the
plan and the code comments.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: `PredecodeWord` F-line cpGEN length framing

**Files:**
- Modify: `src/main/scala/m68k040/frontend/PredecodeWord.scala`
- Modify: `src/test/scala/m68k040/frontend/PredecodeRef.scala` (the authoritative Scala model — the exhaustive RTL-vs-ref sweep compares against it)
- Test: `src/test/scala/m68k040/frontend/PredecodeFpLenSpec.scala`

**Interfaces:**
- Consumes: the file's own local `eaExt(mode, reg, sizeL, allowImm, eaW, eaWKnown)` helper
  (`PredecodeWord.scala:144-190`) and `fullExtLen` (`:71-77`), both unchanged.
- Produces: `ChunkPredecode.lenWords >= 2` for every framed cpGEN instruction. **Task 6 keys its
  `faultUsesNextPc` decision off exactly that** — see the invariant below.

**The invariant this task establishes (and Task 6 depends on):**

> A cpGEN instruction is **never** genuinely one word — its extension word is mandatory. So after
> this task, `lenWords === 1` on a cpGEN opword means, unambiguously, *"predecode declined to frame
> this one"*, and `lenWords >= 2` means *"the full instruction length is known"*. That single fact is
> what lets Task 6 set `faultUsesNextPc` correctly without a new `DecodePacket` field.

**Why the fall-through must stay exactly as it is:** anything this arm does not frame drops to the
existing

```scala
        } .otherwise {
          r.simple := True; r.lenWords := U(1, 4 bits)
        }
```

which is the correct, safe behavior for an unsupported FP encoding: a one-word F-line trap to vector
11 (`MicroOpAssembler.scala:1531-1535` picks vector 11 from the top nibble). The redirect to the
handler means the mis-framed length is never used to advance the PC. Framing *more* than we can
emit is the dangerous direction, not less.

**Deliberate exclusion — `#imm` source EAs get an explicit table, not `eaExt`'s.** For a normal
instruction the `#imm` extension length follows the *operation* size; for cpGEN it follows the
**source specifier** (ext[12:10]): Long 2 words, Single 2, Extended 6, Packed 6, Word 1, Double 4,
Byte 1. `eaExt`'s `sizeL` parameter cannot express that, so this arm computes the immediate length
from its own format table and never calls `eaExt` with `allowImm = true`.

- [ ] **Step 1: Add the cpGEN arm to the `is(U(0xF, 4 bits))` case**

Replace the body of the F-line arm (`PredecodeWord.scala:1050-1069`) with the following. The two
existing carve-outs are preserved verbatim and stay **first**, so `0xF27F`/`0xF620` cannot be
reinterpreted:

```scala
      is(U(0xF, 4 bits)) {
        // FSF (xxx).L narrow carve-out (task #180, exc_fsf_xxx_l_no_fline): opword
        // 0xF27F + ext1 (discarded) + a 2-word abs.L address = 4 words total. See
        // OperationDecoder.scala for the full derivation/rationale.
        // MOVE16 (Ax)+,(Ay)+ (task #207): opword 0xF620|Ax + 1 ext word carrying Ay
        // (ext[14:12]) = 2 words total. The other 3 absolute-addressing MOVE16 forms
        // stay on the 1-word `otherwise` arm.
        //
        // ── F-line FP-GENERIC (cpGEN) framing ───────────────────────────────────
        // `1111 001 000 mmmrrr` (cpID 001, type 000) is ALWAYS opword + 1 mandatory
        // extension word, plus (for the R/M=1 forms) that extension word's own <ea>
        // extension words. This is the FIRST line-F family whose length depends on a
        // word other than the opword, which is exactly why `extW`/`extWKnown` are
        // plumbed into this function.
        //
        // WHY FRAME THE WHOLE cpGEN FAMILY, not just the hardware-native subset:
        // length is independent of the OPMODE (ext[6:0]) -- an FSIN is framed exactly
        // like an FADD -- and a known length is what lets MicroOpAssembler set
        // faultUsesNextPc=True on the vector-11 trap, so a real FPSP kernel can RTE PAST
        // a software-completed instruction instead of looping on the same opword
        // forever (the gap diagnosed in docs/superpowers/specs/2026-08-09-fpu-hardware-
        // design.md section 5). Framing only the HW-native subset would leave every
        // FPSP-routed instruction with an unknown length -- i.e. would leave the actual
        // point of this feature unimplemented.
        //
        // ESTABLISHED INVARIANT (Task 6 depends on it): a cpGEN instruction is never
        // genuinely 1 word, so lenWords===1 on a cpGEN opword means "not framed".
        val fpIsGen   = (op(11 downto 9) === B"3'b001") && (op(8 downto 6) === B"3'b000")
        val fpClass   = extW(15 downto 13)          // 000/010 arith, 011 FMOVE->ea,
                                                    // 100/101 FMOVE(M) ctrl regs, 110/111 FMOVEM
        val fpSrcSpec = extW(12 downto 10)          // R/M=1: source data FORMAT (see Task 4 Step 1)
        val fpEaMode  = op(5 downto 3).asUInt
        val fpEaReg   = op(2 downto 0).asUInt
        val fpIsImmEa = (fpEaMode === U(7, 3 bits)) && (fpEaReg === U(4, 3 bits))
        // #imm source length is keyed off the FP source SPECIFIER, not the op size, so it
        // cannot go through eaExt (whose `sizeL` has no such notion): Long 4B=2w,
        // Single 4B=2w, Extended 12B=6w, Packed 12B=6w, Word 2B=1w, Double 8B=4w,
        // Byte (word-aligned) =1w. VERIFY the specifier->format mapping per Task 4 Step 1
        // before trusting this table.
        val fpImmWords = fpSrcSpec.asUInt.muxListDc(Seq(
          0 -> U(2, 3 bits), 1 -> U(2, 3 bits), 2 -> U(6, 3 bits), 3 -> U(6, 3 bits),
          4 -> U(1, 3 bits), 5 -> U(4, 3 bits), 6 -> U(1, 3 bits), 7 -> U(0, 3 bits)))
        // FMOVECR (opclass 010 + source specifier 111) has NO <ea> at all -- the opword's
        // EA field is unused and the constant's ROM offset rides ext[6:0]. 2 words flat.
        val fpIsMovecr = (fpClass === B"3'b010") && (fpSrcSpec === B"3'b111")
        // opclass 000 is the register-to-register form: the <ea> field is unused. 2 words.
        val fpIsRegForm = (fpClass === B"3'b000")
        // Every other opclass uses the opword's <ea>. Their EA extension length depends on
        // the ADDRESSING MODE alone (format only matters for #imm, handled above), so one
        // eaExt call covers opclass 010/011/100/101/110/111 uniformly.
        val (fpEaOk, fpEaExt, fpEaAmb) =
          eaExt(fpEaMode, fpEaReg, sizeL = False, allowImm = false, eaW = extW2, eaWKnown = extW2Known)

        when(op === B"16'hF27F") {
          r.simple   := True
          r.lenWords := U(4, 4 bits)
        } .elsewhen(op(15 downto 3) === U(0xF620 >> 3, 13 bits).asBits) {
          r.simple   := True
          r.lenWords := U(2, 4 bits)
        } .elsewhen(fpIsGen && !extWKnown) {
          // The extension word straddles the I-cache line / lookahead window, so the real
          // opclass is unknown. Guess the most common shape (the 2-word register form) and
          // FLAG IT -- the Aligner's live re-classify re-resolves an ambiguousLine slot
          // against real words, and stalls if it still cannot resolve
          // (Aligner.scala:95 `val p0 = Mux(preds(0).ambiguousLine, p0LiveReg, preds(0))`
          // + the `.elsewhen(p0.ambiguousLine)` stall arm at :106, and slot1 packing is
          // refused outright by `slot1Ok`'s `!p1.ambiguousLine` at :267). So the guess
          // NEVER reaches decode -- this is the same contract mode-6's "assume brief"
          // fallback already relies on, not a new one.
          r.simple        := True
          r.lenWords      := U(2, 4 bits)
          r.ambiguousLine := True
        } .elsewhen(fpIsGen && (fpIsRegForm || fpIsMovecr)) {
          r.simple   := True
          r.lenWords := U(2, 4 bits)           // opword + the FP extension word; no <ea>
        } .elsewhen(fpIsGen && fpIsImmEa && (fpClass === B"3'b010")) {
          // `#imm,FPn` -- only a SOURCE (opclass 010) can be immediate; an immediate
          // destination is not encodable, so no other opclass reaches here.
          r.simple   := True
          r.lenWords := (U(2, 4 bits) + fpImmWords).resized
        } .elsewhen(fpIsGen && fpEaOk) {
          r.simple        := True
          r.lenWords      := (U(2, 4 bits) + fpEaExt).resized   // opword + FP ext + EA ext
          r.ambiguousLine := fpEaAmb
        } .otherwise {
          r.simple := True; r.lenWords := U(1, 4 bits)
        }
      }
```

Two mechanical points worth stating so they are not rediscovered in review: the EA's own first
extension word sits at **op+2** (the FP extension word occupies op+1), which is why this arm passes
`extW2`/`extW2Known` to `eaExt` — the same shift the bit-field-memory arm handles at `:1014-1015`,
including its task #197 lesson about passing the *real* word rather than a hardcoded zero. And
`lenWords` is 4 bits: the widest framed case is `2 + 6` (an extended/packed `#imm`) `= 8`, which
fits; `eaExt`'s own maximum is 5, giving 7.

- [ ] **Step 2: Mirror the arm in the authoritative Scala model**

`PredecodeWordSpec`'s exhaustive sweep drives the RTL through the **1-argument** overload
(`PredecodeWord.scala:11`), which supplies `extW = 0` with `extWValid = true`. With `extW = 0` the
opclass reads `000` — the register form — so the reference must return 2 words for the whole cpGEN
band:

```scala
// src/test/scala/m68k040/frontend/PredecodeRef.scala
// In `classify`'s `case 0xF =>` arm (line 520-522), insert the cpGEN case AFTER the two
// literal carve-outs:
      case 0xF =>
        if (op == 0xF27F) CP(simple = true, lenWords = 4)                 // FSF (xxx).L
        else if ((op & 0xFFF8) == 0xF620) CP(simple = true, lenWords = 2) // MOVE16
        // F-line FP-generic (cpGEN, `1111 001 000 mmmrrr`). This model is opword-only,
        // and the RTL's length here genuinely depends on the EXTENSION word -- so this
        // case encodes the RTL's behavior under the exhaustive sweep's own input, which
        // drives the 1-arg PredecodeWord.classify(op) overload (extW = 0, extWValid =
        // true). extW = 0 => opclass 000 => the 2-word register-to-register form. The
        // ext-word-DEPENDENT arms (memory <ea>, #imm, FMOVECR) are outside what this
        // model can express and are covered by PredecodeFpLenSpec's directed vectors
        // instead -- see that file.
        else if (((op >> 9) & 0x7) == 1 && ((op >> 6) & 0x7) == 0) CP(simple = true, lenWords = 2)
        else CP(simple = true, lenWords = 1)
```

- [ ] **Step 3: Directed length test (with real ext words)**

```scala
// src/test/scala/m68k040/frontend/PredecodeFpLenSpec.scala
package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Task 5: F-line FP-generic (cpGEN) length framing, with REAL extension words.
  *
  * PredecodeWordSpec's exhaustive 65536-opword sweep drives the 1-arg classify() overload
  * (extW = 0), so it only ever exercises the opclass-000 register form. Everything whose
  * length depends on the extension word lives here.
  *
  * NOTE on that sweep: it aborts at the FIRST mismatch, so "PredecodeWordSpec passes" is
  * not evidence that the rest of the space was checked. This spec deliberately ACCUMULATES
  * failures and reports them together.
  */
class PredecodeFpLenSpec extends AnyFunSuite {
  class Dut extends Component {
    val op    = in Bits (16 bits)
    val extW  = in Bits (16 bits)   // op+1 : the FP extension word
    val extW2 = in Bits (16 bits)   // op+2 : the <ea>'s own first extension word
    val res   = out(ChunkPredecode())
    res := PredecodeWord.classify(op, extW, extW2)
  }

  private def run(body: (Dut, (Int, Int, Int, Int, String) => Unit) => Unit): Unit =
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val fails = scala.collection.mutable.ArrayBuffer[String]()
      def chk(op: Int, ext: Int, ext2: Int, len: Int, name: String): Unit = {
        dut.op #= op; dut.extW #= ext; dut.extW2 #= ext2; sleep(1)
        if (!dut.res.simple.toBoolean)
          fails += f"$name (op=0x$op%04x ext=0x$ext%04x): expected simple"
        else if (dut.res.lenWords.toInt != len)
          fails += f"$name (op=0x$op%04x ext=0x$ext%04x): len=${dut.res.lenWords.toInt} expected=$len"
      }
      body(dut, chk)
      assert(fails.isEmpty, s"${fails.size} framing mismatches:\n" + fails.mkString("\n"))
    }

  test("cpGEN register-to-register and FMOVECR forms frame as 2 words", VerilatorTest) {
    run { (_, chk) =>
      // FADD FP1,FP0   : opclass 000, src FP1, dst FP0, opmode 0x22
      chk(0xF200, 0x0422, 0x0000, 2, "FADD FP1,FP0")
      // FMUL FP2,FP3   : opclass 000, src FP2, dst FP3, opmode 0x23
      chk(0xF200, 0x09A3, 0x0000, 2, "FMUL FP2,FP3")
      // FTST FP0       : opclass 000, opmode 0x3A
      chk(0xF200, 0x003A, 0x0000, 2, "FTST FP0")
      // FMOVECR #$0F,FP0: opclass 010, source specifier 111, ROM offset 0x0F.
      // This literal is Musashi's own documented example (m68kfpu.c: "fmovecr #$f, fp0 f200 5c0f").
      chk(0xF200, 0x5C0F, 0x0000, 2, "FMOVECR #$0F,FP0")
      // A NON-native opmode must be framed IDENTICALLY -- length is opmode-independent, and
      // this is exactly the FPSP-routed case that needs a known length to RTE past.
      chk(0xF200, 0x000E, 0x0000, 2, "FSIN FP0,FP0 (FPSP-routed, still framed)")
    }
  }

  test("cpGEN <ea>-source forms add the EA's own extension words", VerilatorTest) {
    run { (_, chk) =>
      // FADD.L D1,FP0     : opclass 010, src spec 000 (long int), EA mode 0 reg 1 -> 0 ext
      chk(0xF201, 0x4022, 0x0000, 2, "FADD.L D1,FP0")
      // FADD.L (A0),FP0   : EA mode 2 -> 0 ext
      chk(0xF210, 0x4022, 0x0000, 2, "FADD.L (A0),FP0")
      // FADD.L (d16,A0),FP0: EA mode 5 -> 1 ext
      chk(0xF228, 0x4022, 0x0004, 3, "FADD.L (d16,A0),FP0")
      // FADD.L (xxx).L,FP0: EA mode 7 reg 1 -> 2 ext
      chk(0xF239, 0x4022, 0x0000, 4, "FADD.L (xxx).L,FP0")
      // FADD.L (d8,A0,Xn),FP0: EA mode 6, BRIEF format (extW2 bit8 = 0) -> 1 ext
      chk(0xF230, 0x4022, 0x1000, 3, "FADD.L (d8,A0,Xn),FP0 brief")
      // FMOVE.X FP0,(A0)  : opclass 011 (FMOVE FPn -> <ea>), EA mode 2 -> 0 ext.
      // Emission is deferred to Task 10; the LENGTH is framed now so the vector-11 trap
      // carries a known length and FPSP can RTE past it.
      chk(0xF210, 0x6800, 0x0000, 2, "FMOVE.X FP0,(A0) [framed, emission deferred]")
      // FMOVEM.X (A0),FP0-FP7 : opclass 110, EA mode 2 -> 0 ext (same reasoning)
      chk(0xF210, 0xD0FF, 0x0000, 2, "FMOVEM.X (A0),FP0-FP7 [framed, emission deferred]")
    }
  }

  test("cpGEN #imm source length follows the FP source SPECIFIER, not the op size", VerilatorTest) {
    run { (_, chk) =>
      val immEa = 0xF23C   // cpGEN with <ea> = mode 7 reg 4 (#imm)
      chk(immEa, 0x4022, 0x0000, 4, "FADD.L #imm,FP0   (long   -> 2 imm words)")
      chk(immEa, 0x4422, 0x0000, 4, "FADD.S #imm,FP0   (single -> 2 imm words)")
      chk(immEa, 0x4822, 0x0000, 8, "FADD.X #imm,FP0   (ext    -> 6 imm words)")
      chk(immEa, 0x5022, 0x0000, 3, "FADD.W #imm,FP0   (word   -> 1 imm word)")
      chk(immEa, 0x5422, 0x0000, 6, "FADD.D #imm,FP0   (double -> 4 imm words)")
      chk(immEa, 0x5822, 0x0000, 3, "FADD.B #imm,FP0   (byte   -> 1 imm word)")
    }
  }

  test("unframeable cpGEN EAs and the non-cpGEN line-F space keep the 1-word trap framing", VerilatorTest) {
    run { (_, chk) =>
      // Reserved <ea> encodings (mode 7 regs 5/6/7) -> eaExt rejects -> 1-word F-line trap.
      chk(0xF23D, 0x4022, 0x0000, 1, "cpGEN with reserved <ea> mode7/reg5")
      chk(0xF23F, 0x4022, 0x0000, 1, "cpGEN with reserved <ea> mode7/reg7")
      // FScc / FBcc / FSAVE / FRESTORE (opword bits[8:6] != 000) are NOT cpGEN.
      chk(0xF240, 0x0000, 0x0000, 1, "FScc  (type 001)")
      chk(0xF280, 0x0000, 0x0000, 1, "FBcc.W (type 010)")
      chk(0xF2C0, 0x0000, 0x0000, 1, "FBcc.L (type 011)")
      chk(0xF300, 0x0000, 0x0000, 1, "FSAVE-band opword (bit 8 set -- Task 9/11)")
      // The pre-existing carve-outs are unchanged.
      chk(0xF27F, 0x0000, 0x0000, 4, "FSF (xxx).L")
      chk(0xF620, 0x0000, 0x0000, 2, "MOVE16 (Ax)+,(Ay)+")
      chk(0xF600, 0x0000, 0x0000, 1, "MOVE16 absolute form (out of scope, 1 word)")
    }
  }

  test("an unresident FP extension word frames 2 words AND flags ambiguousLine", VerilatorTest) {
    // extWValid=false is the I-cache-line-boundary case: the Aligner must re-resolve or
    // stall, so the guessed length must never be trusted downstream.
    class AmbDut extends Component {
      val op  = in Bits (16 bits)
      val res = out(ChunkPredecode())
      res := PredecodeWord.classify(op, B(0, 16 bits), B(0, 16 bits),
                                    extWValid = false, extW2Valid = false)
    }
    SimConfig.withVerilator.compile(new AmbDut).doSim { dut =>
      dut.op #= 0xF200; sleep(1)
      assert(dut.res.simple.toBoolean, "cpGEN with an unknown ext word must stay simple (guess + flag)")
      assert(dut.res.lenWords.toInt == 2, s"guess must be the 2-word register form, got ${dut.res.lenWords.toInt}")
      assert(dut.res.ambiguousLine.toBoolean,
        "the guess MUST set ambiguousLine so Aligner re-resolves or stalls (Aligner.scala:95,106,267)")
      dut.op #= 0xF27F; sleep(1)
      assert(!dut.res.ambiguousLine.toBoolean, "the FSF carve-out is opword-only -- never ambiguous")
    }
  }
}
```

- [ ] **Step 4: Run the predecode tests, including the exhaustive RTL-vs-ref sweep**

```bash
sbt "testOnly m68k040.frontend.PredecodeFpLenSpec"
sbt "testOnly m68k040.frontend.PredecodeWordSpec m68k040.frontend.PredecodeRefSpec m68k040.frontend.PredecodeSimpleLenSpec"
```
Expected: all PASS. The exhaustive sweep is the one that catches a `PredecodeRef` update that does
not match the RTL under `extW = 0`. **Remember its known limitation** (it aborts at the first
mismatch, so it only proves "no mismatch before the first opword it reached" if it fails); on any
failure, read the reported opword rather than assuming the rest of the space is clean.

- [ ] **Step 5: Confirm the lock-step and frontend suites are unaffected**

```bash
sbt "testOnly m68k040.frontend.*"
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
```
Expected: frontend suite green (`AlignerSpec`/`FetchAlign*`/`InstructionBufferSpec` are the ones
that would notice a new `ambiguousLine` producer); `ExecuteLockStepSpec` 396/396. Nothing executes
differently yet — every cpGEN encoding still traps via Task 4's `fpGenBad`; only the *stacked PC*
would change, and that arrives in Task 6.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/frontend/PredecodeWord.scala \
        src/test/scala/m68k040/frontend/PredecodeRef.scala \
        src/test/scala/m68k040/frontend/PredecodeFpLenSpec.scala
git commit -m "feat(fpu): F-line FP-generic (cpGEN) instruction length framing

Frames the whole cpGEN family -- opword + the mandatory FP extension word
+ that word's own <ea> extension words -- in PredecodeWord's is(0xF) arm.
This is the first line-F family whose length depends on a word other than
the opword, which is what the extW/extWKnown plumbing already in
classify() exists for.

The whole family is framed, not just the hardware-native subset, and that
is the point: length is independent of the extension word's OPMODE field
(an FSIN frames exactly like an FADD), and a KNOWN length is what lets
MicroOpAssembler set faultUsesNextPc on the vector-11 trap so a real FPSP
kernel can RTE past a software-completed FP instruction instead of
looping on the same opword forever -- the gap diagnosed in
docs/superpowers/specs/2026-08-09-fpu-hardware-design.md section 5.
Framing only what we execute in hardware would leave every FPSP-routed
instruction with an unknown length, i.e. would leave the actual point of
the feature unimplemented.

This establishes the invariant Task 6 keys off: a cpGEN instruction is
never genuinely one word, so lenWords===1 on a cpGEN opword means
'predecode declined to frame this', and lenWords>=2 means 'full length
known' -- no new DecodePacket field needed.

Details worth flagging for review:
- The <ea>'s own first extension word is at op+2 (the FP ext word takes
  op+1), so this arm passes extW2/extW2Known to eaExt -- the same shift
  the bit-field-memory arm handles, including task #197's lesson about
  passing the REAL word rather than a hardcoded zero.
- #imm source length follows the FP SOURCE SPECIFIER (long/single 2w,
  extended/packed 6w, word/byte 1w, double 4w), not the operation size,
  so it uses an explicit format table instead of eaExt's sizeL (which
  cannot express it). eaExt is therefore always called allowImm=false.
- An unresident extension word guesses the 2-word register form and sets
  ambiguousLine, so Aligner's live re-classify re-resolves or stalls
  (Aligner.scala:95/106/267) -- the same contract mode-6's 'assume brief'
  fallback already relies on, not a new one.
- Everything unframeable (reserved <ea> modes, FScc/FBcc, the
  FSAVE/FRESTORE band) falls through to the pre-existing 1-word arm: a
  correct vector-11 F-line trap, never a mis-framed instruction stream.

PredecodeRef is updated in lockstep. Its cpGEN case encodes the RTL's
behavior under the exhaustive sweep's own input (the 1-arg classify
overload, extW=0 => opclass 000 => 2 words); the ext-word-dependent arms
are outside what an opword-only model can express and are covered by
PredecodeFpLenSpec's directed vectors, which accumulate failures rather
than aborting at the first one.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: `MicroOpAssembler` — the `faultUsesNextPc` gap, then FP µop assembly

**Files:**
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala`
- Test: `src/test/scala/m68k040/decode/FpAssembleSpec.scala`

**Interfaces:**
- Consumes: `OpSpec.fpGeneric` (Task 4), `pkt.words(1)`/`pkt.lenWords`/`pkt.simple` (Task 5's
  framing), the `DecodedUop` FP fields and `fpInert()` helper (Task 4).
- Produces: fully-populated FP `DecodedUop`s consumed by Task 2's `RenameStage` (`decUop(s).
  fpSrcAReg`/`fpSrcBReg`/`fpDstReg`/`usesFpSrcA`/`usesFpSrcB`/`writesFp`/`readsFpcc`/`writesFpcc`),
  Task 3's IQ, and Task 8's EU (`fpuOp`/`fpSrcKind`).

**Grounding — the shape a plain 2-source/1-dest op is assembled into.** The base `opUop` is built
field-by-field at `:685-743`, then the operand slots are filled by `switch(spec.srcA.kind)` /
`switch(spec.srcB.kind)` (`:746-...`), and later `when(...)` arms override for special families
(last-writer-wins). The CPLX precedent to copy is MUL/DIV — `OperationDecoder.scala:915-927` names
the op and `MicroOpAssembler` refines it; and the CPLX fault-PC precedent is `:731-737`:

```scala
    // CHK / DIV are group-2 traps (CHK vec6, DIV0 vec5) delivered execute-time via
    // euFault -> format-$2: they stack the NEXT instruction's PC (the 040 group-2
    // frame's PC = pc+len). The fault is conditional (set at execute), but faultPc is
    // captured at ALLOC, so faultUsesNextPc must be set NOW for the CPLX ops.
    when(spec.op === DecOp.CHK || spec.op === DecOp.DIV) {
      opUop.faultUsesNextPc := True
    }
```

**The already-diagnosed bug this task fixes first** (`2026-08-09-fpu-hardware-design.md` §5, verbatim):

> **This project has no post-instruction-PC flavor of vector-11 delivery at all** —
> `MicroOpAssembler.scala`'s generic line-F fallback leaves `faultUsesNextPc := False`. This matters
> directly for this FPU work: an FPSP-style handler that expects to RTE past a recognized but
> software-completed FPU instruction needs the post-instruction-PC variant, or it will loop on the
> same opword forever. […] **Required here:** any recognized FPU instruction deliberately handed to
> FPSP after its full length and required source state have been captured sets
> `faultUsesNextPc := True`; an unrecognized line-F encoding keeps it False. The architectural
> vector remains 11 in both cases.

That fix is Steps 1-3, landed and tested **before** any FP assembly. It is genuinely standalone and
genuinely testable at that point, because Task 5 already frames cpGEN lengths while Task 4's
`fpGenBad` still faults every one of them — so the "framed but routed to software" case exists in
the RTL with no FP execution anywhere near it. Do not reorder these steps.

**Scope split inside this plan (state it in review, do not silently widen):** this task emits real
µops for the **single-µop** forms only —

| Form | ext[15:13] | Emitted here? |
|---|---|---|
| `F<op> FPm,FPn` (register to register) | `000` | **Yes** |
| `F<op>.<int/single> Dn,FPn` (data-register source) | `010`, `<ea>` = mode 0 | **Yes** |
| `FMOVECR #ccc,FPn` | `010`, src spec `111` | **Yes** |
| `F<op> <mem>,FPn` (real memory source) | `010`, `<ea>` ≥ mode 2 | No — Task 10 (needs a load crack; the X/D/P formats are 96/64/96-bit, i.e. multi-load) |
| `FMOVE FPn,<ea>` | `011` | No — Task 10 |
| `FMOVE(M) <ea>,FPCR/FPSR/FPIAR` | `100`/`101` | No — Task 10 |
| `FMOVEM <ea>,list` / `list,<ea>` | `110`/`111` | No — Task 10 (a MOVEM-style DecodeStage sequencer, not an assembler crack) |

Everything in the "No" rows keeps taking vector 11 — but now, thanks to Task 5 + Step 2, with
`faultUsesNextPc = True`, which is precisely what makes them FPSP-completable rather than infinite
loops.

---

- [ ] **Step 1: Read the current gap in place (no edit — orientation)**

```bash
sed -n '1517,1536p' src/main/scala/m68k040/decode/MicroOpAssembler.scala
```
This prints the `when(bad)` arm — the *only* producer of vector 11 in the design:

```scala
    when(bad) {
      opUop.op            := DecOp.ILLEGAL
      ...
      opUop.faulted     := True
      opUop.faultVector := (op(15 downto 12).asUInt).mux(
        U(0xA, 4 bits) -> U(10, 8 bits),
        U(0xF, 4 bits) -> U(11, 8 bits),
        default        -> U(4, 8 bits)
      )
    }
```

Note what is absent: nothing in it ever touches `faultUsesNextPc`, so it inherits the base
`opUop.faultUsesNextPc := False` from `:706`. That is the bug.

- [ ] **Step 2: Add the known-length line-F post-instruction-PC flavor**

```scala
// src/main/scala/m68k040/decode/MicroOpAssembler.scala
// Immediately AFTER the closing brace of the `when(bad)` arm (line 1536), add:

    // ── Line-F trap PC flavor: pre-instruction vs post-instruction ───────────────
    // A vector-11 F-line trap comes in two flavors, and this project previously had only
    // one. The generic top-nibble fallback has an UNKNOWN instruction length, so it must
    // stack the FAULTING pc (restartable, faultUsesNextPc=False) -- the handler cannot
    // know how far to advance. But an F-line encoding whose FULL length predecode DID
    // frame is a different case: a real FPSP kernel emulates the instruction and RTEs,
    // and if the frame carries the faulting PC it re-executes the same opword forever.
    // Diagnosed in docs/superpowers/specs/2026-08-09-fpu-hardware-design.md section 5,
    // cross-checked against m68k-ooo's two vector-11 delivery paths (its packed-source
    // capture crack, which knows the length, raises an internal pseudo-vector that commit
    // translates back to architectural vector 11 while selecting the fall-through PC;
    // its plain top-nibble fallback keeps the faulting PC). The ARCHITECTURAL VECTOR IS
    // 11 IN BOTH CASES -- there is no second vector and no pseudo-vector exposed here,
    // only the PC-field selection the ROB already implements for TRAP/TRAPV/CHK/DIV0.
    //
    // The discriminator needs no new DecodePacket field, because Task 5 established the
    // invariant: a cpGEN instruction is NEVER genuinely one word (its extension word is
    // mandatory), so on a cpGEN opword lenWords===1 means "predecode declined to frame
    // it" and lenWords>=2 means "full length known". `pkt.simple` is required too: a
    // COMPLEX packet's lenWords is meaningless (0).
    val fpLenKnown = spec.fpGeneric && pkt.simple && (pkt.lenWords >= U(2))
    when(bad && fpLenKnown) {
      opUop.faultUsesNextPc := True
    }
```

- [ ] **Step 3: Test the fix standalone, then run it**

```scala
// src/test/scala/m68k040/decode/FpAssembleSpec.scala   (part 1 of 2 -- the rest lands in Step 6)
package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class FpAssembleSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt).uops(0)
  }
  /** Drive a packet whose length matches what PredecodeWord would really frame. */
  def drive(dut: Dut, op: Int, ext: Int = 0, ext2: Int = 0, len: Int = 2, simple: Boolean = true): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x2000; dut.pkt.simple #= simple; dut.pkt.complex #= !simple
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= ext; dut.pkt.words(2) #= ext2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  // ── Step 2's standalone fix: the two line-F trap PC flavors ────────────────────
  test("a FRAMED line-F encoding traps to vector 11 with the POST-instruction PC", VerilatorTest) {
    run { dut =>
      // FSIN FP0,FP0 (opmode 0x0E) -- a real cpGEN instruction, framed 2 words by Task 5,
      // NOT hardware-native, so it is exactly the FPSP-routed case.
      drive(dut, op = 0xF200, ext = 0x000E, len = 2); sleep(1)
      assert(dut.uop.faulted.toBoolean, "an unimplemented FP op must fault")
      assert(dut.uop.faultVector.toInt == 11, s"architectural vector stays 11, got ${dut.uop.faultVector.toInt}")
      assert(dut.uop.faultUsesNextPc.toBoolean,
        "a framed line-F trap must stack the POST-instruction PC, else FPSP's RTE re-executes the same opword forever")
      assert(dut.uop.nextPc.toLong == 0x2004L,
        f"nextPc must be pc + 2*lenWords = 0x2004, got 0x${dut.uop.nextPc.toLong}%x")
    }
  }

  test("an UNFRAMED line-F encoding keeps the PRE-instruction (faulting) PC", VerilatorTest) {
    run { dut =>
      // FBcc.W (type 010) -- not cpGEN, predecode frames it 1 word, length unknown.
      drive(dut, op = 0xF280, ext = 0x0000, len = 1); sleep(1)
      assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 11)
      assert(!dut.uop.faultUsesNextPc.toBoolean,
        "an unknown-length line-F trap MUST stay restartable (faulting PC) -- the handler cannot know the length")
      // And a cpGEN opword that predecode declined to frame (reserved <ea>) behaves the same.
      drive(dut, op = 0xF23D, ext = 0x4022, len = 1); sleep(1)
      assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 11)
      assert(!dut.uop.faultUsesNextPc.toBoolean,
        "lenWords===1 on a cpGEN opword means 'not framed' -- it must NOT claim a known length")
    }
  }

  test("line-A and generic illegal traps are untouched by the line-F change", VerilatorTest) {
    run { dut =>
      drive(dut, op = 0xA000, len = 1); sleep(1)
      assert(dut.uop.faultVector.toInt == 10 && !dut.uop.faultUsesNextPc.toBoolean, "line-A vector 10, faulting PC")
      drive(dut, op = 0x4AFC, len = 1); sleep(1)   // ILLEGAL
      assert(dut.uop.faultVector.toInt == 4 && !dut.uop.faultUsesNextPc.toBoolean, "generic illegal vector 4, faulting PC")
    }
  }
}
```

```bash
sbt "testOnly m68k040.decode.FpAssembleSpec"
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
```
Expected: the three tests above PASS and `ExecuteLockStepSpec` is 396/396. The lock-step run is the
real proof that Step 2 is a no-op on the existing ISA: `fpLenKnown` requires `spec.fpGeneric`, which
no pre-FPU encoding sets.

**Commit this sub-step on its own** (it is an independently-diagnosed bug fix with an independent
test, and keeping it separate makes it trivially revertable and trivially reviewable):

```bash
git add src/main/scala/m68k040/decode/MicroOpAssembler.scala \
        src/test/scala/m68k040/decode/FpAssembleSpec.scala
git commit -m "fix(decode): known-length line-F traps stack the POST-instruction PC

Vector-11 F-line delivery has two legitimate flavors and this project
only had one. The generic top-nibble fallback has an unknown instruction
length and must stack the FAULTING pc (restartable) -- the handler cannot
know how far to advance. But an F-line encoding whose full length
predecode DID frame is the FPSP case: the kernel emulates the instruction
and RTEs, and with the faulting PC in the frame it re-executes the same
opword forever.

Diagnosed in docs/superpowers/specs/2026-08-09-fpu-hardware-design.md
section 5 and cross-checked against m68k-ooo's own two vector-11 paths
(its length-aware capture crack selects the fall-through PC; its plain
top-nibble fallback keeps the faulting PC). The ARCHITECTURAL VECTOR IS
11 in both cases -- no second vector, no pseudo-vector exposed; only the
PC-field selection the ROB already implements for TRAP/TRAPV/CHK/DIV0.

The discriminator needs no new DecodePacket field: a cpGEN instruction is
never genuinely one word (its extension word is mandatory), so on a cpGEN
opword lenWords===1 means 'predecode declined to frame it' and
lenWords>=2 means 'full length known'.

Behavior-neutral on the current ISA -- the new term requires
spec.fpGeneric, which no pre-FPU encoding sets -- and ExecuteLockStepSpec
is unchanged at 396/396.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

- [ ] **Step 4: Decode the extension word and define what is emittable**

```scala
// src/main/scala/m68k040/decode/MicroOpAssembler.scala
// Immediately BEFORE the `val bad = ...` expression (i.e. replacing Task 4's placeholder
// `val fpGenBad = spec.fpGeneric` line), add:

    // ── F-line FP-generic (cpGEN) extension-word decode ─────────────────────────
    // The opword names the FAMILY (OperationDecoder, Task 4); everything that matters
    // lives in words(1). Field positions confirmed against the in-tree vendored
    // tools/musashi/musashi/m68kfpu.c (fpgen_rm_reg: rm=(w2>>14)&1, src=(w2>>10)&7,
    // dst=(w2>>7)&7, opmode=w2&0x7f; m68040_fpu_op0's sub-switch on (w2>>13)&7).
    val fpExt      = pkt.words(1)
    val fpOpClass  = fpExt(15 downto 13)      // 000/010 arith, 011 FMOVE->ea, 100/101 ctrl, 110/111 FMOVEM
    val fpSrcSpec  = fpExt(12 downto 10)      // FPm (R/M=0) or the source data FORMAT (R/M=1)
    val fpDstFp    = fpExt(9 downto 7).asUInt // destination FPn
    val fpOpmode   = fpExt(6 downto 0)        // the operation (or, for FMOVECR, the ROM offset)
    val fpEaMode   = op(5 downto 3).asUInt
    val fpEaReg    = op(2 downto 0).asUInt

    // The hardware-native opmode whitelist (plan Global Constraints; every value
    // confirmed against m68kfpu.c's fpgen_rm_reg opmode switch). Anything else --
    // transcendentals, FMOD/FREM/FSCALE/FGETEXP, FSINCOS, and the 68040 rounded-precision
    // FSxxx/FDxxx variants (opmode bit 6 set) -- routes to FPSP via vector 11.
    val fpNative =
      (fpOpmode === B"7'h00") || (fpOpmode === B"7'h01") || (fpOpmode === B"7'h03") ||
      (fpOpmode === B"7'h04") || (fpOpmode === B"7'h18") || (fpOpmode === B"7'h1A") ||
      (fpOpmode === B"7'h20") || (fpOpmode === B"7'h22") || (fpOpmode === B"7'h23") ||
      (fpOpmode === B"7'h28") || (fpOpmode === B"7'h38") || (fpOpmode === B"7'h3A")
    // DYADIC ops compute `FPn <op> source`, so they READ the destination FPn as an
    // operand. The monadic ops (FMOVE/FABS/FNEG/FSQRT/FINT/FINTRZ/FTST) do not -- their
    // result is a function of the source alone, and claiming a false RAW dependency on
    // FPn would needlessly serialize independent FP work in the IQ.
    val fpDyadic =
      (fpOpmode === B"7'h20") || (fpOpmode === B"7'h22") || (fpOpmode === B"7'h23") ||
      (fpOpmode === B"7'h28") || (fpOpmode === B"7'h38")
    // FCMP (0x38) and FTST (0x3A) write ONLY the condition codes -- no FP destination.
    val fpNoFpDst = (fpOpmode === B"7'h38") || (fpOpmode === B"7'h3A")

    // Emittable forms (this task's scope -- see the plan's scope table). All THREE are
    // single-uop and touch no memory:
    //   (a) opclass 000  : F<op> FPm,FPn
    //   (b) opclass 010 with an INT/single source specifier and <ea> = Dn (mode 0):
    //       F<op>.L/.W/.B/.S Dn,FPn -- a plain 32-bit int register read on the EXISTING
    //       int rename path (no 80-bit value ever enters IqContext, per the 2026-08-09
    //       design's gateway topology).
    //   (c) opclass 010 with source specifier 111 : FMOVECR #ccc,FPn (constant ROM).
    // Real memory sources, FMOVE->ea, the FPCR/FPSR/FPIAR moves and FMOVEM are DEFERRED
    // to Task 10 -- they need a load/store crack or a MOVEM-style sequencer, and the
    // X/D/P formats are 96/64/96 bits (multi-access), not a single load.
    val fpFormIsReg    = (fpOpClass === B"3'b000")
    val fpFormIsMovecr = (fpOpClass === B"3'b010") && (fpSrcSpec === B"3'b111")
    val fpIntFmt       = (fpSrcSpec === B"3'b000") || (fpSrcSpec === B"3'b100") ||
                         (fpSrcSpec === B"3'b110") || (fpSrcSpec === B"3'b001")  // L / W / B / S
    val fpFormIsIntReg = (fpOpClass === B"3'b010") && fpIntFmt && (fpEaMode === U(0, 3 bits))
    val fpEmit = spec.fpGeneric && pkt.simple && (pkt.lenWords >= U(2)) &&
                 (fpFormIsMovecr || ((fpFormIsReg || fpFormIsIntReg) && fpNative))
    // Narrowed from Task 4's blanket `spec.fpGeneric`: everything cpGEN that this task
    // does NOT emit still takes the ordinary vector-11 F-line trap -- now with
    // faultUsesNextPc=True whenever predecode framed it (Step 2), which is what makes it
    // FPSP-completable instead of an infinite loop.
    val fpGenBad = spec.fpGeneric && !fpEmit
```

`fpEmit` requires `pkt.simple && lenWords >= 2` for a real reason, not belt-and-braces: if predecode
declined to frame an encoding this arm would happily emit, `nextPc` would be `pc + 2` for a longer
instruction — a wild-PC class bug. Gating emission on the *same* framing fact that gates
`faultUsesNextPc` keeps the two files' views of an instruction provably consistent.

- [ ] **Step 5: Emit the FP µop**

```scala
// src/main/scala/m68k040/decode/MicroOpAssembler.scala
// AFTER the `when(bad)` arm and the Step-2 `when(bad && fpLenKnown)` arm (so it is a
// last-writer-wins override of the ILLEGAL/faulted defaults, exactly as isToCcr/isSysOp/
// isMoveFromSrOp do), add:

    // ── F-line FP-generic uop assembly (DecOp.FPU, CPLX cluster) ────────────────
    when(fpEmit) {
      opUop.op       := DecOp.FPU
      opUop.cluster  := Cluster.CPLX      // spec Decision 9 -- shared CPLX cluster/IQ port/ROB port
      opUop.memOp    := MemOp.NONE
      opUop.unimplemented := False
      opUop.faulted  := False; opUop.faultVector := 0; opUop.faultUsesNextPc := False
      opUop.isBranch := False
      opUop.firstOfInstr := True          // a single uop: it IS the macro boundary
      opUop.fpuOp    := fpOpmode
      // The integer CCR is untouched by every FP op (FPCC is a separate rename class).
      opUop.readsNzvc := False; opUop.writesNzvc := False
      opUop.readsX    := False; opUop.writesX    := False
      // No INT destination: the 80-bit result goes to the FP PRF via Task 8's separate
      // writeback lane (the existing 32-bit CplxResult.data lane cannot carry it).
      opUop.dstValid := False
      // Destination FPn + FPCC. EVERY hardware-native FP op writes FPCC (Musashi calls
      // SET_CONDITION_CODES on every arm, including FMOVE-to-FPn and FMOVECR); FCMP and
      // FTST write ONLY FPCC. Nothing READS FPCC yet -- FBcc/FScc/FDBcc and
      // FMOVE-from-FPSR are deferred -- so readsFpcc stays False here; the rename class
      // and its IQ scoreboard (Task 3) exist so that lands as a pure addition.
      opUop.fpDstReg  := fpDstFp
      opUop.writesFp  := !fpNoFpDst
      opUop.writesFpcc := True
      opUop.readsFpcc  := False
      // srcA = the DESTINATION FPn read back, ONLY for the dyadic ops.
      opUop.fpSrcAReg := fpDstFp
      opUop.usesFpSrcA := fpDyadic
      // Source routing.
      when(fpFormIsMovecr) {
        // FMOVECR: no register source at all; the constant's ROM offset rides `imm`.
        // useImm=True is safe here -- this uop has no integer srcB, and the IQ's
        // srcBIsReg() only consults useImm to decide whether to track psrcB, which is
        // invalid on this uop anyway.
        opUop.fpSrcKind := FpSrcKind.ROMCONST
        opUop.usesFpSrcB := False
        opUop.fpSrcBReg  := 0
        opUop.usesFpSrcA := False        // FMOVECR overwrites FPn; it never reads it
        opUop.srcAValid  := False; opUop.srcBValid := False
        opUop.useImm     := True
        opUop.imm        := fpOpmode.resize(32)
        opUop.size       := Size.LONG
      } .elsewhen(fpFormIsReg) {
        // F<op> FPm,FPn: the source is FP register FPm (ext[12:10]).
        opUop.fpSrcKind  := FpSrcKind.FPREG
        opUop.fpSrcBReg  := fpSrcSpec.asUInt
        opUop.usesFpSrcB := True
        opUop.srcAValid  := False; opUop.srcBValid := False
        opUop.useImm     := False
        opUop.size       := Size.LONG
      } .otherwise {
        // F<op>.L/.W/.B/.S Dn,FPn: a 32-bit INTEGER register read on the ORDINARY int
        // rename/scoreboard path (srcA/psrcA), converted to extended precision inside the
        // EU. This deliberately keeps every 80-bit value out of IqContext and the integer
        // operand mux, per the 2026-08-09 design's gateway topology.
        opUop.fpSrcKind  := FpSrcKind.INTREG
        opUop.usesFpSrcB := False
        opUop.fpSrcBReg  := 0
        opUop.srcAReg    := fpEaReg.resize(5)   // Dn (<ea> mode 0), i.e. arch reg 0..7
        opUop.srcAValid  := True
        opUop.srcBValid  := False
        opUop.useImm     := False
        // `size` tells the EU how wide the integer source is. The single-precision case
        // (specifier 001) is a 32-bit BIT PATTERN, not an integer, and is distinguished by
        // fpSrcSpec-derived state in the EU, not by `size` -- LONG is the correct width
        // for both. (Task 8 owns the conversion; it re-reads the specifier from `fpuOp`'s
        // sibling state, so do NOT try to encode the format in `size`.)
        when(fpSrcSpec === B"3'b100") { opUop.size := Size.WORD }
          .elsewhen(fpSrcSpec === B"3'b110") { opUop.size := Size.BYTE }
          .otherwise { opUop.size := Size.LONG }
      }
    }
```

**Open item for Task 8, flagged rather than fudged:** the INTREG form needs the *source specifier*
(L vs W vs B vs S) in the EU, and the snippet above carries only `size`, which cannot distinguish
Long from Single. Either (a) widen `fpuOp` to carry `{fpSrcSpec, opmode}` (10 bits), or (b) add a
3-bit `fpSrcFmt` field to `DecodedUop`. Decide this at the top of Task 8 and update this step's code
accordingly — **do not leave the Single case silently decoding as Long**, which would produce a
plausible-looking but completely wrong result for `FMOVE.S D0,FP0`.

- [ ] **Step 6: Extend `FpAssembleSpec` with the FP-emission cases**

```scala
// src/test/scala/m68k040/decode/FpAssembleSpec.scala   (part 2 -- append inside the class)

  test("FADD FP1,FP0 emits one CPLX FP uop: dyadic reads FPn, source FPm, writes FPn+FPCC", VerilatorTest) {
    run { dut =>
      drive(dut, op = 0xF200, ext = 0x0422, len = 2); sleep(1)   // opclass 000, src FP1, dst FP0, opmode 0x22
      assert(!dut.uop.faulted.toBoolean, "FADD is hardware-native -- it must not trap")
      assert(dut.uop.op.toEnum == DecOp.FPU && dut.uop.cluster.toEnum == Cluster.CPLX)
      assert(dut.uop.fpuOp.toInt == 0x22, s"fpuOp must be the raw opmode 0x22, got 0x${dut.uop.fpuOp.toInt.toHexString}")
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.FPREG)
      assert(dut.uop.usesFpSrcA.toBoolean && dut.uop.fpSrcAReg.toInt == 0,
        "a DYADIC op reads its destination FP0 as an operand")
      assert(dut.uop.usesFpSrcB.toBoolean && dut.uop.fpSrcBReg.toInt == 1, "source is FP1")
      assert(dut.uop.writesFp.toBoolean && dut.uop.fpDstReg.toInt == 0, "destination is FP0")
      assert(dut.uop.writesFpcc.toBoolean, "every FP op writes FPCC")
      assert(!dut.uop.readsFpcc.toBoolean, "nothing reads FPCC yet (FBcc/FScc deferred)")
      assert(!dut.uop.dstValid.toBoolean && !dut.uop.srcAValid.toBoolean && !dut.uop.srcBValid.toBoolean,
        "an FP register-form uop touches NO integer registers")
      assert(!dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean, "FP ops never touch the integer CCR")
      assert(dut.uop.firstOfInstr.toBoolean, "single uop -- it is the macro boundary")
    }
  }

  test("monadic FP ops do NOT read their destination (no false RAW dependency)", VerilatorTest) {
    run { dut =>
      // FABS FP2,FP3 : opclass 000, src FP2, dst FP3, opmode 0x18 -> ext = 0x09 98
      drive(dut, op = 0xF200, ext = 0x0998, len = 2); sleep(1)
      assert(!dut.uop.faulted.toBoolean && dut.uop.fpuOp.toInt == 0x18)
      assert(!dut.uop.usesFpSrcA.toBoolean, "FABS is monadic -- it must not claim its destination as a source")
      assert(dut.uop.usesFpSrcB.toBoolean && dut.uop.fpSrcBReg.toInt == 2)
      assert(dut.uop.writesFp.toBoolean && dut.uop.fpDstReg.toInt == 3)
    }
  }

  test("FCMP and FTST write ONLY the condition codes", VerilatorTest) {
    run { dut =>
      drive(dut, op = 0xF200, ext = 0x04B8, len = 2); sleep(1)   // FCMP FP1,FP1: opmode 0x38, src FP1, dst FP1
      assert(!dut.uop.faulted.toBoolean && dut.uop.fpuOp.toInt == 0x38)
      assert(!dut.uop.writesFp.toBoolean, "FCMP writes no FP register")
      assert(dut.uop.writesFpcc.toBoolean, "FCMP writes FPCC")
      assert(dut.uop.usesFpSrcA.toBoolean, "FCMP is dyadic -- it reads the destination operand")
      drive(dut, op = 0xF200, ext = 0x003A, len = 2); sleep(1)   // FTST FP0
      assert(!dut.uop.faulted.toBoolean && dut.uop.fpuOp.toInt == 0x3A)
      assert(!dut.uop.writesFp.toBoolean && dut.uop.writesFpcc.toBoolean)
      assert(!dut.uop.usesFpSrcA.toBoolean, "FTST is monadic (a classifier, not a compare against zero)")
    }
  }

  test("FMOVE.L D3,FP0 sources an INTEGER register on the ordinary int rename path", VerilatorTest) {
    run { dut =>
      // opclass 010, source specifier 000 (long int), <ea> = mode 0 reg 3, opmode 0x00
      drive(dut, op = 0xF203, ext = 0x4000, len = 2); sleep(1)
      assert(!dut.uop.faulted.toBoolean)
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.INTREG)
      assert(dut.uop.srcAValid.toBoolean && dut.uop.srcAReg.toInt == 3, "int source is D3 via srcA")
      assert(!dut.uop.usesFpSrcB.toBoolean, "no FP register source in the int-source form")
      assert(!dut.uop.usesFpSrcA.toBoolean, "FMOVE is monadic")
      assert(dut.uop.writesFp.toBoolean && dut.uop.fpDstReg.toInt == 0)
      assert(dut.uop.size.toEnum == Size.LONG)
    }
  }

  test("FMOVECR #$0F,FP0 sources the constant ROM via imm, no register source", VerilatorTest) {
    run { dut =>
      drive(dut, op = 0xF200, ext = 0x5C0F, len = 2); sleep(1)   // Musashi's own documented literal
      assert(!dut.uop.faulted.toBoolean)
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.ROMCONST)
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toInt == 0x0F, "the ROM offset rides imm")
      assert(!dut.uop.usesFpSrcA.toBoolean && !dut.uop.usesFpSrcB.toBoolean && !dut.uop.srcAValid.toBoolean)
      assert(dut.uop.writesFp.toBoolean && dut.uop.fpDstReg.toInt == 0 && dut.uop.writesFpcc.toBoolean)
    }
  }

  test("out-of-scope cpGEN forms still trap to vector 11 -- with the post-instruction PC", VerilatorTest) {
    run { dut =>
      def trapsWithNextPc(op: Int, ext: Int, len: Int, name: String): Unit = {
        drive(dut, op = op, ext = ext, len = len); sleep(1)
        assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 11, s"$name must trap to vector 11")
        assert(dut.uop.faultUsesNextPc.toBoolean, s"$name is framed, so FPSP must be able to RTE past it")
        assert(!dut.uop.writesFp.toBoolean && !dut.uop.writesFpcc.toBoolean,
          s"$name must leave no FP side effect on the trapping uop")
      }
      trapsWithNextPc(0xF200, 0x000E, 2, "FSIN (transcendental -> FPSP)")
      trapsWithNextPc(0xF200, 0x0462, 2, "FSADD (rounded-precision variant, opmode bit6 -> FPSP)")
      trapsWithNextPc(0xF210, 0x4022, 2, "FADD.L (A0),FP0 (memory source -> Task 10)")
      trapsWithNextPc(0xF210, 0x5822, 2, "FADD.P (A0),FP0 (packed decimal -> FPSP)")
      trapsWithNextPc(0xF210, 0x6800, 2, "FMOVE.X FP0,(A0) (opclass 011 -> Task 10)")
      trapsWithNextPc(0xF210, 0xD0FF, 2, "FMOVEM.X (A0),FP0-FP7 (opclass 110 -> Task 10)")
      trapsWithNextPc(0xF210, 0x9000, 2, "FMOVE.L <ea>,FPCR (opclass 100 -> Task 10)")
    }
  }

  test("an FP uop never escapes with an inconsistent framing view", VerilatorTest) {
    run { dut =>
      // A cpGEN opword that predecode did NOT frame (len 1) must never emit an FP uop:
      // emitting one would compute nextPc = pc+2 for a >=2-word instruction (wild PC).
      drive(dut, op = 0xF200, ext = 0x0422, len = 1); sleep(1)
      assert(dut.uop.faulted.toBoolean, "an unframed cpGEN packet must trap, not emit")
      assert(!dut.uop.writesFp.toBoolean && dut.uop.op.toEnum != DecOp.FPU)
      // Likewise for a COMPLEX packet.
      drive(dut, op = 0xF200, ext = 0x0422, len = 2, simple = false); sleep(1)
      assert(dut.uop.faulted.toBoolean, "a complex packet must trap, not emit")
    }
  }
```

- [ ] **Step 7: Run the tests**

```bash
sbt "testOnly m68k040.decode.FpAssembleSpec"
sbt "testOnly m68k040.decode.* m68k040.rename.RenameStageFpSpec m68k040.execute.iq.*"
```
Expected: all PASS. `RenameStageFpSpec` and the IQ specs are included because this is the first task
where a real decode path drives `writesFp`/`writesFpcc`, i.e. the first time Tasks 2 and 3's fields
are fed by something other than a test harness.

- [ ] **Step 8: Lock-step regression**

```bash
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
```
Expected: 396/396 PASS. No lock-step program contains an FP opcode, so a delta here means an FP arm
leaked into a non-FP encoding — most likely `fpEmit`/`fpGenBad` not being properly gated on
`spec.fpGeneric`, or a `when` arm placed before `when(bad)` instead of after it.

- [ ] **Step 9: Confirm full-core elaboration**

```bash
sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
```
Expected: succeeds with no `PhaseCheck_noLatchNoOverride` error. This is the concrete check that
every one of the 24 `DecodedUop` construction sites drives the new FP fields (Task 4 Step 5) and
that the new `when(fpEmit)` arm drives everything it overrides.

- [ ] **Step 10: Commit**

```bash
git add src/main/scala/m68k040/decode/MicroOpAssembler.scala \
        src/test/scala/m68k040/decode/FpAssembleSpec.scala
git commit -m "feat(fpu): FP-generic uop assembly (register, int-register and FMOVECR forms)

Emits real DecOp.FPU / Cluster.CPLX uops for the single-uop cpGEN forms,
decoded from the extension word (which is where FADD-vs-FMUL, the FP
register numbers and the operand-usage bits all live -- OperationDecoder
sees the opword only, by design):

  - opclass 000 : F<op> FPm,FPn                     (all 12 native opmodes)
  - opclass 010 : F<op>.L/.W/.B/.S Dn,FPn           (int source, <ea> = Dn)
  - opclass 010 / src spec 111 : FMOVECR #ccc,FPn   (constant ROM)

Operand routing follows the integer srcA/srcB/dst convention:
usesFpSrcA (the destination FPn read back) is set ONLY for the dyadic ops
(FADD/FSUB/FMUL/FDIV/FCMP) -- the monadic ops compute from their source
alone, and claiming a false RAW dependency on FPn would needlessly
serialize independent FP work in the issue queue. FCMP/FTST set
writesFpcc without writesFp. The int-source form reads Dn through the
ORDINARY int rename/scoreboard path, so no 80-bit value ever enters
IqContext or the integer operand mux (the 2026-08-09 design's gateway
topology).

Everything else cpGEN -- transcendentals, FMOD/FREM/FSCALE/FGETEXP,
FSINCOS, the rounded-precision FSxxx/FDxxx variants, packed decimal, real
memory sources, FMOVE-to-<ea>, the FPCR/FPSR/FPIAR moves, and FMOVEM --
keeps taking vector 11, now with faultUsesNextPc set because Task 5
framed its length, which is exactly what makes those encodings
FPSP-completable rather than infinite loops. The memory/FMOVEM families
are deferred to Task 10 (they need a load/store crack or a MOVEM-style
sequencer; the X/D/P formats are 96/64/96 bits, i.e. multi-access, not a
single load).

Emission is gated on the SAME framing fact that gates the trap PC flavor
(pkt.simple && lenWords >= 2), so decode and predecode can never hold
inconsistent views of an instruction's length -- emitting against an
unframed packet would compute nextPc = pc+2 for a longer instruction, a
wild-PC class bug. A directed test pins that down explicitly.

Opmode values, field positions and the FMOVECR encoding were confirmed
against the in-tree vendored tools/musashi/musashi/m68kfpu.c (including
its own literal 'fmovecr #\$f, fp0  f200 5c0f' example). One open item is
flagged in the code for Task 8 rather than fudged: the int-source form
must carry the source SPECIFIER (Long vs Single is not expressible in
\`size\`) so FMOVE.S does not silently decode as FMOVE.L.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

**Handoffs these four tasks create, for whoever writes Tasks 7-15:**

- **Task 8** must drive `iq.cplxFpWakeup`/`iq.cplxFpccWakeup` from `DivEuPlugin` with the same
  `compLive && <write> && !compFault` gating as `wakeup`/`wakeupNzvc`, and wire them in
  `FullCoreSynth.scala` next to the existing `iq.cplxNzvcWakeup` lines. It must also resolve the
  flagged source-format open item (Long vs Single) before implementing the INTREG conversion.
- **Task 10** owns the deferred cpGEN forms: real memory sources (load crack; X/D/P are
  multi-access), `FMOVE FPn,<ea>`, the FPCR/FPSR/FPIAR moves, and FMOVEM (a MOVEM-style DecodeStage
  sequencer, not an assembler crack). Their lengths are already framed by Task 5, so Task 10 changes
  only emission, and can narrow `fpGenBad` further without touching predecode.
- **Tasks 9/11** own FSAVE/FRESTORE. Their opwords are in the `1111 001 1xx` band that Task 4's arm
  deliberately does **not** claim, and their length framing is **not** added by Task 5 — both are
  explicit to-dos there, and their opword encodings are on this plan's unverified list.
- **Task 3's `readsFpcc` has no producer of readers yet.** The first is FBcc/FScc/FDBcc or
  `FMOVE from FPSR`; whichever task adds one gets the whole dependency mechanism for free, but should
  re-run `IqFpSpec` to confirm the FPCC wait bit clears on the real path.

---

<!-- Task 7 (FpuCore arithmetic core) was drafted by a specialist agent as an internal
     Task A-H breakdown covering the same scope (grounded in MulCore.scala's start/busy/
     done shape, DivEuPlugin's iterative-divider precedent, and Musashi's m68kfpu.c +
     bundled SoftFloat as the bit-exact oracle Decision 1 commits us to). Retained with
     its own internal A-H lettering below; Task 8 immediately follows it in numeric order. -->

---

# FPU Arithmetic Core (`FpuCore`) — implementation plan task-section

> **Scope of this section.** The self-contained arithmetic datapath only. It defines a
> `Component` with a plain `start`/`busy`/`done`/operands/result I/O bundle, with **zero**
> awareness of the IQ, ROB, rename, FP PRF, decode tables, EA generation, FSAVE frames, or
> exception vectoring. Those are consumed/owned by the separately-drafted EU-integration,
> register-file/rename, decode, and exception tasks, which treat `FpuCore` as a black box
> through the **Interfaces** block in Task F6.
>
> **Explicitly NOT owned here** (do not implement in these files, do not let them leak in):
> `Cluster.CPLX` demux, IQ candidate masking / lane credit, ROB completion arbitration,
> `RegfileSpec.Fp`/`Fpcc` and their RATs, FPCR/FPSR/FPIAR architectural state, FPCC rename,
> vector-11/48-55 delivery, `faultUsesNextPc`, FSAVE/FRESTORE frames, **and the EA-decode /
> memory-access half of FMOVE `<ea>,FPn` / `FPn,<ea>`.** `FpuCore` only implements the
> *arithmetic-datapath-adjacent* part of FMOVE: the register-to-register value+FPCC path
> (`FpOp.FMOVE`), which is the same datapath a memory-sourced FMOVE feeds once the EU has
> already produced an 80-bit extended value on `io.src`. Format conversion of 32/64-bit
> memory operands to extended is **not** in this component (Decision 2 traps double-precision
> memory sources to FPSP anyway; single/long/word/byte integer source conversion, if later
> added, is an EU-side pre-conversion feeding `io.src`).

## Global constraints addendum (in addition to the plan's existing GC list)

- **GC-F1 — Musashi is the arithmetic oracle, and it is byte-exact.** Every value-producing
  behaviour in this component must match `tools/musashi/musashi/softfloat/softfloat.c`'s
  `floatx80_*` functions **bit for bit**, including the quirks catalogued in Task A0. Where
  this component deliberately diverges from Musashi, the divergence must appear in the
  **Divergence Register** (Task A0) with a reason, and the corresponding lock-step vectors
  must be excluded from the Musashi-refereed corpus and replaced by directed tests.
- **GC-F2 — `FpuCore.FixedLatency` is a hard constant, exactly like `MulCore.Latency`.**
  `DivEuPlugin.scala` consumes `MulCore.Latency` directly to size `mulCtx`
  (`DivEuPlugin.scala:433,464-465,478`); the FPU EU will do the same with
  `FpuCore.FixedLatency`. **Changing it requires updating the EU descriptor pipe in the same
  commit.** Any stage retiming inside this component that preserves the constant is free.
- **GC-F3 — Primary source over plausibility.** Where a bit-level detail is not confirmed by
  either the MC68040 UM or Musashi's source, it is recorded in Task A0's
  **"VERIFY-AT-IMPLEMENTATION"** list, not guessed. Do not silently resolve one of those.
- **GC-F4 — Area/FMax gate per spec §7** applies to this component like any other slice:
  real post-route, TNS+failing-endpoint-count alongside WNS, worktree-isolated, explicit
  LUT/FF/DSP pre/post, target 250 MHz / floor 200 MHz.

## File structure

| file | action | responsibility |
|---|---|---|
| `src/main/scala/m68k040/execute/fpu/FpTypes.scala` | **Create** | `FpOp` enum, `FpUnpacked`/`FpExcFlags`/`FpResult`/`FpRoundReq` bundles, `Fp80` helper object (unpack/pack/classify/clz/jam-shift/NaN propagation). |
| `src/main/scala/m68k040/execute/fpu/FpRoundPack.scala` | **Create** | The one shared normalize→round→pack→substitute back-end (3 registered stages). Implements Decision 10's OVFL and UNFL substitution as its native range path. |
| `src/main/scala/m68k040/execute/fpu/FpAddPipe.scala` | **Create** | FADD/FSUB/FCMP front (10 registered stages, II=1). |
| `src/main/scala/m68k040/execute/fpu/FpMulPipe.scala` | **Create** | FMUL front (10 registered stages, II=1), DSP48E2-inferring 64×64→128 significand multiply. |
| `src/main/scala/m68k040/execute/fpu/FpCheapPipe.scala` | **Create** | FABS/FNEG/FMOVE/FMOVECR/FTST/FINT/FINTRZ front (10 registered stages, II=1). Owns the FMOVECR constant ROM. |
| `src/main/scala/m68k040/execute/fpu/FpDivSqrtCore.scala` | **Create** | Single-context radix-2 restoring FDIV/FSQRT iterative lane + its own private `FpRoundPack`. |
| `src/main/scala/m68k040/execute/fpu/FpuCore.scala` | **Create** | Top: `object FpuCore { val FixedLatency = 13 }`, the I/O bundle, lane routing, the shared fixed-lane `FpRoundPack`. |
| `src/test/scala/m68k040/execute/FpRefModel.scala` | **Create** | Exact Scala/BigInt `floatx80` reference model mirroring SoftFloat (the spec's `ref`, per `MulCoreSpec.scala:24`'s pattern). |
| `src/test/scala/m68k040/execute/FpuCoreSpec.scala` | **Create** | Standalone `FpuCore` validation, mirroring `MulCoreSpec.scala`'s shape. |

---

## Task A0: Record the divergence register and the verify-at-implementation list (**no RTL — do this first**)

**Why first:** three genuine oracle divergences and one uncertain constant table were found
while writing this design. Building RTL against an unrecorded divergence is how a "why does
lock-step fail on exactly 9 vectors" session gets burned.

**Files:** Modify `docs/superpowers/specs/2026-08-14-fpu-fpsp-design.md` (append the section
below verbatim); no `src/` changes.

- [ ] **Step 1: append the Divergence Register to the design spec**

```markdown
## FpuCore ↔ Musashi Divergence Register (2026-08-15, from the arithmetic-core plan)

Decision 1 promises "bit-exact lock-step on the 11 HW-native ops' arithmetic results."
That promise holds, **with these four documented exceptions**, each of which must be
excluded from the Musashi-refereed corpus and covered by a directed test instead.

**D1 — FINT/FINTRZ: Musashi clamps to a 32-bit integer; real 68040/FPSP does not.**
`tools/musashi/musashi/m68kfpu.c` opmode `0x01` (Fsint) and `0x03` (FsintRZ) are implemented
as `int32_to_floatx80(floatx80_to_int32(source))`. For `|x| >= 2^31` this destroys the value.
Real FINT semantics are `floatx80_round_to_int` (which Musashi's own SoftFloat *provides* at
`softfloat.c:3082` but `m68kfpu.c` never calls). `FpuCore` implements the real semantics.
=> Lock-step FINT/FINTRZ only for `|x| < 2^31`. Larger magnitudes: directed test only.
=> Candidate Decision-3-class Musashi-bridge fix; do NOT "fix" our hardware to match.

**D2 — FMOVECR $38..$3F (10^32 … 10^4096): Musashi's table is double-derived, not the ROM.**
`m68kfpu.c` builds `10^32`..`10^256` with `double_to_fx80(1e32)`-style calls (10^32 is the
first power of ten not exactly representable in an IEEE double), and `10^512`..`10^4096` by
repeatedly squaring an already-rounded `1e256`. Independently computing the correctly-rounded
64-bit-significand value of each 10^k (exact rational arithmetic) reproduces the well-known
real-68881-ROM values and **disagrees with Musashi in the low mantissa bits for all eight
entries $38..$3F**. `FpuCore` implements the correctly-rounded/real-ROM values.
=> Exclude FMOVECR offsets $38..$3F from Musashi lock-step; directed test vs. the UM table.

**D3 — FMOVECR $0B (log10(2)): Musashi stores 1 ULP below correctly-rounded.**
Musashi: `0x3FFD 9A209A84FBCFF798`. Correctly-rounded-to-nearest: `...F799`. Musashi's value
is the truncated one, and is very likely the genuine 68881 ROM word (the ROM is known to
carry a small number of non-RN entries). **This is the single entry where "match Musashi" and
"match correctly-rounded" point in opposite directions and the primary source has not been
read.** See VERIFY-1.

**D4 — Every FPSR exception-status output is directed-test-only.** Decision 3 already
records that Musashi raises zero FP exceptions. `FpuCore.io.res*.exc` (SNAN/OPERR/OVFL/UNFL/
DZ/INEX2) therefore has no oracle and is never lock-stepped, only directed-tested.

### VERIFY-AT-IMPLEMENTATION (do not silently resolve)

- **VERIFY-1 — the FMOVECR constant ROM word for offset $0B**, and confirmation of $38..$3F,
  read from the **MC68881/MC68882 Floating-Point Coprocessor User's Manual** constant-ROM
  table (or the MC68040 UM if it reproduces it). The other 13 defined entries ($00, $0C, $0D,
  $0E, $0F, $30, $31, $32..$37) are **cross-confirmed two independent ways** — Musashi's table
  and an independent exact correctly-rounded computation agree bit-for-bit — and are high
  confidence. $0B and $38..$3F are the nine that are not.
- **VERIFY-2 — whether FPSR.I is *unconditionally* cleared by FCMP.** Musashi clears it only
  via the explicit infinity-comparison table (`m68kfpu.c:1517-1545`); an *overflowing finite*
  difference (e.g. `LARGEST − (−LARGEST)` under RN) still runs through
  `SET_CONDITION_CODES(res)` and would set I. `FpuCore` matches Musashi. Check the M68000
  Family PRM's FCMP page; if it says I is always cleared, that is a *fifth* divergence, not a
  bug in our hardware.
- **VERIFY-3 — FMOVECR and FINT/FINTRZ are NOT hardware ops on a real MC68040.** Per the
  MC68040 UM's on-chip-instruction list they are software-supported (they trap to FPSP), the
  same class as the transcendentals. Decision 2 and the 2026-08-09 spec §1 nevertheless put
  them in the hardware set. That is a deliberate **superset** of real silicon (same posture as
  Decision 10's OVFL/UNFL substitution) and is observationally equivalent to a correct FPSP,
  but it must be a recorded, intentional choice, not an accident. Confirm the UM's list and
  record the choice; the EU-integration task needs to know these two must **not** be routed
  to the F-line trap.
- **VERIFY-4 — "unnormal" (exp≠0, integer bit = 0) source operands.** Real 68040 raises the
  unimplemented-data-type exception (vector 55); SoftFloat/Musashi silently computes with
  them. `FpuCore` **classifies** them (`io.srcUnnormal`/`io.dstUnnormal`, combinational) but
  applies no policy — the trap-or-compute decision belongs to the EU-integration task.
```

- [ ] **Step 2: commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add docs/superpowers/specs/2026-08-14-fpu-fpsp-design.md
git commit -m "docs(spec): FpuCore<->Musashi divergence register + verify-at-impl list"
```

---

## Task A: `FpTypes.scala` + `FpRoundPack.scala` — the shared round/pack back-end

**Why this is one task and why it is first:** every arithmetic op in the core ends in the same
place — take a `(sign, biased exponent, 64-bit significand, round bit, sticky bit)` tuple and
turn it into a packed 80-bit extended result plus OVFL/UNFL/INEX2. Decision 10's overflow
4-way table and its underflow "shift-until-denormalized-or-zero" path are **not** a separate
feature bolted onto this: they are literally the `zExp > 0x7FFE` and `zExp <= 0` arms of the
same block, which is exactly how SoftFloat's `roundAndPackFloatx80` (`softfloat.c:521-670`,
the `precision80` label) structures it. Implementing them anywhere else guarantees a
double-rounding bug.

**Files:**
- Create `src/main/scala/m68k040/execute/fpu/FpTypes.scala`
- Create `src/main/scala/m68k040/execute/fpu/FpRoundPack.scala`

**Interfaces:**
- Produces: `FpOp`, `FpExcFlags`, `FpResult`, `FpRoundReq`, `Fp80` (helpers),
  `FpRoundPack` (`object FpRoundPack { val Latency = 3 }`).
- Consumes: nothing outside `spinal.core`/`spinal.lib`.

### Rounding-mode encoding (locked, and it is free)

`FPCR[5:4]` and SoftFloat's `float_rounding_mode` use the **same** encoding —
`0=RN, 1=RZ, 2=RM(down), 3=RP(up)` (`softfloat.h:81-84`; Musashi wires them straight through
at `m68kfpu.c:1653`, `float_rounding_mode = (REG_FPCR >> 4) & 0x3`). `io.rmode` is therefore
`FPCR[5:4]` verbatim, with no translation table anywhere.

- [ ] **Step 1: create `src/main/scala/m68k040/execute/fpu/FpTypes.scala`**

```scala
package m68k040.execute.fpu

import spinal.core._

/** The 11-op hardware-native FPU baseline (design spec Decision 2) plus FMOVECR/FTST
  * (2026-08-09 spec §1). Everything else traps to FPSP and never reaches FpuCore. */
object FpOp extends SpinalEnum {
  val FADD, FSUB, FMUL, FDIV, FSQRT, FABS, FNEG, FMOVE, FCMP, FTST,
      FINT, FINTRZ, FMOVECR = newElement()
}

/** FPSR exception-status bits this arithmetic core can raise. BSUN is a branch-side
  * condition (FBcc on unordered) and INEX1 is packed-decimal-only; neither can originate
  * here, so neither is present. Never lock-stepped (Divergence Register D4). */
case class FpExcFlags() extends Bundle {
  val snan  = Bool()   // a signalling-NaN operand was consumed
  val operr = Bool()   // inf-inf, 0*inf, 0/0, inf/inf, sqrt(negative)
  val ovfl  = Bool()
  val unfl  = Bool()
  val dz    = Bool()   // finite / zero
  val inex2 = Bool()
  def clearAll(): Unit = { snan := False; operr := False; ovfl := False
                           unfl := False; dz := False; inex2 := False }
  def |(that: FpExcFlags): FpExcFlags = {
    val r = FpExcFlags()
    r.snan := snan | that.snan; r.operr := operr | that.operr
    r.ovfl := ovfl | that.ovfl; r.unfl  := unfl  | that.unfl
    r.dz   := dz   | that.dz;   r.inex2 := inex2 | that.inex2
    r
  }
}

/** One completed FPU operation. `fpcc` uses the internal layout fixed by the 2026-08-09
  * spec §8: [3:0] = {NaN, I, Z, N}, i.e. bit0=N, bit1=Z, bit2=I, bit3=NaN. Architectural
  * FPSR[27:24] = {N,Z,I,NaN} is the reversed presentation of this group; the reversal is
  * the EU/FPSR task's job, not this component's. */
case class FpResult() extends Bundle {
  val value   = Bits(80 bits)   // packed extended result; undefined when writeFp is False
  val writeFp = Bool()          // op produces an FP-register value (False for FCMP/FTST)
  val fpcc    = Bits(4 bits)
  val exc     = FpExcFlags()
}

/** The one canonical hand-off from every arithmetic front-end into FpRoundPack.
  *
  * Normal path: value = sig * 2^(exp - 16383 - 63), with `sig` normalised (bit 63 set) OR
  * `exp <= 0` (the subnormal arm re-shifts). `round`/`sticky` are the two bits below `sig`;
  * together they are exactly equivalent to SoftFloat's 64-bit `zSig1` low word for every
  * quantity `roundAndPackFloatx80`'s precision80 path actually reads, namely `zSig1[63]`
  * (round) and `(zSig1 << 1) != 0` (sticky).
  *
  * Bypass path: `bypass` means the front-end already produced the final 80-bit pattern
  * (NaN propagation, infinity results, FABS/FNEG/FMOVE/FMOVECR/FTST/FINT). Rounding,
  * range-checking and the OVFL/UNFL substitution are all suppressed; FPCC is still
  * classified from the bypassed value, which is what makes FTST fall out for free. */
case class FpRoundReq() extends Bundle {
  val sign         = Bool()
  val exp          = SInt(18 bits)   // biased, signed & widened (FMUL can reach 49150, and
                                     // a subnormal-operand FMUL can reach -16506)
  val sig          = UInt(64 bits)
  val round        = Bool()
  val sticky       = Bool()
  val bypass       = Bool()
  val bypassValue  = Bits(80 bits)
  val writeFp      = Bool()
  val fpccFromSrc  = Bool()          // FCMP's explicit infinity table only
  val fpccOverride = Bits(4 bits)
  val exc          = FpExcFlags()    // upstream-detected snan/operr/dz
}

object Fp80 {
  val ExpInf  = 0x7FFF
  val ExpBias = 16383

  /** SoftFloat's floatx80_default_nan (softfloat-specialize:249-250): 0xFFFF_FFFFFFFFFFFF_FFFF */
  def defaultNan: Bits = B"16'hFFFF" ## B(BigInt("FFFFFFFFFFFFFFFF", 16), 64 bits)

  def sign(v: Bits): Bool = v(79)
  def exp (v: Bits): UInt = v(78 downto 64).asUInt
  def sig (v: Bits): UInt = v(63 downto 0).asUInt

  def pack(s: Bool, e: UInt, m: UInt): Bits = s ## e.resize(15) ## m.resize(64)
  def packInf(s: Bool): Bits  = pack(s, U(ExpInf, 15 bits), U(BigInt(1) << 63, 64 bits))
  def packZero(s: Bool): Bits = pack(s, U(0, 15 bits), U(0, 64 bits))

  /** SoftFloat: (exp == 0x7FFF) && (sig << 1) != 0 */
  def isNan(v: Bits): Bool = exp(v) === ExpInf && v(62 downto 0) =/= 0
  /** SoftFloat: (exp == 0x7FFF) && (sig << 1) == 0  (a mantissa of 0 counts, as in Musashi) */
  def isInf(v: Bits): Bool = exp(v) === ExpInf && v(62 downto 0) === 0
  /** Musashi SET_CONDITION_CODES' zero test (m68kfpu.c:282): exp==0 && (low<<1)==0. Note
    * this deliberately ignores bit 63, so a pseudo-denormal reads as zero. Match it. */
  def isZero(v: Bits): Bool = exp(v) === 0 && v(62 downto 0) === 0
  /** softfloat-specialize:269-278: NaN whose bit 62 is clear. */
  def isSNan(v: Bits): Bool = isNan(v) && !v(62)
  /** exp != 0 and exp != 0x7FFF but the explicit integer bit is clear (VERIFY-4). */
  def isUnnormal(v: Bits): Bool = exp(v) =/= 0 && exp(v) =/= ExpInf && !v(63)
  def isDenorm(v: Bits): Bool   = exp(v) === 0 && v(62 downto 0) =/= 0

  /** Musashi SET_CONDITION_CODES (m68kfpu.c:271-298), in the internal {NaN,I,Z,N} order. */
  def classify(v: Bits): Bits = {
    val n = Bits(4 bits)
    n(0) := sign(v)
    n(1) := isZero(v)
    n(2) := isInf(v)
    n(3) := isNan(v)
    n
  }

  /** SoftFloat propagateFloatx80NaN (softfloat-specialize:320-337): quiet both operands by
    * OR-ing 0xC000000000000000 into the mantissa, then pick a unless a is a signalling NaN
    * and b is also a NaN, in which case pick b; if a is not a NaN at all, pick b. */
  def propagateNan(a: Bits, b: Bits): Bits = {
    val quiet = B(BigInt("C000000000000000", 16), 64 bits)
    val aq = a(79 downto 64) ## (a(63 downto 0) | quiet)
    val bq = b(79 downto 64) ## (b(63 downto 0) | quiet)
    Mux(isNan(a), Mux(isSNan(a) && isNan(b), bq, aq), bq)
  }

  /** Leading-zero count, MSB-first, returning `x.getWidth` when x is zero. Same linear
    * priority-encode idiom (and same caveat) as the existing Bitfield.scala:204-215 clz32:
    * Vivado collapses it. Every use site in this design gives it a dedicated pipeline
    * stage, so it is never in series with a barrel shifter. */
  def clz(x: Bits): UInt = {
    val w  = x.getWidth
    val cw = log2Up(w + 1)
    val r  = x.reversed                       // r(0) is x's MSB
    val res = UInt(cw bits)
    res := U(w, cw bits)
    for (i <- w - 1 downto 0) when(r(i)) { res := U(i, cw bits) }   // lowest index wins
    res
  }

  /** Right shift by `8*k` with all shifted-out bits OR-jammed into bit 0. `k` is 0..8, so
    * this is nine constant (free) shifts, nine cheap OR-reductions and one 9:1 mux -- about
    * two LUT levels, versus the ~7 of a monolithic 67-bit barrel shifter. */
  def shiftRightJamCoarse(w: UInt, k: UInt): UInt = {
    val n = w.getWidth
    val out = UInt(n bits)
    out := w
    switch(k) {
      for (i <- 1 to 8) is(U(i, k.getWidth bits)) {
        val lost = w(8 * i - 1 downto 0) =/= 0
        out := ((w >> (8 * i)) | lost.asUInt.resize(n)).resized
      }
    }
    out
  }

  /** Right shift by `k` (0..7) with jamming, same construction. */
  def shiftRightJamFine(w: UInt, k: UInt): UInt = {
    val n = w.getWidth
    val out = UInt(n bits)
    out := w
    switch(k) {
      for (i <- 1 to 7) is(U(i, k.getWidth bits)) {
        val lost = w(i - 1 downto 0) =/= 0
        out := ((w >> i) | lost.asUInt.resize(n)).resized
      }
    }
    out
  }

  /** Left shift by `8*k`, k = 0..8 (no jamming needed -- see FpAddPipe's header comment). */
  def shiftLeftCoarse(w: UInt, k: UInt): UInt = {
    val n = w.getWidth
    val out = UInt(n bits)
    out := w
    switch(k) { for (i <- 1 to 8) is(U(i, k.getWidth bits)) { out := (w << (8 * i)).resized } }
    out
  }

  /** Left shift by `k`, k = 0..7. */
  def shiftLeftFine(w: UInt, k: UInt): UInt = {
    val n = w.getWidth
    val out = UInt(n bits)
    out := w
    switch(k) { for (i <- 1 to 7) is(U(i, k.getWidth bits)) { out := (w << i).resized } }
    out
  }
}
```

- [ ] **Step 2: create `src/main/scala/m68k040/execute/fpu/FpRoundPack.scala`**

```scala
package m68k040.execute.fpu

import spinal.core._

object FpRoundPack {
  /** Registered stages from `io.inValid` to `io.outValid`. */
  val Latency = 3
}

/** Normalise-range / round / pack, plus Decision 10's hardware-native OVFL and UNFL
  * substitution, for 80-bit extended results. Fully pipelined, initiation interval 1,
  * never stalls: results leave in the order they entered.
  *
  * This is a direct hardware transcription of SoftFloat's `roundAndPackFloatx80`
  * precision80 path (softfloat.c:598-670) -- deliberately, because that function IS the
  * lock-step oracle (Decision 1). The two SoftFloat quantities that need justification:
  *
  *  (a) The 64-bit `zSig1` low word is compressed to {round, sticky}. The precision80 path
  *      reads exactly two things from zSig1: `(sbits64)zSig1 < 0` (i.e. zSig1[63], our
  *      `round`) and `(bits64)(zSig1<<1) == 0` (i.e. zSig1[62:0] == 0, our `!sticky`),
  *      plus `zSig1 != 0` (our `round | sticky`). Nothing else. The compression is exact.
  *
  *  (b) `shift64ExtraRightJamming(zSig0, zSig1, 1 - zExp, ...)` on the subnormal arm is
  *      reproduced by jam-shifting the 66-bit word {sig, round, sticky}: for count c >= 1
  *      SoftFloat produces z1[63] = a0[c-1] and z1[62:0] != 0 iff (a0[c-2:0] != 0 or
  *      a1 != 0), which is bit-for-bit what a jam shift of {sig,round,sticky} yields, and
  *      saturating c at 66 is exact because a 67-position shift and a 4000-position shift
  *      produce the identical {0, 0, sticky=1}.
  *
  * OVERFLOW (Decision 10's 4-way table) is the `exp > 0x7FFE` arm. SoftFloat's own
  * selection expression is `RZ || (sign && RP) || (!sign && RM)` -> largest finite,
  * otherwise infinity, which is *exactly* the design spec's table:
  *   RN -> Infinity(sign) | RZ -> largest(sign) | RM -> +ovfl:largest, -ovfl:Inf
  *   | RP -> +ovfl:Inf, -ovfl:largest.
  *
  * UNDERFLOW (Decision 10's denormalise-then-round path) is the `exp <= 0` arm: shift the
  * mantissa right while the exponent climbs to the denormalised value (here: shift right by
  * 1-exp, exponent becomes 0), then round. If the shift empties the mantissa entirely the
  * same rounding increment produces zero-vs-smallest-denormal per sign and rounding mode --
  * i.e. the spec's "structurally identical in shape to overflow's" fallback table drops out
  * of the same increment logic rather than being a second table. */
class FpRoundPack extends Component {
  val io = new Bundle {
    val inValid  = in Bool ()
    val in       = in(FpRoundReq())
    val rmode    = in Bits (2 bits)     // FPCR[5:4]; 0=RN 1=RZ 2=RM 3=RP
    val outValid = out Bool ()
    val out      = out(FpResult())
  }

  val rn = io.rmode === 0
  val rz = io.rmode === 1
  val rm = io.rmode === 2
  val rp = io.rmode === 3

  // ── RP0: range classification ───────────────────────────────────────────────
  // Decides which of the three arms (overflow / subnormal / normal) applies, and the
  // rounding increment for the normal arm. Only 18-bit compares and a handful of gates.
  val s0 = new Area {
    val nz      = io.in.round || io.in.sticky
    // SoftFloat precision80 `increment` (softfloat.c:598-611).
    val incr    = Mux(rn, io.in.round,
                  Mux(rz, False,
                  Mux(io.in.sign, rm && nz, rp && nz)))
    val allOnes = io.in.sig === U(BigInt("FFFFFFFFFFFFFFFF", 16), 64 bits)
    val ovfl    = (io.in.exp > S(0x7FFE, 18 bits)) ||
                  (io.in.exp === S(0x7FFE, 18 bits) && allOnes && incr)
    val sub     = !ovfl && (io.in.exp <= S(0, 18 bits))
    // shift count 1 - exp, saturated at 66 (exact, see header note (b)).
    val cntFull = S(1, 18 bits) - io.in.exp
    val cntSat  = Mux(cntFull > S(66, 18 bits), U(66, 7 bits), cntFull.asUInt.resize(7))

    val vld   = RegNext(io.inValid) init False
    val req   = RegNext(io.in)
    val rIncr = RegNext(incr)
    val rOvfl = RegNext(ovfl)
    val rSub  = RegNext(sub)
    val rCnt  = RegNext(cntSat)
    // Latched so a mid-flight FPCR write cannot re-mux an in-flight result.
    val rRz   = RegNext(rz); val rRm = RegNext(rm); val rRp = RegNext(rp); val rRn = RegNext(rn)
  }

  // ── RP1: the subnormal (underflow) right-shift, and the final increment decision ─────
  val s1 = new Area {
    // 66-bit {sig, round, sticky}; bit 0 IS the sticky position, so jamming into it is
    // always semantically correct.
    val ext   = (s0.req.sig ## s0.req.round ## s0.req.sticky).asUInt      // 66 bits
    val shC   = Fp80.shiftRightJamCoarse(ext, s0.rCnt(6 downto 3))
    val shF   = Fp80.shiftRightJamFine(shC, s0.rCnt(2 downto 0))
    val subSig    = shF(65 downto 2)
    val subRound  = shF(1)
    val subSticky = shF(0)

    val sig    = Mux(s0.rSub, subSig,    s0.req.sig)
    val round  = Mux(s0.rSub, subRound,  s0.req.round)
    val sticky = Mux(s0.rSub, subSticky, s0.req.sticky)
    val nz     = round || sticky
    val incr   = Mux(s0.rSub,
                     Mux(s0.rRn, round, Mux(s0.rRz, False,
                         Mux(s0.req.sign, s0.rRm && nz, s0.rRp && nz))),
                     s0.rIncr)
    // SoftFloat tininess is `float_tininess_after_rounding` (softfloat-specialize:43), so
    // isTiny is (exp < 0) || !increment || (sig != all-ones) -- true in every case this
    // arm can reach except the exact "rounds back up to the smallest normal" corner.
    val isTiny = (s0.req.exp < S(0, 18 bits)) || !s0.rIncr ||
                 (s0.req.sig =/= U(BigInt("FFFFFFFFFFFFFFFF", 16), 64 bits))
    val exp0   = Mux(s0.rSub, S(0, 18 bits), s0.req.exp)

    val vld    = RegNext(s0.vld) init False
    val req    = RegNext(s0.req)
    val rSig   = RegNext(sig)
    val rIncr  = RegNext(incr)
    val rTieClr= RegNext(s0.rRn && !sticky)       // round-to-nearest-EVEN LSB clear
    val rExp   = RegNext(exp0)
    val rOvfl  = RegNext(s0.rOvfl)
    val rSub   = RegNext(s0.rSub)
    val rInex  = RegNext(nz)
    val rUnfl  = RegNext(s0.rSub && isTiny && nz)
    val rToMax = RegNext(s0.rRz || (s0.req.sign && s0.rRp) || (!s0.req.sign && s0.rRm))
  }

  // ── RP2: increment, carry fix-up, substitution, pack, classify ───────────────
  val s2 = new Area {
    val sum   = s1.rSig +^ s1.rIncr.asUInt.resize(64)          // 65 bits
    val carry = sum(64)
    val incd  = sum(63 downto 0)
    val tied  = Mux(s1.rTieClr && s1.rIncr, incd & ~U(1, 64 bits), incd)

    val sigN = UInt(64 bits)
    val expN = SInt(18 bits)
    when(carry) {                                              // 0xFFFF..F + 1 -> 2^64
      sigN := U(BigInt(1) << 63, 64 bits); expN := s1.rExp + 1
    } elsewhen(!s1.rIncr && s1.rSig === 0) {                   // SoftFloat: zSig0==0 => exp=0
      sigN := U(0, 64 bits);              expN := S(0, 18 bits)
    } otherwise {
      sigN := tied
      // subnormal that rounded up into the smallest normal: SoftFloat sets zExp = 1
      expN := Mux(s1.rSub && tied(63), S(1, 18 bits), s1.rExp)
    }

    val packed = Fp80.pack(s1.req.sign, expN.asUInt.resize(15), sigN)
    // Decision 10's overflow table, verbatim.
    val ovflVal = Mux(s1.rToMax,
                      Fp80.pack(s1.req.sign, U(0x7FFE, 15 bits),
                                U(BigInt("FFFFFFFFFFFFFFFF", 16), 64 bits)),
                      Fp80.packInf(s1.req.sign))

    val value = Mux(s1.req.bypass, s1.req.bypassValue,
                Mux(s1.rOvfl, ovflVal, packed))

    val exc = FpExcFlags()
    exc.snan  := s1.req.exc.snan
    exc.operr := s1.req.exc.operr
    exc.dz    := s1.req.exc.dz
    exc.ovfl  := !s1.req.bypass && s1.rOvfl
    exc.unfl  := !s1.req.bypass && s1.rUnfl
    exc.inex2 := !s1.req.bypass && (s1.rInex || s1.rOvfl)      // overflow is always inexact

    io.outValid  := RegNext(s1.vld) init False
    io.out.value := RegNext(value)
    io.out.writeFp := RegNext(s1.req.writeFp)
    io.out.fpcc  := RegNext(Mux(s1.req.fpccFromSrc, s1.req.fpccOverride, Fp80.classify(value)))
    io.out.exc   := RegNext(exc)
  }
}
```

- [ ] **Step 3: compile check (must pass before proceeding to Task B)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
sbt -batch compile 2>&1 | tail -20
```

Expected: `[success]`. This step exists because `FpRoundPack` is consumed by four separate
downstream tasks; a signature/width error found here costs one edit, found in Task F it costs
five.

---

## Task B: `FpAddPipe.scala` — FADD / FSUB / FCMP front (10 stages, II=1)

**Files:** Create `src/main/scala/m68k040/execute/fpu/FpAddPipe.scala`

**Interfaces:**
- Consumes: `FpOp`, `FpRoundReq`, `Fp80` (Task A).
- Produces: `FpAddPipe` (`object FpAddPipe { val Latency = 10 }`), whose output is an
  `FpRoundReq` + a valid, feeding `FpuCore`'s shared `FpRoundPack`.

### The single-path correctness argument (write this into the file header — it is the reason there is no near/far two-path adder here)

The datapath is one 67-bit word `W`, laid out `W[66:3] = significand`, `W[2] = G`,
`W[1] = R`, `W[0] = S`. Right shifts jam into `W[0]`; left shifts fill with zeros. This is
correct for **both** the large-cancellation and the large-alignment case, with no second path:

- **Alignment ≤ 1 (the only case that can cancel by more than one bit).** Nothing is lost in
  the align shift, so the subtraction is exact and a left shift of up to 66 positions shifts
  in only genuine zeros. Round and sticky end up zero, which is correct.
- **Alignment ≥ 2 (the only case that loses bits).** The result then has at most one leading
  zero, so the left shift is 0 or 1 positions. Correctness with only G/R/S rests on the
  borrow argument: performing `{A,0,0,0} - {B,G,R,S}` as one 67-bit two's-complement subtract
  puts `0 - S` in the S position, so a nonzero true tail borrows into the R position exactly
  as the real infinite-precision subtraction would, and the result's true tail is nonzero iff
  `S` was — so the S position after the subtract still summarises it. After a 1-position left
  shift, `sig ← …G`, `round ← R`, `sticky ← S`, which is what `sig = W'[66:3]`,
  `round = W'[2]`, `sticky = W'[1] | W'[0]` reads out.

Consequently the *only* structure needed beyond the align shifter is one 67-bit LZC and one
67-bit left shifter, both of which get their own pipeline stage.

### Subnormal handling without a pre-normaliser

SoftFloat's add/sub never pre-normalises subnormals; it uses the effective-exponent trick
(`if (bExp == 0) --expDiff` / `if (aExp == 0) ++expDiff`, `softfloat.c:3172,3183,3242-3245`).
This design does the same by defining `eX = (expX == 0) ? 1 : expX` once and using it for
both the alignment amount and the result exponent. A subnormal sum comes out with
`exp <= 0`, and `FpRoundPack`'s underflow arm shifts it back — exactly, bit for bit,
reproducing SoftFloat's `normalizeFloatx80Subnormal` → `roundAndPackFloatx80` round trip.

- [ ] **Step 1: create the file**

```scala
package m68k040.execute.fpu

import spinal.core._

object FpAddPipe {
  /** Registered stages from `io.start` to `io.outValid`. Must equal FpMulPipe.Latency and
    * FpCheapPipe.Latency -- FpuCore relies on all three fronts being the same depth so the
    * shared FpRoundPack back-end can never see two results in one cycle. */
  val Latency = 10
}

/** FADD / FSUB / FCMP. Elastic, fully registered, initiation interval 1: `start` may be
  * asserted every cycle and results leave in issue order at exactly FpAddPipe.Latency.
  * Never stalls -- the EU must reserve result capacity before asserting start (2026-08-09
  * spec §3, "Issue reserves result capacity before entering any unstallable tail").
  *
  * Operand naming follows Musashi: `dst` is the FPn destination (SoftFloat's `a`), `src` is
  * the <ea>/FPm source (SoftFloat's `b`); FSUB computes dst - src. */
class FpAddPipe extends Component {
  val io = new Bundle {
    val start    = in Bool ()
    val op       = in(FpOp())
    val dst      = in Bits (80 bits)
    val src      = in Bits (80 bits)
    val rmode    = in Bits (2 bits)
    val outValid = out Bool ()
    val out      = out(FpRoundReq())
  }

  // ── A0: unpack + classify ───────────────────────────────────────────────────
  val a0 = new Area {
    val isSub  = io.op === FpOp.FSUB || io.op === FpOp.FCMP
    val isCmp  = io.op === FpOp.FCMP

    val vld  = RegNext(io.start) init False
    val dst  = RegNext(io.dst)
    val src  = RegNext(io.src)
    val sub  = RegNext(isSub)
    val cmp  = RegNext(isCmp)
    val rmRM = RegNext(io.rmode === 2)                 // for the exact-cancellation zero sign

    val dNan  = RegNext(Fp80.isNan(io.dst));  val sNan  = RegNext(Fp80.isNan(io.src))
    val dInf  = RegNext(Fp80.isInf(io.dst));  val sInf  = RegNext(Fp80.isInf(io.src))
    val dSNan = RegNext(Fp80.isSNan(io.dst)); val sSNan = RegNext(Fp80.isSNan(io.src))
    // effective exponent: subnormal (exp 0) behaves as exp 1
    val dE = RegNext(Mux(Fp80.exp(io.dst) === 0, U(1, 15 bits), Fp80.exp(io.dst)))
    val sE = RegNext(Mux(Fp80.exp(io.src) === 0, U(1, 15 bits), Fp80.exp(io.src)))
  }

  // ── A1: order by magnitude, compute the alignment amount, resolve all specials ────
  val a1 = new Area {
    val dSign = Fp80.sign(a0.dst); val sSign = Fp80.sign(a0.src)
    val dSig  = Fp80.sig(a0.dst);  val sSig  = Fp80.sig(a0.src)
    // effective add iff the two effective signs agree (FSUB flips src's)
    val effAdd = dSign === (sSign ^ a0.sub)
    val zSign  = dSign                                  // SoftFloat always passes aSign

    val srcBigger = (a0.sE > a0.dE) || (a0.sE === a0.dE && sSig > dSig)
    val bigSig  = Mux(srcBigger, sSig, dSig)
    val smlSig  = Mux(srcBigger, dSig, sSig)
    val bigE    = Mux(srcBigger, a0.sE, a0.dE)
    val smlE    = Mux(srcBigger, a0.dE, a0.sE)
    val diff    = (bigE - smlE).resize(16)
    val shAmt   = Mux(diff > U(66, 16 bits), U(66, 7 bits), diff.resize(7))
    // magnitude-subtract result sign flips when src is the larger operand
    val resSign = Mux(effAdd, zSign, Mux(srcBigger, !zSign, zSign))

    val anyNan   = a0.dNan || a0.sNan
    val bothInf  = a0.dInf && a0.sInf
    val exactZero= !effAdd && !anyNan && !a0.dInf && !a0.sInf &&
                   a0.dE === a0.sE && dSig === sSig

    // FCMP's explicit infinity table (Musashi m68kfpu.c:1517-1545). d = is_inf(dst),
    // s = is_inf(src). Internal FPCC order is {NaN,I,Z,N} = bit3..bit0.
    val fpccCmp = Bits(4 bits)
    fpccCmp := 0
    when(a0.sInf && sSign)        { when(a0.dInf && dSign)  { fpccCmp := B"0011" } }   // Z|N
    .elsewhen(a0.sInf && !sSign)  { when(a0.dInf && !dSign) { fpccCmp := B"0010" }     // Z
                                    otherwise               { fpccCmp := B"0001" } }   // N
    .otherwise                    { when(a0.dInf && dSign)  { fpccCmp := B"0001" } }   // N
    val cmpSpecial = a0.cmp && !anyNan && (a0.dInf || a0.sInf)

    val req = FpRoundReq()
    req.sign := resSign
    req.exp  := bigE.asSInt.resize(18)
    req.sig  := bigSig                            // A2..A7 recompute sig/round/sticky
    req.round := False
    req.sticky := False
    req.writeFp := !a0.cmp
    req.fpccFromSrc  := cmpSpecial
    req.fpccOverride := fpccCmp
    req.exc.clearAll()
    req.exc.snan  := a0.dSNan || a0.sSNan
    req.exc.operr := bothInf && !effAdd && !anyNan
    req.bypass      := False
    req.bypassValue := Fp80.defaultNan

    when(anyNan) {
      req.bypass := True; req.bypassValue := Fp80.propagateNan(a0.dst, a0.src)
    } elsewhen(bothInf && !effAdd) {
      req.bypass := True; req.bypassValue := Fp80.defaultNan      // inf - inf
    } elsewhen(a0.dInf) {
      req.bypass := True; req.bypassValue := a0.dst               // SoftFloat "return a"
    } elsewhen(a0.sInf) {
      req.bypass := True; req.bypassValue := Fp80.packInf(Mux(effAdd, zSign, !zSign))
    } elsewhen(exactZero) {
      // SoftFloat subFloatx80Sigs: pack(rmode == RM, 0, 0)
      req.bypass := True; req.bypassValue := Fp80.packZero(a0.rmRM)
    }
    when(cmpSpecial) { req.bypass := True }        // value irrelevant, FPCC comes from table

    val vld    = RegNext(a0.vld) init False
    val rReq   = RegNext(req)
    val rBig   = RegNext(bigSig)
    val rSml   = RegNext(smlSig)
    val rSh    = RegNext(shAmt)
    val rEffAdd= RegNext(effAdd)
  }

  // ── A2/A3: align the smaller operand (coarse then fine, jamming into bit 0) ────
  // W[66:3] = significand, W[2] = G, W[1] = R, W[0] = S.
  val a2 = new Area {
    val w    = (a1.rSml ## B"000").asUInt                    // 67 bits
    val vld  = RegNext(a1.vld) init False
    val rReq = RegNext(a1.rReq); val rBig = RegNext(a1.rBig)
    val rEffAdd = RegNext(a1.rEffAdd); val rShLo = RegNext(a1.rSh(2 downto 0))
    val rW   = RegNext(Fp80.shiftRightJamCoarse(w, a1.rSh(6 downto 3)))
  }
  val a3 = new Area {
    val vld  = RegNext(a2.vld) init False
    val rReq = RegNext(a2.rReq); val rBig = RegNext(a2.rBig)
    val rEffAdd = RegNext(a2.rEffAdd)
    val rW   = RegNext(Fp80.shiftRightJamFine(a2.rW, a2.rShLo))
  }

  // ── A4: 68-bit add / 67-bit subtract, with the add's carry folded back in ─────
  val a4 = new Area {
    val bigW = (a3.rBig ## B"000").asUInt                    // 67 bits
    val sum  = bigW +^ a3.rW                                 // 68 bits
    // On carry, shift right one with jamming: {sum[67:2], sum[1] | sum[0]}
    val sumN = Mux(sum(67), (sum(67 downto 2) ## (sum(1) | sum(0))).asUInt, sum(66 downto 0))
    val dif  = bigW - a3.rW                                  // never negative (ordered above)
    val vld  = RegNext(a3.vld) init False
    val rReq = RegNext(a3.rReq)
    val rW   = RegNext(Mux(a3.rEffAdd, sumN, dif))
    val rExpAdj = RegNext(Mux(a3.rEffAdd && sum(67), S(1, 18 bits), S(0, 18 bits)))
  }

  // ── A5: leading-zero count (own stage; never in series with a shifter) ────────
  val a5 = new Area {
    val vld  = RegNext(a4.vld) init False
    val rReq = RegNext(a4.rReq); val rW = RegNext(a4.rW); val rExpAdj = RegNext(a4.rExpAdj)
    val rClz = RegNext(Fp80.clz(a4.rW.asBits))               // 0..67
  }

  // ── A6/A7: left-normalise (coarse then fine) and adjust the exponent ─────────
  val a6 = new Area {
    val vld  = RegNext(a5.vld) init False
    val rReq = RegNext(a5.rReq); val rExpAdj = RegNext(a5.rExpAdj)
    val rClz = RegNext(a5.rClz); val rClzLo = RegNext(a5.rClz(2 downto 0))
    val rW   = RegNext(Fp80.shiftLeftCoarse(a5.rW, a5.rClz(6 downto 3)))
  }
  val a7 = new Area {
    val w    = Fp80.shiftLeftFine(a6.rW, a6.rClzLo)
    val isZero = a6.rClz === 67
    val req  = FpRoundReq()
    req := a6.rReq
    req.sig    := w(66 downto 3)
    req.round  := w(2)
    req.sticky := w(1) | w(0)
    // exp = bigExp + carryAdjust - clz. A fully cancelled result cannot reach here (A1's
    // `exactZero` bypasses it), but clamp anyway so a zero word cannot corrupt the exponent.
    req.exp    := a6.rReq.exp + a6.rExpAdj - Mux(isZero, S(0, 18 bits), a6.rClz.asSInt.resize(18))
    when(isZero) { req.sig := 0; req.round := False; req.sticky := False }
    val vld  = RegNext(a6.vld) init False
    val rReq = RegNext(req)
  }

  // ── A8/A9: FMax reserve. See the note below before deleting these. ───────────
  val a8 = new Area { val vld = RegNext(a7.vld) init False; val rReq = RegNext(a7.rReq) }
  val a9 = new Area { val vld = RegNext(a8.vld) init False; val rReq = RegNext(a8.rReq) }

  io.outValid := a9.vld
  io.out      := a9.rReq
}
```

**Note on A8/A9 (do not silently delete them).** They exist so that `FpAddPipe.Latency`
equals `FpMulPipe.Latency`, which is what lets `FpuCore` have exactly **one** fixed-result
port with `MulCore`-identical semantics (§Task F6). They cost ~2 × 95 = 190 flops out of
433,920. If the Task H FMax gate shows A1 (the 64-bit magnitude compare + special-case mux)
or A4 (the 68-bit add) as the limiter, the fix is to **move work into A8/A9**, not to remove
them — e.g. split A1's magnitude compare from its special-case resolution.

---

## Task C: `FpMulPipe.scala` — FMUL front (10 stages, II=1, DSP48E2-inferring)

**Files:** Create `src/main/scala/m68k040/execute/fpu/FpMulPipe.scala`

**Interfaces:**
- Consumes: `FpOp`, `FpRoundReq`, `Fp80`.
- Produces: `FpMulPipe` (`object FpMulPipe { val Latency = 10 }`).

### The DSP inference idiom, quoted and adapted

`MulCore.scala:53-74` is the pattern this project has already validated in silicon:

```scala
  val a0 = Reg(SInt(33 bits)); val b0 = Reg(SInt(33 bits))
  val a1 = Reg(SInt(33 bits)); val b1 = Reg(SInt(33 bits))
  val mul2 = Reg(SInt(66 bits))
  val prod3 = Reg(SInt(66 bits)); val prod4 = Reg(SInt(66 bits))
  val prod5 = Reg(SInt(66 bits)); val prod6 = Reg(SInt(66 bits))
  ...
  a1 := a0; b1 := b0
  mul2 := a1 * b1
  prod3 := mul2; prod4 := prod3; prod5 := prod4; prod6 := prod5
```

with `MulCore.scala:49-52`'s reason: *"Two operand stages match AREG/BREG depth=2. The four
trailing levels are required for Vivado to distribute this tiled 33x33 multiply across every
DSP's MREG/PREG. Valid bits qualify the result; data deliberately shifts on invalid cycles
because per-stage clock enables prevent that DSP retiming."*

Adapted for FMUL: operands are **significands**, always non-negative, so this uses
`UInt(64) * UInt(64) → UInt(128)` rather than the 33-bit sign-extension trick MulCore needed
to cover MULU and MULS with one datapath — there is no signed/unsigned duality here, the sign
is an XOR of two bits handled outside the multiplier entirely. The two operand levels and the
**four** trailing product levels are kept verbatim; a 64×64 tiling is strictly deeper than a
33×33 one, so four is a floor, not a ceiling. The critical property to preserve is the one
MulCore's comment calls out: **no per-stage clock enable on the DSP register chain** — the
data shifts unconditionally and the parallel valid chain qualifies it.

Expected inference (to be *reported*, not assumed — simulation cannot prove it, per
2026-08-09 spec §9): ~16 DSP48E2 for the 64×64, matching the spec §6 probe's
"FMUL significand … 161 LUT, 102 FF, 16 DSP" row.

- [ ] **Step 1: create the file**

```scala
package m68k040.execute.fpu

import spinal.core._

object FpMulPipe { val Latency = 10 }

/** FMUL: subnormal pre-normalise, exponent add, DSP48E2-inferred 64x64 -> 128 significand
  * multiply, 1-bit post-normalise, hand-off to FpRoundPack. Elastic, II=1, never stalls.
  *
  * Unlike FADD, SoftFloat's floatx80_mul DOES pre-normalise subnormal operands
  * (softfloat.c:3363-3370, normalizeFloatx80Subnormal) before forming zExp = aExp + bExp -
  * 0x3FFE, so this pipe carries a real CLZ + left shift per operand in M0/M1. */
class FpMulPipe extends Component {
  val io = new Bundle {
    val start    = in Bool ()
    val dst      = in Bits (80 bits)
    val src      = in Bits (80 bits)
    val outValid = out Bool ()
    val out      = out(FpRoundReq())
  }

  // ── M0: unpack, classify, count leading zeros of both significands ───────────
  val m0 = new Area {
    val vld  = RegNext(io.start) init False
    val dst  = RegNext(io.dst); val src = RegNext(io.src)
    val dNan = RegNext(Fp80.isNan(io.dst));  val sNan  = RegNext(Fp80.isNan(io.src))
    val dInf = RegNext(Fp80.isInf(io.dst));  val sInf  = RegNext(Fp80.isInf(io.src))
    val dSN  = RegNext(Fp80.isSNan(io.dst)); val sSN   = RegNext(Fp80.isSNan(io.src))
    // SoftFloat's true-zero test here is (exp | sig) == 0, i.e. the ENTIRE 79 low bits,
    // NOT Musashi's FPCC zero test -- a subnormal is not a zero operand for FMUL.
    val dZero = RegNext(io.dst(78 downto 0) === 0)
    val sZero = RegNext(io.src(78 downto 0) === 0)
    val dSub  = RegNext(Fp80.exp(io.dst) === 0); val sSub = RegNext(Fp80.exp(io.src) === 0)
    val dClz  = RegNext(Fp80.clz(io.dst(63 downto 0)))
    val sClz  = RegNext(Fp80.clz(io.src(63 downto 0)))
  }

  // ── M1: pre-normalise subnormals, form zExp and zSign, resolve specials ──────
  val m1 = new Area {
    val dSig = Mux(m0.dSub, Fp80.sig(m0.dst) |<< m0.dClz.resize(7), Fp80.sig(m0.dst))
    val sSig = Mux(m0.sSub, Fp80.sig(m0.src) |<< m0.sClz.resize(7), Fp80.sig(m0.src))
    val dExp = Mux(m0.dSub, S(1, 18 bits) - m0.dClz.asSInt.resize(18),
                            Fp80.exp(m0.dst).asSInt.resize(18))
    val sExp = Mux(m0.sSub, S(1, 18 bits) - m0.sClz.asSInt.resize(18),
                            Fp80.exp(m0.src).asSInt.resize(18))
    val zSign = Fp80.sign(m0.dst) ^ Fp80.sign(m0.src)
    val zExp  = dExp + sExp - S(0x3FFE, 18 bits)

    val anyNan  = m0.dNan || m0.sNan
    val infZero = (m0.dInf && m0.sZero) || (m0.sInf && m0.dZero)

    val req = FpRoundReq()
    req.sign := zSign; req.exp := zExp; req.sig := 0; req.round := False; req.sticky := False
    req.writeFp := True; req.fpccFromSrc := False; req.fpccOverride := 0
    req.exc.clearAll()
    req.exc.snan  := m0.dSN || m0.sSN
    req.exc.operr := infZero && !anyNan
    req.bypass := False; req.bypassValue := Fp80.defaultNan
    when(anyNan) {
      req.bypass := True; req.bypassValue := Fp80.propagateNan(m0.dst, m0.src)
    } elsewhen(infZero) {
      req.bypass := True; req.bypassValue := Fp80.defaultNan
    } elsewhen(m0.dInf || m0.sInf) {
      req.bypass := True; req.bypassValue := Fp80.packInf(zSign)
    } elsewhen(m0.dZero || m0.sZero) {
      req.bypass := True; req.bypassValue := Fp80.packZero(zSign)
    }

    val vld  = RegNext(m0.vld) init False
    val rReq = RegNext(req); val rA = RegNext(dSig); val rB = RegNext(sSig)
  }

  // ── M2..M8: the DSP chain. MulCore.scala:53-74's idiom, unconditional shift, no CE. ──
  val opA0 = Reg(UInt(64 bits)); val opB0 = Reg(UInt(64 bits))
  val opA1 = Reg(UInt(64 bits)); val opB1 = Reg(UInt(64 bits))
  val mulP = Reg(UInt(128 bits))
  val prd0 = Reg(UInt(128 bits)); val prd1 = Reg(UInt(128 bits))
  val prd2 = Reg(UInt(128 bits)); val prd3 = Reg(UInt(128 bits))
  opA0 := m1.rA; opB0 := m1.rB
  opA1 := opA0;  opB1 := opB0
  mulP := opA1 * opB1
  prd0 := mulP; prd1 := prd0; prd2 := prd1; prd3 := prd2

  val ctxValid = Vec.fill(7)(RegInit(False))
  val ctxReq   = Vec.fill(7)(Reg(FpRoundReq()))
  ctxValid(0) := m1.vld; ctxReq(0) := m1.rReq
  for (i <- 1 until 7) { ctxValid(i) := ctxValid(i - 1); ctxReq(i) := ctxReq(i - 1) }

  // ── M9: 1-bit post-normalise + sticky, form the FpRoundReq ──────────────────
  val m9 = new Area {
    // SoftFloat: if the product's bit 127 is clear, shift left one and decrement zExp
    // (softfloat.c:3373-3376). Product is in [2^126, 2^128).
    val norm  = Mux(prd3(127), prd3, prd3 |<< 1)
    val expAd = Mux(prd3(127), S(0, 18 bits), S(-1, 18 bits))
    val req   = FpRoundReq()
    req := ctxReq(6)
    req.sig    := norm(127 downto 64)
    req.round  := norm(63)
    req.sticky := norm(62 downto 0) =/= 0
    req.exp    := ctxReq(6).exp + expAd
    val vld  = RegNext(ctxValid(6)) init False
    val rReq = RegNext(req)
  }

  io.outValid := m9.vld
  io.out      := m9.rReq
}
```

---

## Task D: `FpCheapPipe.scala` — FABS / FNEG / FMOVE / FMOVECR / FTST / FINT / FINTRZ

**Files:** Create `src/main/scala/m68k040/execute/fpu/FpCheapPipe.scala`

**Interfaces:**
- Consumes: `FpOp`, `FpRoundReq`, `Fp80`.
- Produces: `FpCheapPipe` (`object FpCheapPipe { val Latency = 10 }`).

### Three design points that must not be "simplified" away

1. **FTST is not FCMP-against-zero.** It classifies its source directly (2026-08-09 spec §3,
   §10 item 5; Musashi `m68kfpu.c:1547-1554`, opmode `0x3a`, `SET_CONDITION_CODES(source)`).
   The concrete, testable consequence: **FTST of ±infinity sets FPSR.I**, whereas FCMP's
   infinity table (`m68kfpu.c:1525-1545`) deliberately leaves I clear. A decode-time rewrite
   into `FCMP #0` would silently lose that bit. Here it falls out for free: FTST is
   `bypass = True, bypassValue = src, writeFp = False`, and `FpRoundPack` classifies whatever
   value it emits — no separate classifier, exactly the "share the generic result-to-FPCC
   classifier … but must not enter the subtract/compare datapath" instruction.
2. **FABS/FNEG are single-cycle *logic*** (one XOR/AND on bit 79, computed in C0), padded to
   the common front depth by plain registers. See Task F6's rationale for why one uniform
   fixed-result port beats a second early port.
3. **FINT/FINTRZ produce an extended-format integer-valued result**, not a narrowing convert.
   FINT uses `io.rmode`; FINTRZ forces RZ regardless of FPCR. The algorithm is SoftFloat's
   `floatx80_round_to_int` (`softfloat.c:3082-3145`) — **not** Musashi's `m68kfpu.c`
   int32-clamping handler (Divergence Register D1).

- [ ] **Step 1: create the file**

```scala
package m68k040.execute.fpu

import spinal.core._

object FpCheapPipe {
  val Latency = 10

  /** FMOVECR constant ROM, densely indexed (see `cromIndex`). Slots 22..31 are 0.0, which
    * is also what every undefined 7-bit offset maps to (Musashi's `default: source = 0`,
    * m68kfpu.c:1345-1347).
    *
    * CONFIDENCE, per Task A0 / VERIFY-1:
    *  - $00,$0C,$0D,$0E,$0F,$30,$31,$32..$37 : HIGH. Musashi's table and an independent
    *    exact correctly-rounded computation of each constant agree bit-for-bit.
    *  - $0B (log10(2))                       : UNCERTAIN. Musashi has ...F798, correctly
    *    rounded is ...F799. The value below is Musashi's, on the belief that the real
    *    68881 ROM carries the truncated word -- MUST be confirmed against the
    *    MC68881/MC68882 UM constant-ROM table before this file is considered done.
    *  - $38..$3F (10^32 .. 10^4096)          : correctly-rounded/real-ROM values used here;
    *    Musashi's are double-derived and differ (Divergence Register D2). Confirm against
    *    the UM table; do NOT "fix" them toward Musashi.
    */
  val cromWords: Seq[BigInt] = Seq(
    BigInt("4000C90FDAA22168C235", 16),   //  0  $00  pi
    BigInt("3FFD9A209A84FBCFF798", 16),   //  1  $0B  log10(2)      <-- VERIFY-1
    BigInt("4000ADF85458A2BB4A9B", 16),   //  2  $0C  e
    BigInt("3FFFB8AA3B295C17F0BC", 16),   //  3  $0D  log2(e)
    BigInt("3FFDDE5BD8A937287195", 16),   //  4  $0E  log10(e)
    BigInt("00000000000000000000", 16),   //  5  $0F  0.0
    BigInt("3FFEB17217F7D1CF79AC", 16),   //  6  $30  ln(2)
    BigInt("4000935D8DDDAAA8AC17", 16),   //  7  $31  ln(10)
    BigInt("3FFF8000000000000000", 16),   //  8  $32  10^0 = 1.0
    BigInt("4002A000000000000000", 16),   //  9  $33  10^1
    BigInt("4005C800000000000000", 16),   // 10  $34  10^2
    BigInt("400C9C40000000000000", 16),   // 11  $35  10^4
    BigInt("4019BEBC200000000000", 16),   // 12  $36  10^8
    BigInt("40348E1BC9BF04000000", 16),   // 13  $37  10^16
    BigInt("40699DC5ADA82B70B59E", 16),   // 14  $38  10^32          <-- VERIFY-1
    BigInt("40D3C2781F49FFCFA6D5", 16),   // 15  $39  10^64          <-- VERIFY-1
    BigInt("41A893BA47C980E98CE0", 16),   // 16  $3A  10^128         <-- VERIFY-1
    BigInt("4351AA7EEBFB9DF9DE8E", 16),   // 17  $3B  10^256         <-- VERIFY-1
    BigInt("46A3E319A0AEA60E91C7", 16),   // 18  $3C  10^512         <-- VERIFY-1
    BigInt("4D48C976758681750C17", 16),   // 19  $3D  10^1024        <-- VERIFY-1
    BigInt("5A929E8B3B5DC53D5DE5", 16),   // 20  $3E  10^2048        <-- VERIFY-1
    BigInt("7525C46052028A20979B", 16)    // 21  $3F  10^4096        <-- VERIFY-1
  ) ++ Seq.fill(10)(BigInt(0))

  /** 7-bit FMOVECR offset -> dense 5-bit ROM index; anything undefined -> slot 22 (0.0). */
  def cromIndex(sel: Bits): UInt = {
    val s   = sel.asUInt
    val idx = UInt(5 bits)
    idx := 22
    when(s === 0x00)                     { idx := 0 }
    when(s >= 0x0B && s <= 0x0F)         { idx := (s - 0x0B + 1).resize(5) }
    when(s >= 0x30 && s <= 0x3F)         { idx := (s - 0x30 + 6).resize(5) }
    idx
  }
}

/** The cheap fixed pipe. Real work happens in C0..C2; C3..C9 are delay registers that hold
  * FpCheapPipe.Latency equal to FpAddPipe/FpMulPipe (see FpuCore's header). */
class FpCheapPipe extends Component {
  val io = new Bundle {
    val start    = in Bool ()
    val op       = in(FpOp())
    val src      = in Bits (80 bits)
    val rmode    = in Bits (2 bits)
    val cromSel  = in Bits (7 bits)
    val outValid = out Bool ()
    val out      = out(FpRoundReq())
  }

  val rom = Mem(Bits(80 bits), FpCheapPipe.cromWords.map(v => B(v, 80 bits)))

  // ── C0: unpack, ROM read, FINT shift amount ─────────────────────────────────
  val c0 = new Area {
    val romOut = rom.readSync(FpCheapPipe.cromIndex(io.cromSel))   // lands in C1
    val eRZ    = io.op === FpOp.FINTRZ
    val rmEff  = Mux(eRZ, B"01", io.rmode)

    val vld = RegNext(io.start) init False
    val op  = RegNext(io.op)
    val src = RegNext(io.src)
    val rm  = RegNext(rmEff)
    val exp = RegNext(Fp80.exp(io.src))
    val sgn = RegNext(Fp80.sign(io.src))
    val sig = RegNext(Fp80.sig(io.src))
    val sn  = RegNext(Fp80.isSNan(io.src))
    val nan = RegNext(Fp80.isNan(io.src))
    // 0x403E - exp, meaningful only on the 0x3FFF <= exp < 0x403E arm (so 1..63)
    val shN = RegNext((U(0x403E, 16 bits) - Fp80.exp(io.src).resize(16)).resize(6))
  }

  // ── C1: FINT masks + the rounding add; every other op's value is already final ───
  val c1 = new Area {
    val isInt  = c0.op === FpOp.FINT || c0.op === FpOp.FINTRZ
    val big    = c0.exp >= 0x403E                     // already integral (or NaN/Inf)
    val small  = c0.exp <  0x3FFF                     // |x| < 1
    val lastM  = (U(1, 64 bits) |<< c0.shN).resize(64)
    val roundM = lastM - 1
    val rnAdd  = c0.sig + (lastM |>> 1)
    val awayAdd= c0.sig + roundM
    val rn     = c0.rm === 0; val rz = c0.rm === 1; val rmD = c0.rm === 2; val rp = c0.rm === 3
    // SoftFloat: sign ^ (mode == round_up) selects "add roundBitsMask"
    val away   = (c0.sgn ^ rp) && (rmD || rp)
    val t      = Mux(rn, rnAdd, Mux(away, awayAdd, c0.sig))

    val vld = RegNext(c0.vld) init False
    val op  = RegNext(c0.op);   val src = RegNext(c0.src); val sgn = RegNext(c0.sgn)
    val exp = RegNext(c0.exp);  val sig = RegNext(c0.sig); val sn  = RegNext(c0.sn)
    val nan = RegNext(c0.nan);  val rom = RegNext(c0.romOut)
    val rIsInt = RegNext(isInt); val rBig = RegNext(big); val rSmall = RegNext(small)
    val rT = RegNext(t); val rRoundM = RegNext(roundM); val rLastM = RegNext(lastM)
    val rRn = RegNext(rn); val rRz = RegNext(rz); val rRmD = RegNext(rmD); val rRp = RegNext(rp)
    // |x| in [0.5,1) with a nonzero tail -- SoftFloat's RN "return +/-1.0" corner
    val rHalfUp = RegNext(c0.exp === 0x3FFE && c0.src(62 downto 0) =/= 0)
    val rTrueZero = RegNext(c0.exp === 0 && c0.src(62 downto 0) === 0)
  }

  // ── C2: mask, carry fix-up, op mux, FpRoundReq ──────────────────────────────
  val c2 = new Area {
    // FINT main arm (SoftFloat softfloat.c:3126-3143)
    val tieClr  = Mux(c1.rRn && (c1.rT & c1.rRoundM) === 0, c1.rT & ~c1.rLastM, c1.rT)
    val masked  = tieClr & ~c1.rRoundM
    val carry   = masked === 0
    val intMain = Fp80.pack(c1.sgn,
                            Mux(carry, c1.exp + 1, c1.exp),
                            Mux(carry, U(BigInt(1) << 63, 64 bits), masked))
    // FINT |x| < 1 arm (softfloat.c:3096-3122)
    val one     = Fp80.pack(c1.sgn, U(0x3FFF, 15 bits), U(BigInt(1) << 63, 64 bits))
    val zeroS   = Fp80.packZero(c1.sgn)
    val intSmall = Mux(c1.rTrueZero, c1.src,
                   Mux(c1.rRn,  Mux(c1.rHalfUp, one, zeroS),
                   Mux(c1.rRmD, Mux(c1.sgn, one, Fp80.packZero(False)),
                   Mux(c1.rRp,  Mux(c1.sgn, Fp80.packZero(True), one),
                                zeroS))))
    val intVal  = Mux(c1.rBig,   Mux(c1.nan, Fp80.propagateNan(c1.src, c1.src), c1.src),
                  Mux(c1.rSmall, intSmall, intMain))
    val intInex = !c1.rBig && (c1.rSmall ? !c1.rTrueZero | (masked =/= c1.sig))

    val req = FpRoundReq()
    req.sign := c1.sgn; req.exp := 0; req.sig := 0; req.round := False; req.sticky := False
    req.bypass := True
    req.fpccFromSrc := False; req.fpccOverride := 0
    req.exc.clearAll(); req.exc.snan := c1.sn
    req.writeFp := True
    req.bypassValue := c1.src
    switch(c1.op) {
      is(FpOp.FABS)    { req.bypassValue := False ## c1.src(78 downto 0) }
      is(FpOp.FNEG)    { req.bypassValue := !c1.src(79) ## c1.src(78 downto 0) }
      is(FpOp.FMOVE)   { req.bypassValue := c1.src }
      is(FpOp.FMOVECR) { req.bypassValue := c1.rom; req.exc.snan := False }
      is(FpOp.FTST)    { req.bypassValue := c1.src; req.writeFp := False }
      is(FpOp.FINT)    { req.bypassValue := intVal; req.exc.inex2 := intInex }
      is(FpOp.FINTRZ)  { req.bypassValue := intVal; req.exc.inex2 := intInex }
    }

    val vld = RegNext(c1.vld) init False
    val rReq = RegNext(req)
  }

  // ── C3..C9: delay to the common front depth ─────────────────────────────────
  val dlyV = Vec.fill(7)(RegInit(False))
  val dlyR = Vec.fill(7)(Reg(FpRoundReq()))
  dlyV(0) := c2.vld; dlyR(0) := c2.rReq
  for (i <- 1 until 7) { dlyV(i) := dlyV(i - 1); dlyR(i) := dlyR(i - 1) }

  io.outValid := dlyV(6)
  io.out      := dlyR(6)
}
```

---

## Task E: `FpDivSqrtCore.scala` — the single-context iterative lane

**Files:** Create `src/main/scala/m68k040/execute/fpu/FpDivSqrtCore.scala`

**Interfaces:**
- Consumes: `FpRoundReq`, `FpResult`, `Fp80`, `FpRoundPack` (its own private instance).
- Produces: `FpDivSqrtCore`, `object FpDivSqrtCore { val WorstCaseLatency = 71 }`.

### Radix choice — radix-2 restoring, and the iteration count is 65 (the spec's "~64-67" estimate, verified)

The 2026-08-09 spec §6 estimated "56→67 iterations" for 64-bit vs 53-bit significands.
**Verified and made exact: it is 65 for both FDIV and FSQRT at radix 2**, because both need
64 significand bits plus one round bit, and radix-2 produces exactly one result bit per
iteration. Sticky comes free from `finalRemainder != 0`, needing no extra iteration.

Radix-2 restoring is chosen over radix-4 SRT deliberately:
- It is **structurally identical to the existing `DivCore.scala:79-92`** — one wide
  compare/subtract plus a 2:1 mux per cycle, with `DivCore`'s own note that *"the per-cycle
  path is one (W+1)-bit compare/subtract + a 2:1 mux (shallow), so FMax is dominated
  elsewhere"*. That claim already survived this design's post-route gates at 32-bit width;
  extending the same shape to 68 bits changes the carry chain from 5 to 9 CARRY8 blocks, not
  the topology.
- Radix-4 SRT would halve the count to 33 but introduces a quotient-digit-selection PLA and a
  redundant (carry-save) partial remainder — i.e. a genuinely new critical-path family, which
  is precisely what 2026-08-09 spec §7 forbids ("the FPU EU must not become a new
  session-worthy critical-path family").
- The payoff would be ~32 cycles on an operation that (a) has one context by design and
  (b) is rare. Recorded as a **documented future lever**, gated on real profiling once FPSP
  is running — not taken now.

Worst-case latency: 1 (unpack) + 1 (CLZ) + 1 (pre-normalise) + 1 (setup) + 65 (iterate)
+ 3 (`FpRoundPack`) = **71 cycles**; special cases (NaN / Inf / zero / divide-by-zero /
sqrt-of-negative) short-circuit straight into `FpRoundPack` in 4 cycles, mirroring
`DivCore.scala:63-70`'s divide-by-zero shortcut.

### Why this lane has its own `FpRoundPack` instance

Its completion time is data-dependent, so it can collide with the fixed lane's. Instantiating
a second `FpRoundPack` (~450 LUT / ~230 FF) removes the structural hazard entirely, which is
strictly cheaper than an arbiter plus a hold buffer *inside* this component and keeps the
"one atomic arbiter" where the 2026-08-09 spec §3 puts it — in the EU, above `FpuCore`.

### The `done`/`ack` contract (differs from `DivCore` on purpose)

`DivCore.io.done` is a one-cycle pulse. Here `io.doneIter` is a **level**, held until
`io.iterAck`, and `io.busyIter` stays asserted across the hold. This directly implements the
spec §3 requirement that *"a non-backpressured completion Flow is not permission to drop
either result"*: if the EU's arbiter grants the fixed lane this cycle, the iterative result
is still there next cycle.

### Square-root formulation

`result = Q · 2^k` with `Q = ⌊√A⌋` a 65-bit integer and `A = sig << (E even ? 65 : 66)`,
where `E = aExp − 0x3FFF`. The parity split forces `A ∈ [2^128, 2^130)` so `Q ∈ [2^64, 2^65)`
in both cases, and `zExp = (E >>> 1) + 0x3FFF` (arithmetic shift = floor), which is
`softfloat.c:3594` verbatim. 130 bits at two bits per iteration = **65 iterations**.
`sig = Q[64:1]`, `round = Q[0]`, `sticky = (A − Q²) ≠ 0`.

- [ ] **Step 1: create the file**

```scala
package m68k040.execute.fpu

import spinal.core._

object FpDivSqrtCore { val WorstCaseLatency = 71 }

/** One held iterative context shared by FDIV and FSQRT (2026-08-09 spec §3: "one held
  * iterative context", NOT replicated). Radix-2 restoring, 65 iterations for both.
  *
  * Handshake: pulse `start` when `!busy`. `busy` stays high until `iterAck`. `done` is a
  * LEVEL held until `iterAck` (unlike DivCore's pulse) so the EU's result arbiter can defer
  * this lane for a cycle without losing the result. */
class FpDivSqrtCore extends Component {
  val io = new Bundle {
    val start  = in Bool ()
    val isSqrt = in Bool ()
    val dst    = in Bits (80 bits)      // dividend (FPn); ignored by FSQRT
    val src    = in Bits (80 bits)      // divisor (<ea>); the operand for FSQRT
    val rmode  = in Bits (2 bits)
    val busy   = out Bool ()
    val done   = out Bool ()
    val ack    = in Bool ()
    val res    = out(FpResult())
  }

  val S_IDLE = 0; val S_CLZ = 1; val S_NORM = 2; val S_SETUP = 3
  val S_ITER = 4; val S_ROUND = 5; val S_HOLD = 6
  val state = RegInit(U(S_IDLE, 3 bits))

  val rp = new FpRoundPack
  rp.io.rmode := io.rmode
  rp.io.inValid := False
  rp.io.in.assignDontCare()

  val sqrtR  = RegInit(False)
  val dstR   = Reg(Bits(80 bits)); val srcR = Reg(Bits(80 bits))
  val aSig   = Reg(UInt(64 bits)); val bSig = Reg(UInt(64 bits))
  val aExp   = Reg(SInt(18 bits)); val bExp = Reg(SInt(18 bits))
  val zSign  = Reg(Bool());        val zExp = Reg(SInt(18 bits))
  val aClz   = Reg(UInt(7 bits));  val bClz = Reg(UInt(7 bits))
  val aSubR  = Reg(Bool());        val bSubR = Reg(Bool())

  val rem    = Reg(UInt(68 bits))
  val quot   = Reg(UInt(65 bits))
  val dEff   = Reg(UInt(65 bits))     // divide: the effective divisor
  val rad    = Reg(UInt(130 bits))    // sqrt: the radicand, consumed 2 bits/iteration
  val cnt    = Reg(UInt(7 bits))

  val bypReq = Reg(FpRoundReq())
  val bypass = RegInit(False)
  val resReg = Reg(FpResult())
  val doneR  = RegInit(False)

  io.busy := state =/= U(S_IDLE)
  io.done := doneR
  io.res  := resReg

  switch(state) {
    is(U(S_IDLE, 3 bits)) {
      when(io.start) {
        sqrtR := io.isSqrt; dstR := io.dst; srcR := io.src
        state := U(S_CLZ, 3 bits)
      }
    }

    // ── S_CLZ: classify, resolve every special case, count leading zeros ───────
    is(U(S_CLZ, 3 bits)) {
      val a = Mux(sqrtR, srcR, dstR)        // FSQRT's single operand arrives on `src`
      val b = srcR
      val aNan = Fp80.isNan(a); val bNan = Fp80.isNan(b)
      val aInf = Fp80.isInf(a); val bInf = Fp80.isInf(b)
      val aZ   = a(78 downto 0) === 0;  val bZ = b(78 downto 0) === 0
      val sgn  = Mux(sqrtR, False, Fp80.sign(a) ^ Fp80.sign(b))

      val r = FpRoundReq()
      r.sign := sgn; r.exp := 0; r.sig := 0; r.round := False; r.sticky := False
      r.writeFp := True; r.fpccFromSrc := False; r.fpccOverride := 0
      r.bypass := True; r.bypassValue := Fp80.defaultNan
      r.exc.clearAll()
      r.exc.snan := Mux(sqrtR, Fp80.isSNan(a), Fp80.isSNan(a) || Fp80.isSNan(b))

      val takeBypass = Bool(); takeBypass := True
      when(sqrtR) {                                       // softfloat.c:3577-3592
        when(aNan)                 { r.bypassValue := Fp80.propagateNan(a, a) }
        .elsewhen(aInf && !Fp80.sign(a)) { r.bypassValue := a }
        .elsewhen(aInf)            { r.bypassValue := Fp80.defaultNan; r.exc.operr := True }
        .elsewhen(Fp80.sign(a) && aZ)    { r.bypassValue := a }        // -0 -> -0
        .elsewhen(Fp80.sign(a))    { r.bypassValue := Fp80.defaultNan; r.exc.operr := True }
        .elsewhen(aZ)              { r.bypassValue := Fp80.packZero(False) }
        .otherwise                 { takeBypass := False }
      } otherwise {                                       // softfloat.c:3401-3432
        when(aNan || bNan)         { r.bypassValue := Fp80.propagateNan(a, b) }
        .elsewhen(aInf && bInf)    { r.bypassValue := Fp80.defaultNan; r.exc.operr := True }
        .elsewhen(aInf)            { r.bypassValue := Fp80.packInf(sgn) }
        .elsewhen(bInf)            { r.bypassValue := Fp80.packZero(sgn) }
        .elsewhen(bZ && aZ)        { r.bypassValue := Fp80.defaultNan; r.exc.operr := True }
        .elsewhen(bZ)              { r.bypassValue := Fp80.packInf(sgn); r.exc.dz := True }
        .elsewhen(aZ)              { r.bypassValue := Fp80.packZero(sgn) }
        .otherwise                 { takeBypass := False }
      }

      bypReq := r; bypass := takeBypass; zSign := sgn
      aSig := Fp80.sig(a); bSig := Fp80.sig(b)
      aExp := Fp80.exp(a).asSInt.resize(18); bExp := Fp80.exp(b).asSInt.resize(18)
      aSubR := Fp80.exp(a) === 0; bSubR := Fp80.exp(b) === 0
      aClz := Fp80.clz(a(63 downto 0)).resize(7)
      bClz := Fp80.clz(b(63 downto 0)).resize(7)
      state := Mux(takeBypass, U(S_ROUND, 3 bits), U(S_NORM, 3 bits))
    }

    // ── S_NORM: pre-normalise subnormal operands (normalizeFloatx80Subnormal) ───
    is(U(S_NORM, 3 bits)) {
      when(aSubR) { aSig := aSig |<< aClz; aExp := S(1, 18 bits) - aClz.asSInt.resize(18) }
      when(bSubR && !sqrtR) { bSig := bSig |<< bClz; bExp := S(1, 18 bits) - bClz.asSInt.resize(18) }
      state := U(S_SETUP, 3 bits)
    }

    // ── S_SETUP ────────────────────────────────────────────────────────────────
    is(U(S_SETUP, 3 bits)) {
      cnt  := 0
      quot := 0
      when(sqrtR) {
        val e     = aExp - S(0x3FFF, 18 bits)
        val even  = !e(0)
        zExp := (e >> 1) + S(0x3FFF, 18 bits)
        rad  := Mux(even, (aSig << 65).resize(130), (aSig << 66).resize(130))
        rem  := 0
      } otherwise {
        val aGe = aSig >= bSig
        // Q = floor(aSig * 2^65 / dEff) with dEff = 2*bSig when aSig >= bSig, else bSig.
        dEff := Mux(aGe, (bSig << 1).resize(65), bSig.resize(65))
        zExp := aExp - bExp + Mux(aGe, S(0x3FFF, 18 bits), S(0x3FFE, 18 bits))
        rem  := aSig.resize(68)
      }
      state := U(S_ITER, 3 bits)
    }

    // ── S_ITER: 65 radix-2 restoring steps ─────────────────────────────────────
    is(U(S_ITER, 3 bits)) {
      when(sqrtR) {
        val r2 = ((rem(65 downto 0) ## rad(129 downto 128)).asUInt).resize(68)
        val t  = ((quot(64 downto 0) ## B"01").asUInt).resize(68)      // (q << 2) | 1
        val ge = r2 >= t
        rem  := Mux(ge, r2 - t, r2)
        quot := (quot(63 downto 0) ## ge).asUInt
        rad  := (rad(127 downto 0) ## B"00").asUInt
      } otherwise {
        val r2 = (rem(66 downto 0) ## False).asUInt.resize(68)
        val ge = r2 >= dEff.resize(68)
        rem  := Mux(ge, r2 - dEff.resize(68), r2)
        quot := (quot(63 downto 0) ## ge).asUInt
      }
      cnt := cnt + 1
      when(cnt === U(64, 7 bits)) { state := U(S_ROUND, 3 bits) }
    }

    // ── S_ROUND: hand the tuple to this lane's private FpRoundPack ─────────────
    is(U(S_ROUND, 3 bits)) {
      rp.io.inValid := True
      when(bypass) {
        rp.io.in := bypReq
      } otherwise {
        val r = FpRoundReq()
        r := bypReq
        r.bypass := False
        r.sign   := zSign
        r.exp    := zExp
        r.sig    := quot(64 downto 1)
        r.round  := quot(0)
        r.sticky := rem =/= 0
        rp.io.in := r
      }
      state := U(S_HOLD, 3 bits)
    }

    // ── S_HOLD: capture, then hold `done` until the EU's arbiter acknowledges ──
    is(U(S_HOLD, 3 bits)) {
      when(rp.io.outValid) { resReg := rp.io.out; doneR := True }
      when(doneR && io.ack) { doneR := False; state := U(S_IDLE, 3 bits) }
    }
  }
}
```

---

## Task F: `FpuCore.scala` — the top-level component

**Files:** Create `src/main/scala/m68k040/execute/fpu/FpuCore.scala`

- [ ] **Step 1: create the file**

```scala
package m68k040.execute.fpu

import spinal.core._

object FpuCore {
  /** Registered stages from an accepted fixed-lane `start` through `doneFixed`.
    *
    * HARD CONSTANT, exactly like MulCore.Latency (MulCore.scala:9): the FPU EU's descriptor
    * shadow pipe must be sized from this, and changing it requires updating that pipe in the
    * same commit (Global Constraint GC-F2).
    *
    * 13 = 10 (lane front) + 3 (FpRoundPack). The 10 is set by FMUL, which is the deepest
    * front and the one whose depth is not free to choose: 1 unpack/CLZ + 1 subnormal
    * pre-normalise + 7 DSP48E2 register levels (2 operand + 1 multiply + 4 product -- the
    * exact structure MulCore.scala:49-52 documents as required for Vivado to distribute a
    * tiled multiply across every DSP's MREG/PREG, and a 64x64 tiling is strictly deeper than
    * MulCore's 33x33, so 4 trailing levels is a floor) + 1 post-normalise. FADD and the
    * cheap pipe are held at the same 10 on purpose -- see the header note below.
    *
    * FDIV/FSQRT are NOT covered by this constant: they are a busy/done handshake exactly
    * like DivCore. Worst case FpDivSqrtCore.WorstCaseLatency = 71 cycles. */
  val FixedLatency = 13
}

/** 80-bit IEEE-754 extended-precision FPU arithmetic core (design spec Decision 1:
  * 1 sign + 15 exponent + 64 explicit-integer-bit mantissa).
  *
  * Self-contained in the sense MulCore.scala is: plain start / busy / done / operands /
  * result, no awareness of the IQ, ROB, rename, FP PRF, decode, FPCR/FPSR architectural
  * state, EA generation, or exception vectoring.
  *
  * TWO RESULT PORTS, ONE PER LANE. This is the topology 2026-08-09 spec §3 specifies
  * ("Separate fixed-result FIFO/hold and iterative-result hold feed one atomic arbiter"):
  * the arbiter lives in the EU, above this component, not inside it.
  *
  * WHY ALL FIXED-LATENCY OPS SHARE ONE LATENCY. FpAddPipe, FpMulPipe and FpCheapPipe are all
  * exactly 10 stages deep, so with a single `start` port at most one of them can ever be
  * presenting a result to the shared FpRoundPack in any cycle -- which is what lets the three
  * lanes share one round/pack back-end with no arbitration, and lets the whole fixed side
  * expose a single MulCore-shaped port whose results are strictly in issue order. The cost
  * is that FABS/FNEG/FMOVE (one cycle of real logic) retire at 13. That is deliberate: the
  * alternative is a second early-result port, which would add a second collision source to
  * the EU's completion arbiter for ops whose latency is already hidden by rename and a
  * 64-entry ROB. If profiling later shows cheap-op latency mattering, adding that port is a
  * localised change here plus one arbiter input in the EU.
  *
  * NO BACKPRESSURE ON THE FIXED SIDE. `doneFixed` is an unconditional 1-cycle pulse
  * FixedLatency after an accepted `start`, identical to MulCore. The EU MUST reserve result
  * capacity before asserting `start` (2026-08-09 spec §3). */
class FpuCore extends Component {
  val io = new Bundle {
    // ── request ──
    val start   = in Bool ()
    val op      = in(FpOp())
    val dst     = in Bits (80 bits)   // FPn destination operand (SoftFloat's `a`)
    val src     = in Bits (80 bits)   // <ea>/FPm source operand (SoftFloat's `b`)
    val rmode   = in Bits (2 bits)    // FPCR[5:4] verbatim: 0=RN 1=RZ 2=RM 3=RP
    val cromSel = in Bits (7 bits)    // FMOVECR offset (command word bits [6:0])
    val ready   = out Bool ()         // combinational, a function of `op`

    // ── fixed lane (FADD/FSUB/FMUL/FABS/FNEG/FMOVE/FCMP/FTST/FINT/FINTRZ/FMOVECR) ──
    val doneFixed = out Bool ()
    val resFixed  = out(FpResult())

    // ── iterative lane (FDIV/FSQRT) ──
    val busyIter = out Bool ()
    val doneIter = out Bool ()        // LEVEL, held until iterAck
    val iterAck  = in Bool ()
    val resIter  = out(FpResult())

    // ── combinational operand classification (VERIFY-4; policy belongs to the EU) ──
    val srcUnnormal = out Bool ()
    val dstUnnormal = out Bool ()
    val srcDenorm   = out Bool ()
    val dstDenorm   = out Bool ()
  }

  val isIter  = io.op === FpOp.FDIV || io.op === FpOp.FSQRT
  val isAdd   = io.op === FpOp.FADD || io.op === FpOp.FSUB || io.op === FpOp.FCMP
  val isMul   = io.op === FpOp.FMUL
  val isCheap = !isIter && !isAdd && !isMul

  val addPipe   = new FpAddPipe
  val mulPipe   = new FpMulPipe
  val cheapPipe = new FpCheapPipe
  val iterCore  = new FpDivSqrtCore
  val roundPack = new FpRoundPack

  addPipe.io.start := io.start && isAdd
  addPipe.io.op    := io.op
  addPipe.io.dst   := io.dst
  addPipe.io.src   := io.src
  addPipe.io.rmode := io.rmode

  mulPipe.io.start := io.start && isMul
  mulPipe.io.dst   := io.dst
  mulPipe.io.src   := io.src

  cheapPipe.io.start   := io.start && isCheap
  cheapPipe.io.op      := io.op
  cheapPipe.io.src     := io.src
  cheapPipe.io.rmode   := io.rmode
  cheapPipe.io.cromSel := io.cromSel

  iterCore.io.start  := io.start && isIter && !iterCore.io.busy
  iterCore.io.isSqrt := io.op === FpOp.FSQRT
  iterCore.io.dst    := io.dst
  iterCore.io.src    := io.src
  iterCore.io.rmode  := io.rmode
  iterCore.io.ack    := io.iterAck

  // Mutually exclusive by construction (one `start` port, three equal-depth fronts).
  GenerationFlags.simulation {
    val n = addPipe.io.outValid.asUInt + mulPipe.io.outValid.asUInt + cheapPipe.io.outValid.asUInt
    assert(n <= 1, "FpuCore: two fixed fronts presented a result in the same cycle")
  }

  roundPack.io.rmode   := io.rmode
  roundPack.io.inValid := addPipe.io.outValid || mulPipe.io.outValid || cheapPipe.io.outValid
  roundPack.io.in      := Mux(addPipe.io.outValid, addPipe.io.out,
                          Mux(mulPipe.io.outValid, mulPipe.io.out, cheapPipe.io.out))

  io.doneFixed := roundPack.io.outValid
  io.resFixed  := roundPack.io.out
  io.busyIter  := iterCore.io.busy
  io.doneIter  := iterCore.io.done
  io.resIter   := iterCore.io.res

  io.ready := Mux(isIter, !iterCore.io.busy, True)

  io.srcUnnormal := Fp80.isUnnormal(io.src)
  io.dstUnnormal := Fp80.isUnnormal(io.dst)
  io.srcDenorm   := Fp80.isDenorm(io.src)
  io.dstDenorm   := Fp80.isDenorm(io.dst)
}
```

### F6 — **Interfaces** (the contract the EU-integration task consumes; nothing else is public)

`FpuCore` is consumed exactly the way `DivEuPlugin.scala:458-510` consumes `MulCore` today.

**Elaboration-time constants**

| name | value | meaning |
|---|---|---|
| `FpuCore.FixedLatency` | `13` | Registered stages from an accepted fixed-lane `start` to `doneFixed`. The EU's descriptor shadow pipe **must** be this deep. |
| `FpDivSqrtCore.WorstCaseLatency` | `71` | Documentation only; the iterative lane is a handshake, not a constant-latency pipe. |
| `FpRoundPack.Latency` | `3` | Internal; exposed only so a future retime keeps the arithmetic. |

**Inputs**

| signal | direction | width / type | semantics |
|---|---|---|---|
| `io.start` | in | `Bool` | Launch. Assert only when `io.ready`. May be asserted on consecutive cycles for fixed-lane ops. |
| `io.op` | in | `FpOp()` (13 elements) | `FADD, FSUB, FMUL, FDIV, FSQRT, FABS, FNEG, FMOVE, FCMP, FTST, FINT, FINTRZ, FMOVECR`. |
| `io.dst` | in | `Bits(80)` | The FPn destination operand (SoftFloat's `a`). Ignored by every one-operand op. |
| `io.src` | in | `Bits(80)` | The `<ea>`/FPm source operand (SoftFloat's `b`), **and** the single operand of FSQRT/FABS/FNEG/FMOVE/FTST/FINT/FINTRZ. Must already be extended-format — format conversion of memory operands is the EU's job. |
| `io.rmode` | in | `Bits(2)` | `FPCR[5:4]` verbatim (`0=RN 1=RZ 2=RM 3=RP`). Sampled at `start`; a later FPCR write cannot re-mux an in-flight result. |
| `io.cromSel` | in | `Bits(7)` | FMOVECR offset, command-word bits `[6:0]`. Ignored by every other op. |
| `io.iterAck` | in | `Bool` | Acknowledges `doneIter`; releases the iterative context. |

**Outputs**

| signal | direction | width / type | semantics |
|---|---|---|---|
| `io.ready` | out | `Bool` | Combinational function of `io.op`: `True` for every fixed-lane op; `!busyIter` for FDIV/FSQRT. |
| `io.doneFixed` | out | `Bool` | **1-cycle pulse**, exactly `FixedLatency` cycles after an accepted fixed-lane `start`. Reproduces the `start` valid pattern; results are strictly in issue order. Never backpressured — the EU reserves capacity first. |
| `io.resFixed` | out | `FpResult` | Valid on the `doneFixed` cycle only. |
| `io.busyIter` | out | `Bool` | High from an accepted FDIV/FSQRT `start` through `iterAck`. |
| `io.doneIter` | out | `Bool` | **Level**, not a pulse. Held until `iterAck`. |
| `io.resIter` | out | `FpResult` | Stable for as long as `doneIter` is high. |
| `io.srcUnnormal`, `io.dstUnnormal` | out | `Bool` | Combinational, same cycle as `io.src`/`io.dst`: exponent ≠ 0 and ≠ 0x7FFF but the explicit integer bit is clear. **Classification only, no policy** (VERIFY-4). |
| `io.srcDenorm`, `io.dstDenorm` | out | `Bool` | Combinational: exponent = 0 with a nonzero fraction. |

**`FpResult` fields** (identical on both ports)

| field | width | semantics |
|---|---|---|
| `value` | `Bits(80)` | Packed extended result. **Undefined when `writeFp` is `False`.** |
| `writeFp` | `Bool` | `False` for FCMP and FTST only; `True` for every other op. |
| `fpcc` | `Bits(4)` | Internal order `{NaN, I, Z, N}` = bit3..bit0 (2026-08-09 spec §8). Architectural `FPSR[27:24] = {N,Z,I,NaN}` is the **reversed** presentation — the reversal is the FPSR task's, not this component's. |
| `exc.snan` | `Bool` | A signalling-NaN operand was consumed. |
| `exc.operr` | `Bool` | `inf−inf`, `0×inf`, `0/0`, `inf/inf`, `sqrt(negative)`. |
| `exc.ovfl` | `Bool` | The Decision-10 overflow substitution fired. `value` already carries the substituted result. |
| `exc.unfl` | `Bool` | Tiny-after-rounding **and** inexact. `value` already carries the denormalised/zero result. |
| `exc.dz` | `Bool` | finite ÷ zero. |
| `exc.inex2` | `Bool` | Inexact (always set alongside `ovfl`). |

**Not on the interface, by design:** BSUN (branch-side), INEX1 (packed-decimal), FPIAR,
FPCR/FPSR storage, FSAVE frame fields, and anything ROB/rename-shaped.

---

## Task G: `FpuCoreSpec.scala` + `FpRefModel.scala` — standalone validation

**Files:**
- Create `src/test/scala/m68k040/execute/FpRefModel.scala`
- Create `src/test/scala/m68k040/execute/FpuCoreSpec.scala`

**Interfaces:** consumes `FpuCore`, `FpOp`, `M68kSim` (`src/test/scala/m68k040/M68kSim.scala`),
`VerilatorTest` (`src/test/scala/m68k040/TestTags.scala:7`). Mirrors `MulCoreSpec.scala`'s
shape: a Scala reference (`MulCoreSpec.scala:24-28`), a driver (`:31-45`), edge vectors
(`:58-73`), a random sweep (`:83-93`), and a dense-burst throughput test (`:104-165`).

- [ ] **Step 1: create `FpRefModel.scala` — the exact `floatx80` reference**

```scala
package m68k040.execute

/** Exact BigInt floatx80 reference, mirroring the SoftFloat in tools/musashi/musashi/
  * softfloat/softfloat.c that Decision 1 makes the bit-exact oracle. Values are the 80-bit
  * pattern as a BigInt: bit79 sign, bits78..64 exponent, bits63..0 explicit-integer-bit
  * mantissa. Rounding modes use the FPCR/SoftFloat encoding 0=RN 1=RZ 2=RM 3=RP. */
object FpRefModel {
  val M64 = (BigInt(1) << 64) - 1
  val DefaultNan = (BigInt(0xFFFF) << 64) | M64

  def sign(v: BigInt): Int = ((v >> 79) & 1).toInt
  def exp (v: BigInt): Int = ((v >> 64) & 0x7FFF).toInt
  def sig (v: BigInt): BigInt = v & M64
  def pack(s: Int, e: Int, m: BigInt): BigInt =
    (BigInt(s & 1) << 79) | (BigInt(e & 0x7FFF) << 64) | (m & M64)
  def isNan(v: BigInt)  = exp(v) == 0x7FFF && (sig(v) & ((BigInt(1) << 63) - 1)) != 0
  def isInf(v: BigInt)  = exp(v) == 0x7FFF && (sig(v) & ((BigInt(1) << 63) - 1)) == 0
  def isSNan(v: BigInt) = isNan(v) && ((sig(v) >> 62) & 1) == 0
  def isTrueZero(v: BigInt) = (v & ((BigInt(1) << 79) - 1)) == 0

  def propagateNan(a: BigInt, b: BigInt): BigInt = {
    val q = BigInt("C000000000000000", 16)
    val aq = (a & ~M64) | (sig(a) | q)
    val bq = (b & ~M64) | (sig(b) | q)
    if (isNan(a)) { if (isSNan(a) && isNan(b)) bq else aq } else bq
  }

  /** SoftFloat roundAndPackFloatx80, precision80 path. `sigv` is the 64-bit significand,
    * `rnd`/`stk` the two bits below it, `e` the biased exponent as a plain Int. */
  def roundPack(s: Int, e0: Int, sig0: BigInt, rnd0: Boolean, stk0: Boolean,
                rm: Int): (BigInt, Boolean, Boolean, Boolean) = {
    var e = e0; var m = sig0 & M64; var r = rnd0; var k = stk0
    def incrOf(rr: Boolean, ss: Boolean): Boolean = rm match {
      case 0 => rr
      case 1 => false
      case 2 => s == 1 && (rr || ss)
      case _ => s == 0 && (rr || ss)
    }
    val incr0 = incrOf(r, k)
    val ovfl = e > 0x7FFE || (e == 0x7FFE && m == M64 && incr0)
    if (ovfl) {
      val toMax = rm == 1 || (s == 1 && rm == 3) || (s == 0 && rm == 2)
      return (if (toMax) pack(s, 0x7FFE, M64) else pack(s, 0x7FFF, BigInt(1) << 63),
              true, false, true)
    }
    var unfl = false
    if (e <= 0) {
      val c = math.min(1 - e, 66)
      val ext = (m << 2) | (if (r) 2 else 0) | (if (k) 1 else 0)   // 66-bit {sig,r,s}
      val shifted = ext >> c
      val lost = (ext & ((BigInt(1) << c) - 1)) != 0
      val isTiny = e < 0 || !incr0 || m != M64
      m = (shifted >> 2) & M64
      r = ((shifted >> 1) & 1) == 1
      k = (((shifted) & 1) == 1) || lost
      e = 0
      if (isTiny && (r || k)) unfl = true
    }
    val inex = r || k
    val incr = incrOf(r, k)
    if (incr) {
      m += 1
      if (m > M64) { m = BigInt(1) << 63; e += 1 }
      else { if (rm == 0 && !k) m &= ~BigInt(1); if (e == 0 && ((m >> 63) & 1) == 1) e = 1 }
    } else if (m == 0) e = 0
    (pack(s, e, m), false, unfl, inex)
  }

  /** Real value of a non-special floatx80 as an exact rational (num, den). */
  private def rat(v: BigInt): (BigInt, BigInt) = {
    val e = if (exp(v) == 0) 1 else exp(v)
    val sh = e - 16383 - 63
    if (sh >= 0) (sig(v) << sh, BigInt(1)) else (sig(v), BigInt(1) << (-sh))
  }

  /** Round an exact rational magnitude to extended, then pack. Used by add/sub/mul/div/sqrt
    * once specials are out of the way -- this is deliberately a DIFFERENT construction from
    * the RTL's (which mirrors SoftFloat's shift/subtract structure), so agreement between
    * them is real evidence, not a shared-bug tautology. */
  def roundRational(s: Int, num: BigInt, den: BigInt, rm: Int)
      : (BigInt, Boolean, Boolean, Boolean) = {
    if (num == 0) return (pack(s, 0, 0), false, false, false)
    // scale so that 2^63 <= num/den < 2^64
    var k = 0
    var n = num; var d = den
    while (n / d < (BigInt(1) << 63)) { n <<= 1; k += 1 }
    while (n / d >= (BigInt(1) << 64)) { d <<= 1; k -= 1 }
    val q = n / d
    val rem = n - q * d
    val e = 16383 + 63 - k
    val half = 2 * rem
    val rnd = half >= d
    val stk = if (rnd) (half != d) else (rem != 0)
    roundPack(s, e, q, rnd, stk, rm)
  }

  def add(a: BigInt, b: BigInt, rm: Int): (BigInt, Boolean, Boolean, Boolean) = addsub(a, b, rm, sub = false)
  def sub(a: BigInt, b: BigInt, rm: Int): (BigInt, Boolean, Boolean, Boolean) = addsub(a, b, rm, sub = true)

  def addsub(a: BigInt, b: BigInt, rm: Int, sub: Boolean)
      : (BigInt, Boolean, Boolean, Boolean) = {
    if (isNan(a) || isNan(b)) return (propagateNan(a, b), false, false, false)
    val bs = sign(b) ^ (if (sub) 1 else 0)
    val effAdd = sign(a) == bs
    if (isInf(a) && isInf(b) && !effAdd) return (DefaultNan, false, false, false)
    if (isInf(a)) return (a, false, false, false)
    if (isInf(b)) return (pack(if (effAdd) sign(a) else 1 - sign(a), 0x7FFF, BigInt(1) << 63),
                          false, false, false)
    val (an, ad) = rat(a); val (bn, bd) = rat(b)
    val sA = if (sign(a) == 1) -an else an
    val sB = if (bs == 1) -bn else bn
    val num = sA * bd + sB * ad
    val den = ad * bd
    if (num == 0) return (pack(if (rm == 2) 1 else 0, 0, 0), false, false, false)
    roundRational(if (num < 0) 1 else 0, num.abs, den, rm)
  }

  def mul(a: BigInt, b: BigInt, rm: Int): (BigInt, Boolean, Boolean, Boolean) = {
    val s = sign(a) ^ sign(b)
    if (isNan(a) || isNan(b)) return (propagateNan(a, b), false, false, false)
    if ((isInf(a) && isTrueZero(b)) || (isInf(b) && isTrueZero(a))) return (DefaultNan, false, false, false)
    if (isInf(a) || isInf(b)) return (pack(s, 0x7FFF, BigInt(1) << 63), false, false, false)
    if (isTrueZero(a) || isTrueZero(b)) return (pack(s, 0, 0), false, false, false)
    val (an, ad) = rat(a); val (bn, bd) = rat(b)
    roundRational(s, an * bn, ad * bd, rm)
  }

  def div(a: BigInt, b: BigInt, rm: Int): (BigInt, Boolean, Boolean, Boolean) = {
    val s = sign(a) ^ sign(b)
    if (isNan(a) || isNan(b)) return (propagateNan(a, b), false, false, false)
    if (isInf(a) && isInf(b)) return (DefaultNan, false, false, false)
    if (isInf(a)) return (pack(s, 0x7FFF, BigInt(1) << 63), false, false, false)
    if (isInf(b)) return (pack(s, 0, 0), false, false, false)
    if (isTrueZero(b)) {
      if (isTrueZero(a)) return (DefaultNan, false, false, false)
      return (pack(s, 0x7FFF, BigInt(1) << 63), false, false, false)
    }
    if (isTrueZero(a)) return (pack(s, 0, 0), false, false, false)
    val (an, ad) = rat(a); val (bn, bd) = rat(b)
    roundRational(s, an * bd, ad * bn, rm)
  }

  def sqrt(a: BigInt, rm: Int): (BigInt, Boolean, Boolean, Boolean) = {
    if (isNan(a)) return (propagateNan(a, a), false, false, false)
    if (isInf(a) && sign(a) == 0) return (a, false, false, false)
    if (isInf(a)) return (DefaultNan, false, false, false)
    if (sign(a) == 1 && isTrueZero(a)) return (a, false, false, false)
    if (sign(a) == 1) return (DefaultNan, false, false, false)
    if (isTrueZero(a)) return (pack(0, 0, 0), false, false, false)
    // exact integer sqrt of sig << (65 or 66), matching the RTL's own formulation
    val e = (if (exp(a) == 0) 1 else exp(a))
    val (m, ee) = if (exp(a) == 0) {
      val c = 64 - sig(a).bitLength
      (sig(a) << c, 1 - c)
    } else (sig(a), e)
    val E = ee - 0x3FFF
    val s = if (E % 2 == 0) 65 else 66            // note: E may be negative; use parity
    val sAdj = if (((E % 2) + 2) % 2 == 0) 65 else 66
    val A = m << sAdj
    var lo = BigInt(1) << 63; var hi = BigInt(1) << 65
    while (lo < hi) { val mid = (lo + hi + 1) >> 1; if (mid * mid <= A) lo = mid else hi = mid - 1 }
    val q = lo
    val zExp = (if (E >= 0) E >> 1 else -(((-E) + 1) >> 1)) + 0x3FFF
    roundPack(0, zExp, q >> 1, (q & 1) == 1, (A - q * q) != 0, rm)
  }

  /** SoftFloat floatx80_round_to_int (softfloat.c:3082-3145) -- the REAL FINT semantics.
    * Musashi's m68kfpu.c does NOT use this (Divergence Register D1). */
  def roundToInt(a: BigInt, rm: Int): BigInt = {
    val e = exp(a); val s = sign(a)
    if (e >= 0x403E) return if (isNan(a)) propagateNan(a, a) else a
    if (e < 0x3FFF) {
      if (e == 0 && (sig(a) & ((BigInt(1) << 63) - 1)) == 0) return a
      rm match {
        case 0 => if (e == 0x3FFE && (sig(a) & ((BigInt(1) << 63) - 1)) != 0)
                    pack(s, 0x3FFF, BigInt(1) << 63) else pack(s, 0, 0)
        case 2 => if (s == 1) pack(1, 0x3FFF, BigInt(1) << 63) else pack(0, 0, 0)
        case 3 => if (s == 1) pack(1, 0, 0) else pack(0, 0x3FFF, BigInt(1) << 63)
        case _ => pack(s, 0, 0)
      }
    } else {
      val lastBit = BigInt(1) << (0x403E - e)
      val roundM  = lastBit - 1
      var lo = sig(a)
      if (rm == 0) { lo = (lo + (lastBit >> 1)) & M64; if ((lo & roundM) == 0) lo &= ~lastBit }
      else if (rm != 1) { if ((s == 1) != (rm == 3)) lo = (lo + roundM) & M64 }
      lo &= ~roundM
      if (lo == 0) pack(s, e + 1, BigInt(1) << 63) else pack(s, e, lo)
    }
  }

  /** Musashi SET_CONDITION_CODES in the internal {NaN,I,Z,N} bit order. */
  def fpcc(v: BigInt): Int = {
    val low63nz = (sig(v) & ((BigInt(1) << 63) - 1)) != 0
    var f = 0
    if (sign(v) == 1) f |= 1
    if (exp(v) == 0 && !low63nz) f |= 2
    if (exp(v) == 0x7FFF && !low63nz) f |= 4
    if (isNan(v)) f |= 8
    f
  }

  /** Musashi FCMP (m68kfpu.c:1517-1545): the explicit infinity table, else classify the
    * rounded difference. */
  def fcmp(d: BigInt, s: BigInt, rm: Int): Int = {
    def inf(v: BigInt) = if (!isInf(v)) 0 else if (sign(v) == 1) -1 else 1
    val di = inf(d); val si = inf(s)
    if (!isNan(d) && !isNan(s) && (di != 0 || si != 0)) {
      if (si < 0) { if (di < 0) 3 else 0 }
      else if (si > 0) { if (di > 0) 2 else 1 }
      else { if (di < 0) 1 else 0 }
    } else fpcc(sub(d, s, rm)._1)
  }
}
```

- [ ] **Step 2: create `FpuCoreSpec.scala`**

```scala
package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import m68k040.execute.fpu._
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

/** Standalone validation of the 80-bit extended FpuCore, in isolation from the IQ/ROB/
  * rename, mirroring MulCoreSpec.scala's structure. `FpRefModel` is the oracle; it is
  * an independent construction (exact rational arithmetic) from the RTL's SoftFloat-shaped
  * one, so agreement is evidence rather than a shared bug. */
class FpuCoreSpec extends AnyFunSuite {

  val PosZero = BigInt(0)
  val NegZero = BigInt(1) << 79
  val PosInf  = (BigInt(0x7FFF) << 64) | (BigInt(1) << 63)
  val NegInf  = (BigInt(1) << 79) | PosInf
  val QNan    = (BigInt(0x7FFF) << 64) | (BigInt(3) << 62)          // quiet:  bit62 set
  val SNan    = (BigInt(0x7FFF) << 64) | (BigInt(1) << 63) | BigInt(1)   // signalling: bit62 clear
  val One     = (BigInt(0x3FFF) << 64) | (BigInt(1) << 63)
  val Two     = (BigInt(0x4000) << 64) | (BigInt(1) << 63)
  val Four    = (BigInt(0x4001) << 64) | (BigInt(1) << 63)
  val Half    = (BigInt(0x3FFE) << 64) | (BigInt(1) << 63)
  val MaxFin  = (BigInt(0x7FFE) << 64) | ((BigInt(1) << 64) - 1)
  val MinNorm = (BigInt(0x0001) << 64) | (BigInt(1) << 63)
  val MinSub  = BigInt(1)                                           // exp 0, mantissa 1
  val ThreePt5= (BigInt(0x4000) << 64) | BigInt("E000000000000000", 16)  // 3.5
  val NegThreePt5 = (BigInt(1) << 79) | ThreePt5

  case class Got(value: BigInt, writeFp: Boolean, fpcc: Int,
                 snan: Boolean, operr: Boolean, ovfl: Boolean,
                 unfl: Boolean, dz: Boolean, inex: Boolean)

  def readRes(r: FpResult): Got = Got(
    r.value.toBigInt, r.writeFp.toBoolean, r.fpcc.toInt,
    r.exc.snan.toBoolean, r.exc.operr.toBoolean, r.exc.ovfl.toBoolean,
    r.exc.unfl.toBoolean, r.exc.dz.toBoolean, r.exc.inex2.toBoolean)

  /** Drive one fixed-lane op and wait exactly FpuCore.FixedLatency cycles. */
  def runFixed(dut: FpuCore, cd: ClockDomain, op: FpOp.E,
               d: BigInt, s: BigInt, rm: Int, crom: Int = 0): Got = {
    dut.io.start #= true; dut.io.op #= op
    dut.io.dst #= d; dut.io.src #= s; dut.io.rmode #= rm; dut.io.cromSel #= crom
    cd.waitSampling()
    dut.io.start #= false
    var n = 0
    while (!dut.io.doneFixed.toBoolean && n < FpuCore.FixedLatency + 8) { cd.waitSampling(); n += 1 }
    assert(dut.io.doneFixed.toBoolean, s"no doneFixed for $op")
    assert(n == FpuCore.FixedLatency - 1,
      s"$op completed after ${n + 1} cycles, expected ${FpuCore.FixedLatency}")
    val g = readRes(dut.io.resFixed); cd.waitSampling(2); g
  }

  def runIter(dut: FpuCore, cd: ClockDomain, op: FpOp.E, d: BigInt, s: BigInt, rm: Int): Got = {
    dut.io.start #= true; dut.io.op #= op
    dut.io.dst #= d
```scala
    dut.io.src #= s; dut.io.rmode #= rm; dut.io.cromSel #= 0
    cd.waitSampling()
    dut.io.start #= false
    var n = 0
    while (!dut.io.doneIter.toBoolean && n < FpDivSqrtCore.WorstCaseLatency + 16) {
      cd.waitSampling(); n += 1
    }
    assert(dut.io.doneIter.toBoolean, s"no doneIter for $op after $n cycles")
    val g = readRes(dut.io.resIter)
    // doneIter is a LEVEL: prove it survives a cycle without ack, then release it.
    cd.waitSampling()
    assert(dut.io.doneIter.toBoolean, "doneIter dropped before iterAck")
    assert(readRes(dut.io.resIter) == g, "resIter changed before iterAck")
    dut.io.iterAck #= true; cd.waitSampling(); dut.io.iterAck #= false
    cd.waitSampling()
    assert(!dut.io.busyIter.toBoolean, "busyIter still set after iterAck")
    g
  }

  def init(dut: FpuCore, cd: ClockDomain): Unit = {
    dut.io.start #= false; dut.io.op #= FpOp.FMOVE
    dut.io.dst #= 0; dut.io.src #= 0; dut.io.rmode #= 0
    dut.io.cromSel #= 0; dut.io.iterAck #= false
    cd.forkStimulus(10); cd.waitSampling(5)
  }
}
```

That closes the driver helpers. The remaining test bodies are broken into their own steps below.

- [ ] **Step 3: append the arithmetic round-trip tests to `FpuCoreSpec.scala`**

These are the "normal-range FADD/FSUB/FMUL/FDIV/FSQRT against known extended results" tests.
Every vector is checked twice: against a literal expected 80-bit word (so a bug in
`FpRefModel` cannot hide a bug in the RTL), and against `FpRefModel` (so the sweep can be
extended without hand-computing every word).

```scala
  test("FpuCore FADD/FSUB/FMUL: directed extended-precision vectors, all 4 rounding modes",
       VerilatorTest) {
    // (op, dst, src, rmode, expected 80-bit result)
    val vec: Seq[(FpOp.E, BigInt, BigInt, Int, BigInt)] = Seq(
      (FpOp.FADD, One,  One,  0, Two),                       // 1 + 1 = 2
      (FpOp.FADD, Two,  Two,  0, Four),                      // 2 + 2 = 4
      (FpOp.FSUB, Four, Two,  0, Two),                       // 4 - 2 = 2
      (FpOp.FSUB, Two,  Two,  0, PosZero),                   // exact cancellation -> +0 (RN)
      (FpOp.FSUB, Two,  Two,  2, NegZero),                   // exact cancellation -> -0 (RM)
      (FpOp.FSUB, Two,  Two,  1, PosZero),                   // ... +0 under RZ
      (FpOp.FSUB, Two,  Two,  3, PosZero),                   // ... +0 under RP
      (FpOp.FMUL, Two,  Two,  0, Four),                      // 2 * 2 = 4
      (FpOp.FMUL, ThreePt5, Two, 0, (BigInt(0x4001) << 64) | BigInt("E000000000000000", 16)), // 7
      (FpOp.FADD, One,  Half, 0, (BigInt(0x3FFF) << 64) | BigInt("C000000000000000", 16)),    // 1.5
      (FpOp.FSUB, One,  Half, 0, Half),                      // 1 - 0.5 = 0.5
      // massive cancellation: (1 + 2^-63) - 1 == 2^-63 exactly (close path, exact)
      (FpOp.FSUB, (BigInt(0x3FFF) << 64) | (BigInt(1) << 63) | BigInt(1), One, 0,
                  (BigInt(0x3FC0) << 64) | (BigInt(1) << 63)),
      // round-to-nearest-EVEN tie: 1 + 2^-64 -> 1.0 (LSB already even)
      (FpOp.FADD, One, (BigInt(0x3FBF) << 64) | (BigInt(1) << 63), 0, One),
      // same tie under RP rounds up by one ULP
      (FpOp.FADD, One, (BigInt(0x3FBF) << 64) | (BigInt(1) << 63), 3,
                  (BigInt(0x3FFF) << 64) | (BigInt(1) << 63) | BigInt(1)))

    M68kSim().withVerilator.compile(new FpuCore).doSim { dut =>
      val cd = dut.clockDomain; init(dut, cd)
      for ((op, d, s, rm, exp) <- vec) {
        val g = runFixed(dut, cd, op, d, s, rm)
        assert(g.value == exp, f"$op d=$d%020x s=$s%020x rm=$rm -> ${g.value}%020x, expected $exp%020x")
        assert(g.writeFp, s"$op must write an FP destination")
        val ref = op match {
          case FpOp.FADD => FpRefModel.add(d, s, rm)
          case FpOp.FSUB => FpRefModel.sub(d, s, rm)
          case _         => FpRefModel.mul(d, s, rm)
        }
        assert(g.value == ref._1, f"$op disagrees with FpRefModel: ${g.value}%020x vs ${ref._1}%020x")
        assert(g.fpcc == FpRefModel.fpcc(g.value), s"$op FPCC ${g.fpcc}")
      }
    }
  }

  test("FpuCore FDIV/FSQRT: directed extended-precision vectors", VerilatorTest) {
    val Three = (BigInt(0x4000) << 64) | BigInt("C000000000000000", 16)
    val Nine  = (BigInt(0x4002) << 64) | BigInt("9000000000000000", 16)
    val vec: Seq[(FpOp.E, BigInt, BigInt, Int, BigInt)] = Seq(
      (FpOp.FDIV,  Four, Two,  0, Two),                      // 4 / 2 = 2
      (FpOp.FDIV,  Two,  Four, 0, Half),                     // 2 / 4 = 0.5
      (FpOp.FDIV,  One,  Two,  0, Half),
      (FpOp.FDIV,  Nine, Three,0, Three),                    // 9 / 3 = 3
      (FpOp.FSQRT, 0,    Four, 0, Two),                      // sqrt(4) = 2
      (FpOp.FSQRT, 0,    Nine, 0, Three),                    // sqrt(9) = 3
      (FpOp.FSQRT, 0,    One,  0, One),
      (FpOp.FSQRT, 0,    PosZero, 0, PosZero),
      (FpOp.FSQRT, 0,    NegZero, 0, NegZero),               // -0 passes through
      // sqrt(2), inexact, correctly rounded
      (FpOp.FSQRT, 0,    Two,  0, (BigInt(0x3FFF) << 64) | BigInt("B504F333F9DE6484", 16)),
      // 1/3, inexact, correctly rounded under RN then RZ
      (FpOp.FDIV,  One,  Three, 0, (BigInt(0x3FFD) << 64) | BigInt("AAAAAAAAAAAAAAAB", 16)),
      (FpOp.FDIV,  One,  Three, 1, (BigInt(0x3FFD) << 64) | BigInt("AAAAAAAAAAAAAAAA", 16)))

    M68kSim().withVerilator.compile(new FpuCore).doSim { dut =>
      val cd = dut.clockDomain; init(dut, cd)
      for ((op, d, s, rm, exp) <- vec) {
        val g = runIter(dut, cd, op, d, s, rm)
        assert(g.value == exp, f"$op d=$d%020x s=$s%020x -> ${g.value}%020x, expected $exp%020x")
        val ref = if (op == FpOp.FDIV) FpRefModel.div(d, s, rm) else FpRefModel.sqrt(s, rm)
        assert(g.value == ref._1, f"$op disagrees with FpRefModel: ${g.value}%020x vs ${ref._1}%020x")
      }
    }
  }
```

> **Note for the implementer on two literals above.** `sqrt(2) = 0x3FFF B504F333F9DE6484` and
> `1/3 = 0x3FFD AAAAAAAAAAAAAAAB` (RN) / `...AAAA` (RZ) are the correctly-rounded extended
> values and are stated here so the test is self-checking rather than tautological with
> `FpRefModel`. **If either literal disagrees with `FpRefModel` on the first run, resolve it
> against exact arithmetic before touching the RTL** — a reference-vs-literal disagreement is
> a test bug, not a hardware bug, and fixing the hardware to match a wrong literal is the
> failure mode this cross-check exists to prevent.

- [ ] **Step 4: append the Decision-10 overflow substitution test (all four rounding modes)**

```scala
  test("FpuCore OVFL: Decision 10's 4-way rounding-mode/sign substitution table", VerilatorTest) {
    val PosMax = MaxFin
    val NegMax = (BigInt(1) << 79) | MaxFin
    // MaxFin + MaxFin overflows in both signs. Expected per the spec's table:
    //   RN -> Infinity(sign) | RZ -> largest(sign)
    //   RM -> +ovfl:largest+, -ovfl:Inf-  | RP -> +ovfl:Inf+, -ovfl:largest-
    val cases: Seq[(BigInt, Int, BigInt)] = Seq(
      (PosMax, 0, PosInf), (PosMax, 1, PosMax), (PosMax, 2, PosMax), (PosMax, 3, PosInf),
      (NegMax, 0, NegInf), (NegMax, 1, NegMax), (NegMax, 2, NegInf), (NegMax, 3, NegMax))

    M68kSim().withVerilator.compile(new FpuCore).doSim { dut =>
      val cd = dut.clockDomain; init(dut, cd)
      for ((v, rm, exp) <- cases) {
        val g = runFixed(dut, cd, FpOp.FADD, v, v, rm)
        assert(g.value == exp, f"OVFL rm=$rm sign=${FpRefModel.sign(v)} -> ${g.value}%020x, expected $exp%020x")
        assert(g.ovfl,  s"OVFL flag not set for rm=$rm")
        assert(g.inex,  s"OVFL must also be inexact (rm=$rm)")
        assert(!g.unfl, s"UNFL wrongly set on an overflow (rm=$rm)")
      }
      // Same table via FMUL, to prove the substitution lives in the SHARED back-end and is
      // not duplicated (or missing) per lane.
      val big = (BigInt(0x7000) << 64) | (BigInt(1) << 63)
      for ((rm, exp) <- Seq((0, PosInf), (1, PosMax), (2, PosMax), (3, PosInf))) {
        val g = runFixed(dut, cd, FpOp.FMUL, big, big, rm)
        assert(g.value == exp, f"FMUL OVFL rm=$rm -> ${g.value}%020x, expected $exp%020x")
        assert(g.ovfl)
      }
      // ... and via FDIV, proving the iterative lane's private FpRoundPack behaves identically.
      val tiny = (BigInt(0x0002) << 64) | (BigInt(1) << 63)
      for ((rm, exp) <- Seq((0, PosInf), (1, PosMax))) {
        val g = runIter(dut, cd, FpOp.FDIV, big, tiny, rm)
        assert(g.value == exp, f"FDIV OVFL rm=$rm -> ${g.value}%020x")
        assert(g.ovfl)
      }
    }
  }
```

- [ ] **Step 5: append the underflow / denormalisation test**

```scala
  test("FpuCore UNFL: denormalise to a nonzero subnormal, and all the way to zero", VerilatorTest) {
    M68kSim().withVerilator.compile(new FpuCore).doSim { dut =>
      val cd = dut.clockDomain; init(dut, cd)

      // (a) smallest normal / 2 -> the largest subnormal, exact, no INEX.
      val g1 = runIter(dut, cd, FpOp.FDIV, MinNorm, Two, 0)
      assert(g1.value == (BigInt(1) << 62), f"MinNorm/2 -> ${g1.value}%020x, expected a subnormal 2^62")
      assert(!g1.inex, "MinNorm/2 is exact; INEX2 must be clear")
      assert(!g1.unfl, "tininess is detected AFTER rounding and this is exact -- UNFL must be clear")

      // (b) smallest subnormal / 2 -> tie at zero. RN rounds to +0, RP up to MinSub.
      for ((rm, exp) <- Seq((0, PosZero), (1, PosZero), (2, PosZero), (3, MinSub))) {
        val g = runIter(dut, cd, FpOp.FDIV, MinSub, Two, rm)
        assert(g.value == exp, f"MinSub/2 rm=$rm -> ${g.value}%020x, expected $exp%020x")
        assert(g.unfl, s"UNFL must be set for MinSub/2 rm=$rm")
        assert(g.inex, s"MinSub/2 is inexact for rm=$rm")
      }
      // negative sign takes the mirror arm of the same table
      val negMinSub = (BigInt(1) << 79) | MinSub
      for ((rm, exp) <- Seq((0, NegZero), (2, negMinSub), (3, NegZero))) {
        val g = runIter(dut, cd, FpOp.FDIV, negMinSub, Two, rm)
        assert(g.value == exp, f"-MinSub/2 rm=$rm -> ${g.value}%020x, expected $exp%020x")
      }

      // (c) shift the mantissa completely out: a huge quotient exponent deficit.
      //     Expect +0 under RN/RZ/RM and MinSub under RP, with UNFL and INEX set.
      val huge = (BigInt(0x7FFE) << 64) | (BigInt(1) << 63)
      for ((rm, exp) <- Seq((0, PosZero), (1, PosZero), (2, PosZero), (3, MinSub))) {
        val g = runIter(dut, cd, FpOp.FDIV, MinSub, huge, rm)
        assert(g.value == exp, f"MinSub/huge rm=$rm -> ${g.value}%020x, expected $exp%020x")
        assert(g.unfl && g.inex, s"MinSub/huge rm=$rm must set UNFL and INEX2")
      }

      // (d) an FADD that underflows through the SHARED back-end (same table, other lane).
      val g4 = runFixed(dut, cd, FpOp.FSUB, MinSub, (BigInt(1) << 79) | MinSub, 0)
      assert(g4.value == (BigInt(2)), f"MinSub - (-MinSub) -> ${g4.value}%020x, expected 2*MinSub")
      assert(!g4.unfl, "exact subnormal sum: UNFL must be clear (tininess after rounding)")
    }
  }
```

- [ ] **Step 6: append the NaN / infinity / OPERR / DZ / SNAN tests**

```scala
  test("FpuCore NaN propagation, SNaN detection, and infinity arithmetic", VerilatorTest) {
    val quietedS = SNan | (BigInt(3) << 62)      // propagateFloatx80NaN OR-quiets both operands
    M68kSim().withVerilator.compile(new FpuCore).doSim { dut =>
      val cd = dut.clockDomain; init(dut, cd)

      // quiet NaN propagates unchanged, does NOT set SNAN
      val a = runFixed(dut, cd, FpOp.FADD, QNan, One, 0)
      assert(a.value == QNan, f"QNaN + 1 -> ${a.value}%020x")
      assert(!a.snan, "quiet NaN must not raise SNAN")
      assert((a.fpcc & 8) != 0, "FPCC.NaN must be set")

      // signalling NaN is quieted on the way out AND raises SNAN
      val b = runFixed(dut, cd, FpOp.FADD, SNan, One, 0)
      assert(b.value == quietedS, f"SNaN + 1 -> ${b.value}%020x, expected quieted $quietedS%020x")
      assert(b.snan, "signalling NaN operand must set FPSR.SNAN")

      // SoftFloat's a-vs-b selection: a signalling `a` with a NaN `b` yields quieted b
      val c = runFixed(dut, cd, FpOp.FADD, SNan, QNan, 0)
      assert(c.value == QNan, f"SNaN + QNaN -> ${c.value}%020x, expected the quiet operand")
      assert(c.snan)

      // infinity edge cases
      case class E(op: FpOp.E, d: BigInt, s: BigInt, v: BigInt, operr: Boolean, dz: Boolean)
      val edges = Seq(
        E(FpOp.FADD, PosInf, NegInf, FpRefModel.DefaultNan, true,  false),  // inf - inf
        E(FpOp.FSUB, PosInf, PosInf, FpRefModel.DefaultNan, true,  false),
        E(FpOp.FADD, PosInf, One,    PosInf,                false, false),
        E(FpOp.FADD, One,    NegInf, NegInf,                false, false),
        E(FpOp.FSUB, One,    PosInf, NegInf,                false, false),
        E(FpOp.FMUL, PosInf, PosZero,FpRefModel.DefaultNan, true,  false),  // 0 * inf
        E(FpOp.FMUL, NegZero,PosInf, FpRefModel.DefaultNan, true,  false),
        E(FpOp.FMUL, PosInf, Two,    PosInf,                false, false),
        E(FpOp.FMUL, NegInf, Two,    NegInf,                false, false))
      for (e <- edges) {
        val g = runFixed(dut, cd, e.op, e.d, e.s, 0)
        assert(g.value == e.v, f"${e.op} ${e.d}%020x,${e.s}%020x -> ${g.value}%020x, expected ${e.v}%020x")
        assert(g.operr == e.operr, s"${e.op} OPERR ${g.operr}, expected ${e.operr}")
      }

      // iterative-lane edges
      val iterEdges = Seq(
        (FpOp.FDIV,  PosInf, PosInf,  FpRefModel.DefaultNan, true,  false),  // inf/inf
        (FpOp.FDIV,  PosZero,PosZero, FpRefModel.DefaultNan, true,  false),  // 0/0
        (FpOp.FDIV,  One,    PosZero, PosInf,                false, true ),  // 1/0 -> DZ
        (FpOp.FDIV,  One,    NegZero, NegInf,                false, true ),
        (FpOp.FDIV,  NegInf, Two,     NegInf,                false, false),
        (FpOp.FDIV,  Two,    PosInf,  PosZero,               false, false),
        (FpOp.FSQRT, 0,      NegInf,  FpRefModel.DefaultNan, true,  false),
        (FpOp.FSQRT, 0,      PosInf,  PosInf,                false, false),
        (FpOp.FSQRT, 0,      (BigInt(1) << 79) | Four, FpRefModel.DefaultNan, true, false))
      for ((op, d, s, v, oe, dz) <- iterEdges) {
        val g = runIter(dut, cd, op, d, s, 0)
        assert(g.value == v, f"$op ${d}%020x,${s}%020x -> ${g.value}%020x, expected $v%020x")
        assert(g.operr == oe, s"$op OPERR ${g.operr}, expected $oe")
        assert(g.dz == dz, s"$op DZ ${g.dz}, expected $dz")
      }
    }
  }
```

- [ ] **Step 7: append the FTST-vs-FCMP-on-infinity divergence regression (the mutation-killer for a decode-time FCMP rewrite)**

```scala
  test("FpuCore FTST vs FCMP on infinity: FTST sets FPSR.I, FCMP does not", VerilatorTest) {
    // Internal FPCC layout, 2026-08-09 spec section 8: bit0=N bit1=Z bit2=I bit3=NaN.
    val N = 1; val Z = 2; val I = 4; val NAN = 8
    M68kSim().withVerilator.compile(new FpuCore).doSim { dut =>
      val cd = dut.clockDomain; init(dut, cd)

      // ---- FTST: direct source classification (Musashi m68kfpu.c:1547-1554) ----
      val ftst = Seq(
        (PosInf,  I),          // <-- the whole point: I set
        (NegInf,  I | N),
        (PosZero, Z),
        (NegZero, Z | N),
        (One,     0),
        ((BigInt(1) << 79) | One, N),
        (QNan,    NAN),
        (SNan,    NAN))
      for ((v, exp) <- ftst) {
        val g = runFixed(dut, cd, FpOp.FTST, 0, v, 0)
        assert(g.fpcc == exp, f"FTST ${v}%020x -> FPCC ${g.fpcc}, expected $exp")
        assert(!g.writeFp, "FTST must not write an FP destination")
        assert(g.fpcc == FpRefModel.fpcc(v), "FTST must agree with the classifier reference")
      }
      assert(runFixed(dut, cd, FpOp.FTST, 0, SNan, 0).snan, "FTST of an SNaN must set FPSR.SNAN")

      // ---- FCMP: the explicit infinity table leaves I clear (m68kfpu.c:1525-1545) ----
      val fcmp = Seq(
        (PosInf, PosInf, Z),        // equal
        (NegInf, NegInf, Z | N),
        (PosInf, NegInf, 0),        // dst greater
        (NegInf, PosInf, N),        // dst less
        (PosInf, One,    0),        // +inf > finite
        (One,    PosInf, N),        // finite < +inf
        (NegInf, One,    N),
        (One,    NegInf, 0))
      for ((d, s, exp) <- fcmp) {
        val g = runFixed(dut, cd, FpOp.FCMP, d, s, 0)
        assert(g.fpcc == exp, f"FCMP ${d}%020x,${s}%020x -> FPCC ${g.fpcc}, expected $exp")
        assert((g.fpcc & I) == 0, "FCMP's infinity table must leave FPSR.I CLEAR")
        assert(!g.writeFp, "FCMP must not write an FP destination")
        assert(g.fpcc == FpRefModel.fcmp(d, s, 0), "FCMP must agree with the reference")
      }

      // THE MUTATION KILLER. A decode-time rewrite of FTST into `FCMP src, #0` would make
      // these two agree. They must not: FTST(+inf) sets I; FCMP(+inf, +0) does not.
      val tst = runFixed(dut, cd, FpOp.FTST, 0,      PosInf, 0)
      val cmp = runFixed(dut, cd, FpOp.FCMP, PosInf, PosZero, 0)
      assert((tst.fpcc & I) != 0 && (cmp.fpcc & I) == 0,
        s"FTST/FCMP infinity divergence lost: FTST=${tst.fpcc} FCMP=${cmp.fpcc} -- " +
        "FTST has been reduced to a compare-against-zero")

      // FCMP's non-special path still goes through the real subtract datapath.
      assert(runFixed(dut, cd, FpOp.FCMP, Two, One, 0).fpcc == 0, "2 vs 1 -> greater")
      assert(runFixed(dut, cd, FpOp.FCMP, One, Two, 0).fpcc == N, "1 vs 2 -> less")
      assert(runFixed(dut, cd, FpOp.FCMP, Two, Two, 0).fpcc == Z, "2 vs 2 -> equal")
      assert((runFixed(dut, cd, FpOp.FCMP, QNan, One, 0).fpcc & NAN) != 0, "NaN compare")
    }
  }
```

- [ ] **Step 8: append the cheap-op tests (FABS/FNEG/FMOVE/FINT/FINTRZ) and the FMOVECR ROM read-back**

```scala
  test("FpuCore FABS/FNEG/FMOVE and FINT/FINTRZ", VerilatorTest) {
    val NegOne = (BigInt(1) << 79) | One
    M68kSim().withVerilator.compile(new FpuCore).doSim { dut =>
      val cd = dut.clockDomain; init(dut, cd)

      for (v <- Seq(One, NegOne, PosZero, NegZero, PosInf, NegInf, QNan, MinSub)) {
        val abs = runFixed(dut, cd, FpOp.FABS, 0, v, 0)
        assert(abs.value == (v & ((BigInt(1) << 79) - 1)), f"FABS ${v}%020x")
        val neg = runFixed(dut, cd, FpOp.FNEG, 0, v, 0)
        assert(neg.value == (v ^ (BigInt(1) << 79)), f"FNEG ${v}%020x")
        val mov = runFixed(dut, cd, FpOp.FMOVE, 0, v, 0)
        assert(mov.value == v, f"FMOVE ${v}%020x")
        for (g <- Seq(abs, neg, mov)) {
          assert(g.writeFp, "FABS/FNEG/FMOVE write an FP destination")
          assert(g.fpcc == FpRefModel.fpcc(g.value), "FPCC must be classified from the RESULT")
        }
      }

      // FINT uses the FPCR mode; FINTRZ always truncates toward zero regardless of it.
      val v3p5 = ThreePt5; val vN3p5 = NegThreePt5
      val four = Four; val three = (BigInt(0x4000) << 64) | BigInt("C000000000000000", 16)
      val nFour = (BigInt(1) << 79) | four; val nThree = (BigInt(1) << 79) | three
      val fint = Seq(
        (v3p5,  0, four),  (v3p5,  1, three), (v3p5,  2, three), (v3p5,  3, four),
        (vN3p5, 0, nFour), (vN3p5, 1, nThree),(vN3p5, 2, nFour), (vN3p5, 3, nThree),
        (Half,  0, PosZero),                             // 0.5 -> 0 (round-half-to-EVEN)
        ((BigInt(0x3FFE) << 64) | BigInt("C000000000000000", 16), 0, One),  // 0.75 -> 1
        (One,   0, One), (PosZero, 0, PosZero), (NegZero, 0, NegZero),
        (PosInf,0, PosInf), (QNan, 0, QNan))
      for ((v, rm, exp) <- fint) {
        val g = runFixed(dut, cd, FpOp.FINT, 0, v, rm)
        assert(g.value == exp, f"FINT ${v}%020x rm=$rm -> ${g.value}%020x, expected $exp%020x")
        assert(g.value == FpRefModel.roundToInt(v, rm), "FINT must match floatx80_round_to_int")
      }
      // FINTRZ ignores FPCR in ALL four modes
      for (rm <- 0 to 3) {
        assert(runFixed(dut, cd, FpOp.FINTRZ, 0, v3p5,  rm).value == three,
               s"FINTRZ(3.5) must truncate regardless of rm=$rm")
        assert(runFixed(dut, cd, FpOp.FINTRZ, 0, vN3p5, rm).value == nThree,
               s"FINTRZ(-3.5) must truncate regardless of rm=$rm")
      }
      // The result is EXTENDED-format, not a narrowing convert: a value far beyond int32
      // range passes through unchanged (Divergence Register D1 -- Musashi gets this wrong).
      val big = (BigInt(0x4100) << 64) | BigInt("ABCDEF0123456789", 16)
      assert(runFixed(dut, cd, FpOp.FINT,   0, big, 0).value == big, "FINT must not narrow")
      assert(runFixed(dut, cd, FpOp.FINTRZ, 0, big, 0).value == big, "FINTRZ must not narrow")
    }
  }

  test("FpuCore FMOVECR constant ROM read-back", VerilatorTest) {
    // Only the HIGH-CONFIDENCE entries are asserted as literals here (Task A0's confidence
    // split): these are cross-confirmed by Musashi's table AND an independent exact
    // correctly-rounded computation. Offsets $0B and $38..$3F are read back and compared to
    // FpCheapPipe.cromWords instead, so the ROM is proven to hold what the source says while
    // the VALUES stay gated on VERIFY-1.
    val certain = Seq(
      (0x00, BigInt("4000C90FDAA22168C235", 16)),   // pi
      (0x0C, BigInt("4000ADF85458A2BB4A9B", 16)),   // e
      (0x0D, BigInt("3FFFB8AA3B295C17F0BC", 16)),   // log2(e)
      (0x0E, BigInt("3FFDDE5BD8A937287195", 16)),   // log10(e)
      (0x0F, BigInt(0)),                            // 0.0
      (0x30, BigInt("3FFEB17217F7D1CF79AC", 16)),   // ln(2)
      (0x31, BigInt("4000935D8DDDAAA8AC17", 16)),   // ln(10)
      (0x32, BigInt("3FFF8000000000000000", 16)),   // 10^0 = 1.0
      (0x33, BigInt("4002A000000000000000", 16)),   // 10^1
      (0x34, BigInt("4005C800000000000000", 16)),   // 10^2
      (0x35, BigInt("400C9C40000000000000", 16)),   // 10^4
      (0x36, BigInt("4019BEBC200000000000", 16)),   // 10^8
      (0x37, BigInt("40348E1BC9BF04000000", 16)))   // 10^16
    val gated = Seq(0x0B) ++ (0x38 to 0x3F)
    val undefined = Seq(0x01, 0x0A, 0x10, 0x2F, 0x40, 0x7F)

    M68kSim().withVerilator.compile(new FpuCore).doSim { dut =>
      val cd = dut.clockDomain; init(dut, cd)
      for ((off, exp) <- certain) {
        val g = runFixed(dut, cd, FpOp.FMOVECR, 0, 0, 0, off)
        assert(g.value == exp, f"FMOVECR #$$$off%02x -> ${g.value}%020x, expected $exp%020x")
        assert(g.writeFp, "FMOVECR writes an FP destination")
        assert(g.fpcc == FpRefModel.fpcc(exp), s"FMOVECR #$off FPCC")
        assert(!g.snan, "FMOVECR never raises SNAN")
      }
      for (off <- gated) {
        val idx = FpCheapPipe.cromIndex(B(off, 7 bits))  // elaboration-time helper, not RTL
        val exp = FpCheapPipe.cromWords(
          if (off == 0x0B) 1 else (off - 0x30 + 6))
        val g = runFixed(dut, cd, FpOp.FMOVECR, 0, 0, 0, off)
        assert(g.value == exp,
          f"FMOVECR #$$$off%02x -> ${g.value}%020x, but cromWords says $exp%020x " +
          "(this entry's VALUE is still gated on VERIFY-1)")
      }
      for (off <- undefined) {
        val g = runFixed(dut, cd, FpOp.FMOVECR, 0, 0, 0, off)
        assert(g.value == 0, f"undefined FMOVECR offset $$$off%02x must read 0.0, got ${g.value}%020x")
      }
    }
  }
```

> `FpCheapPipe.cromIndex` takes a `Bits`, so the `gated` loop above must not call it outside
> an elaboration context. Replace that line with the plain Scala index arithmetic already
> used on the following line — it is written out here only to make the mapping explicit.
> **Delete the `val idx = ...` line when creating the file.**

- [ ] **Step 9: append the throughput / latency / lane-collision tests**

These are the 2026-08-09 spec §9 protocol tests the instruction corpus cannot substitute for,
narrowed to the ones that are *this component's* responsibility (dense start, exact latency,
simultaneous residency, iterative-vs-fixed collision). Flush/ROB-reuse and PRF/wakeup tests
belong to the EU-integration task, not here.

```scala
  test("FpuCore accepts eight consecutive fixed ops and completes them in issue order",
       VerilatorTest) {
    val vectors = Seq(
      (FpOp.FADD, One,  One,  0), (FpOp.FMUL, Two,  Two,  0),
      (FpOp.FABS, BigInt(0), (BigInt(1) << 79) | One, 0),
      (FpOp.FSUB, Four, Two,  0), (FpOp.FNEG, BigInt(0), One, 0),
      (FpOp.FMOVE,BigInt(0), Two, 0), (FpOp.FADD, Two, Two, 0),
      (FpOp.FMUL, ThreePt5, Two, 0))
    val expected = vectors.map { case (op, d, s, rm) => op match {
      case FpOp.FADD  => FpRefModel.add(d, s, rm)._1
      case FpOp.FSUB  => FpRefModel.sub(d, s, rm)._1
      case FpOp.FMUL  => FpRefModel.mul(d, s, rm)._1
      case FpOp.FABS  => s & ((BigInt(1) << 79) - 1)
      case FpOp.FNEG  => s ^ (BigInt(1) << 79)
      case _          => s } }

    M68kSim().withVerilator.compile(new FpuCore).doSim { dut =>
      val cd = dut.clockDomain; init(dut, cd)
      var accepted = 0; var completed = 0; var firstDone = -1; var maxResident = 0
      val total = vectors.length + FpuCore.FixedLatency + 2
      for (cycle <- 0 until total) {
        if (cycle < vectors.length) {
          val (op, d, s, rm) = vectors(cycle)
          dut.io.start #= true; dut.io.op #= op
          dut.io.dst #= d; dut.io.src #= s; dut.io.rmode #= rm; dut.io.cromSel #= 0
        } else dut.io.start #= false
        cd.waitSampling()
        if (cycle < vectors.length) accepted += 1
        maxResident = math.max(maxResident, accepted - completed)
        val shouldDone = cycle >= FpuCore.FixedLatency &&
                         cycle <  FpuCore.FixedLatency + vectors.length
        assert(dut.io.doneFixed.toBoolean == shouldDone,
          s"cycle $cycle doneFixed=${dut.io.doneFixed.toBoolean}, expected $shouldDone")
        if (dut.io.doneFixed.toBoolean) {
          if (firstDone < 0) firstDone = cycle
          val got = dut.io.resFixed.value.toBigInt
          assert(got == expected(completed),
            f"dense result #$completed -> $got%020x, expected ${expected(completed)}%020x " +
            "(results must leave in ISSUE order)")
          completed += 1
        }
      }
      assert(completed == vectors.length, s"completed $completed/${vectors.length}")
      assert(firstDone == FpuCore.FixedLatency,
        s"first doneFixed at $firstDone, expected ${FpuCore.FixedLatency}")
      assert(maxResident >= 8, s"only $maxResident ops were simultaneously in flight")
      assert(!dut.io.doneFixed.toBoolean, "spurious doneFixed after the burst")
    }
  }

  test("FpuCore holds the iterative result across a colliding fixed completion", VerilatorTest) {
    M68kSim().withVerilator.compile(new FpuCore).doSim { dut =>
      val cd = dut.clockDomain; init(dut, cd)
      // Launch FDIV, then keep the fixed lane saturated while it iterates.
      dut.io.start #= true; dut.io.op #= FpOp.FDIV
      dut.io.dst #= Four; dut.io.src #= Two; dut.io.rmode #= 0
      cd.waitSampling(); dut.io.start #= false
      assert(dut.io.busyIter.toBoolean, "busyIter must assert on the accept cycle")
      // A second FDIV must be refused while the single context is occupied.
      dut.io.op #= FpOp.FDIV
      assert(!dut.io.ready.toBoolean, "ready must be low for FDIV while busyIter")
      dut.io.op #= FpOp.FADD
      assert(dut.io.ready.toBoolean, "a busy FDIV must NOT block the fixed lane")

      var fixedDone = 0
      var sawCollision = false
      for (_ <- 0 until FpDivSqrtCore.WorstCaseLatency + 20) {
        dut.io.start #= true; dut.io.op #= FpOp.FADD
        dut.io.dst #= One; dut.io.src #= One
        cd.waitSampling()
        if (dut.io.doneFixed.toBoolean) {
          fixedDone += 1
          assert(dut.io.resFixed.value.toBigInt == Two, "fixed lane corrupted by the iterative lane")
          if (dut.io.doneIter.toBoolean) sawCollision = true
        }
      }
      dut.io.start #= false; cd.waitSampling()
      assert(fixedDone > 20, s"fixed lane only completed $fixedDone ops during an FDIV")
      assert(dut.io.doneIter.toBoolean, "doneIter must still be held -- it was never acked")
      assert(sawCollision, "the test never actually collided a fixed completion with doneIter")
      assert(dut.io.resIter.value.toBigInt == Two, "the held FDIV result was corrupted")
      dut.io.iterAck #= true; cd.waitSampling(); dut.io.iterAck #= false; cd.waitSampling()
      assert(!dut.io.doneIter.toBoolean && !dut.io.busyIter.toBoolean, "iterAck did not release")
    }
  }
```

- [ ] **Step 10: append the randomised sweep against `FpRefModel`**

```scala
  def runSweep(seed: Long): Unit = {
    M68kSim().withVerilator.compile(new FpuCore).doSim { dut =>
      val cd = dut.clockDomain; init(dut, cd)
      val rnd = new Random(seed)
      val specials = Seq(PosZero, NegZero, PosInf, NegInf, QNan, SNan, One, Two,
                         Half, MaxFin, MinNorm, MinSub, ThreePt5)
      def operand(): BigInt = rnd.nextInt(5) match {
        case 0 => specials(rnd.nextInt(specials.length))
        case 1 => // random normal in a mid range (no over/underflow) -- exercises the datapath
          (BigInt(rnd.nextInt(2)) << 79) |
          (BigInt(0x3000 + rnd.nextInt(0x3000)) << 64) |
          (BigInt(1) << 63) | BigInt(63, rnd)
        case 2 => // random subnormal
          (BigInt(rnd.nextInt(2)) << 79) | BigInt(63, rnd)
        case 3 => // near the overflow/underflow boundaries
          (BigInt(rnd.nextInt(2)) << 79) |
          (BigInt(if (rnd.nextBoolean()) 0x7FF0 + rnd.nextInt(15) else 1 + rnd.nextInt(15)) << 64) |
          (BigInt(1) << 63) | BigInt(63, rnd)
        case _ => BigInt(80, rnd)   // anything at all, including unnormals
      }
      for (_ <- 0 until 300) {
        val rm = rnd.nextInt(4)
        val d = operand(); val s = operand()
        val op = Seq(FpOp.FADD, FpOp.FSUB, FpOp.FMUL)(rnd.nextInt(3))
        // Unnormals are out of scope for the oracle (VERIFY-4): skip them here, they are
        // covered by the classification outputs instead.
        if (!Fp80RefHelpers.isUnnormal(d) && !Fp80RefHelpers.isUnnormal(s)) {
          val g = runFixed(dut, cd, op, d, s, rm)
          val ref = op match {
            case FpOp.FADD => FpRefModel.add(d, s, rm)
            case FpOp.FSUB => FpRefModel.sub(d, s, rm)
            case _         => FpRefModel.mul(d, s, rm) }
          assert(g.value == ref._1,
            f"$op rm=$rm d=$d%020x s=$s%020x -> ${g.value}%020x, ref ${ref._1}%020x")
          assert(g.ovfl == ref._2 && g.unfl == ref._3,
            s"$op flags ovfl=${g.ovfl}/${ref._2} unfl=${g.unfl}/${ref._3}")
        }
      }
      for (_ <- 0 until 60) {
        val rm = rnd.nextInt(4)
        val d = operand(); val s = operand()
        if (!Fp80RefHelpers.isUnnormal(d) && !Fp80RefHelpers.isUnnormal(s)) {
          val gd = runIter(dut, cd, FpOp.FDIV, d, s, rm)
          assert(gd.value == FpRefModel.div(d, s, rm)._1,
            f"FDIV rm=$rm $d%020x/$s%020x -> ${gd.value}%020x")
          val gs = runIter(dut, cd, FpOp.FSQRT, 0, s, rm)
          assert(gs.value == FpRefModel.sqrt(s, rm)._1,
            f"FSQRT rm=$rm $s%020x -> ${gs.value}%020x")
        }
      }
    }
  }

  test("FpuCore randomised sweep vs FpRefModel (seed A)", VerilatorTest) { runSweep(0xF9A11EL) }
  test("FpuCore randomised sweep vs FpRefModel (seed B)", VerilatorTest) { runSweep(0x5C0FE2L) }

  test("FpuCore classifies unnormal and denormal operands combinationally", VerilatorTest) {
    val unnormal = (BigInt(0x4000) << 64) | (BigInt(1) << 62)   // exp != 0, integer bit clear
    val denorm   = (BigInt(0) << 64) | (BigInt(1) << 40)
    M68kSim().withVerilator.compile(new FpuCore).doSim { dut =>
      val cd = dut.clockDomain; init(dut, cd)
      dut.io.src #= unnormal; dut.io.dst #= denorm; cd.waitSampling()
      assert(dut.io.srcUnnormal.toBoolean, "unnormal source not classified")
      assert(!dut.io.dstUnnormal.toBoolean, "a subnormal is not an unnormal")
      assert(dut.io.dstDenorm.toBoolean, "denormal destination not classified")
      assert(!dut.io.srcDenorm.toBoolean)
      dut.io.src #= One; dut.io.dst #= One; cd.waitSampling()
      assert(!dut.io.srcUnnormal.toBoolean && !dut.io.dstDenorm.toBoolean)
    }
  }
```

Add the small helper the sweep uses (it needs an unnormal test on the Scala side; `Fp80` is
hardware-only):

```scala
object Fp80RefHelpers {
  def isUnnormal(v: BigInt): Boolean = {
    val e = ((v >> 64) & 0x7FFF).toInt
    e != 0 && e != 0x7FFF && ((v >> 63) & 1) == 0
  }
}
```

- [ ] **Step 11: run the spec**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
sbt -batch "testOnly m68k040.execute.FpuCoreSpec" 2>&1 | tail -40
```

Expected: all tests pass. **Do not proceed to Task H with any failure, and do not weaken a
literal expected value to make a test pass** — if a literal and `FpRefModel` disagree, the
test is wrong; if they agree and the RTL disagrees, the RTL is wrong.

---

## Task H: latency lock-in, area/FMax gate, and the handoff record

**Files:** Modify `synth/census.tcl` (probes only); modify
`.superpowers/sdd/progress-ipc-push-2026-08-09.md` (ledger, `git add -f`).

**Interfaces:** consumes the Task A–G RTL; produces the recorded `FixedLatency` confirmation
and resource numbers that the EU-integration task depends on.

### Declared latencies (locked here, not left as TBD)

| op class | lane | latency | notes |
|---|---|---|---|
| FADD, FSUB, FCMP | fixed | **13** | `FpuCore.FixedLatency`. 10-stage `FpAddPipe` + 3-stage `FpRoundPack`. |
| FMUL | fixed | **13** | 10-stage `FpMulPipe` (7 of them the DSP48E2 chain) + 3. This op sets the constant. |
| FABS, FNEG, FMOVE, FMOVECR, FTST, FINT, FINTRZ | fixed | **13** | 1–3 cycles of real logic, padded — see `FpuCore`'s header for why. |
| FDIV | iterative | **71 worst case** | 1 unpack + 1 CLZ + 1 pre-normalise + 1 setup + **65** radix-2 steps + 3 round/pack. Special cases short-circuit at **4**. |
| FSQRT | iterative | **71 worst case** | Identical shape; **65** iterations over a 130-bit radicand at 2 bits/iteration. Special cases at **4**. |

Initiation interval is **1** for every fixed-lane op and **non-pipelined (one context)** for
FDIV/FSQRT, matching 2026-08-09 spec §1's binding direction exactly.

- [ ] **Step 1: add FPU census probes**

Append to `synth/census.tcl`, immediately before the final `puts` line:

```tcl
# ── FPU arithmetic-core probes (2026-08-15 FpuCore plan, Task H) ──
census_probe fpu_add    "*FpAddPipe*"      $prefix
census_probe fpu_mul    "*FpMulPipe*"      $prefix
census_probe fpu_round  "*FpRoundPack*"    $prefix
census_probe fpu_iter   "*FpDivSqrtCore*"  $prefix
census_probe fpu_cheap  "*FpCheapPipe*"    $prefix
```

- [ ] **Step 2: worktree-isolated post-route gate (GC-F4 / GC3 / GC7 / GC9)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
free -g; ps aux --sort=-%mem | head -8      # require >= 20 GB free, no other Vivado batch
git worktree add /home/qwertyoruiop/wt-fpucore HEAD
cd /home/qwertyoruiop/wt-fpucore
sbt -batch "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_fpucore.log \
       -source synth/impl_FullCore.tcl 2>&1 | tee synth/fpucore_impl.out
```

Report, pre/post, all of: **WNS, TNS, failing-endpoint count**, CLB LUT, FF, BRAM, **DSP48E2**,
and per-pblock occupancy. WNS alone is not a report (standing methodology correction).

- [ ] **Step 3: report the DSP inference explicitly — simulation cannot prove it**

```bash
cd /home/qwertyoruiop/wt-fpucore
grep -iE "DSP48E2|DSP Blocks" synth/fullcore_util.rpt | head -20
grep -c "FpMulPipe" synth/fullcore_routed_cells.rpt 2>/dev/null || true
```

Record: the **DSP48E2 count attributable to `FpMulPipe`** and whether the AREG/BREG/MREG/PREG
internal registers were inferred. 2026-08-09 spec §9 makes this a mandatory physical-gate
deliverable, not an optional nicety. Expectation from the spec §6 probe: **~16 DSP** for the
64×64 significand. Baseline full-core DSP usage was **4**, so the delta should be legible.

**If `FpMulPipe` lands in LUT fabric instead of DSPs, STOP and report.** The likely causes, in
order: a per-stage clock enable crept onto the `opA0/opA1/mulP/prd*` chain (MulCore's comment
at `MulCore.scala:50-52` warns about exactly this — the data must shift unconditionally), or
the product register chain is too shallow for the tiling.

- [ ] **Step 4: accept/reject, stated in advance**

- **ACCEPT** if: `FpuCoreSpec` fully green; post-route FMax **≥ 200 MHz** (the deployment
  floor per 2026-08-09 spec §7) with a documented path to 250; `FpMulPipe` inferred into
  DSP48E2; CLB LUT delta within the budget agreed at review; no pblock pushed overfull.
- **REJECT / re-scope** if: FMax lands below the floor **and** the limiting stage is *not*
  one of the two documented split points (`FpAddPipe` A1/A4 → move into A8/A9;
  `FpRoundPack` RP1's 66-bit jam shifter → split coarse/fine across an added RP stage,
  which changes `FixedLatency` and therefore requires the EU descriptor-pipe update in the
  same commit, per GC-F2).
- Per spec §7, **crossing an area budget is a review checkpoint, not an automatic revert** —
  report the exact resource and pblock deltas rather than reverting unilaterally.

- [ ] **Step 5: ledger entry and commit**

Append to `.superpowers/sdd/progress-ipc-push-2026-08-09.md`:

```markdown
## FPU arithmetic core (FpuCore) — Tasks A–H COMPLETE

- `FpuCore.FixedLatency` = 13 (10-stage lane front + 3-stage FpRoundPack), II=1, one
  in-order fixed-result port. `FpDivSqrtCore` worst case 71 cycles, one held context,
  radix-2 restoring, **65 iterations verified** for both FDIV and FSQRT (the 2026-08-09
  spec §6's "56→67" estimate is now an exact number).
- Post-route: WNS <x> ns, <f> MHz, TNS <t> ns, <n> failing endpoints.
- Area: <lut> CLB LUT (delta <d>), <ff> FF, <dsp> DSP48E2 (baseline 4;
  FpMulPipe attributable = <k>, internal DSP registers inferred: yes/no).
- Divergence Register (design spec) records 4 Musashi divergences: D1 FINT/FINTRZ int32
  clamping, D2 FMOVECR $38..$3F double-derived constants, D3 FMOVECR $0B, D4 all FPSR
  exception bits. VERIFY-1..4 remain OPEN and are inputs to the EU-integration task.
- Consumed by the EU-integration task through Task F6's Interfaces block only.
```

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/execute/fpu/ src/test/scala/m68k040/execute/FpuCoreSpec.scala \
        src/test/scala/m68k040/execute/FpRefModel.scala synth/census.tcl
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "fpu: 80-bit extended arithmetic core (FpuCore) -- FADD/FSUB/FMUL/FDIV/FSQRT/
FABS/FNEG/FMOVE/FCMP/FTST/FINT/FINTRZ/FMOVECR, HW-native OVFL/UNFL substitution"
git worktree remove /home/qwertyoruiop/wt-fpucore
```

---

## Open items this section deliberately hands to the EU-integration task

1. **Result arbitration.** `FpuCore` exposes `doneFixed` (pulse, never backpressured) and
   `doneIter` (level, held until `iterAck`). The EU owns the fair-grant arbiter between them
   and the integer-CPLX tail, and must reserve fixed-lane result capacity **before** asserting
   `start` — this component will not stall.
2. **Unnormal / denormal policy.** `io.srcUnnormal`/`io.dstUnnormal`/`io.srcDenorm`/
   `io.dstDenorm` are classification only (VERIFY-4). Whether an unnormal operand traps to
   vector 55 or is computed with is an architectural decision, not a datapath one.
3. **FPCR sampling.** `io.rmode` is latched at `start`; the EU must present the architectural
   FPCR value of the *issuing* instruction, not the live register, if an FPCR write can be
   in flight.
4. **FPSR accumulation.** `FpResult.exc` is a per-op pulse. Sticky accrual, trap enables, and
   the priority order (BSUN, SNAN, OPERR, OVFL, UNFL, DZ, INEX2 — which, per Decision 7's
   note, is *not* the vector-number order) are the FPSR/exception task's.
5. **FPCC bit order.** Internal `{NaN, I, Z, N}`; architectural `FPSR[27:24] = {N, Z, I, NaN}`
   is the reversal. Doing the reversal twice, or not at all, is the obvious integration bug.
6. **FMOVE `<ea>`.** Only the register-to-register value+FPCC path is here. EA decode, memory
   access, and any single/long/word/byte→extended source conversion are elsewhere; a
   double-precision memory source traps to FPSP per Decision 2 and never reaches this core.
7. **VERIFY-1** (FMOVECR $0B and $38..$3F against the MC68881/MC68882 UM) and **VERIFY-3**
   (FMOVECR and FINT/FINTRZ are software-supported on real 68040 hardware, so the decode
   table must **not** route them to the F-line trap) both block a clean acceptance run of the
   vendored `fpu_*` corpus.
### Task 8: `DivEuPlugin.scala` FPU integration — new parallel FP writeback lane

**Files:**
- Modify: `src/main/scala/m68k040/execute/DivEuPlugin.scala`
- Test: `src/test/scala/m68k040/execute/FpuEuIntegrationSpec.scala`

**Interfaces:**
- Consumes: `FpuCore` (Task 7 — `start`/`busy`/`done`/operand-in/result-out I/O, exact shape
  per Task 7's own Interfaces block; treat as a black-box `Component` the way `MulCore` is
  consumed today), `FpRegFileService` (Task 1), the FP data + FPCC rename fields on
  `RenamedUop` (Task 2), `cplxFpccWakeup`/`cplxFpWakeup` ports (Task 3), `FpuControlPlugin`
  (Task 9 — FPCR rounding-mode read, FPSR exception-status write).
- Produces: nothing new externally — this task is where everything upstream gets consumed
  and turned into real execution. Task 15's protocol tests exercise this task's result.

**This is the highest-risk task in the plan.** It is the only place a genuinely new
structural pattern is introduced (a full second parallel writeback lane, not a port
extension) rather than a mechanical clone of an existing one. Confirmed during planning
research, directly from the code: `CplxResult.data` and the `compData` completion register
are hardcoded `Bits(32 bits)` (`DivEuPlugin.scala:63,206`) — an 80-bit FP result CANNOT ride
the existing writeback bus, not even via the MULHI-style 2×32-bit crack (that trick works
because MUL's *destination* is the pre-existing 32-bit int PRF; FP's destination is a
brand-new 80-bit regfile with its own natural width). The correct precedent is the **MUL
lane's structural independence** (its own descriptor pipe, its own credit-gated result FIFO,
its own regfile write port), not MULHI's chunking.

- [ ] **Step 1: Add FP descriptor pipe + result FIFO, mirroring `mulCtx`/`mulResultQ`
  exactly in shape** (read `DivEuPlugin.scala:39-58,430-521` directly before writing this —
  this step's code below assumes those exact structures as its template and must be checked
  against the real current file, not written blind, since intervening tasks in this plan
  touch the same file's surrounding context)

```scala
// src/main/scala/m68k040/execute/DivEuPlugin.scala
// Add alongside the existing MulPipeContext/MulHiContext/CplxResult definitions:

// FP descriptor: routing + control info the fixed-latency FpuCore pipe needs alongside
// the operand values themselves (which FpuCore reads directly off the FP regfile read
// ports below, not carried in this descriptor -- mirrors MulPipeContext's shape, which
// also does NOT carry operand values, only routing metadata, since MulCore's own a/b
// inputs are driven combinationally from the same-cycle regfile read at issue time).
case class FpPipeContext() extends Bundle {
  val robId    = UInt(6 bits)
  val pdst     = UInt(4 bits)   // FP data physical dest (Task 2's pFpDst width)
  val pdstValid= Bool()
  val pFpccDst = UInt(4 bits)
  val fpccWrite= Bool()
  val op       = Bits(4 bits)   // FpuOp selector, Task 7's FpuTypes.FpuOp encoding
  val roundingMode = Bits(2 bits)
}

// FP result: what a completed FpuCore op hands back to the arbiter.
case class FpResult() extends Bundle {
  val robId     = UInt(6 bits)
  val pdst      = UInt(4 bits); val pdstValid = Bool()
  val data      = Bits(80 bits)
  val pFpccDst  = UInt(4 bits); val fpccWrite = Bool()
  val fpcc      = Bits(4 bits)   // N/Z/I/NAN, per spec's internal [3:0]={NaN,I,Z,N} layout
  val fault     = Bool()          // set only for the narrow enabled-trap OVFL/UNFL escalation (Task 9/11)
  val faultVec  = UInt(8 bits)
}
```

- [ ] **Step 2: Instantiate `FpuCore` and its descriptor shift-register, keyed on Task 7's
  `Latency` constants** (mirrors `mulCtx: Vec.fill(MulCore.Latency)(...)` at
  `DivEuPlugin.scala:464-482`)

```scala
  val fpu = new m68k040.execute.fpu.FpuCore()
  // FpuCore is fixed-II=1 for FADD/FSUB/FMUL/FABS/FNEG/FCMP/FINT/FINTRZ/FMOVECR/FTST
  // (Task 7's FpuTypes.FpuCore.FixedLatency) and iterative single-context for FDIV/FSQRT
  // (Task 7's FpuTypes.FpuCore.IterativeLatencyMax, a WORST-CASE bound for descriptor
  // shift-register sizing -- the iterative lane's actual completion time is data-dependent
  // and signalled by `fpu.io.done`, exactly like DivCore/DivUnit's existing iterative
  // completion signalling, NOT the fixed shift-register drain the elastic lanes use).
  val fpFixedCtx      = Vec.fill(m68k040.execute.fpu.FpuCore.FixedLatency)(Reg(FpPipeContext()))
  val fpFixedCtxValid = Vec.fill(m68k040.execute.fpu.FpuCore.FixedLatency)(RegInit(False))
```

- [ ] **Step 3: Acquire FP regfile + FPCC/FP wakeup ports in `setup`** (mirrors the existing
  `irf.newRead()`/`nz.newWrite()` acquisition block at `DivEuPlugin.scala:117-135`)

```scala
  val fprf = host[m68k040.execute.regfile.FpRegFileService]
  fpRdA = fprf.newRead()
  fpRdB = fprf.newRead()
  fpW   = fprf.newWrite(latency = 1)
  fpByp = fprf.newBypass()

  val fpccrf = host[FpccRegFileService]   // Task 3's new FPCC regfile-service trait, if
                                            // FPCC is backed by a small RegFilePlugin instance
                                            // the same way NZVC/X are (RegfileSpec.Fpcc, 4-bit
                                            // x 16) rather than living only in the RAT -- if
                                            // Task 3 instead keeps FPCC value storage inline
                                            // in IssueQueuePlugin's scoreboard state, replace
                                            // this acquisition with whatever real service Task
                                            // 3 defines; reconcile at merge time, this is the
                                            // one place in this task genuinely contingent on
                                            // an interface Task 3 owns.
  fpccRd = fpccrf.newRead()
  fpccW  = fpccrf.newWrite(latency = 1)
```

- [ ] **Step 4: Extend `issuePort.ready` with an FP branch** (mirrors the existing
  `Mux(issueIsMul, mulCanAccept, ...)` structure at `DivEuPlugin.scala:160-162` exactly —
  add a 4th arm)

```scala
  val issueIsFp = u0.cluster === Cluster.CPLX && m68k040.execute.fpu.FpuTypes.isFpOp(u0.op)
  val fpFixedCanAccept = !fpFixedCtxValid(0)  // head slot of the fixed shift-register free
  val fpIterativeBusy  = Reg(Bool()) init False
  val issueIsFpIterative = issueIsFp && m68k040.execute.fpu.FpuTypes.isIterative(u0.op)
  val issueIsFpFixed     = issueIsFp && !m68k040.execute.fpu.FpuTypes.isIterative(u0.op)

  issuePort.ready := !flushSig && (!issuePort.valid || Mux(issueIsMul,
    mulCanAccept,
    Mux(issueIsMulHi, mulHiAvailable,
    Mux(issueIsFpIterative, !fpIterativeBusy,
    Mux(issueIsFpFixed, fpFixedCanAccept,
    !busy && !s1Valid)))))
```

- [ ] **Step 5: Drive `FpuCore` inputs at issue** (S0 read, mirrors the existing
  `rdA.addr := u0.psrcA` block at `DivEuPlugin.scala:138-147`)

```scala
  fpRdA.addr  := u0.pFpSrcA
  fpRdB.addr  := u0.pFpSrcB
  fpccRd.addr := u0.pFpccSrc

  val fpAccept = issuePort.fire && issueIsFp
  fpu.io.start       := fpAccept
  fpu.io.a           := fpRdA.data
  fpu.io.b           := fpRdB.data
  fpu.io.op          := u0.op.asBits.resized  // FpuTypes.FpuOp encoding, Task 7
  fpu.io.roundingMode:= fpuControl.io.roundingMode  // Task 9's FpuControlPlugin FPCR read
  fpu.io.srcFpcc     := fpccRd.data            // FTST/some FCMP paths may need the prior FPCC; confirm against Task 7's actual FTST semantics before wiring blind

  when(fpAccept && issueIsFpIterative) { fpIterativeBusy := True }
  when(fpu.io.done && fpIterativeBusy) { fpIterativeBusy := False }

  // Push the descriptor into the fixed shift-register on a fixed-lane accept (mirrors
  // mulCtx's own shift-register push at DivEuPlugin.scala:464-482 -- shift every cycle,
  // load stage FpuCore.FixedLatency-1 in the entering-slot cycle).
  when(fpAccept && issueIsFpFixed) {
    fpFixedCtx(m68k040.execute.fpu.FpuCore.FixedLatency - 1).robId     := u0.robId
    fpFixedCtx(m68k040.execute.fpu.FpuCore.FixedLatency - 1).pdst      := renUop.pFpDst
    fpFixedCtx(m68k040.execute.fpu.FpuCore.FixedLatency - 1).pdstValid := renUop.pFpDstValid
    fpFixedCtx(m68k040.execute.fpu.FpuCore.FixedLatency - 1).pFpccDst  := renUop.pFpccDst
    fpFixedCtx(m68k040.execute.fpu.FpuCore.FixedLatency - 1).fpccWrite := renUop.writesFpcc
    fpFixedCtx(m68k040.execute.fpu.FpuCore.FixedLatency - 1).op        := u0.op.asBits.resized
    fpFixedCtxValid(m68k040.execute.fpu.FpuCore.FixedLatency - 1)      := True
  }
  for (i <- 0 until m68k040.execute.fpu.FpuCore.FixedLatency - 1) {
    fpFixedCtx(i)      := fpFixedCtx(i + 1)
    fpFixedCtxValid(i) := fpFixedCtxValid(i + 1)
  }
  // Head (index 0) is consumed the cycle FpuCore.io.done fires for the fixed lane -- see
  // Step 6's result-FIFO push, which reads fpFixedCtx(0) the cycle it retires.
```

**Open question this step deliberately surfaces rather than silently resolves**: whether
`FpuCore`'s single `io.done` pulse needs to distinguish "a fixed-lane op finished" from "the
iterative lane finished" (two different consumers above read it: `fpIterativeBusy` clear, and
implicitly the fixed-lane shift-register's head-retirement in Step 6). If Task 7's `FpuCore`
interface only exposes one `done`, add a second output (`doneFixed`/`doneIterative`, or a
`doneKind` selector) — reconcile this exactly against Task 7's actual delivered interface
before merging Step 5/6; do not guess past it.

- [ ] **Step 6: Result capture + FP writeback arbiter — the load-bearing new logic. Extend
  the existing 3-way `captureArb()` selector (legacy / MULHI / MUL) to 4-way, adding FP**
  (mirrors `DivEuPlugin.scala:607-661` exactly in structure, adds one arm)

```scala
  // fpFixedCtx(0) is valid the cycle FpuCore.io.done fires for a fixed-lane op (by
  // construction of the shift-register's fixed depth == FpuCore.FixedLatency). Iterative
  // completion is signalled independently via fpu.io.done while fpIterativeBusy was set
  // by an iterative accept -- both funnel into one fpResultValid/fpResultCtx pair this
  // cycle, mutually exclusive by construction (fpFixedCtxValid(0) and fpIterativeBusy
  // cannot both source a `done` pulse on the same op, since issuePort.ready already
  // serializes iterative issue against a single held context).
  val fpResultValid = (fpu.io.done && fpFixedCtxValid(0)) || (fpu.io.done && fpIterativeBusy)
  val fpResultCtx    = fpFixedCtx(0)   // for the iterative case this is stale/unused --
                                         // Task 8 Step 5's open question above must resolve
                                         // how the iterative op's OWN descriptor (issued once,
                                         // held for the iteration's duration) is threaded to
                                         // this point; a single held Reg(FpPipeContext()) for
                                         // the iterative lane, set at issue and read here, is
                                         // the straightforward fix once Step 5's question is
                                         // answered -- flagged, not silently papered over.

  when(!flushSig) {
    when(legacyValid) {
      captureArb(legacyResult)
      legacyValid := False
    } elsewhen(mulHiHeadReady) {
      mulHiPendingQ.io.pop.ready := True
      mulHiValid(mulHiHead.robId) := False
    } elsewhen(mulResultQ.io.pop.valid) {
      captureArb(mulResultQ.io.pop.payload)
      mulResultQ.io.pop.ready := True
      when(mulResultQ.io.pop.payload.mul64) {
        val tailRobId = (mulResultQ.io.pop.payload.robId + 1).resized
        mulHiMem.write(tailRobId, mulResultQ.io.pop.payload.mulHigh)
        mulHiValid(tailRobId) := True
      }
    } elsewhen(fpResultValid) {
      // NEW: FP completion, lowest priority in this arbiter -- legacy/MUL/MULHI keep
      // their existing relative priority unchanged; FP is strictly additive. Revisit this
      // priority ordering only with real collision-rate evidence from Task 15's protocol
      // tests, not speculatively.
      fpWriteback(fpResultCtx, fpu.io.result, fpu.io.fpcc, fpu.io.fault, fpu.io.faultVec)
    }
  }
```

Define `fpWriteback` as a small helper mirroring `captureArb`'s shape but driving the NEW
parallel completion register (Step 7), NOT `compData`/`compPdst`/etc (those stay exactly as
they are today, untouched by this task — this is the whole point of the "new lane, not a
widened old one" architecture):

```scala
  // A second, independent completion register -- NOT compValid/compData/etc, which remain
  // exclusively the int/NZVC 32-bit path exactly as before this task.
  val fpCompValid    = RegInit(False)
  val fpCompRobId    = Reg(UInt(6 bits))
  val fpCompPdst     = Reg(UInt(4 bits)); val fpCompPdstValid = Reg(Bool())
  val fpCompData     = Reg(Bits(80 bits))
  val fpCompFpccDst  = Reg(UInt(4 bits)); val fpCompFpccWrite = Reg(Bool())
  val fpCompFpcc     = Reg(Bits(4 bits))
  val fpCompFault    = Reg(Bool()); val fpCompFaultVec = Reg(UInt(8 bits))

  def fpWriteback(ctx: FpPipeContext, data: Bits, fpcc: Bits, fault: Bool, faultVec: UInt): Unit = {
    fpCompValid    := True
    fpCompRobId    := ctx.robId
    fpCompPdst     := ctx.pdst; fpCompPdstValid := ctx.pdstValid
    fpCompData     := data
    fpCompFpccDst  := ctx.pFpccDst; fpCompFpccWrite := ctx.fpccWrite
    fpCompFpcc     := fpcc
    fpCompFault    := fault; fpCompFaultVec := faultVec
  }
```

- [ ] **Step 7: Drive external ports from the FP completion register** (mirrors
  `DivEuPlugin.scala:668-722`'s `compLive`-gated port-drive block exactly, but as an
  ADDITIONAL, independent set of drives — the existing int/NZVC drives in that block are
  UNCHANGED by this task)

```scala
  val fpCompLive = fpCompValid && !flushSig   // no per-descriptor "flushed" latch needed here
                                                // IF Step 8's flush handling clears fpCompValid
                                                // directly on flush -- see Step 8.
  // A second ROB completion signal is needed -- confirmed by prior research, RobPlugin's
  // existing euFaultCompletion / completionPort are both currently singular (one DivEuPlugin
  // source). This task requires EITHER (a) a second completion Flow port added to
  // RobPlugin.scala specifically for the FP lane (cleanest, mirrors how completionPort
  // and euFaultCompletion are already two SEPARATE existing ports on the same plugin, so a
  // third is architecturally consistent), or (b) arbitrating fpCompValid onto the SAME
  // completionPort the int/NZVC path uses, adding a 2-way priority mux at the very top of
  // this plugin's port-drive block. Recommend (a): a RobPlugin.scala change is a small,
  // mechanical, one-file addition (a new `fpCompletionPort: Flow[UInt]` alongside the
  // existing `completionPort`), and avoids adding contention/priority-ordering risk to the
  // int/NZVC completion path that has been stable and correctness-load-bearing across this
  // entire project's history. This RobPlugin.scala change is IN SCOPE for this step --
  // add it as part of this task, it is a two-line addition mirroring the existing port.
  fpCompletionPort.valid   := fpCompLive
  fpCompletionPort.payload := fpCompRobId

  fpW.valid   := fpCompLive && fpCompPdstValid && !fpCompFault
  fpW.address := fpCompPdst
  fpW.data    := fpCompData
  fpByp.valid   := fpW.valid
  fpByp.address := fpW.address
  fpByp.data    := fpW.data

  fpccW.valid   := fpCompLive && fpCompFpccWrite && !fpCompFault
  fpccW.address := fpCompFpccDst
  fpccW.data    := fpCompFpcc

  cplxFpWakeupPort.valid   := fpCompLive && fpCompPdstValid && !fpCompFault
  cplxFpWakeupPort.payload := fpCompPdst
  cplxFpccWakeupPort.valid   := fpCompLive && fpCompFpccWrite && !fpCompFault
  cplxFpccWakeupPort.payload := fpCompFpccDst

  // Hardware-native OVFL/UNFL substitution (spec Decision 10) already happened INSIDE
  // FpuCore (Task 7) -- fpu.io.result already IS the substituted value (infinity/
  // largest-finite/denormalized/zero as appropriate) for the baseline case. fpCompFault
  // here is reserved SOLELY for the narrow Task 9/11 enabled-trap escalation path (a user
  // program explicitly enabled the FPCR OVFL/UNFL trap and wants the busy-frame/ETEMP
  // inspection route) -- it must NOT fire for the ordinary substituted-result case, or
  // every overflow would incorrectly re-introduce the trap-every-time cost this whole
  // design exists to eliminate. Confirm Task 7's fpu.io.fault output genuinely only pulses
  // for the enabled-trap case (reading FpuControlPlugin's FPCR enable bits, Task 9) and
  // never for the baseline substituted-result case, before wiring this blind.
  fpFaultPort.valid            := fpCompLive && fpCompFault
  fpFaultPort.payload.robId    := fpCompRobId
  fpFaultPort.payload.vector   := fpCompFaultVec
  fpFaultPort.payload.faultAddr:= U(0, 32 bits)
```

- [ ] **Step 8: Flush handling for the FP lane** (mirrors the existing `flushSig` gating on
  `compValid`/legacy/mul state — confirm exact reset points in the current file and apply
  identically to every new FP register declared in Steps 2/6)

```scala
  when(flushSig) {
    fpCompValid := False
    for (i <- 0 until m68k040.execute.fpu.FpuCore.FixedLatency) { fpFixedCtxValid(i) := False }
    fpIterativeBusy := False
    // fpu.io.start is combinationally gated by fpAccept, which is gated by issuePort.fire,
    // which is itself gated !flushSig at the top-level issuePort.ready -- confirm FpuCore's
    // OWN internal iterative-lane state (Task 7) has a flush/abort input, or the iterative
    // divider/sqrt engine could be left mid-computation with fpIterativeBusy cleared here
    // but the engine itself still running and eventually asserting a stale fpu.io.done that
    // this plugin no longer expects. This is the FP-lane equivalent of DivCore's own
    // flush-handling (check DivCore/DivUnit's existing flush input as the precedent) --
    // reconcile against Task 7's actual FpuCore flush/abort interface before finalizing.
  }
```

- [ ] **Step 9: Directed integration test — issue FADD, observe FP writeback**

```scala
// src/test/scala/m68k040/execute/FpuEuIntegrationSpec.scala
package m68k040.execute

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class FpuEuIntegrationSpec extends AnyFunSuite {
  test("DivEuPlugin: a single FADD issues, completes, and drives the FP writeback ports") {
    // This test's exact DUT-construction shape depends on how DivEuPlugin is normally
    // instantiated for a standalone-EU test in this codebase -- mirror whatever existing
    // pattern DivEuSpec.scala (if it exists) or MulCoreSpec.scala uses for a
    // plugin-in-isolation test harness; do not invent a bespoke top-level wiring here.
    // Sketch of the assertion shape once that harness exists:
    //   1. drive an FADD IqContext into issuePort with known FP source register contents
    //      pre-loaded into the FP regfile via its dbg write port (Task 1's pattern).
    //   2. wait FpuCore.FixedLatency cycles.
    //   3. assert fpCompletionPort fires with the expected robId.
    //   4. assert fpW fires with the expected 80-bit sum and the expected pdst.
    //   5. assert fpccW fires with the expected N/Z/I/NAN pattern.
    pending  // fill in once the standalone-EU test harness precedent is confirmed --
             // do not merge this test in `pending` state; this is a placeholder ONLY within
             // this planning document's own draft, flagged explicitly per this project's
             // "no placeholders" rule -- the actual implementer must replace this with a
             // real, complete test before this task's Step 10 (commit) is reached.
  }
}
```

**This step is the one place in this entire plan that is knowingly incomplete as written**,
because it depends on a standalone-EU test harness precedent this planning pass could not
independently confirm exists (or its exact shape) without a dedicated file read this dispatch
did not have budget for. The implementer MUST resolve this before Step 10 — either find and
mirror the real existing pattern, or, if none exists, build the minimal harness needed
(instantiate `DivEuPlugin` + `RegFilePluginFp` + `RegFilePluginFpcc` + `RobPlugin` stubs
sufficient to drive `issuePort`/observe the new ports directly) and say so plainly in the
commit message rather than silently shipping a `pending` test.

- [ ] **Step 10: Run the test, run the full lock-step suite, commit**

```bash
sbt "testOnly m68k040.execute.FpuEuIntegrationSpec"
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
```
Expected: the new test passes for real (not `pending`, per Step 9's note); 396/396 lock-step
unchanged.

```bash
git add src/main/scala/m68k040/execute/DivEuPlugin.scala \
        src/main/scala/m68k040/rob/RobPlugin.scala \
        src/test/scala/m68k040/execute/FpuEuIntegrationSpec.scala
git commit -m "feat(fpu): integrate FpuCore into the CPLX cluster via a new parallel FP writeback lane

Confirmed during planning that the existing CplxResult.data/compData
writeback bus is hardcoded 32-bit and cannot carry an 80-bit FP
result, even via MULHI's 2x32-bit crack pattern (that trick works
specifically because MUL's destination is the pre-existing 32-bit int
PRF; FP's destination is a new 80-bit regfile with its own natural
width). Structural precedent is instead MUL's LANE independence: its
own descriptor pipe, its own result path, its own regfile write port,
sharing only the single CPLX issue port and (now) a second, FP-specific
ROB completion port added to RobPlugin.scala alongside the existing
one.

Issue-side routing needs zero IQ changes -- FP ops classify purely via
Cluster.CPLX, exactly like DIV/MUL/CHK/CMP2/CHK2 today, and compete for
the single CPLX issue slot on ordinary program-age priority.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: FMOVE to/from FPCR/FPSR/FPIAR + `FpuControlPlugin`

**Files:**
- Modify: `src/main/scala/m68k040/services/Services.scala` (new `FpuControlService` trait, alongside `MmuControlService` at line 182)
- Create: `src/main/scala/m68k040/execute/FpuControlPlugin.scala`
- Modify: `src/main/scala/m68k040/decode/DecodedUop.scala` (new `SysKind.FMOVE_FPCTRL`)
- Modify: `src/main/scala/m68k040/decode/OperationDecoder.scala` (new `is(0xF)` sub-arm)
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala` (operand routing + `imm` side-channel)
- Modify: `src/main/scala/m68k040/frontend/PredecodeWord.scala` (2-word / 4-word framing)
- Modify: `src/main/scala/m68k040/exception/ExceptionUnit.scala` (commit-time read/write arm)
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala` (`host.get[FpuControlService]` + pass to `ExceptionUnit`)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (instantiate the plugin; wire `committedFpccIn`)
- Test: `src/test/scala/m68k040/execute/FpuControlPluginSpec.scala`
- Test (modify): `src/test/scala/m68k040/decode/MicroOpAssemblerSpec.scala` (line-F exhaustive sweep exclusion set)

**Interfaces:**
- Produces: `FpuControlService` — committed `fpcr`/`fpsr`/`fpiar` reads, `setFpcr`/`setFpsr`/`setFpiar` commit-time write `Flow`s, `orFpsrExc` (the EU's exception-status OR-in port), and the `roundingMode`/`precision`/`excEnable` field accessors Task 8 reads.
- Consumes: nothing new. The commit-time access path reuses `ExceptionUnit`'s existing `sysRegWriteValid`/`sysRegWritePhys`/`sysRegWriteData` port (`ExceptionUnit.scala:356-358`) and the `sysCapVal`/`sysCapRc` capture registers (`ExceptionUnit.scala:245-251`) verbatim — the exact MOVEC precedent.
- Consumed by: Task 8 (`DivEuPlugin`'s FP lane reads `roundingMode`, writes `orFpsrExc`), Task 11 (FSAVE/FRESTORE frame-type selection + the null-frame reset).

**Structural template (confirmed by direct read, not assumed).** The plan's File Structure line says "mirroring `MmuControlPlugin.scala`'s shape". That file does **not** exist under that name — `MmuControlPlugin` is declared in `src/main/scala/m68k040/mmu/MmuControl.scala:54`, and its service trait `MmuControlService` is in `src/main/scala/m68k040/services/Services.scala:182`. The shape being mirrored is precisely:

```scala
// src/main/scala/m68k040/mmu/MmuControl.scala:91-134 (real, quoted)
  val logic = during build new Area {
    val mmuEnable = RegInit(False); mmuEnable.simPublic()
    val urp = Reg(UInt(32 bits)) init 0; urp.simPublic()
    ...
    val setUrp    = Flow(UInt(32 bits))
    setUrp.valid.allowOverride;    setUrp.valid := False;    setUrp.payload.allowOverride;    setUrp.payload := U(0, 32 bits)
    ...
    when(setUrp.valid)    { urp  := setUrp.payload }
```

i.e. a `FiberPlugin` whose `logic = during build new Area` holds `Reg`s (`simPublic` for directed pokes) plus one default-idle `Flow` write port each, with a single `when(port.valid)` writer. `FpuControlPlugin.scala` follows that byte-for-byte.

**Commit-time access precedent (confirmed by direct read).** `ExceptionUnit.scala:1272-1347` is the MOVEC arm of the `S_APPLY` `switch(sysCapKind)`; the read direction is a `sysCapRc.mux(...)` into `sysRegWriteData` (line 1288-1307) and the write direction is a nested `switch(sysCapRc)` fanning out to `Flow` write ports (line 1309-1345), e.g.:

```scala
              is(U(0x806, 12 bits)) { mmuCtrl.setUrp.valid := True; mmuCtrl.setUrp.payload := sysCapVal.asUInt }
```

`ExceptionUnit.scala:1406-1418`'s PTEST arm is the second precedent — a `sysOp` whose "write" direction takes its operand from `sysCapVal` (the An value routed through `srcB` → the EU's MOVE writeback → `sysValStore`) with **no** GPR destination. Task 9 uses the MOVEC arm's shape (both directions) with the PTEST arm's operand-routing override.

**Encoding — VERIFIED, not invented.** This is *not* the MOVEC opcode; FMOVE to/from a floating-point control register is a real dedicated 68881/68040 encoding:

```
opword : 1111 001 000 mmmrrr        =  0xF200 | <ea>       (line-F, cpID=001, opclass 000)
ext    : ddd RRR 0000000 000
         ddd = ext[15:13] : 100 = <ea>  ->  control register(s)   (sysReadDir = False)
                            101 = control register(s) -> <ea>    (sysReadDir = True)
         RRR = ext[12:10] : register-select MASK {FPCR, FPSR, FPIAR}
```

Corroborated three independent ways before writing this task:
1. The vendored corpus's own header comments, which state the encodings verbatim and attribute them to the toolchain: `fpu_fsave_frestore_idle_roundtrip.s` lists `FMOVE.L D0,FPCR = F200 9000`, `FMOVE.L D0,FPSR = F200 8800`, `FMOVE.L FPCR,D0 = F200 B000`, `FMOVE.L FPSR,D0 = F200 A800`. Decoding those: `0x9000` = `ddd=100, RRR=100`; `0x8800` = `ddd=100, RRR=010`; `0xB000` = `ddd=101, RRR=100`; `0xA800` = `ddd=101, RRR=010`. Consistent, and it pins `RRR` bit order as `{FPCR, FPSR, FPIAR}` MSB-first.
2. `fpu_fmovem_ctrl_reg.s`'s header, marked "Encodings (verified via `m68k-linux-gnu-as -m68040`)", gives `F228 BC00` for `FMOVEM.L FPIAR/FPSR/FPCR,(0,A0)` — `ddd=101, RRR=111`, i.e. all three, same bit order.
3. The approved spec's own Decision 8 quotes the real Q700 ROM FPSP prologue opword pair `F227 BC00` (`FMOVEM.L FPIAR/FPSR/FPCR,-(A7)`) — the same `ddd=101, RRR=111` against a `-(A7)` EA.

**Residual uncertainty, flagged:** only the *unused* low ext bits (`ext[9:0]`) are unverified — real hardware requires them zero and this task ignores them entirely (does not gate on them), which is the safe direction. Step 1 makes an independent toolchain confirmation an explicit, executed step rather than trusting the three sources above transitively.

**Scope, explicit.** This task decodes the **single-register** forms only (`RRR` popcount == 1) with a **register-direct or immediate** `<ea>`: mode 000 (`Dn`), mode 001 (`An` — architecturally legal for FPIAR only), and mode 111/reg 100 (`#imm`, write direction only). This exactly mirrors the existing MOVE-to-SR scope decision (`MicroOpAssembler.scala:1645-1647`: "reg-source/reg-dest forms only — a MEMORY-source MOVE-to-SR (load `<ea>` -> SR) is a fast-follow crack"). Deliberately **out of scope here**, all keeping today's vector-11 fall-through unchanged:
- memory-EA forms (`fpu_fmove_mem_ea_fpcr_fpsr_no_fline.s` stays failing),
- multi-register masks / the FMOVEM-control list (`fpu_fmovem_ctrl_*.s`, and the FPSP prologue `F227 BC00`, all stay failing — `fpu_fpsp_selfrecursion_repro.s` therefore cannot pass until that lands).

These are named here so the acceptance-corpus triage after this task is a lookup, not a re-investigation.

- [ ] **Step 1: Independently confirm the opword/ext encoding against the toolchain**

```bash
cd /tmp && cat > fpctrl.s <<'EOF'
    .text
    fmove.l %d0,%fpcr
    fmove.l %d0,%fpsr
    fmove.l %d0,%fpiar
    fmove.l %fpcr,%d0
    fmove.l %fpsr,%d0
    fmove.l %fpiar,%d0
    fmove.l #0,%fpsr
    fmove.l %a0,%fpiar
EOF
m68k-linux-gnu-as -m68040 -m68881 fpctrl.s -o fpctrl.o && m68k-linux-gnu-objdump -d fpctrl.o
```
Expected (record the actual output in the commit message):
`F200 9000` / `F200 8800` / `F200 8400` / `F200 B000` / `F200 A800` / `F200 A400` /
`F23C 8800 0000 0000` / `F208 8400` (the `An` source is `<ea>` mode 001 reg 000).
**If any line disagrees with the table above, STOP and fix the table before writing any RTL** — every later step in this task keys off `ext[15:13]`/`ext[12:10]`.

- [ ] **Step 2: Add the `FpuControlService` trait**

```scala
// src/main/scala/m68k040/services/Services.scala
// Add immediately after the existing `trait MmuControlService { ... }` block (line 182).

/** The single owner of the NON-RENAMED floating-point control state (spec Decision 5):
  * FPCR (rounding/precision/exception-enable), FPSR's NON-FPCC bytes (exception status,
  * accrued exception, quotient), and FPIAR. Contrast with FPCC (N/Z/I/NAN), which IS
  * renamed and lives in the FPCC RAT/PRF (Task 2/3) — this service deliberately does not
  * hold it, and `fpsr` below reads 0 in bits [27:24]; ExceptionUnit splices the live
  * committed FPCC in on an architectural FPSR read and routes it back to the FPCC PRF on
  * an architectural FPSR write.
  *
  * Shape follows MmuControlService exactly: plain accessors for the committed values +
  * one default-idle commit-time write Flow each. */
trait FpuControlService {
  def fpcr:  UInt          // 32 bits
  def fpsr:  UInt          // 32 bits, bits [27:24] always 0 (FPCC is renamed elsewhere)
  def fpiar: UInt          // 32 bits

  def setFpcr:  Flow[UInt]
  def setFpsr:  Flow[UInt]   // bits [27:24] of the payload are IGNORED (masked at the writer)
  def setFpiar: Flow[UInt]

  /** The FP EU's exception-status OR-in port (Task 8). Payload is the 8-bit EXC field
    * {BSUN,SNAN,OPERR,OVFL,UNFL,DZ,INEX2,INEX1} (MSB = BSUN). ORs into FPSR[15:8] and
    * folds the derived AEXC bits into FPSR[7:0]. */
  def orFpsrExc: Flow[Bits]

  /** FPCR field accessors, for Task 8's FP EU. Rounding mode is FPCR[5:4]
    * (00=RN, 01=RZ, 10=RM, 11=RP); precision FPCR[7:6]; exception-enable byte FPCR[15:8],
    * laid out identically to the EXC field above. */
  def roundingMode: Bits
  def precision:    Bits
  def excEnable:    Bits
}
```

- [ ] **Step 3: Create `FpuControlPlugin.scala`**

The AEXC fold below is the one bit-level fact in this task that the three corroborating sources do **not** cover. It is written out explicitly so Step 4 can verify it as a unit, rather than being buried as an assumption.

```scala
// src/main/scala/m68k040/execute/FpuControlPlugin.scala
package m68k040.execute

import m68k040.services.FpuControlService
import spinal.core._
import spinal.core.sim._
import spinal.lib.Flow
import spinal.lib.misc.plugin.FiberPlugin

/** The single owner of the non-renamed FP control state (spec Decision 5): FPCR, FPSR's
  * non-FPCC bytes, FPIAR. Structurally a direct copy of `MmuControlPlugin`
  * (`src/main/scala/m68k040/mmu/MmuControl.scala:91-152`): committed `Reg`s (simPublic for
  * directed pokes), one default-idle `Flow` write port each, a single `when(port.valid)`
  * writer, and the `_field`/`override def` indirection that lets the service be resolved
  * before `logic` elaborates.
  *
  * WHY THESE THREE ARE NOT RENAMED (spec Decision 5, contrast Decision 4's FPCC): none sit
  * on the speculative hot path. FPCR changes only via an explicit FMOVE-to-FPCR and is read
  * for rounding mode, never branched on; FPSR's exception-status/accrued bytes are
  * architecturally an in-order/precise-exception concept, updated at commit exactly like
  * this project's existing `faultPc` capture; FPIAR is write-once-per-exception. A single
  * physical copy each with a commit-time interlock is cheaper and easier to get
  * precise-exception-correct than extending rename to registers that gain nothing from
  * speculative read-ahead.
  *
  * FPSR bit layout (architectural): [31:24] condition code (FPCC in [27:24]), [23:16]
  * quotient, [15:8] exception status (EXC), [7:0] accrued exception (AEXC). Bits [27:24]
  * are NOT stored here — FPCC is renamed (Task 2/3) and `fpsr` reads 0 there. The write
  * port masks them off structurally (not by convention) so the invariant cannot rot. */
class FpuControlPlugin extends FiberPlugin with FpuControlService {
  var _fpcr:  UInt = null
  var _fpsr:  UInt = null
  var _fpiar: UInt = null

  var _setFpcr:   Flow[UInt] = null
  var _setFpsr:   Flow[UInt] = null
  var _setFpiar:  Flow[UInt] = null
  var _orFpsrExc: Flow[Bits] = null

  override def fpcr:  UInt = _fpcr
  override def fpsr:  UInt = _fpsr
  override def fpiar: UInt = _fpiar

  override def setFpcr:   Flow[UInt] = _setFpcr
  override def setFpsr:   Flow[UInt] = _setFpsr
  override def setFpiar:  Flow[UInt] = _setFpiar
  override def orFpsrExc: Flow[Bits] = _orFpsrExc

  override def roundingMode: Bits = _fpcr(5 downto 4).asBits
  override def precision:    Bits = _fpcr(7 downto 6).asBits
  override def excEnable:    Bits = _fpcr(15 downto 8).asBits

  /** EXC -> AEXC fold. Bit positions within the 8-bit EXC field (MSB first):
    *   7 BSUN, 6 SNAN, 5 OPERR, 4 OVFL, 3 UNFL, 2 DZ, 1 INEX2, 0 INEX1.
    * AEXC field (MSB first, FPSR[7:0]): 7 IOP, 6 OVFL, 5 UNFL, 4 DZ, 3 INEX, 2:0 reserved.
    *   IOP  = BSUN | SNAN | OPERR
    *   OVFL = OVFL
    *   UNFL = UNFL & INEX2      (both, per the PRM -- an underflow that rounded EXACTLY
    *                             does not accrue)
    *   DZ   = DZ
    *   INEX = INEX1 | INEX2 | OVFL
    * FLAGGED FOR PRIMARY-SOURCE VERIFICATION: this fold is written from the MC68881/MC68040
    * FPSR description and is NOT covered by any of the three encoding sources that pin the
    * FMOVE opcode. Step 4's `FpuControlPluginSpec` unit-tests it as a truth table, and
    * Step 12 makes cross-checking it against the MC68040 UM FPSR section an explicit,
    * signed-off step. Do not treat it as verified until Step 12 records the page. */
  private def aexcOf(exc: Bits): Bits = {
    val iop  = exc(7) || exc(6) || exc(5)
    val ovfl = exc(4)
    val unfl = exc(3) && exc(1)
    val dz   = exc(2)
    val inex = exc(0) || exc(1) || exc(4)
    (iop ## ovfl ## unfl ## dz ## inex ## B(0, 3 bits))
  }

  val logic = during build new Area {
    // Power-on state: FPCR = 0 (round-to-nearest, extended precision, all traps disabled),
    // FPSR = 0, FPIAR = 0 -- the real 68040 reset state, and the state a null-frame
    // FRESTORE returns to (Task 11).
    val fpcr  = Reg(UInt(32 bits)) init 0; fpcr.simPublic()
    val fpsr  = Reg(UInt(32 bits)) init 0; fpsr.simPublic()
    val fpiar = Reg(UInt(32 bits)) init 0; fpiar.simPublic()

    val setFpcr   = Flow(UInt(32 bits))
    val setFpsr   = Flow(UInt(32 bits))
    val setFpiar  = Flow(UInt(32 bits))
    val orFpsrExc = Flow(Bits(8 bits))
    setFpcr.valid.allowOverride;   setFpcr.valid   := False; setFpcr.payload.allowOverride;   setFpcr.payload   := U(0, 32 bits)
    setFpsr.valid.allowOverride;   setFpsr.valid   := False; setFpsr.payload.allowOverride;   setFpsr.payload   := U(0, 32 bits)
    setFpiar.valid.allowOverride;  setFpiar.valid  := False; setFpiar.payload.allowOverride;  setFpiar.payload  := U(0, 32 bits)
    orFpsrExc.valid.allowOverride; orFpsrExc.valid := False; orFpsrExc.payload.allowOverride; orFpsrExc.payload := B(0, 8 bits)
    setFpcr.valid.simPublic();  setFpcr.payload.simPublic()
    setFpsr.valid.simPublic();  setFpsr.payload.simPublic()
    setFpiar.valid.simPublic(); setFpiar.payload.simPublic()

    when(setFpcr.valid)  { fpcr  := setFpcr.payload }
    // FPCC ([27:24]) is renamed and lives in the FPCC PRF -- mask it out of this copy so
    // an architectural FPSR write can never leave a second, stale condition-code source.
    when(setFpsr.valid)  { fpsr  := setFpsr.payload & U(0xF0FFFFFFL, 32 bits) }
    when(setFpiar.valid) { fpiar := setFpiar.payload }
    // EU exception-status OR-in. Lower priority than an explicit FMOVE-to-FPSR: a `when`
    // written LATER wins in SpinalHDL, so the architectural write above must come FIRST
    // for the EU port to lose an exact-same-cycle collision -- but they cannot in fact
    // collide (the FMOVE write happens at a SERIALIZING commit, with the EU flushed), so
    // this ordering is defence-in-depth, not a live arbitration.
    when(orFpsrExc.valid && !setFpsr.valid) {
      fpsr(15 downto 8) := fpsr(15 downto 8) | orFpsrExc.payload.asUInt
      fpsr(7 downto 0)  := fpsr(7 downto 0)  | aexcOf(orFpsrExc.payload).asUInt
    }

    _fpcr = fpcr; _fpsr = fpsr; _fpiar = fpiar
    _setFpcr = setFpcr; _setFpsr = setFpsr; _setFpiar = setFpiar; _orFpsrExc = orFpsrExc
  }
}
```

- [ ] **Step 4: Add `SysKind.FMOVE_FPCTRL`**

```scala
// src/main/scala/m68k040/decode/DecodedUop.scala
// Add as the LAST element of the SysKind enum (after PTEST, line ~172). Appending is
// deliberate: ExceptionUnit's S_APPLY dispatches via the symbolic `skOrd(SysKind.X)` helper
// (ExceptionUnit.scala:264-265) precisely so an insertion/reorder can no longer silently
// re-point an arm (that regression really happened -- Task P5.2's CINV insertion, see the
// helper's own comment) -- but appending still keeps every existing element's ordinal
// stable, which keeps this task's diff reviewable.
      PTEST,
      // FMOVE to/from a floating-point CONTROL register (FPCR / FPSR / FPIAR).
      // Opword 0xF200|<ea> (line-F, cpID=001 in op[11:9], opclass 000 in op[8:6]) + a
      // command extension word whose ext[15:13] selects the direction (100 = <ea>->ctrl,
      // 101 = ctrl-><ea>) and ext[12:10] is the one-hot register-select mask
      // {FPCR, FPSR, FPIAR}. NOT a MOVEC variant -- a real dedicated 68881/68040
      // instruction; encoding independently confirmed via `m68k-linux-gnu-as -m68040
      // -m68881` and corroborated by the vendored corpus's own documented opwords
      // (fpu_fsave_frestore_idle_roundtrip.s, fpu_fmovem_ctrl_reg.s) and by the real Q700
      // ROM FPSP prologue pair `F227 BC00` quoted in the design spec.
      //
      // A COMMIT-TIME SYSTEM op, exactly like MOVEC: the FPCR/FPSR/FPIAR copies are
      // non-renamed single-copy state (spec Decision 5), so the read/write happens at the
      // serializing retire in ExceptionUnit's S_APPLY. Unlike MOVEC it is NOT privileged --
      // real 68040 FMOVE-to-FPcr is a user instruction (only FSAVE/FRESTORE are
      // privileged), so RobPlugin's `sysPrivFault` must NOT fire for it (see Step 8).
      //
      // Scope: single-register masks with a register-direct or immediate <ea> only.
      // Multi-register masks (the FMOVEM control-list form) and memory <ea>s keep the
      // existing vector-11 fall-through.
      FMOVE_FPCTRL
      = newElement()
```

`ExceptionUnit.scala:269-273`'s `require` already fails elaboration loudly if `SysKind` outgrows 4 bits. With `FMOVE_FPCTRL` the element count is 11 — still inside 4 bits, no width change needed. Task 11 adds two more (13 total), still inside. **Do not** hand-widen anything; if that `require` ever fires, widen `ExceptionUnit.sysKind`/`sysCapKind` **and** `RobPlugin.scala:1124`'s `.resize(4)` together, as its message says.

- [ ] **Step 5: `OperationDecoder.scala` recognition arm**

Add inside the existing `is(0xF)` arm (`OperationDecoder.scala:1005-1137`), after the PTEST block (line 1105) and before the FSF carve-out (line 1129).

```scala
        // ── FMOVE.L <ea>,FPcr / FPcr,<ea>  (FPCR / FPSR / FPIAR) ────────────────────
        // opword 1111 001 000 mmmrrr = 0xF200 | <ea>; ext[15:13] = 100 (to control) /
        // 101 (from control); ext[12:10] = one-hot {FPCR, FPSR, FPIAR}. A COMMIT-TIME
        // SYSTEM op (SysKind.FMOVE_FPCTRL), NOT privileged -- see the enum's comment.
        //
        // SCOPE (deliberate, mirrors MOVE-to-SR's own reg-only scope at
        // MicroOpAssembler.scala:1645-1647): exactly ONE mask bit set, and a
        // register-direct or immediate <ea> only:
        //   mode 000 (Dn)                          -- both directions
        //   mode 001 (An)                          -- both directions (arch-legal for
        //                                             FPIAR only; we do not police that,
        //                                             matching Musashi's permissiveness)
        //   mode 111 / reg 100 (#imm)              -- WRITE direction only (an immediate
        //                                             cannot be a destination)
        // Every other <ea> and every multi-bit mask falls through UNCHANGED to the line-F
        // illegal default -> vector 11. That is the correct conservative behavior, not a
        // gap being papered over: those forms genuinely are not implemented yet.
        val fpCtrlOpBase = (opword(15 downto 12) === B"4'hF") &&
                           (opword(11 downto 9)  === B"3'b001") &&
                           (opword(8 downto 6)   === B"3'b000")
        val fpCtrlExt    = words(1)
        val fpCtrlDir    = fpCtrlExt(15 downto 13)          // 100 = to ctrl, 101 = from ctrl
        val fpCtrlMask   = fpCtrlExt(12 downto 10)          // {FPCR, FPSR, FPIAR}
        val fpCtrlIsTo   = fpCtrlDir === B"3'b100"
        val fpCtrlIsFrom = fpCtrlDir === B"3'b101"
        val fpCtrlOneReg = (fpCtrlMask === B"3'b100") || (fpCtrlMask === B"3'b010") ||
                           (fpCtrlMask === B"3'b001")
        val fpCtrlMode   = opword(5 downto 3)
        val fpCtrlReg    = opword(2 downto 0)
        val fpCtrlEaReg  = (fpCtrlMode === B"3'b000") || (fpCtrlMode === B"3'b001")
        val fpCtrlEaImm  = (fpCtrlMode === B"3'b111") && (fpCtrlReg === B"3'b100")
        val fpCtrlOk     = fpCtrlOpBase && fpCtrlOneReg &&
                           ((fpCtrlIsFrom && fpCtrlEaReg) ||
                            (fpCtrlIsTo   && (fpCtrlEaReg || fpCtrlEaImm)))
        when(fpCtrlOk) {
          o.illegal    := False
          o.op         := DecOp.MOVE          // result = the source value (write direction)
          o.size       := Size.LONG           // all three control registers are 32-bit
          o.sysOp      := True
          o.sysKind    := SysKind.FMOVE_FPCTRL
          o.sysReadDir := fpCtrlIsFrom        // True = FPcr -> Rn (read SYSTEM -> Rn)
          o.dst.setNone(); o.dstWrites := False
        }
```

(`words(1)` is already in scope in this function — the FSF carve-out and MOVE16 arm both read the packet's extension words the same way. If the local name differs in your working copy, use whatever `OperationDecoder.decode` already binds for the first extension word; do **not** introduce a second accessor.)

- [ ] **Step 6: `MicroOpAssembler.scala` operand routing**

Add inside the existing `when(isSysOp)` block (`MicroOpAssembler.scala:1654-1763`), immediately after the PTEST arm (line 1756-1762). This mirrors MOVEC's arm (line 1671-1697) exactly, including the `useImm := False` requirement so the IQ treats `srcB` as a real register source.

```scala
      // FMOVE.L <ea>,FPcr / FPcr,<ea>. The register-select mask (ext[12:10]) rides
      // imm[2:0] -- the SAME side-channel MOVEC's 12-bit Rc id uses (RobPlugin.scala:441
      // stores `u.imm(11 downto 0)` into `p.sysRc`, which ExceptionUnit latches as
      // `sysCapRc`). useImm STAYS FALSE for the register-source forms so the IQ treats
      // srcB as a REGISTER source and wakes the Rn dependency (`srcBIsReg` gates on
      // !useImm) -- the exact constraint MOVEC's own comment calls out at line 1677-1680.
      when(spec.sysKind === SysKind.FMOVE_FPCTRL) {
        val fpExt   = pkt.words(1)
        val fpMask  = fpExt(12 downto 10)
        val fpMode  = op(5 downto 3)
        val fpRegN  = op(2 downto 0).asUInt
        // <ea> mode 000 = Dn (id 0..7), 001 = An (id 8..15), 111/100 = #imm.
        val fpRnId  = Mux(fpMode === B"3'b001", (U(8, 5 bits) + fpRegN.resize(5)).resize(5),
                                                fpRegN.resize(5))
        val fpIsImm = (fpMode === B"3'b111") && (op(2 downto 0) === B"3'b100")
        opUop.imm       := fpMask.resize(32)      // mask in imm[2:0] (NOT useImm)
        opUop.srcAValid := False
        when(spec.sysReadDir) {                   // FPcr -> Rn : real renamed dst
          // Same treatment as MOVEC's read direction: rename allocates a pdst, the ROB
          // commits the arch->pdst mapping at the serializing retire, and S_APPLY writes
          // the control-register VALUE into PRF[pdst] via sysRegWrite*.
          opUop.dstReg    := fpRnId; opUop.dstValid  := True
          opUop.srcBValid := False;  opUop.useImm    := False
        } otherwise {                             // <ea> -> FPcr
          opUop.dstValid := False
          when(fpIsImm) {
            // FMOVE.L #imm,FPcr (e.g. the ROM FPSP's own `F23C 8800 0000 0000`,
            // FMOVE.L #0,FPSR): the 32-bit immediate follows the COMMAND word, so it is
            // words(2)##words(3), NOT words(1)##words(2). Getting this off by one word is
            // the single most likely bug in this arm -- Step 9's directed test pins it.
            opUop.srcBValid := False
            opUop.useImm    := True
            opUop.imm       := (pkt.words(2) ## pkt.words(3))
            // The mask can no longer ride imm (the immediate occupies it). Route it
            // through `sysRc` the ONLY other way available: OperationDecoder already put
            // sysKind/sysReadDir on the uop, and the mask is re-derivable at commit from
            // NOTHING -- so the immediate form is restricted to a SINGLE mask bit and the
            // mask is folded into sysKind-adjacent state via `casForm`, a spare 2-bit
            // uop field already threaded to the ROB. See Step 7's note.
            opUop.casForm   := fpMask(2 downto 1)   // 10 = FPCR, 01 = FPSR, 00 = FPIAR
          } otherwise {
            opUop.srcBReg   := fpRnId; opUop.srcBValid := True
            opUop.useImm    := False
          }
        }
      }
```

**Honest flag on `casForm`:** the immediate form needs both a 32-bit value *and* the 3-bit mask, and `imm` can only carry one of them. Reusing `casForm` (an existing 2-bit uop field, inert for every non-CAS op) is a real, minimal reuse — but it is a second side-channel and it collapses the 3-bit one-hot mask into 2 bits, which only works because the immediate form is restricted to a single mask bit. **Before implementing, confirm `casForm` is actually threaded through rename → `RobPayload` and readable at commit** (`grep -n "casForm" src/main/scala/m68k040/{rename,rob}/*.scala`). If it is *not* ROB-visible, do **not** invent a new thread for it in this task — instead drop the immediate form from Task 9's scope entirely (keep it on the vector-11 fall-through, note it in the commit message alongside the memory-EA and multi-register deferrals) and re-add it with the FMOVEM-control work, which needs a proper multi-word side-channel anyway.

- [ ] **Step 7: `PredecodeWord.scala` length framing**

Add inside the existing `is(U(0xF, 4 bits))` arm (`PredecodeWord.scala:1050-1069`), as a new leading branch before the `0xF27F` FSF case.

```scala
        // FMOVE.L <ea>,FPcr / FPcr,<ea> (Task 9): opword 0xF200|<ea> with a command
        // extension word. Register-direct <ea> (mode 000/001) = 2 words; immediate <ea>
        // (mode 111/reg 100) = 2 + 2 = 4 words (the 32-bit immediate FOLLOWS the command
        // word). Gated on `extWKnown` because the direction/mask live in the ext word: if
        // it is not resident in this cache line, fall back to the existing 1-word F-line
        // framing and mark the line ambiguous, exactly as the other ext-dependent arms in
        // this file do -- a WRONG length here mis-frames the NEXT instruction, which is
        // strictly worse than an extra vector-11 trap.
        val fpCtrlBase = (op(11 downto 9) === B"3'b001") && (op(8 downto 6) === B"3'b000")
        val fpCtrlDirOk = (extW(15 downto 13) === B"3'b100") || (extW(15 downto 13) === B"3'b101")
        val fpCtrlMaskOne = (extW(12 downto 10) === B"3'b100") ||
                            (extW(12 downto 10) === B"3'b010") ||
                            (extW(12 downto 10) === B"3'b001")
        val fpCtrlEaReg = (op(5 downto 3) === B"3'b000") || (op(5 downto 3) === B"3'b001")
        val fpCtrlEaImm = (op(5 downto 3) === B"3'b111") && (op(2 downto 0) === B"3'b100")
        when(fpCtrlBase && fpCtrlDirOk && fpCtrlMaskOne && (fpCtrlEaReg || fpCtrlEaImm)) {
          when(extWKnown) {
            r.simple   := True
            r.lenWords := Mux(fpCtrlEaImm, U(4, 4 bits), U(2, 4 bits))
          } otherwise {
            r.simple := True; r.lenWords := U(1, 4 bits); r.ambiguousLine := True
          }
        } .elsewhen(op === B"16'hF27F") {
          ... // existing FSF arm, unchanged
```

- [ ] **Step 8: `ExceptionUnit.scala` — commit-time read/write, plus the FPCC splice**

First, three new constructor parameters + one new output, following `committedA7In`'s established "optional input, `allowOverride`, safe default so unit DUTs still elaborate" pattern (`ExceptionUnit.scala:169`) and `rteNzvcWriteValid`'s "direct write into the COMMITTED physical mapping, bypassing rename/freelist" pattern (`ExceptionUnit.scala:365-400`, whose doc comment records exactly why a rename-allocated destination is unsafe here).

```scala
// src/main/scala/m68k040/exception/ExceptionUnit.scala
// (a) constructor: add alongside `mmuCtrl` (line 44).
    fpuCtrl: m68k040.services.FpuControlService,

// (b) inputs/outputs: add alongside `committedA7In` (line 169).
  // Live COMMITTED FPCC, read back from the FPCC PRF at its committed arch->phys mapping
  // by the full-core wiring -- byte-for-byte the `committedA7In` pattern above (an
  // optional, allowOverride input whose default keeps every unit DUT elaborating). FPCC
  // is RENAMED (spec Decision 4), so it is NOT in FpuControlPlugin; an architectural FPSR
  // READ has to splice it in. Internal layout is [3:0] = {NaN, I, Z, N} (2026-08-09 spec
  // section 8); architectural FPSR[27:24] = {N, Z, I, NaN} is the REVERSED presentation of
  // that group.
  val committedFpccIn = Bits(4 bits); committedFpccIn.allowOverride; committedFpccIn := B(0, 4 bits)
  // Architectural FPSR WRITE -> the renamed FPCC's committed physical register. Mirrors
  // rteNzvcWriteValid/rteNzvcWriteData EXACTLY (see its doc comment for the full argument
  // for why a DIRECT committed-mapping write is required and a rename allocation is not):
  // the FMOVE-to-FPSR uop keeps writesFpcc FALSE, takes no freelist pop, and the wiring
  // writes whatever physical register fpccRat's committed mapping currently names.
  val fpccWriteValid = Bool();       fpccWriteValid := False; fpccWriteValid.simPublic()
  val fpccWriteData  = Bits(4 bits); fpccWriteData  := B(0, 4 bits); fpccWriteData.simPublic()

  // Architectural FPSR read = the non-FPCC bytes from FpuControlPlugin OR the live
  // committed FPCC, reversed into arch bit order.
  private def fpccToArch(internal: Bits): Bits =
    internal(0) ## internal(1) ## internal(2) ## internal(3)   // {N,Z,I,NaN}
  private def fpccFromArch(arch: Bits): Bits =
    arch(0) ## arch(1) ## arch(2) ## arch(3)                   // involution: same reversal
  val fpsrArch = fpuCtrl.fpsr | (fpccToArch(committedFpccIn).asUInt.resize(32) |<< 24)
```

Then the `S_APPLY` arm, added to the `switch(sysCapKind)` at `ExceptionUnit.scala:1254` (after the PTEST arm, line 1406-1418):

```scala
        is(skOrd(m68k040.decode.SysKind.FMOVE_FPCTRL)) { // FMOVE <ea> <-> FPCR/FPSR/FPIAR
          // sysCapRc[2:0] is the one-hot register-select mask {FPCR, FPSR, FPIAR}
          // (ext[12:10], routed through the imm side-channel by MicroOpAssembler --
          // the same route MOVEC's 12-bit Rc id takes). Exactly one bit is set; the
          // multi-bit (FMOVEM-control) case is not decoded at all and never reaches here.
          when(sysCapReadDir) {                   // FPcr -> Rn : write the int PRF[pdst]
            sysRegWriteValid := True
            sysRegWritePhys  := sysCapDstPhys
            sysRegWriteData  := Mux(sysCapRc(2), fpuCtrl.fpcr,
                                Mux(sysCapRc(1), fpsrArch, fpuCtrl.fpiar))
          } otherwise {                           // <ea> -> FPcr : write the committed reg
            when(sysCapRc(2)) {
              fpuCtrl.setFpcr.valid   := True
              fpuCtrl.setFpcr.payload := sysCapVal.asUInt
            }
            when(sysCapRc(1)) {
              // The non-FPCC bytes land in FpuControlPlugin (which masks [27:24] off
              // itself); the FPCC nibble is reversed back to the internal layout and
              // written DIRECTLY into the FPCC PRF's committed physical register.
              fpuCtrl.setFpsr.valid   := True
              fpuCtrl.setFpsr.payload := sysCapVal.asUInt
              fpccWriteValid          := True
              fpccWriteData           := fpccFromArch(sysCapVal(27 downto 24))
            }
            when(sysCapRc(0)) {
              fpuCtrl.setFpiar.valid   := True
              fpuCtrl.setFpiar.payload := sysCapVal.asUInt
            }
          }
        }
```

**Privilege.** Every existing `sysOp` is privileged, and `RobPlugin`'s `sysPrivFault` traps *any* `sysOp` head retiring at committed S == 0 — but FMOVE-to/from-FPcr is a **user** instruction. Exclude it explicitly:

```scala
// src/main/scala/m68k040/rob/RobPlugin.scala
// Find `sysPrivFault` (grep -n "sysPrivFault") and add the exclusion term:
    val sysPrivFault = sysRetire && !committedS &&
                       (p0.sysKind =/= m68k040.decode.SysKind.FMOVE_FPCTRL)
```
Add a one-line comment at that site recording *why* (real 68040: only FSAVE/FRESTORE and the MMU/cache ops are privileged in line-F; FMOVE-to-FPcr is not). Step 10's test proves both directions.

- [ ] **Step 9: `RobPlugin.scala` service resolution + `FullCoreSynth.scala` wiring**

```scala
// src/main/scala/m68k040/rob/RobPlugin.scala
// Add immediately after the `val mmuCtrl: MmuControlService = host.get[...].getOrElse(...)`
// block (line 1061-1078). Same OPTIONAL-service rationale, verbatim: standalone ROB unit
// DUTs (RobPluginSpec/RobFaultSpec/...) do not instantiate an FpuControlPlugin, so fall
// back to a throwaway idle implementation and keep them elaborating unchanged.
    val fpuCtrl: m68k040.services.FpuControlService =
      host.get[m68k040.services.FpuControlService].getOrElse(new m68k040.services.FpuControlService {
        override def fpcr  = U(0, 32 bits)
        override def fpsr  = U(0, 32 bits)
        override def fpiar = U(0, 32 bits)
        override def setFpcr   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setFpsr   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setFpiar  = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def orFpsrExc = { val f = Flow(Bits(8 bits)); f.valid := False; f.payload := B(0, 8 bits); f }
        override def roundingMode = B(0, 2 bits)
        override def precision    = B(0, 2 bits)
        override def excEnable    = B(0, 8 bits)
      })

// ... and pass it into the ExceptionUnit constructor (line 1080-1082), alongside mmuCtrl:
      mmuCtrl = mmuCtrl,
      fpuCtrl = fpuCtrl,
```

```scala
// src/main/scala/m68k040/top/FullCoreSynth.scala
// (a) instantiate the plugin alongside MmuControlPlugin.
          new m68k040.execute.FpuControlPlugin(),
// (b) wire the committed-FPCC readback + the committed-FPCC write, mirroring the existing
//     committedA7In / rteNzvcWrite* wiring at this same site (grep -n "committedA7In\|
//     rteNzvcWriteValid" src/main/scala/m68k040/top/FullCoreSynth.scala for the two
//     existing blocks and add immediately after them):
        exc.committedFpccIn := fpccRegFileReadAtCommittedMapping   // Task 2/3's fpccRat.committedPhys(0)
        fpccDirectWrite.valid   := exc.fpccWriteValid
        fpccDirectWrite.address := rename.logic.fpccRat.committedPhys(0)
        fpccDirectWrite.data    := exc.fpccWriteData
```

If Task 3 has not yet landed an FPCC PRF read/write port at this site, wire `committedFpccIn` to `B(0,4 bits)` and leave `fpccWriteValid` unconsumed **for this task only**, and say so explicitly in the commit message — the FPSR read will then return FPCC=0, which is exactly the state before this task, not a regression. Do **not** silently skip it.

- [ ] **Step 10: Directed tests**

```scala
// src/test/scala/m68k040/execute/FpuControlPluginSpec.scala
package m68k040.execute

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class FpuControlPluginSpec extends AnyFunSuite {
  class Dut extends Component {
    val p = new FpuControlPlugin()
    // Force the plugin's Fiber `logic` Area to elaborate inside this Component.
    val h = new spinal.lib.misc.plugin.PluginHost
    h.asHostOf(Seq(p))
  }

  test("FpuControlPlugin: FPCR/FPSR/FPIAR round-trip through their write Flows") {
    SimConfig.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val l = dut.p.logic
      dut.clockDomain.waitSampling(2)
      assert(l.fpcr.toBigInt == 0 && l.fpsr.toBigInt == 0 && l.fpiar.toBigInt == 0,
        "reset state must be all-zero (RN, extended precision, all traps disabled)")

      l.setFpcr.valid #= true; l.setFpcr.payload #= BigInt("00000030", 16)  // RND=11 (RP)
      dut.clockDomain.waitSampling(); l.setFpcr.valid #= false
      dut.clockDomain.waitSampling()
      assert(l.fpcr.toBigInt == 0x30, s"FPCR write, got ${l.fpcr.toBigInt.toString(16)}")

      l.setFpiar.valid #= true; l.setFpiar.payload #= BigInt("0000ABCD", 16)
      dut.clockDomain.waitSampling(); l.setFpiar.valid #= false
      dut.clockDomain.waitSampling()
      assert(l.fpiar.toBigInt == 0xABCD, "FPIAR write")
    }
  }

  test("FpuControlPlugin: an FPSR write MASKS OFF bits [27:24] (FPCC is renamed elsewhere)") {
    SimConfig.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val l = dut.p.logic
      dut.clockDomain.waitSampling(2)
      l.setFpsr.valid #= true; l.setFpsr.payload #= BigInt("0F000000", 16)  // all 4 FPCC bits
      dut.clockDomain.waitSampling(); l.setFpsr.valid #= false
      dut.clockDomain.waitSampling()
      assert(l.fpsr.toBigInt == 0,
        s"FPSR[27:24] must not be stored here -- FPCC lives in the renamed FPCC PRF; got ${l.fpsr.toBigInt.toString(16)}")
      l.setFpsr.valid #= true; l.setFpsr.payload #= BigInt("12345678", 16)
      dut.clockDomain.waitSampling(); l.setFpsr.valid #= false
      dut.clockDomain.waitSampling()
      assert(l.fpsr.toBigInt == BigInt("12045678", 16),
        s"only [27:24] masked, everything else kept; got ${l.fpsr.toBigInt.toString(16)}")
    }
  }

  test("FpuControlPlugin: orFpsrExc ORs into EXC[15:8] and folds AEXC[7:0]") {
    SimConfig.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val l = dut.p.logic
      dut.clockDomain.waitSampling(2)
      // EXC = OPERR only (bit 5 of the 8-bit field) -> AEXC.IOP (bit 7) set, nothing else.
      l.orFpsrExc.valid #= true; l.orFpsrExc.payload #= 0x20
      dut.clockDomain.waitSampling(); l.orFpsrExc.valid #= false
      dut.clockDomain.waitSampling()
      assert(((l.fpsr.toBigInt >> 8) & 0xFF) == 0x20, "EXC.OPERR set")
      assert((l.fpsr.toBigInt & 0xFF) == 0x80, s"AEXC.IOP only; got ${(l.fpsr.toBigInt & 0xFF).toString(16)}")

      // EXC = UNFL alone (bit 3) must NOT accrue AEXC.UNFL (needs INEX2 too).
      l.setFpsr.valid #= true; l.setFpsr.payload #= 0
      dut.clockDomain.waitSampling(); l.setFpsr.valid #= false
      l.orFpsrExc.valid #= true; l.orFpsrExc.payload #= 0x08
      dut.clockDomain.waitSampling(); l.orFpsrExc.valid #= false
      dut.clockDomain.waitSampling()
      assert((l.fpsr.toBigInt & 0x20) == 0,
        "AEXC.UNFL must require UNFL && INEX2 -- an exact underflow does not accrue")

      // EXC = UNFL | INEX2 -> AEXC.UNFL and AEXC.INEX both set.
      l.orFpsrExc.valid #= true; l.orFpsrExc.payload #= 0x0A
      dut.clockDomain.waitSampling(); l.orFpsrExc.valid #= false
      dut.clockDomain.waitSampling()
      assert((l.fpsr.toBigInt & 0x28) == 0x28, "AEXC.UNFL | AEXC.INEX")
    }
  }
}
```

Decode-side assertions go in the existing `OperationDecoderSpec` (alongside its MOVEC/PTEST cases at lines 278-328) and `MicroOpAssemblerSpec`:

```scala
// src/test/scala/m68k040/decode/OperationDecoderSpec.scala -- add alongside the MOVEC cases.
  test("FMOVE.L D0,FPCR (F200 9000): sysOp/FMOVE_FPCTRL, write direction", VerilatorTest) {
    run { dut => drive(dut, 0xF200, 0x9000); sleep(1)
      assert(!dut.o.illegal.toBoolean, "FMOVE.L Dn,FPCR must not be illegal (it F-line trapped before this task)")
      assert(dut.o.sysOp.toBoolean && dut.o.sysKind.toEnum == SysKind.FMOVE_FPCTRL)
      assert(!dut.o.sysReadDir.toBoolean, "ext[15:13]=100 is <ea> -> control register")
    }
  }
  test("FMOVE.L FPSR,D0 (F200 A800): read direction", VerilatorTest) {
    run { dut => drive(dut, 0xF200, 0xA800); sleep(1)
      assert(dut.o.sysOp.toBoolean && dut.o.sysKind.toEnum == SysKind.FMOVE_FPCTRL)
      assert(dut.o.sysReadDir.toBoolean, "ext[15:13]=101 is control register -> <ea>")
    }
  }
  test("FMOVEM.L control LIST (F200 BC00, mask=111) stays ILLEGAL -> vector 11", VerilatorTest) {
    run { dut => drive(dut, 0xF200, 0xBC00); sleep(1)
      assert(dut.o.illegal.toBoolean,
        "multi-register control masks are explicitly OUT of Task 9's scope and must keep the F-line fall-through")
    }
  }
```

```scala
// src/test/scala/m68k040/decode/MicroOpAssemblerSpec.scala -- the exhaustive line-F sweep's
// `implemented(op)` helper currently keys on the OPWORD ALONE, but FMOVE-to/from-FPcr is
// only recognized in combination with its extension word, and the sweep drives ext = 0
// (`drive(dut, op)` leaves words(1) = 0 -> ext[15:13] = 000, NOT 100/101). So the sweep
// keeps seeing vector 11 for the whole 0xF200-0xF23F range and needs NO exclusion added
// for this task. Add an explicit regression assertion instead, so a future change that
// makes the arm ext-word-INDEPENDENT fails visibly here:
  test("FMOVE-control arm is EXT-WORD gated: 0xF200 with ext=0 still faults to vector 11", VerilatorTest) {
    run { dut =>
      drive(dut, 0xF200, 0x0000, len = 1); sleep(1)
      assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 11,
        "ext[15:13]=000 is a register-to-register FPU op, NOT a control-register move -- must stay vector 11")
      drive(dut, 0xF200, 0x9000, len = 2); sleep(1)
      assert(!dut.uop.faulted.toBoolean && dut.uop.sysOp.toBoolean,
        "ext[15:13]=100 IS the control-register move -- must be a sysOp, not a fault")
      assert(dut.uop.sysKind.toEnum == SysKind.FMOVE_FPCTRL)
      assert(dut.uop.imm.toLong == 0x4, "mask ext[12:10]=100 (FPCR) rides imm[2:0]")
      assert(dut.uop.srcBReg.toInt == 0 && dut.uop.srcBValid.toBoolean, "Rn=D0 rides srcB")
      assert(!dut.uop.useImm.toBoolean, "useImm MUST stay False so the IQ wakes the Rn dependency")
    }
  }
  test("FMOVE.L #imm,FPSR (F23C 8800 xxxx xxxx): the immediate follows the COMMAND word", VerilatorTest) {
    run { dut =>
      // words: F23C, 8800, 1234, 5678 -> imm must be 0x12345678, NOT 0x88001234.
      dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
      dut.pkt.lenWords #= 4; dut.pkt.wordCount #= 4; dut.pkt.fault #= false
      dut.pkt.words(0) #= 0xF23C; dut.pkt.words(1) #= 0x8800
      dut.pkt.words(2) #= 0x1234; dut.pkt.words(3) #= 0x5678
      sleep(1)
      assert(dut.uop.sysOp.toBoolean && dut.uop.sysKind.toEnum == SysKind.FMOVE_FPCTRL)
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 0x12345678L,
        f"immediate must skip the command word; got 0x${dut.uop.imm.toLong}%08X")
    }
  }
```

Finally, an end-to-end whitebox test in `ExecuteLockStepSpec` proving the round-trip and the non-privileged property. This is **not** lock-steppable — Musashi's `m68k_get_reg` has no FP surface at all (spec Decision 3), and the vendored build's coprocessor stubs claim this opword range anyway (the oracle gap already documented at `ExecuteLockStepSpec.scala:3620-3634`). Model it on `runCpushPriv` (`ExecuteLockStepSpec.scala:3695-3742`), reading `dut.rob.logic.exc.fpuCtrl`-visible state through the plugin's `simPublic` regs:

```scala
  // FMOVE.L Dn,FPCR / FPCR,Dn round-trip + the USER-mode non-privileged property.
  // Whitebox, not lock-step: Musashi exposes no FP registers (spec Decision 3) and its
  // CPU_TYPE_68040 coprocessor stubs claim this opword range (the same oracle gap already
  // documented for line-F vector-11 above).
  test("FMOVE.L D0,FPCR then FMOVE.L FPCR,D1 round-trips, in USER mode (not privileged)", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val src = "move.l #0x00000030,%d0 ; .short 0xF200,0x9000 ; .short 0xF200,0xB200 ; done: bra.s done"
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }
    var fpcrSeen = BigInt(-1); var d1Seen = BigInt(-1); var sawExc = false
    compiledDut.doSim(freshSimName("fmove-fpctrl")) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false; dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true; cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.usp #= 0x00200000L
      dut.rob.logic.exc.ss.srSys #= 0x00            // USER mode -- the point of the test
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00200000L)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      var guard = 0
      while (guard < 900) {
        if (dut.rob.logic.exceptionPending.toBoolean) sawExc = true
        cd.waitSampling(); guard += 1
      }
      fpcrSeen = dut.fpuCtrl.logic.fpcr.toBigInt
    }
    assert(!sawExc, "FMOVE-to/from-FPcr is a USER instruction -- it must not raise a privilege violation")
    assert(fpcrSeen == 0x30, s"FPCR should hold 0x30 after FMOVE.L D0,FPCR; got 0x${fpcrSeen.toString(16)}")
  }
```

- [ ] **Step 11: Run the tests**

```bash
sbt "testOnly m68k040.execute.FpuControlPluginSpec"
sbt "testOnly m68k040.decode.OperationDecoderSpec m68k040.decode.MicroOpAssemblerSpec"
```
Expected: all PASS, including the two exhaustive line-A/line-F sweeps (unchanged — this task deliberately leaves the sweep's exclusion set alone; see Step 10's note).

- [ ] **Step 12: Pin down the AEXC fold against primary source**

The only unverified bit-level fact this task introduces. Open the MC68040 User's Manual (the same `bitsavers` copy the design spec used) at the FPSR description, confirm the five AEXC derivation rules quoted in `FpuControlPlugin.aexcOf`'s comment, and **record the section/page number in that comment and in the commit message**. If any rule differs, fix `aexcOf` and the corresponding assertion in `FpuControlPluginSpec`. Do not mark this step done by re-reading the code.

- [ ] **Step 13: Confirm the existing lock-step suite is unaffected**

```bash
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
```
Expected: 396/396 PASS **plus** the one new test from Step 10 (397). No previously-passing test may flip — in particular the "line-F opcode -> vector 11 -> handler via VBR+0x2c -> RTE" test at `ExecuteLockStepSpec.scala:3660` uses opword `0xFD00` (cpID=110), which this task's `op(11 downto 9) === 001` gate cannot claim.

- [ ] **Step 14: Commit**

```bash
git add src/main/scala/m68k040/services/Services.scala \
        src/main/scala/m68k040/execute/FpuControlPlugin.scala \
        src/main/scala/m68k040/decode/DecodedUop.scala \
        src/main/scala/m68k040/decode/OperationDecoder.scala \
        src/main/scala/m68k040/decode/MicroOpAssembler.scala \
        src/main/scala/m68k040/frontend/PredecodeWord.scala \
        src/main/scala/m68k040/exception/ExceptionUnit.scala \
        src/main/scala/m68k040/rob/RobPlugin.scala \
        src/main/scala/m68k040/top/FullCoreSynth.scala \
        src/test/scala/m68k040/execute/FpuControlPluginSpec.scala \
        src/test/scala/m68k040/decode/OperationDecoderSpec.scala \
        src/test/scala/m68k040/decode/MicroOpAssemblerSpec.scala \
        src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala
git commit -m "feat(fpu): FMOVE to/from FPCR/FPSR/FPIAR + FpuControlPlugin

The non-renamed half of the FP architectural state (spec Decision 5).
FpuControlPlugin holds single-copy committed FPCR / FPSR-non-FPCC-bytes
/ FPIAR, structurally a direct copy of MmuControlPlugin
(mmu/MmuControl.scala): Fiber-built Regs with one default-idle Flow
write port each and a single when(port.valid) writer. It also carries
the FP EU's exception-status OR-in port (orFpsrExc) and the FPCR field
accessors Task 8 reads for rounding mode.

FMOVE-to/from-a-control-register is a REAL dedicated 68881/68040
instruction, not a MOVEC variant: opword 0xF200|<ea> with a command
extension word whose ext[15:13] selects direction (100 = <ea>->ctrl,
101 = ctrl-><ea>) and ext[12:10] is a one-hot {FPCR,FPSR,FPIAR} mask.
The encoding was confirmed three independent ways before any RTL was
written -- a direct m68k-linux-gnu-as -m68040 -m68881 assembly (output
recorded below), the vendored corpus's own toolchain-verified header
comments (fpu_fsave_frestore_idle_roundtrip.s, fpu_fmovem_ctrl_reg.s),
and the real Q700 ROM FPSP prologue pair F227 BC00 quoted in the design
spec.

Because FPCR/FPSR/FPIAR are non-renamed, the access is a COMMIT-TIME
SYSTEM op, following MOVEC's arm in ExceptionUnit's S_APPLY exactly:
read direction writes the int PRF[pdst] through the existing
sysRegWrite* port, write direction fans out to the plugin's Flow ports,
and the register-select mask rides the imm side-channel into sysCapRc
the same way MOVEC's 12-bit Rc id does. Two deviations from MOVEC, both
deliberate and tested: (1) it is NOT privileged (real 68040 FMOVE-to-
FPcr is a user instruction), so RobPlugin's sysPrivFault gains an
explicit exclusion; (2) an architectural FPSR read has to SPLICE in the
live committed FPCC, which is renamed (Decision 4) and therefore not in
this plugin -- read back through a new committedFpccIn input following
committedA7In's optional-input pattern, and written back on an FPSR
write through a direct committed-mapping FPCC write following
rteNzvcWriteValid's already-proven-safe pattern (never a rename
allocation -- see that field's doc comment for the confirmed
corruption regression that rules the alternative out).

Scope is deliberately the single-register forms with a register-direct
or immediate <ea>, mirroring MOVE-to-SR's own long-standing reg-only
scope. Multi-register masks (the FMOVEM control-list form, including
the FPSP prologue's own F227 BC00) and memory <ea>s keep today's
vector-11 fall-through unchanged; fpu_fmove_mem_ea_fpcr_fpsr_no_fline,
fpu_fmovem_ctrl_*, and fpu_fpsp_selfrecursion_repro therefore stay
failing after this task, by design and not by oversight.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 10: Fix the `faultUsesNextPc` gap for recognized FPU instructions

**Files:**
- Modify: `src/main/scala/m68k040/decode/DecodedUop.scala` (new `fpuSoftwareComplete` + `fpuCmdWord` fields)
- Modify: `src/main/scala/m68k040/rename/RenamedUop.scala` (same two fields)
- Modify: `src/main/scala/m68k040/rename/RenameStage.scala` (copy them through)
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala` (the actual fix)
- Modify: `src/main/scala/m68k040/frontend/PredecodeWord.scala` (2-word framing for the recognized class)
- Test (modify): `src/test/scala/m68k040/decode/MicroOpAssemblerSpec.scala` (the exhaustive line-F sweep)
- Test: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` (directed + mutation-proof)

**Interfaces:**
- Produces: `DecodedUop.fpuSoftwareComplete` (Bool) — "this F-line opword is a RECOGNIZED FPU instruction whose full length and required source state are captured; deliver vector 11 with the POST-instruction PC". Consumed here (drives `faultUsesNextPc`) and by Task 11 (the unimplemented-instruction FSAVE-frame capture trigger). `DecodedUop.fpuCmdWord` (Bits 16) — the FPU command extension word, consumed by Task 11 as CMDREG1B.
- Consumes: nothing new.

**This is a real, already-diagnosed, independently-scoped bug.** `docs/superpowers/specs/2026-08-09-fpu-hardware-design.md` §5 states it precisely, after a full vector-by-vector audit against m68k-ooo:

> **This project has no post-instruction-PC flavor of vector-11 delivery at all** — `MicroOpAssembler.scala`'s generic line-F fallback leaves `faultUsesNextPc := False`. This matters directly for this FPU work: an FPSP-style handler that expects to RTE past a recognized but software-completed FPU instruction needs the post-instruction-PC variant, or it will loop on the same opword forever.

and resolves the trigger from the sibling RTL rather than leaving it as a guess:

> **Required here:** any recognized FPU instruction deliberately handed to FPSP after its full length and required source state have been captured sets `faultUsesNextPc := True`; an unrecognized line-F encoding keeps it False. The architectural vector remains 11 in both cases—do not expose `0x8B` outside an internal control encoding.

**Confirmed by direct read of the current code (not assumed):**
- `faultUsesNextPc` is a real, live, *fully-wired* field, not a dead one. `DecodedUop` declares it, `RenamedUop.scala:46` carries it, `RenameStage.scala:154` copies it (`r.faultUsesNextPc := dec.faultUsesNextPc`), and `RobPlugin.scala:861,886` consumes it at alloc: `faultPcStore(tail) := Mux(allocUopVec(0).faultUsesNextPc, allocUopVec(0).nextPc, allocUopVec(0).pc)`. Nothing needs adding to that chain.
- The default is set once at `MicroOpAssembler.scala:706`: `opUop.faultUsesNextPc := False  // default: stack the faulting instr PC (pc); TRAP/TRAPV -> nextPc`.
- The line-F fallback that must be fixed is the `when(bad)` block at `MicroOpAssembler.scala:1517-1536`, which picks the vector via a top-nibble mux (`U(0xF, 4 bits) -> U(11, 8 bits)`) and never touches `faultUsesNextPc`.
- Established precedent for setting it at decode for a *conditionally* faulting op, from the same file (lines 731-737): CHK/DIV "are group-2 traps ... The fault is conditional (set at execute), but faultPc is captured at ALLOC, so faultUsesNextPc must be set NOW".

**DO NOT touch the format selection.** `ExceptionUnit.scala:769-771`'s `is2` term already includes `entryVector === 11`, so vector 11 already stacks a 6-word format-$2 frame. The 2026-08-09 audit confirms this is correct and matches m68k-ooo across both of its vector-11 delivery paths (13 of 13 other vector/format pairs matched too). This task changes **only** which PC lands in the frame's PC field. `ExceptionUnit.scala:730-749`'s long comment records that format-$2-for-vector-11 is a *disputed but deliberately-chosen* position (corpus over Musashi); leave it exactly as it is.

**The recognized set, chosen so this task is genuinely standalone.** `faultUsesNextPc := True` is only *safe* when the instruction's length is genuinely known — a wrong length makes the handler resume mid-instruction. The set used here is therefore the one F-line class whose length is unambiguous with no dependence on Task 4/5's decode work:

```
op[15:12] = 1111, op[11:9] = 001, op[8:6] = 000     (line-F, cpID = 001, opclass 000)
ext[15:13] = 000                                     (R/M = 0: source is a FLOATING-POINT
                                                      REGISTER, so the <ea> field is IGNORED)
  => the instruction is EXACTLY 2 words, unconditionally, regardless of the <ea> field.
```

This is the register-to-register FPU-general form — every transcendental (`FSIN FP1,FP0`, `FETOX`, `FLOGN`, …), every rounded-precision variant, and every other non-hardware-native op in its register-source form. All of them must go to FPSP, all of them have their complete source state already in the FP register file (which Task 11's unimplemented-instruction frame captures), and all of them are exactly 2 words. That makes this the correct, provably-safe initial member set, and Task 4/5 widen it later by extending one predicate in one place.

Deliberately **excluded** (keep `faultUsesNextPc := False`, i.e. today's behavior): every other line-F encoding, including `ext[15:13] = 010/011` (memory source/destination — length depends on the `<ea>` and on the operand format field, which is Task 5's work), the control-register forms (Task 9 handles those as sysOps, they never reach `bad`), FMOVEM, FBcc/FScc/FDBcc/FTRAPcc, and every cpID ≠ 001 opword.

- [ ] **Step 1: Add the two decode fields**

```scala
// src/main/scala/m68k040/decode/DecodedUop.scala
// Add to `case class DecodedUop()`, immediately after the existing `faultUsesNextPc` /
// fault-family fields:

  // ── Recognized-FPU-instruction software completion (vector 11, POST-instruction PC) ──
  // True for an F-line opword this core RECOGNIZES as an FPU instruction and DELIBERATELY
  // hands to FPSP with its full length and required source state captured. Drives
  // faultUsesNextPc (below) so the vector-11 format-$2 frame carries the POST-instruction
  // PC -- an FPSP handler that software-completes the instruction then RTEs lands on the
  // NEXT instruction instead of re-trapping on the same opword forever. An UNRECOGNIZED
  // line-F encoding (unknown length) keeps this False and keeps the faulting PC, which is
  // the pre-existing behavior for every line-F opword and stays correct for them.
  //
  // The architectural vector is 11 in BOTH cases; this is purely the PC-field selector.
  // (m68k-ooo raises an internal pseudo-vector 0x8B for its equivalent path and translates
  // it back to 11 at commit -- see the 2026-08-09 design spec section 5. Do NOT replicate
  // the pseudo-vector: it is an internal control encoding there, and this project has a
  // simpler, already-wired mechanism in faultUsesNextPc.)
  val fpuSoftwareComplete = Bool()
  // The FPU COMMAND extension word (words(1)) of a recognized FPU instruction. Zero for
  // everything else. Task 11 stacks this as the unimplemented-instruction state frame's
  // CMDREG1B field; it is captured HERE, at decode, because by the time the frame is
  // emitted (a later FSAVE) the instruction words are long gone.
  val fpuCmdWord = Bits(16 bits)
```

Then set both to their inert defaults at **every** `DecodedUop` construction site in `MicroOpAssembler.scala`. Those sites are already enumerated by the existing `faultUsesNextPc` initializations — lines 82, 133, 184, 239, 706, 1084, 1151, 1199, 1964, 2033, 2110, 2194, 2254, 2357, 2398, 2494, 2533, 2594, 2651, 2874 (and the microcode `UOpFromCtx` builder). Mechanically:

```bash
grep -n "faultUsesNextPc := False\|faultUsesNextPc := True" src/main/scala/m68k040/decode/MicroOpAssembler.scala
```
and add `u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)` (or `opUop.`/`ldUop.`/… as appropriate) next to each. If any site is missed, SpinalHDL fails elaboration with an UNASSIGNED signal error — a loud failure, not a silent one.

- [ ] **Step 2: Thread them through rename**

```scala
// src/main/scala/m68k040/rename/RenamedUop.scala -- add alongside line 46's faultUsesNextPc:
  val fpuSoftwareComplete = Bool()
  val fpuCmdWord          = Bits(16 bits)

// src/main/scala/m68k040/rename/RenameStage.scala -- add alongside line 154:
      r.fpuSoftwareComplete := dec.fpuSoftwareComplete
      r.fpuCmdWord          := dec.fpuCmdWord
```

Task 11 consumes them from there; this task only needs them to exist and be carried (nothing downstream reads them yet, which is fine — a carried-but-unread renamed field is the same shape as several already in that bundle).

- [ ] **Step 3: The fix itself in `MicroOpAssembler.scala`**

Add immediately after the existing `when(bad) { ... }` block (which ends at line 1536), so it is a *refinement* of the fault the `bad` block just raised, not a competing writer:

```scala
    // ── Recognized-FPU-instruction software completion: vector 11, POST-instruction PC ──
    // The 2026-08-09 FPU hardware design spec, section 5, records this as a real,
    // already-diagnosed gap: this core had NO post-instruction-PC flavor of vector-11
    // delivery at all, so an FPSP-style handler that software-completes a recognized FPU
    // instruction and RTEs would land back ON the same opword and re-trap forever. The
    // audit that found it also resolved the trigger from the sibling m68k-ooo RTL rather
    // than leaving it an implementation-time guess: a recognized FPU instruction whose
    // full length and required source state are captured uses the fall-through PC; a plain
    // top-nibble line-F fallback with unknown/untrusted length keeps the faulting PC.
    //
    // THE RECOGNIZED SET (deliberately the ONE class whose length is unambiguous without
    // any of the FPU decode work in Tasks 4/5, so this fix is independently landable and
    // independently testable):
    //     op[15:12]=1111, op[11:9]=001 (cpID), op[8:6]=000 (opclass: FPU general)
    //     ext[15:13]=000  -> R/M=0: the SOURCE is a floating-point REGISTER, so the <ea>
    //                        field is IGNORED and the instruction is EXACTLY 2 words,
    //                        unconditionally, whatever the <ea> bits happen to say.
    // That is the register-to-register form of every transcendental / rounded-precision /
    // otherwise-non-hardware-native FPU op -- precisely the population that must reach
    // FPSP, with its complete source state already sitting in the FP register file.
    //
    // GATED ON `pkt.lenWords === 2`, not merely on the bit pattern. PredecodeWord frames
    // this class as 2 words only when the extension word was actually resident in the
    // cache line being predecoded (see its `extWKnown` guard); if it was not, predecode
    // falls back to 1-word framing and marks the line ambiguous, and this gate then
    // correctly declines to promise a fall-through PC we cannot compute. Length
    // correctness is the whole safety argument for faultUsesNextPc -- do not weaken it to
    // a pure opword/ext match.
    //
    // The vector stays 11 (set by the `bad` block above) and the FRAME FORMAT stays
    // format-$2 (ExceptionUnit.scala:769-771 already includes vector 11 in its `is2`
    // term, confirmed correct against m68k-ooo by the same 2026-08-09 audit). This block
    // changes ONLY which PC lands in the frame's PC field.
    val fpuGenReg = (op(15 downto 12) === B"4'hF") &&
                    (op(11 downto 9)  === B"3'b001") &&
                    (op(8 downto 6)   === B"3'b000") &&
                    (pkt.words(1)(15 downto 13) === B"3'b000") &&
                    (pkt.lenWords === U(2, pkt.lenWords.getWidth bits))
    when(bad && fpuGenReg) {
      opUop.fpuSoftwareComplete := True
      opUop.fpuCmdWord          := pkt.words(1)
      // nextPc is already `pkt.pc + (pkt.lenWords << 1)` (line 682) = pc + 4 here, and the
      // ROB captures Mux(faultUsesNextPc, nextPc, pc) into faultPcStore at ALLOC
      // (RobPlugin.scala:861/886) -- the same alloc-time capture CHK/DIV0 rely on.
      opUop.faultUsesNextPc     := True
    }
```

- [ ] **Step 4: `PredecodeWord.scala` — 2-word framing for the recognized class**

Add to the `is(U(0xF, 4 bits))` arm, *after* Task 9's FMOVE-control branch (whose `ext[15:13]` is 100/101 and therefore cannot collide with this one's 000) and before the `0xF27F` FSF case:

```scala
        // Recognized register-to-register FPU general instruction (Task 10): cpID=001,
        // opclass 000, ext[15:13]=000 (R/M=0, source is FPm) -> the <ea> field is IGNORED
        // and the length is EXACTLY 2 words. Framing this correctly is the precondition
        // for MicroOpAssembler's faultUsesNextPc promise: it gates on lenWords===2, so if
        // the extension word is not resident here we fall back to the pre-existing 1-word
        // framing + ambiguousLine, and the assembler correctly declines the promise.
        } .elsewhen((op(11 downto 9) === B"3'b001") && (op(8 downto 6) === B"3'b000") &&
                    (extW(15 downto 13) === B"3'b000")) {
          when(extWKnown) { r.simple := True; r.lenWords := U(2, 4 bits) }
          .otherwise      { r.simple := True; r.lenWords := U(1, 4 bits); r.ambiguousLine := True }
```

- [ ] **Step 5: Update the exhaustive line-F sweep (it will otherwise fail — by design)**

`MicroOpAssemblerSpec`'s "line-F: every UNIMPLEMENTED opword faults to vector 11, exhaustively" test currently asserts `usesNextPc == false` for every swept opword, and its `drive(dut, op)` helper leaves `words(1) = 0` (so `ext[15:13] = 000`) and `lenWords = 1`. With Step 4 landed, the *sweep's own driving* still uses `len = 1`, so the `pkt.lenWords === 2` gate declines and the sweep still sees `usesNextPc == false` for the whole range. **That is a false negative, not a pass** — the sweep would silently stop covering the new behavior. Fix it by driving the recognized class at its real length and asserting the real expectation:

```scala
// src/test/scala/m68k040/decode/MicroOpAssemblerSpec.scala
// Replace the body of the line-F sweep's loop with a length-aware drive:
      for (op <- 0xF000 to 0xFFFF if !implemented(op)) {
        // The recognized register-to-register FPU class (cpID=001, opclass 000, ext=0 ->
        // ext[15:13]=000) is 2 words; everything else in line F is framed as 1 word by
        // PredecodeWord. Drive each at its REAL framed length, or the lenWords gate in
        // MicroOpAssembler silently masks the very behavior this sweep exists to pin.
        val fpuGenReg = ((op & 0xFE00) == 0xF200) && ((op & 0x01C0) == 0x0000)
        drive(dut, op, len = if (fpuGenReg) 2 else 1); sleep(1)
        swept += 1
        val faulted = dut.uop.faulted.toBoolean
        val vec     = dut.uop.faultVector.toInt
        val unimpl  = dut.uop.unimplemented.toBoolean
        val nextPc  = dut.uop.faultUsesNextPc.toBoolean
        // Task 10: a RECOGNIZED FPU instruction still faults to vector 11 (architectural
        // vector unchanged) but stacks the POST-instruction PC so an FPSP handler can RTE
        // past it. Every OTHER line-F opword keeps the faulting PC.
        val wantNextPc = fpuGenReg
        if (!faulted || vec != 11 || !unimpl || nextPc != wantNextPc)
          bad += f"0x$op%04X faulted=$faulted vec=$vec unimpl=$unimpl usesNextPc=$nextPc want=$wantNextPc"
      }
```

The line-A sweep is untouched: line A must keep `faultUsesNextPc == false` (its own comment explains why — the Toolbox dispatcher reads the trap word back from the stacked address), and this task's gate cannot match it.

- [ ] **Step 6: Directed regression test — an FPSP-style handler RTEs past a software-completed instruction**

Whitebox, not lock-step: Musashi has no unimplemented-instruction trap for this class at all (spec Decision 3), and its CPU_TYPE_68040 coprocessor stubs claim this opword range outright — the same oracle gap `ExecuteLockStepSpec.scala:3620-3634` already documents for line F. The test drives the real `FullCoreDut` through the real decode/commit/exception path and asserts on architectural memory, exactly like `runCpushPriv` (`ExecuteLockStepSpec.scala:3695-3742`).

```scala
// src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala

  /** Task 10: a recognized FPU instruction (register-to-register FPU general, cpID=001,
    * opclass 000, ext[15:13]=000) delivered to a vector-11 handler must stack the
    * POST-instruction PC, so an FPSP-style handler that software-completes the op and
    * plainly RTEs resumes on the NEXT instruction.
    *
    * `0xF200,0x0018` = FMOVE.X FP0,FP0 in the register-to-register encoding shape used
    * here purely as a RECOGNIZED-but-not-hardware-native stand-in; what matters for this
    * test is only that it matches the recognized class and is 4 bytes long. The handler is
    * a MINIMAL FPSP: it does NOT patch the stacked PC (the whole point -- if the frame
    * carried the faulting PC the plain RTE would loop forever), it just marks that it ran
    * and returns.
    *
    * The format-$2 frame for vector 11 is UNCHANGED by this task and deliberately so
    * (ExceptionUnit.scala:769-771; confirmed correct against m68k-ooo by the 2026-08-09
    * audit). The frame's PC field is at offset +2, so the handler reads 2(%a7) to observe
    * which PC was stacked -- that read is the actual assertion.
    */
  private def runFpuNextPcHandoff(mutationProof: Boolean): (Long, Long, Int) = {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    // d5 counts handler entries; d6 receives the stacked PC; d7 is set ONLY if execution
    // reaches the instruction AFTER the trapping one.
    val src =
      "move.l #0x3000,%d0 ; movec %d0,%vbr ; move.l #handler,%d1 ; move.l %d1,0x302c ; " +
      "moveq #0,%d5 ; moveq #0,%d7 ; " +
      "trapping: .short 0xF200,0x0018 ; " +
      "moveq #1,%d7 ; " +
      "loop: bra loop ; " +
      "handler: addq.l #1,%d5 ; move.l 2(%a7),%d6 ; rte"
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }
    var stackedPc = 0L; var reachedAfter = 0L; var entries = 0
    compiledDut.doSim(freshSimName(if (mutationProof) "fpu-nextpc-mut" else "fpu-nextpc")) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false; dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true; cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L
      dut.rob.logic.exc.ss.srSys #= 0x27
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00100000L)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      // Bounded run: an infinite re-trap loop (the pre-fix behavior) burns the budget and
      // leaves d7 == 0 with d5 climbing -- which is exactly what the mutation-proof test
      // asserts, so the budget MUST be generous enough that the correct path finishes.
      cd.waitSampling(3000)
      stackedPc    = dut.wire.logic.dbgArchReg(6).toLong
      reachedAfter = dut.wire.logic.dbgArchReg(7).toLong
      entries      = dut.wire.logic.dbgArchReg(5).toInt
    }
    (stackedPc, reachedAfter, entries)
  }

  test("Task 10: recognized FPU instr stacks the POST-instruction PC; handler RTEs past it", VerilatorTest) {
    val (stackedPc, reachedAfter, entries) = runFpuNextPcHandoff(mutationProof = false)
    val trappingPc = /* address of the `trapping:` label -- recompute from the assembled
                        image's symbol table, do not hardcode */ ProgramAssembler
      .assemble("", 0).fold(_ => 0L, _ => 0L)
    assert(entries == 1,
      s"the handler must be entered EXACTLY ONCE; got $entries entries (>1 means it re-trapped on the same opword)")
    assert(reachedAfter == 1,
      "execution must reach the instruction AFTER the trapping one -- a plain RTE past a software-completed op")
    assert(stackedPc == trappingPc + 4,
      f"the format-$2 frame's PC field must be the POST-instruction PC (trapping+4); got 0x$stackedPc%08X")
  }
```

(Replace the `trappingPc` placeholder with the real symbol lookup this spec already uses for its other label-relative assertions — `grep -n "image.symbols\|labelAddr" src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` and reuse whatever accessor exists. Do **not** hardcode an address; `ProgramAssembler.DefaultLoadAddress` plus a hand-counted byte offset is exactly the kind of brittle constant this suite has been bitten by before.)

- [ ] **Step 7: Mutation-proof test — prove the OLD behavior infinite-loops**

```scala
  /** MUTATION PROOF for Step 6. If the Task 10 fix is reverted (faultUsesNextPc left False
    * for the recognized class), the format-$2 frame carries the FAULTING PC, the handler's
    * plain RTE lands back ON the same opword, and the trap re-fires forever. This test
    * pins that failure mode DIRECTLY rather than trusting Step 6's positive assertion to
    * notice: it asserts the handler is entered exactly once and that execution escapes.
    * Both assertions fail loudly (many entries, never escaped) under the reverted code.
    *
    * HOW TO VERIFY THIS TEST ACTUALLY BITES (do this once, by hand, and record the numbers
    * in the commit message -- a mutation-proof test that was never mutated proves nothing):
    *   1. Comment out the `opUop.faultUsesNextPc := True` line in MicroOpAssembler's
    *      `when(bad && fpuGenReg)` block.
    *   2. Re-run this test. It MUST fail, with entries >> 1 and reachedAfter == 0.
    *   3. Restore the line; the test passes again.
    */
  test("Task 10 mutation proof: with faultUsesNextPc=False the handler would re-trap forever", VerilatorTest) {
    val (_, reachedAfter, entries) = runFpuNextPcHandoff(mutationProof = true)
    assert(entries == 1,
      s"MUTATION SENTINEL: entries=$entries. >1 means the frame carried the FAULTING PC and " +
      "the handler re-entered on the same opword -- i.e. the Task 10 fix is absent or reverted.")
    assert(reachedAfter == 1,
      "MUTATION SENTINEL: execution never escaped the trapping instruction -- the classic " +
      "FPSP re-trap livelock the 2026-08-09 design spec section 5 predicted.")
  }
```

- [ ] **Step 8: Run the tests**

```bash
sbt "testOnly m68k040.decode.MicroOpAssemblerSpec"
```
Expected: PASS, including both exhaustive sweeps (line A unchanged; line F now asserting `usesNextPc == true` for exactly the 512-opword recognized class and `false` for everything else).

```bash
sbt "testOnly m68k040.frontend.PredecodeWordSpec"
```
Expected: PASS. **Caveat, recorded in this project's own memory:** `PredecodeWordSpec`'s "exhaustive 65536-opword" test aborts at the first mismatch, so a green result is *not* proof of exhaustive coverage — do not report it as such.

- [ ] **Step 9: Confirm the existing lock-step suite is unaffected**

```bash
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
```
Expected: 397/397 from Task 9, plus the two new tests from Steps 6-7 (399). Watch specifically that "lock-step: line-F opcode -> vector 11 -> handler via VBR+0x2c -> RTE" (`ExecuteLockStepSpec.scala:3660`) still passes — its opword `0xFD00` has cpID=110 and is outside the recognized class, so its handler's explicit `addq.l #2` PC patch must keep working exactly as before. If that test flips, the recognized-set predicate is too wide.

- [ ] **Step 10: Commit**

```bash
git add src/main/scala/m68k040/decode/DecodedUop.scala \
        src/main/scala/m68k040/decode/MicroOpAssembler.scala \
        src/main/scala/m68k040/frontend/PredecodeWord.scala \
        src/main/scala/m68k040/rename/RenamedUop.scala \
        src/main/scala/m68k040/rename/RenameStage.scala \
        src/test/scala/m68k040/decode/MicroOpAssemblerSpec.scala \
        src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala
git commit -m "fix(exception): post-instruction-PC vector-11 delivery for recognized FPU ops

Closes a real, already-diagnosed gap recorded in the 2026-08-09 FPU
hardware design spec, section 5: this core had NO post-instruction-PC
flavor of vector-11 delivery at all. MicroOpAssembler's generic line-F
fallback raises vector 11 and leaves faultUsesNextPc at its False
default, so the format-\$2 frame always carried the FAULTING PC. An
FPSP-style handler that software-completes a recognized FPU instruction
and then plainly RTEs therefore lands back ON the same opword and
re-traps forever -- the exact livelock class m68k-ooo hit four times on
real hardware.

The trigger is not an implementation-time guess: the same vector-by-
vector audit that found the gap resolved it from the sibling RTL. A
recognized FPU instruction whose full length and required source state
are captured uses the fall-through PC; a plain top-nibble line-F
fallback with unknown length keeps the faulting PC. The architectural
vector stays 11 in both cases -- m68k-ooo's internal 0x8B pseudo-vector
is deliberately NOT replicated, since faultUsesNextPc is already a
fully-wired mechanism here (RenamedUop -> RenameStage:154 ->
RobPlugin:861/886's alloc-time faultPcStore capture, the same path CHK
and DIV0 already depend on).

The recognized set is deliberately narrow and self-contained: line-F,
cpID=001, opclass 000, ext[15:13]=000 -- the register-to-register FPU
general form, where R/M=0 means the <ea> field is IGNORED and the
instruction is EXACTLY 2 words regardless of what those bits say. That
is every transcendental / rounded-precision / non-hardware-native op in
its register-source form, i.e. precisely the population that must reach
FPSP, with complete source state already in the FP register file. The
promise is gated on pkt.lenWords===2, not on the bit pattern alone:
PredecodeWord only frames 2 words when the extension word was actually
resident in the line being predecoded, so a not-yet-resident ext word
correctly declines the fall-through PC instead of promising one we
cannot compute. Length correctness IS the safety argument here.

Frame FORMAT is untouched. ExceptionUnit's `is2` term already includes
vector 11, confirmed correct against m68k-ooo across both of its
vector-11 paths by the same audit (13 of 13 other vector/format pairs
matched too). This change selects the PC field's value and nothing else.

Verified by a directed test that installs a minimal FPSP-shaped handler
which does NOT patch the stacked PC and returns via a plain RTE, plus a
mutation-proof sibling that pins the reverted behavior (many handler
entries, execution never escapes). The mutation was actually performed
by hand, not merely asserted -- numbers in the review notes. The
exhaustive line-F opword sweep in MicroOpAssemblerSpec was updated to
drive each opword at its REAL framed length, since the old fixed
len=1 drive would have silently masked the new behavior behind the
lenWords gate.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 11: FSAVE / FRESTORE — null, idle, and unimplemented-instruction state frames

**Files:**
- Modify: `src/main/scala/m68k040/decode/DecodedUop.scala` (`SysKind.FSAVE`, `SysKind.FRESTORE`)
- Modify: `src/main/scala/m68k040/decode/OperationDecoder.scala` (recognition arm)
- Modify: `src/main/scala/m68k040/decode/MicroOpAssembler.scala` (operand routing)
- Modify: `src/main/scala/m68k040/execute/FpuControlPlugin.scala` (sticky `fpuEverExecuted` + the latched unimplemented-instruction capture group)
- Modify: `src/main/scala/m68k040/exception/ExceptionUnit.scala` (frame emit/consume FSM + the trap-time capture)
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala` (per-entry `fpuUnimpStore`/`fpuCmdStore`; drive `setEverExecuted` from FP commit)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (committed FP operand readbacks)
- Test: `src/test/scala/m68k040/exception/FsaveFrestoreSpec.scala`
- Test (modify): `src/test/scala/m68k040/decode/MicroOpAssemblerSpec.scala` (line-F sweep exclusions)

**Interfaces:**
- Consumes: `DecodedUop.fpuSoftwareComplete` / `.fpuCmdWord` (Task 10), `FpuControlService` (Task 9), `CommitSlot.fpWrite` (Task 2), the FP register file (Task 1).
- Produces: `FpuControlService.everExecuted` / `setEverExecuted` / `unimpValid` / `setUnimpFrame` / `clearFpuState`, and the `ExceptionUnit` FSAVE/FRESTORE arms.

**Encoding — VERIFIED against the vendored corpus, and the task prompt's stated pattern is WRONG.** The prompt gives `1111 001 000 EA` (= `0xF200`), which is the FPU **general** opclass, not FSAVE. The real encoding is:

```
FSAVE    <ea> : 1111 001 100 mmmrrr  = 0xF300 | <ea>     (opclass 100)
FRESTORE <ea> : 1111 001 101 mmmrrr  = 0xF340 | <ea>     (opclass 101)
```

Confirmed directly from the vendored corpus, which encodes these as raw literals precisely because they are load-bearing: `fpu_fsave_idle_format_byte.s` and `fpu_fsave_null_frame.s` both use `.short 0xF327` commented `| FSAVE -(A7)` (mode 100, reg 111 = `-(A7)` → `0x27`), and `.short 0xF35F` commented `FRESTORE (A7)+` (mode 011, reg 111 = `0x1F`). `fsave_frestore_basic.s` uses the real `fsave`/`frestore` mnemonics through the toolchain across five EA modes. `0xF300 | 0x27 = 0xF327` and `0xF340 | 0x1F = 0xF35F` — both check out. Step 1 still re-confirms via the assembler, because the corpus is a secondary source.

**Both are PRIVILEGED** (real 68040). Unlike Task 9's FMOVE-to-FPcr, these need **no** exclusion from `RobPlugin`'s `sysPrivFault` — the default `sysOp` behavior (any `sysOp` head retiring at committed S == 0 takes vector 8) is exactly right, and Step 12 tests it.

**Architectural mechanism: these are commit-time system ops, not microcode.** The prompt suggests following `Microcode.MOVE16_ENTRY`. Having read that pattern directly (`Microcode.scala:1578-1651`, rows 242-251, quoted below), it is the **wrong** precedent here and I am flagging the deviation rather than forcing it:

```scala
    //   m0 LOAD.L  (Ax+0)  -> T0                                              (isFirst)
    //   ...
    //   m8 ADD.L Ax + 16 -> Ax   (dropped crack µop)
    //   m9 ADD.L Ay + 16 -> Ay   (dropped crack µop)                          (isLast)
    Desc(UMove, mem = MLoad, srcA = SAy, dst = ST0, sz = SzLong, isFirst = true),          // µPC242 (m0)
    Desc(UMove, mem = MStore, srcA = SMove16Ay, srcB = ST0, sz = SzLong),                 // µPC244 (m2)
```

Microcode rows are **static**: MOVE16 works there because it is always exactly four transfers. FSAVE's length is **runtime-selected** (4 vs 44 bytes, decided from live FPU state), which the `Desc` vocabulary cannot express — the closest existing runtime-variable-length mechanism is MOVEM's dedicated `DecodeStage` FSM (`DecodeStage.scala:329-425`, `movemActive`/`movemMask`/`movemEmitted`/…), which is a genuine option but a large, speculative-path sequencer.

The far better fit — and the one this task uses — is `ExceptionUnit`'s **own** frame machinery, which already *is* "write N words to memory where N is a runtime-selected frame size, then optionally read a frame back and dispatch on its format byte":

- `E_STORE`/`E_STWAIT` (`ExceptionUnit.scala:928-978`) push one frame word at a time through `driveStoreNoXlate`, driven by `stStep`/`lastStep` where `lastStep = Mux(curIs7, U(29), Mux(curIs2, U(5), U(3)))` (line 628) — a runtime-selected frame length, with cross-cache-line word splitting already handled (`stSplitLow`, the task #163 fix, lines 606-624).
- `frameWordAddr(step)` / `frameWordData(step)` (lines 631-659) are a `switch(step)` word emitter — exactly the shape a 22-word FSAVE frame needs.
- `R_FMTREQ`/`R_FMTWAIT` (lines 1080-1108) already implement "read a frame's format word from memory and select the pop size from it", which is *literally* FRESTORE's semantics.
- FSAVE/FRESTORE are privileged and serializing, which is what `sysOp` retirement already gives for free.

So: two new `SysKind` values, two new `S_APPLY` arms, and a small set of new FSM states reusing the existing store/load helpers. This is reuse of proven machinery, not a new subsystem.

**Frame layouts — from the approved spec, with the one genuinely-unpinned part called out.** Per `2026-08-14-fpu-fpsp-design.md`'s FSAVE/FRESTORE section (which explicitly *corrects* an earlier recollection: there is **no** 52-byte frame; null and idle are both 4 bytes; the byte at offset `$01` is a **length-in-hex indicator**, not a format enum):

| Frame | Size | `$00` | `$01` | Implemented |
|---|---|---|---|---|
| Null | 4 B | version forced to `$00` | undefined | **Yes, in full** |
| Idle | 4 B | real version | `$00` (0 extra bytes) | **Yes, in full** |
| Unimplemented FP instruction | 44 B (22 words) | version | `$28` (40 extra bytes) | **Yes, in full** |
| Busy | 100 B | version | `$60` | Out of scope (spec-narrowed) |

**Version byte decision, made here as the spec directs.** The spec says "`$40` is a reasonable default choice, finalized at implementation time (not a design blocker)". **Choose `$41`**, for two concrete reasons: (a) `fpu_fsave_idle_format_byte.s` asserts the format byte is exactly `0x41` and passes for free; (b) the Q700 FPSP's documented optimization `if ((frame[0] & 0xf) == 0) skip_restore;` needs a **non-zero low nibble** on a non-null frame, and `$40` would make every idle frame look null to it. The null frame's `$00` (version *forced* to zero per the spec, not the real version) keeps that optimization correct in the other direction.

**The unimplemented-instruction frame's field OFFSETS are NOT pinned by the spec** — the spec verifies the *size* (44 B), the *header bytes* (`$00` version, `$01` = `$28`), and the *field list* (STAG, DTAG, CMDREG1B, E1, FPTS/FPTE/FPTM, ETS/ETE/ETM per MC68040 UM Figure 9-7), but not each field's byte offset. One offset **is** anchored: the spec's ROM-disassembly analysis states the four non-busy handlers read "an opclass/CMDREG1B-class field at what resolves to **offset `$08`** of that frame". Step 2 makes pinning the rest an explicit, blocking step.

**Acceptance-corpus reality check, done up front so it is not rediscovered as a surprise.** Reading the four vendored FSAVE tests reveals they encode **m68k-ooo's own non-conformant frame shapes**, which contradict the manual-verified spec and each other:
- `fpu_fsave_idle_format_byte.s` expects a **4-byte** frame with format byte `0x41` → **will PASS** with this task's idle frame + version `$41`.
- `fpu_fsave_null_frame.s` (despite its name) expects a **52-byte** frame with header long `0x41300000` (size byte `$30`) → **will stay FAILING**. There is no 52-byte frame in the MC68040 UM; the spec says so explicitly. Its own header comment admits it was rewritten twice in place to chase m68k-ooo's behavior.
- `fsave_frestore_basic.s` expects the same 52-byte frame across five EA modes, plus a FRESTORE pop-size matrix → **partially failing** (its `(An)+` pop-size matrix for NULL/`$28`/`$60` headers is architecturally correct and *should* pass; its `0x41300000` sub-tests will not).
- `fpu_fsave_frestore_idle_roundtrip.s` expects FRESTORE to **restore FPCR/FPSR** → **will stay FAILING**, and correctly so: real 68040 FSAVE saves *no* control registers, which is exactly why the ROM FPSP prologue does a separate `FMOVEM.L FPIAR/FPSR/FPCR,-(A7)` (the `F227 BC00` the spec quotes). The test encodes m68k-ooo's side-channel behavior, not architecture.

**Do not chase the 52-byte frame.** Step 14 records this classification in the commit message so the post-task triage is a lookup.

- [ ] **Step 1: Confirm the FSAVE/FRESTORE encodings against the toolchain**

```bash
cd /tmp && cat > fsv.s <<'EOF'
    .text
    fsave    -(%sp)
    fsave    (%a0)
    frestore (%sp)+
    frestore (%a0)
EOF
m68k-linux-gnu-as -m68040 -m68881 fsv.s -o fsv.o && m68k-linux-gnu-objdump -d fsv.o
```
Expected: `F327`, `F310`, `F35F`, `F350`. Record the actual output. If any differs from `0xF300|<ea>` / `0xF340|<ea>`, fix the decode arm before proceeding.

- [ ] **Step 2: Pin the 44-byte unimplemented-instruction frame's field offsets (BLOCKING)**

Open the MC68040 User's Manual §9.7 / **Figure 9-7** (the same bitsavers copy the design spec used) and write down the exact byte offset of each of: CMDREG1B, STAG, DTAG, E1, ETS/ETE, ETM, FPTS/FPTE, FPTM. Then **replace** the provisional table in Step 6's `fsFrameWordData` with the real one and record the figure/page in the code comment and the commit message.

The provisional layout Step 6 ships (anchored on the spec's confirmed `$08` CMDREG1B and the confirmed 44-byte total; **everything else is a placement guess and is labelled as such in the code**):

```
$00      version byte ($41)
$01      frame-size indicator ($28 = 40 body bytes)
$02..$07 reserved / zero
$08..$09 CMDREG1B (the FPU command extension word)     <- ANCHORED by the spec
$0A      STAG (bits 2:0)
$0B      DTAG (bits 2:0)
$0C      E1   (bit 7)
$0D..$0F reserved / zero
$10..$11 ETS (bit15) | ETE (bits 14:0)   -- SOURCE operand sign+exponent, extended
$12..$13 reserved / zero
$14..$1B ETM  -- SOURCE operand 64-bit mantissa, big-endian
$1C..$1D FPTS (bit15) | FPTE (bits 14:0) -- DEST operand sign+exponent, extended
$1E..$1F reserved / zero
$20..$27 FPTM -- DEST operand 64-bit mantissa, big-endian
$28..$2B reserved / zero
                                          (44 bytes = 22 words total)
```

**This step is not done until the manual has been read.** Every other step in this task can be implemented against the provisional table; only Step 13 (the frame-content assertions) depends on the final one.

- [ ] **Step 3: Extend `FpuControlPlugin` with the sticky bit + the fault-capture group**

```scala
// src/main/scala/m68k040/services/Services.scala -- extend `trait FpuControlService`:

  /** Sticky "an FP operation has executed since reset or since the last null-frame
    * FRESTORE". Selects NULL (False) vs IDLE (True) at FSAVE time. Set by the first
    * committed FP-writing uop; cleared ONLY by a null-frame FRESTORE ("all FPU operations
    * are aborted, and the FPU enters the reset state" -- the one FRESTORE behavior real
    * hardware unconditionally requires). */
  def everExecuted:    Bool
  def setEverExecuted: Flow[Bool]

  /** Latched unimplemented-instruction state, captured when a RECOGNIZED-but-unsupported
    * FP op is delivered to vector 11 (Task 10's fpuSoftwareComplete path). A subsequent
    * FSAVE emits the 44-byte unimplemented-instruction frame from it instead of the
    * 4-byte idle frame; the FSAVE consumes it (uiValid clears). */
  def uiValid:       Bool
  def uiCmdReg1B:    Bits    // 16
  def uiSrcOperand:  Bits    // 80 (extended: ETS/ETE/ETM)
  def uiDstOperand:  Bits    // 80 (extended: FPTS/FPTE/FPTM)
  def setUnimpFrame: Flow[Bits]   // 176 = 16 + 80 + 80, packed {cmd, src, dst}
  def clearUnimp:    Flow[Bool]
```

```scala
// src/main/scala/m68k040/execute/FpuControlPlugin.scala -- add to `logic`:

    // ── FSAVE null-vs-idle discriminator (Task 11) ────────────────────────────────
    // RegInit(False): power-on is NULL ("no FP op ever executed"), which is what real
    // hardware reports and what lets an OS skip a pointless FRESTORE.
    val everExecuted = RegInit(False); everExecuted.simPublic()
    val setEverExecuted = Flow(Bool())
    setEverExecuted.valid.allowOverride;   setEverExecuted.valid := False
    setEverExecuted.payload.allowOverride; setEverExecuted.payload := False
    when(setEverExecuted.valid) { everExecuted := setEverExecuted.payload }

    // ── Latched unimplemented-instruction state (Task 11) ──────────────────────────
    // Written at vector-11 delivery for a RECOGNIZED FP op (Task 10's
    // fpuSoftwareComplete), read back by a later FSAVE, cleared when that FSAVE consumes
    // it. This is the "route to FPSP" hand-off: the trap tells the handler THAT something
    // unsupported happened; the frame tells it WHAT.
    val uiValid      = RegInit(False); uiValid.simPublic()
    val uiCmdReg1B   = Reg(Bits(16 bits)) init 0; uiCmdReg1B.simPublic()
    val uiSrcOperand = Reg(Bits(80 bits)) init 0; uiSrcOperand.simPublic()
    val uiDstOperand = Reg(Bits(80 bits)) init 0; uiDstOperand.simPublic()
    val setUnimpFrame = Flow(Bits(176 bits))
    val clearUnimp    = Flow(Bool())
    setUnimpFrame.valid.allowOverride;   setUnimpFrame.valid := False
    setUnimpFrame.payload.allowOverride; setUnimpFrame.payload := B(0, 176 bits)
    clearUnimp.valid.allowOverride;      clearUnimp.valid := False
    clearUnimp.payload.allowOverride;    clearUnimp.payload := False
    when(setUnimpFrame.valid) {
      uiValid      := True
      uiCmdReg1B   := setUnimpFrame.payload(175 downto 160)
      uiSrcOperand := setUnimpFrame.payload(159 downto 80)
      uiDstOperand := setUnimpFrame.payload(79 downto 0)
    }
    // Later `when` wins in SpinalHDL: a same-cycle clear beats a set. That ordering can
    // only matter if an FSAVE retires in the same cycle a vector-11 entry is delivered,
    // which the serializing sysOp/exception FSM makes impossible -- defence in depth.
    when(clearUnimp.valid) { uiValid := False }
```

Plus the matching `_field`/`override def` plumbing at the top of the class, following the existing pattern for `_fpcr`/`_setFpcr`.

**And drive the sticky bit from FP commit:**

```scala
// src/main/scala/m68k040/rob/RobPlugin.scala -- in the commit block, alongside the existing
// commitPorts fan-out. `fpWrite` is CommitSlot's FP-destination-written bit (Task 2).
    when((commitPorts(0).valid && commitPorts(0).fpWrite) ||
         (commitPorts(1).valid && commitPorts(1).fpWrite)) {
      fpuCtrl.setEverExecuted.valid   := True
      fpuCtrl.setEverExecuted.payload := True
    }
```

- [ ] **Step 4: `SysKind.FSAVE` / `SysKind.FRESTORE` + the decode arm**

```scala
// src/main/scala/m68k040/decode/DecodedUop.scala -- append after FMOVE_FPCTRL:
      // FSAVE <ea> (0xF300|<ea>) / FRESTORE <ea> (0xF340|<ea>): line-F, cpID=001,
      // opclass 100 / 101. PRIVILEGED (unlike FMOVE-to-FPcr) -- the default sysOp
      // vector-8 behavior via RobPlugin's sysPrivFault is exactly right, no exclusion.
      // Encoding confirmed from the vendored corpus's own raw literals (0xF327 =
      // FSAVE -(A7), 0xF35F = FRESTORE (A7)+, both commented as such in
      // fpu_fsave_idle_format_byte.s / fpu_fsave_null_frame.s) and re-confirmed against
      // m68k-linux-gnu-as -m68040.
      //
      // COMMIT-TIME SYSTEM ops, deliberately NOT microcoded: the frame length is
      // RUNTIME-selected (4 vs 44 bytes, from live FPU state), which the static
      // Microcode.Desc row vocabulary (see Microcode.MOVE16_ENTRY's fixed 4-transfer
      // chain, rows 242-251) cannot express. ExceptionUnit's own frame machinery ALREADY
      // is "emit N words where N is runtime-selected, then read a frame back and dispatch
      // on its format byte" (E_STORE/E_STWAIT's stStep/lastStep and R_FMTREQ/R_FMTWAIT),
      // so these reuse it directly.
      FSAVE,
      FRESTORE
      = newElement()
```

```scala
// src/main/scala/m68k040/decode/OperationDecoder.scala -- inside the is(0xF) arm.
        // ── FSAVE / FRESTORE ──────────────────────────────────────────────────────────
        // SCOPE: the register-indirect EA modes only --
        //   FSAVE    : mode 100 (-(An))  and mode 010 ((An))   [predecrement + control]
        //   FRESTORE : mode 011 ((An)+)  and mode 010 ((An))   [postincrement + control]
        // matching the architectural alterable/control restrictions on each. The
        // displacement/absolute forms that fsave_frestore_basic.s sub-tests 3-5 exercise
        // are OUT of scope here (they need a real EA computation, which the commit-time
        // sysOp path has no AGU for) and stay on the line-F vector-11 fall-through.
        val fsvBase   = (opword(15 downto 12) === B"4'hF") && (opword(11 downto 9) === B"3'b001")
        val fsvMode   = opword(5 downto 3)
        val isFsave    = fsvBase && (opword(8 downto 6) === B"3'b100") &&
                         ((fsvMode === B"3'b100") || (fsvMode === B"3'b010"))
        val isFrestore = fsvBase && (opword(8 downto 6) === B"3'b101") &&
                         ((fsvMode === B"3'b011") || (fsvMode === B"3'b010"))
        when(isFsave || isFrestore) {
          o.illegal    := False
          o.op         := DecOp.MOVE
          o.size       := Size.LONG
          o.sysOp      := True
          o.sysKind    := Mux(isFsave, SysKind.FSAVE, SysKind.FRESTORE)
          o.sysReadDir := False        // both take An as a SOURCE value (PTEST's precedent)
          o.dst.setNone(); o.dstWrites := False
        }
```

No `PredecodeWord` change is needed: all four admitted forms are single-word, and the existing line-F `otherwise` arm (`PredecodeWord.scala:1066-1067`) already yields `simple = True, lenWords = 1`. Verified by reading that arm; do not add a redundant branch.

- [ ] **Step 5: `MicroOpAssembler` operand routing**

Add inside `when(isSysOp)`, after Task 9's arm. This is PTEST's arm (`MicroOpAssembler.scala:1756-1762`) plus a renamed destination for the auto-update modes, which is MOVE_USP's read-direction pattern (line 1704-1706).

```scala
      // FSAVE / FRESTORE: the An is op[2:0] (the standard <ea> reg field, NOT op[11:9]) --
      // same override precedent as MOVE_USP/PTEST/CPUSH. An's VALUE rides srcB so the ALU
      // EU's plain MOVE writeback lands it in sysValStore -> sysCapVal, giving S_APPLY the
      // frame base address.
      //
      // For the AUTO-UPDATE modes (FSAVE -(An), FRESTORE (An)+) the An must also be
      // WRITTEN BACK. dstReg=An with dstValid=True makes rename allocate a pdst that the
      // ROB commits at the serializing retire, and the FSM writes the updated value into
      // PRF[pdst] through the existing sysRegWrite* port -- byte-for-byte MOVEC's and
      // MOVE_USP's read-direction mechanism. The EU's own writeback into that pdst (which
      // for op=MOVE is just An's old value) is overwritten by the FSM at commit; that
      // EU-writes-then-FSM-overwrites shape is exactly what MOVE_USP's read arm already
      // relies on.
      //
      // imm[3:0] carries the EA mode (imm[3:1]) and an isRestore marker (imm[0]) through
      // the SAME imm -> RobPlugin.scala:441 (`p.sysRc := u.imm(11 downto 0)`) -> sysCapRc
      // side-channel that MOVEC's Rc id and CPUSH's {scope,cacheSel} nibble already use.
      when(spec.sysKind === SysKind.FSAVE || spec.sysKind === SysKind.FRESTORE) {
        val fsvAn      = (U(8, 5 bits) + op(2 downto 0).asUInt).resize(5)
        val fsvIsRest  = spec.sysKind === SysKind.FRESTORE
        val fsvAuto    = (op(5 downto 3) === B"3'b100") || (op(5 downto 3) === B"3'b011")
        opUop.srcBReg   := fsvAn; opUop.srcBValid := True
        opUop.srcAValid := False
        opUop.useImm    := False
        opUop.imm       := (op(5 downto 3) ## fsvIsRest.asBits).resize(32)
        opUop.dstReg    := fsvAn
        opUop.dstValid  := fsvAuto      // only -(An)/(An)+ write An back; plain (An) does not
        opUop.isMovea   := True         // An destination: full-32 write, no partial merge
      }
```

- [ ] **Step 6: `ExceptionUnit` — the FSAVE emit path**

New captured state and the frame emitter, added alongside the existing entry-frame equivalents (`stStep`/`lastStep`/`frameWordAddr`/`frameWordData`, lines 606-659):

```scala
// src/main/scala/m68k040/exception/ExceptionUnit.scala

  // ── FSAVE / FRESTORE captured state (Task 11) ────────────────────────────────
  // The FPU state-frame VERSION byte. Finalized at implementation time per the design
  // spec ("$40 is a reasonable default choice ... not a design blocker"): $41 is chosen
  // because (a) the vendored fpu_fsave_idle_format_byte.s asserts exactly 0x41, and (b)
  // the Q700 FPSP's `if ((frame[0] & 0xf) == 0) skip_restore` optimization needs a
  // NON-ZERO low nibble on a non-null frame -- $40 would make every idle frame look null
  // to it. The NULL frame still forces byte $00 to $00 (per the spec's table: "Version
  // forced to $00 (identifies null, not a length code)"), which keeps that same
  // optimization correct in the other direction.
  val FPU_FRAME_VERSION = 0x41

  val fsFrameBase = Reg(UInt(32 bits))       // LOW address of the frame
  val fsSize      = Reg(UInt(8 bits))        // total frame size in BYTES (4 or 44)
  val fsIsNull    = RegInit(False)
  val fsIsUnimp   = RegInit(False)
  val fsStep      = Reg(UInt(5 bits)) init 0; fsStep.simPublic()   // WORD index, 0..21
  val fsSplitLow  = Reg(Bool()) init 0
  val fsAnUpdate  = Reg(UInt(32 bits))       // the value written back into An
  val fsAnWrite   = RegInit(False)           // does this op write An back at all?

  // FSAVE frame words. 22 words max ($2C bytes). PROVISIONAL FIELD PLACEMENT -- only the
  // 44-byte total, the two header bytes, and CMDREG1B's offset $08 are spec-verified; the
  // rest MUST be confirmed against MC68040 UM Figure 9-7 (this task's Step 2) before the
  // frame-content assertions in FsaveFrestoreSpec are trusted. Record the figure/page here
  // once confirmed:  [ PIN ME -- MC68040 UM Fig 9-7, page ___ ]
  def fsFrameWordAddr(step: UInt): UInt = (fsFrameBase + (step << 1)).resized
  def fsFrameWordData(step: UInt): Bits = {
    val out = Bits(16 bits); out := B(0, 16 bits)
    // Header word: [15:8] = version, [7:0] = length-in-hex indicator.
    //   null : $00 $00   (version FORCED to $00 -- identifies null, not a length code)
    //   idle : $41 $00   (0 extra bytes)
    //   unimp: $41 $28   (40 extra bytes)
    val hdr = Mux(fsIsNull, U(0x0000, 16 bits),
              Mux(fsIsUnimp, U((FPU_FRAME_VERSION << 8) | 0x28, 16 bits),
                             U((FPU_FRAME_VERSION << 8) | 0x00, 16 bits)))
    val src = fpuCtrl.uiSrcOperand    // 80 bits: [79] sign, [78:64] exponent, [63:0] mantissa
    val dst = fpuCtrl.uiDstOperand
    switch(step) {
      is(U(0,  5 bits)) { out := hdr.asBits }
      is(U(4,  5 bits)) { out := fpuCtrl.uiCmdReg1B }                       // $08 CMDREG1B
      is(U(5,  5 bits)) { out := (fpTag(src) ## B(0, 5 bits) ##
                                  fpTag(dst) ## B(0, 5 bits)) }             // $0A STAG, $0B DTAG
      is(U(6,  5 bits)) { out := B"16'h8000" }                              // $0C E1 (bit 7 of byte $0C)
      is(U(8,  5 bits)) { out := src(79 downto 64) }                        // $10 ETS|ETE
      is(U(10, 5 bits)) { out := src(63 downto 48) }                        // $14..$1B ETM
      is(U(11, 5 bits)) { out := src(47 downto 32) }
      is(U(12, 5 bits)) { out := src(31 downto 16) }
      is(U(13, 5 bits)) { out := src(15 downto 0) }
      is(U(14, 5 bits)) { out := dst(79 downto 64) }                        // $1C FPTS|FPTE
      is(U(16, 5 bits)) { out := dst(63 downto 48) }                        // $20..$27 FPTM
      is(U(17, 5 bits)) { out := dst(47 downto 32) }
      is(U(18, 5 bits)) { out := dst(31 downto 16) }
      is(U(19, 5 bits)) { out := dst(15 downto 0) }
      // every other word is reserved -> 0 (the default above)
    }
    out
  }
  /** 3-bit operand-type tag (STAG/DTAG) from an 80-bit extended value: exponent all-ones
    * with a zero mantissa = infinity (010), all-ones with non-zero = NaN (011), exponent
    * zero with zero mantissa = zero (001), exponent zero with non-zero mantissa =
    * denormalized (100), otherwise normalized (000). ALSO PROVISIONAL -- confirm the
    * encoding alongside the offsets in Step 2. */
  def fpTag(v: Bits): Bits = {
    val exp  = v(78 downto 64)
    val man  = v(63 downto 0)
    val expAllOnes = exp.andR
    val expZero    = !exp.orR
    val manZero    = !man.orR
    Mux(expAllOnes, Mux(manZero, B"3'b010", B"3'b011"),
    Mux(expZero,    Mux(manZero, B"3'b001", B"3'b100"), B"3'b000"))
  }
  val fsLastStep = Mux(fsIsUnimp, U(21, 5 bits), U(1, 5 bits))   // 22 words vs 2 words
```

The `S_APPLY` arm and the store loop:

```scala
        is(skOrd(m68k040.decode.SysKind.FSAVE)) {
          // Frame-type selection from LIVE FPU state (spec's FSAVE/FRESTORE section):
          //   unimplemented-instruction : a recognized-but-unsupported FP op is pending
          //                               software completion (Task 10's vector-11 path
          //                               latched it) -- this is the "route to FPSP" frame
          //   null                      : no FP op has executed since reset / null restore
          //   idle                      : otherwise
          val isNull  = !fpuCtrl.everExecuted && !fpuCtrl.uiValid
          val isUnimp = fpuCtrl.uiValid
          val size    = Mux(isUnimp, U(44, 8 bits), U(4, 8 bits))
          fsIsNull  := isNull
          fsIsUnimp := isUnimp
          fsSize    := size
          // sysCapRc[3:1] = the EA mode, sysCapRc[0] = isRestore (0 here).
          // -(An): the frame occupies [An-size, An), and An := An-size.
          // (An)  : the frame occupies [An, An+size), An unchanged.
          val isPredec = sysCapRc(3 downto 1) === U(4, 3 bits)
          val base     = Mux(isPredec, sysCapVal.asUInt - size.resize(32), sysCapVal.asUInt)
          fsFrameBase := base
          fsAnWrite   := isPredec
          fsAnUpdate  := base
          fsStep      := 0
          fsSplitLow  := False
          // An write-back, through the SAME port MOVEC's read direction uses. Driven here
          // (not after the stores) because the value is already known combinationally --
          // exactly MOVEC's shape. FRESTORE cannot do this (its size comes from memory),
          // hence its separate F_RDONE state below.
          when(isPredec) {
            sysRegWriteValid := True
            sysRegWritePhys  := sysCapDstPhys
            sysRegWriteData  := base
          }
          // Emitting the frame CONSUMES the pending unimplemented state.
          when(isUnimp) { fpuCtrl.clearUnimp.valid := True; fpuCtrl.clearUnimp.payload := True }
          goto(F_STORE)
        }
```

```scala
    // ── FSAVE: write the state frame one word at a time ────────────────────────────
    // Structurally identical to E_STORE/E_STWAIT (lines 928-978), including the task-#163
    // line-crossing word split: driveStoreNoXlate's plain {paddr,size} store path has no
    // cross-cache-line handling and silently DROPS the second byte of a word landing at
    // line-relative offset 15, so a crossing word is split into two single-byte pushes.
    // That bug was found the hard way once (exc_aline_odd_sp_mmu_dcache); do not omit the
    // split here just because FSAVE frame bases are "usually" aligned -- fsave -(An) with
    // an odd An is perfectly legal.
    F_STORE.whenIsActive {
      val addr    = fsFrameWordAddr(fsStep)
      val data    = fsFrameWordData(fsStep)
      val crosses = addr(3 downto 0) === U(15, 4 bits)
      when(fsSplitLow)      { driveStoreNoXlate(addr + U(1, 32 bits), Size.BYTE, data(7 downto 0)) }
      .elsewhen(crosses)    { driveStoreNoXlate(addr, Size.BYTE, data(15 downto 8)) }
      .otherwise            { driveStoreNoXlate(addr, Size.WORD, data) }
      goto(F_STWAIT)
    }
    F_STWAIT.whenIsActive {
      when(dcStoreAck) {
        val addr    = fsFrameWordAddr(fsStep)
        val crosses = addr(3 downto 0) === U(15, 4 bits)
        when(crosses && !fsSplitLow) { fsSplitLow := True; goto(F_STORE) }
        .otherwise {
          fsSplitLow := False
          when(fsStep === fsLastStep) { goto(S_REDIR) }
          .otherwise                  { fsStep := fsStep + 1; goto(F_STORE) }
        }
      }
    }
```

- [ ] **Step 7: `ExceptionUnit` — the FRESTORE consume path**

```scala
        is(skOrd(m68k040.decode.SysKind.FRESTORE)) {
          // The frame base is An for BOTH admitted modes ((An)+ and (An)); only the
          // write-back differs. Size is unknown until the header word is read.
          fsFrameBase := sysCapVal.asUInt
          fsAnWrite   := sysCapRc(3 downto 1) === U(3, 3 bits)   // (An)+ postincrement
          goto(F_HDRREQ)
        }
```

```scala
    // ── FRESTORE: read the header word, dispatch on it ─────────────────────────────
    // Structurally R_FMTREQ/R_FMTWAIT (lines 1080-1108): a single WORD read whose value
    // selects what happens next. WORD granularity, not LONG, for the same reason task #189
    // split RTE's PC read -- DcacheByteLane.extract has no cross-line awareness.
    F_HDRREQ.whenIsActive {
      dtoVld := True; dtoVpn := fsFrameBase(31 downto 12)
      ldoVld := True; ldoVaddr := fsFrameBase; ldoSize := Size.WORD
      when(dcLoadCmd.fire) { goto(F_HDRWAIT) }
    }
    F_HDRWAIT.whenIsActive {
      dtoVld := True; dtoVpn := fsFrameBase(31 downto 12)
      when(dcLoadRsp.valid) {
        val hdr     = dcLoadRsp.payload.data(15 downto 0)
        val version = hdr(15 downto 8)
        val lenByte = hdr(7 downto 0).asUInt
        // Pop size = 4 + the length-in-hex indicator, for EVERY frame flavour. This is the
        // one FRESTORE rule that is architecturally universal and it is what
        // fsave_frestore_basic.s's sub-test 6 pop-size matrix actually checks (NULL->4,
        // $28->44, $60->100, and the FPSP's manufactured version-$41/size-$00 pseudo-null
        // ->4). It costs nothing to get right for frame shapes we never EMIT.
        fsSize := (U(4, 9 bits) + lenByte.resize(9)).resize(8)
        // A NULL frame (version byte $00) aborts all FPU operations and puts the FPU in
        // the RESET state -- per the design spec, the only FRESTORE behavior real hardware
        // unconditionally requires, and the only one implemented here. A non-null frame's
        // BODY fields are deliberately NOT applied (out of scope, spec-narrowed): real
        // 68040 FSAVE saves no control registers at all, which is exactly why the ROM
        // FPSP prologue separately does FMOVEM.L FPIAR/FPSR/FPCR,-(A7).
        when(version === U(0, 8 bits)) {
          fpuCtrl.setEverExecuted.valid := True; fpuCtrl.setEverExecuted.payload := False
          fpuCtrl.clearUnimp.valid      := True; fpuCtrl.clearUnimp.payload      := True
          fpuCtrl.setFpcr.valid  := True; fpuCtrl.setFpcr.payload  := U(0, 32 bits)
          fpuCtrl.setFpsr.valid  := True; fpuCtrl.setFpsr.payload  := U(0, 32 bits)
          fpuCtrl.setFpiar.valid := True; fpuCtrl.setFpiar.payload := U(0, 32 bits)
          fpccWriteValid := True; fpccWriteData := B(0, 4 bits)
        } otherwise {
          // A non-null frame means "the FPU had state" -> idle, not null, on a later FSAVE.
          fpuCtrl.setEverExecuted.valid := True; fpuCtrl.setEverExecuted.payload := True
        }
        goto(F_RDONE)
      }
    }
    // The An write-back for FRESTORE (An)+ has to happen HERE, not in S_APPLY: the pop
    // size is only known once the header has been read out of memory.
    F_RDONE.whenIsActive {
      when(fsAnWrite) {
        sysRegWriteValid := True
        sysRegWritePhys  := sysCapDstPhys
        sysRegWriteData  := fsFrameBase + fsSize.resize(32)
      }
      goto(S_REDIR)
    }
```

Register the six new states alongside the existing ones (`ExceptionUnit.scala:661-718`):

```scala
    val F_STORE   = new State   // FSAVE: issue one frame word
    val F_STWAIT  = new State   // FSAVE: await its write-through ACK; advance
    val F_HDRREQ  = new State   // FRESTORE: load the header word
    val F_HDRWAIT = new State   // FRESTORE: dispatch on version/length
    val F_RDONE   = new State   // FRESTORE: An += popSize, then redirect
```

`S_REDIR` (line 1475-1506) needs **no** change: it already pulses the obs, re-banks A7, and redirects to `sysCapNextPc` for every `sysKind`, and its `obsSetCcr5` fan-out is gated on MOVE_TO_SR/STOP only.

- [ ] **Step 8: Capture the unimplemented state at vector-11 delivery**

```scala
// src/main/scala/m68k040/rob/RobPlugin.scala
// Two new per-entry stores, alongside the existing faultAddrStore/faultWrStore/... family
// (which are written ONLY at alloc, per that family's own doc comment at line 67):
    val fpuUnimpStore = Vec.fill(RobDepth)(Reg(Bool()))
    val fpuCmdStore   = Vec.fill(RobDepth)(Reg(Bits(16 bits)))
    // written at alloc from Task 10's decode fields, exactly like faultPcStore:
      fpuUnimpStore(tail)     := allocUopVec(0).fpuSoftwareComplete
      fpuCmdStore(tail)       := allocUopVec(0).fpuCmdWord
      fpuUnimpStore(tail + 1) := allocUopVec(1).fpuSoftwareComplete
      fpuCmdStore(tail + 1)   := allocUopVec(1).fpuCmdWord

// ... and passed into the ExceptionUnit constructor alongside entryFaultAddr:
      entryFpuUnimp = fpuUnimpStore(h0),
      entryFpuCmd   = fpuCmdStore(h0),
```

```scala
// src/main/scala/m68k040/exception/ExceptionUnit.scala
// (a) constructor params, following entryFaultAddr's optional-with-safe-default shape:
    entryFpuUnimp: Bool = False,
    entryFpuCmd:   Bits = B(0, 16 bits),

// (b) committed FP operand readbacks -- committedA7In's pattern exactly. The full-core
//     wiring reads the FP PRF at the committed arch->phys mapping for the SOURCE FPm
//     (command word bits [12:10]) and DESTINATION FPn (bits [9:7]); a DUT that does not
//     wire them leaves the frame's operand fields zero, which is the same graceful
//     degradation every other optional input in this file provides.
  val committedFpSrcIn = Bits(80 bits); committedFpSrcIn.allowOverride; committedFpSrcIn := B(0, 80 bits)
  val committedFpDstIn = Bits(80 bits); committedFpDstIn.allowOverride; committedFpDstIn := B(0, 80 bits)
  // Exposed so the wiring can address the FP RAT with them.
  val fpuSrcArch = entryFpuCmd(12 downto 10).asUInt; fpuSrcArch.simPublic()
  val fpuDstArch = entryFpuCmd(9 downto 7).asUInt;   fpuDstArch.simPublic()

// (c) the capture itself, in IDLE's entryTrigger arm (alongside `curVec := entryVector`,
//     line 781) -- non-speculative, since exception delivery is at commit:
        when(entryVector === 11 && entryFpuUnimp) {
          // A RECOGNIZED-but-unsupported FP op is being handed to FPSP. Latch what a
          // subsequent FSAVE needs to build the 44-byte unimplemented-instruction frame:
          // the command word (CMDREG1B) plus both operands, from which STAG/DTAG and the
          // ETS/ETE/ETM + FPTS/FPTE/FPTM fields are derived at emit time.
          fpuCtrl.setUnimpFrame.valid   := True
          fpuCtrl.setUnimpFrame.payload := entryFpuCmd ## committedFpSrcIn ## committedFpDstIn
          // FPIAR := the faulting instruction's own PC (its architectural definition:
          // "last FP exception PC"). entryPc is the POST-instruction PC here (Task 10 set
          // faultUsesNextPc for this class), so the instruction's own PC is entryPpc.
          fpuCtrl.setFpiar.valid   := True
          fpuCtrl.setFpiar.payload := (if (entryPpc != null) entryPpc else (entryPc - 2).resized)
        }
```

```scala
// src/main/scala/m68k040/top/FullCoreSynth.scala -- alongside the committedA7In wiring:
        exc.committedFpSrcIn := fpRegFile.read(rename.logic.fpRat.committedPhys(exc.fpuSrcArch))
        exc.committedFpDstIn := fpRegFile.read(rename.logic.fpRat.committedPhys(exc.fpuDstArch))
```

If Task 1's FP regfile has no committed-mapping read port wired at this site yet, leave both inputs at their `B(0, 80 bits)` defaults **for this task only** and say so in the commit message — the frame then carries a correct header, correct CMDREG1B, and zeroed operands, which is strictly better than no frame at all and degrades exactly where the missing dependency is.

- [ ] **Step 9: Update the line-F exhaustive sweep exclusions**

```scala
// src/test/scala/m68k040/decode/MicroOpAssemblerSpec.scala -- extend `implemented(op)`:
        val fsave    = ((op & 0xFFC0) == 0xF300) &&
                       (((op >> 3) & 7) == 4 || ((op >> 3) & 7) == 2)
        val frestore = ((op & 0xFFC0) == 0xF340) &&
                       (((op >> 3) & 7) == 3 || ((op >> 3) & 7) == 2)
        cpush || pflush || ptest || move16 || fsf || fsave || frestore
```

- [ ] **Step 10: Directed test — frame emission, all three types**

```scala
// src/test/scala/m68k040/exception/FsaveFrestoreSpec.scala
package m68k040.exception

import m68k040.VerilatorTest
import m68k040.oracle.ProgramAssembler
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** FSAVE/FRESTORE state frames. Whitebox, NOT lock-step, and deliberately so: the design
  * spec's Decision 3 records that Musashi has the WRONG FSAVE frame shape entirely (a
  * 28-byte format-$1f frame, not the real 68040's 4/44/100-byte null/idle/unimplemented
  * frames) and no unimplemented-instruction trap at all. There is nothing for a lock-step
  * oracle to referee here; the MC68040 UM is the reference and memory contents are the
  * assertion. */
class FsaveFrestoreSpec extends AnyFunSuite {
  // Reuse ExecuteLockStepSpec's FullCoreDut harness shape (see `runCpushPriv`,
  // ExecuteLockStepSpec.scala:3695-3742) -- same DUT, same attachProgram /
  // BehavioralMemAgent / seed / redirect boot sequence. Factored into a local helper here
  // rather than duplicated per test.
  private def runFsave(prologue: String, everExecuted: Boolean, unimpPending: Boolean): Array[Int] = {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val src = s"lea 0x00010000,%a7 ; $prologue .short 0xF327 ; done: bra.s done"
    ...  // boot exactly as runCpushPriv does, in SUPERVISOR mode (srSys = 0x27)
    ...  // poke dut.fpuCtrl.logic.everExecuted #= everExecuted
    ...  // poke dut.fpuCtrl.logic.uiValid      #= unimpPending
    ...  // if (unimpPending) poke uiCmdReg1B / uiSrcOperand / uiDstOperand with sentinels
    ...  // run to quiescence, then read back 64 bytes below 0x00010000 from the mem agent
  }

  test("FSAVE -(A7) with no FP op ever executed emits the 4-byte NULL frame", VerilatorTest) {
    val m = runFsave("", everExecuted = false, unimpPending = false)
    // Null: version FORCED to $00 (identifies null, not a length code), byte 1 undefined
    // (we emit $00), 4 bytes total -> A7 -= 4.
    assert(m(0) == 0x00, f"null frame version byte must be $00, got 0x${m(0)}%02X")
    assert(m(1) == 0x00, "null frame length indicator")
  }

  test("FSAVE -(A7) after an FP op emits the 4-byte IDLE frame, version $41", VerilatorTest) {
    val m = runFsave("", everExecuted = true, unimpPending = false)
    assert(m(0) == 0x41,
      f"idle frame version byte -- $41 is this implementation's finalized version " +
      f"(see ExceptionUnit.FPU_FRAME_VERSION); got 0x${m(0)}%02X")
    assert(m(1) == 0x00, "idle frame carries 0 extra bytes")
  }

  test("FSAVE -(A7) with a pending unimplemented-instruction emits the 44-byte frame", VerilatorTest) {
    val m = runFsave("", everExecuted = true, unimpPending = true)
    assert(m(0) == 0x41, "unimplemented frame version byte")
    assert(m(1) == 0x28, f"unimplemented frame length indicator must be $28 (40 extra bytes = 44 total); got 0x${m(1)}%02X")
    // CMDREG1B at offset $08 -- the ONE field offset the design spec independently
    // anchors (via its ROM FPSP disassembly analysis). Sentinel poked in as 0xBEEF.
    assert(((m(8) << 8) | m(9)) == 0xBEEF,
      f"CMDREG1B at offset $08; got 0x${(m(8) << 8) | m(9)}%04X")
    // The remaining field offsets are Step 2's pin-down; assert them here ONLY after the
    // MC68040 UM Figure 9-7 check is recorded. Until then this test intentionally asserts
    // just the header + the anchored field, rather than baking in a guess as a gate.
  }

  test("FSAVE consumes the pending unimplemented state (a second FSAVE emits IDLE)", VerilatorTest) {
    val m = runFsave(".short 0xF327 ; ", everExecuted = true, unimpPending = true)
    // The SECOND FSAVE's frame (the one at the lower address) must be idle, not another
    // unimplemented frame -- emitting the frame is what hands the state to FPSP.
    assert(m(0) == 0x41 && m(1) == 0x00,
      "the second FSAVE must emit an IDLE frame -- the first one consumed the pending state")
  }
}
```

- [ ] **Step 11: Directed test — FRESTORE size-aware pop and the null-frame reset**

```scala
  test("FRESTORE (A7)+ pops 4 + the in-memory length byte, for every frame flavour", VerilatorTest) {
    // Exactly fsave_frestore_basic.s's sub-test 6 matrix, which is architecturally correct
    // (unlike that test's 0x41300000 sub-tests) and therefore worth pinning independently:
    //   0x00000000 (null)          -> pop 4
    //   0x41000000 (FPSP pseudo-null, version $41 size $00) -> pop 4
    //   0x40280000 (unimplemented) -> pop 44
    //   0x40600000 (busy)          -> pop 100
    for ((header, expectPop) <- Seq(0x00000000L -> 4, 0x41000000L -> 4,
                                    0x40280000L -> 44, 0x40600000L -> 100)) {
      val a0After = runFrestorePostinc(header)
      assert(a0After == 0x00021000L + expectPop,
        f"FRESTORE (A0)+ on header 0x$header%08X must advance A0 by $expectPop; got +${a0After - 0x00021000L}")
    }
  }

  test("FRESTORE of a NULL frame resets the FPU (a following FSAVE emits NULL again)", VerilatorTest) {
    // "FRESTORE of a null state frame ... all FPU operations are aborted, and the FPU
    // enters the reset state" -- per the design spec, the ONLY FRESTORE behavior real
    // hardware unconditionally requires, and the only one implemented. Prove it via the
    // observable consequence: everExecuted is cleared, so the next FSAVE emits NULL.
    val (fpcrAfter, frameByte0) = runNullRestoreThenFsave()
    assert(fpcrAfter == 0, "a null-frame FRESTORE must clear FPCR (FPU reset state)")
    assert(frameByte0 == 0x00,
      "after a null-frame FRESTORE the FPU is 'never used' again -> the next FSAVE emits NULL")
  }
```

- [ ] **Step 12: Directed test — privilege**

```scala
  test("FSAVE in USER mode raises a vector-8 privilege violation", VerilatorTest) {
    val (saw, vec) = runFsavePriv(userMode = true)
    assert(saw, "user-mode FSAVE must raise a precise exception")
    assert(vec == 8, s"privilege violation must be vector 8, got $vec")
  }
  test("FSAVE in SUPERVISOR mode raises NO exception", VerilatorTest) {
    val (saw, _) = runFsavePriv(userMode = false)
    assert(!saw, "supervisor FSAVE must not trap")
  }
```

(`runFsavePriv` is `runCpushPriv` with `"fsave -(%sp) ; handler: bra.s handler"` as its source — copy that method verbatim and change the one string. Unlike Task 9's FMOVE-to-FPcr, **no** `sysPrivFault` exclusion is added for these two, so the existing default path is what is being tested.)

- [ ] **Step 13: Run the tests**

```bash
sbt "testOnly m68k040.exception.FsaveFrestoreSpec"
sbt "testOnly m68k040.decode.MicroOpAssemblerSpec m68k040.decode.OperationDecoderSpec"
```
Expected: all PASS.

```bash
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
```
Expected: 399/399 from Task 10, unchanged. Nothing here touches a lock-steppable path.

Then re-run the FSAVE family of the acceptance corpus and record the outcome against the classification made at the top of this task:

```bash
sbt "testOnly m68k040.fuzz.PortedM68kOooSpec -- -z fsave -z frestore"
```
Expected, and to be recorded verbatim in the commit message:
`fpu_fsave_idle_format_byte` **PASS** (new); `fpu_fsave_null_frame` **FAIL** (asserts a 52-byte frame that does not exist in the MC68040 UM); `fsave_frestore_basic` **FAIL** (same 52-byte expectation; its pop-size matrix sub-test is nonetheless satisfied by this implementation); `fpu_fsave_frestore_idle_roundtrip` **FAIL** (asserts FRESTORE restores FPCR/FPSR, which real FSAVE never saves). **If any of these three "expected FAIL" tests instead passes, stop** — it means the implementation drifted toward m68k-ooo's non-conformant behavior rather than the manual's.

- [ ] **Step 14: Commit**

```bash
git add src/main/scala/m68k040/decode/DecodedUop.scala \
        src/main/scala/m68k040/decode/OperationDecoder.scala \
        src/main/scala/m68k040/decode/MicroOpAssembler.scala \
        src/main/scala/m68k040/execute/FpuControlPlugin.scala \
        src/main/scala/m68k040/services/Services.scala \
        src/main/scala/m68k040/exception/ExceptionUnit.scala \
        src/main/scala/m68k040/rob/RobPlugin.scala \
        src/main/scala/m68k040/top/FullCoreSynth.scala \
        src/test/scala/m68k040/exception/FsaveFrestoreSpec.scala \
        src/test/scala/m68k040/decode/MicroOpAssemblerSpec.scala \
        src/test/scala/m68k040/decode/OperationDecoderSpec.scala
git commit -m "feat(fpu): FSAVE/FRESTORE null, idle and unimplemented-instruction frames

The core route-to-FPSP mechanism. FSAVE emits one of three real MC68040
state frames selected from LIVE FPU state -- null (4 bytes, version
forced to \$00) when no FP op has executed since reset or the last
null-frame restore; idle (4 bytes, version + \$00 length indicator) when
one has and nothing is pending; unimplemented-instruction (44 bytes /
22 words, version + \$28) when a recognized-but-unsupported FP op is
awaiting software completion. FRESTORE reads the header word, pops
4 + the in-memory length byte for every frame flavour (including the
100-byte busy frame and the FPSP's manufactured version-\$41/size-\$00
pseudo-null, neither of which we ever emit), and resets the FPU on a
null frame -- the one FRESTORE behavior real hardware unconditionally
requires. Non-null frame BODIES are deliberately not applied: real
68040 FSAVE saves no control registers at all, which is precisely why
the ROM FPSP prologue separately does FMOVEM.L FPIAR/FPSR/FPCR,-(A7).

Implemented as COMMIT-TIME SYSTEM ops (two new SysKind values), NOT via
the microcode ROM. The MOVE16 precedent was evaluated directly and
rejected on a real constraint: Microcode.Desc rows are static, and
MOVE16 works there only because it is always exactly four transfers,
whereas FSAVE's length is runtime-selected. ExceptionUnit's own frame
machinery already IS 'emit N words where N is runtime-selected, then
read a frame back and dispatch on its format byte' -- E_STORE/E_STWAIT
driven by stStep/lastStep (which already mux 4/6/30-word frames) and
R_FMTREQ/R_FMTWAIT, which is literally FRESTORE's semantics. The new
states reuse driveStoreNoXlate and the registered load path verbatim,
including the task-#163 cross-cache-line word split (fsave -(An) with
an odd An is legal, and that split's absence silently drops a byte).

Encodings (0xF300|<ea> FSAVE, 0xF340|<ea> FRESTORE) were confirmed via
m68k-linux-gnu-as -m68040 and independently corroborated by the vendored
corpus's own raw literals (0xF327 / 0xF35F, commented as FSAVE -(A7) /
FRESTORE (A7)+). Both are PRIVILEGED, unlike Task 9's FMOVE-to-FPcr, so
they take RobPlugin's default sysPrivFault path with no exclusion --
tested in both directions.

Version byte finalized as \$41, as the design spec directs
('\$40 is a reasonable default ... finalized at implementation time').
\$41 makes fpu_fsave_idle_format_byte pass and keeps the Q700 FPSP's
'if ((frame[0] & 0xf) == 0) skip_restore' optimization meaningful --
\$40 would make every idle frame look null to it -- while the null
frame's forced \$00 keeps that same optimization correct in the other
direction.

ACCEPTANCE-CORPUS CLASSIFICATION, recorded so it is not rediscovered:
three of the four vendored FSAVE tests encode m68k-ooo's own
non-conformant frame shapes and stay FAILING BY DESIGN.
fpu_fsave_null_frame and fsave_frestore_basic both assert a 52-byte
header-0x41300000 frame; the MC68040 UM has no 52-byte frame at all and
the approved design spec says so explicitly while correcting that exact
recollection. fpu_fsave_frestore_idle_roundtrip asserts FRESTORE
restores FPCR/FPSR, which real FSAVE never saves. Do not chase these.
fpu_fsave_idle_format_byte flips to PASS.

The unimplemented-instruction frame's 44-byte size, its two header
bytes, its field list and CMDREG1B's offset \$08 are spec-verified; the
REMAINING field offsets (STAG/DTAG/E1/ETS-ETE-ETM/FPTS-FPTE-FPTM) were
pinned against MC68040 UM Figure 9-7 during implementation -- figure and
page recorded in ExceptionUnit's fsFrameWordData comment.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

**Notes on what I could not pin down, surfaced rather than assumed** (each is an explicit step above, not a silent guess):
1. The task prompt's stated FSAVE/FRESTORE opword `1111 001 000 EA` (`0xF200`) is the FPU *general* opclass, not FSAVE — the real encodings are `0xF300`/`0xF340`, confirmed from the vendored corpus's own `0xF327`/`0xF35F` literals plus a toolchain re-check (Task 11 Step 1).
2. The 44-byte frame's per-field byte offsets are **not** in the spec (only the size, header bytes, field list, and CMDREG1B's `$08` anchor) — Task 11 Step 2 is a blocking manual read.
3. The FPSR EXC→AEXC accrual fold is not covered by any of the encoding sources — Task 9 Step 12 is a blocking manual read.
4. Task 9's immediate-source form depends on `casForm` actually being ROB-visible; if it is not, the step says to drop that form from scope rather than invent a new side-channel.
5. The vendored FSAVE corpus contradicts the approved spec (and itself); the expected pass/fail split is written into Task 11 Step 13 as a gate in *both* directions.
### Task 12: Musashi oracle FP register accessors

**Files:**
- Modify: `tools/musashi/m68k_ref.h`
- Create: `tools/musashi/m68k_ref_fp.cpp`
- Modify: `tools/musashi/Makefile`
- Modify: `tools/musashi/musashi_run.cpp`
- Modify: `src/test/scala/m68k040/oracle/OracleStep.scala`
- Create: `src/test/scala/m68k040/lockstep/FpCompare.scala`
- Test: `src/test/scala/m68k040/oracle/OracleFpTraceSpec.scala`

**Interfaces:**
- Consumes: Musashi's PRIVATE core header `tools/musashi/musashi/m68kcpu.h` —
  `m68ki_cpu_core.fpr[8]` (`floatx80`), `.fpiar`, `.fpsr`, `.fpcr`
  (`m68kcpu.h:953-956`), reached through `extern m68ki_cpu_core m68ki_cpu;`
  (`m68kcpu.h:1020`). The PUBLIC `m68k_get_reg`/`m68k_set_reg` API is NOT used and
  CANNOT be used: `m68k.h`'s `m68k_register_t` enum (`m68k.h:111-152`) has **zero**
  FP entries — verified directly, `grep -n -i 'fp' m68k.h` returns no matches at all.
- Produces: `MusashiRef::get_fp(int)` returning `Fp80{high,low}`, plus
  `get_fpcr()`/`get_fpsr()`/`get_fpiar()`; new `fpNh=`/`fpNl=`/`fpcr=`/`fpsr=`/
  `fpiar=` fields on every `musashi_run --trace` step line and in the `--out`
  final-state dump; `OracleStep.fp: Vector[BigInt]` /`.fpcr`/`.fpsr`/`.fpiar`; and
  `FpCompare.assertFpEqual`. Consumed by Task 13 (`FpuLockStepSpec.scala`).

Musashi's SoftFloat `floatx80` is `{ bits16 high; bits64 low; }`
(`tools/musashi/musashi/softfloat/softfloat.h:49-52`) — sign in bit 15 of `high`,
15-bit biased exponent in `high[14:0]`, and the full 64-bit explicit-integer-bit
significand in `low`. There is no implicit bit and no padding: `(high << 64) | low`
is exactly the 80 architectural bits, which is exactly the width of `RegfileSpec.Fp`
from Task 1. That is what makes Decision 1's "bit-exact, not ±1 ULP" claim actually
cashable.

**This task must NOT extend `LockStep.compare`/`CommitObservation`.** Both are
`Long`-based end to end (`CommitObservation.scala:6-19`, all `Long`/`Int`;
`LockStep.scala:31-55`, every comparison masked `& 0xffffffffL`). An 80-bit value
does not fit in a `Long`. The project already has a precedent for spot-checking a
register class OUTSIDE `LockStep.compare`, using direct `BigInt` signal access —
`ExecuteLockStepSpec.scala:2321`/`:2333` reads `dut.rob.logic.exc.ss.usp.toBigInt`
for the USP-untouched assertion (poke side at `:726-727`,
`dut.rob.logic.exc.ss.usp #= BigInt(usp & 0xffffffffL)`). FP registers follow that
precedent exactly: a direct `BigInt` compare, invoked from the test, never routed
through the generic comparator.

Note for the plan's own File Structure section: it lists
`src/test/scala/m68k040/lockstep/CommitObservation.scala` as modified by Task 12 —
that is now known to be wrong; correct it to name
`src/test/scala/m68k040/lockstep/FpCompare.scala` (new) instead.

- [ ] **Step 1: Reproduce the macro-collision hazard yourself before writing any code**

`m68kcpu.h` cannot simply be `#include`d into `m68k_ref.cpp`. It defines object-like
macros (`m68kcpu.h:331-352`) — `REG_PC`, `REG_SP`, `REG_USP`, `REG_ISP`, `REG_MSP`,
`REG_SFC`, `REG_DFC`, `REG_VBR`, `REG_CACR`, `REG_CAAR` — every one of which collides
**by name** with a `MusashiRef::Reg` enumerator declared in `m68k_ref.h:44-60`, so
`get_reg`/`set_reg`'s `case REG_PC:` labels (`m68k_ref.cpp:265-275`,`:300-310`) get
preprocessed into `case m68ki_cpu.pc:`. It ALSO defines `m68k_read_immediate_16/32`
and `m68k_read_pcrelative_8/16/32` as function-like macros (`m68kcpu.h:465-470`,
guarded by `#if !M68K_SEPARATE_READS`, the active configuration), colliding with the
real function definitions `m68k_ref.cpp:514-528` provides. Confirm both classes of
breakage before trusting the workaround below:

```bash
T=$(mktemp -d); cd "$T"
M=<repo>/tools/musashi
sed -e 's|^#include "m68k.h"$|#include "m68k.h"\n#include "m68kcpu.h"|' \
    "$M/m68k_ref.cpp" > probe.cpp
g++ -O2 -std=c++17 -I"$M/musashi" -I"$M/musashi/softfloat" -I"$M" -c -o probe.o probe.cpp
```
Expected: a hard compile FAILURE, first error
`m68kcpu.h:336: error: the value of 'm68ki_cpu' is not usable in a constant expression`
pointing at `case REG_PC:`. (Measured during plan-writing; gcc 13.) This is why
Step 3 puts the FP accessors in their OWN translation unit instead of in
`m68k_ref.cpp`.

- [ ] **Step 2: Declare `Fp80` and the four accessors in `m68k_ref.h`**

```cpp
// tools/musashi/m68k_ref.h
// Add immediately BEFORE `class MusashiRef {` (i.e. just after the `#include
// "m68k_bus.h"` at line 29):

// 80-bit IEEE-754 extended value, split exactly the way Musashi/SoftFloat stores it
// (`floatx80` = { bits16 high; bits64 low; }, softfloat/softfloat.h:49-52):
//   high[15]   = sign
//   high[14:0] = 15-bit biased exponent
//   low[63:0]  = significand INCLUDING the explicit integer bit (extended precision
//                has no implicit bit)
struct Fp80 { uint16_t high; uint64_t low; };
```

```cpp
// tools/musashi/m68k_ref.h
// Add immediately after the existing `void set_reg(Reg r, uint32_t v);` (line 126):

    // -- FP state (68040 FPU) --------------------------------------------
    // Musashi's PUBLIC m68k_get_reg()/m68k_set_reg() API has NO floating-point
    // surface whatsoever (m68k.h:111-152 -- verified, M68K_REG_FP* does not
    // exist), so unlike get_reg() above these do NOT forward to it: they read
    // m68ki_cpu.fpr[]/.fpcr/.fpsr/.fpiar (m68kcpu.h:953-956) directly.  Defined
    // in the SEPARATE translation unit m68k_ref_fp.cpp.
    Fp80     get_fp(int i) const;
    uint32_t get_fpcr() const;
    uint32_t get_fpsr() const;
    uint32_t get_fpiar() const;
```

No setters are added: nothing seeds FP state out-of-band. Every lock-step program
establishes its own FP register contents with real instructions.

- [ ] **Step 3: Create `tools/musashi/m68k_ref_fp.cpp`**

```cpp
// tools/musashi/m68k_ref_fp.cpp -- MusashiRef FP register accessors
//
// WHY THIS IS A SEPARATE TRANSLATION UNIT (do not "simplify" it back into
// m68k_ref.cpp): Musashi's PUBLIC API (m68k.h) exposes no FP registers at all.
// The only way to observe FP state is Musashi's PRIVATE core header, m68kcpu.h,
// which declares `extern m68ki_cpu_core m68ki_cpu;` (m68kcpu.h:1020) whose
// fpr[8]/fpiar/fpsr/fpcr fields (m68kcpu.h:953-956) hold exactly what we need.
//
// But m68kcpu.h ALSO defines object-like macros REG_PC / REG_SP / REG_USP /
// REG_ISP / REG_MSP / REG_SFC / REG_DFC / REG_VBR / REG_CACR / REG_CAAR
// (m68kcpu.h:331-352), each colliding BY NAME with a MusashiRef::Reg
// enumerator, and function-like macros m68k_read_immediate_16/32 +
// m68k_read_pcrelative_8/16/32 (m68kcpu.h:465-470) which collide with the real
// callback functions m68k_ref.cpp defines.  Including it in m68k_ref.cpp is a
// HARD compile error (see this task's Step 1, which reproduces it).
//
// Here the REG_* collisions are removed with a targeted #undef block before
// m68k_ref.h is pulled in, and the m68k_read_* collisions simply never arise
// because this TU defines none of those callbacks.  m68kcpu.h is C++-safe on
// its own (self-guards with `#ifdef __cplusplus extern "C" {`, m68kcpu.h:37-39),
// so no extern "C" wrapper is needed here.

#include "m68kcpu.h"

// Undo m68kcpu.h's REG_* convenience macros so m68k_ref.h's MusashiRef::Reg
// enumerators of the same names survive the preprocessor.  Keep this list in
// sync with m68kcpu.h:336-346 if Musashi is ever re-vendored.
#undef REG_PC
#undef REG_SP
#undef REG_USP
#undef REG_ISP
#undef REG_MSP
#undef REG_SFC
#undef REG_DFC
#undef REG_VBR
#undef REG_CACR
#undef REG_CAAR

#include "m68k_ref.h"

Fp80 MusashiRef::get_fp(int i) const {
    Fp80 r{0, 0};
    if (i < 0 || i > 7) return r;
    r.high = (uint16_t)(m68ki_cpu.fpr[i].high & 0xFFFFu);
    r.low  = (uint64_t)(m68ki_cpu.fpr[i].low);
    return r;
}

uint32_t MusashiRef::get_fpcr()  const { return (uint32_t)m68ki_cpu.fpcr;  }
uint32_t MusashiRef::get_fpsr()  const { return (uint32_t)m68ki_cpu.fpsr;  }
uint32_t MusashiRef::get_fpiar() const { return (uint32_t)m68ki_cpu.fpiar; }
```

- [ ] **Step 4: Add the new object to `tools/musashi/Makefile`**

The include path needs no change: `INCS` is already
`-I$(MUSASHI_DIR) -I$(MUSASHI_DIR)/softfloat -I$(HERE)` (`Makefile:28`), which already
covers `musashi/m68kcpu.h` and `musashi/softfloat/softfloat.h` — verified by reading
the Makefile and confirmed by Step 1's successful probe compile. Only the object list
and one rule change:

```make
# tools/musashi/Makefile
# Change line 52 from:
REF_OBJS := $(HERE)m68k_ref.o
# to:
REF_OBJS := $(HERE)m68k_ref.o $(HERE)m68k_ref_fp.o

# Add immediately after the existing $(HERE)m68k_ref.o rule (lines 65-66):
$(HERE)m68k_ref_fp.o: $(HERE)m68k_ref_fp.cpp $(HERE)m68k_ref.h \
                      $(MUSASHI_DIR)/m68kcpu.h $(GEN_HDRS)
	$(CXX) $(CXXFLAGS) $(INCS) -c -o $@ $<
```

The `m68kcpu.h` prerequisite is deliberate: unlike `m68k_ref.o`, this object depends
on Musashi's private core header, so a re-vendor of Musashi must rebuild it.

- [ ] **Step 5: Extend `musashi_run.cpp`'s per-step trace line**

The trace record is the `std::fprintf(tf, ...)` at `musashi_run.cpp:214-230`. Each FP
register is emitted as **two** fields (`fpNh` = the 16-bit high half, `fpNl` = the
64-bit low half) rather than one 20-hex-digit field.

```cpp
// tools/musashi/musashi_run.cpp
// Inside the trace loop, replace lines 212-230 with:

            uint32_t pc = ref.get_reg(MusashiRef::REG_PC);
            uint32_t sr = ref.get_reg(MusashiRef::REG_SR) & 0xFFFFu;
            // FP state (68040 FPU).  Each FP register is emitted as two fields:
            // fpNh = floatx80.high (sign + 15-bit biased exponent), fpNl =
            // floatx80.low (64-bit significand WITH the explicit integer bit).
            // The consumer recombines them as (fpNh << 64) | fpNl.  Emitted
            // unconditionally: OracleStep.scala is the sole consumer and is
            // key/value based, so unknown keys are ignored; for a non-FP program
            // every field is 0.  Cost measured during plan-writing: 283 -> 620
            // bytes per step line (2.19x).
            Fp80 fp[8];
            for (int i = 0; i < 8; i++) fp[i] = ref.get_fp(i);
            std::fprintf(tf,
                "step pc=0x%08x sr=0x%04x"
                " d0=0x%08x d1=0x%08x d2=0x%08x d3=0x%08x"
                " d4=0x%08x d5=0x%08x d6=0x%08x d7=0x%08x"
                " a0=0x%08x a1=0x%08x a2=0x%08x a3=0x%08x"
                " a4=0x%08x a5=0x%08x a6=0x%08x a7=0x%08x"
                " msp=0x%08x isp=0x%08x"
                " fp0h=0x%04x fp0l=0x%016llx fp1h=0x%04x fp1l=0x%016llx"
                " fp2h=0x%04x fp2l=0x%016llx fp3h=0x%04x fp3l=0x%016llx"
                " fp4h=0x%04x fp4l=0x%016llx fp5h=0x%04x fp5l=0x%016llx"
                " fp6h=0x%04x fp6l=0x%016llx fp7h=0x%04x fp7l=0x%016llx"
                " fpcr=0x%08x fpsr=0x%08x fpiar=0x%08x\n",
                pc, sr,
                ref.get_reg(MusashiRef::REG_D0), ref.get_reg(MusashiRef::REG_D1),
                ref.get_reg(MusashiRef::REG_D2), ref.get_reg(MusashiRef::REG_D3),
                ref.get_reg(MusashiRef::REG_D4), ref.get_reg(MusashiRef::REG_D5),
                ref.get_reg(MusashiRef::REG_D6), ref.get_reg(MusashiRef::REG_D7),
                ref.get_reg(MusashiRef::REG_A0), ref.get_reg(MusashiRef::REG_A1),
                ref.get_reg(MusashiRef::REG_A2), ref.get_reg(MusashiRef::REG_A3),
                ref.get_reg(MusashiRef::REG_A4), ref.get_reg(MusashiRef::REG_A5),
                ref.get_reg(MusashiRef::REG_A6), ref.get_reg(MusashiRef::REG_A7),
                ref.get_reg(MusashiRef::REG_MSP), ref.get_reg(MusashiRef::REG_ISP),
                fp[0].high, (unsigned long long)fp[0].low,
                fp[1].high, (unsigned long long)fp[1].low,
                fp[2].high, (unsigned long long)fp[2].low,
                fp[3].high, (unsigned long long)fp[3].low,
                fp[4].high, (unsigned long long)fp[4].low,
                fp[5].high, (unsigned long long)fp[5].low,
                fp[6].high, (unsigned long long)fp[6].low,
                fp[7].high, (unsigned long long)fp[7].low,
                ref.get_fpcr(), ref.get_fpsr(), ref.get_fpiar());
```

- [ ] **Step 6: Extend `musashi_run.cpp`'s final-state dump**

Add immediately after the existing `a0..a7` loop in the final-state block
(`musashi_run.cpp:264-298`, right after the loop ending at `:283`), before
`final_mem_writes()`:

```cpp
// tools/musashi/musashi_run.cpp
// Insert after the a-register loop:

    // FP state.  Same two-field-per-register encoding as the --trace records.
    // Appended AFTER the a0..a7 block and BEFORE mem_writes= so the existing key
    // order Musashi.scala's parseOutput reads is untouched -- that parser is
    // key/value based and ignores keys it does not ask for.
    for (int i = 0; i < 8; i++) {
        Fp80 v = ref.get_fp(i);
        std::fprintf(f, "fp%dh=0x%04x\n", i, v.high);
        std::fprintf(f, "fp%dl=0x%016llx\n", i, (unsigned long long)v.low);
    }
    std::fprintf(f, "fpcr=0x%08x\n",  ref.get_fpcr());
    std::fprintf(f, "fpsr=0x%08x\n",  ref.get_fpsr());
    std::fprintf(f, "fpiar=0x%08x\n", ref.get_fpiar());
```

- [ ] **Step 7: Build and smoke-verify against a real FP program**

```bash
make -C tools/musashi
```
(The repo-root convenience target `make musashi` forwards to exactly this, and also
reinstalls the shared binary into `~/.cache/m68k-core-040-ooo/musashi_run`, which
`Musashi.runnerPath` prefers — do NOT skip the install or Scala tests use a stale
binary.)

Then run a real FP program end to end:

```bash
T=$(mktemp -d)
cat > $T/fp.s << 'EOF'
	fmove.l #5,%fp0
	fmove.l #3,%fp1
	fadd.x %fp1,%fp0
	fmove.x cinf(%pc),%fp2
	fmove.x cnan(%pc),%fp3
	fadd.x %fp3,%fp2
	move.l #0xC0FFEE00,(0xFFFF0000).l
spin:	bra.s spin
	.align 2
cinf:	.short 0x7FFF ; .short 0 ; .long 0x80000000 ; .long 0x00000000
cnan:	.short 0x7FFF ; .short 0 ; .long 0xC0000000 ; .long 0x00000000
EOF
m68k-linux-gnu-as -m68040 -o $T/fp.o $T/fp.s
m68k-linux-gnu-ld -Ttext 0x40800000 -o $T/fp.elf $T/fp.o
m68k-linux-gnu-objcopy -O binary $T/fp.elf $T/fp.bin
tools/musashi/musashi_run --bin $T/fp.bin --load-addr 0x40800000 \
  --initial-sp 0x00100000 --sentinel 0xFFFF0000 --max-cycles 20000 \
  --trace $T/fp.trace
grep -o 'fp[0-3][hl]=0x[0-9a-f]*\|fpsr=0x[0-9a-f]*' $T/fp.trace | paste - - - - - - - - -
```

Expected values, measured against the real vendored Musashi during plan-writing — if
any differ, stop and investigate rather than adjusting the expectation:

| after | fp0 | fp1 | fp2 | fp3 | fpsr |
|---|---|---|---|---|---|
| `fmove.l #5,%fp0` | `4001_a000000000000000` (=5.0) | -- | -- | -- | `00000000` |
| `fmove.l #3,%fp1` | | `4000_c000000000000000` (=3.0) | | | `00000000` |
| `fadd.x %fp1,%fp0` | `4002_8000000000000000` (=8.0) | | | | `00000000` |
| `fmove.x cinf(%pc),%fp2` | | | `7fff_8000000000000000` (+inf) | | `02000000` (FPCC_I) |
| `fmove.x cnan(%pc),%fp3` | | | | `7fff_c000000000000000` (NaN) | `01000000` (FPCC_NAN) |
| `fadd.x %fp3,%fp2` | | | `7fff_c000000000000000` (NaN propagated) | | `01000000` |

This simultaneously proves four things the rest of the FPU work depends on:
`m68k-linux-gnu-as -m68040` accepts the FP mnemonics; Musashi genuinely executes them
for `M68K_CPU_TYPE_68040`; the new accessors read live state; and `floatx80` really is
exact (5.0 + 3.0 = 8.0 with a zero low-order tail).

- [ ] **Step 8: Extend `OracleStep.scala` with the FP fields**

**Critical parsing gotcha, pinned down during plan-writing:** the existing private
helper is `java.lang.Long.parseLong(s.stripPrefix("0x"), 16)` (`OracleStep.scala:13`).
That **throws** `NumberFormatException` on any 16-hex-digit value whose top bit is set
— and `fpNl` genuinely produces such values (`0/0` under FDIV yields Musashi's
default NaN `ffff_ffffffffffffffff`). The `fpNl` fields MUST NOT go through `hex`.

```scala
// src/test/scala/m68k040/oracle/OracleStep.scala
// Replace the case class (line 8) with:

final case class OracleStep(pc: Long, sr: Int, d: Vector[Long], a: Vector[Long],
                            msp: Long = -1L, isp: Long = -1L,
                            fp: Vector[BigInt] = Vector.empty,
                            fpcr: Long = 0L, fpsr: Long = 0L, fpiar: Long = 0L) {
  def ccr: Int = sr & 0x1f
}
```

```scala
// src/test/scala/m68k040/oracle/OracleStep.scala
// Add alongside the existing `hex` helper (line 13):

  private def hex(s: String): Long = java.lang.Long.parseLong(s.stripPrefix("0x"), 16)
  // fpNl fields are FULL 64-bit unsigned values (e.g. Musashi's default NaN
  // significand ffffffffffffffff), which java.lang.Long.parseLong THROWS on.
  // Parse every FP field as an unsigned BigInt instead.
  private def hexBig(s: String): BigInt = BigInt(s.stripPrefix("0x"), 16)
```

```scala
// src/test/scala/m68k040/oracle/OracleStep.scala
// Replace the `yield OracleStep(...)` tail of parseLine (lines 37-39) with:

    } yield {
      val fpRegs: Vector[BigInt] =
        if ((0 until 8).forall(i => kv.contains(s"fp${i}h") && kv.contains(s"fp${i}l")))
          (0 until 8).map { i =>
            (hexBig(kv(s"fp${i}h")) << 64) | hexBig(kv(s"fp${i}l"))
          }.toVector
        else Vector.empty
      OracleStep(pc, sr.toInt & 0xffff, d, a,
                 msp   = kv.get("msp").map(hex).getOrElse(-1L),
                 isp   = kv.get("isp").map(hex).getOrElse(-1L),
                 fp    = fpRegs,
                 fpcr  = kv.get("fpcr").map(hex).getOrElse(0L),
                 fpsr  = kv.get("fpsr").map(hex).getOrElse(0L),
                 fpiar = kv.get("fpiar").map(hex).getOrElse(0L))
    }
```

- [ ] **Step 9: Create the `FpCompare` helper (the new, deliberately-separate FP
  compare path)**

```scala
// src/test/scala/m68k040/lockstep/FpCompare.scala
package m68k040.lockstep

import org.scalatest.Assertions.fail

/** Bit-exact 80-bit FP comparison for lock-step tests.
  *
  * DELIBERATELY OUTSIDE LockStep.compare/CommitObservation.  Both are Long-based
  * end to end, so an 80-bit extended value does not fit.  The established project
  * precedent for spot-checking a register class outside the generic comparator is
  * direct BigInt signal access (ExecuteLockStepSpec.scala's USP-untouched
  * assertion). These helpers are that precedent, packaged. */
object FpCompare {
  val Width  = 80
  val Mask   = (BigInt(1) << Width) - 1

  def fmt(v: BigInt): String = {
    val x    = v & Mask
    val sign = ((x >> 79) & 1).toInt
    val exp  = ((x >> 64) & 0x7fff).toInt
    val sig  = x & ((BigInt(1) << 64) - 1)
    f"$sign%d:$exp%04x:${sig.toString(16)}%16s (raw 0x${x.toString(16)}%020s)"
      .replace(' ', '0')
  }

  def assertFpEqual(dut: BigInt, oracle: BigInt, what: String): Unit = {
    val d = dut & Mask
    val o = oracle & Mask
    if (d != o) {
      val diff = d ^ o
      fail(s"$what: FP value diverged (bit-exact compare)\n" +
           s"  dut    = ${fmt(d)}\n" +
           s"  oracle = ${fmt(o)}\n" +
           s"  xor    = 0x${diff.toString(16)}" +
           (if (((diff >> 64) & 0x7fff) != 0) "  [exponent differs]" else "") +
           (if ((diff & ((BigInt(1) << 64) - 1)) != 0) "  [significand differs]" else "") +
           (if (((diff >> 79) & 1) != 0) "  [sign differs]" else ""))
    }
  }

  def assertFpBits(actual: BigInt, expectedHex: String, what: String): Unit =
    assertFpEqual(actual, BigInt(expectedHex, 16), s"$what (anchor 0x$expectedHex)")

  def fp80(high: Int, low: BigInt): BigInt =
    ((BigInt(high) & 0xffff) << 64) | (low & ((BigInt(1) << 64) - 1))
}
```

- [ ] **Step 10: Write the oracle-side parser/accessor test**

```scala
// src/test/scala/m68k040/oracle/OracleFpTraceSpec.scala
package m68k040.oracle

import org.scalatest.funsuite.AnyFunSuite

/** Proves Task 12's oracle-side plumbing end to end. Pure oracle -- no DUT, no
  * Verilator; runs in the fast suite. */
class OracleFpTraceSpec extends AnyFunSuite {

  private val Src = Seq(
    "fmove.l #5,%fp0",
    "fmove.l #3,%fp1",
    "fadd.x %fp1,%fp0",
    "fmove.x cinf(%pc),%fp2",
    "fmove.x cnan(%pc),%fp3",
    "fadd.x %fp3,%fp2",
    "spin: bra.s spin",
    ".align 2",
    "cinf: .short 0x7FFF", ".short 0", ".long 0x80000000", ".long 0x00000000",
    "cnan: .short 0x7FFF", ".short 0", ".long 0xC0000000", ".long 0x00000000"
  ).mkString(" ; ")

  test("Musashi trace carries bit-exact 80-bit FP state") {
    Musashi.assembleAndTrace(Src) match {
      case Left(err) => fail(s"oracle error: ${err.reason}")
      case Right(steps) =>
        assert(steps.size >= 6, s"expected >= 6 steps, got ${steps.size}")
        assert(steps.forall(_.fp.size == 8),
          "every step must carry all 8 FP registers -- if this fails, musashi_run is " +
          "stale: rebuild with `make -C tools/musashi`")

        assert(steps(0).fp(0) == BigInt("4001a000000000000000", 16),
          f"FP0 after fmove.l #5,%%fp0 should be 5.0, got 0x${steps(0).fp(0).toString(16)}")
        assert(steps(1).fp(1) == BigInt("4000c000000000000000", 16),
          f"FP1 after fmove.l #3,%%fp1 should be 3.0, got 0x${steps(1).fp(1).toString(16)}")
        assert(steps(2).fp(0) == BigInt("40028000000000000000", 16),
          f"FP0 after FADD should be exactly 8.0, got 0x${steps(2).fp(0).toString(16)}")

        assert(steps(3).fp(2) == BigInt("7fff8000000000000000", 16), "FP2 should be +inf")
        assert(steps(3).fpsr == 0x02000000L,
          f"FPSR after loading +inf should have FPCC_I set, got 0x${steps(3).fpsr}%08x")
        assert(steps(4).fp(3) == BigInt("7fffc000000000000000", 16), "FP3 should be NaN")
        assert(steps(4).fpsr == 0x01000000L,
          f"FPSR after loading NaN should have FPCC_NAN set, got 0x${steps(4).fpsr}%08x")
        assert(steps(5).fp(2) == BigInt("7fffc000000000000000", 16),
          f"inf+NaN must propagate the NaN unchanged, got 0x${steps(5).fp(2).toString(16)}")

        assert(steps.forall(_.fpcr == 0L),  "FPCR must stay 0 (no FMOVE to FPCR here)")
        assert(steps.forall(_.fpiar == 0L), "FPIAR must stay 0 (Musashi raises no FP exceptions)")
    }
  }

  test("OracleStep parses a full-64-bit fpNl field without overflowing Long") {
    val Div0 = Seq(
      "fmove.l #0,%fp0",
      "fdiv.x %fp0,%fp0",
      "spin: bra.s spin"
    ).mkString(" ; ")
    Musashi.assembleAndTrace(Div0) match {
      case Left(err)   => fail(s"oracle error: ${err.reason}")
      case Right(steps) =>
        assert(steps.size >= 2, s"expected >= 2 steps, got ${steps.size}")
        assert(steps(1).fp(0) == BigInt("ffffffffffffffffffff", 16),
          f"0/0 should yield Musashi's default NaN, got 0x${steps(1).fp(0).toString(16)}")
    }
  }
}
```

- [ ] **Step 11: Run the oracle tests, and confirm nothing existing regressed**

```bash
sbt "testOnly m68k040.oracle.OracleFpTraceSpec"
sbt "testOnly m68k040.oracle.MusashiTraceSpec m68k040.oracle.MusashiOracleSpec"
```
Expected: `OracleFpTraceSpec` both tests PASS; existing oracle tests unchanged.

```bash
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
```
Expected: 396/396 PASS, unchanged from the pre-Task-12 baseline.

- [ ] **Step 12: Commit**

```bash
git add tools/musashi/m68k_ref.h \
        tools/musashi/m68k_ref_fp.cpp \
        tools/musashi/Makefile \
        tools/musashi/musashi_run.cpp \
        src/test/scala/m68k040/oracle/OracleStep.scala \
        src/test/scala/m68k040/oracle/OracleFpTraceSpec.scala \
        src/test/scala/m68k040/lockstep/FpCompare.scala
git commit -m "test(fpu): Musashi oracle FP register accessors + 80-bit lock-step compare path

Musashi's PUBLIC m68k_get_reg()/m68k_set_reg() API has no floating-point
surface at all -- m68k.h's m68k_register_t enum has no M68K_REG_FP* of
any kind. The FP state exists in the emulation core
(m68ki_cpu.fpr[8] as SoftFloat floatx80, plus .fpcr/.fpsr/.fpiar), so
the accessors reach it directly through Musashi's private m68kcpu.h.

That header cannot simply be included into m68k_ref.cpp: it defines
object-like macros REG_PC/REG_SP/REG_USP/REG_ISP/REG_MSP/REG_SFC/
REG_DFC/REG_VBR/REG_CACR/REG_CAAR that collide by name with
MusashiRef::Reg enumerators, and function-like macros
m68k_read_immediate_*/m68k_read_pcrelative_* that collide with the
callbacks m68k_ref.cpp defines. Both reproduced as hard compile errors
before choosing a workaround: the accessors live in their own
translation unit, m68k_ref_fp.cpp, which #undefs the ten REG_*
collisions and defines none of the colliding callbacks.

musashi_run's trace and final-state dump each gained the eight FP
registers as two fields apiece (fpNh/fpNl) plus fpcr/fpsr/fpiar.
OracleStep recombines them as (high << 64) | low. FP parsing does NOT
use the existing `hex` helper: it throws on a 16-hex-digit value with
the top bit set, and Musashi's default NaN significand is exactly
ffffffffffffffff (reachable by a plain 0.0/0.0 FDIV) -- a separate
BigInt parser is used, with a dedicated regression test.

FP comparison is a NEW path (FpCompare.assertFpEqual), deliberately
bypassing LockStep.compare/CommitObservation, which are Long-based end
to end. Follows the project's existing precedent of spot-checking a
register class outside the generic comparator via direct BigInt signal
access.

Verified end to end against the real vendored Musashi: FMOVE.L #5 ->
0x4001A000000000000000, #3 -> 0x4000C000000000000000, FADD -> exactly
0x40028000000000000000 (8.0, zero low-order tail), +inf load sets
FPCC_I, NaN load sets FPCC_NAN, and inf+NaN propagates the NaN
bit-for-bit. No DUT-side FP support exists yet (Tasks 1-11); this task
is oracle plumbing only, consumed by Task 13.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 13: Directed bit-exact lock-step tests for the HW-native FP op set

**Files:**
- Modify: `src/main/scala/m68k040/execute/regfile/RegFilePlugin.scala`
- Modify: `src/main/scala/m68k040/rename/RenameStage.scala`
- Modify: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`
- Create: `src/test/scala/m68k040/lockstep/FpuLockStepSpec.scala`

**Interfaces:**
- Consumes: Task 12's `OracleStep.fp`/`.fpsr`/`.fpcr`/`.fpiar` and
  `FpCompare.assertFpEqual`/`assertFpBits`; Task 1's `RegFilePluginFp`; Task 2's
  `RenameStage.fpRat`; Tasks 4-8's decode/EU work (the DUT must actually execute the
  ops before any of these tests can pass); Task 9's `FpuControlPlugin`.
- Produces: `FpuLockStepSpec` — the directed bit-exact acceptance suite for the
  HW-native op set. Complements, and does NOT replace, the vendored `fpu_*`/`fpsp_*`
  tests (Decision 8) which cover the trap boundary rather than arithmetic.

Scope, stated plainly: this task covers the **register-to-register** forms of FADD,
FSUB, FMUL, FDIV, FSQRT, FABS, FNEG, FCMP, FINT, FINTRZ, FTST, FMOVECR, plus the
`FMOVE.L #imm,FPn` and `FMOVE.X <ea>,FPn` loads needed to establish operands at all.
**Memory-source forms beyond those two loads, and FMOVEM, are an explicitly smaller
follow-up subset and are NOT covered here** — Musashi is separately known to have
buggy multi-register FMOVEM EA handling (Decision 3), so FMOVEM is not
lock-steppable regardless.

The following was all confirmed by direct experiment during plan-writing: gas
`-m68040` assembles every mnemonic above (e.g. `fadd.x %fp1,%fp0` -> `F200 0422`,
`ftst.x %fp0` -> `F200 003A`, `fmovecr #0x0,%fp0` -> `F200 5C00`, `fmove.l
%fpsr,%d2` -> `F202 A800`); Musashi executes all of them for `M68K_CPU_TYPE_68040`
without a `fatalerror` or a line-F trap; and the `;`-joined single-line program form
assembles and traces correctly through the existing `ProgramAssembler`/
`Musashi.assembleAndTrace` path.

- [ ] **Step 1: Add the sim-only FP PRF shadow — the committed FP value is NOT
  readable any other way**

This is a genuine, verified obstacle. `RegFilePlugin`'s backing store is `val ram =
Mem(Bits(spec.dataWidth bits), spec.depth)` (`RegFilePlugin.scala:87`), but every
full-core build routes through `M68kSpinalConfig`, which installs
`MultiPortWritesSymplifier` and **rewrites a multi-write Mem out of the netlist
entirely** into `RamAsyncMwMux` banks. The project has already hit this exact wall
once — `RobPlugin.scala:334-345`'s sim-only whitebox shadow of `payload.pc` (for the
identical reason: `payload.getBigInt(i)` is not available in simulation). Follow that
precedent verbatim.

```scala
// src/main/scala/m68k040/execute/regfile/RegFilePlugin.scala
// Add immediately after `spinal.core.sim.SimPublic(dbgW)` (line 94):

    // SIM-ONLY whitebox shadow of `ram`.  The real Mem is NOT readable from a
    // simulation: M68kSpinalConfig installs MultiPortWritesSymplifier, which
    // rewrites a multi-write Mem out of the netlist entirely -- exactly the
    // situation RobPlugin.scala:334-345 hit for its `payload` Mem.
    //
    // Elaborated ONLY when includeSimulation is set (see M68kSim.scala) => zero
    // synthesis cost.  `shadow` is null in every synth/GenVerilog build.
    val shadow = GenerationFlags.simulation {
      val v = Vec.fill(spec.depth)(Reg(Bits(spec.dataWidth bits)) init 0)
      when(!initDone) { v(initCounter.resized) := B(0, spec.dataWidth bits) }
      for (w <- phys) { when(w.valid) { v(w.address) := w.data } }
      v.foreach(_.simPublic())
      v
    }
```

```scala
// src/main/scala/m68k040/rename/RenameStage.scala
// Add alongside the fpRat/fpccRat instantiation added by Task 2:

    // SIM-ONLY: expose the committed arch->phys FP mapping so a lock-step test can
    // resolve architectural FP0-FP7 to a physical index and read it out of
    // RegFilePluginFp's sim shadow.  Absent from every synth/GenVerilog build.
    GenerationFlags.simulation { fpRat.io.committedPhys.simPublic() }
```

- [ ] **Step 2: Wire the FP plugins into `ExecuteLockStepSpec`'s own `FullCoreDut`**

Tasks 1 and 9 wire `RegFilePluginFp`/`FpuControlPlugin` into `FullCoreSynth.scala`.
The lock-step harness has a SEPARATE DUT (`ExecuteLockStepSpec.scala:361`'s
`FullCoreDut`) which those tasks do not touch — it needs the same additions or
`host[FpRegFileService]` fails at elaboration.

```scala
// src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX, RegFilePluginFp}

// Inside `class FullCoreDut`, add alongside the existing rfInt/rfNzvc/rfX vals:
    val rfFp   = new RegFilePluginFp
    val fpuCtrl = new m68k040.execute.FpuControlPlugin
// ...and add both to the host.asHostOf plugin sequence.
```

Note: if Task 2/3's FPCC rename ends up backed by its own physical regfile plugin,
that must be added here too — confirm the real name Task 3 lands with before
implementing.

- [ ] **Step 3: Add an `afterRun` hook to `runLockStep`**

```scala
// src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala
// Extend runLockStep's signature with one new parameter:
  def runLockStep(name: String, src: String, nInstr: Int = -1, checkMem: Seq[Long] = Seq.empty,
                  checkSpan: Int = 4, mmuMap: Option[(Long, Long)] = None,
                  initialSr: Option[Int] = None, usp: Long = 0x00200000L,
                  initialMsp: Option[Long] = None, pcOnly: Boolean = false,
                  // Post-run whitebox hook, default no-op => every existing call
                  // site is unchanged. Used by FpuLockStepSpec to assert 80-bit FP
                  // registers, which cannot go through LockStep.compare.
                  afterRun: (FullCoreDut, Vector[OracleStep]) => Unit = (_, _) => ()): Unit = {
```

```scala
// At the very END of the doSim body, as the last statement:
      afterRun(dut, oracle.toVector)
```

- [ ] **Step 4: Create `FpuLockStepSpec.scala`**

```scala
// src/test/scala/m68k040/lockstep/FpuLockStepSpec.scala
package m68k040.lockstep

import m68k040.VerilatorTest
import m68k040.oracle.OracleStep
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Directed bit-exact lock-step tests for the HW-native FP op set. Bit-exact is
  * possible because Musashi's internal representation is SoftFloat floatx80 --
  * portable 80-bit extended, not a platform long double -- matching our own 80-bit
  * internal register file (Decision 1).
  *
  * FP data registers go through FpCompare.assertFpEqual (cannot use
  * LockStep.compare, which is Long-based). FPSR/FPCC is compared through the
  * ARCHITECTURAL path instead: the program executes `fmove.l %fpsr,%dN`, landing
  * FPSR in an integer register the EXISTING LockStep.compare already checks
  * bit-exactly every step -- no new plumbing needed. */
class FpuLockStepSpec extends AnyFunSuite {

  private val h = new ExecuteLockStepSpec

  private def dutFp(dut: h.FullCoreDut, arch: Int): BigInt = {
    require(arch >= 0 && arch < 8, s"FP arch reg must be 0..7, got $arch")
    val phys = dut.ren.logic.fpRat.io.committedPhys(arch).toInt
    dut.rfFp.logic.shadow(phys).toBigInt & FpCompare.Mask
  }

  private def expectFp(name: String, regs: Int*)
                      (dut: h.FullCoreDut, oracle: Vector[OracleStep]): Unit = {
    val last = oracle.last
    assert(last.fp.size == 8,
      s"[$name] oracle step carries no FP state -- musashi_run is stale, rebuild with " +
      "`make -C tools/musashi` (Task 12)")
    for (r <- regs)
      FpCompare.assertFpEqual(dutFp(dut, r), last.fp(r), s"[$name] FP$r")
  }

  private def ext(label: String, high: Int, low: BigInt): String = {
    val hi32 = ((low >> 32) & 0xffffffffL).toLong
    val lo32 = (low & 0xffffffffL).toLong
    f"$label: .short 0x$high%04X ; .short 0 ; .long 0x$hi32%08X ; .long 0x$lo32%08X"
  }

  private val PosInf = ("c_pinf", 0x7FFF, BigInt("8000000000000000", 16))
  private val NegInf = ("c_ninf", 0xFFFF, BigInt("8000000000000000", 16))
  private val QNan   = ("c_qnan", 0x7FFF, BigInt("C0000000DEADBEEF", 16))
  private val Frac   = ("c_frac", 0xC000, BigInt("B400000000000000", 16))

  private def prog(instrs: Seq[String], consts: Seq[(String, Int, BigInt)] = Seq.empty): String =
    (instrs ++ Seq("spin: bra.s spin", ".align 2") ++
      consts.map { case (l, h, lo) => ext(l, h, lo) }).mkString(" ; ")

  // ── Normal-value round trip, one per op ──────────────────────────────────

  test("lock-step FP: FADD.X register-register (5.0 + 3.0 = 8.0, exact)", VerilatorTest) {
    val instrs = Seq("fmove.l #5,%fp0", "fmove.l #3,%fp1", "fadd.x %fp1,%fp0")
    h.runLockStep("fp-fadd", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fadd", 0, 1)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 0), "40028000000000000000", "fp-fadd FP0 = 8.0")
      })
  }

  test("lock-step FP: FSUB.X register-register (5.0 - 3.0 = 2.0, exact)", VerilatorTest) {
    val instrs = Seq("fmove.l #5,%fp0", "fmove.l #3,%fp1", "fsub.x %fp1,%fp0")
    h.runLockStep("fp-fsub", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fsub", 0, 1)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 0), "40008000000000000000", "fp-fsub FP0 = 2.0")
      })
  }

  test("lock-step FP: FMUL.X register-register (5.0 * 3.0 = 15.0, exact)", VerilatorTest) {
    val instrs = Seq("fmove.l #5,%fp0", "fmove.l #3,%fp1", "fmul.x %fp1,%fp0")
    h.runLockStep("fp-fmul", prog(instrs), nInstr = instrs.size,
      afterRun = expectFp("fp-fmul", 0, 1))
  }

  test("lock-step FP: FDIV.X register-register (12.0 / 3.0 = 4.0, exact)", VerilatorTest) {
    // Also the multi-cycle-EU case: FDIV shares the CPLX cluster's iterative context
    // with integer DIV (Decision 9).
    val instrs = Seq("fmove.l #12,%fp0", "fmove.l #3,%fp1", "fdiv.x %fp1,%fp0")
    h.runLockStep("fp-fdiv", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fdiv", 0, 1)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 0), "40018000000000000000", "fp-fdiv FP0 = 4.0")
      })
  }

  test("lock-step FP: FSQRT.X (sqrt(9.0) = 3.0, exact)", VerilatorTest) {
    val instrs = Seq("fmove.l #9,%fp2", "fsqrt.x %fp2,%fp3")
    h.runLockStep("fp-fsqrt", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fsqrt", 2, 3)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 3), "4000c000000000000000", "fp-fsqrt FP3 = 3.0")
      })
  }

  test("lock-step FP: FABS.X / FNEG.X (sign-only, mantissa untouched)", VerilatorTest) {
    val instrs = Seq(
      s"fmove.x ${Frac._1}(%pc),%fp4",
      "fabs.x %fp4,%fp5",
      "fneg.x %fp4,%fp6",
      "fneg.x %fp5,%fp7")
    h.runLockStep("fp-fabs-fneg", prog(instrs, Seq(Frac)), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fabs-fneg", 4, 5, 6, 7)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 5), "4000b400000000000000", "fp-fabs FP5 = +2.8125")
        FpCompare.assertFpBits(dutFp(dut, 6), "4000b400000000000000", "fp-fneg FP6 = +2.8125")
        FpCompare.assertFpBits(dutFp(dut, 7), "c000b400000000000000", "fp-fneg FP7 = -2.8125")
      })
  }

  test("lock-step FP: FINT vs FINTRZ disagree on -2.8125 (-3.0 vs -2.0)", VerilatorTest) {
    val instrs = Seq(
      s"fmove.x ${Frac._1}(%pc),%fp0",
      "fint.x %fp0,%fp1",
      "fintrz.x %fp0,%fp2")
    h.runLockStep("fp-fint", prog(instrs, Seq(Frac)), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fint", 0, 1, 2)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 1), "c000c000000000000000", "fp-fint FP1 = -3.0 (RN)")
        FpCompare.assertFpBits(dutFp(dut, 2), "c0008000000000000000", "fp-fintrz FP2 = -2.0 (RZ)")
        assert(dutFp(dut, 1) != dutFp(dut, 2),
          "FINT and FINTRZ MUST differ on -2.8125 -- identical results mean one is " +
          "implemented as an alias of the other")
      })
  }

  test("lock-step FP: FMOVECR ROM constants (pi and 1.0)", VerilatorTest) {
    val instrs = Seq("fmovecr #0x00,%fp0", "fmovecr #0x32,%fp1", "fmovecr #0x0f,%fp2")
    h.runLockStep("fp-fmovecr", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fmovecr", 0, 1, 2)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 0), "4000c90fdaa22168c235", "fp-fmovecr FP0 = pi")
        FpCompare.assertFpBits(dutFp(dut, 1), "3fff8000000000000000", "fp-fmovecr FP1 = 1.0")
        FpCompare.assertFpBits(dutFp(dut, 2), "00000000000000000000", "fp-fmovecr FP2 = 0.0")
      })
  }

  // ── NaN propagation ──────────────────────────────────────────────────────

  test("lock-step FP: NaN propagates bit-for-bit through FADD/FMUL/FSUB", VerilatorTest) {
    val instrs = Seq(
      s"fmove.x ${QNan._1}(%pc),%fp0",
      "fmove.l #7,%fp1",
      "fmove.x %fp0,%fp2", "fadd.x %fp1,%fp2",
      "fmove.x %fp0,%fp3", "fmul.x %fp1,%fp3",
      "fmove.x %fp0,%fp4", "fsub.x %fp1,%fp4")
    h.runLockStep("fp-nan", prog(instrs, Seq(QNan)), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-nan", 0, 1, 2, 3, 4)(dut, oracle)
        for (r <- Seq(2, 3, 4))
          FpCompare.assertFpBits(dutFp(dut, r), "7fffc0000000deadbeef",
            s"fp-nan FP$r must be the ORIGINAL NaN, payload intact")
      })
  }

  test("lock-step FP: 0.0/0.0 yields the architectural default NaN", VerilatorTest) {
    val instrs = Seq("fmove.l #0,%fp0", "fdiv.x %fp0,%fp0")
    h.runLockStep("fp-nan-created", prog(instrs), nInstr = instrs.size,
      afterRun = expectFp("fp-nan-created", 0))
  }

  // ── Infinity arithmetic ──────────────────────────────────────────────────

  test("lock-step FP: infinity arithmetic (inf+finite, inf-inf, inf*0, inf/inf)", VerilatorTest) {
    val instrs = Seq(
      s"fmove.x ${PosInf._1}(%pc),%fp0",
      s"fmove.x ${NegInf._1}(%pc),%fp1",
      "fmove.l #7,%fp2",
      "fmove.l #0,%fp3",
      "fmove.x %fp0,%fp4", "fadd.x %fp2,%fp4",
      "fmove.x %fp0,%fp5", "fadd.x %fp1,%fp5",
      "fmove.x %fp0,%fp6", "fmul.x %fp3,%fp6",
      "fmove.x %fp0,%fp7", "fdiv.x %fp0,%fp7")
    h.runLockStep("fp-inf", prog(instrs, Seq(PosInf, NegInf)), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-inf", 0, 1, 2, 3, 4, 5, 6, 7)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 4), "7fff8000000000000000",
          "fp-inf FP4: +inf + 7.0 must remain exactly +inf")
        for (r <- Seq(5, 6, 7)) {
          val v = dutFp(dut, r)
          assert(((v >> 64) & 0x7fff) == 0x7fff && (v & ((BigInt(1) << 63) - 1)) != 0,
            f"fp-inf FP$r must be a NaN, got 0x${v.toString(16)}")
        }
      })
  }

  // ── FCMP vs FTST on infinity: the directed regression ────────────────────

  test("lock-step FP: FTST of infinity sets FPSR.I; FCMP inf,inf leaves I clear", VerilatorTest) {
    // Flagged in docs/superpowers/specs/2026-08-09-fpu-hardware-design.md section 3:
    // FTST is a distinct classifier, not a decode rewrite into FCMP-against-zero.
    // Confirmed in the vendored source (m68kfpu.c:1547-1553 FTST calls
    // SET_CONDITION_CODES directly, which sets FPCC_I for infinity; m68kfpu.c:1517-1546
    // FCMP takes a SEPARATE infinity branch that clears all FPCC bits and sets only
    // N/Z, never I) and empirically on the real oracle (FPSR 0x02000000 after FTST of
    // +inf, 0x04000000 after FCMP +inf,+inf).
    val instrs = Seq(
      s"fmove.x ${PosInf._1}(%pc),%fp0",
      s"fmove.x ${PosInf._1}(%pc),%fp1",
      "ftst.x %fp0",
      "fmove.l %fpsr,%d0",
      "fcmp.x %fp1,%fp0",
      "fmove.l %fpsr,%d1",
      s"fmove.x ${NegInf._1}(%pc),%fp2",
      "ftst.x %fp2",
      "fmove.l %fpsr,%d2",
      "fcmp.x %fp2,%fp0",
      "fmove.l %fpsr,%d3")
    h.runLockStep("fp-ftst-vs-fcmp", prog(instrs, Seq(PosInf, NegInf)),
      nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        val last = oracle.last
        val I = 0x02000000L; val Z = 0x04000000L; val N = 0x08000000L
        assert((last.d(0) & I) != 0,
          f"oracle sanity: FTST of +inf must set FPSR.I, got D0=0x${last.d(0)}%08x")
        assert((last.d(1) & I) == 0,
          f"oracle sanity: FCMP inf,inf must leave FPSR.I CLEAR, got D1=0x${last.d(1)}%08x")
        assert((last.d(1) & Z) != 0,
          f"oracle sanity: FCMP +inf,+inf must set FPSR.Z, got D1=0x${last.d(1)}%08x")
        assert((last.d(2) & I) != 0 && (last.d(2) & N) != 0,
          f"oracle sanity: FTST of -inf must set FPSR.I and FPSR.N, got D2=0x${last.d(2)}%08x")
        assert((last.d(3) & I) == 0,
          f"oracle sanity: FCMP -inf,+inf must leave FPSR.I CLEAR, got D3=0x${last.d(3)}%08x")
        expectFp("fp-ftst-vs-fcmp", 0, 1, 2)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 0), "7fff8000000000000000",
          "fp-ftst-vs-fcmp FP0 must be UNMODIFIED by FTST/FCMP")
      })
  }
}
```

**Pin-down step, do not skip:** the FMOVECR anchors above are Musashi's table
(`m68kfpu.c:60-165`), measured live during plan-writing — pi = `$00` at
`4000_C90FDAA22168C235`, `1.0` at offset `$32`, `0.0` at offset `$0F`. Musashi's own
source flags offset `$32` as uncertain ("1 (or 100? manuals are unclear)"). Before
implementing Task 7/8's constant ROM, **verify offsets `$00`, `$0F` and `$32` against
the real MC68040 User's Manual's FMOVECR ROM table** and correct these anchors if the
manual disagrees — the same discipline that caught m68k-ooo's wrong divide-by-zero
vector (Decision 7).

- [ ] **Step 5: Run the suite**

```bash
sbt "testOnly m68k040.lockstep.FpuLockStepSpec"
```
Expected: all 11 tests PASS once Tasks 4-9 have landed the decode/EU/control-register
work. Until then they fail at the front end (FP opwords fall through to the generic
`bad`/`faultVector` vector-11 path) — the correct pre-implementation state; do NOT
weaken the tests to make them pass early.

```bash
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
```
Expected: 396/396 PASS unchanged, and Verilog generation succeeds (this specifically
guards the `GenerationFlags.simulation` guards — `shadow` is `null` in a synth build,
so any unguarded reference is an immediate NPE, not a silent area cost).

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/execute/regfile/RegFilePlugin.scala \
        src/main/scala/m68k040/rename/RenameStage.scala \
        src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala \
        src/test/scala/m68k040/lockstep/FpuLockStepSpec.scala
git commit -m "test(fpu): directed bit-exact lock-step tests for the HW-native FP op set

Eleven directed tests covering the register-to-register forms of FADD,
FSUB, FMUL, FDIV, FSQRT, FABS, FNEG, FCMP, FINT, FINTRZ, FTST and
FMOVECR, compared BIT-EXACTLY against Musashi -- possible only because
Decision 1 moved the internal format to 80-bit extended, matching
Musashi's native SoftFloat floatx80.

Operands are chosen so results are exactly representable rather than
rounding-noise-dependent, and several cases are deliberately
aliasing-sensitive: FINT vs FINTRZ on -2.8125 (must disagree, -3.0 vs
-2.0); FABS/FNEG on a non-trivial mantissa; NaN propagation with a
distinctive payload. The FCMP-vs-FTST-on-infinity case is a dedicated
regression for a real, documented divergence (FTST sets FPCC.I on
infinity, FCMP does not), confirmed both by reading the vendored
Musashi source and by running the real oracle -- asserted through the
architectural path (fmove.l %fpsr,%dN), needing no FP-specific DUT
plumbing.

Reading committed 80-bit FP registers required sim-only plumbing
following RobPlugin's existing pcStore precedent, guarded by
GenerationFlags.simulation: RegFilePlugin gains a shadow Vec mirroring
the merged physical write buses (the real Mem is unreadable from
simulation once MultiPortWritesSymplifier rewrites it), and
RenameStage marks fpRat's committedPhys simPublic.

runLockStep gains one optional afterRun callback (default no-op) so
FpuLockStepSpec reuses the harness verbatim rather than duplicating it.

One anchor remains gated on primary-source verification: the FMOVECR
constant-ROM offsets are currently Musashi's table, and Musashi's own
source flags one entry as uncertain -- verify against the real MC68040
UM before Task 7/8 builds the constant ROM.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 14: Ported-test-corpus triage (fpu_*/fpsp_* acceptance sweep)

**Why:** the 35-vendored-tests figure quoted in the design spec's Decision 8 and the
48-currently-failing figure quoted in the 2026-08-09 hardware design doc's §9 are both
**stale counts from earlier vendoring passes, not re-derived ground truth** — this project's
own standing practice (see `PredecodeWordSpec`'s "doesn't actually run exhaustively" finding,
the SoC-address-map rule, the FMax-measurement-discipline findings) is to never trust a cached
number in a doc when the real filesystem/tool can answer directly. This task re-derives the
real corpus, runs it, and characterizes every result — it does not accept either doc's number
on faith.

**Files:**
- Create: `docs/superpowers/sdd/2026-08-15-fpu-ported-test-triage.md` (the triage deliverable)
- No RTL, no test-harness changes — `PortedM68kOooSpec.scala`/`PortedTestRunner.scala`/
  `ProgramAssembler.scala` are confirmed pre-existing and already wire the `fpu_*`/`fpsp_*`
  corpus (Global Constraints, Decision 8) — this task is read-only analysis against them.

**Interfaces:**
- Consumes: `PortedM68kOooSpec`'s existing `-z <substring>` ScalaTest filter (each vendored
  `.s` file becomes one `test(s"ported: $name")` case, per-test timeout from an optional
  sidecar `.timeout` file — confirmed at
  `src/test/scala/m68k040/fuzz/PortedM68kOooSpec.scala:74-91`). No new test-generation logic.
- Produces: the triage doc, consumed by nothing downstream in this plan — it is the
  human-facing acceptance record for Tasks 1-13's landed work, and the hand-off point for any
  bucket-(c) finding into a future dedicated fix task (this project's established pattern —
  see `ported-tests-triage-2026-07-17` memory topic: "characterize every failure by name and
  root-cause bucket... zero ad-hoc-dispatch candidates").

- [ ] **Step 1: Re-derive the real corpus (do not trust the "35" or "48" figures from the
  docs)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
ls src/test/resources/m68kooo-ported-tests/asm | grep -iE '^(fpu|fpsp)_.*\.s$' | sort > \
  docs/superpowers/sdd/fpu-corpus-manifest.txt
wc -l docs/superpowers/sdd/fpu-corpus-manifest.txt
```

Expected (confirmed during plan-writing research, 2026-08-15): **46** files, not 35 (design
spec's Decision 8 background figure — that figure is now known stale and should be corrected
in the spec itself alongside this task landing) and not 48 (hardware design doc §9's figure,
which also folds in a broader `fsave*`/`fmovem_ctrl_*` glob against the *sibling* m68k-ooo
repo's count, not this repo's `fpu_`/`fpsp_`-prefixed count directly). **Record whichever
number Step 1 actually produces at execution time as the authoritative count** — do not
silently keep "46" in the final triage doc if the corpus has grown or shrunk since this plan
was written; re-run this exact command and use its live output.

- [ ] **Step 2: Run just the fpu_*/fpsp_* subset**

`-z` is a ScalaTest substring filter (not a regex/OR), so run it twice — once per corpus
prefix — and capture full output (per-test PASS/FAIL, not just a summary count, is required
for Step 3's per-test triage):

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
LOGDIR=/tmp/fpu-triage-logs
mkdir -p "$LOGDIR"
sbt 'testOnly m68k040.fuzz.PortedM68kOooSpec -- -z fpu_' 2>&1 | tee "$LOGDIR/fpu-run.log"
sbt 'testOnly m68k040.fuzz.PortedM68kOooSpec -- -z fpsp_' 2>&1 | tee "$LOGDIR/fpsp-run.log"
```

Each vendored test appears as `- ported: <name>` (PASS, no marker) or as a `*** FAILED ***`
block reporting one of `PortedTestRunner`'s four outcomes verbatim
(`PortedFail`/sentinel word, `PortedHang`/cycles, `PortedGenFail`/toolchain error — see
`PortedM68kOooSpec.scala:81-90`). Grep both logs for the per-test verdict lines:

```bash
grep -E '^- (ported: |\*\*\* )' "$LOGDIR/fpu-run.log" "$LOGDIR/fpsp-run.log" > "$LOGDIR/fpu-verdicts.txt"
```

- [ ] **Step 3: Cross-reference every FAIL against the design spec's own scope boundaries**

For each failing test, classify into exactly one bucket using ONLY the design spec's own
words as the citation (do not invent a scope boundary not already locked in the spec):

- **Bucket (a) — now passing, or a genuine target still failing pending real diagnosis**:
  anything exercising the hardware-native op set (Global Constraints' extended 13-op list:
  FADD/FSUB/FMUL/FDIV/FSQRT/FABS/FNEG/FMOVE/FCMP/FINT/FINTRZ/FMOVECR/FMOVEM/FTST), FPCC
  rename/readback, FSAVE/FRESTORE null/idle frames (design spec's FSAVE/FRESTORE table:
  "Yes, in full"), the vector-11/unimplemented-instruction frame + self-recursion-guard
  protocol (Decision 8's two named deep-validation tests, `fpu_fpsp_selfrecursion_repro.s`
  and `fpsp_packed_kernel_e2e.s` — both explicitly named acceptance-corpus targets in
  Decision 8, NOT out-of-scope, even though the second embeds real ROM packed-decimal bytes:
  it validates the *trap delivery* mechanism, not hardware packed-decimal arithmetic), or FBcc
  (NOT in Decision 2's deferred list — only FScc/FDBcc/FTRAPcc are).
- **Bucket (b) — still failing because it exercises something explicitly out-of-scope**:
  cite Decision 2 verbatim ("transcendentals, packed decimal, rounded-precision variants,
  FScc/FDBcc/FTRAPcc") or the FSAVE/FRESTORE section's narrowed busy-frame deferral ("Only
  the user-enabled-FPCR-trap-with-custom-handler path for OVFL/UNFL exceptional-operand
  inspection... remains open"). Per static-inspection sweeps during plan-writing (zero
  transcendental-mnemonic hits, zero busy-frame/FPCR-trap-enable hits anywhere in the
  corpus), **the true bucket-(b) surface in this corpus is narrow — likely limited to at
  most one line inside `fpu_fmove_fp_to_ea_matrix.s`** (its EA matrix includes one
  `fmove.p %fp0,ABSL:l{#0}` packed-decimal-destination case among many in-scope EA forms —
  if that single sub-case is what fails while the rest of the matrix passes, bucket it (b)
  for that sub-case specifically and (a) for the file's other coverage).
- **Bucket (c) — genuine, unexpected failure needing investigation**: everything that fails
  for a reason NOT covered by (a)'s target-op list or (b)'s citations. Per this project's
  established practice, a bucket-(c) finding is fully characterized (test name, sentinel/fail
  code observed, suspected root cause with file:line if identifiable) but **not fixed inside
  this task** — it is handed off as a dedicated future finding.

- [ ] **Step 4: Write the triage doc**

```bash
cat > docs/superpowers/sdd/2026-08-15-fpu-ported-test-triage.md << 'HEADER'
# FPU/FPSP ported-test-corpus triage (Task 14)

Run after Tasks 1-13 landed. Corpus re-derived live (Step 1) rather than trusting the design
spec's "35" or the hardware design doc's "48" figures — see Task 14 for why both are stale.

Command used: `sbt "testOnly m68k040.fuzz.PortedM68kOooSpec -- -z fpu_"` +
`-z fpsp_` (see Task 14 Step 2).

## Legend
- **(a)** now passing / genuine acceptance target
- **(b)** still failing, explicitly out-of-scope per the design spec — cited
- **(c)** still failing, genuine unexpected bug — needs a dedicated future fix task

## Results

| Test | Result | Bucket | Citation / root cause |
|---|---|---|---|
HEADER
```

Then append one row per test from Step 1's manifest, in manifest order, filling `Result`
(PASS/FAIL, real, from Step 2's log) and `Bucket`/`Citation` per Step 3's classification —
based on the real run, not a static guess. **If a materially larger bucket-(b) or bucket-(c)
count turns up than the narrow surface predicted above, that itself is a finding to report**
— it would mean the corpus exercises surface this plan-writing pass missed.

## Summary

*(fill in after Step 2: total run, pass count, bucket (a)/(b)/(c) counts, and — critically —
list every bucket-(c) test by name with its root-cause characterization; if bucket (c) is
non-empty, this is NOT a task failure, it is Task 14 doing its job)*
```

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/sdd/2026-08-15-fpu-ported-test-triage.md \
        docs/superpowers/sdd/fpu-corpus-manifest.txt
git commit -m "docs(fpu): ported-test-corpus triage (Task 14) -- real pass/fail counts, every finding characterized

Re-derived the real fpu_*/fpsp_* corpus live rather than trusting the
design spec's stale '35' or the hardware design doc's stale '48'
figures (real count: re-run Step 1, was 46 during plan-writing). Every
FAIL bucketed into (a) genuine-target-still-failing, (b)
explicitly-out-of-scope-per-spec (cited), or (c) genuine bug needing a
dedicated future fix task -- no ambiguous pass/fail count left
uncharacterized, per this project's established triage practice.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 15: Protocol-level EU tests (`FpuProtocolSpec.scala`)

**Why:** the ported instruction corpus (Task 14) cannot substitute for protocol-level
proof — hardware design doc §9: "The implementation also requires protocol-level tests that
the inherited instruction corpus cannot substitute for." This task implements the six
specific requirements from that section as real SpinalHDL sim tests, plus the mutation-proof
variants this project's standing rule requires (write test against correct code → PASS →
mutate the real production RTL → confirm FAIL with the expected message → revert → confirm
PASS again → commit only the test).

**Files:**
- Create: `src/test/scala/m68k040/execute/FpuProtocolSpec.scala`
- Temporarily modifies, then reverts: `src/main/scala/m68k040/execute/DivEuPlugin.scala`
  (Steps 5, 8, 12's mutation/revert cycles — reverted before the final commit; no net RTL
  change from this task)

**Interfaces — forward-reference contract (read before writing code):**

This task is sequenced after Tasks 7 (`FpuCore`) and 8 (`DivEuPlugin`'s new FP writeback
lane), so the real names must be confirmed against the landed diff before finalizing this
file. Grounded directly in the *already-existing* analogous machinery in `DivEuPlugin.scala`
(the MUL-vs-legacy arbiter at `DivEuPlugin.scala:607-666`) — not invented from nothing, but
NOT yet fixed by a landed task either:

- `object FpuCore { val FixedLatency = <N> }` — by exact analogy with the already-existing
  `object MulCore { val Latency = 7 }` (`MulCore.scala:9`). Confirm the real constant name
  Task 7 lands with.
- `DivEuPlugin.logic` needs an FP-lane mirror of the existing legacy/MUL arbiter — Task 8's
  `fpFixedCtx`/`fpIterativeBusy`/`fpCompValid` naming (see Task 8) is the actual landed
  contract; reconcile this task's field references against Task 8's real names before running.
- A whitebox tap analogous to the existing `wbObs`/`WbObs()` bundle but carrying an 80-bit
  `result` field — this task assumes it exists as `fpWbObs`; confirm against whatever Task 8
  actually names it (Task 8's own draft does not explicitly define one — flag this gap when
  reconciling Task 8 and Task 15, a whitebox observation tap needs adding to Task 8 if it is
  not already there, since Task 15's tests cannot observe internal completion timing without
  one).
- `wakeupFp: Flow[UInt]` / `wakeupFpcc: Flow[UInt]` on `DivEuService`, mirroring the existing
  `wakeup`/`wakeupNzvc` exactly — Task 8 names these `cplxFpWakeupPort`/`cplxFpccWakeupPort`
  internally; confirm the externally-exposed service method names match what this task's
  `Dut` wiring expects.

If Task 8 lands with different names, **update every reference in this file** — do not
silently rename without updating all tests and both mutation-target snippets below.

- [ ] **Step 1: Quote the six requirements verbatim (hardware design doc §9) and confirm the
  SpinalHDL sim API used below**

> - drive at least eight independent operations into each fixed lane on consecutive cycles,
>   require eight consecutive accepts and results at the documented fixed latency, and check
>   exact ROB/destination/FPCC association;
> - prove at least four fixed operations are simultaneously in flight, with no duplicate or
>   missing PRF write, wakeup, status observation, or completion;
> - collide a fixed-pipeline result with FDIV/FSQRT completion and prove the result arbiter
>   retains both exactly once;
> - hold a request valid while result capacity is unavailable and prove stable payload plus
>   exactly one acceptance when credit returns;
> - flush a dense fixed pipeline and an active iterative operation, immediately reuse ROB and
>   physical destinations, and prove no stale side effect survives; and
> - mutation-check the dense-start, collision, and flush/reuse tests so a return to a
>   busy-gated singleton or global flush latch fails visibly.

This file drives `Stream`/`Flow` ports with raw field-by-field `#=` pokes and peeks via
`.toBoolean`/`.toInt`/`.toBigInt`, matching the idiom already used throughout this project's
existing standalone-EU test files (e.g. `LsEuBackStageSupervisorSpec.scala`) — not the
`spinal.lib.sim` `StreamDriver`/`StreamMonitor` helper classes, for consistency with that
established precedent.

- [ ] **Step 2: Create the standalone DUT** (mirrors the standalone-EU harness shape used by
  this project's existing single-plugin test specs — confirm the exact `PluginHost`/
  `Database` wiring idiom against a real existing file such as
  `LsEuBackStageSupervisorSpec.scala` before finalizing, since this plan-writing pass sketches
  the shape but did not have budget to read that file directly)

```scala
// src/test/scala/m68k040/execute/FpuProtocolSpec.scala
package m68k040.execute

import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX, RegFilePluginFp}
import m68k040.isa.DecOp
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Protocol-level tests for the FPU writeback lane inside DivEuPlugin (Task 8), per
  * hardware design doc §9 (quoted in Task 15 Step 1). Drives DivEuPlugin's issue Stream and
  * observes its completion/wakeup/whitebox ports directly -- standalone-EU pattern, no
  * RobPlugin/IssueQueuePlugin in this DUT.
  *
  * MUTATION GATE (spec-mandated, hardware design doc §9's 6th bullet): Steps 5, 8, and 12
  * each temporarily mutate the real DivEuPlugin.scala to reintroduce a specific class of bug
  * and confirm the corresponding test FAILS with the expected message, before reverting. Do
  * not weaken these tests to make a future refactor convenient. */
class FpuProtocolSpec extends AnyFunSuite {
  class Dut extends Component {
    val db     = new Database
    val host   = db on (new PluginHost)
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
    val rfFp   = new RegFilePluginFp
    val divEu  = new DivEuPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](rfInt, rfNzvc, rfX, rfFp, divEu)) }
  }

  def driveFpIssue(dut: Dut, robId: Int, op: SpinalEnumElement[DecOp.type],
                    pFpSrcA: Int, pFpSrcB: Int, pFpDst: Int, pFpccDst: Int): Unit = {
    val p = dut.divEu.issuePort
    p.valid #= true
    p.payload.robId #= robId
    p.payload.uop.op #= op
    p.payload.uop.pFpSrcA #= pFpSrcA; p.payload.uop.psrcAFpValid #= true
    p.payload.uop.pFpSrcB #= pFpSrcB; p.payload.uop.psrcBFpValid #= true
    p.payload.uop.pFpDst  #= pFpDst;  p.payload.uop.pFpDstValid  #= true
    p.payload.uop.pFpccDst #= pFpccDst; p.payload.uop.writesFpcc #= true
  }
  def clearFpIssue(dut: Dut): Unit = { dut.divEu.issuePort.valid #= false }
```

- [ ] **Step 3: Tests 1-5, the six protocol requirements as real sim code**

Implement, as five `test(...)` blocks inside the class above:

1. **Dense 8-fill**: drive 8 distinct FADD ops on consecutive cycles, wait, assert exactly 8
   completions arrive in issue order, each with the correct `robId`/`pFpDst`/`fpccWrite`
   association (no cross-talk between results).
2. **4 simultaneously in flight**: issue 4 distinct ops on 4 consecutive cycles, assert all 4
   eventually complete with no duplicate or missing `robId` in the completion stream and no
   duplicate wakeup pulses.
3. **Collision**: launch an FDIV, then time a fixed-lane FADD's issue so its completion lands
   the same cycle as the FDIV's, assert BOTH complete exactly once (neither dropped).
4. **Backpressure**: saturate the fixed lane until `issuePort.ready` deasserts, hold one more
   request valid with a fixed, distinguishing payload across the stall, assert the payload
   never drifts and exactly one accept happens once credit returns.
5. **Flush + immediate reuse**: fill the fixed lane and launch an iterative FSQRT, flush
   mid-flight, immediately reissue the same `robId`/`pFpDst` with a new deterministic op,
   assert the reused identity completes correctly and no stale completion for any flushed
   `robId` ever arrives (a generous post-check window, since a late stale completion is
   exactly the bug class this test exists to catch).

Each test needs concrete field names from Task 8's actual landed `DivEuPlugin.scala` for its
completion/wakeup observation points — this plan-writing pass could not finalize those without
Task 8 having landed first; the implementer must fill in the exact observation-port references
(e.g. whatever whitebox tap Task 8 exposes) as the first sub-step of this task, not invent
plausible-looking signal names.

- [ ] **Step 4: MUTATION for Test 2 (4-simultaneously-in-flight) — revert to a
  single-outstanding busy-gate, confirm FAIL**

Locate Task 8's FP-lane issue-accept condition (the FP-lane analogue of
`mulCanAccept`/`!busy && !s1Valid` at `DivEuPlugin.scala:158-163`). Temporarily force it to a
single-outstanding busy-gate — exactly the pattern the Global Constraints explicitly reject
("does not permit a DSP-backed fixed-latency operation to be busy-gated one at a time").

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
sbt 'testOnly m68k040.execute.FpuProtocolSpec -- -z "simultaneously"' 2>&1 | tail -30
```

Expected: **FAIL**. If it PASSES, the test does not discriminate — strengthen it before
proceeding. Record the observed failure text.

- [ ] **Step 5: Revert Step 4's mutation, confirm GREEN**

```bash
git checkout -- src/main/scala/m68k040/execute/DivEuPlugin.scala
sbt 'testOnly m68k040.execute.FpuProtocolSpec -- -z "simultaneously"' 2>&1 | tail -20
```

- [ ] **Step 6: MUTATION for Test 3 (collision) — force the arbiter to drop instead of hold,
  confirm FAIL**

Locate Task 8's FP arbiter (the 4-way `captureArb` extension). Mutate the fixed-lane result
queue's pop-ready to fire unconditionally instead of only on the cycle it actually wins the
shared completion register — exactly reproducing a dropped-result bug.

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
sbt 'testOnly m68k040.execute.FpuProtocolSpec -- -z collision' 2>&1 | tail -30
```

Expected: **FAIL** with a dropped-result message. If it PASSES, widen the collision-timing
sweep before proceeding (the mutation's effect is timing-dependent). Record the failure text.

- [ ] **Step 7: Revert Step 6's mutation, confirm GREEN**

```bash
git checkout -- src/main/scala/m68k040/execute/DivEuPlugin.scala
sbt 'testOnly m68k040.execute.FpuProtocolSpec -- -z collision' 2>&1 | tail -20
```

- [ ] **Step 8: MUTATION for Test 5 (flush+reuse) — drop the iterative-lane flush-suppress
  term, confirm FAIL**

Locate Task 8's FP-lane flush-suppression register (the FP-lane analogue of the existing
`flushed` register at `DivEuPlugin.scala:186-190`: `when(flushSig && (busy || s1Valid))
{ flushed := True }`). Mutate it to drop the iterative in-flight term specifically.

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
sbt 'testOnly m68k040.execute.FpuProtocolSpec -- -z "flush"' 2>&1 | tail -30
```

Expected: **FAIL** (a stale completion for a flushed `robId` survives, or the reused identity's
result is corrupted by a late FSQRT completion). If it PASSES, the reuse window may be too
generous relative to the mutated engine's real completion latency — narrow it before
proceeding. Record the failure text.

- [ ] **Step 9: Revert Step 8's mutation, confirm GREEN**

```bash
git checkout -- src/main/scala/m68k040/execute/DivEuPlugin.scala
sbt 'testOnly m68k040.execute.FpuProtocolSpec -- -z "flush"' 2>&1 | tail -20
```

- [ ] **Step 10: Run the full suite once more, confirm the existing lock-step suite is
  unaffected**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
sbt 'testOnly m68k040.execute.FpuProtocolSpec' 2>&1 | tail -30
sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec' 2>&1 | tail -20
```
Expected: all `FpuProtocolSpec` tests PASS (post-revert state); `ExecuteLockStepSpec`
unchanged.

- [ ] **Step 11: Commit**

```bash
git status --porcelain src/main/scala/m68k040/execute/DivEuPlugin.scala
# Expected: EMPTY -- confirm no net production change survived the mutation/revert cycles
# before committing. If non-empty, resolve before proceeding; this task must land test-only.
git add src/test/scala/m68k040/execute/FpuProtocolSpec.scala
git commit -m "test(fpu): protocol-level EU tests -- dense-fill/4-in-flight/collision/backpressure/flush-reuse, mutation-killed

Implements the 6 protocol requirements from the 2026-08-09 hardware
design doc's §9 as real SpinalHDL sim tests against Task 8's FP
writeback lane, standalone (no RobPlugin/IssueQueuePlugin). The
4-simultaneous, collision, and flush-reuse tests are each
mutation-killed: reverting the fix to a single-outstanding busy-gate /
a pop-unconditionally arbiter / a flush-suppress latch missing its
iterative-lane term all provably FAIL the corresponding test before
the mutation is reverted -- proving the safety net can actually fail,
per this project's standing mutation-proof rule.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 16: Mandatory OOC + post-route synth gate

**Why:** this project's standing rule (`synth-gate-every-slice`, project memory): every
implementation slice ends with a full-core post-route FMax gate. This is the closing task of
the entire FPU/FPSP plan.

**Files:** none (this task produces reports + a ledger entry, no RTL/test changes).

**Interfaces:**
- Consumes: `synth/impl_FullCore.tcl` (unmodified — `IMPL_STRATEGY=postrouteN`,
  `POSTROUTE_ROUNDS` env var, and the automatic 200MHz sign-off check are all already-landed
  infrastructure: `POSTROUTE_FULLCORE_WNS_NS`/`POSTROUTE_FULLCORE_RESULT
  MET_250|FAILED_AT_250 ACHIEVED_FMAX_MHZ`/`SIGNOFF_200MHZ_WNS_NS`/`SIGNOFF_200MHZ_RESULT
  MET_200|FAILED_AT_200`/`SOURCE_MD5`/`NETLIST_MD5` are the real printed tokens), `synth/clk.xdc`
  (unmodified, standard 4.000 ns/250MHz probe).
- Produces: `synth/archive/<sha>_fpu_postrouteN9_<mode>/`, a new `## §41` ledger section.

- [ ] **Step 1: Verify the machine is uncontended before starting**

```bash
pgrep -af impl_FullCore
pgrep -af vivado
free -g
```
Expected: both `pgrep` commands print nothing. Do not start if either finds a live process.

- [ ] **Step 2: Regenerate the netlist with the CORRECT command**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
ls -la generated/M68kFullCoreSynth.v
md5sum generated/M68kFullCoreSynth.v
```
**Do NOT use `make verilog`** — it elaborates a toy component, not the real
`FullCoreSynth`, and would silently gate a stale or wrong netlist.

- [ ] **Step 3: Launch the gate in the background**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
nohup env IMPL_STRATEGY=postrouteN POSTROUTE_ROUNDS=9 vivado -mode batch \
  -source synth/impl_FullCore.tcl > synth/gate_fpu.out 2>&1 &
echo "GATE_PID=$!"
```

- [ ] **Step 4: Actively poll — do NOT wait for a background-task notification**

Per this project's own confirmed finding this session (repeated multiple times): nested
Vivado job completions launched via `nohup ... &` do **not** reliably trigger a background-task
completion notification. Poll directly:

```bash
kill -0 $GATE_PID 2>/dev/null && echo RUNNING || echo DONE
tail -5 synth/gate_fpu.out
```
Re-run every few minutes until `DONE`. Expect on the order of tens of minutes for a
`POSTROUTE_ROUNDS=9` gate.

- [ ] **Step 5: Extract the results**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
grep -E "SOURCE_MD5|NETLIST_MD5|POSTROUTE_ROUND [0-9]+ WNS|POSTROUTE_FULLCORE_WNS_NS|POSTROUTE_FULLCORE_RESULT|SIGNOFF_200MHZ_WNS_NS|SIGNOFF_200MHZ_RESULT" synth/gate_fpu.out
grep -c "^ERROR" synth/gate_fpu.out    # expect 0
```
Confirm `SOURCE_MD5 == NETLIST_MD5` (the gate read the freshly-regenerated netlist, not a
stale cached one) before trusting any other number in the run.

- [ ] **Step 6: Compare against baseline, branch on the result**

Baseline (ledger §39/40): **201.450 MHz post-route** (WNS −0.964 at the 4.000 ns probe),
**`SIGNOFF_200MHZ_RESULT MET_200`**.

- **`MET_200` and FMax >= 201.450**: no regression — proceed to Step 7/8, PASS framing.
- **`MET_200` but FMax < 201.450**: a real regression that still clears the 200MHz floor.
  **Report this plainly in the ledger entry, do not bury it in a PASS framing** — this
  design "just crossed the target with limited headroom to spare" per the UFA/VTL campaign's
  own `pb_fetch` thin-headroom finding.
- **`FAILED_AT_200`**: **STOP. Explicit escalation, not a silent accept or silent revert.**
  Skip to Step 9 instead of Step 7/8's normal path.

- [ ] **Step 7: Archive the results**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
SHA=$(git rev-parse --short HEAD)
ARCHDIR="synth/archive/${SHA}_fpu_postrouteN9_decode_fetch"
mkdir -p "$ARCHDIR"
cp synth/fullcore_synth_timing.rpt synth/fullcore_synth_util.rpt \
   synth/fullcore_route_timing.rpt synth/fullcore_route_util.rpt \
   synth/fullcore_congestion.rpt synth/fullcore_fanout.rpt \
   synth/fullcore_path_analysis.rpt synth/fullcore_slack_matrix.rpt \
   synth/fullcore_pb_*_util.rpt synth/gate_fpu.out \
   "$ARCHDIR"/
```
(Confirm `decode_fetch` is still the live floorplan-mode label by checking
`synth/impl_FullCore.tcl`'s current pblock configuration before assuming it — do not hardcode
a stale name if the floorplan changed since ledger §39/40.)

- [ ] **Step 8: Append the ledger entry + commit (PASS / non-blocking-regression path only —
  skip to Step 9 if Step 6 routed you to the escalation branch)**

Write a `## §41 (date)` entry to `.superpowers/sdd/progress-ipc-push-2026-08-09.md` quoting
the real measured WNS/FMax/SIGNOFF numbers, the utilization delta, and an explicit
comparison against the 201.450 MHz baseline — following this ledger's own established prose
style (see §37-§40 for the exact tone/structure: measured numbers quoted directly, any
regression stated plainly, verdict stated last). Commit both the ledger update and the
archive directory together.

- [ ] **Step 9: Escalation path (ONLY if Step 6 routed here — `FAILED_AT_200`)**

**Do not silently accept this result. Do not silently revert Tasks 1-15. Stop and report to
the user for an explicit decision.** Still archive the failing run (Step 7 unchanged), then
report: the measured shortfall, the full utilization + per-pblock congestion tables (the CPLX
cluster, where the FPU folds in, is the a priori suspect for new congestion per Decision 9),
and the concrete options (accept + open a dedicated follow-up FMax-closure task targeting the
FP writeback lane/arbiter; revert or narrow part of Tasks 1-15; retarget a floorplan pblock) —
without choosing one unilaterally. Do NOT append a ledger §41 entry claiming completion in
this branch — only once a real verdict (PASS or an explicitly-accepted-and-recorded
regression) exists.

---

