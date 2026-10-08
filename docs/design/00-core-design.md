# 00 — m68k040 out-of-order core: high-level design

Source tree: commit `fee95f6f`, the design running on the FPGA board. All code paths are
relative to `src/main/scala/m68k040/` unless they start with `src/`, `docs/` or a
component-document number.

**How this document was written.** It synthesises the nine component documents
(`01`–`09`, index in §9). Every number carries a pointer to the component-document
section it came from, written `[0N §x]`. Where two component documents disagreed, or a
claim was load-bearing for this summary and looked doubtful, the cited RTL was opened and
the question settled from the code. Those checks are marked **[checked: file:line]**.
Source comments and `docs/*` outside `docs/design` were not used as evidence. Git history
was used once (§4.4, CPUSH) to date a code change.

Numbers come in three kinds, and each is labelled:

- **code**: read directly from a width, depth, `require` or constant;
- **derived**: worked out from register boundaries in the code, not simulated;
- **silicon**: a board measurement supplied with the task. Silicon numbers are not code
  facts.

The shipping configuration is the build-log `SHIPPING_CONFIG` line quoted in the task,
with unlisted knobs at their `ShippingCoreConfig` defaults and `CPU_DEBUG_PROFILE=full`.
Of the knobs that line lists, nine ship with a value different from the source default
(§4.4).

---

## 1. Overview

The core is a SpinalHDL implementation of a Motorola 68040-compatible CPU. It has an
out-of-order backend: 68k instructions are cracked into µops, renamed onto physical
register files, issued out of order from one unified issue queue, and retired in order
from a µop-granular reorder buffer. The top-level shell (`core/M68kCore.scala`) is a
SpinalHDL `PluginHost`. Every function lives in a `FiberPlugin` [05 §1.1]. The shipping
top is `M68kSocketTop` (`top/SocketTop.scala`). It exposes three AXI masters (`axi_i`,
`axi_d`, `axi_dh`), an IPL input and an AXI-Lite debug slave to a Quadra 700-class SoC
[09 §2.3].

**Target (silicon).** Xilinx UltraScale+, 200 MHz, closing at WNS +0.001 ns. `u_cpu` is
about 103k LUT and 50k FF. About 49% of the LUTs are LUT6 selection logic and 1.2% are
carry.

### 1.1 Headline parameters

| Item | Value (shipping) | Kind | Source |
|---|---|---|---|
| Fetch | one 8-byte window per cycle, 2-cycle I-cache hit, up to 3 requests in flight | code/derived | [01 §3.1, §3.4] |
| Instruction buffer | 20 × 16-bit words, 4-word push, 10-word head | code | [01 §3.5] |
| FTQ | 32 × 80 bits, LUTRAM | code | [01 §3.6] |
| Decode width | 2 instructions/cycle (slot 0 + slot 1), ≤10 words, ≤4 µops pushed/cycle | code | [02 §3.2] |
| µop queue | 16 entries, push ≤4 (needs ≥4 free), pop ≤2 | code | [03 §3.5] |
| Microcode ROM | 511 of 512 rows used, about 79 bits/row, BRAM `readSync` | code (row count by script) | [03 §3.3] |
| Rename width | 2 µops/cycle, 5 renamed classes | code | [04 §3.1] |
| Int PRF | 54 × 32 bits (20 arch: D0–7, A0–7, T0–3); 17 read ports, 6 physical write ports | code | [04 §3.1, §3.7.2] |
| NZVC / X PRFs | 32 × 4 bits / 32 × 1 bit (source default 16 each) | code + env | [04 §3.1] |
| FP / FPCC PRFs | 16 × 80 bits (8 arch) / 16 × 4 bits | code | [04 §3.1] |
| Dispatch | 2 µops/cycle; one handshake for ROB allocation and IQ push | code | [04 §3.5] |
| Issue queue | 1 unified queue, 16 slots = 8 lines × 2 ways, uniform-shift insertion | code | [04 §3.6.1] **[checked: `execute/iq/IssueQueuePlugin.scala:1603-1632, 2052`]** |
| Issue ports | 5: ALU0, ALU1, Branch, LS, CPLX | code | [04 §3.6.4] |
| ROB | 32 µop entries, 2-wide retire | code | [05 §3.1] |
| Execution units | 2 × integer ALU (with 4-stage shifter), 1 × branch, 1 × load/store, 1 × CPLX (MUL, DIV, CHK/CMP2, bit-field, FPU, FP→int store conversion) | code | [06 §1] |
| FPU pipes | add/mul/cheap fronts, each 10 stages, + shared 3-stage round = 13-cycle fixed latency; FDIV/FSQRT iterative, 73 cycles | code/derived | [06 §3.6] |
| L1 I-cache | 16 KiB, 4-way, 64-byte lines, 64 sets, VIPT; 5 refill MSHRs (1 demand + 4 prefetch); next-line prefetch up to 5 lines ahead | code | [01 §3.1, §3.2] |
| L1 D-cache | 8 KiB, 4-way, 16-byte lines, 128 sets, VIPT; non-blocking, 4 MSHRs, 4-entry write-back buffer, 5-entry early-probe queue, 4-entry replay queue | code | [08 §3.1, §3.6] |
| ITLB / DTLB | 32 entries each, 4-way × 2 banks, all flops; ITLB combinational, DTLB registered; 2 hardware walkers + a separate PTEST search | code | [09 §3.2–3.7] |
| Load/store pipe | 1 LS µop/cycle; 4-entry in-order load ring; 4-entry park buffer (LS-OoO) | code | [07 §3.1, §3.4] |
| Store queue | 8 entries, about 245 bits each | code | [07 §3.6] |
| BTB | 128 entries, direct-mapped, slot 0 only | code | [01 §3.8] |
| FTB | 128 entries, direct-mapped, one branch per aligned 8-byte window | code | [01 §3.6] |
| RAS | 16 entries + full 16-entry checkpoint | code | [01 §3.9] |
| gshare | 2048 × 2-bit PHT, 16-bit GHR (11 bits used), 7 async read ports | code | [01 §3.8, §7.3–4] |
| AXI | `axi_i` 256-bit read; `axi_d` 128-bit R/W, 1 read + 1 write in flight; `axi_dh` 128-bit read-only, 2-bit ID, up to 4 refills in flight | code | [08 §2.3, §3.10] **[checked: `socket/AxiDMerge.scala:276,405`; `cache/DcachePlugin.scala:6340-6380`]** |

---

## 2. Pipeline

### 2.1 Stage diagram

Names are the ones the RTL uses. Each `│` boundary is a register.

```
FRONT END [01]
  fetchPc ─► IcachePlugin  C: accept (ITLB comb, BRAM addr from VA) │ C+1: S1 tag/way verdict │ C+2: FetchRsp
             FTB + gshare window lookup ────────── registered rsp at C+1 (applyNow: taken-branch redirect, 0 bubble)
  FetchRsp ─► fetch ring (3) ─► InstructionBuffer (20 words) ─► Aligner (slot0, slot1; comb)
              decode-time predictors on slot0: BTB / RAS / computed target ─► predictFire (C+1)

DECODE [02][03]
  feed ─► raw (PipeStage) │ fed (+OperationDecoder, 2×EaDecoder per slot) │
          assemble×2 / µcode ROM / MOVEM / MOVEP / FMOVEM.X FSMs ─► pushReg (PipeStage) │
          MicroOpQueue ring (LUTRAM, 16) │ pop ≤2 ─► ImmEnc widen + brPredTag expand ─►

RENAME / DISPATCH [04]
  RenameStage (5 RATs, 5 freelists, intra-group bypass; comb) │ PipeStage │
  DispatchPlugin (ROB alloc + IQ push, same fire) │

ISSUE [04]
  IQ slots (sel, triggers, dynWait) ─► select (5 ports, oldest-first) │ m2sPipe issue register
  (LS port: skid │ issue register) ─► cold-payload LUTRAM read ─► PRF read (async LUTRAM + bypass)

EXECUTE [06][07]
  ALU   : S0 PRF │ S1 fast result+bypass+complete ;  shift: S1a │ S2 │ S3
  Branch: S0 PRF │ S1 cond/target/mispredict ─► ROB branchCompletion ─► earlyFire (registered)
  CPLX  : extra issue register `ip` │ legacy FSM / MUL 7-stage / BF 4-stage / FPU 13 / FDIV 73
  LS    : S0 PRF │ P1 AGU │ P2 DTLB req + VIPT probe │ P2T DTLB rsp │ P3 SQ CAM │ P4 launch ─►
          D-cache S0 │ S1 │ S2 (or early-probe: S2 directly) ─► ring rsp ─► comp* stage

COMMIT [05]
  completion ports ─► ROB completes[] ─► headReady ─► retire0/retire1 ─► RenameCommit
  (RAT commit + freelist push, +1 cycle) ; SQ commit ; TLB U/M commit ; ExceptionUnit FSM
  flush: doFlushReg (registered, all sources at the ROB head) ─► backendSquash / feFlush
```

### 2.2 Critical loops (cycle counts)

Cycle 0 of each row is defined in the "Basis" column. All rows are **derived** from
register boundaries unless marked silicon.

| Loop | Cycles | Basis | Source |
|---|---|---|---|
| Taken branch predicted by the FTB (fetch-directed) | 0 fetch bubbles; slot 1 of the branch's packet is dropped | target window issued the cycle after window W | [01 §3.6, §5] |
| Taken branch predicted at decode (BTB, RAS, computed BRA/BSR target) | 3 feed bubbles | branch fires C, `predictFire` C+1, target rsp C+3, IBuf head C+4 | [01 §5] |
| FTQ framing mismatch | ~4–5 | detect D, act D+1, feed D+5 | [01 §5] |
| Front-end redirect pulse → next feed | 4 | arm R, cmd R+1, rsp R+3, feed R+4 | [01 §5] |
| Aligner output → rename input | 4 register edges (`raw`, `fed`, `pushReg`, ring); no empty-queue bypass | | [02 §3.1], [03 §3.5] |
| Complex (`complex=1`) instruction | ≥5 + decode sequencing, full IBuf flush and refetch every time | stall → `complexResume` → 1-cycle pipe → redirect | [01 §5, §7.8] |
| Sequencer entry (MOVEM, µcode, MOVEP, FMOVEM.X) | ≥2 bubbles (`fed` cold-hint drain + begin cycle), no co-issue | | [02 §5], [03 §3.1] |
| Branch resolve → front-end redirect (Tier 1) | branch selected C, branch-EU S1 at C+2, `earlyFire` at C+3 | | [06 §3.3], [05 §3.2.3] |
| Mispredict, Tier-1 hit | front end refetches from C+3; rename is frozen (`earlyPend`) until the branch **retires**; rename resumes 2 cycles after retire (`doFlushReg` T+1, `earlyPend` clear T+2) | penalty ≈ max(front-end refill to the µop queue ≈ 8, resolve→retire wait + 2) + rename→IQ→EU ≈ 3 | [05 §3.2.4, §5.2], [04 §3.2], this doc |
| Flush without Tier-1 hit (exception, RTE, sysOp, LS order violation, LS replay, debug) | retire + 1 + full front-end restart (4) + aligner→rename (4) + rename→EU (~3–4) ≈ 13–14 | consistent with the prior "fixed 14-stage restart chain" | [05 §3.2.4], this doc |
| Dependent integer ALU op | 1 cycle select-to-select (back-to-back) | static trigger clear at select | [04 §5.2], [06 §5.1] |
| Shift → dependent | consumer selected at C+4 (S1a broadcast) | | [06 §3.1] |
| L1 load hit (early-probe hit) → dependent select | **8** | IQ sel 0, S0 1, P1 2, P2 3, P2T 4, P3 5, P4 6, D$ S2 + capture 7, IQ clear 8 | [07 §3.3] |
| L1 load hit, probe miss | 9 | extra D$ S1 RAM read | [07 §3.3] |
| Store-queue forwarded load | 7 | completes at P4 | [07 §3.3] |
| L1 miss | ≈ L + 14, where L = AR accept → R valid at the `axi_dh` pins | 8 − 1 + (L + 7) [08 §5] | this doc (derived) |
| L1 hit, silicon | **8.29** cycles | silicon | task |
| L2 hit, silicon | ~21 cycles (implies L ≈ 7 at the pins under the derivation above) | silicon / inference | task, this doc |
| MUL / DIV / FADD / FDIV | ≈ C+11–12 / ≈ C+70 / C+17 / C+77 | | [06 §5.1] |
| Exception entry, format $0 | ≈ 6 + D + X + 4S + L, then a full refetch; S = one write-through AXI write round trip per 16-bit frame word | | [05 §3.3.8] |
| RTE, format $0 | ≈ 11 + D + X + 4L, then a full refetch | | [05 §3.3.8] |
| Simple sysOp (MOVEC, MOVE to SR, …) | ~4–5 + full refetch | | [05 §3.3.8] |
| DTLB walk, uncontended | ≈ 5 + 3·(3 + H) ≈ 20 for D$ hit latency H = 2; each level can lose arbitration to core loads for up to 64 cycles | | [09 §5] |
| I-cache demand miss | ~3 to AR + memory + 2 beats + ~7 post-burst; fetch port closed | | [01 §3.1] |

---

## 3. Component summaries

### 3.1 Front end: I-cache, fetch, prediction [01]

**Role.** Turns a fetch PC into up to two aligned 68k instructions per cycle for decode,
and owns every fetch-time prediction.

**Key structures.**
- `IcachePlugin`: 16 KiB 4-way VIPT. Tags are LUTRAM with 2 async read ports per way; data
  plus 8-bit-per-word predecode lives in one 384-bit BRAM line per beat [01 §3.1]. 5 MSHRs
  (1 demand, 4 prefetch). One fill FSM: `IDLE → REFILL → INSTALL_ARM → PREDECODE×2 → COMMIT
  → REPLAY`. No critical-word-first. A demand miss closes the fetch port. One AR at most
  every 2 cycles [01 §7.15].
- Predecode: 16 `classify` instances at refill plus 1 live on the IBuf head. The last 3 words
  of a line are classified without lookahead (`ambiguousLine`), and an ambiguous head costs
  ≥1 cycle [01 §3.3].
- `FetchAlignPlugin`: 3-slot fetch ring, 20-word IBuf, combinational `Aligner`, FTQ (32).
- Predictors: FTB (128, window-indexed, 0-bubble taken redirect); BTB (128, slot 0 only);
  RAS (16 + full checkpoint, `branchRepair` on); gshare (2048, PHT index =
  fold(PC) XOR GHR[10:0]); computed BRA/BSR targets.

**Interfaces.** `FetchService` (I-cache → FA, untagged in-order responses),
`DecodeFeedService` (FA → decode), `TranslationService` (ITLB, combinational),
`BtbUpdateService`/`GshareUpdateService` from the ROB. Most predictor control (BTB/RAS/
gshare ports, `mispredictRedirect`, `icMaintFlush`, `nonSpecFetch`) is plain wires driven
by `BackendWiringPlugin` and `SpeculativeFetchGate`, not services [01 §2.1].

**Live vs dead in shipping.**
- Live: fetch-directed prediction (`enableFetchDirected`), computed targets, slot-1
  deferral for unconditional branches and DBcc, slot-1 conditional training and deferral,
  next-line prefetch (runtime toggle in debug CSR).
- Dead: `StraddleTable`/`StraddlePending`, `deferSlot1Conditional`, the external
  `invalidateAll` (so RAS/gshare clear logic), FA `redirect`/`resume`/`vioRedirect`,
  `GsharePlugin.queryValid0/1`, the 8-wide predecode path (if `ICACHE_PREDECODE_WORDS`=16,
  which is the default but not printed in `SHIPPING_CONFIG`) [01 §4].
- The BTB and FTB 2-bit counters are stored and read-modify-written but never decide
  direction [01 §7.2].

**Behaviour that matters.** Conditional branches that miss both BTB and FTB always fall
through (no static backward-taken rule) [01 §5]. Every complex instruction pays a full
front-end refetch [01 §7.8]. CPUSH/CINV of IC/BC clears the whole I-cache, BTB and FTB
[01 §3.12].

### 3.2 Decode [02]

**Role.** From an aligned packet pair to a 2-wide stream of `DecodedUop`s for rename.

**Key structures.** `raw` and `fed` registers; per slot one `OperationDecoder` (opword
only) and two `EaDecoder`s computed before `fed` [02 §3.1]. 27 elaborated `EaDecoder`
instances in total [02 §6.2]. The decode record narrows `imm` to 18 bits:
{mode, payload}, with the real 32-bit values in a 32 × 192-bit GROUP bank and a 32 × 64-bit
PUSH bank, re-expanded at pop. Every assembler µop goes through the GROUP bank; SEXT mode is
never produced [02 §3.5]. Two more side tables: `brPredTable` (64 × 45 bits) and
`fpImmTable` (16 × 80 bits).

**Rules.** Slot 1 pairs only if neither slot cracks to 3 µops and slot 1 is not cold
(MOVEM/MOVEP/µcode/memory-indirect/dynamic bit-field/FP #imm). Otherwise slot 1 is stashed
and replayed alone next cycle [02 §3.2]. A-line opwords have no decoder arm and become
vector-10 traps; unknown F-line becomes vector 11 [02 §3.7]. Privilege is checked at
commit, not decode.

**Interfaces.** Consumes `DecodeFeedService`; provides `DecodeUopService`,
`FpImmTableService`, `FpTrapImmediateService`, `FrontendDebugMatchService` (4 PC
breakpoints). `pipeFlush` = `feFlush` (Tier-1 or Tier-2 front-end flush, or exception
active); `backendFlush` = `backendSquash` [02 §2.1].

**Dead.** `Global.DECODE_WIDTH`, `EaClass.MEMCOMPLEX`, write-only `OpSpec` fields
(`movemDir` etc.), `OpSpec.div64` (always False), `ImmEnc` SEXT mode [02 §7].

### 3.3 Micro-ops, cracking, microcode ROM [03]

**Role.** Defines the one µop record (`DecodedUop`, about 227 bits at 18-bit `imm`) and all
ways a macro becomes µops: the combinational fast crack (1–3 µops), the microcode ROM
engine, and the MOVEM/MOVEP/FMOVEM.X FSMs [03 §2.3, §3.1].

**Expansion that matters for IPC** [03 §5.1]:
- 1 µop: register ALU ops, `move.l (An)/d16(An),Dn` (fused with `fuseLongMoveLoads`),
  stores with a destination An update folded in, Bcc/DBcc/JMP/LEA.
- 2 µops: other memory-source ops, BSR/JSR/RTS/PEA.
- 3 µops (forces single-issue plus a replay cycle): any `(An)+` **source** load
  **[checked: `decode/MicroOpAssembler.scala:4448-4465`]**, memory RMW, RTR/RTD/LINK/UNLK/EXG,
  JSR (A7).
- ROM: memory-indirect EAs, CAS/CAS2, BCD/ADDX/SUBX/CMPM memory forms, MOVE16, dynamic and
  memory-indirect bit-fields, cpGEN with memory EA. 1 µop/cycle plus an entry bubble.

**Temporaries.** T0–T3 are architectural registers 16–19, renamed like D/A [03 §3.7].
**Flags.** NZVC is renamed as one 4-bit unit and X separately; partial-flag writers
(BTST/BCHG, ADDX/SUBX/ABCD Z-clear-only) must read NZVC to merge [03 §3.9].

**Risks.** The ROM is 511/512 full; the next customer forces a 10-bit µPC [03 §7.2-2].
ROM rows 152–165 are dead [03 §7.1-2]. `types/MicroOp.scala` and
`types/PredecodeMeta.scala` describe a different design and are used only by tests.

### 3.4 Rename, dispatch, issue, register files [04]

**Rename.** 2 µops/cycle, 5 classes (int, NZVC, X, FP, FPCC). Each RAT is two flop copies
(`specReg`, `commReg`) plus a `location` bit; rollback clears `location` in one cycle, so
**the only recovery point is the committed map**. There are no branch checkpoints
[04 §3.2]. Freelists are circular `Mem`s with pointer rollback; frees re-enter one cycle
after commit [04 §3.3]. Rename stalls unless **all five** freelists have ≥2 free ids
[04 §3.3].

**Dispatch.** No storage; ROB alloc and IQ push share one `fire` [04 §3.5].
`MemoryOrderService` has no provider in shipping, so `memoryReady = True`.

**Issue queue.** 16 slots in 8 lines of 2. Every push shifts **all** lines down by one and
writes the new pair into line 7; push requires line 0 to be empty next cycle
**[checked: `execute/iq/IssueQueuePlugin.scala:1603-1632, 640-643, 2052`]**. Wakeup has two
classes: static latency-1 producers use a triangular `triggers` mask cleared by select
events (no comparators); dynamic producers (LS, CPLX, shifts) use per-source `dynWait`
bits cleared by tag compare, 336 comparators in total [04 §3.6.3]. Select is
oldest-first on 5 ports. LS select in shipping (`loadBypassUnreadyLoad`): a load may pass
older LS µops only when no older **store** occupies the IQ, and a non-`firstOfInstr` load
may not pass an unready older LS µop; stores issue only as the oldest LS µop [04 §3.6.4].
The full `RenamedUop` lives in 2 cold LUTRAMs indexed by robId and read after the issue
register (10 async read ports) [04 §3.6.1].

**PRFs.** Each `RegFilePlugin` is one logical `Mem` lowered by `MultiPortWritesSymplifier`
into LVT (≥10 bits wide) or XOR (narrow) multi-write RAMs, replicated above 12 read ports
[04 §3.7.1]. Int: 17 reads, 6 physical writes, 7 bypass sources → 12 LUTRAMs of 54×32
plus a 3-core LVT [04 §3.7.2]. Bypass is "write in flight this cycle" only.

**Live vs dead.** Speculative LS wakeup and the 4-cycle-L1 preselect are compiled out.
`sbFp`/`sbFpcc`, the duplicate port-2 busy clear and IQ `count` are dead [04 §4].

### 3.5 ROB, commit, exceptions, system state, plugin framework [05]

**ROB.** 32 µop entries, `payload` in a 2-write XOR LUTRAM (~184 bits/entry), plus
`nextPcMem`, `branchTrainMem`, `faultDynMem` and ~16 per-entry flop arrays [05 §3.1.1].
Retire is 2-wide. **Every branch µop retires alone** (`retireAlone := isBranch`,
`pairCorrectBranch=false` in shipping) **[checked: `rob/RobPlugin.scala:1050, 1444`]**.
Other pair blockers: sysOp/RTE/needsSup at h1, a precise-store-completed head, armed trace,
an unmasked IPL across a macro boundary, and `faultCapPending` (any fault completion, even
wrong-path, freezes commit for one cycle) [05 §3.1.4, §3.1.7].

**Flush.** All flush sources fire at the ROB head: branch mispredict at retire, LS order
violation, LS liveness replay, exception/RTE/sysOp redirect, debug [05 §3.2.1]. Two-tier
mispredict recovery: Tier 1 redirects the front end from branch completion; Tier 2 does the
backend rollback at retire [05 §3.2.3].

**ExceptionUnit.** A serialising FSM inside RobPlugin. It builds frames $0/$1/$2/$7, RTE
for formats 0/1/2/7, and system ops (MOVEC, MOVE USP, MOVE to SR, STOP, RESET, CPUSH/CINV,
PFLUSHA, PTEST, FMOVE FPcr, FSAVE (always 52 bytes), FRESTORE, dynamic FMOVEM)
[05 §3.3]. Frame stores are precise write-through stores, one AXI write round trip per
16-bit word. Every sysOp ends in a full squash and refetch.

**Interrupts.** Recognised only at a `first` head (no IACK cycle; autovectored at the
socket); the head is preempted and re-executed; nothing is recognised with an empty,
non-stopped ROB [05 §3.3.6].

**System state.** SR system byte, VBR, USP/ISP/MSP, SFC/DFC, CACR in `SystemState`; the
CCR lives in the renamed NZVC/X PRFs. A7 is mirrored into the active SP bank every cycle
through a dedicated int-PRF read port [05 §3.4].

### 3.6 Execution units [06]

- **ALU ×2.** Identical. Fast ops complete at C+2 (S1) with same-cycle bypass. Shifts run
  S1a/S2/S3 and block that EU's fast issue for one cycle [06 §3.1]. Each ALU carries every
  rare datapath (BCD, PACK/UNPK, CAS kernel with a second adder, BFRESOLVE, SR/CCR RMW) and
  a third int read port `rdC` used only by CASOP [06 §6].
- **Branch.** Fixed 2 stages, never stalls, **no flush input**. Full 32-bit target compare
  in S1. RTS/RTR write A7 through a write port shared with the ExceptionUnit [06 §3.3].
- **CPLX (`DivEuPlugin`).** One issue port, one extra issue register (+1 cycle on every
  CPLX op). Legacy lane (CHK, CMP2/CHK2, DIV, DIVREM, FPCTRLRD, FPSTORECVT) is single-
  occupancy; DIV is radix-2 restoring, always 64 iterations. MUL is 33×33, II=1, 7 stages.
  Bit-field lane II=1. FPU: three equal-depth 10-stage fronts sharing one round/pack;
  FDIV/FSQRT 65 iterations regardless of precision [06 §3.4–3.6].
- **FPU coverage.** 13 operations in hardware including FMOVECR/FINT/FINTRZ; everything else
  traps to the FPSP path [06 §3.6.1].
- **FMOVEM (dynamic list)** runs in the ExceptionUnit, one byte per translate + memory
  transaction [06 §3.6.10].

### 3.7 Load/store unit [07]

**Pipe.** S0 (PRF read) → P1 (3-operand AGU) → P2 (DTLB request + VIPT D-cache probe) →
P2T (registered DTLB response, probe resolve) → P3 (SQ forward CAM) → P4 (launch to the
4-entry ring; same-cycle fall-through to the D-cache). One completion stage (`comp*`) is
shared by six producers [07 §3.1–3.2].

**Ordering.** Loads pass only older loads (IQ policy). No memory-dependence speculation.
The only speculation is device ordering: cacheability is unknown at issue, so a younger
cacheable load may launch before an older op turns out INHIBITED. Three mechanisms cover
it: the park buffer, order-violation flush, and liveness replay [07 §3.4].

**Store queue.** 8 entries. Forwarding needs full containment (or the sub-word case); any
partial overlap, or any older same-line non-COPYBACK store, stalls P4 [07 §3.6]. Stores
drain after commit in order, pipelined for COPYBACK/WRITETHROUGH. **Precise stores** drain
at the ROB head before completing. A store is precise when
`!(TC.E && cmode≠INHIBITED)` **[checked: `execute/LsEuPlugin.scala:1317-1320, 1997, 4702`]**,
see §4.3.

**Late store data.** A store may issue on its address and publish data later through a
detached owner (1 active + 3 FIFO) [07 §3.6].

**Early An.** LEA, pushes and `(An)+`/`-(An)` stores write the new An at P1 through a
one-entry retry register [07 §3.8].

**Dead in shipping.** `bkFsm` split-replay machine, `specLoadWakeup`, `p1EarlyLoad`,
`p3FastLoad`, `fourCycleL1`, `MemoryOrderPlugin`/`MemoryDependencyTracker`/`Check`
[07 §4].

### 3.8 L1 D-cache and the D-side AXI [08]

**Arrays.** Data 4 × (128 × 128 bits) BRAM, tags BRAM, valid/dirty LUTRAM, round-robin
victim with **no invalid-way preference**. One shared synchronous read port and one write
port per way [08 §3.1].

**Policies.** COPYBACK: write-allocate (full-line stores skip the fill); hits ack locally.
WRITETHROUGH: write-around on miss, 16-byte strobed AXI write. INHIBITED: never allocates;
naturally aligned sub-transactions, one at a time [08 §3.2].

**Non-blocking path (shipping).** 4 MSHRs, one fill per set, secondary-miss merging, a
store-merge buffer per MSHR, 5 waiters (one per ring slot + one serial), a 4-entry replay
queue (`rq`) that **closes the load port while non-empty**, early response from the R beat,
victim invalidated at allocation [08 §3.6]. The legacy blocking FSM survives only for
INHIBITED loads; its cacheable machinery and `hitUnderMissAccept` are elaborated but
unreachable [08 §3.5, §4.2].

**Ports.** Cacheable refills use `axi_dh` (ID = MSHR index, R always ready). Inhibited reads,
the reset-vector read and every write use `axi_d` through `AxiDMerge`, which holds a read
grant until `r.last` and a write grant until `b.fire` [08 §3.10]. The core enforces RAW
ordering between the two ports itself [08 §3.8].

**Serialising commands.** `ooOk=0` loads (line-crossing splits, exception loads, walker
descriptor reads) wait for the whole nb subsystem to be quiet and block every later load
until they respond [08 §3.6].

**Maintenance.** CPUSH/CINV walk line (4 ways) or all 512 ways (page and all scopes), ≥1,536
cycles for page/all [08 §3.11].

### 3.9 MMU, socket glue, debug, top level [09]

**MMU.** 68040 three-level tables, 4 or 8 KiB pages, TT registers checked before and
independent of TC.E. ATCs are 32-entry, 4-way × 2-bank flop arrays keyed with FC2. The ITLB
answers combinationally inside the I-cache accept cycle; the DTLB answers registered and is
**blocking**: `req.ready` includes `!missPending`, so even hits wait for a walk
**[checked: `mmu/DtlbPlugin.scala:416-428, 645`]** [09 §3.5]. Walkers read descriptors
through the D-cache (WRITETHROUGH when CACR.DE=1), arbitrated inside `LsEuPlugin` where they
lose to core loads for up to 64 cycles per level [09 §3.8]. U/M bits are written back by a
4-entry `UmWriteQueue` per side as a byte RMW after commit. PFLUSH of any form is a full
flush; any TC.E/TC.P/root/TTR write empties the ATC [09 §3.7, §5].

**Socket.** Reset-vector FSM (one AR at PA 0 for SSP/PC), IPL synchroniser, `ipl_ack` pulse,
`cpu_peripheral_reset` (518 cycles), halt-reason mux, read-reset absorbers on all three AXI
read ports (none on writes) [09 §3.9, §2.3].

**Debug.** `DebugCtrlPlugin` at stage 5 (hard-wired), AXI-Lite slave, survives CPU reset:
run control, 4 PC breakpoints, PC-range halt (`full` profile), halt-on-vector mask, arch
shadow and apply, history rings (PC, branch, exception; 32 deep, BRAM), 18 perf counters
[09 §3.10].

**Top.** `M68kSocketTop` builds the shipping plugin list through `ShippingPlugins`;
`BackendWiringPlugin` wires EU↔ROB↔IQ↔LSU↔frontend. The OOC `FullCoreSynth` target is **not**
the shipping configuration [09 §3.11, O-15].

---

## 4. Cross-cutting design

### 4.1 Plugin / service / Global-key architecture, and where the code breaks its rules

**The model.** `M68kCore` creates a `Database` and a `PluginHost`. Plugins publish sizing
keys during `setup` (only `ParamPlugin` calls `.set`), and build hardware during `build`.
Consumers block on `Database.blocking` keys and on `host[Service]` [05 §1.1].

**Invariant #3 (one producer per key) holds for Database keys.** Every key is set only by
`ParamPlugin` [05 §1.1, §2.1]. It does **not** hold for services: `GshareUpdateService` is
implemented by both `RobPlugin` and `GsharePlugin`
**[checked: `rob/RobPlugin.scala:55`, `frontend/Gshare.scala:53`]**. Nothing calls
`host[GshareUpdateService]`; the wiring connects the two directly [05 §2.2].

**Dead or misleading keys.** `DECODE_WIDTH`, `RETIRE_WIDTH`, `L1I_KB`, `L1I_WAYS`,
`L1I_LINE_BYTES` are set and never read. Most sizing uses plain constants
(`ROB_DEPTH_DEFAULT`, `PHYS_INT_REGS_DEFAULT`, literal 6-bit int tags and 4-bit FP tags in
rename, IQ, LSU and CPLX contexts). `ParamPlugin` `require`s some equalities, but nothing
ties the literal tag widths to the pool sizes [04 §2.2], [05 §2.1], [06 §4], [07 §7.2-4].
Ten `M68kParams` fields are never read [05 §4.1].

**"Plugins communicate only via services and Global keys" is not what the code does.**
- `BackendWiringPlugin` (`top/FullCoreSynth.scala`) reads and drives dozens of
  `x.logic.*` internals of the ROB, IQ, rename, EUs, TLBs, caches and predictors
  [05 §1.1], [01 §7.7].
- `SpeculativeFetchGate` reads ~30 internal signals of FetchAlign, DecodeStage,
  RenameStage and RobPlugin [01 §7.7].
- `M68kCore.dbg040` reads internals of nine plugins; `DebugCtrlPlugin` reads
  `rob.logic.*` [05 §1.1].
- The ROB's outputs are setup-time bare wires driven at build time, to avoid Fiber cycles
  [05 §1.1].
- Plugin `var` hooks on the TLBs, LSU and ROB are driven from outside [09 §2.1], [07 §2.1].

These are structural, not cosmetic: the EU→ROB completion ports and the fault ports are
plain `allowOverride` wires, which is how the `euFaultCompletion` collision (§7a-1) became
possible.

### 4.2 Flush and recovery model

- **Every flush fires at the ROB head.** Mispredict flush happens when the branch
  **retires**; LS order and replay flushes, exceptions, RTE, sysOps and debug all act on the
  head [05 §3.2.1], [04 §3.2].
- **No RAT checkpoints.** Rename rolls back to the committed map in one cycle (`location`
  cleared). Freelists roll back by pointer [04 §3.2–3.3].
- **Tier 1 / Tier 2.** On a mispredicted branch completion, `earlyFire` (one cycle after
  branch-EU S1) redirects fetch, flushes decode, restores the RAS checkpoint and repairs it,
  and opens a gshare history epoch; `earlyPend` freezes rename. When the branch retires,
  `doFlushReg` rolls back the backend; if Tier 1 already redirected to the same PC, the
  front-end flush is suppressed [05 §3.2.3].
- **Consequence.** Correct-path µops are fetched and decoded early but cannot rename until
  the branch reaches the head. Wrong-path µops keep executing and occupying the IQ, LS pipe
  and D-cache meanwhile [05 §5.2], [04 §7.2-11].
- **What the head-only rule makes safe.** The IQ wipes every busy bitmap on flush, the
  freelist drops pushes during flush, and the branch EU has no flush input. All of these are
  correct only because no older producer can still be in flight [04 §7.3]. Any future
  execute-time partial flush must revisit each of them.
- **What survives a flush.** A DTLB walk started by a wrong-path load keeps running and keeps
  `missPending` set, so the DTLB refuses correct-path translations until it finishes
  **[checked: `mmu/DtlbPlugin.scala:645, 715-728`]** (only U/M write-back is poisoned).
  Iterative FP ops and DIVs finish and are discarded.

### 4.3 Memory ordering model

- **Program order for stores.** Stores issue only as the oldest LS µop in the IQ, allocate
  in the SQ in order at P3, commit in order, and drain in order [04 §3.6.4], [07 §3.6].
- **Loads vs stores.** A load never passes an older store that occupies the IQ, so by P3 the
  SQ holds every older store. There is no memory-dependence prediction and no load-store
  violation detection [07 §3.4].
- **Forwarding.** Full containment only (plus aligned LONG→BYTE/WORD). Partial overlap and
  same-line non-COPYBACK stores stall the load in P4, blocking the whole LS pipe [07 §3.6].
- **Device (INHIBITED) accesses.** Loads launch only at the ROB head with no older store, and
  with D4 (`inhibitedFullBarrier`) only after `busQuiesced` (every MSHR, waiter and
  write-back drained). An in-flight inhibited load blocks every younger launch. Stores to
  inhibited pages are precise. Ordering against speculative younger loads uses park,
  violation flush and replay [07 §3.7], [08 §3.9].
- **When stores are precise.** `fastStore = TC.E && cmode≠INHIBITED (both halves)`, and
  `cmode` is forced INHIBITED when CACR.DE=0 **[checked: `execute/LsEuPlugin.scala:1317-1320,
  4702-4703`]**. So stores are precise (serialised at the ROB head, one in flight, each
  waiting for the D-cache ack) whenever **TC.E=0**, **or CACR.DE=0**, or the page/DTTR says
  INHIBITED. A DTT hit with TC.E=0 does **not** make stores fast; `mmuEnable` is TC.E alone.
  Whether this matters for Mac OS depends on runtime state that the code cannot show: on a
  stock 68040 Macintosh the ROM is expected to enable the MMU (TC.E=1) even with virtual
  memory off, in which case the precise path applies only to inhibited (I/O, framebuffer if
  so mapped) pages. **This has not been verified on the board.** The debug block's live
  MMU-register read (`readLive`, [09 §3.10]) can answer it directly.
- **Page-table walks are not ordered against older stores.** The walker's load grant has no
  SQ or ROB-age term (§7a-2).
- **Atomics.** No lock primitive. TAS and CAS/CAS2 are ordinary load/compare/store
  sequences; atomicity relies on a single bus master and in-order SQ drain [07 §3.9].
  U/M descriptor updates are a non-atomic byte RMW (§7a-3).
- **I/D coherence.** None in hardware; software uses CPUSH, as on a real 68040
  [08 §3.11].
- **Two-port ordering.** Refills on `axi_dh` are held while any write to the same line is
  buffered or awaiting its B on `axi_d` [08 §3.8].

### 4.4 Configuration model

- **Three layers.** `M68kParams` (`Config.scala`; partly dead), plugin constructor arguments,
  and `ShippingCoreConfig`, which reads **environment variables at elaboration time**
  [05 §4.3], [08 §4].
- **Shipping ≠ source default** for 9 knobs: `lsOooIssue`, `inhibitedFullBarrier`,
  `dcacheNonBlocking`, `dcacheNbEarlyResponse`, `lsEarlyAnRetry`, `physNzvc`, `physX`,
  `dcacheHotDoor`, `dcacheNbEagerAr` [05 §4.3]. A plain `sbt` build with an empty
  environment produces a different core: blocking D-cache, no LS-OoO, 16-entry flag pools.
- **Not in the `SHIPPING_CONFIG` line.** `ICACHE_PREDECODE_WORDS` (printed on its own line;
  default 16) [01 header], `PRF_SLOT_PERM_*` [04 §4], `PERF_DETAIL_ENABLE` (default 0).
- **Hard-wired.** `debugStage=5` in `M68kSocketTop` has no environment control [09 O-12d].
- **Other targets differ.** `GenFullCoreSynthVerilog` (OOC "full core") bypasses
  `ShippingPlugins`: different LSU/IQ/FA construction, default caches, no `AxiDMerge`, debug
  stage 2 [09 O-15]. The IPC benches run `pairCorrectBranch=true`, which the board does not
  have [05 §4.2, §7.5-4].
- **The CPUSH history (git, not comments).** `maintCmdOut.payload.invalidate := True` for
  CPUSH was added by commit `cbec2197` (2026-09-30 23:41), which is an ancestor of
  `fee95f6f`. See §5.6 and §7a-9.

### 4.5 Debug and observability

- Stage 5 debug: halt/step/resume, 4 PC breakpoints (matched after `pushReg` in decode),
  PC-range halt, halt-on-vector mask (256 bits), A7-odd halt, arch shadow/apply, live
  register reads, debug cache maintenance [09 §3.10].
- 18 always-built perf counters (cycles, instructions, mispredicts by class, flushes, DC/IC
  misses, prefetch issued/used/late, D/ITLB walks, stall-retire, stall-DC, stall-walk). All
  are `RegNext`-tapped [09 §3.10]. There is no counter for dual retires, for "IQ push blocked
  by line 0", or for MLP; `perfLvlRetire` counts cycles with ≥1 retire [05 §5.1], [04 §5.3].
- History rings: 32 entries; 128-bit entries are snapshotted only when word 0 is read, so a
  host must read words 0→3 in order [09 O-14].
- Unimplemented register offsets (watchpoints `WP*`, A-trap `AT*`, `DCACHE_PROBE_*`,
  `WEDGE*`, `FAULT_SNAP_*`, `RTS_SNAP_*`, `REDIRECT_*`) read 0 with OKAY. **Data watchpoints
  do not exist in this tree** [09 §3.10] (project notes describe them on a separate branch;
  that branch is not part of `fee95f6f`).
- `dbg040_*` exports ~800 bits of raw core state for an ILA [09 §2.3].

---

## 5. Performance model

### 5.1 Where the cycles go (silicon) and what in the code explains it

Silicon facts: Dhrystone IPC ≈ 0.36; ≈ 52% of cycles in retire stall on a byte `strcmp`
chain; branch mispredicts ≈ 14% of cycles; MLP measured 1.000.

The code gives a consistent picture of a machine whose **dependent-chain latency** and
**recovery latency** are long relative to its width:

1. **Load-to-use is 8 cycles by construction** (§5.2). A byte `strcmp` loop body such as
   `move.b (a0)+,d0 ; cmp.b (a1)+,d0 ; bne` cracks into 3 + 3 + 1 µops (both `(An)+` source
   loads are 3 µops and force single-issue decode). The loop-carried chain per byte is
   load → compare → branch: about 8 (load-to-use) + 1 (compare) cycles before the branch can
   resolve, and the branch then retires alone. With 7 µops per byte, the machine sits at the
   head waiting for the load almost all the time, which is what a 52% retire stall with an
   idle D-cache looks like. (The loop shape is an illustration of the mechanism, not a trace.)
2. **Branch recovery waits for retirement.** Rename stays frozen from Tier-1 detection until
   the branch reaches the ROB head, so in load-bound code the mispredict penalty includes
   the older loads' latency (§2.2, [05 §5.2]). Conditional branches that miss the BTB/FTB
   always fall through [01 §5]; decode-time predictions cost 3 bubbles even when right
   [01 §7.10]; branches retire alone [05 §3.1.4].
3. **Narrow commit for cracked code.** Branches retire alone; any fault completion (even
   wrong-path) costs a commit cycle; 3-µop cracks take 1.5 rename cycles and break decode
   pairing [05 §5.1], [03 §5.1].

### 5.2 Reconciling load-to-use: 8 derived vs 8.29 silicon

[07 §3.3] counts 8 cycles from load select to consumer select on an early-probe L1 hit:
select (0) → S0 (1) → P1 AGU (2) → P2 DTLB/probe launch (3) → P2T registered DTLB (4) →
P3 SQ CAM (5) → P4 launch with same-cycle fall-through to the D-cache (6) → D-cache S2
response and completion capture, wakeup (7) → IQ registered `dynWait` clear and consumer
select (8). [08 §3.3] independently gives the D-cache side: early-probe hit responds 1 cycle
after `loadCmd` fires, a normal hit 2 cycles. The two documents agree. The probe-miss
variant is 9 and the SQ-forward variant 7. A 0.29-cycle average excess over 8 is consistent
with a minority of hits taking the 9-cycle probe-miss path (probe credit unavailable,
`earlyProbeConflict`, an older unsent ring entry, P4 retries) plus occasional completion-
stage contention. This is an inference; no counter separates these cases today.

Five of the 8 cycles are register stages with one thin layer of logic each (S0→P1, P1→P2,
P2→P2T, P2T→P3, P3→P4) [07 §3.3, §7.1]. The 68040 does EA, ATC and cache in about 2–3.

### 5.3 L2 load-to-use: ~21 silicon

From [08 §5], a miss responds about L + 7 cycles after `loadCmd` fires (S1 detect, staging,
AR register, AR at the core port, FULL AR slice, L, FULL R slice, response). Replacing the
early-probe hit's 1 cycle with L + 7 gives load-to-use ≈ L + 14. The silicon ~21 implies
L ≈ 7 cycles from AR accept to R valid at the `axi_dh` pins. That is plausible for an
on-chip L2 but is an inference from two numbers, not a measurement of L.

### 5.4 MLP = 1.000 vs the code

**The task framing "axi_d allows one read in flight, so MLP is capped at 1 regardless of 4
MSHRs" is refuted by the code for cacheable loads.** `AxiDMerge` does hold the read grant
until `r.last` and the write grant until `b.fire`, so `axi_d` has exactly one read and one
write in flight **[checked: `socket/AxiDMerge.scala:255, 276, 405`]**. But in shipping
(`dcacheHotDoor=true`) cacheable refills do **not** use `axi_d`: they go out on `axi_dh`
with ID = MSHR index, one AR per cycle, any WAIT_AR MSHR a candidate, and R always ready
**[checked: `cache/DcachePlugin.scala:6340-6380`; `socket/SocketHotReadBoundary.scala`]**.
Up to 4 cacheable misses can be outstanding at the core boundary. `axi_d`'s single read
affects only INHIBITED loads and the reset-vector fetch [08 §7.3].

What in the code can still hold MLP near 1 (each is a code fact; their relative weight is
not measured):

- **The IQ window.** Uniform-shift insertion with the line-0 rule: a µop still unissued 7
  pushes after its own (for example the consumer of a missing load) stops all dispatch,
  even with 14 free slots, and single-µop groups halve capacity [04 §5.3, §7.2-8]. Behind a
  miss, only about 7 groups can enter before dispatch halts, which limits how far issue
  can run ahead to the next independent miss.
- **The in-order LS pipe.** P1–P4 are in order; a P4 load retrying on an SQ stall or
  serial condition blocks every younger LS µop [07 §5].
- **The blocking DTLB.** One translation in flight; a miss blocks hits; a wrong-path walk
  survives a flush (§4.2) [09 O-7].
- **The D-cache replay queue.** One `lFail` (same-set fill in progress, MSHRs or write-back
  slots short) closes the load port until it drains [08 §7.3].
- **Serialising loads.** Line-crossing splits, walker and exception loads drain the nb
  subsystem first [08 §3.6].
- **The single response slot,** which S2 hits always win [08 §7.3].
- **Outside the repository.** Whether the SoC/L2 behind `axi_dh` serves 4 IDs
  concurrently cannot be checked here.
- **Measurement date.** Project notes date the MLP measurement to 2026-09-30, the same day
  as the "go all-in on bandwidth" decision; whether it was taken on a build with
  `dcacheNonBlocking` + `dcacheHotDoor` + LS-OoO all on is not recorded in the code.
  Re-measure on this bitstream before acting on it.

### 5.5 Mispredicts (~14% of cycles)

Code-level contributors, in rough order of plausibility:

- penalty structure: Tier 1 overlaps refetch, but rename waits for retirement (§4.2);
- coverage: Bcc missing BTB and FTB always falls through (no BTFN) [01 §5];
- every BTB/RAS/computed prediction costs 3 decode bubbles; only FTB-framed branches are
  free, and the FTB holds one branch per 8-byte window and only branches inside it
  [01 §7.10];
- gshare uses 11 of 16 GHR bits; PHT size is frozen at 2048 by 11-bit fields [01 §4];
- BTB/FTB counters never influence direction [01 §7.2];
- the RAS checkpoint is the last ROB-empty state, which can be arbitrarily old [01 §7.12].

### 5.6 Front-end and decode limits

Fetch delivers 8 bytes/cycle from a 32-byte BRAM beat; at 2–3 words per instruction that
caps a 2-wide decode below 2 IPC on straight-line code [01 §7.9]. Slot-1 deferral knobs and
3-µop cracks reduce pairing further [02 §5], [03 §5.3].

### 5.7 Exceptions and A-line traps

Format $0 entry ≈ 6 + D + X + 4S + L; RTE ≈ 11 + D + X + 4L; both end in a full refetch
[05 §3.3.8]. With S an L2 write round trip, entry cost is dominated by 4 serialised frame
writes. Mac OS toolbox calls are A-line traps, so each costs at least 8 memory round trips
and two pipeline restarts [05 §5.3].

---

## 6. Area and congestion model

All LUT/FF figures here are **estimates** from structure, taken from the component documents
unless marked "this doc". Only the totals in §1 and the composition facts are silicon.

| Consumer | Structure | Estimate | Source |
|---|---|---|---|
| Int PRF | 17 read ports (15 bypassed), 6 physical write ports, 7 bypass sources; >12 reads forces 2 replicas of each of 6 data banks (12 LUTRAMs of 54×32) + 3-core XOR LVT | ~270 LUT per read port (brief figure) ≈ 4.6k LUT for reads alone; each extra write port ≈ 750 LUT of data bank | [04 §6] |
| Int PRF address fan-out | each read-address bit reaches ~66 LUTRAM sites + 6 LVT banks; address comes from a cold-store LUTRAM read (LUTRAM → LUTRAM in one cycle) | consistent with the silicon finding that congestion is dominated by PRF LUTRAM address nets (~970 fanout); the exact fan-out is netlist-dependent | [04 §6] |
| IQ | 16 slots × ~100-bit hot record, 336 tag comparators, 5 select trees, 472 busy flops, 600 `physToPosition` flops, 2 cold banks × 5 reads | cold store "a few thousand LUTRAM-LUTs" | [04 §6] |
| Rename | 8 int RAT read ports (20:1 × 6 bits × 2 copies), 5 freelists with a 3rd (power-on) write port each | — | [04 §6, §7.1-6] |
| Decode | 2 full `assemble` instances, 27 `EaDecoder`s, wide `fed`/`pushReg`/stash/pend registers; `imm` cone (silicon area map: ~6,760 LUT, 19 levels) now ends at a 192-flop register | — | [02 §6], [03 §6] |
| µop queue | 4 banks × 4 rows × ~227 bits, 2 async reads per bank | ~150 RAM32M ≈ 600 LUT | [03 §6] |
| µop record | ~227 bits; `pc` (32) and `fpuCmdWord` (16) ≈ 21% ride every slot of push, stash, queue | — | [02 §7.9], [03 §7.4] |
| ROB | 32 × ~184-bit 2-write XOR payload, 16 flop arrays with 32:1 muxes, `completes` with 9 writers/bit | — | [05 §3.1, §6] |
| FPU | 3 × 10-stage fronts carrying a ~182-bit `FpRoundReq`; delay-only stages A8–A9, C3–C9 | ~4.5k FF of request context; DSP ≈ 4 (MUL) + 10–16 (FMUL) | [06 §6] |
| ALU duplication | both ALUs carry CAS second adder, BCD, PACK/UNPK, BFRESOLVE, shifter, `rdC` port | — | [06 §6] |
| D-cache | ~26 × 128-bit line registers ≈ 3.3k FF; data BRAMs at 25% depth | ~8 RAMB36-equivalents for data | [08 §6] |
| TLBs | 2 × 32 × 44-bit flop arrays + lookup cones | ~2.8k FF, 400–500 LUT per TLB | [09 §6] |
| Debug (stage 5) | arch shadow + apply shadow (2k FF), perf counters, halt mask, breakpoints (duplicated in decode), PC-range | ~4.5–5k FF, 4–6 BRAM | [09 §6] |
| Predictors | gshare 2048×2 with 7 async read ports (each a LUTRAM copy + mux tree); RAS + checkpoint 1,024 flops; FTB 3 ports, BTB 2 ports | gshare ≈ 7 × (64 LUTRAM + mux) ≈ 0.7k LUT (this doc, estimate) | [01 §6] |
| ExceptionUnit | `fmLoaded` 640 flops, 16-source MOVEC read mux, frame muxes | — | [05 §6] |

**Why the core is selection-bound.** Almost every large item above is a wide multiplexer:
PRF read ports and LVT bank selects, RAT read muxes, IQ `ohSelect` AND-OR trees, the
cold-payload reads, decode `imm` selection, the D-cache way and response muxes, debug read
OR-trees. That matches the silicon composition (49% LUT6 selection, 1.2% carry).

**Control-net fan-out.** `doFlushReg`/`flushing` (ROB pointers, RAT rollback ×5, freelists ×5,
capture valids; `flushing` is a LUT OR although `backendSquash` is an exact registered
copy), `backendSquash` (IQ: 16 `sel` + 472 busy flops + select/issue valids),
`pushPort.fire` (every IQ slot), `ic.cmd.fire`, `feed.fire`, `anyInvalidate` [05 §6],
[04 §6], [01 §6].

---

## 7. Consolidated findings register

Verdicts: **CONFIRMED-IN-CODE** (I opened the cited code and it does what is claimed);
**PLAUSIBLE** (the claim is consistent with the cited code but reachability or impact was not
proven; "doc-cited" means I did not reopen the code); **REFUTED** (the code does not support
the claim).

### 7a. Suspected correctness bugs

| # | Finding | Verdict, reachability | Code | Doc |
|---|---|---|---|---|
| 1 | **`euFaultCompletion` collision.** Branch-EU `trapvFault` (TRAPV/TRAPcc vec 7, odd-target vec 3) and DivEu `euFault` (CHK 6, DIV0 5) share one ROB fault Flow. The DivEu `when` is textually later, so in SpinalHDL last-assignment semantics **DivEu wins** and the branch fault is dropped. The branch µop still completes through `branchCompletion`, so it retires without trapping (TRAPcc not taken; odd-target transfer falls through). The source comment claims the branch fault has priority; the code does the opposite. | **CONFIRMED-IN-CODE**. This is the shipping wiring: `M68kSocketTop` instantiates `BackendWiringPlugin` (`top/SocketTop.scala:203`). Reachable whenever both EUs report a fault in the same cycle; no interlock found. Rare. Fix: a fifth port into the existing age-arbitrated fault fold. | `top/FullCoreSynth.scala:298-299, 404-407` | 05 §7.1-1, 06 §7-1 |
| 2 | **Speculative DTLB walk not ordered against older stores.** A walk can read a descriptor before an older store to it has drained, install a stale translation, and commit through it. | **CONFIRMED-IN-CODE** (no SQ or ROB-age term in `walker.io.start` or `ldGrantOk`). Two RED reproducers exist in the test tree. | `mmu/DtlbPlugin.scala:599`; `execute/LsEuPlugin.scala:5524-5530`; `src/test/resources/m68kooo-ported-tests/asm/walk_vs_older_store_{uncommitted,slow}.s` | 09 O-1 |
| 3 | **U/M descriptor update is a non-atomic RMW.** Between the drain's byte re-read and its store grant, a core SQ store to the same byte could drain and be overwritten with `stale \| U/M`. | PLAUSIBLE; no interlock found in the slice; window a few cycles. | `mmu/DtlbPlugin.scala:842-939`; `execute/LsEuPlugin.scala:5557-5560` | 09 O-2 |
| 4 | **`atcFlush` drops an accepted, not-yet-started DTLB request with no response.** | CONFIRMED-IN-CODE (`missReqReg.valid := False` with no reply). Latent: safe only while every `atcFlush` source also squashes the requester; nothing asserts it. | `mmu/DtlbPlugin.scala:717-723` | 09 O-6 |
| 5 | **`firstOfInstr` can be False on a lone `bad` µop** (inertTrap does not reset it; `lastOfInstr` was moved to a count-based stamp, `firstOfInstr` was not). | PLAUSIBLE; reachability unverified. | `decode/MicroOpAssembler.scala:1230-1231, 1246-1262, 4245-4248` | 03 §7.2-1 |
| 6 | **FMOVEM.X slot 1 admits full-format mode-6 EA**, and the FSM would then use `(An)`. | PLAUSIBLE; depends on whether predecode marks such a packet `simple`. | `decode/DecodeStage.scala:1096-1100, 1143-1146, 1634, 1665-1671` | 02 §7-5 |
| 7 | **MOVE destination memory-indirect correction exists for slot 0 only.** Either slot 1 is wrong or the slot-0 correction is redundant. | PLAUSIBLE (one of the two is true). | `decode/DecodeStage.scala:487-489, 1028-1050`; `decode/MicroOpAssembler.scala:884-886` | 02 §7-6 |
| 8 | **MOVE16 ROM rows do not mask An to 16-byte alignment.** | PLAUSIBLE; LS-side alignment unverified. | `decode/Microcode.scala` rows 242–251 | 03 §7.2-3 |
| 9 | **Non-precise store bus errors.** The SQ ignores `drainErr` unless the store is precise; the D-cache latches `diagFault` kind 0 for a non-precise WT/inhibited B error, which **halts the core**. Not silent, but not an architectural bus-error exception either. Copyback store hits have no AXI write. | CONFIRMED-IN-CODE (behaviour = core halt). | `ls/StoreQueue.scala:1300, 1329`; `cache/DcachePlugin.scala:5683-5694`; `top/FullCoreSynth.scala:610` | 07 §7.2-1 (corrected, §10), 08 §3.4 |
| 10 | **Precise split-store fault address is always slot A**; `vaddrBs` is stored but unused. | PLAUSIBLE (doc-cited); 68040 behaviour unchecked. | `ls/StoreQueue.scala:1292` | 07 §7.2-2 |
| 11 | **SQ flush tail assumes kept entries are contiguous from head.** | REFUTED as a bug by construction: stores allocate in program order (stores issue only as the oldest LS µop) and commit in ROB order, so committed entries are a head prefix. The invariant is not asserted. | `ls/StoreQueue.scala:1547` | 07 §7.2-5 |
| 12 | **Branch EU has no flush input**; a squash-cycle `anW` write could land on a just-freed pdst. | PLAUSIBLE but likely harmless: the write lands at most one cycle into the squash, before rename resumes (T+2) and before any new producer is pushed. Not traced end to end. | `execute/BranchEuPlugin.scala:161, 375, 426, 441` | 06 §7-11 |
| 13 | **MSHR responses can be starved by S2 hits** (`nbRespFire = rV && !ldS2Resp`); fixed-index waiter priority. A head-of-ROB miss can wait indefinitely under a hit stream. | CONFIRMED structure (doc-cited line); a liveness/perf risk, not a data error. | `cache/DcachePlugin.scala:6458, 6464` | 08 §7.3 |
| 14 | **Architectural divergences from the MC68040:** frames $3/$4 never produced and rejected by RTE [05]; FSAVE always 52 bytes **[checked: `exception/ExceptionUnit.scala:1420, 2866`]**; FBcc never raises BSUN [06]; unnormal operands not trapped [06]; FPCR stored unmasked [06]; indirect page descriptors fault; table bases masked to 16 bytes; URP/SRP unmasked; root/pointer U bits never set [09]; PFLUSH(An)/PFLUSHN treated as PFLUSHA [09]; all interrupts autovectored [09]. | CONFIRMED-IN-CODE for FSAVE and the vector-11/format logic; the rest PLAUSIBLE (doc-cited). Impact on Mac OS unknown per item. | see docs | 05 §7.1-3,5; 06 §7-13,15,16; 09 O-3, O-4, O-17 |
| 15 | **FPSR accrual dropped when `setFpsr` fires the same cycle; `FpNarrowPack` uses the live rounding mode while the FP lane samples at issue.** | PLAUSIBLE; safe only if every FPCR/FPSR write serialises (it is a sysOp, so probably safe). | `execute/FpuControlPlugin.scala:152`; `execute/DivEuPlugin.scala:798, 1169` | 06 §7-12,14 |
| 16 | **Read-reset absorbers cover reads only;** a write in flight across a core reset is not absorbed. | PLAUSIBLE (doc-cited); `b.ready` is always 1 so a stray B is probably dropped. | `socket/AxiReadResetAbsorber.scala:46-61` | 09 O-16 |

### 7b. Dead or unreachable logic (shipping build)

| Item | Status | Code | Doc |
|---|---|---|---|
| `StraddleTable`, `StraddlePending`, `ChunkPredecode` token helpers | not instantiated | `cache/StraddleTable.scala`, `cache/StraddlePending.scala` | 01 §7-1 |
| BTB/FTB direction counters | stored and RMW-updated, never decide direction | `frontend/Btb.scala:88, 114-123`; `frontend/Ftb.scala:94-132` | 01 §7-2 |
| GHR bits [15:11] | never index the PHT | `frontend/Gshare.scala:114-115` | 01 §7-4 |
| RAS/gshare `invalidateAll`; FA `redirect`/`resume`/`vioRedirect`; `GsharePlugin.queryValid0/1`; `deferSlot1Conditional` | tied off / unread | `top/SocketTop.scala:496`; `frontend/Gshare.scala:120,122` | 01 §4 |
| Global keys `DECODE_WIDTH`, `RETIRE_WIDTH`, `L1I_*`; 10 `M68kParams` fields; `FlushService`; `types/RobEntry.scala`, `types/MicroOp.scala`, `types/PredecodeMeta.scala`; `HelloPlugin` | no consumer | `core/ParamPlugin.scala:39-43`; `Config.scala` | 01 §7-6, 02 §7-1, 03 §7.1-1, 05 §7.2 |
| `EaClass.MEMCOMPLEX`, `ImmEnc` SEXT/`zero`, `OpSpec.movem*/movep*Dir/SizeLong`, `OpSpec.div64` | never produced / write-only | `decode/DecodeContracts.scala:33`; `decode/ImmEnc.scala:125` | 02 §7-2..4 |
| µcode ROM rows 152–165 (`MI_BF_INS_DO0_ENTRY`), `Cluster.EA`, `DecodedUop.unimplemented` after rename, `RegListWalk.extractLowest1/extract1`, `Microcode.resolve/selReg/selImm` (test oracle) | unreferenced | `decode/Microcode.scala:2379` | 03 §7.1 |
| `sbFp`/`sbFpcc` static scoreboards (likely), IQ port-2 duplicate busy clear, IQ `count`, `IqHot.op/cluster/leaAddr/useImm`, rename `rnDbg`, `PreparedCommit`/`preparation`, freelist banked path | dead or sim-only | `execute/iq/IssueQueuePlugin.scala:443-444, 1977-1982, 636` | 04 §4 |
| `pairCorrectBranch`, `preparedRetireEntries`, retire lanes 2–3, `CommitTrace` ports (unconnected in SocketTop), `privOnly` term, duplicate `frameBase` | constant / redundant | `rob/RobPlugin.scala:1444, 2682-2684`; `top/FullCoreSynth.scala:779-787` | 05 §7.2 |
| `FpuCore.io.ready/unnormal/denorm` outputs, `DivCore` divide-by-zero arm, `DivUnit.io.divByZero/busy`, `MulCore.io.busy`, `brDbg`, `wbObs`, `Bitfield.apply`, `Shifter.apply/stage1`, `FpNarrowPack` packed-format arm (returns 0) | unread / unreachable | `execute/fpu/FpuCore.scala:196-201`; `execute/DivCore.scala:75-80` | 06 §4 |
| LSU `bkFsm` split-replay machine (`bkStart` only ever False), `reqStale/reqFresh`, `SQ.count`, `SQ.vaddrBs`, `MemoryOrderPlugin`/`MemoryDependencyTracker`/`Check`, `wakeupSpec*`, `liveLoadConfirm` | constant / not instantiated | `execute/LsEuPlugin.scala:1371-1470`; `ls/MemoryOrderPlugin.scala` | 07 §4, §7.3 |
| D-cache legacy cacheable miss path (EVICT_WR, REPLAY relaunch and store merge, `victimEvict*`), `hitUnderMissAccept`, `settled`/`blocked`, `storeCompletion`, `loadBusy` consumer, `residentHit`; sectored, fill-forward, direct-refill, line-forward, probe-miss-stage, full-line-no-fill, HUM-read, preselect, dynamic release | elaborated-unreachable or Scala-`if` off; ~300–400 code lines behind off flags + ~250 elaborated-unreachable | `cache/DcachePlugin.scala:2674-2757, 3295-3356, 3709-3771` | 08 §4.2, §7.2 |
| `UmWalkBatchQueue`, `TableWalker.batchMode`, `WalkRsp.faultReason`, `TlbEntry.vpnTag`, `rootChanged`, ITLB victim buffer, identity-translation plugins, `VioProbePlugin`, unimplemented debug offsets | not instantiated / unused | `mmu/UmWalkBatchQueue.scala`; `mmu/Tlb.scala:22` | 09 §4 |

### 7c. Candidate improvements

Sizes are rough and **unmeasured** unless stated. Risk: L = local change, M = touches a
protocol between plugins, H = architectural (per AGENTS.md needs a spec update).

| # | Candidate | Expected win | Risk | Code | Doc |
|---|---|---|---|---|---|
| 1 | **IQ insertion: allow push when line 0 is occupied but another line is free, or collapse holes** | Raises the effective window from 8 groups toward 16 µops; directly attacks run-ahead to a second miss (MLP) and single-µop-group waste. Add the "push blocked while count<14" counter first. | M | `execute/iq/IssueQueuePlugin.scala:640-643, 1603-1632, 2052` | 04 §7.2-8 |
| 2 | **Load-to-use stage cuts:** query SQ with the page offset in parallel with the DTLB; relax `p3FastLoad`'s `sq.io.empty` gate to "no overlap verdict"; AGU add in S0 for non-bypassed bases; `p1EarlyLoad` | each removes 1 of the 8 cycles; Dhrystone retire stall is load-to-use bound | M | `execute/LsEuPlugin.scala:902, 988, 2026, 4599-4617, 4795-4810` | 07 §7.1 |
| 3 | **Rename at Tier-1 time** (per-branch RAT checkpoints or a ROB walk) | removes the resolve→retire wait from every mispredict | H | `rename/RatTable.scala`; `rob/RobPlugin.scala:3729-3795` | 05 §7.6, 04 §7.2-11 |
| 4 | **Hit-under-walk in the DTLB; kill wrong-path walks on flush** | removes LS-pipe serialisation on every DTLB miss | M | `mmu/DtlbPlugin.scala:428, 645, 715-728` | 09 O-7 |
| 5 | **`(An)+` source load as 1–2 µops** (second LS destination via early-An machinery), and **allow 3+1 pairing** | the hottest 68k idiom stops forcing single-issue | M | `decode/MicroOpAssembler.scala:4452-4465`; `decode/DecodeStage.scala:537` | 03 §7.3-1,2 |
| 6 | **Static BTFN for unpredicted Bcc** (computed Bcc target at decode) | coverage for cold backward branches | M (GHR consistency) | `frontend/FetchAlignPlugin.scala:1193-1200, 1312-1332` | 01 §5, §7-11 |
| 7 | **Ship or drop `pairCorrectBranch`** (benches already use it) | retire bandwidth around branches; removes a bench/board mismatch | L | `rob/RobPlugin.scala:1444-1446` | 05 §7.5-4 |
| 8 | **Complex-instruction resume without IBuf flush** | ≥4 cycles per complex instruction | L–M | `top/FullCoreSynth.scala:135-145`; `frontend/FetchAlignPlugin.scala:1834-1855` | 01 §7-8 |
| 9 | **D-cache: invalid-way victim preference; invalidate victim at install not allocation; let ooOk hits pass a non-empty replay queue; MSHR responses not starved by hits** | hit rate and load-port availability | L–M | `cache/DcachePlugin.scala:3053, 6062, 6458, 6528` | 08 §7.5 |
| 10 | **Remove the same-line S1-vs-S3 store hold** using `stS3WriteD1` | ~0.6 → 1 store/cycle for same-line copyback bursts (stack frames, MOVEM, memcpy destination) | L | `cache/DcachePlugin.scala:4620-4621, 2241-2254` | 08 §7.5 |
| 11 | **Pipeline exception frame writes and RTE reads** | ~3 write round trips per format-$0 entry; ~half the RTE pop; A-line-heavy Mac OS | M | `exception/ExceptionUnit.scala:914-928, 1945, 2138-2430` | 05 §7.5-3 |
| 12 | **Walker priority / hold `ldOwner` across levels; ITLB walker first** | bounds walk time (up to ~192 cycles of arbitration loss today), which stalls fetch for ITLB | L | `execute/LsEuPlugin.scala:5514-5525` | 09 O-8 |
| 13 | **CPLX: DIV early-out (start at 32 for .W/.L32), stop HOL blocking behind DIV, precision-keyed FDIV/FSQRT iterations, remove the extra CPLX issue register** | DIV ~70→~38; MUL/FP/BF no longer stall ~66 cycles behind DIV.L | L | `execute/DivCore.scala:97`; `execute/DivEuPlugin.scala:418, 471-476` | 06 §7-2..4,19 |
| 14 | **Int PRF: fold ROB `fmAnWrite` into `IntWbKey`; mux `debugIntRead` onto `a7Rd`; steer CAS to one ALU and drop one or both `rdC`** | one write port ≈ 750 LUT; read ports ≈ 270 LUT each, but the step only pays below 12 reads (17 today, so ≥5 must go before replicas halve) | L | `rob/RobPlugin.scala:187`; `execute/AluEuPlugin.scala:140` | 04 §7.1-1..3, 06 §7-8 |
| 15 | **FP PRF: fold `fmFpWrite` and the three ROB FP read ports** | one FP LVT bank + ~2 × 80-bit read structures | L | `rob/RobPlugin.scala:179-184` | 04 §7.1-4, 05 §7.5-2 |
| 16 | **Drive `rc.flushPort` and ROB flush resets from `backendSquash`** (exact registered copy) instead of the `flushing` LUT OR | removes a LUT level in front of the widest control fan-out; the 200 MHz tail is control broadcast | L | `rob/RobPlugin.scala:1187, 3612, 3812` | 05 §7.5-1 |
| 17 | **Decode record diet:** move `pc`, `fpuCmdWord` and other rare fields to tag side tables; halve queue read ports by bank/row rule; use SEXT/ZEXT for small immediates; remove the redundant bit-field EA re-decodes | ~21% of record width; queue LUTRAM; pop-path depth | L | `decode/DecodedUop.scala`; `decode/MicroOpQueue.scala:183-186` | 02 §7-8,9; 03 §7.4, §7.5 |
| 18 | **gshare PHT read ports** (7 on a 2048-deep LUTRAM): bank the window reads | largest predictor area item | L | `frontend/Gshare.scala:138-187` | 01 §7-3 |
| 19 | **FPU padding:** drop `FpRoundReq` from delay-only stages | part of ~4.5k FF | L | `execute/fpu/FpAddPipe.scala:279-280`; `FpCheapPipe.scala:278-284` | 06 §7-5,6 |
| 20 | **Debug trims:** `archApplyShadow` (1k FF), 64-bit halt mask, optional `reduced` profile, environment control of `debugStage` | ~1–1.5k FF; density near ROB/decode | L | `debug/DebugCtrlPlugin.scala:1393, 1578`; `top/SocketTop.scala:61` | 09 O-12 |
| 21 | **Commit hold only for head faults** (`faultCapPending` vs `h0`) | removes a commit cycle per wrong-path fault event | L | `rob/RobPlugin.scala:1222, 2282` | 05 §7.5-5 |
| 22 | **Per-cycle dual-retire counter and IQ line-0-block counter in the default build** | makes the two biggest unmeasured structural limits observable on silicon | L | `debug/DebugCtrlPlugin.scala:581-869` | 05 §7.5-6, 04 §5.3 |
| 23 | **Rename gate per class** (FP/FPCC `popReady` only when the group pops them); FP freelist 8 free | FP-heavy code only | L | `rename/RenameStage.scala:162-163` | 04 §7.1-5, §7.2-12 |

---

## 8. Stale comment and doc register

Code is authoritative. Each row: where, what it says, what the code does.

| Where | Says | Code |
|---|---|---|
| `top/FullCoreSynth.scala:399-403` | branch-EU fault takes priority over DivEu | DivEu wins (last assignment) |
| `top/SocketTop.scala:175-178` | `LS_OOO_ISSUE` unset in every ordinary build | env-driven; shipping sets it |
| `top/ShippingCoreConfig.scala` (several, e.g. `:465`) | knobs "OFF pending measurement"; `physNzvc` "16 is the shipping default" | shipping turns them on by environment; physNzvc=32 |
| `cache/IcachePlugin.scala:19` | FSM `IDLE → REFILL → REPLAY → IDLE` | eight states |
| `frontend/Btb.scala:53` | index = `pc[1+idxBits:2]` | PC[7:1] |
| `IcacheTypes` `ChunkPredecode` comments | 9-bit / 5-bit | 8 bits |
| FA ring commentary | 3-stage cache pipe | 2-cycle cmd→rsp |
| `docs/DESIGN_icache_straddle_token.md` | "implementation starting" | not instantiated |
| `decode/DecodeStage.scala:14-15` | 1–2 µop sequence per slot | 1–3 |
| `decode/DecodeStage.scala:1503-1507` | FMOVEM.X load-only, (An)/(d16,An) only | store phases and auto-update exist |
| `decode/DecodeStage.scala:2974-2976`, `decode/Microcode.scala:2251-2252` | 252-row ROM | 511 rows |
| `decode/EaDecoder.scala:7-14` | indexed modes stay MEMCOMPLEX | MEMSIMPLE / MEMINDIRECT; MEMCOMPLEX never produced |
| `decode/OperationDecoder.scala:7-9` | memory/complex forms illegal | routed |
| `EaSpec.reg` comment | temps 16/17 | 16–19 |
| `decode/MicroOpAssembler.scala:9-18` | 1–2 µops; (An)+, −(An), indexed, RMW deferred | ≤3 µops; all implemented |
| `decode/Microcode.scala:6-25` | v1, one customer family | 511 rows, >20 families |
| `decode/DecodedUop.scala:307-311` | only 2-µop case is memSimple source | many 2/3-µop cracks |
| `decode/DecodedUop.scala:372-374` | FMOVEM_DATA admission disabled | emitted and dispatched |
| `execute/fpu/FmovemColdWalk.scala` | not wired into the core | instantiated via `FmovemColdBackend` in ExceptionUnit |
| `rename/RenamedUop.scala:10` | PHYS_INT_REGS=48 | 54 |
| `rename/RatTable.scala:26,77` | int = 18 × 6b | 20 |
| `RegfileSpec.scala:9-16` | physCount = 50 | 54 |
| `rename/RenameStage.scala:10-21` | three RATs | five |
| `execute/iq/IssueQueuePlugin.scala:9-10, 14-15, 312, 1569-1572` | pushes immediately ready / triggers always 0; push gated on count; bound 62; `events` ALU-only | triggers/dynWait used; line-0 gate; bound 30; all 5 ports |
| `RegFilePluginFp.scala` (FPCC doc) | write-only, `readsFpcc` always False | FPCC readers exist |
| `execute/LsEuPlugin.scala:390-391` | int PRF has six bypasses | seven |
| `DebugCtrlPlugin.scala`, `FullCoreSynth.scala` | int PRF avoided a 6th write port | 6 physical write ports (ROB `fmAnWrite`) |
| RobPlugin (many) | 64-entry ROB | 32 |
| `rob/RobPlugin.scala:35-45` | retire two/four-wide, branches "for now" | 2-wide; every branch retires alone |
| `types/RobEntry.scala:6-7` | ROB keyed by instruction | keyed by µop |
| `exception/SystemState.scala:46-52` | CACR has no effect on the D-cache | CACR.DE/IE consumed |
| `services/Services.scala:88` | CommitTrace consumed by lock-step | unconnected in the socket build |
| `execute/LsEuPlugin.scala:99-112, 1110-1112` | two-stage S0/S1 LSU; four-entry ring | P1–P4 + ring; parametric |
| SQ `olderThan` comment (`ls/StoreQueue.scala` ≈411-448) | 64-entry ROB | 32 |
| `docs/ls-hit-latency.md`; `docs/LS_OOO_PERFORMANCE_REFERENCE.md` | `baseline` default; LS-OoO off in production | `throughput-v2` default; shipping LS-OoO on |
| `cache/AxiIds.scala:38-39, 72-75` | `dRefill` "only 0 used"; `D_PUSH`/`D_EVICT` docstrings | hot-door IDs are MSHR index; docstrings swapped |
| `cache/DcachePlugin.scala:12-41, 1525, 2920` | single-beat refill, register valids; 4-entry probe queue | MSHR file, LUTRAM valids; 5 entries |
| `cache/DcacheTypes.scala:306` | `loadBusy` back-pressures loads | its only consumer is never read |
| Project memory note (2026-09-30), "CPUSH does NOT invalidate D$" | — | CPUSH sends `invalidate=1` and the walk clears matching lines (see §7a/§10 and below) |

**CPUSH: code vs the silicon observation (unresolved).** In `fee95f6f`, CPUSH issues
`push=1, invalidate=1` **[checked: `exception/ExceptionUnit.scala:2696-2701`]**, and the
D-cache walk clears valid on clean matches in CHECK and on dirty matches after an OKAY
write-back **[checked: `cache/DcachePlugin.scala:4272-4283, 4336-4341`]**. Only a failed
write-back leaves a line valid. The silicon membench note that "CPUSH does not invalidate
the D$" is dated 2026-09-30; git shows `invalidate := True` arrived in `cbec2197` at
2026-09-30 23:41, an ancestor of `fee95f6f`. The likeliest explanation is that the
measurement predates the change, but nothing in the code proves which bitstream it was
taken on. Treat it as an open discrepancy until re-measured on this bitstream.

---

## 9. Index of component documents

| Doc | Scope |
|---|---|
| [01-frontend-icache.md](01-frontend-icache.md) | L1 I-cache, prefetch, predecode, fetch ring, IBuf, aligner, BTB/FTB/RAS/gshare, FTQ, redirects |
| [02-decode.md](02-decode.md) | DecodeStage, OperationDecoder, EaDecoder, decode record, side tables, slot rules |
| [03-microcode-uops.md](03-microcode-uops.md) | `DecodedUop`, fast crack table, microcode ROM, sequencers, µop queue, flags |
| [04-rename-issue-regfile.md](04-rename-issue-regfile.md) | RATs, freelists, dispatch, issue queue, wakeup/select, physical register files |
| [05-rob-exceptions-core.md](05-rob-exceptions-core.md) | Plugin framework, ROB, commit, flush/recovery, ExceptionUnit, system state, config |
| [06-execution-units.md](06-execution-units.md) | ALU, shifter, branch EU, CPLX: MUL/DIV/CHK/bit-field, FPU |
| [07-load-store-unit.md](07-load-store-unit.md) | LS pipe, store queue, LS-OoO ordering, device accesses, early An |
| [08-dcache-axi.md](08-dcache-axi.md) | L1 D-cache, non-blocking MSHRs, maintenance, AxiDMerge, hot door, byte order |
| [09-mmu-socket-debug-top.md](09-mmu-socket-debug-top.md) | MMU, TLBs, walkers, PTEST/PFLUSH, socket glue, debug block, top-level assembly |

---

## 10. Corrections to component documents

The component documents were not edited. These statements are contradicted, or need a
qualifier, after checking the code.

1. **[07 §7.2-1] "Non-precise store bus errors are silently dropped."** The SQ ignores
   `drainErr` for non-precise stores, but the D-cache raises `diagFault` kind 0 on a
   non-precise WT/inhibited B error (`cache/DcachePlugin.scala:5683-5694`), and `diagFault`
   halts the core (`top/FullCoreSynth.scala:610`; [08 §3.4]). The error is not silent; it
   is a core halt instead of an architectural exception.
2. **[07 §3.2 and §5] / [08 §3.2] "With the MMU disabled, every store is precise."** True,
   but incomplete as a condition. `fastStore` also fails when CACR.DE=0 (the cache mode is
   forced INHIBITED, `execute/LsEuPlugin.scala:4702-4703`) and for INHIBITED pages. "MMU
   disabled" means TC.E=0 specifically; a matching DTT does not make stores fast while
   TC.E=0 (`execute/LsEuPlugin.scala:1317-1320`).
3. **[05 §1.1] "`BackendWiringPlugin` … hosted in the shipping core".** Correct, and it
   matters for [05 §7.1-1]/[06 §7-1]: the colliding `euFaultCompletion` wiring is the
   shipping wiring (`top/SocketTop.scala:203`). [05 §7.1-1] says reachability "was not
   proven"; the code shows no interlock between the two EUs, so it is reachable whenever
   both fault in one cycle (still rare).
4. **[08 §3.11] and [08 §7.1] on CPUSH** are correct about the code. The statement that
   "the task notes and an earlier memory entry say otherwise. The RTL contradicts them"
   should add that the RTL change (`cbec2197`) is dated the same day as that memory entry,
   so the silicon observation may predate it (§8).
5. **Task framing, not a component document: "axi_d single outstanding read caps MLP at 1
   regardless of 4 MSHRs."** [08 §7.3] itself already says the `axi_d` read limit matters
   only for MMIO; that is correct. Cacheable refills use `axi_dh` with up to 4 IDs in
   flight (§5.4).
6. **[07 §7.2-5] SQ flush tail "No ordering invariant check was found."** Accurate, but the
   invariant holds by construction (stores allocate in program order and commit in ROB
   order), so this is not a live hazard (§7a-11).
7. **[06 §7-11] branch EU no-flush hazard** is most likely harmless for timing reasons
   (§7a-12); the component document leaves it fully open.
8. **[04 §3.5] "`rob.allocReady` … at most 30 entries before an allocation, i.e. a full
   ROB"** and **[05 §3.1.3] "the full 32 entries are usable"** describe the same expression
   (`RegNext(countNext <= 30 && sysCapFreeNext >= 2)`); they agree. No correction, noted
   because they read as if they disagree.
