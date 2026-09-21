# Packed-BCD add/subtract (ABCD / SBCD, register form) — design

**Date:** 2026-06-10
**Status:** approved (brainstorm) — ready for implementation plan
**Branch (to be):** `feat/abcd-sbcd`

## Goal

Implement the two 68k packed-decimal instructions **ABCD** and **SBCD** in their
**register (data-reg → data-reg)** form:

- **ABCD `1100 xxx 1 0000 0 yyy`** (line C): `Dx[7:0] := BCD(Dx + Dy + X)`.
- **SBCD `1000 xxx 1 0000 0 yyy`** (line 8): `Dx[7:0] := BCD(Dx - Dy - X)`.

`xxx`(11:9)=Dx (dst, also a source), `yyy`(2:0)=Dy (source). Bit 8 = 1, bits 7:6 = 00
(opmode 4 of the line-8/C "OR/AND" group), bits 5:4 = 00, **bit 3 = 0** selects the
**data-register form** (bit 3 = 1 would be the `-(Ay),-(Ax)` memory form — deferred).

**BYTE size only.** The op reads the **low byte** of each register, runs a binary add/sub
plus the classic **decimal-adjust** correction (add/subtract 6 on a nibble overflow, add/
subtract 0xA0 on a byte overflow), and writes the corrected BCD byte back into `Dx[7:0]`
**preserving `Dx[31:8]`** (partial-register merge). Resumes the ISA-completion track
([[isa-completion-roadmap]]); gates at **≥200 MHz** (the relaxed gate, see
[[synth-gate-every-slice]]).

## Context

The machinery is in place. `DecOp.SHIFT`/`shiftOp` is the exact template for "one op with a
sub-kind field" — ABCD/SBCD become **one `DecOp.BCD`** with a 1-bit `bcdSub` sub-kind
(0=ABCD add, 1=SBCD subtract), mirroring `shiftOp`/`shiftDir`. The decode lives in the
existing line-8/C block of `OperationDecoder` (the same `is(0x8, 0x9, 0xB, 0xC, 0xD)` switch
that already disambiguates OR/AND/ADD/SUB/EOR/DIV/MUL by opmode). The ALU EU already has
everything ABCD/SBCD need on the **fast path**: it reads `srcA`(=Dx) + `srcB`(=Dy), it reads
**X** (`s1X`, used today by NEGX), it does **byte partial-register merge** (the `Size.BYTE`
writeback path), and it implements a **clear-only Z** (NEGX's `Z := Z_old && result==0`) and
a **read-NZVC-then-write** flag mechanism (NEGX/toCcr). A new BCD **decimal-adjust datapath**
slots into `AluDatapath`'s op-mux next to the existing arithmetic; no new EU, no new IQ/ROB
port, no new operand fields.

**No new addressing machinery.** Register form only: `srcA`=Dx, `srcB`=Dy are plain
data-register reads. The memory `-(Ay),-(Ax)` form (a double-predecrement byte-RMW crack) is
deferred — it lands with the general `-(An)` addressing work (MOVEM).

## Encoding & decode disambiguation (line 8 / line C)

Both ops sit **inside the existing line-8/C "OR/AND" group** (`is(0x8, 0x9, 0xB, 0xC, 0xD)`
switch). That switch reads `opmode = opword(8 downto 6)`:

- **ABCD** = line C, opmode `100` (=4). Today opmode 4/5/6 on line C is **AND Dn,<ea> RMW**.
- **SBCD** = line 8, opmode `100` (=4). Today opmode 4/5/6 on line 8 is **OR Dn,<ea> RMW**.

The ALU-RMW path (`isRmw`, `aluRmwMemBad`) **requires a MEMSIMPLE EA** — a data-register EA
(mode 000) there is currently rejected as **illegal** (`aluRmwMemBad = isAluRmwOp &&
srcEa.klass =/= MEMSIMPLE`, `MicroOpAssembler.scala`). So the ABCD/SBCD bit patterns
presently decode to illegal. We carve them out by their fixed sub-fields, **before** the
`isRmw` test:

```
ABCD/SBCD register form: 1xx0 xxx 1 0000 0 yyy
   line (15:12) = C (ABCD) / 8 (SBCD)
   bit 8     = 1            ← opmode bit, part of opmode==4
   bits 7:6  = 00           ← opmode==4 together with bit8
   bits 5:4  = 00           ← the "00000" reg-form selector (high bits)
   bit 3     = 0            ← DATA-register form (1 = -(An) memory form, deferred)
   bits 2:0  = yyy = Dy
```

So **`isBcdReg = (line==0x8 || line==0xC) && opword(8) && opword(7 downto 3) === B"00000"`**.

Disambiguation is exact and total:
- It does **not** collide with the opmode-0/1/2 (EA→Dn) or opmode-3/7 (ADDA/.../DIV/MUL)
  forms: those have `opmode != 4`.
- It does **not** collide with the AND/OR RMW (opmode 4/5/6 to memory): the RMW path needs
  a MEMSIMPLE EA; here bits 5:3 = `000` (Dn-direct), which the RMW path rejects. We add
  `isBcdReg` as a **higher-priority** `when` so the AND/OR-RMW branch never sees it.
- It does **not** collide with EOR (line B only) — different line.
- The **memory form** `1xx0 xxx 1 0000 1 yyy` (bit 3 = 1) is explicitly **excluded** by the
  `opword(7 downto 3) === "00000"` test (bit 3 = 1 fails it) → stays illegal/deferred.

Because the BCD test is checked first and is mutually exclusive with every other line-8/C
case, the surrounding `isDivuW/isMuluW/isEor/isRmw/!isRmw` chain is left untouched (the BCD
`when` is the first branch; the rest become `.elsewhen`, OR each existing branch is gated
`&& !isBcdReg`).

## Architecture

### 1. Op + field (`DecodedUop` / `OpSpec`)

Add **`DecOp.BCD`** (next to `SHIFT`) and a 1-bit **`bcdSub`** sub-kind field
(`Bool`, default `False`), mirroring `shiftDir`. `bcdSub = False` → ABCD (add), `True` →
SBCD (subtract). Add `bcdSub` to `OpSpec` (default `False`) and thread it through
`MicroOpAssembler` into the `DecodedUop`. No other new fields: the operands reuse `srcA`
(Dx), `srcB` (Dy), `dst` (Dx); X-in/X-out reuse the existing `readsX`/`writesX`; the
NZVC read reuses `readsNzvc` (the EU needs old N/V/Z to compute the quirky N/V and the
clear-only Z).

### 2. Decode (`OperationDecoder`, line-8/C block)

Inside `is(0x8, 0x9, 0xB, 0xC, 0xD)`, **first**:
```scala
val isBcdReg = (line === 0x8 || line === 0xC) && opword(8) &&
               (opword(7 downto 3) === B"00000")            // bit3=0 -> data-reg form
when(isBcdReg) {
  o.illegal := False
  o.op    := DecOp.BCD
  o.bcdSub:= (line === 0x8)                 // line 8 = SBCD (subtract), line C = ABCD
  o.size  := Size.BYTE                       // BCD is byte-only
  o.srcA  := dnField                         // Dx (bits 11:9): dst operand + merge source
  o.srcB  := dnFieldLo                       // Dy (bits 2:0): the source byte (see O3)
  o.dst   := dnField                         // writeback to Dx (partial merge)
  o.dstWrites  := True
  o.readsNzvc  := True                       // EU needs old Z (clear-only) + computes N/V
  o.writesNzvc := True
  o.readsX     := True                       // X-in (the carry/borrow into the add/sub)
  o.writesX    := True                       // X-out := decimal carry/borrow
}
```
The existing OR/AND/ADD/SUB/CMP/EOR/DIV/MUL cases are wrapped so they only fire when
`!isBcdReg` (`isBcdReg` is the first `when`, the rest become `.elsewhen`).

**`srcB = Dy` operand source (O3).** Dy is `opword(2 downto 0)` — the EA register field — but
ABCD/SBCD's `yyy` is **always a data register**, never an EA mode. Cleanest is a **new
fixed-field operand** `dnFieldLo` (`OperandKind.REGFIELD`, reading bits **2:0** as a data
reg — mirror `dnField` which reads 11:9). The decode spec confirms whether the existing
`easrc` (EASRC, which for mode 000 resolves to `D[opword(2:0)]`) already lands the right read
— if it does, `easrc` may be reused; but `dnFieldLo` is unambiguous and avoids invoking EA
plumbing for a fixed register field. **Resolve in Task 1's decode spec.**

### 3. Assemble (`MicroOpAssembler`)

`DecOp.BCD` is a **single ALU µop**, register-direct only:
- `srcA = Dx` (read; the dst operand + the .B merge source), `srcB = Dy` (read), `dst = Dx`
  (`dstWrites = True` — both ABCD and SBCD write), `size = BYTE`.
- `readsX/writesX = True`, `readsNzvc/writesNzvc = True`.
- **No EA crack, no memory.** A BCD op with a non-Dn `yyy` cannot occur (the decode already
  fixed `yyy` to a data reg via `dnFieldLo`). Add `isBcd = spec.op === DecOp.BCD` to the
  assembler's op classification so it is NOT mistaken for the AND/OR-RMW path — i.e.
  `isAluRmwOp` must **exclude** `DecOp.BCD`, and `aluRmwMemBad` must not fire for it. The op
  routes to the **ALU cluster** (INT), latency-1 fast path.

### 4. Execute (ALU EU fast path) — the BCD decimal-adjust datapath

This is the new datapath. It is **tuned to bit-match Musashi** (the golden reference); the
lock-step is the oracle. The algorithm below is transcribed directly from Musashi's
`m68k_op_abcd_8_rr` / `m68k_op_sbcd_8_rr` (`tools/musashi/musashi/m68k_in.c`, the `rr`
register-to-register forms), so the implementer reproduces it exactly, including the
documented-as-"undefined" N and V.

Let `dx = src1[7:0]`, `dy = src2[7:0]`, `xin = X`. Work in a **wide unsigned lane** (≥10 bits;
SBCD's subtraction wraps in unsigned and must be allowed to — see notes) so the corrections
and the `>0x99` test see the **un-masked** intermediate, exactly like Musashi's `uint res`.

2026-09-22 width clarification: invalid BCD digits make the raw low sum reach
31, adjusted low sum 37 and full sum 517, so nine bits is insufficient. Ten
bits preserves the original unsigned-32 output/flag semantics over every byte
input; see `../../bcd-narrow-intermediates.md` for the bounds and validation gate.

**ABCD (add)** — from Musashi `m68k_op_abcd_8_rr`:
```
res  = (dy & 0x0f) + (dx & 0x0f) + xin       // LOW_NIBBLE(src)+LOW_NIBBLE(dst)+X  (0..0x1f)
Vraw = ~res                                   // FLAG_V = ~res   (V part I, full width)
if (res > 9)  res += 6                         // low-nibble decimal adjust
res += (dy & 0xf0) + (dx & 0xf0)               // + HIGH_NIBBLE(src)+HIGH_NIBBLE(dst)
carry = (res > 0x99)                           // decimal carry out
X = C = carry                                  // FLAG_X = FLAG_C = (res>0x99)<<8
if (carry) res -= 0xA0
V = bit7( Vraw & res )                         // FLAG_V &= res; CCR V = FLAG_V&0x80  (V part II)
N = bit7( res )                                // FLAG_N = NFLAG_8(res); CCR N = &0x80
res8 = res & 0xff                              // res = MASK_OUT_ABOVE_8(res)
Z = Z_old && (res8 == 0)                        // FLAG_Z |= res  -> CCR Z = !FLAG_Z  (CLEAR-ONLY)
Dx = (Dx & ~0xff) | res8                        // MASK_OUT_BELOW_8(Dx) | res8
```
*Ordering note (ABCD):* Musashi computes `FLAG_V &= res` and `FLAG_N = NFLAG_8(res)` **before**
`res = MASK_OUT_ABOVE_8(res)`. But N reads only bit 7 and V reads only bit 7, and after the
`-0xA0` correction `res` is already within 0..0xFF for valid carry handling; still, the
implementer should follow the exact source order (V&N off the post-correction, pre-mask `res`).

**SBCD (subtract)** — from Musashi `m68k_op_sbcd_8_rr`:
```
res  = (dx & 0x0f) - (dy & 0x0f) - xin        // may "wrap" negative in unsigned
Vraw = ~res                                    // FLAG_V = ~res   (V part I)
if (res > 9)  res -= 6                          // unsigned wrap -> res huge -> >9 -> borrow adj
res += (dx & 0xf0) - (dy & 0xf0)
borrow = (res > 0x99)                           // decimal borrow out
X = C = borrow                                  // FLAG_X = FLAG_C = (res>0x99)<<8
if (borrow) res += 0xA0
res8 = res & 0xff                               // res = MASK_OUT_ABOVE_8(res)  (BEFORE V/N here)
V = bit7( Vraw & res8 )                         // FLAG_V &= res  (res ALREADY masked for SBCD)
N = bit7( res8 )                                // FLAG_N = NFLAG_8(res)
Z = Z_old && (res8 == 0)                         // FLAG_Z |= res  (CLEAR-ONLY)
Dx = (Dx & ~0xff) | res8
```
*Ordering note (SBCD):* Musashi masks `res` to 8 bits **before** `FLAG_V &= res` / `FLAG_N`
(the source order differs from ABCD). So SBCD's V/N read the **masked** `res8`, while ABCD's
read the pre-mask `res`. **This asymmetry is real and must be preserved** (risk R1).

**Critical corner-case notes (carried verbatim from Musashi; the implementer must match):**

1. **`res > 9` uses the RAW binary nibble sum/difference, NOT a masked nibble.** For ABCD
   `res` here is `(dy&0xf)+(dx&0xf)+X` (0..0x1f), so a nibble sum above 9 (half-carry) AND
   any low-nibble value 10..15 from invalid-BCD inputs both trigger `+6`. For SBCD the
   subtraction wraps in unsigned, so a borrow yields a very large `res` which is `> 9` and
   triggers `-6`. **Do NOT pre-mask to a nibble** — that breaks the invalid-BCD and SBCD
   borrow cases.

2. **C/X = the decimal carry/borrow**, `(res > 0x99)` AFTER the high-nibble add, written to
   **both X and C**.

3. **Z is CLEAR-ONLY** (`FLAG_Z |= res`): `Z := Z_old && (res8 == 0)`. Reuse the EU's NEGX
   clear-only-Z mechanism exactly (it already reads the old Z from `s1Nzvc(2)`).

4. **N is DEFINED here as `res[7]`** (Musashi: `FLAG_N = NFLAG_8(res)` → CCR N = bit 7). It is
   "officially undefined" on real 68k, but **Musashi computes a specific value and the
   lock-step compares the full CCR byte bit-exact** → we MUST output `N = res[7]`.

5. **V is DEFINED here as `bit7((~rawsum) & res)`** (Musashi's two-part `FLAG_V`). Same story
   — "officially undefined" but Musashi computes it and the harness compares it, so we MUST
   reproduce the two-part `~rawsum & res` expression, with the ABCD/SBCD masking-order
   difference above. *This is the single most error-prone corner — risk R1.*

The result `res8` flows into the EU's existing **`Size.BYTE` writeback merge** (`s1Src1[31:8]
## res8`), so `Dx[31:8]` is preserved automatically. The X/C/N/V/Z assemble into the EU's
`finalNzvc`/`finalX` via a new `op === DecOp.BCD` branch (next to the NEGX/toCcr branches),
**overriding** the generic `AluDatapath` nzvc for BCD. Where the datapath lives is a choice:
either inside `AluDatapath.apply` (add a `bcdRes`/`bcdNzvc`/`bcdX` and mux on `DecOp.BCD`,
threading `xIn` which is already a `cmd` field), or as a small block in `AluEuPlugin`'s S1
alongside the NEGX/toCcr merges. The latter keeps `AluDatapath` purely combinational on
`cmd` and matches how toCcr is handled; **prefer the EU-side block** so the X-in/old-NZVC
reads (already present as `s1X`/`s1Nzvc`) feed it directly.

### 5. The N/V flag decision: COMPARED, not masked

The lock-step harness (`LockStep.compare`, `src/test/scala/m68k040/lockstep/LockStep.scala`)
checks the **full CCR byte bit-exact** (`c.ccr != s.ccr`) and the DUT commits all five flags
{X,N,Z,V,C} from the PRFs — there is **no per-flag masking** available in the harness.
Musashi computes specific (if architecturally "undefined") N and V for ABCD/SBCD. Therefore
**N and V are COMPARED, and the datapath MUST reproduce Musashi's exact N (`res[7]`) and V
(`bit7((~rawsum) & res)`)** — masking is not an option without changing the harness, which we
do not do. This is the decisive design point: *the BCD datapath is tuned to Musashi as the
oracle, N and V included.* (See O1 if a real-hardware-accurate "undefined" policy is ever
desired — out of scope here.)

## Testing (gates, in order)

1. **`BcdDecodeSpec`** (new) — the encoding: ABCD `0xC101` (`1100 000 1 0000 0 001`,
   `ABCD D1,D0`) and SBCD `0x8101` decode to `op=BCD`, `bcdSub` (False/True), size=BYTE,
   srcA=D0, srcB=D1, dst=D0, dstWrites, reads/writes X+NZVC. The **memory form** `0xC109`
   (`...0000 1 001`, bit3=1) and the opmode-0/3 AND/MUL forms (`0xC001` AND, `0xC0C1` MULU)
   decode to **AND/MUL, NOT BCD** (regression that the carve-out is exact). MOVEP/illegal
   patterns unaffected. (Mirror `Line4DecodeSpec`/`SccDecodeSpec` structure.)

2. **Lock-step vs Musashi** — the matrix (Musashi is the oracle for the exact corrections +
   the quirky N/V). Seed Dx/Dy with `move.l #...,%dN`, pre-set X via a prior carry-producing
   op (e.g. an `add` that overflows, or `move #...,%ccr` if the assembler supports it), then
   ABCD/SBCD; compare regs + **full CCR** step-for-step:
   - **Digit-pair sweep**, X=0 and X=1: `(dx_lo, dy_lo)` over
     `{00,01,09,10,45,50,55,99}` × `{00,01,05,09,50,55,99}` — no-carry, low-nibble half-carry
     (`05+06`, `09+01`), high-nibble carry (`50+50`, `90+10`), full carry (`99+01`, `99+99`),
     and the `+X` ripple.
   - **SBCD borrow cases**: `00-01`, `10-01` (low borrow → `-6`), `00-99`, `50-99` (high
     borrow → `+0xA0`), with X=0/1.
   - **Upper-byte preservation**: Dx = `0x11223344`, BCD on the low byte → assert `Dx[31:8]`
     unchanged.
   - **Z clear-only**: pre-set Z=1, then a BCD whose result is non-zero (Z must clear) AND a
     two-byte chain whose results are both 0 (Z stays set only if it was set AND all results
     0) — mirror the NEGX Z test.
   - **N/V exact-match**: a case driving N=1 (result bit7 set, e.g. produces `0x80`+) and a
     case designed to make V differ between the naive (V=0) and Musashi (`~rawsum & res`)
     formula. Since the harness compares the full CCR, ANY N/V mismatch fails — these are
     implicitly covered by every case, but include at least one explicit canary each.
   - **Multi-byte BCD chain**: a two-/four-byte packed-BCD add and subtract using X to ripple
     the carry between bytes (`abcd` of low bytes, then `abcd` of the next bytes reading the
     rippled X) — the canonical use of ABCD — compared end-to-end.
   - **Invalid-BCD inputs** (low nibble `0xF`, byte `0xFF`): Musashi has specific behavior;
     include a few so the un-masked `>9` / `>0x99` handling matches.

3. **`make test-fast`** + the decode regression — green (the new `BcdDecodeSpec` included; no
   regression in the line-8/C AND/OR/ADD/SUB/EOR/DIV/MUL decode).

4. **≥200 MHz full-core synth gate** — reported (relaxed gate). The BCD decimal-adjust is a
   handful of small adders + two comparators on the byte lane — shallower than the barrel
   shifter — so the fast path should hold. If a BCD cone is the new worst path AND < 200,
   move BCD to the slow ALU path (like SHIFT) and re-gate.

## Non-goals

- **Memory form `ABCD/SBCD -(Ay),-(Ax)`** (bit 3 = 1) — a double-predecrement byte-RMW
  crack; deferred to the general `-(An)`/`(An)+` addressing work (MOVEM).
- **NBCD memory-EA forms** (`0100 1000 00 mmmrrr`, line 4) — the BCD negate's RMW
  forms remain a separate microcode slice. The later register-only slice has landed:
  `NBCD Dn` is exactly `0x4800–0x4807`, reuses this datapath with `dst=0`, and predecodes
  as simple/one-word; do not widen that rule to the deferred memory partition.
- **PACK/UNPK** (68020 BCD pack/unpack) — separate, later.

## Risks

- **R1 — the quirky V (`~rawsum & res`), and the ABCD-vs-SBCD masking-order difference.**
  Musashi computes V from the un-masked raw binary sum AND'd with the corrected `res`
  (un-masked for ABCD, masked for SBCD). A naive "V=0" or a wrong masking order silently
  diverges on V. Mitigated by transcribing the C exactly and by V-targeted lock-step cases;
  the full-CCR bit-exact compare catches any mismatch. **The implementer MUST trace the exact
  Musashi ordering — do not simplify.**
- **R2 — N defined as `res[7]`, not 0.** Same class as R1 (officially undefined, but
  compared). Covered by an N=1 case + the full-CCR compare.
- **R3 — `>9` / `>0x99` on the RAW (un-masked) intermediate.** Pre-masking to a nibble/byte
  breaks the half-carry, invalid-BCD, and SBCD-borrow cases. Mitigated by the wide-lane
  datapath + the borrow/invalid-BCD lock-step cases.
- **R4 — Z clear-only.** Must AND with old Z and never set Z; reuse the NEGX mechanism
  exactly. Covered by the Z-clear-only chain case.
- **R5 — decode carve-out.** ABCD/SBCD must be peeled off the line-8/C AND/OR-RMW path (which
  currently makes their pattern illegal) WITHOUT disturbing AND/OR/ADD/SUB/EOR/DIV/MUL or the
  memory-RMW crack. Covered by `BcdDecodeSpec`'s positive (BCD) and negative (AND/MUL still
  decode normally; memory-form bit3=1 stays illegal) assertions.
- **R6 — X both in and out.** ABCD/SBCD read X as the operand AND write X = the decimal
  carry; the EU must read X (`s1X`) before computing and write the new X. Reuses NEGX's
  `readsX` + the X writeback; covered by the X=0/1 sweep and the multi-byte ripple chain.

## Open questions / assumptions

- **O1 — N/V are "undefined" on real 68040 but Musashi computes specific values.** We match
  Musashi (compared, not masked) because the lock-step harness compares the full CCR byte and
  has no masking hook. Assumption: matching Musashi is the project's definition of correct
  (consistent with every other slice). If a future goal is bit-accurate-to-silicon
  "undefined" behavior, that would require a harness change (a per-instruction flag mask) —
  out of scope.
- **O2 — exact Musashi V/N ordering.** The ABCD-vs-SBCD masking-order asymmetry (`FLAG_V &=
  res` before vs after `MASK_OUT_ABOVE_8`) is transcribed from the current vendored Musashi
  (`tools/musashi/musashi/m68k_in.c`, lines ~941 ABCD / ~9361 SBCD). The implementer should
  re-read those two functions verbatim while coding the datapath and let the lock-step (not
  this prose) be the final arbiter — flagged so any subtle bit (e.g. whether V's `~res` is
  9-bit or wider before `&`) is resolved against the source, not assumed.
- **O3 — `srcB = Dy` operand plumbing.** Whether to reuse `easrc` (EASRC mode-000 →
  `D[opword(2:0)]`) or add a dedicated `dnFieldLo` (REGFIELD on bits 2:0). The spec proposes
  `dnFieldLo` for clarity; Task 1's decode spec confirms which lands the correct read and
  the plan picks one. Assumption: a fixed-field data-register read of bits 2:0 is trivial to
  add (mirrors `dnField`).
- **O4 — X pre-set in lock-step.** Pre-setting X to drive `xin=1` requires a prior op that
  writes X (an overflowing `add`, or `move #..,%ccr` if `ProgramAssembler`/Musashi accept it).
  Assumption: the existing harness can express this (the NEGX / extended-arith tests already
  pre-set X) — confirm the exact mnemonic in Task 4.
