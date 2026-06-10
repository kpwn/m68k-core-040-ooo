# Predecrement / postincrement addressing modes (`-(An)` / `(An)+`) — design

**Date:** 2026-06-10
**Status:** approved (brainstorm) — ready for implementation plan
**Branch (to be):** `feat/predec-postinc`

## Goal

Implement the **`-(An)` predecrement** and **`(An)+` postincrement** effective-address modes
(EA modes 4 and 3) in `EaDecoder` + the assembler crack, generalizing the call/return A7
side-effect machinery (`stkPush` predec-store, `anInc` postinc) to **any** address register.
This is the foundational unblock for **MOVEM (the committed next slice), PEA, string ops,
and the deferred ADDX/SUBX/ABCD/SBCD/CLR/NEG/etc. memory forms.**

The **indexed** modes `(d8,An,Xn)`/`(d8,PC,Xn)` (mode 6, 7/3) stay deferred (orthogonal —
index-reg read + scale + extension format; a separate later slice), as do the 68020
memory-indirect/base-disp formats.

## Context

`EaDecoder` (`decode/EaDecoder.scala`) currently classifies mode 3 `(An)+` and mode 4
`-(An)` as `EaClass.MEMCOMPLEX`, which the assembler keeps `unimplemented`. The MEMSIMPLE
control modes (`(An)`, `(d16,An)`, `(xxx).W/.L`, `(d16,PC)`) are cracked into load/store
µops with the EA base/disp. The call/return slice already proves the side-effect pattern:
`stkPush` is a predecrement store (`A7 := A7−4`, store data, the µop's int dst = the new
A7), and RTS/RTR postincrement-load A7 via `anInc`. This slice **generalizes** that
register-update-folded-into-the-mem-µop pattern from A7 to any An and to any size.

## Architecture

### 1. `EaDecoder` — classify predec/postinc with a side-effect marker
Add to `EaSpec` a small side-effect descriptor: `autoMode` ∈ {none, postInc, preDec} and
the access size is already known to the assembler. Mode 3 → `postInc`, mode 4 → `preDec`;
both set `baseValid := True`, base = `An` (`8+reg`). The **effective address**:
- `postInc (An)+`: EA = `An`; An updated to `An + delta` *after* the access.
- `preDec -(An)`: EA = `An − delta`; An updated to `An − delta` *before* the access (the
  decremented value is both the EA and the new An).
- `delta = sizeBytes`, **except a byte access on A7** (`reg == 7 && size == BYTE`) →
  `delta = 2` (keep the stack pointer even — the 68k A7 rule).

These stay a memory class (reuse the MEMSIMPLE crack path) plus the `autoMode`/`delta`.

### 2. Assembler crack — fold the An write-back
Extend the load/store cracks (`MicroOpAssembler`) so a predec/postinc EA additionally
writes `An := An ± delta`, reusing the `stkPush`/`anInc` mechanism generalized to any An:
- **Load `(An)+`** (e.g. `MOVE (An)+,Dn`): load from `An` → result; `An += delta`.
- **Store `-(An)`** (e.g. `MOVE Dn,-(An)`): `An −= delta`; store to the new `An`.
- **RMW `(An)+`/`-(An)`** (e.g. `ADD Dn,(An)+`): load/op/store the **same** computed EA, with
  **one** An update for the instruction.
- **Both operands auto** (e.g. `MOVE (Ay)+,(Ax)+`, or `ABCD -(Ay),-(Ax)`): each EA updates
  its own An; the cracks compose (source EA crack + dest EA crack each carry their An write).
The An write-back is an int writeback on the existing AGU/LS path (no new EU datapath) —
the same port the `stkPush` A7 side-effect already uses.

### 3. EU/AGU
The AGU computes `An` (postinc) or `An − delta` (predec) as the access address; the `An`
writeback rides the existing int write port (as `stkPush` does for A7). No new datapath.

## Testing (gates, in order)

1. **`EaDecoder`/crack decode spec** (extend the decode specs): mode 3/4 → the new
   class + `autoMode`/`delta` (incl. A7-byte → delta 2); the µop crack emits the An
   write-back; existing MEMSIMPLE/DATAREG/IMM modes unchanged.
2. **Lock-step vs Musashi** — across consumers, with `checkMem`:
   - `MOVE.B/.W/.L (An)+,Dn` / `MOVE Dn,-(An)` / `MOVE (Ay)+,(Ax)+` / `MOVE Dn,-(A7)` (byte → A7−2).
   - ALU `ADD/SUB/AND/OR (An)+,Dn` and the **now-enabled memory forms**: `ADDX -(Ay),-(Ax)`,
     `SBCD -(Ay),-(Ax)`, `CLR (An)+`, `NEG -(An)`.
   - Edge cases: An wraparound, `(An)+` then a read of the same An (RAW on the An update),
     A7-byte even-keeping, and a predec/postinc whose An equals a source/dest reg.
3. **`make test-fast`** + decode/predecode specs (incl. the stale-spec sweep:
   `OperationDecoderSpec`/`PredecodeWordSpec` — re-run since the predec/postinc cracks may
   reframe predecode lengths).
4. **≥200 MHz full-core synth gate** — reported (the An write-back is a shallow add; expect
   FMax-neutral, D-cache still the limiter).

## Non-goals

- Indexed modes `(d8,An,Xn)`/`(d8,PC,Xn)` and the 68020 full extension (base/outer disp,
  memory indirect, scale) — deferred (separate slice).
- MOVEM itself — the **committed next slice** (its primary consumer), separate spec.

## Risks

- **An write-back as a per-instruction side-effect** — must land exactly once for RMW
  (load+store share the EA + one An update), and compose for double-auto (`(Ay)+,(Ax)+`).
  Covered by the RMW + double-auto lock-step with `checkMem`.
- **A7-byte even rule** — `delta=2` for byte on A7; a missed special-case desyncs the stack.
  Covered by the `MOVE.B Dn,-(A7)` / `(A7)+` lock-step.
- **RAW on the An update** — a following µop/instruction reading An must see the updated
  value (rename + the IQ wakeup for the An-producing mem µop — the same LS-int-producer path
  the LINK/UNLK slice broadened to `s0IsLsIntProd`). Covered by `(An)+` followed by an An use.
- **Predecode framing** — the predec/postinc forms must frame the right instruction length
  (no extension word for these modes) — confirm `PredecodeWord`/`PredecodeRef` parity.
