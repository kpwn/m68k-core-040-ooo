# Extended add/subtract (ADDX / SUBX, REGISTER form) — design

**Date:** 2026-06-10
**Status:** approved (brainstorm) — ready for implementation plan
**Branch (to be):** `design/addx-subx` (implementation on a `feat/addx-subx` worktree)

## Goal

Implement the **register form** of the 68k extended-arithmetic instructions **ADDX
Dy,Dx** and **SUBX Dy,Dx**:

- **ADDX** `1101 xxx 1 ss 00 0 yyy` (line D): `Dx := Dx + Dy + X`.
- **SUBX** `1001 xxx 1 ss 00 0 yyy` (line 9): `Dx := Dx - Dy - X`.

These are the multi-precision building blocks: the **X** (extend) bit carries the
carry/borrow from one limb into the next, so a chain of ADDX/SUBX adds/subtracts integers
wider than 32 bits. The defining quirk is the **clear-only Z**: `Z := Z_old && (result ==
0)` — a zero limb only *keeps* a prior `Z=0`, it never *sets* Z. The core already
implements exactly this rule for **NEGX**; ADDX/SUBX reuse it verbatim. Resumes the
ISA-completion track ([[isa-completion-roadmap]]); gates at **≥200 MHz** (the relaxed gate,
see [[synth-gate-every-slice]]).

## Context

The datapath is already in place. `AluDatapath` runs ADD/SUB/CMP/NEG/NEGX through **one
adder** (`sum32 = aEff + bEff + cin`), and it already has an **X carry-in** (`cmd.xIn`,
fed by the EU's `s1X` from the X-register read port) which NEGX uses as `cin := !cmd.xIn`
to compute `0 - Dn - X`. The EU already implements the **clear-only Z** (`negxZ =
rsp.nzvc(2) && s1Nzvc(2)`, gated by the µop reading the old NZVC). And the EU already does
**size-merged writeback** for `.B`/`.W` (preserve the upper bits of the destination data
register). ADDX/SUBX need **no new EU machinery** — they are `a + b + X` / `a - b - X`,
i.e. ordinary ADD/SUB with X folded into the carry-in and the NEGX Z-clear applied.

The only genuinely new work is **decode disambiguation**: ADDX shares line D with ADD and
SUBX shares line 9 with SUB, and the ADDX/SUBX opword sits inside the *RMW opmode* space
(bit8=1) that the decoder currently routes to the "ADD/SUB Dn,<ea>" memory-destination
form. The register-form ADDX/SUBX must be carved out *without disturbing* the existing
ADD/SUB (`<ea>,Dn`), ADDA/SUBA, and the deferred ADD/SUB-to-memory RMW decode.

## Encoding (lines 9 and D)

ADDX (line D) / SUBX (line 9) register form:

```
 15 14 13 12 | 11 10  9 |  8 |  7  6 |  5  4 |  3 |  2  1  0
   1  1  0  1 |   x  x  x |  1 |  s  s |  0  0 |  0 |  y  y  y     ADDX Dy,Dx
   1  0  0  1 |   x  x  x |  1 |  s  s |  0  0 |  0 |  y  y  y     SUBX Dy,Dx
```

- `xxx` (bits 11:9) = **Dx** — the destination data register (read as the addend/minuend
  `a`, written back).
- `yyy` (bits 2:0) = **Dy** — the source data register (the addend/subtrahend `b`).
- `ss` (bits 7:6) = size: `00`=.B, `01`=.W, `10`=.L (`11` illegal).
- bits 5:4 = `00`, bit 3 = `0` → the EA field (bits 5:0) is `000 yyy` = **Dn-direct, reg
  Dy**. This is the data-register (R/M = 0) form.
- bit 8 = `1`.

**Key disambiguation fact:** the register-form ADDX/SUBX is exactly *"line D/9, bit8=1,
ss∈{.B,.W,.L}, EA mode field (bits 5:3) == 000 (Dn-direct)"*. The existing decoder routes
*"line D/9, bit8=1 (RMW opmode 4/5/6)"* to the ADD/SUB-to-memory RMW form, where the EA is
the destination; but a **Dn-direct EA in that RMW slot is not a valid ADD/SUB encoding —
it IS ADDX/SUBX**. So the carve-out is: in the line-8/9/C/D block, when `isRmw` AND `line
∈ {0x9, 0xD}` AND the EA mode field `opword(5 downto 3) === 000`, decode ADDX/SUBX instead
of the (illegal-for-Dn-direct) memory RMW. Everything else in that block is untouched.

### Out of scope (noted, routed to existing illegal/deferred)

- **Memory form** `ADDX -(Ay),-(Ax)` / `SUBX -(Ay),-(Ax)` (`… 1 ss 00 1 yyy`, bit3=1):
  needs **predecrement** addressing, which is deferred with MOVEM. Bit3=1 here makes the
  EA mode field 001 (An-direct in the RMW slot) → `eaMode ≠ 000`, so the ADDX carve-out
  does NOT fire; it stays on the existing RMW path where the assembler's non-MEMSIMPLE
  gate rejects An-direct → illegal. So it is harmlessly rejected, not mis-decoded.
- ABCD/SBCD (the BCD cousins, lines C/8 `… 1 0000 yyy`) are a *separate* slice — they
  share the same `… 1 00 0 yyy` shape but in lines C/8 (the AND/OR group), so the
  line-9/D carve-out does not touch them.

## Architecture

### 1. Op marker (`DecodedUop` / `OpSpec`)

ADDX = ADD with X folded into the carry-in; SUBX = SUB with X folded into the borrow-in,
*plus* the NEGX clear-only-Z. Two viable shapes:

- **(Recommended) New `DecOp.ADDX` / `DecOp.SUBX`.** Mirrors how NEGX is its own `DecOp`
  (NEGX is *not* "NEG + a flag"; it is a distinct op precisely because of the X carry-in
  and the clear-only Z). A dedicated op keeps the datapath mux + the EU's Z-clear gate
  trivially selectable (`op === ADDX || op === SUBX`) and keeps the decode self-documenting.
  No new µop *field* is needed — the X-in path (`cmd.xIn`/`readsX`) and the old-NZVC read
  (`readsNzvc`) already exist for NEGX.
- (Rejected) Reuse `DecOp.ADD`/`DecOp.SUB` + a new `withX` marker bit. Saves two enum
  elements but spreads ADDX semantics across `op` + a flag in three files (datapath cin,
  EU Z-clear, decode), and risks an ordinary ADD/SUB accidentally inheriting the Z-clear
  if the flag is mis-defaulted. The dedicated-op shape is the lower-risk match to NEGX.

This design uses **new `DecOp.ADDX` / `DecOp.SUBX`** (next to `NEGX` in the enum). No new
`DecodedUop`/`OpSpec` field.

### 2. Decode (`OperationDecoder`, the `is(0x8,0x9,0xB,0xC,0xD)` block)

In the existing line-8/9/C/D block, `opmode = opword(8 downto 6)`, `isRmw = opmode ∈
{4,5,6}`. Add an ADDX/SUBX detector **before** the `isEor`/`isRmw` cases (so it wins the
mode-000 RMW slot):

```scala
val eaMode = opword(5 downto 3)
val isAddxSubx = isRmw && (line === 0x9 || line === 0xD) && (eaMode === U"000")
```

When `isAddxSubx`:
```scala
o.illegal := False
o.op   := Mux(line === 0xD, DecOp.ADDX, DecOp.SUBX)
o.srcA := dnField          // Dx (bits 11:9) — the dst operand `a` (read + written)
o.srcB := easrc            // EA = 000yyy -> EaDecoder yields DATAREG Dy = the source `b`
o.dst  := dnField          // writeback to Dx
o.dstWrites := True
// size from opmode 4/5/6 = .B/.W/.L (mirror the RMW size decode):
when(opmode === 4) { o.size := Size.BYTE }
  .elsewhen(opmode === 5) { o.size := Size.WORD }
  .otherwise { o.size := Size.LONG }
o.writesNzvc := True; o.writesX := True     // NZVC + X (X = carry/borrow out)
o.readsX     := True                        // X carry/borrow IN (like NEGX)
o.readsNzvc  := True                        // old Z, for the clear-only-Z merge (like NEGX)
```

**Why `srcB := easrc` resolves to Dy with no new operand kind:** the ADDX EA field is
`000 yyy` (mode 000, reg yyy); `EaDecoder.decode` already maps mode 000 → `EaClass.DATAREG,
reg = yyy`, and `MicroOpAssembler`'s EASRC srcB slot routes a DATAREG EA straight to that
register. So srcB naturally becomes **Dy** (bits 2:0) without inventing a "REGFIELD-low"
operand. srcA stays `dnField` = **Dx** (bits 11:9) — the existing `EA -> Dn` operand
shape, identical to the `opmode 0/1/2` ADD/SUB case (`srcA := dnField; srcB := easrc; dst
:= dnField`).

This carve-out **does not disturb** any existing decode:
- ADD/SUB `<ea>,Dn` (bit8=0, opmode 0/1/2) — different opmode, untouched.
- ADDA/SUBA (opmode 3/7) — `!isRmw`, untouched.
- ADD/SUB-to-memory RMW (opmode 4/5/6 with a *memory* EA, mode field ≠ 000) — `eaMode ≠
  000`, so `isAddxSubx` is false; the existing `isRmw` branch still handles it.
- EOR / MUL / DIV (line B / lines 8,C opmode 3,7) — not line 9/D RMW, untouched.

### 3. Assemble (`MicroOpAssembler`)

ADDX/SUBX register form is a **single ALU µop**, no crack:
- srcA = Dx (REGFIELD bits 11:9), srcB = Dy (EASRC → DATAREG Dy), dst = Dx.
- The EASRC srcB resolving to a DATAREG falls straight through the existing EASRC routing
  (`srcIsReg` → `srcEa.reg`); there is **no** memSimple/crack path (mode 000 is DATAREG,
  not MEMSIMPLE), so none of the `crackLoad`/`crackRmw`/`crackStore` predicates fire.
- `readsNzvc`/`readsX`/`writesNzvc`/`writesX` propagate from the OpSpec exactly as NEGX's
  do (the assembler already copies `spec.readsNzvc/readsX/writesNzvc/writesX` into the
  µop). The EU's X-source read port (`pXSrc`) and NZVC-source read port (`pNzvcSrc`) get
  renamed because `readsX`/`readsNzvc` are set — the same plumbing NEGX uses.

No assembler change should be needed **beyond** confirming the ADDX/SUBX ops are not
accidentally swept into a Dn-only/line-4-unary or mem-RMW predicate (they aren't: ADDX is
a line-9/D op with a DATAREG EA, so `srcIsMem`/`memDest`/`isLine4Unary` are all false). If
any predicate enumerates ops by `DecOp` and a new op needs inclusion/exclusion, add
ADDX/SUBX there; otherwise the assembler is unchanged.

### 4. Execute (ALU EU, fast path)

ADDX/SUBX run on the **fast (latency-1) ALU path**, like ADD/SUB/NEGX. Two small additions
to the shared adder + the EU Z-merge:

**`AluDatapath` — fold X into the adder carry-in and pick sub/add:**
- Today: `isSub = op∈{SUB,CMP}`, `isNeg`, `isNegx`; `aEff/bEff/cin` are derived from those.
- Extend the predicates so ADDX behaves like ADD-with-carry-in-X and SUBX like
  SUB-with-borrow-in-X. The operands are the ordinary two-operand shape (`a = src1 = Dx`,
  `b = src2 = Dy`), unlike NEG/NEGX which force `aEff = 0`:
  - `isAddx = op === ADDX`, `isSubx = op === SUBX`.
  - `isArith := ADD || SUB || CMP || NEG || NEGX || ADDX || SUBX`.
  - `aEff := a` for ADDX/SUBX (the normal two-operand `a`; only NEG/NEGX force 0).
  - `bEff`: fold SUBX into the existing `~b` (subtract) select and ADDX into the `b` (add)
    select: `bEff := Mux(isNeg || isNegx, ~a, Mux(isSub || isSubx, ~b, b))`.
  - **`cin`** carries X:
    - ADDX: `cin := xIn` (so `a + b + X`).
    - SUBX: `cin := !xIn` (so `a + ~b + (1 - X) = a - b - X`, the borrow form — exactly
      how NEGX uses `!xIn`).
    - Existing: SUB/NEG `cin := 1`, NEGX `cin := !xIn`, ADD `cin := 0` — unchanged.
    - Concretely: `cin := Mux(isNegx || isSubx, !xIn, Mux(isSub || isNeg, True, isAddx &&
      xIn))` (ADDX contributes `xIn`; plain ADD stays 0).
  - The result mux `default -> sum32` already covers ADDX/SUBX (they are arithmetic);
    `cFlag`/`vFlag`/`nGen`/`zGen` from `piece(w)` already compute the right C, V, N, raw Z
    at the operation size *provided* the borrow-vs-carry select includes SUBX:
    `cFlag := Mux(isArith, Mux(isSub || isNeg || isNegx || isSubx, ~cout, cout), False)`
    (SUBX = borrow `~cout`; ADDX = carry `cout`). `vFlag := Mux(isArith, vArith, False)`
    already covers both. `xOut := cFlag` (X := carry/borrow out) is already correct.

**`AluEuPlugin` — clear-only Z (reuse NEGX's exact merge):**
- Today: `isNegx → negxZ = rsp.nzvc(2) && s1Nzvc(2)` (raw Z AND old Z), and
  `aluNzvc = Mux(isNegx, negxNzvc, rsp.nzvc)`.
- Generalize the guard to the **extended family**: `isExtended = isNegx || (u1.op ===
  DecOp.ADDX) || (u1.op === DecOp.SUBX)`. For all three, `Z := rawZ && oldZ` (clear-only);
  N/V/C come from the datapath unchanged; X = `rsp.xOut` (carry/borrow out). I.e. rename
  `negxZ`/`negxNzvc` to an `extZ`/`extNzvc` computed for `isExtended` and select with it.
  (The old NZVC is `s1Nzvc`, valid because `readsNzvc` is set on these µops.)
- **Size-merge** of the int writeback is already correct: ADDX/SUBX write Dx, the merge
  source is `s1Src1 = srcA = Dx`, so `.B`/`.W` preserve Dx's upper bits exactly like every
  other Dn-dest ALU op. `.L` writes the full 32 bits.
- ADDX/SUBX are **not** `isSlow` (only SHIFT is), so they stay latency-1 and need no slow
  ports / dynamic wakeup.

## Testing (gates, in order)

1. **`AddxSubxDecodeSpec`** (new, mirror `Line4DecodeSpec`/`BitOpDecodeSpec` — pure
   decoder/assembler unit test, no full core). Assert for representative opwords:
   - `ADDX D2,D3` .L = `0xD782` (`1101 011 1 10 000 010`): op=ADDX, size=LONG, srcA=D3
     (Dx, bits 11:9), srcB=D2 (Dy via EASRC mode-000), dst=D3, dstWrites,
     readsX/readsNzvc/writesX/writesNzvc all set.
   - `SUBX D0,D1` .B = `0x9300` (`1001 001 1 00 000 000`): op=SUBX, size=BYTE, srcA=D1,
     srcB=D0.
   - `ADDX D5,D5` .W = `0xDB45` (`1101 101 1 01 000 101`): srcA=srcB=D5 (Dx==Dy).
   - **Negative guards (untouched):** `ADD.L D2,D3` (`<ea>,Dn`, `0xD682`, bit8=0) still
     decodes ADD (NOT ADDX); `ADDA.L A2,A3` (opmode 7) still ADDA; the memory-form
     `ADDX -(A2),-(A3)` (`0xD78A`, bit3=1 → EA mode field 001) routes to illegal/deferred
     (NOT ADDX, NOT a wrong ADD).

2. **Lock-step vs Musashi** (`ExecuteLockStepSpec`, mirror the NEGX / ADD lock-step
   cases — assemble a short program, run DUT vs Musashi, compare regs + CCR). The matrix:
   - **{ADDX, SUBX} × {.B, .W, .L} × {X=0, X=1}.** Seed X via a prior flag-setter (e.g. a
     `move #…,%ccr` / an `addq` that produces a known carry) and seed Dx/Dy with `move.l`.
     Verify the result, **X = carry/borrow out**, N (result MSB at size), V (overflow), C.
   - **Multi-precision carry/borrow chain:** two back-to-back ADDX limbs (low limb sets X,
     high limb consumes it) on a 64-bit add `(Dx:Dlo) + (Dy:Dylo)` — and the SUBX twin —
     to verify X propagates between limbs. Use values where the low limb overflows (X=1
     into the high limb) AND a case where it does not (X=0).
   - **Clear-only Z across a zero result with Z preceding = 0:** set `Z=0` (e.g. a prior
     op with a non-zero result), then an ADDX/SUBX whose result == 0; assert **Z stays 0**
     (NOT set to 1). Mirror with `Z=1` preceding + a zero result → Z stays 1; and a
     non-zero result → Z clears to 0. This is the NEGX clear-only rule applied to ADDX/SUBX.
   - **Overflow edges:** `.B` `0x7F + 1` (signed overflow V=1, N=1), `.B` `0x80 - 1` /
     SUBX borrow edges, `.L` `0x7FFFFFFF + 0 + X(=1)` (V on the X-induced increment),
     `.W` `0x8000` boundaries.
   - **Upper-byte preservation:** a `.B`/`.W` ADDX/SUBX must leave Dx's upper bits intact
     (size-merge) — seed Dx with a non-zero upper half and assert it survives.

3. **`make test-fast`** + the existing decode/lock-step regression — green (no ADD/SUB/
   ADDA/NEGX regression from the new line-9/D carve-out).

4. **≥200 MHz full-core synth gate** — reported (the ADDX/SUBX additions are a couple of
   mux terms on the existing adder + the existing Z-merge; no new deep cone, so the fast
   path holds). If a new worst path appears < 200, report it.

## Non-goals

- **Memory form** `ADDX/SUBX -(Ay),-(Ax)` — needs predecrement addressing; deferred with
  MOVEM. (Routed to illegal/deferred here, harmlessly.)
- **ABCD / SBCD** (BCD extended add/sub, lines C/8) — separate slice.
- No new addressing modes, no new EU latency class (ADDX/SUBX are fast/lat-1).

## Risks

- **Decode carve-out collision** — the register ADDX/SUBX slot (line 9/D, bit8=1, EA mode
  000) sits *inside* the ADD/SUB-to-memory RMW opmode space. A too-broad detector would
  steal a legitimate ADD/SUB-to-memory RMW (mode ≠ 000) or, conversely, a too-narrow one
  would let a Dn-direct RMW (which is *not* valid ADD/SUB) mis-decode as an ADD writing
  Dy. Mitigated by keying strictly on `eaMode === 000` and by the negative-guard decode
  cases (ADD `<ea>,Dn`, ADDA, ADDX-mem all asserted to NOT become register ADDX).
- **X carry-in polarity** — ADDX is `+X` (`cin := xIn`), SUBX is `-X` (`cin := !xIn`, the
  borrow form). A swapped polarity is a silent off-by-one. Covered by the X=0 vs X=1
  lock-step cases and the multi-precision chain.
- **Clear-only Z** — must AND with the old Z (never set Z on a zero limb when Z was 0). A
  raw-Z write would break multi-precision compares. Mitigated by reusing NEGX's exact
  `rawZ && oldZ` merge (generalized to the extended family) and the dedicated Z-preceding-0
  lock-step case.
- **Size-merge** — `.B`/`.W` must preserve Dx's upper bits; the merge source is srcA=Dx,
  which is already correct, but the test asserts it explicitly (a non-zero upper half
  survives).
- **Flag-select inclusion** — the datapath C borrow-vs-carry select currently branches on
  `isSub`/`isNeg`/`isNegx`; SUBX (borrow) must be added and ADDX (carry) must NOT be. A
  missed inclusion shows as wrong C/X on the lock-step borrow edges.
