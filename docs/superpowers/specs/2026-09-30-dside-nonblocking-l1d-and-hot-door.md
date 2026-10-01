# D-side read bandwidth: a non-blocking L1D over the `axi_dh` hot door

**Status: stage 1 implemented behind default-OFF flags; simulation validation and bandwidth
measurement in progress, 2026-10-01.** Owner's brief: *"i'd go balls to the wall on bandwidth, it's crazy to have such a
slow core on fast DDR."* Design principle
(`maximize-speculation-hot-fast-cold-slow-2026-09-30`): keep the hot path fast and speculative,
let rare cases be slow, and don't add complexity that isn't needed.

Parent documents: `2026-09-29-throughput-architecture.md` (§2.1 the split, §2.4, §2.5, rows
P6/P7/P10), `2026-07-30-mshr-multi-outstanding-design-proposal.md` (§12.6-12.7 on branch
`perf/dside-mshr2`), and SoC `docs/l2c_spec.md` "The hot/cold memory split" plus `cpu_socket.vh`
on `feat/p6-dside-hot-door`.

Branch: `perf/dside-bw` = `e98a322c` (P1 default) + `ee3d3ae1`/`aece26b6` (shipping-harness
fix) + `662b5029` (lock-step lazy-val fix) + `feat/d4-inhibited-barrier` `8ffcf4fd`.

Every number is either cited to a measurement or marked **ESTIMATE** with its derivation.
SoC cycle counts come from a register-stage trace of the RTL, not from a measurement, and are
marked **TRACE**.

---

## 0. Summary

1. **The L1D becomes non-blocking.** A file of **N = 4 MSHRs** handles every *cacheable* miss,
   for loads and for copyback store write-allocates. Each MSHR holds one 16-byte line, its own
   refill buffer, a byte-strobed store-merge buffer, and a reserved victim way. While a miss is
   outstanding the cache keeps accepting loads. Hits are answered, same-line loads join the
   in-flight MSHR as **secondaries**, different-line misses take another MSHR, and stores to an
   in-flight line are **merged into its buffer and acknowledged at once**. Refill data answers
   every waiter straight from the MSHR buffer (fill-forward generalised to secondaries), then
   installs into the array in one write.
2. **Refills leave by the hot door.** An MSHR's AR goes out on `axi_dh` (read-only, 128-bit, ID =
   MSHR index, 2 bits). The route is chosen by the miss's MMU cache mode, never by an address
   decode. Everything else stays on the in-order cold path `axi_d`: inhibited accesses (the
   unchanged legacy FSM, fenced by D4), writethrough and precise store writes, and dirty-victim
   writebacks (from a 4-entry writeback buffer).
3. **There is one cross-path hazard, and it is closed locally.** A per-line **writeback-address
   gate** holds an `axi_dh` AR for line X while any cold write to X (a victim writeback, or a
   writethrough store) is still waiting for its B. The SoC trace confirms B means "in the L2
   array" (§6.3).
4. **The honest ceiling is well short of 8 B/cycle.** ESTIMATE: 0.33 → ~0.7-1.0 copy-B/cycle on
   `move.l` memcpy (2-3x). The next binding limits are:
   - **the LS EU's 4-entry aligned-load ring:** load MLP ≤ 4 × access-bytes / 16 lines, which is
     **one line** for `move.l`;
   - **the one-outstanding cold write path:** ~8-9 cycles per 16-byte writeback, so ≤ ~1.8 B/cycle
     for any copy that dirties lines;
   - **LS issue:** one LS uop per cycle, so ≤ 2 B/cycle for `move.l` copies.

   8 B/cycle needs 64-byte sectors on both doors, line-granular copy instructions (MOVE16 /
   coalesced full-line stores), N ≥ 8 and a deeper ring (§11). This note builds the enabler and
   states each wall with a falsifier. It does not claim the target.

---

## 1. What limits bandwidth today (measured)

| fact | value | source |
|---|---|---|
| `memcpy-16k` copy bandwidth | **0.3278 B/cyc** (48.81 cyc per 16 B line); fill-forward 0.3421 | `MemcpyBandwidthSpec`, `IPC_MEM=l2:5:60:4096` |
| decomposition | three **serial** trips per line: T1 src refill, T2 dst write-allocate refill, T3 dirty writeback | `DcachePlugin` `loadMissStoreBarrier` note; fill-forward saved exactly one refill's 2.04 cyc/line |
| D-side MLP | **exactly 1.000** | `perf/dside-mshr2` §12.6 |
| same-line customer during a refill | **90.5% of refill cycles** (44,442 of 49,102) on grouped `L,L,L,L,S,S,S,S`; 110 on interleaved | §12.7 |
| instruction order alone | **+20.1%** (0.3401 → 0.4085) | §12.7 |
| lever 17, writeback/refill overlap | **−0.72%** | `f4a7464c` |
| `oldestUnready` | 83% of memcpy cycles | GOAL.txt |

**Why lever 17 bought nothing (and why this design is not lever 17 again).** Lever 17
overlapped T3 with T1 inside a machine that still ran **one excursion at a time**, with the store
pipe frozen for every load refill (`storePipeHeld` ⊇ `loadMissDiscovered`, `loadMissStoreBarrier`).
It also ran in a sim where a write costs nothing: `AxiMemModel` has no writeback cost on its L2
side (`872e7faf`). It removed a term that was neither on the critical path nor costed. The lesson
recorded in GOAL.txt applies here too: **price against `oldestUnready`, not transaction count**.
So every claim below is priced against the serial chain (T1 → T2 → T3 → the next load) that the
design breaks, and each has a falsifier that reads the stall budget (`IPC_STALL_BUDGET=1`), not a
traffic counter.

---

## 2. Architecture

```
                 loadCmd ─► S0/S1 (tag compare) ─┬─ hit ─► S2 ─────────────────────────┐
                                                 │                                    ▼
                                                 └─ miss ─► ALLOC STAGE (flops only)  loadRsp
                 storePort ─► S0 ─► S1 ─► S2 ─┬─ hit ─► S3 RMW write (unchanged)      ▲
                                              └─ miss ─► ALLOC STAGE / MSHR merge     │
                                                                                      │
   ALLOC STAGE ──► MSHR file (N=4) ──AR (ID=k)──► axi_dh ──► L2 hot door             │
        │          [line, set, way, rbuf, mbuf+strb, state]  ◄──R (by ID)──           │
        │                │  └─► response stage (1 waiter/cycle, reuses missLine mux) ─┘
        │                └───► install (one array write, from flops)
        └──► victim invalidate + WB buffer (4) ──AW/W (D_PUSH)──► axi_d ──► xbar ──► L2 cold door
   legacy FSM: INHIBITED loads only (cold, D4-fenced)          WB gate: hold AR(X) while any
   legacy store AXI: WT / inhibited writes (cold)              cold write to X awaits B
```

What stays exactly as it is: the S0/S1/S2 load pipe, the early-probe queue, the store S0-S3 RMW
pipe for hits, the maintenance walk, the inhibited sub-transaction sequencer, and the WT store AXI
path. What the flag replaces: the legacy REFILL-allocate and REPLAY paths for *cacheable* misses,
`pendingStoreMiss`, `loadMissStoreBarrier`, `storeMissBarrier` and `EVICT_WR` for cacheable
victims. With the flag ON those arms are Scala-conditional (not elaborated), so the array
write-port muxes swap one writer for another rather than gaining one (§9).

---

## 3. The MSHR file

### 3.1 Sizing: why N = 4

- **Customers.** Load MLP is bounded by the LS EU aligned ring (4 entries, `LsEuPlugin`
  `alignedDepth = 4`; `DLoadRid.Width = 2`). Stores add at most about one write-allocate line in
  flight on a streaming copy, because the SQ drains in order. With `move.l` that is ~1 src line +
  ~1 dst line; with a line-granular load (MOVE16, `needsLine`) it is up to 4 src lines. N = 4
  covers every shape the current LS EU can produce.
- **Fabric.** The hot door has **4 IDs** (`CPU_SOCKET_AXI_DH_IW = 2`, 4 = `AxiIds.dRefill(0..3)`).
  L2 **stalls its whole front door** on a second outstanding miss with the same ID
  (`ord_now_block_c`, `l2c_ctrl.v:1833-1860`, TRACE). So ID = MSHR index, and an ID is never
  reissued until its R has been received, which the MSHR state machine guarantees by construction.
  `MAX_BULK_AHEAD = 4` while scanout is active also caps L2→DDR fills at 4.
- **Little's law.** At an L2 hit, T_fill ≈ 12-14 cycles (§8), so 4 × 16 B / 13 ≈ **4.9 B/cycle**
  of read bandwidth is available. That is more than the ring can consume (§10). N > 4 needs a
  deeper ring *and* more hot-door IDs. The parameter is plumbed so it can grow, but 4 is the
  build.

### 3.2 Entry

| field | bits | note |
|---|---:|---|
| `state` | 3 | FREE, WAIT_AR, WAIT_R, FILLED, LINGER |
| `line` | 28 | physical line address `paddr[31:4]`. CAM key for every merge and the WB gate |
| `set` | 7 | = `line[6:0]` (VIPT-safe, `offBits + setBits = 11 ≤ 12`), kept as its own register for D3-SET |
| `way` | 2 | the victim way reserved and invalidated at allocation |
| `rbuf` | 128 | refill data, written by the R beat with this ID |
| `mbuf` / `mstrb` | 128 / 16 | store-merge bytes and strobes |
| `mdirty` | 1 | a COPYBACK store merged, so install dirty |
| `fault` | 1 | the R response was not OKAY |
| `noFill` | 1 | full-strobe store allocate: never issues an AR (§7.4) |
| `wbBlocked` | 1 | registered WB-gate verdict (§6.3) |
| `lingerCnt` | 2 | LINGER countdown |

~320 FF per entry, ~1,290 for N = 4.

### 3.3 States

```
FREE ──alloc──► WAIT_AR ──AR fire──► WAIT_R ──R(id=k)──► FILLED ──install──► LINGER(2) ──► FREE
   └──alloc(noFill)──────────────────────────────────────► FILLED
```

- **WAIT_AR**: eligible for AR issue when `!wbBlocked`. One AR per cycle; lowest index wins.
  `arReg` is a holding register, so AR payload is stable from VALID to READY (AXI rule).
- **FILLED**: data is present in `rbuf` (plus `mbuf`/`mstrb`). Waiters may be answered. Install is
  pending (§5).
- **LINGER**: the line is in the array but the entry stays CAM-visible for 2 cycles. This covers
  every access whose array read happened before the install write and whose compare happens after
  it (§4.4). An entry is FREE only after LINGER expires **and** it has no waiter.

**Every state field has exactly one owner and one writer site per transition.** No flag is set
in one state and cleared in another (the `evictAxiPairOpen` one-way latch lesson). A
simulation-only liveness monitor fails the run if any entry sits outside FREE for more than
`MSHR_LIVENESS_LIMIT` cycles (default 20,000), and the same for a WB entry and a waiter.

---

## 4. Primary and secondary handling; merge rules

### 4.1 Where a miss is discovered, and the allocation stage

A load miss is discovered at S1 (`ldS1Valid && !ldS1Hit`, `ldS1Cmode ≠ INHIBITED`). A store miss
is discovered at S2 (`stS2Valid && !stS2HitAny`). An early-probe miss is not used for allocation
in v1: it falls through to the S1 read as today. §12 lists the probe-miss early AR as a follow-up.

The BRAM-compare result `ldS1Hit` drives **exactly one flop**, `stgValid`, plus the staging
payload registers. Those payload registers **load every cycle with no clock enable**:

- `stgLine`, `stgSet`, `stgOff/Size/Token/Rid/RidV/LineOnly/ooOk`;
- `stgVictimWay` = `victim(ldS1Set)`, which is flops and needs no hit;
- `stgVictimLine/Tag/Dirty/Valid` = `rdData/rdTag/rdDirty/rdValid(victimWay)`, with the existing
  `victimFromS3` / `victimFromS3D1` bypasses.

The allocation decision happens **one cycle later, from flops only** (§9). It costs one cycle of
miss latency and in return keeps the tag-compare cone off every wide clock enable. That trade
follows `200mhz-tail-is-control-broadcast` directly: `tagMem → victimEvictLine[*]/CE` is one of
the five recurring tail hubs today.

At the allocation stage, in priority order:

| case | condition (all registered) | action |
|---|---|---|
| **secondary** | CAM hit on entry k in WAIT_AR / WAIT_R / FILLED / LINGER | add a waiter pointing at k (§4.3) |
| **primary** | no CAM hit, free entry, **no valid entry with the same set** (D3-SET), victim clean or a WB slot free, not `maintBusyReg` | allocate k: invalidate victim way (valid:=0, dirty:=0), push a dirty victim to the WB buffer, state WAIT_AR |
| **cannot allocate** | otherwise | the load goes to the **replay slot** (the existing `loadShadowCmd`, reused). While it is occupied, `loadCmdPort.ready` is dropped (a registered term). It relaunches through S1 when the blocking condition clears |

A load and a store can both reach the allocation stage in the same cycle: store S2 and load S1
share the read port and never coincide (the existing
`assert(!stS2Valid)` at `loadMissDiscovered`), but store S2 can coincide with load staging one
cycle later. Only one allocation happens per cycle. **The load wins; the store holds in its
pending-allocation register** (§4.2) and retries next cycle. It then sees the load's new entry
through the CAM, which removes the same-cycle same-line duplicate-allocation race by
construction.

### 4.2 Stores

Stores are committed (they drain after retirement), so merging them into an MSHR and
acknowledging them is architecturally a completed write. At S2/S3:

| store | lookup | action |
|---|---|---|
| any | array hit | unchanged S3 RMW (WT also writes through on `axi_d`) |
| COPYBACK | miss, CAM hit k (WAIT_AR/WAIT_R/FILLED) | **S3 merge** into `mbuf/mstrb` of k, `mdirty := 1`, **ack at S3** |
| COPYBACK | miss, no CAM hit | latch into `stAllocPending` (the successor of `pendingStoreMiss`, holding the S2 victim snapshot exactly as `pendingVictim*` does today). Allocate at the next free allocation slot, then S3-merge and ack |
| COPYBACK, `strb = 0xFFFF` | miss, no CAM hit | allocate **noFill** (no AR ever issued, §7.4), merge, ack |
| WRITETHROUGH | miss, CAM hit k | S3 merge into k **without** `mdirty`, **and** the unchanged AXI write-through; ack on B (unchanged) |
| WRITETHROUGH | miss, no CAM hit | unchanged: no allocate, AXI write |
| INHIBITED | — | unchanged serial path |

- **Why WT must merge.** A WT store to line X that misses while X's refill is in flight can reach
  L2 *after* the hot read has already read L2. Without the overlay, the refill installs stale bytes
  into L1 and every later load hits them. The WT write still goes to memory, so the line stays
  clean.
- **While `stAllocPending` is set**, S1 → S2 advance is held (`storePipeHeld` gains one registered
  term). Stores stay strictly in order, which is what keeps `storeAck` single-source (§7.1).
- **The existing `(!inputCopyback || wtOutstanding === 0)` admission rule is kept.** It is what
  makes a WT B and a copyback S3 ack mutually exclusive in time.

### 4.3 Waiters (who may merge, and in what order)

The waiter table is **rid-indexed**: 4 entries, one per LS ring slot, `{valid, mshr, off, size,
lineOnly, token}`. There is one extra **serial slot** for a non-reorderable command. The
`DLoadCmd` contract governs who may take which:

- **`ooOk` commands** (ordinary aligned ring loads) may be answered in any order relative to each
  other and to older outstanding accesses. That is exactly what `ooOk` permits, and the ring parks
  and completes in order (`alignedDone`/`alignedRData`). They can be primaries or secondaries at
  any time.
- **Non-`ooOk` commands** (split-pair halves, the exception sequencer 0x80, both walkers
  0x81/0x82) are **accepted only when no waiter is pending, no replay is pending, `!ldS1Valid` and
  `!stgValid`**. While one is outstanding, **no further load command is accepted**. That is
  precisely today's in-order contract, and it also keeps the LS EU's positional `ldFifoTags` class
  assert true: responses are never reordered across requester classes. It is a **cold path** by
  design (walkers and exceptions). §10 counts it with `serialWaitCycles`, and its falsifier says
  when to relax it.
- **rid reuse is safe.** The LS EU never re-sends ring slot r while the cache owes it a response.
  A flushed slot is poisoned and still waits for its response (`alignedPoisoned`), and a slot pops
  only on `alignedRspFire`. A simulation assert pins this in the cache: a `loadCmd` with a valid
  rid r fires while waiter r is valid → FAILURE.
- **Squash** does nothing to MSHRs or waiters. The line fill is side-effect-free, the answer to a
  poisoned slot is discarded by the LS EU, and the cancel port only touches the probe queue.

### 4.4 Ordering against older stores, inhibited accesses, faults, flushes and maintenance

| hazard | rule | enforced by |
|---|---|---|
| a load secondary answered from `rbuf ⊕ mbuf` after the array has moved on | a store that drains while a load waits is **older** (stores drain post-retire; a waiting load has not retired), and does not overlap the load's bytes: an overlapping older store would have forwarded or stalled the load at SQ check. The same premise fill-forward and the early-probe path already rely on. The load-past-unresolved-store case is LS-OoO's detect-and-replay, unchanged | argument + the shadow stress (§13) |
| a load/store read the array before an install and compares after it | the entry is still CAM-visible in LINGER (2 cycles ≥ S0→S2), so the access becomes a secondary (load) or merges nothing, because install is held while a store in S1/S2 targets the set (store) | install hold (§5), LINGER |
| a store merges while its MSHR installs | **install is held** while any store in S1 or S2 targets the entry's set, or S3 writes the entry's way. That is `refillWriteHold` generalised, with the same three terms. A merge and an install can never straddle | install hold |
| a store to the victim's OLD line, read before the allocation invalidated the way | store S1 advance is held for one cycle when `stgValid && stS1Set === stgSet` (flops). The store's read then happens after the invalidate, it misses, and it takes the ordinary miss path | store hold. This is the **same-cycle store unseen by the victim-dirty check** bug class, designed out; a sim assert fails any S3 array write into an invalid way |
| a pending store allocation vs a load that fills its way with another line | stores never hold a way: they merge by **line CAM** into an MSHR that owns its way, and D3-SET forbids a second fill in the set | D3-SET + CAM. This is the **pending store miss whose way was refilled with another line** class, designed out; a sim assert at install checks that the reserved way is still invalid |
| inhibited accesses | D4 launches an inhibited access only when `busQuiesced`, which now also requires **MSHR file FREE, WB buffer empty, no waiter, no replay, `!stgValid`**. The legacy FSM (inhibited only) and the MSHR file are therefore never active together | D4 (`CPU_INHIBITED_FULL_BARRIER`, **required** by elaboration when this flag is on) |
| refill bus error (window-guard DECERR, SLVERR) | no install; every waiter answered with `fault = 1`; if a store merged, the store bytes are dropped and `diagFault` kind 1 is raised, **the same semantics as today's write-allocate fault path** (`storeAllocAckReg` + diag) | response stage |
| maintenance (CPUSH/CINV) | `dcIdleForMaint` also requires MSHR file FREE, WB empty, no waiter. New allocations are refused while `maintBusyReg` (loads go to replay; the store allocation holds) | quiesce terms |
| reset | MSHR/WB/waiter valids `RegInit(False)`; a hot-door `AxiReadResetAbsorber` in `axiPorCd` drops R beats for ARs issued before reset, the same as `axi_i`/`axi_d` (`SocketTop.scala:337-353`) | absorber |
| multi-hot S1 result | treated as a miss exactly as today. The primary path's `dupPurgeFire` repair is unchanged. A multi-hot line is never CAM-merged, because a multi-hot means the line IS in the array | unchanged |

---

## 5. How refill data reaches waiters, and install

**Response stage.** This is a single registered response slot (`mshrResp*`). **It reuses the
existing `missLine`/`missOff`/`missSize`/`missToken`/`missRid`/`missRidV`/`missLineOnly` registers
and the existing `missLineResp` output mux arm.** So the load-response data path into the LS EU
gains no mux input. In ON mode the legacy FSM uses those registers only for INHIBITED responses,
and D4 makes those exclusive with MSHR activity (asserted).

- Each cycle, if the slot is free (or fired this cycle), it picks one waiter whose entry is FILLED
  or LINGER. The pick is round-robin over flops.
- It loads `missLine := rbuf(k) ⊕ (mbuf(k), mstrb(k))` (a per-byte 2:1 after a 4:1 line select;
  flop → flop) plus that waiter's fields.
- It pulses into the port when `!ldS2Resp` (a flop). Otherwise it holds. This is the one-Flow rule
  fill-forward already uses.
- **Starvation is bounded structurally.** Only the 4 ring loads can occupy S2 back to back, and
  when the ring head is the waiting load the ring fills and S2 empties. A sim assert fails if a
  waiter is ready for more than 64 cycles.

**Install.** Install happens when an entry is FILLED, not faulted, and none of the following hold
(all flops):

- `stS1Valid && stS1Set === set`;
- `stS2Valid && stS2Set === set`;
- `stS3ArrayWrite && stS3Way === way` (and `stS3Set === set`);
- another entry's allocation invalidate this cycle on the same way.

It then writes `dataMem(way)(set) := rbuf ⊕ mbuf`, the tag, `valid := 1`, `dirty := mdirty`, and
advances `victim(set)`. It pulses `mshrArrayWrite`/`mshrArrayWriteSet`, which OR into
`earlyProbeSetWriteVec`/`allocRacesArrayWrite` so probe entries on that set go stale (the
`missArrayWrite` idiom). The allocation-time victim invalidate pulses the same pair. After
install the entry enters LINGER.

- **Install is not blocked by waiters.** Waiters keep draining from the buffer after install;
  §4.4 row 1 is why that is correct.
- **Starvation escape.** If an install has waited more than 16 cycles, a registered
  `installStarve` drops `storePort.ready` until it lands.

---

## 6. Eviction, writeback, and the cross-path gate

### 6.1 Victim at allocation, not at install

The victim way is chosen and **invalidated at allocation** from the staging snapshot. This keeps
the victim data capture on the S1 read that already happened: an install-time victim would need a
new array read, and that means a new `rdEn` driver, which is the cone that disabled
`hitUnderMissRead`.

- **Clean victim:** invalidate only.
- **Dirty victim:** its 128-bit line and tag are pushed into the **WB buffer** in the same cycle.

The way is dead for the duration of the fill (~12-75 cycles), which costs a quarter of one set's
capacity for that time. Accepted. The falsifier is `victimReuseMiss`: a miss on a line
invalidated less than 128 cycles earlier, above 1% of misses.

### 6.2 WB buffer

Four entries `{line, data}` drain FIFO on `axi_d` with ID `D_PUSH`, one outstanding (the
crossbar allows exactly one write per master anyway, `axi_xbar.v:2057-2061`). The entry pops on
B. Its AW/W pair joins the existing mutual-exclusion web (`storeWantsAxi`, `evictAxiPairOpen`,
`maintAxiPairOpen`): the WB pair *is* `evictAxiPairOpen` in ON mode.

- **The single writer rule.** The pair-open bits are set False **only** at the WB head's kickoff
  and set True only by its own AW/W handshakes. Any walk that might skip `EVICT_WR` is gone.
- **B error:** `diagFault` kind 2, as today.

Why 4 entries: a pending store plus a load in staging and a store in S2 can each
consume a slot before the next store is admitted. The depth reserves those concurrent
allocations while a previous writeback's B is still outstanding (TRACE, §6.3).
WB-full holds allocations, which is counted.

### 6.3 The writeback-address gate

Rule: **hold `axi_dh` AR for line X while any cold write to line X has been issued, or is queued
to issue, and its B has not returned.** The sources are the WB buffer entries and the
writethrough FIFO `wtFaultFifoAddr[pop..push)`, which already holds line addresses.

- **Why B is the right event.** SoC TRACE: for cacheable RAM, B comes **only from L2**, on the
  same edge as the array write (hit, or fully-strobed install), or after an MSHR replay for a
  partial-strobe miss (`l2c_ctrl.v:2534-2566`, `l2c_mshr.v:696-766`). The crossbar forwards B
  combinationally (`axi_xbar.v:2400-2402`). There is no posted write anywhere on the path. After
  B, a later hot read is ordered behind the write by L2's in-order rigid pipeline, `set_haz_c` and
  `victim_query_hit`.
- **AW or W handshake is NOT enough.** L2's full-line gather buffer (`flw_data`) is outside the
  cross-door guarantee and treats a gathered line as cold traffic that hot reads outrank
  (`l2c_ctrl.v:673-679`, `728`). It arms only for 64-byte bursts, so it is irrelevant at 16-byte
  lines and load-bearing at 64-byte sectors (§11).
- **Timing and AXI stability.** `wbBlocked(k)` is registered, so AR selection must also
  check the *current-cycle* WB/WT sources, including an S3 WT push. Otherwise AR and
  that push can cross the same edge, and an exact AR-time gate would withdraw an
  already advertised `ARVALID` under backpressure. Once an AR is selected, its VALID,
  address, and ID stay fixed until handshake. An S1 WT store to its line is held until
  that handshake; WT stores already in S1/S2 are included in the selection check.
  Later same-line WT bytes may merge into the MSHR before its response (§4.2).
  D3-SET prevents a later dirty victim of that line while the MSHR lives. Sim
  assertions check the exact outstanding WB and WT addresses at every AR fire.

### 6.3a Stage-2 amendments to §6 (as built)

- **WB depth is 4, not 2.** The store S1 resource check must reserve a slot for every
  allocation that can land before the store's own (the load staging, a store in S2, the
  store staging), so a 2-deep buffer would stall stores whenever one writeback is in
  flight. 4 x 156 FF.
- **Install is held by a store in S1 only when that store could advance this cycle**
  (`!nbStoreHold`). Holding it unconditionally deadlocks: a store held in S1 for want of an
  MSHR in its set waits on an install that waits on the store.
- **The victim invalidate is deferred** (`invPend`, one per cycle), because store S3 owns the
  dirty-bit port of that way on the allocation cycle. Every store is held in S1 while any
  invalidate is pending, so no store can write the evicted line between snapshot and
  invalidate.

### 6.3b The WB buffer's memory side is ONE narrow interface (re-pointable to the write door)

The SoC now has a write half on the hot door (`DW_PORT_EN`, branch
`feat/p6w-dside-write-door`; `tb_l2c` memcpy at L2: 2 outstanding writes = 4.56 B/cycle vs
2.29). The WB buffer therefore talks to memory through exactly these signals, today bound to
`axi` AW/W/B with ID `D_PUSH` and one outstanding:

| signal | meaning |
|---|---|
| `wbKick` | head entry may issue (today: the `axi` pair-open exclusion) |
| head `{line, data}` | one 16 B line, all strobes set, `awlen=0`, `awsize=4`, INCR |
| `wbB` (+resp) | the head's B, popping the FIFO; non-OKAY = diag kind 2 |

Re-pointing to the write door (a later flag) changes only the binding: AW/W on `axiDh`
(2 write IDs, up to 2 outstanding, AW may run ahead of W, B in order per ID). The contract's
extra rules and where each lands here:

- **Extended WB gate.** Hold a read of line X on *either* port while any write of X on
  *either* port lacks its B. The hot-read side is `coldWriteTo()` already, and it just gains
  the write-door entries. Reads on `axi` are only inhibited, and those are D4-fenced.
- **Never two writes to one line on different ports.** Hold a WT store kickoff for line X
  while the WB holds X, and a WB kick of X while a WT write of X lacks its B. Both are line
  CAMs over structures that exist (`wbLine`, `wtFaultFifoAddr`).
- **D4.** `nbIdle` already requires the WB empty, and it gains "no hot write without its B".
  No hot write may issue while an inhibited access is outstanding: the legacy FSM being out
  of IDLE, or a serial inhibited store.
- **Only copyback victims and full-line (`noFill`) installs** use it. WT and uncached writes
  stay on the crossbar.

### 6.4 The four known bug shapes in this interplay, and where each goes

| today's bug (`dcache-sectored-wedges-silicon`, `lever1-no-write-allocate-is-incorrect`) | this design |
|---|---|
| one-way latch with a skippable re-set (`evictAxiPairOpen`) | single-writer state per entry; liveness monitor per MSHR/WB/waiter |
| same-cycle store unseen by the victim-dirty check | staging snapshot keeps the S3/S3D1 bypass; store S1 held on `stgSet`; assert: no S3 write into an invalid way |
| pending store miss whose way was refilled with another line | stores own no way; line-CAM merge; D3-SET; assert: reserved way still invalid at install |
| `storeAck` two sources in one cycle | every store completion is in-order and local to S3, or WT B with copyback admission gated on `wtOutstanding === 0`; plus the counted ack interface (§7.1) |

---

## 7. Interfaces

### 7.1 Store completion (for the write-path agent; replaces the untagged pulse)

```scala
case class DStoreAck() extends Bundle {
  val count = UInt(2 bits)   // 0..2 descriptors completed THIS cycle
  val err   = Bits(2 bits)   // err(i): the i-th of those completed with a bus error
}
// DcacheService: def storeAck: DStoreAck   (registered)
```

- **Semantics:** acks are **in acceptance order**, so the `count` oldest outstanding descriptors
  (in `storePort` handshake order) completed. The SQ frees from its head by `count`. That needs no
  tags and no SQ restructuring, and two sources in one cycle become representable instead of lost.
- **Producers:** `cbHitAck`/`mshrMergeAck`/`allocAck` (all at S3, at most one per cycle), plus
  `storeBAck && stSubLast` (WT/inhibited B).
- **Invariant, asserted:** a B ack is never younger than an S3 ack in the same cycle. The
  copyback-behind-WT admission rule guarantees this today. `count ≤ 2` is asserted, and
  `count = 2` is expected never to occur today. It exists so a future admission relaxation (a
  copyback store admitted behind an un-B'd WT store) is a counter change, not a lost ack.
- **Compatibility:** `storeAckReg` stays as `count =/= 0` for single-ack consumers until the SQ
  side switches. A sim assert fails if `count > 1` while any consumer still uses the pulse.
- **Upgrade path, not proposed now:** a tagged ack (`DStoreCmd.tag` = SQ slot, echoed) for
  out-of-order store completion. Nothing in this design completes stores out of order, so the tag
  would cost SQ area for no reachable behaviour.

### 7.2 Loads

`DLoadCmd`/`DLoadRsp` are **unchanged**. `ooOk`, `rid` and `token` already carry everything this
design needs. `loadBusy` becomes `busy || mshrAny || wbAny` (registered). Its only in-core consumer
is the exception unit on the FullCoreSynth build.

### 7.3 `axi_dh` (the socket, coordinated two-repo change)

| name (socket) | dir | width | value |
|---|---|---|---|
| `axi_dh_arid` | out | 2 | MSHR index k |
| `axi_dh_araddr` | out | 32 | `line ## 0000` (critical byte irrelevant at 16 B) |
| `axi_dh_arlen` | out | 8 | 0 (16 B); 3 for 64 B sectors (§11) |
| `axi_dh_arsize` | out | 3 | 4 |
| `axi_dh_arburst` | out | 2 | INCR (WRAP for 64 B sectors) |
| `axi_dh_arvalid` / `arready` | out / in | 1 | |
| `axi_dh_rid/rdata/rresp/rlast/rvalid` | in | 2/128/2/1/1 | `rdata` through `SocketByteOrder.permuteData` like `axi_i` |
| `axi_dh_rready` | out | 1 | **constant 1**: every entry in WAIT_R owns its buffer |

The SoC binds these to `dha_ar*`/`dha_r*`, `CPU_AXI_DH_IW = 2` and `dha_rready_gated`
(`fpga_top_ddr.vh:257-298` behind `L2C_DH_PORT`, TRACE). Core side:

- `DcachePlugin.logic.axiDh = master(Axi4ReadOnly(128b, id 2))`, elaborated only when
  `dcacheHotDoor`. It is plain `master`, like `IcachePlugin.axi`, independent of `socketMerged`.
- SocketTop adds a FULL register slice on AR and R (the port-boundary rule) and a third
  `AxiReadResetAbsorber`.
- `tools/socket/check_socket_netlist.py` gets the `axi_dh_*` allowlist and shape rows.
  `fullcore_ports.golden` is untouched because FullCoreSynth keeps the flag off.
- **Build coupling:** `CPU_AXI_DH=1` in the SoC Makefile must also define `L2C_DH_PORT` and
  `DH_PORT_EN=1`. The CPU's `SHIPPING_CONFIG` echoes `dcacheHotDoor`. The SoC build must fail
  (not silently tie off) if the generated `M68kSocketTop.v` has `axi_dh_*` ports and
  `L2C_DH_PORT` is undefined, or the reverse.
- **Routing is by construction, not by a mux on an address.** An MSHR is only ever allocated for
  a miss whose `cmode ∈ {COPYBACK, WRITETHROUGH}`, so every MSHR AR is a hot-door AR. With
  `dcacheHotDoor` OFF, the same ARs go out on `axi_d` with `AxiIds.dRefill(k)`. AxiDMerge
  serialises them (single-outstanding per direction), which is correct and slow. That arm isolates
  what the non-blocking cache is worth on today's fabric (§13 C4).
- **A mis-marked cacheable device access fails loudly.** The SoC window guard answers DECERR
  (`ifetch_window_guard.v:173-209`, TRACE). That arrives as `fault = 1` on every waiter → a
  vector-2 physical bus error through the existing LS EU path. It drains all hot reads first and
  blocks the door while pending (TRACE). That is fine for a boot-time mistake; the guard's
  `fault_count` is the counter to read.

### 7.4 Full-line store without a fill (the write-path agent's hook, native here)

The write-path agent's contract (`perf/wr-coalesce`, `DCACHE_FULLLINE_NOFILL` on the legacy FSM)
is: "a copyback store miss with all 16 strobes set allocates the victim way with tag, valid,
merged data and dirty, WITHOUT any read; the dirty victim is written back first; the ack comes
from the same local source as write-allocate".

Here that is **an MSHR allocated in FILLED with `noFill`**. It never issues an AR, and its install
follows the ordinary §5 rules. Its dirty victim goes to the WB buffer at allocation; "written back
first" is satisfied because any later *read* of that victim's line is WB-gated until B. It is
acked at S3 like every other copyback store. Loads that CAM-hit it before install are answered
from `mbuf` (strobes full).

Optional (flagged, default 0): **`storeAllocArDelay`**, which holds a store-allocated entry's AR
for D cycles so trailing stores to the same line can complete the strobes. The AR is then
cancelled (the entry becomes `noFill`), so coalescing works even when the SQ could not coalesce.

---

## 8. Latency (ESTIMATE, with derivation)

Board, L2 hit, from S1 miss detect (cycle 0):

| cycle | event |
|---:|---|
| +1 | staging → alloc |
| +2 | `arReg` |
| +3 | socket AR slice |
| +3…+9 | SoC hot door AR → R (TRACE: `dh_ar_pend` C0 → `u_dh_r_skid` C6) |
| +10 | socket R slice |
| +11 | `rbuf` |
| +12 | response slot → `loadRsp` |

**T_fill ≈ 12-13 cycles.** Today it is ~20 (the marginal L1D-miss → L2-hit cost, measured with the
model L2 at 5 cycles, `latency-microbenchmarks`). Sim (`IPC_MEM=l2:5`, zero crossbar in the
model): ≈ 11-12.

This design does **not** shorten the path by removing the crossbar hop. It is an ~7-cycle
difference, but it comes mostly from the REPLAY relaunch disappearing (fill-forward generalised),
the AxiDMerge + crossbar single-outstanding path disappearing, and the FSM excursion; −1 for the
staging cycle.

---

## 9. 200 MHz plan

The tail is a wall of ~110 near-tied paths whose hubs are **control registers driving wide
CE/reset nets** (`TIMING_200MHZ_TAIL_2026-09-28.md`). Rules applied:

1. **The tag-compare result drives one flop** (`stgValid`, plus the replay decision one cycle
   later). All staging payload registers load unconditionally, with no CE, so the D input mux
   selects on flops only. This **removes** the `tagMem → victimEvictLine[*]/CE` hub in ON mode
   (legacy `victimEvictLine` is used only for inhibited misses, which have no victim).
2. **Every MSHR/WB/waiter write enable is a function of flops**: the staging valid, the registered
   CAM result, the R-slice valid+ID and S3 flops. No MSHR field is enabled by `ldS1Hit`, `rdEn`,
   `useEarlyProbe` or `doFlushReg`.
3. **The CAM is computed at the staging stage**, flop against flop (4 × 28-bit). `wbBlocked` is
   registered. The AR pick is registered into `arReg`.
4. **No new `rdEn` driver.** The MSHR subsystem never reads the array. The replay relaunch reuses
   the existing shadow-slot launch site, which ships.
5. **`loadCmdPort.ready` and `storePort.ready` gain only registered terms** (`replayValid`,
   `serialHold`, `stAllocPending`, `installStarve`).
6. **No new mux input on the load-response data path** (the §5 register reuse). The array
   write-data mux swaps the REFILL/REPLAY writers for one install writer, so it does not grow.
7. **Wide-fanout controls are replicated per entry,** not broadcast: each entry has its own
   `installGo(k)`, and the response pick is an OH over entries.
8. `missCmode[0] → LsEuPlugin s1Base/R` (a tail hub) is unchanged in fanout. In ON mode
   `missCmode` is only written by inhibited misses.

**Falsifier:** in the 200 MHz lane, any `DcachePlugin_logic_mshr*`, `stg*` or `wb*` net appears
among the worst 110 paths, or among the hubs of any path ≤ 0.020 ns.

---

## 10. What it is worth (ESTIMATE, with derivation), and what it is not

Sim regime `IPC_MEM=l2:5:60:4096`, 16 B lines, T_fill ≈ 12, one LS uop per cycle. "Ring" = the LS
EU aligned-load ring depth (4 today). MLP = mean outstanding line refills over cycles with at
least one.

| kernel | today | MSHR, LS in-order | MSHR + LS-OoO, ring 4 | + ring 8 | binding wall |
|---|---|---|---|---|---|
| `memcpy-16k` (`move.l (a0)+,(a1)+` ×4) | 0.328 B/c, MLP 1.00 | **~0.6-0.7**, MLP ~1.3-1.6 (src + dst write-allocate overlap; T2/T3 leave the chain) | **~0.8-1.0**, MLP ~1.8-2.0 | ~1.4-1.6, MLP ~3 | cold writeback ≈ 16 B / ~9 cyc ≈ 1.8 B/c (TRACE); LS issue 2 B/c |
| `memcpy-grp-16k` (L,L,L,L,S,S,S,S) | 0.41 | ~0.8-0.9 (the 90.5% same-line customers become secondaries) | ~0.9-1.0 | ~1.5 | same |
| stream (new `kStream`: `add.l (a0)+,d0`, 64 KB) | ~0.6 (ESTIMATE: 20 + 3×~2 cyc/line) | ~1.0 (loads always ready; ring = 1 line) | ~1.0 | ~2.0 | ring; then 4 B/c at one load/cycle |
| `chase-128` | MLP 1.00 | **unchanged ±3%**, MLP 1.00 | unchanged | unchanged | dependent loads (negative control) |
| DDR regime (`l2:5:240:64`), memcpy | ~3 serial trips of ~75 | ~2x (T1 ∥ T2) | ~2x | ~3x | MAX_BULK_AHEAD 4 |

**The reasoning behind each cell:**

- **In-order LS issue on interleaved memcpy:** L(k+1) cannot pass the unready S(k), whose data is
  L(k)'s result (§12.7(a)). So only one src line is ever missing. What MSHRs remove is T2
  (write-allocate becomes merge + immediate ack) and T3 (async WB) from the serial chain, plus the
  REPLAY relaunch. The ~24 cycles per line left are T_fill + 3 dependent hits + the loop.
- **With LS-OoO, the ring is the cap.** Four `move.l` loads fill the ring with the four longs of
  one line. The next line's first load waits for a ring slot, which frees only when that line
  fills. Per line that is ≈ T_fill + ~4-5 issue cycles, i.e. ~17. **The MLP a move.l stream can
  expose is ≤ ring × 4 B / 16 B = 1 line, however many MSHRs there are.** This is a new,
  code-derived bound (`LsEuPlugin` `alignedDepth = 4`; a missing load holds its slot until
  `alignedRspFire`). It has not been measured, so its falsifier is C2 below.
- **The cold write path is the next wall.** Every copied line eventually becomes a dirty 16 B
  writeback on a one-outstanding path that takes ~8-9 cycles per write (TRACE). That is ~1.8 B/c
  at the SoC, before the socket slices.

**So the enabler alone moves memcpy 2-3x, not 25x.** 8 B/cycle needs all of:

- N × 16 B / T_fill ≥ 8, so N ≥ 6-7 at an L2 hit;
- ring ≥ N × 16 / access-bytes;
- a write path ≥ 8 B/c: 64 B bursts (a 4-beat AW/W carries 4x the bytes for about the same ~9
  cycles, ≈ 7 B/c) or multi-outstanding cold writes;
- a copy instruction that is ≤ 2 LS ops per 16 B (MOVE16, or SQ-coalesced full lines, plus a
  line-granular load).

§11 is where the first three change. The fourth belongs to the write-path agent and the MOVE16
conformance work (P10).

### 10.1 Counters (sim `simPublic` + a debug-CSR block for the board)

The block is `OFF_DC_MSHR_*`, one cycle lagged, per `debug-instrumentation-latency-is-free`:

- **Occupancy:** `mshrBusyCycles[n]`, a histogram of 0..4 entries valid; MLP = Σn·c[n] / Σ(n≥1)c[n].
- **Allocation:** `primaryAllocs`, `loadSecondaries`, `storeMerges(cb/wt)`, `noFillAllocs`.
- **Allocation failures:** `allocFail{full, setConflict, wbFull, maint}`.
- **Waits and the cold path:** `replayCycles`, `serialWaitCycles`, `wbGateCycles`, `lingerHits`,
  `installHoldCycles`, `victimReuseMiss`.
- **Door traffic:** `hotAr`, `hotBeats`, `coldWb`, `wbFullCycles`, `respHoldCycles`.

The bench prints them as `[mshr]`, and the counters are included in the stall budget's
conservation check.

⚠ **Instrument hygiene** (three prior null-disguises): the print is unconditional whenever the
flag is on. A test asserts `primaryAllocs > 0` and `loadSecondaries > 0` on `memcpy-grp-16k`, so a
silently discarded counter fails loudly.

---

## 11. What 64-byte sectors would change later (not built now; sectoring stays OFF)

`CPU_DCACHE_SECTORED` still corrupts data on silicon (`dcache-sectored-wedges-silicon`, round 3).
This design targets 16 B lines. What moves when sectors come back:

- **MSHR = line, 4 sectors.** Per-sector `rbuf` (4 × 128 bits, ~2 kFF more at N = 4), or install
  per beat as it arrives and keep only the demanded sector buffered. `mstrb` becomes 64 bits.
- **AR = WRAP, `arlen = 3`, demanded sector first** (the SoC hot door accepts WRAP). A waiter on
  sector j is answered when beat j lands.
- **R beats interleave across IDs** (TRACE: `l2c.v:1053-1069` has no burst lock). So each entry
  keeps its own beat counter, selected by RID. That is new control.
- **The WB becomes a dirty-sector push.** Either one beat per dirty sector (not gather-eligible),
  or the whole line as a 4-beat burst. The latter is gather-eligible at L2, and that is exactly
  when **the WB gate on B stops being optional** (§6.3, the `flw_data` caveat).
- **The CAM and WB gate are line (64 B) granular; merge and valid are sector granular.** CINVL
  stays sector-exact, as in the ratified amendment.
- **The payoff is 4x bytes per MSHR and 4x bytes per cold write transaction at the same N and the
  same ID count.** It is the lever that makes the §10 walls move. It must be re-certified against
  the sectored silicon corruption first, and this subsystem's shadow stress (§13) is the gate that
  would have caught the two round-3 bugs.

---

## 12. Deliberately not in v1, each with its trigger

| item | why not now | trigger to build |
|---|---|---|
| way reservation (>1 fill per set) | D3-SET is simpler and memcpy src/dst share sets only per-index | `allocFail.setConflict` > 5% of allocations on page-aligned memcpy |
| early AR from a resolved probe miss (§12.2 of the MSHR doc) | saves ~2 cycles; the victim can be captured later at the command's S1 read | T_fill measured > 14 in sim |
| non-`ooOk` reordering (walkers past waiters) | needs a class-aware `ldFifo` in the LS EU | `serialWaitCycles` > 2% of cycles on `dhrystone-x0-cb`/corpus |
| hot-door writes | SoC rejected a second write door (W-owner lock) | `wbFullCycles` > 10% with ring 8 |
| deeper ring / `DLoadRid.Width = 3 or 4` | descriptor, response association, and positional FIFO must resize together | a checked independent-load workload shows the 4-entry ring limits distinct-line MLP |

### 12.1. Load concurrency amendment (2026-10-01; default remains 4)

The first integrated `memcpy-16k` run with four MSHRs, the hot door, LS out-of-order issue,
and SQ coalescing issued 3,810 refill ARs while an older victim writeback B was pending,
but retired only 0.3167 copy B/cycle and measured 1.078 distinct-line MLP. That kernel
offers little independent load work; overlapping bus transactions alone does not establish
a throughput gain. Acceptance includes four independent pointer chains (separate address
and destination registers), a checked sequential load stream, and grouped copy. A single
dependent chain is the negative control. Compare the legacy blocking cache (one demand
miss, explicitly a different architecture), then this cache with N=2 and N=4 MSHRs,
with LS out-of-order issue on in every arm.

The aligned-load descriptor ring is parameterized at 4, 8, or 16 entries, default 4.
`DLoadRid.Width = log2(ringDepth)`, the park buffer equals ring depth, and the positional
load-source FIFO is the next power of two covering `ringDepth + 3` (exception, ITLB, DTLB).
The cache waiter table is `2^DLoadRid.Width + 1` (one extra serial slot). Split-pair
admission reserves two ring slots; the response-token check, flush poisoning, inhibited
ordering, and ring-wrap liveness assertions remain required at every depth. The ROB and
issue queue do not change. A larger ring is accepted only if checked data, unique active
line misses, ring occupancy, outstanding AXI IDs/requests, IQ ready-but-blocked and
retirement blocking counters, cycles/instruction, and bandwidth show more useful
concurrency under the same modeled latency. `CPU_LS_LOAD_RING_DEPTH` controls this sweep
and remains 4 in the shipping default.

**Inhibited-probe credit rule (2026-10-01).** An early VIPT probe for a load whose
translation resolves to `INHIBITED` is retired at resolve, before that load can enter
the inhibited park. The serial device command does not use the cache-array snapshot
and can later take the ordinary resolved command path. This retirement is independent
of the tokenized fault/squash cancellation Flow: a translation fault for another load
can coincide with the inhibited resolve. Keeping such tokens while parked exhausted
the five-entry probe queue with six parked loads at ring depth 8, blocking an older
ready load from issuing and producing an `OLDEST-LS-STARVED` liveness failure. The
ring-depth acceptance test must fill the park and observe replay, not merely reach its
pass sentinel.

The first paired full-core sweep used `IPC_MEM=l2:5:60:4096`, LS out-of-order issue,
seed 1, and checked final data on every kernel. `chase-four` traverses four disjoint
256-record pointer cycles, warming L2 on the first walk; `stream-16k` reads sequential
longs through four independent accumulators. Both report useful bytes in the measured
window, with identical instruction counts across arms. These are simulation results,
not board measurements. Bytes/cycle and CPI use the post-warmup commit window;
the `[mshr]` MLP and `DSIDE_CONCURRENCY` occupancy/ID observations currently
cover the whole kernel run, including warmup, and are labelled as such in logs:

| cache / ring | four-chain read B/cyc | four-chain distinct-line MLP | stream read B/cyc | stream distinct-line MLP |
|---|---:|---:|---:|---:|
| legacy blocking / 4 | 0.3651 | one demand miss | 0.9407 | one demand miss |
| nonblocking N=2 / 4 | 0.3262 | 1.381 | 0.9352 | 1.000 |
| nonblocking N=4 / 4 | 0.6552 | 1.797 | 0.9352 | 1.000 |
| nonblocking N=4 / 8 | 0.6595 | 1.836 | 1.0600 | 1.551 |
| nonblocking N=4 / 16 | 0.6595 | 1.836 | 1.1001 | 1.549 |

N=4, ring 4 is 1.79x the legacy four-chain throughput and 2.01x N=2, with four
AXI hot IDs simultaneously outstanding. Ring 8 improves the sequential stream by
12.7% over legacy and enables a second distinct active line; ring 16 adds 3.8% over
ring 8 while measured ring occupancy still peaks at eight. The dependent single-chain
negative control stays at 0.4453 B/cycle across arms. The `oldestUnready` window falls
from 20,345 of 22,435 cycles in legacy four-chain to 10,955 of 12,503 in N=4/ring4.
This demonstrates latency hiding on offered independent misses. It does not imply a
general memcpy gain: scalar memcpy remained 0.3167 B/cycle and 1.078 MLP in the first
integrated run. Ring 8 is an experimental throughput candidate; its area/timing and
broader correctness gates remain separate from these measurements.
With seed 17, N=4/ring 8 again checked all data: four-chain reached 0.6577 B/cycle
with four hot IDs outstanding, stream reached 1.1305 B/cycle with ring occupancy
eight and two hot IDs, and the dependent chase stayed at 0.4453 B/cycle. The
16-entry ring also passed the split-load enqueue and split/ordinary ring-wrap
regressions, but the current full-core kernels used at most eight slots.

---

## 13. Claims and their falsifiers (recorded before any RTL)

| # | claim | measure | falsified if |
|---|---|---|---|
| C1 | MSHRs + merge ≥ 1.8x `memcpy-16k` copy-B/c with LS in-order | `MemcpyBandwidthSpec`, 2 seeds, flag OFF vs ON | < 1.3x → the chain is not the refill; re-attribute with `IPC_STALL_BUDGET` before building more |
| C2 | load MLP on a `move.l` stream ≤ 1 line at ring 4, even with LS-OoO | `[mshr]` occupancy histogram, `kStream` | mean outstanding src-line refills > 1.3 → my ring-cap analysis is wrong |
| C3 | chase is a negative control | `chase-128` cycles, MLP | cycles move > 3% or MLP ≠ 1.00 → a counter or ordering bug |
| C4 | on today's fabric (`axi_d`, AxiDMerge single-outstanding) the non-blocking cache still pays most of C1 | flag ON, `CPU_AXI_DH=0` vs `=1` | DH adds < 3% → the hot door is purely an enabler at ring 4 (expected, and not a failure of the design); DH=0 ≥ DH=1 → a door bug |
| C5 | store write-allocate leaves the critical path | `stAllocPending` cycles, store-pipe stall share | > 20% of cycles → allocation resources (MSHR/WB/set) are the new wall |
| C6 | the cold writeback path is the next wall | `wbFullCycles` with ring 8 (when built) | < 5% → the wall is elsewhere; re-price |
| C7 | D3-SET conflicts are rare | `allocFail.setConflict` / allocs on page-aligned memcpy | > 5% → build way reservation |
| C8 | LINGER / install-hold / replay are cold | `lingerHits`, `installHoldCycles`, `replayCycles` on corpus + bench | any > 1% of cycles → a "cold" path became hot (principle rule) |
| C9 | no 200 MHz regression | 100 MHz lane WNS/area, then 200 MHz tail census | the §9 falsifier |
| C10 | correct | §14 stress (fails first on each mutant), corpus + lock-step by name, board boot | any new name, any mutant surviving, any board corruption |
| C11 | OFF is byte-identical | generate twice, canonicalise `_lNNNN` names, structural diff | any difference |
| C12 | board bandwidth | `tools/board_membench.sh` (SoC repo) at 100 MHz | < 1.5x membench copy with `CPU_AXI_DH=1` |

**Area (ESTIMATE):**

| item | FF | LUT |
|---|---:|---:|
| MSHR entries | ~1,290 | |
| WB buffer (4 entries) | ~620 | |
| waiters + serial slot | ~90 | |
| staging | ~330 | |
| `arReg` + socket slices | ~350 | |
| CAMs (staging, S2, WB gate) | | ~300 |
| byte-merge + line-select for install/response | | ~400 |
| control | | ~400 |
| AR/R plumbing | | ~150 |
| legacy arms removed in ON mode | | −~200 |
| **total** | **~2.7k** | **~1.1-1.6k** |

0 BRAM, 0 DSP. The flow's LUT noise floor is ~800 (`PERF_LEVER_QUEUE` rule 6), so area is a
guard, not a verdict.

---

## 14. Verification plan (stage 3, summarised)

1. **Shadow stress.** Port `DcacheSectorSpec` test 6 (`fix/dcache-sectored-wedge`) as
   `DcacheNonBlockingSpec`, flat 16 B, and take over `DcacheMissMergeSpec`'s token-attributed
   multi-outstanding load groups (agent-28, `05437638`). Add:
   - **several outstanding loads per group** (the old stress waits for each response), with
     `ooOk` mixed with non-`ooOk`;
   - **memory `rspMode` `Reordered` and `Chaos`**, so R returns out of order across IDs;
   - **forced D3-SET conflicts, WB-full and MSHR-full;**
   - **WT and CB on one line, and full-strobe stores** (the noFill path);
   - **CPUSH/CINV while MSHRs are busy;**
   - **window-guard-style DECERR on the hot door.**
   - **same-line delayed visibility:** in a test-only memory mode, hold dirty-victim W
     bytes invisible until B; a hot load of the evicted line must issue no AR before B
     and must then return the new bytes. The normal memory model remains write-before-B.

   It must **fail first** on each of 10 single-mechanism mutants:
   1. no install hold on store S1/S2 set;
   2. no WB gate;
   3. no D3-SET;
   4. no LINGER;
   5. waiter answered at the primary's offset;
   6. no S3 bypass in the victim snapshot;
   7. store merge drops `mdirty`;
   8. ID reissued before R;
   9. serial rule off;
   10. no store S1 hold on `stgSet`.
2. **Mechanism counters** fire (`primaryAllocs`, `loadSecondaries`, `storeMerges`, MLP > 1.2 on
   `memcpy-grp` with LS-OoO).
3. **Bench:** `MemcpyBandwidthSpec`, `MissMergeBandwidthSpec`, a new `kStream`, and
   `IPC_MEM=l2:5:60:4096` and `l2:5:240:64`. The hot-door model is attached with
   `AxiMemModel.attachReadOnly(..., sharedMem = dmem.mem)`, and the L2 residency state is shared
   between the two engines (a small test-side refactor; today each engine keeps its own, which
   would misclassify hot hits). On one continuous dirty-miss stream, count refill ARs
   while an earlier victim's B is pending and report AR initiation intervals, `failWb`
   (demand allocations blocked by WB capacity), `wbFull` (raw occupancy), and `wbGate`
   (same-line read wait). No maintenance drain is inserted between stream samples.
4. **`make test-fast`** 400/0/2, both arms.
5. **Corpus and lock-step**, OFF vs ON, **by name** (explicit Python set difference, never `comm`)
   under `FUZZ_SHIPPING=1` / `LOCKSTEP_SHIPPING=1`. These harnesses attach the hot door when
   `CPU_AXI_DH=1`, and `ShippingConfigParitySpec` guards the new fields.
6. **Board, 100 MHz:** `board_membench.sh`, then boot with exc-halts 2/3/4 armed and Finder
   detected in the framebuffer. The owner decides when the board is probed.

---

## 15. Coordination owed (through the PM)

- **Write-path agent:** confirm §7.1 (counted in-order `DStoreAck`) and §7.4 (noFill native, the
  `storeAllocArDelay` hook). Its legacy-FSM hook `DCACHE_FULLLINE_NOFILL` and this flag are
  mutually exclusive at elaboration.
- **LS-OoO agent:**
  - D4's `busQuiesced` gains the MSHR/WB/waiter terms (D4 memory: "the bus-quiet check must move
    from P4 to the ring send" for P7; that is theirs).
  - Ring depth / `DLoadRid.Width` is the next MLP wall (C2).
  - The positional `ldFifoTags` assert stays valid under the §4.3 serial rule. If they relax it,
    the serial rule can relax too.
- **Same-line-merge agent (agent-28):** its stress harness and `MissMergeBandwidthSpec` are
  adopted. `CPU_DCACHE_MISS_MERGE` (the legacy-FSM merge) and this flag are mutually exclusive,
  and this flag subsumes it.
- **SoC (`feat/p6-dside-hot-door`):**
  - `L2C_DH_PORT` + `DH_PORT_EN` from one Makefile knob, with the consistency check of §7.3;
  - the `dha_*` binding in `fpga_top_debug_ctrl.vh`;
  - a `cpu_stub` tie-off;
  - `dh_guard_fault_count` exposed in a CSR.
- **Flags:**
  - `CPU_DCACHE_NONBLOCKING` (default 0), with `CPU_DCACHE_MSHRS` (default 4) and `CPU_AXI_DH`
    (default 0; requires NONBLOCKING);
  - NONBLOCKING requires `CPU_INHIBITED_FULL_BARRIER=1`, `!sectored`, `!hitUnderMissRead` and
    `!dcacheMissMerge`, checked at elaboration;
  - all three are echoed in `SHIPPING_CONFIG` and default from `ShippingCoreConfig`, so the parity
    spec covers them.
