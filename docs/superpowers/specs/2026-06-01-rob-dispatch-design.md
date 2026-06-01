# ROB + Dispatch Slice 1 — Design

**Status:** Draft for review
**Date:** 2026-06-01
**Parent spec:** `docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md` (ch 4.5 ROB entry, ch 8 Commit & exceptions; invariant #1 one precise retire boundary, #2 FMax, #3 plugin boundaries, #5 CommitTrace observability)
**Builds on:** merged rename (`RenameUopService` — 2-wide `RenamedUop` stream; rename has test-driven `commit`/`flush` ports + idle freelist push).
**Slice position:** the OoO backend's commit keystone. Establishes the precise instruction-level retire boundary, closes rename's freelist-free loop, and fires `CommitTrace` (the lock-step harness's designed consumer).

---

## 1. Purpose

Allocate instruction-level ROB entries from renamed µops, track completion, and retire **2-wide in order**, driving: rename's **committed-RAT update + freelist-free** (closing the rename follow-up — without it the freelist drains and the core stalls forever), and the **`CommitTrace`** port. Completion and mispredict are **test-driven ports** this slice (no execute units yet); the computed value/CCR carried in `CommitTrace` are stubbed (0) until execute exists. FMax-disciplined (invariant #2): a ring with head/tail pointers, async-read entry RAM, retire/commit logic bounded to 2-wide — no high-fanout broadcast.

## 2. Scope

**In:** `RobPlugin` (one plugin: dispatch/alloc + ring + completion + 2-wide retire); a `RenameCommitService` rename exposes and the ROB drives (committed-RAT update + freelist-free); `CommitTrace` via `CommitTraceService` fired on retire; test-driven `markComplete` + `flush`. Modify `RenameStage` to (a) extend its commit interface to carry old pdsts, (b) wire `intFree/nzvcFree/xFree.push` from commit (closing the freelist loop), (c) expose commit/flush as `RenameCommitService`.

**Out (later):** issue clusters + execute (real completion + computed values/CCR); CCR-write / store-queue drain / exception entry at commit; cluster steering at dispatch; precise per-robId squash (coarse `tail:=head` now); real branch/mispredict resolution; SQ/memory; serialization handling.

## 3. Components

### 3.1 ROB ring
- Depth `ROB_DEPTH` (=64). Entry fields (subset of `RobEntry` relevant to commit now): `pc`, `predNextPc`, int `{archDst, newPdst, oldPdst, writes}`, nzvc `{newPdst, oldPdst, writes}`, x `{newPdst, oldPdst, writes}`, `ccrWriteMask`, `retireAlone`, `singleUop`, `complete`.
- Storage: `complete`/`valid` as FF vectors (async, set/clear per cycle); the wider payload (mappings/pc) in a `Mem` (async read at the head for retire). `head`/`tail`/`count` regs. `robId` = ring index (`log2Up(ROB_DEPTH)` bits).
- Alloc ≤2/cycle at tail; retire ≤2/cycle at head.

### 3.2 `RobPlugin` (FiberPlugin)
- **Dispatch/alloc:** `val du = host[RenameUopService]`. For each valid renamed slot (`du.uops.valid` for slot0, `du.uop1Valid` for slot1): write a ROB entry at `tail + slotIndexAmongValid`, fields from the `RenamedUop` (`predNextPc = pc + 2` placeholder fall-through — real length later; `complete := False`; `singleUop := True` for these 1-µop ops). `tail += #allocated`. `du.uops.ready := room for 2` (back-pressure). robId of slot = the tail index it took.
- **Completion:** `markComplete: slave Flow(UInt(robIdW))` (test port) → `complete(robId) := True`. (Real driver = execute writeback by robId.)
- **Commit/retire:** `h0 = head`, `h1 = head+1`. Retire slot0 when `valid(h0) && complete(h0)`. Retire slot1 also when slot0 retires AND `valid(h1) && complete(h1) && !entry(h0).retireAlone && !entry(h1).retireAlone`. On each retired entry: drive its `RenameCommitService.commit` slot (archDst→newPdst committed; free oldPdst/nzvcOld/xOld); fire a `CommitTrace` (pc=predNextPc, archRegId=int archDst, archRegValid=int writes, archRegWrite=0 (stub, no execute), ccr=0/ccrValid=false (stub), memWrite=false, excTaken=false). Advance `head += #retired`; `count -= #retired`. (CommitTrace fires once per retired instruction; with 2-wide retire, the `CommitTraceService` exposes up to 2 trace records/cycle — `trace: Vec(CommitTrace, 2)` + per-slot fire.)
- **Flush:** `flush: slave Flow(NoData)` (test) → `tail := head`, `count := 0`, clear `valid`; drive `RenameCommitService.flush` (rename rollback). Coarse squash (everything younger than head).
- `extends FiberPlugin with CommitTraceService`. Resolves `host[RenameUopService]` + `host[RenameCommitService]`.

### 3.3 `RenameCommitService` + rename changes
```
case class CommitSlot() extends Bundle {   // one retired instruction's commit/free info
  val intArch  = UInt(4 bits); val intNew = UInt(6 bits); val intOld = UInt(6 bits); val intWrite = Bool()
  val nzvcNew  = UInt(4 bits); val nzvcOld = UInt(4 bits); val nzvcWrite = Bool()
  val xNew     = UInt(4 bits); val xOld    = UInt(4 bits); val xWrite    = Bool()
}
trait RenameCommitService {
  def commit: Vec[Flow[CommitSlot]]   // length 2 (2-wide retire)
  def flush:  Flow[NoData]
}
```
`RenameStage` implements it (plain wires): on `commit(k).valid`, write the committed RAT (`intArch→intNew` etc.) AND push `intOld`/`nzvcOld`/`xOld` to the respective **freelists** (`intFree.io.push(k)` etc. — replacing the current `setIdle()`, closing the free loop); on `flush.valid`, rollback (as today). The ROB drives `commit`/`flush`; rename's standalone tests drive them via a small source plugin (convention).

### 3.4 `CommitTraceService`
```
trait CommitTraceService { def trace: Vec[CommitTrace]; def traceFire: Vec[Bool] }   // length 2
```
(There is an existing `CommitTraceService` stub trait from the foundation — extend/align it to this 2-wide shape.) The lock-step harness consumes this once execute fills real values.

## 4. Data flow
`RenameUopService → RobPlugin.dispatch (alloc) → ring → [markComplete test port sets complete] → RobPlugin.commit (2-wide retire) → {RenameCommitService.commit (RAT-commit + freelist-free), CommitTrace}`. `flush` test port squashes + rolls back rename.

## 5. Verification (test-driven completion/flush; sink/source helpers per convention)
Host `RenameUopSource (test) → RenameStage → RobPlugin`, with a `CommitTraceSink` reading the trace. Tests:
1. **Alloc + retire:** dispatch 2 renamed µops, `markComplete` both, observe 2-wide `CommitTrace` fire with correct `pc`/`archRegId`; head advances.
2. **In-order retire:** complete head+1 before head → head+1 does NOT retire until head completes.
3. **Closes the freelist-free loop (the key test):** in a sustained loop, dispatch+complete+commit many µops (more than `PHYS_INT_REGS`=48 allocations); assert the freelist never runs dry / rename keeps accepting (proves `oldPdst` is pushed back on commit). Without the fix this would deadlock.
4. **Committed-RAT update:** after committing `D0→Pnew`, a flush then a rename reading D0 returns `Pnew` (committed mapping) — proves commit wrote the committed RAT.
5. **Flush squash:** dispatch some, `flush`, then dispatch again from a clean ROB (tail==head); no stale retire.
6. **retireAlone:** an entry marked `retireAlone` retires 1-wide even if head+1 is complete.

## 6. Files & structure
| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/rob/RobPlugin.scala` | dispatch/alloc + ring + completion + 2-wide retire + drives rename commit/flush + CommitTrace |
| `src/main/scala/m68k040/rob/RobTypes.scala` | `CommitSlot` (+ ring entry payload bundle if helpful) |
| `src/main/scala/m68k040/services/Services.scala` (modify) | add `RenameCommitService`; align `CommitTraceService` to 2-wide |
| `src/main/scala/m68k040/rename/RenameStage.scala` (modify) | implement `RenameCommitService`: wire freelist push from commit; expose commit/flush as the service |
| `src/test/scala/m68k040/rob/RobPluginSpec.scala` | the §5 tests |
| `src/test/scala/m68k040/rob/RenameUopSourcePlugin.scala`, `CommitTraceSinkPlugin.scala` | test helpers |

## 7. Open items for the plan
- Ring storage exact form (Mem for payload + FF vectors for valid/complete); 2-alloc / 2-retire pointer arithmetic (wraparound).
- `predNextPc` placeholder (`pc+2`) until real instruction length flows through rename (decode carries `lenWords`; thread it into `RenamedUop`/ROB later — for now `pc+2` is a stub since lock-step value-compare isn't active yet).
- How rename's existing standalone tests drive the new `RenameCommitService` (a source plugin, replacing the current direct `commit`/`flush` test ports) — keep rename's tests green.
- Whether `CommitTraceService` reuses the foundation stub trait or supersedes it.

## 8. Known divergences / deferrals (logged)
- Completion + mispredict test-driven (real = execute + branch resolution).
- `CommitTrace` computed value/CCR stubbed (0) until execute; `pc` uses `predNextPc=pc+2` placeholder. Full lock-step value-compare awaits execute.
- Coarse squash (`tail:=head`) — precise per-robId squash later.
- No CCR-write / SQ-drain / exception entry / serialization at commit yet (need execute/LSU/CCR values).
- Cluster steering at dispatch deferred (no issue queues yet).
