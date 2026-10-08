# 06 — Execution Units (ALU, Shifter, Bit-field, Branch, CPLX: MUL / DIV / FPU)

Source of truth: commit `fee95f6f`, the image running on the board. Every statement here
comes from the RTL. Source comments and `docs/*` were **not** used as evidence. Where a
comment disagrees with the code, the disagreement is noted. Where a number depends on a
library default or another module's behaviour that was not traced, it is marked
**(unverified)**.

All paths are relative to `src/main/scala/m68k040/` unless they start with `src/` or `docs/`.

---

## 1. Purpose and position

The issue queue (`execute/iq/IssueQueuePlugin.scala`) has **five issue ports**
(`IssueQueuePlugin.scala:136`). Four of them feed the units described here. The LS port is
covered in its own document.

| IQ port | Consumer | Instances | Wiring |
|---|---|---|---|
| 0 | `AluEuPlugin` "eu0" | 1 | `top/FullCoreSynth.scala:274` |
| 1 | `AluEuPlugin` "eu1" | 1 | `top/FullCoreSynth.scala:275` |
| 2 | `BranchEuPlugin` | 1 | `top/FullCoreSynth.scala:294` |
| 3 | `LsEuPlugin` | 1 | (not covered here) |
| 4 | `DivEuPlugin` (the CPLX cluster: MUL, DIV, CHK/CMP2, bit-field, FPU, FP→int store conversion, FP control reads) | 1 | `top/FullCoreSynth.scala:374` |

The shipping top (`top/SocketTop.scala:98-126`) builds the same set: two ALU EUs, one
branch EU and one `DivEuPlugin`. It wires them through `BackendWiringPlugin` in
`top/FullCoreSynth.scala`.

Dataflow, common to all units:

```
IQ select (cycle C) ──m2sPipe──► EU issue register (C+1, PRF read "S0")
   ──► execute ──► PRF write + same-cycle bypass ──► ROB completion port
                └► wakeup to the IQ: static (ALU fast), early dynamic (ALU slow),
                   or dynamic at completion (CPLX)
Branch EU ──► BranchCompletion ──► ROB ──(registered earlyFire)──► frontend redirect
```

* The IQ's output register is a `m2sPipe(collapsBubble = false, flush = flushSignal)`, and
  its valid is additionally gated by `!flushSignal`
  (`execute/iq/IssueQueuePlugin.scala:1278-1282`).
* The EUs read the physical register files (PRFs) in the cycle the IQ register presents the
  uop. The CPLX EU adds a second register first (§3.5).
* Squash. `iq.flushPort := pipeFlush` (`top/FullCoreSynth.scala:119`), where
  `pipeFlush = rob.logic.backendSquash` (`:95`). That signal is one flop equal to
  `doFlushReg || exc.active` (`rob/RobPlugin.scala:3612`). Both ALU EUs take
  `iq.flushPort` (`FullCoreSynth.scala:278-279`). The CPLX EU takes
  `rob.logic.backendSquash` (`:377`). **The branch EU has no flush input at all**
  (`BranchEuPlugin.scala:161`, `issuePort.ready := True`, and there is no flush port).

---

## 2. Interfaces

### 2.1 Services and Global keys

No unit in this slice produces a `Global` Database key. They consume:

| Key / service | Consumer | Use |
|---|---|---|
| `IntRegFileService` | ALU ×2, Branch, CPLX | read, write and bypass ports |
| `NzvcRegFileService` | ALU ×2, Branch, CPLX | NZVC read, write and bypass |
| `XRegFileService` | ALU ×2 | X read, write and bypass |
| `FpccRegFileService` | Branch (read), CPLX (read and write) | FBcc condition, FPCTRLRD, FP result |
| `FpRegFileService` | CPLX | 2 reads and 1 write, 80-bit |
| `FpImmTableService` | CPLX | FP wide-immediate side table: read the tag, free the entry on accept (`DivEuPlugin.scala:1115-1121`) |
| `FpuControlService` (`FpuControlPlugin`) | CPLX, via `DivEuPlugin.wireFpControl` (`DivEuPlugin.scala:169-184`, called at `FullCoreSynth.scala:773`) | FPCR, FPSR, FPIAR, rounding mode, precision, enable byte, and the FPSR EXC accrual (`orFpsrExc`) |
| `Global.ROB_ID_W(_DEFAULT)` | all | widths of the robId fields |
| `Global.ROB_DEPTH.get` | CPLX | size of the MULHI stash (`DivEuPlugin.scala:1036-1037`) |
| `Global.PHYS_NZVC_ID_W` | CPLX contexts | `log2Up(ShippingCoreConfig.physNzvc)` (`Global.scala:145-146`). Shipping `physNzvc=32` gives 5 bits. |
| `Global.FP_IMM_TAG_W` | CPLX | FP-immediate tag slice of `imm` |

`FpuControlPlugin` (`execute/FpuControlPlugin.scala`) owns the architectural FPCR, FPSR
and FPIAR registers (`:125` ff.). Each is a full 32-bit register. It also holds the FPSP
"unimplemented" frame state (`uiCmdReg1B` 16 bits, `uiSrcOperand` and `uiDstOperand` 80
bits each, `:174-177`).

### 2.2 Port inventory per EU

| EU | Int RF reads | Int RF writes | NZVC R/W | X R/W | FP / FPCC | Bypass ports | ROB ports |
|---|---|---|---|---|---|---|---|
| ALU (each) | 3 (`rdA`, `rdB`, `rdC`; `AluEuPlugin.scala:134-140`) | fast and slow merged into **1 physical port** by `sharingKey = wbKey` (`:141,152`) | 1 R + 1 W (merged) | 1 R + 1 W (merged) | – | **2 per file** (fast `intByp`, slow `intByps`, and the same for NZVC and X) | `completion` (`Flow[robId]`), `slowWakeup`, `fastAcceptNext`, `ccrObs` → `rob.ccrCompletion(0/1)` |
| Branch | 3 (`tgtRd`, `anRd`, `idxRd`; `BranchEuPlugin.scala:141-155`) | 1, **shared with the ExceptionUnit A7 write** via `IntWbKey` (`:153`; `top/FullCoreSynth.scala:73-74`) | 1 R | – | FPCC 1 R (no bypass, `:156`) | 1 int | `branchCompletion`, `trapvFault` → `rob.euFaultCompletion` |
| CPLX | 3 (`rdA`, `rdB`, `rdH`; `DivEuPlugin.scala:286-288`) | 1 (`:291`) | 1 R + 1 W | – | FP 2 R + 1 W, FPCC 1 R + 1 W, all without bypass (`:316-327`) | 1 int, 1 NZVC | `completion(3)`, `fpCompletion` → `completion(5)`, `wakeup`, `wakeupNzvc`, `fpWakeup`, `fpccWakeup`, `euFault`, `fpFault` (`FullCoreSynth.scala:374-408`) |

How write-port sharing works (`execute/regfile/RegFilePlugin.scala:20-22,115-144`).
Requests with the same `sharingKey` become one physical port, and the highest `priority`
valid request wins. Each `AluEuPlugin` instance uses its own `new Object` key
(`AluEuPlugin.scala:125`), so eu0 and eu1 never merge with each other.

---

## 3. Microarchitecture per unit

### 3.1 Integer ALU EU (`execute/AluEuPlugin.scala`, `execute/AluDatapath.scala`)

**Count:** two identical instances. The IQ can steer any ALU uop to either one
(`IssueQueuePlugin.scala:677-678`: `cand0` and `cand1` are both drawn from
`allAluCandidates`). Both instances therefore carry every rare datapath: BCD, PACK/UNPK,
CAS/CAS2, BFRESOLVE, the SR/CCR read-modify-write, and the barrel shifter.

**Stages (relative to IQ select C):**

| Stage | Cycle | Work |
|---|---|---|
| S0 | C+1 | PRF read (`rdA/rdB/rdC/nzvcRd/xRd`). `src1 = psrcAValid ? rdA : 0` (`:196`). `src2 = useImm ? imm : rdB` (`:197`). |
| S1 | C+2 | **Fast** execute, write, bypass and complete (`:680-689`). In parallel, `Shifter.stage1a` runs for a SHIFT. |
| S1a | C+3 | Shift only: `Shifter.stage1b` (the variable barrel shifts) (`:740`). Dynamic wakeup is broadcast here (`:826-834`). |
| S2 | C+4 | Shift only: `Shifter.stage2` (bit-indexing, compares, flag mux) (`:748`). |
| S3 | C+5 | Shift only: size merge, write, bypass and complete on the shared port (`:761-788`). |

**Operation classes.** `isSlow = (op === SHIFT)` (`:228-229`). Everything else is fast. The
fast datapath is `AluDatapath` (`AluDatapath.scala:32-135`). It uses one 32-bit adder
(`sum32`, `:58`) for ADD/SUB/CMP/NEG/NEGX/ADDX/SUBX. It also covers AND/OR/EOR/NOT/CLR/TST,
SWAP, EXT/EXTB, TAS, MOVE and BITOP (BTST/BCHG/BCLR/BSET with mod-32 or mod-8 bit numbers,
`:74-82`). In the EU itself, around the datapath:

* **ADDA/SUBA/CMPA** (`anWide = isMovea && op != MOVE`, `:275-282`). The datapath runs at
  LONG width and the `.W` source is sign-extended.
* **MOVEA / MOVES-to-An.** The source is sign-extended from B, W or L (`:558-561`).
* **Extended-arithmetic Z** (NEGX/ADDX/SUBX). Z is clear-only:
  `Z = Z_raw && Z_old` (`:294-296`).
* **BITOP flags.** Only Z changes. N, V and C come from the old NZVC (`:302-304`).
* **BCD** (ABCD, SBCD, NBCD). A byte datapath with 10-bit intermediates (`:318-368`).
  * X and C get the decimal carry (`bcdCarry`, `:364,677`).
  * Z is clear-only (`bcdZ = s1Nzvc(2) && …`, `:367`).
  * NBCD has its own adjust rule (`:354-360`).
* **PACK/UNPK.** Register form, plus the PACK memory form keyed by
  `extByte` (`:380-410`).
* **BFRESOLVE.** Packs the dynamic offset and width for bit-field ops. It has a register
  layout, a memory layout and a byte-delta layout (offset `>>> 3`) (`:417-441`).
* **CASOP.** The CAS and CAS2 compute kernel (`:454-500`). It instantiates a **second
  `AluDatapath`** in CMP mode for the flags (`:467-471`).
* **MOVE from CCR/SR** (`:567-578`) and **ANDI/ORI/EORI #imm,SR** (`isLogicSr`,
  `:588-595`). The SR system byte comes from `srSysIn`, which is wired to the ROB's
  committed `exc.ss.srSys` (`FullCoreSynth.scala:283-284`) and registered once (`:567`).
* **toCcr** (ANDI/ORI/EORI/MOVE to CCR). A 5-bit fold on the fast path (`:647-655`).

**Flags.** NZVC comes from `AluDatapath`'s per-size `piece(8/16/32)` adders
(`AluDatapath.scala:99-113`). Each `piece` is an extra narrow adder used for carry-out.
X is `rsp.xOut = cFlag` (`:133`), or the toCcr or BCD value. The uop's `writesNzvc` and
`writesX` masks gate the writes (`AluEuPlugin.scala:682-683`). The final result mux is
re-associated so the adder output passes through only one 2:1 mux per byte lane
(`:612-639`). The NZVC mux is ordered the same way (`:673-677`).

**Partial-register writes.** `.B` and `.W` results merge with `s1Src1` (the old
destination register value) in three lanes (`:624-639`). Shifts merge against `s3Src1`
(`:761-764`).

**Shifter (`execute/Shifter.scala`).** This is a Musashi-structured barrel shifter for
ASx/LSx/ROXx/ROx.

* `stage1a` (`:119-153`) computes everything derived from the count: the masked source,
  the `cMinus*` offsets, rotate `sMod`, and the ROX `rmod`. `rmod` is **seven serial
  compare-and-subtract steps** (`:505-511`).
* `stage1b` (`:157-~283`) does the 66-bit funnel shifts.
* `stage2` (`:285-~503`) does bit extraction and muxing.
* The count is `s1Src2(5:0)` (`AluEuPlugin.scala:710`), so a register count is taken
  mod 64.
* The midpoint bundles hold many 66-bit "flag words" (`ShiftStage1`, `:39-62`), but
  `stage2` reads only bit 8 of each (`flBit(v) = v(8)`, `:300`). Synthesis can trim those
  registers and shifts to single bits, so the declared widths overstate the real cost
  (not confirmed against a netlist).

**Slow-path hazard and wakeup.**

* A slow uop is always accepted. A fast uop is refused only if `s2Valid`
  (`AluEuPlugin.scala:256`), because it would reach S1 in the same cycle the S2 uop
  reaches S3.
* The IQ also gets a look-ahead, `fastAcceptNext := !flush && !s1aValid` (`:267`). When it
  is false, that EU may select only slow candidates
  (`IssueQueuePlugin.scala:677-678`).
* **Net effect: every SHIFT costs its EU one fast-issue slot three cycles after it was
  selected.**
* `slowWakeup` is broadcast from S1a, two cycles before S3 (`:826-834`). A consumer
  selected at C+4 reads at C+5 and takes the S3 bypass (`intByps`/`nzvcByps`/`xByps`,
  `:779-788`).

**Completion.** There is one `completionPort`, fed by `fastFire || slowFire`. The robId mux
prefers slow (`:838-839`). The same mux builds `ccrObs` (`:893-905`), which drives the
ROB's synthesizable `ccrCompletion(0/1)` (`FullCoreSynth.scala:307-315`). It carries the
32-bit result, NZVC and X. `wbObs` is a sim-only, one-cycle-delayed copy (`:858-877`). It
has no hardware consumer, so it should be pruned in synthesis **(unverified)**.

**Bypass forwarding.** Fast results are forwarded in S1 of the same cycle
(`intByp := intW`, `:687-689`). Slow results are forwarded in S3. Each ALU therefore
contributes **two** int, two NZVC and two X bypass sources to every non-`forceNoBypass`
read port in the core.

### 3.2 Bit-field datapath (`execute/Bitfield.scala`). It runs on the CPLX lane, not the ALU.

`Bitfield.stage1` (`:226-277`) does four rotates and shifts: `rotL`, `mask`, `insPos`, and
`extU`/`extS`. `stage2` (`:281-339`) does the CLZ32 (BFFFO), the mask-modify, and the
eight-way result and N/Z mux. `clz32` is a linear 32-way priority loop (`:348-360`). The
only caller is the CPLX bit-field lane (§3.5.4). No ALU instance uses it anymore:
`AluEuPlugin` has no `Bitfield` reference.

### 3.3 Branch EU (`execute/BranchEuPlugin.scala`)

**Pipeline.** Fixed latency, never stalls (`:161`).

| Stage | Cycle | Work |
|---|---|---|
| S0 | C+1 | Read NZVC (`pNzvcSrc`), FPCC (`pFpccSrc`), int base `psrcA` (gated to 0 when invalid, `:189`), pre-pop A7 `psrcB`, and index `psrcC`. The index is sign- or zero-extended and scaled **combinationally in S0** (`:175-178`). |
| S1 | C+2 | Condition evaluation, target, mispredict, DBcc/Scc/RTS writes, completion, faults. |

**Condition evaluation.** There is a 16-way integer table (`:202-219`) and a 16-way FP table
on {NaN, Z, N} (`:229-247`). `readsFpcc` selects between them (`:250`). FP condition bit
cc[4] (the BSUN distinction) is not used. Nothing here raises BSUN.

**Target.**
* PC-relative: `relTarget = pc + 2 + imm` (`:263`).
* Indirect: `indTarget = s1TgtBase + imm + s1Index`, a 3-input adder on flops (`:264`).
* `target = ibranch ? ind : rel` (`:265`).
* The fall-through is `u1.nextPc = pc + lenWords*2` (`:320`; `decode/DecodedUop.scala:880`).

**Redirect and mispredict.**
* `rawRedirect` (`:289-291`):
  * Scc and TRAPcc never redirect.
  * DBcc redirects if `!cond && Dn.W-1 != -1` (`:278-282`).
  * An ibranch always redirects.
  * A Bcc redirects if `taken`.
* `addrErr = rawRedirect && target(0)` suppresses the redirect and raises vector 3 with
  `faultAddr = target` (`:301,314,441-444`).
* `mispredict = predTaken != actualTaken || (actualTaken && target != predTarget)`
  (`:337-338`). This is a full 32-bit compare of the target in S1.

**Writes.** RTS/RTR set `A7 := s1AnBase + anInc` (`:419`). Scc writes `{Dn[31:8], cond ? FF : 00}`.
DBcc writes `cond ? Dn : {Dn[31:16], Dn.W-1}` (`:270-281`). All of them use the single
`anW` port and its bypass (`:426-429`), suppressed on `addrErr`.

**Predictor update payload.** The EU only forwards values. It does not train anything
itself.
* `isBranch`, the BTB/FTB training qualifier (`:356-357`), excludes Scc, cond-traps,
  returns and address errors.
* `brType`. FP cond 15 and integer cond 0 count as "always taken" (`:369-372`).
* `phtValid`/`phtIndex` are passed through (`:397-398`).
* The RAS repair payload: `rasKind` is CALL if `isCall`, RET if `isReturn && predTaken`
  (`:406-411`). `rasData` is `fallThruPc` for a call and `predTarget` otherwise.
* `btbLen = lenWords` (`:391`).
* The ROB keeps these per entry. Training happens at retire, not here.

**Redirect timing** (code in `rob/RobPlugin.scala:3738-3751`). On a valid, in-flight,
mispredicted `branchCompletion`, `earlyArm` is computed combinationally. **Only the oldest
pending early redirect is kept**: a later completion re-arms only if it is older
(`bcAge < curAge`). `earlyFire` is a register, so the frontend redirect
(`faRedir.valid … || earlyFire`, `FullCoreSynth.scala:141-143`) starts at **C+3**. That is
one cycle after the branch EU's S1. The same pulse freezes rename (`allocHalt :=
earlyPend`, `FullCoreSynth.scala:123`). Backend squash still happens only at commit
(`doFlushReg`). Wrong-path uops younger than the branch keep executing until the branch
retires.

**Debug.** `brDbg` (`:464-519`) and `wbObs` have no hardware consumer outside this file
(grep confirms).

### 3.4 Multiplier (`execute/MulCore.scala`, MUL lane in `DivEuPlugin.scala:937-1040`)

* **Datapath.** Each operand is extended to 33 bits (sign if `signed`, else zero). One
  `SInt(33) × SInt(33)` multiply is registered (`MulCore.scala:341-366`). That single
  product is the correct low 64 bits for both MULU and MULS.
* **Registers.** `a0/b0` are loaded only on `start`. `a1/b1`, `mul2` and `prod3..prod6`
  shift **unconditionally** with no clock enable (`:359-370`). The valid chain qualifies
  them (`:356-363`). `MulCore.Latency = 7` (`:305`). The structure (2 operand registers,
  1 multiply register, 4 product registers) is meant for DSP48E2 A/B/M/P absorption. With
  27×18 DSP tiles, 33×33 tiles to **about 4 DSP48E2** (estimate, not taken from a report).
* **.W.** The EU sign- or zero-extends the 16-bit operands to 32 at issue
  (`DivEuPlugin.scala:940-948`). The product is `prodLo`.
* **.L64.** The low half completes through the MUL FIFO. The high half is written into
  `mulHiMem(robId+1)` when the low half drains (`:1877-1881`). The cracked `MULHI` uop
  is parked in `mulHiPendingQ` (`:955,967-970`). It completes from the arbiter once
  `mulHiValid(robId)` is set (`:1039,1858-1873`).
* **Flags** (`:1007-1013`):
  * V = overflow, only for `.L32` (not `.W`, not `.L64`).
  * N = high bit of the low half (32-bit forms) or of the high half (64-bit form).
  * Z covers the full 64 bits for `.L64`.
  * C = 0.
* **Throughput.** II = 1. Accept is gated by a credit counter, `mulReserved`, against a
  `StreamFifo` of depth `Latency + 2 = 9` (`:953-976`). MUL bypasses the
  divider-busy gate (`:471-476`), so it keeps issuing while a divide iterates.

### 3.5 CPLX cluster (`execute/DivEuPlugin.scala`)

**Issue.**
1. The IQ port is re-registered: `ip = issuePort.m2sPipe(flush = flushSig)` (`:418`). Every
   PRF, FP-PRF and FP-immediate read is addressed from that register (`:422-431`).
   **Every CPLX op therefore pays +1 cycle** relative to ALU and branch.
2. Admission is decided per class (`:471-476`):
   * MUL: credit available.
   * MULHI: `mulHiPendingQ` has room.
   * BITFIELD: credit available.
   * FP fixed: always.
   * FP iterative (FDIV/FSQRT, any precision; `fpu/FpSource.scala:143`, `:90-91`):
     `!fpIterBusy`.
   * Everything else (the "legacy" lane: CHK, CMP2/CHK2, DIV, DIVREM, FPCTRLRD,
     FPSTORECVT): `!busy && !s1Valid`.

#### 3.5.1 Legacy lane: CHK, CMP2/CHK2, FPCTRLRD, FPSTORECVT, DIV, DIVREM

The S1 operand registers are captured on `ip.fire` for legacy ops only (`:507-516`). The FSM
(`:1431-1584`) has two states, IDLE and DIVING.

* **CHK.** Compares sign-extended `boundLo` (Dn) and `boundHi` (bound) (`:592-612`). N is
  `Dn<0` and Z is `Dn==0`. V and C are 0. NZVC is always written, including on the trap
  path. A fault raises vector 6.
* **CMP2/CHK2.** `c2Compare` from `s1H`. Data registers are sign-extended, address
  registers zero-extended at `.B`/`.W`. `u1.divSigned` is reused as the An/Dn flag
  (`:631`). Z = equal to either bound. C = out of bounds. N and V are preserved
  (`:646-651`). CHK2 traps with vector 6.
* **FPCTRLRD.** Reads FPCR, FPSR (with the renamed FPCC spliced in) or FPIAR, chosen by
  list position (`:765-783`).
* **FPSTORECVT** (FMOVE FPn,<ea>). Uses `FpNarrowPack`, one registered stage (§3.6.6), so
  it **occupies S1 for 2 cycles** (`fpCvtWait`, `:824,1486-1500`). An enabled exception
  becomes `captureFault(vec)`.
* **DIV.**
  * Operands: `.W` uses a 32-bit dividend, sign- or zero-extended to 64, and a 16-bit
    divisor extended to 32. `.L32` extends the dividend to 64. `.L64` is `Dr:Dq`
    (`:704-728`).
  * `divisor == 0` → fault vector 5 immediately (`:1527-1536`).
  * Otherwise `DivUnit` starts and the FSM moves to DIVING.
  * On `done`, the remainder is published to a ROB-tagged one-entry stash (`:905-925`).
  * On overflow: V is set, N, Z and C are kept, and the old Dq value **is written back**
    to the new pdst (`:1573-1574`).
  * Otherwise the quotient (`.W` is `{rem16, quot16}`) is written with N/Z (`:742-747`).
* **DIVREM.** Writes `remOv ? s1A (old Dr) : remLatch`. It asserts that it is consuming its
  own DIV's stash (`remIsMine`, robId+1, `:924,1520-1524`).

**DivUnit / DivCore algorithm** (`DivUnit.scala`, `DivCore.scala`):

1. Sign-normalise both operands to magnitudes (`DivUnit.scala:167-174`).
2. Run an **unsigned radix-2 restoring** divide over a 64-bit dividend:
   * one 33-bit trial subtract per cycle;
   * `qd` is a single combined 64-bit dividend/quotient register (`DivCore.scala:57-101`);
   * **always 64 iterations** (`cnt === 63`, `:97`), with **no early-out** for `.W`,
     `.L32` or small operands.
3. Fix up signs (`DivUnit.scala:192-201`). The remainder takes the dividend's sign.
4. Check overflow per form against the 64-bit magnitude quotient (`:207-232`). There is a
   special divide-by-(−1) suppression term per form (`:290-293`).

`DivCore`'s own `divByZero` path (`DivCore.scala:75-80`) can never be reached: the EU
filters `divisor32 === 0` before `start`. `DivUnit.io.divByZero` and `io.busy` are not
read by the EU.

#### 3.5.2 Completion arbitration (int / NZVC lane)

There are four sources and one registered completion stage (`:1854-1891`). The priority
is: **legacy > ready MULHI > MUL FIFO > bit-field FIFO**.

The `comp*` registers drive the PRF write, bypass, ROB completion, `wakeup`, `wakeupNzvc`
and `euFault` ports in the next cycle (`:1908-1938`). `compFlushed` (set from the `flushed`
latch) suppresses a wrong-path legacy completion (`:673,691,1908`).

`ccrObs = wbObs` and is driven straight from `compLive` (`:1941-1965`). It feeds
`rob.ccrCompletion(3)` (`FullCoreSynth.scala:408`).

#### 3.5.3 FP lane (separate completion port)

* **FS1 issue register** (`:1101-1173`). Raw operands are latched on `fpAccept`: int A, B
  and C; FPn and FPm (80-bit each); the 80-bit FP immediate from the side table; kind,
  fmt, opmode; `rmode` and effective `prec` **sampled at issue** (`:1169-1171`).
  `fpS1Kind` is duplicated as `fpS1KindB`, with `dont_touch` on both (`:1144-1146`).
* **Source gateway**, combinational in FS1 (`:1185-1216`). It contains one converter each
  for int, single and double (`FpSource.intToExtended/singleToExtended/doubleToExtended`),
  then a nine-way 80-bit `fpSrcVal` mux. MEMEXT is a pure bit placement of the three int
  chunks. ROMCONST supplies 0 as the source and `cromSel = opmode`.
* `fpu.io.start := fpS1Valid`. It is **not** flush-gated (`:1224`).
* The fixed-lane descriptor shadow pipe is `FpuCore.FixedLatency = 13` deep. It is pushed
  on `fpS1Fixed` and cleared on flush (`:1239-1247`).
* The iterative lane holds one context, `fpIterCtx`. A flush only sets `fpIterFlushed`.
  The result is still acknowledged (`iterAck`) when it arrives (`:1250-1290`).
* **FP-internal arbitration.** The fixed lane wins. A held iterative `done` retries the
  next cycle (`:1283-1290`).
* **Completion register.** The `fpComp*` registers drive `fpCompletion`, `fpW`, `fpccW`,
  `fpWakeup`, `fpccWakeup`, `fpFault` and the FPSR accrual (`:1294-1419`). Writes and
  wakeups are suppressed on an escalated fault (`:1389-1398`). The accrual is **not**
  suppressed (`:1415-1419`).
* **Escalation** (`:1324-1337`). Exception flags AND the FPCR enable byte, with priority
  SNAN > OPERR > OVFL > UNFL > DZ > INEX2. The vectors are 54, 52, 53, 51, 50 and 49
  (`:138-145`).
* **No bypass on FP or FPCC reads** (`forceNoBypass = true`, `:316-327`). The earliest a
  dependent can read the result is decided by the IQ's dynamic `cplxFpWait`.

#### 3.5.4 Bit-field lane

Accept is gated by a credit counter, `bfReserved`, against a `StreamFifo` of depth 6
(`:1625-1641`). The routing context shifts through a 4-deep `bfCtx` (`:1644-1659`).

Data stages, relative to accept cycle A:

| Cycle | Work |
|---|---|
| A+1 | `bf1*` operand capture (`:1692-1702`). `bf1A` is gated by `psrcAValid`, `bf1B` is raw `rdB`, `bf1C` is `rdH`. |
| A+1 | Forward memory funnel and `BitfieldCmd` assembly (`:1708-1742`). |
| A+2 | `Bitfield.stage1` (`:1751`). |
| A+3 | `Bitfield.stage2` (`:1761`). |
| A+4 | Memory RMW inverse funnel and store-form mux (`:1779-1795`), then push to the FIFO (`:1798`). |

The result writes the full 32-bit int register, plus N and Z with V = C = 0 (`:1805`).

### 3.6 FPU core (`execute/fpu/*`)

#### 3.6.1 What runs in hardware vs. what traps

`FpOp` has exactly 13 operations (`FpTypes.scala:49-52`): FADD, FSUB, FMUL, FDIV, FSQRT,
FABS, FNEG, FMOVE, FCMP, FTST, FINT, FINTRZ and FMOVECR.

The hardware opmode whitelist is `FpSource.nativeOpmodes`. It is the base, `FSxxx` and
`FDxxx` forms of MOVE, SQRT, ABS, NEG, DIV, ADD, MUL and SUB, plus 0x01 FINT, 0x03 FINTRZ,
0x38 FCMP and 0x3A FTST (`FpSource.scala:123-139`).

The decoder consumes the same predicate (`decode/DecodeStage.scala:1999`,
`decode/MicroOpAssembler.scala:2164`). Any other cpGEN opmode never reaches `FpuCore`.
That includes the transcendentals, FMOD/FREM/FSCALE/FGETEXP/FGETMAN/FSGLDIV/FSGLMUL and
FSINCOS. It goes down the decode's non-native (FPSP trap) path. The trap mechanism itself
belongs to the decode/exception documents.

FMOVECR, FINT and FINTRZ are executed natively. A real MC68040 emulates these three in
the FPSP, so this core implements a superset of the silicon.

#### 3.6.2 FpuCore structure (`FpuCore.scala`)

* One `start` port feeds three equal-depth (10-stage) fixed fronts: `FpAddPipe` (FADD,
  FSUB, FCMP), `FpMulPipe` (FMUL) and `FpCheapPipe` (FABS, FNEG, FMOVE, FMOVECR, FTST,
  FINT, FINTRZ) (`:139-168`).
* Because every front is exactly 10 deep and there is one start port, at most one front
  presents a result in any cycle. They therefore share **one `FpRoundPack`** (3 stages)
  without arbitration (`:186-190`). `FixedLatency = 13` (`:76`).
* `FpDivSqrtCore` (FDIV, FSQRT) has its own private `FpRoundPack` (`FpDivSqrtCore.scala:141`)
  and a level-held `done`/`ack` handshake.
* `io.ready`, `srcUnnormal`, `dstUnnormal`, `srcDenorm` and `dstDenorm` (`:196-201`) have
  **no consumer** (grep). They are dead outputs. Unnormal operands are not trapped here.

#### 3.6.3 FpAddPipe: 10 stages, II = 1 (`FpAddPipe.scala`)

| Stage | Work |
|---|---|
| A0 | Unpack and classify. Effective exponent: `exp==0 → 1` (`:131-132`). FCMP is forced to extended precision (`:125`). |
| A1 | Magnitude order, align amount (saturated at 66), all special cases (NaN propagation, inf−inf → default NaN with OPERR, exact-zero sign = RM), and the FCMP infinity table (`:136-211`). |
| A2–A3 | Coarse then fine right shift with jamming, on a 67-bit `{sig, G, R, S}` (`:215-227`). |
| A4 | 68-bit add or 67-bit subtract (`:230-240`). |
| A5 | 67-bit CLZ (`:243-247`). |
| A6–A7 | Coarse then fine left normalise, plus exponent adjust (`:250-271`). |
| A8–A9 | Pure delay (`:279-280`). |

There is a single path (no near/far split).

#### 3.6.4 FpMulPipe: 10 stages, II = 1 (`FpMulPipe.scala`)

| Stage | Work |
|---|---|
| M0 | Classify, plus CLZ64 on both significands (`:74-89`). |
| M1 | Subnormal pre-normalise, `zExp = dExp + sExp − 0x3FFE`, special cases (`:92-126`). |
| M2–M8 | `UInt(64) × UInt(64) → 128`, using the same no-clock-enable 2+1+4 register idiom as MulCore (`:129-142`). |
| M9 | 1-bit post-normalise, then round and sticky from the low 64 bits (`:145-158`). |

The FpRoundReq context travels alongside in a `Vec(7)` (`:139-142`). DSP count: a 64×64
unsigned multiply is on the order of 10–16 DSP48E2 (estimate). No utilization report was
available in the tree.

#### 3.6.5 FpCheapPipe: 10 stages (`FpCheapPipe.scala`)

* C0 reads the ROM: a synchronous `Mem` of 32×80 bits holding 22 constants
  (`:62-85,140-144`).
* C1 does FINT/FINTRZ mask building and the rounding add (`:163-188`).
* C2 builds the final value and the request (`:191-275`). FABS, FNEG and FMOVE are re-opened
  for rounding when precision is not extended (`reRound`, `:252-254`). FMOVECR, FINT and
  FINTRZ are always bypassed, so they are **not** rounded to FPCR.PREC.
* **C3–C9 are seven pure delay registers** (`:278-284`).

#### 3.6.6 FpRoundPack: 3 stages (`FpRoundPack.scala`)

| Stage | Work |
|---|---|
| RP0 | Per-precision guard, sticky and LSB. Increment = `guard && (sticky || lsb)` for round-to-nearest-even. Overflow and subnormal classification (`:191-214`). |
| RP1 | Subnormal jam right-shift, re-decided increment, after-rounding tininess, mask applied before the adder (`:217-256`). |
| RP2 | 65-bit increment, carry fix-up, overflow substitution (largest finite at the selected precision, or extended infinity), FPCC classify, exception flags (`:259-307`). |

Rounding modes are RN, RZ, RM and RP. Precision is Extended, Single or Double, with 11
treated as Extended (`FpTypes.scala:102-120`). OVFL and UNFL are substituted in hardware.
They trap only when enabled (§3.5.3).

#### 3.6.7 FpDivSqrtCore (`FpDivSqrtCore.scala`)

The FSM states are IDLE, CLZ, NORM, SETUP, ITER×65, ROUND and HOLD (`:116-274`).

* Radix-2 restoring for both operations, **65 iterations regardless of precision**
  (`:45,252`).
* Divide: one 68-bit compare and subtract per iteration (`:246-249`).
* Square root: a 130-bit radicand consumed two bits per iteration (`:238-244`).
* Special cases (NaN, Inf, zero, divide by zero, sqrt of a negative) go
  CLZ → ROUND directly (`:178-204`).
* Measured from `start` (by state count): 1 + 1 + 1 + 1 + 65 + 1 + 3 + 1 = **73 cycles**
  to `done`. A special case takes **6**.

#### 3.6.8 FpNarrowPack: FP → int / single / double / extended for stores

One registered stage (`FpNarrowPack.scala:119-280`).
* Stage 0: three jam shifters, three round adders (int 64-bit, f32 32-bit, f64 64-bit),
  plus the extended-chunk mux.
* Stage 1: range checks, saturation and packing.
* Exceptions are raised on chunk 0 only (`:359-368`).
* The packed formats (011 and 111) fall to `default → 0` (`:354`).
* The rounding mode is the **live** `fpRmodeIn` (`DivEuPlugin.scala:798`), not an
  issue-time sample. The FP lane, by contrast, samples it at issue (§3.5.3).

#### 3.6.9 FP register-file interplay

* The FP physical tag is 4 bits (`FpPipeContext.pdst`, `DivEuPlugin.scala:128`; FP wakeup
  `UInt(4 bits)`, `:297`), so there are 16 FP physical registers, hard-coded.
* Dyadic ops read FPn through `fpRdA` (`:1052`). The dyadic and monadic split is defined in
  `FpSource.dyadicOpmodes` (`FpSource.scala:150-153`), and only dyadic ops claim the FPn
  dependency.
* FPCC is renamed separately. It is written with internal bit order {NaN, I, Z, N}
  (`FpTypes.scala`, `FpResult`) and reversed for FPSR reads (`DivEuPlugin.scala:778`).

#### 3.6.10 FMOVEM cold path (`fpu/FmovemCold*.scala`)

This path is instantiated inside `exception/ExceptionUnit.scala:574` (`fmBackend`). It is
**not** in the CPLX EU. It is driven by the serialized sysOp FSM (`ExceptionUnit.scala:3062-3130`)
and is used for the dynamic-register-list FMOVEM form.

* `FmovemColdWalk`: one 12-byte element per mask bit. It walks FP0→FP7, or FP7→FP0 with
  decreasing addresses for predecrement (`FmovemColdWalk.scala:40-106`).
* `FmovemColdTransfer`: **one byte per transaction**, 12 per register (`:136-210`).
* `FmovemColdMemory`: **a separate translation per byte**, then one memory access, with a
  single outstanding request (states IDLE → XLATE → XWAIT → MEMORY → MWAIT → DONE,
  `:244-309`).

The ExceptionUnit stages loaded values in `fmLoaded = Vec(8, 80 bits)`
(`ExceptionUnit.scala:598`). That is 640 flops.

---

## 4. Configuration knobs

`ShippingCoreConfig` (`top/ShippingCoreConfig.scala`) has **no knob that targets these
units**. Their shape comes from code constants:

| Constant | Value | Where | Notes |
|---|---|---|---|
| ALU EU count | 2 | `SocketTop.scala:98-99` | hard-coded instances |
| `MulCore.Latency` | 7 | `MulCore.scala:305` | MUL FIFO depth = 9 |
| `bfLatency` | 4 | `DivEuPlugin.scala:1625` | bit-field FIFO depth = 6 |
| `FpuCore.FixedLatency` | 13 | `FpuCore.scala:76` | = 10 (front) + 3 (`FpRoundPack.Latency`) |
| `FpAddPipe/FpMulPipe/FpCheapPipe.Latency` | 10 | each file | must be equal |
| `FpDivSqrtCore.Iterations` | 65 | `FpDivSqrtCore.scala:45` | |
| `DivCore` iterations | 64 (literal `63`) | `DivCore.scala:97` | no knob |
| `physNzvc` | shipping 32 (default 16) | `ShippingCoreConfig.scala:508`, `Global.scala:145` | NZVC tag width 5 bits in the CPLX contexts |
| `ROB_DEPTH` | 32 (`ROB_DEPTH_DEFAULT`) | `Global.scala:34` | sizes `mulHiMem`/`mulHiValid` |

Hard-coded widths that do not follow the configuration: `pdst = UInt(6 bits)` in
`MulPipeContext`, `MulHiContext`, `BfPipeContext`, `CplxResult`, `compPdst` and
`wakeupPort` (`DivEuPlugin.scala:64,79,95,106,278,536`). The FP tags are `UInt(4 bits)`
(`:128-130,296-299`).

### Dead or unreachable logic (from code)

* `FpuCore.io.ready`, `src/dstUnnormal` and `src/dstDenorm` have no reader.
* `DivCore`'s divide-by-zero arm cannot be reached (the EU filters zero divisors).
  `DivUnit.io.divByZero` and `io.busy` are unread.
* `MulCore.io.busy` is the constant `False` (`MulCore.scala:373`) and is unread.
* `BranchEuPlugin.brDbg`, `AluEuPlugin.wbObs`, `BranchEuPlugin.wbObs`, `chkNObs`,
  `arbCollisionObs` and `divRemStale` have no hardware consumer, so synthesis should prune
  them.
* `Bitfield.apply` (`:343`) has no caller in RTL (only `stage1`/`stage2` are used).
* `Shifter.apply` and `Shifter.stage1` (compositions) have no RTL callers. The ALU calls
  `stage1a`, `stage1b` and `stage2` directly.
* In `AluDatapath`, `rsp.result` for BITFIELD-class ops is no longer routed, because those
  ops never reach the ALU.
* The `FpNarrowPack` `default` arm (packed formats) returns zero.

---

## 5. Performance behaviour

### 5.1 Latency table

Cycle numbers count from IQ select C. "Done" means the cycle the PRF write, ROB
completion and wakeup fire.

| Op class | Unit | Done at | Dependent can read | Issue rate |
|---|---|---|---|---|
| ADD/SUB/logic/MOVE/CMP/ADDA/Scc-free ALU, BCD, PACK, CAS kernel, toCcr | ALU (×2) | C+2 | back-to-back (consumer selected at C+1 reads the S1 bypass) | 1/cycle/EU |
| ASx/LSx/ROx/ROXx | ALU slow | C+5 | consumer selected at C+4 (wakeup at S1a) | 1/cycle, but takes away 1 fast slot at C+3 |
| Bcc/DBcc/Scc/JMP/JSR/RTS/TRAPcc | Branch | C+2 (completion) | A7/Dn write bypassed at C+2 | 1/cycle |
| Mispredict → frontend redirect | Branch → ROB | redirect valid at C+3 | – | oldest pending only |
| CHK, CMP2/CHK2, FPCTRLRD | CPLX legacy | C+5 | dynamic wakeup at C+5 | 1 per 2 cycles (`!s1Valid`) |
| FMOVE FPn,<ea> chunk | CPLX legacy | C+6 | same | 1 per 3 cycles |
| DIVU/DIVS (any size) | CPLX legacy | ≈ C+70 | dynamic | 1 per ≈68 cycles |
| DIV.L remainder (DIVREM) | CPLX legacy | ≈ C+72 | dynamic | follows its DIV |
| DIV by zero | CPLX legacy | C+5 (fault) | – | – |
| MULU/MULS (.W/.L) | CPLX MUL | ≈ C+11..12 (MUL core done at C+9; the `StreamFifo` push→pop latency is the SpinalHDL 1.14 default, **unverified**) | dynamic | 1/cycle |
| MUL .L64 high half (MULHI) | CPLX | ≥ 1 cycle after the low half | dynamic | – |
| BFxxx | CPLX BF | ≈ C+8..9 (FIFO push at C+6, plus the same FIFO caveat) | dynamic | 1/cycle |
| FADD/FSUB/FCMP/FMUL/FABS/FNEG/FMOVE/FTST/FINT/FMOVECR | CPLX FP fixed | C+17 (accept C+2, FS1 C+3, FpuCore done C+16, comp register C+17) | dynamic, no FP bypass | 1/cycle |
| FDIV/FSQRT (all precisions) | CPLX FP iterative | C+77 (start C+3 + 73 + 1) | dynamic | 1 per ≈74 cycles |
| FDIV/FSQRT special operand | CPLX FP iterative | C+10 | – | – |

How the DIV number is derived: accept at C+2, S1 at C+3, DivCore running C+4..C+67,
`done` at C+68, legacy result at C+69, comp register at C+70. The code paths are
`DivCore.scala:73-101` and `DivEuPlugin.scala:1526-1582,1854-1857`.

### 5.2 Structural hazards

1. **One CPLX issue port, head-of-line blocking.** The IQ does not know that the legacy lane
   is busy. It only masks younger divide-family slots (`IssueQueuePlugin.scala:945-948`).
   - The trailing DIVREM of a DIV.L becomes the oldest divide-family uop and can be selected
     right after its DIV. It then sits in `ip` with `ip.ready = !busy && !s1Valid = False`
     for the whole divide.
   - The IQ's own `m2sPipe(collapsBubble = false)` takes its `ready` directly from the EU (`piped.ready := issuePorts(k).ready`, `IssueQueuePlugin.scala:1278-1282`).
   - Result: **MUL, FP and BITFIELD issue stall for about 66 cycles behind every DIV.L that
     has a DIVREM.** The same happens behind any legacy op (CHK, CMP2, a second DIV)
     selected while a divide iterates.
   - This is derived from the code and has not been measured.
2. **One int completion stage in the CPLX EU** for legacy, MULHI, MUL and BF. A legacy result
   always wins, so the MUL and BF FIFOs absorb the delay. The credit counters make the FIFOs
   lossless.
3. **One branch EU.** At most one branch resolves per cycle.
4. **ALU shift port reservation.** Covered in §3.1.
5. **FP fixed vs. iterative.** The fixed result always wins the FP completion register. A
   continuous fixed-op stream delays an FDIV result.
6. **FPSTORECVT** holds the legacy lane for 2 cycles per chunk. Extended stores take 3 chunks
   and double stores take 2.
7. **The FMOVEM cold path** handles one byte per translate plus memory round trip, so 96
   byte transactions for 8 registers. It runs serialized in the ExceptionUnit.

---

## 6. Area and timing notes (inferred from structure; no netlist or utilization report was consulted)

* **Duplicated rare logic in both ALU EUs.** Each ALU instance carries:
  - the second `AluDatapath` used only by CASOP (`AluEuPlugin.scala:467-471`);
  - BCD (two 10-bit add/sub chains plus NBCD);
  - PACK/UNPK (two 32-bit adders, `:396,409`);
  - the BFRESOLVE packers;
  - the CAS result mux;
  - the full four-stage barrel shifter with its ~1.4 kbit midpoint register (much of it
    prunable, see §3.1);
  - **a third int PRF read port `rdC` that only CASOP uses** (`:140,171`).
* **PRF read and bypass load from this slice.** These units hold 3+3+3+3 = 12 int read ports
  (ALU×2, branch, CPLX). They contribute 2+2+1+1 = 6 int bypass sources (ALU fast and slow
  ×2, branch `anByp`, CPLX `intByp`). Every non-`forceNoBypass` int read port compares
  against every bypass source. The ALU slow bypasses add 2 int, 2 NZVC and 2 X sources.
* **Wide CPLX operand cones.**
  - FS1 `fpSrcVal` is a nine-way 80-bit mux fed by the three format converters. `intToExtended`
    contains CLZ32 plus a 64-bit shift, `doubleToExtended` CLZ52 plus a shift
    (`DivEuPlugin.scala:1186-1216`; `FpSource.scala:231-290`).
  - FS1 also latches 80-bit FPn, FPm and FP immediate values plus three 32-bit int words
    (≈336 flops).
* **FP pipeline flop cost.** `FpRoundReq` is about 182 bits (sign 1 + exp 18 + sig 64 +
  round/sticky/bypass 3 + bypassValue 80 + writeFp/fpccFromSrc 2 + fpccOverride 4 + exc 6
  + rmode 2 + prec 2). It is registered at:
  - every FpAddPipe stage A1–A9 (9 × 182);
  - FpMulPipe `ctxReq` `Vec(7)` plus M9 (8 × 182);
  - FpCheapPipe C2 plus `dlyR` `Vec(7)` (8 × 182).

  That is roughly 4.5 kflops of request context. Many bits are constant per front, for
  example `bypassValue` in the multiply front or `round`/`sticky` in the cheap front, and
  synthesis may trim those. FpRoundPack itself registers `req` in two stages.
* **DSP.**
  - MulCore: a 33×33 signed multiply, about 4 DSP48E2.
  - FpMulPipe: a 64×64 unsigned multiply, about 10–16 DSP48E2.
  - Both depend on unconditional, clock-enable-free register chains for retiming into the DSP
    (`MulCore.scala:363-370`, `FpMulPipe.scala:134-137`).
* **Fan-out duplication by hand.** `fpS1Kind`/`fpS1KindB` with `dont_touch`
  (`DivEuPlugin.scala:1144-1146`).
* **Branch S1 cone.** `relTarget`, the 3-input `indTarget` adder, the 32-bit target mux, the
  32-bit `!=` compare against `predTarget`, then `mispredict` → `completionPort`. The S0 side
  has the index sign-extend and shift (0..3) sitting after the int PRF read and bypass mux
  before the register (`BranchEuPlugin.scala:175-178,191`).
* **ALU issue ready.** `issuePort.ready` decodes `issuePort.payload.uop.op === SHIFT`
  (`AluEuPlugin.scala:253-256`). The payload is `coldRead(piped.payload)`
  (`IssueQueuePlugin.scala:1280`), an asynchronous cold-payload read, so the ready path
  depends on that read.

---

## 7. Observations (factual; candidates for the reviewer)

1. **Lost fault when branch and CPLX faults coincide (probable correctness bug).**
   `rob.logic.euFaultCompletion` is driven from `branchEu.trapvFault`
   (`FullCoreSynth.scala:298-299`). It is then overridden by
   `when(divEu.euFault.valid){…}` (`:404-407`). Last assignment wins, so in a cycle where
   both fire, **the branch EU's TRAPV/TRAPcc or address-error fault is dropped**. The source
   comment says the branch fault "takes priority"; the code does the opposite.
   - The branch's `completionPort` still fires (`BranchEuPlugin.scala:375`).
   - With `addrErr`, `redirect` was already forced false (`:314`).
   - So an older faulting branch whose fault is dropped retires as a normal fall-through
     (TRAPcc is not taken, and an odd-target transfer silently falls through). This happens
     whenever the CPLX fault belongs to a younger instruction.
   - It is rare (it needs same-cycle TRAPcc/odd-target and CHK/DIV0/FPSTORECVT faults), but
     it does not require any special flag. A fix needs a second port or a merge.
2. **CPLX head-of-line blocking behind DIV** (§5.2-1). A cheap mitigation: let IQ select see
   a "legacy busy" signal, or hold DIVREM out of selection until its DIV completes. MUL, FP
   and BF would then flow during a divide.
3. **No divider early-out.** `DivCore` always runs 64 iterations (`DivCore.scala:97`). For
   `.W` and `.L32` the upper 32 dividend-magnitude bits are always zero (the operand was
   extended from 32 bits, `DivEuPlugin.scala:715-721`). The first 32 iterations therefore
   only shift zeros. Pre-shifting the dividend by 32 and starting at `cnt = 32` would cut
   DIV latency from about 70 to about 38 cycles for those forms, with no change to the
   per-cycle path.
4. **FDIV/FSQRT ignore precision for iteration count.** It is always 65 (`FpDivSqrtCore.scala:252`).
   `FSDIV`/`FSSQRT` (or FPCR.PREC = single) need only about 26 quotient bits and double
   needs about 55. An early stop keyed on `precR` would be exact because the rounding
   precision is applied afterwards.
5. **Cheap FP ops cost 15 cycles from accept.** FMOVE, FABS and FNEG do one cycle of work
   and then wait seven delay stages (`FpCheapPipe.scala:278-284`). The single-port design
   requires this. The cost is FP register-to-register moves at full FADD latency, plus about
   1.3 kflops of padding for the request context.
6. **The FpAddPipe A8/A9 delay stages** exist only to equalise depth (`FpAddPipe.scala:279-280`).
   Each one carries a full ~182-bit `FpRoundReq`.
7. **The MULHI stash is sized to `ROB_DEPTH` (32 entries)** (`DivEuPlugin.scala:1036-1037`).
   At most `mulResultDepth = 9` MUL results are in flight. A small tagged structure, like
   the one-entry DIV remainder stash next to it, would remove a 32×32 LUTRAM, a 32-bit valid
   vector and a 32:1 read mux.
8. **Rare datapaths duplicated across the two ALUs** (§6). CAS/CAS2, BCD, PACK/UNPK,
   BFRESOLVE and SR/CCR RMW could be steered to one EU, or to CPLX as bit-field already was.
   That would delete one CAS `AluDatapath`, one PACK/UNPK pair, one BCD chain, and **one or
   both `rdC` int read ports** (each costs a PRF read port).
9. **The ALU shift costs a fast slot** (§3.1). The IQ masks fast candidates whenever
   `s1aValid` is set. A shift-heavy stream on one EU loses 1 of every 2 fast selections on
   that EU while shifts are in S1a. If shifts were steered to a single EU, the other EU's
   fast stream would never be blocked.
10. **ALU slow-path bypasses** (`intByps`, `nzvcByps`, `xByps`) add 6 bypass sources
    core-wide for the only path that uses them (shift → dependent at exactly S3). Waking one
    cycle later (from S2 instead of S1a) would let the consumer read the array instead.
    That removes these bypasses for +1 cycle of shift latency.
11. **The branch EU has no flush.** Its S1 can fire `anW` (A7 or Dn), `completion` and
    `trapvFault` in the backend-squash cycle (`BranchEuPlugin.scala:375,426,441`). The ROB
    filters completions with `bcInFlight`/`!flushing` (`RobPlugin.scala:3741-3742`).
    Whether a squash-cycle `anW` write to a just-freed pdst is harmless depends on rename and
    free-list timing, which was not traced. **(unverified)**
12. **`FpNarrowPack` reads the live FPCR rounding mode** (`DivEuPlugin.scala:798`), while the
    FP arithmetic lane samples at issue (`:1169`). This is consistent only while every FPCR
    write is serializing. That is not checked here.
13. **FpuControlPlugin stores the FPCR without a mask** (`FpuControlPlugin.scala:142`). FPSR
    is masked with `0xF0FFFFFF` (`:145`). The FPCR reserved bits ([31:16], [3:0]) are kept and
    read back through FPCTRLRD (`DivEuPlugin.scala:780`) unless the writer masks them. The
    writer, the ExceptionUnit, was not checked.
14. **An FPSR accrual is dropped when `setFpsr` fires in the same cycle**
    (`FpuControlPlugin.scala:152`). Whether that is architecturally right depends on the
    ordering of the two events, which was not traced.
15. **Unnormal operands** are classified (`FpuCore.scala:198-199`) but never acted on. FADD
    treats an unnormal as a normal with its leading zeros, and FMUL pre-normalises only when
    `exp==0`. The MC68040 takes an unimplemented-data-type trap here. This is a possible
    divergence on exotic inputs.
16. **FBcc never raises BSUN**, because cc[4] is ignored (`BranchEuPlugin.scala:229-250`).
    An FBcc with the BSUN enable set on an unordered compare will not trap.
17. **FMOVEM cold path translates every byte** (`FmovemColdMemory.scala:257-299`). That is
    12 translations per register. A cold path by design, but it costs about
    12 × (TLB + uncached access) per register.
18. **Hard-coded tag widths** in the CPLX contexts (6-bit int pdst, 4-bit FP tags; §4) are
    not derived from `M68kParams`. Changing `physInt` above 64 or the FP physical count above
    16 would silently truncate them. Whether any elaboration-time `require` guards these
    widths was not checked.
19. **The CPLX `+1` issue register** (`DivEuPlugin.scala:418`) adds a cycle to every CPLX op,
    including the 1-cycle CHK/CMP2/FPCTRLRD and the II = 1 MUL/BF/FP pipes. For MUL- and
    FP-heavy code this is pure latency.

---

*Output scope: this document only. No RTL was modified.*
