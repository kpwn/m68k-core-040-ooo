# Rename Slice 1 — Integer + Split-CCR Register Rename — Design

**Status:** Draft for review
**Date:** 2026-06-01
**Parent spec:** `docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md` (ch 3 Rename, 4.2 PRF, 4.3 dual-RAM RAT, 4.6 split CCR; **invariant #2 FMax 250 MHz / no high-fanout**, #3 plugin boundaries)
**Builds on:** merged decode (`DecodeUopService` — 2-wide `DecodedUop` stream).
**Reference studied:** NaxRiscv `RfTranslationPlugin` (`TranslatorWithRollback`), `RfAllocationPlugin` (multi-port freelist), `RfDependencyPlugin` — FPGA-friendly rename patterns.
**Slice position:** first OoO-backend slice. Rename is a subsystem; this builds the dual-RAM RAT + freelist + 2-wide hazard machinery for the integer regs and the split CCR (NZVC/X).

---

## 1. Purpose & FMax discipline

Map `DecodedUop` architectural operands → physical registers, 2-wide, producing a `RenamedUop` stream for the (future) dispatch/ROB. Rename is on the critical mispredict-recovery path, so it is built for **250 MHz with no high-fanout signals** (invariant #2). The FPGA-friendly choices (from NaxRiscv) are first-class requirements here:

- **Dual-RAM RAT + per-arch-reg location bit** (NaxRiscv `TranslatorWithRollback`): flush is `location := 0` — **O(1)**, no ROB-walk fanout. Reads are **async LUTRAM** (`readAsync`) — no broadcast, no read latency.
- **Pointer-based freelist** (circular buffer of free phys-ids): allocation/free move head/tail pointers; **flush resets pointers, not a broadcast-clear of per-entry valid bits** (avoids a flush signal fanning out across all 48 entries).
- **Bounded ports:** 2 write/2 commit/few read ports; the multi-write WAW bypass is a small per-port compare (NaxRiscv pattern), not a wide crossbar.
- **Flush fans out only to the tiny location-bit vectors** (16 + 1 + 1 bits) + the freelist pointers — never to wide arrays.
- No global broadcast of allocated pdsts: a pdst feeds only the spec-RAT write port and the bounded slot-bypass comparators.
- Apply SpinalHDL's `MultiPortWritesSymplifier`-style intent (keep multi-port `Mem` writes simple) where it helps timing.

## 2. Scope

**In this slice:** integer RAT+freelist (16 arch regs, 48 phys) AND split-CCR RATs+freelists (NZVC: 1 arch / 16 phys; X: 1 arch / 16 phys); 2-wide intra-group RAW (slot-bypass) + WAW (RAT write ordering) hazards; `RenamedUop` + `RenameUopService`; `flush` + `commit` as test-driven ports (no ROB/backend yet). Reusable `RatTable` + `Freelist` components.

**Out of scope (later):** the real ROB/dispatch/commit driver; FP rename (slots reserved in M68kParams but no PRF built); precise per-physreg freelist rollback on flush (first cut: coarse pointer reset to the committed state); memory/complex µops (decode sub-slices 2/3).

## 3. Components

### 3.1 `RatTable` (generic dual-RAM RAT — adapt NaxRiscv `TranslatorWithRollback`)
Params: `physIdWidth`, `archDepth`, `writePorts`, `commitPorts`, `readPorts`.
- `specRam = Mem(UInt(physIdWidth bits), archDepth)`, `commitRam = Mem(...)` — both `readAsync`.
- `location = Reg(Bits(archDepth bits))` init 0 — bit `a` set ⇒ spec is current for arch reg `a`.
- **Write ports** (rename): `specRam.write(addr, data, en && !laterPortSameAddr)` (multi-write-bypass: a later write port to the same addr wins); set `location(addr) := 1` on any write.
- **Commit ports** (commit): `commitRam.write(addr, data, en)`. (Committed mappings become the rollback target.)
- **Read ports**: `out := location(a) ? specRam.readAsync(a) : commitRam.readAsync(a)`.
- **`rollback: in Bool`**: `when(rollback){ location := 0 }` — O(1) restore to committed.
- Init: a small counter fills `commitRam` with the identity mapping over `archDepth` cycles (arch reg a ↦ phys a) and leaves `location := 0`.

### 3.2 `Freelist` (pointer-based multi-port allocator)
Params: `physCount`, `popPorts` (=2), `pushPorts` (=2). A circular buffer `Mem(physIdWidth, physCount)` of free phys-ids with head/tail pointers and an occupancy count.
- `pop`: up to `popPorts` ids/cycle (head advances); `io.pop.ready` false when fewer free than requested (back-pressure). Each pop port returns a distinct free id.
- `push`: up to `pushPorts` freed ids/cycle on commit (tail advances).
- **Init**: fill with the non-architectural phys-ids (arch-count .. physCount-1) so the architectural ids are reserved as the initial committed mapping.
- **Flush (first cut)**: reset head/tail/occupancy to the committed allocation state — a **pointer reset** (low fanout), not a per-entry clear. (Precise rollback that reclaims exactly the speculative allocations is a later refinement; the coarse reset is correct because flush also resets the spec RAT to committed, so no speculative pdst is referenced after flush.)

### 3.3 `RenameStage` (FiberPlugin)
- `extends FiberPlugin with RenameUopService`. Consumes `host[DecodeUopService]`.
- Owns: `intRat` (RatTable 16-arch), `nzvcRat` (1-arch), `xRat` (1-arch); `intFree` (48), `nzvcFree` (16), `xFree` (16).
- Per slot (0,1): read int `srcA/srcB` (when `srcAValid/srcBValid`), NZVC src (when `readsNzvc`), X src (when `readsX`) from the RATs; allocate `pdst` (int, when `dstValid`), `pNzvcDst` (when `writesNzvc`), `pXDst` (when `writesX`) from the freelists; capture the **old** mappings (`pdstOld` = current RAT entry before overwrite) for commit-time free; write new mappings into the spec RATs.
- **Intra-group hazards (2-wide):** slot-1 int sources compare against slot-0's int dst (and flag sources vs slot-0 flag dsts); on match, bypass to slot-0's just-allocated pdst (NaxRiscv slot-bypass). WAW (both slots write same arch reg/flag): the RAT multi-write-bypass + sequential ordering make slot-1's mapping final; slot-0's pdst is still allocated (freed later at commit).
- Emit `RenamedUop` per slot. Back-pressure (`feed.ready := uops.ready && allFreelistsReady`); `uops.valid := feed.valid`.
- `flush: Flow()` → `rollback` to all RATs + freelist pointer reset. `commit: Flow(Vec(commitSlot, 2))` → RAT commit-port writes + freelist pushes (free `pdstOld`). Both test-driven now.

### 3.4 `RenamedUop` + `RenameUopService`
```
RenamedUop {  // rename -> dispatch/ROB contract
  // carried from DecodedUop:
  valid, pc, op, cluster, size, useImm, imm, isBranch, cond, branchDisp, unimplemented
  // physical integer operands:
  psrcA, psrcAValid; psrcB, psrcBValid; pdst, pdstValid; pdstOld   // old int mapping (for commit free)
  // physical split-flag operands:
  pNzvcSrc, readsNzvc; pNzvcDst, writesNzvc; pNzvcOld
  pXSrc,    readsX;    pXDst,    writesX;    pXOld
}
```
`RenameUopService { def uops: Stream[Vec[RenamedUop]]; def uop1Valid: Bool }` (plain-wire convention). Phys-id widths from `Global.PHYS_INT_REGS/PHYS_NZVC_REGS/PHYS_X_REGS`.

## 4. Verification
- **`RatTable`** (directed): write→read returns spec value; after `commit` to a different value + `rollback`, read returns the committed value (O(1) restore); two write ports same addr → later wins; init gives identity mapping.
- **`Freelist`** (directed): sequential pops return distinct ids; pop when empty → `!ready`; push then pop returns the pushed id; flush resets to committed pointer state.
- **`RenameStage`** (directed — the crux): `MOVE D1,D0` → `pdst` fresh, `psrcA` = current D1 phys; **intra-group RAW**: slot0 `MOVE x,D0`, slot1 `MOVE D0,D2` → slot1.psrcA == slot0.pdst; **WAW**: slot0 & slot1 both write D0 → spec RAT(D0) == slot1.pdst, slot0.pdst ≠ slot1.pdst (distinct allocations); **flag rename**: slot0 `ADD` (writesNzvc+X), slot1 `Bcc`(readsNzvc) → slot1.pNzvcSrc == slot0.pNzvcDst; **flush** restores committed mappings; **commit** of a uop frees its `pdstOld` (next alloc may reuse it).
- **FMax intent check (non-gating):** keep the RAT read/bypass path shallow; note in review that flush fans out only to location bits + freelist pointers.

## 5. Files & structure
| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/rename/RatTable.scala` | generic dual-RAM RAT (location-bit, rollback, multi-write-bypass) |
| `src/main/scala/m68k040/rename/Freelist.scala` | pointer-based multi-port allocator |
| `src/main/scala/m68k040/rename/RenamedUop.scala` | `RenamedUop` bundle |
| `src/main/scala/m68k040/rename/RenameStage.scala` | FiberPlugin: RATs + freelists + 2-wide hazards |
| `src/main/scala/m68k040/services/Services.scala` (modify) | add `RenameUopService` (plain wires) |
| `src/test/scala/m68k040/rename/RatTableSpec.scala` | RAT directed tests |
| `src/test/scala/m68k040/rename/FreelistSpec.scala` | freelist directed tests |
| `src/test/scala/m68k040/rename/RenameStageSpec.scala` | rename directed tests (hazards, flush, commit) |

## 6. Open items for the plan
- Exact `Freelist` circular-buffer width/occupancy logic and the 2-pop/2-push port arbitration (distinct ids).
- `RatTable` multi-write-bypass exact form (NaxRiscv: later port `valid && sameAddr` disables earlier write).
- Whether the rename output is registered (1 cycle) or combinational from the RAT reads; baseline registered for clean timing (RAT reads are async → register the `RenamedUop`).
- Commit-port `Vec` shape and how `pdstOld` is sourced (from the RAT read at rename, carried in `RenamedUop`, returned via the test `commit` port).

## 7. Known divergences / deferrals (logged)
- Commit/flush test-driven (real driver = ROB/commit slice).
- **REQUIRED follow-up (commit/ROB slice): wire freelist push-on-commit.** This slice leaves `Freelist.io.push`
  idle (`setIdle()`) — commit updates the committed RAT but does NOT return `pdstOld` back to the freelist.
  Correct now (no test drains 48/16/16 regs), but under sustained operation the freelist monotonically drains
  and rename stalls forever. `RenamedUop` already carries `pdstOld/pNzvcOld/pXOld` for this; the commit loop
  MUST push them back. Non-negotiable before the core runs real programs.
- Coarse freelist reset on flush (precise speculative-reclaim deferred; correct because spec RAT also resets to committed).
- FP rename deferred (PRF not built; rename namespace reserved).
- Memory/complex µops not produced by decode yet (sub-slices 2/3) — rename handles whatever DecodeUopService emits; `unimplemented` µops are carried through (rename maps their declared operands or none).
