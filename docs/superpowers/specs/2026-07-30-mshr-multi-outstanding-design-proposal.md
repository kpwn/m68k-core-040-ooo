# Multi-outstanding, bursting MSHRs for L1I + L1D in an L2-backed SoC — design proposal

Date: 2026-07-30. Status: **DESIGN PROPOSAL for human review. No RTL written, no RTL to be
written until this is signed off and a `writing-plans` pass has produced a sliced plan.**

Scope: make the instruction and data caches *non-blocking* and *bursting* — i.e. give them
miss-status holding registers (MSHRs), multiple outstanding AXI transactions with real ID
routing, and (on the D side) a line/burst size chosen for an L2 rather than for a bare
single-beat memory. Branch of record for the baseline: `feat/rob-predictor-mem` @ `86190eb`.

---

## 0. Reading guide — the short version

**Read this first, because it reframes everything.** The L2 is real, specified and read (§3.1):
`macqd700-soc/rtl/soc/l2c*.v` — 2 MB, 8-way, **64-byte lines**, write-back/write-allocate, **8
internal MSHRs**, **5.1–5.3-cycle hit**, **AXI4 with 4 CPU-visible ID bits**, and it explicitly
supports **out-of-order responses across distinct IDs** while **blocking a repeated ID at its
front door**. So distinct-ID multi-outstanding is exactly what it wants. **But the crossbar
between the CPU and the L2 (`macqd700-soc/rtl/soc/axi_xbar.v`) is strictly single-outstanding per
master port for both reads and writes, and its authors deferred pipelining deliberately, with a
warning (§3.2).** Therefore:

> **On today's SoC, every multi-outstanding slice in this document yields zero end-to-end
> benefit until the crossbar is reworked. The one exception — hit-under-miss — needs no bus
> concurrency at all, and is also the cheapest thing here.**

That is why the recommended first RTL slice is hit-under-miss, and why the multi-ID slices are
explicitly contingent (§11.Q3). Note also that `m68k-core-040-ooo` has **no SoC integration at
all** today, so none of this is currently binding — it is the contract we would be retargeting.

**Seven decisions I am asking to be ratified or overruled:**

| # | Decision proposed | Where |
|---|---|---|
| D1 | **Do not build a MOB.** Keep LS issue strictly in program order; MSHRs buy hit-under-miss, miss-under-miss and drain overlap *without* one. Keep the load-tracking structure MOB-shaped so a MOB remains possible later. | §5.1, §10.A |
| D2 | **Distinct AXI ID per read MSHR; ONE shared AXI ID for all D-side writes.** The L2 *blocks* a repeated ID at its front door (`id_busy_c`), so distinct IDs are mandatory rather than merely nice; and AXI's same-ID ordering rule then gives write ordering for free. Correct under in-order *or* out-of-order arrival, so it is also robust to §3.4's U2. | §7.1 |
| D3 | **One outstanding fill per cache SET** (merge on same-line, stall on same-set-different-line). This single invariant subsumes victim-selection races, the fill-vs-drain window the copyback design has to patch by hand (its §4.3), and the read-during-write tag hazard. | §5.3 |
| D4 | **Widen the L1D line from 16 B to 64 B** (bursting refill, 4 × 128-bit beats) — **now confirmed as exactly the L2's line size**, so each L1D refill becomes precisely one L2 line. Treat this as *higher priority than MSHR count*; a 16-byte line is the real anomaly. **Must be decided before copyback slice P4 lands, because dirty-bit and eviction granularity bake it in.** | §5.2, §11.Q2 |
| D5 | **On the I side, demand miss-under-miss is nearly worthless; next-line prefetch is where all the value is.** Build 1 demand MSHR + 1 prefetch MSHR, keep response delivery to FetchAlign strictly in order (so FetchAlign needs no change at all). | §6.1, §6.2 |
| D6 | **Move predecode off the refill path.** The 32× `PredecodeWord.classify` unroll is what makes I-side MSHRs expensive; the Aligner already runs a live `classify`. Two options are offered (per-beat = safe 2× cut; align-time = ~10× cut plus it deletes an entire bug class). | §6.4, §10.E |
| D7 | **Sequence around the SoC crossbar, do not pretend it is not there.** Land hit-under-miss (no bus concurrency) first and unconditionally; make every multi-ID slice explicitly contingent on a SoC-side crossbar rework; keep `N_MSHR` a build parameter validated at 1 so the single-outstanding configuration is always a supported, tested build. | §3.2, §9, §11.Q3 |

**Recommended slice 1: no RTL at all — the verification substrate** (§9, slice V1). A
configurable-latency, per-ID-reordering AXI memory model with a protocol checker, plus
miss-heavy benchmark kernels and miss/stall counters, landed against *today's unchanged RTL*
where it must pass. Everything after it is gated on it. Rationale in §8.1: there is currently
**no** benchmark in the tree that exercises a cache miss at all (§1.7), and the single shared
in-order response FIFO in `BehavioralMem` (§1.6) would let multi-outstanding RTL that secretly
assumes in-order responses pass green.

**Recommended first RTL slice: D-side hit-under-miss with ONE MSHR** (§9, slice D1). It needs
zero AXI protocol change, zero new IDs, zero memory-model reordering — it is purely a
restructuring of a blocking FSM into a parked miss context. Best value-to-risk ratio in the
whole document.

**Open questions needing user input are collected in §11.** The one that actually blocks
design closure is **§11.Q3 — will the SoC crossbar be reworked?**, since it decides whether half
the slices here are worth building at all. Then **§11.Q2** (L1D line size vs. copyback
sequencing), which is time-sensitive. §11.Q1 and §11.Q5 were open when this document was started
and are now **answered by §3** — they survive only as confirmation requests.

---

## 1. Verified baseline — including four corrections to the brief

Everything in this section was re-verified against the working tree at `86190eb`. It matters
because three of the corrections change the design.

### 1.1 Correction 1 — there is no AXI arbiter. There are four *independent* top-level masters.

`FullCoreSynth.scala:288-289` states it outright ("declared `master()` inside its plugin and
surfaces as a top-level IO automatically … so no extra wiring needed"), and the generated
`generated/M68kFullCoreSynth.v` port list confirms four AXI ports:

| Port | Config | Requesters on it | IDs used |
|---|---|---|---|
| `IcachePlugin_logic_axi` | `Axi4ReadOnly`, 32/**256**/id **2** (`IcachePlugin.scala:31,39`) | I-cache refill (AR/R only) | `0` (`:336`) |
| `DcachePlugin_logic_axi` | `Axi4`, 32/**128**/id **4** (`DcachePlugin.scala:44,59`) | load refill (AR/R) + store write-through (AW/W/B) | AR `0` (`:373`), AW `1` (`:507`) |
| `itlbAxi` | `Axi4`, 32/128/id 4 (`ItlbPlugin.scala:65,70`) | ITLB table walker (AR/R) + deferred U-bit descriptor write (AW/W/B) | AR `2` (`TableWalker.scala:108`), AW `3` (`ItlbPlugin.scala:253`) |
| `dtlbAxi` | `Axi4`, 32/128/id 4 (`DtlbPlugin.scala:72,77`) | DTLB walker + U/M descriptor write | AR `2`, AW `3` (`DtlbPlugin.scala:343`) |

Consequences that shape this design:

- **Response→requester routing today is implicit in "which physical port, which channel".**
  Within a port, reads and writes never share a channel, so no arbitration is needed at all.
  Nothing anywhere reads `r.payload.id` or `b.payload.id` (verified by grep over
  `src/main/scala`: only the three *write* sites of `ar.payload.id` exist). The bundle has the
  fields (`M68kFullCoreSynth.v:44,27,66,84,101,124,141`); the logic does not.
- **IDs are not globally unique.** Both walkers emit AR ID 2 and AW ID 3. Harmless on separate
  ports; a hard bug the moment anything is merged.
- The architecture spec makes separate I/D masters *non-negotiable*
  (`docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md:379-380`), but the
  m68k-ooo socket contract this project wants to drop into exposes only `axi_i_*` and `axi_d_*`
  (memory: `drop-in-replacement-gap-assessment`). **The two walker masters therefore have to be
  folded into the I/D masters eventually — and that fold is impossible without exactly the
  ID-allocation and per-ID response-routing infrastructure this document builds.** That is a
  significant, independent reason to do this work, beyond IPC.

### 1.2 Correction 2 — `r.ready` is state-gated, so an unexpected R beat is silently *dropped*

`axi.r.ready` defaults `False` and is asserted only inside the refill/walk state:
`IcachePlugin.scala:194` / `:343`, `DcachePlugin.scala:144` / `:379`, `TableWalker.scala:76` /
`:114`. `axi.b.ready` *is* unconditionally `True` (`DcachePlugin.scala:149`,
`ItlbPlugin.scala:75`, `DtlbPlugin.scala:83`).

So the brief's "`axi.r.ready`/`b.ready` are unconditionally True" is right for B and wrong for
R, and the R case is worse than unconditional-ready would be: a beat arriving outside the
window is not back-pressured, it is *consumed and thrown away* by the AXI handshake never
completing… precisely, `r.valid` stays high with `r.ready` low, so the slave stalls. The real
hazard is the reverse: any design that lets a *second* transaction's beats arrive while the FSM
is servicing the first will mis-attribute them, because the FSM's `beatCnt` counts beats
without checking `r.id` (`IcachePlugin.scala:352-365`). **Rule for this design: `r.ready` must
become unconditional-per-MSHR-pool and every beat must be routed by `r.id`, never by FSM
state.** A sim assert on "beat with an ID that has no allocated MSHR" is mandatory (§8.2).

### 1.3 Correction 3 — the copyback design's slices P1 and P2 are already **landed**, not pending

The brief describes `docs/superpowers/specs/2026-07-23-dcache-copyback-store-retirement-design.md`
as "approved-but-unimplemented". Its first two slices are in the tree:

```
2ea177f mmu/cache: widen CacheMode to 3-way {WRITETHROUGH,COPYBACK,INHIBITED}      <- P1
4f87d56 cache/ls: carry cacheMode on DLoadCmd/DStoreCmd + capture it at translate  <- P1
2a2992e cache: check axi.b.resp (inert storeErr) + inhibited-load no-allocate/bypass <- P1
0cd2167 rob: add CacheControlService exposing CACR.DE (ss.cacr bit 31)             <- P1
e6ba39f ls: widen SqAlloc/SQ entry storage with vaddr/cacheMode/supervisor/precise <- P2
a1ecb7a ls: classify fast vs precise stores; withhold completion on the precise path <- P2
b9123fe rob: 5th completion port + sqFaultCompletion + preciseDrainBusyIn gate      <- P2
6089d98 ls: SQ at-head drain trigger for precise-path stores + error-pop            <- P2
916a4d5 ls: fix split-store sqCompletion firing on slot-A ack instead of terminal pop
d8475bd top: wire the precise-path SQ<->ROB loop in all 4 DUTs
fc6b64a fix(rob,ls): sticky h0PreciseCompletedSticky + directed collision/stall tests
```

So what remains of that design is **P3 (preempt-interlock hardening), P4 (dirty bits +
copyback drain + write-allocate + eviction writeback + async diagnostic fault), P5 (CPUSH/CINV
+ real CACR.DE), P6 (pipelined hit-drain)**. This matters a great deal for sequencing:

- P4 bakes in **dirty-bit and eviction granularity == the line size**. D4 (widening the L1D
  line to 64 B) is therefore *cheap now and expensive after P4*. See §11.Q2 — this is the one
  genuinely time-sensitive item in this document.
- P6 (pipelined hit-drain) and §5.6 (outstanding write-through) are the same lever seen from
  two sides and should be merged into one slice rather than done twice.
- Already-landed P1/P2 machinery this design must not disturb: `fastStore`
  (`LsEuPlugin.scala:448-450`), `headPreciseReady` (`StoreQueue.scala:200-204`),
  `preciseDrainBusyReg` (`StoreQueue.scala:392-402`, consumed only at `RobPlugin.scala:1069-1071`
  and `:1138-1140`), ROB `completion(4)`, `h0PreciseCompletedSticky`
  (`RobPlugin.scala:526-539`), and the deferred side-effect replay FIFO
  (`LsEuPlugin.scala:900-960`, `:1390-1487`).

### 1.4 Correction 4 — the L1D line is **16 bytes**, one AXI beat. That is the anomaly.

`DcachePlugin.scala:34-42`: `CacheGeometry(cacheBytes = 8192, lineBytes = 16, ways = 4, Vipt)`
⇒ 128 sets, offset 4 b, index 7 b, tag 21 b. The refill is `len = 0`, `size = 4` — literally one
128-bit beat (`:372-378`). The I-cache by contrast has 64-byte lines and already bursts
(`len = 1`, `size = 5`, 2 × 32 B beats, `IcachePlugin.scala:335-341`).

A 16-byte L1D line means a linear walk misses **every 16 bytes**. Behind a 64-byte-line L2 that
is 4 L1D misses per L2 line, three of which are L2 hits that would have been free had the L1D
line matched. This is why §5.2 argues the *bursting* half of this project outranks the
*multi-outstanding* half on the D side.

VIPT headroom for that change (`CacheGeometry.scala:36,52-56`: `offsetBits + indexBits ≤ 12`
for 4 KiB pages):

| Geometry | offset+index | VIPT-safe? |
|---|---|---|
| L1D today: 8 KiB / 16 B / 4-way, 128 sets | 4 + 7 = **11** | yes (1 bit spare) |
| L1D at 8 KiB / 32 B / 4-way, 64 sets | 5 + 6 = 11 | yes |
| **L1D at 8 KiB / 64 B / 4-way, 32 sets** | 6 + 5 = 11 | **yes** |
| L1D at 16 KiB / 64 B / 4-way, 64 sets | 6 + 6 = 12 | yes (at the limit) |
| L1I today: 16 KiB / 64 B / 4-way, 64 sets | 6 + 6 = **12** | yes — **exactly at the limit** |

Note the L1I is *at* the VIPT limit (`IcachePlugin.scala:23` asserts it). **The I-cache cannot
grow in capacity** without either a bigger minimum page size or dropping to PIPT. It *can*
change line size at constant capacity. Any I-side proposal must respect this.

### 1.5 The rest of the baseline, as verified

**LS execution is single-outstanding at three independent levels**, and all three must move
together or nothing changes:

1. **IQ**: one LS issue port (port 3 of 5, `IssueQueuePlugin.scala:51`,
   `IqContext.scala:26`), selected **strictly oldest-first in program order** by construction:
   ```
   :335  val lsPresent = B(slots.map(s => s.sel && isLs(s.context.uop)))
   :336  val ohLoldest = OHMasking.first(lsPresent)   // oldest OCCUPIED LS slot (ready or not)
   :337  val ohL       = ohLoldest & lsReady          // issue it ONLY if it is ready
   ```
   The `lsPresent`-then-mask idiom is what makes it in-order: if the oldest LS uop is not
   ready, *nothing* LS issues. The rationale at `:316-334` names the missing machinery
   explicitly ("no MOB / no load-store disambiguation"). Loads already use **dynamic** wakeup
   (`lsBusy` bitmap, `:175-181`; consumers latch `lsWait`, `:102,523,692-698`) — variable and
   out-of-order load latency therefore composes with the IQ as-is. That is a large piece of
   luck: **no IQ change is needed for out-of-order load completion.**
2. **LS EU**: `issuePort.ready := !busy && !s1Valid && !compValid` (`LsEuPlugin.scala:1013`),
   one `llReg` cache-launch context (`:494-504`), one `fwdHit/fwdStall/fwdData`
   (`:749-751`), and one shared `comp*` writeback register set (`:652-717`, ~26 fields).
3. **D-cache**: `loadCmdPort.ready` is driven **only inside `IDLE`** (`DcachePlugin.scala:330`,
   default `False` at `:141`), so no load is accepted at all during REFILL/REPLAY; scalar
   `miss*` state (`:107-138`).

**Storage port structure (important, and better than feared).** Each of the 8 D-side Mems has
exactly **1 write + 1 sync read** (`DcachePlugin.scala:90-91`, `:103-104`). Fills write; lookups
read. Those are *different physical ports*, so a fill beat and a load lookup can proceed in the
same cycle without contention. The real contentions are write-vs-write (fill vs store RMW,
resolved refill-priority at `:461-465`) and read-vs-read (load lookup vs store old-line read,
arbitrated load>store at `:293-307`, `:451-459`). `valids` and `victim` are flop arrays
(`:80-81`), so they are freely multi-ported.

**Refill delivers data by re-reading the line it just wrote** (`DcachePlugin.scala:411-441`,
REPLAY re-arms `rdSet`/`ldS1Valid` and lets the response fall out of S1 combinationally the
following cycle). There is no fill-to-load bypass on the allocating path — only the
non-allocating INHIBITED path bypasses, via `missLine`/`inhibitedResp` (`:228-231`, `:420-424`).
Cost: ~3 fixed cycles of post-data overhead. A fill-forward path removes 2 of them.

**Flush is retire-time only** (`RobPlugin.scala:709-715`, task #116). This is the single most
load-bearing simplification available to this design; see §4.3.

**The one existing multi-outstanding structure is the fetch ring**, not AXI:
`FetchAlignPlugin.scala:171-190` (depth-3 ring, per-entry `ringStale`/`ringDrop`), issue
throttle `ibufRoomForIssue` at `:230-231`, count at `:302-312`, stale filtering at `:261-300`.
`IcachePlugin` has **zero** knowledge of redirects (grep for `redirect|flush|stale|squash` over
that file returns nothing); its only control input is `invalidateAll` (`:40`). A wrong-path
refill runs to completion, allocates, and bumps `victim`; FetchAlign consumes the response as a
stale head and discards it (`:263,283,299`). **There is no refill-abort path anywhere.**

**The I-side miss is a total fetch-port stall.** `cmdPort.ready` is asserted in exactly one
place, `IcachePlugin.scala:261`, inside `IDLE.whenIsActive`. For the whole duration of
REFILL → PREDECODE → REPLAY the cache accepts nothing. End-to-end cost with a zero-latency
slave is **7 cycles + AXI latency L** (2 before AR, 2 for beats, then PREDECODE + REPLAY + S1 +
rsp = 4 fixed cycles after `r.last`); a warm hit is 3 cycles (asserted by
`src/test/scala/m68k040/cache/IcacheSpec.scala:221-256`). Exactly **one** further fetch is
parked in the T-stage across the refill (`:262-266,314-323`).

**The predecode block.** `IcachePlugin.scala:390-442`, PREDECODE state:
`val words = lineReg.subdivideIn(16 bits)` (`:392`, 32 words) then
`Vec((0 until 32).map(i => PredecodeWord.classify(words(i), words(i+1), words(i+2), words(i+3), …)))`
(`:424-431`) — `i` is a Scala `Int`, so this is **32 independent combinational instances of a
~860-line decode tree, all in one cycle**. Per instance: 64 bits in, **6 bits out**
(`ChunkPredecode` = `simple` + `lenWords[3:0]` + `ambiguousLine`, `IcacheTypes.scala:19-35`).
Inside one `classify`: a 16-arm `switch` on `op(15 downto 12)` (`PredecodeWord.scala:181`)
containing **17 `eaExt` instances + 10 `memDestExt` instances + 5 `fullExtLen`**, each itself a
nested `switch(mode)`/`switch(reg)`. Aggregate: order **860 EA-length decoders and 160 3-bit
adders in a single combinational block**, feeding a 192-bit register write. A **33rd** live
instance already exists in the Aligner (`Aligner.scala:63-65`, task #202's `p0Live`) and
evidently meets timing on the front-end critical path — a fact §6.4/§10.E leans on hard.

`lineReg` (`IcachePlugin.scala:108`) is a **512-bit staging flop register, separate from
`dataMem`**; a refill writes the line twice, once into 256-bit BRAM per beat (`:354-359`) and
once into `lineReg` (`:360-364`). Only PREDECODE reads it (`:392`). **One `lineReg` ⇒ one
refill's worth of predecode source at a time** — the central structural obstacle to I-side
MSHRs.

**`FetchRsp` has no tag** (`IcacheTypes.scala:66-77`: `pc`, `data[63:0]`, `fault`, `atc`,
`pred: Vec(ChunkPredecode, 4)`), is a **`Flow` with no `ready`** (`IcachePlugin.scala:38,183-188`),
and FetchAlign attributes each response to the **ring head** (`FetchAlignPlugin.scala:263,283`).
Out-of-order I-cache responses would therefore break FetchAlign silently. §6.2 turns this
constraint into a simplification rather than fighting it.

**The BTB/predictor is downstream of the I-cache**, queried with the *Aligner's decoded*
instruction PCs (`FetchAlignPlugin.scala:315,331-334`), not with `fetchPc`. Consequently
`fetchPc` advances **strictly `+8` sequentially** (`:249`) until a decoded, emitted branch
redirects it. There is no fetch-stage prediction at all. **The instruction fetch stream is
provably sequential between redirects** — which is the strongest possible argument for next-line
prefetch (§6.2) and a weak one for demand miss-under-miss (§6.1).

**`invalidateAll` is driven by nothing** and the latent hazard is confirmed: the clear block is
`IcachePlugin.scala:80-82`, PREDECODE's `valids(victimWay)(missSet) := True` is `:437`, and
SpinalHDL is last-assignment-wins, so `:437` overrides `:81` — a simultaneous invalidate leaves
the just-filled line valid. It also does not reset `arSent`/`beatCnt`/`missBusFault`, does not
squash the in-flight burst, and does not invalidate `s1*`/`rsp*Reg`. It is currently only *read*
— `FullCoreSynth.scala:79,94,107` fan it out to BTB/RAS/gshare from a dangling top-level input.
With N outstanding refills the window widens from one line/one cycle to N lines over N cycles.

`CPUSH` is a documented no-op (`ExceptionUnit.scala:1150-1152`, "No cache to push/invalidate in
this core — an internal NOP, like RESET"); the D-cache has no invalidate port at all (service
trait `DcacheTypes.scala:49-60` exposes only `loadCmd/loadRsp/loadBusy/store/storeAck/storeErr`).

### 1.6 The simulation memory model

**As-built update (2026-08-10): V1.6 is consolidated.** The general-purpose model is
`src/test/scala/m68k040/sim/AxiMemModel.scala`, with one `AxiReadEngine`, one
`AxiWriteEngine`, the always-on protocol checker, selectable legal/chaos response ordering,
and the zero/L2/crossbar latency and capacity controls specified later in this document.
`BehavioralMemAgent` and `Axi4ReadOnlyBehavioralAgent` are constructor-compatible shims over
that model; they contain no second AXI implementation.

All full-core instruction images use `AxiMemModel.attachProgramIFetch`; focused I-cache
fixtures use the same read engine with an explicit `SparseMemory`. The loader is the sole
owner of the I-side low-byte-first-per-opword convention. The D side deliberately remains
plain byte-at-address: these are two architectural views of the same big-endian program
image, not interchangeable layouts. Finite lock-step images request a 4 KiB `BRA.S -2`
run-ahead guard so speculative fetch cannot execute `SparseMemory`'s PRNG-filled bytes;
open-ended fuzz/ported images retain their explicitly selected beyond-image behavior.
The consolidated model deliberately exposed six deterministic LSU failures (memory-source
ADDA/SUBA/CMPA and dynamic-memory bit-field cases); the snapshot-coherency and MOVE-flag
RTL bugs were fixed, and the full lock-step result returned to the exact four-failure
historical baseline. No response-ordering exception was added to make the test pass.

Purpose-built fault responders may still exist where a test needs a response shape the
general address-based error model cannot express (for example one bad beat inside an I-cache
line burst). They are narrow fault injectors, not alternate general memory models.

### 1.7 There is no benchmark that measures a cache miss

`src/test/scala/m68k040/bench/IpcBenchSpec.scala`'s memory kernels are explicitly designed to
be **all hits after the first refill**: `kLoadStore` walks a 4-line window
(`0x3000/0x3010/0x3020/0x3030`, `:536`) — "all hits after first refill" (`:532`); `kMixed`
likewise (`:604`). Every kernel is straight-line-unrolled specifically to keep redirects out of
the LS pipe (`:527-528`, `:600-601`). So the existing IPC numbers measure *hit* throughput and
say nothing about miss behaviour, and there are **no miss-rate, MSHR-occupancy or stall-cycle
counters anywhere**. Aggregate IPC ≈ 0.53 (memory: `ipc-and-deadlock-findings`).

### 1.8 What the docs already say about the L2

Design intent exists; no implementation and no contract:
`docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md:315-320` — L1D
write-through/write-no-allocate so that "**L1D never holds a line newer than L2** ('never
dirty')", `:320` "**L2 = write-back** to main memory (the bandwidth filter)", `:379-381`
"**L2:** URAM-backed, **1 MiB** baseline (up to ~2 MiB), write-back to main memory", `:461` open
question "**L2 allocation policy** on L2 store miss". `MSHR` appears exactly once in the whole
repository, as a declared non-goal:
`2026-07-23-dcache-copyback-store-retirement-design.md:660-665` — "a non-blocking L1D / **MSHRs
and multi-outstanding LS EU** … and **pipelined multi-ID AXI write-through** … Both compose
cleanly with this design later." This document is the attempt to cash that cheque.

Note that the copyback design (P4) *deliberately breaks* the ":315-320 never-dirty" invariant,
so the architecture doc's justification for a 16-byte write-through L1D line is already gone.

---

## 2. Goals and non-goals

### 2.1 Goals

- **G1 — Stop a D-cache miss from blocking the LS pipe.** Serve hits (and forwards) while a
  miss is outstanding. This is the largest single structural IPC item in the core for a
  memory-referencing CISC ISA (memory: `ipc-and-deadlock-findings`).
- **G2 — Overlap multiple D-side misses** (miss-under-miss), including the common
  MOVEM / block-move / stack-burst shapes, and overlap store drain with load misses.
- **G3 — Right-size the D-side burst for an L2** — one bus transaction should move an
  L2-line-sized chunk, not 1/4 of one (§5.2).
- **G4 — Stop an I-cache miss from stalling the entire fetch port**, and hide sequential
  I-miss latency with next-line prefetch (§6).
- **G5 — Real AXI ID discipline**: unique IDs, per-ID response routing, correct under both
  in-order and out-of-order response arrival. This is also the enabler for folding the walker
  masters into the I/D masters for socket compatibility (§1.1).
- **G6 — Reduce, not increase, the I-side predecode area** while doing G4 (§6.4).
- **G7 — Compose with the landed precise-store path and the pending copyback design** without
  weakening either (§5.7), and *amend* the copyback design where MSHR invariants make its
  hand-patched hazards structural (§5.3).
- **G8 — Make the verification substrate honest first** (§8).

### 2.2 Non-goals (explicit, so the plan does not drift)

- **N1 — No memory order buffer / no load-store reordering.** LS issue stays program-ordered
  (D1). §5.1 quantifies what is and is not obtainable without one.
- **N2 — No second LS EU, no two LS issue slots per cycle.** That needs N1 plus a second
  D-cache load port plus a second ROB completion port (enumerated in §10.A).
- **N3 — No hardware I/D coherence, no snooping.** Software coherence via CPUSH/CINV, matching
  the 68040 model. §7.4 scopes only *reaching* the L2.
- **N4 — No changes to the L2 or the SoC.** This document may state requirements on the SoC
  seam, and must flag them (§11.Q3, §11.Q7), but proposes no SoC RTL.
- **N5 — No stride/pointer-chasing D-side prefetcher.** Next-line *I*-side prefetch only
  (§6.2). D-side prefetch is discussed and rejected for now in §10.D.
- **N6 — Not the copyback design's remaining slices.** P3/P4/P5/P6 stay that design's work;
  this document only states the interactions and the one sequencing dependency (§11.Q2).
- **N7 — Exact-size MMIO reads** stay deferred, as in the copyback design's §5.5 — but §5.5
  here tightens *ordering* for INHIBITED accesses, which is a correctness matter rather than a
  width matter.
- **N8 — FPU. Out of scope, as always.**

---

## 3. How the L2 changes the design space — mostly FACTS, and one decisive blocker

**The L2 is real, fully specified, and I read it.** It is in **`/home/qwertyoruiop/macqd700-soc`**
(`rtl/soc/l2c.v`, `l2c_ctrl.v`, `l2c_mshr.v`, `l2c_defs.vh`, contract in `docs/l2c_spec.md`, 837
lines). That is the SoC the *predecessor* core plugs into — `macqd700-soc/.gitmodules` points
`path = cpu` at `/home/qwertyoruiop/m68k-ooo` branch `split/macqd700-soc`, i.e. the Verilog OOO
core. **`m68k-core-040-ooo` has zero SoC integration today** (no submodule, no sbt dependency, no
vendored copy, no mention of `macqd700`/`l2c` anywhere in `docs/`, `src/`, `AGENTS.md`,
`README.md`). So this is the contract we would be *retargeting*, not one we are currently bound
by. `/home/qwertyoruiop/m68k-soc-iifx` also has an `L2Cache`, but it is the **68030** in-order
SoC (`m68k-soc-iifx/build.sbt:10-25` depends on `m68k-core-030-inorder`;
`L2Cache.scala:44-45` — "This is the **68030** RMW/TAS enforcement point") and matters only as a
comparison (§3.4).

### 3.1 The L2's actual contract

| Property | Value | Source |
|---|---|---|
| Line size | **64 bytes** | `l2c_defs.vh:26-42` (`L2C_LINE_BYTES 64`, `L2C_OFF_BITS 6`) |
| Geometry | 4096 sets × 8 ways = **2 MB**, tag = `addr[31:18]`, index = `addr[17:6]` | `l2c_defs.vh:26-42`, `docs/l2c_spec.md:153-166` |
| Storage | 8 per-way URAM 4096×512b (2-cycle registered read); tags/valid/dirty in per-way BRAM (1-cycle); PLRU 7b/set | `docs/l2c_spec.md:191-200`, `l2c_ctrl.v:202-216` |
| Replacement | tree-PLRU with a **busy-way mask** (MSHR-claimed ways excluded, so two misses cannot double-claim a way) | `l2c_ctrl.v:229-231`, `l2c_mshr.v:44-47,107` |
| Write policy | **write-back, write-allocate**; write miss allocates, fetches the line, merges the store quadrant at install | `l2c_ctrl.v:243-247`, `l2c_mshr.v:216-233` |
| CPU-facing bus | full **AXI4** (AW/W/B/AR/R, 8-bit LEN, WSTRB, WLAST), 32-bit addr, **128-bit data**, `ID_WIDTH = 6` | `l2c.v:37-39,49-60` |
| ID composition | XID = `{xbar slot[1:0], CPU master ID[3:0]}` — **the CPU gets 4 ID bits = 16 IDs** | `axi_xbar.v:168-169,2890`, `l2c.v:5-7`, `docs/l2c_spec.md:136-140` |
| Bursts | **INCR only** (FIXED/WRAP ⇒ one SLVERR per burst); LEN up to 255 structurally; each beat independently tag-looked-up | `l2c_ctrl.v:110-116,136-139,306-307,326-335` |
| No `AxLOCK`/`AxCACHE`/`AxPROT`/`AxQOS` ports at all | the L2 is exclusive-access-blind and cache-attribute-blind | `l2c.v:49-60` |
| **Hit latency** | **measured 5.1–5.3 cycles** round trip (target 4–6) | `docs/l2c_spec.md:668-679` |
| **Miss** | **no critical-word-first, no streaming** — fill AR is always line-base-aligned `ARLEN=3`, all 4 beats assembled into `line_reg`, response produced only after `S_INSTALL → S_PRSP`. **The requester pays the whole 64-byte line even for a 4-byte load.** | `l2c_mshr.v:160-163,211-246` |
| Internal MSHRs | **8 primary + 4 same-line secondary merges each**; allocation back-pressures rather than dropping | `l2c_mshr.v:1-7,131`, `l2c_defs.vh:38-41` |
| **Distinct concurrent AR IDs** | **YES** — nothing restricts ID diversity; `tb/tb_l2c_chain.cpp:200-207` issues 8 back-to-back reads with 8 distinct IDs | — |
| **Same AR ID twice concurrently** | **BLOCKED at dispatch** by `id_busy_c`, a combinational CAM over every valid MSHR's primary ID and populated replay-slot IDs | `l2c_ctrl.v:140-142,151`, `l2c_mshr.v:100-116` |
| **Out-of-order R/B across IDs** | **YES, by design** — "Cross-ID ordering remains unconstrained by design". Mechanism: MSHR service is **round-robin by table index, not allocation order**, plus a fixed-priority bypass > MSHR > hit response arbiter | `docs/l2c_spec.md:474-477`, `l2c_mshr.v:121-128`, `l2c.v:296-336` |
| Header acceptance | exactly **1 deep** (`s_arready = !ar_have`), but `ar_have` clears when the burst's last beat enters the lookup pipe — long before R returns | `l2c_ctrl.v:113,296-299,306` |
| Downstream fill | **only ONE fill AR outstanding** (one global FSM, one `act` entry, one 512-bit `line_reg`) ⇒ 8 concurrent misses cost 175 vs 195 cycles = **1.11× only** | `l2c_mshr.v:9-12,135-141,159-165`, `docs/l2c_spec.md:483-489` |
| Behind the L2 | `l2c → vram_priority_mux3 → axi_async_bridge (CDC; AR/AW/B depth 4, W/R depth 16) → axi_ddr4_mig_bridge (**8 reads / 4 writes outstanding, cut-through**, same-64B-line RAW interlock) → MIG → DDR4`. `clk_core` 50–200 MHz, `clk_ddr_ui` 300 MHz async. | `docs/handoff_l2c_cutover.md:9-17`, `docs/ddr4_mig_bridge_contract.md:48-107,177-196`, `rtl/soc/axi_async_bridge.v:36-40`, `docs/clocking.md:29,37` |
| Reset | ~**4096 cycles** of tag-clear walk with `s_axi_*ready` held low; dirty lines intentionally dropped | `docs/l2c_spec.md:117-122,407-418` |
| Maintenance interface | **none** — no invalidate port, no flush port, no snoop port, no CSRs. Only reset clears it. | `l2c.v:46-85`, `docs/l2c_spec.md:495` |

Two important corrections to the L2's own docs, worth knowing before reading them:
`docs/l2c_spec.md:491-494` claims only full-width 16-byte INCR bursts are supported and other
`AxSIZE` gets SLVERR — **stale**; the RTL rejects only non-INCR (`l2c_ctrl.v:26-37,136-139`) and
narrow `AxSIZE` is legal. And the L2's `L2ZeroRegionSpec`/`L2ContentMergeSpec` named in the brief
are **iifx** diagnostic probes, not L2 features (a decode-ordering regression test for
above-installed-RAM reads, and a WSTRB byte-merge correctness probe respectively).

**What this does to §2's goals:** A 64-byte L2 line **confirms D4** (§5.2) exactly — widen the
L1D line to 64 bytes and each L1D refill becomes precisely one L2 line. A 5.1–5.3-cycle L2 hit
means an L1D miss that hits in L2 is *short*, while an L2 miss pays DRAM plus a full 4-beat fill
plus ~4 fixed cycles — so miss latency **is** strongly bimodal, which is exactly what MSHRs
monetise. And because the L2 blocks a repeated ID at its front door (`id_busy_c`), **distinct IDs
per outstanding request are mandatory, not optional**: a constant-ID master gets *zero* benefit
from the L2's 8 MSHRs. That is decision D2, now on a factual footing rather than a hedge.

### 3.2 The decisive blocker: the SoC crossbar is single-outstanding per master port

`macqd700-soc/rtl/soc/axi_xbar.v` (3 masters × 6 slaves; M0 = CPU LSU, M1 = host debug, M2 = CPU
instruction fetch; L2 on S0) admits **one read and one write per master port**:

```verilog
// axi_xbar.v:2388-2412 — a new AR is only requested when the slot FSM is idle
assign req_rd_ddr[mi] = (rs_state[mi] == RS_IDLE) && mr_arvalid[mi] && ...
// rs_state: RS_IDLE -> RS_WAIT_SLV_AR -> RS_WAIT_R -> RS_IDLE   (axi_xbar.v:2278-2284)
//   returns to IDLE only after RLAST.
```

and for writes, verbatim from `axi_xbar.v:1234-1258`:

> `sw_owned` locks a slave's whole AW→B sequence to one master: the next AW (from ANY master) is
> not even arb-latched until the previous write's B has been consumed. Combined with the single
> per-master `ws_state` FSM this means **1 outstanding write per master AND per slave**.

The same comment records that pipelining was **analysed and deliberately not implemented**,
because same-master pipelining across slaves "can complete out of order, which violates AXI
same-ID B ordering for a master reusing one AWID (the LSU does) unless the xbar reorders B. That
is a substantial, risk-bearing redesign. Revisit only with a dedicated design pass +
multi-outstanding BFM coverage; do not bolt onto the current owner scheme."

Response routing is by the 2-bit **slot tag** in the ID, and the ID returned to the master is the
latched `rs_mid[mi]` (`axi_xbar.v:2604,2677-2680,2890`) — since only one transaction per slot is
ever live, the master's own ID bits are decorative *to the crossbar* (they do reach the L2 intact
in `XID[3:0]`).

**Consequence, stated as bluntly as it deserves: on the SoC as it exists today, every
multi-outstanding slice in this document delivers exactly zero end-to-end benefit, because the
crossbar serialises at the master port. The one exception is hit-under-miss (§5.1 item 1), which
needs no bus concurrency at all.** This is the single strongest argument in the document for the
recommended ordering — land slice **D1** (hit-under-miss, no AXI change) first, and treat the
multi-ID slices (D2, I3) as **contingent on a SoC-side crossbar rework** that the SoC's own
authors deferred with an explicit warning. §11.Q3 asks for that decision. Regression surface for
whoever does it: `tb-axi-xbar`, 196 tests (`docs/handoff_l2c_cutover.md:97`).

Note the irony worth recording: the L2's own docs name CPU-side MSHRs as the thing that would
make its single-fill-FSM limitation matter ("expected to matter once L1-miss MSHRs land
CPU-side"), while the crossbar between them makes CPU-side MSHRs moot. **Three layers each
deferred multi-outstanding on the assumption another layer would go first.**

### 3.3 Prior art in the SoC repo that independently corroborates this design

`macqd700-soc/docs/fpga_axi_address_space_lsu_audit.md:43-98` already states a **"minimum safe
multi-outstanding contract"** for this core family. It matches this document's independently
derived conclusions closely enough to be worth quoting as corroboration:

- only cacheable RAM/ROM/VRAM **loads** may be multi-outstanding; uncached/MMIO, maintenance,
  split-unaligned accesses and **stores** stay single-outstanding and ordered — this is §5.4's
  store-exclusive-request-path rule plus §5.5's INHIBITED serialisation, arrived at separately;
- **flush must poison in-flight load slots** — §4.3;
- item 6: "**Dcache/AXI responses need IDs or slot tags. The existing single `dc_req` and single
  `rvalid/bvalid` upstream interface is not enough.**" — §5.4's `DLoadRsp` tagging and §7.1's ID
  scheme;
- declared non-goals there: store-to-load forwarding (this core already has it) and dirty-victim
  eviction overlapping independent fills (this document also defers that — §5.7 keeps eviction
  per-MSHR and sequenced).

That three independent derivations agree is reassuring. It also means a future integrator has a
document on the SoC side to check this against.

### 3.4 Residual unknowns and one flagged hazard

Only three things remain genuinely unknown, and none of them changes a mechanism:

- **U1 — real DDR4/MIG read latency in cycles is undocumented** in either repo. The only figures
  that exist are the L2's 5.1–5.3-cycle hit and simulation models (`tb_l2c` uses a random 10–60
  cycles; `rtl/board/sim_mig_backend.v:33` models no refresh/bank/row timing at all). **Do not
  build any performance claim on a specific DRAM number.** §8.1's model must sweep it.
- **U2 — whether the L2 illegally interleaves read data across IDs.** A multi-beat *hit* burst
  releases the R channel for 3 cycles between beats (`l2c_ctrl.v:302-366`), `r_lock` clears on
  every accepted beat (`l2c.v:312-320`), and the MSHR path outranks the hit path
  (`l2c.v:298-301`) — which appears to permit a different-ID MSHR replay beat to land mid-burst.
  AXI4 prohibits that. The L2's own testbench cannot catch it: its checker is a **per-ID**
  deque (`docs/l2c_spec.md:770-780`), blind to cross-ID interleaving by construction. **Good
  news: this design is robust to it anyway** — §5.3 routes *every* beat by `r.id` into that
  MSHR's own beat counter, so even illegal interleaving is handled correctly. It should still be
  reported to the SoC side and covered by a directed test (§8.2), because today's constant-ID
  single-outstanding masters can never expose it.
- **U3 — the SoC's crossbar rework (§3.2) is a decision, not an unknown.** §11.Q3.

**Two assumptions I am deliberately *not* making, because the standing rule forbids them
(§4.1):** that any particular address range is backed; and that "the L2 answered OKAY for this
line before, so it will again." §3.5 shows exactly how right the rule is here.

### 3.5 The SoC side vindicates the no-static-address-map rule, emphatically

Context only — **none of this may leak into the core**, and it is recorded to show that the rule
is protecting against real behaviour rather than a hypothetical:

- **`ddr_flatten()`** (`axi_xbar.v:1050-1069`, `axi_defs.vh:45-65,152-154`) rewrites addresses
  before the L2: RAM `0x0000_0000` (1 GiB window) → DDR offset 0, the Quadra-700 RAM-probe alias
  `0x5800_0000` (64 MiB) → **the same DDR offset**, ROM mirror `0x4000_0000` → `0x4000_0000`,
  legacy framebuffer `0x6000_0000` → `0x4040_0000`. So **three distinct CPU addresses alias into
  one L2 tag space**, and a CPU-side belief that "different address ⇒ different data" is false.
- **The RAM window size is a runtime knob**: `ram_window_mask`, driven by `cpu_ram_window_lg2`
  from the CPU's own debug CSR (`cpu_socket.vh:47`, `axi_xbar.v:1054-1060`), truncates addresses
  above installed RAM.
- **ROM-window writes are silently dropped with `BRESP = OKAY`** when the writer presents as the
  CPU LSU, but allowed for the boot FSM (`axi_xbar.v:76-82`) — MAME-canonical Q700 behaviour. A
  store can therefore "succeed" and not land, and **the CPU cannot detect it and must not try.**
- **VRAM byte-lane swap on S3**: the crossbar byte-swaps each 32-bit word and reverses each WSTRB
  nibble at that boundary (`axi_xbar.v:106-117`) — endianness is not uniform across the map.
- Unmapped reads return `0x00000000` open-bus, MAME-faithfully; Lite-only slaves reject bursts
  with SLVERR (`axi_xbar.v:129-140`).
- The L2's own cacheable decode is compile-time (`CACHEABLE_BASE = 0x0`,
  `CACHEABLE_SIZE = 0x4100_0000`, `l2c.v:44-45`), and the bypass-window mask sense was a real
  corruption bug that survived three review rounds (`docs/l2c_spec.md:612-631`).

The only contract-level facts a CPU may rely on are: 32-bit flat physical post-MMU addresses,
128-bit data, INCR-only bursts, one BRESP per burst, and per-ID ordering. Everything in §5–§7 is
built on exactly that list.

One observation that follows and is worth recording *as an observation, not a work item*: because
`0x0000_0000` and `0x5800_0000` alias to the same DRAM, an L1D (or L2) can in principle hold two
lines for the same physical data under different tags, and a write via one is invisible via the
other. That is a SoC-level aliasing property the CPU has no architectural visibility into; the
alias exists for a boot-time RAM-sizing probe. **The correct core-side response is none at all** —
attempting to handle it would require exactly the forbidden knowledge.

### 3.6 Where the L2 leaves the core's own caches

With the L2 present and the copyback design's P4 landed, the L1D becomes write-back and the L1I
stays clean-only. The architecture doc's original justification for a write-through L1D
(`2026-05-31-…-architecture-design.md:315-320`, "L1D never holds a line newer than L2") is
retired by P4 anyway — and note that the L2 is **write-back, write-allocate** (§3.1), so an L1
dirty writeback is simply an ordinary AXI write burst that merges into the L2's own policy. That
is precisely the composition the L2's docs describe (§7.4). Worth stating so nobody later
"restores" the write-through invariant as if it were a requirement.

---

## 4. Cross-cutting invariants

### 4.1 The standing no-SoC-address-map rule, applied mechanism by mechanism

The rule (memory: `feedback-no-soc-address-map-assumptions`, and the copyback design's §4.2):
correctness may rest only on **(a)** software-configured MMU/TT/CACR attributes, **(b)** the
CPU's own cache/TLB state with a real CPU-controlled invalidation path, and **(c)** the
**actual, contemporaneous** response of the transaction being decided on. Never on a remembered
past response, and never on any belief about SoC decode topology.

Three mechanisms in this document are exactly the shape that could smuggle a violation in. Each
is checked explicitly:

**(i) Secondary-miss merging (§5.3).** Two loads to the same line share one MSHR and therefore
one bus transaction. *This is not the banned pattern*, and the distinction is worth stating
precisely because it is easy to blur: merging does not reuse a *past* response, it applies **one
single contemporaneous response to two accesses whose bus requirement is byte-identical**. The
binding rules that keep it legitimate:

- **M1** — the fill's `r.resp` must be propagated to **every** merged waiter. A non-OKAY
  response faults the **oldest** merged waiter precisely (the flush from that fault removes the
  younger ones, which is correct — they are architecturally after it).
- **M2** — a merged fill that errors allocates **nothing** (today's behaviour,
  `DcachePlugin.scala:392-393`), so a later access to the same line issues a **new real
  transaction**. No error verdict is ever cached. This is the property that keeps M1 from
  becoming "we remember this line is bad".
- **M3** — merging is forbidden across cacheability classes, and **INHIBITED accesses never
  merge at all** (two device reads must be two bus reads). Enforced by §5.5's rule that
  INHIBITED accesses are strictly serialised, which makes merging structurally unreachable for
  them.

**(ii) I-side next-line prefetch (§6.2).** A prefetch issues a real bus read for an address the
program has not demanded. The rule-compliant formulation:

- **P1** — a prefetch is issued **only** for a next line whose translation is **already
  resident** in the ITLB and whose page is software-configured cacheable. A prefetch must
  **never trigger a table walk** and must never be issued for an INHIBITED page. (Class-(a)
  evidence only; no guessing.)
- **P2** — a prefetch that receives a non-OKAY response is **silently discarded**: no
  allocation, no architectural fault, no sticky diagnostic record, no core halt. This is
  mandatory precisely *because* the core cannot know whether the address is backed — the
  prefetch never asserted that it was.
- **P3** — because P2 allocates nothing, a later **demand** fetch of that line issues a **new
  real transaction** and gets its own contemporaneous response, with the existing
  task-#211 I-side `r.resp` check (`IcachePlugin.scala:352-353`, and its D-side sibling from
  task #189) delivering the fault precisely. No cached verdict, in either direction.
- **P4** — a prefetch must never cross a page boundary on a *guess*: if the next line is in the
  next page, P1 already requires a resident ITLB translation for that page, so the prefetch is
  simply dropped when there is none.

**(iii) Early ack / posted writes (§5.6).** Allowing multiple outstanding write-through beats
must not let any store's *architectural* success be decided on anything but its own real B
response, for stores on the precise path. The rule is inherited unchanged from the landed P2
work: precise-path stores retire on their own contemporaneous B (`StoreQueue.scala:342-390`),
full stop, and §5.6 only ever adds concurrency to **fast-path** (software-declared-cacheable)
writes, whose error story is the copyback design's asynchronous diagnostic channel and never a
retirement decision. **No "this line was written successfully before" state is introduced
anywhere.**

**(iv) MSHR state itself.** An MSHR holds {line address, set, way, cacheability, beat progress,
poison} for the *duration of one transaction* and is deallocated when it resolves. It is not a
cache of anything and has no persistence across transactions. Clean by construction.

I also re-checked the one place a reviewer might suspect: **way reservation** (§5.3) records
"MSHR k owns way w of set s" — CPU-internal cache state with a real deallocation path, class
(b), and it never survives the transaction. Clean.

### 4.2 What ordering the core actually requires

Enumerated so §7.1's ID scheme can be justified rather than asserted.

| Pair | Ordering required? | Enforced by |
|---|---|---|
| load → load, different lines | **No.** The ROB completes out of order natively (robId-keyed, `RobPlugin.scala` completion ports); the IQ uses dynamic load wakeup (`IssueQueuePlugin.scala:175-181`). | Nothing needed. Distinct IDs are safe. |
| load → load, same line | N/A — merged into one transaction. | §5.3 merging. |
| store → load, same bytes | **Yes.** | Existing SQ forwarding (`StoreQueue.scala:225-298`), unchanged. |
| store → load, same **line**, no byte overlap | **Yes** — a missing load must not refill a line an undrained store has not yet written. | Existing `sameLine` stall (`StoreQueue.scala:250-261`), unchanged. §5.2 notes this stall's granularity is coupled to D4. |
| store → store | **Yes** (same address must not reorder; program order is the simplest sufficient rule). | One shared AW ID ⇒ AXI4 requires same-ID B in order (D2), plus the SQ's ring order. |
| write → read, same line (eviction then refill) | **Yes**, and **AXI provides nothing here** — read and write channels are independent, so the core must self-order. | Per-MSHR sequencing: the eviction's B must be received before that MSHR's AR is issued (the copyback design's §4.3 EVICT_WR → REFILL order, now per-MSHR). §5.3 D3 makes the same-line case impossible to reach concurrently anyway. |
| read → write, same line (fill then drain) | **Yes.** | D3's per-set exclusion: a drain to a set with an active fill holds. |
| INHIBITED access → anything | **Yes, total order.** Device accesses must not reorder against each other or be duplicated. | §5.5: INHIBITED accesses are strictly serialised — no other D-side transaction outstanding when one launches, and it completes before the next launches. |
| instruction fetch → anything | **No.** Self-modifying code requires CPUSH/CINV architecturally on the 68040. | Nothing needed. **The I side can reorder freely** — which is why it is the low-risk side. |

**There is no need for any new architectural fence.** The natural serialisation points are:
INHIBITED accesses (§5.5), exception entry, CPUSH/CINV, and RTE. §7.3 defines a single
`dQuiesce` signal (all MSHRs empty, no outstanding writes) for those, and shows it is bounded.

### 4.3 Retire-time-only flush ⇒ blanket poison is correct (and this is a big simplification)

Today the LS EU carries **one scalar** squash bit:

```
LsEuPlugin.scala:1039  val poisoned = RegInit(False)
              :1041    when(<new access accepted>) { poisoned := False }
              :1042    .elsewhen(sqFlushSig && (busy || s1Valid)) { poisoned := True }
```

and it gates every `captureCompletion`/`captureFault` (`:1102,1128,1143,1174,1231,1316,1357,1375`).
With N outstanding accesses this must become **one poison bit per tracking entry**. The question
is whether poisoning needs an *age comparison* against the flush point.

**It does not, because flush is retire-time only** (`RobPlugin.scala:709-715`, task #116; the
copyback design's §4.1 relies on the same fact). The flush point is the ROB head; by
construction every instruction older than the head has already retired and left the machine.
Therefore **every** access outstanding in the LS pipe or an MSHR at flush time is younger than
the flush point and is wrong-path. Blanket-poison-all is correct and needs **no robId
comparators in the MSHR/tracking array** — a meaningful area and FMax saving.

Two riders that must be written into the plan as invariants to re-check:

- **R1** — if a future front-end early-resolve redirect (a fetch-stage predictor correction that
  flushes before retire) is ever added, this argument collapses and poison must become
  age-based. Flag it in the code comment at the poison site so the coupling is discoverable.
- **R2** — the interrupt/trace *preemption* paths retire-preempt a head **without** requiring
  `completes(h0)` (`RobPlugin.scala:416-438` shape, gated by `preciseDrainBusyIn` at `:1069-1071`
  and `:1138-1140`). A preempted head re-executes after RTE. For a **load** that is harmless
  (loads have no side effects) **except for MMIO loads with read side effects** — which is an
  independent argument for §5.5's rule that INHIBITED accesses are non-speculative and
  serialised.

A poisoned fill still **completes on the bus and still allocates** into the cache. That is
harmless and is already the I-side's established behaviour (§1.5: wrong-path refills allocate;
`FetchAlignPlugin` filters on the consumer side). Reusing that principle on the D side is
deliberate: **no refill-abort mechanism is proposed anywhere in this document**, because none
exists and inventing one would be a much larger change (§10.F).

---

## 5. D-side design

### 5.1 What MSHRs buy with no MOB — honestly

Since D1 keeps LS issue in program order, loads still *start* in order. Only completion
reorders — which the ROB and IQ already support natively (§4.2). The obtainable wins:

1. **Hit-under-miss.** A younger load that hits the cache (or forwards from the SQ) completes
   while an older load's miss is outstanding. Needs **zero bus concurrency** — this is worth
   emphasising, because it means the biggest single win is available without touching AXI, IDs,
   or the memory model's reordering. In a CISC memory-referencing stream (every mem-mem MOVE,
   every RMW, every MOVEM) this fires constantly.
2. **Miss-under-miss.** Consecutive misses overlap: MOVEM across lines, block moves, stack
   bursts, `memcpy`-shaped loops. With a 16-byte line (§1.4) misses are dense, so this fires
   often — but note two interactions that both cut against it: **widening the line (D4) reduces
   miss density and therefore reduces how much miss-under-miss is worth**, and **the SoC crossbar
   serialises it away entirely today (§3.2)**. So miss-under-miss is the *least* certain of these
   four wins, and it is the one this document makes contingent (D7).
3. **Store-drain / load-miss overlap.** Today a drain and a refill cannot overlap: the drain
   holds `drainBusy` until B (`StoreQueue.scala:189,363-390`) and a refill blocks the whole
   cache. With MSHRs they proceed concurrently (subject to D3 and the `sameLine` stall).
4. **Removal of the "miss blocks the LS issue port" coupling.** `issuePort.ready`
   (`LsEuPlugin.scala:1013`) currently goes low for the entire miss, which back-pressures the
   IQ's LS port and — because LS issue is oldest-LS-first (`IssueQueuePlugin.scala:335-337`) —
   parks the LS class entirely. Breaking that is a second-order win on top of (1).

**What is NOT obtainable without a MOB**, stated plainly so nobody expects it: a younger load
cannot execute *before* an older store whose address is not yet known. In an in-order LS stream
a single long-latency miss still blocks every *subsequent* LS access that also misses beyond N
MSHRs, and any store that follows a missing load still waits its turn. So the realistic
expectation is a **workload-dependent improvement in the 10–25% IPC range on memory-heavy
kernels, not a multiple**. I am deliberately refusing to put a single headline number on it:
§8.1's miss-heavy kernels plus the L2-faithful latency model are precisely how this should be
measured before anyone commits to a target. Anyone who wants a bigger number is asking for the
MOB (§10.A), which is a separate project.

**And the honest ordering of the four wins by expected value, given what §3 now establishes:**
D4 (bursting, §5.2 — 4× fewer misses, works today, crossbar-independent) > (1) hit-under-miss
(no bus concurrency needed, works today) > (3) drain overlap > (2) miss-under-miss (blocked
end-to-end by the crossbar until it is reworked). That ordering is the reason §9's slice order is
what it is, and it is close to the *opposite* of what "add MSHRs" intuitively suggests.

**A structural note that keeps the MOB reachable.** The load-tracking table proposed in §5.4 is
"the load-tracking half of a MOB without the disambiguation half". Keeping it tagged,
poison-capable and address-carrying means a later MOB adds address-match + replay logic on top
rather than replacing the structure. That is worth the small extra generality now.

### 5.2 Bursting first: widen the L1D line to 64 bytes (D4)

**The claim:** on the D side, the *bursting* half of this project is worth more per unit of risk
than the *multi-outstanding* half, and it should probably land first.

Reasoning:

- A 16-byte line (§1.4) makes the L1D miss every 16 bytes on any linear access. A 64-byte line
  cuts miss count ~4× on such streams and cuts tag/valid state 4× (32 sets instead of 128).
- **The L2's line is 64 bytes (§3.1) and the L2 has no critical-word-first: its fill AR is always
  line-base-aligned `ARLEN=3` and the requester's response is produced only after the whole line
  is assembled and installed (`l2c_mshr.v:160-163,211-246`).** So on an L2 *miss* the CPU already
  pays for a full 64-byte line today, and a 16-byte L1D line simply throws three quarters of it
  away and then misses three more times to re-fetch neighbours as L2 *hits* (5.1–5.3 cycles each,
  §3.1). Matching the L1D line to the L2 line converts 4 transactions into 1 and captures data the
  CPU was charged for regardless. **This makes D4 the single best-supported change in the
  document, and it is independent of the crossbar blocker (§3.2) — a wider burst is still one
  transaction.**
- It is VIPT-safe at constant capacity (§1.4 table: 6 + 5 = 11 ≤ 12).
- It is **structurally simpler than MSHRs**: `len = 3`, `size = 4`, a beat counter, per-beat
  `dataMem` writes (the I-cache already does exactly this, `IcachePlugin.scala:354-359`), and
  tag/valid allocation on the last beat.

**Costs, honestly:**

- **Refill data time rises from 1 beat to 4.** With a fill-forward/critical-word-first path
  (§5.4) the *demanded* word is available on its own beat, so the latency the load sees need not
  rise at all; without one, a naive implementation makes every miss 3 cycles longer. **CWF is
  therefore not optional if D4 lands** — it is part of the same slice.
- **The SQ `sameLine` stall coarsens 4×** (`StoreQueue.scala:250-261` stalls a load if *any*
  older in-flight store touches the same line). At 64 bytes this window is 4× wider and will
  cost real cycles on store-then-load-nearby patterns, which are extremely common (stack
  frames!). Two mitigations, in preference order: (a) **merge undrained SQ stores into the
  refilled line** as it fills — the standard fix, removes the stall entirely, and is a natural
  addition once a fill has a staging path anyway; (b) keep the stall but make its comparison
  16-byte-granular *and* additionally interlock at line granularity only when the store's
  16-byte chunk is one of the chunks still to arrive. I recommend costing (a) in the plan pass
  and treating this as the main technical risk of D4.
- **Eviction/dirty granularity becomes 64 bytes** — which is exactly why this must be decided
  **before** copyback P4 (§11.Q2). Write amplification on eviction rises, but copyback also
  removes 4× more individual write-through beats, so the net bus traffic almost certainly falls.
- 4 beats needs `beatCnt` widening and a burst-length parameter. `len = 3` at `size = 4` is INCR
  and well inside what the L2 accepts (§3.1: 8-bit LEN, INCR-only, narrow `AxSIZE` legal), so
  there is no fabric risk on this axis.

**Recommendation:** decide D4 now; implement it as its own slice **after** the low-risk
hit-under-miss slice (so that CWF/fill-forward already exists) and **before** copyback P4. The
size is **64 bytes**, matched to the L2's measured line (§3.1) rather than chosen on principle.

### 5.3 The MSHR array

**Parameters:** `N_MSHR` = build parameter, validated at 1 / 2 / 4. Recommend shipping at 2 and
raising only on measurement (§8.3).

**Per-MSHR fields:**

| Field | Width | Note |
|---|---|---|
| `valid` | 1 | |
| `lineAddr` | 28 (or 26 at 64 B lines) | physical line address; the AR address and the merge key |
| `set` | 7 (5 at 64 B) | derived, but registered to keep it out of the merge cone |
| `tag` | 21 | |
| `way` | 2 | **reserved at allocate**, not chosen at fill (see below) |
| `cmode` | 2 | latched cacheability, as today's `missCmode` (`DcachePlugin.scala:118`) |
| `arSent` | 1 | |
| `beatsRcvd` | 1–2 | 0 at 16 B lines, 2 bits at 64 B |
| `err` | 1 | sticky non-OKAY, as today's `missFault` (`:134`) |
| `poison` | 1 | set by blanket flush poison (§4.3) |
| `fillData` | 128 | one beat, for fill-forward to merged waiters; at 64 B lines this is a *rotating single-beat* latch, not a whole line (see below) |
| `evictPending` | 1 | copyback composition (§5.7): this MSHR owes a victim writeback before its AR |

**MSHR allocate / merge / stall decision** — a 3-way classification of an incoming miss against
the array, all comparisons on registered fields:

```
sameLine(k) = mshr(k).valid && mshr(k).lineAddr === reqLineAddr
sameSet(k)  = mshr(k).valid && mshr(k).set      === reqSet

if      any sameLine  -> MERGE  (attach a waiter to that MSHR; no new AR)
else if any sameSet   -> STALL  (hold loadCmdPort.ready low; retry next cycle)
else if array full    -> STALL
else                  -> ALLOCATE
```

**D3 — at most one outstanding fill per set — is the load-bearing invariant of this design.**
It costs `N_MSHR × 7` bits of comparison and, at 128 sets (or 32 at 64 B lines) with N ≤ 4,
essentially never stalls anything real. In exchange it eliminates, structurally:

1. **Two misses to the same set choosing the same victim way.** With one fill per set, way
   reservation is trivially conflict-free: sample `victim(set)` **and increment it at allocate**
   (rather than at fill, as today at `DcachePlugin.scala:403`), so the second miss to that set —
   which cannot exist — could not collide anyway.
2. **The copyback design's §4.3 drain-vs-refill same-set window** — the 2-cycle race it has to
   patch by hand (store drain's S1 tag read vs. a concurrent refill's array write into the same
   set, giving a stale-tag hit-detect in S2). Under D3, a drain to a set with an active MSHR
   simply holds. **This is a proposed amendment to that design: replace its hand-rolled
   "hold the refill's array write for ≤ 2 cycles" with D3's set-exclusion.** Note that design
   also flags this as a *pre-existing bug in today's code* (its §5, item 11) — D3 fixes it as a
   side effect, which strengthens the case for landing D3 early.
3. **A read-during-write tag hazard that hit-under-miss would otherwise *create*.** The Mems are
   `readSync` with write-first semantics, so a lookup launched in the same cycle a fill writes
   `tagMem`/`dataMem` at that set gets the **pre-write** tag and data, while `valids` (a flop,
   `:80`) reads **True** the next cycle. That combination can produce a hit on stale data
   whenever an *invalid* way holds a tag equal to the requested tag. Today that is unreachable
   (no load is accepted during REFILL, and `valids` never goes False because nothing drives
   `invalidateAll`), but it becomes reachable **both** from hit-under-miss **and**, independently,
   from copyback P5's real CINV. Closure: extend the exclusion to lookups —
   `loadCmdPort.ready` also requires `!(fillWritesThisCycle && fillSet === cmdSet)`, a 1-cycle
   bubble on a rare collision. **Worth flagging to the copyback design as a latent hazard CINV
   will expose on its own.**

**Per-ID R-beat routing.** `axi.r.ready` becomes a pool-level ready (unconditional while any
MSHR is outstanding), and each beat is routed by `mshrIdx = axi.r.payload.id`:

- write the beat to `dataMem(mshr(k).way)` at `mshr(k).set` (per-beat, exactly the I-cache
  pattern at `IcachePlugin.scala:354-359`),
- latch it into `mshr(k).fillData` for fill-forward,
- `mshr(k).err ||= (resp =/= OKAY)`, `beatsRcvd++`,
- on `r.last`: allocate tag + valid **iff** `!err && cacheable` (preserving today's
  `doAllocate` rule at `:392-393` — including its no-allocate-on-error property, which §4.1 M2
  depends on), then wake waiters, then deallocate.

Because each MSHR owns a distinct set and a reserved way, and writes go to a *different physical
port* than lookups (§1.5), **fill beats and load lookups never contend**. The only write-port
contention is fill vs. store-RMW, already resolved refill-priority (`:461-465`) — and now with
up to `N_MSHR` fills, so the arbiter needs a real priority scheme plus a sim assert that no two
fills write the same way in one cycle (impossible under D3, worth asserting anyway).

**At 64-byte lines**, per-MSHR `fillData` should *not* become a 512-bit line latch (that is
`N × 512` flops and re-creates the I-side's `lineReg` problem). Instead: write every beat
straight into `dataMem` as it arrives, and keep only **one beat** latched per MSHR for
fill-forward of the *critical* word. A waiter whose bytes are in a non-critical beat is woken
after that beat lands and reads its bytes from the latched beat if it is the current one, or —
simplest and recommended — is simply woken at `r.last` and served from the now-allocated line
via a normal re-lookup. That keeps per-MSHR storage at 128 bits regardless of line size.

### 5.4 Making the LS EU multi-outstanding: the Load Tracking Table

The LS EU today is a blocking FSM with one context and one writeback register set (§1.5). The
proposed restructuring:

- **Split it into a pipelined request path and a decoupled completion path.** The request path
  (S0 latch → S1 AGU → XLATE/DTLB → SQ-forward query → cache launch) becomes a
  1-uop-per-cycle pipeline; `issuePort.ready` (`LsEuPlugin.scala:1013`) stops depending on
  `busy` and depends only on request-path structural availability plus LTT space.
- **Loads that HIT complete exactly as today**, through the existing `comp*` stage. No LTT entry
  is consumed. This is what makes the first slice small.
- **Loads that MISS park in a Load Tracking Table (LTT) entry** and are woken by their MSHR.

**LTT entry** ≈ the resolved-data-free half of today's `comp*` set (`LsEuPlugin.scala:652-717`):
`{valid, poison, waiting, mshrIdx, robId[5:0], pdst, pdstValid, size, signExt/isMovea,
lineOff, vaddr[31:0], sup, cmode, twoAccess/slotB state, ccrRestore, dstArch, wakes, nzvc
fields…}` — order 100 bits per entry. `N_LTT` should be ≥ `N_MSHR` and is the merge capacity
(several waiters can share one `mshrIdx`). At `N_LTT = 4` that is ~400 flops; at 2, ~200.
**Because `comp*` is a 26-field bundle, replicating it is the single largest area item on the D
side** — which is another reason to start at `N_LTT = 1` (§9 slice D1) and grow on measurement.

**Writeback arbitration.** `comp*` is a single shared writeback stage, and it is *already*
2-way arbitrated: the precise-store deferred replay FIFO contends for it with strict live-pipe
priority (`LsEuPlugin.scala:1454-1487`, `liveCompletionFires` at `:725`, `:1073`). This design
turns that into a 3-source arbiter — {live hit path, LTT fill-wakeup, precise-store replay} —
with the same retry-next-cycle discipline. Two consequences to design in:

- **One wakeup per cycle.** A merged group of `k` waiters completes over `k` cycles. Fine.
- **Do not weaken the live-priority rule**, and keep the replay FIFO's in-order
  `pendReady`/`pendApply` discipline untouched (`:1395-1406`) — it needs no tagging because it
  is 1:1 with SQ pops, and this design must not make it need any.

**Fill-forward / critical-word-first.** Today the allocating path delivers by **re-reading the
line it just wrote** (§1.5). Replace that with extraction from `mshr(k).fillData`: it saves the
2 REPLAY/re-read cycles, avoids consuming the shared read port on every miss, and is *required*
for D4 to not lengthen miss latency (§5.2). The non-allocating INHIBITED path already does
exactly this (`missLine`/`inhibitedResp`, `:228-231`), so the mechanism exists and only needs
generalising per-MSHR.

**Two one-shot response pulses must be tagged.** `busFaultResp` (`:216`, `:419`) and
`inhibitedResp` (`:224`, `:424`) bypass the normal hit path entirely and carry no identity, and
`DLoadRsp` (`DcacheTypes.scala:24-28`) has **no tag field at all**. With N outstanding accesses,
`DLoadRsp` must gain a tag (the LTT index) and both pulses must carry it. This is small but it
is the first thing that breaks, so it belongs in the earliest RTL slice.

**Per-entry poison** replaces the scalar `poisoned` (§4.3): blanket-set on `sqFlushSig`, cleared
per entry at allocate. Add the `R1` comment coupling it to retire-time-only flush.

**The SQ forward query does not need N ports.** The query is combinational and keyed
`{robId, paddr, size}` (`LsEuPlugin.scala:566-568`); since the request path issues at most one
load per cycle, **one query port serves the pipeline**, answered once per load at its own launch
stage. No change to `StoreQueue.scala:225-298`.

**Preserved correctness properties**, called out because they are easy to lose:

- A **store must be visible to the SQ-forward query of every younger load**. Today the
  single-outstanding pipe guarantees a store has allocated into the SQ before any younger load
  enters. With a pipelined request path that is no longer automatic. **Rule: a store occupies
  the request path exclusively until it has SQ-allocated** (which is fast — the XLATE arm at
  `:1187-1204`), i.e. only *loads* are multi-outstanding in the request path. Stores are ~15–20%
  of the stream and allocation is 2–3 cycles, so the cost is small and the rule is trivially
  correct. This is a **hard requirement**, not a tuning choice.
- The `sameLine` and partial-overlap stalls (`StoreQueue.scala:250-261,296-298`) still gate load
  *launch*, unchanged.

### 5.5 INHIBITED (MMIO) accesses are strictly serialised

**Rule:** an INHIBITED load or store is launched only when no other D-side transaction is
outstanding (`dQuiesce`, §7.3), and no further D-side transaction launches until it resolves.

Justification, all from legitimate evidence classes:

- Device accesses must not reorder or duplicate (§4.2). Merging must be impossible for them
  (§4.1 M3).
- The decision rests on **software-configured cacheability** (class (a)) — `s2Cmode`, already
  latched at `LsEuPlugin.scala:1136` and carried to `loadCmd.cacheMode` at `:519`. No address-map
  knowledge.
- It closes an issue that MSHRs would otherwise *widen*: today loads issue speculatively (the
  oldest LS uop may still be on a wrong path from an unresolved older branch), so speculative
  MMIO reads with side effects are already possible; N outstanding loads would multiply them.
  Serialising INHIBITED accesses is the cheap containment. (Making them fully non-speculative —
  at-ROB-head, mirroring the precise-store path — is the stronger fix and is listed as §11.Q4
  rather than assumed here.)
- It also covers §4.3 R2 (a preempted head re-executing an MMIO load).

MMIO is a cold path by construction, so the cost is irrelevant. The exact-size-AR gap stays
deferred (N7 / copyback §5.5).

### 5.6 Store side: what overlap is legal

The precise path's at-head serialisation is **architecturally mandatory** and this design does
not touch it: precise stores drain at ROB head, one at a time, and retire on their own real B
response (`StoreQueue.scala:200-204,342-390,392-402`). No concurrency may be added there —
that would be exactly the §4.1(iii) violation.

What *is* legal:

1. **Fast-path (software-declared-cacheable) write-through beats may be multi-outstanding**,
   all sharing **one AW ID** so AXI4's same-ID ordering rule keeps them in program order (D2).
   The gate is `drainBusy` (`StoreQueue.scala:189`), which generalises from a boolean to a small
   in-flight count — **this is the same change the copyback design's P6 "pipelined hit-drain"
   already specifies (its §4.3), and the two should be one slice.** Under copyback most
   hit-drains terminate locally with no bus beat at all, so the remaining outstanding-write
   depth is needed mainly for WT pages and evictions.
2. **Store drain concurrent with load fills** — subject to D3 (drain to a set with an active
   MSHR holds) and the unchanged `sameLine` stall.
3. **Eviction writebacks concurrent with other writes** (same AW ID ⇒ ordered), but a given
   MSHR's own eviction must complete before that MSHR's AR (§4.2, read-vs-write is unordered in
   AXI).

Two contracts that must not break:

- **One-ack-per-store.** `storeAck`/`storeErr` are untagged single-cycle pulses
  (`DcachePlugin.scala:530-531`) consumed by both the SQ's `drainBusy` and the ExceptionUnit's
  per-word `E_STWAIT` (`ExceptionUnit.scala:755`). The copyback design already flags this as its
  open question 7 (three ack sources). With outstanding writes there are N in flight, so
  **`storeAck` must gain a tag** (or the SQ must count acks and the exception path must keep its
  own strictly-one-at-a-time discipline). Recommend: tag the ack, and keep the exception FSM on
  a serialised path (it is not throughput-critical).
- The exception FSM drives `dcache.store` directly via `driveStoreNoXlate`, arbitrated at
  `LsEuPlugin.scala:1196-1199`. §7.3 gates exception entry on `dQuiesce`, which makes this
  trivially safe.

### 5.7 Composition with the copyback design (P4–P6)

| Copyback element | Interaction | Proposed resolution |
|---|---|---|
| Dirty bits, per line (`DcachePlugin.scala:78` neighbourhood) | Granularity = line size | **D4 must be decided first** (§11.Q2) |
| Eviction writeback FSM (its §4.3 `EVICT_RD`/`EVICT_WR` ahead of REFILL) | Must become **per-MSHR**, not a single FSM | `evictPending` field (§5.3); the MSHR sequences EVICT → B → AR. Under D3 the victim's set is exclusively owned, so the victim read/write cannot race another fill. |
| Drain-vs-refill same-set window (its §4.3, also flagged as a bug in today's code) | Same hazard | **Amendment: replace the hand-rolled hold with D3's set exclusion.** |
| Post-commit write-allocate on a copyback drain miss (its §4.3) | Needs a refill from the store side | An MSHR allocated by the *store* path, with `way` reserved the same way. D3 applies unchanged. |
| Pipelined hit-drain (its P6) | Same lever as §5.6(1) | **Merge into one slice.** |
| CPUSH/CINV flush engine (its P5) | A third requester for the shared read port; plus the read-during-write hazard (§5.3 item 3) it exposes independently | Gate maintenance on `dQuiesce` (§7.3); adopt §5.3's lookup exclusion. |
| Async diagnostic-fault channel + core halt (its §4.2) | Fill and eviction errors on trusted data feed it | Per-MSHR `err` feeds the same sticky record. **A poisoned (wrong-path) fill's error must NOT raise it** — a wrong-path access is not a real architectural access. Add this explicitly; it is easy to get wrong. |
| Precise-store at-head drain + `preciseDrainBusy` (landed) | Untouched | §5.6; no concurrency added to the precise path |
| CACR.DE = 0 ⇒ fully uncached (its P5 decision) | Every access becomes INHIBITED ⇒ §5.5 serialises everything | Correct but slow; matches that design's stated cold-path acceptance. Worth noting the harness CACR reset-poke (its §5.2) becomes *more* important once §5.5 exists. |

---

## 6. I-side design

### 6.1 Demand miss-under-miss is nearly worthless here — and that is a useful finding

The tempting design is "N I-cache MSHRs so consecutive misses overlap". It does not pay,
for a specific structural reason:

- The line is 64 bytes; a fetch is 8 bytes (`IcacheTypes.scala:68`) and `fetchPc` advances
  `+8` (`FetchAlignPlugin.scala:249`). So **8 consecutive demand fetches hit the same line**.
- The outstanding-fetch ring is **depth 3** (`FetchAlignPlugin.scala:171`).

Therefore the demand stream can never have two *different-line* fetches outstanding: reaching
the next line takes 8 fetches and only 3 can be in flight. **Demand miss-under-miss is
structurally unreachable on the I side.** Raising the ring depth to 9+ to create the opportunity
would need a bigger InstructionBuffer (today `BUF_WORDS = 20`, `HEAD_WORDS = 10`,
`InstructionBuffer.scala:43-45`) and a reworked `ibufRoomForIssue` reservation
(`FetchAlignPlugin.scala:230-231`) — a lot of flops to enable an overlap that a prefetcher gets
for free.

What *is* worth having on the demand side:

- **Hit-under-miss / non-blocking accept.** `cmdPort.ready` is gated to `IDLE`
  (`IcachePlugin.scala:261`), so a miss stalls the entire fetch port for 7 + L cycles (§1.5),
  even for fetches that would hit. Letting the cache keep accepting (and answering hits) during
  a refill is a real win *within* the ring's depth.
- **Same-line duplicate-miss suppression.** With a non-blocking accept, several of the 8
  same-line fetches will miss on the same line concurrently. They must **merge** onto the one
  outstanding MSHR, never allocate a second (and never issue a second AR). This is the I-side's
  version of §4.1's merge rules, and it is mandatory the moment accept is non-blocking.

### 6.2 Next-line prefetch is where the I-side value is

The fetch stream is **provably sequential between redirects** — the BTB/RAS/gshare sit
*downstream* of the I-cache and cannot steer `fetchPc` at all (§1.5). There is no better
possible setting for a next-line prefetcher: no stride detection is needed, no confidence
estimation, and there is no fetch-stage predictor to fight with.

**Design:** `N_MSHR_I = 2` — one **demand** MSHR and one **prefetch** MSHR.

- **Trigger:** on a demand miss that allocates line `L`, and/or on the first demand *hit* in a
  line that was itself prefetched (a cheap "useful prefetch ⇒ keep going" heuristic), issue a
  prefetch for line `L + 64`.
- **Constraints** — the §4.1(ii) rules P1–P4 are binding: resident ITLB translation only, never
  trigger a walk, never for an INHIBITED page, never cross a page on a guess, errors silently
  discarded with no allocation and no diagnostic record.
- **Suppression:** never prefetch a line already resident, already in an MSHR, or in the same
  set as an active MSHR (the I side gets the same D3 set-exclusion, for the same reasons — 64
  sets, `IcachePlugin.scala` geometry).
- **The prefetch MSHR has no waiting consumer.** This is the key simplification: a prefetch
  never produces a `FetchRsp`, never occupies a ring entry, never needs a tag, and cannot
  produce a fault. It is a pure allocate. So **FetchAlign needs no change whatsoever** and the
  `ibufRoomForIssue` reservation (`:230-231`) is untouched.
- **Demotion:** if a demand fetch misses on a line that a prefetch MSHR is already filling,
  *attach the demand waiter to that MSHR* (merge) rather than allocating — this is the case
  where the prefetch pays off partially.
- **Prefetch is NOT blocked by the SoC crossbar (§3.2) — and this is important.** Prefetch hides
  latency *in time*, not by concurrency: its benefit comes from starting a line's fill early
  relative to when the program *demands* it, not from overlapping it with another transaction.
  Since 8 consecutive fetches (≥ 24 cycles at the 3-cycle hit rate) elapse before the next line
  is demanded, a prefetch issued as soon as the bus is free — even on a strictly
  one-outstanding-per-master-port fabric — still lands well ahead of need. The crossbar only
  costs the prefetch its *overlap* with the demand fill that triggered it, which is a fraction of
  the win. **So slice I3 is one of only two slices with real end-to-end value on today's SoC**
  (the other being D1), which is a strong argument for prioritising the I-side chain.
- **Wrong-path prefetch is harmless** and needs no abort: exactly today's wrong-path-refill
  behaviour (§1.5). It does consume bus bandwidth and can evict a useful line (it bumps
  `victim`) — a real but small cost, worth measuring (§8.3) with a "prefetch off" control.

### 6.3 Response delivery stays strictly in order — deliberately

`FetchRsp` has no tag and FetchAlign attributes responses to the ring **head**
(`FetchAlignPlugin.scala:263,283`). Rather than adding a tag and reworking the ring, keep
**in-order response delivery** from the I-cache. This costs nothing real, because:

- the instruction stream is consumed in order anyway — a younger fetch's data is useless before
  an older fetch's data;
- with the prefetch MSHR carrying no consumer, the only responses are demand responses, and
  demand fetches are issued in order;
- in-order delivery is a legal subset of what per-ID R routing supports, so the L2's by-design
  cross-ID reordering (§3.1) is handled either way.

So the I-side ordering requirement is: **route R beats by `r.id` (so a prefetch's beats and a
demand's beats cannot be confused — the bug §1.2 warns about), but deliver `FetchRsp`s in
demand order.** A small completion/reorder point at the cache output, or simply "only the demand
MSHR ever produces responses, and there is only one", suffices at `N_MSHR_I = 2`.

### 6.4 The 32× predecode block: the real I-side cost, and how MSHRs can *reduce* it

The obstacle to I-side MSHRs is not the MSHR array; it is that **PREDECODE is a single-instance,
single-cycle, 32-way-unrolled ~860-EA-decoder combinational block reading one 512-bit `lineReg`**
(§1.5). Replicating it per MSHR is prohibitive. Three options, in increasing ambition:

**Option I-a — time-share it (minimum viable).** Keep the block as-is; arbitrate it between
MSHRs with demand priority. Since a fill's predecode is one cycle and fills are rare, contention
is negligible. But `lineReg` must be replicated per MSHR (**+512 flops each**), which is exactly
the wrong direction on a branch already under investigation for ~3× LUT bloat. *Not
recommended*, but it is the fallback if the others fail a synth gate.

**Option I-b — per-beat predecode (recommended, safe).** Predecode **16 words per beat** instead
of 32 per line, as the beats arrive:

- **Halves the classify instances (32 → 16)** — a direct cut in the largest suspected LUT
  driver.
- **Deletes `lineReg` entirely** (512 flops) — only one 256-bit beat needs holding, and it can
  be per-MSHR at 256 bits instead of 512.
- **Removes the separate PREDECODE cycle** (predecode happens in the beat cycle), cutting
  miss latency by 1 of its 4 fixed post-data cycles.
- **The lookahead problem is solvable with no new correctness surface.** Word `i` needs words
  `i+1..i+3` (`IcachePlugin.scala:424-431`). Word 15 of beat 0 needs words 16–18, in beat 1.
  Fix: **lag by one beat** — predecode beat 0's words when beat 1 arrives (beat 1 supplies the
  lookahead), and predecode beat 1's words with the *existing* line-end semantics
  (`extWValid = false` ⇒ `ambiguousLine`, re-classified live in the Aligner at
  `Aligner.scala:63-65`). Only the final 3 words of the line hit the boundary case, which is
  **exactly the case that already exists today**. Zero new ambiguity, zero new bug surface.
- A 4× cut (8 words/cycle, 4 predecode cycles) is possible but adds 3 cycles to every demand
  miss, which is the wrong trade *unless* prefetch is already hiding the latency. Note the
  synergy: **prefetch is what makes a slower, narrower, cheaper predecode affordable.** Worth
  keeping as a knob, not the default.

**Option I-c — move predecode off the refill path entirely, to align time (biggest win, real
FMax risk).** Delete `predMem`, `PREDECODE`, `lineReg`, `s1PredEntries`, `FetchRsp.pred`, the
ibuf's `headPred`, and the 32 instances; classify **at the Aligner** instead. Evidence this is
more plausible than it sounds:

- **The Aligner already does it.** `Aligner.scala:63-65` runs a live
  `PredecodeWord.classify(words(0..3), extWValid = avail >= 2, …)` on the front-end critical
  path today (task #202's `p0Live`) and passed a synth gate.
- **The InstructionBuffer already provides the window**: `head`/`headPred` are
  `HEAD_WORDS = 10` words wide (`InstructionBuffer.scala:43,49-51`) and are **PC-relative, not
  line-relative**, so they "transparently span physical I-cache lines" (the comment at
  `Aligner.scala:44-52` says so explicitly). Full 3-word lookahead is available from the buffer.
- **It deletes an entire bug class.** `ambiguousLine` exists *only* because predecode is baked
  per-line at refill with no cross-line visibility (`IcachePlugin.scala:396-422`,
  `IcacheTypes.scala:21-35`). Tasks #202, #204 and #209 were all line-boundary predecode
  ambiguity bugs. At align time there is no line boundary at all, so the concept disappears.
- **Area:** 32 instances (+1 live) → 2 (slot0 and slot1), plus `predMem` (4 ways × 64 sets ×
  192 bits of distributed RAM — part of the reported 2788 LUTs of distributed RAM),
  `s1PredEntries` (768 flops), `rspPredReg`, and 24 bits of `FetchRsp` all disappear. This is
  plausibly the largest single area reduction available anywhere in the front end.
- **And it makes I-side MSHRs almost free** — no shared predecode block to arbitrate, no
  `lineReg`, so an MSHR becomes {line addr, set, tag, way, beat count, err, poison}, a handful
  of flops.

**Honest risks of I-c:** (1) slot1's classify inputs sit at a *dynamic* offset (`words(L0)`),
needing a barrel mux over the 10-word head window — cheap in LUTs but on the FMax-critical
decode path; (2) it moves a decode tree from a rare refill cycle onto the *every-cycle* align
path, which is the opposite of why predecode-at-refill was chosen; (3) it is a large, invasive
front-end change that touches Aligner, InstructionBuffer, FetchAlign, IcachePlugin and
IcacheTypes at once, and would need its own design pass.

**Recommendation:** adopt **I-b** as the plan of record (safe, 2× cut, ‑1 cycle, no new bug
surface), and raise **I-c** as a separate, independently-justified proposal — it is motivated by
the LUT-bloat investigation and the #202/#204/#209 bug class on its own merits, entirely apart
from MSHRs. Do not couple the MSHR work's fate to it.

### 6.5 The `invalidateAll` hazard must be closed before it becomes reachable

§1.5 confirms `IcachePlugin.scala:437` (`valids(victimWay)(missSet) := True`) overrides `:81`
(`valids := False`) under last-assignment-wins. With N MSHRs the window widens from one
line/one cycle to N lines over N cycles, and copyback P5 (real CINV/CPUSH) makes
`invalidateAll` actually driven. Required closure, in whichever slice lands first:

- make the invalidate **priority-correct** (invalidate wins over a same-cycle fill valid-write,
  or the fill is suppressed and its MSHR poisoned),
- **poison all outstanding MSHRs** on invalidate so in-flight fills do not re-allocate,
- invalidate the `s1*`/`rsp*Reg` stages, whose in-flight response is from a now-invalidated line,
- and add a directed test, since the whole hazard is currently unreachable and therefore untested.

---

## 7. Ordering, IDs, exceptions, maintenance

### 7.1 AXI ID allocation (D2)

Design rules:

- **Distinct ID per outstanding read.** This is **mandatory, not an optimisation**: the L2 blocks
  a second transaction with a live ID at its front door via the `id_busy_c` CAM
  (`l2c_ctrl.v:140-142,151`, `l2c_mshr.v:100-116`), so a constant-ID master gets zero benefit
  from the L2's 8 MSHRs — which is exactly the position today's Verilog CPU is in
  (`m68k-ooo/rtl/core/m68k_axi_wrapper.v:1021-1068` hardwires `ID_TAG 4'd0` for data and `4'd1`
  for fetch). Per-ID routing is also correct under in-order arrival (a legal subset) and even
  under the illegal cross-ID beat interleaving flagged as §3.4 U2, since every beat is routed by
  `r.id` into its own MSHR's beat counter.
- **One shared ID for all D-side writes.** AXI4 requires same-ID transactions to complete in
  order, so program-order write visibility comes for free, with no core-side reorder logic. The
  cost — an eviction queued behind a slow store — is acceptable. (Note the L2 serialises same-ID
  anyway, so this costs nothing extra there.)
- **Never rely on cross-channel ordering.** AXI gives no read-vs-write ordering; the core
  self-orders (§4.2).
- **IDs must be globally unique per physical master**, fixing today's collision (§1.1: both
  walkers use AR 2 / AW 3).
- **The CPU-visible ID width is 4 bits.** The socket contract fixes `AXI_IW = 4`
  (`macqd700-soc/rtl/soc/cpu_socket.vh:78-87`) and the crossbar prepends a 2-bit slot tag to
  reach the L2's `ID_WIDTH = 6` (§3.1). So 16 IDs are available per master and the D side's
  existing `idWidth = 4` is already right; the I side's `idWidth = 2` is not.

Proposed maps:

**D master** (`idWidth = 4`, 16 IDs — ample, and socket-conformant):

| ID | Requester |
|---|---|
| `0 .. N_MSHR-1` | D-cache load-refill MSHRs (recommend N = 2, room to 4) |
| `4` | DTLB table walker read |
| `8` | **All** D-side writes: write-through beats, eviction writebacks, CPUSH writebacks |
| `9` | DTLB U/M descriptor write |
| `12` | reserved for a future store-side write-allocate refill, if it wants its own read ID |

**I master** (`idWidth = 2` today — only 4 IDs; **widen to 4 bits** for uniformity, headroom, and
socket conformance):

| ID | Requester |
|---|---|
| `0` | I-cache demand MSHR |
| `1` | I-cache prefetch MSHR |
| `4` | ITLB table walker read (when folded onto this master) |
| `5` | ITLB U-bit descriptor write (when folded) |

Widening `idWidth` is a pure-Verilog-port change with no logic cost and it de-risks the eventual
walker fold (§1.1). Recommend doing it in the ID-hygiene slice.

**A separate socket incompatibility worth recording here, since it lives on the same seam:** the
I-cache master is **256-bit** (`IcachePlugin.scala:31`), and the socket/fabric is **128-bit**
(`cpu_socket.vh:78-87`, `l2c.v:37-39`). Today's Verilog CPU bridges its narrow master upward with
`axi_narrow_to_wide`; this core would need the *opposite* — a 256→128 downsizer, or a change of
the I-cache's AXI width to 128 with `len = 3` instead of `len = 1` (which, note, is the same
4-beat shape D4 gives the D side, and would make both masters uniform). **Recommendation: change
the I-cache AXI to 128-bit / 4 beats rather than adding a downsizer**, and fold it into whichever
I-side slice touches the refill FSM. This is out of scope as a *goal* (N4) but it is cheap to do
while the FSM is open, and doing it later means reworking the same code twice.

**Mandatory assertions** (sim, and ideally synthesisable-but-unused formal-ish checks):
a response ID with no allocated MSHR is a fatal error; two MSHRs may never hold the same ID; per
ID, beats must arrive in order and `r.last` must arrive exactly once; `b.id` must match an
outstanding write.

### 7.2 Interrupts and precise exceptions

- **Poison, not abort** (§4.3). Outstanding fills complete on the bus and allocate; their
  waiters' completions are suppressed. Correct because flush is retire-time only.
- **A poisoned fill's `err` must not raise the copyback design's diagnostic fault / core halt**
  (§5.7). A wrong-path access is not an architectural access, and halting the core on one would
  be a spectacular false positive.
- **`preciseDrainBusyIn`** (`StoreQueue.scala:392-402` → `RobPlugin.scala:1069-1071`, `:1138-1140`)
  is unchanged: outstanding *loads* neither set nor need it.
- **Interrupt preemption of a head with an outstanding load** is safe (load re-executes after
  RTE) except for MMIO reads — covered by §5.5, and §11.Q4 offers the stronger fix.
- **Exception entry is gated on `dQuiesce`** (§7.3) so the ExceptionUnit's direct frame stores
  (`driveStoreNoXlate`, arbitrated at `LsEuPlugin.scala:1196-1199`, per-word `E_STWAIT` at
  `ExceptionUnit.scala:755`) keep their one-store-at-a-time contract with no new reasoning.

### 7.3 `dQuiesce` — the one serialisation primitive

`dQuiesce := no valid MSHR && no outstanding write && !drainBusy`. Bounded: every outstanding
transaction either completes or errors, and errors already resolve (no allocation, response
delivered). Consumers: exception entry, INHIBITED access launch (§5.5), cache maintenance
(§7.4), and RTE. An `iQuiesce` equivalent exists for the I side (maintenance only).

This deliberately avoids inventing an architectural fence: all four consumers are existing
serialisation points.

### 7.4 Cache maintenance in the L2 era

Two separate problems, and only the first is the CPU's to solve:

**(1) L1 maintenance.** Copyback P5's job. This design's contributions: gate it on
`dQuiesce`/`iQuiesce`; close the `invalidateAll` hazard (§6.5); and note the read-during-write
tag hazard (§5.3 item 3) that CINV exposes independently of MSHRs. The D-cache needs an
invalidate port it does not have (`DcacheTypes.scala:49-60`); flush-all is nearly free because
`valids` is a flop array (`DcachePlugin.scala:80`), whereas line-granular CINVL needs a tag read
and therefore a third requester on the single shared read port (`:99-104`, arbiter at
`:293-307,451-459`).

**(2) Reaching the L2 — already resolved by the SoC's design, and no sideband is needed.** This
was written as an open problem before the L2 was read; the L2's own contract answers it directly,
so the answer is recorded here rather than left as a question.

The L2 has **no invalidate, flush, snoop or CSR interface whatsoever** (`l2c.v:46-85`,
`docs/l2c_spec.md:495`), and that is **deliberate**. `docs/l2c_spec.md:30-43`, "Why CPUSH/CINV
never reach L2": CPUSH/CINV are 68040 **L1-local** operations; an L1 dirty writeback arrives at
the L2 as "an ordinary AXI write burst… indistinguishable at the xbar from any other store" and
merges into the L2's own write-back/write-allocate policy. The L2 is the declared **single point
of coherency** for everything DDR-backed, and invariant 2 of its spec is "**no path around L2 for
cacheable windows**" (`docs/l2c_spec.md:8-28,111-116`) — enforced structurally by address decode
(e.g. VRAM traffic is routed to a real S3 slave and "never reaches xbar S0, and therefore never
reaches l2c's slave port, at all", `:45-63`).

So the correct core-side behaviour is exactly what the copyback design's P5 already plans:

- **`CPUSH`** writes dirty L1 lines out as ordinary AXI write bursts. They land in the L2. Nothing
  further is required, and nothing further is *possible*.
- **`CINV`** discards L1 lines. Because nothing bypasses the L2 for cacheable windows, a
  subsequent load re-reads through the L2 and sees the point of coherency. Correct.
- **No maintenance sideband, no AXI-Lite control master, no L2 register address in the core.**
  This is fortunate: option (b) from the earlier draft would have put an L2 register address
  inside the CPU, which is precisely the class of assumption §4.1 bans.

Two residual caveats that belong in the record rather than in the design:

- **DMA/peripheral coherency is a SoC-level property, not a CPU one.** If a DMA engine could
  reach DRAM without going through the L2 for a cacheable window, the L2's invariant 2 would be
  violated and no CPU-side instruction could fix it. The SoC asserts that invariant; the CPU
  relies on nothing beyond it.
- **The only way to clear the L2 is a full reset**, which takes ~4096 cycles with
  `s_axi_*ready` held low and **drops dirty lines** (`docs/l2c_spec.md:117-122,407-418`). A core
  that issues a bus transaction into a resetting L2 simply back-pressures. Worth knowing for
  boot/bring-up sequencing, not something the core must handle specially.

**Net effect: §11.Q5 is downgraded from a blocking design question to a confirmation request.**

---

## 8. Verification plan

### 8.1 Slice V1 — the memory-model successor (mandatory, first, no RTL)

A new AXI slave model replacing/absorbing today's three implementations (§1.6). Requirements:

1. **Per-ID out-of-order responses.** Replace the single global `rQueue`
   (`BehavioralMem.scala:97`, and the clone at `:275`) with per-ID queues mirroring the existing
   `bQueue` structure (`:155-156`), plus a chooser like `bDriver`'s (`:225`). **Per-ID beat order
   must be preserved; cross-ID interleaving must be free.** Note `resp`/`last` are frozen at
   AR-accept today (`:116-117`) — that stays fine per-ID.
2. **Modes, all of which must pass:** `InOrder` (today's behaviour — proves no regression),
   `Reordered` (random cross-ID, which is what the real L2 does by design, §3.1), and **`Chaos`**
   (maximally adversarial: always deliver the *newest* eligible ID first, so any in-order
   assumption fails immediately). A fourth mode, **`IllegalInterleave`**, should reproduce §3.4's
   U2 (cross-ID beat interleaving inside a burst) — the design is supposed to tolerate it, and
   this is the only place that claim can be tested.
3. **An L2-faithful two-tier latency model**, parameterised from the measured contract (§3.1) so
   the numbers are not invented: 64-byte line, **hit ≈ 5 cycles**, miss = DRAM + full 4-beat fill
   + ~4 fixed cycles with **no critical-word-first** (the requester waits for the whole line),
   8 internal MSHRs with 4 same-line merges each, allocation back-pressure rather than drop, and
   a **same-ID front-door block** mirroring `id_busy_c` — that last one is essential, because it
   is what punishes a constant-ID design and it is invisible in any simpler model. DRAM latency
   is **unmeasured (§3.4 U1)** and must be a swept parameter, not a constant. Needs a cycle
   counter, which the file does not currently have at all.
4. **A crossbar model** (§3.2): a mode that enforces **one outstanding read and one outstanding
   write per master port**, i.e. today's SoC. This is what makes the "multi-outstanding buys
   nothing end-to-end yet" claim measurable rather than asserted, and it is the configuration
   against which slice D1's benefit must be demonstrated to be real.
5. **Bandwidth/burst limits**: maximum accepted `len`, INCR-only enforcement (the L2 SLVERRs
   FIXED/WRAP), minimum inter-beat gap, maximum outstanding — with asserts when the DUT exceeds
   them.
5. **A protocol checker**, always on: response ID must be outstanding; per-ID beat order and
   exactly-one-`r.last`; `b.id` outstanding; burst length matches AR; no beat delivered to an ID
   with no outstanding transaction; write-strobe sanity. **This checker is the artefact that
   makes every later slice trustworthy.**
6. **Preserve the write-before-B invariant** (`:127-148`) — any deferred-apply latency model must
   still apply bytes before driving B, or it re-introduces the harness-induced store→load race
   that comment exists to prevent.
7. **Consolidate the duplication** while touching it: `BehavioralMemAgent` +
   `Axi4ReadOnlyBehavioralAgent` (an acknowledged copy, `:250-259`) share one read implementation;
   the four `attachProgram` clones (`ExecuteLockStepSpec.scala:379-395`, `FuzzDut.scala:283-294`
   — already flagged at `:23-30`, `IpcBenchSpec.scala:289-300`, `IcacheSim.scala:20-56`) plus the
   three inline agents in `ExecuteLockStepSpec.scala` (`:5348,:5455,:5581`) collapse to one
   helper. Mind the byte-swap convention difference documented at `PortedTestRunner.scala:109-115`.

**Acceptance for V1: the entire existing suite passes, unchanged, against the new model in
`InOrder` mode with zero added latency; and passes in `Reordered`/`Chaos` too (which it must,
since today's RTL is single-outstanding — if it does *not*, V1 has found a real bug in today's
RTL, which is itself a valuable outcome).**

### 8.2 Directed tests, per mechanism

D side: hit-under-miss (load hits while a miss is outstanding, completion order checked);
merge (two loads same line ⇒ **one** AR — assert AR count); merge with error (fill errors ⇒
oldest waiter faults precisely, younger flushed, **and a subsequent access re-issues a real AR**,
which is §4.1 M2's test); same-set-different-line ⇒ stall, no double allocate, no victim
collision; drain-vs-fill same set (the copyback §4.3 / today's-code bug — a directed repro is
worth having independent of this design); read-during-write tag collision (§5.3 item 3);
flush-poison with a straggling fill (fill lands after the flush ⇒ no completion, no PRF write,
no ROB completion — this is the rename-exposure class from tasks #176/#194/#200, so it wants a
whitebox check on the freelist too); MMIO serialisation (assert no other D transaction
outstanding across an INHIBITED access, and exactly one bus access per MMIO access); IRQ storm
across an outstanding load; MSHR-exhaustion backpressure; `N_MSHR = 1` regression run.

I side: miss with non-blocking accept (a hit is served during a refill); same-line duplicate
suppression (8 sequential fetches into one line ⇒ one AR); prefetch issued / suppressed
(resident-ITLB-only, not across a page without translation, never for INHIBITED); **prefetch
error silently discarded and a later demand fetch re-issues** (§4.1 P2/P3); prefetch-then-demand
merge; wrong-path refill + prefetch during a redirect storm; `invalidateAll` vs. in-flight fill
(§6.5) — currently unreachable and untested; predecode equivalence for I-b (per-beat vs
whole-line predecode must be bit-identical for every line, including the boundary words — an
exhaustive-per-line equivalence test, and note the standing warning that `PredecodeWordSpec`'s
"exhaustive 65536-opword" test aborts at first mismatch and does **not** actually run
exhaustively, so do not lean on it).

### 8.3 Measurement — new, because none exists

- **Miss-heavy IPC kernels** (§1.7: none exist). Minimum set: a stride walk larger than 8 KiB
  (guaranteed misses, measures raw miss throughput and MSHR depth sensitivity); a `memcpy`-shaped
  MOVEM loop (the LSU-stress shape the ported corpus already has, and the case D4 and §5.6 both
  target); a pointer-chase (dependent misses — the case MSHRs *cannot* help, included precisely
  as a control); a large-footprint sequential-code loop (I-side miss + prefetch); and a
  mixed load/store burst crossing lines (the `sameLine` interaction of D4, §5.2).
- **Counters** (simPublic, off in synthesis): D/I miss count, MSHR occupancy histogram, merge
  count, stall-by-reason (MSHR full, same-set, `sameLine`, SQ partial, `dQuiesce`), prefetch
  issued/useful/wasted, cycles with ≥1 outstanding transaction.
- **Sweeps to report at each gate:** `N_MSHR ∈ {1,2,4}` × latency tier ∈ {L2-hit, DRAM} ×
  response mode ∈ {InOrder, Chaos} × prefetch ∈ {on, off}. `N_MSHR = 1` must always be run: it
  is the regression baseline, the today's-crossbar configuration (§3.2), and the fallback if
  §11.Q3 is answered "not reworking it".

### 8.4 Lock-step implications

Musashi models no cache and no bus timing, so **lock-step is indifferent to all of this** as
long as memory-visible semantics are unchanged — which they are on every path here (the only
memory-visible change in the whole document is D4's larger refill granularity, which reads
*more* data but writes nothing extra). Two riders:

- Uninitialised-`Reg` randomisation makes lock-step seed-flaky (memory:
  `spinalhdl-sim-poke-gotchas`); every new MSHR/LTT register must be `RegInit`.
- Added latency changes *timing*, not results, but it changes how many instructions are in
  flight at any moment — so it will shift which interleavings the fuzz and ported suites explore.
  Expect new (real) bugs to surface from re-running the corpus, and budget for that rather than
  treating it as regression.

---

## 9. Implementation slicing

Every slice ends with the standing full-core OOC synth gate (≥ 250 MHz) plus a lock-step-green
requirement, per the standing project rule. Given the D-cache-corridor congestion history
(`iter_100_CongestedCLBsAndNets.txt` names `tagMem`/`ldS1Tag` nets) and the **unresolved ~3×
LUT bloat and 142 MHz measurement on this branch**, two additional gate conditions apply:
**(a)** no slice merges until the branch's existing bloat/FMax anomaly is resolved or explicitly
waived, because otherwise no slice's area/FMax delta is measurable; **(b)** at least one
post-route run on an **uncontended** machine (memory records repeated confirmation that FMax
measurement on this shared machine is unreliable — 214.3 vs 163.9 MHz for an identical commit).
Worktree isolation (`git worktree add`) is mandatory for before/after comparison.

The **"E2E?"** column records whether a slice delivers real end-to-end benefit on the SoC **as it
exists today** (§3.2's single-outstanding crossbar) or only after a SoC-side rework. This is the
column to read first.

| Slice | Content | E2E? | Files | Lines | Depends on |
|---|---|---|---|---|---|
| **V1** | **Memory-model successor**: per-ID reordering, L2-faithful two-tier latency (§3.1 numbers, `id_busy_c` block, no CWF), crossbar mode, protocol checker, `IllegalInterleave` mode, miss-heavy kernels, miss/stall counters (§8.1, §8.3). **No RTL.** | n/a | 6 test + 1 new | 500–800 | — (**do first**) |
| **V2** | ID hygiene, depth still 1: unique IDs, widen I-side `idWidth` to 4, route R/B **by ID** with asserts, `r.ready` pool-level, tag `DLoadRsp` + `busFaultResp`/`inhibitedResp` (§5.4), tag `storeAck` (§5.6). Inert — behaviour identical. | n/a (enabler) | 6–8 | 150–250 | V1 |
| **D1** | **D-side hit-under-miss, ONE MSHR, `N_LTT = 1`.** Park the miss context; keep accepting; per-entry poison; fill-forward (kills 2 REPLAY cycles); D3 set-exclusion incl. the lookup-vs-fill-write rule; store-exclusive-request-path rule. **No AXI concurrency, no new IDs.** ← *recommended first RTL slice* | **YES** | 3–4 | 250–400 | V1, V2 |
| **D3** | **Bursting: L1D line 16 B → 64 B (D4)**, matched to the L2 line, + critical-word-first + the `sameLine` mitigation (refill-merge of undrained SQ stores, §5.2). **Must precede copyback P4.** | **YES** | 3–4 | 250–400 | D1 (needs fill-forward) |
| **I1** | **I-side non-blocking accept + 1 demand MSHR + same-line duplicate suppression + set exclusion + close the `invalidateAll` hazard** (§6.1, §6.5). Optionally fold in the 256→128-bit AXI change (§7.1). | **YES** | 2–3 | 200–300 | V1, V2. **Independent of all D slices.** |
| **I2** | **Per-beat predecode (option I-b)**: 32 → 16 classify instances, delete `lineReg`, ‑1 cycle, beat-lag lookahead + equivalence test (§6.4). Pure area/latency win. | **YES** | 2–3 | 150–250 | none (standalone) |
| **I3** | **Next-line prefetch MSHR** with rules P1–P4 (§6.2), prefetch counters, on/off control. **Works on today's crossbar** (§6.2). | **YES** | 2–3 | 200–300 | I1, I2 |
| **D4** | **INHIBITED strict serialisation + `dQuiesce`** (§5.5, §7.3) and its consumers (exception entry, maintenance, RTE). Correctness, not performance. | **YES** (correctness) | 4–5 | 100–200 | D1 |
| **D2** | **`N_MSHR`/`N_LTT` → 2 (parameterised), miss-under-miss, secondary-miss merging + M1–M3 rules, per-ID R routing live.** First slice needing V1's reordering modes. | **NO — contingent on §11.Q3** | 3–4 | 200–350 | D1, V1 |
| **D5** | **Outstanding fast-path writes — merge with copyback P6 pipelined hit-drain** (§5.6). | **NO — contingent** (crossbar locks AW→B per master *and* per slave) | 2–3 | 150–250 | copyback P4, D2 |
| ~~X1~~ | ~~Cache-maintenance sideband to the L2~~ — **deleted. §7.4 establishes that no sideband exists or is needed**; CPUSH/CINV reaching the L2 as ordinary write bursts is the L2's documented design. | n/a | 0 | 0 | — |

**Parallelism:** the **I-side chain (I1 → I2 → I3) is fully independent of the D-side chain** —
disjoint files (`cache/IcachePlugin.scala`, `frontend/*` vs `cache/DcachePlugin.scala`,
`execute/LsEuPlugin.scala`, `ls/StoreQueue.scala`). I2 depends on nothing at all. So the I-side
work can also run concurrently with the copyback design's remaining P3–P6 slices. **D3 is the one
hard sequencing constraint: it must land before copyback P4.**

Rough total: **~2000–3200 lines across ~20 files**, i.e. several sessions.

**Recommended commitment order, revised in light of §3 AND the 2026-07-30 ratified decisions
(§11.Q2/Q3/Q4/Q7):**
**V1 → V2 (extended: + walker-master fold + I-cache 128-bit narrowing, Q7) → D1 → I2 → I1 → I3 →
D4 (extended: full at-ROB-head non-speculative MMIO, Q4)**, then *stop and re-measure*. **D3
(bursting to 64 B) is DROPPED from this near-term order per Q2** — the L1D line stays 16 bytes for
now; copyback P4 is unblocked and may proceed without waiting on this design. D2 and D5 stay in
the plan (Q3: the crossbar WILL be reworked) but should not be scheduled until that SoC-side rework
is confirmed landed — build them next once it is. I2 is pulled forward because it is standalone,
is plausibly part of the answer to the branch's LUT-bloat problem (§11.Q8), and carries no
functional risk beyond its equivalence test.

---

## 10. Alternatives considered

**A — Build a MOB / out-of-order LS issue instead.** The genuinely larger IPC lever: it would
let a younger load bypass an older store of unknown address. Rejected *for now*, honestly on
scope not on merit. What it needs (all enumerated from source): relax
`IssueQueuePlugin.scala:335-337` to `OHMasking.first(lsReady)`; then close the dropped-store
race the comment at `:319-327` describes, which needs a load queue of executed-load addresses, a
store-vs-load address match at SQ alloc, and a **selective replay/squash of an already-completed
load and its dependents** — a recovery mode that does not exist anywhere (recovery is
retire-time flush only, `RobPlugin.scala:709-715`, task #116). It also interacts with the
retire-time-only-flush simplification this design leans on (§4.3 R1). Verdict: a separate
project, and §5.4's LTT is deliberately shaped to be its load-tracking half so it is not
foreclosed.

**B — Minimal "hit-under-miss + bursting only", never more (D1 + D3 + the I-side chain, stop
there).** No multi-ID, no AXI concurrency, no reordering, no dependency on any SoC change. It
captures D4's 4× miss reduction, hit-under-miss, drain overlap and I-side prefetch — i.e.
**every win that is real on today's SoC (§3.2)**. **Given the crossbar finding, this is now a
seriously competitive end state rather than a fallback**, and it is the correct one if §11.Q3 is
answered "the crossbar will not be reworked". The recommended slice order is deliberately
constructed so that stopping here is cheap and leaves nothing half-built.

**C — Keep the LS EU single-outstanding; just pipeline the cache.** Deeper D-cache pipelining
with one outstanding request buys nothing: the stall is the *miss latency*, not the cache's
occupancy. Rejected — it does not address the actual bottleneck.

**D — D-side prefetch (next-line or stride).** Rejected for now (N5). Unlike the I side, the
D-side access stream is *not* provably sequential (no equivalent of the fetchPc `+8` argument),
so it needs a real predictor; and a D-side prefetch to a wrong address is worse than an I-side
one because of §4.1's rules — it must be suppressed for INHIBITED pages and must never trigger a
walk, which on the D side removes much of the benefit (the interesting misses are often the
ones that also miss the DTLB). **However**, §6.2's observation applies here too — prefetch hides
latency in time, not by concurrency, so it is *not* blocked by the crossbar — which makes it the
most attractive *future* D-side lever once D3's 64-byte line is in place (a 64-byte line plus
next-line prefetch approximates a 128-byte fetch granule without the tag cost). Revisit after
D3 with real miss-rate data from §8.3.

**E — Banked vs unified MSHR file / per-set MSHRs.** A per-set or banked MSHR structure would
remove D3's stall case. Rejected: with 128 (or 32) sets and N ≤ 4, D3's stall is
vanishingly rare, and a unified file with N × 7-bit set comparators is far cheaper than banking.
D3 also *buys* three separate hazard eliminations (§5.3), which banking would not.

**F — Add a refill-abort / squash path.** Tempting for wrong-path misses (§1.5: a wrong-path
I-miss costs the full 7 + L cycles and still allocates and bumps `victim`). Rejected: AXI has no
transaction cancellation, so "abort" can only mean "discard the response", which is exactly what
poison already does — with the beats still consumed. The only real saving would be not *issuing*
the AR, which requires knowing the fetch is wrong-path before the miss resolves, i.e. a
fetch-stage predictor (which does not exist, §1.5). Poison-and-allocate is strictly simpler and
matches the established I-side behaviour.

**G — I-side deep ring (depth 9+) to enable demand miss-under-miss.** Rejected in §6.1: it costs
a much larger InstructionBuffer plus a reworked `ibufRoomForIssue` reservation, to enable an
overlap that next-line prefetch (I3) provides for free.

**H — Option I-a (replicate `lineReg` per MSHR).** Kept only as the fallback if I-b fails a
synth gate; it pushes area the wrong way on a branch already under LUT-bloat investigation
(§6.4).

**I — Do nothing; spend the effort on the copyback design's P4–P6 instead.** A legitimate
option, and worth stating: P4–P6 are already designed, already sliced, and close known-red
tests, whereas this document opens new architecture with no test currently gated on it. The
counter-argument is D4/§11.Q2 — the line-size decision is *cheaper now than later* — plus the
fact that the I-side chain is independent and can run alongside P4–P6 without contention.

---

## 11. Open questions needing user input

**Q1 — RESOLVED, no longer a question.** The L2's contract is now read and documented (§3.1):
`macqd700-soc/rtl/soc/l2c*.v` + `docs/l2c_spec.md`. 64-byte lines, 2 MB 8-way, write-back
write-allocate, 8 internal MSHRs, 5.1–5.3-cycle hit, no critical-word-first, AXI4 with 4
CPU-visible ID bits, out-of-order across IDs by design, same-ID blocked at the front door. Two
residual unknowns remain and neither changes a mechanism: DRAM latency is undocumented (§3.4 U1 —
sweep it, never assume it) and the L2 may illegally interleave R beats across IDs (§3.4 U2 — this
design tolerates it, but the SoC side should be told).

**Q2 — RATIFIED 2026-07-30: keep the L1D line at 16 bytes for now; revisit later.** User decision,
against this document's own recommendation (recorded honestly: the recommendation was to widen to
64 B now, before copyback P4 bakes in the line-size-coupled dirty-bit/eviction granularity).
**Consequence: slice D3 (bursting) is DROPPED from the near-term commitment order** (§9) — copyback
P4 may proceed with today's 16-byte granularity without waiting on this design. D3 remains a valid
future slice; if it is picked up later, it must be sequenced before whatever *then*-current
copyback/eviction machinery would need to be redone, exactly as this section originally argued.

**Q3 — THE decisive question: will the SoC crossbar be reworked to allow multiple outstanding
transactions per master port?** (§3.2.) `macqd700-soc/rtl/soc/axi_xbar.v:1234-1258` and
`:2388-2412` serialise reads and writes per master port, and its authors explicitly analysed and
declined pipelining ("a substantial, risk-bearing redesign… do not bolt onto the current owner
scheme"). Until that changes, slices D2 and D5 deliver **nothing** end-to-end. Three coherent
positions, and I need to know which one applies:

- **(a) Not reworking it.** Then adopt alternative B (§10): D1 + D3 + I1/I2/I3 + D4, and
  **drop D2 and D5 from the plan entirely** rather than building dead machinery. This is a
  perfectly defensible end state and captures most of the available win.
- **(b) Reworking it as separate SoC work.** Then D2/D5 stay in the plan but gated, and someone
  should read `axi_xbar.v`'s own warning and budget the B-reordering problem it names. Regression
  surface: `tb-axi-xbar`, 196 tests.
- **(c) Retargeting a different SoC / integration entirely.** Note the iifx alternative is
  *worse* (`m68k-soc-iifx/.../MemCrossbar.scala:50-60`: "Single outstanding transaction across
  the crossbar", and its L2 has no MSHRs at all), so this would mean new SoC work either way.

Related and cheap to answer at the same time: is SoC integration of *this* core (which today has
none — §3) actually a near-term goal? If not, D2/D5 are speculative against an unknown future
fabric and (a) is clearly right.

**Q4 — RATIFIED 2026-07-30: full at-ROB-head non-speculative MMIO.** User decision, taking the
stronger of the two options this document offered. **Scope consequence:** slice D4 (§9) is
upgraded from "§5.5 strict serialisation" to a genuinely new mechanism — an INHIBITED load must
not even *launch* until it is the ROB head (mirroring the landed precise-store `headPreciseReady`/
at-head-drain shape in `StoreQueue.scala`/`RobPlugin.scala`'s completion-port machinery, but for
a LOAD rather than a store, and without needing the store side's write-visibility concerns). This
is materially more than §5.5's plain serialisation (which only ordered INHIBITED accesses
relative to each other and to other D-side transactions) — it also closes §4.3 R2 (a preempted
head re-executing an MMIO load) architecturally rather than by accident, and removes the
speculative-MMIO-read gap this document's brief flagged as pre-existing. This upgraded D4 should
be planned as its own right-sized slice (likely comparable in scope to the original precise-store
retirement work, Task-brief-wise) rather than folded casually into the smaller serialisation-only
version originally scoped — the `writing-plans` pass should size it accordingly.

**Q5 — DOWNGRADED to a confirmation request; the SoC already answers it.** §7.4: the L2 has no
maintenance interface **by design**, because CPUSH/CINV are L1-local and an L1 dirty writeback
reaches the L2 as an ordinary AXI write burst that merges into the L2's own write-back policy
(`macqd700-soc/docs/l2c_spec.md:30-43`), with the L2 declared the single point of coherency and
"no path around L2 for cacheable windows" (`:8-28,111-116`). So: **no sideband port, no AXI-Lite
control master, no L2 register address inside the CPU** — and that last point is a relief, because
it would have been exactly the assumption §4.1 bans. Copyback P5 can proceed as designed. The only
thing I would like confirmed is that we accept the SoC's invariant-2 claim at face value (no
DMA path around the L2 for cacheable windows), since no CPU-side instruction could compensate if
it were false.

**Q6 — Is option I-c (move predecode to align time, §6.4/§10.E) worth its own design pass?**
It is independently justified by the LUT-bloat investigation and by deleting the
`ambiguousLine` bug class that produced tasks #202, #204 and #209 — not by MSHRs. I recommend
adopting I-b for this project and raising I-c separately. Confirmation that I should *not* couple
the two would be useful.

**Q7 — RATIFIED 2026-07-30: yes, drop-in socket compatibility is a near-term goal — do the fold
now.** User decision. **Scope consequence:** slice V2 (§9) is extended to include (a) folding the
DTLB/ITLB table-walker masters into the D/I masters respectively (needs V2's ID-hygiene work
regardless, so this is genuinely the efficient moment, not scope creep) and (b) narrowing the
I-cache AXI master from 256-bit to 128-bit (`len = 3` instead of `len = 1` — note this is
INDEPENDENT of the Q2 line-size decision: the I-cache already has 64-byte lines today, §1.4, so
this narrowing changes only the bus width/beat count, not the cache geometry). This makes V2 a
larger slice than originally scoped; the `writing-plans` pass should size it accordingly, and may
choose to split the walker-fold and I-cache-narrowing into their own sub-slices if V2 grows
unwieldy as a single unit.

**Q8 — Confirm the branch's existing area/FMax anomaly is resolved or waived before any slice
merges.** With an unresolved ~3× LUT bloat and a 142 MHz measurement outstanding, no slice's
area or timing delta is measurable, so the standing synth gate cannot do its job. Slice I2 is
plausibly *part of the answer* to the bloat (§6.4), which is an argument for doing it early — but
that is a hypothesis, not a plan.
