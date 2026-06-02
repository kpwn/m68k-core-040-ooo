# Branch Unit + Commit-Time Mispredict Recovery (3d-1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Branch resolution (latency-1 branch EU) + FMax-safe commit-time mispredict recovery (registered flush pulse, pointer-based ROB squash), with branches lock-stepping vs Musashi and the full core staying ≥250 MHz.

**Architecture:** (1) Refactor ROB validity to pointer/count-derived (drop `valids`; flush = `tail:=head;count:=0`, zero per-entry fanout). (2) Add a branch EU (reads NZVC, resolves taken/target/nextPc/mispredict) + a 3rd IQ issue port that age-selects branch-class slots (class = `context.uop.isBranch`). (3) Commit-time recovery: ROB stores `{mispredict,nextPc}` per branch from a branch-completion port; at retire of a mispredicting branch, a **registered** `RedirectService` pulse drives rename-rollback + freelist-reset + IQ-clear + the two FE `PipeStage.flush` + ROB pointer-reset + fetch redirect. (4) Branch `commitObs.pc` = resolved nextPc. Then full-core wiring + branch lock-step + re-synth.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt `~/sbt/bin/sbt` (NOT on PATH) / Verilator / Vivado (xcku5p-ffvb676-2). Reuses `AluEuPlugin` (branch EU shape), `BackendWiringPlugin`/`FullCoreSynth`, `ExecuteLockStepSpec`/`WhiteboxCapture`, `Musashi.assembleAndTrace`.

**Branch:** `feat/branch-unit` (created; spec committed).

**Facts:** ROB: `valids`/`completes` Vec(64), `count`/`head`/`tail`, `completion=Vec.fill(2)(Flow(UInt6))`, `retireAlone:=u.isBranch`, `commitObs.pc:=p.predNextPc`. IQ: slots have `sel`(occupied)/`ready`/`context:IqContext`; `context.uop.isBranch` tags branches; `issuePorts=Vec(Stream,2)` (ALU); select = `OHMasking.first` over `ready`. RenamedUop: `isBranch`, `cond:Bits(4)`, `branchDisp:Bits(32)`, `pc`, `pNzvcSrc`/`readsNzvc`. AluEu: 2-stage (S0 read|S1), `completion:Flow[UInt]`, sim `WbObs`. NZVC PRF: `NzvcRegFileService.newRead`.

---

### Task 1: ROB pointer-based validity (the fanout fix)

**Files:** Modify `src/main/scala/m68k040/rob/RobPlugin.scala`; Modify `src/test/scala/m68k040/rob/RobPluginSpec.scala`.

- [ ] **Step 1: Replace per-entry `valids` with count-derived head-validity**

In `RobPlugin.logic`:
- DELETE `val valids = Vec.fill(depth)(RegInit(False))`.
- Change retire guards:
  ```scala
  val retire0 = (count > 0) && completes(h0) && !flush.valid
  val retire1 = retire0 && (count > 1) && completes(h1) && !p0.retireAlone && !p1.retireAlone
  ```
- In alloc: DELETE `valids(tail):=True` and `valids(tail+1):=True` (validity is now count-derived). KEEP the `completes(tail):=False` / `completes(tail+1):=False`.
- In retire: DELETE `when(retire0){ valids(h0):=False }` and the retire1 one (head/count advance already handles validity).
- In flush: change to pointer-only — `when(flush.valid){ tail := head; count := 0 }` (DELETE `valids.foreach(_:=False)`). This removes the 64-wide fanout net.

- [ ] **Step 2: Alloc-priority over stale completions on `completes`**

A wrong-path µop completing after a flush sets `completes(staleRobId):=True`; if that index is re-allocated, alloc's `completes:=False` must WIN. In SpinalHDL the LATER `when` wins for the same signal, so ORDER the completion-set BEFORE the alloc-reset. Arrange the code so:
```scala
    // completion marks (must come BEFORE the alloc-reset so alloc wins on a reused index)
    completion.foreach { c => when(c.valid) { completes(c.payload) := True } }
    // ... (branch completion in Task 3 also sets completes here) ...
    when(alloc0) { payload.write(tail, payloadFrom(allocUopVec(0))); completes(tail) := False }
    when(alloc1) { payload.write(tail + 1, payloadFrom(allocUopVec(1))); completes(tail + 1) := False }
```
(Move the existing `completion.foreach{...completes:=True}` above the alloc block if it isn't already.)

- [ ] **Step 3: Run RobPluginSpec + add a stale-completion test**

Run: `~/sbt/bin/sbt "testOnly m68k040.rob.RobPluginSpec"` → green (ring alloc/retire/in-order/retireAlone/flush/free-loop preserved with pointer-validity). Add a test: alloc 2, flush (pointer reset), then fire a `completion` to one of the flushed robIds, then alloc a new µop at that index and DON'T complete it — assert it does NOT retire (the stale `completes` was reset by alloc). Keep strict.

- [ ] **Step 4: Commit**
```bash
git add src/main/scala/m68k040/rob/RobPlugin.scala src/test/scala/m68k040/rob/RobPluginSpec.scala
git commit -m "rob: pointer-based validity (count-derived head, flush=pointer reset, zero squash fanout)"
```

---

### Task 2: Branch EU + IQ branch issue port

**Files:** Create `src/main/scala/m68k040/execute/BranchEuPlugin.scala`; Modify `src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala`; Create `src/test/scala/m68k040/execute/BranchEuSpec.scala`.

- [ ] **Step 1: IQ — add a 3rd (branch) issue port + class-filtered select**

In `IssueQueuePlugin`: change `issuePorts = Vec.fill(wayCount)(Stream(IqContext()))` to `Vec.fill(3)(Stream(IqContext()))` (ports 0,1 = ALU; port 2 = branch). In the select logic, class-filter by `context.uop.isBranch`:
```scala
    val aluReady = B(slots.map(s => s.ready && !s.context.uop.isBranch))
    val brReady  = B(slots.map(s => s.ready &&  s.context.uop.isBranch))
    val oh0 = OHMasking.first(aluReady)
    val oh1 = OHMasking.first(aluReady & ~oh0)
    val ohB = OHMasking.first(brReady)
    issuePorts(0).valid := oh0.orR; issuePorts(0).payload := MuxOH(oh0, contexts)
    issuePorts(1).valid := oh1.orR; issuePorts(1).payload := MuxOH(oh1, contexts)
    issuePorts(2).valid := ohB.orR; issuePorts(2).payload := MuxOH(ohB, contexts)
```
and each slot `fire` if selected by ANY port that's ready: `slot.fire := ((oh0 | oh1 | ohB)(idx)) && <that port>.ready` — adapt the existing per-slot fire to OR in `ohB & issuePorts(2).ready`. (Keep the existing ALU fire logic; add the branch term.) The `IssueQueueService.issue` Vec is now length 3.

- [ ] **Step 2: Branch EU**

Create `src/main/scala/m68k040/execute/BranchEuPlugin.scala` — mirror `AluEuPlugin`'s 2-stage shape (S0 read | M2S | S1 resolve), but read NZVC and resolve a branch:

```scala
package m68k040.execute

import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{NzvcRegFileService, RegFileReadPort}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Branch completion: robId + whether it mispredicted + the resolved next PC. */
case class BranchCompletion() extends Bundle {
  val robId = UInt(6 bits); val mispredict = Bool(); val nextPc = UInt(32 bits)
}
case class BrWbObs() extends Bundle {  // sim-only whitebox (branch writes no reg; CCR unchanged)
  val valid = Bool(); val robId = UInt(6 bits); val nextPc = UInt(32 bits)
}
trait BranchEuService { def issue: Stream[IqContext]; def completion: Flow[BranchCompletion] }

/** Latency-1 branch EU. S0 reads NZVC; S1 evaluates the 68k condition, computes
  * target = pc+2+disp, taken, nextPc, mispredict (=taken; no predictor yet). */
class BranchEuPlugin extends FiberPlugin with BranchEuService {
  var issuePort: Stream[IqContext] = null
  var completionPort: Flow[BranchCompletion] = null
  var nzRd: RegFileReadPort = null
  override def issue = issuePort
  override def completion = completionPort

  during setup {
    issuePort = Stream(IqContext())
    completionPort = Flow(BranchCompletion())
    nzRd = host[NzvcRegFileService].newRead(forceNoBypass = false)
  }

  val logic = during build new Area {
    // S0: read NZVC source
    issuePort.ready := True
    nzRd.addr := issuePort.payload.uop.pNzvcSrc
    val s1Valid = RegNext(issuePort.valid) init False
    val s1Ctx   = RegNext(issuePort.payload)
    val s1Nzvc  = RegNext(nzRd.data)              // {N(3),Z(2),V(1),C(0)}
    val u1 = s1Ctx.uop

    // S1: condition eval (cond[3:0]), target, nextPc, mispredict
    val n = s1Nzvc(3); val z = s1Nzvc(2); val v = s1Nzvc(1); val c = s1Nzvc(0)
    val taken = u1.cond.mux(
      0  -> True,            // T
      1  -> False,           // F
      2  -> (!c && !z),      // HI
      3  -> (c || z),        // LS
      4  -> !c,              // CC/HS
      5  -> c,               // CS/LO
      6  -> !z,              // NE
      7  -> z,               // EQ
      8  -> !v,              // VC
      9  -> v,               // VS
      10 -> !n,              // PL
      11 -> n,               // MI
      12 -> (n === v),       // GE
      13 -> (n =/= v),       // LT
      14 -> (!z && (n === v)),// GT
      15 -> (z || (n =/= v)) // LE
    )
    val target = (u1.pc + 2 + u1.branchDisp.asUInt)
    val nextPc = Mux(taken, target, u1.pc + 2)

    completionPort.valid          := s1Valid
    completionPort.payload.robId  := s1Ctx.robId
    completionPort.payload.mispredict := s1Valid && taken   // no predictor: taken => mispredict
    completionPort.payload.nextPc := nextPc

    // sim-only whitebox (branch: no reg write, CCR unchanged)
    val wbObs = BrWbObs()
    wbObs.valid := RegNext(s1Valid) init False
    wbObs.robId := RegNext(s1Ctx.robId)
    wbObs.nextPc := RegNext(nextPc)
    wbObs.simPublic()
  }
}
```
Verify the `cond.mux(0 -> ..., ...)` form compiles (cond is `Bits(4)`; use `u1.cond.asUInt.mux(...)` if needed). The condition semantics are the arbiter target — **Musashi `Bcc` is ground truth** (Task 4 lock-step validates; the unit test below cross-checks).

- [ ] **Step 3: Branch EU unit test**

Create `src/test/scala/m68k040/execute/BranchEuSpec.scala`: host NzvcRegFilePlugin + BranchEuPlugin + a source driving the issue Stream (op/cond/pc/branchDisp/pNzvcSrc) and a probe to write the NZVC PRF (preload the flag value) + observe `completion`. For each of the 16 conditions × representative NZVC values, issue a branch and assert `taken`/`nextPc`/`mispredict` match the expected (compute by hand from the condition table, OR cross-check the taken-ness against Musashi's `Bcc` over a known CCR). Assert target = pc+2+disp. Keep strict.

- [ ] **Step 4: Run + commit**
Run: `~/sbt/bin/sbt "testOnly m68k040.execute.BranchEuSpec m68k040.execute.iq.IssueQueueSpec"` → green (the IQ's existing 2-port tests must still pass with the 3rd port added; if `issue` length changed broke a test, fix it to the new Vec(3)).
```bash
git add src/main/scala/m68k040/execute/BranchEuPlugin.scala src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala src/test/scala/m68k040/execute/BranchEuSpec.scala
git commit -m "execute: branch EU (cond eval + target/nextPc/mispredict) + IQ branch issue port"
```

---

### Task 3: Commit-time recovery (RedirectService) + branch trace

**Files:** Modify `src/main/scala/m68k040/services/Services.scala`, `src/main/scala/m68k040/rob/RobPlugin.scala`.

- [ ] **Step 1: `RedirectService`**
Add to `Services.scala`:
```scala
/** Owned by the ROB (commit-time mispredict). Consumed by rename/IQ/frontend.
  * doFlush is a REGISTERED pulse (FMax: drives only pointer/bitmap resets). */
trait RedirectService { def doFlush: Bool; def flushPc: UInt }
```

- [ ] **Step 2: ROB — branch completion store + commit-time registered redirect**
In `RobPlugin` (`extends ... with RedirectService`):
- Add a branch completion input + per-robId stores:
  ```scala
  val branchCompletion = Flow(m68k040.execute.BranchCompletion())   // driven by the branch EU
  val mispredictStore = Vec.fill(depth)(Reg(Bool()))
  val nextPcStore     = Vec.fill(depth)(Reg(UInt(32 bits)))
  when(branchCompletion.valid) {
    completes(branchCompletion.payload.robId) := True          // also marks complete (order per Task 1: before alloc-reset)
    mispredictStore(branchCompletion.payload.robId) := branchCompletion.payload.mispredict
    nextPcStore(branchCompletion.payload.robId)     := branchCompletion.payload.nextPc
  }
  ```
  (Place the `completes:=True` set with the other completion sets, BEFORE the alloc-reset, per Task 1 Step 2.)
- Commit-time registered redirect: branches are `retireAlone` → retire 1-wide (retire0). When the retiring head is a mispredicting branch, register the flush for next cycle:
  ```scala
  val doFlushReg = RegInit(False)
  val flushPcReg = Reg(UInt(32 bits))
  doFlushReg := retire0 && p0.retireAlone && mispredictStore(h0)
  when(retire0 && p0.retireAlone && mispredictStore(h0)) { flushPcReg := nextPcStore(h0) }
  ```
- Self-squash on the registered pulse (pointer-only) + keep the existing test `flush` port too (OR-in):
  ```scala
  val flushing = flush.valid || doFlushReg
  // in the retire guards use `!flushing` instead of `!flush.valid`
  when(flushing) { tail := head; count := 0 }
  ```
- `rc.flushPort := flushing` (rename rollback). (rename freelist reset is inside rename's flush.)
- Branch trace nextPc: in `driveCommit`, set `traceVec(k).pc`/the commitObs pc from the resolved nextPc for branches:
  ```scala
  commitObs(0).pc := Mux(p0.retireAlone, nextPcStore(h0), p0.predNextPc)
  commitObs(1).pc := Mux(p1.retireAlone, nextPcStore(h1), p1.predNextPc)
  ```
  (retire1 of a branch can't happen — branches are retireAlone → retire 1-wide — so commitObs(1) is never a branch; the Mux is harmless.)
- Expose: `override def doFlush = logic.doFlushReg; override def flushPc = logic.flushPcReg`.

- [ ] **Step 3: Run ROB + dispatch specs**
Run: `~/sbt/bin/sbt "testOnly m68k040.rob.RobPluginSpec m68k040.dispatch.DispatchSpec"` → green. (The `branchCompletion` input is a plain Flow driven by the branch EU / tied off in tests that don't use it — drive it idle in those Duts.) Commit:
```bash
git add src/main/scala/m68k040/services/Services.scala src/main/scala/m68k040/rob/RobPlugin.scala src/test/scala/m68k040/rob/RobPluginSpec.scala
git commit -m "rob: branch completion store + commit-time registered redirect (RedirectService) + branch trace nextPc"
```

---

### Task 4: Full-core wiring + branch lock-step + re-synth

**Files:** Modify `src/main/scala/m68k040/top/FullCoreSynth.scala`, the lock-step Dut/`ExecuteLockStepSpec`, `WhiteboxCapture`.

- [ ] **Step 1: Wire the branch EU + RedirectService (full core)**
In the full-core wiring (the `BackendWiringPlugin` in `FullCoreSynth.scala` and the lock-step test Dut): construct a `BranchEuPlugin`; `branchEu.issue << iq.issue(2)`; `rob.branchCompletion << branchEu.completion`. Wire the RedirectService: `rename.flushPort` / `iq.flushPort` / `decode.pipeFlush` / `rename.pipeFlush` := `host[RedirectService].doFlush`; `fetchAlign.redirect.valid := doFlush`, `.payload := flushPc`. (The `PipeStage.flush` inputs were tied False in the FE-pipeline slice — now drive them from `doFlush`.) Resolve the exact handles per the existing wiring pattern.

- [ ] **Step 2: WhiteboxCapture — accept branch wbObs (no reg write)**
The branch EU's `wbObs` has no reg/flag write. In the lock-step sampler, feed branch wbObs as a `Wb` with `intWrite=false, nzvcWrite=false, xWrite=false` (and the commit pc comes from `commitObs.pc` = resolved nextPc, already handled in the ROB). So a branch's reconstructed `CommitObservation` = `{pc=nextPc, archRegValid=false, ccr=unchanged}`. Update the sampler to read both ALU `wbObs` AND the branch EU `wbObs` (mapping branch ones to a no-write `Wb`). No change to `WhiteboxCapture.Handle` logic (it already handles intWrite=false / nzvcWrite=false).

- [ ] **Step 3: Branch lock-step corpus**
Add to `ExecuteLockStepSpec` (2-byte short branches only — `predNextPc=pc+2` governs non-branch instrs; the branch's own pc is the resolved nextPc):
  1. `moveq #1,%d0 ; cmp.l %d0,%d0 ; beq.s .L ; moveq #9,%d1 ; .L: moveq #7,%d2` — taken Beq skips the `moveq #9` (D1 stays 0); verify the trace skips it + lands at the target.
  2. not-taken: `moveq #1,%d0 ; cmp.l %d0,%d0 ; bne.s .L ; moveq #9,%d1 ; .L: moveq #7,%d2` — Bne not-taken, falls through (D1=9).
  3. `bra.s .L ; moveq #9,%d0 ; .L: moveq #7,%d1` — unconditional, skips moveq #9.
  4. a small countdown loop with a backward `bne.s` taken a few times then fall-through (subbr/dbne-style via moveq/subq+bcc — keep all ops 2-byte; if subq isn't decoded, use sub.l of a reg holding 1).
  Assemble each with a sentinel/`move.l %dx,0xFFFF0000`-ish stop or bound by instruction count; `Musashi.assembleAndTrace` gives the oracle; reconstruct via whitebox; `LockStep.compare` ok. Each a `VerilatorTest`; run the suite TWICE (determinism). The taken-branch trace pc MUST equal Musashi's (target), proving the resolved-nextPc + commit-time redirect.

- [ ] **Step 4: Run + re-synth (FMax gate)**
Run: `~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"` → all (old 5 + new branch programs) green, deterministic (2 runs). Then `make SBT=~/sbt/bin/sbt test-fast` + `test-verilator` → all pass.
**Re-synth (must hold WNS):** `~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"` then `vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/ooc_M68kFullCoreSynth.tcl 2>&1 | grep -iE "RESULT|Unsupported|multi-driven net on pin|CRITICAL WARNING|Synth Design complete"`. Confirm **WNS ≥ 0** (≥250 MHz; the 309 MHz baseline ideally held). Inspect `synth/M68kFullCoreSynth_timing.rpt`: the critical path must NOT be the redirect/flush broadcast (proving the registered-pulse + pointer-squash worked). Report new WNS + critical path. If the flush IS on the critical path, the `doFlush` pulse isn't registered cleanly or a wide net snuck in — fix (do not accept a regression).

- [ ] **Step 5: Commit**
```bash
git add -A && git commit -m "execute: wire branch EU + commit-time redirect into full core; branch lock-step; re-synth >=250MHz"
```

---

## Self-Review

**1. Spec coverage:** §3.1 ROB pointer-validity (T1). §3.2 branch EU (T2 S2). §3.3 IQ branch port + class select (T2 S1). §3.4 RedirectService + commit-time registered redirect (T3). §3.5 branch trace nextPc (T3 S2). §4 verification: ROB+stale-completion (T1 S3), branch EU conditions (T2 S3), branch lock-step + determinism (T4 S3-4), synth FMax gate (T4 S4). ✓

**2. Placeholder scan:** Branch EU + cond table + ROB diff + RedirectService given concretely. Wiring (T4 S1) says "resolve handles per existing pattern" — bounded (the BackendWiringPlugin pattern + the named flush/redirect ports are shown). The cond `.mux` form has a compile-check note. No vague TBD.

**3. Type consistency:** `BranchCompletion{robId,mispredict,nextPc}` driven by branch EU `completion`, consumed by ROB `branchCompletion`. `RedirectService{doFlush,flushPc}` ROB-exposed, consumed by rename/IQ/FE/fetch. IQ `issue: Vec(Stream,3)`; branch EU consumes `issue(2)`. `commitObs.pc` Mux on `retireAlone`. NZVC `{N3,Z2,V1,C0}` consistent with the cond eval + AluDatapath. `pipeFlush` (the FE skid flush) wired from `doFlush`.

**Note on FMax (the slice's reason for being):** `doFlush` is a REGISTERED Bool (`doFlushReg`), fanning only to pointer/bitmap/valid-reg resets (ROB tail/count, rename location, freelist pointer, IQ sel-clear, 2 FE skid valids) + the registered redirect PC. No combinational execute→flush path; no per-entry valid-clear (ROB squash is pointer-only after Task 1). The T4 S4 synth gate is the proof.
