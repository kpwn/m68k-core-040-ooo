# 04 — Rename, Dispatch, Issue Queue and Physical Register Files

Source: commit `fee95f6f` (the bitstream on the board). All paths are relative to
`src/main/scala/m68k040/` unless they start with `docs/`.

**Method.** Every statement here comes from the RTL. Source comments and `docs/*` were
not used as evidence: many are stale, and where one disagrees with the code this
document says so (section 7.4). Area figures in section 6 are **structural estimates
worked out from port/width/depth counts**. They are not synthesis measurements, except
the per-read-port figure the brief supplied (~270 LUT per int-PRF read port), which is
labelled where it is used.

**Shipping configuration that matters for this slice** (from the `SHIPPING_CONFIG`
build log line). The other knobs in that line do not touch this slice.

| Knob | Shipping | Effect here |
|---|---|---|
| `lsOooIssue` (`LS_OOO_ISSUE=1`) | true | `IssueQueuePlugin.loadBypassUnreadyLoad = true` (`top/SocketTop.scala:191`) |
| `ipcThroughput` | true | IQ `earlyStoreAddress = true` (`top/ShippingPlugins.scala:75`) |
| `ipcLateStore` | true | IQ `earlyAutoStoreAddress = true` (`top/ShippingPlugins.scala:75`) |
| `specLoadWakeup` | false | IQ `specLoadWakeup = false`; the speculative LS clear and `lsSpecBlocked` are constant False |
| `lsFourCycleL1` | false | IQ `fourCycleL1 = false`; `p3Preselect` = 0 |
| `physNzvc` / `physX` | 32 / 32 | NZVC and X pools of 32 (the default is 16, `top/ShippingCoreConfig.scala:508,515`); 5-bit tags |
| RenameStage | `new RenameStage()` (`top/SocketTop.scala:155`) | `retireWidth = 2`, `preparedRetirement = false` |
| ROB depth | `Global.ROB_DEPTH_DEFAULT = 32` (`Global.scala:34`) | 5-bit robId |
| Int PRF pool | `Global.PHYS_INT_REGS_DEFAULT = 54` (`Global.scala:137`) | 6-bit tags |

---

## 1. Purpose and position

```
DecodeStage (MicroOpQueue pop, 2 uops/cycle)
   │  DecodeUopService.uops : Stream[Vec[DecodedUop],2] + uop1Valid + uopPred(s)
   ▼
RenameStage  — 5 RATs, 5 freelists, intra-group bypass        (rename/RenameStage.scala)
   │  combinational rename → PipeStage register (flushable)   (RenameStage.scala:559-560)
   ▼
DispatchPlugin — one handshake: ROB alloc AND IQ push          (dispatch/DispatchPlugin.scala)
   ▼
IssueQueuePlugin — 16 slots, 2-wide push, 5 select ports       (execute/iq/IssueQueuePlugin.scala)
   │  select (cycle C) → registered issue stage → cold-payload LUTRAM read (C+1)
   ▼
ALU0, ALU1, Branch, LS, CPLX(DivEu/FPU) EUs
   │  read operands from the PRFs (async LUTRAM + bypass)      (execute/regfile/RegFilePlugin.scala)
   ▼
writeback → PRF write ports; wakeups → IQ; completion → ROB
```

The front end feeds 2 uops/cycle. Rename is combinational and is followed by one
registered `PipeStage` (`frontend/PipeStage.scala`, used at `RenameStage.scala:560`).
Dispatch is a pure handshake with no storage (`DispatchPlugin.scala:299-319`). The IQ
holds uops until their operands are ready. Its 5 select ports feed the 5 execution units
through one register stage per port. The physical register files are separate
`RegFilePlugin` instances: int, NZVC, X, FP and FPCC.

---

## 2. Interfaces

### 2.1 Services

| Plugin | Provides | Consumes |
|---|---|---|
| `RenameStage` | `RenameUopService` (`uops`, `uop1Valid`), `RenameCommitService` (`commitPorts`, `flushPort`), `CommittedMapService` (`intPhys`, `nzvcPhys`, `xPhys`), `CommittedFpMapService` (`fpPhys`), `PreparedCommitService` (unused, `None`) — `RenameStage.scala:23-25,563-603` | `DecodeUopService` (`:30`) |
| `DispatchPlugin` | `DispatchPerfDetailService` | `RenameUopService`, `RobAllocService`, `IssueQueueService`, optionally `MemoryOrderService` (`DispatchPlugin.scala:37-42`) |
| `IssueQueuePlugin` | `IssueQueueService` (`execute/iq/IqContext.scala:208-284`), `LateStoreDataService` (only when `earlyStoreAddress`, `IssueQueuePlugin.scala:131`) | `LsEuService` only when `fourCycleL1` (off). Every other input arrives as a port that `BackendWiringPlugin` overrides (`top/FullCoreSynth.scala:276-422`) |
| `RegFilePlugin{Int,Nzvc,X,Fp,Fpcc}` | `RegfileService` subtypes (`execute/regfile/RegfileService.scala:58-74`) | none |

`MemoryOrderService` is implemented only by `ls/MemoryOrderPlugin.scala`, and that plugin
is not in the shipping plugin list (`top/SocketTop.scala:137-206`). So in the shipping
core `memoryReady = True` (`DispatchPlugin.scala:298`), and the memory-order reservation
code in dispatch (`:273-297`) is not elaborated.

Non-service cross-plugin wires, all driven by `BackendWiringPlugin`
(`top/FullCoreSynth.scala:95-123`):

- `iq.flushPort := rob.logic.backendSquash`.
- `RenameStage.logic.pipeFlush := backendSquash`, which flushes the rename→dispatch register.
- `RenameStage.logic.allocHalt := rob.logic.earlyPend`, the Tier-1 rename freeze.
- `RenameStage.flushPort` is the ROB's `rc.flushPort := flushing` (`rob/RobPlugin.scala:3812`). It drives the RAT rollback and the freelist rollback.

### 2.2 Global (Database) keys

| Key | Producer | Consumer in this slice |
|---|---|---|
| `PHYS_INT_REGS` | `ParamPlugin` (`core/ParamPlugin.scala:26`) | IQ `physIntN` (`IssueQueuePlugin.scala:399`) |
| `PHYS_NZVC_REGS` | `ParamPlugin` (`:33`) | IQ `physNzvcN` (`:417`) |
| `PHYS_X_REGS` | `ParamPlugin` (`:38`) | IQ `physXN` (`:426`) |
| `ROB_DEPTH` | `ParamPlugin` (`:25`) | not read here; the IQ uses the plain constant `ROB_DEPTH_DEFAULT` |

Most sizing in this slice uses **plain constants**, not keys:

- `Global.PHYS_INT_REGS_DEFAULT`, which sizes the int freelist (`RenameStage.scala:71`) and the int PRF (`RegfileSpec.scala:20`).
- `PHYS_NZVC_ID_W` and `PHYS_X_ID_W`.
- `ROB_ID_W_DEFAULT`, which sizes the IqContext robId and the cold-Mem depth (`IssueQueuePlugin.scala:340-341`).

The IQ cross-checks key against constant with `require` (`IssueQueuePlugin.scala:406-432`).

**Hard-coded widths.** The int phys-tag width is the literal 6 in four places:

- `RenameStage.scala:35` (`intRat physIdWidth = 6`)
- `RenamedUop.scala:10`
- `IqContext.scala:56` and `:203`
- the IQ wakeup ports (`IssueQueuePlugin.scala:139-142`)

FP and FPCC are the literal 4 in `RenamedUop.scala:13`, `IqContext.scala:59` and
`RenameStage.scala:43,45,83-84`. Nothing ties these literals to `PHYS_INT_REGS_DEFAULT`.
Raising the int pool above 64 would silently truncate tags; there is no `require` for
this width.

### 2.3 Renamed-uop format (`RenamedUop`, `rename/RenamedUop.scala:9-231`)

The bundle is every `DecodedUop` field copied verbatim (`RenameStage.scala:233-302`),
plus the prediction fields taken from `du.uopPred(s)` (`:205,295-298`), plus these
physical fields:

| Field | Width (shipping) | Source |
|---|---|---|
| `psrcA/B/C` + valid | 6+1 each | int RAT ports 2s, 2s+1, 6+s; slot-1 bypass from slot 0 (`:308-313,404-406`) |
| `pdst`, `pdstValid`, `pdstOld` | 6, 1, 6 | int freelist pop s; old = RAT port 4+s (`:320-326`) |
| `dstArch` | 5 | `dec.dstReg` (`:305`), the commit-RAT address |
| `pNzvcSrc/Dst/Old`, `readsNzvc`, `writesNzvc` | 5, 5, 5, 1, 1 | NZVC RAT/freelist (`:329-337`) |
| `pXSrc/Dst/Old`, `readsX`, `writesX` | 5, 5, 5, 1, 1 | X RAT/freelist (`:340-348`) |
| `pFpSrcA/B`, `pFpDst`, `pFpOld` + valids, `fpDstArch` | 4 each, 3 | FP RAT/freelist (`:353-366`) |
| `pFpccSrc/Dst/Old`, `readsFpcc`, `writesFpcc` | 4 each | FPCC RAT/freelist (`:369-377`) |

The ROB and the IQ cold store each keep a full copy of this bundle (section 3.6.4).

---

## 3. Microarchitecture

### 3.1 Rename width and the renamed register classes

Rename is **2 uops per cycle**: 2 slots, with slot 1 qualified by `uop1Valid`
(`RenameStage.scala:192,212`). Five independent rename classes:

| Class | Arch regs | Physical | Initially free | RAT read / write / commit ports | Freelist pop / push |
|---|---|---|---|---|---|
| Int (D0-7, A0-7, T0-T3) | 20 (`isa/Isa.scala:18`) | 54 | 34 | 8 / 2 / 2 (`RenameStage.scala:35`) | 2 / 2 (`:71`) |
| NZVC | 1 | 32 (shipping) | 31 | 2 / 2 / 2 (`:36`) | 2 / 2 (`:75`) |
| X | 1 | 32 (shipping) | 31 | 2 / 2 / 2 (`:37`) | 2 / 2 (`:80`) |
| FP data (FP0-7) | 8 | 16 | 8 | 6 / 2 / 2 (`:43`) | 2 / 2 (`:83`) |
| FPCC | 1 | 16 | 15 | 2 / 2 / 2 (`:45`) | 2 / 2 (`:84`) |

The CCR is split into NZVC and X, with X renamed separately. FPCC readers exist in the
shipping decode: `readsFpcc := True` at `decode/MicroOpAssembler.scala:1575` and
`decode/Microcode.scala:3090,3637`. Nothing else is renamed. SR, USP, VBR and the other
system state are handled by the ROB/ExceptionUnit, not here.

Int RAT port use per slot: srcA and srcB on ports 2s and 2s+1, dst-old on port 4+s,
srcC on port 6+s (`RenameStage.scala:215-220`).

### 3.2 RAT structure (`rename/RatTable.scala`)

- Two register Vecs per class, `specReg` and `commReg`, each `archDepth × physIdWidth` flops (`RatTable.scala:78-79`). They are not `Mem`s, so the multi-write lowering does not touch them.
- Writes: for each arch cell, the ports are scanned from low to high, so on a same-cycle, same-address conflict the **highest-numbered port wins** (`:83-94`). Slot 1 is port 1, so the younger write wins, which is the correct WAW result.
- A `location` bit per arch reg says which copy is current (`:120`): 1 means `specReg`, 0 means `commReg`. A rename write sets it (`:123-127`). Rollback clears all of it in one cycle (`:130-132`).
- Reads are fully combinational: `Mux(location(a), specReg(a), commReg(a))` (`:135-139`). For the int RAT each read port is a 20:1 mux of 6 bits from each copy, plus the 2:1 location mux, and there are 8 such ports.
- `committedPhys` exports `commReg` (`:142`). Its consumers are `committedPhysA7` (feeding the PRF `a7Rd` port, `top/FullCoreSynth.scala:739`), `CommittedMapService` (the ROB's FMOVEM An write, `rob/RobPlugin.scala:2994`; the DebugCtrl live-register read) and the NZVC/X/FPCC committed readers (`RenameStage.scala:577-599`).

**Initialisation** (`RenameStage.scala:101-108,455-481`). A 5-bit counter runs for
`ARCH_INT_REGS` = 20 cycles:

- It writes the identity map to `intRat.commits(0)`.
- It writes FP0-7 → phys 0-7 to `fpRat.commits(0)` during the first 8 ticks.
- It writes arch 0 → phys 0 to the NZVC, X and FPCC RATs on tick 0.

Rename is gated by `initDone` (`:189-190`).

**Commit** (`RenameStage.scala:492-525`). `commitPorts(k)` drives `intRat.commits(k)`
and `fpRat.commits(k)`. Lane 0 is muxed with the init writes. The NZVC, X and FPCC
commit lanes are gated by `initDone` on lane 0 only.

**Checkpointing and recovery.** There are no branch checkpoints. Recovery is always
"restore the committed map":

- `flush` asserts `rollback` on all five RATs (`RenameStage.scala:139-143`). That zeroes `location`, so every later read returns `commReg`.
- `flush` is the ROB's `flushing` (`rob/RobPlugin.scala:1187,3812`).
- Every redirect source in `doFlushNext` (`RobPlugin.scala:3594`) fires at the ROB head. `branchRedirect = retire0 && p0.retireAlone && mispredictStore(h0)` (`:2591`). `orderRedirect` requires `retire0` (`:3524`). `lsReplayRedirect` requires `headReady` (`:3542`). Exceptions also come out of the ROB/exception unit.
- So the committed map is always exactly the correct restart map.
- Latency: `location` is cleared on the flush clock edge, so a rename on the cycle after `flushing` already sees the committed map. The **real** recovery cost is that mispredicts are resolved at retire, not at execute.

An early, execute-time branch mispredict does not roll back rename. It sets `earlyPend`,
which freezes rename through `allocHalt` (`RenameStage.scala:185,189-190`) until the
branch reaches the head and the Tier-2 flush fires.

### 3.3 Freelist (`rename/Freelist.scala`)

- **Storage.** A circular buffer `Mem(UInt(idW), 1 << ptrW)` (`Freelist.scala:42`). For int, `ptrW = 6`, so the buffer is 64 deep and holds 34 live ids. The Mem has three write ports: the power-on fill (`:118-122`) and 2 push lanes (`:198-202`). Its width is 6 bits, which is below 10, so `MultiPortWritesSymplifier` lowers it to `RamAsyncMwXor` (`hw/MultiportRam.scala:310-316`). That XOR RAM has 3 banks, each with 2 cross-reads plus 2 pop reads.
- **Pointers.** `head` advances on speculative pops. `tail` advances on reclaimed pushes. `commHead` advances by the number of committed pushes (`:178-183`). `count` is the free count.
- **Pop.** Takes are compacted: pop k reads `head + (number of takes on ports 0..k-1)` (`:155-161`). The read is an async read with no underflow guard inside the freelist.
- **`popReady = initDone && count >= popPorts`** (`:149`). It needs 2 free ids even when only one is wanted.
- **Deferred reclamation.** A commit push enters `reclaim`, which is registered one cycle (`:94-99`). The id therefore re-enters the ring, and `count`, **one cycle after** the commit. The `reclaim` valid is gated by `!io.flush`. Draining from `reclaim` to the RAM and `tail` continues during a flush (`:187-206`).
- **Recovery.** On a flush, `head := commHead` and `count := freeN` (`:139-146`). This is a pointer-only, single-cycle rollback. Any push that arrives during a flush is dropped (`:178` sits under `!io.flush`). The ROB cannot commit while `flushing`, because `headReady` includes `!flushing` (`rob/RobPlugin.scala:1221`), so such a push never occurs.
- **Init.** On power-on the RAM is filled at one id per cycle: 34 cycles for int, 31 for NZVC/X, 8 for FP, 15 for FPCC (`:123-134`).
- The `banked` path (`pushPorts >= 16`, `:40,208-241`) is not elaborated in shipping.

**The rename stall gate** (`RenameStage.scala:162-163,189-190`): rename advances only if
**all five** freelists report `popReady`, rename `initDone` is set and `allocHalt` is low.
The gate is unconditional: an int-only group still stalls if FP or FPCC has fewer than 2
free ids.

### 3.4 Intra-group dependencies (`RenameStage.scala:404-422`)

Slot 1 takes slot 0's freshly allocated tag in these cases:

| Condition | Slot-1 field that takes slot 0's tag |
|---|---|
| `dec0.dstValid && dec0.dstReg === dec1.srcXReg` | `psrcA`, `psrcB`, `psrcC` (each compared separately) |
| `dec0.dstValid && dec0.dstReg === dec1.dstReg` | `pdstOld` |
| `dec0.writesNzvc` (unconditional) | `pNzvcSrc` and `pNzvcOld` |
| `dec0.writesX` (unconditional) | `pXSrc` and `pXOld` |
| FP: same three comparisons, gated by `dec0.writesFp` | `pFpSrcA`, `pFpSrcB`, `pFpOld` |
| `dec0.writesFpcc` | `pFpccSrc` and `pFpccOld` |

The int comparisons do **not** check `dec1.srcAValid` and the other valids. That is
harmless, because an invalid source tag is ignored downstream.

### 3.5 Dispatch (`dispatch/DispatchPlugin.scala`)

- `fire = ren.uops.valid && rob.allocReady && iq.push.ready && memoryReady` (`:299`). In shipping `memoryReady` is True.
- The ROB alloc and the IQ push share that same `fire`. The IQ gets `robId0` and `robId1` from the ROB (`:310-319`).
- There is no queue in dispatch. The only storage between rename and the IQ is the rename `PipeStage` register.
- Stall conditions:
  - `rob.allocReady` = `RegNext(countNext <= depth-2 && sysCapFree >= 2)` (`rob/RobPlugin.scala:2510`). With a 32-entry ROB this means at most 30 entries before an allocation, i.e. a full ROB.
  - `iq.push.ready` = `readyReg` (section 3.6.2).
  - Rename itself stalls on any freelist, `allocHalt` or `initDone`.

### 3.6 Issue queue (`execute/iq/IssueQueuePlugin.scala`)

#### 3.6.1 Organisation

- **One** unified queue: `slotCount = 16`, `wayCount = 2`, `lineCount = 8` (`:98-100`).
- Slot priority is `line*2 + way`. Slot 0 is the oldest (`:189-191`).
- Each slot has these fields (`:192-230`):
  - `sel` (occupied).
  - `triggers`: a triangular `Bits(priority+1)` static-dependency mask, 136 flops in total.
  - `hot`: the narrow `IqHot` record (`IqContext.scala:55-195`).
  - `isAluSlow`.
  - `dynWait`: a 16-bit per-source dynamic-wait vector. The bit map is `DynWait`, `:54-85`.
  - `dynWaitAny`: a registered OR of the next value of `dynWait`.
- **ready** = `sel && triggers(priority-1..0) == 0 && !dynWaitAny` (`:229-230`).
- **Cold payload.** The full `RenamedUop` is not kept in the slots. It is written to two `Mem(RenamedUop, 32)` LUTRAMs, `coldWay0` and `coldWay1`. They are indexed by robId and banked by push way, so each bank has exactly one write port (`:340-346,1645-1652`). It is read at issue, after the registered issue stage, at `coldRead(piped.payload)` (`:1280`). Each of the 5 ports reads both banks, so there are 10 async read ports in total.

#### 3.6.2 Insertion and "compaction"

On every `push.fire`:

- Every line `i` copies line `i+1`, and line 0 is discarded (`:1603-1618`).
- The two new uops go into line 7 (slots 14 and 15) (`:1621-1632`). Way 1 is marked occupied only when `pushSlot1Port` is set.

This is a **uniform shift**. Holes are not squeezed out.

`push.ready = readyReg`, where `readyReg := Mux(push.fire, line1Ready, line0Ready)` and
each term is "both slots of that line empty after this cycle's issue" (`:640-643,2052`).

The consequences of this push rule are in section 5.4.

**Positions and epoch.** Producer positions do not move. `physToPosition[p]` records
`{positionEpoch, way}` at insertion (`:388-394`). `positionEpoch` (3 bits) increments on
every push (`:354-358`). A position decodes to the current slot as
`((pos[3:1] - epoch) ## pos[0])`.

#### 3.6.3 Wakeup: static triggers and dynamic per-source waits

**Static, latency-1 class.** This covers the int, NZVC, X, FP and FPCC producers that are
*not* LS, CPLX or SLOW:

- Scoreboards `sbInt(54)`, `sbNzvc(32)`, `sbX(32)`, `sbFp(16)`, `sbFpcc(16)`. Each is a `busy` bitmap plus `physToPosition` (`:371-445`).
- At push, `trigInit` sets the trigger bit at the producer's slot for each source whose `busy` is set (`:1349-1370`). It uses `sb*BusyEff = busy & ~sb*Clr`, which bypasses the clear that is retimed to C+1 (`:473-482`).
- Intra-push: slot 1 gets bit 14 when slot 0 is a static producer (`:1581-1600`).
- **Event.** When a slot is selected on any of the 5 ports, `events(j)` clears bit j in every younger slot. Because `triggers` is a register, the consumer becomes ready the next cycle (`:1332-1334,1659-1664`). On a push cycle the event index shifts down by 2 (`eventsMoved`).
- The busy bit is cleared at C+1 from the registered issue-stage payload. Port 3 uses `lsSkidHot` instead (`:1959-1968`). There is also a duplicate clear for port 2 (`:1977-1982`).

**Dynamic class.** These producers complete at a time the IQ cannot predict:

- The bitmaps are `lsBusy(54)`, `cplxBusy(54)`, `aluSlowIntBusy(54)`, `lsNzvcBusy(32)`, `cplxNzvcBusy(32)`, `aluSlowNzvcBusy(32)`, `aluSlowXBusy(32)`, `cplxFpBusy(16)` and `cplxFpccBusy(16)` (`:490-538`).
- At push, each source's verdict goes into its own `dynWait` bit. The `still*` helpers exclude a wakeup that fires in the same cycle (`:1411-1563`).
- After that, a bit is cleared only by a **tag compare** against its class's wakeup port, in every slot, every cycle (`:1717-1784`). The `*Busy` bitmaps are not re-read from the slots.
- The busy bitmaps are set at push and cleared on the wakeup. A push in the same cycle wins (`:1987-2045`).
- Every in-flight producer belongs to exactly one tracking structure. The routing order, for push slot 0 and likewise for slot 1, is (`:1812-1862`):
  - int dst: LS → CPLX → SLOW → static.
  - NZVC dst: SLOW → LS → CPLX → static.
  - X dst: SLOW → static.
  - FP and FPCC dsts: CPLX → static.

| Wakeup port | Width (shipping) | Driver (`top/FullCoreSynth.scala`) | Clears |
|---|---|---|---|
| `lsWakeup` | 6 | `lsEu.wakeup` (`:413`) | LS_A/B/C, lsBusy |
| `lsWakeupSpec` | 6 | `lsEu.wakeupSpec` (`:418`) | nothing (specLoadWakeup=false) |
| `lsNzvcWakeup` | 5 | `lsEu.wakeupNzvc` (`:422`) | LS_NZVC |
| `cplxWakeup` | 6 | `divEu.wakeup` (`:380`) | CPLX_A/B/C |
| `cplxNzvcWakeup` | 5 | `divEu.wakeupNzvc` (`:384`) | CPLX_NZVC |
| `cplxFpWakeup` / `cplxFpccWakeup` | 4 / 4 | `divEu.fpWakeup/fpccWakeup` (`:393-396`) | FP_A/B, FPCC |
| `aluSlowWakeup[0..1]` | {6,5,5} each | `eu0/eu1.slowWakeup` (`:287-290`) | SLOW_A/B/C/NZVC/X |

A dynamic wakeup in cycle W clears the bit into `dynWaitNext`. Both `dynWait` and
`dynWaitAny` are registered, so the consumer is selectable at W+1. The ALU slow
broadcast fires at S1a (`execute/AluEuPlugin.scala:826-834`), which is earlier than the
slow result at S3. The broadcast timing relative to writeback is the EU's contract and
is outside this slice.

**Per-slot CAM cost** with physNzvc = physX = 32:

| Wakeup source | Compares | Width |
|---|---|---|
| LS (A/B/C) | 3 | 6-bit |
| CPLX (A/B/C) | 3 | 6-bit |
| SLOW int (A/B/C × 2 ports) | 6 | 6-bit |
| SLOW NZVC (× 2 ports) | 2 | 5-bit |
| SLOW X (× 2 ports) | 2 | 5-bit |
| LS NZVC | 1 | 5-bit |
| CPLX NZVC | 1 | 5-bit |
| FP A/B | 2 | 4-bit |
| FPCC | 1 | 4-bit |

That is 12 six-bit, 6 five-bit and 3 four-bit compares per slot, ×16 slots = 336 tag
comparators (`:1717-1773`). The static triggers need no comparator.

#### 3.6.4 Select

Five independent ports pick from one-hot masks every cycle. `OHMasking.first` means
lowest index, i.e. oldest first (`:658-948`).

| Port | EU | Candidate mask | Rule |
|---|---|---|---|
| 0 | ALU0 | `aluReady & (fastAcceptNext0 ? all : aluSlowSlots)` | oldest |
| 1 | ALU1 | same with `fastAcceptNext1`, excluding `oh0` | second oldest |
| 2 | Branch | `ready && isBranch` | oldest |
| 3 | LS | see below | relaxed in-order |
| 4 | CPLX | `ready && isCplxClass`, excluding every divide-family slot but the oldest (`:944-948`) | oldest |

- `aluReady` = ready and not branch, LS or CPLX (`:658`). Both ALUs accept the same class.
- A FAST ALU uop is a candidate on port k only when the EU signals `fastAcceptNext = !flush && !s1aValid` (`AluEuPlugin.scala:267`). Otherwise only SHIFT (`isAluSlow`) slots qualify.

**LS select in shipping** (`loadBypassUnreadyLoad = true`, `:730-882`).

`eligible = (lsPresent & ~isStore & ~olderStorePresent & intraMacroOk) | ohLoldest`. In words:

- A **load** may issue ahead of older LS uops when no older LS slot holds a *store*. "Holds" means occupied, whether or not the store is ready. In addition, either no older LS uop is unready, or the load is `firstOfInstr` (`intraMacroOk`).
- A **store** issues only as the oldest occupied LS uop.
- Both prefixes are computed by recursive doubling, 4 levels each (`:818-855`).

`lsReady = lsFullyReady | storeAddressReady`. With `earlyStoreAddress = true`, a store
with `canEarlyStoreData` set may be selected while only its *data* dynamic-wait bits
(LS_B, CPLX_B, SLOW_B) are pending (`:661-671`). It must still have all static triggers
clear and no pending address waits. `canEarlyStoreData` is precomputed at push
(`IqContext.scala:166-172`): a plain MOVE store with no auto-increment and no dst, or
(with `earlyAutoStoreAddress`) a POSTINC store.

The divide-family restriction applies to `isDivFam` = DIV or DIVREM: only the oldest
present divide may be selected. Everything else on CPLX (MUL, CHK, CMP2, FP) is
out-of-order oldest-ready.

**Issue width:** up to 5 uops per cycle, one per port. Dispatch supplies at most 2.

**Payload path.** `ohSelect` is an AND-OR reduce of the 16 `IqHot` records (`:1010-1016`).
The result goes to `selPorts(k)`, then:

- Ports 0, 1, 2 and 4 go through `m2sPipe(collapsBubble = false, flush)` (`:1276-1284`).
- Port 3 goes through a one-entry skid (`lsSkidValid`/`lsSkidHot`) followed by an issue register (`lsIssValid`/`lsIssHot`) (`:1130-1238`). `selPorts(3).ready = !lsSkidValid`, a flop, so the LS EU's ready never reaches the slot array.

`issuePorts(k).valid = piped.valid && !flushSignal`. The payload is the cold-Mem read
indexed by the registered robId and way (`:1279-1281`).

**Flush** (`:2122-2150`) clears every `sel`, every busy bitmap and the skid/issue
registers, and sets `readyReg := True`. Because every flush source fires at the ROB head,
no older producer can still be in flight when the busy bitmaps are wiped.

#### 3.6.5 Speculative wakeup

The design has an LS-only speculative path (`lsWakeupSpec` clear at `:1738-1753`, re-check
`lsSpecBlocked` at `:1182-1200`) and a 4-cycle-L1 preselect (`:888-899`). **Both are
compiled out in shipping.** `specLoadWakeup` and `fourCycleL1` are false, so
`lsSpecBlocked = False`, `lsSpecW = False` and `p3Preselect = 0`.

Every wakeup in the shipping build is therefore either static at select time (latency 1)
or non-speculative from completion. The EU's own `earlyIntWakeup`/`earlyNzvcWakeup` may
announce one cycle before writeback, but that is not hit speculation.

### 3.7 Physical register files (`execute/regfile/RegFilePlugin.scala`)

#### 3.7.1 Structure

- Each file is one logical `Mem(Bits(w), depth)` (`:224`). All ports are created at setup by `newRead`, `newWrite` and `newBypass`.
- Write requests with the same `sharingKey` merge into one physical port through a priority mux (`:172-222`). A request with no key gets a fresh `new Object` key (`:96`), so it **becomes its own physical port**.
- Physical write port 0 also performs a power-on zero sweep (`:244-257`).
- Each read is `ram.readAsync`, followed by a **balanced masked-OR bypass**: one compare per bypass source, then OR-reduce, then a 2:1 mux against the RAM data (`:323-345`). Ports created with `forceNoBypass` skip the bypass.
- `MultiPortWritesSymplifier` (installed at `M68kSpinalConfig.scala:13`) rewrites any multi-write async-read Mem:
  - width ≥ 10 → `RamAsyncMwMux` (LVT): one data bank per write port, plus an XOR-RAM "live value table" of `log2(W)` bits (`hw/MultiportRam.scala:216-287`).
  - width < 10 → `RamAsyncMwXor` (`:177-211`).
  - Banks with more than 12 reads are replicated or split (`maxReadPortsPerMem = 12`, `:55`, `ReplicatedBank` `:80-103`, core split `:183-195`).

#### 3.7.2 Shipping port census (from the `newRead`/`newWrite`/`newBypass` call sites)

**Int PRF (32 bits × 54):**

| Use | Read ports | Physical write port | Bypass |
|---|---|---|---|
| AluEu0 | rdA, rdB, rdC (`AluEuPlugin.scala:134,140`) | `wbKey` (fast pri 1 + slow pri 0) (`:141,152`) | intByp, intByps |
| AluEu1 | rdA, rdB, rdC | its own `wbKey` | intByp, intByps |
| BranchEu | tgtRd, anRd, idxRd (`BranchEuPlugin.scala:141,142,155`) | `IntWbKey` = BranchEu anW (pri 1) + ExceptionUnit a7Wr (pri 0, `FullCoreSynth.scala:73`) + DebugCtrl debugIntWrite (pri 2, `DebugCtrlPlugin.scala:98`) | anByp |
| LsEu | rdBase, rdData, rdIndex (`LsEuPlugin.scala:369-371`) | `"lsIntWb"` = completion (pri 1) + anEarlyW (pri 0, `:387,400`) | intByp |
| DivEu | rdA, rdB, rdH (`DivEuPlugin.scala:286-288`) | **own (no key)** (`:291`) | intByp |
| ExceptionUnit | a7Rd, no bypass (`FullCoreSynth.scala:75`) | — | — |
| DebugCtrl (stage 5 ≥ 3) | debugIntRead, no bypass (`DebugCtrlPlugin.scala:85`) | — | — |
| ROB (FMOVEM An) | — | **fmAnWrite, own (no key)** (`rob/RobPlugin.scala:187`) | — |
| **Total** | **17** (15 bypassed + 2 raw) | **6 physical** | **7 sources** |

Lowering (≥10 bits, so LVT) for the int PRF:

- 6 data banks, each a `ReplicatedBank` with 17 reads. That is ⌈17/12⌉ = 2 replicas per bank, so **12 LUTRAMs of 54×32**.
- The LVT is `RamAsyncMwXor` of 3 bits, 6 writes and 17 reads. `readsPerCore = 12 − 5 = 7`, so `coreCount = 3`. Each core has 6 banks of 54×3, each bank with 5 cross-reads plus up to 6 external reads.
- Each read is then a 6:1 bank mux of 32 bits, selected by the 3-bit LVT output.

**NZVC PRF (4 bits × 32, XOR lowering):**

- Writes, 5 physical: AluEu0 wbKey, AluEu1 wbKey, LsEu nzvcW (`LsEuPlugin.scala:406`), DivEu nzvcW (`DivEuPlugin.scala:293`), and `"rteNzvc"` (ExceptionUnit `FullCoreSynth.scala:76` + DebugCtrl pri 2).
- Reads, 4: AluEu ×2, Branch nzRd, DivEu nzvcRd.
- Bypasses, 6: AluEu 2 each, Ls 1, Div 1.

**X PRF (1 bit × 32, XOR):**

- Writes, 4 physical: AluEu ×2, LsEu xW (`LsEuPlugin.scala:411`), `"rteX"`.
- Reads, 2: the AluEus.
- Bypasses, 5: AluEu 2 each, Ls 1.

**FP PRF (80 bits × 16, LVT):**

- Writes, 2 physical: DivEu fpW (`DivEuPlugin.scala:318`), ROB fmFpWrite (`RobPlugin.scala:183`).
- Reads, 5, none bypassed: DivEu fpRdA and fpRdB, plus three ROB ports — frameFpSrcRead, frameFpDstRead and fmFpRead (`RobPlugin.scala:180-182`).

**FPCC PRF (4 bits × 16, XOR):**

- Writes, 2: DivEu fpccW, `"excFpcc"` (`FullCoreSynth.scala:79`).
- Reads, 3, none bypassed: BranchEu fpccRd, ExceptionUnit fpccRd and DivEu fpccRd (`DivEuPlugin.scala:327`).

#### 3.7.3 Read timing contract

- An EU reads the PRF in the cycle after select, from the **registered** issue-stage robId/payload. The cold-Mem read and the PRF read are both async LUTRAM reads in series in that cycle (`IssueQueuePlugin.scala:1276-1284`).
- The bypass network is a same-cycle forward of the write-port data (`intByp.* := intW.*`, `AluEuPlugin.scala:687,779`). It covers the cycle in which a write is presented but not yet visible in the RAM.
- There is no wider bypass network. All forwarding is "write in flight this cycle".

---

## 4. Configuration knobs

| Knob (where) | Default | Shipping | Notes |
|---|---|---|---|
| `RenameStage.retireWidth` | 2 | 2 | 2 commit/push lanes per RAT and freelist |
| `RenameStage.preparedRetirement` | false | false | The `PreparedCommit` and `RatTable.preparation` areas (`RatTable.scala:98-115`, `RenameStage.scala:527-551`) are not elaborated |
| `Freelist` banked path (`pushPorts>=16`) | — | not elaborated | `Freelist.scala:208-241` |
| `PHYS_INT_REGS_DEFAULT` | 54 | 54 | constant, `Global.scala:137` |
| `physNzvc` (`CPU_PHYS_NZVC`) | 16 | 32 | `ShippingCoreConfig.scala:508` |
| `physX` (`CPU_PHYS_X`) | 16 | 32 | `:515` |
| FP / FPCC pools | 16 / 16 | fixed literals | `RenameStage.scala:83-84` |
| `IssueQueuePlugin.earlyStoreAddress` | false | true | store address issues before its data |
| `earlyAutoStoreAddress` | false | true | extends it to POSTINC stores |
| `loadBypassUnreadyLoad` | false | true | relaxed LS select (section 3.6.4) |
| `specLoadWakeup` | false | false | speculative LS wakeup and re-check compiled out |
| `fourCycleL1` | `lsFourCycleL1` = false | false | `p3Preselect` compiled out |
| `DispatchPlugin.detailedPerf` | false | per `PERF_DETAIL_ENABLE` | perf event taps only |
| `MultiportRam.maxReadPortsPerMem` | 12 | 12 | replication threshold |
| `PRF_SLOT_PERM_*` / `-DprfSlotPerm.*` | identity | identity (assumed; env not in SHIPPING_CONFIG) | permutes the physical write-port order (`RegFilePlugin.scala:159-187`) |

**Dead or idle code in the shipping build:**

- Everything under `GenerationFlags.simulation`. This includes the `fsCtx` shadow (`IssueQueuePlugin.scala:2076-2120`), `legacyPhysToSlot`, the `dynView` taps, the PRF `shadow`, `bypLive`, and the Freelist `dbgPushAddr` (driven 0 outside sim).
- `rnDbg` in RenameStage (`:384-436`). It is SimPublic only, with no RTL reader.
- IQ `count` (`:636,2047,2133`). Nothing in RTL reads it; it is only assigned.
- `IqHot.op`, `cluster`, `leaAddr` and `useImm` have no synthesis reader, only sim asserts and taps. `memOp` **is** live, because `lsStorePresent` reads it (`:813`).
- The port-2 duplicate busy clear (`:1977-1982`). It is the same as the `k = 2` iteration of the loop at `:1959-1968`.
- `sbFp`/`sbFpcc` static FP scoreboards (`:443-444`) and their `trigInit` legs. They are reached only for an FP/FPCC writer that is not CPLX. The writers checked — `MicroOpAssembler.scala:494` (FMOVEM.X, CPLX) and `:2410` (FPU generic, CPLX) — are all CPLX. The Microcode sites `:3073,3626` were not verified. If they are CPLX too, these scoreboards are never set and could be removed.
- `lsWakeupSpecPort` and the `lateStorePorts`-adjacent spec logic, when `specLoadWakeup` is off.

---

## 5. Performance-relevant behaviour

### 5.1 Bandwidth

| Stage | Width | Throttled by |
|---|---|---|
| Rename | 2 | all-5-freelists `popReady`, `allocHalt`, downstream ready |
| Dispatch | 2 | ROB `allocReady`, IQ `readyReg` |
| Issue | 5 ports (ALU, ALU, BR, LS, CPLX) | class mix. **LS is 1/cycle and CPLX is 1/cycle**, and a FAST ALU uop is blocked for a cycle after a SHIFT enters the same EU's slow pipe |

### 5.2 Wakeup-to-issue latency

| Producer → consumer | Path | Latency |
|---|---|---|
| Static latency-1 (ALU fast, branch-A7, static NZVC/X writers) | producer selected at C → `events` clears the consumer's trigger into the register → consumer ready and selected at C+1 | 1 cycle select-to-select |
| Dynamic (LS, CPLX, ALU slow, LS-NZVC, CPLX-NZVC/FP/FPCC) | broadcast in cycle W → per-source bit cleared into register → selectable W+1 | the remaining latency is set by when the EU broadcasts relative to its writeback (LS completion; ALU slow at S1a, `AluEuPlugin.scala:826`) |
| Push → issue | slot written at the push edge, `sel` visible next cycle | a uop dispatched in cycle D is selected at D+1 at the earliest, reaches its EU at D+2 |
| Same-group dependent (slot 1 on slot 0) | trigger bit 14 (static) or a dynamic bit | same as above |

On a dependency chain that happens to be the oldest ready work, back-to-back dependent
issue is sustained **every cycle** for latency-1 ALU ops.

### 5.3 Structural stalls

**Freelist exhaustion.** Frees are deferred 1 cycle after commit. Rename needs ≥ 2 free
in every class.

The worst case for int: up to 32 ROB entries, plus 2 renamed uops held in the `PipeStage`,
plus up to 2 ids still in `reclaim`. That is 36 live speculative ids against **34** free
ids (54 − 20). So the int freelist can bind only when ROB occupancy is close to 32 *and*
nearly every uop writes an int register. In practice the ROB `allocReady` limit (≤ 30
before allocating) binds first or at the same time. **This is an inference from the
sizes, not a measurement.**

NZVC and X (31 free in shipping) bind on long chains of flag writers once the ROB plus
`PipeStage` exceed 31. With the default of 16 (15 free) they bind at half the ROB.

FP has 8 free (16 − 8). FPCC has 15 free. Because the gate ANDs all five classes,
`fpFree` and `fpccFree` are on every rename's critical stall path. With ≥ 2 free they
cannot block non-FP code, but an FP-heavy region with more than 6 in-flight FP writers
stalls *all* rename.

**IQ full, the line-0 rule** (`IssueQueuePlugin.scala:640-643,2052`). Push requires line 0
(slots 0 and 1) to be empty next cycle. Every push shifts *all* occupants down one line.
Two consequences:

- (a) Any uop that is still unissued 7 pushes after its own push sits in line 0 and **blocks all dispatch**, even with up to 14 free slots behind it. Typical culprits are a load consumer waiting on a miss, a divide, or a store behind the strict-store rule.
- (b) A single-uop push (`pushSlot1Port = 0`) still consumes a whole line, so the IQ holds between 8 and 16 uops depending on how many groups carry two uops. The front end's slot-1 deferral knobs (`deferSlot1Uncond`, `deferSlot1Dbcc`, `deferTakenSlot1Conditional`) produce single-uop groups and therefore shrink the IQ window. *Inferred from the structure; not measured here.*

In effect the IQ is an 8-group age window, not a 16-entry pool. Combined with the
32-entry ROB, this window — not the ROB — is the most likely limit on how far issue can
run ahead (MLP and load-to-use hiding). No counter in this slice separates "IQ blocked
by line 0" from "IQ full"; the `dispIqReadySim` tap (`DispatchPlugin.scala:303`) only
records `push.ready`.

**LS port.**

- Stores stay strictly in program order relative to older LS uops.
- A load cannot pass an older **unissued store** (occupancy, not readiness, `:880`).
- A load cannot pass an older unready LS uop unless the load is `firstOfInstr`.
- The skid absorbs a one-cycle EU stall. A longer stall holds the port.

**CPLX port.** One uop per cycle. Divides are in program order among themselves.

**ALU.** When `fastAcceptNext` is false (the cycle after a SHIFT entered S1a on that EU,
`AluEuPlugin.scala:267`), only SHIFT candidates are offered to that port. The other port
can take the fast uop.

---

## 6. Area and timing notes

(Structural counts are from the code. LUT figures are estimates and are labelled.)

**Int PRF.** 17 read ports × 32 bits, 6 physical write ports, 7 bypass sources.

- Storage: 12 LUTRAMs of 54×32 (6 banks × 2 replicas), plus an LVT made of 3 XOR cores × 6 banks of 54×3, each LVT bank with 5 cross-reads.
- Every read port fans its 6-bit address out to 6 data banks (one replica each) and to one LVT core.
- With per-bank keep-tagged address copies (`hw/MultiportRam.scala:273-282`), each read-address bit drives roughly 6 banks × ⌈32/3⌉ RAM64M sites, about 66 LUTRAM address pins, plus 6 LVT banks.
- Every write address and data bit fans out to 2 replicas × 11 RAM64M sites of its data bank, *and* to 3 LVT cores × 6 banks (5 cross-read banks plus its own).
- The brief's figure of **~270 LUT per read port** matches 6 banks × ~44 LUT (RAM64M, 3 bits/4 LUT, 32 bits) plus the 6:1 × 32 output mux. This is estimated, not measured here.
- On every bypassed read, the bypass adds 7 × 6-bit compares plus a 7-input 32-bit AND-OR and a 2:1 mux: 15 ports × that structure.

**Cost of one more int write port.** It adds a data bank (×2 replicas × 17 reads), one
more bank in each LVT core, and one more XOR cross-read in every LVT bank. It also
lowers `readsPerCore`; at 7 write ports it falls to 6, so the LVT grows to 3 cores
(⌈17/6⌉ = 3). The cost is roughly linear in reads per bank: about 17 × 44 ≈ 750 LUT of
data bank alone, plus the wider LVT and output mux. *Estimate.*

**IQ.**

- 16 slots × `IqHot`. About 90–110 surviving bits per slot after pruning: the tags and valids, the class bits, the 5 destination tags, robId and way.
- 16 × 16 `dynWait` flops and 136 trigger flops.
- 336 tag comparators (section 3.6.3).
- 5 × 16:1 AND-OR select trees over `IqHot`.
- Scoreboard `physToPosition`: (54 + 32 + 32 + 16 + 16) × 4 = 600 flops, read by 8 sources × 2 ways of 54:1, 32:1 or 16:1 4-bit muxes at push.
- Busy bitmaps: 3 × 54 + 4 × 32 + 2 × 16 dynamic, plus 54 + 32 + 32 + 16 + 16 static = 472 flops. Each takes 2 push-set decoders plus 1–2 clear decoders.
- Cold store: 2 banks × 32 rows × `RenamedUop` width (several hundred bits) × 5 async reads per bank. Vivado prunes bits a port's EU never reads, so the real width differs per port. Expect a few thousand LUTRAM-LUTs. *Estimate.*

**Hot select cone.** `sel`/`triggers`/`dynWaitAny` (flops) → `ready` → class mask →
`OHMasking.first` → `ohSelect` → `selPorts` → m2sPipe register. The relaxed LS select
adds two 4-level prefix ORs. One prefix's input is `!slots(i).ready`, a derived signal
(`:845`), so the LS select is the deepest of the five.

`slot.fire` (OR of 5 one-hot terms) is the clock enable for `sel`, and `events` feeds the
trigger next-state of all younger slots (`:1659-1664`). That is a ~16-way broadcast from
each select port.

**Fanout hot spots (structural):**

- **Wakeup payload buses.** Each of the 7 tag buses (+2 slow) feeds 16 slots × up to 3 sources of comparators. `lsWakeup.payload` additionally feeds the `lsBusy` clear decoder (54 outputs) and `lateStorePorts.queryReadyNext` (`:526`).
- **`flushSignal`.** It feeds the 16 `sel` flops, 472 busy flops, `readyReg`, the 5 select valids and the 5 issue valids.
- **`pushPort.fire`.** It is the clock enable / mux select for every slot's `hot` record (~16 × 100 flops), `triggers`, `dynWaitNext` and `positionEpoch`.
- **PRF read address** (the RAM32M/RAM64M `ADDR*` pins). One driver is per port, but each port reaches about 66+ LUTRAM sites, as above. Each address is a registered issue-stage field, which is good. However, the same robId first goes through the cold-Mem async read (`coldRead`) before it becomes `psrcA` and the PRF address. So the PRF read address is **LUTRAM-read → LUTRAM-address**: two async distributed-RAM reads in series inside the EU's operand-read cycle.

**RAT.**

- 8 int read ports × (20:1 × 6 bits × 2 copies + location mux), all combinational from `du.uops.payload` to the `PipeStage` register, followed by the slot-1 bypass muxes and the freelist async reads.
- The freelist RAM read address `head + lowerTakes` depends on slot 0's `take`, which in turn depends on `fire = du.uops.fire`. That makes `fire` → adder → XOR-RAM read → `pdst` → slot-1 bypass a serial path.

---

## 7. Observations (factual; for the reviewer)

### 7.1 Likely cheap area/congestion wins

1. **The ROB's `fmAnWrite` is a whole 6th physical int-PRF write port** (`rob/RobPlugin.scala:187`, `rf.newWrite()` with no `sharingKey`). It writes only under `exc.active` (asserted at `:2996`). That is the same serialised window in which `a7Wr` and `debugIntWrite` *share* `BranchEuPlugin.IntWbKey` (`top/FullCoreSynth.scala:73`, `debug/DebugCtrlPlugin.scala:98`). Putting it on `IntWbKey` at another priority would reduce the int PRF from 6 to 5 physical write ports:
   - one fewer 2 × 17-read data bank (≈ 750 LUT, estimated);
   - 6 → 5 LVT XOR banks, with cross-reads per bank going from 5 to 4;
   - `readsPerCore` rises from 7 to 8, so the LVT still needs 3 cores, but each is smaller;
   - one fewer 32-bit write-data broadcast.

   `RegFilePlugin` already asserts same-key exclusivity in simulation (`:215-220`), so the change is checkable.
2. **DivEu's int write port is unkeyed** (`DivEuPlugin.scala:291`). Whether it can share with another lane depends on DivEu/LS completion exclusivity, which is outside this slice. It is noted because each physical port is the most expensive item in the PRF.
3. **There are 17 int read ports, and 2 are not for execution.** `a7Rd` reads the committed A7 every cycle (`FullCoreSynth.scala:739`). `debugIntRead` is DebugCtrl's live read (`DebugCtrlPlugin.scala:85,1483`). Both are raw (no bypass) and both are addressed from committed RAT state. Muxing the debug read onto `a7Rd`'s address when the core is halted would remove one 17th-port's worth of banks. In this LVT that is the full ~270 LUT per read port.
   - Going from 17 to 16 reads does not change the replica count (⌈16/12⌉ = 2).
   - Going from 17 to 12 would drop the data banks to 1 replica each and halve the address fanout.
   - So the payoff is step-shaped: **every read port above 12 forces the second replica.**
4. **The three ROB FP-PRF read ports** (`frameFpSrcRead`, `frameFpDstRead`, `fmFpRead`, `RobPlugin.scala:180-182`) are used only by exception-frame and FMOVEM sequencing. They are 80 bits wide × 2 LVT banks. Folding them into one read port with a mux on the address is likely ~2 × 80-bit bank-read structures saved (estimate). Also unverified: whether the three can be live in the same cycle.
5. **The `fpFree` and `fpccFree` `popReady` terms gate every rename** (`RenameStage.scala:162-163`). They are correct, but they place the FP and FPCC freelists' `count >= 2` compares on the integer rename handshake. Gating by "this group pops from class K" would be more precise, at the cost of a slightly deeper ready cone. This is a correctness-neutral IPC item only for FP-heavy code.
6. **The freelist Mem has a 3rd write port used only at power-on** (`Freelist.scala:118-122`). Because it is a separate port, the XOR lowering builds 3 banks with 2 cross-reads each, instead of 2 banks with 1. The power-on fill could instead be muxed onto push lane 0, as `RegFilePlugin` does with its zero sweep (`RegFilePlugin.scala:248-253`). Small gain (×5 freelists).
7. **Dead structures.** `sbFp`, `sbFpcc` and their `trigInit` legs (section 4), the duplicate port-2 clear (`:1977-1982`), and IQ `count`.

### 7.2 IPC-relevant structure

8. **The IQ's line-0 push rule is the likely issue-window limiter** (section 5.3). One blocking uop that is 8 groups old stops dispatch while 14 slots may be free, and single-uop groups halve the capacity. A hole-collapsing compaction (move only occupied entries down) would remove the 7-push age limit. Failing that, a rule that allows a push when line 0 is occupied but some other line is empty would raise the effective window toward 16. *Not measured;* adding a sim counter "push blocked while `count < 14`" would quantify it.
9. **The LS port is one per cycle for both loads and stores, and the `intraMacroOk` prefix reads `!ready`** (`:845`), not a flop. Under the relaxed rule a load that is *not* `firstOfInstr` cannot pass any unready older LS uop. Every cracked memory-source ALU macro has its load as uop 0 (`firstOfInstr`), so this mostly hits the second and later loads of multi-load macros. This is a plausible but unquantified IPC limiter.
10. **`storeAddressReady` requires every static trigger to be clear** (`:666`). A store whose data comes from a latency-1 ALU op cannot go early on its address, and has to wait the one cycle for the trigger to clear. That is small. Only dynamic data producers (loads, CPLX, shifts) get the early-address benefit.
11. **There is no execute-time recovery.** RAT and freelist restoration happen only at the ROB head (section 3.2). The Tier-1 `earlyPend` freezes rename (`allocHalt`) from mispredict detection until the branch retires, so *no* correct-path uop can be renamed until then, even though the front end has refetched. The mispredict penalty therefore includes the whole drain to the head. That is a ROB/rename policy choice, noted here because the freeze is implemented in this slice.
12. **The FP freelist has 8 free ids.** More than 6 in-flight FP writers stall all rename (section 5.3).

### 7.3 Correctness notes (no defect found; invariants worth knowing)

- The IQ relies on **at most one in-flight producer per physreg**, which holds because a physreg is freed only after its overwriter commits. This is used by `physToPosition`, by the same-cycle "push wins over clear" ordering (`:1987-2045`) and by the PRF's OR-bypass. Simulation asserts it in `RegFilePlugin.scala:339-342`.
- The flush wipes every busy bitmap, including the dynamic ones (`:2134-2147`). This is safe *only* because every flush source fires at the ROB head (section 3.2). Any future execute-time partial flush would leave older in-flight producers' consumers marked ready: a silent stale read.
- `Freelist.popReady` is the **only** underflow protection (`Freelist.scala:155-161` has no guard). The rename gate must include every class that pops, and it does (`RenameStage.scala:162-163`).
- The intra-group int bypass compares tags without the source-valid bits (`RenameStage.scala:404-406`). This is harmless, as section 3.4 explains.
- Int tag width is a literal 6 in several places, with no `require` tying it to `PHYS_INT_REGS_DEFAULT` (section 2.2). This is safe at 54. It would silently alias above 64.

### 7.4 Comments and docs that disagree with the code (do not trust these)

- `RenamedUop.scala:10`: "log2Up(PHYS_INT_REGS=48)". The pool is 54.
- `RatTable.scala:26,77`: "int=18×6b". Int arch is 20 (`isa/Isa.scala:18`).
- `RegfileSpec.scala:9-16`: "physCount = 50". It is 54.
- `IssueQueuePlugin.scala:312`: "count <= depth-2 = 62". With ROB 32 the bound is 30.
- `IssueQueuePlugin.scala:1569-1572`: "`events` covers only the ALU ports". `events` covers all 5 ports (`:1332-1334`).
- `IssueQueuePlugin.scala:9-10` header: "Task 1: every pushed uop is immediately ready; triggers field is kept but always 0". False; triggers and dynWait are fully used.
- `IssueQueuePlugin.scala:14-15`: "push.ready gated on count <= slotCount-2". It is gated on line-0 emptiness (`:2052`).
- `RegFilePluginFp.scala` (FPCC doc): "Write-only until an FPCC READER lands… `readsFpcc` is driven False by every decode path today". Decode sets `readsFpcc := True` (`decode/MicroOpAssembler.scala:1575`, `decode/Microcode.scala:3090,3637`), and BranchEu, the ExceptionUnit and DivEu all have FPCC read ports.
- `LsEuPlugin.scala:390-391`: "the int PRF already has six [bypasses]". The code has 7, including BranchEu `anByp` (`BranchEuPlugin.scala:154,429`).
- `DebugCtrlPlugin.scala` and `FullCoreSynth.scala` describe the int PRF as having avoided a 6th write port. With the unkeyed ROB `fmAnWrite` it has 6 anyway (section 7.1 item 1).
- `top/SocketTop.scala:177-178`: "`LS_OOO_ISSUE` is unset, so this is false in every ordinary build". The shipping build log reports `lsOooIssue=true`.
- `RenameStage.scala:33` header "int RAT: … = 8 ports" is correct. `RenameStage.scala:10-21` says "Three RATs"; there are five.
