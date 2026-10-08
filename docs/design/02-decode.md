# 02 — Decode (DecodeStage, OperationDecoder, EaDecoder, decode record)

Source tree: commit `fee95f6f` (the bitstream on the board). All paths are relative to
`src/main/scala/m68k040/` unless they start with `docs/` or `src/`.

**Method.** This document is derived from the RTL code only. Source comments and
`docs/*` were treated as unreliable. Where a comment disagrees with the code, §7 records
it as a stale comment. I did not build or simulate anything. Bit widths are counted by
hand from the bundle declarations; I note this wherever the figure matters.

Scope: `decode/DecodeStage.scala`, `decode/OperationDecoder.scala`, `decode/EaDecoder.scala`,
`decode/DecodeContracts.scala`, `decode/DecodedUop.scala`, `decode/ImmEnc.scala`, `isa/Isa.scala`,
`top/DecodeUopInputPlugin.scala`. `MicroOpAssembler`, `Microcode`, `MicroOpQueue` and
`RegListWalk` belong to another document. They appear here only where they form part of the
boundary.

---

## 1. Purpose and position in the pipeline

```
FetchAlignPlugin (Aligner)                         DecodeStage (this doc)                                   RenameStage
 DecodeFeedService.feed  ──► raw ──► fedIn/fed ──► assemble×2 + pack + FSMs ──► pushReg ──► MicroOpQueue(16) ──► widen/expand ──► DecodeUopService.uops
 Stream[Vec(DecodePacket,2)]  (reg)   (reg; carries     (MicroOpAssembler,          (reg)      (LUTRAM ring,       (ImmEnc + brPred
 + slot1Valid                          OperationDecoder  Microcode ROM, MOVEM/MOVEP/            push ≤4, pop 2)     side tables)
                                       + 2 EaDecoders    FMOVEM.X FSMs)
                                       per slot)
```

- **Input.** `host[DecodeFeedService]` (`decode/DecodeStage.scala:215`) supplies
  `feed: Stream[Vec[DecodePacket]]` (2 packets) plus `slot1Valid` (`services/Services.scala:728-731`).
  A `DecodePacket` (`frontend/DecodePacket.scala:6-70`) holds `pc`, a 10-word window
  `words(0..9)`, `wordCount`, `simple`/`complex`, `lenWords` (the predecode length),
  `fault`/`faultAtc`, the fetch prediction (`predTaken`, `predTarget`, `phtValid`, `phtIndex`),
  `brPredTag`, and `size`. The `size` field is the operand size, computed at I-cache refill
  time by calling `OperationDecoder.decode(op).size` (`frontend/PredecodeWord.scala:84`).
- **Output.** `DecodeUopService` (`services/Services.scala:753-791`), implemented at
  `decode/DecodeStage.scala:3920-3934`, exposes:
  - `uops: Stream[Vec[DecodedUop](2)]` with a 32-bit `imm`;
  - `uop1Valid`;
  - `uopPred: Vec[BranchPredRec](2)`;
  - `pipeFlush`, `backendFlush` and `complexResume: Flow[UInt]`.

  RenameStage consumes them.
- **What happens inside.** DecodeStage also instantiates the two `MicroOpAssembler.assemble`
  crackers (`:328-329`), the microcode sequencer and its ROM (`:2972-3013`), and the
  `MicroOpQueue` (`:218`). In this design, "decode" therefore covers everything from the
  aligned packet to the 2-wide rename feed.

---

## 2. Interfaces

### 2.1 Services

| Direction | Service | Where | Notes |
|---|---|---|---|
| consumed | `DecodeFeedService` | `DecodeStage.scala:215,243-248` | `feed.ready := rawIn.ready` |
| provided | `DecodeUopService` | `DecodeStage.scala:33-34,3920-3925` | 2 popped µops per cycle |
| provided | `FpImmTableService` | `:3926-3928`, table `:573-686` | 16×80b FP-immediate side table; DivEu reads and frees entries |
| provided | `FpTrapImmediateService` | `:52-54`, `:648-650` | read-only view for exception entry |
| provided | `FrontendDebugMatchService` | `:3930-3934`, matcher `:3134-3168` | 4 PC breakpoints |

**`pipeFlush` and `backendFlush` are inputs, despite being declared on the provided
service.** Both default to `False` with `allowOverride` (`:223`, `:72`), and a wiring
plugin overrides them. In the shipping SoC, `top/FullCoreSynth.scala:117-121` sets:

- `pipeFlush = feFlush = (doFlush && !feSuppress) || excActive || earlyFire`;
- `backendFlush = rob.backendSquash`.

**`complexResume`** (`:2318-2341`) is registered and then passed through
`ComplexResumeActionPipe` into `FetchAlignPlugin.mispredictRedirect`
(`top/FullCoreSynth.scala:135-145`).

### 2.2 Global (Database) keys

DecodeStage reads **no** `Database` key. Decode width is hard-coded to 2 through
`Vec(…, 2)` and `Vec(…, 4)` throughout. The size constants it uses are plain Scala vals, not
keys (`Global.scala:181-258`):

| Constant | Value | Used for |
|---|---|---|
| `FP_IMM_TABLE_DEPTH` | 16 | `fpImmTable` (`:573`) |
| `BR_PRED_TABLE_DEPTH` | 64 | `brPredTable` (`:704`), `DecodedUop.brPredTag` (6 bits) |
| `WIDE_IMM_TABLE_DEPTH` | 32 | `wideImm` GROUP/PUSH banks (`:337`) |

`Global.DECODE_WIDTH` is produced by ParamPlugin (`core/ParamPlugin.scala:39`) from
`Config.decodeWidth = 2` (`Config.scala:48`). **Nothing reads it** (repo-wide grep finds only
the `set`). It is a dead key; see §7.

### 2.3 `DecodedUop` — the decode→rename record (`decode/DecodedUop.scala:382-894`)

`DecodedUop(immW)` takes a parameter `immW`. Different stages instantiate it with different
widths:

- **Architectural width, `immW = 32`.** Used for `AssembledUops` and for the
  `DecodeUopService` output.
- **Narrow decode record, `immW = ImmEnc.WIDTH = 18`.** Used for `PushPayload`
  (`DecodeStage.scala:114-120`), the slot-1 stash (`:426`) and the queue ring
  (`decode/MicroOpQueue.scala:98`).

Fields (hand count, total ≈ 227 bits narrow / 241 bits at 32-bit `imm`):

| Group | Fields (width) |
|---|---|
| identity | `valid`(1), `pc`(32), `lenWords`(4). `nextPc` is derived, not stored (`:880`) |
| class | `op: DecOp` (38 values → 6b), `cluster` (2b: INT/EA/LS/CPLX, `isa/Isa.scala:42-44`), `size` (2b), `memOp` (2b) |
| int operands | `srcA/B/C Reg`(5)+`Valid`(1) each, `dstReg`(5)+`dstValid`(1). Arch ids 0-7 = Dn, 8-15 = An, 16-19 = T0-T3 (`Isa.ARCH_INT_REGS = 20`, `isa/Isa.scala:18`) |
| immediate | `useImm`(1), `imm`(`immW`). Three roles: a value, a tag/id, and the PC-relative branch displacement |
| flags | `readsNzvc`, `readsX`, `writesNzvc`, `writesX` |
| branch | `isBranch`, `ibranch`, `anInc`(3), `isReturn`, `isCall`, `cond`(4), `isCondTrap`, `isScc`, `isDbcc`, `brPredTag`(6) |
| fault | `unimplemented`, `faulted`, `faultVector`(8), `faultUsesNextPc`, `sswInstr`, `faultAtc`, `fpuSoftwareComplete`, `fpuCmdWord`(16) |
| op sub-kinds | `divSigned`, `div64`, `divIsRem`, `isChk2`, `shiftOp`(2), `shiftDir`, `bcdSub`, `bitOp`(2), `bfOp`(3), `bfDynamic`, `bfMem`, `bfStoreForm`(3), `extByte`, `casForm`(3) |
| memory / EA | `stkPush`, `eaAuto`(2), `eaDelta`(3), `indexLong`, `indexScale`(2), `leaAddr`, `movesAliasStore`, `altAddrSpace` |
| system | `isRte`, `ccrRestore`, `toCcr`, `fromCcr`, `fromSr`, `needsSupervisor`, `keepCommit`, `sysOp`, `sysKind` (15 values → 4b), `sysReadDir`, `isMovea` |
| macro boundary | `firstOfInstr`, `lastOfInstr`, `debugBreakValid`, `debugBreakSlot`(2) |
| FP | `fpSrcAReg/fpSrcBReg/fpDstReg`(3 each) + `uses/writes`, `readsFpcc`, `writesFpcc`, `fpuOp`(7), `fpSrcKind` (9 values → 4b), `fpSrcFmt`(3) |

Helpers:

- `DecodedUop.copyNoImm` (`:903-914`) copies every field except `imm`, matched by name, and
  raises an elaboration error on a width mismatch.
- `withImm` (`:917-922`) moves a µop between the two `immW` widths.

`BranchPredRec` (45b) is **not** on the µop. It is re-expanded from `brPredTag` at the pop
(`DecodeStage.scala:3833-3834`).

### 2.4 Decoder-internal contracts (`decode/DecodeContracts.scala`)

**`OpSpec`** (`:157-274`): the output of `OperationDecoder.decode(opword)`. It is a function
of the opword alone. It contains:

- the operation: `op`, `form: OpForm`, `size`, `cluster`;
- the operands: `srcA/srcB/dst: OperandSrc{kind, isAddr}` and `dstWrites`;
- the flag masks, plus `writesNzvcIfDataDst`;
- branch information: `isBranch`, `cond`;
- legality: `illegal`;
- sub-kinds: `divSigned`, `div64`, `shiftOp/Dir/Imm`, `bcdSub`, `bitOp`, `bfOp`, `extByte`;
- multi-µop routing markers:
  - `movem` and `movep`, each with `Dir` and `SizeLong`;
  - `microcoded` and `ucEntry`(9b);
- system ops: `sysOp`, `sysKind`, `sysReadDir`;
- EA typing: `eaSrcValid`, `eaSrcShift`(2b), `eaDstValid`;
- `fpGeneric`.

`OperandKind` takes the values NONE, REGFIELD, EASRC, EADST, IMMQ (the MOVEQ d8), IMMEXT
(trailing extension-word immediate) and IMMQ3 (the ADDQ/SUBQ 1-8) (`:10-18`).

**`EaSpec`** (`:45-85`): the output of `EaDecoder.decode(eaField, size, words)`. It contains:

- `klass: EaClass`, with values DATAREG, ADDRREG, IMM, MEMSIMPLE, MEMINDIRECT, MEMCOMPLEX and
  ILLEGAL;
- `reg`(5), `imm`(32);
- `baseValid`, `base`(5), `disp`(32), `pcRel`, `baseDirect`;
- `autoMode`, `autoDelta`(3);
- `indexValid`, `indexReg`(5), `indexLong`, `indexScale`(2);
- `od`(32), `memPost`.

**`Offload`** (`decode/MicroOpAssembler.scala:720-724`) bundles one `OpSpec` with two
`EaSpec`s: `srcEa` for op[5:0] and `dstEa` for the MOVE destination field, op[11:6] swapped.
It is computed once per slot, before the `fed` register.

---

## 3. Microarchitecture

### 3.1 Stage-by-stage timing

Each cycle below is a register boundary in the code.

| Cycle | Register | Logic between registers | Code |
|---|---|---|---|
| A | `raw` (`PipeStage`) | Aligner output (FetchAlignPlugin) into `rawIn` | `DecodeStage.scala:243-250` |
| R | `fed` (hand-written 1-deep register `fedRegValid/fedRegData`) | `computeOffload` ×2: `OperationDecoder` + 2 `EaDecoder` per slot | `:260-315`, `MicroOpAssembler.scala:879-905` |
| D | `pushReg` (`PipeStage`) | `assemble` ×2, slot packing, stash, FSM arbitration → `pushProduced` | `:328-3116` |
| P | queue ring (LUTRAM write) | `pushReg` → `queue.io.push` (count-gated); debug PC match | `:3116-3186` |
| Q | — | `queue.io.pop` (async LUTRAM read) → `wideImm.widen` + `brPredTable.expand` → rename | `:3823-3834` |

There is **no bypass**:

- `MicroOpQueue.io.pop.valid := count > 0`, and `count` is a register
  (`decode/MicroOpQueue.scala:113`).
- `PipeStage` is a plain 1-deep register with `valid` cleared by `flush`
  (`frontend/PipeStage.scala`).

The minimum latency from aligner output to rename input is therefore **4 clock edges**: `raw`,
`fed`, `pushReg`, and the ring.

**`fed` handshake (`:306-315`).** `fed` accepts a new group only under `fedAllowXfer`
(`:3341`):

```
fedAllowXfer = !fed.valid || (fedFastConsume && !fedColdHint)
```

- `fedFastConsume` (`:3307`) is built from registered stash, FSM and pend state, plus
  `pushProduced.ready`.
- `fedColdHint` (`:3326-3340`) is a cheap superset of the "cold head" classifiers. It ORs
  together:
  - `spec0.movem`, `movep` and `microcoded`;
  - any full-format bit 8 across `words(1..9)` when the source or destination EA mode is
    indexed;
  - a bit-field op with a memory EA;
  - cpGEN with ext[15:14] = 11 (FMOVEM.X);
  - an FP #imm while `fpImmTable.fullHint` is set.

When the hint fires, `fed` drains and refills one cycle later. That is **one bubble per cold
head** (§5). An assertion at `:3342` checks that `fedAllowXfer` implies `fed.ready`.

### 3.2 Decode width and slot rules

**Packet supply (FetchAlign/Aligner).** Each cycle presents slot 0 and, optionally, slot 1.

- Slot 1 is offered only if all of the following hold (`frontend/Aligner.scala:274`):
  - the packet is `simple`;
  - it is not `ambiguousLine`;
  - both instructions fit in the 10-word window and the available words.
- FetchAlign then suppresses slot 1 (`frontend/FetchAlignPlugin.scala:1371-1373,1441-1444`)
  when any of these holds:
  - slot 0 is predicted (BTB, RAS or computed target);
  - slot 1 would be RAS- or conditionally predicted;
  - FTQ conditions apply;
  - `deferSlot1Uncond`: slot 1 is BRA/BSR other than `.L`, JSR or JMP;
  - `deferSlot1Dbcc`: slot 1 is a DBcc other than DBT.
- `deferSlot1Uncond` and `deferSlot1Dbcc` are both ON in shipping.

**Decode side.** Each slot is cracked by its own `MicroOpAssembler.assemble` into 1–3 µops
(`AssembledUops.count`, `MicroOpAssembler.scala:50-66`). Packing rules (`DecodeStage.scala:742-749,1681-1699`):

- The **head** is the stashed slot-1 µops if `stashValid`, otherwise slot 0 (`a0`).
  `nCur` ∈ 1..3.
- Slot 1 is appended in the same cycle (`slot1Emit`, `:537`) only if every one of these holds:
  1. no stash is being replayed;
  2. slot 0 is not 3 µops, and slot 1 is not 3 µops;
  3. slot 1 is not MOVEM, MOVEP or microcoded;
  4. slot 1 is not memory-indirect, nor a dynamic or memory-indirect bit-field;
  5. slot 1 is not an FP-immediate (`a1raw.fpImmAlloc`).
- Otherwise slot 1 is **stashed** (`deferSlot1`, `:542`) and replayed the next cycle as the
  head, alone. `fed.ready` requires `!stashValid` (`:3298`), so the next group waits.
  Alternatively, a cold slot 1 is **pended**: its packet goes into `movemPendPkt`,
  `movepPendPkt`, `fmovemxPendPkt` or `ucPendPkt` (`:3355-3372`), and the matching FSM starts
  from it next cycle.
- At most 4 µops are pushed per cycle (`totalCount`, `:749`). With 2+2 = 4, two 2-µop
  instructions do decode together.
- `MicroOpQueue.push.ready` requires **≥ 4 free entries** regardless of `count`
  (`MicroOpQueue.scala:107`). Pop is 2 µops per cycle (`pop1Valid = count > 1`).

**Steady-state ceiling.** 2 macro instructions per cycle when both are 1–2 µops. Rename
drains 2 µops per cycle, so any pair of 2-µop instructions builds up in the queue.

### 3.3 Opcode classification (`OperationDecoder.decode`, `decode/OperationDecoder.scala:11-1561`)

The decoder starts from `OpSpec.illegalDefault()` (`illegal = True`, `form = NONE`). It then
does one `switch(line)` on opword[15:12]. Inside each arm, conditional `when`s override the
defaults (`allowOverride`, `:13`). EA typing is applied **after** the switch (`:1555-1560`):

```
eaSrcValid = eaHand || srcA/srcB/dst.kind == EASRC
eaDstValid = dst.kind == EADST
```

| Line | Recognised (non-illegal) in `OpSpec` | Code |
|---|---|---|
| 0 | ORI/ANDI/SUBI/ADDI/EORI/CMPI (IMMEXT srcB, `eaShift` = 1 or 2); BTST/BCHG/BCLR/BSET static/dynamic; MOVEP (`movep`); CMP2/CHK2 (CPLX); CAS/CAS2 (`microcoded`, CAS2_ENTRY/CAS_ENTRY); MOVES (`microcoded`) | `:45-232` |
| 1/2/3 | MOVE.B/.L/.W, any EA pair (`srcB = EASRC`, `dst = EADST`, `writesNzvcIfDataDst`) | `:299-308` |
| 4 | CHK; CLR/NEG/NEGX/NOT/TST; SWAP; EXT/EXTB; TAS; NBCD Dn; BKPT and NOP (as no-op MOVE); MOVEM (`movem`, EA gate `movemEaOk` `:570-573`); LEA/PEA (`eaHand`, form); MOVE from/to SR/CCR; MOVE USP / MOVEC / RESET / STOP (`sysOp`). JMP/JSR, DIVL/MULL, RTE/RTS/RTD/RTR/TRAPV/TRAP/LINK/UNLK/LINK.L are **named by `form` only** and left `illegal = True`; MicroOpAssembler builds them | `:314-753` |
| 5 | ADDQ/SUBQ (IMMQ3); Scc/DBcc/TRAPcc named by `form` only (Scc gets `eaHand`, size BYTE) | `:253-297` |
| 6 | Bcc/BRA/BSR (BRANCH, `isBranch`, `cond = op[11:8]`, `readsNzvc` iff cond ≥ 2); BSR `form` | `:755-763` |
| 7 | MOVEQ (bit 8 = 0) | `:234-243` |
| 8/9/B/C/D | OR/SUB/CMP/AND/ADD in both directions, ADDA/SUBA/CMPA, EOR, DIVU.W/DIVS.W, MULU.W/MULS.W, ABCD/SBCD reg, ADDX/SUBX reg, PACK/UNPK reg, EXG (form). Memory forms of BCD/ADDX/SUBX/PACK/UNPK and CMPM go to `microcoded` | `:894-1155` |
| A | **No arm**: always illegal | — |
| E | reg-form shift/rotate (SHIFT); memory shift (word, count 1); bit-fields: register form, memory read-only (CPLX), memory RMW (`microcoded` BF_RMW_4B_ENTRY) | `:773-892` |
| F | FScc/FDBcc/FTRAPcc (form only); CPUSH/CINV, PFLUSH, PTEST, FSAVE/FRESTORE (`sysOp`); MOVE16 (`microcoded`); cpGEN `1111 001 000` (`fpGeneric`, op FPU, CPLX); cpGEN with a memory EA (`microcoded`, placeholder `FP_MEM_TRAP_ENTRY`); FBcc (BRANCH; FBF → no-op MOVE); 0xF27F special case (CLR.B, no flags) | `:1157-1552` |

Notes, all from code:

- `OpSpec` is opword-only. Anything that depends on an extension word is resolved
  downstream, by the MicroOpAssembler or by `ucBegin`'s Ctx population (`DecodeStage.scala:1795-2232`).
  This covers:
  - MOVEC register numbers;
  - bit-field offset and width;
  - the CAS Dc/Du registers;
  - the cpGEN opclass;
  - DIVL/MULL size;
  - the CMP2/CHK2 Rn.
- `cluster` is set only for CHK, CMP2/CHK2, DIV, MUL, BITFIELD and FPU (CPLX) and for
  SHIFT, BRANCH, CAS and MOVES (INT). Everything else keeps the default INT. LS routing is
  decided in the assembler.
- `ucEntry` is a placeholder for the bit-field RMW, MOVES and cpGEN memory cases.
  `ucRealEntry` in DecodeStage overrides it once the extension word is visible.

### 3.4 EA decoding (`EaDecoder.decode`, `decode/EaDecoder.scala:36-200`)

One combinational function of (`eaField[5:0]`, `size`, `words: Vec[Bits]`), where `words(1)`
is the first EA extension word.

| Mode | Class | base / disp / extras |
|---|---|---|
| 0 Dn | DATAREG | `reg = rrr` |
| 1 An | ADDRREG | `reg = 8+rrr` |
| 2 (An) | MEMSIMPLE | `baseDirect`, disp 0 |
| 3 (An)+ | MEMSIMPLE | `autoMode = POSTINC`, `autoDelta = 1/2/4`, but 2 for a byte access on A7 (`:46-49`) |
| 4 -(An) | MEMSIMPLE | `PREDEC`, same delta |
| 5 (d16,An) | MEMSIMPLE | `disp = sext(words(1))` |
| 6 brief (ext bit 8 = 0) | MEMSIMPLE | `disp = sext(d8)`, `indexValid`, `indexReg` (D/A + Xn), `indexLong`, `indexScale` |
| 6 full (bit 8 = 1) | MEMSIMPLE if I/IS = 000, else MEMINDIRECT | `baseValid = !BS`, `disp = bd` (0/16/32), `indexValid = !IS`, `od` (0/16/32, position = 2+bdWords), `memPost = ext[2]` |
| 7.0 abs.W | MEMSIMPLE | `disp = sext(w1)`, no base |
| 7.1 abs.L | MEMSIMPLE | `disp = w1:w2` |
| 7.2 (d16,PC) | MEMSIMPLE | `pcRel`, `disp = sext(w1)` (the PC fold happens in the assembler) |
| 7.3 brief / full | as mode 6, with `pcRel = !BS` | |
| 7.4 #imm | IMM | `imm = w1:w2` (.L) or `sext(w1)` |
| 7.5–7.7 | ILLEGAL | |

Points to note:

- `MEMCOMPLEX` is **never produced**. No code anywhere assigns or compares it; it appears only
  in the enum (`DecodeContracts.scala:33`).
- No validation of reserved full-format encodings (BD-SIZE = 00, the reserved I/IS
  combinations, ext bit 3) is done here. `fBdSize = 00` and `01` both decode to a null bd
  (`:94-97`).
- `fOdWordAt` (`:25-33`) indexes `words(2..5)` dynamically. It reads 0 for callers that pass
  a shorter `Vec`.

**Who executes what:**

- **MEMSIMPLE** (including indexed, and the no-memory-indirect full format) goes through the
  normal assembler crack: [load → T0][op], [op][store], or [load][op][store] for RMW.
- **MEMINDIRECT** sends the instruction to the microcode engine through `slot0IsMemInd`
  (`DecodeStage.scala:1152-1154`) and `slot1IsMemIndEarly` (`:487-489`). Those gates use
  `spec.eaSrcValid/eaDstValid`.
  - The source EA klass comes from either the offloaded `srcEa` (when `eaSrcShift = 0`) or
    the shifted re-decode `s0EaShifted`/`s1mi_shiftEa` (`:997-1002`, `:481-486`).
  - For the slot-0 MOVE destination, a dynamic word-index correction
    (`s0dstIsMemIndShifted`, `:1028-1050`) recomputes where the destination extension word
    really sits after a multi-word source EA. **Slot 1 has no equivalent correction**
    (`slot1IsMemIndEarly` uses the unshifted `s1mi_dstEa`). See §7.

**Extension-word placement.** `computeOffload` decodes `srcEa` with `words(1)` as the first
EA word. It decodes `dstEa` on a dynamically shifted view: `shiftedWordsFor(words,
srcEaWordCount(...))`, using the predecode `pkt.size` (`MicroOpAssembler.scala:742-777,879-887`).

Instructions that place their own word(s) before the EA set `eaSrcShift`. These are:

- line-0 immediates (1 or 2);
- static bit-ops, bit-fields, CAS, MOVES, CMP2/CHK2 and DIVL/MULL (1).

DecodeStage re-decodes the shifted view for routing (`s0EaShifted`). The assembler re-decodes
it again for the operation itself (`immEa`, `immDstEa`, `divlSrcEa`, `mullSrcEa`, `bfmEaDec`,
`c2SrcEa`, `coldEa`; `MicroOpAssembler.scala:989,1700,3154,3339,3473,3636,4198`).

### 3.5 Immediate and extension-word handling

**Where the 32-bit values are computed.** All 32-bit immediates, displacements and folded PC
values are computed in `MicroOpAssembler.assemble` from `pkt.words`. They appear on each
cracked µop's `imm`.

**Narrow-carry encoding (`decode/ImmEnc.scala`).** The decode record carries `imm` as
{mode(2), payload(16)}. The payload for WIDE/WIDEP modes is {0…, tag(5), sel(3)}.

- **GROUP bank** `grpMem`: 32 entries × 192 bits = 6 slots × 32b, distributed RAM
  (`DecodeStage.scala:346-370`).
  - Slot `sel = 3·packet + crackPosition`.
  - Written from `a0.uops(0..2).imm` and `a1raw.uops(0..2).imm` on **every** `fed.valid`
    cycle.
  - The write is **registered one cycle late** (`grpWrEn/Addr/Data = RegNext(…)`, `:364-367`).
  - `grpTag` advances on `fed.fire`.
- **PUSH bank** `pushMem`: 32 × 64 bits (`:377-392`), for the MOVEM, MOVEP, FMOVEM.X and
  µcode sequencers.
  - It is written when a sequencer arm's push is accepted (`wideImm.pushWr`, `:3110`).
  - That write is also registered.
- **Conversion into the record.** Every assembler µop is converted with
  `wideImm.grp(u, pk, pos)` (`:419-420`). Every sequencer µop is converted with
  `wideImm.push(u, k)` (`:3027-3032`).
  - **The decode record's `imm` is therefore never the value itself.** It is always a WIDE or
    WIDEP pointer.
  - The only other mode produced is **ZEXT**: the FP-immediate table tag stamped into the
    head µop (`:3101-3104`).
  - `ImmEnc.sext` and `ImmEnc.zero` have **no callers**, so the SEXT mode is never produced.
- **Re-expansion at the pop.** `ImmEnc.expand` (`ImmEnc.scala:118-131`) does an async read of
  both banks at `tag` for each of the 2 popped µops, then a 6:1 or 2:1 slot mux and a 4-way
  mode mux (`DecodeStage.scala:409-413,3827`).

**Two further side tables follow the same idea:**

- **`brPredTable`** (`:704-738`): 64 × 45b LUTRAM, written idempotently from `fed` with the
  record of the tagged packet (slot 1's when `allowSlot1Prediction` and slot 1 is tagged),
  read twice at the pop. Tag 0 means inert.
- **`fpImmTable`** (`:573-686`): 16 × 80b.
  - Allocation goes to the first free entry (`OHMasking.first(~valid)`), for the head only.
  - Entries carry frontend and backend ownership bits.
  - The table stalls when full (`stall = headPend && full`, which feeds `normalHeadValid`,
    `fed.ready` and the consume block).
  - The data write is registered (`:595-598`).
  - It has 2 async read ports, DivEu and trap (`:648-650`).

### 3.6 Fast path versus sequencers

**Fast (single cycle, at most 3 µops, both slots).** Everything the assembler cracks:

- register/register and register/immediate ALU ops;
- MOVE between a register and a MEMSIMPLE EA, and MEMSIMPLE→MEMSIMPLE;
- RMW to MEMSIMPLE;
- Bcc/BRA/BSR, JSR/JMP/RTS/RTR;
- LEA/PEA;
- DIV/MUL;
- line-0 immediates;
- bit-ops;
- static bit-fields (register form, or memory read-only and non-dynamic);
- cpGEN with a register, #imm or Dn source;
- sysOps (as a single µop).

Shipping enables `fuseLongMoveLoads`, which turns `MOVE.L <mem>,Dn/An` (no auto-update) into
a single LOAD µop that writes the destination and NZVC (`MicroOpAssembler.scala:4461-4472`).

**Sequencers.** Each holds `fed` while active and emits as follows. **They are mutually
exclusive, and the decode stage emits nothing else while any one of them runs**
(`normalHeadValid`, `:1699`).

| Engine | Entry condition | Emission |
|---|---|---|
| MOVEM FSM (`:751-951`, transitions `:3375-3513`) | `spec.movem` | optional T0/T1 snapshot µop(s), then **2 registers per cycle** (`RegListWalk.extractLowest2`, `:838`), then one An update. Mask = `words(1)` |
| MOVEP FSM (`:1373-1482`) | `spec.movep` | 1 µop per cycle; 3 / 7 / 7 / 11 µops for W-store / L-store / W-load / L-load (`:1407-1452`) |
| FMOVEM.X FSM (`:1491-1680`) | `slot0IsFmovemx` / `slot1IsFmovemx` (cpGEN memory EA, ext opclass 110/111, static list) | 1 µop per cycle: per element 3 chunk loads + 1 FP issue (load) or 3 CVT + 3 stores (store), plus an optional An update |
| µcode engine (`:1703-2985`, ROM `:2972-3013`) | `slot0OwnedByUc = microcoded ∨ memIndirect ∨ bfDynMem ∨ bfMemindMem` (excluding FMOVEM.X), `:1243` | 1 µop per cycle from a BRAM `readSync` ROM addressed by `ucNextPc`; one `resolveFromBits` cone (`:3011-3013`) |

**Entry cost.** On the begin cycle (`movemBegin`, `ucBegin`, etc.) nothing is pushed for the
sequenced instruction. Every push arm is keyed on the registered `*Active` flag
(`:3033-3089`). This adds **one bubble per sequenced instruction**, on top of the `fed`
cold-hint bubble.

**Pending packets.** A slot-1 cold instruction is parked in a pend register. The pend
payloads are loaded every cycle while their flag is clear (`:3801-3806`). Its slot 0 is
emitted normally.

**Complex packets.** A `complex` packet (`simple = 0`) makes FetchAlign stall after emitting
it. Only a redirect or `resume` restarts fetch (`frontend/FetchAlignPlugin.scala:1747`).
DecodeStage drives `complexResume` in only two cases:

- a `complex` MOVE entering the µcode engine with a computable length (`ucIsMove &&
  ucMoveLenKnown`);
- an indexed MOVEM (`:2318-2341`).

All other `complex` packets get `bad`, because `bad` includes `!pkt.simple` for `form ===
NONE` (`MicroOpAssembler.scala:2334-2337`). They take an illegal trap, and the exception
redirect restarts fetch.

### 3.7 Illegal, unimplemented, A-line and F-line handling

The assembler's `bad` path calls `inertTrap(vector)` (`MicroOpAssembler.scala:2373-2381`). The
vector is chosen from the top nibble: 0xA → 10, 0xF → 11, otherwise 4.

`inertTrap` (`:1246-1260`) clears every side effect and sets `faulted` together with
`unimplemented`.

- **A-line.** Line A has no `OperationDecoder` arm, so every A-line opword is `illegal`. It
  becomes a 1-µop `faulted` µop with vector 10, carrying the faulting PC.
- **F-line.** An unrecognised F-line opword gives vector 11. If the instruction is cpGEN and
  `pkt.simple && lenWords ≥ 2`, `faultUsesNextPc` is set (`:2402-2405`). The intent is to
  stack the PC of the next instruction.
- **Faulting I-fetch packets** (`pkt.fault`) become a single fault-delivery µop
  (`MicroOpAssembler.scala:3067-3078`, `:4232`).
- **Privilege** is not checked in decode. Decode only sets `needsSupervisor` and `sysOp`; the
  check happens at commit.

### 3.8 Stall and backpressure

`fed.ready` (`:3298-3301`) is True when there is:

- no stash, no MOVEM/µcode/MOVEP/FMOVEM.X hold or slot-0 ownership,
- no FP table stall,
- and `pushProduced.ready`,

or on a slot-0 entry cycle of any FSM.

`pushProduced.ready = !pushReg.valid || queue.push.ready`, which comes from `PipeStage`. The
queue is ready only with ≥ 4 free entries out of 16.

Backpressure from rename travels `uopsOut.ready → queue.pop.ready`. Rename must take both
µops when `pop1Valid`: `popN` is 2 or 1 depending on `count` (`MicroOpQueue.scala:124`).

### 3.9 Flush behaviour

`pipeFlush` clears all of the following in the same cycle:

- `raw.valid`, `fedRegValid` (`:312`), `pushReg.valid`, the queue (`:224`);
- `stashValid`;
- all four `*PendValid` flags (with a final last-assignment block at `:3778-3784`);
- `movemActive`/`AnUpdPhase`/`SnapPhase`, `movepActive`, `fmovemxActive`, `ucActive`;
- `ucComplexResumeValidReg` (`:2330`).

The side tables need no clearing:

- **WIDE and BR banks:** a squashed µop is never popped, so its entry is never read.
- **FP-immediate table:** `pipeFlush` frees frontend-owned entries and `backendFlush` frees
  backend-owned entries (`:634-638`).

A µop counts as backend-owned once it has been popped, i.e. handed to rename. Because
`pipeFlush` also includes `earlyFire` (Tier 1), the decode pipe can be flushed while the
backend is not.

---

## 4. Configuration knobs

| Knob | Where | Constructor default | Shipping | Effect on decode |
|---|---|---|---|---|
| `allowSlot1Prediction` | `DecodeStage(…)` `:33`; `ShippingPlugins.scala:111` (= `ipcThroughput`) | false | **true** | `brPredTable` writes slot 1's record when slot 1 is tagged (`:711-713`) |
| `fuseLongMoveLoads` | same | false | **true** | MOVE.L mem→Dn/An becomes 1 µop (`MicroOpAssembler.scala:4461`) |
| `deferSlot1Uncond` | FetchAlignPlugin `:40`, `ShippingCoreConfig.scala:283` | false / env default true | **true** | slot 1 never holds BRA/BSR/JSR/JMP |
| `deferSlot1Dbcc` | FetchAlignPlugin `:41`, `ShippingCoreConfig.scala:304` | false / env default true | **true** | slot 1 never holds a DBcc other than DBT |
| `computeDirectTargets` | FetchAlignPlugin | false / true | **true** | slot-0 relative branches are "predicted", so slot 1 is suppressed |
| `trainSlot1Conditional`, `deferTakenSlot1Conditional` | FetchAlignPlugin via `ipcThroughput` | false | **true** | slot-1 conditional branches carry `phtValid` and a tag |
| `Config.decodeWidth` / `Global.DECODE_WIDTH` | `Config.scala:48`, `ParamPlugin.scala:39` | 2 | 2 | **Dead**: never read |
| `MicroOpQueue(depth = 16)` | `:218` | hard-coded | 16 | |
| `FP_IMM_TABLE_DEPTH`, `WIDE_IMM_TABLE_DEPTH`, `BR_PRED_TABLE_DEPTH` | `Global.scala:181,254,257` | 16 / 32 / 64 | same | constants |

`DecodeUopInputPlugin` (`top/DecodeUopInputPlugin.scala`) replaces DecodeStage **only** for
backend-only out-of-context synthesis (`top/GenVerilog.scala:59`). It:

- registers 2 µops and the `BranchPredRec` records from top-level IO;
- ties `pipeFlush`, `backendFlush` and `complexResume` to False/0;
- drives `uopsOutReady := RegNext(uopsPort.ready)`.

Nothing in the shipping core uses it.

---

## 5. Performance-relevant behaviour

| Situation | Decode cost (from code) |
|---|---|
| Two 1–2-µop simple instructions | 2 per cycle, up to 4 µops pushed |
| Slot 0 cracks to 3 µops (e.g. RMW load-op-store, RTR, a load with auto-update) with a valid slot 1 | slot 1 stashed; next cycle replays the stash **alone** (`slot1Emit` requires `!stashValid`). 2 instructions over 2 cycles |
| Slot 1 cracks to 3 µops | same stash and replay |
| Slot 1 is an FP `#imm` op | always stashed (single table write port, `:537,542`) |
| MOVEM / MOVEP / FMOVEM.X / µcode (CAS, CAS2, MOVES, BCD/ADDX/SUBX/PACK/UNPK memory, CMPM, MOVE16, bit-field RMW and dynamic memory, all MEMINDIRECT EAs, cpGEN memory EAs) | ≥ 2 bubbles: the `fed` cold hint plus the begin cycle. Then 1 µop/cycle (MOVEM: 2), with no co-issue of any other instruction |
| Any `complex` packet | fetch stops until resume or redirect. A legal `complex` MOVE or indexed MOVEM resumes one cycle after `ucBegin`/`movemBegin`, plus `ComplexResumeActionPipe` latency |
| Slot 0 predicted, or slot 1 an unconditional/DBcc branch (shipping knobs) | group truncated to 1 instruction by FetchAlign |
| FP-immediate table full (16 live) | the head stalls |
| Queue fewer than 4 free | the push stalls even for a 1-µop push |
| Decode → rename latency | 4 register stages (`raw`, `fed`, `pushReg`, ring). Adds directly to the mispredict and redirect refill penalty |

The pop is 2 µops per cycle. Rename therefore sees at most 2 µops per cycle even when decode
pushes 4, so decode bursts are smoothed by the 16-entry queue.

---

## 6. Area and timing notes (inferred from RTL structure)

1. **Where the `imm` cone now ends.** The brief records the decode `imm` mux cone at about
   6,760 LUT and 19 levels. In this RTL, the full 32-bit `imm` of all 6 crack positions (2
   assemblers × 3 µops) is collected into `grpData` (192 bits) and **registered** before the
   LUTRAM (`:355-367`). The decode record carries only a constant {WIDE, tag, sel} per slot
   position (`:419-420`).
   - The `imm` cone therefore ends at `grpWrData`, a 192-flop register with CE = none
     (`RegNext`). It no longer reaches `pushReg`, the stash or the ring.
   - Its depth is still that of the assembler's per-µop imm selection. That logic belongs to
     the other document. I did not measure it.
2. **Replicated decoders in the decode stage.**
   - `OperationDecoder.decode`: 2 full instances (`computeOffload` per slot), plus one in
     `PredecodeWord` of which only `.size` is used.
   - `EaDecoder.decode`: 4 call sites in the offload, 9 in DecodeStage (`:485,495,1001,1168,1810,1902,2148,2221,2465`),
     and 7 per `assemble` instance × 2 = 14. That is **27 elaborated EaDecoder instances**.
     Some are partially constant-folded (size = LONG, 3-entry word vectors).
3. **Wide registered state per cycle path.**
   - `raw`: 2 packets.
   - `fed`: 2 packets plus 2 `Offload`s, about 2 × (`OpSpec` + 2 × `EaSpec`), where an
     `EaSpec` alone is more than 130 bits.
   - `pushReg`: 4 µops × about 227b.
   - stash: 3 × 227b, plus an 80b `stashFpVal`.
   - 4 pend packets of about 230b each (10 words + pc + …) and `ucPendSpecReg`.
   - ring: 16 × 227b in 4 LUTRAM banks with 2 async read rows each.
4. **Side-table read ports.** `grpMem` (192b) and `pushMem` (64b) are each `readAsync` twice
   for the pop, and twice more in the simulation-only shadow. In LUTRAM, each read port of a
   192-bit-wide memory replicates RAM32M storage. `brPredTable` (45b) has 2 reads.
   `fpImmTable` (80b) has 2 reads.
5. **Debug breakpoint matcher.** 4 slots × 4 PCs = 16 32-bit comparators, placed after
   `pushReg` (`:3149-3168`), so they start at flops.
6. **Wide `fed` CE and `fed.ready` fan-out.** `fedAllowXfer` gates the `raw→fed` load
   (`:313`) and `raw.ready`, which goes to `df.feed.ready` (`:248,276`). Its inputs are
   registered state plus `fedColdHint`. `hAnyBit8` is a 9-input OR (`:3332`).
7. **Microcode ROM.** One BRAM `readSync` with a single `resolveFromBits` (`:2984,3011-3013`).
   It is addressed by `ucNextPc`, a combinational mux of `ucRealEntry`, `ucPc + 1` and `ucPc`
   (`:3615-3625`). This puts `pushProduced.ready` on the BRAM address path.
8. **MOVEM.** `movemTotalCount` is a 16-bit popcount at entry (`:3387`). The An delta uses a
   shift and negate instead of a multiplier (`:935-939`). FMOVEM.X uses a 4×4 multiply,
   `fxCount * 12` (`:1650-1652`); it is small.

---

## 7. Observations (factual; candidates for a deeper reviewer)

**Dead or never-produced logic**

1. **`Global.DECODE_WIDTH` is a dead Database key.** It is set in `core/ParamPlugin.scala:39`
   and read nowhere. Decode width is hard-coded to 2 and 4 in `DecodeStage`, `MicroOpQueue`
   and the services. Either read it or delete it. Invariant #3 is satisfied, but the key is
   misleading.
2. **`EaClass.MEMCOMPLEX` is never produced** (`decode/DecodeContracts.scala:33`). No code
   assigns or tests it.
3. **Write-only `OpSpec` fields.**
   - `movemDir`, `movemSizeLong`, `movepDir` and `movepSizeLong` are assigned
     (`OperationDecoder.scala:133-134,578-579`) and never read. The FSMs re-read the opword
     instead (`DecodeStage.scala:3391-3392,1405-1406`).
   - `OpSpec.div64` is only ever `False` (default `DecodeContracts.scala:287`; never assigned in OperationDecoder). It is copied to
     `opUop.div64` (`MicroOpAssembler.scala:1200`).

   Synthesis will prune these, but the fields mislead readers, and each one is carried
   through the `fed` register's `Offload` until pruning.
4. **`ImmEnc` SEXT mode is never produced.** `ImmEnc.sext` and `zero` have no callers, but
   `expand` still contains the sign-extend arm (`ImmEnc.scala:125`). Synthesis cannot prove
   that the mode is never 0, because it comes out of the ring.
   - Making SEXT unreachable would shrink the pop-side mux.
   - Alternatively, use SEXT for crack immediates that are provably 16-bit, which avoids the
     bank write for them.

   As built, **every** cracked µop goes through the 192-bit GROUP bank, including µops with no
   immediate at all.

**Asymmetries and possible correctness hazards**

5. **FMOVEM.X slot0/slot1 EA admission differs.**
   - Slot 0 admits mode 110 only for a brief extension word: `(mode == 110) &&
     !s0pkt.words(2)(8)` (`DecodeStage.scala:1096-1100`).
   - Slot 1 admits mode 110 unconditionally (`:1143-1146`).
   - The FSM computes `fxIsIdxAn = mode 110 && !fxIdxExt(8)` (`:1634`). For a full-format
     mode-6 extension word, `fxBaseDisp` falls through to 0 and `fmovemxBaseValid` is True
     (`:1665-1671,3531`). A slot-1 full-format `FMOVEM.X (bd,An,Xn)` would therefore run
     against `(An)`.
   - **Uncertain:** whether predecode ever marks such a packet `simple`, which slot 1
     requires (`Aligner.scala:274`). Needs a check against `PredecodeWord`.
6. **The MOVE destination memory-indirect correction exists for slot 0 only.** Slot 0
   recomputes where the destination extension word lies after a multi-word source
   (`s0dstIsMemIndShifted`, `:1028-1050`). Slot 1 uses `s1mi_dstEa.klass` directly
   (`:487-489`).
   - `computeOffload`'s `dstEa` already applies `shiftedWordsFor(…, srcEaWordCount)`
     (`MicroOpAssembler.scala:884-886`). Note that `srcEaWordCount` handles full-format
     sources through `miEaWordCountG`.
   - The slot-0 correction may therefore be redundant rather than slot 1 being wrong. Both
     cannot be needed. A reviewer should determine which is the case.
7. **Slot-1 classifiers duplicate slot-0 ones by hand.** Examples: `slot1IsFmovemx` against
   `slot0IsFmovemx`, `slot1IsBfDynMemEarly` against `slot0IsBfDynMem`. Items 5 and 6 are both
   instances of drift between these copies. A single per-slot function applied to both slots
   would remove the drift class.

**Cheap area and congestion opportunities**

8. **Redundant bit-field EA re-decodes.**
   - `s0bfEa` (`:1168`) and `s1bfEa` (`:495`) decode `op[5:0]` with words
     `(opw, w2, w3)` and size LONG.
   - For any BITFIELD opword, OperationDecoder sets `eaSrcShift = 1` (`OperationDecoder.scala:850`)
     and size LONG. `s0EaShifted` and `s1mi_shiftEa` (`:1001`, `:485`) therefore already
     decode the same EA from `(opw, w2, w3, w4)`.
   - The only difference is that `w4` feeds the full-format `od` field, which the bit-field
     gates do not read.
   - Reusing the shifted decode would delete 2 EaDecoder instances from the slot-0 and slot-1
     cold classifiers.
9. **Per-µop 32-bit `pc` and 16-bit `fpuCmdWord`.** These ride every decode-record slot: 4
   push, 3 stash and 16 ring, about 21% of a µop by hand count. Both are constant per macro.
   - `pc` is read at rename and in the ROB.
   - `fpuCmdWord` is non-zero only for the `fpuSoftwareComplete` FP trap µops.
   - The existing tag side-table pattern (`brPredTable`) would apply to both.
10. **Queue admission is pessimistic.** `push.ready` requires 4 free entries whatever the push
    count (`MicroOpQueue.scala:107`). That gives an effective depth of 12, plus `pushReg`, for
    the common 1–2-µop pushes. Using `freeSlots ≥ count` would put `count` on the ready path,
    which is a timing tradeoff.
11. **The GROUP bank is written every `fed.valid` cycle,** including stalled cycles and cycles
    where `fed` is held by a sequencer (`grpWrEn = RegNext(fed.valid)`, `:364`). This is
    functionally idempotent, but it is a 192-bit LUTRAM write every cycle. Gating on
    `fed.fire` would cut toggling but would need the late-capture argument re-checked.
12. **The 0xF27F special case sits in the hot offload cone.** `srcEaFor` compares the whole
    opword against `F27F` and muxes the EA field and the word vector for every slot, every
    cycle (`MicroOpAssembler.scala:862-873`). This is one ROM-specific opcode on the shared
    EA-decode input.

**IPC**

13. **Sequenced instructions cost at least 2 bubbles and block co-issue.** The causes are the
    cold-hint drain of `fed` (`:3341`) plus a begin cycle with no push (`:3033-3089`). The
    µcode ROM is addressed with `ucNextPc`, so the first row is available on the cycle after
    `ucBegin`. The FSMs could emit their first µop on the begin cycle from the combinational
    entry context. This matters most for MEMINDIRECT-EA instructions, MOVEM (ROM
    prologues and epilogues) and FMOVEM.X.
14. **A stash replay emits only the stashed instruction** (`slot1Emit` requires
    `!stashValid`, `:537`). After any 3-µop instruction followed by another instruction, the
    next group's slot 0 waits a full cycle. For example, slot 0 = RMW and slot 1 = 1 µop: the
    replay pushes 1 µop where up to 3 more could fit.

**Stale comments** (the code is authoritative; these mislead readers)

15. Several comments contradict the code:
    - The `DecodeStage` class doc says each slot produces "a 1–2 µop sequence"
      (`DecodeStage.scala:14-15`). `count` is 1–3.
    - The `EaDecoder` header says indexed modes "stay MEMCOMPLEX so the assembler keeps them
      `unimplemented`" (`EaDecoder.scala:7-14`). Brief-indexed decodes to MEMSIMPLE and the
      full format to MEMSIMPLE or MEMINDIRECT.
    - The `OperationDecoder` header says "memory/complex forms are marked illegal"
      (`OperationDecoder.scala:7-9`). Many are routed instead.
    - The `EaSpec.reg` comment says "temps 16/17". There are 4 temps, 16–19
      (`Isa.scala:18`).
