# 01 — Front end: L1 I-cache, fetch/align, branch prediction

Source: commit `fee95f6f` (the bitstream running on the board). Paths are relative to the
repo root; `FA` = `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala`, `IC` =
`src/main/scala/m68k040/cache/IcachePlugin.scala`, `FCS` =
`src/main/scala/m68k040/top/FullCoreSynth.scala`.

**Method.** Every size, latency and liveness claim here was derived from the RTL
(statements, widths, register boundaries), not from source comments or `docs/*`. The
source comments in this slice are long and frequently describe earlier designs; where a
comment or doc conflicts with code, that is flagged in §7. Cycle counts are derived by
tracing register boundaries and were not measured in simulation.

**Shipping elaboration of this slice** (from `M68kSocketTop`, `top/SocketTop.scala:140-155`,
and `top/ShippingPlugins.scala:96-108`; `ipcThroughput=true` in the shipping config):

| Plugin | Constructor as shipped |
|---|---|
| `IcachePlugin` | `predecodeWords = IcachePredecodeConfig.fromEnvironment` — env `ICACHE_PREDECODE_WORDS`, default `"16"` (`cache/IcachePredecodeConfig.scala:8`). The SHIPPING_CONFIG line does not record it; the build prints it on a separate `ICACHE_PREDECODE_WORDS=` line (`SocketTop.scala:675`). **This document assumes 16** (the default); the 8-wide path is described as non-shipping. |
| `BtbPlugin` | no args; size from `Global.BTB_ENTRIES` = `M68kParams().btbEntries` = **128** (`Config.scala:42`, `core/ParamPlugin.scala:44`) |
| `FtbPlugin` | default `entries = 128` (`frontend/Ftb.scala:29`) |
| `RasPlugin` | `branchRepair = rasBranchRepair = true`; 16 entries (`Config.scala:43`) |
| `GsharePlugin` | `retainRedirectHistory = ipcThroughput = true`; GHR 16 bits, PHT 2048 (`Config.scala:44-45`) |
| `FetchAlignPlugin` | `enableFetchDirected=true, ftqDepth=32 (default), trainSlot1Conditional=true, deferTakenSlot1Conditional=true, computeDirectTargets=true, deferSlot1Uncond=true, deferSlot1Dbcc=true, deferSlot1Conditional=false` |
| `ItlbPlugin` | `victimEntries = 0` (32 entries / 4 ways / 2 banks, `mmu/Tlb.scala:31-33`) |

---

## 1. Purpose and position in the pipeline

The front end turns a fetch PC into a stream of up to **two 68k instructions per cycle**
(`DecodePacket` pairs) for `DecodeStage`, and owns all fetch-time branch prediction.

```
             ┌──────── FTB (128, window-indexed) ─┐  registered, C+1
 fetchPc ──► │  cmdWindowPc mux ──► IcachePlugin  ├──► FetchRsp (8 B + 4×predecode), C+2
   ▲         │                 ▲ ITLB (comb)      │
   │         └── gshare window lookup (4 PHT rd) ─┘
   │                     │
   │                fetch ring (3) ──► InstructionBuffer (20 words) ──► Aligner (2 slots)
   │                                                     │                 │
   │        FTQ (32) ◄── FTB apply                       │   BTB(slot0) / RAS / gshare(slot0,1)
   │                                                     ▼                 ▼
   └──── redirects: ROB Tier-1/Tier-2, complex resume, predictFire, ftqMismatch, CPUSH/CINV
                                                     feed (Stream[Vec(2) DecodePacket]) ──► DecodeStage
```

* **Feeds it:** redirect PCs from the ROB (Tier-1 `earlyFire`, Tier-2 `doFlush`), the
  decode-side complex-instruction resume, the reset-vector FSM, the CPUSH/CINV maintenance
  pulse, the ITLB (`TranslationService`), the AXI read channel (I-side master, 256-bit), and
  retire-time predictor training from the ROB (`BtbUpdateService`, `GshareUpdateService`,
  RAS repair carry).
* **Feeds:** `DecodeStage` through `DecodeFeedService.feed`
  (`decode/DecodeStage.scala:243-250`: the feed is captured into a 1-deep `PipeStage`
  whose `ready = !valid || out.ready`, `frontend/PipeStage.scala`).

## 2. Interfaces

### 2.1 Services

| Service | Provider | Consumer(s) in this slice | Notes |
|---|---|---|---|
| `FetchService {cmd: Stream[FetchCmd], rsp: Flow[FetchRsp]}` | `IcachePlugin` (`IC:3083-3084`) | `FetchAlignPlugin` (`FA:144`) | `rsp` carries **no tag**; FA attributes every response to its ring head (in-order contract). |
| `DecodeFeedService {feed, slot1Valid}` | `FetchAlignPlugin` (`FA:2000-2001`) | `DecodeStage` | |
| `FtbLookupService {lookupCmd, lookupRsp, clearOne}` | `FtbPlugin` | FA (only if `enableFetchDirected`, `FA:149-151`) | rsp is exactly cmd+1. |
| `GshareWindowService {windowCmd, windowRsp}` | `GsharePlugin` | FA (`FA:152-153`) | rsp exactly cmd+1, same token as FTB. |
| `GshareSecondaryLookupService` | `GsharePlugin` | FA, slot-1 conditional knobs (`FA:1247,1502`) | exposes `phtIndex1/phtTaken1`. |
| `GshareUpdateService` | `GsharePlugin` | driven by ROB (`FCS:268-269`) | |
| `BtbUpdateService` | ROB | `BtbPlugin` (`Btb.scala:104`), `FtbPlugin` (`Ftb.scala:109`) | one update port shared by BTB and FTB. |
| `TranslationService {req, rsp}` | `ItlbPlugin` | `IcachePlugin` (`IC:187`) | combinational: `rsp.{ready,ppn,cacheMode,fault}` computed the same cycle as `req` (`mmu/ItlbPlugin.scala:1103-1135`). |
| `PrivilegeService`, `CacheControlService`, `MmuControlService` | ROB / MMU control | `IcachePlugin` via optional `host.get` (`IC:197,211,216`) | supervisor bit, CACR.IE, TCR.P (8 KiB pages). |
| `FrontendQuiesceService {active,next}` | ROB | FA (`FA:216-227`) | FA registers `next` locally (`quiesce`). |
| `PredictorHistoryRecoveryService {historyEpochStart, historyKeepOnFlush}` | ROB (`= earlyFire`, `= earlySuppressFe && !excActive`, `rob/RobPlugin.scala:3794-3795`) | `GsharePlugin` when `retainRedirectHistory` (`Gshare.scala:238-247`) | |

**Not via services.** BTB, RAS, gshare query/shift ports, `icMaintFlush`,
`mispredictRedirect`, RAS checkpoint/repair and `nonSpecFetch` are plain wires inside each
plugin's `logic` Area, assigned from outside by `BackendWiringPlugin` (`FCS:133-269`) and by
`SpeculativeFetchGate.wire` (`top/SpeculativeFetchGate.scala:66-127`), which reads
`DecodeStage`, `RenameStage` and `RobPlugin` internals directly. See §7.

### 2.2 Global database keys

Consumed (all produced by `ParamPlugin`, `core/ParamPlugin.scala:44-47`):
`BTB_ENTRIES` (`Btb.scala:49`), `RAS_ENTRIES` (`Ras.scala:191`), `GHR_BITS`, `PHT_ENTRIES`
(`Gshare.scala:67-68`). Plain constants (not keys): `Global.BR_PRED_TABLE_DEPTH = 64`,
`BR_PRED_TAG_W = 6` (`Global.scala:257-258`).

Produced: none.

Set but **never read anywhere**: `L1I_KB`, `L1I_WAYS`, `L1I_LINE_BYTES`, `DECODE_WIDTH`
(`ParamPlugin.scala:39-43`). The I-cache geometry is hard-coded through
`CacheGeometry.l1i040` (`cache/CacheGeometry.scala:79-81`, `IC:25`).

### 2.3 Main bundles

| Bundle | Fields / width | Where |
|---|---|---|
| `FetchCmd` | `pc[32]` (8-byte aligned window base) | `cache/IcacheTypes.scala:192-194` |
| `FetchRsp` | `pc[32]`, `data[64]` (raw byte order, byte 0 = lowest address), `fault`, `atc`, `pred: Vec(4, ChunkPredecode)` | `IcacheTypes.scala:197-208` |
| `ChunkPredecode` (per 16-bit word, **8 bits**) | `lenWords[4]` (0 = not simple; `simple` is derived `lenWords =/= 0`), `ambiguousLine`, `size` (Spinal enum BYTE/WORD/LONG, 2 bits), `ctrlXfer` | `IcacheTypes.scala:30-167` |
| `DecodePacket` | `valid, pc[32], words: Vec(10,16b), wordCount[4], simple, lenWords[4], complex, fault, faultAtc, predTaken, predTarget[32], phtValid, phtIndex[11], brPredTag[6], size` | `frontend/DecodePacket.scala:6-69` |
| `FtbLookupCmd` | `windowPc[32], drop[2], token{ringSlot[2], seq[8]}` | `services/Services.scala:308-321` |
| `FtbLookupRsp` | `windowPc, token, hit, framedOk, brWordOff[2], brLen[4], target[32], brType[2], framedUncond` | `Services.scala:330-340` |
| `GshareWindowRsp` | `token, taken: Vec(4), phtIdx: Vec(4, 11b)` | `Services.scala:350-354` |
| `BtbUpdate` | `pc[32], taken, target[32], brType[2], len[4]` | `Services.scala:292-298` |
| `GshareUpdate` | `index[11], taken` | `Services.scala:376-379` |
| AXI I master | `Axi4ReadOnly`, addr 32, **data 256**, id 4 | `IC:41,49` |

---

## 3. Microarchitecture

### 3.1 L1 instruction cache (`IcachePlugin`)

**Geometry** (`CacheGeometry.l1i040`): 16 KiB, 4 ways, 64-byte lines, 64 sets, VIPT
(virtual index from PC[11:6], physical tag PA[31:12] = 20 bits). `requireViptSafe(4096)`
holds because index+offset = 12 bits (`IC:26`). With TCR.P (8 KiB pages) PA[12] comes from
the untranslated PC (`IC:341-343`).

**Storage**

| Structure | Shape | Storage (inferred from Spinal construct) | Ref |
|---|---|---|---|
| `tagMem(w)` | 4 × (64 × 20b) | `Mem` with `readAsync` → distributed RAM. **Two async read ports per way**: `lookupTags` (`IC:1353`) and prefetch residency `pfCandResident` (`IC:1498-1499`); one write port (`IC:2935`). | `IC:234` |
| `lineMem(w)` "unified fetch array" | 4 × (128 × **384b**) — 128 = 64 sets × 2 beats; 384 = 256 data + 16 words × 8 predecode bits | `Mem` with `readSync` → BRAM; one read port (`IC:800`), one write port (`IC:2997-3001`) | `IC:268-269` |
| `valids` | 4 × 64 flops | `Vec(RegInit)` | `IC:271` |
| `victim` | 64 × 2b round-robin | flops | `IC:273` |
| `pfFilled` | 4 × 64 flops (prefetch-provenance telemetry, feeds a perf counter) | flops | `IC:306` |
| `fillLo`, `fillHi` (MSHR line file) | 5 × 256b each, indexed by AXI RID; `readSync(installIdx)` | `Mem` | `IC:474-475,722-723` |
| MSHR control | 5 entries (1 demand + 4 speculative): `valid, arSent, complete, beat, err, poison, pa[32], set[6], tag[20], way[2], gen[4], arSentGen[4]` | flops | `IC:650-659,693-694` |
| S0 context | `s0Pc[32], s0Set[6], s0Beat, s0Ppn[20], s0Cacheable, s0Fault, s0Atc, s0Lane[2], s0Replay, s0FromMiss, tagQ 4×20, validsQ 4, pfFilledQ 4` | flops | `IC:864-930` |
| Response register | `rspPc[32], rspData[64], rspFault, rspAtc, rspPred 4×8` | flops | `IC:1117-1122` |
| Inhibited/poisoned bypass | `bypWindow[64]`, `bypPred[32]` | flops | `IC:561,747` |
| Prefetch frontier | `pfSeqValid, pfDemandLine[32], pfNextPa[32], pfLimitPa[32]` | flops | `IC:753-756` |
| AR holding register | `arHoldValid, arHoldId[4], arHoldAddr[32]`; `arOutstanding` 5 flops | flops | `IC:1703-1705,2595` |

**Lookup pipeline (hit).** Let C be the cycle `cmdPort.fire`.

* **C (accept).** `xlate.req` is the cmd PC (`IC:225-228`); the ITLB answers
  combinationally. The BRAM read is armed from **virtual** bits only:
  `ufaReadAddr = {PC[11:6], PC[5]}`, `ufaReadEn = cmdPort.valid && !setBlocked`
  (`IC:1814-1815`). `cmdPort.ready = xlate.rsp.ready && !setBlocked && !s1Unresolved &&
  pfAcceptOk && !inhibitedSpecBlock` (`IC:1842-1843`). On fire, the S0 context is captured
  unconditionally, including the async tag/valid reads of all 4 ways and the translated PPN
  (`IC:1845-1890`).
* **C+1 (S1).** Verdict from registers only: `s1HitVec(w) = s0Cacheable && validsQ(w) &&
  tagQ(w) === s0Ppn` (`IC:955-956`); `s1Hit = exactlyOne(s1HitVec)` (a 2-hot match is
  treated as a miss and purges the matching ways, `IC:975-976,3048-3066`). Data path: per
  way, the 64-bit lane `s0Lane = PC[4:3]` is pre-selected from the 256-bit BRAM output, then
  a one-hot AND-OR over 4 ways (`IC:1239-1290`); predecode likewise (`IC:1245,1310`).
  `rspValidReg := s0Valid && !s1Unresolved` (`IC:1323`).
* **C+2.** `rsp.valid` (registered) reaches FetchAlign.

So the **hit latency is 2 cycles cmd→rsp**, initiation interval 1. A miss is accepted
first and discovered at S1; `s1Unresolved = s0Valid && !s0Replay && !s0Fault && !s1Hit`
(`IC:986`) closes `cmdPort.ready`, so **no younger command is accepted behind a miss**
(responses must stay in order because `FetchRsp` has no tag).

**Fill FSM** (`IC:1708-2141`): `IDLE → REFILL → INSTALL_ARM → PREDECODE(×2) → COMMIT →
REPLAY → IDLE`, plus `FAULT` (AXI error, no allocation) and the speculative install path
`IDLE → INSTALL_ARM → PREDECODE(×2) → COMMIT → IDLE`.

Demand miss, cycle-by-cycle from the accept cycle C (derived from register boundaries):

| Cycle | Event | Ref |
|---|---|---|
| C | accept | |
| C+1 | S1 `s1Unresolved`; if FSM in IDLE: MSHR0 allocated (`pa = s0Ppn ## s0Pc[11:0]`, `way = victim(s0Set)`), `startDemandFill`. Otherwise `s0Valid` is held. | `IC:2173-2198` |
| C+2 | REFILL; `arHold` loaded (`refillActive && !mshrArSent(0) && !arOutstanding(0)`) | `IC:2600-2604` |
| C+3 | `axi.ar.valid` (earliest AR) — INCR, `len=1`, `size=5` (2 × 32 B) | `IC:2622-2651` |
| … | memory latency; beats written into `fillLo/fillHi(RID)` | `IC:2806-2807` |
| L | last beat: `refillDone` → INSTALL_ARM (registers `installIdx/Set/Way`) | `IC:1987-1994` |
| L+1 | INSTALL_ARM (sync read of MSHR line file) | `IC:2007` |
| L+2, L+3 | PREDECODE beat 0, beat 1 (16 classifiers each cycle); tag/valid written on beat 1 | `IC:2041-2069, 2886-2971` |
| L+4 | COMMIT (registered last-beat `lineMem` write lands) | `IC:2075-2087, 2991-3006` |
| L+5 | REPLAY: re-read BRAM at `{installSet, missPC[5]}`, `s0Valid := True, s0Replay := True` | `IC:2090-2140` |
| L+6 | S1 of the replay | |
| L+7 | `rsp.valid` | |

Overhead outside the bus: ~3 cycles before the AR and ~7 cycles after the last beat. There
is **no critical-word-first** delivery and no early restart: the response waits for both
beats, predecode and the array write.

`fsm.isActive(IDLE)` alone calls `lookupTick(canStartFill=true)`; PREDECODE and COMMIT call
`lookupTick(canStartFill=false)` **only for speculative installs** (`IC:2049-2057,
2076-2083`). Hence:

* during a **demand** refill/install/replay the fetch port is closed;
* during a **speculative** install the port is closed for INSTALL_ARM (1 cycle) and open for
  PREDECODE/COMMIT except to the set being written (`setBlocked = fillArrayWrActive &&
  lookupSet === installSet`, `IC:1812-1813`);
* a demand miss accepted during a speculative install waits in S0 (`s0Valid` hold) until IDLE.

**Cacheability, CACR.IE, inhibited pages.** `lookupPageCacheable = cacheMode =/= INHIBITED`;
`lookupCacheable = pageCacheable && icacheEnabled` (`IC:349,358`). A non-cacheable miss runs
the full refill but `doAllocate = missCacheable && !mshrPoison(installIdx)` is false
(`IC:2900`), and REPLAY delivers the 64-bit window from `bypWindow/bypPred`
(`IC:2134,1290,1310`). Non-allocating fetches therefore cost a whole 64-byte burst **per
8-byte window**.

The **speculative-fetch gate**: `inhibitedSpecBlock = !lookupPageCacheable &&
!nonSpecFetch` (`IC:416`) refuses to accept a fetch into a cache-inhibited page unless
`nonSpecFetch = RegNext(drainedRaw) && ringCount === 0` (`top/SpeculativeFetchGate.scala:
79-126`), where `drainedRaw` requires the fetch ring, IBuf aligner output, decode stages,
MicroOpQueue, rename stages and ROB all empty and no redirect/flush pending. Code executing
from an inhibited page is therefore fully serialized (one fetch window per pipeline drain).
CACR.IE=0 does **not** trigger this gate (it is keyed on the page attribute only).

**Faults.** ITLB fault: `s0Fault := lookupFault`, response with zeroed predecode
(`IC:1311`), `atc=1`. AXI error on a demand refill: `FAULT` state, one response with
`fault=1, atc=0`, no allocation (`IC:2015-2034`). Speculative errors/poisoned fills free
locally (`IC:2488-2504`).

**Invalidate.** `anyInvalidate = invalidateAll || maintInvalidateAll` clears all `valids`
and `pfFilled` in one cycle (`IC:321-328`), poisons every live MSHR (`IC:2521-2523`) and
kills the prefetch window (`IC:2524`). A same-cycle install's `valids` write yields to it
(`IC:2946-2948`). In-flight fetch responses are still delivered (FetchAlign marks them stale).
In `M68kSocketTop` the external `invalidateAll` is tied to `False` (`SocketTop.scala:496`);
only `maintInvalidateAll` (ROB `icMaintPulse`, `FCS:170`) is live.

**AXI.** One AR holding register; demand has priority, then lowest-numbered speculative
slot, except that when an offered demand is refused because a speculative slot owns its set,
that slot is chosen (`pfChosenArSel`, `IC:2546-2573`). `arHoldValid` clears on fire and can
only be reloaded the next cycle, so **at most one AR every 2 cycles** (`IC:2600-2620,
2653-2658`). Per-ID `arOutstanding` prevents re-issuing an ID with an outstanding burst
(`IC:2595-2598`). R beats are routed solely by RID = MSHR index; beats that do not match the
current occupant's generation are drained (`staleDrain`, `axi.r.ready` always asserted for own
IDs, `IC:2705-2734`).

### 3.2 Next-line prefetcher (inside `IcachePlugin`)

* **Seed.** From the registered S1 disposition `s1Disp` (one cycle after S1), for every
  accepted, non-faulting, cacheable demand (hit or miss) whose line differs from the current
  `pfDemandLine` and is not its +64 successor (`IC:1687-1698`). `seedPfWindow(line)`:
  `pfNextPa := line + 64` (unless sequential), `pfLimitPa := min(line + 5×64, last line of
  the 4 KiB page)` (`IC:1646-1663`). A faulting or non-cacheable fetch kills the window.
* **Allocation** (≤1 per cycle, `IC:2419-2445`), enabled when `pfSeqValid && prefetchEnable
  && icacheEnabled && pfNextPa <= pfLimitPa && same 4 KiB page as pfDemandLine` and not
  frozen (`anyInvalidate`, `demandFillStart`, `pfWindowUpdate`, `demandStuckQ`,
  `s0KillsWindowQ`). Candidate already live in an MSHR or resident in the tags → advance;
  candidate set owned by any MSHR or by a pending/offered demand → wait; else allocate the
  first free of the **4 speculative MSHRs**.
* **Lookahead**: up to 5 lines ahead of the last demand line, never crossing a 4 KiB physical
  page (even with 8 KiB pages).
* **Install**: completed speculative fills are installed by the single shared installer
  (`IDLE` arm, `IC:1949-1959`) through INSTALL_ARM/PREDECODE×2/COMMIT; a speculative install
  produces no FetchRsp.
* **Runtime control**: `prefetchEnable` resets to `ShippingCoreConfig.icachePrefetch` (true)
  and can be rewritten from the debug CSR (`IC:141-184`, `debug/DebugCtrlPlugin.scala:2434-2435`).
* **Invariant** "one fill owner per set" is enforced by `pfCandSetBusy` (all 5 MSHRs),
  `demandSetOwned` (3 lanes, `IC:2313-2315`) and SG-1 `pfAcceptOk = !pfLookupSetBusy`
  (a hit to a set owned by an in-flight speculative fill is **refused** until that fill
  installs, `IC:1538-1540,1841`).

### 3.3 Predecode (`PredecodeWord.classify`, `IcachePlugin.classifyBeat`)

* At refill, `classifyBeat` runs **16 parallel `classify` instances** over one 32-byte beat
  per PREDECODE cycle, each with a 3-word lookahead (`IC:1189-1209`); the instance group is
  shared by the two beats via `beatSrc = Mux(isLoBeat, fillLoQ, fillHiQ)` (`IC:2847-2851`).
  Low-beat lookahead comes from `fillHiQ[47:0]`; for the high beat the lookahead flag is
  `isLoBeat` = false, i.e. the **last 3 words of each line are classified without lookahead**.
* Output per word: `lenWords` (1..10 when simple), `ambiguousLine` (length was guessed
  because the disambiguating extension word lies beyond the line), `size`, and `ctrlXfer`
  = Bcc/BRA/BSR (line 6), DBcc, JMP/JSR, RTE/RTD/RTS/RTR, FBcc (cc≠0), FDBcc
  (`frontend/PredecodeWord.scala:104-122`).
* A 17th `classify` instance runs **every cycle** in FetchAlign on IBuf head words 0..6
  (`FA:1085-1092`), registered into `p0LiveReg`; it is used only when the head word's stored
  predecode has `ambiguousLine` (`frontend/Aligner.scala:91`). `p0LiveReg.ambiguousLine` is
  forced true on any flush, shift, push into words 0..6 (`cnt < 7`) or `availEff` change
  (`FA:1109-1114`), so an ambiguous head costs ≥1 extra stall cycle.
* 8-wide option (`predecodeWords=8`, not shipping by assumption): 8 classifiers shared between
  AXI-beat arrival (`earlyPred` per-MSHR storage) and the installer (`IC:732-742, 2852-2884`).

### 3.4 Fetch control and fetch ring (`FetchAlignPlugin`)

State: `decodePc[32]`, `decodePcHiP1[27]` (= decodePc[31:5]+1, `FA:305-340`), `fetchPc[32]`
(8-aligned), `stalled`, `started`, `faultHold`, `faultEmitted`, `pendingDrop[2]`.

**Fetch ring** (`RING = 3`, `FA:389-415`): per slot `stale`, `drop[2]` (leading words to
skip), `keep[3]` (exclusive end word, 4 = whole window, lowered by an FTB application),
`planSeq[8]`; `ringHead`, `ringTail`, `ringCount`.

**Issue condition** (`FA:756-783`):
```
ic.cmd.valid = started && (ringCount<3 || (rsp.valid && !rsp.fault))
            && (ibuf.cnt + (ringCount+1)*4 <= 20 || predictFire)
            && !stalled && !faultHold && !quiesce && !ftqMismatch
ic.cmd.pc   = predictFire ? predictTargetReg&~7 : targetHoldValid ? targetHoldPc
            : applyNow ? ftbTarget&~7 : fetchPc
```
On fire, `fetchPc := cmdWindowPc + 8` unless a redirect acts this cycle (`FA:785-804`).
A command issued in a redirect cycle is "born stale" (`ringStale(tail) := redirectThisCycle`).

**Response handling** (`FA:840-912`): the head ring entry's response is discarded if stale
(or if `predictFire` this cycle); otherwise words `[drop, keep)` are byte-swapped into 68k
opword order and pushed into the IBuf with their predecode. A fault response sets `faultHold`.

Steady state: with 2-cycle cache latency, two outstanding requests sustain one 8-byte window
per cycle; the third ring slot is not needed for throughput but adds headroom when the IBuf
reservation allows it.

### 3.5 Instruction buffer (`InstructionBuffer`)

Circular buffer, `BUF_WORDS = 20` entries of `{word[16], ChunkPredecode[8]}` (24 bits → 480
flops), `HEAD_WORDS = 10`, `PUSH_WORDS = 4` (`frontend/InstructionBuffer.scala:43-45`).
Push writes `n ≤ 4` words at `(headPtr+count) mod 20` through one `UIntToOh` decode
(`:117-132`); the head is a 10-entry barrel read (`:148-176`), zero-filled beyond `count`;
`avail = min(count,10)`. Flush resets `count/headPtr`. Push is unconditional from FetchAlign's
side (the issue-time reservation guarantees room; `io.push.ready` is computed but not used by
FA as a gate).

### 3.6 FTQ and fetch-directed prediction (FTB + gshare window)

**FTB** (`frontend/Ftb.scala`): direct-mapped, 128 entries, one entry per aligned 8-byte
window: index = PC[9:3], tag = PC[31:10] (22 bits). Entry `{tag[22], brWordOff[2], brLen[4],
target[32], brType[2], counter[2]}` = 64 bits in a `Mem` (async reads → LUTRAM) with **three
async read ports** (lookup `:70`, update RMW `:112`, clear `:143`) plus 128 valid flops.
Lookup is registered: `rsp` = `RegNext(cmd)`; the framing verdict `framedOk = hit && brLen≠0 &&
brWordOff+brLen ≤ 4 && brWordOff ≥ cmd.drop` and `framedUncond = framedOk && brType≠0` are
computed at command time (`:83-102`). Install at retire only if the branch lies wholly inside
its 8-byte window (`len≠0 && off+len ≤ 4`, `:117-120`); `clearOne` invalidates the entry whose
tag and `brWordOff` match a mismatching branch PC (`:142-147`); `invalidateAll` wins.

**gshare window lookup** (`Gshare.scala:145-175`): on the same command, four PHT reads at
`windowPc + 0/2/4/6`, registered with the same token.

**Application** (`FA:676-690`): at C+1,
```
applyNow = resultExpectedValid && ftbRsp.valid && gshareRsp.valid && slotLegal
         && (framedUncond || (framedOk && gshareTaken[brWordOff])) && !ftbBlocked
ftbBlocked = redirect || resume&&stalled || quiesce || stalled || faultHold
           || ftbSuppress || targetHoldValid || icMaintFlushArm
```
On `applyNow`: `ringKeep(resultSlot) := brWordOff+brLen` (the window is truncated after the
branch), an FTQ entry `{brPc, brLen, target, phtIdx, isCond}` is pushed, and the **same
cycle's** cache command goes to the target window (`cmdWindowPc`), or into the one-entry
`targetHold` if the cache refuses (`FA:809-831`). A taken FTB-predicted branch therefore
costs **no fetch bubble**: window W at C, target window at C+1.

**FTQ** (`FA:417-429`): 32 entries × 80 bits, `Mem` with one async read at `ftqHead`
(LUTRAM). No full check in hardware (`ftqFull` only feeds a sim assertion, `FA:1996`); the
elaboration-time bound `ftqDepth ≥ RING + BUF_WORDS + 1 = 24` is the only guard (`FA:419`).

**Decode-side consumption** (`FA:974-1015,1265-1269,1656-1661,1752-1759`):
* `ftqDelta`/`ftqNear`: head FTQ branch PC is 0..31 bytes ahead of `decodePc`. `availEff`
  clamps the aligner's visible words to those before `brPc + brLen` (splice point), so target
  bytes can never be framed as part of a fall-through instruction.
* `ftqConfirm = ftqAt0 && slot0Valid && slot0.simple && headPred(0).ctrlXfer && slot0.lenWords
  === brLen`: slot0 is stamped `predTaken/predTarget/phtValid/phtIndex` from the FTQ, slot1 is
  suppressed, and on fire `decodePc := target` with **no IBuf flush** (target bytes were
  already appended); FTQ pops.
* Mismatch (`ftqLenBad`, `ftqOvershoot`, `ftqPast`, or `ftqStarved` = clamped with no emit for
  4 consecutive cycles) → `ftqMismatchDetect` → registered `ftqMismatch` next cycle: restart at
  the first unconsumed PC, flush IBuf/FTQ, mark ring stale, clear the FTB entry, set
  `ftbSuppress` (skip one FTB application) and restore the RAS checkpoint (`FA:1892-1903,
  FCS:209`).

### 3.7 Aligner (`frontend/Aligner.scala`)

Combinational, `WINDOW = 10` words, inputs: `headPc = decodePc`, IBuf head words/predecode,
`availEff`, `p0LiveReg`.
* `p0 = preds(0).ambiguousLine ? p0LiveReg : preds(0)`; `L0 = p0.lenWords` (`:91-92`).
* Stall if `avail = 0`, `p0.ambiguousLine`, or a **complex** head with `avail < 10`.
* Complex head (`lenWords = 0`): emit slot0 with all 10 words, `complex=1`, `shiftWords=0`
  (`:148-171`); FA then sets `stalled` until resumed (`FA:1746-1749`).
* Simple slot0 if `avail ≥ L0`; words beyond L0 are zeroed.
* Slot1 if `p1 = preds(L0)` is simple, **not** ambiguous, and `L0+L1 ≤ min(avail,10)`
  (`:240-274`); slot1 words are `words(L0+i)` (10 × 10:1 muxes × 16 bits).
* Slot1 PC uses the split adder `{decodePc[4:0] + 2·L0}` with `decodePcHiP1` (`:97-103`).

Max consumption: 2 instructions / ≤10 words per cycle. FA additionally suppresses slot1 (only
slot0's words are shifted) under `suppressSlot1` (`FA:1413-1414`):
`slot0Predicted || slot1WouldRasPred || slot1WouldCondPred || slot1WouldFtq ||
slot1WouldUncond || slot1WouldDbcc || ftqConfirm`.

### 3.8 Decode-time prediction (slot 0 only)

All decode-time predictors look at the aligner's **slot0** in the same cycle the packet is
offered (`FA:1137-1417`):

* **BTB** (`frontend/Btb.scala`): direct-mapped 128 entries, index = PC[7:1], tag = PC[31:8]
  (24 bits); entry `{tag[24], target[32], brType[2], counter[2]}` = 60 bits in a `Mem` with
  **two async read ports** (query `:84`, update RMW `:107`) + 128 valid flops.
  `queryValid0 = predEnable && slot0Valid && headPred(0).ctrlXfer` (`FA:1185`).
  `predTaken = hit && (counter ≥ 2 || brType == 1)` (`Btb.scala:88`).
* **gshare** (`frontend/Gshare.scala`): PHT 2048 × 2-bit, init 2, `Mem` with **seven async
  read ports** (slot0, slot1, 4 window lookups, update RMW: `:138,139,159,187`). Index =
  `fold31→11(PC[31:1]) XOR GHR[10:0]` (`:76-86,114-116`) — only 11 of the 16 GHR bits are
  used. For a slot0 conditional BTB hit (`brType==0`) the direction is the PHT bit
  (`FA:1193-1198`).
* **RAS**: slot0 opword `0x4E75`/`0x4E77` with non-empty stack → target = top-of-stack
  (`FA:1220-1230`). RTD/RTE are not RAS-predicted.
* **Computed direct targets** (`computeDirectTargets`, on): slot0 BRA/BSR with 8- or 16-bit
  displacement and no BTB/RAS prediction → `target = pc + 2 + sext(disp)` (`FA:1312-1332`).
  Bcc and 32-bit-displacement forms are excluded.
* Source priority: RAS > computed > BTB (`predTargetSel`, `FA:1416-1417`). FTQ confirmation
  overrides all stamps (`FA:1474-1479`).

**Redirect for decode-time predictions** (`FA:1698-1717, 1804-1822`): when the predicted
slot0 fires (C), `predictTargetReg` captures the target and `predictPending` is set; at C+1
`predictFire` blocks the feed, flushes the IBuf and ring, sets `decodePc := target`, and the
target command may issue in that same cycle (C+1) bypassing the IBuf-room check.

**Slot-1 handling** (all shipping-on except `deferSlot1Conditional`):

| Term | Condition (slot0 emitted, not predicted) | Ref |
|---|---|---|
| `slot1WouldRasPred` | slot1 is RTS/RTR and RAS non-empty | `FA:1249-1252` |
| `slot1WouldCondPred` | slot1 is Bcc (cond ≥ 2) **and** (slot0 is a conditional BTB hit **or** gshare predicts slot1 taken via `phtTaken1`) | `FA:1241-1248` |
| `slot1WouldUncond` | slot1 is BRA/BSR (disp ≠ 0xFF), JSR or JMP | `FA:1357-1366` |
| `slot1WouldDbcc` | slot1 is DBcc other than DBT | `FA:1394-1400` |
| `slot1WouldFtq` | the head FTQ branch is the slot1 instruction | `FA:1267-1268` |

A deferred slot1 becomes next cycle's slot0. A non-deferred slot1 Bcc is trained as
implicitly not-taken (`brPredLive1`: its `phtIndex1` is stamped, the GHR shifts a 0) only when
slot0 carries no prediction record (`FA:1496-1506,1774-1777`).

**Prediction side-channel tag**: a packet carrying a prediction gets `brPredTag` from a
wrapping counter 1..63 that advances on `feed.fire` (`FA:1494-1513`); 0 = none.

### 3.9 Global history, RAS state and recovery

**GHR** (`Gshare.scala:91-255`): `ghr[16]` shifts at `feed.fire` for (a) a confirmed FTQ
conditional (dir 1), (b) a slot0 conditional BTB hit (dir = predicted), (c) the implicit
slot1 not-taken (dir 0) (`FA:1773-1777`). `ghrArch[16]` shifts at retire with the resolved
direction on every `GshareUpdate` (`:221-224`). Repair: `repairArm = RegNext(doFlush)` →
`ghr := ghrArch` **two cycles after the Tier-2 flush** (`:225-235`). With
`retainRedirectHistory` (shipping), `RetainedHistoryRepair` (16-bit `suffix`, 5-bit count)
records the shifts made after a Tier-1 redirect; if the subsequent Tier-2 flush keeps the
front end (`historyKeepOnFlush`), the repair installs `(ghrArch << count) | suffix` instead
(`frontend/RetainedHistoryRepair.scala:24-48`). Tier-1 itself does not repair the GHR.
PHT training: retire-time, carried fetch-time index, ±1 saturating (`:187-191`).

**RAS** (`frontend/Ras.scala`): 16 × 32-bit register stack (`ras`), `rasSp[4]`,
`count[5]`; a full 16 × 32 checkpoint copy (`ckRas`, `ckRasSp`, `ckCount`) — 1,024 payload
flops in total.
* Push: slot0 BSR/JSR (`simple`) on `feed.fire && !faultHold`, data `pc + 2·lenWords`
  (`FA:1795-1797`). Pointer/count update immediately; the 32-bit payload write is delayed one
  cycle through `pendValid/pendIdx/pendData` with a bypass into `predTarget` (`Ras.scala:
  263-273`).
* Pop: on a RAS-predicted slot0 return.
* Checkpoint save: armed by `rob.countIsZero` (registered "ROB empty"), copied the next
  cycle (`:392-400`, `FCS:219`).
* Restore: `(doFlush && !feSuppress) || earlyFire || ftqMismatch` (`FCS:209-220`) copies all
  16 entries back (`:406-410`).
* `branchRepair` (shipping on): one cycle after `earlyFire`, re-push the mispredicted call's
  return PC unless already on top, or re-pop a mispredicted return's entry if it is on top
  (`:312-336`, `FCS:231-235`).
* `invalidateAll` is wired to the external I-cache invalidate (`FCS:187`), which is tied
  False in `M68kSocketTop`; the RAS is never cleared in the shipping top.

### 3.10 Redirect sources and priority

All restart arms (`FA:1804-1959`), lowest to highest priority (last assignment wins):

| Arm | Source | Effect |
|---|---|---|
| `predictFire` | registered decode-time BTB/RAS/computed prediction | `decodePc:=tgt`, ibuf/ring flush, target cmd in same cycle |
| `icMaintFlushArm` | `RegNext(maintInvalidateAll)` (CPUSH/CINV IC/BC) | restart at current `decodePc` |
| `ftqMismatch` | registered FTQ framing failure | restart at first unconsumed PC, clears `faultHold` |
| `redirect` (port) | tied False in `M68kSocketTop` | — |
| `resetRedirect` | `ResetVectorPlugin` | `commonRedirect` |
| `resume` (port) | tied False in `M68kSocketTop` | — |
| `vioRedirect` | not driven in `M68kSocketTop` (no `VioProbePlugin`) | — |
| `mispredictRedirect` | `(doFlush && !feSuppress) ? flushPc : earlyFire ? earlyPcReg : complexResume` (`FCS:142-145`) | `commonRedirect` |

`commonRedirect` (`FA:1834-1855`): set `decodePc`, `fetchPc := newPc & ~7`, flush IBuf, clear
`stalled`/`faultHold`, `pendingDrop := newPc[2:1]`, mark all ring slots stale. FTQ and
`targetHold` are flushed by every arm except plain FTB application (`FA:724-726,1972-1977`).

**Complex instructions** resume through this path: `DecodeStage.complexResume` →
`ComplexResumeActionPipe` (one register, cancelled by `feFlush`) → `mispredictRedirect`
(`FCS:135-145`) — i.e. a full IBuf flush and refetch at the complex instruction's successor.

### 3.11 MMU/ITLB interaction

The I-cache issues `xlate.req` (VPN = PC[31:12], supervisor from `PrivilegeService`, write=0)
while `lookupActive && cmdPort.valid` (`IC:225-228`). The ITLB (32 entries, 4-way, 2 banks;
`mmu/Tlb.scala:31-33`) answers combinationally; on a miss `rsp.ready = 0` holds the command at
the Stream boundary while the ITLB's own walker runs (descriptor reads go through the D-cache).
The translated PPN, fault and cache mode terminate in S0 registers (`IC:1865-1879`); only
`xlate.rsp.ready` and `cacheMode` (via `inhibitedSpecBlock`) reach `cmdPort.ready`.
The BTB/FTB/RAS/gshare are keyed on virtual PC and are **not** invalidated by PFLUSH or MMU
register writes; the `ctrlXfer` predecode gate (BTB query, FTQ confirm, computed targets) is
what keeps a stale entry from redirecting a non-branch.

### 3.12 Maintenance (CINV/CPUSH)

`maintInvalidateAll = rob.exc.icMaintPulse || debugMaintDonePulse` (`FCS:170`) clears **all**
I-cache valids (no per-line or per-page invalidate exists in `IcachePlugin`), all BTB and FTB
valids (`FCS:158-161`), poisons in-flight fills, and one cycle later restarts fetch at
`decodePc` with IBuf/ring/FTQ flushed (`FA:140,1881-1890`). RAS and gshare are not touched.

---

## 4. Configuration knobs

| Knob | Where | Default | Shipping | Notes |
|---|---|---|---|---|
| `predecodeWords` (`ICACHE_PREDECODE_WORDS`) | `IC:21`, `IcachePredecodeConfig.scala:8` | 16 | 16 (assumed; not in SHIPPING_CONFIG) | 8 = shared/early-predecode path |
| `icachePrefetch` (`DBG_IC_PREFETCH_DISABLE`) | `ShippingCoreConfig.scala:580` | on | on | reset value of `prefetchEnable`, runtime-writable |
| L1I geometry | `CacheGeometry.l1i040` | 16K/4w/64B | same | `M68kParams.l1i*` are dead |
| `btbEntries` | `Config.scala:42` | 128 | 128 | power of 2 |
| FTB `entries` | `Ftb.scala:29` | 128 | 128 | |
| `rasEntries` | `Config.scala:43` | 16 | 16 | |
| `gshareHistory` / `gshareEntries` | `Config.scala:44-45` | 16 / 2048 | 16 / 2048 | index width hard-wired 11 bits in `DecodePacket.phtIndex`, `FetchTargetQueueEntry.phtIdx`, FA ports — PHT size other than 2048 will not elaborate consistently |
| `enableFetchDirected` | `FA:35` | false | **true** | false → FTB/gshare-window/FTQ inert |
| `ftqDepth` | `FA:35` | 32 | 32 | must be ≥ 24 |
| `deferSlot1Conditional` | `FA:36` | false | false | **dead in shipping** |
| `trainSlot1Conditional` | `FA:37` | false | true | |
| `deferTakenSlot1Conditional` | `FA:38` | false | true | requires training |
| `computeDirectTargets` | `FA:39` | false | true | |
| `deferSlot1Uncond` | `FA:40` | false | true | |
| `deferSlot1Dbcc` | `FA:41` | false | true | |
| `retainRedirectHistory` | `Gshare.scala:53` | false | true | instantiates `RetainedHistoryRepair` |
| `RasPlugin.branchRepair` | `Ras.scala:188` | false | true | |
| `ItlbPlugin.victimEntries` | `ShippingCoreConfig.scala:360` | 0 | 0 | |
| `RING`, `BUF_WORDS`, `HEAD_WORDS`, `PUSH_WORDS`, `WINDOW` | FA:389, IBuf:43-45, Aligner:8 | 3, 20, 10, 4, 10 | fixed constants | |

**Dead / never-enabled in the shipping top:**
* `StraddleTable`, `StraddlePending` (`cache/StraddleTable.scala`, `cache/StraddlePending.scala`)
  — instantiated nowhere in `src/main` (only unit specs); `ChunkPredecode.hasToken/setToken/
  tokenIdx` have no caller.
* `IcacheTop` — elaboration wrapper used only outside the shipping top.
* `FetchAlignPlugin.redirect`, `.resume`, `.vioRedirect` arms; external `IcachePlugin.invalidateAll`
  (and therefore RAS/gshare `invalidateAll`) — tied False / undriven in `M68kSocketTop`.
* `deferSlot1Conditional` path; the `earlyPred` area (8-wide predecode) if 16 is shipped.
* `GsharePlugin.queryValid0/1` — assigned by the wiring layer, read by nothing in `Gshare.scala`.

---

## 5. Performance-relevant behavior

**Bandwidth.**
* Fetch: one 8-byte (4-word) window per cycle at best, from a 2-cycle-latency cache with up to
  3 requests outstanding. The response is a 64-bit lane pre-selected out of a 256-bit BRAM
  beat (`IC:1239`).
* IBuf issue throttle: `cnt + (ringCount+1)·4 ≤ 20` (`FA:756-757`); with 2 requests in flight
  a new fetch needs `cnt ≤ 8`.
* Decode: ≤2 instructions and ≤10 words per cycle; slot1 is dropped whenever any of the seven
  suppression terms fires (§3.7). Sustained instruction rate is bounded by
  `4 words/cycle ÷ average instruction length`.
* After a taken branch the first window starts at `target & ~7` and drops `target[2:1]`
  leading words, so the first useful window can carry as little as 1 word.

**Redirect penalties** (cycles in which no new useful packet reaches `feed`, derived from
register boundaries, I-cache hit assumed):

| Event | Timeline | Decode-feed bubble |
|---|---|---|
| FTB-predicted taken (fetch-directed, confirmed at decode) | window W cmd at C, target cmd at C+1 | 0 extra fetch cycles; the branch's packet carries only slot0 (`ftqConfirm` suppresses slot1) |
| Decode-time BTB / RAS / computed target | branch fires at C, `predictFire` C+1 issues target cmd, rsp C+3, IBuf head C+4 | 3 cycles (C+1..C+3) |
| `mispredictRedirect` (Tier-1/Tier-2/complex resume) | arm at R, cmd R+1, rsp R+3, feed R+4 | 4 cycles after the redirect pulse, plus the back-end's detection latency and decode/rename refill |
| `ftqMismatch` | detect D, act D+1 (no fetch/feed in D+1), cmd D+2, feed D+5 | ~4-5 cycles |
| CPUSH/CINV restart | arm 1 cycle after the pulse, then as a redirect | 4+ cycles, plus cold I-cache/BTB/FTB |
| Complex instruction | stall until `complexResume` → 1-cycle `ComplexResumeActionPipe` → redirect → +4 | ≥5 cycles plus decode's own sequencing time, on **every** complex instruction |
| Ambiguous head (`ambiguousLine`) | `p0LiveReg` invalidated on any head change | ≥1 cycle each time |

**I-cache miss**: ~3 cycles to AR + memory latency + 2 beats + ~7 cycles post-burst (§3.1);
the whole fetch port is closed for the duration. Speculative installs close the port for 1
cycle (INSTALL_ARM) each and delay a demand miss dispatch by up to 4 cycles.

**Prediction coverage limits (from code structure):**
* BTB is queried for slot0 only; slot1 branches need deferral (a 1-cycle slot1 loss) to be
  predicted at decode.
* FTB holds one branch per 8-byte window and only branches fully contained in it.
* Conditional branches that miss BTB/FTB always fall through (no static BTFN rule, no computed
  Bcc target).
* gshare overrides direction only on BTB/FTB hits; slot1 conditionals get a direction only via
  the deferral term.
* RAS: only RTS/RTR predicted; RAS checkpoint is the last ROB-empty state.

**Known stall sources inside the slice:** I-cache miss (port closed); ITLB miss (cmd held);
SG-1 refusal of a hit to a set owned by an in-flight prefetch; `setBlocked` during a speculative
install; inhibited-page fetches (full drain each window); non-allocating fetches (64-byte burst
per 8-byte window); IBuf reservation; complex-instruction stall + refetch; `ftqStarved`
detection takes 4 cycles (`FA:1423-1431`).

---

## 6. Area / timing notes (inferred from RTL structure)

* **Accept cone.** `fetchPc/targetHold/predictTargetReg/ftbRsp → cmdWindowPc (4:1 × 32b) →
  ITLB CAM (comb) → xlate.rsp.ready / cacheMode → cmdPort.ready → ic.cmd.fire`. `ic.cmd.fire`
  is then the enable of the S0 capture (~150 flops in the I-cache, `IC:1845-1890`), the ring
  slot writes, `fetchPc`, `planSeq`, and the FTB/gshare command valid. `applyNow` (from FTB/
  gshare registered responses plus `ftbBlocked`) selects the PC at the head of this cone. The
  FTB/gshare response payload registers are captured unconditionally to keep `fire` off their
  CEs (`Ftb.scala:93-102`, `Gshare.scala:165-170`).
* **decodePc loop.** `decodePc → ftqDiffLow (6-bit) / 27-bit equality → availEff → Aligner
  (L0 from preds(0)/p0LiveReg, preds(L0), slot1 window mux) → BTB async read at slot0 PC and
  gshare fold/XOR async read → slot0PredTaken → suppressSlot1 → decodePcNext 2:1 → decodePc`.
  Also `feed.fire` (depends on `DecodeStage`'s raw-stage ready) gates `decodePc`, IBuf shift,
  RAS push, GHR shift, `predictTargetReg` capture and the brPredTag counter.
* **Wide multiplexers.** Aligner slot1 `words(L0+i)`: 10 × 10:1 × 16b; IBuf head barrel:
  10 × 20:1 × 24b; I-cache way select: 4-way AND-OR over 64+32 bits after lane preselect;
  RAS top-of-stack 16:1 × 32b plus a 16 × 32b restore mux into every `ras` entry.
* **Async-read port replication.** gshare PHT 2048 × 2b with 7 read ports; FTB 128 × 64b with
  3; BTB 128 × 60b with 2; I-cache tags 64 × 20b × 4 ways with 2 each. Each distributed-RAM
  read port is a separate RAM copy (plus, for 2048-deep, a mux tree per port). The BTB/FTB
  `valids` are flop vectors read through 128:1 muxes at each read index.
* **Classifier count.** 17 instances of `PredecodeWord.classify` (16 at refill, 1 live on the
  IBuf head every cycle).
* **Flop-heavy blocks.** RAS + checkpoint 1,024 payload flops; IBuf 480; I-cache `valids`/
  `pfFilled` 512; FTQ is LUTRAM (32 × 80b).
* **High-fanout control nets** visible in code: `ic.cmd.fire`, `feed.fire`, `applyNow`,
  `redirectThisCycle`/`ftqFlush` (fan into ring, FTQ, ibuf, decodePc, fetchPc),
  `checkpointRestore` (512 RAS D-muxes), `anyInvalidate` (256 valids + 256 pfFilled + 5 MSHR
  poison + 128+128 BTB/FTB valids via the wiring), `s1HitVec` (way mux, pfFilled clear, purge).

---

## 7. Observations (candidates for the reviewer)

1. **Dead straddle-token machinery.** `cache/StraddleTable.scala` and
   `cache/StraddlePending.scala` are not instantiated anywhere in `src/main`, and
   `ChunkPredecode.hasToken/setToken/tokenIdx` (`IcacheTypes.scala:159-166`) have no caller.
   `docs/DESIGN_icache_straddle_token.md` still says "implementation starting". The live
   mechanism is still the every-cycle `p0LiveReg` classify (`FA:1085-1114`).
2. **BTB and FTB direction counters are never consulted.** Branch EU emits `brType ∈ {0,1}`
   only (`execute/BranchEuPlugin.scala:372`). For a BTB hit, `brType==0` is overridden by
   gshare (`FA:1198`) and `brType==1` is forced taken (`Btb.scala:88`), so `counter` never
   affects `slot0PredTaken`. The FTB response does not carry `counter` at all
   (`Ftb.scala:94-102`); direction is `brType≠0 || gshare`. Both tables still store and
   read-modify-write the counter (`Btb.scala:114-123`, `Ftb.scala:121-132`). `brType[1]` is
   also always 0. Candidate: drop 3 bits/entry and the RMW logic (or, conversely, use the BTB
   counter for BTB-hit/FTB-miss cases instead of gshare).
3. **gshare PHT has 7 async read ports on a 2048-deep LUTRAM** (`Gshare.scala:138,139,159,187`).
   The update port is a RMW; a 2-bit counter could be updated with a separate small
   write-only shadow or the window reads could be banked by `PC[2:1]` (the four window
   indices differ only by the PC low bits folded into bit 0 of chunk 0). Largest single
   predictor area item by structure.
4. **Only 11 of 16 GHR bits are used** (`Gshare.scala:114-115`), but `ghr`, `ghrArch` and the
   `RetainedHistoryRepair` suffix are 16 bits wide. Small dead state.
5. **`GsharePlugin.queryValid0/1` are unused inputs** (`Gshare.scala:120,122`).
6. **Global keys `L1I_KB`, `L1I_WAYS`, `L1I_LINE_BYTES`, `DECODE_WIDTH` have no consumer**;
   the I-cache uses `CacheGeometry.l1i040`. Changing `M68kParams.l1i*` silently does nothing.
7. **Architecture rule.** AGENTS.md requires plugins to communicate only via `host[Service]` /
   `Global`. The BTB, RAS, gshare query/shift ports, `icMaintFlush`, `mispredictRedirect`,
   `maintInvalidateAll`, `nonSpecFetch` are driven by `BackendWiringPlugin` reaching into
   `x.logic.*` (`FCS:155-269`), and `SpeculativeFetchGate` reads ~30 internal signals of FA,
   DecodeStage, RenameStage and RobPlugin (`SpeculativeFetchGate.scala:66-126`).
8. **Every complex instruction costs a full front-end refetch.** The resume goes through
   `mispredictRedirect` → `commonRedirect`, which flushes the IBuf and stales the ring
   (`FCS:135-145`, `FA:1834-1855`), even though the bytes after the complex instruction are
   usually already in the IBuf. A resume that only clears `stalled` and shifts `lenWords` would
   save ≥4 cycles per complex instruction (needs the real length from decode).
9. **Fetch width is 8 bytes while the BRAM delivers 32.** The S1 stage throws away 3 of 4
   lanes (`IC:1239`). With an average 68k instruction near 2-3 words, 4 words/cycle caps the
   2-wide decode below 2 IPC on straight-line code. A 16-byte response (2 lanes) would need IBuf
   push 8 and a larger reservation, but no new array.
10. **Decode-time predictions cost 3 bubbles** vs 0 for FTB-directed ones. Branches that the FTB
    cannot hold (straddling an 8-byte window, or the second branch in a window) always pay the
    decode-time path. A second FTB way or a 2-branch-per-window entry is a candidate.
11. **`deferTakenSlot1Conditional` can pay a cycle for nothing.** A slot1 Bcc is deferred when
    `phtTaken1` says taken (`FA:1246-1247`), but at slot0 a conditional is only predicted on a
    BTB hit (`FA:1193-1200`) — there is no computed Bcc target. If the BTB misses, the deferral
    loses slot1 and the branch still falls through.
12. **RAS checkpoint is a 512-bit full copy** with a 16-way restore mux on every entry
    (`Ras.scala:207,395-410`), restored on every Tier-1 and Tier-2 redirect and FTQ mismatch.
    The restore source is the last ROB-empty state, which can be arbitrarily old.
13. **Speculative install closes the fetch port 1 cycle** (INSTALL_ARM has no `lookupTick`,
    `IC:2007`) and occupies the single installer for 4 cycles, during which an accepted demand
    miss is held (`IC:2173-2198`). With 4 prefetch slots streaming, that is up to 1 lost fetch
    cycle per prefetched line.
14. **Demand miss has ~7 cycles of fixed post-burst latency** (INSTALL_ARM, 2×PREDECODE,
    COMMIT, REPLAY, S1, rsp; §3.1) and no critical-word-first. A direct response from
    `fillLo/fillHi` (as already done for inhibited fills via `bypWindow`) could overlap
    install with delivery.
15. **AR issue rate is 1 per 2 cycles** (`arHoldValid` clear-then-reload, `IC:2600-2658`).
    Unimportant for the demand path, but bounds how fast 4 prefetch slots can launch.
16. **Non-allocating fetches refetch a 64-byte line per 8-byte window** (CACR.IE=0 or
    inhibited pages; `IC:2900`, REPLAY bypass). Expected to be rare in practice; noted for
    completeness.
17. **Second tag read port per way exists only for prefetch residency** (`pfCandResident`,
    `IC:1498-1499`). A registered or slower check would halve tag LUTRAM.
18. **FTB update term `len <= 5` is redundant** with `off + len <= 4` (`Ftb.scala:117-120`).
19. **FTQ capacity is unchecked in hardware** (`FA:1996` is simulation-only); correctness rests
    on the elaboration-time bound `ftqDepth ≥ 24`. Any change to RING or BUF_WORDS must keep
    this `require` meaningful.
20. **RING=3 exceeds what a 2-cycle cache needs** for II=1 (two outstanding suffice, with
    full-ring turnover credit `FA:762-763`). Removing the third slot would save a few flops and
    shrink ring-index muxes; throughput effect should be checked against ITLB-miss / backpressure
    patterns before changing.
21. **Predictor invalidation in shipping**: RAS and gshare are wired only to the external
    `invalidateAll`, which `M68kSocketTop` ties to False (`SocketTop.scala:496`, `FCS:187,246`),
    so their clear logic is dead in the bitstream. BTB/FTB are cleared on every CPUSH/CINV of
    the I or both caches (`FCS:158-161`), including CPUSHL/CINVL, since the I-cache has no
    line-scoped invalidate.
22. **Stale in-code statements found while reading** (code is authoritative): the
    `IcachePlugin` header claims "FSM: IDLE → REFILL → REPLAY → IDLE" (`IC:19`), the FSM has
    eight states; `BtbPlugin` comment "index = pc[1+idxBits downto 2]" (`Btb.scala:53`) vs code
    `idxLo = 1` (PC[7:1]); FA's ring commentary describes a 3-stage cache pipe, the code has a
    2-cycle cmd→rsp path; `ChunkPredecode` comments mention 9-bit and 5-bit widths, the code
    yields 8 bits.
