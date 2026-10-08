# 05 — Plugin framework, ROB / commit, exceptions and system state

Source tree: `/var/tmp/m68k-docs-r4`, commit `fee95f6f` (the bitstream on the board).
All paths are relative to `src/main/scala/m68k040/` unless they start with `src/` or `docs/`.

**Method.** Every statement below comes from the RTL. Source comments and `docs/*` were
not trusted; where a comment disagrees with the code, the code wins and the disagreement
is noted (§7.4). Cycle counts are derived from the FSM and register structure, not
measured. Where a number depends on something outside this slice (D-cache/AXI latency,
DTLB response latency) it is given as a formula. The "prior findings" quoted in the task
(14-stage restart chain, ~36-cycle sequencer, 52% retire stall, `doFlushReg` in the
timing tail) are context supplied by the coordinator; this document neither derived nor
re-measured them.

**Shipping configuration assumed** (from the build log quoted in the task, plus generator
defaults for flags it does not print): `lsOooIssue=true`, `ipcThroughput=true`,
`rasBranchRepair=true`, `physNzvc=32`, `physX=32`, `inhibitedFullBarrier=true`,
`dcacheNonBlocking=true`; `CPU_DEBUG_PROFILE` defaults to `full` so `pcRangeEnable=true`
(`top/SocketTop.scala:672-673`); `PERF_DETAIL_ENABLE` defaults to `0` so
`detailedPerf=false` (`top/SocketTop.scala:653-657`); `RobPlugin.pairCorrectBranch`
and `preparedRetireEntries` keep their defaults (`false`, `0`) because
`ShippingPlugins.rob` never passes them (`top/ShippingPlugins.scala:479-484`);
`RenameStage` is built with its default `retireWidth = 2` (`top/SocketTop.scala:155`,
`rename/RenameStage.scala:23`). The socket build does not enable
`includeSimulation`, so every `GenerationFlags.simulation { ... }` block (shadow
arrays, assertions, `commitObs`, `pcStore`) is absent from the netlist.

---

## 1. Purpose

### 1.1 The plugin framework

`core/M68kCore.scala:285-292` is the whole core shell: it creates a SpinalHDL
`Database`, a `PluginHost` bound to it, and hosts the `Seq[FiberPlugin]` it is given.
All behaviour lives in the plugins. Plugins elaborate in two Fiber phases:

* `during setup` — allocate wires, publish Database keys, resolve services needed
  early. `ParamPlugin` publishes every sizing key here (`core/ParamPlugin.scala:17-47`).
* `during build` — build hardware. A `Database.blocking` key read here blocks the
  reader's fiber until the producer has `set` it; `host[Service]` blocks until a
  provider exists.

The shipping plugin list is built inline in `top/SocketTop.scala:137-204`
(ParamPlugin, MmuControl, FpuControl, InterruptControl, ITLB, DTLB, I$, D$, BTB, FTB,
RAS, gshare, FetchAlign, Decode, Rename, Dispatch, **RobPlugin**, IssueQueue, 2×ALU,
Branch EU, LS EU, Div/CPLX EU, five PRF plugins, AxiDMerge, ResetVector, IplAck,
PeripheralReset, **BackendWiringPlugin**, DebugCtrl). Plugins whose constructors take
shipping knobs are produced by `top/ShippingPlugins.scala` so sim harnesses can call the
same constructors.

**Services.** A plugin `extends ... with SomeService`; consumers call `host[SomeService]`
or `host.get[SomeService]`. All service traits live in `services/Services.scala`,
`services/LateStoreDataService.scala`, `services/PerfDetailService.scala` (§2.2).

**The setup-wire pattern.** RobPlugin's `build` body starts with
`host[RenameCommitService]` (`rob/RobPlugin.scala:360`), so anything RobPlugin
publishes as a `build`-time value would create a Fiber cycle with its consumers. Every
ROB-provided signal that an upstream plugin reads is therefore allocated as a bare wire
in `during setup` and driven later in `build` (`rob/RobPlugin.scala:63-81, 170-213`):
privilege/FC, CACR enables, frontend quiesce, all `DebugCommitService` readbacks,
history-recovery bits, retirement notices, LS replay, perf wires.

**One-producer invariant.** `Global.scala:6-12` declares that every key has exactly one
producer. Nothing in `Global.scala` or `ParamPlugin` checks this at runtime; it is a
convention. In this tree it holds for the Database keys (only `ParamPlugin` calls
`.set`). It does **not** hold for services: `GshareUpdateService` is implemented by both
`RobPlugin` (`rob/RobPlugin.scala:55`) and `GsharePlugin` (`frontend/Gshare.scala:53`)
(§7.3).

**Rule violations in practice.** The stated rule "plugins communicate only via
`host[Service]` and Global keys" is not followed by the wiring layer:
`BackendWiringPlugin` (`top/FullCoreSynth.scala:28`, hosted in the shipping core) reads
and drives dozens of `rob.logic.*` internals (`top/FullCoreSynth.scala:84-123, 283-433,
537-632`), `M68kCore.dbg040` reads internals of nine plugins
(`core/M68kCore.scala:377-452`), and `DebugCtrlPlugin` reads
`rob.logic.branchRedirect/doFlushReg/count/retire0` (`debug/DebugCtrlPlugin.scala:205,
666-669`).

### 1.2 The ROB / commit / exception subsystem

`RobPlugin` (`rob/RobPlugin.scala`, 4203 lines) owns:

* the reorder buffer ring (µop-granular, 32 entries) and in-order 2-wide retirement;
* the only architectural-commit interface to rename (`RenameCommitService`);
* the global flush/redirect (`doFlushReg`/`flushPcReg`) and the Tier-1 early frontend
  redirect (`earlyFire`/`earlyPend`);
* precise fault capture and the decision to deliver faults, interrupts, trace, RTE and
  serializing system ops;
* an embedded `ExceptionUnit` (`exception/ExceptionUnit.scala`, 3691 lines), a
  serializing FSM that pushes/pops stack frames through the D-cache, applies system ops
  and runs FSAVE/FRESTORE/PTEST/cold-FMOVEM;
* `SystemState` (`exception/SystemState.scala`): SR system byte, VBR, USP/ISP/MSP,
  SFC/DFC, CACR;
* predictor training (BTB/FTB/gshare) at retire, and the whole debug halt/step
  machinery.

---

## 2. Interfaces

### 2.1 Global Database keys and constants

All blocking keys are `set` by `ParamPlugin` during setup (`core/ParamPlugin.scala:25-47`)
from `M68kParams` (`Config.scala:7-50`). Consumers were found by grepping
`Global.<KEY>` / `<KEY>.get` across `src/main`.

| Key | Kind | Producer | Value (shipping) | Consumers |
|---|---|---|---|---|
| `ROB_DEPTH` | blocking | ParamPlugin (`ParamPlugin.scala:25`) | 32 | `DivEuPlugin.scala:1036-1037` (`mulHiMem` depth), `Global.ROB_ID_W` → `DivEuPlugin.scala:63,78`; `HelloPlugin` (not in shipping list). **RobPlugin does not read it**: it uses `ROB_DEPTH_DEFAULT` directly (`RobPlugin.scala:382`). |
| `PHYS_INT_REGS` | blocking | ParamPlugin (`:26`) | 54 | `execute/iq/IssueQueuePlugin.scala:399-406` (require only); RenameStage/RegfileSpec use the constant `PHYS_INT_REGS_DEFAULT` |
| `PHYS_NZVC_REGS` | blocking | ParamPlugin (`:33`) | 32 | IssueQueuePlugin |
| `PHYS_X_REGS` | blocking | ParamPlugin (`:38`) | 32 | IssueQueuePlugin |
| `DECODE_WIDTH` | blocking | ParamPlugin (`:39`) | 2 | **none** |
| `RETIRE_WIDTH` | blocking | ParamPlugin (`:40`) | 2 | **none** (real width = `RenameStage.retireWidth`, §3.1) |
| `L1I_KB`, `L1I_WAYS`, `L1I_LINE_BYTES` | blocking | ParamPlugin (`:41-43`) | 16, 4, 64 | **none** |
| `BTB_ENTRIES` | blocking | ParamPlugin (`:44`) | 128 | `frontend/Btb.scala:49` |
| `RAS_ENTRIES` | blocking | ParamPlugin (`:45`) | 16 | `frontend/Ras.scala:191` |
| `GHR_BITS` | blocking | ParamPlugin (`:46`) | 16 | `frontend/Gshare.scala:67` |
| `PHT_ENTRIES` | blocking | ParamPlugin (`:47`) | 2048 | `frontend/Gshare.scala:68` |

Plain constants (no producer; used by bundles elaborated outside a host):

| Constant | Value | Defined |
|---|---|---|
| `ROB_DEPTH_DEFAULT` / `ROB_ID_W_DEFAULT` | 32 / 5 | `Global.scala:34,42` |
| `ROB_ID_W` (def, reads `ROB_DEPTH.get`) | 5 | `Global.scala:39` |
| `PHYS_INT_REGS_DEFAULT` | 54 | `Global.scala:137` |
| `PHYS_NZVC_REGS_DEFAULT` / `PHYS_NZVC_ID_W` | `ShippingCoreConfig.physNzvc` (32) / 5 | `Global.scala:145-146` |
| `PHYS_X_REGS_DEFAULT` / `PHYS_X_ID_W` | `ShippingCoreConfig.physX` (32) / 5 | `Global.scala:153-154` |
| `FP_IMM_TABLE_DEPTH` / tag width | 16 / 4 | `Global.scala:181-182` |
| `WIDE_IMM_TABLE_DEPTH` / tag width | 32 / 5 | `Global.scala:254-255` |
| `BR_PRED_TABLE_DEPTH` / tag width | 64 / 6 | `Global.scala:257-258` |
| `robTag(hi, id, w, robIdBits)` helper | — | `Global.scala:77-88` |

`ParamPlugin` `require`s `robDepth == ROB_DEPTH_DEFAULT`, `physNzvc ==
PHYS_NZVC_REGS_DEFAULT`, `physX == PHYS_X_REGS_DEFAULT` (`ParamPlugin.scala:20, 30, 35`).
There is no equivalent check that `RETIRE_WIDTH` matches the rename commit width.

### 2.2 Services

Providers = classes that `extend/with` the trait; consumers = `host[...]` /
`host.get[...]` call sites (grep over `src/main`). "Wiring" = `BackendWiringPlugin`
in `top/FullCoreSynth.scala`.

| Service | Provider | Consumers | Notes |
|---|---|---|---|
| `RenameCommitService` (commit ports, flushPort) | RenameStage | RobPlugin | ROB drives `commitPorts(k)` and `flushPort` |
| `PreparedCommitService` | RenameStage | RobPlugin | `Option`, `None` in shipping (`preparedRetirement=false`) |
| `RobAllocService` | RobPlugin | DispatchPlugin | passive alloc (§3.1.3) |
| `RobRetirementService` (4 `Flow[robId]`) | RobPlugin | DtlbPlugin, ItlbPlugin, LsEuPlugin | lanes 2-3 constant False at retire width 2 (`RobPlugin.scala:1951-1954`) |
| `RobLsReplayService` | RobPlugin | wiring | LS-OoO liveness replay input |
| `DebugLoadPreemptService` | RobPlugin | LsEuPlugin | `haltAfterLoadHold` (`RobPlugin.scala:1164`) |
| `SerializedMemoryContextService` | RobPlugin | LsEuPlugin | cold-FMOVEM context from `exc` |
| `RedirectService` (`doFlush`, `flushPc`) | RobPlugin | DecodeStage, FetchAlign, RenameStage, wiring | `doFlushReg`, `flushPcReg` |
| `PredictorHistoryRecoveryService` | RobPlugin | GsharePlugin | `historyEpochStart = earlyFire`, `historyKeepOnFlush = earlySuppressFe && !excActive` (`RobPlugin.scala:3794-3795`) |
| `FrontendQuiesceService` | RobPlugin | FetchAlign, VioProbe | STOP / fatal halt / debug quiesce (`RobPlugin.scala:3257-3260`) |
| `BtbUpdateService` | RobPlugin | BtbPlugin, FtbPlugin | registered valid, `readSync` payload |
| `GshareUpdateService` | **RobPlugin and GsharePlugin** | none via `host[]` | wiring connects `gsh.gshareUpdate := rob.logic.gshareUpdateFlow` directly (`FullCoreSynth.scala:268-269`) |
| `PrivilegeService` (S, SFC, DFC) | RobPlugin | IcachePlugin, LsEuPlugin | from `exc.ss` (`RobPlugin.scala:3070-3074`) |
| `CacheControlService` (DE, IE) | RobPlugin | IcachePlugin, LsEuPlugin | `cacr(31)`, `cacr(15)` |
| `CommitTraceService` | RobPlugin | wiring (`FullCoreSynth.scala:781`), SynthProbe, VioProbe | in the socket build only feeds unconnected `M68kCore` outputs (§7.2) |
| `DebugCommitService` | RobPlugin | DebugCtrlPlugin | halt/step/resume, halt-after, A7-odd, PC-range |
| `DebugSystemStateService` | RobPlugin | DebugCtrlPlugin | SR/VBR/SPs/CACR/MMU regs readback + apply |
| `DebugHistoryService` | RobPlugin | DebugCtrlPlugin | retire PCs (one cycle late), branch, exception events |
| `RobPerfDetailService` | RobPlugin | DebugCtrl, M68kCore | `None` unless `detailedPerf` |
| `CommittedMapService` | RenameStage | DebugCtrl, RobPlugin | RobPlugin uses it for cold-FMOVEM An write |
| `CommittedFpMapService` | RenameStage | RobPlugin | FP frame operand reads, cold FMOVEM |
| `FpTrapImmediateService` | DecodeStage | RobPlugin | FP immediate for vector-11 frame |
| `InterruptControlService` | InterruptControlPlugin | RobPlugin | IPL / autovector / vector |
| `MmuControlService` | MmuControlPlugin | RobPlugin(+ExceptionUnit), TLBs, caches, LsEu, wiring | MOVEC/PTEST/PFLUSHA write via `set*` Flows |
| `FpuControlService` | FpuControlPlugin | RobPlugin/ExceptionUnit, DivEu, wiring | FPCR/FPSR/FPIAR, FSAVE state |
| `DTranslationService` | DtlbPlugin | LsEuPlugin, RobPlugin | ROB reads `rsp.payload.cacheMode` only (`RobPlugin.scala:2998-3000`) |
| `FlushService` | **none** | **none** | dead trait (`services/Services.scala:95-98`) |
| `LateStoreDataService` | IssueQueuePlugin | LsEuPlugin | out of slice |
| `DispatchPerfDetailService` | DispatchPlugin | DebugCtrl, M68kCore | `None` unless `detailedPerf` |

Other traits in `Services.scala` belong to other slices: Fetch, Translation, walker
clients, FTB/gshare lookup, DecodeFeed/DecodeUop/RenameUop, FpImmTable,
DebugMemory/DebugIrqInject, FrontendDebugMatch.

### 2.3 Non-service ports of RobPlugin (driven by the wiring)

The ROB's execution-side inputs are plain `allowOverride` wires that the wiring drives
from EU outputs (`top/FullCoreSynth.scala:295-406`):

| ROB input | Width/count | Driver |
|---|---|---|
| `completion(0..5)` `Flow[robId]` | 6 | ALU0, ALU1, LS EU, CPLX int lane, SQ precise drain, CPLX FP lane (`RobPlugin.scala:406`, `FullCoreSynth.scala:300-392`) |
| `branchCompletion` | 1 | Branch EU (`FullCoreSynth.scala:295-296`) |
| `ccrCompletion(0..3)` | 4 | ALU0/1, LS, CPLX `ccrObs` (`wireCcr`) |
| `lsFaultCompletion`, `sqFaultCompletion` | 2 | LS EU |
| `euFaultCompletion` | 1 (**shared**) | Branch EU `trapvFault`, overridden by DivEu `euFault` (`FullCoreSynth.scala:298-299, 404-407`) |
| `fpFaultCompletion` | 1 | CPLX FP lane |
| `lsOrderViolation`, `lsReplay` | 2 | LS EU (LS-OoO) |
| `preciseDrainBusyIn`, `inhibitedLoadBusyIn` | 2 | LS EU |
| `coreHaltedIn`, `haltReasonIn` | — | D$ diag, FSAVE xlate fault, arbiter/walker wedge, reset-vector halt, double fault (`FullCoreSynth.scala:610-620`) |
| `flush` (test port) | — | tied False in the socket (`top/SocketTop.scala:498`) |

ROB outputs consumed by the wiring: `retire0/retire1/h0/h1` → SQ commit and TLB U/M
commit (`FullCoreSynth.scala:431-437, 538-553`); `h0`, `!countIsZero`,
`irqPreemptArmed` → LS EU (`:347-363`); `backendSquash` → IQ, rename skid, decode
side table, ALU EUs, CPLX EU (`:95-99, 377`); `excActiveLs` → LS EU (`:632`);
`exc.*` D-cache/DTLB request ports → LS EU arbitration mux (`:566-631`); `exc.a7Write`,
`exc.sysRegWrite*`, `exc.rteNzvc/X*`, `exc.fpccWrite*` → PRF write ports
(`:726-776`); `countIsZero` → RAS checkpoint save (`:219`).

---

## 3. Microarchitecture

### 3.1 ROB

#### 3.1.1 Organisation

* **Depth 32 µop entries** (`RobPlugin.scala:382`, `Global.ROB_DEPTH_DEFAULT`), robId
  5 bits. Entries are per µop, not per instruction: a cracked macro occupies one entry
  per µop and is delimited by `payload.first`/`payload.last` (`RobPlugin.scala:261,
  270`, written from `RenamedUop.firstOfInstr/lastOfInstr` at `:1054-1055`).
* Pointers `head`, `tail` (5 b) and `count` (6 b) (`:389-391`). Valid window is
  `[head, head+count)`. There is no per-entry valid bit.
* **`payload`**: `Mem(RobPayload, 32)` with **2 write ports** (alloc0 at `tail`,
  alloc1 at `tail+1`, `:2382, 2410`) and **2 async read ports** at `h0`/`h1`
  (`:1094-1095`). Because it has two write ports it is lowered by
  `MultiPortWritesSymplifier` (`M68kSpinalConfig.scala:13`) into
  `RamAsyncMwXor` (`hw/MultiportRam.scala`): one LUTRAM bank per write port, each write
  XORs in a cross-read of the other bank, each read XORs both banks.
  Fields (`:216-321`), by count ≈ 184 bits/entry at physNzvc=physX=32: predNextPc 32,
  pc 32, int {arch 5, new 6, old 6, wr}, nzvc {5,5,wr}, x {5,5,wr}, fp {arch 3, 4, 4,
  wr}, fpcc {4,4,wr}, retireAlone, isRte, first, last, debugBreak{valid, slot 2},
  needsSup, sysOp, sysKind (4), sysReadDir, sysRc 12, faultUsesNextPc, faultVector 8,
  sswInstr, faultAtc, fpuUnimp, fpuCmd 16, fpuImm, fpuImmTag 4.
* **`nextPcMem`** 32×32, 1W (branchCompletion) / 2 async reads (`:506-507,
  1909-1910`) — the resolved branch target/fall-through.
* **`branchTrainMem`** 32×82 bits, 1W (branchCompletion) / 1 `readSync(h0, retire0)`
  (`:539, 2005, 2600`) — BTB/gshare training payload.
* **`faultDynMem`** 32×46 bits, 1W (arbitrated, one cycle late) / 1 async read at h0
  (`:824-825, 2277, 2700`) — completion-time fault record.
* **Per-entry flop arrays (32 each)**: `completes`, `mispredictStore`,
  `orderViolated` (LS-OoO only), `branchTakenStore`, `btbIsBranchStore`,
  `phtValidStore`, `faultedStore`, `faultDynStore`, `sysValRdyStore`, `nzvcWrStore`,
  `xValStore`, `xWrStore` (1 bit), `nzvcValStore` (4 bits) (`:387, 477, 487-489, 515,
  521, 528, 543, 660, 826, 1004-1008`). ≈ 16 bits × 32 = 512 flops, each with an
  alloc-reset write plus one or more completion writes and a 32:1 read mux at h0
  (some also at h1).
* **System-value capture buffer**: 8 slots `{valid, tag 5, data 32}` (`:640-653`),
  reserved at alloc for any `sysOp` or `FPCTRL_CAP` µop, filled by CAM-matching the
  4 `ccrCompletion` ports (`:2349-2353`), freed on retire / flush / re-alloc
  (`:2341`). Read as a masked OR (`sysValStore(idx)`, `:650-653`).
* **`sysAux`** 3×32 (`:689`): FMOVEM control-register-list values copied at the
  retirement of `FPCTRL_CAP` loads (`:1849-1856`).

`types/RobEntry.scala` describes a per-instruction entry with an `outstandingUops`
counter and `sqPtrBase`; **no RTL instantiates it** (grep finds only a comment
reference in `types/MicroOp.scala:8`). It does not describe the real ROB.

#### 3.1.2 Completion tracking

`completes(robId) := True` for each valid completion port and `branchCompletion`
(`:1979-1985`); alloc of the same index in the same cycle wins because the alloc
`when` is textually later (`:2381-2424`). There is no per-macro completion counter;
completion is per µop. Branch completion also writes `mispredictStore`,
`nextPcMem`, `branchTakenStore`, `btbIsBranchStore`, `phtValidStore`,
`branchTrainMem` (`:1983-2006`). `ccrCompletion` writes the NZVC/X value arrays and
sets `sysValRdyStore` (`:2009-2021`).

`completes` therefore has 9 writers per bit (6 completion ports, branch completion,
two alloc ports), each a 5-bit robId compare.

#### 3.1.3 Allocation (`RobAllocService`)

Passive: DispatchPlugin drives `allocFireSig`, `allocUopVec(2)`, `allocSlot1Sig`;
the ROB exposes `robId0 = tail`, `robId1 = tail+1` and `allocReadySig`
(`:1965-1973, 4127-4132`). Width 2 µops/cycle.

`allocReadySig = RegNext(countNext <= 30 && sysCapFreeNext >= 2) init True`, forced
True on `flushing` (`:2510-2511, 3805`). It is exact (registered from the next-state
expression), so the full 32 entries are usable.

Allocation is **not** gated by `flushing`: on a flushing cycle the alloc writes still
happen but `tail := head; count := 0` is textually last and wins (`:2486-2488,
3799-3801`). The sysCap reservation explicitly masks `!flushing` (`:2314-2315`).

#### 3.1.4 Retirement / commit width

Commit width = `rc.commitPorts.length` = 2 (`:361`; `RenameStage(retireWidth=2)`).
Lanes 2..3 of every per-lane loop elaborate to nothing.

`headReady = count>0 && completes(h0) && !flushing && !coreHalted && !debugHalted &&
!faultCapPending` (`:1221-1222`).

`retire0` (`:1368-1372`) = `headReady` and none of: replay-marked head (LS-OoO),
`faultedStore(h0)`, `p0.isRte`, `p0.sysOp`, `interruptPending`, `privViolation`,
`stopped`, `tracePendingFire`, an unready `FPCTRL_CAP` value, `haltAfterDue`,
`haltAfterRetireBlock`, `debugBreakpointBoundaryHit`.

`retire1` (`:1481-1490`) = `retire0 && count>1 && completes(h1)` and:

* `headAllowsPair` = `!p0.retireAlone` (shipping; `pairCorrectBranch=false`, `:1444-1446`);
* `!p1.retireAlone`, `!faultedStore(h1)`, `!p1.isRte`, `!p1.needsSup`, `!p1.sysOp`,
  `!p1.debugBreakValid`;
* `orderPairOk` (LS-OoO: no order-violation pending in the retiring macro, h1 not the
  replay op, `:1473-1474`);
* `!(irqBoundaryHold && p0.last)` — while any unmasked IPL is active, never retire
  across a macro boundary in one cycle;
* `!h0TraceArmed`, `!h0PreciseCompletedSticky` (h0 was completed by the SQ precise
  drain, `:1397-1420`), `sysAuxRdy1`;
* debug: not across a macro boundary while halt-after is armed, a stop is requested
  or pending, or single-stepping.

`retireAlone := u.isBranch` (`:1050`). With `pairCorrectBranch=false` **every branch
µop retires alone in slot 0 and its neighbour in either slot cannot pair with it**.

On retire, `driveCommit` presents a `CommitSlot` (int/NZVC/X/FP/FPCC new/old/write,
`rob/CommitSlot.scala:7-21`) to rename (`:1883-1901, 1931-1932`); rename updates the
committed RATs and pushes the old physical register to each freelist
(`rename/RenameStage.scala:125-135`). `head += retiredThisCycle`;
`count += alloc - retired` (`:2491-2495`).

`commitPc` for a branch is `nextPcMem(h)` (resolved), otherwise `predNextPc`
(`:1911-1913`).

#### 3.1.5 Macro-boundary handling

* Interrupts and trace are only taken at a head with `p0.first`
  (`normalIrqGate`/`traceNormalGate`, `:3174-3176, 3414-3416`).
* Debug stop/step boundaries use `p.last` of a retiring lane (`:1632, 1645-1648`).
* LS order-violation redirect needs `p0.last`; a violation marked on an earlier µop is
  carried in `macroViolatedSticky` (`:1440-1442, 1493-1499`).
* The macro counter counts retiring µops with `last` (`:1596-1597`).

#### 3.1.6 Stores and late store data

The ROB does not track store-queue slots. Store commit is signalled to the LS EU by
`sqCommit := {retire0, h0}` and `sqCommitB := {retire1, h1}` (`FullCoreSynth.scala:
431-437`); the SQ matches robIds. A precise store completes through `completion(4)`
when its at-head drain resolves; `h0PreciseCompletedSticky` then forbids pairing h1
with it (`RobPlugin.scala:1397-1420, 1485`). `preciseDrainBusyIn` and
`inhibitedLoadBusyIn` block interrupt/trace recognition (`:3174-3176`) and debug
recovery (`:1687`). `LateStoreDataService` (store data arriving after the address) is
an IQ↔LSU contract with no ROB involvement (`services/LateStoreDataService.scala:10-20`).

#### 3.1.7 Precise state

* The faulting/serializing µop never commits: `retire0` excludes faulted, RTE and
  sysOp heads; they leave via `faultRetire`/`rteRetire`/`sysRetire` into the
  `ExceptionUnit` (`:1230, 1237, 1246-1248`).
* **Fault marking is split** (`:2259-2282`): `faultedStore(robId)` is set
  combinationally by each of the four fault ports, unarbitrated; the record
  (`faultDynMem` row + `faultDynStore` gate) goes through a 4-entry capture register
  and an oldest-age fold (`age = robId - head`) one cycle later. `faultCapPending`
  (any capture valid) drops `headReady` for that cycle (`:2282, 1222`), so **any
  fault-completion event — including one on a younger or wrong-path entry — costs one
  commit cycle**.
* Head fault record = `faultDynStore(h0) ? faultDynMem(h0) : alloc-time fields of
  payload` (`:2699-2710`). Alloc-time size/wr/sup are constants (`:838-840`).
* Committed CCR value: the ROB keeps `committedCcr` (5 b) folded from
  `nzvcValStore/xValStore` and a same-cycle bypass from the 4 `ccrCompletion` ports
  (`:2784-2810`). It is used only for stacked SR and debug readback; the real flags
  live in the renamed NZVC/X PRFs. `ccrForException` adds the faulting head's own
  flag write (CHK) (`:2820-2822`).

### 3.2 Flush / redirect generation

#### 3.2.1 Sources

`doFlushNext` (`:3594-3596`) =
`branchRedirect || orderRedirect || lsReplayRedirect || exc.redirectValid ||
debugRecoverEnter || debugPcApply`; `doFlushReg := doFlushNext` (`:3597`).

| Source | Condition | Restart PC (`flushPcReg`, `:3624-3656`) |
|---|---|---|
| Branch mispredict | `retire0 && p0.retireAlone && mispredictStore(h0)` (`:2591`) | `nextPcMem(h0)` |
| LS order violation | `retire0 && macroViolationPending && p0.last && count>1 && !branchRedirect` (`:3525`) | `p1.pc` |
| LS liveness replay | `headReady && replayAtHead && p0.first && ...` (`:3542-3545`) | `p0.pc` |
| Exception / RTE / sysOp end | `exc.redirectValid` (E_REDIR, R_REDIR, S_REDIR) | `exc.redirectPc` (highest priority) |
| Debug recover / PC apply | `:1687`, `:3574-3575` | `debugRestartPc` / applied PC |

Note that the mispredict flush is taken only when the branch **retires**. There is no
partial (younger-than-branch) backend squash: the RATs only support "restore to
committed" (`RatTable.io.rollback`, `rename/RenameStage.scala:139-143`).

#### 3.2.2 What a flush resets

`flushing = flush.valid || doFlushReg || excSquash`, `excSquash = exc.active`
(`:1187, 3488`). Since `flush.valid` is tied False in the socket, **`flushing`
equals `backendSquash`** (`:3612`, `doFlushNext || exc.activeNext` registered), but the
ROB uses the combinational OR form internally and for `rc.flushPort`.

Inside the ROB, `flushing` drives: `tail`, `count`, `allocReadySig`, `countIsZero`
(`:3799-3811`); all 8 `sysCapValid` (`:2341`); the 4 `faultCap.valid` (`:2264`);
`replayPendValid`, `macroViolatedSticky`, `earlyPend`, `h0PreciseCompletedSticky`
(`:1363, 1498, 3782, 1398`); `headReady` (and through it every retire/commit/trigger
term); `rc.flushPort` → rollback of 5 RATs and flush of 5 freelists
(`rename/RenameStage.scala:139-143`).

Outside the ROB (wiring, `FullCoreSynth.scala:84-123, 211, 267, 439, 542, 554`):

* `backendSquash` (one flop) → `iq.flushPort`, `eu0/eu1.flush`, rename→dispatch skid,
  decode backend side table, `divEu.cplxFlush`;
* `doFlush` (`doFlushReg`) → frontend flush/redirect unless suppressed, RAS checkpoint
  restore, `gsh.flushRepair`, `lsEu.sqFlush`, `dtlb/itlb.umFlush`, RenameStage,
  DecodeStage, FetchAlign (via `RedirectService`);
* `excActive` (on every cycle of an exception episode) → frontend flush;
  `excActiveLs` (separate flop, `dont_touch`) → LS EU.

#### 3.2.3 Two-tier mispredict recovery

Tier 1 (`:3729-3795`): when `branchCompletion` reports a mispredict for an in-flight
robId (`bcAge < count`), and nothing is flushing/halted, and it is older than any
already-armed one, `earlyArm` fires. Registered: `earlyFire` (one-cycle pulse),
`earlyPend` (sticky), `earlyRobId`, `earlyPcReg`, and the RAS repair kind/data.

* `earlyFire` → FetchAlign redirect to `earlyPcReg`, decode `pipeFlush`, RAS
  checkpoint restore + branch repair, gshare history epoch (`FullCoreSynth.scala:
  116-143, 211, 232-234`).
* `earlyPend` → `RenameStage.allocHalt` (`FullCoreSynth.scala:123`;
  `rename/RenameStage.scala:189-190`): rename stops accepting.

Tier 2: when the branch retires, `branchRedirect` → `doFlushReg`. If it is the same
robId and PC that Tier 1 armed for (`earlyHit`, `:3789-3791`), `earlySuppressFe`
(registered, aligned with `doFlushReg`) suppresses the frontend flush/redirect and the
RAS restore; the backend still takes the full rollback. `earlyPend` clears on the
cycle after `flushing` (`:3782`).

Consequence, from the structure: after a resolved mispredict the correct path is
fetched and decoded early but **cannot be renamed until the branch reaches the head**;
wrong-path µops younger than the branch stay in the ROB/IQ and keep executing until
then. The only overlap gained is frontend refetch.

#### 3.2.4 Flush timing (derived)

Cycle T: branch retires at h0 (`branchRedirect` combinational).
T+1: `doFlushReg=1`, `backendSquash=1`: ROB pointers reset, IQ cleared, RATs rolled
back, freelists restored; with Tier-1 suppression the frontend keeps its contents.
T+2: `earlyPend=0` → rename may accept again; allocation into an empty ROB.
So with a successful Tier-1 hit the backend restart bubble after the branch retires
is 2 cycles plus whatever rename→dispatch→IQ→EU latency the next µop has. Without a
Tier-1 hit (exceptions, order/replay flushes, debug, or a Tier-1 robId/PC mismatch),
`feFlush` flushes the frontend at T+1 and fetch restarts at `flushPcReg`; the cost is
the full fetch-to-dispatch chain (the "~14 stage" prior finding; not re-derived here).

### 3.3 Exception unit

`exception/ExceptionUnit.scala` is an `Area` instantiated inside RobPlugin
(`RobPlugin.scala:2915-2978`). One FSM (`ExceptionUnit.scala:1560-1602` state list,
bodies to `:3546`), `active = RegNext(stateNext =/= IDLE)` (`:3552-3561`).

#### 3.3.1 Triggers and priorities (ROB side)

`excEntryTrigger = exceptionPending || tracePendingFire || interruptPending`
(`RobPlugin.scala:2851`), vector/PC selected in that priority (`:2855-2858`).
`exceptionPending = faultRetire || sysPrivFault || privOnly` (`:2684`). RTE uses
`rteTrigger = rteRetire`; system ops use `sysTrigger = sysRetire && (S || FMOVE_FPCTRL
|| FMOVEM_DATA)`; user-mode sysOps become vector 8 (`:3088-3091`). In the FSM's IDLE
state the priority is entry > RTE > sysOp (`ExceptionUnit.scala:1604-1712`).

#### 3.3.2 Vectors produced

| Vector | Source | Frame |
|---|---|---|
| 2 access fault | LS / SQ fault ports; alloc-time I-fetch fault (`MicroOpAssembler.scala:3076`); RTE/FSAVE/FRESTORE/FMOVEM frame-access faults (`busErrorEntry`, `ExceptionUnit.scala:2177-2205`) | $7 (60 bytes) |
| 3 address error | branch EU odd target (`execute/BranchEuPlugin.scala:441-444`); RTE odd PC (`ExceptionUnit.scala:2378-2403`) | $2 (12 bytes, "PPC" slot carries the address) |
| 4 illegal | decode `inertTrap(4)` (`MicroOpAssembler.scala:2377-2380` and others) | $0 |
| 5 DIV0, 6 CHK | DivEu `euFault` | $2 |
| 7 TRAPV / TRAPcc / FTRAPcc | branch EU `trapvFault` (`BranchEuPlugin.scala:441-443`) | $2 |
| 8 privilege | `privViolation` / `sysPrivFault` (`RobPlugin.scala:1226, 3091`) | $0 |
| 9 trace | `tracePendingFire` (`:3417`) | $2 |
| 10 A-line, 11 F-line | decode (`MicroOpAssembler.scala:2377-2380`); vector 11 also FP "software complete" ops | $0, or $2 when `entryFpuUnimp` |
| 14 format error | RTE with format ∉ {0,1,2,7} (`ExceptionUnit.scala:2404-2420`) | $0 |
| 24+L / vectored | interrupts (`RobPlugin.scala:3164-3212`) | $0, plus $1 throwaway when M=1 |
| 32-47 TRAP #n | decode (`MicroOpAssembler.scala:2955-2957`, PC = next) | $0 |
| 49-54 FP enabled exceptions | `fpFaultCompletion` | $0 (format selection has no FP arm, `ExceptionUnit.scala:1666-1669`) |

Format selection (`ExceptionUnit.scala:1665-1669`): `is7 = !int && vec==2`;
`is2 = !int && vec ∈ {3,5,6,7,9} or (vec==11 && fpuUnimp)`; everything else $0. The
real 68040 uses format $3 for FP post-instruction exceptions (vectors 48-54) and
format $4 for unimplemented FP effective address; neither is generated, and RTE
treats formats 3/4 as a format error (vector 14). Whether any software path depends on
this was not checked.

Frame base = supervisor stack (`M ? MSP : ISP`) − {60, 12, 8} (`:1760`, recomputed in
`E_DRAIN` `:1861-1867`). Frame words are built by `frameWordData`
(`:1084-1103`): SR, PC, format/vector word, then PPC/EA (steps 4-5), SSW (step 6),
fault address (steps 10-11), zeros elsewhere. The $7 SSW is assembled at trigger from
the fault record (`:1735-1752`).

On redirect (`E_REDIR`, `:2054-2097`): SSP bank updated, SR := (SR | S) with T1/T0
cleared, M cleared for a throwaway entry, I-mask := level for interrupts; `redirectPc =
vecTarget`; `obsA7` drives the A7 PRF write (`a7WriteValid := obsFire`, `:710-711`).

#### 3.3.3 RTE

`R_DRAIN` → four sequential WORD reads (SR, PC hi, PC lo, format) each through
`R_*REQ`/`R_*WAIT` (`:2138-2430`), any of which may split into two byte reads at a
line-offset-15 address. Format $1 loops back to pop the real frame from MSP
(`:2431-2437`). Formats 0/2/7 adjust SSP by 8/12/60, restore SR, and write the popped
CCR directly into the committed NZVC/X physical registers (`rteNzvcWrite*`,
`rteXWrite*`, `:2438-2450`; wired at `FullCoreSynth.scala:748-753`). Format $7 RTE
does not replay or complete any faulted bus cycle; it restarts at the stacked PC.

#### 3.3.4 System ops (`S_APPLY`, `:2546-2870`)

| SysKind | Effect |
|---|---|
| MOVE_TO_SR (also ANDI/ORI/EORI to SR, `MicroOpAssembler.scala:2694-2704`) | SR system byte; CCR written to NZVC/X PRFs in `S_REDIR` (`:3147-3161`) |
| MOVE_USP | USP ↔ An |
| MOVEC | read/write VBR, USP, MSP, ISP, SFC, DFC, CACR, TC (E, P only), ITT0/1, DTT0/1, URP, SRP; read MMUSR; other Rc read 0 / ignore writes |
| RESET | pulse `resetInstrRetire` |
| STOP | SR := imm; ROB sets `stopped`, `stoppedPc = predNextPc` (`RobPlugin.scala:3244-3250`) |
| CPUSH / CINV | 1-cycle `maintCmdOut` to D$, wait `maintDoneIn`, then I$ invalidate pulse for IC/BC (`:2617-2636, 2989-2995`) |
| PFLUSHA | `sysFlushAllValid` → both TLBs `flushAll` |
| PTEST | TT-register match, MMU-off identity, or a 3-level descriptor walk via the FSM's own load port (`S_PTREQ/S_PTWAIT`, `:2981-3047`); writes MMUSR |
| FMOVE_FPCTRL | FPCR/FPSR/FPIAR read/write, FPSR.FPCC to the FPCC PRF |
| FSAVE | always a 52-byte frame (`size = 52`, `fsLastStep = 25`, `:2865, 1420`), 26 serial word stores with per-page translation |
| FRESTORE | header read only; version 0 resets FP control state; An += 4 + length |
| FMOVEM_DATA | `FmovemColdBackend` byte-serial load/store, `fmLoaded` 8×80-bit buffer, then serial FP PRF writes (`:3068-3146`) |

Every sysOp ends in `S_REDIR`, which redirects to `sysCapNextPc` — i.e. **every
serializing op squashes all younger work and refetches**.

#### 3.3.5 Memory access path and translation

All FSM accesses go through registered single-entry command buffers
(`stoValidReg`, `ldoValidReg`, `:870-1025`) to the LS EU's arbitration mux, and
through the DTLB via the shared `X_REQ/X_WAIT` stage that re-translates only when the
VPN changes (`:1327-1352, 2249-2275`). A translation fault on an entry frame store or
vector fetch is a double fault (`E_DBLFAULT`, terminal; `dblFault` → `coreHaltedIn`).

Frame stores use `excCacheMode = CACR.DE ? WRITETHROUGH : INHIBITED` with
`precise := True` (`:914-928`). In `DcachePlugin` the store ack for a write-through
store is generated from the AXI B response (`cache/DcachePlugin.scala:5575`), and
`E_STWAIT` waits for that ack before building the next word (`:1945-1993`). **Frame
pushes are therefore fully serialized, one AXI write round trip per 16-bit word.**

#### 3.3.6 Interrupts

`iplActive = (ipl > I-mask && (ipl != 7 || nmiEdge)) || nmiPending`
(`RobPlugin.scala:3168`); NMI is edge-latched (`:1262-1265, 3240`).
`interruptPending = (normalIrqGate || stopped) && !flushing && excIdle && iplActive &&
!coreHalted && !debugQuiesceActive` (`:3211-3212`). `normalIrqGate` needs a `first`
head that is not faulted/RTE/sysOp/priv and no precise drain or inhibited load in
flight (`:3174-3176`); it does **not** need the head to be complete, so the head
instruction is preempted and re-executed after RTE (stacked PC = `p0.pc`, or
`stoppedPc` when stopped, `:1294`). Vector = autovector `24+level` or the SoC-supplied
vector (`:1290`); there is no IACK bus cycle and no spurious-interrupt path. With an
empty ROB (`count == 0`) and not stopped, no interrupt can be recognized until a µop is
allocated.

`irqPreemptArmed = (iplActive || tracePendingReg) && (p0.first || stopped)`
(`:3474`) is exported to the LS EU to hold inhibited-load launches.

#### 3.3.7 Trace

T1 arms on every retire; T0 arms on a retiring `retireAlone` head with
`branchTakenStore` (`:3375-3382`). Arming latches `{commitPc0, p0.pc}`
(`:3398-3407`); dispatch at the next `first` head (`traceNormalGate`, `:3414-3417`),
format $2 vector 9. `h0TraceArmed` forbids pairing.

#### 3.3.8 Sequencer cycle cost (derived from the FSM)

Notation: `D` = cycles waiting for `sqDrained && dcQuiesced` (≥1), `X` = one DTLB
round (X_REQ ≥1 + X_WAIT until the registered response, ≥1), `S` = cycles from
`E_STWAIT` entry to the write-through store ack (one AXI write round trip plus the
register stage), `L` = D-cache load response latency after `dcLoadCmd.fire`.

* **Format $0 entry** (illegal, TRAP, A/F-line, priv, interrupt): trigger cycle +
  `D` + `X` (stack page) + 4 × (1 + `S`) + [`X` if VBR page ≠ stack page] + 2
  (`E_VECREQ`: capture, then fire) + `L` + 1 (`E_REDIR`) + 1 (`doFlushReg`) →
  ≈ `6 + D + X + 4S + L` (+`X`), then the full frontend restart. Format $2 adds 2
  words (6 × (1+S)); format $7 is 30 words (30 × (1+S)); an M=1 interrupt pushes a
  second 4-word frame.
* **RTE** (format $0): trigger + `D` + `X` + 4 × (2 + `L`) + 1 + 1 → ≈ `11 + D + X +
  4L`, then frontend restart. $1 throwaway doubles the pops.
* **Simple sysOp** (MOVEC, MOVE USP, MOVE to SR, PFLUSHA): `sysRetire` needs
  `sysValRdyStore`, then `S_DRAIN` (≥1) + `S_APPLY` (1) + `S_REDIR` (1) + `doFlushReg`
  → ~4-5 cycles plus the full frontend restart.
* **FSAVE**: 26 × (1 + `S`) + ≤2 `X`.

With `S` an L2 write round trip, the entry cost is dominated by `4S`; any measured
"~36 cycles" therefore depends strongly on the memory system and is not a property of
the FSM alone.

### 3.4 System state

`exception/SystemState.scala`:

| Register | Reset | Notes |
|---|---|---|
| `srSys` (SR[15:8]) | 0x27 | writes masked with 0xF7 (bit 11 reserved) (`SystemState.scala:36, 105-106`) |
| CCR | — | not here: renamed NZVC/X PRFs; `RobPlugin.committedCcr` is a shadow for frames/debug |
| `vbr` | 0 | |
| `usp`, `isp`, `msp` | 0 | `a7 = S ? (M ? msp : isp) : usp` (`:68-70`) |
| `sfc`, `dfc` | 0 | 3 bits |
| `cacr` | 0 | bit 31 → D$ enable (`CacheControlService`), bit 15 → I$ enable |
| TC/URP/SRP/ITTx/DTTx/MMUSR | — | in `MmuControlPlugin`, written via `MmuControlService.set*` |
| FPCR/FPSR/FPIAR | — | in `FpuControlPlugin` |

`writeA7` is asserted **every cycle** with `committedA7In`, a dedicated int-PRF read at
the committed A7 mapping (`ExceptionUnit.scala:856`; `FullCoreSynth.scala:75, 739-740`),
so the active SP bank continuously mirrors arch A7; explicit `setUsp/Isp/Msp` win
(later `when`, `SystemState.scala:110-116`).

`InterruptControlPlugin` (`exception/InterruptControl.scala`) holds three registers
(`iplIn`, `iackAvec`, `iackVector`) with no next-state assignment inside the plugin
(`allowUnsetRegToAvoidLatch`); the top drives them.

### 3.5 Performance counters

* Always built (DebugCtrlPlugin, 64-bit, through one-cycle `perfTap` registers,
  `debug/DebugCtrlPlugin.scala:611-669`): mispredict redirects (`branchRedirect`),
  flushes (`doFlushReg`), ROB-non-empty cycles (`count =/= 0`), retire cycles
  (`retire0`; counts cycles with ≥1 retire, not µops), branch retires and
  conditional/unconditional mispredicts (from `DebugHistoryService.branchRetire`).
* Macro counter: 64-bit `debugMacroCountReg`, retimed as `lag + registered increment`
  (`RobPlugin.scala:1138-1143, 1612-1620, 1813`).
* Optional (`detailedPerf`, off in shipping): 23 ROB event classes
  (`services/PerfDetailService.scala:35-82`) using a 32×3 alloc tag RAM
  (`RobPlugin.scala:3039-3068`), exported as a 95-bit trace through `M68kCore.perfTrace`.

---

## 4. Configuration knobs

### 4.1 `M68kParams` (`Config.scala`)

| Field | Default = shipping | Read by RTL? |
|---|---|---|
| robDepth | 32 (`ROB_DEPTH_DEFAULT`) | yes (require + key) |
| physInt | 54 | yes |
| physNzvc / physX | `ShippingCoreConfig.physNzvc/X` (32/32 shipping) | yes |
| intRsDepth, eaRsDepth, memRsDepth | 8 | **no** |
| cplxRsDepth | 4 | **no** |
| loadQDepth | 8 | **no** |
| storeQDepth | 8 | **no** (real depth is `ShippingCoreConfig.storeQueueDepth`) |
| l1iKb / l1iWays / l1iLineBytes | 16 / 4 / 64 | only to set unused keys |
| l2Kb | 1024 | **no** |
| btbEntries / rasEntries | 128 / 16 | yes |
| gshareHistory / gshareEntries | 16 / 2048 | yes |
| tlbWays / tlbSets | 4 / 16 | **no** |
| decodeWidth / retireWidth | 2 / 2 | only to set unused keys |
| derived `robIdWidth`, `sqPtrWidth` | 5, 3 | only by `types/MicroOp.scala`, `types/RobEntry.scala` |
| `lqPtrWidth` | 3 | **no** |

(Usage counts from grepping `.<field>` outside `Config.scala`.)

### 4.2 `RobPlugin` constructor (`rob/RobPlugin.scala:47-55`)

| Param | Default | Shipping | Notes |
|---|---|---|---|
| detailedPerf | false | false (`PERF_DETAIL_ENABLE=0`) | |
| pairCorrectBranch | false | false | never set in `src/main`; set **true** by IPC benches (`src/test/.../IpcBenchSpec.scala:98` v2 path, `StrcmpIpcSpec`, `BranchPredictIpcSpec`, `RasBranchRepairIpcSpec`, `MissInjectionSweepSpec`) |
| preparedRetireEntries | 0 | 0 | prepared 4/8/16-wide retire; dead in shipping |
| pcRangeEnable | true | true (`CPU_DEBUG_PROFILE=full`) | |
| rasBranchRepair | false | true | must match RasPlugin (`FullCoreSynth.scala:228-230`) |
| lsOooIssue | false | true (`LS_OOO_ISSUE=1`) | adds `orderViolated[32]`, replay logic |

### 4.3 `ShippingCoreConfig` / `SocketTopConfig` / profile

Env name, source default, and shipping value from the build log.

| Knob | Env | Source default | Shipping |
|---|---|---|---|
| lsOooIssue (`SocketTopConfig.LS_OOO_ISSUE`) | `LS_OOO_ISSUE` | false | **true** |
| ipcThroughput / ipcLateStore | `CPU_IPC_PROFILE` | throughput-v2 → true/true | true/true |
| specLoadWakeup | `SPEC_LOAD_WAKEUP` | false | false |
| dcacheHitUnderMiss | none (constant) | true | true |
| dcacheHitUnderMissRead | `CPU_DCACHE_HIT_UNDER_MISS_READ` / `CPU_DCACHE_HUM_READ` | false | false |
| dcacheFillForward | `CPU_DCACHE_FILL_FORWARD` | false | false |
| dcacheDirectRefillResponse | `CPU_DCACHE_DIRECT_REFILL` | false | false |
| dcacheSectored | `CPU_DCACHE_SECTORED` | false | false |
| rasBranchRepair | `CPU_RAS_BRANCH_REPAIR` | true | true |
| computeDirectTargets | `CPU_COMPUTE_DIRECT_TARGETS` | true | true |
| deferSlot1Uncond | `CPU_DEFER_SLOT1_UNCOND` | true | true |
| deferSlot1Dbcc | `CPU_DEFER_SLOT1_DBCC` | true | true |
| itlbVictimEntries | `ITLB_VICTIM` | 0 | 0 |
| storeQueueDepth | `SQ_DEPTH` | 8 | 8 |
| sqNarrowDrainMerge | `SQ_NARROW_MERGE` | false | false |
| sqCoalesceLines / sqCoalesceHold | `CPU_SQ_COALESCE_LINES` / `_HOLD` | false / 16 | false / 16 |
| dcacheFullLineNoFill | `CPU_DCACHE_FULLLINE_NOFILL` | false | false |
| inhibitedFullBarrier | `CPU_INHIBITED_FULL_BARRIER` | false | **true** |
| dcacheNonBlocking | `CPU_DCACHE_NONBLOCKING` | false | **true** |
| dcacheNbEarlyResponse | `CPU_DCACHE_NB_EARLY_RESPONSE` | false | **true** |
| dcacheMshrs | `CPU_DCACHE_MSHRS` | 4 | 4 |
| lsLoadRingDepth | `CPU_LS_LOAD_RING_DEPTH` | 4 | 4 |
| lsP3FastLoad / lsP1EarlyLoad / lsFourCycleL1 | `CPU_LS_*` | false | false |
| lsEarlyAnRetry | `CPU_LS_EARLY_AN_RETRY` | false | **true** |
| physNzvc / physX | `CPU_PHYS_NZVC` / `CPU_PHYS_X` | 16 / 16 | **32 / 32** |
| dcacheEarlyProbeLineForward | `CPU_DCACHE_EARLY_PROBE_LINE_FORWARD` | false | false |
| dcacheHotDoor | `CPU_AXI_DH` | false | **true** |
| axiDhArFallThrough | `CPU_AXI_DH_AR_FALL_THROUGH` | false | false |
| dcacheStoreAllocArDelay | `CPU_DCACHE_STORE_AR_DELAY` | 0 | 0 |
| dcacheNbEagerAr | `CPU_DCACHE_NB_EAGER_AR` | false | **true** |
| dcacheNbPreselectAr / DynamicRelease / ProbeMissStage | `CPU_DCACHE_NB_*` | false | false |
| icachePrefetch | `DBG_IC_PREFETCH_DISABLE` (inverted) | true | true |
| OPEN1_GATE_DISPATCH | none | false | false |
| shippingPretranslatedProbeHints (`ShippingPlugins.scala:460`) | none | false | false |

(Rows in **bold** ship with a value different from the source default. Nine knobs (eight rows) are
in that state, so an `sbt` build with an empty environment is not the board core;
§7.4.)

Never-enabled in shipping (code present, elaborated out or idle): specLoadWakeup,
lsP3FastLoad, lsP1EarlyLoad, lsFourCycleL1, dcacheFillForward, dcacheDirectRefill,
dcacheSectored, dcacheHitUnderMissRead, dcacheEarlyProbeLineForward,
sqNarrowDrainMerge, sqCoalesceLines, dcacheFullLineNoFill, axiDhArFallThrough,
dcacheNbPreselectAr/DynamicRelease/ProbeMissStage, itlbVictimEntries, and in this slice
`pairCorrectBranch`, `preparedRetireEntries`, `detailedPerf`.

---

## 5. Performance-relevant behaviour

### 5.1 Commit bandwidth

* Peak 2 µops/cycle. A macro that cracks into k µops takes ≥ ⌈k/2⌉ retire cycles.
* Branch µops retire alone and block pairing on both sides (§3.1.4): a branch costs at
  least one full retire cycle on its own, and the µop before it cannot pair with it.
* Other pair blockers in the hot path: `needsSup` at h1, precise-store-completed h0,
  trace armed, any unmasked IPL across a macro boundary, `faultCapPending` (whole
  commit stalls one cycle per fault-completion event).
* Write-direction sysOps wait one extra cycle for `sysValRdyStore`
  (`RobPlugin.scala:1246`).
* `perfLvlRetire` counts only `retire0` (cycles with ≥1 retire); there is no counter
  for dual retires in the default build, so the 1-vs-2 split is not observable on the
  board unless `detailedPerf` is built.

### 5.2 Flush penalty breakdown

1. Mispredict detected at branch-EU completion; Tier-1 redirect one cycle later.
2. Rename frozen (`earlyPend`) until the branch retires; wrong-path µops continue to
   occupy ROB/IQ/EUs and consume issue bandwidth.
3. Branch retires (needs every older µop complete) → `doFlushReg` at +1 → rename
   resumes at +2 (§3.2.4).

So the penalty = (resolve → retire wait) + ~2 + refill of rename→EU, with the frontend
refetch overlapped. In load-to-use–bound code (the given Dhrystone profile) the
resolve→retire wait includes older loads' latency.

Non-Tier-1 flushes (exception, RTE, sysOp, LS order violation, LS replay, debug) take
the full frontend restart.

### 5.3 Exception latency

See §3.3.8. Entry ≈ `6 + D + X + 4S + L` for format $0 with `S` an AXI write round
trip per frame word; RTE ≈ `11 + D + X + 4L`; both followed by a full refetch.
A-line traps (Mac OS toolbox) and RTE pairs therefore cost at least `8` memory round
trips plus two pipeline restarts.

### 5.4 Serializing instructions

`sysOp` µops (all SysKinds in §3.3.4), RTE and faulted µops serialize: they must be at
the head, retire alone, drain the SQ and D-cache (`S_DRAIN`/`E_DRAIN`/`R_DRAIN`), and
end with a global flush and refetch of the next instruction. MOVE to SR, ANDI/ORI/EORI
to SR, MOVEC, MOVE USP and FMOVE to/from FPCR/FPSR/FPIAR are in this class.
STOP additionally quiesces fetch until an interrupt.

---

## 6. Area / timing notes (structure only)

* **`doFlushReg` / `flushing` fan-out.** `doFlushReg` is a single flop driving the
  frontend redirect mux, RAS restore, gshare repair, SQ flush, both TLB U/M flushes,
  RenameStage/DecodeStage/FetchAlign (§3.2.2), and through `flushing` (a LUT OR with
  `exc.activeReg`) the ROB pointers, 8+4 capture valids, 5 RAT rollbacks and 5
  freelist flushes. `backendSquash` already exists as an exact registered copy of
  `flushing` (`RobPlugin.scala:3612`) but is only used externally.
* **`exc.activeReg`** feeds `excSquash`/`flushing`, `excIdle` (in every retire/trigger
  term), the wiring's `excActive` (frontend flush, `umAccessPreCommitted`, SQ entry
  pulse), and the LS-side copy `excActiveLs`.
* **Retire cone.** `retire0` depends on `payload.readAsync(h0)` (2-bank XOR LUTRAM
  read), `completes(h0)`/`faultedStore(h0)` 32:1 muxes, `sysValRdyStore(h0)`,
  `interruptPending` (which needs `exc.ss.srSys`, `iplIn`, `p0.first`, …) and debug
  terms. `retire0/retire1/h0/h1` then fan out to rename commit, the SQ commit, both TLB
  U/M queues, sysCap frees, the `committedCcr` fold, `sysAux`, PC-range compares
  (two 64-bit range compares on `p0.pc/p1.pc`, `:4025-4027`), `perfLvlRetire`, and the
  `dbgRetQ` snapshot.
* **Head-relative age arithmetic** appears three times: fault capture
  (`age = robId - head`, `:2156`), Tier-1 (`bcAge`, `curAge`, `:3761-3762`), LS replay
  (`:1357-1358`).
* **Completion write decoders**: `completes` 9 writers/bit; `nzvcValStore/xValStore/
  sysValRdyStore` 4 writers + alloc; `faultedStore` 4 + alloc.
* **ExceptionUnit width**: `fmLoaded` 8×80 = 640 flops for cold FMOVEM
  (`ExceptionUnit.scala:598`); `frameWordData`/`fsFrameWordData` 32:1 and 26:1 16-bit
  muxes; the S_APPLY MOVEC read mux (16 sources × 32 bits, `:2566-2583`); three
  32-bit frame-base subtract/mux chains (`:1760`, `:1863-1865`) feeding the registered
  load/store command buffers.
* **PRF ports owned by this slice** (each distinct `sharingKey` is a separate
  physical port in `RegFilePlugin`, lowered by the XOR multi-write scheme):
  int PRF — `a7Wr` (shared with branch EU, `FullCoreSynth.scala:73-74`), `a7Rd`
  (read), **`fmAnWrite` (separate port, `RobPlugin.scala:187`)**; FP PRF —
  `frameFpSrcRead`, `frameFpDstRead`, `fmFpRead` (3 reads) and **`fmFpWrite` (separate
  port)** (`RobPlugin.scala:179-184`); NZVC/X — one write each; FPCC — one read, one
  write.

---

## 7. Observations

### 7.1 Likely defects / risks

1. **`euFaultCompletion` collision drops a fault.** Branch-EU `trapvFault`
   (TRAPV/TRAPcc vector 7, odd-target vector 3) and DivEu `euFault` (CHK 6, DIV0 5)
   share one Flow; when both are valid in the same cycle the DivEu payload overrides
   (`top/FullCoreSynth.scala:298-299, 404-407`). The branch EU completes the same µop
   on its separate `branchCompletion` port (`execute/BranchEuPlugin.scala:375, 441`),
   so a dropped fault leaves an entry complete but unfaulted and it would retire
   without trapping. The two EUs are independent pipelines; nothing found in this
   slice prevents a same-cycle fault. Reachability was not proven. Fix: a fifth fault
   port in the existing `faultPorts` fold (`RobPlugin.scala:2162-2176`), which already
   arbitrates by age.
2. **`GshareUpdateService` has two providers** (`RobPlugin`, `GsharePlugin`). Any
   future `host[GshareUpdateService]` is ambiguous. GsharePlugin should not implement
   it.
3. **Frame/stack formats $3/$4 are not produced and RTE rejects them** (§3.3.2).
4. **Interrupt cannot be recognized on an empty, non-stopped ROB**
   (`normalIrqGate` requires `count > 0`, `RobPlugin.scala:3174`). If fetch stalls
   with the ROB empty (e.g. a long I-miss), an interrupt waits for the next µop.
   Harmless for correctness, adds latency.
5. **FSAVE always writes 52 bytes** even for null/idle state (`ExceptionUnit.scala:
   2865`); the null-frame branch of `fsFrameWordData` and `fsIsNull` are effectively
   unused (`fsIsNull := False` always in S_APPLY). This deviates from the 68040
   (null = 4 bytes) by design; any OS FSAVE/FRESTORE context switch moves 52 bytes with
   26 serialized write-through round trips.

### 7.2 Dead or redundant logic

* `types/RobEntry.scala` — unused type that describes a different ROB.
* `FlushService` — no provider, no consumer.
* Global keys `DECODE_WIDTH`, `RETIRE_WIDTH`, `L1I_KB`, `L1I_WAYS`,
  `L1I_LINE_BYTES` — set, never read. `M68kParams` fields `intRsDepth`, `eaRsDepth`,
  `memRsDepth`, `cplxRsDepth`, `loadQDepth`, `storeQDepth`, `l2Kb`, `tlbWays`,
  `tlbSets`, `lqPtrWidth` — never read. `core/HelloPlugin.scala` — not in any shipping
  list.
* `RobRetirementService` lanes 2-3 and every `for (lane <- 2 until retireWidth)`
  block — empty at width 2. `preparedBatch` and `pairCorrectBranch` — not elaborated /
  constant in shipping.
* `CommitTrace` (`types/CommitTrace.scala`): in `driveCommit` most fields are constant
  0 (`RobPlugin.scala:1890-1900`); in the socket build `BackendWiringPlugin` registers
  it into `M68kCore` output ports `traceOut/fireOut/eu0Res/eu1Res`
  (`FullCoreSynth.scala:779-787`) that `M68kSocketTop` never connects. Synthesis
  should prune them, but they are RTL noise and a naming/keep hazard.
* `exceptionPending = faultRetire || sysPrivFault || privOnly` — `privOnly` is
  implied by `faultRetire` (`RobPlugin.scala:2682-2684`).
* `frameBase` computed twice (IDLE `:1760` and `E_DRAIN` `:1863-1865`); the IDLE copy
  is always overwritten before use.
* `M68kCore.dbg040` (`core/M68kCore.scala:303-454`) re-drives ~20 internal signals of
  nine plugins to ports in every socket build; whether they survive depends on the SoC
  ILA define.

### 7.3 Framework issues

* Fiber-cycle avoidance forces most ROB outputs into setup-allocated wires; combined
  with the wiring plugin's direct `rob.logic.*` access, the "services only" rule is
  not what the code does (§1.1).
* RobPlugin sizes itself from `Global.ROB_DEPTH_DEFAULT`, not the published
  `ROB_DEPTH` key, while DivEu reads the key. Consistent only because ParamPlugin
  requires equality.
* RETIRE_WIDTH key (2) and the actual retire width (`RenameStage.retireWidth`) are
  independent; nothing ties them.

### 7.4 Code vs comments / docs (stale text found while reading)

* Many RobPlugin comments describe a 64-entry ROB (`64-entry async head muxes`,
  `count <= depth-2 = 62`, `(robId-head) mod 64`, `64x32 = 2048 FF`) — the code is 32
  entries.
* `types/RobEntry.scala:6-7` says "the ROB is keyed by INSTRUCTION, never by µop"; the
  ROB is keyed by µop.
* `RobPlugin.scala:35-45` header says retire is "two/four-wide" and branches "for now";
  shipping is 2-wide and all `isBranch` µops are retire-alone.
* `ShippingCoreConfig` comments call several knobs "OFF pending measurement" while the
  shipping build turns them on by environment (`physNzvc` comment says "16 is the
  shipping default").
* `SystemState.scala:46-52` says CACR has "NO functional effect on the D-cache"; CACR
  bit 31 and bit 15 are consumed (`RobPlugin.scala:3075-3078`,
  `ExceptionUnit.scala:914`).
* `services/Services.scala:88` says CommitTrace is "consumed by lock-step"; in the
  socket build it is unconnected.

### 7.5 Cheap opportunities (unmeasured; ranked by expected value)

1. **Drive `rc.flushPort` (and the ROB-internal flush resets) from `backendSquash`
   instead of the combinational `flushing` OR.** In the socket build they are equal
   every cycle (`flush.valid` tied False). This removes a LUT level in front of the
   RAT-rollback/freelist-flush and ROB pointer CE/reset fanout — the class of net the
   prior timing findings say dominates the 200 MHz tail. A replica flop per consumer
   group (rename vs ROB) costs one flop each.
2. **Fold `fmAnWrite` into the `a7Wr` port** (same `BranchEuPlugin.IntWbKey`,
   priority 0, as `FullCoreSynth.scala:73-74` already does for `a7Wr`), and fold
   `fmFpWrite` into an existing FP write key. Both are exception-serialized cold paths;
   each deletes one physical PRF write port (one LUTRAM bank plus an XOR term on every
   read port of that PRF). Likewise the three cold FP read ports
   (`frameFpSrc/Dst`, `fmFpRead`) are never used concurrently with each other and could
   be one port.
3. **Exception frame stores as non-precise / pipelined.** Every frame word waits a
   full write-through AXI round trip (`ExceptionUnit.scala:914-928, 1945`). Issuing the
   4/6 words back-to-back (or as two longword stores) and waiting once for the last ack
   would cut format-$0 entry by ~3 round trips. RTE similarly issues four WORD reads
   strictly one after another (`R_*REQ`/`R_*WAIT`); with an aligned SSP the 8-byte frame
   is two longwords, so two LONG reads (or overlapped WORD reads) would roughly halve
   the pop. Relevant to A-line-heavy
   Mac OS code.
4. **Enable `pairCorrectBranch` (or evaluate it on silicon).** The logic exists
   (`RobPlugin.scala:1444-1446`) and the IPC benches already run with it on
   (`IpcBenchSpec` v2 path), so sim IPC numbers from those benches include a feature the
   board does not have. Either ship it or stop benchmarking with it.
5. **Remove the one-cycle commit hold on every fault-completion event**
   (`faultCapPending` in `headReady`). Restricting the hold to the case where a
   captured robId equals `h0` would make wrong-path/younger faults free. Needs a
   compare of 4×5 bits against `h0`; measure how often `faultCapPending` fires first.
6. **Per-retire counter of dual retires** for the default build (one 64-bit counter on
   the registered `dbgRetQ.fire(1)`), so commit bandwidth can be read on silicon without
   `detailedPerf`.
7. **Delete dead config/keys/types** in §7.2 to reduce the misleading surface
   (no hardware effect).

### 7.6 Larger levers (not cheap; noted for the reviewer)

* Mispredict recovery waits for the branch to retire because RATs/freelists only
  restore to committed state. Per-branch RAT checkpoints (or a ROB-walk restore) would
  let rename resume at Tier-1 time; with Tier 1 already redirecting the frontend, this
  is the remaining mispredict cost (§5.2).
* Every serializing system op refetches. MOVE to SR / ANDI-to-SR that do not change S,
  M, T or I could avoid the flush; MOVEC to non-translation registers likewise.
