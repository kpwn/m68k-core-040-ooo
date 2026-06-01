# ALU Datapath (Execute Slice 1) — Design

**Status:** Draft for review
**Date:** 2026-06-02
**Parent spec:** `.../specs/2026-05-31-m68k-040-ooo-architecture-design.md` (ch 5 execute clusters, ch 4.6 split CCR; invariant #1 lock-step correctness, #2 FMax).
**Reference studied:** NaxRiscv `execute/SrcPlugin.scala` (shared add/sub adder, source select) + `execute/IntAluPlugin.scala` (ALU_CTRL result mux). We adapt the combinational core and add 68k condition-code generation (NaxRiscv has none).
**Builds on:** decode (`DecOp`, `Size`), rename (`RenamedUop` carries `writesNzvc/writesX/readsX`), the Musashi oracle harness (`m68k040.oracle.{Musashi, ProgramAssembler}`).
**Slice position:** first piece of the execute side. A pure combinational ALU datapath, the part that lock-step exists to validate. Slice 2 = the three PRFs; Slice 3 = full NaxRiscv-style IssueQueue + two ALU EUs + wakeup/bypass + ROB completion + CommitTrace.

---

## 1. Purpose

Compute the integer result and the 68k condition codes (NZVC + X) for the simple ALU operations our decode emits, as a **pure combinational unit** with no PRF/scheduler dependency. This isolates the 68k-specific flag semantics — the highest-correctness-risk logic in the core — so it can be verified directly against Musashi at the value/flag level before any pipeline is built.

## 2. Scope

**In:** a combinational `AluDatapath` taking `op, size, src1, src2, xIn` and producing `result, nzvc, xOut`. Operations: `MOVE, ADD, SUB, AND, OR, CMP` (the set `SimpleDecodeUnit` emits) × sizes `B/W/L`. A shared add/sub adder (NaxRiscv carry-trick) feeding ADD/SUB/CMP; a bitwise unit (AND/OR); a move path; and a per-op×size flag generator. Verification against Musashi single-instruction lock-step + directed corner cases + randomized sweep.

**Out:** operand selection from PRF/immediate/bypass (Slice 3 EU); sub-word destination merge / write masking (Slice 3 writeback); shifts/rotates, ADDX/SUBX/ABCD, MULU/DIVU, address-register arithmetic side-effects — none are emitted by simple decode yet. No clock, no state, no ports beyond the combinational in/out bundles.

## 3. Component

`m68k040.execute.AluDatapath` — a combinational helper (an `Area`/function, instantiable inside a stage), with two payload bundles:

```
case class AluCmd() extends Bundle {
  val op    = DecOp()        // MOVE/ADD/SUB/AND/OR/CMP (others -> result/flags don't-care)
  val size  = Size()         // B/W/L
  val src1  = Bits(32 bits)  // = dst operand (SUB/CMP compute src1 - src2)
  val src2  = Bits(32 bits)  // = src operand
  val xIn   = Bool()         // current X (for future ADDX; unused by this op set, plumbed for Slice 3)
}
case class AluRsp() extends Bundle {
  val result = Bits(32 bits) // low `size` bits valid; upper bits are raw adder output (writeback merges)
  val nzvc   = Bits(4 bits)  // {N,Z,V,C} — bit3=N, bit2=Z, bit1=V, bit0=C
  val xOut   = Bool()        // computed X (= C for ADD/SUB); for non-X-writers, value is don't-care (write gated by writesX)
}
def apply(cmd: AluCmd): AluRsp
```

The datapath **always computes** NZVC and xOut; whether they are committed is decided downstream by `writesNzvc`/`writesX` (rename). Likewise `result` is always produced; whether it is written (and merged at sub-word size) is the EU's job. This keeps the datapath a clean total function.

### 3.1 Internal structure (NaxRiscv-faithful)
- **Adder** (shared by ADD/SUB/CMP): `sub = (op === SUB || op === CMP)`; `b2 = sub ? ~src2 : src2`; `full = src1.zext + b2.zext + sub` over 33 bits. `carryOut` = `full(32)` taken at the **size boundary** (bit 8/16/32). For SUB/CMP the 68k carry is a **borrow** = `!carryOut`.
- **Bitwise**: `src1 & src2` (AND), `src1 | src2` (OR).
- **Move**: `result = src2` (the moved value; MOVEQ's sign-extension is done in decode's `imm`, so src2 already carries the 32-bit value).
- **Result mux** by `op`: MOVE→src2; ADD/SUB→adder sum (low bits); AND/OR→bitwise; CMP→adder sum (used only for flags; result marked don't-care for write).

### 3.2 Flag generation (per op, evaluated at `size`; Musashi is the final arbiter)
Let `w ∈ {8,16,32}` from `size`, `r = result(w-1 downto 0)`, msb index `w-1`.
- **N** = `r(w-1)`.
- **Z** = `r === 0`.
- **MOVE, AND, OR:** N, Z as above; **V = 0; C = 0; X unchanged** (xOut = xIn; not written).
- **ADD:** N, Z; **C** = carry-out at bit `w` (unsigned carry); **V** = signed overflow `= (src1(w-1) === src2(w-1)) && (r(w-1) =/= src1(w-1))`; **X = C**.
- **SUB, CMP:** N, Z; **C** = borrow `= !carryOut` at bit `w`; **V** = signed overflow `= (src1(w-1) =/= src2(w-1)) && (r(w-1) =/= src1(w-1))`; for **SUB X = C**, for **CMP X unchanged** (not written).

`nzvc := N ## Z ## V ## C`. These formulas are the implementation target; **any discrepancy with Musashi is resolved in Musashi's favor** and the formula corrected (the exhaustive/randomized sweep is the gate).

## 4. Data flow
`(op,size,src1,src2,xIn) → [adder | bitwise | move] → result mux → flag generator → (result, nzvc, xOut)`. Purely combinational; no stage boundary. In Slice 3 the EU drives `src1/src2` from PRF-read/bypass/immediate and routes `result→int PRF`, `nzvc→NZVC PRF`, `xOut→X PRF` under the rename write masks.

## 5. Verification

Oracle: **Musashi single-instruction lock-step** (`m68k040.oracle.{Musashi, ProgramAssembler}`). For each op×size:
1. Assemble the corresponding 68k instruction operating reg→reg (e.g. `ADD.B D1,D0` → D0 = D0 + D1) with a chosen initial D0 (=src1), D1 (=src2), and initial CCR (for X-in coverage).
2. Single-step Musashi; read the result register and post CCR.
3. Feed the identical `op,size,src1,src2,xIn` to `AluDatapath`; assert: `result` low-`size` bits == Musashi's result low-`size` bits, AND the **written** flag bits (per the op's NZVC/X write rule) == Musashi's CCR bits. (Unwritten flags are not compared, matching the write masks.)

Test set per op×size:
- **Directed corner cases:** zero result; sign boundary (`0x7F`+1, `0x80`−1 at byte; analogous W/L); unsigned carry (`0xFF`+1); overflow (pos+pos→neg, neg−pos→pos); equal operands (CMP → Z); X-in 0 and 1.
- **Randomized sweep:** N random `(src1,src2,ccrIn)` per op×size (e.g. 200 each), all checked against Musashi.

Pure-Scala unit checks (non-oracle) for the bundle/encoding are optional; the Musashi sweep is the gate. A `fastTest`-tagged subset (small directed set, no Verilator) keeps the fast suite quick; the full randomized sweep runs under the Musashi-bearing suite.

## 6. Files

| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/execute/AluDatapath.scala` | `AluCmd`/`AluRsp` bundles + combinational ALU (adder, bitwise, move, result mux, flag generator) |
| `src/test/scala/m68k040/execute/AluDatapathSpec.scala` | directed corner cases + randomized sweep vs Musashi, per op×size |

## 7. Open items / deferrals (logged)
- ADDX/SUBX (read X), shifts/rotates, MUL/DIV, BCD — not emitted by simple decode; `xIn` is plumbed but unexercised this slice.
- Sub-word destination merge (MOVE.B preserving upper 24 bits) and flag write-masking are Slice 3 (EU writeback) — the datapath only produces low-`size` result + computed flags.
- Address-register operations (ADDA/CMPA: no/partial flag effects) suppressed via rename write masks downstream; the datapath needs no special case.
- If Musashi reveals a flag formula error (esp. V/C at sub-word sizes, or CMP borrow), correct the formula here; that is the expected outcome of building it oracle-first.
