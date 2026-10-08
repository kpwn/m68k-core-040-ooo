# 07 — Load/Store Unit (LS EU, Store Queue, memory ordering)

Source tree: commit `fee95f6f`, the RTL running on the board.
Shipping configuration: the `SHIPPING_CONFIG` line from the build log, quoted in §4.

**Method.** Every structural, timing and liveness claim below comes from the Scala
code, not from source comments or older `docs/*`. The source comments in this slice
are long and often out of date, so they were treated as unverified. Where an older
document or comment disagrees with the code, §7.6 records the disagreement. The
silicon numbers (8.3-cycle L1 load-to-use, MLP = 1.000, and so on) come from the task
brief. They are measurements, not code facts, and are labelled as such.

**Path abbreviations** (all relative to the repo root):

| Short | Path |
|---|---|
| `LSU:` | `src/main/scala/m68k040/execute/LsEuPlugin.scala` |
| `SQ:` | `src/main/scala/m68k040/ls/StoreQueue.scala` |
| `MDT:` | `src/main/scala/m68k040/ls/MemoryDependencyTracker.scala` |
| `MDC:` | `src/main/scala/m68k040/ls/MemoryDependencyCheck.scala` |
| `MOP:` | `src/main/scala/m68k040/ls/MemoryOrderPlugin.scala` |
| `IQ:` | `src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala` |
| `DC:` | `src/main/scala/m68k040/cache/DcachePlugin.scala` |
| `DTLB:` | `src/main/scala/m68k040/mmu/DtlbPlugin.scala` |
| `SCC:` | `src/main/scala/m68k040/top/ShippingCoreConfig.scala` |
| `SP:` | `src/main/scala/m68k040/top/ShippingPlugins.scala` |
| `ST:` | `src/main/scala/m68k040/top/SocketTop.scala` |
| `FCS:` | `src/main/scala/m68k040/top/FullCoreSynth.scala` (holds `BackendWiringPlugin`) |
| `ROB:` | `src/main/scala/m68k040/rob/RobPlugin.scala` |

---

## 1. Purpose and position

`LsEuPlugin` is the core's only memory execution unit. It does the following:

- receives one LS-class uop per cycle from issue-queue port 3 (`IQ:1208`,
  `LSU:issuePort`);
- reads the base, index and store-data registers from the integer PRF, and computes
  the effective address;
- translates through the DTLB (`DTranslationService`). In parallel it launches a
  virtually-indexed probe of the D-cache tag/data RAM;
- for stores, allocates an entry in its private `StoreQueue` (instantiated at
  `LSU:762-767`). For loads, queries the SQ for forwarding or a stall;
- launches loads to the D-cache through a 4-entry in-order descriptor ring, and
  matches the responses;
- writes integer, NZVC and X results through one shared registered completion stage,
  and announces completion and wakeups to the ROB and the IQ;
- arbitrates the D-cache load and store ports among four clients: itself, the
  commit-side exception sequencer, and the ITLB and DTLB table walkers
  (`LSU:547-689`, `LSU:5416-5648`);
- enforces the device-access (cache-inhibited) ordering rules. These are: launch at the
  ROB head, the D4 full barrier, the park buffer, order-violation recovery and the
  liveness replay.

The SQ is drained in program order into the D-cache store port after the ROB retires
each store (`sqCommit`/`sqCommitB`). Precise stores are the exception: they drain at
the ROB head before they complete (§3.6).

The `ls/` package also holds `MemoryOrderPlugin`, `MemoryDependencyTracker` and
`MemoryDependencyCheck`. **None of them is instantiated in any top.** `grep` finds no
`new MemoryOrderPlugin` anywhere in `src/main`. `DispatchPlugin` only does
`host.get[MemoryOrderService]` (`src/main/scala/m68k040/dispatch/DispatchPlugin.scala:41`),
which resolves to `None`. They are dead in the shipping core (§3.5, §7.3).

---

## 2. Interfaces

### 2.1 Services and Global keys

`LsEuPlugin` produces no `Database` key. It only uses Global constants:
`ROB_ID_W_DEFAULT` (5 bits; `ROB_DEPTH_DEFAULT = 32`, `Global.scala:34,42`),
`PHYS_NZVC_ID_W`, and the `robTag` helper. `StoreQueue` and `MemoryDependencyTracker`
also size their robIds from `ROB_ID_W_DEFAULT`.

**Produced service: `LsEuService`** (`LSU:17-78`). The members are:

| Port | Type | Meaning (from the driver code) |
|---|---|---|
| `issue` | `Stream[IqContext]` (slave) | uop from IQ port 3. `ready` is set at `LSU:4875`. |
| `completion` | `Flow[robId]` | Registered `compValid`/`compRobId` (`LSU:3068-3069`). |
| `wakeup` | `Flow[UInt(6)]` | Integer pdst. Shipping (`earlyIntWakeup`) announces at capture, one cycle before the PRF write. The early-An wake is OR-ed in (`LSU:3195-3198`). |
| `wakeupNzvc` | `Flow` | NZVC pdst, early form (`LSU:3213-3214`). |
| `wakeupSpec` | `Flow` | Constant-idle in shipping (`specLoadWakeup = false`, `LSU:4205`). |
| `wakeupSpecP3` | `Flow` | Constant-idle (`fourCycleL1 = false`, `LSU:4223`). |
| `liveLoadConfirm` | `Flow` | Constant-idle (`fourCycleL1 = false`, `LSU:2621-2632`). |
| `orderViolation` | `Flow[robId]` | Under LS-OoO: an inhibited op, with a younger access already in the ring or launched (`LSU:4268-4286`). |
| `replayRequest` | `Flow[robId]` | Under LS-OoO: liveness replay of a P4 op (`LSU:3227-3228`). |
| `faultCompletion` | `Flow[LsFault]` | DTLB fault, or a refill bus error on a load (`LSU:3226-3234`). |
| `sqCommit`, `sqCommitB` | `Flow[robId]` (in) | ROB retire slots 0 and 1. Extra lanes come from `RobRetirementService` (`LSU:774-777`). |
| `sqFlush` | `Bool` (in) | Wired to `doFlush \|\| excEnteringSq` (`FCS:439`). |
| `sqDrained` | `Bool` | `sq.io.empty` (`LSU:824`). |
| `xlateRobId` | `UInt` | robId of the LS translation request. Zero when the exception sequencer owns the DTLB (`LSU:5414`). |

**Other plugin-level wires** (plain `var`s, driven by `BackendWiringPlugin` in `FCS:`):

- the exception-sequencer hand-over: `excActive`, `excLoadCmd*`, `excStore*`,
  `excXlate*` (`LSU:264-273`);
- `robHeadIn`/`robHeadValidIn` = ROB `h0`/count, and `irqPreemptPendingIn`
  (`FCS:347,363`);
- `sqCompletionPort`/`sqFaultCompletionPort`, the ROB completion port for precise
  stores;
- `preciseDrainBusySig`, `inhibitedLoadBusySig` and `quiesceHold`.

**Consumed services** (`LSU:368-444`, `491`, `550`, `761`, `5291`, `5397`):

- `IntRegFileService`: three read ports (`rdBase`, `rdData`, `rdIndex`); one write
  port under sharing key `"lsIntWb"` at priority 1, plus the early-An write at
  priority 0 under the same key (`LSU:387,400`); one bypass port;
- `NzvcRegFileService`: write + bypass. `XRegFileService`: write + bypass;
- `DcacheService`, `DTranslationService`;
- optional services: `LateStoreDataService`, `PrivilegeService`, `MmuControlService`,
  `CacheControlService`, `DebugLoadPreemptService`, `RobRetirementService`,
  `SerializedMemoryContextService`, and `Itlb`/`DtlbWalkerDcacheClient`.

### 2.2 Ports by neighbour

- **IQ.** `issue` (one uop per cycle), `wakeup`, `wakeupNzvc`. The LS-issue order
  policy lives in the IQ, not the LSU (§3.5).
- **PRF.** Reads happen at S0, in the same cycle as `issuePort.fire` (`LSU:873-927`).
  Writes come from the registered `comp*` stage (`LSU:3070-3094`) and from the
  early-An writer (`LSU:3148-3150` / `3183-3185`). `rdData` is time-shared with
  late-store-data capture; that is what deasserts `issuePort.ready` (`LSU:4875`).
- **DTLB.** Request `vpn`/`supervisor`/`write`/`token` (`LSU:5324-5342`), with the
  exception-side request buffered at `LSU:5378-5408`. The DTLB response is
  registered: `rspValid` is set on the cycle after `req.fire` on a hit
  (`DTLB:544-566`). `_req.ready` requires `!rspValid || rsp.ready` (`DTLB:428`), so at
  most one translation is in flight.
- **D-cache.** The LSU drives the following ports:
  - `loadProbe`, the VIPT early probe (`LSU:2157-2182`);
  - `loadProbeResolve`, the physical tag for that probe (`LSU:2704-2710`);
  - `loadProbeCancel` (`LSU:2183-2185`);
  - `loadCmd`, which carries `vaddr`, `paddr`, `size`, `cacheMode`, `token`,
    `lineOnly`, `ooOk` and `rid` (`LSU:1910-1947`);
  - `loadRsp`, a `Flow` with no backpressure;
  - `store`, which carries `DStoreCmd` from `sq.io.drain` (`LSU:783-785`);
  - `storeAck`/`storeErr`, untagged and demultiplexed by owner (`LSU:800-801`).

  It also reads `busQuiesced`, used only with D4 (`LSU:3955`).
- **ROB.** Completion ports, `faultCompletion`, the precise-store completion, the
  order-violation and replay requests, and the two busy signals.
- **Store queue.** Internal ports: `alloc`, `reserveOnly`/`allocSlot`, `publish`,
  `fwd.query`/`fwd.rsp`, `barrier.robId`/`olderStore`/`olderInhibitedStore`, `full`,
  `empty`, `sqCompletion`, `flushKeptPrecise`/`sqCompletionOrphan`,
  `inhibLaunchOk`/`inhibHeadHold` (`SQ:116-193`).

---

## 3. Microarchitecture

### 3.1 Stage names and registers

The RTL names the front stages S0/P1/P2/P2T/P3/P4. Each stage's valid flag and context
register are listed below.

| Stage | Valid / context | Contents |
|---|---|---|
| S0 (issue cycle) | `issuePort.valid` | PRF reads. Capture into `s1*` on `issuePort.fire` (`LSU:4926-4932`). |
| P1 ("S1") | `s1Valid`, `s1Ctx` (full `IqContext`), `s1Base`, `s1Data`, `s1Index`, `s1LateData` | `LSU:946-953` |
| P2 | `tValid`, `tCtx: FrontPipeCtx` | `LSU:1195-1196` |
| P2T | `txValid`, `txCtx`, `txSecond`, `txWaitingRsp`, `txPaddrA/CmodeA`, `txToken` | `LSU:1197-1203` |
| P3 | `p3Valid`, `p3Ctx: XlatePipeCtx` (front + `paddr`/`paddrB`/`cmode`/`cmodeB`) | `LSU:1205-1206` |
| P4 | `p4Valid`, `p4Ctx: ResolvePipeCtx` (xlate + `fwdHit`/`fwdStall`/`fwdData`/`fwdSerial`), `p4RetryQ` | `LSU:1207-1208`, `1239` |
| Ring | `alignedMem[4]` plus `valid`/`sent`/`poisoned`/`done`/`wb`/`RData`/`RLine`/`RFault`, and the push/send/rsp pointers | `LSU:1526-1556` |
| Park (LS-OoO) | `parkValid/parkMem/parkSaw/parkHitR[4]` | `LSU:1596-1608`, `3831` |
| Completion | `compValid`, `compRobId`, `compData`, `compPdst`, … | `LSU:2208-2291` |

All stages are elastic: `xReady = !xValid || xCanLeave` (`LSU:4159,4623,4771,4862,4870`).
Valid updates are ordered oldest first, with accept-last (`LSU:4879-4932`). The
ordering lets a stage drain and refill on the same edge, which sustains II=1.

`FrontPipeCtx` (`LSU:1106-1170`) is about 170 bits per copy. It carries:

- `robId`, `vaddr`, `addrB`, `storeData`, `anWb`, `storeNzvc`, `size`, `memOp`,
  `twoAccess`;
- `pdst`/`pdstValid`, `dstArch`;
- the flag bits `stkPush`, `autoStoreAn`, `ccrRestore`, `signExtW`, `leaAddr`,
  `writesNzvc`/`pNzvcDst`, `writesX`/`pXDst`, `crackDrop`, `keepCommit`,
  `needsSupervisor`, `supervisor`, `fcSup`, `probeOnlyB`;
- optionally `lateDataPending`/`lateDataTag`.

The context is replicated in P2, P2T, P3 and P4.

### 3.2 Stage by stage

**S0 — operand read** (`LSU:866-932`)

- `rdBase.addr := psrcA`, `rdData.addr := psrcB`, `rdIndex.addr := psrcC`.
- `base0` is the base read, or 0 when `!psrcAValid`. It covers absolute and
  PC-relative addressing, where the constant is folded into `imm` (`LSU:902-903`).
- `data0` is `imm` for a BSR/JSR push, otherwise the source register (`LSU:911`).
- The index term is sign- or zero-extended and shifted by the scale (`LSU:921-927`).
- The S0 work ends in registers: `s1Base`, `s1Data` and `s1Index`. No AGU add happens
  in S0.

**P1 — AGU** (`LSU:964-1100`)

- `s1Disp` is selected from `-size` (stack push), `-eaDelta` (PREDEC), `0` (POSTINC)
  or `imm` (`LSU:979-982`).
- `s1Va = s1Base + s1Disp + s1Index`. This is a three-operand 32-bit add
  (`LSU:988`).
- The An write-back value is `s1AnWb`: `s1Base + eaDelta` for POSTINC, else `s1Va`
  (`LSU:1006-1007`).
- Cross detection: `s1CrossLine = off + n > 16` and `s1CrossPage = pageOff + n > 4096`.
  `s1AddrB` is the next line base (`LSU:1036-1042`).
- MOVEM far-page probe. The first element of a LOAD MOVEM carries the element count in
  `faultVector`. If the span from that element crosses a 4 KB page, `twoAccess` and
  `probeOnlyB` are forced so that the second-translation machinery checks the far page
  before any element can retire (`LSU:1057-1099`, `2355-2366`). `txOut` then clears
  `twoAccess` before P3 (`LSU:4733-4734`).
- `captureFrontCtx` builds the pruned context, including `fcSup` from SFC/DFC bit 2
  for MOVES (`LSU:2344-2405`).
- P1 moves to P2 when `tReady` (`s1ToT`, `LSU:4867`). The issue-side ready is
  `s1Ready && !sqFlushSig && !excActive && !lateDataCapture` (`LSU:4870-4875`).

**P2 — translation request and VIPT probe** (`LSU:2050-2193`, `4775-4862`)

- `normalReqArm := tValid && tIsMem && txReady && !xlateBArm && !sqFlushSig &&
  !dcLoadHeldByOther && (!tIsLoad || dcache.loadProbe.ready)` (`LSU:4789-4790`).
  A load therefore takes a D-cache probe credit atomically with the DTLB request.
- The request payload comes from `tCtx`, or from `txCtx.addrB` for the second
  translation of a split access, which takes priority (`LSU:2088-2107`).
- `dcache.loadProbe` presents `tCtx.vaddr` with `resolved = False`, `paddrHint = 0`
  and `cacheMode = INHIBITED` (`LSU:2157-2182`). The probe reads the virtual-set RAM
  while the TLB looks up.
- LEA and other non-memory LS-cluster uops complete directly from P2 through
  `captureCompletionFront` (`LSU:4850-4858`). They never reach P3.

**P2T — translation response** (`LSU:4628-4771`)

- The DTLB response is matched by token (`txTokenMatch`, `LSU:4631-4632`). The token is
  `{epoch, isSplitB, robId}` (`LSU:2106`). `xlateEpoch` toggles on every flush
  (`LSU:4950`), which retires stale responses.
- The physical address is `ppn ## VA[11:0]`, or `ppn[19:1] ## VA[12:0]` in 8 KB mode
  (`LSU:1267-1279`).
- The cache mode is forced to INHIBITED when `CACR.DE = 0` (`LSU:4702-4703`).
- `loadProbeResolve` sends the translated `s1Paddr` for the probe token on an aligned
  single-access load (`LSU:4704-4710`). This is where the early-probe tag compare is
  qualified.
- On a fault, `captureFaultFront` runs and the probe is cancelled (`LSU:4757-4760`).
  The exception is a `needsSupervisor` access in user mode: its fault is suppressed so
  that the ROB raises the privilege violation instead (`LSU:4755`).
- For a split access, the first response latches `txPaddrA/CmodeA` and re-arms P2T for
  `addrB` (`LSU:4761-4765`). P2T is busy for one extra translation round trip.
- A non-split, non-fault response is consumed only when `p3Ready`
  (`txCanConsumeRsp`, `LSU:4637-4645`). Its result moves into P3 as `txOut`.

**P3 — SQ operation** (`LSU:4437-4623`)

- *Stores*:
  - fast (`fastStore`): allocate in the SQ and complete through
    `captureCompletionFront` (`LSU:4583-4597`);
  - precise: allocate with `precise = 1` and push a deferred-completion record into
    `pendMem` (`deferCompletion`, `LSU:3009-3063`);
  - privileged-in-user (`storePrivBlocked`): complete with no SQ allocation
    (`LSU:4575-4582`);
  - late data: reserve an SQ slot (§3.6).

  `fastStore = mmuEnable && cmode ≠ INHIBITED && (!twoAccess || cmodeB ≠ INHIBITED)`
  (`LSU:1317-1320`). **With the MMU disabled, every store is precise.**
- *Loads*: P3 moves to P4 when `p4Ready`. P4 captures the SQ forward response that P3
  queried that cycle (`LSU:4885-4898`, `4613-4616`). The `p3FastLoad` direct enqueue is
  OFF in shipping.
- The SQ forward query context is P3, unless P4 is retrying, in which case it is P4
  (`fwdQueryCtx = Mux(p4RetryQ, p4Ctx.xlate, p3Ctx)`, `LSU:2026`). `p4RetryQ` is a
  registered copy of the retry predicate (`LSU:1239`, `4104`, `4879`, `4894`, `4945`).

**P4 — resolve and launch** (`LSU:4075-4159`)

- `fullForward = fwdHit && !twoAccess`. The load completes from the SQ data, and the
  probe is cancelled (`LSU:4084-4092`).
- `mustRetry = fwdStall || (fwdHit && twoAccess) || fwdSerial`: P4 re-queries the SQ
  every cycle and holds (`LSU:4093-4106`).
- Otherwise, if `p4LaunchOk` (`LSU:3977-3979`):
  - an aligned load pushes one ring descriptor (`alignedEnq`), gated by
    `alignedCanEnq`;
  - a split load pushes an A/B pair (`alignedEnqSplit`), gated by
    `alignedCanEnqSplit`, which needs two free slots (`LSU:4108-4127`).
- If `!p4LaunchOk` and LS-OoO is on, an inhibited op that is not at the ROB head moves
  to the park (`LSU:4149-4155`).

**Ring send and the D-cache command** (`LSU:1819-1951`)

- The command comes from `alignedMem(alignedSendPtr)`. `alignedLoadFallThrough` is ON
  in shipping. When the ring has no unsent entry (`sendPtr === pushPtr`, ring not
  full), the command is built directly from the P4 registers in the same cycle as the
  push. This applies only to non-inhibited loads (`LSU:1836-1883`). The pushed entry
  is marked `sent` immediately (`LSU:2833`).
- `ooOk = !twoAccess` (`LSU:1942`): the cache may return this response out of order.
- `rid = alignedSendPtr` (`LSU:1946`). It is the response identity.
- `lineOnly = twoAccess` (`LSU:1937`).
- `loadCmd.valid` requires `coreLsGrant` (the port owner is CORE and there is no
  exception load) and `!ldFifoFull` (`LSU:1907-1910`).

**Response, extract and writeback** (`LSU:1720-1737`, `2606-2928`, `3317-3399`)

- `alignedRspMatchVec(i)` decodes `loadRsp.rid`. A response for the ring head is
  consumed live (`alignedHeadLive`). A response for an entry behind the head is
  parked in `alignedRData`/`alignedRLine`/`alignedRFault` (`LSU:2745-2752`).
- Ring slots are released strictly in order (`alignedRspFire`, `LSU:1732`).
- A non-head entry whose data has arrived may write back out of order through
  `alignedEarlyWbFire`. This needs no fault, not split, not poisoned, and no pending
  privilege check, and the head must not be completing that cycle (`LSU:2669-2688`,
  `3387-3391`).
- The byte-lane extract and sizing (`data`) is done in the D-cache
  (`loadRsp.payload.data`). The LSU only:
  - sign-extends for MOVEM.W (`signExtW`, `LSU:2522`);
  - derives NZVC (`moveNzvc`, `LSU:2331-2341`);
  - merges split halves with `DcacheByteLane.extractCross(splitMergeLine, line,
    mergeOff, size)` (`LSU:3350-3353`).

  Slot A's line is held in `splitMergeLine`. It carries a valid bit and the robId, and
  is cleared on flush (`LSU:3310-3321`, `3396-3399`).
- `captureCompletionDesc` loads the `comp*` registers. When `earlyIntWakeup` is on,
  it also asserts `nextIntWake` combinationally, so `wakeup` fires one cycle before
  `compValid` (`LSU:2517-2548`, `3195-3198`).
- The next cycle, `compValid` drives `intW`/`intByp`, `nzvcW`, `xW` and `completion`
  (`LSU:3068-3094`).

**One completion stage, five writers.** At most one capture per cycle, in this
priority order:

1. back/ring head, or an early writeback (`backCompFires`, `LSU:3504-3506`);
2. a backlogged precise-store replay (`preciseReplayClaimsComp`, `LSU:3592`);
3. a detached-store completion;
4. P4;
5. P3;
6. P2T fault / P2 LEA.

The `olderThan*Comp` terms implement this order (`LSU:4493`, `4634`, `4847`). A
fast-path precise replay yields to any live capture and retries the next cycle
(`LSU:5057`).

### 3.3 Cycle-accurate L1-hit load-to-use, shipping configuration

Take an aligned, cacheable `move.l (An),Dn` with a DTLB hit, no SQ overlap, an empty
ring and no port contention, followed by a dependent consumer. Cycle 0 is the cycle in
which the IQ selects the load.

| Cycle | Unit / stage | What happens | Code |
|---|---|---|---|
| 0 | IQ select | `ohL` chosen, `selPorts(3).fire`; payload into `lsIssValid/lsIssHot` | `IQ:880-884`, `1208`, `1226-1230` |
| 1 | LSU S0 | `issuePort.fire`; PRF read of An (+bypass); capture `s1Base` | `LSU:873-903`, `4926-4932` |
| 2 | LSU P1 | `s1Va = s1Base + disp + index`; cross detect; `tCtx` capture | `LSU:988`, `1036`, `4921-4924` |
| 3 | LSU P2 | DTLB request + VIPT `loadProbe` from `tCtx` flops | `LSU:2089`, `2157`, `4789`, `4907-4913` |
| 4 | LSU P2T | DTLB `rspValid` (registered hit); `paddr`; `loadProbeResolve`; `txOut→p3Ctx` | `DTLB:564-566`, `LSU:1267`, `4704`, `4899-4902` |
| 5 | LSU P3 | SQ forward CAM on `p3Ctx.paddr` (8 entries); result captured into `p4Ctx` | `LSU:2026-2041`, `4885-4898`, `SQ:834-1161` |
| 6 | LSU P4 | `p4LaunchOk`; `alignedEnq`; fall-through `loadCmd` fires | `LSU:4108-4113`, `1858-1883`, `1948` |
| 7 | D-cache S2 | Early-probe hit: the command fire sets `ldS2Valid` directly; `loadRsp.valid` | `DC:1727`, `3002-3011`, `1881` |
| 7 | LSU | `alignedRspFire`; `captureCompletionDesc`; `wakeup` (`nextIntWake`) | `LSU:1731-1732`, `3360`, `3195` |
| 8 | LSU / IQ | `compValid` → `intW` (latency 1) and `intByp`; IQ `dynWait`/`dynWaitAny` registers clear | `LSU:3070-3075`; `IQ:202-209` |
| 8 | IQ select | consumer `ready = sel && … && !dynWaitAny`, consumer selected | `IQ:208-209` |
| 9 | consumer S0 | consumer reads the PRF | — |

**Result: 8 cycles from load select to consumer select**, and equally 8 cycles from
load S0 to consumer S0, on an early-probe L1 hit. This matches the measured
**8.29 cycles** on silicon. Variants:

- **Probe miss** (no early-probe entry, or `earlyProbeConflict`): the command fire goes
  through the S1 RAM read, `ldS1Valid` → `ldS2Valid` (`DC:1863`, `3024`). +1 cycle,
  9 total.
- **SQ-forwarded load**: completes at P4, cycle 6. `nextIntWake` fires at cycle 6, so
  load-to-use is **7**.
- **Ring not empty at the send pointer** (an older unsent command): no fall-through;
  the command leaves one or more cycles later from the ring.
- **Inhibited (device) loads** never fall through (`LSU:1860-1863`). They also wait
  for the ROB head, for every older store to drain, and with D4 for `busQuiesced`
  (§3.7).

**Where the 8 cycles go:**

| # | Cycle | Avoidable? |
|---|---|---|
| 1 | IQ select → issue register | — |
| 2 | S0 operand read → `s1Base` register | The AGU add is deliberately not in S0. |
| 3 | P1 AGU add → `tCtx` register | — |
| 4 | P2 DTLB/probe launch from registers | The DTLB is accessed from a register, not from the AGU output. |
| 5 | P2T registered DTLB response | — |
| 6 | P3 SQ CAM → registered verdict | — |
| 7 | P4 launch → D-cache S2 response | — |
| 8 | IQ registered dynamic-wait clear | — |

A 68040 overlaps EA calculation, ATC lookup and cache access in roughly 2–3 cycles.
Here AGU, TLB, SQ check and cache access each take their own registered stage. Three
of them — P1/P2, P2T/P3 and P3/P4 — are register boundaries with one thin layer of
logic each.

**Experimental paths in the code that remove cycles; all OFF in shipping:**

- `p1EarlyLoad` (`LSU:2051`, `4795-4810`): for a simple `MOVE.L (An),Dn` with zero
  displacement and no index, sends the DTLB request and probe from P1. Saves P2.
- `p3FastLoad` (`LSU:4599-4617`): enqueues to the ring from P3, but only while
  `sq.io.empty`, P4 is empty, the park is idle, `!inhibLoadInFlight` and preemption is
  safe. Saves P4.
- `specLoadWakeup` (`LSU:4205-4222`): sends a speculative wakeup from the P4 (or P3)
  flops, confirmed later by the real `wakeup` through an IQ hold.
- `fourCycleL1` (`LSU:898-903`, `2621-2635`, `IQ:885-899`): an IQ P3-preselect plus a
  live bypass of the head-response data into the next load's base operand. It
  requires all three of the above (`LSU:179-181`).

### 3.4 Out-of-order load issue (`lsOooIssue` / IQ `loadBypassUnreadyLoad`)

**IQ policy** (`IQ:730-884`). LS select is `OHMasking.first(eligible & lsReady)`. A
slot is `eligible` when **all** of the following hold:

- it is the oldest LS slot (always eligible); or it is a non-store LS slot with no
  older store of any readiness present in the IQ (`olderStorePresent`, a prefix-OR,
  `IQ:735-843`);
- and `intraMacroOk`: no older LS slot is unready, unless this slot is `firstOfInstr`
  (`IQ:844-879`).

Consequences:

- Stores issue only as the oldest LS slot.
- Loads can pass only older **loads**, never an older store. A store that has already
  issued is ahead in the in-order LS pipe, so it allocates in the SQ at P3 before any
  younger load reaches P3.
- So the forward/stall check always sees every older store, and **there is no
  memory-dependence speculation, no prediction and no load–store violation
  detection**. The single exception is the device-ordering hazard below.

**Device-ordering hazard.** Cacheability is unknown at issue. A younger cacheable load
can therefore reach the ring before an older op that turns out to be INHIBITED. Three
mechanisms handle this:

1. **Park buffer** (`LSU:1596-1614`, `2762-2807`, `3831-3872`). Four entries
   (= `loadRingDepth`), random access.
   - Admit: P4 holds an inhibited, non-split, non-head op and a park slot is free
     (`LSU:4150`). P4 is freed the same cycle.
   - Drain: `parkHitR(i)` is a registered "slot i is the ROB head" compare
     (`LSU:3840`). The drain reuses the ring push `alignedEnq` with the parked payload
     (`LSU:2831`, `3989-3997`) and takes over the SQ barrier query for the head robId
     (`LSU:3864`). P4 launch is blocked while the park owns the barrier.
   - `parkSaw(i)` is sticky: it is set whenever the ring sends a command that is not
     older than the parked op (`LSU:2783-2797`).
   - Flush drops every parked entry (`LSU:2806`).
2. **Order-violation recovery** (`LSU:4256-4287`). `lsOooViolation` fires while P4
   holds an inhibited op and any valid, unpoisoned ring entry is not older than it.
   `parkViolation` is reported at drain when `parkSaw` is set. The ROB records
   `orderViolated` per entry. When that macro's last uop retires, `orderRedirect`
   flushes everything younger and restarts at `p1.pc` (`ROB:3505-3507`, `1436-1500`,
   `2379`). No send is ever held.
3. **Liveness replay** (`LSU:4038-4073`). `replayArm` is registered from the previous
   cycle. It is set when P4 is waiting, a strictly older LS op is upstream (S1, P2,
   P2T or P3), the ROB is non-empty, and either:
   - "certain": inhibited, not the head, and split or park full; or
   - `stallCnt` has saturated at 255.

   P4 then completes with a throw-away result plus `compIsReplay`, which drives
   `replayRequest`. The ROB never retires it. At the head the ROB flushes and restarts
   at that op's PC (`ROB:1353-1368`, `3537-3550`).

**Intra-ring ordering.** Ring completion stays in order. Out-of-order data return
(`ooOk`, `rid`) and out-of-order PRF writeback (`alignedEarlyWb*`) exist
independently of `lsOooIssue`. They depend on the non-blocking D-cache returning
responses out of order.

### 3.5 Memory-dependency tracker (dead code)

`MemoryDependencyTracker` (`MDT:86-212`) is a standalone 8-slot table. It has:

- per-slot `occupied`/`canceled`/`committed`/`irreversible`/`known`/`dataReady`/
  `stores`/`serial` bits;
- a 32-bit generation counter per slot (`MDT:119`);
- two 16-byte fragments per slot (`MDC:6-9`);
- a pairwise allocation-order matrix (`precedes`, N(N−1)/2 flops, `MDT:136-147`).

`MemoryDependencyCheck` (`MDC:29-56`) grants `allowMemory` only when no older record
is unknown, serial or overlapping. **Neither is instantiated** (§1). Their only use is
`DispatchPlugin`'s optional `memoryOrder` hook, which is empty in shipping. They do not
contribute to the shipping netlist.

### 3.6 Store queue

**Geometry and fields.** Depth `sqDepth = 8` (`SCC:400`), a power of two between 4 and
16 (`LSU:173`). Per entry (`SQ:199-347`):

| Field | Width / type | Notes |
|---|---|---|
| `valids`, `committed`, `dataReady` | 1 each | `dataReady` exists only with `reserveLateStore` (ON) |
| `robIds` | 5 | — |
| `paddrs`, `datas`, `vaddrAs` | 32 each | — |
| `sizes` | 2 | — |
| `useStrbAs` | 1 | — |
| `maskAs` | 16 | byte-lane mask within the line |
| `validBs` | 1 | — |
| `paddrBs` | 32 | — |
| `maskBs` | 16 | — |
| `vaddrBs` | 32 | written, never read in RTL; only `simPublic` |
| `cacheModes`, `cacheModesB` | CacheMode each | — |
| `supervisors`, `precises`, `orphans` | 1 each | — |

That is about 245 flop bits per entry, about 1.96 kbit in total, plus pointers
(`head`, `tail`, `sendPtr`, `sendPhaseB`, `ackPhaseB`, `acceptedHalves`). Strobes and
line data are re-derived at drain time from `paddr[3:0]`/`size`/`data`
(`SQ:552-559`, `576-597`).

**Allocation** (`SQ:1195-1226`). One entry per cycle at `tail`. Each half is confined
to one 16-byte line, and a split store is one entry with two halves. The masks come
from `spanMaskOfCount` (`SQ:378-390`). The alloc `Flow` has no ready; the LSU gates it
on `!sq.io.full` (`LSU:4583`). `full` is `CountOne(valids) == depth` (`SQ:1567`).

**Late store data** (`reserveLateStore`, `detachLateStore`, `forwardOnPublish`,
`detachedStoreEntries = 4`, `earlyAutoStoreAddress`, `earlyStoreDataWake` — all ON):

1. With `IQ.earlyStoreAddress`, a store may issue when its address operands are
   ready, even if its data is not (`IQ:661-669`).
2. At P3 the store reserves an SQ slot (`alloc` with `reserveOnly`, so `dataReady = 0`)
   and gives up P3 into the `detachedStore` owner (`LSU:4563-4574`, `4499-4525`).
3. The owner (`LSU:3624-3740`) holds one active context plus a three-deep
   `StreamFifo`.
4. When `LateStoreDataService` reports the source ready (a registered `readyPrior`;
   with `earlyStoreDataWake` this uses `queryReadyNext`), the owner borrows `rdData`
   (`LSU:3655-3659`) and `publish`es the data into the slot (`SQ:1175-1180`).
5. It then completes through the shared `comp*` stage. With `earlyAutoStoreAddress`,
   this includes the POSTINC An result.

While the data capture uses `rdData`, `issuePort.ready` is forced low (`LSU:4875`).

**Commit.** Each cycle, all retire-slot robIds are compared against all 8
`robIds` (`SQ:1166-1171`).

**Drain to the D-cache** (`SQ:493-541`, `1299-1346`). The send cursor `sendPtr`
presents halves in order, and an entry pops only on its terminal ack. There are three
drain modes:

| Mode | Applies to | Condition | Effect |
|---|---|---|---|
| Pipelined | non-precise COPYBACK or WRITETHROUGH | `valid && dataReady && committed` | Back-to-back halves without waiting for acks; `acceptedHalves` counts outstanding ones. |
| Precise | — | `sendPtr == head`, the entry is **not** committed, `robIds(head) === robHeadIn`, no IRQ/trace preemption armed, no accepted halves, plus with D4 `inhibLaunchOk` for an inhibited head | Drains at the ROB head **before** retirement. Its ROB completion is the `pendMem` replay after the ack (`LSU:4959-5098`). |
| Serial | other non-precise entries | at head, nothing outstanding | — |

So the drain rate is at most one half per cycle. A split store needs two halves.
Stores pop one per terminal ack, and coalescing is OFF.

**Forwarding** (`SQ:834-1161`). Per entry:

- `ent = valid && (committed || olderThan(robId, q.robId))`, where `olderThan` is a
  subtraction relative to the ROB head (`SQ:449-453`).
- Overlap is a 28-bit line equality plus a mask AND. There are four line compares per
  entry: entry A/B against query own-line/second-line.
- `full` needs:
  - no B half, the same line, and `maskA == qSpan`; or the subword case (an aligned
    LONG store containing a BYTE, or a WORD at offset ≤ 2 within the long);
  - and `hasData || publishing` (`SQ:873-885`).
- The youngest overlapping entry is chosen by distance from the SQ head
  (`ageDist`, `reduceBalancedTree`, `SQ:1091-1110`).

Outputs:

- `hit = best.full && !serialStall`.
- `stall = (anyPartial || anySameLine) && !fullValid` (`SQ:1161`).
- `serial = (q.inhibited && anyOlder) || anyOlderInhibitedStore` (`SQ:1134-1159`).

`anySameLine` is true when any older entry sits on the same 16-byte line as either
half of the query with a **non-COPYBACK** cache mode, *whatever the byte overlap*
(`SQ:988-995`). A load to the same line as an older WRITETHROUGH or INHIBITED store
therefore stalls until that store drains. Under MMU-off, the DTLB returns
WRITETHROUGH (`DTLB:552-553`).

Partial overlaps and split loads with a hit become `stall`/`mustRetry`, so P4 spins
until the store drains. A split load is never forwarded (`LSU:4079`). Inhibited loads
never forward: `p3/p4SqForwardAllowed` (`LSU:2005-2012`).

**Barrier query** (`SQ:1594-1599`). `olderStore` means any committed or older valid
entry. `olderInhibitedStore` is the subset with an inhibited half. The robId is P4's,
or the head robId when the park drains (`LSU:3864`).

**Flush** (`SQ:1516-1550`). The flush keeps committed entries, plus an uncommitted
precise head whose drain is already accepted; that head is marked `orphan`.
`tail = headAfter + CountOne(keep)`, which assumes kept entries are contiguous from the
head. `flushKeptPrecise` and `sqCompletionOrphan` keep `pendMem` in lockstep
(`LSU:4986-5016`, `5049-5097`).

### 3.7 Cache-inhibited (device) accesses and the D4 full barrier

**Loads** (`LSU:3780-3783`, `3977-3979`):

- `p4LaunchOk` for an inhibited op requires all of: `p4AtRobHead`, `!olderStore`,
  `p4PreemptSafe` (no armed IRQ/trace and no imminent debug halt, `LSU:3898`), and
  with D4 `dLaunchOk`.
- `dLaunchOk = busQuiesced && dFenced && both port owners are CORE` (`LSU:3957`).
  `dFenced` is a register that goes high one cycle after the op becomes pending at the
  head, so it adds at least one cycle (`LSU:3969-3972`).
- For an ordinary load, launch requires `!olderInhibitedStore`, and with D4 also
  `!parkOwnsBarrier && !inhibLoadInFlight`.
- With D4, `inhibLoadInFlight` (`LSU:4407`) blocks every younger launch from P4 or
  the park until the inhibited load's terminal response.

**Stores**: always precise (`fastStore` is false for INHIBITED). They drain at the head.
With D4 they also need `inhibLaunchOk` (`SQ:537-539`).

**Interrupt interlock.** `loadBusyReg`/`inhibitedLoadBusySig` is held from the launch
of an inhibited load until its robId leaves the ROB head (`LSU:4349-4400`).
`preciseDrainBusy` is the store-side equivalent (`SQ:1411-1430`).

**Walkers.** Table walkers are fenced (`d4WalkerFence`) from new grants while a device
access is pending or in flight (`LSU:5527-5529`, `5559`).

### 3.8 Early An writeback and retry

`earlyAutoAnWriteback` (ON) covers LEA, stack push and (An)+/−(An) store uops. Their
integer result is `s1Va` or `s1AnWb`, available at P1 (`LSU:3132-3138`).

- **Without `earlyAnRetry`:** the write and the wake fire at P1 only if the completion
  write and the completion wake are both idle *that cycle*. Otherwise the uop falls
  back to its ordinary completion writeback (`LSU:3146-3152`).
- **With `earlyAnRetry` (ON in shipping):** a one-entry register
  (`rValid`/`rWritten`/`rPdst`/`rData`/`rRobId`) parks the candidate at P1. It writes
  when the completion write port is idle, then wakes when the wake port is idle, at or
  after the write. It is cleared by its own completion, by flush or exception, or once
  both are done (`LSU:3167-3188`).
  - A new candidate is accepted only if the entry is empty or waking this cycle
    (`park`). A second early-An uop arriving while the first is still pending falls
    back to completion.
  - The write is idempotent with the later completion write: the same value to the
    same pdst, through the same shared port key.

### 3.9 Locked RMW, MOVE16, MOVEM

There is **no lock or atomic primitive** in the LSU or `DStoreCmd`:

- TAS is decoded as an ordinary memory RMW (`OperationDecoder.scala:416-427`).
- CAS is a 4-row microcode sequence: load, compare, conditional, store
  (`Microcode.scala:2408`).
- MOVE16 is a microcode sequence starting at row 242 (`Microcode.scala:2417`).

All of these reach the LSU as ordinary LONG/BYTE uops. Atomicity relies on a single
bus master (the CPU) plus in-order SQ drain. No line-burst path exists for MOVE16.

MOVEM loads and stores are one uop per element. For loads there are two special cases:

- the far-page translate-ahead probe (§3.2);
- sign extension for `.W` (`signExtW`, `LSU:2374-2375`, `2424-2425`).

MOVEM.L walking a misaligned base produces mixtures of split and non-split ring
entries. The split pair logic (`alignedSendHeld` using `alignedRspPtr =/=
alignedSendPtr`, `LSU:1673-1681`, and the abort, `LSU:2689-2737`) supports this.

### 3.10 Faults and restart

| Fault | Path |
|---|---|
| DTLB fault | Completes at P2T through `captureFaultFront`, with the slot B address if this is the second translation (`LSU:4757-4758`). Drives `faultCompletion` (vector 2, `atc = 1`). |
| Load refill bus error | Delivered on the ring response (`alignedRspFault`) through `captureFaultDesc` with `atc = 0` (`LSU:3325-3327`). A slot A fault aborts the pair, and slot B is never sent (`LSU:2689-2737`). |
| Precise store bus error | `sqFaultCompletion` at the terminal ack (`SQ:1328-1338`). `faultAddr` is always `vaddrAs(head)`, i.e. slot A (`SQ:1292`). |
| Non-precise (cacheable) store error | `drainErr` is **ignored**. `terminalAck` uses `drainErr` only when `precises(head)` (`SQ:1299-1300`, `1330`). |
| Privilege | User-mode `needsSupervisor` stores never allocate (`storePrivBlocked`). Load faults are suppressed so the ROB raises vector 8. |
| Flush | All unlaunched stages are invalidated (`LSU:4937-4946`). Launched ring entries are poisoned and drained (`LSU:2757-2761`). `xlateEpoch` toggles. Every probe is cancelled (`probeCancelAll`, `LSU:2149`). |

---

## 4. Configuration knobs

`SHIPPING_CONFIG` comes from the build log. LSU knobs are passed through
`SP:49-60` (`lsEuKnobs`) with `ipcThroughput = true` and `ipcLateStore = true`
(`CPU_IPC_PROFILE` defaults to `throughput-v2`, `ST:670`). `LS_OOO_ISSUE=1` was set in
the shipping build's environment (`ST:615`).

| Knob (LsEuPlugin param) | Ctor default | Shipping | Effect |
|---|---|---|---|
| `alignedLoadFallThrough` | false | **true** | P4 command in the same cycle as the push (§3.2). −1 cycle/load. |
| `earlyIntWakeup` | false | **true** | `wakeup` at capture, one cycle before the PRF write. |
| `earlyNzvcWakeup` | false | **true** | Same, for NZVC. |
| `sqSubwordForwarding` | false | **true** | BYTE/WORD forward out of an aligned LONG store. |
| `reserveLateStore` | false | **true** | SQ reservation without data. |
| `detachLateStore` | false | **true** | The detached owner frees P3. |
| `forwardOnPublish` | false | **true** | Forward on the publish cycle. |
| `detachedStoreEntries` | 1 | **4** | Detached contexts: 1 active + 3 FIFO. |
| `earlyAutoStoreAddress` | false | **true** (late store) | The POSTINC An result rides the detached completion. |
| `earlyStoreDataWake` | false | **true** (late store) | Capture qualified on `queryReadyNext`. |
| `earlyAutoAnWriteback` | false | **true** | §3.8. |
| `earlyAnRetry` | `SCC.lsEarlyAnRetry` (env, default false) | **true** | §3.8 registered retry. |
| `specLoadWakeup` | false | false | Dead in netlist. P4/P3 speculative wake (§3.3). |
| `p3FastLoad` | `SCC` (false) | false | P3 direct enqueue. Requires D4. |
| `p1EarlyLoad` | `SCC` (false) | false | P1 DTLB+probe for simple `MOVE.L (An),Dn`. |
| `fourCycleL1` | `SCC` (false) | false | Needs P1+P3+spec+fall-through+early wake. Live operand bypass. |
| `lsOooIssue` | false | **true** | Park, violation, replay (§3.4). Paired with IQ `loadBypassUnreadyLoad` (`ST:191`). |
| `inhibitedFullBarrier` (D4) | `SCC` (env, default false) | **true** | §3.7. Required by `dcacheNonBlocking`. |
| `loadRingDepth` | `SCC` = 4 | 4 | Ring and park depth, `DLoadRid` width. 8/16 allowed. |
| `sqDepth` | `SCC` = 8 | 8 | SQ ring and `pendMem` depth. |
| `sqNarrowDrainMerge` | false | false | Narrow slot-B drain encoding (`SQ:470-534`). |
| `sqCoalesceLines` / `Hold` | false / 16 | false | Four-LONG line coalescing (`SQ:714-…`). Not elaborated. |
| `walkerAgeLimit` | 64 | 64 | Walker force priority. |
| `walkerWedgeLimit` | 2^20 | 2^20 | Sticky wedge report. |

IQ knobs that shape LS behaviour (`SP:73-76`): `earlyStoreAddress = true`,
`earlyAutoStoreAddress = true`, `loadBypassUnreadyLoad = true` (shipping),
`specLoadWakeup = false`.

**Dead or OFF paths in the shipping netlist:**

- `bkFsm`/`llReg`/`bkCtx`/`bkBusy`/`bkStart`/`lineA`/`aDone`/`bkPoisoned`
  (`LSU:1371-1470`, `2323-2324`, `3406-3474`). `bkStart` is only ever assigned
  `False` (`LSU:1470`) and `bkBusy` is never set `True`, so `useSplitCmd` is constant
  `False`.
- `wakeupSpec`, `wakeupSpecP3` and `liveLoadConfirm`: constant-idle.
- `p1ReqArm`, `p3FastEnq`, `liveBaseBypass`: `False`.
- `reqStale`/`reqFresh`: constants (`LSU:2113-2114`).
- `SQ.count`: written, never read (`SQ:1558`).
- `SQ.vaddrBs`: written, never read.
- `MemoryOrderPlugin`/`Tracker`/`Check`: not instantiated.

---

## 5. Performance-relevant behaviour

- **Load-to-use.** 8 cycles on an early-probe L1 hit, 9 on a probe miss, 7 when
  SQ-forwarded (§3.3). Silicon: 8.29 cycles on L1 hits and ~21 on L2 (task brief).
  The L2 figure includes D-cache miss handling, which is outside this slice.
- **Issue throughput.** One LS uop per cycle. II=1 is sustained only while every stage
  can hand off each cycle:
  - the DTLB response must be consumed in the same cycle as the next request
    (`DTLB:428`);
  - the probe credit must be available;
  - P4 must not be retrying;
  - the completion stage must be free for stores and LEAs.
- **Completion bandwidth.** One `comp*` capture per cycle shared by: load completions
  (head or early writeback), fast stores, LEA, detached-store completions, precise
  replays and replays. A store completes in P3 and needs this stage, so a store burst
  interleaved with load responses serializes on it (`frontCompHeld`, `LSU:4552-4553`,
  `4584-4585`).
- **DTLB miss.** P2T holds the request, and the DTLB accepts nothing new while
  `missPending` (`DTLB:428`). Every younger LS op is blocked behind a TLB miss; there
  is no hit-under-TLB-miss.
- **P4 head-of-line blocking.** A load in P4 retrying on SQ `stall`/`serial` blocks
  every younger LS op. Typical causes are a partial overlap, an older same-line
  WT/INHIBITED store, or a store whose data is not yet published. `lsOooIssue` cannot
  help after the IQ: P1–P4 are in order.
- **MLP limits** (structural):
  - at most 4 aligned loads in the ring;
  - `ldFifo` of 8 response tags across all clients (`LSU:699`);
  - one translation in flight;
  - one P4 decision per cycle.

  The ring sends and completes strictly in order, but data may return and write back
  out of order. Measured MLP on silicon is 1.000 (task brief). This slice does not
  establish whether the limit is in the LSU or in D-cache miss acceptance.
- **Store drain.** At most one half per cycle presented. COPYBACK and WRITETHROUGH
  committed stores pipeline. Each pop needs a terminal ack. **With the MMU disabled,
  every store is precise:** it drains at the ROB head before completion and the ROB
  waits on the bus ack (`LSU:1317`). Stores to INHIBITED pages are always precise.
- **Early An.** With `earlyAnRetry`, an An result written at P1 lets the next
  `(An)+` access start without waiting for the previous store's completion.
- **Device reads.** Each one costs: reaching the ROB head, draining every older store,
  with D4 `busQuiesced` plus at least one fence cycle, a non-fall-through send, and
  blocking younger launches until its response.

---

## 6. Area and timing notes (from structure)

**Store queue**

- About 1.96 kbit of entry state.
- Forward CAM, per entry:
  - four 28-bit line equality compares;
  - two 16-lane mask ANDs plus 3-lane spill ANDs;
  - one 5-bit head-relative age compare (subtract + compare);
  - the `sameLine` and `inhibited` terms;
  - an 8-way balanced reduction carrying 32-bit data and a 3-bit distance
    (`SQ:1107-1110`).
- The barrier query adds a second set of eight head-relative compares (`SQ:1594`).
  Commit adds 8 × `retireWidth` 5-bit equality compares (`SQ:1166`).
- `full` is a live popcount (`SQ:1567`).

**LSU front**

- The ~170-bit `FrontPipeCtx` is replicated four times (P2, P2T, P3, P4). P4 adds 32+3
  bits of forward verdict. The ring holds four `AlignedLoadCtx` entries (≈100 bits
  each) plus 32-bit data and 128-bit line copies per entry, ≈ 1 kbit of `alignedRLine`.
- With LS-OoO, the park adds four `AlignedLoadCtx`, and
  `lsOooViolation`/`parkSaw`/`replayArm` add 4 + 4 + 4 head-relative compares
  (`LSU:2792-2796`, `4045-4048`, `4269-4272`).
- `pendMem` is 8 × ~70 bits.

**High-fanout control inputs.** Several head-relative compares in this slice take
`robHeadIn` as an input: SQ forward, SQ barrier, park, violation, replay,
`p4AtRobHead` and `launcherStillAtHead`. `sqFlushSig` reaches every stage valid.
Registered cuts already exist for these:

- `p4RetryQ` (`LSU:1239`);
- `parkHitR` (`LSU:3831-3841`);
- the `replayArm` register (`LSU:4041`);
- `applyBacklog`-only priority (`LSU:3592`);
- the registered walker load request `m2sPipe` (`LSU:585-591`);
- the exception DTLB request buffer (`LSU:5378`);
- the registered watchdog inputs (`LSU:5678-5679`).

These show which nets were critical, but this document does not quote timing figures
from comments.

**D-cache load command mux.** Up to four sources: ring/fall-through, exception,
pre-muxed walker, and the dead `llReg` leg (`LSU:1897-1915`, `5235-5300`, `5441-5468`).
The fall-through select is `!alignedFull && sendPtr === pushPtr`, which uses only
registers (`LSU:1836-1838`).

---

## 7. Observations

These are factual pointers for a reviewer. "Candidate" marks something unverified.

### 7.1 Load-to-use

1. **Five register stages between operand capture and cache launch** (P1, P2, P2T, P3,
   P4, §3.3). The DTLB is looked up from P2 flops and returns registered (P2T). The SQ
   CAM runs in P3 and its verdict is registered into P4 before launch. Candidates:
   - query the SQ in parallel with the DTLB using the untranslated page offset (12
     bits), then confirm with the PPN;
   - or launch speculatively from P3 and cancel on a late SQ hit.

   `p3FastLoad` exists, but its `sq.io.empty` condition (`LSU:4607`) makes it almost
   never applicable in store-dense code. A gate on "no SQ overlap verdict this cycle"
   would be the natural relaxation.
2. **The AGU add is separate from operand read** (S0 → `s1Base` register → P1 add,
   `LSU:902`, `988`). A base that comes from the PRF (not a same-cycle bypass) could be
   added in S0. `p1EarlyLoad` attacks the following stage instead.
3. **The IQ's registered dynamic-wait clear** costs one cycle per dependent link
   (`IQ:202-209`). `specLoadWakeup` and `fourCycleL1` are the existing attempts; both
   are OFF.
4. **The extra S1 RAM cycle on a probe miss** costs one cycle. The early probe needs
   `loadProbe.ready` at P2 (`LSU:4790`) and is cancelled on any walker hand-over
   (`LSU:2149`).

### 7.2 Correctness or suspicious code

1. **Non-precise store bus errors are silently dropped** (`SQ:1299-1300`, `1330`).
   Only precise stores report `drainErr`.
2. **Precise split-store fault address** is always slot A (`SQ:1292`). `vaddrBs` is
   stored but unused. Whether the 68040 reports the slot B address for a fault on the
   second half was not checked here.
3. **The same-line non-COPYBACK stall has no byte-overlap condition** (`SQ:988-995`).
   This is functionally conservative but costs performance for WRITETHROUGH pages,
   including the MMU-off default (`DTLB:553`).
4. **`pdst` widths are hard-coded to `UInt(6 bits)`** throughout the LSU (for example
   `LSU:355`, `1118`, `2224`, `3118`) instead of being derived from the physical
   integer register count (54 → 6 today).
5. **The flush tail computation** (`SQ:1547`) assumes kept entries are contiguous
   from `head`. That holds only if commit marking is in order. Commit is a CAM over
   all entries by robId (`SQ:1166-1171`). No ordering invariant check was found.
6. **Late-data capture steals `rdData`** and stalls LS issue for that cycle
   (`LSU:4875`, `3656`, `4468`). The stall is a performance cost, not a bug.
7. **Order-violation recovery is conservative** (`LSU:4268-4273`). It flushes when any
   younger ring entry is resident, including ones that have already completed and are
   harmless.

### 7.3 Dead logic and redundancy

- The `bkFsm` split-replay machine and its state (`LSU:1371-1470`, `2323-2324`,
  `3406-3474`), plus the `useSplitCmd` mux arms (`LSU:1897-1937`). They are constant
  after elaboration and synthesis should prune them, but they obscure the live path.
- `reqStale`/`reqFresh` (`LSU:2113-2117`), `SQ.count` (`SQ:351`, `1558`),
  `SQ.vaddrBs`, `drainPhaseB` alias (`SQ:311`).
- `MemoryOrderPlugin`, `MemoryDependencyTracker` and `MemoryDependencyCheck` are
  unused. If revived, note the 32-bit generation per slot (`MDT:119`); a few bits
  would do.
- `captureBkCtx` produces `wakes`, which the ring copies per entry. `wakes` is
  recomputable from `pdstValid`/`ccrRestore` for ring entries, because ring entries
  are loads.

### 7.4 Cheap IPC, area and congestion candidates

- The completion stage is one wide port shared by six producers. Stores need only a
  ROB completion plus optional NZVC/An writes. A separate store-completion port would
  remove `frontCompHeld` serialization (`LSU:4552`, `4584`). Cost: a second
  ROB completion input.
- Per-entry `alignedRLine` (128 bits × 4) is needed only for split pairs, and split
  pairs are never `ooOk`. The parked response for a non-split entry needs only
  `alignedRData`, so `alignedRLine` could shrink to one register (`splitMergeLine`
  already exists).
- `SQ.full` popcount (`SQ:1567`) could be a counter; the dead `count` register is
  already maintained.
- Forward `ent` uses head-relative subtraction per entry (`SQ:449-453`, `838`). The
  ring index order relative to SQ `head` already encodes program order among entries.
  Only the query's position relative to the entries is unknown, and a stored "SQ tail
  at issue" per load would replace eight subtractors with an index compare.

### 7.5 Throughput and MLP

- One translation outstanding (`DTLB:428`). No TLB miss-under-miss.
- Ring depth 4, in-order send, single P4.
- Every inhibited load blocks all younger launches until its response (D4).

### 7.6 Docs and comments that disagree with the code

| Source | Says | Code |
|---|---|---|
| `LSU:99-112` (class doc) | A two-stage S0/S1 pipe with completion at S1 | P1–P4 plus ring. |
| `LSU:1110-1112` | "four-entry" ring | Parametric (`loadRingDepth`). |
| `ST:175-178` comment | LS-OoO off in every ordinary build | It is env-driven, and shipping sets it. |
| `SCC:465` doc | `inhibitedFullBarrier` "OFF pending measurement" | Shipping sets it. |
| `SQ` `olderThan` comment (≈`SQ:411-448`) | A 64-entry ROB | `ROB_DEPTH_DEFAULT = 32` (`Global.scala:34`). |
| `docs/ls-hit-latency.md` | "`baseline` remains the default" | Default profile is `throughput-v2` (`ST:670`). |
| `docs/memory-dependencies.md` | Tracker "not enabled" | Consistent with the code: not instantiated. |
| `docs/LS_OOO_PERFORMANCE_REFERENCE.md` | Production default OFF | True only for the env default; this shipping build has it ON. |
