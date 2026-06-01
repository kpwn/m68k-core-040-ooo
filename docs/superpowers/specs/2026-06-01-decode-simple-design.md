# Decode Sub-Slice 1 — Register/Immediate Simple Decode — Design

**Status:** Draft for review
**Date:** 2026-06-01
**Parent spec:** `docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md` (ch 2 Decode & µop expansion; invariants #2 FMax, #3 plugin boundaries)
**Builds on:** merged frontend — I-cache, predecode-on-miss, fetch/align (`DecodeFeedService` 2-wide `DecodePacket` stream).
**Slice position:** first of the decode sub-slices. Decode is a large subsystem; this establishes the contract + the two-unit 2-wide structure and decodes the register/immediate simple subset.

---

## 1. Idea & decomposition

m68k decode is a large subsystem (the 030's decoder was ~3,800 lines). It is decomposed into sub-slices:
- **Sub-slice 1 (this):** the `DecodedUop` contract + **two parallel `SimpleDecodeUnit`s** (2-wide) decoding the **register/immediate** simple subset → one µop each. Plus the service-port-directionality cleanup logged by the fetch/align review.
- **Sub-slice 2 (later):** memory-EA simple decode — the same units extended to emit load/store µops for simple addressing modes.
- **Sub-slice 3 (later):** a **third `ComplexDecodeUnit`** (microcode ROM) for complex instructions, which also drives the fetch/align `resume` port.

This mirrors the frontend's structure: predecode classifies `simple`/`complex`; the simple decode units handle what simple-predecode covers; a complex unit handles the rest.

## 2. Scope

**In this sub-slice:**
- `DecodedUop` bundle — the pre-rename µop (architectural operands).
- `SimpleDecodeUnit` — combinational `DecodePacket → DecodedUop` for the register/immediate forms (no memory operands). Two instances for 2-wide.
- `DecodeStage` (FiberPlugin) — consumes `DecodeFeedService.feed`, runs the two units, emits a 2-wide `DecodeUopService` (`Stream(Vec(DecodedUop,2))`); backpressures the feed.
- **Service-port-directionality cleanup** (the fetch/align follow-ups): make `FetchService`/`DecodeFeedService`/`DecodeUopService` ports plain `Stream`/`Flow` wires (no `slave`/`master` on the plugin-to-plugin services → drop `setAsDirectionLess()`); remove the `complexPending` 1-cycle bubble in `FetchAlignPlugin` (emit-once already holds via the `stalled` latch).

**Out of scope (later sub-slices / stages):**
- Memory-EA decode + load/store µops (sub-slice 2).
- Complex/microcode decode + `resume` driver (sub-slice 3).
- Rename (consumes `DecodedUop` → physical `MicroOp`).
- ADDQ/SUBQ, Scc/DBcc, shifts, LEA/PEA, bit/immediate (class-0) ops — these are predecode-complex in the current first cut, so they arrive as complex packets (sub-slice 3); not decoded here.

## 3. `DecodedUop` — the decode→rename contract

```
DecodedUop {
  valid        : Bool
  pc           : UInt(32)
  op           : DecOp          // enum: MOVE, ADD, SUB, AND, OR, CMP, BRANCH, ILLEGAL
  cluster      : Cluster        // = INT for this sub-slice
  size         : Size           // BYTE/WORD/LONG
  // architectural operands (D0-7 = 0..7, A0-7 = 8..15)
  srcAReg      : UInt(4); srcAValid : Bool
  srcBReg      : UInt(4); srcBValid : Bool
  dstReg       : UInt(4); dstValid  : Bool
  useImm       : Bool;    imm       : Bits(32)
  // flags (split per spec 4.6)
  readsNzvc    : Bool; readsX  : Bool
  writesNzvc   : Bool; writesX : Bool
  // branch
  isBranch     : Bool
  cond         : Bits(4)        // m68k condition field (BRA=0,BSR/“F”=1,Bcc=2..15)
  branchDisp   : Bits(32)       // sign-extended displacement (target = pc + 2 + disp)
  // status
  unimplemented: Bool           // a predecode-simple form this sub-slice can't yet crack (e.g. memory EA)
}
```
Rename later allocates physical regs from `srcAReg/srcBReg/dstReg` and the flag masks, producing `MicroOp`. `memOp` is always NONE here (no memory operands).

## 4. `SimpleDecodeUnit` — decode rules (register/immediate only)

Input: a `DecodePacket` (`pc`, `words: Vec(16b,5)`, `wordCount`, `simple`, `lenWords`, `complex`, `fault`). Output: one `DecodedUop`. The opword is `words(0)`. **If `!packet.simple` → `op=ILLEGAL`, `unimplemented=True`** (complex isn't this unit's job). For simple packets, decode by opword class (`op[15:12]`); any operand that is a **memory** EA (mode ∉ {Dn(0), An(1), #imm(7/4)}) → `unimplemented=True` (sub-slice 2):

- **MOVEQ** (`0111 ddd 0 iiiiiiii`): `op=MOVE`, `dstReg=d`, `dstValid`, `useImm`, `imm = signExtend8→32(op[7:0])`, `size=LONG`, `writesNzvc`, `!writesX`. (1 µop.)
- **MOVE/MOVEA** (`0001`=B / `0011`=W / `0010`=L): `srcMode=op[5:3], srcReg=op[2:0]`; `dstMode=op[8:6], dstReg=op[11:9]`. Register/immediate only:
  - src `Dn`(0)/`An`(1) → `srcAReg = (srcMode==1 ? 8+srcReg : srcReg)`, `srcAValid`. src `#imm`(7/4) → `useImm`, `imm` from `words(1..)` per size (B/W = `words(1)`, L = `words(1)##words(2)`). Other src modes → `unimplemented`.
  - dst `Dn`(0) → `dstReg=op[11:9]`, `writesNzvc`, `!writesX`. dst `An`(1) → MOVEA: `dstReg=8+op[11:9]`, `!writesNzvc` (MOVEA doesn't set flags), value sign-extended. Other dst modes → `unimplemented`.
  - `op=MOVE`, `dstValid`.
- **ALU read-forms** (`1101`=ADD,`1001`=SUB,`1100`=AND,`1000`=OR,`1011`=CMP): `opmode=op[8:6]`, EA=`op[5:3]/op[2:0]`, `Dn=op[11:9]`. Register-only EA (`Dn`/`An`); else `unimplemented`. `op` per class.
  - `opmode ∈ {0,1,2}` (EA→Dn, size B/W/L): `srcAReg=EAreg`, `srcBReg=Dn`(0..7), `dstReg=Dn`, `dstValid` (except CMP: `!dstValid`). 
  - `opmode ∈ {3,7}` (ADDA/SUBA/CMPA, EA→An, size W/L): `srcAReg=EAreg`, `srcBReg=8+Dn`, `dstReg=8+Dn` (ADDA/SUBA write An; CMPA `!dstValid`).
  - Flags: ADD/SUB → `writesNzvc+writesX`; AND/OR → `writesNzvc` only; CMP/CMPA → `writesNzvc` only, `!dstValid`; ADDA/SUBA → no flag writes. (CMP reads nothing extra; none of these read flags.)
- **Bcc/BSR/BRA** (`0110`): `op=BRANCH`, `isBranch`, `cond=op[11:8]`, `disp8=op[7:0]`. `branchDisp = disp8==0x00 ? signExt16(words(1)) : disp8==0xFF ? signExt32(words(1)##words(2)) : signExt8(disp8)`. No reg operands/writes. `readsNzvc = cond >= 2` (conditional Bcc reads flags; BRA/BSR don't). (1 µop.)
- **Anything else** → `op=ILLEGAL`, `unimplemented=True` (shouldn't occur for a simple packet given predecode's set, but safe default).

## 5. `DecodeStage` (FiberPlugin)
- Consumes `DecodeFeedService.feed` (`Stream(Vec(DecodePacket,2))` + `slot1Valid`). Two `SimpleDecodeUnit`s decode slot 0 and slot 1. Emits `DecodeUopService.uops` = `Stream(Vec(DecodedUop,2))` (uop0 valid when feed fires; uop1 valid when `slot1Valid`). `feed.ready := uops.ready` (1:1 passthrough; pure combinational decode + registered output for clean timing).
- `extends FiberPlugin with DecodeUopService` (register synchronously). Resolves `host[DecodeFeedService]`.

## 6. Service-port-directionality cleanup (isolated first task)
The fetch/align review found `FetchService`/`DecodeFeedService` ports declared `slave`/`master` (directional IO) forced a `setAsDirectionLess()` hack in the consumer. Fix the convention: **plugin-to-plugin service ports are plain `Stream(...)`/`Flow(...)` (no `slave`/`master`)**; direction is applied only where a port reaches the top-level Dut boundary (test harness). Apply to `FetchService` (I-cache producer / fetch-align consumer), `DecodeFeedService`, and the new `DecodeUopService`. Remove the `setAsDirectionLess()` calls in `FetchAlignPlugin` and the `complexPending` bubble (emit-once holds via `stalled`). **Verify the entire frontend stays green (43 fast + 19 verilator) before building decode.**

## 7. Verification
- **`SimpleDecodeUnit` directed tests** (standalone, like `Aligner`/`PredecodeWord`): construct `DecodePacket`s for representative instructions; assert the `DecodedUop` fields. Examples: `MOVEQ #5,D3` → `{op=MOVE,dstReg=3,useImm,imm=5,size=L,writesNzvc,!writesX}`; `MOVE.W D0,D1` → `{op=MOVE,srcAReg=0,dstReg=1,writesNzvc}`; `MOVEA.L A0,A1` → `{op=MOVE,srcAReg=8,dstReg=9,!writesNzvc}`; `ADD.L D1,D0` → `{op=ADD,srcAReg=1,srcBReg=0,dstReg=0,writesNzvc,writesX}`; `CMP.W D2,D3` → `{op=CMP,!dstValid,writesNzvc}`; `BRA.w` → `{isBranch,cond=0,branchDisp=signExt(words1),!readsNzvc}`; `BEQ.s +4` → `{isBranch,cond=7,branchDisp=4,readsNzvc}`; a memory-EA `MOVE (A0),D0` packet → `{unimplemented}`.
- **End-to-end through the real frontend** (`IcachePlugin + predecode + FetchAlignPlugin + DecodeStage`): preload a register/immediate instruction stream via `attachMemoryWithWords`, `redirect` to the base PC, consume `DecodeUopService.uops`, assert the emitted `DecodedUop`s match the program (op, regs, imm, branch). Covers a 2-wide pair.

## 8. Files & structure

| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/decode/DecodedUop.scala` | `DecodedUop` bundle + `DecOp` enum |
| `src/main/scala/m68k040/decode/SimpleDecodeUnit.scala` | combinational reg/imm decode |
| `src/main/scala/m68k040/decode/DecodeStage.scala` | FiberPlugin: two units, DecodeUopService |
| `src/main/scala/m68k040/services/Services.scala` (modify) | add `DecodeUopService`; make service ports plain wires |
| `src/main/scala/m68k040/cache/IcachePlugin.scala` (modify) | FetchService ports plain wires |
| `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` (modify) | drop setAsDirectionLess + complexPending |
| `src/test/scala/m68k040/decode/SimpleDecodeUnitSpec.scala` | directed decode tests |
| `src/test/scala/m68k040/decode/DecodeStageSpec.scala` | end-to-end frontend→decode tests |

## 9. Open items for the plan
- Exact `DecOp` enum members and `op` packing.
- Immediate extraction for MOVE `#imm` (size-dependent word count) and branch displacement word selection from `DecodePacket.words`.
- The clean service-port pattern: confirm plugins still elaborate with plain `Stream`/`Flow` service ports wired plugin-to-plugin and exposed at the Dut boundary for tests (the directionality fix must keep all existing tests green).

## 10. Known divergences / deferrals (logged)
- Register/immediate only; memory-EA simple forms emit `unimplemented` (sub-slice 2).
- Complex instructions not decoded here (sub-slice 3) — they arrive as complex packets the fetch/align stage already stalls on.
- No rename/backend consumer yet; `DecodedUop` stream tested in isolation.
