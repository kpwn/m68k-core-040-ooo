# ROB + Dispatch Slice 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the instruction-level ROB + dispatch that allocates renamed µops, retires 2-wide in order, and drives rename's committed-RAT update + freelist-free (closing the loop) and `CommitTrace`.

**Architecture:** `RobPlugin` consumes `RenameUopService`, allocs into a ring (head/tail), tracks completion via a test-driven `markComplete` port, and on 2-wide in-order retire drives a new `RenameCommitService` (commit + freelist-free) and `CommitTraceService`. Completion/flush are test-driven (no execute). FMax: head/tail ring, async reads, bounded 2-wide.

**Tech Stack:** Scala 2.13.16 / SpinalHDL 1.14.1 / SpinalSim + Verilator / ScalaTest 3.2.19.

**Spec:** `docs/superpowers/specs/2026-06-01-rob-dispatch-design.md`. **FMax invariant #2:** no high-fanout; ring pointers; async reads; flush fans out only to head/tail + a coarse valid clear.

**Toolchain:** `sbt` at `~/sbt/bin/sbt` (NOT on PATH). Per-spec `~/sbt/bin/sbt "testOnly <spec>"`; timeout up to 590000 ms. Verilator sims tagged `m68k040.VerilatorTest`.

**Branch:** execution on fresh `feat/rob` (controller creates before Task 1; not `master`).

**Existing (read):** `m68k040.rename.{RenameStage, RenamedUop, RatTable, Freelist}`; `m68k040.services.{RenameUopService { uops: Stream[Vec[RenamedUop]]; uop1Valid }, CommitTraceService (foundation stub: `def trace: CommitTrace` — will be superseded to 2-wide), FlushService}`; `m68k040.types.CommitTrace`; `m68k040.Global.ROB_DEPTH=64`. RenameStage currently has top-level test ports `commit: Vec(2, slave Flow{intArch:UInt4, intNew:UInt6, intWrite:Bool})` and `flush: in Bool()`, and `intFree/nzvcFree/xFree.io.push(k).setIdle()`. Service convention: plain wires; plugin `extends FiberPlugin with TheService`; standalone tests host a source/sink plugin.

---

## File structure
| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/rob/CommitSlot.scala` | `CommitSlot` bundle (per-retired-instr commit/free info) |
| `src/main/scala/m68k040/rob/RobPlugin.scala` | dispatch/alloc + ring + completion + 2-wide retire + drive rename/CommitTrace |
| `src/main/scala/m68k040/services/Services.scala` (modify) | add `RenameCommitService`; supersede `CommitTraceService` to 2-wide |
| `src/main/scala/m68k040/rename/RenameStage.scala` (modify) | implement `RenameCommitService`: extended commit + freelist-free |
| `src/test/scala/m68k040/rename/RenameCommitSourcePlugin.scala` | test helper to drive RenameCommitService standalone |
| `src/test/scala/m68k040/rob/RobUopSourcePlugin.scala`, `CommitTraceSinkPlugin.scala` | ROB test helpers |
| `src/test/scala/m68k040/rob/RobPluginSpec.scala` | the §5 tests |

---

## Task 1: `RenameCommitService` + close the freelist-free loop in rename

Isolated, green-gated change to merged rename code: extend its commit interface to carry old pdsts, wire the freelist push, expose it as a service.

**Files:** Create `src/main/scala/m68k040/rob/CommitSlot.scala`; Modify `src/main/scala/m68k040/services/Services.scala`, `src/main/scala/m68k040/rename/RenameStage.scala`; Create `src/test/scala/m68k040/rename/RenameCommitSourcePlugin.scala`; Modify `src/test/scala/m68k040/rename/RenameStageSpec.scala`

- [ ] **Step 1: Write `CommitSlot.scala`**
```scala
package m68k040.rob

import spinal.core._

/** One retired instruction's commit + free info (rename writes committed RAT, frees old pdsts). */
case class CommitSlot() extends Bundle {
  val intArch  = UInt(4 bits); val intNew  = UInt(6 bits); val intOld  = UInt(6 bits); val intWrite  = Bool()
  val nzvcNew  = UInt(4 bits); val nzvcOld = UInt(4 bits); val nzvcWrite = Bool()
  val xNew     = UInt(4 bits); val xOld    = UInt(4 bits); val xWrite    = Bool()
}
```

- [ ] **Step 2: Add `RenameCommitService` + supersede `CommitTraceService` (2-wide) in `Services.scala`** (READ first; add imports; the old `CommitTraceService { def trace: CommitTrace }` has no RTL consumer — replace it):
```scala
import m68k040.rob.CommitSlot
import m68k040.types.CommitTrace

/** Rename exposes; ROB drives. commit = 2-wide retire commit+free; flush = rollback. */
trait RenameCommitService {
  def commitPorts: Vec[Flow[CommitSlot]]   // length 2
  def flushPort:   Bool
}

/** ROB exposes; lock-step harness / sinks consume. Up to 2 retired instr/cycle. */
trait CommitTraceService {
  def trace:     Vec[CommitTrace]   // length 2
  def traceFire: Vec[Bool]          // length 2
}
```
(Remove the old single-trace `CommitTraceService` body. If `FlushService` references nothing affected, leave it.)

- [ ] **Step 3: Modify `RenameStage`** to implement `RenameCommitService` and close the free loop:
  - Change the commit ports to carry `CommitSlot`: `val commitPorts = Vec.fill(2)(slave(Flow(CommitSlot())))` (replaces the minimal `commit` bundle). `val flushPort = in Bool()` (rename keeps a `flush` input; alias). Keep them as top-level-drivable ports (the standalone test drives them; the ROB drives them when hosted).
  - Drive the RAT commit from `commitPorts` (as today, now using `intArch/intNew`, plus `nzvcNew/xNew` to nzvcRat/xRat commit ports addr 0).
  - **Wire freelist push (closes the loop):** replace the three `push(k).setIdle()` with: for k in 0..1, `intFree.io.push(k).valid := commitPorts(k).valid && commitPorts(k).intWrite; intFree.io.push(k).payload := commitPorts(k).intOld`; same for `nzvcFree`←`nzvcOld`/`nzvcWrite`, `xFree`←`xOld`/`xWrite`.
  - `override def commitPorts = logic.commitPorts; override def flushPort = logic.flushPort`. `extends FiberPlugin with RenameUopService with RenameCommitService`.
  - Mind the init/commit mux on `intRat.io.commits(0)` (init counter vs commit port) — keep it; the commit port now provides intArch/intNew.

- [ ] **Step 4: Update `RenameStageSpec`** — the 4 existing tests drive `commit`/`flush`. Update them to the new `CommitSlot` shape: where they drove `commit(k).intArch/intNew/intWrite`, now also set `intOld` (and the nzvc/x fields to 0/false unless used). The flush test still drives `flushPort`. (The standalone test can keep driving these ports directly — they remain top-level-drivable.) Keep all assertions; the commit→committed-RAT behavior is unchanged, plus now the freed id returns to the freelist (which only helps).
  Also create `src/test/scala/m68k040/rename/RenameCommitSourcePlugin.scala` if a hosted driver is cleaner — but if the rename test drives the ports directly (top-level), the source plugin may be unnecessary; only add it if elaboration requires an internal driver. (Prefer: keep `commitPorts`/`flushPort` as top-level test-drivable IO in the standalone Dut.)

- [ ] **Step 5: Run** `~/sbt/bin/sbt "testOnly m68k040.rename.RenameStageSpec"` — 4 tests pass. Then `make SBT=~/sbt/bin/sbt test-fast` and `make SBT=~/sbt/bin/sbt test-verilator` — ALL green (the service/commit-bundle change must not break anything). Add a focused test: dispatch a commit with `intWrite`+`intOld=P`, then over many cycles confirm the freelist returns `P` (the loop). (Or defer that proof to Task 2's sustained test.)
- [ ] **Step 6: Commit** — `git add -A && git commit -m "rob: RenameCommitService + close rename freelist-free loop"`

---

## Task 2: `RobPlugin` (ring + dispatch + 2-wide retire) + tests

**Files:** Create `src/main/scala/m68k040/rob/RobPlugin.scala`; Create `src/test/scala/m68k040/rob/{RobUopSourcePlugin,CommitTraceSinkPlugin}.scala`; Test `src/test/scala/m68k040/rob/RobPluginSpec.scala`

**Structural spec** — `class RobPlugin extends FiberPlugin with CommitTraceService`. In `during build`:
- Resolve `val ru = host[RenameUopService]`, `val rc = host[RenameCommitService]`.
- Ring: `depth = ROB_DEPTH` (=64), `robIdW = log2Up(depth)`. Storage: payload `Mem(RobPayload, depth)` (RobPayload = `{predNextPc:UInt32, intArch:UInt4, intNew:UInt6, intOld:UInt6, intWrite, nzvcNew/Old/Write, xNew/Old/Write, retireAlone, archRegId:UInt4}`); `valid = Vec(depth)(RegInit(False))`, `complete = Vec(depth)(RegInit(False))`. Regs `head`, `tail`, `count`.
- **Dispatch/alloc:** `ru.uops.ready := count + 2 <= depth`. On `ru.uops.fire`: write entry at `tail` from `ru.uops.payload(0)` (predNextPc = pc+2; map RenamedUop physical fields into RobPayload; `valid(tail):=True; complete(tail):=False`); if `ru.uop1Valid`, write `tail+1` from payload(1). `tail += (#allocated)`; `count += #allocated`.
- **Completion:** `val markComplete = slave(Flow(UInt(robIdW bits)))` (test port) → `when(markComplete.valid){ complete(markComplete.payload) := True }`.
- **Commit/retire:** `h0=head, h1=head+1`. `retire0 = valid(h0) && complete(h0)`. `retire1 = retire0 && valid(h1) && complete(h1) && !payload(h0).retireAlone && !payload(h1).retireAlone`. For each retired entry e (read its payload via `Mem.readAsync`): drive `rc.commitPorts(k)` (valid := retired_k; CommitSlot fields from the payload: intArch/intNew/intOld/intWrite, nzvc*, x*); drive `traceReg(k)` (CommitTrace: pc=predNextPc, archRegId=payload.archRegId, archRegValid=intWrite, archRegWrite=0, ccr=0/ccrValid=false, memWrite=false, excTaken=false), `traceFire(k) := retired_k`. `valid(h0/h1) := False` for retired; `head += #retired`; `count -= #retired`.
- **Flush:** `val flush = slave(Flow(NoData()))` (test) → `tail := head; count := 0; valid.foreach(_ := False)`; drive `rc.flushPort := flush.valid`.
- `override def trace = logic.traceVec; override def traceFire = logic.traceFireVec`. (Register the trace outputs.)
- Drive `rc.commitPorts(k).valid := False` by default; only assert on retire. Drive `rc.flushPort := flush.valid`.

**Test helpers:**
- `RobUopSourcePlugin` (test): `extends FiberPlugin with RenameUopService`; exposes `master(Stream(Vec(RenamedUop,2)))` + `uop1` as top-level IO the test drives; `override def uops`/`uop1Valid` return them. (The ROB consumes RenameUopService.) Wait — RenameUopService is PRODUCED by rename. For the ROB test we either host the real `RenameStage`+source, OR a fake source plugin that PRODUCES RenameUopService. Use a fake source: `RobUopSourcePlugin extends FiberPlugin with RenameUopService` exposing the uop stream from top-level IO (the test drives valid/payload, ROB drives ready). AND the ROB also needs a `RenameCommitService` to drive — host the REAL `RenameStage` so commit actually frees, OR a `RenameCommitSinkPlugin` that just absorbs commit/flush. For the **sustained-free-loop test** (the key one) we need the REAL rename in the loop. So the end-to-end Dut hosts: `RenameStage` (real) + a decode-uop source feeding it + `RobPlugin` + a CommitTrace sink. That exercises decode→? No decode needed — feed RenamedUops? But RenameStage PRODUCES RenameUopService from DecodeUopService. So to get real rename in the loop, feed it DecodedUops (via a DecodeUopSource) → rename → RobPlugin → rename.commit. That's the full backend path. Use that for the sustained test. For the simpler alloc/retire tests, a fake RenameUopService source + a RenameCommitService sink suffices.
- `CommitTraceSinkPlugin` (test): `extends FiberPlugin`; `val du = host[CommitTraceService]`; expose `trace`/`traceFire` as top-level IO for the test to read.

- [ ] **Step 1: Write `RobPluginSpec.scala`** with the §5 tests. For the **sustained-free-loop test** host the real backend (`DecodeUopSourcePlugin` → `RenameStage` → `RobPlugin` + `CommitTraceSinkPlugin`): drive a long stream of dst-writing DecodedUops, mark each robId complete a few cycles after dispatch, and assert the pipeline keeps flowing past 48+ allocations (freelist never dry → rename stays ready) and CommitTrace fires in order. For alloc/retire/in-order/flush/retireAlone, a fake `RenameUopService` source + a `RenameCommitSinkPlugin` is simpler. Write the test bodies to assert the §5 behaviors EXACTLY (controller-reviewed for rigor). Tag VerilatorTest.
- [ ] **Step 2: Run, expect FAIL** (`RobPlugin` undefined). `~/sbt/bin/sbt "testOnly m68k040.rob.RobPluginSpec"`.
- [ ] **Step 3: Implement `RobPlugin.scala` + the test helpers** per the structural spec. Ring pointer arithmetic (2-alloc/2-retire, wraparound); async payload read at head; drive rename commit/free + CommitTrace on retire; flush squash.
- [ ] **Step 4: Run, expect PASS** (the §5 tests). Iterate the ring/retire/handshake logic to green; do NOT weaken assertions. The sustained-free-loop test is the proof the rename follow-up is closed.
- [ ] **Step 5: Confirm gates** — `make SBT=~/sbt/bin/sbt test-fast` + `make SBT=~/sbt/bin/sbt test-verilator` all green.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "rob: RobPlugin (ring + 2-wide retire, drives rename commit/free + CommitTrace)"`

---

## Self-review notes (author-completed)
- **Spec coverage:** §3.3 RenameCommitService + rename free-wiring → T1; §3.1 ring + §3.2 RobPlugin dispatch/completion/retire/flush + §3.4 CommitTraceService → T2; §5 verification → T1 (rename green) + T2 (alloc/retire/in-order/**sustained-free-loop**/committed-RAT/flush/retireAlone). Deferred (§8): execute, CCR/SQ/exception commit, cluster steering, precise squash.
- **Type consistency:** `CommitSlot` fields used in RenameCommitService + rename free-wiring + RobPlugin retire; `RenameCommitService { commitPorts: Vec[Flow[CommitSlot]]; flushPort: Bool }`; `CommitTraceService { trace: Vec[CommitTrace]; traceFire: Vec[Bool] }`; RobPayload fields; robIdW=log2Up(64)=6.
- **Risk isolation:** T1 isolates the merged-rename change (commit bundle + freelist push + service) and green-gates the whole suite before the ROB is built. T2's sustained-free-loop test is the end-to-end proof.
- **Placeholder note:** T2's test bodies are specified by the §5 behaviors (assert exactly); the implementer fills them with the source/sink helpers, controller-reviewed for non-weakening (same calibration as rename R4). RobPlugin body is a structured spec gated by those tests.

## Downstream (not this plan)
Issue clusters + execute (real completion via robId writeback + computed values/CCR → fills CommitTrace for full Musashi lock-step); precise per-robId squash + real branch/mispredict; CCR-write/SQ-drain/exception entry at commit; cluster steering at dispatch; thread real instruction length into predNextPc.
