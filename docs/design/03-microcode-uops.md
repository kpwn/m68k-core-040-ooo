# 03 — Micro-op format, cracking, microcode ROM and the µop queue

Source tree: commit `fee95f6f` (the bitstream on the board). All paths are relative to
the repo root. Code paths below are `src/main/scala/m68k040/...`, shortened to `m68k040/...`.

**Method.** This document is based on the **code only**. Source comments and `docs/*`
were not used as evidence: every size, count, latency and liveness claim below comes from
the code (enumerations, `Desc(...)` rows, `when`/`Mux` trees, `count :=` assignments,
`require`s, and caller graphs found with grep). Where a number needed arithmetic (ROM row
counts, bundle widths), the arithmetic is shown. Where a claim cannot be settled from the
code alone, it is marked **(unverified)**. Where a source comment contradicts the code, the
contradiction is listed in §7.6.

Files in this slice:

| File | Lines | Role |
|---|---|---|
| `m68k040/decode/MicroOpAssembler.scala` | 4530 | Combinational cracker: one fetched instruction to 1–3 `DecodedUop`s, plus the µop builders that the MOVEM, MOVEP and FMOVEM.X FSMs use |
| `m68k040/decode/Microcode.scala` | 3690 | Microcode ROM content (`Desc` rows), its hardware encoding (`DescBits`), the latched context (`Ctx`), and the row resolver (`resolveFromBits`) |
| `m68k040/decode/MicroOpQueue.scala` | 189 | 16-entry µop ring between decode and rename: up to 4 pushed per cycle, up to 2 popped per cycle |
| `m68k040/decode/RegListWalk.scala` | 85 | Priority-extract helpers for register-mask walks (MOVEM, FMOVEM.X) |
| `m68k040/types/MicroOp.scala` | 46 | **Dead.** Only test code uses it (§7.1) |
| `m68k040/types/PredecodeMeta.scala` | 19 | **Dead.** Only test code uses it (§7.1) |

The real micro-op type is `DecodedUop`, in `m68k040/decode/DecodedUop.scala`. That file is
not in the slice, but it defines the µop format, so §2.3 covers it. The sequencers that
drive the ROM and the FSMs live in `m68k040/decode/DecodeStage.scala`. This document
describes only the parts of that file that sequence µops.

---

## 1. Purpose and position

```
FetchAlign ──► [raw reg] ──► [fed reg: packets(0..1) + offloaded OpSpec/EAs]
                                   │
                ┌──────────────────┼──────────────────────────────────────┐
                │  MicroOpAssembler.assemble × 2   (slot0 → a0, slot1 → a1raw)
                │  Microcode engine (ucRomMem BRAM + resolveFromBits)     │  DecodeStage
                │  MOVEM FSM / MOVEP FSM / FMOVEM.X FSM                   │
                └──────────────► pushProduced (≤4 µops) ──► pushReg (PipeStage)
                                                                  │
                                              MicroOpQueue (16, LUTRAM, 4 banks)
                                                                  │ pop ≤2/cycle (async read)
                                    widen imm (ImmEnc) + expand brPredTag
                                                                  │
                                                          RenameStage (du.uops)
```

- Two assembler instances run on the registered `fed` packets:
  `DecodeStage.scala:328-329` (`a0 = assemble(packets(0), specs(0), fuseLongMoveLoads)`,
  `a1raw = assemble(packets(1), …)`).
- Anything that cannot be expressed in at most 3 µops goes to a **sequencer** that holds
  `fed` and emits µops over several cycles: the microcode ROM engine (1 µop/cycle), the
  MOVEM FSM (1–2 µops/cycle), the MOVEP FSM (1/cycle) or the FMOVEM.X FSM (1/cycle). The
  four sources and the normal crack path are merged in one `when/elsewhen` chain
  (`DecodeStage.scala:3035-3105`).
- The merged push goes through a register (`pushReg = PipeStage(pushProduced, pipeFlush)`,
  `DecodeStage.scala:3116`) and then into the `MicroOpQueue` (`DecodeStage.scala:218`,
  `3192-3195`).
- On the pop side, `DecodeStage.scala:3826-3832` re-expands the narrow immediate and the
  branch-prediction tag. `RenameStage` consumes the result as the `DecodeUopService.uops`
  stream (`services/Services.scala:753-755`; `rename/RenameStage.scala:189-191`).

---

## 2. Interfaces

### 2.1 Services and Global keys

None of the six slice files is a plugin, so none of them produces a `Global` key or
provides a `host[Service]`. They are plain Scala objects and Components that
`DecodeStage` instantiates. `DecodeStage` exposes the µops through `DecodeUopService`
(`uops: Stream[Vec[DecodedUop]]` of length 2, plus `uop1Valid`; `Services.scala:753-755`).

Compile-time constants these files read:

| Constant | Value (code) | Used for |
|---|---|---|
| `Isa.ARCH_INT_REGS` | 20 (`isa/Isa.scala:18`) | Architectural int-register space: D0–7 = 0–7, A0–7 = 8–15, temps T0–T3 = 16–19 (`MicroOpAssembler.scala:45-48`) |
| `Global.WIDE_IMM_TABLE_DEPTH` | 32 (`Global.scala:254`) | Depth of the `wideImm` group and push banks (`DecodeStage.scala:355-398`) |
| `Global.BR_PRED_TABLE_DEPTH` | 64 (`Global.scala:257`), so `BR_PRED_TAG_W` = 6 | `DecodedUop.brPredTag` width |
| `Global.FP_IMM_TABLE_DEPTH` | 16 (`Global.scala:181`), so the tag is 4 bits | FP 80-bit immediate side table, tag carried in `imm` |
| `ImmEnc.WIDTH` | 2 + 16 = 18 (`decode/ImmEnc.scala:54-56`) | Width of `imm` inside the decode record and queue |

### 2.2 Inputs to the cracker

`MicroOpAssembler.assemble(pkt: DecodePacket, off: Offload, fuseLongMoveLoads)`
(`MicroOpAssembler.scala:932-936`). `Offload` (`:720`) carries the `OpSpec` from
`OperationDecoder` and the pre-decoded source and destination `EaDescriptor`s. Both are
computed one stage earlier and arrive registered in `fed.payload.specs(i)`. The entry
`ucEntry` (9 bits) and the `microcoded` flag also come from `OpSpec`
(`DecodeContracts.scala:227,236`).

### 2.3 The micro-op format: `DecodedUop` (`decode/DecodedUop.scala:382-894`)

There is **one** µop record. Every producer builds it: the assembler, the microcode
resolver and the three FSM builders. Rename consumes it. Register fields are
architectural IDs; rename maps them to physical tags. The bundle is parameterized by
`immW`: 32 for the architectural record, 18 (`ImmEnc.WIDTH`) inside the decode record,
`pushReg`, the slot1 stash and the queue (`DecodeStage.scala:422-423`,
`MicroOpQueue.scala:105-108`).

Widths of the enumerations used in the record (binary-sequential encoding):
`DecOp` has 38 elements, so 6 bits. `Cluster {INT,EA,LS,CPLX}`, `Size {B,W,L}`,
`MemOp {NONE,LOAD,STORE}` and `EaAuto {NONE,POSTINC,PREDEC}` are 2 bits each.
`SysKind` has 15 elements and `FpSrcKind` 9, so 4 bits each.

| Field (line) | Width | Meaning (from producers and consumers) |
|---|---|---|
| `valid` (383) | 1 | Slot valid |
| `pc` (384) | 32 | PC of the macro instruction; every µop of the macro carries the same value |
| `lenWords` (402) | 4 | Instruction length in words. `def nextPc = pc + lenWords*2` (880) is computed by the consumer |
| `op` (403) | 6 | `DecOp`: MOVE, ADD, SUB, AND, OR, EOR, CMP, BRANCH, ILLEGAL, CHK, DIV, DIVREM, MUL, MULHI, SHIFT, CLR, NEG, NEGX, NOT, TST, SWAP, EXT, TAS, ADDX, SUBX, BCD, NBCD, BITOP, PACK, UNPK, BITFIELD, BFRESOLVE, CMP2CHK2, CASOP, MOVES, FPU, FPCTRLRD, FPSTORECVT (`DecodedUop.scala:7-163`) |
| `cluster` (404) | 2 | Issue cluster INT / LS / CPLX. `EA` is never assigned anywhere in `src/main` (§7.1) |
| `size` (405) | 2 | Operand size |
| `memOp` (406) | 2 | NONE / LOAD / STORE |
| `srcAReg/Valid`, `srcBReg/Valid`, `srcCReg/Valid`, `dstReg/Valid` (407-412) | 4×(5+1) | Architectural register IDs, 0–19. `srcC` carries the third source: the AGU index register, DIVL's high dividend, the BFINS insert source, the CAS status temp, or the FP chunk-2 temp |
| `useImm`, `imm` (445) | 1 + immW | Value immediate when `useImm`. Otherwise a tag or ID (FP-immediate tag, MOVEC Rc, FMOVEM mask). For a relative branch, the displacement. DecodeStage asserts that a redirecting relative branch never has `useImm` set (`DecodeStage.scala:3184-3188`) |
| `readsNzvc`, `readsX`, `writesNzvc`, `writesX` (446-447) | 4 | Flag rename controls (§3.9) |
| `isBranch`, `ibranch` (448, 455) | 2 | Branch-EU µop; `ibranch` = indirect target `psrcA + imm (+index)` |
| `anInc` (464) | 3 | Postincrement of A7 folded into RTS (4) and RTR (6) |
| `isReturn`, `isCall` (473, 483) | 2 | RAS and BTB classification |
| `cond` (484) | 4 | Condition code (Bcc / Scc / DBcc / TRAPcc) |
| `unimplemented` (485) | 1 | Copied into `RenamedUop` (`RenameStage.scala:253`); nothing in `src/main` reads it after that (§7.1) |
| `faulted`, `faultVector`, `faultUsesNextPc` (489-496) | 1+8+1 | Decode-time fault. The MOVEM builder reuses `faultVector` as a probe count (`MicroOpAssembler.scala` `movemMoveUop`, `u.faultVector := probeCount`) |
| `fpuSoftwareComplete`, `fpuCmdWord` (522, 532) | 1+16 | Capture for the FPSP unimplemented-instruction frame; consumed at ROB alloc (`rob/RobPlugin.scala:1074`) |
| `sswInstr`, `faultAtc` (545, 551) | 2 | Instruction-fetch fault SSW selectors |
| `isRte`, `isCondTrap` (553, 559) | 2 | RTE; TRAPV / TRAPcc evaluated in the branch EU |
| `divSigned`, `div64`, `divIsRem`, `isChk2` (566-574) | 4 | CPLX controls. **`divIsRem` is also the general "dropped crack µop" marker**: every `addUop(..., drop=True)` and every FSM temp write sets it |
| `stkPush` (581) | 1 | Predecrement push. The address is base − size, the int dst is the new An, and the data is `imm` or `srcB` |
| `eaAuto`, `eaDelta` (592-593) | 2+3 | (An)+ / −(An) side effect folded into an LS µop |
| `ccrRestore`, `toCcr` (599, 607) | 2 | RTR CCR pop; ANDI/ORI/EORI to CCR |
| `shiftOp`, `shiftDir`, `bcdSub`, `bitOp`, `bfOp`, `bfDynamic`, `bfMem`, `bfStoreForm`, `extByte` (612-669) | 2+1+1+2+3+1+1+3+1 | Sub-op selectors per family |
| `isMovea`, `isScc`, `isDbcc` (675-687) | 3 | `isMovea` is also reused as "sign-extend a .W load" on LS µops (MOVEM loads) |
| `firstOfInstr`, `lastOfInstr` (693, 709) | 2 | Macro boundaries (§3.8) |
| `debugBreakValid`, `debugBreakSlot` (713-714) | 1+2 | Stamped after `pushReg` (`DecodeStage.scala:3142-3165`) |
| `indexLong`, `indexScale` (721-722) | 1+2 | AGU index size and scale; the index register itself rides `srcC` |
| `leaAddr`, `movesAliasStore`, `altAddrSpace` (728, 740, 757) | 3 | Address-only µop; MOVES specifics |
| `fromCcr`, `fromSr`, `needsSupervisor`, `keepCommit` (764-774) | 4 | `keepCommit` feeds the lock-step `wbObs` observers in the EUs |
| `sysOp`, `sysKind`, `sysReadDir` (790-792) | 1+4+1 | Commit-time serializing system op (§5.2) |
| `brPredTag` (812) | 6 | Index into DecodeStage's 64×45-bit prediction table; expanded at pop |
| `casForm` (828) | 3 | CAS / CAS2 kernel select (`CasForm`, `:926-933`) |
| `fpSrcAReg/usesFpSrcA`, `fpSrcBReg/usesFpSrcB`, `fpDstReg/writesFp` (844-846) | 3×4 | Architectural FP registers |
| `readsFpcc`, `writesFpcc` (852) | 2 | Renamed FPCC |
| `fpuOp` (858), `fpSrcKind` (859), `fpSrcFmt` (866) | 7+4+3 | FP op, source routing and source format |

**Record width, computed from the table:** about **227 bits** at `immW = 18`, and about
241 at `immW = 32`. This assumes default binary enum encoding; it is not a synthesis
number. `DecodedUop.copyNoImm` (`:903-912`) moves every field except `imm` between the two
widths by name.

**`AssembledUops`** (`MicroOpAssembler.scala:50-71`): `uops: Vec(DecodedUop(), 3)`,
`count: UInt(2)` (1..3), `fpImmAlloc: Bool`, `fpWideImm: Bits(80)`.

---

## 3. Microarchitecture

### 3.1 Four ways a macro becomes µops

| Path | Selected by | µops/cycle | Cycles |
|---|---|---|---|
| **Fast crack** (combinational) | Default | slot0 + slot1 ≤ 4 | 0 extra |
| **Microcode ROM engine** | `slot0OwnedByUc` (`DecodeStage.scala:1243`): `OpSpec.microcoded`, a memory-indirect EA, a dynamic memory bit-field, or a memory-indirect bit-field | 1 | 1 entry cycle + N rows |
| **MOVEM FSM** | `OpSpec.movem` | 1 or 2 | 1 entry cycle + snapshot µops + ⌈n/2⌉ + final µop |
| **MOVEP FSM** | `OpSpec.movep` | 1 | 1 entry cycle + 3 / 7 / 7 / 11 steps |
| **FMOVEM.X FSM** | `slot0IsFmovemx` (`DecodeStage.scala:1113`) | 1 | 1 entry cycle + 4 per element (load) or 6 per element (store) + An update |

The "1 entry cycle" is visible in the code. On a begin cycle the sequencer only latches
its state. `normalHeadValid` excludes the owned slot0 (`DecodeStage.scala:1699`). The
`*Active` arm of the push chain is selected only from the next cycle. For the ROM engine,
`ucNextPc := ucRealEntry` is also the synchronous BRAM read address, so the first row is
available exactly when `ucActive` rises (`DecodeStage.scala:3626-3632, 3655-3658`).

### 3.2 Fast-crack table (code: the crack tree at `MicroOpAssembler.scala:4218-4488`)

The tree is a priority `when/elsewhen` chain. `count` comes from the arm that wins.

| Family / shape | µop sequence | Count |
|---|---|---|
| Register / immediate ALU, MOVE reg→reg, Bcc / BRA, DBcc, Scc Dn, TRAP, TRAPV / TRAPcc, RTE, NOP, Line-4 unary on Dn, MUL.W / DIV.W reg, ABCD / SBCD / ADDX / SUBX reg, PACK / UNPK reg, static register bit-field, FP reg→reg and FP #imm, FMOVE FPcr reg (sysOp), most sysOps | `[op]` | 1 |
| Illegal / bad (`bad`, `:2334`) | `[op]` made inert by `inertTrap` (`:1246`) | 1 |
| Fetch fault | `[op]` faulted | 1 |
| memSimple **source** (`crackLoad`, `:1166`) | `[load→T0][op reads T0]` | 2 |
| … with an (An)+ / −(An) source | `[load→T0][An±=δ (dropped ADD)][op]` | **3** |
| … `MOVE.L <memSimple>,Dn/An` (no auto), with `fuseLongMoveLoads` | `[load→Dn]` with `writesNzvc` taken from the op (`:4461-4471`) | **1** |
| MOVE reg→mem (`crackStore`) | `[store]`, with the destination An update folded in | 1 |
| MOVE #imm→mem | `[MOVE #imm→T1][store T1]` | 2 |
| MOVE mem→mem (`crackMemMem`) | `[load→T0][store]` | 2 |
| … with an auto source | `[load→T0][An±=δ][store]` | 3 |
| Memory-destination RMW (`crackRmw`): ADD / SUB / AND / OR / EOR Dn,<ea>; xxxI #imm,<ea>; ADDQ / SUBQ; NEG / NEGX / NOT / TAS <ea>; memory shift and rotate; BCHG / BCLR / BSET <mem> | `[load→T0][op→T1 +flags][store T1]` | **3** |
| TST / CMPI / CMP / BTST to memory (`crackLoadOnly`) | `[load][op]` or `[load][An±=δ][op]` | 2 / 3 |
| CLR <mem> (`crackClr`), Scc <mem> | `[op→T1][store]` | 2 |
| MOVE from SR / CCR to memory | `[op→T1][store.w]` | 2 |
| LEA | `[addr-gen→An]` (`leaAddr`) | 1 |
| PEA | `[addr→T0][push T0]` | 2 |
| JMP | `[ibranch]` | 1 |
| JSR | `[push retPC][ibranch]`; JSR (A7): `[T0:=A7][push][ibranch]` | 2 / 3 |
| BSR | `[push retPC][bra]` | 2 |
| RTS | `[load (A7)→T0][ibranch T0; A7+=4]` | 2 |
| RTD | `[load][A7+=4+d (dropped)][ibranch]` | 3 |
| RTR | `[pop.w → CCR][pop.l → T0][ibranch; A7+=6]` | 3 |
| LINK.W / LINK.L | `[push An; dst=A7][A7+=d (dropped)][An:=…]` | 3 |
| UNLK | `[load (An)→T0][A7:=An+4 (dropped)][An:=T0]` | 3 |
| EXG | `[T0:=Ra][Ra:=Rb][Rb:=T0]` | **3** |
| MUL.L / DIV.L | `[mul]`, `[mul][mulhi]`, `[div]`, `[div][divrem]`, plus a leading load for a memory operand | 1–3 |
| Static memory bit-field read (BFTST / BFEXTU / BFEXTS / BFFFO) | `[load.L→T0]([load.B→T1])[BITFIELD]` | 2 / 3 |
| Dynamic register bit-field | `[BFRESOLVE→T0][BITFIELD]` | 2 |
| CMP2 / CHK2 | `[load lo→T0][load hi→T1][compare]` | 3 |
| FMOVE.L #imm,FPcr | `[MOVE #imm→T0][sysOp]` | 2 |
| FSAVE / FRESTORE with a computed EA | `[addr→T0][sysOp]` | 2 |
| Dynamic FMOVEM (`isDynamicFmovem`, `:22-38`) | `[capture Dn mask→T0][addr→T1][sysOp FMOVEM_DATA]` | 3 |
| `microcoded` | Placeholder, not pushed (the ROM engine owns it) | — |

The MOVE.L load fusion fires only when every term of
`spec.op===MOVE && size===LONG && !srcAuto && !sysOp && !microcoded && opUop.dstValid &&
dstEa ∈ {DATAREG, ADDRREG}` holds (`:4463-4465`).

### 3.3 Microcode ROM (`Microcode.scala`)

**Structure.** The ROM is straight-line. A row has no next-address field: the next µPC is
`ucPc+1` until a row with `isLast` (`DecodeStage.scala:3630`). It is **not** a
combinational decoder. The rows are compile-time Scala `Desc` values (`Microcode.scala:310`).
`descToBits` (`:519`) converts them to `DescBits` (`:471`), which initialize
`ucRomMem = Mem(DescBits(), romSize)` with `rom_style = "block"` and a `readSync` read
(`DecodeStage.scala:2972-2984`). One `resolveFromBits` cone (`Microcode.scala:3303-3690`)
combines the registered row with the latched `Ctx` (`:2461`) to produce one `DecodedUop`
per cycle.

**Row encoding (`DescBits`), computed from the enums:**
`uop` has 21 values (5 bits), `mem` 3 (2), `auto` 11 (4), and the 5 selector fields
(`srcA`, `srcB`, `srcC`, `dst`, `effectiveImm`) have 66 values each (7 bits, so 35). `sz`
is 3 bits. There are 22 single-bit flags. `bfStoreForm` is 3 bits, `fpSrcKindSel` 2 and
`casForm` 3. Total ≈ **79 bits per row**.

**ROM size, computed from the code (not quoted from comments).** A Python pass counted the
`Desc(` constructors in `romP1..romP8` (`:685-1825`) after stripping `//` comments, giving
**252 rows**. The parameterized groups add:

| Partition | Generator | Rows |
|---|---|---|
| P9: FP memory-source load (`:1887-1923`, `:2089-2096`) | B/W/L/S/D/X have 1/1/1/1/2/3 chunks. Base group = chunks+1: 15. Post group = chunks+2: 21. Pre group: 21. Trap row: 1 | 58 |
| P10: FMOVEM control list (`:2156-2233`) | Popcount n = 1..3. Load: n+1, n+2, n+2. Store: 2n, 2n+1, 2n+1 | 9+12+12+12+15+15 = 75 |
| P11: FMOVE FPn,<ea> (`:1966-2000`) | 2c, 2c+1, 2c+1 | 18+24+24 = 66 |
| P12: FP memory-indirect (`:2049-2087`) | Load 2+c+1 = 27, store 2+2c = 30 | 57 |
| Scc memory-indirect (`:2246-2252`) | 3 rows | 3 |
| **Total** | | **511** |

`ucPc`, `ucNextPc` and `OpSpec.ucEntry` are 9 bits, and `require(rom.size <= 512)`
(`Microcode.scala:2358`). **The ROM uses 511 of its 512 addresses.** The 79-bit row is
wider than a single RAMB36 in 512×72 mode, so the ROM needs more than one BRAM36
(**(unverified)**: which primitive mix Vivado actually picks).

**Program map for rows 0–251**, reconstructed from the parsed rows
(`isFirst`/`isLast`/`mem`/`uop`), with entry constants from `Microcode.scala:2374-2424`:

| Entry (row) | Program | Rows = µops | Shape |
|---|---|---|---|
| `BCD_MEM_ENTRY` 0 | ABCD / SBCD / ADDX / SUBX −(Ay),−(Ax) | 6 | L, +Ay, L, +Ax, op(+flags), S |
| `BF_RMW_4B_ENTRY` 6 | BFCHG / CLR / SET / INS mem, 4-byte span | 3 | L, compute, S |
| `BF_RMW_5B_ENTRY` 9 | Same, 5-byte span | 7 | L, L, 3×compute, S, S |
| 16 / 19 / 21 / 24 / 28 | Memory-indirect MOVE src / MOVE dst / ALU src / RMW / flags | 3 / 2 / 3 / 4 / 3 | ptr-load + host load / store / op |
| `CAS_ENTRY` 31 | CAS | 4 | L, CASS, CASC, S |
| `CAS2_ENTRY` 35 | CAS2 | 10 | 2L, C1, C2, DC×2, SEL×2, 2S |
| `MOVES_WRITE_ENTRY` 45 / `MOVES_READ_ENTRY` 46 | MOVES | 1 / 3 | S (keepCommit) / L, An, MovesRead |
| 49 / 53 / 59 | Dynamic bit-field read: DO0 / DO1 / FFO-DO1 | 4 / 6 / 7 | |
| 66 / 74 | Dynamic bit-field RMW: DO0 / DO1 | 8 / 12 | |
| `BF_DYN_ILLEGAL_ENTRY` 86 (also `MI_UNSUPPORTED_ENTRY`) | vector-4 fail-safe | 1 | |
| 87 / 98 | Dynamic BFINS: DO0 / DO1 | 11 / **17** | |
| `CMPM_ENTRY` 115 | CMPM (Ay)+,(Ax)+ | 5 | L, +Ay, L, +Ax, CMP (`nzvcOnly`) |
| 120 / 123 / 126 / 127 | Memory-indirect MOVE EA↔EA / reverse / illegal / #imm destination | 3 / 3 / 1 / 3 | |
| 130 / 135 / 142 | Memory-indirect bit-field: read DO0 / read DO1 / RMW DO0 | 5 / 7 / 10 | |
| `MI_BF_INS_DO0_ENTRY` 152 | **No reference outside its own definition** | 14 | dead (§7.1) |
| `PACK_MEM_ENTRY` 166 / `UNPK_MEM_ENTRY` 172 | PACK / UNPK −(Ay),−(Ax) | 6 / 6 | |
| 178 / 184 | (d16,PC) dynamic bit-field read DO1 / FFO | 6 / 7 | |
| 191 / 193 / 196 / 198 | Memory-indirect LEA / PEA / JMP / JSR | 2 / 3 / 2 / 3 | |
| 201 / 212 / 226 | Memory-indirect bit-field: RMW DO1 / INS DO1 / INS DO0-v2 | 11 / 14 / 12 | |
| 238 | Memory-indirect MOVE with both sides memory-indirect | 4 | |
| `MOVE16_ENTRY` 242 | MOVE16 (Ax)+,(Ay)+ | **10** | 2L, 2S, 2L, 2S, +Ax, +Ay (4 LONG transfers) |

**FP programs (P9–P12)**, from the generators:

- Memory-source FP op: `[load chunk]×c [UFpIssue]`, plus one dropped `UAddDrop` for (An)+ or −(An).
- FMOVE FPn,<mem>: `[UFpStoreCvt]×c [store]×c`, plus the An update.
- FMOVEM control list, load: `[load with fpCtrlCap]×n [UFpCtrlRead…] [one terminal sysOp FMOVE_FPCTRL]`.
- FMOVEM control list, store: `[UFpCtrlRead]×n [store]×n`.
- FP memory-indirect: `[ptr-load→T3][LeaFinal→T3]` followed by the chunk rows.

**Entry selection.** `OperationDecoder` provides a default `ucEntry` for 11 families
(`OperationDecoder.scala:185-1507`). `DecodeStage` then overrides it with `ucRealEntry`
(`DecodeStage.scala:2935-2957`). That is a 9-deep priority `Mux` chain:

1. FP memory
2. Memory-indirect bit-field RMW
3. Memory-indirect bit-field read
4. Memory-indirect (other)
5. Dynamic bit-field read
6. Dynamic bit-field RMW
7. Static bit-field RMW (4-byte vs 5-byte)
8. MOVES
9. `spec.ucEntry`, or `MI_UNSUPPORTED_ENTRY` as the fail-safe

**Resolver.** `resolveFromBits` maps each selector through `selRegHw`, a 66-way switch to
a 5-bit register ID plus valid (`:3202-3240`). That switch is instantiated 4 times (srcA,
srcB, srcC, dst). `selImmHw` (`:3249-3295`) is a 40-arm 32-bit mux and includes two
32-bit adders (`SFpDispMid`/`SFpDispHi` = `eaDispLo + 4/8`).

**`Ctx`** (`:2461-2622`) is latched in full on `ucBegin` (`DecodeStage.scala:3657`). It has
19 fields of 32 bits (`pc`, `nextPc`, `eaDispLo/Hi`, `bfPcRelConst`, `bfImm`, `bfResImm`,
`bfMiDispLo/Hi`, `miOd`, `miHostImm`, `movesDelta`, `miOtherEaDispLo`, `miOtherOd`,
`packAdj`, `fpAutoDelta`, `fpCmd`, `fpCtrlRc`, `fpCtrlDelta`) plus roughly 200 bits of
narrow fields, so about **800+ flops**.

**Reference oracle.** `Microcode.resolve(Desc, …)` and `selReg`/`selImm` (`:2628-2725`)
have **no caller in `src/main`**. Only tests call them, so they cost no hardware.

### 3.4 Throughput into the queue

- **Normal path:** up to 2 instructions per cycle. `slot1Emit` (`DecodeStage.scala:537`)
  requires all of the following: no stash replay in progress, slot1 present, neither
  slot's crack is 3 µops, slot1 is not a MOVEM / MOVEP / microcoded / memory-indirect /
  dynamic-bit-field instruction, and slot1 is not an FP immediate. Otherwise slot1 is
  stashed (`deferSlot1`, `:542`) or packet-pended, and replayed **alone** on the next
  cycle. `slot1Emit` requires `!stashValid`, so a replay cycle never pairs. The push count
  is `nCur + n1`, at most 4 (`:742-749`).
- **Sequencers:** count 1 per cycle (ROM, MOVEP, FMOVEM.X), and MOVEM 2 per cycle except
  the odd tail, the snapshot µops and the final µop (`:855`, `:3035-3090`).
- The ROM engine and the FSMs hold `fed` (`fed.ready` excludes `*HoldsFed`,
  `DecodeStage.scala:3298-3301`). When the sequencer finishes, the held following group is
  decoded normally the next cycle.

### 3.5 `MicroOpQueue` (`MicroOpQueue.scala`)

- **Depth** 16, set at the instance (`DecodeStage.scala:218`). `require(isPow2)`.
- **Storage:** 4 banks (`bank = addr[1:0]`), each a `Mem(DecodedUop(18-bit imm), 4 rows)`
  with `ram_style = distributed` (`:154-164`). Each bank has 1 write port and **2 async
  read ports** (rows for `head` and `head+1`, `:183-184`), followed by a 4:1 bank select
  for each of the 2 outputs (`:185-186`).
- **Push:** `count` 0..4, compacted at `tail`. Bank `b` receives push slot
  `(b − tail) mod 4` when that slot index is below `count` (`:204-215`).
  `ready = freeSlots >= 4` (`:171-172`), independent of `count`.
- **Pop:** `valid = count>0`, `pop1Valid = count>1`. A fire pops 2 if `count>1`, else 1
  (`:178-188`). Pop is all-or-nothing on the 2-wide Vec; there is no partial accept.
- **Flush:** resets `head`, `tail` and `count` (`:229-233`). Storage contents are left
  as they are.
- **Sim-only:** a register-ring shadow and a bit-equality assert (`:201-253`), with no
  synthesis cost (`GenerationFlags.simulation`).
- **Latency:** `pushProduced` (cycle E) → `pushReg` (E+1) → ring write at the end of E+1
  → `pop.valid` from the registered `count` at **E+2**. There is no push-to-pop bypass.

### 3.6 Register-list walking (`RegListWalk.scala`)

- `extractLowest2(mask)` (`:39-47`): two cascaded `OHMasking.first` passes, plus
  `mask & (mask-1)` twice. MOVEM uses it at `DecodeStage.scala:838`.
- `extractHighest1(mask)` (`:64-68`): `OHMasking.last`. Used by the FMOVEM.X FSM
  (`DecodeStage.scala:1553,1555,1675`) and `execute/fpu/FmovemColdWalk.scala:57`.
- `regOf(bit, reverse, fwd, rev)` (`:83-84`): a 2:1 mux between caller-supplied maps.
  MOVEM uses `15-bit` (`:849`), FMOVEM.X uses `7-bit` (`:1543`).
- `extractLowest1` and `extract1` (`:54-75`) have **no caller in `src/main`**.

The MOVEM FSM, from code:

- An An-based **control** EA always gets a base snapshot µop (`eNeedBaseSnap`,
  `DecodeStage.scala:1355`). An indexed EA also gets an index snapshot.
- After that come ⌈n/2⌉ move cycles.
- Every An-based mode ends with a final An-update µop, including the delta-0 control
  modes (`movemHasFinal := eBaseValidV(0)`, `:3395`).
- An empty mask enters and exits in the same cycle (`:3456-3459`).

### 3.7 Temporaries

T0–T3 are architectural registers 16–19 and are renamed exactly like D/A registers
(`MicroOpAssembler.scala:45-48`; `ARCH_INT_REGS = 20`). The fast crack uses T0 and T1 (T0
for loads and PEA, T1 for RMW results, #imm staging and JSR snapshots). The ROM uses
T0–T3. MOVEP uses T0 (scratch) and T1 (accumulator). MOVEM uses T0 and T1 as the base and
index snapshot. FMOVEM.X uses T0–T2 as chunk temps. No µop frees a temp explicitly: the
next write releases the old physical register through the normal rename path.

### 3.8 First / last marking

- **Fast crack:** each builder sets `firstOfInstr`. For example, `ldUop` is True
  (`:1682`) and `opUop` is `!opHasLeadingLoad` (`:1231`). `lastOfInstr` is stamped once,
  after the crack tree, as `count === i+1` (`:4512-4515`).
- **ROM:** `firstOfInstr := d.isFirst`, `lastOfInstr := d.isLast` (`Microcode.scala:3684-3688`).
- **MOVEP:** `lastOfInstr := movepLast` (`DecodeStage.scala:1489`).
- **MOVEM / FMOVEM.X:** set per builder argument.
- **Dropped µops** (`divIsRem = True`) still write the PRF. The marker only affects
  lock-step observation and the DIVREM datapath.

### 3.9 Flags (NZVC / X)

A µop carries four booleans: `readsNzvc`, `readsX`, `writesNzvc`, `writesX`. NZVC is
renamed as **one** 4-bit unit and X as a separate one. A µop that writes only some of
N, Z, V, C must declare `readsNzvc` so that the EU can merge the bits it does not compute.
Examples: BITOP (`OperationDecoder.scala:145-146`), CMP2/CHK2 (`:115-116`), the
clear-only-Z family ABCD/SBCD/ADDX/SUBX/NEGX/NBCD (`:1014-1047`), and CAS2C2
(`Microcode.scala` `casReadsNzvc`).

ROM rows express flags through three Desc bits (`Microcode.scala:3477-3482`):

- `writesFlags` reads NZVC and X and writes NZVC and X.
- `nzvcOnly` writes NZVC only.
- `bfWritesNz` writes NZVC with no read.

`UMiHostMove` writes NZVC only when `miMoveFlags && ctx.miWNzvc`. `UCasOp` uses its own
read/write masks. RTR's CCR restore is an LS µop with `ccrRestore` and
`writesNzvc = writesX = True`.

FP condition codes travel in `readsFpcc`/`writesFpcc`. The only ROM reader is
`UFpCtrlRead` (`Microcode.scala:3633-3635`).

---

## 4. Configuration knobs

| Knob | Where | Default | Shipping | Effect |
|---|---|---|---|---|
| `fuseLongMoveLoads` | `DecodeStage(…)` → `assemble` (`MicroOpAssembler.scala:933, 4461`) | false | **true** (`= ipcThroughput`, `top/ShippingPlugins.scala:110-111`; build log `ipcThroughput=true`) | `MOVE.L <memSimple>,Rn` becomes 1 µop |
| `allowSlot1Prediction` | `DecodeStage` (`:33`; prediction write at `:711-713`) | false | **true** (same line) | Lets the prediction-table write come from slot1's packet |
| `MicroOpQueue(depth)` | `DecodeStage.scala:218` | 16 | 16 (literal) | Must be a power of two and a multiple of 4 |
| `ImmEnc.WIDTH` | `ImmEnc.scala:54-56` | 18 | 18 | Width of the queue/record `imm` |
| `WIDE_IMM_TABLE_DEPTH` / `BR_PRED_TABLE_DEPTH` / `FP_IMM_TABLE_DEPTH` | `Global.scala:254/257/181` | 32 / 64 / 16 | same | Side-table depths |

`Microcode.scala`, `RegListWalk.scala` and `MicroOpQueue.scala` have no other parameters.
None of the `SHIPPING_CONFIG` flags in the build log is read by these six files.

Dead code (no caller in `src/main`): `types/MicroOp.scala`, `types/PredecodeMeta.scala`,
`RegListWalk.extractLowest1/extract1`, `Microcode.resolve/selReg/selImm` (test oracle),
ROM rows 152–165, `Cluster.EA`.

---

## 5. Performance-relevant behavior

### 5.1 Expansion ratios (µops per instruction) for common instructions

| Instruction | µops | Decode-pairing effect |
|---|---|---|
| `move.l d0,d1`, `add.l d0,d1`, `addq`, `moveq`, `bcc`, `dbcc`, `jmp`, `lea` | 1 | Pairs |
| `move.l (a0),d0`, `move.l d16(a0),d0` (shipping, fused) | **1** | Pairs |
| `move.w (a0),d0`, `add.l (a0),d0`, `cmp.l (a0),d0`, `tst.l (a0)` | 2 | Pairs if the partner has ≤ 2 µops |
| `move.l (a0)+,d0` | **3** (the source An update is a separate ADD; `srcAuto` disables fusion) | **Forces single-issue that cycle plus a replay** |
| `move.l d0,(a1)+`, `move.l d0,-(sp)` | 1 (destination An folded into the store) | Pairs |
| `move.l (a0)+,(a1)+` (memcpy) | 3 | Single-issue |
| `add.l d0,(a0)`, `addq.l #1,(a0)`, `not/neg <mem>`, `bset <mem>`, memory shift | 3 | Single-issue |
| `bsr`, `jsr <ea>`, `rts`, `pea` | 2 | Pairs |
| `rtr`, `rtd`, `link`, `unlk`, `exg` | 3 | Single-issue |
| `movem.l dX-dY,-(sp)` (n regs) | n+1 over 1+⌈n/2⌉+1 cycles | Sequencer; holds `fed` |
| `movem.l (sp)+,…` | n+1 | Same |
| `movem.l d16(a6),…` (control mode) | n+2 (snapshot + delta-0 final) | Same |
| ABCD / ADDX −(Ay),−(Ax) | 6 µops / 7 cycles | ROM |
| CMPM | 5 / 6 | ROM |
| CAS / CAS2 | 4 / 10 (+1 entry cycle each) | ROM |
| MOVE16 | 10 / 11 | ROM |
| MOVEP .W / .L | 3 or 7 / 7 or 11 (+1) | FSM |
| `fadd.x (a0),fp0` | 4 (3 loads + issue) / 5 | ROM |
| `fmove.x fp0,(a0)` | 6 / 7 | ROM |
| FMOVEM.X n registers | 4n or 6n (+An update) | FSM |
| Memory-indirect `([bd,An],od)` source ALU op | 3 / 4 | ROM |

The queue drains at most 2 µops per cycle (`MicroOpQueue.scala:188`). Any 2-µop
instruction therefore takes at least 1 rename cycle by itself, and a 3-µop instruction
takes 1.5.

### 5.2 Serializing sequences

These come from the µop fields; the retire-side behavior is described in the ROB
document.

- **`sysOp = True` µops**, which retire alone at commit: MOVE to SR, ANDI/ORI/EORI to SR
  (`MicroOpAssembler.scala:2703`), MOVE USP, MOVEC, RESET, STOP, CPUSH/CINV, PFLUSHA,
  PTEST, FSAVE/FRESTORE (`OperationDecoder.scala:675-1348`), FMOVE FPcr
  (`MicroOpAssembler.scala:2653`), the dynamic FMOVEM `FMOVEM_DATA` (`:4214`), and the
  terminal row of every FMOVEM control-list load program (`Microcode.scala:2147-2149`).
- **`isRte`.**
- The other decode-side serialization is the hold of `fed` while a sequencer runs, which
  stops all younger decode.

### 5.3 Decode cycles lost

- One **entry bubble** per sequencer instruction (§3.1). For MOVEM this is 1 cycle out of
  1+⌈n/2⌉+1.
- Every **3-µop crack** costs one instruction-pair slot: its partner replays alone on the
  next cycle (`DecodeStage.scala:537-542`). This includes (An)+ source loads,
  memory-destination RMW, EXG, LINK/UNLK, RTR/RTD and JSR (A7).
- An **FP #imm in slot1** is always deferred, because the FP immediate side table has a
  single head-only write port (`a1raw.fpImmAlloc` in `slot1Emit`/`deferSlot1`). If the
  table is full (`fpImmTable.stall`), the normal path stalls (`DecodeStage.scala:586`, `:1699`).
- The **minimum fed→rename latency is 2 cycles** even when the queue is empty (§3.5).
  Every front-end restart after a mispredict pays this.

---

## 6. Area and timing notes (inferred from the RTL, not from reports)

- **Two full `assemble` instances** (`DecodeStage.scala:328-329`), each the entire
  4.5k-line combinational cracker. Each contains several local `EaDecoder.decode` calls on
  shifted word views: `immEa` (`:989`), `coldEa` (`:4198`), the DIVL/MULL/bfm/CMP2
  re-decodes, plus `computeOffload`'s two decodes one stage earlier.
- **Queue:** 4 banks × 4 rows × about 227 bits in LUTRAM with 2 read ports. This is
  roughly 38 RAM32M per bank (at 6 bits per RAM32M), so about 150 RAM32M (≈600 LUTs)
  **(unverified estimate)**, plus two 227-bit 4:1 bank-select muxes.
- **Pop path:** queue async read → `wideImm.widen` (a second async LUTRAM read, indexed by
  the tag just read, then a 6:1 slot mux) → `brPredTable.expand` (a third async read) →
  `RenameStage` combinational accept (`DecodeStage.scala:3826-3832`;
  `RenameStage.scala:189-191`). That is three LUTRAM reads in series on one path, two of
  them dependent.
- **Push path:** the deep `assemble` cone ends at `pushReg` (`DecodeStage.scala:3116`).
  The `wideImm` and `fpImmTable` LUTRAM writes are registered one cycle late
  (`:378-381, 397-399, 591-593`).
- **ROM:** BRAM `readSync` with no output register (`DecodeStage.scala:2983-2984`), so
  BRAM clock-to-out plus `resolveFromBits` (four 66-way register selects, a 40-way 32-bit
  immediate mux with two adders) plus the `wideImm.push` narrowing all land in the cycle
  that ends at `pushReg`.
- **Storage:** `Ctx` ≈ 800+ flops; the slot1 stash is 3 narrow µops (`:430`); `pushReg`
  is 4 narrow µops. The PUSH-bank wide immediates are 2×32 bits × 32 entries; the group
  bank is 6×32 = 192 bits × 32 entries (`ImmEnc.scala:103-104`).
- **Every assembler µop's `imm` goes through the group bank:** `wideImm.grp` always
  encodes mode `WIDE` (`DecodeStage.scala:403-404`), even when the value would fit the
  16-bit `SEXT`/`ZEXT` payload.

---

## 7. Observations (factual, with citations; no fixes applied)

### 7.1 Dead or near-dead logic

1. **`types/MicroOp.scala` and `types/PredecodeMeta.scala` are unused in `src/main`.**
   Only `MicroOpSpec` and `RobEntrySpec` reference them. `MicroOp` describes a different
   design (`uopIdx` "up to 4 µops", `staticLatency`, `sqPtr0/1`, `twoAccess`) that the
   real `DecodedUop`/`RenamedUop` do not follow. It is misleading documentation in code
   form.
2. **ROM rows 152–165** (`MI_BF_INS_DO0_ENTRY`, `Microcode.scala:2379`) are not
   referenced anywhere but their definition. These are 14 dead rows in a ROM that is 511/512
   full. Removing them would require renumbering the hand-written literal entries 166–251
   (`:2374-2424`).
3. **`RegListWalk.extractLowest1` and `extract1`** have no callers.
4. **`DecodedUop.unimplemented`** is written to `RenamedUop.unimplemented`
   (`RenameStage.scala:253`), and nothing reads it after that. Synthesis may prune it
   after rename, but it still rides the queue and the stash.
5. **`Cluster.EA`** is never assigned.
6. **`Microcode.resolve`/`selReg`/`selImm`** (~600 lines) are test-only. They cost no
   hardware.

### 7.2 Likely mistakes or latent hazards

1. **`firstOfInstr` can be False on a lone illegal µop.** `opUop.firstOfInstr :=
   !opHasLeadingLoad` (`MicroOpAssembler.scala:1230-1231`). The `bad` arm (`:4245-4248`)
   emits `opUop` alone (count 1), and `inertTrap` (`:1246-1262`) does not reset
   `firstOfInstr`. If `bad` and `crackLoad`, `crackRmw` or `crackLoadOnly` are both true,
   the macro has no first µop. The arm `(usesDstEa && !dstOk)` with a memSimple source is
   a candidate, and `!pkt.simple` with a memory source is another. `lastOfInstr` was moved
   to a count-based stamp (`:4512-4515`) exactly to avoid this class of bug;
   `firstOfInstr` was not. Reachability is **(unverified)**.
2. **The ROM has one free address** (511/512). The next ROM customer forces widening
   `ucPc`, `ucNextPc` and `ucEntry` to 10 bits. Rows 0–251 use literal entry constants;
   only P9+ use computed (`scanLeft`) offsets. The only guard against a stale literal is a
   simulation assert (`DecodeStage.scala:3646`).
3. **MOVE16 addresses are not 16-byte aligned in the ROM rows.** Rows 242–251 use `SAy` /
   `SMove16Ay` with offsets 0/4/8/12 directly, and nothing in the rows masks the low 4
   bits. Whether the LS-EU aligns a MOVE16 access is **(unverified)**. If it does not,
   MOVE16 with an unaligned An differs from the architected behavior.
4. **The MOVEM final µop is always emitted for An-based control modes** (delta 0,
   `movemHasFinal := eBaseValidV(0)`, `DecodeStage.scala:3395`). The control modes also
   always emit a base snapshot. `movem.l d16(a6),regs` therefore costs 2 extra µops and
   2 extra cycles.

### 7.3 Cheap IPC opportunities

1. **(An)+ source loads are 3 µops** and therefore never pair. `crackLoad` with `srcAuto`
   emits `[load][ADD An][op]` (`MicroOpAssembler.scala:4452-4459`), and fusion is
   disabled by `!srcAuto` (`:4463`). The destination side already folds the An update
   into the store (`eaAuto` on `stUop`). The LS µop has a single int destination, which
   is why the load cannot fold it. Two ways to remove the extra µop and the pairing loss
   on the hottest 68k idiom (`move.l (a0)+,dN`, memcpy, string loops):
   - an LS µop with a second destination for the An writeback (the LSU already has
     `lsEarlyAnRetry`/early-An writeback machinery downstream), or
   - letting a 3-µop crack pair with a following 1-µop instruction (3+1 ≤ 4 fits the
     4-wide push).
2. **`slot1Emit` refuses any 3-µop crack in either slot** (`DecodeStage.scala:537`), even
   when the total is ≤ 4 (3+1 or 1+3). The queue and push are 4-wide (`nCur +^ n1` ≤ 4
   would still hold). The limit is the stash/packing structure (positions 0..3), not the
   width.
3. **EXG costs 3 µops** through T0 (`MicroOpAssembler.scala:4075-4077`). A rename-time
   RAT swap would make it 0 or 1 µop. This matters less than items 1 and 2.
4. **No empty-queue bypass**: fed→rename takes at least 2 cycles (§3.5), and every
   restart pays it. A bypass from `pushReg` to the pop port when `count == 0` would cut 1
   cycle per redirect. Cost: a 2:1 mux on the pop path, which already has the three
   LUTRAM reads in series described in §6.
5. **The sequencer entry bubble** (§3.1) could be overlapped by starting the ROM read
   from the pre-register entry. This is minor for long programs and 1 of 3–4 cycles for
   memory-indirect programs.
6. **Partial-flag writers serialize on NZVC.** BTST / BCHG and the clear-only-Z family
   read NZVC (§3.9), so a BTST in a loop depends on the previous flag producer. Splitting
   Z, or treating "Z-only" writes as a merge done at rename, would remove that edge. This
   is an architectural change (per AGENTS.md it needs a spec update).

### 7.4 Cheap area and congestion opportunities

1. **Rarely used fields ride every queue entry:** `fpuCmdWord` (16 bits),
   `fpuSoftwareComplete`, `faultVector` (8), `sswInstr` / `faultAtc`, `casForm` (3), the
   bit-field group (≈11) and the FP group (≈28). They are present in all 16 entries ×
   4 banks × 2 read ports, plus `pushReg`, the stash and rename's input. The code already
   moved `predTaken/Target/phtIndex` (`brPredTag`) and the 80-bit FP immediate to side
   tables keyed by a tag. `fpuCmdWord` is captured only for FPSP frames and is the most
   obvious next candidate.
2. **`pc` (32 bits) is per µop** in a 16-entry queue. All µops of a macro share it.
3. **`Ctx` has 19 separate 32-bit fields**, many mutually exclusive by program family
   (for example `bfMiDispLo` / `packAdj` / `movesDelta` / `fpCtrlDelta` / `miHostImm`).
   A few shared 32-bit "immediate slots" filled at `ucBegin` would shrink both the flop
   count and the `selImmHw` mux. The `eaDispLo + 4/8` adders (`:3283-3284`) could also be
   precomputed into `Ctx` at entry, which would take two 32-bit adders off the BRAM→pushReg
   path.
4. **The group bank always stores 32 bits per slot** even for small immediates (§6, last
   bullet). Routing the values that fit 16 bits through `SEXT`/`ZEXT` in the record would
   leave the bank RAM unchanged (it is written whole each cycle), but lets the pop path
   skip the second-level read for most µops. That is a timing win, not an area win.
5. **The two `assemble` instances** are inherent to 2-wide decode. Hoisting further
   EaDecoder re-decodes (DIVL/MULL/bfm/CMP2/`coldEa`) into the pre-register `Offload`
   would shorten the slot cones. This is a trade-off: it increases the `fed` register
   width.

### 7.5 Queue-specific notes

- `ready = free ≥ 4` even when the sequencers push 1 µop per cycle
  (`MicroOpQueue.scala:172`). That wastes up to 3 entries of capacity, a mild loss.
- Each bank reads two rows (`headRow`, `head1Row`, `:183-184`). `head` and `head+1` are
  always in different banks, so each bank needs only the one row that belongs to whichever
  of the two pointers maps to it: `row_b = (b == head[1:0]) ? headRow : head1Row`. Using
  that rule would halve the LUTRAM read ports (RAM32M) per bank. **(unverified)** whether
  Vivado already merges them.

### 7.6 Source comments contradicted by the code (stale)

- `MicroOpAssembler.scala:9-18` (object header) says "1–2 µop SEQUENCE" and lists (An)+,
  −(An), indexed, RMW and memory-destination forms as deferred. The code emits up to 3 µops
  and implements all of them.
- `Microcode.scala:6-25` says "v1 … ONE customer family". The ROM has 511 rows covering
  more than 20 families.
- `DecodedUop.scala:307-311` (`firstOfInstr` doc) says "the only 2-µop case is a
  memSimple source crack". Many 2- and 3-µop cracks exist (§3.2).
- `DecodedUop.scala:372-374` (`SysKind.FMOVEM_DATA`) says "Admission remains disabled".
  The assembler emits it whenever `isDynamicFmovem` holds (`MicroOpAssembler.scala:4219`),
  and `ExceptionUnit.scala:2919` dispatches it to `FM_START`.
- `DecodeStage.scala:1503-1507` says FMOVEM.X is "LOAD direction only, (An)/(d16,An)
  only". The FSM has store phases (`fmovemxLastPhase`, `:1582`) and the auto-update
  `fmovemxHasFinal`.
- `DecodeStage.scala:2974-2976` and the `Microcode.scala:2251-2252` arithmetic still refer
  to a 252-row ROM. The code builds 511 rows.
- `execute/fpu/FmovemColdWalk.scala` says "Not yet wired into the core". It is
  instantiated by `FmovemColdEngine.scala:30`, which `FmovemColdBackend.scala:29` instantiates; whether
  `FmovemColdBackend` is in the shipping plugin list was not checked **(unverified)**.
