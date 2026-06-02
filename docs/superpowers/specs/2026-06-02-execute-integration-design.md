# Execute Integration + First Musashi Lock-Step (Slice 3c) — Design

**Status:** Draft for review
**Date:** 2026-06-02
**Parent spec:** `.../specs/2026-05-31-m68k-040-ooo-architecture-design.md` (ch 5 execute, ch 8 commit; invariant #1 precise retire, #2 FMax, #5 CommitTrace/lock-step observability).
**Reference studied + Haiku-confirmed:** NaxRiscv `frontend/DispatchPlugin.scala` (dispatch stage pushes IQ, halts on IQ-full), `misc/CommitPlugin.scala` (ROB.ID assigned at the `allocated` stage, halts on ROB-full; **commit does NO register-file reads**; sim-only `commit_pc`), `misc/RegFilePlugin.scala` (`writeEvents` `Verilator.public`, write bus carries `robId`), `interfaces/Service.scala` (`RegFileWrite.robId = ROB.ID()`). Lock-step (RVLS) is a **whitebox** that observes writeback buses (by robId) + the commit stream — not a synthesizable trace.
**Builds on (all merged, standalone):** frontend (icache→predecode→fetch/align→decode→rename), ROB, IssueQueue, AluEuPlugin, the three PRFs, the Musashi `LockStep`/`CommitObservation`/`CommitTraceCapture` harness.
**Slice position:** the keystone — wires the whole backend together and runs the **first Musashi lock-step**. Order 3b→3a→**3c**→3d.

---

## 1. Purpose

Connect rename → dispatch → {ROB-alloc + IQ-push} → two ALU EUs → completion → ROB retire/commit, and reconstruct per-retired-instruction architectural state from **whitebox observation** (FPGA-clock-friendly: the commit path stays free of register-file reads, exactly as NaxRiscv). Run straight-line programs (MOVEQ/ALU chains) end-to-end from the I-cache through commit and compare to Musashi — the first time the whole core executes a program in lock-step.

## 2. Scope

**In:** (1) a `DispatchPlugin` (new stage) that consumes `RenameUopService.uops`, takes pre-assigned robIds from the ROB's alloc interface, and fans each µop to **both** ROB-alloc and IQ-push with combined back-pressure; refactor `RobPlugin` to a **passive alloc** interface (expose room + robId0/1 + an alloc-fire/payload-write driven by dispatch) instead of consuming rename directly. (2) Replace the ROB's single test `markComplete` with **two real completion ports** (one per ALU EU). (3) **Whitebox observation**: each `AluEuPlugin` exposes a sim-public writeback-obs at S1 (`{valid, robId, dstArch, result, intWrite, nzvc, nzvcWrite, x, xWrite}`); the ROB exposes a sim-public commit-obs at retire (`{fire, robId, pc=predNextPc}` ×2). (4) Drive `RenameCommitService` (commit→freelist) from ROB retire (close the real commit loop — was test-driven). (5) Full-core top wiring + a reworked lock-step harness that reconstructs `CommitObservation` from the whitebox streams and compares straight-line programs to Musashi.

**Out:** branches/prediction/mispredict (3d — straight-line programs only, no taken branches). Memory/LSU (no loads/stores in the corpus). The synthesizable `CommitTrace` port + `RobPlugin`'s stubbed trace fill — **superseded** by the whitebox (the port may be left dormant or removed; lock-step no longer depends on it). Exceptions. Two-EU write-port conflict beyond what the PRF already supports (distinct sharingKeys → 2 physical writes/class, synth-confirmed).

## 3. Components

### 3.1 ROB → passive alloc (refactor `RobPlugin`)
- Remove the direct `host[RenameUopService]` consume + self-alloc. Expose a `RobAllocService`:
  - `allocReady : Bool` (room for 2 = `count <= depth-2`),
  - `robId0 : UInt(robIdW)` = `tail`, `robId1 : UInt(robIdW)` = `tail+1`,
  - inputs driven by dispatch: `allocFire : Bool`, `allocUop : Vec(RenamedUop,2)`, `allocSlot1 : Bool`.
  - On `allocFire`: write `payload(tail/tail+1)` via the existing `payloadFrom`, set `valids`, clear `completes`, advance `tail` (+1/+2), update `count`. (Same logic as today's alloc, now triggered by `allocFire` from dispatch.)
- **Completion:** replace `markComplete` with `completion : Vec(Flow(UInt(robIdW)), 2)` (one per EU); each sets `completes(payload)`. (Two write contexts on the `completes` FF vector — distinct robIds in practice; OR a clear-priority on retire.)
- **Retire/commit (unchanged shape):** `retire0`/`retire1`, advance `head`, drive `RenameCommitService.commitPorts` (real now — commit→freelist), `flush`.
- **Commit-obs (sim-public):** `commitObs : Vec({fire, robId, pc}, 2)` set at retire (`fire := retireK`, `robId := h0/h1`, `pc := pK.predNextPc`). `Verilator.public` / `simPublic`.

### 3.2 `DispatchPlugin` (new)
- `host[RenameUopService]` (consume `uops`), `host[RobAllocService]`, `host[IssueQueueService]`.
- `rename.uops.ready := rob.allocReady && iq.push.ready`.
- On `rename.uops.fire`: drive `rob.allocFire := True`, `rob.allocUop := uops`, `rob.allocSlot1 := uop1Valid`; drive `iq.push.valid := True`, `iq.push.payload(k) := IqContext{uop=uops(k), robId = (k==0 ? rob.robId0 : rob.robId1)}`, `iq.pushSlot1Valid := uop1Valid`.
- (NaxRiscv splits "allocated" and "dispatch" into two pipeline stages; we collapse into one dispatch step since robId comes combinationally from the ROB tail and both writes happen the same cycle. Documented divergence.)

### 3.3 `AluEuPlugin` — completion + writeback-obs
- `completion` already a `Flow(robId)` — wire each EU's completion to one ROB `completion(k)` port (in the top).
- Add a sim-public writeback-obs at S1: `wbObs : Flow({robId, dstArch, result:32, intWrite, nzvc:4, nzvcWrite, x:1, xWrite})`, `valid := s1Valid`, fields from `s1Ctx.uop` + the `AluDatapath` result. (`dstArch = s1Ctx.uop.dstArch`; `intWrite = pdstValid`; etc.) `simPublic`. This is the per-instruction value source for lock-step.

### 3.4 IssueQueue / EUs wiring
- `iq.issue(0/1)` → the two `AluEuPlugin.issue` ports.
- The two EUs allocate int/NZVC/X write+bypass ports with **distinct sharingKeys** (they co-fire) → 2 physical write ports/class (PRF LVT/XOR, supported + synth-confirmed). Their bypass ports both feed all read ports (PRF bypass network).
- IQ `flushPort` + ROB `flush` tied (test-driven now; real mispredict in 3d).

### 3.5 Lock-step harness rework
- A `WhiteboxCapture` (sim) samples each cycle: the two EU `wbObs` (into a map `robId → {dstArch,result,intWrite,nzvc,nzvcWrite,x,xWrite}`) and the ROB `commitObs` (ordered list of `{robId, pc}`).
- After the run, replay the commit order: maintain an arch model (D0-7/A0-15 + a 5-bit CCR); for each committed robId, look up its writeback, set `arch[dstArch]=result` (if intWrite), fold CCR (`if nzvcWrite: nzvc bits := wb.nzvc; if xWrite: X := wb.x`), and emit a `CommitObservation{pc, archRegId=dstArch, archRegWrite=result, archRegValid=intWrite, ccr=foldedCcr}`. Compare the stream to Musashi `assembleAndTrace` `OracleStep`s via the existing `LockStep.compare`.

## 4. Data flow
`icache→…→rename.uops → DispatchPlugin → {ROB.alloc (payload+robId), IQ.push (uop+robId)} → IQ.issue×2 → AluEu×2 → {PRF writeback+bypass, completion→ROB.completes, wbObs} → ROB retire (commitObs + RenameCommit→freelist)`. Lock-step: whitebox(wbObs by robId + commitObs order) → reconstructed CommitObservation → `LockStep` vs Musashi.

## 5. Verification
- **Unit (Task 1):** rename source → Dispatch → ROB + IQ: a pushed µop lands in the ROB (alloc) and the IQ (push) with the **same robId**; combined back-pressure (stall when either full); 2-wide.
- **Unit (Task 2):** two EU completions mark the right ROB entries; `wbObs` carries the correct values; `commitObs` fires in retire order with correct pc/robId.
- **End-to-end lock-step (Task 3):** assemble + run straight-line programs in Musashi (`assembleAndTrace`) and in the core; reconstruct `CommitObservation` from whitebox; `LockStep.compare` passes. Programs:
  1. A chain of `MOVEQ #n,Dx` (independent) — 2-wide retire, reg writes + N/Z flags.
  2. `MOVEQ` then `ADD/SUB/AND/OR Dx,Dy` dependency chains — exercises IQ wakeup + EU bypass + flags (V/C/X).
  3. `CMP` (flags only, no reg write) interleaved — `archRegValid=false`, CCR still compared.
  4. A longer mixed straight-line program (e.g. 20-40 instrs) — sustained 2-wide flow, freelist recycling via the real commit loop.
- `make test-fast` + `make test-verilator` green.

## 6. Files
| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/dispatch/DispatchPlugin.scala` | NEW — consume rename, fan to ROB-alloc + IQ-push, combined ready |
| `src/main/scala/m68k040/services/Services.scala` (modify) | add `RobAllocService` |
| `src/main/scala/m68k040/rob/RobPlugin.scala` (modify) | passive alloc interface; 2 completion ports; commitObs sim-public; remove direct rename consume + stubbed CommitTrace fill |
| `src/main/scala/m68k040/execute/AluEuPlugin.scala` (modify) | sim-public `wbObs` at S1 |
| `src/main/scala/m68k040/top/GenVerilog.scala` (modify) | full-core plugin set (add IQ + 2 EUs + 3 PRFs + Dispatch) |
| `src/test/scala/m68k040/lockstep/WhiteboxCapture.scala` | NEW — sample wbObs + commitObs, reconstruct CommitObservation |
| `src/test/scala/m68k040/dispatch/DispatchSpec.scala`, `src/test/scala/m68k040/execute/ExecuteLockStepSpec.scala` | unit + end-to-end lock-step tests |

## 7. Open items / deferrals (logged)
- ROB.ID assigned at a separate `allocated` stage in NaxRiscv; we collapse alloc+dispatch into one step (robId combinational from ROB tail). Documented divergence; revisit if it limits Fmax.
- Synthesizable `CommitTrace` port + the ROB's stubbed trace fill are superseded by the whitebox; leave dormant or delete. (Invariant #5 observability is now satisfied by the whitebox.)
- `wbObs`/`commitObs` are sim-only (`simPublic`) — no synthesizable cost, FPGA-clean (matches NaxRiscv RVLS).
- Straight-line only (no taken branches) — the frontend fetches sequentially; the corpus avoids branches until 3d (branch EU + prediction + mispredict/squash).
- Two EUs co-fire → 2 physical write ports/PRF (distinct sharingKeys); the "different-key writers target distinct physregs/cycle" invariant holds (rename unique pdst).
- `opword` in the trace remains unavailable (not threaded through); lock-step doesn't need it.
- Program "start": redirect FetchControl to the program PC; "stop": capture N commits = the oracle step count (compare positionally).
