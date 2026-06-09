# EXG (exchange two registers) — design

**Date:** 2026-06-10
**Status:** approved (brainstorm) — ready for implementation plan
**Branch (to be):** `feat/exg`

## Goal

Implement the 68k **EXG** instruction — **exchange the full 32-bit contents of two
registers** — in all three forms (`EXG Dx,Dy`, `EXG Ax,Ay`, `EXG Dx,Ay`). EXG sets **NO
condition codes** (NZVC and X are untouched). It needs **two register writes**, so it is
**cracked into 3 MOVE µops through an int temp** (`T0 := Rx ; Rx := Ry ; Ry := T0`),
reusing the existing 3-µop crack + temp-reg machinery (the same `AssembledUops` 2→3 budget
+ `firstOfInstr` + `T0/T1` temps that RTR / mem-RMW already use). NO new `DecOp`, NO new EU
datapath — pure **decode + assemble** (three plain `DecOp.MOVE` µops). Resumes the
ISA-completion track ([[isa-completion-roadmap]]); gates at **≥200 MHz** (the relaxed gate,
see [[synth-gate-every-slice]]).

## Context

The machinery is already in place and is reused wholesale:

- **3-µop crack budget.** `MicroOpAssembler.AssembledUops` carries `Vec(DecodedUop(), 3)` +
  `count`. The only existing 3-µop case is **RTR** (`pop.w CCR + pop.l PC + ibranch`); a
  second is the mem-dest RMW (`load → op → store`). `DecodeStage` already gates a 3-µop
  instruction one-per-cycle (`slot0Is3`/`slot1Is3` defer the sibling slot to the stash and
  replay it), so a 3-µop EXG needs **no new front-end plumbing** — it rides the RTR path.
- **`mkUop` builder.** `MicroOpAssembler` has a `mkUop(...)` helper (used by the
  BSR/JSR/RTS/RTR cracks) that assigns **every** `DecodedUop` field **exactly once** (Spinal
  flags an unconditional reassignment as an overlap). EXG's three MOVE µops are built with
  `mkUop` (plain `DecOp.MOVE`, INT cluster, full-32 register moves, no flags).
- **Int temps.** `T0 = 16`, `T1 = 17` are the cracker's architectural temp regs (5-bit reg
  ids so the temp targets fit). EXG uses **one** temp (`T0`) to hold the first register
  across the swap.
- **Plain register MOVE µop.** A `DecOp.MOVE` with `size = LONG`, `srcB = a register`,
  `dst = a register`, `writesNzvc = False` is exactly the "move a full 32-bit register to
  another register" primitive the swap needs (the ALU EU's MOVE result = srcB; LONG → no
  partial merge). MOVEQ and the call/return temp moves already exercise this shape.

EXG lives in **line C (`1100`)**, which is **shared** with the big-5 **AND** (and, when they
land, **ABCD** and the existing **MULU**). The decode addition must not disturb the existing
line-C AND decode — it is disambiguated by **bit8=1 + the 5-bit opmode pattern** (see §4).

## Encoding (line C)

`EXG` is `1100 xxx 1 ooooo yyy`:

| bits  | field   | meaning |
|-------|---------|---------|
| 15:12 | `1100`  | line C |
| 11:9  | `xxx`   | register **Rx** (the bits-11:9 register) |
| 8     | `1`     | always 1 for EXG |
| 7:3   | `ooooo` | **opmode** — selects the register *classes* |
| 2:0   | `yyy`   | register **Ry** (the bits-2:0 register) |

The 5-bit **opmode** `ooooo` selects which two register files are exchanged:

| `ooooo` | form         | Rx (11:9) | Ry (2:0) | arch reg ids |
|---------|--------------|-----------|----------|--------------|
| `01000` | `EXG Dx,Dy`  | Dx        | Dy       | Dx = `xxx`, Dy = `yyy` (0..7) |
| `01001` | `EXG Ax,Ay`  | Ax        | Ay       | Ax = `8+xxx`, Ay = `8+yyy` (8..15) |
| `10001` | `EXG Dx,Ay`  | Dx        | Ay       | Dx = `xxx` (0..7), Ay = `8+yyy` (8..15) |

All three forms swap the **full 32 bits** of the two registers. There is **no size field**
and there are **no condition codes**. EXG is a **single-word** instruction (no extension
words). Note the operand-ordering convention: for `EXG Dx,Ay` the *data* register is in
bits 11:9 and the *address* register is in bits 2:0 (this is fixed by the opmode, NOT
free — only `10001` mixes the files, and it is always D in 11:9 / A in 2:0).

### Architectural-register-id mapping (the only subtlety)

The existing `MicroOpAssembler` operand router maps `op(11:9)` to a data reg (`= op(11:9)`)
or an address reg (`= 8 + op(11:9)`) via `OperandSrc.isAddr`, and likewise for `op(2:0)`.
EXG's two register ids are therefore:

- `EXG Dx,Dy`: regA = `op(11:9)` (D), regB = `op(2:0)` (D).
- `EXG Ax,Ay`: regA = `8 + op(11:9)` (A), regB = `8 + op(2:0)` (A).
- `EXG Dx,Ay`: regA = `op(11:9)` (D), regB = `8 + op(2:0)` (A).

The assembler computes these two 5-bit arch ids directly from the opmode (no `EaDecoder`
needed — EXG has no EA; both operands are fixed register fields).

## Architecture

### 1. Op + fields (`DecodedUop`)

**No change.** EXG reuses `DecOp.MOVE` for all three crack µops; no new `DecOp`, no new µop
field. (Contrast with bit-ops / shifts, which added a sub-kind field — EXG needs none.)

### 2. Decode (`OperationDecoder`, line C)

EXG is detected in the existing `is(0x8, 0x9, 0xB, 0xC, 0xD)` arm, **scoped to line C +
bit8=1 + a valid EXG opmode**, BEFORE / disjoint from the AND-RMW and MULU detection:

```
isExg = (line === 0xC) && opword(8) &&
        (opword(7 downto 3) === "01000" ||    // EXG Dx,Dy
         opword(7 downto 3) === "01001" ||    // EXG Ax,Ay
         opword(7 downto 3) === "10001")       // EXG Dx,Ay
```

Because EXG is fully cracked in the assembler (3 MOVE µops with computed reg ids), the
cleanest grounding given the current code shape is to **detect + crack EXG in the
`MicroOpAssembler`** (like RTS/RTR/JSR/BSR, which are matched on the raw opword in the
assembler and built with `mkUop`), and have `OperationDecoder` simply NOT illegalise the
EXG opwords. Two acceptable implementations, decided at Task-1 (see Open Questions):

- **(A) Assembler-cracked (preferred, mirrors RTR/RTS):** `OperationDecoder` leaves line-C
  EXG as a benign placeholder (not illegal, so `bad` stays false for EXG); the assembler
  matches `isExgOp` on the opword and emits the 3-µop sequence, exactly like the `isRtrOp`
  path. This keeps the multi-write crack in one place with the other multi-µop cracks and
  reuses `mkUop`.
- **(B) Decoder-named + assembler-routed:** add an `isExg`-style marker to `OpSpec` and
  route in both files (heavier; only needed if the assembler cannot see enough of the
  opword — it can, so (A) is preferred).

The plan is written for **(A)**.

### 3. Assemble (`MicroOpAssembler`) — the 3-µop crack

Detect `isExgOp` on the raw opword and crack into three full-32 register MOVE µops through
`T0`. Let `regA` = the bits-11:9 register id and `regB` = the bits-2:0 register id (each
data/address per the opmode, computed as in §1):

```
µop0:  MOVE.L  regA -> T0        (firstOfInstr = True ;  save Rx)
µop1:  MOVE.L  regB -> regA      (firstOfInstr = False;  Rx := Ry)
µop2:  MOVE.L  T0   -> regB      (firstOfInstr = False;  Ry := saved Rx)
count = 3
```

Each µop is built with `mkUop`:
- `cluster = INT`, `op = MOVE`, `size = LONG`, `memOp = NONE`.
- **srcB** = the move source register (`srcBReg = src`, `srcBValid = True`) — the ALU EU's
  MOVE result is `srcB` (matches the existing MOVE operand convention: `OperationDecoder`
  places the MOVE source in the srcB slot).
- **dst** = the move destination register (`dstReg = dst`, `dstValid = True`).
- `srcA` unused (full-32 MOVE.L → no partial-register merge → no old-value read).
- `useImm = False`, `writesNzvc = False`, `writesX = False`, `readsNzvc/readsX = False`,
  `isBranch/ibranch/stkPush = False`, `isMovea = False` (the dst is written full-32 with no
  flags whether D or A, which is exactly plain MOVE.L-to-reg; **no `isMovea` needed** — see
  the EXG-Ax,Ay note below).
- `firstOfInstr`: **True for µop0 only**; µop1/µop2 are trailing crack µops (so an
  interrupt is taken only at the instruction boundary, matching RTR's `first = True/False`
  pattern).

Sequence selection: add an `isExgOp` arm to the `out.uops`/`out.count` chain (alongside
`isRtrOp`), emitting `count = 3` with `uops(0..2)` = the three moves. Place it so it does
**not** fall through to the line-C ALU paths (the `bad`/AND-RMW gating must not fire for an
EXG opword — see §4).

**Why one temp (T0), not T1.** A register swap is the textbook 3-move-through-temp idiom;
only one scratch is needed. `T1` is free (RTR/mem-RMW use it elsewhere, never concurrently
with EXG since EXG is its own instruction). Renaming gives each µop fresh physical
destinations, so the WAR/WAW between `regA`/`regB`/`T0` is resolved by the rename map +
in-order intra-instruction µop ordering: µop1 reads the OLD `regB` and writes a NEW physical
`regA`; µop2 reads `T0` (= the OLD `regA`, saved by µop0) and writes a NEW physical `regB`.
The data dependency `T0 (µop0) → µop2` and the read-before-overwrite of `regA`
(µop0 reads regA before µop1 overwrites it) are the standard OoO RAW/WAR handling, identical
to how RTR's three µops are renamed.

### 4. Line-C disambiguation (vs AND / ABCD / MULU)

Line C currently decodes (in `OperationDecoder`'s `is(0x8,0x9,0xB,0xC,0xD)` arm):
- **MULU.W**: `opmode(8:6) = 3 or 7` (i.e. `opword(8:6) ∈ {011,111}`), EA ≠ An-direct.
- **AND `<ea>,Dn`** (`!isRmw`): `opmode(8:6) ∈ {0,1,2}`.
- **AND `Dn,<ea>` RMW** (`isRmw`): `opmode(8:6) ∈ {4,5,6}`, EA must be MEMSIMPLE
  (`aluRmwMemBad` illegalises a non-memory EA).

EXG always has **bit8 = 1**, so `opword(8:6) = 1 ## opword(7:6)`:
- `EXG Dx,Dy` (`ooooo=01000`): `opword(7:6) = 01` → `opmode(8:6) = 101 = 5`.
- `EXG Ax,Ay` (`ooooo=01001`): `opword(7:6) = 01` → `opmode(8:6) = 101 = 5`.
- `EXG Dx,Ay` (`ooooo=10001`): `opword(7:6) = 10` → `opmode(8:6) = 110 = 6`.

So EXG opwords land in the **AND-RMW opmode band (5/6)**. They do **not** collide with MULU
(opmode 3/7) or with AND-to-Dn (opmode 0/1/2). Within the RMW band, they are distinguished
because **EXG's EA bits 5:3 are register-direct** (mode 0 for `EXG Dx,Dy`, mode 1 for
`EXG Ax,Ay` / `EXG Dx,Ay`), which the AND-RMW path **rejects** (`aluRmwMemBad` requires
MEMSIMPLE; a register-direct EA there is illegal). Today, therefore, these exact opwords
already decode to **illegal** — EXG is a pure *addition*, claiming opwords the AND-RMW path
discards.

**The discriminator is the full 5-bit opmode `opword(7:3) ∈ {01000, 01001, 10001}` with
`line==C && bit8==1`.** The implementation must check `isExgOp` **before** (or instead of)
letting the AND-RMW `bad`/`aluRmwMemBad` path illegalise these opwords:

- Implementation (A) matches `isExgOp` on the opword in the assembler and emits the EXG
  crack in the `out.uops` selection chain, and ensures `bad`/`aluRmwMemBad` do **not** also
  force `uops(0)` illegal for an EXG opword (mirror how `isRtrOp`/`isJsrOp` are excluded
  from `bad`: `bad := !isExgOp && … && (…)`).
- Crucially, the AND-RMW detection is **opmode 4/5/6 + MEMSIMPLE-EA**; EXG's EA is reg-
  direct, so the AND-RMW crack never fires for an EXG opword regardless — the only work is
  to NOT take the illegal path and to take the EXG crack instead.

**ABCD** (`1100 xxx 1 0000 m yyy`) is **not yet implemented**. When it lands it occupies
`opword(7:4) = 1000` with `opword(3)` = R/M (the byte BCD-add form, opmode-band `1 00000` /
`opword(8:3)=100000`). EXG's `10001` (= `EXG Dx,Ay`) has `opword(7:4)=1000` and
`opword(3)=1`, which **looks adjacent to ABCD**. To keep EXG and ABCD disjoint, the future
ABCD slice MUST key ABCD off the tight pattern `opword(8:3) = 100000` (R/M in `opword(3)`,
i.e. `ooooo ∈ {10000}`) and **exclude EXG's `ooooo ∈ {01001, 10001}`** rather than a loose
`opword(7:4)=1000` mask. Documented here so the ABCD author does not clobber EXG.

## EXG-Ax,Ay note (full-32 to An, no flags)

`EXG Ax,Ay` and `EXG Dx,Ay` write address registers. A plain `MOVE.L reg -> An` writes the
An **full-32 with no flags** — which is exactly what the EXG crack needs and what a plain
`mkUop(MOVE, size=LONG, dst=An)` produces (the `isMovea` marker is only needed for `.W`
sign-extension / partial-merge bypass, neither of which applies to a full-32 LONG move). So
**no `isMovea` flag is required** on the EXG µops; LONG MOVE-to-An is already full-32. (To
be confirmed against the ALU EU at Task-1 — see Open Questions — but the existing RTS/RTR
ibranch writes A7 full-32 the same way, so the full-32 path is exercised.)

## Edge cases

- **`EXG Dn,Dn` (same register, e.g. `EXG D3,D3`).** regA == regB. The 3-move crack is a
  no-op net effect: `T0 := D3 ; D3 := D3 ; D3 := T0`. Correct (D3 unchanged), no flags. The
  rename handles the same-arch-reg dual-touch correctly (each µop renames to a fresh phys
  reg; the final mapping points D3 at its original value). Lock-step must include this case
  (a same-reg dual-write was a historical RAT hazard — see [[ipc-and-deadlock-findings]] —
  so assert D3 is preserved). The 040 ISA permits `EXG Dn,Dn` (and `EXG An,An`).
- **A7 (stack pointer) involvement: `EXG A7,An` / `EXG An,A7`.** A7 is a normal address
  reg (id 15) here; the EXG crack just moves 32-bit values. No stack-frame side effects, no
  banking interaction (EXG does not push/pop). Lock-step should include an A7 form to
  confirm the SP is swapped like any An. (The whitebox A7 reconstruction from arch-15
  writeback — see [[call-return]] — applies; EXG writes arch-15 via a plain MOVE, so the
  reconstructed A7 tracks it.)
- **Single-word / nextPc framing.** EXG is one word with no extension. `PredecodeWord` must
  frame the line-C EXG opwords as `simple, lenWords = 1` so `nextPc = pc + 2` (else
  `lenWords = 0 → nextPc = pc`, the recurring crack-framing bug — see [[multiply]],
  [[traps]], [[call-return]]). Line C is currently handled by the `is(8,9,C,D)` predecode
  arm; add an EXG `len = 1` case there scoped to `line==C && bit8==1 && opmode∈{01000,
  01001,10001}`, BEFORE the AND-RMW `memDestExt` path (which would otherwise mark these
  reg-direct EAs not-simple → complex → wrong length).
- **Crack ordering / interrupt point.** `firstOfInstr` True on µop0 only; an interrupt may
  be taken only at the EXG boundary (head = µop0), never mid-swap (a partial swap is not
  architectural). Matches RTR.
- **3-µop slot gating.** A 3-µop EXG defers its sibling decode slot to the `DecodeStage`
  stash and replays it (exactly the RTR/3-µop path) — no new gating.

## Testing (gates, in order)

1. **`ExgDecodeSpec`** (new) — the encoding/crack: for `EXG D1,D2`, `EXG A1,A2`,
   `EXG D1,A2`, and `EXG D3,D3` (same-reg) (exact opwords in the plan), assert `count == 3`
   and the three µops are `MOVE regA→T0`, `MOVE regB→regA`, `MOVE T0→regB` with the correct
   **arch reg ids** (D vs A mapping per opmode), `size == LONG`, `writesNzvc == False`,
   `writesX == False`, `firstOfInstr` = {True, False, False}. Also assert a representative
   AND opword (e.g. an AND-to-Dn and an AND-RMW-to-memory) is **unchanged** (still
   `DecOp.AND`, not EXG) — the disambiguation regression.
2. **Lock-step vs Musashi** — the matrix: `EXG Dx,Dy`, `EXG Ax,Ay`, `EXG Dx,Ay` with
   **distinct seed values** in the two registers; assert **both registers are swapped** and
   **NZVC + X are unchanged** (pre-set the flags via a prior op, then EXG, then read CCR).
   Include `EXG Dn,Dn` (same reg → unchanged) and an **A7** form (`EXG A7,A0`). Seed with
   `move.l #X,%dX ; move.l #Y,%dY ; exg %dX,%dY ; …`.
3. **`make test-fast`** + the decode/crack regression (`DecodeStageSpec`,
   `OperationDecoderSpec`, the AND/MUL specs) — green (no line-C regression).
4. **≥200 MHz full-core synth gate** — reported. EXG adds only a small opword-match +
   3-µop sequence to the assembler (no datapath); FMax-neutral expected.

## Non-goals

- **ABCD / SBCD / line-C BCD ops** — separate, later (this slice only documents the
  disambiguation so ABCD does not clobber EXG; see Risks).
- New EU datapath or `DecOp` — none; EXG is decode + assemble only.
- Memory operands — EXG is register-only by ISA; no EA, no addressing modes.

## Risks

- **Line-C disambiguation (EXG vs AND-RMW vs MULU vs future ABCD).** EXG's opmode band
  (5/6) overlaps AND-RMW; the discriminator is the full 5-bit opmode + reg-direct EA, and
  EXG must be matched BEFORE the AND-RMW illegal path. A loose match could (a) swallow a
  real AND-RMW-to-memory opword, or (b) leave EXG illegal. Mitigated by the exact
  `opword(7:3) ∈ {01000,01001,10001}` match + the `ExgDecodeSpec` AND-unchanged assertion.
  **Future ABCD must exclude EXG's `01001`/`10001` opmodes** (documented in §4).
- **D-vs-A register-id mapping.** `EXG Dx,Ay` mixes files: regA = data `op(11:9)`, regB =
  address `8+op(2:0)`. A swapped/forgotten `+8` is a silent wrong-register bug. Covered by
  the `ExgDecodeSpec` reg-id assertions + the `EXG Dx,Ay` lock-step.
- **No-flags guarantee.** EXG must leave NZVC + X untouched; a stray `writesNzvc` on a MOVE
  µop (the MOVE-to-Dn default sets NZVC!) would corrupt CCR. The EXG µops must force
  `writesNzvc = False` (NOT the MOVE-to-Dn default). Covered by the flag-preservation
  lock-step case.
- **MOVE-to-Dn sets NZVC by default.** A plain `DecOp.MOVE` to a data reg normally sets
  NZVC (`writesNzvcIfDataDst`). The EXG crack uses `mkUop`, which defaults `writesNzvc =
  False` and does NOT run the `writesNzvcIfDataDst` resolution (that path keys off
  `spec.writesNzvcIfDataDst`, applied to `opUop` only, never to `mkUop`-built µops) — so the
  register moves write no flags. **Verify at Task-1**; asserted by lock-step.
- **Same-reg / A7 hazards.** `EXG Dn,Dn` and A7-involving forms exercised in lock-step
  (the historical RAT same-cell dual-write hazard — [[ipc-and-deadlock-findings]]).
- **nextPc framing.** EXG must predecode `simple, len=1` (line C, reg-direct EA), else the
  AND-RMW `memDestExt` marks it complex / wrong length. Covered by a predecode case +
  lock-step (a wrong nextPc desyncs the next instruction immediately).

## Assumptions (call out, don't guess)

- **A1: Assembler-cracked (impl A) is the chosen shape** — EXG matched on the opword in
  `MicroOpAssembler` (like RTS/RTR/JSR), `OperationDecoder` merely not-illegalises it. If
  Task-1 finds the assembler cannot keep line-C EXG out of the AND-RMW illegal path cleanly,
  fall back to impl (B) (an `isExg` `OpSpec` marker). Either way the 3-µop crack + reg-id
  mapping is identical.
- **A2: A plain `MOVE.L reg→An` (mkUop, size LONG) writes An full-32 with no flags and
  needs no `isMovea`.** Grounded in the RTS/RTR A7 writeback (also a full-32 reg write to
  arch-15). To be reconfirmed against `AluEuPlugin`/`AluDatapath` at Task-1.
- **A3: One temp (`T0`) suffices and `T0` is free during an EXG.** EXG is a single
  instruction; no other crack runs concurrently within it.
- **A4: The 3-µop `DecodeStage` stash path (RTR) carries an EXG unchanged** — no EXG-
  specific front-end change. To be reconfirmed at Task-1 (the stash carries `count` 1..3
  generically).
