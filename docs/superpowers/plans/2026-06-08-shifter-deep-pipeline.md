# Shifter Deep-Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pipeline the line-E barrel shifter across one extra register stage (shifts lat2→lat3, adaptive to lat4) so the now-isolated ~22-level shifter FMax cone is split roughly in half, moving the full core toward the 250 MHz post-route gate.

**Architecture:** Split the self-contained combinational `Shifter` into a `stage1` (all variable-shift networks) / `stage2` (bit-extraction + mux) API validated standalone against the `ShiftRef` Musashi mirror, then add an `S3` stage to `AluEuPlugin`'s slow path so `S1`=stage1, `S2`=stage2, `S3`=writeback. The `aluSlowWakeup` dynamic-completion fires one stage later (latency-agnostic); the single-outstanding slow stall extends to the deeper pipe.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt at `~/sbt/bin/sbt` (NOT on PATH) / Verilator / Vivado 2025.2 (`xcku5p-ffvb676-2-e`, OOC 4ns). Lock-step vs Musashi (`m68k040.oracle.Musashi`).

**Working dir:** an isolated git worktree on branch `feat/shifter-deep-pipeline` off current `master` (created by the executor via superpowers:using-git-worktrees). Run all `sbt`/`vivado` from the worktree root.

**Memory discipline (HARD):** NEVER run Verilator and Vivado concurrently. The full `ExecuteLockStepSpec` OOMs a 12 GB heap — always use `-z "<substr>"` subsets. A separate m68k030 QoR vivado runs intermittently and is NOT ours — only ever kill `040`/`FullCore` jobs; check `pgrep -af vivado` first. `make test-fast` (`sbt fastTest`) EXCLUDES `VerilatorTest` — Verilator specs must be run explicitly with `testOnly`.

---

## File Structure

- `src/main/scala/m68k040/execute/Shifter.scala` — **modify.** Add `ShiftStage1()` bundle; refactor `apply` into `stage1`/`stage2`; `apply = stage2(stage1(cmd))`. The 4 private rotate defs (`ror`/`rol`/`roxr`/`roxl`) each likewise split into a stage1-part (rotated word + ring) and stage2-part (extract/mux), or — simpler and equally valid — keep each rotate def whole but invoke it inside `stage2` while carrying its *inputs* (which are all already in `ShiftStage1`); see Task 1 for the chosen rule.
- `src/test/scala/m68k040/execute/ShifterSpec.scala` — **modify.** Add a `ShifterStagedDut` (`Shifter.stage2(Shifter.stage1(io.cmd))`) and a Layer-2b test running the same grid against `ShiftRef`.
- `src/main/scala/m68k040/execute/AluEuPlugin.scala` — **modify.** Slow path S1→S2→S3: add `s2Stage1` FFs, compute `stage2` at S2, rename `s2Shift*`→`s3Shift*`, move slow writeback/bypass/completion/wakeup/wbObs to S3, pipe `s2Ctx`/`s2Src1`→`s3Ctx`/`s3Src1`, extend the single-outstanding stall.
- `src/test/scala/m68k040/execute/AluFastSlowSpec.scala` — **modify if needed.** Bump any cycle-precise settle/observation window for the extra slow-path stage (assert *values* unchanged).
- `src/test/scala/m68k040/execute/BackendWhiteboxSpec.scala` — **modify if needed.** Same: if it injects a SHIFT, widen the settle window by one cycle.
- `synth/ooc_M68kFullCoreSynth.tcl`, `synth/impl_FullCore.tcl` — **use as-is** (gen + OOC + post-route).

---

## Task 1: Split `Shifter` into `stage1` / `stage2` (standalone, validated vs ShiftRef)

This is the heavy lift and the de-risk: it touches NO pipeline. Correctness is proven by `ShifterSpec` against the Musashi mirror before any EU edit.

**Files:**
- Modify: `src/main/scala/m68k040/execute/Shifter.scala`
- Test: `src/test/scala/m68k040/execute/ShifterSpec.scala`

**The cut rule (apply consistently):** Stage 1 contains every computation that performs a *variable-amount shift/rotate of data* — the result barrel-shifts (`asrArea.res`, `lsrArea.res`, `lshArea.resW`), the per-op carry-flag wide shifts (every `shl(...)`/`shr(...)`/`flBit` *argument*, i.e. the 66-bit shifted words BEFORE bit-8 indexing), the four rotate result words, and the ROX ring `res` + `cx`-bearing word + the `rmod` subtract-reduce. Stage 2 contains only *shallow* logic: `flBit`/`v(8)` bit indexing, `nOf`/`zOf` compares, the V `shTable` compares, the per-op `when(countZ)/when(count<size)/edge` selection, the `switch(sel)` op mux, and the final `& mask`. `shTable(count)` and `shTable(count+1)` are themselves variable shifts → compute them in stage 1 and carry the resulting masks.

- [ ] **Step 1: Add the `ShiftStage1` bundle**

In `Shifter.scala`, after `case class ShiftRsp()` (line ~32), add the intermediate bundle. Carry the cmd fields stage 2 still needs plus every stage-1 shifted word. Field set:

```scala
/** Registered midpoint of the 2-stage barrel shifter. Holds the cmd fields stage 2
  * still needs PLUS every VARIABLE-SHIFT output (result barrel-shifts, the per-op
  * carry-flag 66-bit shifted words pre-bit-8-index, the four rotate words, the ROX
  * ring). Stage 2 does only bit-indexing / compares / muxing on these — no variable
  * shift. Keeps the deep funnel-shifter cone entirely in stage 1. */
case class ShiftStage1() extends Bundle {
  // passthrough cmd fields
  val shiftOp = UInt(2 bits); val dirLeft = Bool(); val size = Size()
  val isImm   = Bool();       val xIn = Bool();     val count = UInt(6 bits)
  // size-derived (cheap, recomputed-or-carried)
  val mask    = UInt(32 bits); val sizeBits = UInt(7 bits)
  val src     = UInt(32 bits); val srcMsb = Bool()
  // ASR
  val asrRes  = UInt(32 bits); val asrCImmWord = UInt(66 bits); val asrCRegWLWord = UInt(66 bits)
  // LSR
  val lsrRes  = UInt(32 bits); val lsrCImmWord = UInt(66 bits); val lsrCRegWLWord = UInt(66 bits)
  // ASL/LSL (left)
  val lshResW = UInt(66 bits)
  val lshCBWord = UInt(66 bits); val lshCWimmWord = UInt(32 bits); val lshCWregWord = UInt(66 bits)
  val lshCLimmWord = UInt(32 bits); val lshCLregWord = UInt(66 bits)
  val lshTbl = UInt(32 bits)   // shTable(count+1) for ASL V
  // rotates: carry the four rotated/ring words + the per-op carry words.
  val rorRes = UInt(32 bits); val rorCImmWord = UInt(66 bits); val rorCRegBWord = UInt(66 bits); val rorCRegWLWord = UInt(66 bits)
  val rolRes = UInt(32 bits); val rolCImmBWord = UInt(66 bits); val rolCImmWWord = UInt(32 bits); val rolCImmLWord = UInt(32 bits)
  val rolCRegBWord = UInt(66 bits); val rolCRegWWord = UInt(66 bits); val rolCRegLWord = UInt(66 bits)
  val rolSMod = UInt(6 bits)   // rol needs sMod===0 distinction in stage2
  val roxrRes = UInt(32 bits); val roxrCx = Bool()
  val roxlRes = UInt(32 bits); val roxlCx = Bool()
}
```

(If a carried word's exact width differs from the source expression, match the source: the rule is "register the value the current code feeds into `flBit`/`v(8)`/the mux", at its current width.)

- [ ] **Step 2: Write `stage1` — move every variable shift into it**

Convert the current `apply` body so the `Area` it builds becomes `stage1(cmd): ShiftStage1`. Keep the existing `asrArea`/`lsrArea`/`lshArea` and the four rotate computations, but each now writes its *shifted words* into the `ShiftStage1` result instead of feeding the local `switch`. Concretely:
- `asrArea`: `s1.asrRes := asrArea.res`; `s1.asrCImmWord := shl(cMinus(9))`; `s1.asrCRegWLWord := (shr((count-1).resize(6)) << 8).resize(66)`.
- `lsrArea`, `lshArea`: same pattern for their `res`/`resW`/carry words + `s1.lshTbl := shTable((count+1).resize(6))`.
- The four rotates: factor each private def into a `*_stage1` returning the rotated word + carry words (and `roxrCx`/`roxlCx` for ROX, `rolSMod`), assigned into `s1`. Carry `src`, `srcMsb`, `mask`, `sizeBits`, and the passthrough cmd fields.

`stage1` returns the populated `ShiftStage1`.

- [ ] **Step 3: Write `stage2` — bit-index, compare, mux (no variable shift)**

`stage2(s1: ShiftStage1): ShiftRsp` rebuilds `rsp` using ONLY shallow ops on the carried words: `flBit(w) = w(8)`, `nOf/zOf` on `s1.*Res`, the V compares (`s1.lshTbl`), the per-op `when(countZ)/when(count<sizeBits)/edge` chains, and the `switch(s1.shiftOp @@ s1.dirLeft)` op mux — identical structure to today's `switch`, but reading `s1.asrRes` etc. instead of `asrArea.res`, and `s1.*Word(8)` instead of `flBit(shl(...))`. The count-edge constants (`countZ`, `count < sizeBits`, `count === sizeBits`) recompute from `s1.count`/`s1.sizeBits` (cheap compares, fine in stage 2).

- [ ] **Step 4: Make `apply` delegate**

```scala
def apply(cmd: ShiftCmd): ShiftRsp = stage2(stage1(cmd))
```

- [ ] **Step 5: Add the staged-equivalence test**

In `ShifterSpec.scala`, after `ShifterDut` (line ~107), add:

```scala
  class ShifterStagedDut extends Component {
    val io = new Bundle {
      val cmd = in(ShiftCmd())
      val rsp = out(ShiftRsp())
    }
    io.rsp := Shifter.stage2(Shifter.stage1(io.cmd))
  }
```

and a test mirroring the Layer-2 test verbatim but compiling `new ShifterStagedDut` (same grid, same `ShiftRef` asserts):

```scala
  test("RTL Shifter STAGED (stage2∘stage1) matches ShiftRef (all op x dir x size, imm + reg count, X-in)", VerilatorTest) {
    SimConfig.withVerilator.compile(new ShifterStagedDut).doSim { dut =>
      // ... identical body to the Layer-2 test (srcs, grid, asserts) ...
    }
  }
```

(Copy the Layer-2 loop body exactly — same `srcs`, `OPS`, `SIZES`, count grid, and the 6 asserts.)

- [ ] **Step 6: Run the shifter tests**

Run: `cd <worktree> && ~/sbt/bin/sbt 'testOnly m68k040.execute.ShifterSpec -- -n m68k040.VerilatorTest'`
Expected: both the combined and STAGED Layer-2 tests PASS (the staged one is the new gate; the combined one proves `apply` still equivalent). The SlowTest Layer-1 (ShiftRef vs live Musashi) is unaffected.

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/m68k040/execute/Shifter.scala src/test/scala/m68k040/execute/ShifterSpec.scala
git commit -m "shifter: split into stage1 (variable shifts) / stage2 (extract+mux); apply=stage2∘stage1; staged test vs ShiftRef"
```

---

## Task 2: EU slow path → S1 → S2 → S3 (lat-3)

**Files:**
- Modify: `src/main/scala/m68k040/execute/AluEuPlugin.scala`
- Test: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` (run only)

Current slow path (`AluEuPlugin.scala`): S1 builds `shiftCmd` (lines ~216-223) and `val shiftRsp = Shifter(shiftCmd)` (line 224); S2 registers `s2Shift*` (lines 227-232), size-merges + slow-writes (lines 238-251), fires `slowWakeupPort` (256-262) and completion (266-267). The fast path is untouched by this task.

- [ ] **Step 1: Compute stage1 at S1, register into S2**

Replace line 224 `val shiftRsp = Shifter(shiftCmd)` with:

```scala
    // SLOW path stage 1 (S1): the deep variable-shift networks only. Register the
    // ShiftStage1 midpoint into S2 (the cut that halves the cone).
    val s1Stage1 = Shifter.stage1(shiftCmd)
    val s2Valid  = RegNext(s1Valid && isSlow) init False
    val s2Stage1 = RegNext(s1Stage1)
    val s2Ctx    = RegNext(s1Ctx)
    val s2Src1   = RegNext(s1Src1)        // merge source preserved to S3
```

Delete the old `val s2Valid = RegNext(s1Valid && isSlow) init False` (line 227) and the old `s2Ctx`/`s2Src1` regs (228-229) since they are redefined here.

- [ ] **Step 2: Compute stage2 at S2, register results into S3**

Replace the old `s2ShiftRes`/`s2ShiftNzvc`/`s2ShiftX` regs (lines 230-232) with stage2-at-S2 then S3 registers:

```scala
    // SLOW path stage 2 (S2): bit-extract + mux on the registered midpoint. Register
    // the finished result/flags into S3 (arch latency-3).
    val s2Rsp    = Shifter.stage2(s2Stage1)
    val s3Valid  = RegNext(s2Valid) init False
    val s3Ctx    = RegNext(s2Ctx)
    val s3Src1   = RegNext(s2Src1)
    val s3ShiftRes  = RegNext(s2Rsp.result)
    val s3ShiftNzvc = RegNext(s2Rsp.n ## s2Rsp.z ## s2Rsp.v ## s2Rsp.c)
    val s3ShiftX    = RegNext(s2Rsp.xOut)
    val u3 = s3Ctx.uop
```

- [ ] **Step 3: Move size-merge + slow writeback/bypass to S3**

Rewrite the old S2 writeback block (lines ~234-251) to read the S3 regs. Replace `u2`→`u3`, `s2Src1`→`s3Src1`, `s2ShiftRes`→`s3ShiftRes`, `s2ShiftNzvc`→`s3ShiftNzvc`, `s2ShiftX`→`s3ShiftX`, and gate on `s3Valid`:

```scala
    val slowMerged = u3.size.mux(
      Size.BYTE -> (s3Src1(31 downto 8)  ## s3ShiftRes(7 downto 0)),
      Size.WORD -> (s3Src1(31 downto 16) ## s3ShiftRes(15 downto 0)),
      Size.LONG -> s3ShiftRes)
    val slowResult = slowMerged
    val slowNzvc   = s3ShiftNzvc
    val slowX      = s3ShiftX
    intWs.valid   := s3Valid && u3.pdstValid;  intWs.address  := u3.pdst;     intWs.data  := slowResult
    nzvcWs.valid  := s3Valid && u3.writesNzvc; nzvcWs.address := u3.pNzvcDst;  nzvcWs.data := slowNzvc
    xWs.valid     := s3Valid && u3.writesX;    xWs.address    := u3.pXDst;     xWs.data    := B(slowX)
    intByps.valid  := intWs.valid;  intByps.address  := intWs.address;  intByps.data  := intWs.data
    nzvcByps.valid := nzvcWs.valid; nzvcByps.address := nzvcWs.address; nzvcByps.data := nzvcWs.data
    xByps.valid    := xWs.valid;    xByps.address    := xWs.address;    xByps.data    := xWs.data
```

- [ ] **Step 4: Fire `aluSlowWakeup` from S3**

Replace the `slowWakeupPort` block (lines ~256-262), driving from S3:

```scala
    slowWakeupPort.valid            := s3Valid
    slowWakeupPort.payload.pdst     := u3.pdst
    slowWakeupPort.payload.pdstValid:= u3.pdstValid
    slowWakeupPort.payload.pNzvcDst := u3.pNzvcDst
    slowWakeupPort.payload.nzvcValid:= u3.writesNzvc
    slowWakeupPort.payload.pXDst    := u3.pXDst
    slowWakeupPort.payload.xValid   := u3.writesX
```

- [ ] **Step 5: Move completion + wbObs to S3**

Update completion (lines ~266-267): the slow op now completes at S3.

```scala
    completionPort.valid   := fastFire || s3Valid
    completionPort.payload  := Mux(s3Valid, s3Ctx.robId, s1Ctx.robId)
```

In the wbObs block (lines ~277-285), replace the slow side of every mux: `s2Valid`→`s3Valid`, `s2Ctx`→`s3Ctx`, `u2`→`u3`, `slowResult`/`slowNzvc`/`slowX` already point at S3. e.g. `val obsValidS = Mux(s3Valid, True, fastFire)`, `val obsResult = Mux(s3Valid, slowResult, mergedResult)`, etc. (Keep the single-port assumption: fast S1 and slow S3 still never coincide — guaranteed by Step 6.)

- [ ] **Step 6: Extend the single-outstanding stall to the deeper pipe**

The slow op now occupies S1→S2→S3 and completes at S3. A fast op entering S1 completes at S1's next cycle (the registered fast writeback is latency-1). To guarantee fast-S1 and slow-S3 never collide on the shared completion port AND no two slow ops overlap on the single slow write ports, hold issue while ANY slow op is in flight:

Replace line 129 `issuePort.ready := !(s1Valid && isSlow)` with:

```scala
    // Single-outstanding SLOW: hold new issue while a slow op occupies ANY of S1/S2/S3
    // (it now completes at S3). Prevents (a) two slow ops contending for the single slow
    // write/completion ports and (b) a fast op's completion coinciding with the slow op's
    // S3 completion. Slow ops are rare; the sibling ALU EU + 2-wide IQ absorb the bubble.
    issuePort.ready := !((s1Valid && isSlow) || s2Valid || s3Valid)
```

- [ ] **Step 7: Run the shift lock-step (now lat-3) + dependent-chain wakeup**

Run each subset (NEVER the whole spec — OOM):
```bash
~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "ASL"' \
              'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "LSL"' \
              'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "LSR"' \
              'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "ASR"' \
              'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "ROX"' \
              'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "ROL"' \
              'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "ROR"' \
              'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "SHIFT"'
```
Expected: all PASS, 0 diverged, 0 "Simulation failed". The `-z "SHIFT"` group is the 3 slow-ALU dependent-chain tests — they exercise the lat-3 `aluSlowWakeup` (a shift's dependent must now wait one extra cycle and still read the correct value). If any deadlocks ("Simulation failed at time=…"), the stall (Step 6) or wakeup (Step 4) is wrong.

- [ ] **Step 8: Commit**

```bash
git add src/main/scala/m68k040/execute/AluEuPlugin.scala
git commit -m "alu: deepen slow path to S1->S2->S3 (lat3); stage1@S1, stage2@S2, writeback@S3; wakeup+stall for the deeper pipe"
```

---

## Task 3: Adapt cycle-precise harnesses + IPC + test-fast

**Files:**
- Modify (if needed): `src/test/scala/m68k040/execute/AluFastSlowSpec.scala`, `src/test/scala/m68k040/execute/BackendWhiteboxSpec.scala`
- Test (run only): `src/test/scala/m68k040/bench/IpcBenchSpec.scala`

- [ ] **Step 1: Run the slow-path/EU-stub specs; widen settle windows by one cycle if a SHIFT is injected**

Run: `~/sbt/bin/sbt 'testOnly m68k040.execute.AluFastSlowSpec' 'testOnly m68k040.execute.iq.IqAluSlowSpec' 'testOnly m68k040.execute.BackendWhiteboxSpec'`
Expected: PASS. If `AluFastSlowSpec` (or any) injects a shift and asserts on a fixed cycle, the result now lands one cycle later — increase that test's `cd.waitSampling(N)` settle count by 1 (or its observation guard bound). Change ONLY timing/settle counts; the asserted *values* (result/flags) are unchanged. If a test has no shift, it needs no change.

- [ ] **Step 2: Run the IPC benchmark — confirm unchanged**

Run: `JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec'`
Expected: PASS. dependent-ALU ≈ 0.817, independent-ALU ≈ 0.791, aggregate ≈ 0.495 (within noise of the lat-2 numbers — shifts are not a benchmark kernel and the fast path is untouched). If dependent-ALU drops materially, a fast-path op was wrongly routed slow — investigate before continuing.

- [ ] **Step 3: Run test-fast (non-Verilator gate)**

Run: `~/sbt/bin/sbt fastTest`
Expected: `All tests passed.` (~91). This catches any decode/framework regression.

- [ ] **Step 4: Commit (only if harness timing changed)**

```bash
git add -A
git commit -m "test: widen slow-path harness settle windows for the lat3 shifter (values unchanged); IPC + test-fast green"
```

---

## Task 4: OOC worst-path measurement + adaptive lat-4 decision

**Files:** uses `synth/ooc_M68kFullCoreSynth.tcl`; possibly re-modifies `Shifter.scala` + `AluEuPlugin.scala` if escalating to lat-4.

- [ ] **Step 1: Generate the branch Verilog**

Run: `JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"`
Expected: `Generated generated/M68kFullCoreSynth.v`.

- [ ] **Step 2: Run OOC synth + worst-path report (vivado — NO Verilator concurrent)**

First `pgrep -af vivado` and confirm no `040`/`FullCore` job is yours-in-flight; spare any `030`.
Run: `timeout 1800 vivado -mode batch -nojournal -nolog -source synth/ooc_M68kFullCoreSynth.tcl`
Expected: prints `RESULT FullCore WNS <wns> FMAX <fmax>`. Then read `synth/M68kFullCoreSynth_timing.rpt`:
```bash
grep -nE "Slack \(VIOLATED|Source:|Destination:|Logic Levels:" synth/M68kFullCoreSynth_timing.rpt | head -20
```

- [ ] **Step 3: Adaptive decision gate (log which branch)**

- If the worst path's Source/Destination are **no longer the shifter** (not `s2Stage1`/`s3Shift*`/`…Shifter…`) → the shifter is no longer the limiter. STOP at lat-3. Record the new worst path as the next slice's target. Proceed to Task 5.
- If the worst path is **`…→ s2Stage1`** (stage 1 deep) or **`s2Stage1 →…→ s3Shift*`** (stage 2 deep) with high logic levels (≳ the other half) → split that stage again (lat-4):
  - If **stage 1** deep: split the variable shift itself — register the *result/rotate words* of the heaviest ops into a `ShiftStage1b` after a partial shift; practically, add a `stage1a`/`stage1b` cut inside the funnel (e.g. coarse-by-high-count-bits then fine), OR move the `shTable`/`rmod` reduce into its own registered sub-stage. Add `ShiftStage1b` + `stage1b`, register it between the EU's S1 and S2 (making the EU S1→S1b→S2→S3, lat-4), and extend the stall/`*Valid` chain one more stage.
  - If **stage 2** deep (less likely — it has no variable shift): split `stage2` into `stage2a` (per-op extract/compare) / `stage2b` (final mux), register between S2 and S3 → lat-4, extend the chain.
  - Update `ShifterSpec`'s staged test to the new composition (`stage2(stage1b(stage1a(cmd)))` etc.), re-run it (must stay green vs ShiftRef), re-run the shift lock-step subsets, then re-measure (back to Step 1).

- [ ] **Step 4: Commit (only if escalated to lat-4)**

```bash
git add -A
git commit -m "shifter+alu: escalate to lat4 (split the still-deep <stage1|stage2> half); staged test + shift lock-step green; OOC worst-path re-measured"
```

---

## Task 5: Post-route gate + honest report

**Files:** uses `synth/impl_FullCore.tcl`.

- [ ] **Step 1: Run the post-route implementation (vivado — NO Verilator concurrent)**

Confirm `generated/M68kFullCoreSynth.v` is the final branch netlist (re-gen if any RTL changed since Task 4). Then:
Run: `timeout 3600 vivado -mode batch -nojournal -nolog -source synth/impl_FullCore.tcl`
Expected: completes place & route; the tcl prints/writes the post-route WNS/FMax (read its RESULT line and `synth/`*_timing/route reports).

- [ ] **Step 2: Report the honest post-route number**

Record: post-route FMax before (baseline) vs after this slice, and whether the shifter is still the worst path post-route. Per the standing rule, the **post-route number is the authoritative gate** — do NOT merge below 250 silently; if it lands between the prior baseline and 250, report it honestly with the new worst path and let the user decide (the slice still wins if it lifts FMax and isolates the next limiter).

- [ ] **Step 3: Final commit (reports/notes only, if any)**

```bash
git add -A 2>/dev/null; git commit -m "synth: post-route gate for shifter deep-pipeline — <FMax> (was <baseline>); worst path now <path>" --allow-empty
```

---

## Done-When

- `ShifterSpec` staged test green vs `ShiftRef` (correctness de-risk).
- All shift lock-step subsets + the 3 slow-ALU dependent-chain tests green at lat-3 (or lat-4 if escalated).
- `IpcBenchSpec` dependent/independent-ALU unchanged; `fastTest` green.
- OOC worst-path shows the shifter cone halved (or escalated until it is), FMax up from 175.
- Post-route FMax measured and reported honestly (the authoritative gate).
- Memory updated (synth-gate-every-slice.md) with the depth chosen, the FMax delta, and the new worst path.
