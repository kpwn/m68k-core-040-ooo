# 08 — L1 data cache and the D-side AXI socket

Scope: the L1 data cache (`DcachePlugin`) and everything between it and the SoC pins:
the merge arbiter on `axi_d`, the read-only "hot door" `axi_dh`, the reset absorbers,
byte-order permutation and the cache-inhibited (MMIO) access splitter.

Source tree: commit `fee95f6f` (the bitstream currently on the board).

**How this document was written.** Every claim here was checked against the RTL, not
against the source comments. `DcachePlugin.scala` is 6,732 lines, and about 3,660 of
them are comment lines. Those comments hold design history, measured numbers and
rationale, and several are now stale (see §7.1). Where this document gives a cycle
count, it was worked out from the register stages in the code, and it says so. No
simulation or synthesis was run for this document. Numbers labelled "derived" should
be confirmed in simulation before anyone relies on them.

## File legend (all paths relative to the repo root)

| Short name | Path |
|---|---|
| `DP` | `src/main/scala/m68k040/cache/DcachePlugin.scala` |
| `DT` | `src/main/scala/m68k040/cache/DcacheTypes.scala` |
| `CG` | `src/main/scala/m68k040/cache/CacheGeometry.scala` |
| `IDS` | `src/main/scala/m68k040/cache/AxiIds.scala` |
| `MRG` | `src/main/scala/m68k040/socket/AxiDMerge.scala` |
| `MRGP` | `src/main/scala/m68k040/socket/AxiDMergePlugin.scala` |
| `ABS` | `src/main/scala/m68k040/socket/AxiReadResetAbsorber.scala` |
| `SAXI` | `src/main/scala/m68k040/socket/SocketAxi.scala` |
| `SBO` | `src/main/scala/m68k040/socket/SocketByteOrder.scala` |
| `SHRB` | `src/main/scala/m68k040/socket/SocketHotReadBoundary.scala` |
| `MMIO` | `src/main/scala/m68k040/socket/MmioCover.scala` |
| `ST` | `src/main/scala/m68k040/top/SocketTop.scala` |
| `SCC` | `src/main/scala/m68k040/top/ShippingCoreConfig.scala` |
| `SP` | `src/main/scala/m68k040/top/ShippingPlugins.scala` |
| `FCS` | `src/main/scala/m68k040/top/FullCoreSynth.scala` (holds `BackendWiringPlugin`) |
| `LSU` | `src/main/scala/m68k040/execute/LsEuPlugin.scala` |
| `EXC` | `src/main/scala/m68k040/exception/ExceptionUnit.scala` |

---

## 1. Purpose and position

```
   LsEuPlugin (LS pipe)            StoreQueue (8 entries)      ExceptionUnit      ITLB/DTLB walkers
   loadProbe / Resolve / Cancel    drain (post-commit)         frame loads/stores  descriptor R / U-M W
   loadCmd  <-> loadRsp            store <-> storeAck          maintCmd (CPUSH/CINV)
        \___________________ all multiplexed inside LsEuPlugin ___________________/
                                         |
                               DcachePlugin  (DcacheService)
                 8 KiB, 4-way, 16 B lines, VIPT, copyback/writethrough/inhibited
                    |                                         |
     axi  (128b, ID 4b, R+W)                         axiDh (128b, ID 2b, read-only)
     inhibited reads, all writes                     cacheable refills (MSHR k -> ID k)
                    |                                         |
          AxiDMerge (MRG) + reset-vector reader       SocketHotReadBoundary (SHRB)
                    |                                         |
     register slice (FULL, all 5 channels)            register slice (AR, R) + absorber
     byte-lane permute (W data/strb, R data)          byte-lane permute (R data)
     read reset absorber                                      |
                    |                                         |
                 axi_d pins ---------- SoC L2 / DDR -------- axi_dh pins
```

- **Clients.** `DcachePlugin` implements `DcacheService` (`DT:297-367`). The only plugin
  that calls it directly is `LsEuPlugin` (`LSU:419`, `host[DcacheService]`). The other
  clients go through `LsEuPlugin`'s multiplexers on the same ports:
  - the commit-side exception sequencer: loads with token `0x80` (`LSU:5246-5300`) and
    stores (`LSU:5303-5307`);
  - both MMU table walkers: tokens `0x81`/`0x82` (`LSU:5441-5466`) and U/M descriptor
    stores (`LSU:5477-5486`);
  - the StoreQueue drain (`LSU:783-784`).

  The maintenance command and the quiesce handshake are wired in
  `BackendWiringPlugin` (`FCS:565-669`). The core halts if `diagFault` is set
  (`FCS:610-622`).
- **Walker traffic is ordinary D-cache traffic.** Walker descriptor reads use cache mode
  WRITETHROUGH (`src/main/scala/m68k040/mmu/DtlbPlugin.scala:141,153`). They are looked
  up in the L1D and are refilled into it, so walks see dirty copyback data. The
  walker-specific AXI IDs are reserved but never emitted (`IDS:106-109`). In
  `AxiDMergePlugin` the walker ports of the merge arbiter are tied off (`MRGP:55-61`).
- **Downstream.** The SoC L2 is outside this repository. The core assumes nothing about
  it beyond AXI4 per-ID ordering. It does enforce its own read-after-write ordering
  between the two physical ports (§3.8).

## 2. Interfaces

### 2.1 Services and `Global` keys

- **Produced:** `DcacheService` (`DT:297`), implemented at `DP:6714-6731`. Its members:
  `loadProbe`, `loadProbeResolve`, `loadProbeCancel`, `loadCmd`, `loadRsp`, `loadBusy`,
  `store`, `storeAck`, `storeCompletion`, `storeErr`, `diagFault`, `maintCmd`,
  `maintDone`, `maintError`, `maintQuiesced` and `busQuiesced`.
- **Consumed:** `MmuControlService` (optional, `DP:4142-4143`), used only for the TCR.P
  8 KiB page flag in page-scope maintenance.
- **`Global` keys:** none. `DcachePlugin` neither reads nor writes `Global`.
- **Exported but unused in the shipping build:**
  - `storeCompletion` (`DP:5585-5603`). It is built when `nonBlocking` is set, but
    nothing in `src/main` reads it.
  - `loadBusy`. `BackendWiringPlugin` wires it into `ExceptionUnit.dcLoadBusy`
    (`FCS:569`), but `ExceptionUnit` only declares and defaults that signal
    (`EXC:140,160`) and never reads it.
  - `DLoadRsp.residentHit`. It is read only under `fourCycleL1` (`LSU:2621-2622`), and
    `lsFourCycleL1=false` in the shipping build.
  - `AxiDMergePlugin.logic.dbgArbPack` and `dbgStallDcPack` (`DP:4028-4056`,
    `DP:5799-5809`). These exist only for debug-register visibility.

### 2.2 Request and response bundles (`DT`)

| Bundle | Fields | Notes |
|---|---|---|
| `DLoadProbe` (`DT:85-95`) | vaddr, token(8), resolved, paddrHint, size, cacheMode, needsLine | Starts a virtual-set array read before translation. The shipping build sets `allowPretranslatedProbeHints=false` (`SP:88-94`), so `probeReadUsable` is always False (`DP:2888-2892`). A probe can hit only through the resolve port. |
| `DLoadProbeResolve` (`DT:113-117`) | token, paddr, cacheMode | The registered DTLB result. It must land in the same cycle as the probe's BRAM output (`DP:1275-1278`), otherwise the probe entry is marked unusable. An INHIBITED resolve retires the entry (`DP:5378-5386`). |
| `DLoadProbeCancel` (`DT:99-102`) | token, all | |
| `DLoadCmd` (`DT:127-208`) | vaddr, paddr, size, cacheMode, token, lineOnly, ooOk, rid(log2 ringDepth = 2), ridValid | The resolved load. `lineOnly` is used only by a simulation assertion. `ooOk=0` makes the command serializing under `nonBlocking` (§3.6). |
| `DLoadRsp` (`DT:214-240`) | data(32), line(128), fault, residentHit, token, rid, ridValid | `fault` signals only a physical AXI read error (`DP:1908-1910`). |
| `DStoreCmd` (`DT:252-264`) | paddr, data(32), size, useStrb, strb(16), lineData(128), cacheMode, precise | Both the size-based and the strobe-based forms travel through all four store stages (§7.4). |
| `CacheMaintCmd` (`DT:276-282`) | push, invalidate, scope(2), sel(2), addr | `scope`: 01 line, 10 page, 11 all. `sel`: 01 DC, 10 IC, 11 both. |
| `DStoreAck` (`DT:291-294`) | count(2), err(2) | Has no consumer. |

Token encoding (`DT:29-43`): bit 7 = 0 means an LS-pipe token, `{split, robId[5:0]}`.
`0x80` is the exception sequencer, `0x81` the ITLB walker and `0x82` the DTLB walker.

### 2.3 AXI ports and ID allocation

**`DcachePlugin.logic.axi`** (`DP:437,502`): 32-bit address, 128-bit data, 4-bit ID,
full read/write. In the shipping build (`socketMerged=true`, `ST:129`) it is
directionless and is connected to `AxiDMerge.io.dc` (`MRGP:40`).

**`DcachePlugin.logic.axiDh`** (`DP:506-507`): read-only, 128-bit, 2-bit ID. It exists
only when `hotDoor` is set. It goes through `SHRB.connect` (`ST:374-376`) to the
`axi_dh` pins (`ST:228-230`).

IDs as the code actually emits them:

| Traffic | Port | ID | Code |
|---|---|---|---|
| Cacheable refill, MSHR k (hot door) | `axiDh` | k (0..3, 2 bits) | `DP:6380` |
| Cacheable refill, MSHR k (no hot door; not shipping) | `axi` | `dRefill(k)` = k | `DP:6393-6397` |
| Inhibited read, and legacy refill | `axi` | `dRefill(0)` = 0 | `DP:3476` |
| WT / inhibited / precise store write | `axi` | `D_STORE` = 1 | `DP:5448` |
| Dirty-victim eviction (legacy `EVICT_WR` and the nb write-back buffer) | `axi` | `D_PUSH` = 2 | `DP:3313`, `DP:6427` |
| CPUSH maintenance write-back | `axi` | `D_EVICT` = 4 | `DP:4299` |
| Reset-vector read (`AxiDMerge` owner 3) | `axi_d` | `RESET_VEC` = 5 | `MRG:229` |
| Walker R/W (reserved, never emitted) | — | 2 / 3 | `IDS:107,109` |

**Naming defect:** the docstrings on `AxiIds.D_PUSH` ("cache-maintenance (CPUSH)
writeback", `IDS:72-73`) and `AxiIds.D_EVICT` ("eviction writeback", `IDS:74-75`)
describe each other. The code uses `D_PUSH` for evictions and `D_EVICT` for CPUSH. This
is harmless, because each B-response demux compares against the same constant its
issuer used (`DP:3327`, `DP:4316`, `DP:6440`). It is still a trap for anyone reading the
constants.

B channel: `axi.b.ready` is held True at all times (`DP:1128`). Responses are
demultiplexed by ID:
- `storeBAck`, ID 1 (`DP:5507`);
- `wbB`, ID 2 (`DP:6440`);
- legacy `EVICT_WR`, ID 2 (`DP:3327`);
- maintenance, ID 4 (`DP:4316`).

A B response with an unknown ID is silently dropped.

### 2.4 Byte order (`SBO`, `DT:369-377`)

- **Inside the core**, a 16-byte line is held exactly as the AXI beat arrives: memory
  byte `o` of the line is `line[8o+7:8o]`. The big-endian m68k view exists only in the
  load extractor `DcacheByteLane.extract` (`DT:382-460`) and the store merger
  `storeData`/`storeStrb` (`DT:466-501`).
- **At the pins**, `SocketByteOrder.permuteData` reverses the four bytes inside every
  32-bit lane (`SBO:52-72`). `permuteStrb` reverses every nibble of the strobe
  (`SBO:75-87`). They are applied:
  - on `axi_d`: W data (`ST:445`), W strobe (`ST:446`) and R data (`ST:463`);
  - on `axi_i`: R data (`ST:427`);
  - on `axi_dh`: R data (`SHRB:36`).

  Addresses, `AxSIZE` and IDs are not permuted.
- **Narrow inhibited stores:** the strobe-run derivation (`MMIO.strbRunStart/End`) runs
  on the core-side strobe, before the permutation (`DP:5292-5293`). Whether the SoC
  interprets a permuted strobe together with an unpermuted narrow `AWADDR` consistently
  is a contract with the SoC that cannot be checked from this repository.
- The extractor zeroes lanes that would wrap past byte 15 (`DT:446-447`). A load that
  crosses a line boundary must be split by the requester. The LSU splits every access
  that crosses a line, whatever its cache mode.

---

## 3. Microarchitecture

### 3.1 Geometry and storage

| Item | Value | Code |
|---|---|---|
| Capacity / ways / line | 8 KiB, 4-way, 16 B | `DP:387-388` |
| Sets | 128 (7 index bits) | `CG:33-35` |
| Offset bits | 4 | |
| Tag | `paddr[31:11]`, 21 bits | `DP:392`, `DP:1143` |
| Indexing | VIPT: index = `vaddr[10:4]`, tag = physical. `requireViptSafe(4096)` checks that index+offset is 11 bits, within the 4 KiB page offset. | `DP:389`, `DP:1141-1143` |
| Data array | `dataMem(w)`: `Mem(Bits(128), 128)`, one per way. One write port and one `readSync` port. No RAM-style attribute, so it is inferred as BRAM. | `DP:510,675,695` |
| Tag array | `tagMem(w)`: `Mem(UInt(21), 128)`, `ram_style=block` | `DP:519,676,699` |
| Valid / dirty | `validsMem(w)`, `dirtysMem(w)`: `Mem(Bool, 128)` with init. No attribute, so these are LUTRAM in practice. | `DP:537-538,677-678,703-704` |
| Replacement | `victim`: `Vec(128)` of 2-bit round-robin registers | `DP:576` |
| Runtime reset sweep | Clears valid and dirty, one set per cycle, for 128 cycles after every reset. Loads, stores, probes and maintenance are blocked meanwhile. | `DP:582-584,652-673` |

- **One shared read port.** All four arrays of each way share a single synchronous read
  address and enable (`rdSet`/`rdEn`, `DP:688-709`). That one port serves:
  - load S0 reads (`DP:3019-3038`);
  - early probes (`DP:2835-2837`);
  - shadow/replay relaunches (`DP:2975-2992`);
  - the store S1 RMW read (`DP:2339-2342`);
  - the maintenance walk (`DP:4223-4224`).

  Priority is set by "last assignment wins" in elaboration order: store base first,
  then the load FSM, then maintenance. The registered `storeClaimReg`/`storeReadOwed`
  pair gives a waiting store a turn (`DP:4808-4809`, `DP:5018`).
- **One write port per way**, shared by all sets: `wrEn`/`wrSet`/`wrData`/`wrTag`
  (`DP:587-591`). Its writers are:
  - store S3 (`DP:5245-5263`);
  - the legacy REFILL/REPLAY (`DP:3536-3577`, `DP:3732-3761`);
  - the non-blocking install (`DP:6176-6219`).

  The valid and dirty LUTRAMs have their own muxed write ports, with up to six writers
  each (`DP:608-613`).
- **No invalid-way preference.** The victim is always `victim(set)`, with no check for an
  invalid way (`DP:3053`, `DP:5208`, `DP:5897`). After a CINV, or right after reset, a
  miss can still evict a valid line while an invalid way sits in the same set.

### 3.2 Write policy by page cache mode

The cache mode arrives with each command (`DLoadCmd.cacheMode`, `DStoreCmd.cacheMode`)
from the TLB/TTR.

| Mode | Load hit | Load miss | Store hit | Store miss |
|---|---|---|---|---|
| COPYBACK | Served from the array | Allocate (MSHR) | Merge into the array and set dirty. Local ack, no AXI traffic (`DP:5245-5260`, `cbHitAckReg` `DP:5401`). | Write-allocate through an MSHR. The ack is local and fires at MSHR allocation (`DP:6089-6091`). A store whose strobe covers all 16 bytes allocates without a fill (`sNoFill`, `DP:5944`, `DP:6054`). |
| WRITETHROUGH | Served from the array | Allocate (MSHR) | Merge into the array (no dirty bit) **and** send a 16-byte AXI write with strobes (`DP:5445-5463`). The ack is the B response. | No allocate; AXI write only. Under `nonBlocking`, if an MSHR holds the line, the bytes are also merged into its buffer (`DP:6095-6101`). |
| INHIBITED | Forced miss: `ldS1Cacheable` clears every hit bit (`DP:1194-1197`). Never allocates (`DP:3525`). Handled by the legacy FSM using naturally-aligned narrow sub-transactions (§3.9). | same | The array is never touched (`stS3ArrayWrite` requires `!stS3Inhibited`, `DP:2232`). Narrow AXI sub-transactions are sent. | same |

Admission classes at the store port (`DP:2156-2161`, `DP:4835-4840`):

- **Serial.** This covers every store with `precise=1`, and every INHIBITED store. A
  serial store is admitted only when nothing else is outstanding
  (`storeOutstanding===0`), and it then blocks the port (`serialStoreInFlight`) until
  its ack. `precise = !fastStore` (`LSU:1997`). `fastStore` requires the MMU to be
  enabled and the page not to be inhibited (`LSU:1317-1320`). **With the MMU disabled,
  every store is serial**, including copyback hits: at most one store is in flight, and
  each takes at least about 4 cycles from fire to ack.
- **Pipelined WT.** A non-precise WRITETHROUGH store. Up to `MAX_WT_OUTSTANDING=4`
  descriptors may be waiting for their B response (`DP:2170`). In practice the merge
  arbiter allows only one write on the bus at a time (§3.10).
- **Copyback.** A non-precise COPYBACK store is admitted only when `wtOutstanding===0`
  (`DP:4838`).

### 3.3 Load pipeline and hit latency

Stages, counted from the cycle `loadCmd` fires (cycle t):

1. **S0, cycle t.** The IDLE arm puts the set on `rdSet`/`rdEn` (`DP:3019-3038`).
2. **S1, cycle t+1.** The registered BRAM outputs are compared with the registered
   physical tag (`DP:1195-1213`). A multi-hot match counts as a miss
   (`OneHotSafe.exactlyOne`, `DP:1212`).
3. **S2, cycle t+2.** The way-selected line is registered (`ldS2Line`, `DP:1850-1877`).
   The byte-lane extract is combinational into `loadRsp.data`, and `loadRsp.valid` is
   `ldS2Resp` (`DP:1878-1881`).

**Derived hit latency: the response is valid 2 cycles after `loadCmd` fires.**

**Early VIPT probe** (`DP:711-823`, `DP:1272-1458`, `DP:1464-1735`, `DP:2774-2896`):

- **Launch, cycle p.** The LSU offers the probe at P2, in parallel with the DTLB request
  (`LSU:2157`). The probe reserves one of 5 queue entries (`earlyProbeDepth=5`,
  `DP:733`) and reads the virtual set.
- **Cycle p+1.** The resolve port must present the translated tag in this same cycle,
  for the compare against the BRAM outputs (`probeWayMatchResolved`, `DP:1316-1329`).
  A 128-bit line is registered in `probeLineLine`.
- **Cycle p+2.** The extracted 32-bit value and the physical tag are stored in the entry
  (`DP:1446-1458`).
- **Consumption.** A later `loadCmd` is matched by token + VA (`DP:1477-1479`),
  readiness, hit, a matching physical tag (`DP:1487`), and the entry not being stale
  (`DP:1628-1630`). Such a command takes the `useEarlyProbe` arm (`DP:3002-3014`) and
  writes S2 directly. **The response is valid at t+1, one cycle earlier than a normal
  hit, and no array read is used.**
- **Stale entries.** Any array write to the entry's set marks it stale, and the flag is
  sticky (`DP:1509-1514`, `DP:1538-1545`, `DP:5392-5397`).
- **Collision hold.** If a normal S1 resolution is in flight, the command is held for
  one cycle (`earlyProbeConflict`, `DP:1731`, `DP:2957`).
- **Admission credit.** Probe admission is a registered credit, `probeReadyReg`
  (`DP:1088-1090`, `DP:5023-5029`). If the read port is taken in the launch cycle, the
  probe is dropped (`loadProbeLaunch=False`, `DP:2821-2822`). The resolved command then
  takes the ordinary S1 read.

**Load-port admission in IDLE** (`DP:2953-2958`):

```
!resetSweepBusy && !loadShadowValid && !pendingStoreMiss
  && (!maintBusyReg || probe-owned)
  && (!tokenPresent || probeOwnsCmd)
  && (!(storeReadOwed && stS1Valid) || useEarlyProbe)
  && !earlyProbeConflict
  && nbCmdReady
```

`nbCmdReady` exists only under `nonBlocking`. This expression is combinational from
`loadCmd.payload` (the CAM), so it lies on the LSU's ready cone.

### 3.4 Store pipeline (S0..S3) and store throughput

| Stage | What happens | Code |
|---|---|---|
| S0 | Payload latch: `s0Payload` is loaded on `storePort.fire` (when `s0Ready`) | `DP:2175-2178`, `DP:4843-4861` |
| S1 | Drives the base `rdSet=stS1Set`. Advances when `storeClaimReg && !loadPortReserved && !maintUsesPort && !storePipeHeld && !stS1SameLineAsS3` (and `!nbStoreHold`). | `DP:2339-2342`, `DP:4746-4754` |
| S2 | Tag compare (`stS2HitVec`). The hit line is captured, with a bypass from a same-line S3 write (`DP:2313-2315`). A COPYBACK miss latches `pendingStoreMiss` together with the victim snapshot. | `DP:2269-2321`, `DP:5162-5240` |
| S3 | Byte merge (`DP:2220-2231`) and array write. Either a local ack, or the AXI write kick-off: `stAwDone/stWDone := False`, or `pendingWtKickoff` when another writer has an open AW/W pair. | `DP:5244-5347` |

- **`storePipeHeld`** (`DP:4577-4578`) is the OR of five terms:
  - `storeMissBarrier`;
  - `storeMissDiscovered`;
  - `loadMissStoreBarrier`;
  - `loadMissDiscovered`;
  - `wtKickoffBusy`, which is true while any non-copyback store sits in S2/S3, or while
    a store's AXI write is not yet accepted (`DP:4574-4575`).

  WT and inhibited stores therefore do not overlap inside S0..S3. A cacheable load miss
  freezes S0/S1 for the cycle in which it is discovered.
- **Same-line hold, `stS1SameLineAsS3`** (`DP:4620-4621`). An S1 store whose line equals
  the store being written in S3 waits. Worked from the register equations: in a burst
  of copyback stores to one 16-byte line (sequential `move.l` to a stack or buffer), a
  third store in S1 waits behind the store in S3, and again behind the next one when it
  reaches S3. The burst runs at about 3 stores per 5 cycles, not 1 per cycle (derived,
  not simulated). S2 already has an S3 bypass (`stS2UsesS3Line`), and a one-cycle-late
  copy of the S3 write already exists (`stS3WriteD1`, `DP:2241-2254`). Extending the
  bypass to cover the S1 case would remove this hold (§7.5).
- **Minimum latency from fire to ack.** Without contention, a copyback hit fires at t,
  is in S0 at t+1, S1 at t+2, S2 at t+3, and acks from S3 at t+4. An `loadProbe.valid`
  at t+1 takes the read port, unless the store is owed a turn (`DP:5018`).
- **Acks** (`DP:5575`): `storeAck = (storeBAck && stSubLast) || cbHitAckReg ||
  storeAllocAckReg`. A simulation assertion requires that at most one of these fires
  per cycle (`DP:5630-5633`).
- **Bus errors:**
  - a precise store reports its error through `storeErr`;
  - a non-precise WT store latches the first error into the sticky `diagFault`
    (`DP:5683-5731`), which halts the core (`FCS:610`).

### 3.5 Legacy load FSM (IDLE / EVICT_WR / REFILL / REPLAY)

`fsm` (`DP:2533-3815`) is the original single-MSHR blocking miss engine:

- **IDLE** accepts commands, launches probes, captures the one-entry shadow, and detects
  misses (`DP:3042-3181`).
- **EVICT_WR** writes back the dirty victim with ID 2 (`DP:3295-3356`).
- **REFILL** issues one AR and installs the line (`DP:3432-3666`).
- **REPLAY** relaunches the read, sends the inhibited or fault response, or merges the
  write-allocate (`DP:3668-3814`).

**Under the shipping `nonBlocking=true`, this FSM serves only INHIBITED loads:**

- The miss arm is `loadMissDiscovered && ldS1Cmode === INHIBITED` (`DP:3042-3043`).
- The store-miss pickup arm is the literal `False` (`DP:3182`).
- `evictThis` requires `ldS1Cmode =/= INHIBITED` (`DP:3144`), so `EVICT_WR` cannot be
  entered. A simulation assertion checks this (`DP:6561`).
- The cacheable REFILL allocate and the REPLAY write-allocate merge each carry a
  simulation `assert(False)` under `nonBlocking` (`DP:3533-3535`, `DP:3729-3731`).

The consequences for the shipping build are covered in §4.2 and §7.2.

### 3.6 Non-blocking L1D (`nb` Area, `DP:5826-6710`) — the shipping miss path

**MSHR file** (`nMshr=4`, `DP:5841-5856`). Each entry holds:

| Field | Meaning |
|---|---|
| `st` | State: FREE, WAIT_AR, ARQ, WAIT_R, FILLED, LINGER (`DP:5831`) |
| `line` | Line address, 28 bits |
| `eset` / `eway` | Target set and way |
| `rbuf` | Refill data, 128 bits |
| `mbuf` / `mstrb` | Store merge data (128) and its strobe (16) |
| `mdirty` | Install dirty |
| `fault`, `invPend`, `byStore`, `linger` | Status |
| `settled`, `blocked`, `instWait` | Bookkeeping |

**Load-miss staging** (`DP:5883-5904`). A cacheable S1 miss registers its request and its
victim snapshot (`stgV*`, taken from the same S1 read) into `stg*` one cycle later.
Under `nonBlocking` the legacy FSM stays in IDLE and keeps accepting commands. Only an
inhibited miss captures the legacy shadow (`DP:2993-2994`).

**What happens to a staged miss in the `stg` cycle** (`DP:5951-5975`):

- **Secondary** (`lSecondary`): the line CAM matches a live MSHR, so a waiter is attached
  to it.
- **Primary** (`lAlloc`): the set has no MSHR (one fill per set), there are enough free
  MSHRs, and the write-back buffer has room if the victim is dirty. The first free MSHR
  is taken and enters WAIT_AR. The victim way is chosen at S1 and `victim(set)` is
  advanced at allocation (`DP:6088`).
- **Fail** (`lFail`): the command is pushed into the 4-entry replay queue `rq`
  (`DP:6010-6046`). The queue is drained into the legacy shadow slot (`nbShadowLoad`)
  once `rqGo` holds. `rqGo` is registered and requires either a matching line CAM, or
  a free set plus at least 2 free MSHRs plus at least 2 write-back slots. **While `rq`
  is not empty, `nbCmdReady` refuses every new command** (`DP:6528`).

**Waiters** (`DP:5866-5879`). There are `NW = ringDepth + 1 = 5` slots:

- one per LS aligned-ring slot (indexed by `rid`);
- one `SER` slot for commands with `ridValid=0` (walkers, exceptions, split halves).

Each holds off, size, lineOnly, token and rid.

**Store miss** (`DP:5936-5946`, `DP:6051-6092`). A COPYBACK S2 miss sets
`pendingStoreMiss`. The next cycle (`sDo`) does one of two things:
- merges the store into a live MSHR on the same line (`sCam`): `mbuf`/`mstrb`/`mdirty`;
- or allocates a new MSHR. That MSHR enters WAIT_AR, or goes straight to FILLED if the
  strobe covers all 16 bytes.

In both cases `storeAllocAckReg` is pulsed in that same cycle (`DP:6089-6091`). Store
S1 is held by `nbStoreHold` (`DP:6549-6552`) whenever:
- the store's set conflicts with the stg/S1/S2/pending store-miss set;
- an MSHR on a different line holds the set;
- MSHRs or write-back slots would run short;
- an invalidation is pending.

These holds keep the per-set invariant and guarantee that a store miss always finds a
free MSHR (asserted at `DP:6565-6567`).

**Victim invalidation.** At allocation `invPend` is set. One pending invalidation is
applied per cycle: valid and dirty of `(eset, eway)` are cleared (`DP:6130-6147`). The
victim line becomes unreachable at allocation, not at install. A dirty victim's data
(from the S1 snapshot) goes into the write-back buffer (`DP:6117-6127`).

**AR issue** (`DP:6301-6374`):
- A single registered AR slot, `arV`/`arIdx`/`arAddr`.
- With `nbEagerAr=true`, any WAIT_AR entry is a candidate (`settled`/`blocked` are
  ignored, `DP:6344`) unless one of these holds:
  - a write-back or a WT write to the same line is waiting for its B (`coldWriteTo`,
    `DP:6276-6279`);
  - a WT store to the line is in S0..S2 (`wtPipeToMshr`, `wtS0To`).
- Entries are picked lowest-index first. On `arFire` the entry moves to WAIT_R
  (`DP:6361`).
- `nbS0ArHold` keeps a WT store to the same line in S0 while that AR is offered
  (`DP:6544`).

**R handling** (`DP:6375-6415`). The hot door accepts every R beat
(`axiDh.r.ready := True`, `DP:6385`). The beat is routed by ID into `rbuf(k)` and
`fault(k)`, and the entry moves to FILLED.

**Install** (`DP:6153-6219`):
- Only the lowest-index FILLED, non-faulting, non-invalidating MSHR is a candidate
  (`OHMasking.first`). A FILLED entry blocked behind it waits even if it has no conflict
  of its own.
- The install is held if a store in S1..S3 or a pending store miss touches the same
  set, if S3 is writing the same way, if any invalidation is pending, or during the
  reset sweep.
- The install writes data = `merged(k)` (refill bytes overlaid with store bytes), the
  tag, valid, and `dirty = mdirty`.
- Under `nonBlocking`, the write-port data, set and tag are driven every cycle as
  `Mux(s3W, stS3MergedLine, instData)` (`DP:6212-6219`).
- An install that waits more than 16 cycles closes the store port through
  `nbStorePortHold` (`DP:6225-6230`).

**Faulting refill** (`DP:6234-6247`): the entry goes straight to LINGER and nothing is
installed. If the entry had merged store data, `diagFault` kind 1 is raised, because
that store is lost.

**LINGER** (`DP:6253-6263`): the entry stays CAM-visible for 3 cycles (`nbDynamicRelease`
is off) and is freed once no waiter refers to it.

**Responses** (`DP:6455-6522`). One registered response slot, `rV`, loads `missLine`,
`missOff`/`missSize`/`missToken`/`missRid` from the first eligible waiter (fixed
priority, not age). It is emitted through the legacy `missLine` arm of the response mux
(`missLineResp` contains `nbRespFire`, `DP:1827-1829`). `nbRespFire = rV && !ldS2Resp`:
**an S2 hit always wins the single response port**, so MSHR responses can be starved by
a stream of hits.

**Early response** (`nbEarlyResponse=true`, `DP:6479-6522`). On the R beat itself, if
exactly one waiter is attached, no store has merged into the MSHR, and the response slot
is free, then `missLine := rData` and `rV` is armed directly. This skips the trip
through FILLED and the waiter scan.

**Serializing commands** (`DP:6525-6531`). A command with `ooOk=0` is accepted only when
`quietForSerial` holds: no S1, no stg, no waiters, no response, an empty `rq`, and no
shadow. After it, no further command is accepted until a response arrives
(`serialPending`). Commands with `ooOk=0` are:
- both halves of a line-crossing split (`LSU:1942`);
- every exception-sequencer load (`LSU:5259`);
- every walker descriptor read (`LSU:5466`).

**Write-back buffer** (`WBD=4`, `DP:5924-5933`, `DP:6419-6452`):
- Dirty victims are written back in FIFO order with ID 2.
- Only one write-back is in flight at a time (`wbIssued`), and it waits for its B.
- A write-back starts only when no other D-side write pair is open
  (`stAwDone && stWDone && !maintAxiPairOpen`), no WT store sits in S3, and no store B
  is arriving in that cycle.

**Idle signal.** `nbIdle` (`DP:6554-6555`) is part of `dcIdleForMaint`, `busQuiesced`
and `loadBusy`.

### 3.7 Hit-under-miss

- **Shipping path.** Under `nonBlocking`, a cacheable miss never takes the legacy FSM
  out of IDLE. Hits to other lines, and secondaries to the in-flight line, keep being
  accepted, subject to `nbCmdReady`. This is the real hit-under-miss in the shipping
  build.
- **The `hitUnderMiss` flag.** It is `true` in the shipping config (`SCC:78`). Its arm
  (`hitUnderMissAccept`, `DP:2674-2757`) runs only in `EVICT_WR`/`REFILL`. The term
  `humCommon` requires `missCmode =/= INHIBITED` (`DP:2685`), and under `nonBlocking`
  those states are reached only for INHIBITED misses. So `hitUnderMissAccept` can never
  accept anything in the shipping build.
- **What the flag still changes:** probes may launch during REFILL/EVICT_WR
  (`DP:3298,3435`; credit at `DP:4935-4937`). In practice that means during an inhibited
  load's refill.

### 3.8 Ordering between the two physical ports

Cacheable refills go out on `axi_dh`. Writes go out on `axi_d`. The core keeps
read-after-write order itself:

- An MSHR's AR is suppressed while any of these is true for its line (`DP:6343-6346`;
  simulation assertions at `DP:6597-6603`):
  - a buffered or in-flight write-back;
  - an in-flight WT write still waiting for its B (`wtFaultFifoAddr`, `DP:6266-6278`);
  - a non-copyback store in S3;
  - a WT store in S0..S2.
- A WT write to a line whose dirty victim is still buffered waits for that write-back
  (`nbWtWbHazardS3/Pending`, `DP:6274-6275`, `DP:5331-5336`).

### 3.9 Cache-inhibited (MMIO) accesses

- **Loads.** The S1 miss is forced (`DP:1194`). In REFILL the legacy FSM issues a
  sequence of naturally aligned sub-transactions covering `[off, min(off+n,16))`
  (`DP:3089-3095`, `DP:3463-3486`):
  - each sub-transaction has `AxSIZE ∈ {0,1,2}` (`MMIO.stepLog2`, `MMIO:106-115`);
  - there are at most 3 of them (`MMIO:69`);
  - each AR waits for its R before the next one;
  - the bytes are merged into `missLine` (`DP:3580-3614`).

  REPLAY then pulses `inhibitedResp` (`DP:3702-3708`). No line is allocated.
- **Stores.** S3 records `stSubP`/`stSubEnd`, taken from the size, or from the strobe run
  when `useStrb` is set (`DP:5288-5309`). Each B (`storeBAck && !stSubLast`) starts the
  next sub-write (`DP:5515-5561`). The ack waits for the last B.
- **D4 barrier.** With `inhibitedFullBarrier=true`, the LSU waits for the registered
  `busQuiesced` (`DP:4108-4120`; consumer `LSU:3955`). That signal includes `nbIdle`
  (`DP:4115`), so **an MMIO access waits until every outstanding MSHR, waiter and
  write-back has drained**. A simulation assertion checks that the nb subsystem is idle
  at every inhibited miss (`DP:6588-6590`).
- **Reordering.** Under `nonBlocking`, a non-`ooOk` command (such as an exception load)
  also waits for `quietForSerial`.

### 3.10 AXI path to the pins

**`AxiDMerge`** (`MRG:97-479`) has 4 read owners and 3 write owners:
- read owners: DCACHE, ITLB, DTLB, RESETVEC;
- write owners: the same without RESETVEC.

ITLB and DTLB are tied off (`MRGP:55-61`), so only the D-cache and the reset-vector
reader ever request.

- **Read side** (`MRG:138-286`):
  - Round-robin pick (`MRG:151-156`). The grant is combinational in the request cycle
    (`MRG:158-170`).
  - `busy` stays set from the grant **until `r.last` fires** (`MRG:276`). Exactly one
    read is outstanding on `axi_d`.
  - If an owner withdraws its request before its AR is taken, the grant is released
    (`MRG:255`). This is the fix for the historical grant leak.
- **Write side** (`MRG:289-413`):
  - Request = `aw.valid || w.valid`.
  - `busy` stays set until **`b.fire`** (`MRG:405`), so **exactly one write transaction
    is in flight on `axi_d` at any time**. The core can have several WT descriptors
    waiting for their B (`wtOutstanding` ≤ 4), and the next AW can be presented early.
    The merge still serializes AW→B round trips (§5).
- **Watchdog.** `wdog` resets on any progress and fires `wedge` after `grantTimeout`
  cycles (`MRG:280-285`, `MRG:407-412`). The default is 2×10^9 cycles (`MRG:23`), about
  10 s at 200 MHz. `wedge` halts the core with `ARBITER_WEDGE` (`FCS:589-592`, `FCS:610-622`).

**Register slices.**
- `axi_d` gets `pipelined(FULL)` on all five channels, in `coreCd` (`ST:406-411`).
- `axi_dh` gets FULL on AR and R. With `axiDhArFallThrough` (off in shipping), AR uses
  S2M instead (`SHRB:20-24`).
- FULL is `s2mPipe().m2sPipe()`, so each channel adds one cycle of forward latency.

**Read reset absorbers** (`ABS`; instances at `ST:355-370` and `SHRB:12-20`). These live
in `axiPorCd`, which the CPU reset does not clear:
- They count reads accepted at the pins and not yet answered by `r.last` (5-bit
  counter).
- Any reset arms them. While armed, they refuse new ARs and accept-and-discard R beats
  until the count reaches zero.
- There is one each for `axi_i`, `axi_d` and `axi_dh`.
- There is no absorber on writes, because the core's `b.ready` is always 1.

**Socket bundles** (`SAXI`): pin-named `SocketAxiI` and `SocketAxiD`. They have no
prot/cache/lock/qos/region sidebands. `axi_dh` reuses `SocketAxiI` with a 128-bit data
width and a 2-bit ID (`ST:228-229`).

### 3.11 CINV / CPUSH maintenance (`maint` Area, `DP:4145-4368`)

- **Who issues it.** `ExceptionUnit` issues it at commit:
  - CPUSH sends `push=1, invalidate=1` (`EXC:2696-2701`);
  - CINV sends `push=0, invalidate=1` (`EXC:2707-2712`).

  `addr` is the An value, used directly as a physical address (`DP:4253-4257`). The
  debug controller can also issue an all-scope command (`FCS:689-696`).
- **Handshake.** `ExceptionUnit` waits for `sqDrained && maintQuiesced`.
  `maintQuiesced` is `dcIdleForMaint && !maintBusyReg`, registered (`DP:3963-3970`,
  `DP:4010`). The walk re-checks the local `dcIdleForMaint` in WAIT before it touches
  anything (`DP:4215-4218`).
- **Walk.** IDLE → WAIT → per (set, way): READ → CHECK → [WRB] → NEXTW
  (`DP:4181-4366`).
  - Line scope walks the 4 ways of one set.
  - **Page scope and All scope both walk all 128 sets × 4 ways.** Page scope only
    filters by tag prefix, with TCR.P sampled at command time (`DP:4196`,
    `DP:4253-4259`).
  - Derived cost: page or all scope takes at least 3 × 512 = 1,536 cycles, plus one
    AW/W/B round trip per dirty match, all serialized through the merge.
- **Effect on the D-cache.** Code says **CPUSH does invalidate the D-cache.**
  - Clean matches are invalidated in CHECK (`DP:4272-4283`).
  - Dirty matches are written back with ID 4, then have dirty cleared and valid cleared
    (`DP:4330-4342`).
  - Only a write-back that fails with a bus error leaves the line valid and dirty.

  The task notes and an earlier memory entry say otherwise. The RTL contradicts them.
- **IC-only selector.** A command with `sel=IC` finishes at once with no D-cache work
  (`DP:4200-4207`).
- **I-cache coherence.** There is no hardware coherence between D-cache writes and the
  I-cache. Software must use CPUSH, as on a real 68040. The I-cache flush for IC/BC is
  done by `ExceptionUnit` in S_MAINTWAIT.
- **Interlocks.** During a walk:
  - `maintBusyReg` blocks new loads, new stores and probes;
  - the walk's own AXI pair is gated off while another writer's pair is open, and the
    other writers are likewise gated off while the walk's pair is open
    (`maintAxiPairOpen`, `DP:3310,3319,5331,5356`).

### 3.12 Other mechanisms

- **Multi-hot fail-safe.** A duplicate tag match is treated as a miss at four sites:
  load S1, the probe, store S2, and sector (dead). `dupPurge` then invalidates only the
  *clean* matching ways (`DP:4430-4476`). Sticky evidence registers record where it
  happened (`DP:4459-4465`).
- **Diagnostic fault** (`DP:5718-5731`). A first-error-wins sticky latch, with kinds:
  - 0: WT/inhibited non-precise B error;
  - 1: drain-miss allocate, or a lost nb store merge;
  - 2: eviction write-back;
  - 3: CPUSH write-back.

  It halts the core.
- **`busQuiesced`** (`DP:4108-4120`). A registered "no D-side bus activity, and nothing
  is being offered on the load/store/maintenance ports" signal, used by the D4 barrier.

---

## 4. Configuration knobs

`ShippingCoreConfig` reads most of these from environment variables at elaboration time.
**The code default is not the shipping value** for most of the non-blocking flags: an
`sbt` build without the right environment produces the blocking legacy cache. The
shipping column below comes from the build log.

### 4.1 Knobs that affect this component

| Knob (env) | Code default (`SCC`) | Shipping | Live? | What it does in code |
|---|---|---|---|---|
| `dcacheNonBlocking` (`CPU_DCACHE_NONBLOCKING`) | false (`:485`) | **true** | Live | Elaborates the `nb` Area. The legacy FSM is limited to INHIBITED. Requires `inhibitedFullBarrier`, `!sectored` and `!hitUnderMissRead` (`DP:376-379`, `SCC:560`). |
| `dcacheMshrs` (`CPU_DCACHE_MSHRS`) | 4 (`:489`) | 4 | Live | MSHR count, 2..4 (bounded by the 2-bit hot-door ID). |
| `dcacheHotDoor` (`CPU_AXI_DH`) | false (`:528`) | **true** | Live | Refills go out on `axiDh`. Off: they share `axi` and stall while the legacy FSM is in REFILL (`DP:6392-6404`). |
| `axiDhArFallThrough` | false (`:531`) | false | — | AR register slice is S2M instead of FULL (`SHRB:20`). |
| `dcacheNbEarlyResponse` | false (`:487`) | **true** | Live | Responds straight from the R beat (`DP:6479-6522`). |
| `dcacheNbEagerAr` | false (`:539`) | **true** | Live | AR candidates ignore `settled`/`blocked` (`DP:6344`). This leaves `settled` and `blocked` without any non-simulation reader in the shipping build (§7.2). |
| `dcacheNbPreselectAr` | false | false | **Dead** | Would put a just-allocated load straight into ARQ (`DP:6353-6373`). |
| `dcacheNbDynamicRelease` | false | false | **Dead** | Would release LINGER early once stale stages drain. Off means a fixed 3-cycle linger (`DP:6223`, `DP:6254-6261`). |
| `dcacheNbProbeMissStage` | false | false | **Dead** | Would let a clean probe miss skip the S1 read (`DP:777-810`, `DP:2504-2528`, `DP:5905-5921`, `DP:6636-6676`). |
| `dcacheStoreAllocArDelay` | 0 | 0 | **Dead** | Would delay a store-allocate AR (`DP:6310-6321`). |
| `dcacheHitUnderMiss` | **true** (constant, `:78`) | true | **Functionally dead under nonBlocking** | See §3.7. The logic is still elaborated (§7.2). |
| `dcacheHitUnderMissRead` | false (`:117`) | false | **Dead** | Would accept an S1 read during a refill (`DP:2612-2638`, `DP:2704-2741`). Excluded by `nonBlocking`. |
| `dcacheFillForward` | false (`:202`) | false | **Dead** | Would answer a legacy cacheable miss from `missLine` (`DP:1819-1821`, `DP:3785-3812`). Unreachable under nb, because the legacy path never handles cacheable misses. |
| `dcacheDirectRefillResponse` | false (`:206`) | false | **Dead** | Would answer from the live R beat (`DP:938`, `DP:1830-1832`, `DP:1888-1900`, `DP:3502-3511`). |
| `dcacheSectored` | false (`:225`) | false | **Dead** | 64 B lines in 4×16 B sectors: line-indexed tag, `lineDirtyMem`, EVICT_RD/EVICT_CK walk, 4-beat burst refill (`DP:400-435`, `DP:558-561`, `DP:912-921`, `DP:3359-3430`, `DP:3446-3482`, `DP:3621-3647`). Mutually exclusive with `nonBlocking`. |
| `dcacheEarlyProbeLineForward` | false (`:520`) | false | **Dead** | Would let a command consume the probe result one stage early, from `probeLineLine` (`DP:1550-1578`, `DP:1684-1694`). |
| `dcacheFullLineNoFill` | false (`:435`) | false | **Dead** | Legacy no-fill write-allocate (`DP:1053-1056`, `DP:3242-3253`, `DP:3746-3761`). nb has the same behaviour natively through `sNoFill` (`DP:5944`). Forbidden together with nb (`DP:367`). |
| `inhibitedFullBarrier` (`CPU_INHIBITED_FULL_BARRIER`) | false (`:465`) | **true** | Live | Elaborates `busQuiesced` (`DP:335`, `DP:4108`). |
| `allowPretranslatedProbeHints` | `true` (constructor default, `DP:43`) | **false** (`SP:94`) | Live (off) | `probeReadUsable` is constant False. |
| `socketMerged` | false (`DP:42`) | **true** (`ST:129`) | Live | `axi` is directionless and owned by `AxiDMergePlugin`. |
| `lsLoadRingDepth` | 4 (`:492`) | 4 | Live | Sets `DLoadRid.Width` = 2, which gives 5 waiters. |
| `storeQueueDepth` | 8 | 8 | (SQ) | Not inside this component. |
| `noFillMutation` | 0 | 0 | Test-only | |

### 4.2 Legacy logic that the shipping build elaborates but can never use

Because nb leaves the legacy FSM to handle only INHIBITED misses, the following is
compiled into the shipping netlist but can never fire. Synthesis can remove it only where
the dead condition simplifies to a constant. When the condition depends on a register,
such as `missCmode`, the logic stays in the netlist.

- `hitUnderMissAccept` in EVICT_WR/REFILL: the ready term, the S2 write arm, and the
  probe-entry invalidation (`DP:2674-2757`). It depends on the `missCmode` register, so
  it is probably not removed.
- REPLAY's cacheable relaunch, `replayRelaunch` (`DP:2649-2672`, `DP:3811`), and its
  write-allocate merge (`DP:3709-3771`). The latter is gated by `refillReqIsStore`,
  which is never set True, because its only setter is in the `False` arm. Synthesis can
  likely fold that one.
- The `EVICT_WR` state body (`DP:3295-3356`). Its entry condition is
  `x===INHIBITED && x=/=INHIBITED`, which synthesis may or may not fold.
- `pendingVictimTag`/`pendingVictimLine` are still live, because nb's write-back push
  uses them (`DP:6121`). `victimEvictTag`/`victimEvictLine` are written only from the
  `False` arm under nb (`DP:3153-3158`, `DP:3228-3230`), so they are dead.
- The legacy `loadShadow` path is reused by the nb replay queue (`DP:6041-6046`), so it
  is live.

### 4.3 How much of `DcachePlugin.scala` is not on the shipping path

Line counts: 6,732 total; 3,657 comment; 165 blank; **2,910 code**.

| Region (code lines, comments excluded) | Lines | Shipping status |
|---|---|---|
| nb Area (`DP:5826-6710`) | 728 | Live. 81 of these lines are simulation-only counters (`DP:6626-6709`), and 64 are simulation assertions (`DP:6558-6623`). |
| Legacy FSM (`DP:2533-3815`) | 597 | Mostly dead under nb. The live parts are IDLE, the INHIBITED REFILL/REPLAY path, and the probe launch. |
| Maintenance walk (`DP:4145-4368`) | 155 | Live |
| Sectored walk states (`DP:3359-3430`) | 31 | Dead |
| `hitUnderMissAccept` (`DP:2674-2757`) | 54 | Elaborated, functionally dead |
| `humS1MissPark` (`DP:2612-2638`) | 15 | Dead (Scala `if`) |
| Line-forward (`DP:1550-1578`) | 29 | Dead (Scala `if`) |
| nbProbeFast candidate (`DP:2504-2530`) | 27 | Dead (Scala `if`) |
| Legacy `EVICT_WR` body | 40 | Elaborated, unreachable |
| Legacy REPLAY store merge | 42 | Elaborated, unreachable |

Other counts across the code lines:
- 57 code lines mention `sectored` and 13 mention `fullLineNoFill`.
- 126 lines are `simPublic()` calls, 32 are `GenerationFlags.simulation` blocks, and 80
  are `assert`s.

Rough estimate: about 300–400 code lines are behind OFF Scala flags (sectored,
fill-forward, direct refill, line-forward, probe-miss stage, full-line no-fill, HUM-read,
preselect, dynamic release), and about 250 more are elaborated but unreachable under nb.
The rest is shipping logic plus simulation-only instrumentation.

---

## 5. Performance-relevant behaviour (shipping config)

All figures below are derived from the register stages in the code. They are not
measurements.

| Path | Latency or throughput | Basis |
|---|---|---|
| Load hit, normal | `loadRsp` 2 cycles after `loadCmd` fires | S0 read → S1 compare → S2 register (`DP:3019`, `DP:1863-1881`) |
| Load hit via early probe | 1 cycle after `loadCmd` fires, with no array read | `DP:3002-3014` |
| Probe → usable entry | Data is in the entry 2 cycles after launch | `DP:1374-1458` |
| Load miss (nb + hot door + early response) | About **L + 7** cycles from `loadCmd` fire to response, where L is the latency from AR accept at the pin to R valid at the pin. Breakdown: S1 detect (+1), stg/alloc (+1), WAIT_AR→arV (+1), arV→AR at the core port (+1), FULL AR slice (+1), L, FULL R slice (+1), rFire→rV→response (+1). | `DP:5884`, `DP:5964`, `DP:6362-6364`, `SHRB:23`, `DP:6500-6519` |
| Load miss without early response | About one more cycle (FILLED → waiter scan → `rV`) | `DP:6460-6478` |
| Miss with a dirty victim | Same as above. The write-back is buffered and does not block the fill. The AR waits only if the fill is to the very line being written back. | `DP:6117-6127`, `DP:6276-6279` |
| MLP | Up to 4 primary misses, one fill per set, and up to 5 waiting loads. AR issue is one per cycle at most. R returns in any order by ID. | `DP:5841`, `DP:5955`, `DP:5866` |
| Inhibited load | Waits for the full nb drain (`busQuiesced`), then up to 3 serialized AR→R rounds through the merge and the FULL slices | §3.9 |
| Copyback store hit | Fire → ack in 4 cycles at best. A burst to the same line runs at about 0.6 stores per cycle (S1-vs-S3 hold). Probes and loads take priority on the read port. | §3.4 |
| Copyback store miss | Ack 1 cycle after S2 discovers the miss (local, at allocation). The fill happens in the background. | `DP:6089-6091` |
| WT store stream | One AW/W→B round trip at a time on `axi_d`, because of the `AxiDMerge` write busy (`MRG:405`), plus the FULL slices on AW, W and B | §3.10 |
| Dirty write-back stream | One write-back in flight at a time, with ID 2. It also excludes every other D-side write. | `DP:6419-6423` |
| Store with MMU off | Every store is precise, so the whole pipeline serializes, one store at a time | `LSU:1317`, `DP:4837` |
| Split / exception / walker load | Drains the nb subsystem before acceptance and blocks all further loads until it responds | `DP:6525-6531` |
| Replay-queue stall | After any `lFail` (same-set MSHR, MSHRs full, write-back buffer full), the load port is closed until the queue drains | `DP:6528` |
| CPUSHA / CPUSHP / CINVP | At least 1,536 cycles (walks all 512 ways), plus one serialized write-back per dirty match | §3.11 |

---

## 6. Area and timing notes (from the structure only; no synthesis report was used)

- **Block RAM (estimated, not measured):**
  - Data: 4 × (128 deep × 128 wide). Each way is width-bound, roughly 2 RAMB36 or 4
    RAMB18 in SDP mode, so about 8 RAMB36-equivalents in total, at only **25% depth
    usage** (128 of 512).
  - Tags: 4 × (128 × 21), each one RAMB18 because of `ram_style=block`.
  - Valid and dirty are LUTRAM.

  Check the utilization report before relying on any of these numbers.
- **Large 128-bit registers** (counted from the code). The live ones are:
  - `probeLineLine`, `ldS2Line`, `missLine`, `stS3OldLine`, `stS3WriteLineD1`, `stMergeReg`;
  - `pendingMergeData`, `pendingVictimLine`, `stgVLine`, maintenance `wbLineReg`;
  - the `lineData` field of `s0`/`stS1`/`stS2`/`stS3Payload` (4);
  - nb `rbuf` ×4, `mbuf` ×4, `wbData` ×4.

  That is about 26 × 128 ≈ 3.3 k flip-flops of line-wide state, plus the
  `victimEvictLine` register, which is dead under nb.
- **Wide multiplexers:**
  - 4:1 way muxes: `ldS1Line = rdData(ldS1HitWay)` (`DP:1265`), probe `MuxOH`
    (`DP:1425`), store `MuxOH` (`DP:2309`), victim snapshots (`DP:5904`).
  - nb write data: `Mux(s3W, stS3MergedLine, MuxOH(instHeadOH, merged(0..3)))` per way.
    Each `merged(k)` is 16 byte muxes (`DP:6175`, `DP:6216`).
  - Response: two byte-lane extractors, on `missLine` and on `ldS2Line` (`DP:1882-1887`).
  - The `victim` register file is read at `ldS1Set`/`stS2Set` with 128:1 × 2-bit muxes.
- **Combinational cones that leave the cache.** These run from `loadCmd.payload`
  (VA 32b + token 8b + tag 21b, compared against 5 CAM entries) through
  `earlyProbeHit` and `useEarlyProbe` to `loadCmd.ready` (`DP:1727`, `DP:2953-2958`).
  `loadProbe.ready` is a registered credit. `maintQuiesced` and `busQuiesced` are
  registered exports.
- **High-fanout nets:**
  - `rdSet`/`rdEn` drive the addresses of 4 data BRAMs, 4 tag BRAMs and 8 LUTRAMs.
  - `wrSet`/`wrEn`/`wrData` per way.
  - `storePipeHeld`, `nbStoreHold` and `loadPortReserved` feed the store stage enables.
  - The `nbCmdReady` term is ANDed into `loadCmd.ready` (`DP:2958`).

---

## 7. Observations (factual; for the deep reviewer)

### 7.1 Stale or misleading comments (code is authoritative)

- **`AxiIds` docstrings are swapped.** `D_PUSH` is used for evictions and `D_EVICT` for
  CPUSH (§2.3).
- **`AxiIds.dRefill` doc is stale.** It says "only 0 is used while N_MSHR == 1"
  (`IDS:38-39`). With the hot door, `dRefill` is used only for the legacy inhibited
  read. Refill IDs on `axi_dh` are the raw MSHR index (`DP:6380`).
- **Class header is stale.** It says "Miss -> single-beat refill -> replay" and "Valids:
  register array" (`DP:12-41`). Under nb the shipping miss path is the MSHR file, and
  the valids are LUTRAM `Mem`s.
- **Probe queue depth.** Several comments say "four-entry result queue" (e.g.
  `DP:1525`, `DP:2920`). The code has 5 entries (`DP:733`).
- **CPUSH.** The task brief, an earlier project memory entry, and some comments say
  CPUSH does not invalidate the D-cache. The code sends `invalidate=1` for CPUSH
  (`EXC:2698`), and the walk invalidates every matching line (§3.11).
- **`DcacheService.loadBusy`** is described as "back-pressures loads" (`DT:306`). Its
  only consumer, `ExceptionUnit.dcLoadBusy`, is never read.

### 7.2 Dead or redundant logic in the shipping build

- **`hitUnderMiss` is functionally dead under `nonBlocking`** (§3.7), but it is
  elaborated:
  - `hitUnderMissAccept` in EVICT_WR/REFILL (the `loadCmd.ready` override and the S2
    write arm);
  - `probeAdmitNext`'s REFILL/EVICT_WR term.

  Removing this logic only when nb is on would trim the `loadCmd.ready` cone. The one
  piece of behaviour lost would be probe launches during inhibited refills.
- **The cacheable legacy miss machinery is elaborated but unreachable** (§4.2):
  EVICT_WR, the REPLAY relaunch and store merge, and the `victimEvict*` registers.
- **`settled`/`blocked`** are written every cycle (`DP:6052`, `DP:6298`). With
  `nbEagerAr`, nothing outside simulation reads them (`DP:6344`, `DP:6689-6691`).
  `blocked(k) := coldWriteTo(line(k))` builds 4 × (4 write-back + 4 WT + 1)
  line-address comparators that feed only a simulation counter. Synthesis should prune
  them, but they add elaboration noise.
- **Built but never consumed:** `storeCompletion` (`DStoreAck`), `loadBusy`, and
  `residentHit` (only read with `lsFourCycleL1`).
- **Duplicated store byte-lane logic.** It is computed at S2 (`stS2MergeData`/`Strb`,
  `DP:2303-2308`, used only for `pendingMerge*`) and again at S3 (`DP:2220-2225`).
- **Simulation-only instrumentation.** `nbCtr_*` (81 lines) and many `simPublic()`
  calls. Some comments note that `simPublic` can change synthesized signal names. It
  should not change area.

### 7.3 Likely mistakes and risks

- **No invalid-way preference** in replacement (§3.1). After a CINV, after the reset
  sweep, or after a faulting fill, a miss still evicts the round-robin way even when an
  invalid way exists. This costs hit rate and adds avoidable dirty write-backs.
- **The victim is invalidated at MSHR allocation** (`invPend`, `DP:6062,6075`), not at
  install. The victim line is lost for the whole miss latency, and a same-set hit to it
  turns into a further miss. That miss then fails `lSetBusy` and enters the replay
  queue, which closes the whole load port (`DP:6528`).
- **A single replay-queue entry stalls all loads.** `nbCmdReady` requires `!rqNonEmpty`
  (`DP:6528`). So one load to a busy set blocks hits to unrelated lines until `rqGo`
  (registered) lets it through.
- **Install order.** Only the lowest-index FILLED MSHR may install (`instHeadOH`,
  `DP:6169`). A FILLED MSHR with no conflict waits behind a held lower-index one. This
  is head-of-line blocking within the MSHR file.
- **MSHR responses lose to S2 hits** (`nbRespFire = rV && !ldS2Resp`, `DP:6458`). Under a
  long run of hits, a miss response, possibly for the oldest load at the ROB head, can
  be delayed indefinitely. The simulation liveness assertion only fires after 20,000
  cycles (`DP:6615-6619`).
- **Waiter responses use fixed index priority**, not age (`DP:6464`).
- **Write side fully serialized at the merge.** `AxiDMerge.wr.busy` is held until B
  (`MRG:405`). The core's `MAX_WT_OUTSTANDING=4` therefore cannot reach the bus, and
  write-backs, WT stores and CPUSH write-backs all queue behind one round trip.
- **The read side of `axi_d` is also single-outstanding** (`MRG:276`). Only inhibited
  loads and the reset vector use it now, so this matters only for MMIO.
- **Inhibited loads drain the whole nb subsystem first** (`busQuiesced` includes
  `nbIdle`, `DP:4115`). MMIO latency includes every outstanding fill and write-back.
- **Splits, walker reads and exception loads are fully serializing under nb**
  (`ooOk=0`, `quietForSerial`, `DP:6527`). Every line-crossing misaligned load and every
  table-walk descriptor read drains the MSHR file first.
- **Line/page maintenance treats `An` as physical** (`DP:4253-4257`). This matches
  68040 semantics. Page scope walks all 512 ways, about 1.5 k cycles at least.
- **Interface hazard.** The bit width of `DLoadRid` comes from the global
  `ShippingCoreConfig.lsLoadRingDepth` (`DT:26`), not from a constructor parameter. A
  harness that changes the ring depth must change this global too.
- **The shipping configuration depends on the environment** (§4.1). Without the env
  variables, `sbt` produces the blocking cache. This is the "the shipping config is not
  the tested config" risk, located in this component's knobs.

### 7.4 Cheap area and congestion opportunities (unmeasured)

- **Carry only one store-data form through S0..S3.** Each stage carries both
  `data(32)+size` and `lineData(128)+strb(16)`. Building `merge data + strobe` once at
  S0 would drop about 4 × 34 bits, and would remove the duplicate S2/S3 lane logic.
  Building it once at the SQ boundary would be an alternative.
- **BRAM byte-write enables for store hits.** A copyback or WT hit reads the old line
  (`stS2CapturedLine` → `stS3OldLine`) only to merge it with 16 byte muxes and write
  back the whole 128 bits. Writing through the BRAM's byte write-enables, with all-ones
  for fills and installs, would drop the 128-bit S3 old-line register and the merge
  muxes. The S1 read would still be needed for the tag, the victim snapshot and the
  dirty bit. The `stS2UsesS3Line` bypass would also disappear.
- **Prune legacy cacheable miss logic when `nonBlocking`** (§4.2 and §7.2).
- **Data arrays use 25% of BRAM depth.** If a BRAM budget is needed elsewhere, the data
  array could be laid out with 2 ways per BRAM (sets × 2), at the cost of a second
  enable-steering bit.

### 7.5 Cheap IPC opportunities (unmeasured)

- **Remove the same-line S1-vs-S3 store hold** (`stS1SameLineAsS3`). Extend the S2
  capture with the already-registered `stS3WriteD1` copy, analogous to `stS2UsesS3Line`.
  This would restore II=1 for same-line copyback bursts (stack frames, MOVEM to memory,
  memcpy destination).
- **Prefer an invalid way in victim selection.** That needs a 4-bit valid vector, which
  is already read at S1 (`rdValid`).
- **Allow younger loads past a non-empty replay queue** when they are `ooOk` and hit.
  For example, gate on the head entry only for same-set commands.
- **Give MSHR responses priority over S2 hits**, or alternate between them, so a
  head-of-ROB miss cannot be starved.
- **Make the walker's descriptor read `ooOk`-safe**, or exempt it from `quietForSerial`
  when it hits. Table walks currently drain all outstanding misses.
- **Write path.** The `AxiDMerge` write busy (one in flight until B) is the limit for WT
  pages and write-back-heavy streams. Pipelining the same-ID AW/W while holding B order
  would need matching SoC crossbar support. That is outside this repository and cannot
  be checked here.
