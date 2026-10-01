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
FREE ──alloc──► WAIT_AR ──AR fire──► WAIT_R ──R(id=k)──► FILLED ──install──► LINGER(3→0) ──► FREE
   └──alloc(noFill)──────────────────────────────────────► FILLED
```

- **WAIT_AR**: eligible for AR issue when `!wbBlocked`. One AR per cycle; lowest index wins.
  `arReg` is a holding register, so AR payload is stable from VALID to READY (AXI rule).
- **FILLED**: data is present in `rbuf` (plus `mbuf`/`mstrb`). Waiters may be answered. Install is
  pending (§5).
- **LINGER**: the line is in the array but the entry stays CAM-visible for at least the
  3→0 countdown, four sampled state cycles in the current RTL. This covers
  every access whose array read happened before the install write and whose compare happens after
  it (§4.4). An entry is FREE only after LINGER expires **and** it has no waiter.
  The earlier two-cycle wording understated the implemented residency; do not
  shorten it without a directed S0→S2 stale-read and waiter-lifetime proof.

#### 3.3a Experimental pipeline-aware LINGER release (2026-10-01 amendment)

`CPU_DCACHE_NB_DYNAMIC_RELEASE=1` is an independent default-OFF experiment for
non-blocking mode. It may retire an **installed, nonfaulted** LINGER entry as
soon as all of these are false: a waiter still references its index, a waiter
is added to it this edge, a registered load S1 for its set, and a registered
staged-miss decision for its set. The fixed 3→0 countdown remains byte-for-byte
the default behavior. Faulted fills keep their existing LINGER(0) rule. The
experiment uses the existing entry state, S1/staging set registers, and waiter
table; it adds no state and no path into the CPU read response or BRAM read
enable. The two set comparisons feed only the MSHR state-register D input.
At the four-MSHR configuration the source adds at most eight 7-bit set
equalities (S1 and staged set against each entry), plus their state gating;
synthesis may share some equalities. This is a source-level upper bound, not a
mapped LUT or Fmax result.

The edge contract is: an install write and a BRAM read may meet at edge T, so
that read can observe the old tag. From T to T+1, `ldS1Valid/ldS1Set` identify
it. At T+1, a miss is captured into `stgValid/stgSet`, which identify it from
T+1 to T+2. The CAM-visible MSHR must survive both stages; at T+2, its
same-line `lAddW` captures the secondary waiter. A read launched only after
edge T sees the installed tag, while an early VIPT probe retained across T is
invalidated by `earlyProbeSetWriteVec`/sticky `earlyProbeStale`, including a
probe allocated on T. Store S1/S2/S3 and pending-store same-set hazards already
prevent installation if they could carry a pre-install store read. The response
slot holds a copied line independently after its waiter bit clears. An entry
made FREE at an edge cannot be reallocated at that same edge because
`freeBits` observes the old `st` register value; no bypass is added.

Acceptance requires a cycle-exact install/read collision that becomes a
secondary, a staged miss held across attempted release, same-edge waiter add,
multiple waiters draining through the copied response slot, backpressured
response, fault/no-fill/store conflict and reordered-refill checks. A mutant
that removes the S1-set guard while retaining the staged-set guard must fail a
checked stale-read test, not merely elaboration. A staged-set-only mutant may
pass because same-line `lAddW` protects the transition on the staged-decision
edge; that guard remains a conservative set-local hold for delayed stage/replay
interactions, without a claimed standalone mutation proof. The experiment
should be benchmarked only if exact
`failFull` snapshots show installed LINGER entries are a meaningful part of
capacity pressure; a FILLED or WAIT_R wall cannot improve by retiring LINGER.

The first default-OFF evaluation used the integrated NB4/ring8/hot-door
configuration with LS-OoO, fusion, speculative wakeup, P3 fast load, early
probe forwarding, early response, eager AR, and preselected AR all enabled;
direct-refill response stayed OFF. Both arms ran the same seed-1, 1024-record
independent-chain kernels and `l2:5:60:4096` memory model. Four chains changed
from 10,600 to 10,552 measured cycles (0.7758 to 0.7794 B/cycle); eight
chains changed from 12,185 to 10,677 cycles (0.6749 to 0.7703 B/cycle).
The eight-chain full-MSHR attempt count fell from 1,545 to 1,464 and mean
LINGER occupancy at those attempts from 0.384 to 0.198. Both arms checked
8,224 output bytes per kernel. These are simulator results, not post-route
area/timing or board IPC. Logs: `/tmp/codex-nb-dynamic-release-bench-off.log`
and `/tmp/codex-nb-dynamic-release-bench-on.log` from agent66 commit
`a1f36283`. A second seed-17 eight-chain control changed from 12,075 to
10,760 cycles (0.6811 to 0.7643 B/cycle), with the same checked byte count;
logs are `/tmp/codex-nb-dynamic-release-seed17-off.log` and
`/tmp/codex-nb-dynamic-release-seed17-on.log`. The directed legal-port
collision sweep checked 17 responses and one hot AR per line, including a
COPYBACK store merged before R, a same-line second load at install, a resident
reread, and final CPUSH. Removing only the S1-set guard made that second load
return stale backing-memory bytes `0x50515253` instead of the merged
`0x77777777` (`/tmp/codex-nb-dynamic-release-mut-s1-dirty.log`). Removing only
the staged-set guard passed the earlier clean-line collision test, so no
separate necessity is claimed for that guard.

Integration checkpoint `113d0d43` passed default `test-fast` (404 tests),
precise-store/payload-ownership checks (7 + 3 tests), and the full NB cache
suite (18 tests). The cache gate enabled dynamic release, preselected/eager
AR, early response, P3 fast load, probe forwarding and direct refill together,
with NB4/ring8/hot door and seeds 1–4 at 1000 operations per stress arm.
Exact source, counts and logs are in `/tmp/codex-agent59-dynamic-results.json`;
runner `/tmp/codex-agent59-dynamic-integrated-gates.py`. This also integrates
the separately specified original-VA split-store fault fix. Dynamic release
remains default OFF; these gates do not establish mapped area or routed timing.

A follow-up seed-17 test-only cross-tab retained the exact 10,760-cycle,
8,224-byte result (`/tmp/codex-nb-hol-cross-tab-seed17.log`). Of 891 full-ring
cycles waiting for the head response with a younger response already parked,
850 included a younger load already written back/woken. All 108 such cycles
with a younger completed load still needing writeback had an early-writeback
fire. These sets overlap; their counts must not be added. Only 102 of the
891 cycles had any IQ-ready load, and 210 coincided with full-MSHR rejection.
The existing `alignedEarlyWb` path therefore already overlaps younger result
use with the older miss. This does not justify another completion mechanism
or larger ring; recovering ring credits early is a separate ownership and
area question. The temporary cross-tab instrumentation was reverted after
measurement, and is not part of the integration source above.

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

### 6.3c Experimental early AR selection (2026-10-01 amendment)

`CPU_DCACHE_NB_EAGER_AR=1` may remove one registered allocation-to-AR
selection bubble; it defaults OFF and only exists with `CPU_DCACHE_NONBLOCKING=1`.
The allocation edge writes the new line, state `WAIT_AR`, and any dirty
victim into the WB FIFO. On the following cycle, the candidate may use that registered line
without waiting for `settled` or the **previous line's** registered `blocked`.
It must still check the exact current `coldWriteTo(line)` sources (WB entries,
WT FIFO, and simultaneous WB/S3 WT pushes), current S1/S2 WT stores, and the
configured store-allocation AR delay. The 16-byte full-strobe COPYBACK store
starts in `FILLED` and remains a no-AR case. Selection remains registered in
`arV/arIdx/arAddr`; no new combinational path reaches the CPU read stage or
the AXI AR output. Once advertised, ARVALID/address/ID remain fixed through
ARREADY backpressure, and a newer same-line WT writer is held until handshake.

The safety argument is edge-local: a WB push on the allocation edge is visible
as `wbV/wbLine` on the following selection cycle, while a push on that selection
cycle is visible in `coldWriteTo` combinationally. An S3 WT at either edge is
likewise visible in the WT FIFO or the
current S3 predicate; an older S1/S2 WT is explicitly excluded. Thus the stale
registered `blocked` bit adds delay but no ordering information. The separate
`settled` bit supplies no data dependency once C0 has latched `line`, victim
data, and `WAIT_AR`. This option changes only selection timing, not allocation,
WB occupancy, install, response, or same-line merge rules. Acceptance requires
an exact command-C0 and allocation-to-AR OFF/ON test, AXI backpressure and delayed-B tests, live
dirty WB/WT hazards, noFill and store-delay cases, randomized cache stress, and
matched dependent-chase throughput. If any safety assertion or checked-data
test fails, the option stays OFF and is not a shipping candidate.
The simulation-only `wbGate` counter retains its legacy registered-gate meaning
with the option OFF; with it ON, it counts `WAIT_AR` candidates blocked by a
current WB or WT address (including S1/S2 WT) after any configured store-AR
delay. This avoids reporting a stale `blocked` bit as an actual eager-mode stall.

Isolated RTL measurement on base `5123593f` with the same 4-MSHR hot door,
4-slot load ring, and LS-OoO issue **OFF** in both arms: accepted load command
to advertised hot AR is 5 cycles OFF and 4 ON; MSHR allocation to advertised
AR is 2 OFF and 1 ON (`/tmp/codex-nb-eager-dualwb.log`). The matched five-lap
dependent chase (`MB_PLAN=l2:5:60:4096|2048,65536|chase`) checks the final
pointer: the 2 KiB L1-resident loop is 9.000 cycles/hop in both arms, while
the 64 KiB L2 loop is 21.993 OFF and 21.015 ON, a 0.978-cycle/hop reduction
(`/tmp/codex-nb-eager-chase-off.log`, `/tmp/codex-nb-eager-chase-on.log`).
Configured model L2 hit latency 5 yields measured hot AR-to-R of 6 cycles;
these measurements are paired internally and must not be numerically combined
with the separate LS-OoO-ON `l2:6:33` experiments. The 9-cycle L1 value is
the full dependent issue-to-next-issue loop, not SRAM latency. No board IPC or
post-route area is inferred from this simulation.

Two independent dirty-victim pushes cannot coincide through the current legal
cache ports. `sWbPush` is the first `pendingStoreMiss` allocation cycle; the
miss is discovered in store S2. `lWbPush` is the `stgValid` allocation cycle;
`stgValid` is registered from a load S1 miss. A store S1 advance and an ordinary
load-read launch are mutually excluded by `loadPortReserved`. A command served
by an existing early-probe hit bypasses the ordinary read and cannot produce
`stgValid`; a load shadow launch itself reserves the read port. Therefore the
store S2 miss and load S1 miss cannot occupy the same cycle, and their
corresponding allocation pulses cannot coincide on the next edge. Once
`pendingStoreMiss` is set, `loadCmdPort.ready` blocks later ordinary loads.
The FIFO retains its two-push handling and `coldWriteTo` retains both current
push terms for future port configurations. In the legal-port directed phase
sweep (`/tmp/codex-nb-eager-adjacentwb2.log`), 15 pairs each produced
two checked dirty-victim pushes, both orderings were observed, and the closest
pair was one cycle apart; no dual push occurred. The final acceptance test
requires two pushes per pair, both orderings, a one-cycle gap, checked load
data, exact request handshakes, and a drained cache. This is reachable corner
coverage, not a claim that the dual-push FIFO path was exercised.
The selected eager cache run (`/tmp/codex-nb-eager-final-suite.log`) executes
the timing, WT S1/S2/S3 with delayed B, store-delay/no-fill, adjacent dirty
victims, WB-full pressure/release, stalled AR stability, delayed WB visibility,
and four 1,000-operation hot-door chaos seeds. All selected checks pass with
checked data; inactive ScalaTest arms in the same suite are not counted as
mechanism coverage.

### 6.3d Matched early-response × eager-AR measurement (2026-10-01)

The isolated combined source is agent-67 at `e69ccfd6` plus eager-AR
cherry-picks `3bbfc3e0`/`69a3b3da`; the only additional changes are opt-in
benchmark settings and printed configuration. Every row below uses 4 MSHRs,
an 8-slot LS load ring, the hot door, LS-OoO issue ON, fused long-move loads ON,
speculative load wakeup ON, direct refill OFF, fill-forward OFF, seed 1, and
the same `l2:5:60:4096` memory model. The five-lap dependent chase covers
2 KiB resident and 64 KiB L2-working-set rings; the independent `chase-four`
uses 256 records and 768 iterations with all four final pointers checked.
Configured L2 hit 5 produces an accepted hot AR-to-R interval of 6 cycles
(baseline trace AR at cycle 85196, R at 85202); ARVALID at 85195 was
backpressured, so it is not the acceptance timestamp. Direct refill is
explicitly disabled in `MB_DIRECT_REFILL` and by `FullCoreDut`'s argument in
the D-side bench.

| NB early response | NB eager AR | 2 KiB chase cycles/hop | 64 KiB chase cycles/hop | Four-chain B/cycle | Four-chain IPC | WAIT_R MLP (active cycles, whole run) | Peak hot AXI requests | Log |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 0 | 0 | 7.016 | 20.004 | 0.6980 | 0.2621 | 1.912 | 4 | `/tmp/codex-eager-combined-00.log` |
| 0 | 1 | 7.016 | 18.958 | 0.7270 | 0.2730 | 1.949 | 4 | `/tmp/codex-eager-combined-01.log` |
| 1 | 0 | 7.016 | 18.958 | 0.7230 | 0.2715 | 1.939 | 4 | `/tmp/codex-eager-combined-10.log` |
| 1 | 1 | 7.016 | 17.986 | 0.7454 | 0.2799 | 1.991 | 4 | `/tmp/codex-eager-combined-11.log` |

Both options together cut the modeled dependent L2 loop by 2.018 cycles/hop
(10.1% fewer cycles, 11.2% more hops per cycle) relative to the matched
both-OFF arm. The independent four-chain workload improves from 0.6980 to
0.7454 B/cycle (+6.8%) while still reaching four simultaneous hot-door IDs;
the latency gain therefore does not trade away observed miss overlap in this
case. Four-chain ring occupancy is a whole-run state sample. The listed MLP
is `mlpSum/mlpCyc`: MSHRs in WAIT_R divided by **only cycles with at least
one WAIT_R**, accumulated over the whole run including cold warmup. It is
neither all-cycle occupancy nor the matched commit-window outstanding count;
peak hot AXI IDs is also a whole-run maximum. B/cycle, IPC, and cycles/hop
use their measured commit windows. No board IPC or
post-route area is inferred. The probe-forward/P3 option is absent from this
matrix and must be measured as a separate matched arm.

### 6.3e Experimental load-allocation AR preselection (2026-10-01 amendment)

`CPU_DCACHE_NB_PRESELECT_AR=1` is a separate, default-OFF experiment that
requires non-blocking mode and eager AR selection. On a legal load MSHR
allocation, the new line, slot index, and address are already available from
the registered staging snapshot and `lOH`. When the single AR holding register
is free (or its prior command fires), no older `WAIT_AR` candidate is eligible,
and live write-order checks permit the line, the allocator may put that load
directly into `ARQ` and latch its index/address into the existing `arV/arIdx/arAddr`
registers. A load rejected by allocation admission or hazard checks stays on
the ordinary eager `WAIT_AR` path. An already-advertised AR retains VALID,
address, and ID through ARREADY backpressure; an eligible older `WAIT_AR`
candidate keeps priority over a new allocation. No combinational signal from
this option reaches the CPU read response or the AXI AR outputs.

The preselection gate must use the **current** `coldWriteTo(lLine)` predicate,
which includes queued WB entries, both same-cycle dirty-victim pushes, WT
outstanding entries, and S3 WT pushes, plus the existing S1/S2 WT line check.
Preselection is one cycle earlier than eager selection, so it must also check
an accepted WT still held in S0 and any same-line WT presented at the store
input. A presented WT can be younger than the load, as the load-first directed
diagnostic demonstrated; the input guard deliberately delays the AR anyway.
For an older accepted WT, a pipeline hold can leave it in S0 at allocation,
then move it to S1 when the newly registered AR first becomes valid. This
conservative line check is local to preselection; the
normal eager path continues to retry after the writer drains. The two WB-push
terms remain even though legal current ports phase-separate their simultaneous
assertion (§6.3c), so future admission changes cannot silently break the gate.
Only `lAlloc` may preselect: a store allocation still obeys configured
`storeAllocArDelay`, and a full-line no-fill store still issues no refill AR.
`lOH` already excludes the simultaneous store-reserved MSHR slot, so the AR
ID must be derived from `lOH`, not an independent free-slot encoder.

Acceptance requires an exact load-command-to-AR advertisement change from
eager-only 4 cycles to preselect 3 cycles on a clean miss, stable ARVALID and
payload under backpressure, legally reachable WT S1/S2/S3 and delayed-B
visibility, a separately labeled S0/input gate proof (with legal held-S0
coverage identified explicitly if obtained), both reachable adjacent dirty-WB
push orders and the retained dual-push predicate, full WB admission pressure,
store-delay/no-fill invariance, randomized stress, checked full-core dependent
and independent-load measurements, and an area estimate for the added line
comparators. An untested S0 or simultaneous dual-push condition must be marked
as such, not counted as covered. A failed invariant keeps this option OFF;
the current eager selection remains the shipping candidate.

The opt-in selector uses no new register, but its `lLine` gate can add up to
15 28-bit line comparisons to the AR-register D cone: four WB FIFO entries,
two same-edge victim pushes, four outstanding WT entries, and WT S3/S2/S1/S0
and input. It also adds the allocated-slot/address choice after older
`WAIT_AR` candidates. These are source-level upper bounds, not measured LUTs
or Fmax; sharing/constant pruning depends on synthesis. A board build must
remain OFF until measured area and timing justify the cycle gain.

The legal-port held-S0 test (`/tmp/codex-nb-preselect-held-s0b.log`) accepts
an unrelated WT, then a WT to the target line, then the load. Test-only cold
AW backpressure holds the older write pipeline. At load allocation the target
WT is in S0 (`allocWithTargetS0=1`); the model observes six AWVALID/!AWREADY
cycles and no hot AR. After AW release the two WT writes B-ack, the hot refill
occurs only afterward, and the load returns the target WT's checked data.
The ordinary WT-before-load test separately covers S1/S2/S3 and delayed-B
visibility in both eager and preselected configurations. The `awReadyGate`
test-model hook defaults to true in every other test. The simultaneous dual
dirty-WB push remains structurally absent from the legal port schedule (§6.3c)
and is not claimed as covered.

The matched integrated full-core source is agent-67 `bf952978` plus
`25d80d08`/`d05ddcc0`/`808f0a2f` (preselection) and benchmark config print
`acfe37f1`. Both arms use NB4, load ring 8, hot door, LS-OoO, fused long-move
loads, speculative wakeup, P3 fast load, probe-line forwarding, early response,
eager AR, direct refill OFF, seed 1, and `l2:5:60:4096`. Only
`CPU_DCACHE_NB_PRESELECT_AR` differs. The memory model's configured L2 hit 5
corresponds to accepted AR-to-R 6 cycles; the per-cycle `MB_TRACE hotAR` field
shows ARVALID and can repeat under ARREADY backpressure. The directed timing
test proves command-to-AR **advertisement** 5/4/3 cycles for legacy/eager/
preselected selection. All pointer/data oracles passed.

| Preselect | 2 KiB chase cycles/hop | 64 KiB chase cycles/hop | Four-chain B/cycle | Four-chain IPC | Active-cycle MLP | Peak hot IDs | Log |
|---|---:|---:|---:|---:|---:|---:|---|
| OFF | 6.023 | 17.986 | 0.7457 | 0.2800 | 1.997 | 4 | `/tmp/codex-nb-preselect-matched-0.log` |
| ON | 6.023 | 17.002 | 0.7561 | 0.2839 | 2.097 | 4 | `/tmp/codex-nb-preselect-matched-1.log` |

Preselection removes 0.984 modeled cycles per dependent 64 KiB hop (5.47%
fewer cycles) and improves the checked independent four-chain stream by 1.39%
in B/cycle. The resident 2 KiB loop is unchanged. Four-chain B/cycle/IPC use
the commit window; MLP is MSHR WAIT_R occupancy averaged over whole-run cycles
that have at least one WAIT_R, and peak hot IDs is a whole-run maximum. These
figures do not establish FPGA area, Fmax, board IPC, or a gain when the bus is
already at its throughput limit. The option remains default OFF.

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

### Resident dependent-chain wakeup diagnostic (2026-10-01)

The 2 KB `BoardMembenchSimSpec` chase was measured with NB4, ring depth 8,
the hot door, LS out-of-order issue, `l2:6:33:4096`, and five laps. Its first
lap warms L1. The test-only `MB_FUSE_LONG_MOVE_LOADS` and `MB_SPEC_WAKE` knobs
were varied by name; all four arms checked the pointer-chain result:

| Fused long MOVE load | Speculative load wakeup | Cycles / 128 hops | Cycles / hop |
| --- | --- | ---: | ---: |
| OFF | OFF | 1152 | 9.000 |
| OFF | ON | 1152 | 9.000 |
| ON | OFF | 1025 | 8.008 |
| ON | ON | 898 | 7.016 |

In each arm, the 400-row steady trace showed a constant command-to-command
interval of 9, 9, 8, or 7 cycles respectively. Relative to a cache command
at cycle zero, the L1 response and guaranteed wakeup land at +1, registered
writeback at +2, and the next load's IQ-ready/issue at +3/+4 (unfused),
+2/+3 (fused), or +1/+2 (fused with speculative wakeup). The fused+speculative
commit window had 127 speculative announcements and zero IQ re-check hold
cycles. The unfused chain cracks through an intervening ALU `MOVEA`, which
speculative wakeup deliberately does not release; it targets LS load consumers.
`ShippingPlugins.decodeStage` already enables fusion when `ipcThroughput` is
on. `SPEC_LOAD_WAKEUP` defaults OFF and additionally requires `ipcThroughput`,
so seven cycles is an experimental flag result, not the current default.
These are core simulation timings, not whole-SoC or post-route measurements.


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

- **Occupancy:** `occ[n]`, a histogram of 0..4 allocated MSHRs in any state.
  The separate `MLP` print is `mlpSum/mlpCyc`: the mean number of entries
  in `WAIT_R`, conditional on at least one outstanding refill. Store-allocated
  refills count too; ARQ/FILLED/LINGER entries do not. It is not the mean ring
  occupancy and is not sampled only in the commit window.
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
the `[mshr]` MLP and `DSIDE_CONCURRENCY` occupancy/ID observations cover the
whole kernel run, including warmup, and are labelled as such in logs. The
`[ls-order-window]` row instead uses the same first-to-last macro-commit span
as bytes/cycle. `youngerReadyLoadPresent` is a slot-presence opportunity, not
blocked or lost cycles; `opportunityBypassSelected`, `opportunitySelectedToSkid`,
and `lsEuFire` provide selection, buffering, and acceptance evidence. The
whole-run ring counters additionally distinguish a full ring without a head
pop from the subset where a younger response is parked behind an outstanding
head (`ringFullHeadRspHolStateCycles`). Neither full-ring count requires an
incoming load demand, so neither is a rejected-demand or lost-cycle count.
These are overlapping observations, not an additive stall budget:

| cache / ring | four-chain read B/cyc | four-chain WAIT_R MLP, active cycles | stream read B/cyc | stream WAIT_R MLP, active cycles |
|---|---:|---:|---:|---:|
| legacy blocking / 4 | 0.3651 | one demand miss | 0.9407 | one demand miss |
| nonblocking N=2 / 4 | 0.3262 | 1.381 | 0.9352 | 1.000 |
| nonblocking N=4 / 4 | 0.6552 | 1.797 | 0.9352 | 1.000 |
| nonblocking N=4 / 8 | 0.6595 | 1.836 | 1.0600 | 1.551 |
| nonblocking N=4 / 16 | 0.6595 | 1.836 | 1.1001 | 1.549 |

The MLP columns are whole-run `mlpSum/mlpCyc`, conditional on at least one
MSHR in WAIT_R (§10.1); they include the cold warmup. The throughput columns
use commit windows. These are different populations and denominators, so the
MLP values alone cannot predict the displayed B/cycle.

N=4, ring 4 is 1.79x the legacy four-chain throughput and 2.01x N=2, with four
AXI hot IDs simultaneously outstanding. Ring 8 improves the sequential stream by
12.7% over legacy and enables a second distinct active line; ring 16 adds 3.8% over
ring 8 while measured ring occupancy still peaks at eight. That gain cannot be
attributed to using a ninth descriptor on this run; scheduling effects remain
possible. The dependent single-chain
negative control stays at 0.4453 B/cycle across arms. The `oldestUnready` window falls
from 20,345 of 22,435 cycles in legacy four-chain to 10,955 of 12,503 in N=4/ring4.
This demonstrates latency hiding on offered independent misses. It does not imply a
general memcpy gain: scalar memcpy remained 0.3167 B/cycle and 1.078 MLP in the first
integrated run. Ring 8 is an experimental throughput candidate; its area/timing and
broader correctness gates remain separate from these measurements.
With corrected instrumentation and the same seed-1 configuration, the ring-8
four-chain commit window had 779 cycles with an unready oldest LS slot and a
younger ready load; all 779 had an actual bypass load selection, none parked
in the IQ skid, and none lacked a selection. They were not blocked cycles.
The ring-8 stream had zero such opportunities, 4,098 IQ selections and 4,098
EU handshakes in its commit window, and 40 whole-run full-ring/no-pop state
cycles (zero with a younger response parked behind the outstanding head).
Ring 16 reproduced 1.1001 B/cycle with the same 4,098 selections/handshakes,
zero full-ring/no-pop state cycles, zero head-response HOL cycles, and a peak
ring occupancy of eight. The 40-vs-zero difference is state evidence, not a
causal upper bound on the 563-cycle commit-window difference: admission and
memory phase shifts can amplify it. No descriptor-reclamation bottleneck is
demonstrated by these runs.
With seed 17, N=4/ring 8 again checked all data: four-chain reached 0.6577 B/cycle
with four hot IDs outstanding, stream reached 1.1305 B/cycle with ring occupancy
eight and two hot IDs, and the dependent chase stayed at 0.4453 B/cycle. The
16-entry ring also passed the split-load enqueue and split/ordinary ring-wrap
regressions, but the current full-core kernels used at most eight slots.

**Integrated-source repeat, 2026-10-01.** Full-core source `c3706b52`
(`5123593f` plus the §14 mutation-evidence test commit) used
`CPU_DCACHE_NONBLOCKING=1`, `CPU_DCACHE_MSHRS=4`, `CPU_AXI_DH=1`,
`CPU_INHIBITED_FULL_BARRIER=1`, `CPU_LS_LOAD_RING_DEPTH=8`,
`IQ_LOAD_BYPASS=1`, and `IPC_MEM=l2:5:60:4096`. With seed 1 and checked
final data, `chase-four` again reached 0.6595 B/cycle, 0.2476 IPC,
four simultaneous hot AXI IDs, and whole-run active-WAIT_R MLP 1.836;
`stream-16k` reached 1.0600 B/cycle, 0.3978 IPC, ring occupancy eight,
and whole-run active-WAIT_R MLP 1.551. The dependent `chase-128` control reached
0.4453 B/cycle and whole-run active-WAIT_R MLP 1.021. Four-chain `failSet=87` and `failFull=failWb=0`
identify set conflicts rather than MSHR or writeback capacity in this run.
The source and checked metrics are in `/tmp/codex-bw-integrated-mlp-r8.log`;
the three active focused arms (`inhibitedProbeCancel`, `mergeDirty`,
`hotChaos`, seed 1/1,000 operations) passed in
`/tmp/codex-bw-integrated-focus.log`. These are simulation measurements;
ring-8 area/timing and board IPC remain unmeasured.

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

   **Mutation acceptance, 2026-10-01.** `tools/nb_mutation_proof.py` applies one
   source mutation in an isolated worktree, runs the named test, and restores the
   source in `finally`. The unmodified eight-seed cache suite passed all nine tests
   with checked data; the directed clean-load-MSHR store-merge test also passed.
   Each mutant below failed at runtime for the stated mechanism (none failed to
   compile). Mutants 1, 3–6, 8, and 10 used hot Chaos seed 1 with 4,000 operations;
   2, 7, and 9 used the named directed arm.

   | # | Fault removed | First failure |
   |---:|---|---|
   | 1 | install hold on store S1/S2 set | installed-line store merge tripwire: lost store |
   | 2 | WB address gate | hot AR while dirty WB awaits B |
   | 3 | D3-SET allocation gate | shadow line byte mismatch at `0x90844` |
   | 4 | LINGER state after install | two waiters at `0x92848/0x92842` never answered |
   | 5 | waiter-specific offset | first group returned `0x1910`, expected `0x9db5` |
   | 6 | S3 bypass in victim snapshot | byte at `0x9204f` returned `0xf4`, expected `0xdd` |
   | 7 | `mdirty` on store merge | directed CPUSH left old memory bytes after a merged CB store |
   | 8 | wait for R before ID reuse | AXI checker rejected duplicate AR ID 0 |
   | 9 | serial admission rule | serial load admitted before older load response |
   | 10 | store S1 hold on `stgSet` | store allocation violated D3-SET tripwire |

   A separate *shortened* LINGER mutant (`linger := 0` but still one LINGER
   cycle) survived this seed; the specified **no-LINGER** mutation above did
   not. This proves the state is necessary, but does not independently prove
   that its current three-cycle countdown is minimal.
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

## 14.1. Optional registered clean-refill response shortcut

`CPU_DCACHE_NB_EARLY_RESPONSE=1` (default OFF) may populate the existing
registered response slot on the cycle that a clean AXI R beat is accepted.
It is restricted to a single waiter for that MSHR, no store-byte overlay,
an OKAY R response, and an empty response slot. The response data, token,
RID, offset and size all enter the existing response registers; no AXI R
payload drives `loadRsp` combinationally. The selected waiter is cleared
exactly once. The MSHR still captures the R beat and transitions to FILLED
for normal install, so refills without an eligible waiter retain their
original behavior. A fault, merged store, multiple waiters, occupied slot,
or simultaneous higher-priority response uses the ordinary FILLED→response
slot path. Resident-hit response arbitration retains priority at `loadRsp`.
OFF must elaborate the unchanged response logic. Directed checks must cover
exactly-once tokens under resident-hit and refill collisions, fault and
store-overlay fallback, and held response-slot behavior.

The focused one-beat cache test measures accepted AXI R→`loadRsp` as two
cycles with this option OFF and one cycle ON. In the matched NB4 full-core
dependent chase (`MB_LS_OOO=1`, L2 model hit=6, DDR=33, 5 laps), the 2 KB
L1 case remains 9.000 cycles/hop and the 64 KB L2 case changes from 22.997
to 21.983 cycles/hop. Thus the L2-over-L1 gap remains 12.983 cycles, above
the requested six-cycle goal. Four-seed cache-only stress over reordered
cold and chaotic hot response modes found no cycle-count regression and
covered faulting loads, store merges, secondaries and replay. This is a
simulation result, not a post-route timing or whole-SoC latency claim.

## 14.2. Optional P3 ordinary-load ring admission experiment

`CPU_LS_P3_FAST_LOAD` defaults OFF and requires the inhibited full barrier.
With it ON, a translated P3 load may enter the existing aligned-load descriptor
ring without occupying P4 only if it is a
single-access, cacheable, privilege-legal ordinary load, the store queue is
empty (including no drain in flight), P4 and the inhibited park are idle,
there is no inhibited load in flight or pending preemption/flush/exception,
and the ring has one free descriptor. All other loads retain P3→P4 and the
registered SQ forwarding verdict. The fast path shares the ordinary ring
pointer, RID, send, response, fault, poison and probe bookkeeping; it creates
no new completion or cache-response producer. A refused cache command keeps
the same descriptor and token in the ring. An accepted fast command uses P3's
registered address/context for fall-through, never a stale P4 context.

The ordering argument depends on two existing rules: the IQ does not select a
younger load ahead of an older store, whether that store is ready or unready;
and the elastic LS frontend does not overtake its own P3 store. A P3 store
either allocates/reserves SQ state before leaving or completes without a data
access. Consequently an empty SQ with no P4/park owner excludes an older
unresolved store for this load. A detached late-data store still holds a SQ
reservation, so it excludes the fast arm. This proof must be checked against
the actual IQ and LS frontend gates before implementation and covered with
directed ordering tests. The shortcut is not valid for SQ forwarding, split,
inhibited, privilege-fault, or replay cases.

Speculative wakeup is optional and separately gated. A shallow P3-context
announce may replace P4's announce for the fast arm only when P4 is idle;
the existing IQ confirmation/recheck still determines when a dependent may
issue. The announce may precede ring admission, because a held dependent
cannot issue before the real completion confirms it. This announce must not
depend on SQ CAM output, ring capacity, or the P3 launch decision, avoiding a
new long IQ-clear timing cone. The option is experimental: matched L1 chase,
fallback/collision/cancellation tests and a timing report are required before
any default change; simulation alone does not establish 200 MHz closure.

The first matched NB4/ring8, fused-MOVEA and speculative-wakeup 2 KB chase
measured **898 cycles / 128 hops = 7.016 cycles/hop in both OFF and ON arms**.
The ON stage trace confirms the shortcut actually admits each sampled load in
P3, but its cache command remains held one cycle: on the P3 fast-enqueue edge,
fall-through and `loadCmd.valid` are asserted while `loadCmd.ready` is low,
`earlyProbeTokenPresent` is high and `earlyProbeOwnsCmd` is low. On the next edge
the probe becomes ready/owned and the command is accepted. The cache's existing
`earlyProbeMatchVec` requires the registered `earlyProbeReadies` bit, which is
set only after the probe-line data has been captured. Bypassing that bit without
same-cycle data/tag forwarding would risk consuming an unresolved or stale line.
The present P3 shortcut therefore saves **no dependent-load cycle**; it is not
a candidate for default promotion as implemented. This is a simulation result,
not timing closure or a board claim.

The next boundary has two possible experiments, neither implemented here.
Launching the virtual-set probe in P1 would use the AGU's `s1Va` before it is
registered into `tCtx`; its token and access size are available from the P1
context, but the DTLB request still starts in P2. The probe would therefore
outlive a possibly stalled or cancelled translation request. P1 would need
atomic queue-credit reservation, flush/walker cancellation and slot-generation
checks; the longer dwell would increase the chance of an intervening store
invalidating its data. It would also put AGU addition and probe-queue `ready`
on the already-sensitive P1/issue accept chain. This is the higher timing and
ownership risk.

A narrower candidate forwards the **registered** `probeLineValid`, line, hit,
tag, slot, offset and size to the P3 command on the cycle before
`earlyProbeReadies(slot)` becomes true. The candidate can only own a command
after a token-plus-VA match to the still-valid slot and a physical-tag match;
it must apply the current and sticky same-set-write checks, the ordinary
`ldS1Valid` conflict, cancellation/slot reuse, and one-hot consume-and-replace
rules. The 128-bit line extraction would then feed the existing registered
`ldS2DirectData` endpoint. This preserves the launch boundary but may lengthen
the tag/CAM/byte-select path into that register and the ready path back into
the LS ring. A directed P3-cycle probe/cancel/write collision gate and a routed
timing comparison are needed before claiming the possible one-cycle saving.

## 14.3. Optional registered probe-line forwarding experiment

`CPU_DCACHE_EARLY_PROBE_LINE_FORWARD` defaults OFF. It may bypass the one-cycle
copy from the **registered** `probeLine*` result into the early-probe slot's
`readies/hits/tags/data` registers. It does not bypass the synchronous tag/data
RAM, a DTLB response, or a fault, and it does not create a combinational AXI
R→LS response path. The normal registered slot result remains the fallback.
The first intended measurement pairs this switch with `CPU_LS_P3_FAST_LOAD=1`
on the otherwise identical NB4/ring8 fused+speculative-wakeup chase.

The forwarding candidate may consume a slot only if `probeLineValid`, its slot
is still valid and **not yet ready**, its stored token and VA match the current
resolved command, and its captured physical tag matches `cmdTag`. A hit must be
the one-hot-safe registered `probeLineHit`; all-zero or multi-hot read decisions
fall back. The candidate must reject a live same-set array write, the slot's
sticky stale bit, a same-cycle matching cancel/inhibited resolve, and an
`ldS1Valid` conflict. On a rejected candidate, existing not-ready token
backpressure stays in force; no raw probe-line data is served. On acceptance,
`loadCmd.fire` owns both the response and the queue-slot consume exactly once,
including flush priority. The registered admission credit closes when all five
slots are occupied, so a sixth probe offered on the forward-consume edge waits
for that credit to reopen rather than replacing the consumed slot on that edge.
The byte extract
from the registered 128-bit line must feed the existing registered
`ldS2DirectData`, never `loadRsp` directly. OFF elaboration must retain the
original ready, hit and data equations.

Before relying on the slot-valid predicate alone, audit the two-stage result
lifetime against cancellation and reallocation. A not-ready slot cannot be
consumed, allocation chooses a pre-edge free slot, and cancellation clears valid
before a later allocation can select it. With a launch on edge N, the RAM-read
metadata is live in N+1 and `probeLine*` in N+2. Cancellation on N+1 makes the
slot invalid on N+2, so the deferred write sees invalid even if N+2 allocates
that free slot. Before N+2, the slot is not ready and cannot be the queue-full
consume/replacement victim. On N+2 a successful forwarded consume releases the
slot, but the previous full-queue credit still prevents a new allocation on
that edge. These rules exclude a replacement
occupant before the old line result has finished; a simulation-only captured
token/VA assertion checks the deferred write in the focused tests. It does not
claim a hardware generation counter or a wrap bound. If a reachable alias is
found, add a bounded generation protocol before enabling forwarding. The
deferred `earlyProbeReadies` write must pass this audit independently.

Expected area is a byte extract from the existing registered 128-bit line, a
32-bit result mux, a physical-tag comparator, five slot-select gates and
readiness gates. The existing token/VA present vector is reused; the candidate
adds no synthesis registers (the identity tripwire is simulation-only) and no
wide raw RAM-output holding buffer. The ready and data paths may lengthen, so
the experiment needs a routed timing report before any promotion. Required
directed gates include
hit/miss, token and translated-tag mismatch, same-set write before/on consume,
cancel and slot reuse, split/inhibited fallback, collision with an S1 load,
held command stability, and exactly-once response/fault attribution. A matched
7→6-cycle chase result is a target, not an assumption.

The isolated first measurement used one source revision, `IPC_SEED=-2036122926`,
`MB_PLAN='l2:6:33:4096|2048|chase'`, `MB_LAPS=5`, `MB_FF=0`,
`MB_LS_OOO=1`, `MB_SPEC_WAKE=1`, `MB_FUSE_LONG_MOVE_LOADS=1`,
`CPU_DCACHE_NONBLOCKING=1`, `CPU_DCACHE_NB_EARLY_RESPONSE=1`,
`CPU_INHIBITED_FULL_BARRIER=1`, `CPU_AXI_DH=1`, and
`CPU_LS_LOAD_RING_DEPTH=8`. The three arms differ only in the two switches
listed here:

| P3 fast load | Probe-line forward | Window cycles / 128 hops | Cycles/hop |
| --- | --- | ---: | ---: |
| ON | OFF | 898 | 7.016 |
| OFF | ON | 898 | 7.016 |
| ON | ON | 771 | 6.023 |

The combined switches save 127 window cycles, 0.992 cycles per hop, about
14.1% of the OFF-arm cycle count. Neither switch alone changes this chase:
P3 admission and registered probe-result readiness each bound the other.
This is a core-only calibrated 2 KiB L1-resident pointer chase, not a routed
timing result or a shipping-default promotion. The ON-mode directed probe tests
cover an early resident hit, physical-tag mismatch fallback, five-slot
backpressure/recovery, and N+1 cancellation followed by same-slot N+2
reallocation while the old registered line result is live. Broader stale/alias
and hazard checks passed in the ON-mode VIPT suite (11/11), including same-set
store staleness, virtual/physical alias fallback, queue-full cancel-all, and
unresolved-probe fallback. The ON-mode P3-fast LS EU tests passed 2/2 for SQ
forwarding, split fallback, squash recovery, physical bus fault attribution,
and no faulting PRF write. The default-OFF `make test-fast` gate passed 403/403
(2 ignored). Routed timing and a combined root-tree gate remain necessary
before promoting either default.

## 14.4. Integrated dependent-miss stage budget and next candidate

On the integrated P3-fast/probe-forward/NB-early-response/eager-AR arm, with
NB4, ring8, hot door, fused long MOVE loads, LS-OoO and speculative load wakeup,
`IPC_MEM=l2:5:60:4096`, and seed 1, the core-only 2 KiB chase measures
771/128 = 6.023 cycles/hop. The 64 KiB chase measures 73672/4096 = 17.986
cycles/hop. Thus this combination improves the resident-hit loop but leaves
the hot-L2 differential at 11.963 cycles/hop. This model observes six cycles
from an *accepted hot AXI AR* to its R handshake; it is not a whole-SoC
miss-discovery or return measurement and is not a routed timing result.

`MB_TRACE=mb-chase-64k MB_TRACE_STEADY=1` on that same arm captured 400
steady-state cycles. One complete dependent hop is:

| Event | Cycle | Offset from issue |
| --- | ---: | ---: |
| LS issue | 83140 | 0 |
| P1 / P2 / translation wait / P3 fast enqueue | 83141 / 83142 / 83143 / 83144 | 1 / 2 / 3 / 4 |
| cache command accepted (C0) | 83145 | 5 |
| cache S1 / NB staged miss and MSHR allocation | 83146 / 83147 | 6 / 7 |
| hot AR first valid and accepted | 83149 | 9 |
| hot R accepted | 83155 | 15 |
| load response and IQ wake | 83156 | 16 |
| dependent LS issue | 83157 | 17 |

Across the 23 cache commands in the trace, C0 to first hot ARVALID is
**always four cycles**. ARREADY adds 0, 1, 2, 3, or 4 cycles on 14, 6, 1, 1,
or 1 of those commands, respectively. Of the 22 completed R events, accepted
AR to R is exactly six cycles and R to response/wakeup is one. The 21 fully
bounded issue-to-next-issue intervals are 17 cycles (13), 18 (6), 19 (1),
and 21 (1). These counts have different denominators because the 400-row
window cuts through hops at each end. The measurement uses the existing
`MB_TRACE` table, with no RTL instrumentation or modified simulator.

The matched four-chain run on this integrated arm reports 8192 useful bytes
in 10986 commit-window cycles, or 0.7457 B/cycle. The otherwise identical
early-response/eager-AR arm without P3 fast admission or probe forwarding
reported 0.7454 B/cycle. That difference is 0.04%, so the resident-hit
one-cycle gain does not establish a four-chain bandwidth improvement. The
integrated run's whole-run WAIT_R active-cycle MLP was 1.997, with four hot
outstanding requests maximum and 69 set-conflict allocation failures but no
full-MSHR failures; those counters have a different denominator from the
commit-window throughput. The all-cycle whole-run WAIT_R mean is
41740/23077 = 1.809; neither number is a warm-only L2 service statistic.

The source sequence behind C0-to-ARVALID is C0 array read, registered S1
miss, registered `nb.stgValid` with victim metadata, `nb.lAlloc` allocating
WAIT_AR, then registered `arV`. ARREADY is external backpressure and cannot
be removed by eliminating a source register. The separate preselected-AR
experiment may remove one source edge; it does not make the 11.963-cycle
incremental penalty meet the six-cycle goal by itself.

There is a strict model-specific timing floor if no AR precedes C0: a resident
hit needs one cycle from C0 to the dependent issue, whereas an ideal miss with
zero C0-to-AR delay, six AR-to-R cycles, and the same one-cycle return would
need seven. The differential is already six, before any ARREADY backpressure
or extra response/wakeup stage. Therefore reaching a six-cycle incremental
penalty at this model setting needs all launch and return overhead removed or
overlapped; removing just one or two registers cannot suffice.

A possible next **default-OFF, unimplemented** experiment is to reuse a
*definitive registered early-probe miss* at C0 instead of repeating the S1
array lookup. The P2 probe's virtual set alone cannot authorize it: the
physical tag must come from the matching DTLB resolve, and an unresolved,
faulting, inhibited, split, or multi-hot probe must take today's path. A
candidate record would need exact token and VA, translated physical line and
cache mode, one-hot-safe miss qualification, and the selected victim way,
valid/dirty/tag/128-bit line state with the current S3/S3-D1 store-write
bypasses. Before MSHR allocation it must recheck same-set writes and
maintenance after the probe, SQ/older-store ordering, flush/cancel, token
reuse, same-line MSHR merge, set conflicts, MSHR and WB credits, and the
inhibited/serial fence. A record from another token or a stale set falls
back to S1; no AR is sent for an unqualified probe. The present five-slot
early-probe queue does not store victim lines. Replicating the 128-bit line
per slot would alone add 640 flops, before tags/identity; a one-entry
registered candidate could bound area to one 128-bit line plus roughly
100–120 bits of translated address, token/VA identity, victim tag/way/dirty,
and control (about 230–250 new flops before synthesis optimization). It would
also add a victim-way/data mux and exact-token/physical-tag comparisons. The
single record must admit overlapping probes by falling back when another
probe overwrites it; that arbitration and any new BRAM-output-to-register
critical path need proof. An allocation on the C0 edge could plausibly remove
one miss-staging cycle; an earlier AR requires a separate order/credit proof
and routed timing measurement. There is no claim that the six-cycle
incremental target is reachable from this candidate alone.

## 14.8. Optional P1 paired translation and VIPT launch experiment

`CPU_LS_P1_EARLY_LOAD` defaults OFF. The matched fused-MOVEA, speculative-wakeup,
P3-fast-enqueue and registered-probe-forwarding 2 KiB pointer chase still takes
771/128 = 6.023 cycles per dependent load. Its issue-to-issue schedule is P1 EA,
P2 paired DTLB/VIPT launch, P2T translated probe verdict, P3 command, registered
cache response/wakeup, next issue. This experiment moves the **existing paired
request** from P2 into P1 for a narrow `(An)` LOAD, aiming to remove the P1-to-P2
edge. It does not pretranslate, introduce a new queue, or expose untranslated
probe data as an architectural hit. A P1 probe-only launch would lengthen a
token's lifetime independently of its DTLB request and is deliberately excluded.

### 14.8.1. Fused LONG MOVE into Dn

The same default-OFF option may admit a fused `MOVE.L (An),Dn` as well as the
original `MOVEA.L (An),An`. The assembler already emits one LS uop for both
full-width destinations; the first-and-last markers and `pdstValid` remain
mandatory. Widen only the destination predicate from An 8–15 to architectural
integer register 0–15. Keep `imm=0`, no index/auto-update, natural alignment,
and every existing ownership, translation, flush, split, SQ and replay rule.
There is no new context register or address datapath. Dn's decoded
`writesNzvc` and renamed flag destination travel through `captureFrontCtx`;
the existing MOVE load completion writes NZVC from the returned value, while
`compIsFault` suppresses both integer and NZVC writes on a fault. No early
flags or data are exposed by the P1 probe.

Directed ON tests must establish actual P1 fire, data and NZVC for positive,
zero and negative values, and fault-side suppression of both write ports.
Retain exact older-SQ forwarding/probe cancellation and partial-overlap
ordering checks. Compare matched OFF/ON dependent Dn-consumer cycles with
identical instruction shape and memory model before claiming a benefit.
The broader destination gate is simpler, but P1 still selects a live AGU
address onto the DTLB path; mapped timing and area remain unproven.

The matched `chase-dn` kernel keeps the same pointer ring but makes each hop
`MOVE.L (A0),D0; MOVEA.L D0,A0`. With seed 17, `l2:6:33:4096`, fused decode,
LS-OoO, speculative wake, P3 fast enqueue, registered probe forwarding,
NB early response/eager AR, ring 8/MSHR 4, the 2 KiB chase measured
1027/128 = **8.023** cycles/hop OFF and 900/128 = **7.031** ON. The 64 KiB
chase measured 85990/4096 = **20.994** OFF and 81887/4096 = **19.992** ON.
The L2-over-L1 difference remains about 12.97 cycles/hop; this broadening
removes one frontend cycle from both tiers, with no demonstrated miss-path
reduction. Logs: `/tmp/codex-agent74-dn-chase-{off,on}.log`.

With `FUZZ_SHIPPING=1` (which enables fused decode; `FUZZ_SHIPPING_LSU=1`
alone does not), the 120-op LS-OoO stress seeds 15–17 passed exact device
counts and liveness. The IRQ seeds had 42 and 34 **accepted P1 pairs**,
respectively; the three seeds had 98 combined, 1,850 exception entries and
22 replays. The test required a positive count for each IRQ seed, so option
selection alone could not satisfy it. Log:
`/tmp/codex-agent74-dn-irq-stress.log`. These are simulation results; the
default remains OFF pending mapped timing/area evidence.

The eligible uop is a fused LONG MOVEA load through a single An base: P1 has a
live `s1Valid`, `u1.op=MOVE`, An `dstArch`, first-and-last macro markers, LONG size, valid base operand, no
index/auto-update/displacement/stack push/alternate address space/CCR restore,
no privilege requirement, `s1TwoAccess=false`, and `s1Va[1:0]=0`. The decoded P1 address
`s1Va` is the *virtual* address. Cacheability and physical tag remain unknown;
the side-effect-free virtual-set probe retains `resolved=false`, no physical
hint, and provisional INHIBITED cache mode until the normal P2T translation
verdict. If the translation reports a fault, INHIBITED, or a disallowed
privilege, the existing token cancellation and P3/P4 fallback rules apply; no
load command may use the probe as an unqualified hit.

The P1 DTLB request and cache probe must handshake **atomically**, using the
same ROB/epoch translation token and ROB-derived probe token as P2. Admit P1
only when P2 is empty, no split second-half request owns the port, P2T can
accept another tagged request, and no flush/exception/walker owns the load or
translation port. The P1 arm asserts valid only with both grants already high:
DTLB `req.ready` is independent of `req.valid`, and D-cache probe ready is a
registered credit. Thus P1 never presents a stalled Stream request whose valid
could be withdrawn next cycle. If either port is not ready, make no P1 request or probe and
move the held P1 context into the ordinary P2 stage; it retries there. On a
paired P1 fire, capture the existing `captureFrontCtx` output directly into
`txCtx`, set the usual `txValid/txWaitingRsp/txToken`, and suppress that same
uop's `s1ToT` push. Allow the existing P1 slot to accept a following issue on
the edge as before; `issuePort.ready` must remain the original `s1Ready` path,
without DTLB or probe-ready feedback. The old P2 path and split path keep
priority, so there is one producer per translation/probe port and no overtaking.

Cancellation covers flush/exception, walker handover, translation fault,
inhibited result, forward/SQ resolution, and a late same-set store. Reuse
the probe queue's token+VA match, generation/slot reuse checks, sticky stale
bit, and one-hot consume rules; a P1 request cannot be interpreted as the
next uop's P2 token. Directed tests must exercise P1 success, P2 fallback on
each ready-low input, simultaneous previous P2T consume and P1 admit,
split/unaligned exclusion, translated INHIBITED and ATC fault, flush at each
stage, walker handover, stale/same-set write, ROB-ID reuse, and full probe
queue. Assert one DTLB request pairs with one probe, no duplicate cache
command, no unpaired resident probe, and identical fault/architectural state.
Compare matched ON/OFF 2 KiB chase cycles and independent-load throughput,
then run `test-fast` and timing/area analysis. A simulated one-cycle gain is
not 200 MHz timing proof. If the AGU-to-DTLB lookup or P1 ownership mux is too
deep, keep this flag OFF and evaluate a registered-probe-line completion
shortcut separately; do not weaken the translation or replay guards.

The initial matched full-core simulation (source in isolated agent72, seed 17,
`l2:6:33:4096`, fused MOVEA, LS-OoO, speculative wake, P3 fast enqueue,
registered probe forwarding, NB early response/eager AR, ring 8/MSHR 4)
measured 2 KiB chase **771/128 = 6.023** cycles/hop OFF and **644/128 =
5.031** ON. The 64 KiB chase measured **77698/4096 = 18.969** OFF and
**73614/4096 = 17.972** ON. The L2-over-L1 differential therefore remains
12.946 versus 12.941 cycles/hop: this P1 change removes one frontend cycle
for both tiers, but does not address the L2 miss-path excess. The paired
logs are `/tmp/codex-agent72-p1-chase17-{off,on}.log`; this is a simulation
result, not mapped timing or netlist-area evidence.

Directed ON gates in the isolated branch passed: paired success, both
ready-low fallbacks, within-line misalignment and split exclusions, flush plus
ROB-token reuse, and translation-fault cancellation/no cache command/no
integer-or-NZVC write (CrossSpec 11/11); exact older-store forwarding and
partial-overlap drain ordering (LsEuSpec 2/2). Settled-source `test-fast`
passed 404/404 with the flag OFF and 404/404 with it ON. The new hardware arm
reuses the existing P1/P2T context and probe slots, adding no production
state registers; it does add P1 eligibility logic and payload selection before
the DTLB lookup. Mapped area and critical-path timing have not been measured,
so the flag remains OFF by default.

## 14.5. Experimental queued clean-victim probe-miss stage (2026-10-01 amendment)

`CPU_DCACHE_NB_PROBE_MISS_STAGE=1` is an independent default-OFF experiment in
non-blocking, unsectored mode. It uses an **accepted resolved command** and the
matching **ready miss** in the existing five-entry early-probe queue to load
the existing `stg*` registers directly. The regular S1 array read is omitted
only for that command; the existing store-first MSHR allocation, waiter,
replay, preselected-AR, and response machinery runs on the following edge.
The expected source timing is C0→allocation 2→1 and C0→advertised AR 3→2
when the bus accepts immediately. There is no speculative allocation from a
P2 probe before its command, and no new path to command `ready` or the CPU
read response. The ordinary read/stage path remains the exact fallback.

The fast candidate requires all of the following at C0: exact probe token
**and virtual address** ownership; ready miss with a resolved physical tag
equal to the command's tag; one-hot-safe tag outcome; cacheable ordinary
COPYBACK **or WRITETHROUGH** access (`ooOk`, not split/line-only, inhibited,
or faulting); no
sticky or current-cycle same-set array write; no matching cancel/flush; no
maintenance/reset; no older S1 decision competing for `stg*`; and no store
same-set input-store `valid`, S0/S1/S2/S3/pending-store activity for that set.
The input check uses `valid`, rather than `fire`, so it does not add a
store-ready to load-stage feedback path. The queue carries a victim-way
snapshot, a clean-or-invalid certificate, a resolved-tag certificate, and a
multi-hot certificate alongside the already-stored physical tag. A dirty
victim or uncertain certificate falls back: **no 128-bit victim line or dirty
writeback data is copied into the probe queue**. The prototype may use one
five-bit pipeline record and five five-bit queue records (about 30 new flops),
plus narrow selects and comparisons; mapped area and timing must be measured.

The selected victim way must equal the current victim pointer at C0. A
same-edge MSHR allocation for the same set is also excluded: its pointer
advance is registered at C0 and would otherwise stale the staged victim on
the next edge. The later allocator still checks active-set conflict, CAM,
store priority, and resource credit before allocating. These checks must be
pinned by directed tests, including dynamic early MSHR release, rather than
assumed from a successful benchmark. Any same-line MSHR already active can take the existing
secondary path at staging; a different-line same-set MSHR replays. A store
entering S0 after C0 is younger than the staged load; a same-set store
presented at C0 or already in S0/S1/S2/S3 forces the
fallback. Same-cycle invalidation, no-fill store, WT overlay, and maintenance
write races also force fallback.

The measured reason for the queued form is concrete: in the integrated
NB4/ring8 eight-chain seed-17 run, a matching live `probeLineValid` miss
coincided with 70 command-valid cycles and **zero command handshakes**;
however 1,448 of 2,049 accepted load commands had a fresh matching queued
miss (before physical-tag and clean-victim qualification). These counts are
upper bounds on useful fast allocations, not a predicted IPC gain. Validation
must print fast allocations and fallback causes, check C0→allocation/AR
timing, compare matched dependent chase and independent four/eight-chain
throughput and data, and force token reuse, physical-tag alias, stale set
write, older store pipeline, dirty victim, same-line/set-busy MSHR,
reordered/error refill, cancel/flush, and no-fill/maintenance fallbacks. A
qualification-removal mutant must fail checked behavior, not elaboration.

The isolated prototype at `30d2a17a` satisfies the directed timing and
fallback test and two 1,000-operation hot-chaos seeds (1 and 17), with 41
actual fast stages in the mixed-traffic run. Removing only the physical-tag
equality guard returns stale data in the directed alias test. In a matched
seed-17 full-core dependent chase with the same RTL and L2 model
`l2:5:60:4096`, 2 KiB resident latency stays 771/128 = 6.023 cycles/hop;
64 KiB latency changes from 69,530/4,096 = 16.975 to 65,562/4,096 =
16.006 cycles/hop (0.969 cycle, 5.7%). The fast arm stages 5,120 of 5,120
primary allocations over the whole run. With eight independent chains,
8,224 checked bytes take 10,760 cycles OFF and 10,689 ON (0.7643 to
0.7694 bytes/cycle, 0.67%). This limited MLP gain is consistent with the
measured older-S1 staging conflicts: 1,957 of 2,110 otherwise eligible
accepted misses in the ON run. These are simulation results, with P1 early
translation OFF and all other stated integrated latency/MLP features ON;
they are not routed area, timing, or board measurements. The prototype adds
30 functional flops for metadata, but its extra selection and comparison
logic still requires synthesis and timing assessment before enabling it by
default.

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

### Integrated probe-miss test harness repair (2026-10-01)

At integrated source `8ac8d80f`, default `test-fast` passed 404/404 and combined
P1/probe-miss LS gates passed CrossSpec 11/11 plus forwarding 2/2. The full
19-case nonblocking-cache suite passed its new explicit probe-miss case, then
stalled elaborating the legacy-AR DUT. Two JVM thread dumps showed the same
monitor cycle: ScalaTest held the suite monitor in `nbHotLegacyArDut$lzycompute`
while waiting for the Spinal fiber; the fiber waited on that monitor to
initialize the nested `Dut` companion for the newly defaulted constructor
argument. This was not a simulated hardware deadlock. The affected test JVM
was terminated; other regressions were preserved, and the dependent performance
matrix correctly refused to start after the failed gate.

Remove the `probeMissStage` constructor default and pass explicit `false` at
all legacy DUT call sites. Existing explicit ON/environment-selected cases
stay unchanged. This repairs the test fixture only; combined cache validation
and the performance matrix remain pending on the repaired source. Logs and
thread dumps: `/tmp/codex-agent59-combined-all-options-cache.log`,
`/tmp/codex-agent59-combined-cache-threads{,-confirm}.txt`.

### Integrated latency composition and Dn acceptance (2026-10-02)

The repaired integrated source `97b0ba49` passed default `test-fast` 404/404,
combined LS CrossSpec 11/11 plus older-store forwarding 2/2, and the complete
nonblocking-cache suite 19/19 with four randomized seeds of 1000 operations
per stress arm. The default-argument elaboration deadlock described above is
resolved. Results: `/tmp/codex-agent59-combined-r2-results.json`.

A same-source seed-17 four-way comparison uses `l2:5:60:4096`, fused MOVEA,
speculative wakeup, P3/probe forwarding, NB4/ring8/hot door/D4,
early/eager/preselected AR, dynamic release, and direct refill OFF:

| P1 early launch | Probe-miss staging | L1 2 KiB cycles / 128 hops | L2 64 KiB cycles / 4096 hops |
| --- | --- | --- | --- |
| OFF | OFF | 771 / 6.023 | 69530 / 16.975 |
| ON | OFF | 644 / 5.031 | 65562 / 16.006 |
| OFF | ON | 771 / 6.023 | 65562 / 16.006 |
| ON | ON | 644 / 5.031 | 61598 / 15.039 |

Each row passed architectural final-pointer checking. Both probe-miss ON arms
record 5120 whole-run primary allocations and 5120 fast miss stages with
5120 resolved certificates and no older-S1 blocking in this dependent chain.
Thus the one-cycle frontend and one-cycle miss-stage savings compose on this
workload. The combined L2-over-L1 difference remains about **10.008 cycles**;
the six-cycle incremental target is not achieved. These results use the
behavioral memory model, not the actual SoC L2, and are not mapped timing,
area, independent-load IPC, or board evidence. Exact flags and logs:
`/tmp/codex-agent59-combined-r2-matrix-results.json`.

The separate Dn extension was integrated at `16bde59c`, preserving that cache
RTL and adding only the previously validated register-destination broadening.
Default fast passed 404/404; combined CrossSpec 12/12 and forwarding 2/2 passed.
Shipping-fused full-core stress, inhibited-store IRQ replay, and inhibited
ordering passed 3/3 with both optimizations enabled. Stress seeds 15/16/17
record 42/22/34 accepted P1 pairs (IRQ seeds 15 and 17 require nonzero),
5830 commits, 1850 exception entries, and 22 replays, with exact device counts
and zero failures. These establish combined correctness, not Dn performance
under the new miss-stage configuration. Results:
`/tmp/codex-agent68-dn-integrated-results.json` and
`/tmp/codex-agent68-dn-integrated-core-results.json`.

### Early-load throughput coverage limitation (2026-10-02)

A matched P1 OFF/ON comparison at `63171ff6` passed both DsideBandwidthSpec
arms with the probe-miss stage fixed ON, seed 17, `l2:5:60:4096`, NB4/ring8,
and the same other options as the dependent matrix. Results were identical:

| Kernel | Measured bytes | Cycles in each arm | Bytes/cycle |
| --- | ---: | ---: | ---: |
| stream-16k | 16384 | 13355 | 1.2268 |
| chase-chains-4-1024-skew0 | 8224 | 10335 | 0.7957 |
| chase-chains-8-1024-skew0 | 8224 | 10689 | 0.7694 |

This is a scope control, not evidence that early launch cannot help eligible
independent loads. `kChaseChains` uses `move.l (%a0,%dN.l),%dN`, with a second
address source excluded by P1. `kStream` uses `add.l (%a0)+,%dN`, whose
auto-update and multi-uop form also fall outside P1's simple fused-load
contract. Keep those workloads because they expose real coverage gaps; add
simple-An independent chains with an accepted-P1 nonvacuity check before
making a throughput claim about this optimization. The four/eight-chain
comparison also changes loop overhead per load, so it is not a pure queue
capacity experiment. Exact flags, retirement counts, checked results and
logs: `/tmp/codex-agent68-dn-throughput-results.json`.

### Current miss recurrence and socket boundary cost (2026-10-02)

The integrated `71d5302d` model run repeats the combined latency result
(L1 644/128, L2 61598/4096) and passes its architectural checks. With
`MB_TRACE=mb-chase-` and `MB_TRACE_STEADY=1`, each 400-cycle trace starts
after warmup. Complete observable intervals are:

| Interval | Cycles | Samples |
| --- | ---: | ---: |
| L1 issue to C0 | 3 | 79 |
| L2 issue to C0 | 4 | 26 |
| L2 C0 to allocation | 1 | 27 |
| L2 C0 to first ARVALID | 2 | 27 |
| Accepted AR to R | 6 | 27 |
| R to next dependent issue | 2 | 26 |

The minimum issue-to-issue interval is 14 cycles (16 samples); the remaining
bounded samples take 15/16/17/19 cycles (2/4/1/2 samples). External ARREADY
backpressure is separate from the fixed C0-to-first-ARVALID interval. The
uncontended core recurrence therefore contains eight cycles outside the
six-cycle modeled AR/R interval: issue-to-C0 four, C0-to-AR two, and
response-to-next-issue two. Endpoint counts differ because the trace clips
the first and last transactions. Raw trace and derived histograms:
`/tmp/codex-agent59-integrated-stage-trace.log`,
`/tmp/codex-agent59-integrated-stage-trace-results.json`, and
`/tmp/codex-agent59-integrated-stage-budget.json`.

The real socket additionally uses `StreamPipe.FULL` on **both** hot AR and
hot R (`SocketTop.scala`, `dhSlice`). Those two forward register stages are
absent from the core-only harness. The actual CPU/L2 bus trace independently
shows response-to-next-accepted-AR of exactly 10 cycles in all 4095 bounded
intervals, with either SoC response-bypass setting. It is consistent with the
eight-cycle core recurrence plus two socket edges. The SoC L2 interval itself
is six cycles OFF and five ON, yielding 16/15-cycle accepted-AR spacing.
The corrected SoC `446abae` run now confirms this using true macro-retirement
PCs: all 127 bounded L1 load-retirement intervals are five cycles, and all
4095 L2 intervals are sixteen OFF or fifteen ON. Marker totals are 646/128
for L1 and 65541/4096 OFF versus 61445/4096 ON for L2; the difference from
steady spacing is fixed marker overhead. Both measured L2 windows have
4096 ordered, checked pointer requests and zero DDR reads. Exact inputs and
parser/trace provenance: `/tmp/codex-soc-chase-446abae/retirement-validation-results.json`.

Removing these socket edges is not automatically safe for timing. The FULL
slices were introduced to break long fabric-to-core paths, including fault
capture, and their reset domain is deliberately distinct from the boot-domain
read absorber. An empty-path hot-AR-only slice experiment is a smaller next
candidate than simultaneously bypassing both AR and R, especially with the
SoC R bypass enabled. Selection needs a separate spec, reset/backpressure
proof, and mapped/routed evidence; this paragraph does not authorize a
shipping default change or remove existing boundary registers.

### Speculative-wakeup control on independent indexed loads (2026-10-02)

At source `564e3b2b`, a test-only monitor fix made the existing full-core
benchmark valid with `DSIDE_SPEC_WAKE=0`: speculative-wakeup signals are
sampled only when that option elaborates them. Default `test-fast` passed
404/404. A seed-17 OFF/ON pair used identical source, indexed-load kernels,
`l2:5:60:4096`, P1/probe-miss staging, NB4/ring8/hot door/D4,
P3/probe forwarding, early response, eager/preselected AR, dynamic release,
and direct refill OFF. Only `DSIDE_SPEC_WAKE` changed.

| Kernel | Wake OFF cycles / bytes per cycle | Wake ON cycles / bytes per cycle | ON minus OFF |
| --- | --- | --- | --- |
| stream-16k | 13355 / 1.2268 | 13355 / 1.2268 | 0 cycles |
| indexed four-chain | 10503 / 0.7830 | 10335 / 0.7957 | -168 cycles (-1.60%) |
| indexed eight-chain | 10666 / 0.7710 | 10689 / 0.7694 | +23 cycles (+0.22%) |

The four-chain timed-window IQ recheck held/skid counts were 0/0 OFF versus
8251/8213 ON; eight-chain counts were 0/4 versus 140/134. Four-chain
MSHR-full and ring-full-no-pop counts were zero in both arms, while eight-chain
MSHR-full was 1481 OFF versus 1503 ON and ring-full-no-pop was 1877 versus
1766. These are observed events, not a causal decomposition of the cycle
difference. Blanket wake suppression removes many rechecks but also loses
four-chain throughput; this pair does not justify changing the default or
adding a hit-qualified wake policy. A narrower policy would need a measured
ready-load-while-skid cross-tab and its own matched gate. This is a behavioral
memory-model result, not SoC timing evidence. Exact paired outputs:
`/tmp/codex-agent76-specwake-pair-results.json` and
`/tmp/codex-agent76-specwake-pair-{off,on}.log`.
### Optional hot-AR socket empty path (2026-10-02)

`CPU_AXI_DH_AR_FALL_THROUGH=1` is an experimental, default-OFF socket option
requiring `CPU_AXI_DH=1`. It changes only the `axi_dh` AR stream pipe from
SpinalHDL `FULL` (`s2mPipe().m2sPipe()`) to `S2M` (`s2mPipe()`); the hot R pipe,
all cold/I channels, byte permutation and persistent reset absorber remain
unchanged. The absorber still counts port-level accepted AR and RLAST edges,
blocks new external AR while discarding stale R, and is clocked in `axiPorCd`;
the pipe remains in `coreCd`. The optional path must not create an ARVALID
dependency on ARREADY or withdraw a stalled request before a handshake.

SpinalHDL 1.14.1 gives an empty S2M stage zero forward latency while retaining
registered upstream READY and a one-entry skid payload under backpressure.
Dropping M2S nominally removes its 47-bit externally used AR payload register
and one valid register, or 48 logical flip-flops; this is not a mapped-area
claim. The direct forward path may increase the MSHR-to-L2 timing cone, so
functional simulation alone cannot establish 200 MHz closure. The R pipe stays
FULL because the historical fabric-to-core timing failure was on the return
side. Evaluate AR-only against the unchanged baseline in matched actual-SoC
bus recurrence and routed timing before any default promotion.


#### AR-only generation and boundary validation (2026-10-02)

The isolated implementation `851ef575` passed all ten directed socket boundary
cases and the required fast gate (414 tests). It is integrated as `d1b76823`;
`src`, `build.sbt`, `project`, and `Makefile` are identical to that tested source.
The boundary cases cover empty-path latency, held AR payload, reset cancellation,
stale multi-ID response absorption with a fresh pending request, and byte order.
Logs: `/tmp/codex-agent77-hot-ar-focused-r4.log` and
`/tmp/codex-agent77-hot-ar-testfast.log`.

Matched CPU generation from clean `d1b76823` succeeded with the AR option OFF
and ON, and both generated interfaces passed `check_socket(path, False, True)`.
The two recorded environments differ only in `CPU_AXI_DH_AR_FALL_THROUGH`.
All sixteen generated filenames match; only `M68kSocketTop.v` differs, and all
module definitions after the socket top are byte-identical. Input manifests
and file hashes are in `/tmp/codex-soc-cpu-inputs-d1b76823-ar{0,1}/manifest.json`.

The emitted Verilog removes 64 declared M2S storage bits: 48 for exposed AR
payload plus valid, and 16 unused AXI sideband bits. This is a source-level
state count, **not** mapped flip-flop savings: synthesis can already remove
unused or constant fields. The S2M skid storage remains. The full diff and
count are `/tmp/codex-soc-ar-netlist.diff` and
`/tmp/codex-soc-ar-generated-state.json`. The generated top hashes are
`3b5bf0aa2737717f3b42ca886418db03dd50a891650fad402b1478c7ecf9704c` OFF and
`d26737865c6b8a13ec048a223ae975401de7312ec71ab7f6c36d5aaa237a9a9e` ON.

At this checkpoint, matched actual-SoC execution with L2 response fallthrough
fixed ON is still running. No AR-option performance, mapped-area, or routed
200 MHz result is established, and the option remains default OFF.
