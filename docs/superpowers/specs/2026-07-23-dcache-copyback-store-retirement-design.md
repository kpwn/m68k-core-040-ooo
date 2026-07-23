# D-cache copyback + precise store retirement — combined design

Date: 2026-07-23. Status: DESIGN PROPOSAL for human review (no code written).
Scope: the two coupled gaps characterized in earlier sessions — (1) the D-cache
has no real cacheability/copyback semantics, and (2) stores retire from the ROB
before their physical write completes, making a store bus error impossible to
deliver precisely.

Blocked tests this targets directly: `exc_ssw_size_field.s`,
`ssw_atc_bus_error_rw_consistency.s` (both in
`src/test/resources/m68kooo-ported-tests/asm/`), plus the whole
"D-cache cache-mode" characterized-not-fixed cluster the corpus already
contains: `mmu_ttr_cm_copyback_vs_serialized.s`, `cinv_line_basic.s`,
`mmu_pflusha_dirty_dcache_reconfig.s`, `cacr_bit31_roundtrip.s`,
`pea_4x_dcache_mmu.s`, `movem_postinc_odd_sp_cold_dcache.s`.
`exc_partial_macro_move_mem_mem.s` is only *partially* addressed — see §5.3.

---

## 1. Problem statement

Two facts about today's core, taken together, make precise store bus faults
structurally impossible:

**Fact 1 — a store's architectural "success" is decided before anything real
happens.** `LsEuPlugin` calls `captureCompletion(...)` the moment a store is
allocated into the StoreQueue (`LsEuPlugin.scala:946-951`, the XLATE state) —
this marks the ROB entry complete, the entry retires in order, `sqCommit`
marks the SQ entry committed (`FullCoreSynth.scala:219-225`), and only *then*
does the SQ drain the entry to the D-cache and the AXI bus
(`StoreQueue.scala:144-147`: `headReady = valids(head) && committed(head)`).
By the time the AXI B response comes back, the instruction has retired, its
ROB slot is freed and possibly reused, and its rename mappings are committed.
There is no instruction left to attach a fault to. `DcachePlugin` completes
the picture by never even looking at the response code
(`DcachePlugin.scala:470`: `storeAckReg := axi.b.valid && axi.b.ready` — a
SLVERR/DECERR is indistinguishable from OKAY).

**Fact 2 — the D-cache is unconditionally write-through/no-allocate/never-dirty
and ignores cacheability entirely.** Every store, regardless of the page's CM
bits, goes to the physical bus (`DcachePlugin.scala:14-21` header,
`:449-464` AXI write driver). The DTLB *correctly computes* `CacheMode`
(`DtlbPlugin.scala:366-400` response mux, from `MmuDesc.pgInhibited` /
`TtMatch.inhibited`, `MmuTypes.scala:61-64,121-138`) but no cache port carries
it: `DLoadCmd`/`DStoreCmd` (`DcacheTypes.scala:13-17,37-44`) have no
cache-mode field. CACR round-trips via MOVEC but has no consumer
(`SystemState.scala:47-53`). CPUSH/CINV decode to a `SysKind.CPUSH` no-op
that doesn't even read its An operand (`OperationDecoder.scala:970-988`,
`MicroOpAssembler.scala:1692-1697`, `ExceptionUnit.scala:1143-1145`). There
is **zero dirty-tracking anywhere** — not even vestigial: `DcachePlugin`'s
only per-line state is `valids` and `victim` (`DcachePlugin.scala:78-79`).

**Why they are one problem.** The obvious fix for Fact 1 — "don't let a store
retire until its physical write has been acknowledged" — is only affordable if
most stores don't actually need to wait. A store whose write cannot
architecturally fail (because it lands on a cache line that is *known to be
backed by real, decoded physical memory*) can retire immediately; only stores
whose transaction genuinely might error (uncached stores, and stores to lines
never proven backed) must wait for a real bus response. Distinguishing those
two classes *is* the cacheability/residency machinery of Fact 2. Fix Fact 1
alone and every store eats a full AXI round trip at retirement (§3.2
quantifies this); fix Fact 2 alone and the unmapped-store tests stay red.
Hence one combined design.

A sharpening worth stating explicitly, because it drives the whole design:
the trust boundary is **software-configured cacheability, not physical
backing**. When the MMU is ON and software has deliberately declared a page
CACHEABLE (page-descriptor CM bits / DTT windows), the CPU trusts that
declaration *unconditionally* — a store to such a page completes and retires
exactly as today, and if the declaration was a lie (page tables pointing at
nothing real), the error surfaces later, at eviction-writeback time, as an
explicitly **imprecise, non-restartable diagnostic fault** (a configuration
error, not something the CPU protects against with precision). When the MMU
is OFF — which is exactly the regime both blocked SSW tests run in — there
is no deliberate software declaration to trust (`DtlbPlugin.scala:366-371`
merely defaults everything, including the unmapped `0xAAAA0000`, to
CACHEABLE mechanically), so MMU-off stores take the conservative precise
path: retirement waits for a real, contemporaneous bus response, per the
standing user decision (§5.1). Cache-inhibited stores likewise. Crucially,
the CPU **never** bases any decision on assumptions about the SoC's
physical address decode ("this address answered OK before, so it is safe
forever") — that entire class of mechanism is banned by a standing
project-wide constraint (§4.2).

---

## 2. Current architecture: how a store flows today

Concrete, verified against source on branch `feat/rob-predictor-mem`:

1. **Issue → AGU → translate** (`LsEuPlugin.scala`): the IQ issues the store
   µop; S0 latches base/data (`:826-834`), S1 computes `s1Va` (`:300`), the
   FSM's IDLE state waits for the *registered* DTLB request to match and
   resolve (`:890`, `reqMatch`/`xlateReady`). A translation fault
   (non-resident / WP / supervisor page) is caught **here, at execute time**,
   via `captureFault()` (`:891-900` → `:710-732`) — this path is already
   precise and feeds `faultCompletion` → `RobPlugin.lsFaultCompletion`
   (`RobPlugin.scala:600-613`) → `faultedStore(h0)` → `faultRetire`
   (`:379`) → the ExceptionUnit format-$7 entry. Note the DTLB's
   `rsp.cacheMode` is *available and ignored* at this exact spot.

2. **SQ alloc = architectural completion** (`LsEuPlugin.scala:919-954`,
   XLATE state): if the SQ isn't full, `sq.io.alloc.valid := True` and
   `captureCompletion(B(0,32))` fire in the same cycle. The registered comp
   stage drives `completionPort` the next cycle → `RobPlugin.completes(robId)`
   (`RobPlugin.scala:553`). **This is the moment the store becomes
   architecturally "done"** — before any cache or bus activity.

3. **Retire** (`RobPlugin.scala:371,471-475`): `headReady = count>0 &&
   completes(h0) && !flushing`; `retire0` commits the rename mappings. The
   full-core wiring turns `retire0/retire1` into `sqCommit`/`sqCommitB`
   pulses (`FullCoreSynth.scala:219-225`) which set `committed(entry)` in the
   SQ (`StoreQueue.scala:241-248`).

4. **Drain, post-commit** (`StoreQueue.scala:144-163,277-292`): the oldest
   valid+committed entry is presented once on `io.drain` (a `Flow` —
   no backpressure), held resident (still forwarding) until `drainAck`.
   `DcachePlugin` latches it (store-S0 `:225-231`), reads the old line + tags
   (S1 `:237-242,272-275`), resolves hit and merges (S2 `:413-446`), writes
   the line **if it hit**, and *always* latches an AXI write-through beat
   (`:441-445` → aw/w drivers `:449-464`). The B handshake produces
   `storeAckReg` (`:470`) → `sq.io.drainAck` (`LsEuPlugin.scala:194`) → the
   SQ pops. **`axi.b.payload.resp` is never read.** (Contrast the load-refill
   side, which *does* check `axi.r.payload.resp` since task #189,
   `DcachePlugin.scala:352-365`, and delivers a precise vector-2 with
   SSW.ATC=0 through `busFaultResp` → `LsEuPlugin` WAIT `:1026-1070` →
   `captureFault(atc=false)`.)

5. **Fault delivery machinery that already exists and works**
   (`ExceptionUnit.scala:604-642`): the format-$7 SSW builder consumes
   `entryFaultAtc` (bit 10), `entryFaultSize` (bits 6:5, with the
   internal→SSW size re-encoding), `entryFaultWr` (RW bit 8), FC bits, EA
   from `entryFaultAddr` — all threaded from
   `RobPlugin.faultAddrStore/faultWrStore/faultSizeStore/faultSupStore/faultAtcStore`
   (`RobPlugin.scala:770-780,897-905`), which are written by
   `lsFaultCompletion`. **This design does not touch any of it** — it only
   adds new *producers* into `lsFaultCompletion`-shaped ports.

6. **Exception-FSM store path** (separate from the SQ): the ExceptionUnit
   stacks frames via `driveStoreNoXlate` directly onto `dcache.store`
   (arbitrated in `LsEuPlugin.scala:1196-1199`), waiting `dcStoreAck` per
   word (`ExceptionUnit.scala:716-770`). It relies on the one-ack-per-store
   contract; any change to ack semantics must preserve it.

Also relevant: the SQ forward/same-line stall logic (`StoreQueue.scala:
165-238`) assumes "a drained entry is held resident until the memory write
landed" to close a stale-refill window; §5.6 re-audits this under copyback.

---

## 3. Candidate approaches

### 3.1 Approach A — trust software-configured cacheability unconditionally; precise at-head machinery only where there is nothing to trust — RECOMMENDED (this is the user's stated intent)

Two paths, selected by one architectural fact the CPU legitimately owns —
what software told the MMU:

- **Fast path — MMU-on, page declared cacheable (CM=WT or CB), CACR.DE=1**:
  the store is trusted **unconditionally**. Complete at SQ-alloc, retire
  immediately — byte-for-byte today's timing (`LsEuPlugin.scala:946-951`
  unchanged). No probe, no filter, no residency check, no classification
  beyond the page attribute itself. The drain happens post-commit into the
  cache (merge+dirty under copyback; +write-through beat under WT;
  write-allocate refill on a drain miss). If software's cacheability
  declaration was a *lie* (page tables mapping a region no hardware backs),
  the CPU finds out only when a bus transaction on behalf of that data
  eventually errors — eviction writeback, drain-miss refill, WT beat — and
  reports it as an **asynchronous, imprecise, non-restartable diagnostic
  fault** (§4.2): a sticky core-fault record surfaced for JTAG/debug, on
  which **the core halts** (quiesces, frozen for post-mortem; only
  debug/reset recovers it — optionally an NMI instead, §5.4), by design
  NOT attributable to the originating instruction (that information is
  architecturally gone by eviction time, and that is accepted — this is a
  configuration error, not a case precision protects against).
- **Precise path — everything else**: MMU-off (identity translation
  defaults everything to "cacheable" mechanically; that is not a software
  declaration and is NOT trusted — per the standing §5.1 user decision,
  MMU-off is a conservative cold path), cache-INHIBITED pages, and
  CACR.DE=0. These stores allocate into the SQ but their **ROB completion
  is withheld**; the SQ drains them **at ROB head** (non-speculative: all
  older instructions retired, preemption gated), waits for the real,
  contemporaneous AXI B response, and then reports either a completion
  (retire proceeds) or a fault (`atc=0`) into the ROB → the untouched
  format-$7/SSW machinery. This is exactly the machinery the MMIO case
  needs in any correct design; MMU-off simply shares it.

This split is what makes the blocked SSW tests pass (they run MMU-off →
precise path → the store to unmapped `0xAAAA0000` faults precisely, younger
FAIL-sentinel instructions never retire) while keeping MMU-on production
code at full speed.

Plus the copyback substrate (dirty bits, hit-drain without AXI, eviction
writeback, CPUSH/CINV/CACR) detailed in §4.

Tradeoffs, honestly:

- **Performance**: MMU-on cacheable stores — the entire hot path of real
  workloads — are *cycle-identical to today* at execute and retire, and
  copyback additionally improves drain throughput (hit-drain acks in ~3
  cycles instead of an AXI B round trip, relieving the SQ-full/`WAIT_SQ`
  backpressure MOVEM-style bursts hit today). MMU-off and inhibited stores
  serialize at retirement on a real bus ack — the deliberate price of
  precision where nothing softer is trustworthy; MMU-off is explicitly a
  cold path (boot/bring-up; also the ported-test corpus — §5.1 quantifies
  the sim-time consequence honestly).
- **Complexity**: the smallest of every variant considered: no filter, no
  probe, no per-store classification machinery beyond reading the already-
  computed `cacheMode`. The genuinely new pieces: cache-mode plumbing,
  deferred-completion for precise-path stores, the at-head drain trigger +
  preempt interlock, the async diagnostic-fault channel, dirty bits +
  eviction writeback, CPUSH/CINV/CACR.
- **FMax risk**: moderate. Nothing new lands on the known-critical load-tag
  arc or the IQ select cone. Dirty bits add a 512-FF vec in the already
  congested D-cache corridor (`iter_100_CongestedCLBsAndNets.txt` named
  `tagMem`/`ldS1Tag` nets); the eviction-writeback FSM adds states but no
  deep cones; the SQ at-head compare is a 6-bit equality. Standing ≥250 MHz
  OOC gate + post-route check mandatory per project rule.
- **Verification**: large but the corpus already contains the acceptance
  tests (§1 list). Musashi models no cache, so lock-step is unaffected as
  long as memory-visible semantics stay equivalent; the corpus's own
  cache-mode tests map their sentinel region non-cacheable via DTT1
  (`mmu_ttr_cm_copyback_vs_serialized.s` line "DTT1 = passthrough
  0xFFxxxxxx (sentinel), CM=11 non-cacheable") — the test authors already
  solved sentinel-visibility-under-copyback at the test level. The MMU-off
  bulk of the corpus stays on write-through-visible memory semantics per
  the §5.1 user decision (MMU-off = conservative cold path), so sentinel
  polling and end-of-run compares keep working unchanged.

### 3.2 Approach B — every store waits for its ack at retirement (conservative)

Withhold completion for **all** stores; drain at ROB head; wait for B; check
resp. No cache changes beyond reading `axi.b.resp`.

- **Correctness**: fixes both SSW tests. Simplest to reason about.
- **Performance — quantified as best I can**: every store's retirement stalls
  the ROB head for a drain + AXI write round trip. In the sim harness the B
  latency is a `StreamDriver`-randomized handful of cycles plus the aw/w
  ready-randomizers (`BehavioralMem.scala:222-234`) — call it 5–15 cycles;
  on a real SoC (the cpu_socket AXI contract) a write ack through an
  interconnect is plausibly 10–30 cycles. Stores are roughly 10–20% of the
  instruction stream; at the current aggregate IPC ≈ 0.53 (CPI ≈ 1.9),
  adding even 8 cycles to 15% of instructions is +1.2 CPI → IPC ≈ 0.32, a
  ~40% regression, worse with real-SoC latencies. It also serializes retire
  (a waiting head blocks slot-1 retire entirely). And note it is exactly
  Approach A's precise path applied to *every* store — the identical
  at-head-drain interlock and interrupt gating — so it saves no machinery;
  it only forgoes the trusted fast path (and, optionally, the copyback
  substrate) that make A fast.
- **Verdict**: unacceptable as the end state; possibly acceptable as a
  *temporary* internal milestone during implementation (bring-up of the
  at-head interlock before the fast path exists), if staged carefully.

### 3.3 Approach C — retire fast, deliver the bus error imprecisely (silicon-faithful deferred fault)

Let stores retire as today; check `b.resp` at drain; on error, latch
{addr, size, wr, sup} and raise a *deferred* vector-2 (format-$7, ATC=0) at
the next instruction boundary, like an interrupt with a $7 frame. This is
actually close to what a real 68040 does — its writeback stages mean a store
bus error is reported with the PC arbitrarily ahead, and the $7 frame's WB
fields exist precisely so software can complete pending writes.

Considered because the two SSW tests *only* check SSW fields, not the frame
PC — their handlers discard the frame with `lea 60(%a7),%a7` and never RTE.
**But it fails anyway, decisively**: in
`ssw_atc_bus_error_rw_consistency.s:44-53`, the instructions *immediately
after* the faulting store write the FAIL sentinel
(`_fail_unreached_w: … move.l %d1,(%a1)`). Under deferred delivery the core
retires those instructions while the bad store's B response is still in
flight; the FAIL-sentinel store enters the SQ *committed* and must drain
(committed = architecturally done — it cannot be squashed,
`StoreQueue.scala:294-313` keeps committed entries across flushes). The FAIL
sentinel lands in memory regardless of the handler. The test's very structure
("Unreachable — handler diverts…") encodes a *precision requirement*: younger
instructions must never retire past a faulting store. Approach C also does
nothing for Gap 1. **Rejected**, but recorded because the
fail-sentinel-after-faulting-store argument is the concrete reason precision
is mandatory, and future readers will otherwise re-derive Approach C.

A rollback-via-replay variant (retire speculatively, roll back on error) is a
non-starter in this core: recovery is retire-time flush only
(`RobPlugin.scala:709-715` — task #116), there are no checkpoints, and
post-retire the RAT is committed and old physregs freed. Rolling back a
retired instruction is impossible without a from-scratch checkpoint scheme.

---

## 4. Recommended design (Approach A, in concrete detail)

Layered so each layer is independently testable and gateable. Layer 1 alone
turns the two SSW tests green; Layer 2 closes the cache-mode cluster and the
drain-throughput cost.

### 4.0 Cache-mode plumbing (prerequisite for both layers)

- Widen `CacheMode` (`IcacheTypes.scala:38-40`) from
  {CACHEABLE, INHIBITED} to **{WRITETHROUGH, COPYBACK, INHIBITED}** (a
  fourth "INHIBITED_SERIALIZED" value is not needed — this core's
  single-outstanding LS pipe is already serialized; collapse CM=10 and CM=11
  as today). Producers to update: `DtlbPlugin.scala:366-400` (identity →
  see §5.1 for which mode; TT hit → decode `ttr(6 downto 5)` instead of just
  bit 6; TLB path → carry 2 bits from `pgCacheMode`), `TableWalker`/`Tlb`
  entry field, `MmuTypes.scala` accessors, and `IdentityTranslationPlugin`/
  `DIdentityTranslationPlugin` stubs. The I-side can keep treating both
  cacheable modes identically (no I-side stores).
- Add `cacheMode` to `DLoadCmd` and `DStoreCmd` (`DcacheTypes.scala`), and
  to `SqAlloc`/SQ entry storage. The LS EU captures it in IDLE alongside
  `s2Paddr` (a new `s2Cmode` reg next to `LsEuPlugin.scala:369-371`), and
  `llReg` carries it for loads (`:387-396`).
- Additionally add to `SqAlloc`/SQ entries: `vaddr` (32b — the SSW EA field
  for an inhibited-drain fault must be the *logical* address; the SQ only
  stores `paddr` today), `supervisor` (1b, from `reqReg.sup`), and the
  `uncached` classification bit. ~70 bits/entry × 8 entries of new state —
  acceptable.
- CACR consumer: expose `ss.cacr(31)` (DE — confirmed by
  `cacr_bit31_roundtrip.s`) through a small `CacheControlService` using the
  **setup-allocated-wire pattern** — this is mandatory, not stylistic: the
  Fiber deadlock RobPlugin documents for PrivilegeService
  (`RobPlugin.scala:23-38`) applies identically here (DcachePlugin ←
  RobPlugin dependency direction).

### 4.1 Layer 1 — store retirement paths and fault reporting

**LS EU store flow:** in XLATE, the classification is a pure read of
already-available architectural facts — no probe, no filter, no history:

```
fast := mmuEnabled && cacheable(s2Cmode) && dcacheEnabled(CACR.DE)
```

(The `mmuEnabled` term is a USER DECISION, 2026-07-19, reconfirmed
2026-07-23: MMU-off stays conservative/cold-path — an identity translation
that merely *defaults* to "cacheable" is not a deliberate software
declaration and is not trusted. The cacheability term is trusted
**unconditionally** when it comes from real software configuration —
page-descriptor CM bits or DTT windows — per the 2026-07-23 user
statement of intent; see §4.2 for the trust model.)

- `fast` → exactly today's path, end to end: SQ-alloc + `captureCompletion`
  (`LsEuPlugin.scala:946-951` unchanged), retire on normal schedule, drain
  post-commit (hit → merge+dirty/WT-beat; miss → post-commit write-allocate
  or WT write, §4.3). Any bus error later incurred on behalf of this store
  (drain-miss refill, WT beat, eventual eviction writeback) is reported on
  the **asynchronous diagnostic-fault channel** (§4.2) — imprecise and
  non-restartable by design.
- `!fast` (MMU-off, INHIBITED, or DE=0) → **precise path**:
  SQ-alloc with `precise := True` and **no ROB completion yet**. The LS EU
  frees (its single-outstanding contract doesn't depend on completion);
  the store's completion arrives later from the SQ at-head drain, below.

- **SQ at-head drain trigger** (`StoreQueue.scala` around `:144-147`):
  ```
  headPreciseReady := valids(head) && !committed(head) && precise(head) &&
                      (robIds(head) === robHeadIn) && robHeadValidIn &&
                      !io.flush && !irqPreemptPendingIn
  headReady := (valids(head) && committed(head) && !io.flush) || headPreciseReady
  ```
  New SQ inputs `robHeadIn`/`robHeadValidIn` (= `rob.logic.h0`,
  `count > 0`) and `irqPreemptPendingIn` come via top wiring. This is
  deadlock-free because SQ ring order == program order: LS µops issue
  strictly in program order (verified — `IssueQueuePlugin.scala:316-337`
  explicitly enforces oldest-occupied-LS-only issue), so when a precise
  store's robId reaches the ROB head it is necessarily the SQ ring head
  (all program-older stores allocated earlier and already drained). ROB
  in-order retire guarantees all older instructions retired — so the
  drain is non-speculative. Drain semantics for a precise store: MMU-off
  cacheable → today's write-through drain (cache update on hit, no
  allocate on miss) but with the B response *awaited and checked* before
  completion; INHIBITED/DE=0 → AXI-only (no cache touch), same wait.
- **Resolution:** the D-cache must expose, alongside `storeAck`, an error
  qualifier: `storeErr := axi.b.valid && axi.b.ready &&
  (axi.b.payload.resp =/= OKAY)` (the one-line core of Gap 2,
  `DcachePlugin.scala:470`). On ack-OK the SQ drives a new
  `Flow` `sqCompletion{robId}` → a 5th ROB completion port (or an OR into
  port 2's wiring with a collision-free mux — recommend a dedicated port,
  the `Vec.fill(4)` at `RobPlugin.scala:98` becomes 5) → `completes(h0)` →
  retire proceeds → `sqCommit` marks it committed → pop (reuse the existing
  ack-pop, `StoreQueue.scala:281-292`). On ack-ERR the SQ drives
  `sqFaultCompletion{robId, vaddr, wr=1, sizeBits, supervisor, atc=0}`
  **plus** the completion (headReady in the ROB requires `completes`,
  `RobPlugin.scala:371`); `faultedStore(h0)` → `faultRetire` → format-$7,
  SSW.ATC=0/W=1/SIZE correct — all existing. The SQ pops the errored entry
  terminally (do *not* leave it for the `excEnteringSq` flush; popping on
  the error B keeps `drainBusy`/phase state clean and avoids a new orphan
  class). Add a second ROB `lsFaultCompletion` port rather than sharing —
  the LS EU can fault a younger access the same cycle
  (`RobPlugin.scala:282-290` shape, duplicated).
- **Interrupt/trace preemption interlock — the one genuinely dangerous
  race:** `interruptPending` and `tracePendingFire` preempt a head *without*
  requiring `completes(h0)` (`RobPlugin.scala:416-438,984,1052`); a
  preempted head re-executes after RTE. If the precise-path write has been
  issued (or completed) when preemption fires, the store executes twice —
  fatal for MMIO. Gating, both directions:
  - SQ side: `headPreciseReady` includes `!irqPreemptPendingIn`
    (= `interruptPending || tracePendingFire` from the ROB) — don't launch
    into a cycle that wants to preempt.
  - ROB side: `normalIrqGate` (`:982-984`) and `traceNormalGate` (`:1050-52`)
    gain `&& !preciseDrainBusyIn` (a registered busy from the SQ covering
    launch-through-resolution *and* the one cycle between ack-OK and
    retire).
  Because the SQ launch decision should be registered and the ROB samples
  the registered busy, there is a one-cycle handshake seam; the
  implementation plan must pick a single priority rule (recommend: a
  same-cycle `interruptPending` beats a not-yet-launched drain; a launched
  drain beats everything until resolved) and add a directed test for the
  race (store-to-MMIO + IRQ storm — `via1_t1_irq_storm` style).
  Flush sources are not a hazard: the head is the oldest instruction, branch
  flushes originate only from a retiring head (a store is not a branch),
  and `excSquash` requires the exc FSM active, which `excIdle` gating
  prevents while the head is a plain store.
- **Exception-FSM E_DRAIN compatibility**: a *faulted* precise entry pops on
  its error before the exception FSM starts, so `sqEmpty`
  (`ExceptionUnit.scala:694-713`) still resolves; the `excEnteringSq`
  one-cycle flush (`FullCoreSynth.scala:226-227`) remains the backstop for
  a *never-launched* precise orphan (e.g. its instruction was flushed) —
  uncommitted entries squash, which now includes precise ones; verify the
  `keep` logic (`StoreQueue.scala:302-313`) counts them correctly.
- **Store-fault EA precision detail**: `sqFaultCompletion.vaddr` must
  report the failing *slot's* logical address for a split (cross-line/
  cross-page) store — slot A's vaddr or the slot-B address — so the
  format-$7 EA field is the actually-faulting half (the SQ already tracks
  per-slot paddrs; carry per-slot vaddrs alongside).

**Also in Layer 1 (cheap, closes latent load gaps):** gate line *allocation*
on cacheability — an INHIBITED load must not allocate (add
`&& cacheable` to the REFILL allocate writes, `DcachePlugin.scala:353-363`)
and should bypass hit-detect (a stale resident alias must not be observed:
force `ldS1Hit := False` for an inhibited access). Same bypass for inhibited
stores at drain-S2 (`:431-439`): skip the line write, AXI-only. Exact-size
(non-16B) AXI reads for MMIO loads are deferred — §5.5.

### 4.2 The trust model, the standing constraint, and the async diagnostic-fault channel

**Standing project-wide constraint (user, 2026-07-23, ABSOLUTE):** the core
must **never** depend on any assumption about the SoC-side physical address
map — in particular, never on any variant of "this physical address/page
responded OK before, therefore it is safe forever." No mechanism of that
shape may exist anywhere in the core, regardless of how much it simplifies
or speeds anything up.

**What the CPU legitimately relies on** — exactly three evidence classes,
each either architectural or contemporaneous:

1. **Software-configured MMU cacheability** (page-descriptor CM bits, DTT
   windows, CACR.DE). This is an *architectural input*, trusted
   unconditionally as a statement of intent — and it is re-read on every
   access from structures with real, CPU-controlled invalidation (TLB
   entries invalidated by PFLUSH; TTR/CACR are live registers). If
   software lies (declares cacheable a region nothing backs), the
   consequence is the diagnostic channel below — a reported configuration
   error, never silent corruption and never a precision obligation.
2. **CPU-internal cache state** (a line being valid/dirty). Legitimate for
   the same reason TLB caching is: it is invalidated by architected
   instructions the CPU itself executes (CINV/CPUSH — made real by this
   design) plus its own eviction machinery.
3. **A real, contemporaneous bus response** for the very transaction being
   decided on (the precise path's awaited B/R responses; the load path's
   existing task-#189/#211 resp checks).

**The disqualified variant, recorded permanently so it is never
re-proposed:** an earlier revision of this document proposed an
execute-time probe-refill plus a persistent "proven-backed pages" filter — a
CAM of pages that had once answered OKAY, never invalidated, justified by
"the SoC decode map is static." That mechanism is **banned** by the
constraint above: it bakes an unverifiable assumption about the outside
world into the core's correctness argument, has no CPU-controlled
invalidation path (unlike TLB/PFLUSH or cache/CINV), and would break
silently on any SoC with remappable/hot-pluggable decode. It is also
unnecessary: the design above needs no residency or backing knowledge at
all — the fast path trusts software configuration (class 1), and every
path that cannot rest on that trust waits for class-3 evidence.

**The asynchronous diagnostic-fault channel** (the *only* error path for
trusted fast-path stores — USER DECISION 2026-07-19/2026-07-23):
every post-commit AXI transaction performed on behalf of trusted cacheable
data — dirty-line **eviction writebacks**, **drain-miss write-allocate
refills**, **write-through beats**, **CPUSH writebacks** — has its response
checked (never ignored), and a non-OKAY response raises a machine-level
diagnostic fault that is explicitly **imprecise and non-restartable**:
- a sticky fault record in the D-cache (`diagFaultValid` + first-error
  {addr, resp, kind}), surfaced as a top-level output for JTAG/debug and
  `simPublic` for the harness;
- **the core HALTS** (user, 2026-07-23): on the fault the core quiesces —
  no further fetch, retire, or bus activity — leaving the machine frozen
  for JTAG post-mortem rather than executing onward from a known-corrupt
  memory state. Implementation shape: a sticky variant of the existing
  STOP quiesce plumbing (`RobPlugin.scala:233-243` `stopped` gates
  retire + fetch already), but not interrupt-wakeable — only debug/reset
  leaves it;
- sim-side: fatal `assert` in any test not explicitly expecting it;
- NO architectural exception, NO attempt to attribute it to the
  originating instruction — that information is architecturally gone by
  eviction time, and that is accepted: this reports a software/hardware
  configuration error, it does not recover from it.
Hardware surfacing is DECIDED (§5.4): a core fault, not architecturally
visible by default (no exception, no frame) — a top-level status output now,
foldable into the future dbg_axi register set, with an optional NMI trigger
left as a possible later follow-up knob, not part of the base slices.

**Sanity sweep of the existing core for the banned pattern (done
2026-07-23, result: clean):** the DTLB/ITLB/walk-result latch cache
positive translations invalidated by PFLUSHA/`flushAll` and clear the
fault latch on `umFlush` (`DtlbPlugin.scala:287-296`) — CPU-controlled,
legitimate (class 2 pattern). Neither cache ever *caches an error
verdict*: the D-side refill error allocates nothing and re-probes the bus
on re-access (`DcachePlugin.scala:352-365`), and the I-side does the same
(`IcachePlugin.scala:345-375`, task #211). No existing mechanism assumes
"OK once ⇒ OK forever"; the banned filter would have been the first.
One adjacent flag for the record: until this design's CPUSH/CINV land,
the I/D caches have NO invalidation path at all (CINV is a no-op) — a
*staleness* cousin of the anti-pattern (cached data with no
CPU-controlled invalidation), already known as the D-cache-coherency gap
and closed by Layer 2.

### 4.3 Layer 2 — copyback substrate + CPUSH/CINV/CACR

- **Dirty bits**: `val dirtys = Vec.fill(ways)(Vec.fill(sets)(RegInit(False)))`
  beside `valids` (`DcachePlugin.scala:78`). Set on a copyback store-hit
  drain; cleared on refill-allocate and on CPUSH writeback.
- **Drain behavior by mode** (store-S2, `DcachePlugin.scala:413-446`):
  - COPYBACK hit → merge + line write + `dirtys := True`; **no AXI write**;
    pulse `storeAck` immediately (S2 or the following cycle). This is the
    drain-throughput win.
  - WRITETHROUGH hit (fast path) → today's behavior exactly (line write +
    AXI beat, ack on B). Retirement was never gated on this B; its resp is
    *checked* and a non-OKAY raises the §4.2 async diagnostic fault (a
    lied-about-cacheable configuration error — never silent, never
    precise).
  - COPYBACK miss at drain (fast path) → **post-commit write-allocate**:
    the store-side requests a refill from the (shared, single) refill
    engine, merges, writes the line + dirty. The refill's R resp is
    checked → non-OKAY = async diagnostic fault (no allocation). This is
    a normal event (first store to a cold line), entirely off the retire
    path — it costs SQ-drain latency only.
  - WRITETHROUGH miss at drain (fast path) → no-allocate AXI write exactly
    as today, resp checked → async diagnostic fault on error. (Optional
    later refinement: write-allocate for WT too, purely a
    traffic/locality tuning knob.)
  - Precise-path drains (MMU-off / INHIBITED / DE=0) → per §4.1: awaited,
    resp-checked, precise.
- **Drain-vs-refill same-set interlock (REQUIRED for copyback; also a
  pre-existing latent bug today)**: the store drain's S1 tag read and its
  S2 hit-detect/write are one cycle apart, and a *concurrent* load-refill
  (a younger load can legitimately be in REFILL while the SQ drains — the
  SQ `sameLine` stall only covers same-LINE overlaps, not same-SET
  different-line) can write tags/valids into that same set in the window:
  S2 then hit-detects on the stale registered tag read. Concrete failure
  today: refill replaces way W's line X with line Y at the cycle between
  the store's S1 read and S2 write → S2 still sees X → merges X-based
  data into a way now tagged Y (write-through masks the damage in memory
  but the CACHED line Y is corrupt). Under copyback the same window
  becomes a lost store (refill-priority discards the store's only write).
  Fix: a 2-cycle mutual exclusion — hold the refill's array write (delay
  `axi.r.ready`/the write by ≤2 cycles) while a store drain is in S1/S2,
  or replay the drain from S1 when any same-set array write landed in the
  window. Recommend the hold (trivially correct, bounded cost). Flag the
  today's-code exposure for the standing memory record as its own
  candidate bug, independent of this design.
- **Eviction writeback**: the load-refill FSM (`:331-367`) must, when the
  chosen victim way is valid+dirty, first read the victim line (one shared-
  port read — the FSM owns the port during REFILL, no new arbitration) and
  issue a line write to `tagOf(victim)`-derived address, wait B, then
  proceed with AR/refill. New states ≈ {EVICT_RD, EVICT_WR(aw/w/b)} ahead of
  the existing REFILL. Writeback B errors: USER DECISION (2026-07-19,
  confirmed 2026-07-23 as the ENTIRE fault story for trusted-cacheable
  data): the §4.2 async diagnostic-fault channel — sim-side fatal
  `assert`, sticky hardware fault record surfaced for JTAG/debug — never
  an architectural trap, explicitly imprecise and non-restartable (the
  originating instruction is long retired; this reports a configuration
  lie, it does not recover from one). Note the store-S2
  write and refill write already share the muxed write port with
  refill-priority (`:413-417` comment) — the eviction path must respect the
  same single-writer discipline.
- **CPUSH / CINV made real**:
  - Decode (`OperationDecoder.scala:970-988`): stop collapsing to a no-op —
    extract scope (line/page/all), cache selector (DC/IC/BC), the
    CINV-vs-CPUSH distinction, and the An register. The current comment's
    bit map ("1111 0100 ss CCC Ann") should be re-verified against the
    M68040UM / Musashi's decode table during implementation — I am
    deliberately not asserting exact bit positions here. Route An like
    PTEST does (`MicroOpAssembler.scala:1704-1713`: srcB → EU result →
    `sysValStore`), so S_APPLY has the address.
  - Execution: a new cache-maintenance command interface
    {push?, invalidate?, scope, addr} from the ExceptionUnit's S_APPLY
    (`ExceptionUnit.scala:1143-1145` replaced) into a DcachePlugin **flush
    engine** FSM: line scope = one set, tag-match 4 ways; page scope = walk
    all 128 sets (a 4 KB page wraps the 2 KB/way index space twice — walk
    sets, match tag∈page); all scope = walk 128×4. Each dirty match:
    read line → AXI write → clear dirty (+ clear valid for CPUSH/CINV —
    on the 040 CPUSH also invalidates for DC in the default CACR mode;
    verify against UM/tests). The exception FSM inserts a wait state
    (mirroring E_STWAIT) on a `maintDone` handshake before S_REDIR —
    serializing, so a multi-hundred-cycle walk is fine. IC/BC selector:
    clear IcachePlugin valids via a flushAll-style pulse (invalidate only —
    the I-cache is never dirty).
  - CINV DC semantics = discard dirty data (no writeback) — this is what
    `mmu_ttr_cm_copyback_vs_serialized.s` phase 1 and `cinv_line_basic.s`
    actually test. Lock-step caveat in §5.1.
- **CACR.DE**: gate cacheability with DE. USER DECISION (2026-07-19):
  DE=0 means **literally fully uncached, matching real silicon** —
  `effectiveMode = DE ? pageMode : INHIBITED` for every data access: loads
  bypass hit-detect and never allocate; stores take the at-head uncached
  path. Cache contents (including dirty lines) persist untouched across
  DE=0, exactly as on silicon; CINV/CPUSH remain the only invalidation
  paths. Consequences for the existing suites are in §5.2.
- **Pipelined hit-drain (the store-throughput lever).** Today the SQ drain
  is single-outstanding end-to-end because every drain terminates in an AXI
  B round trip. Under copyback a hit-drain terminates *locally* at store-S2,
  so there is no reason to keep the SQ at one-store-per-round-trip: let the
  SQ present the next committed entry as soon as the previous one has passed
  S2-with-hit (ack pipelined behind it), giving a sustained hit-drain rate
  of ~1 store / 1–3 cycles. Two hazards to close, both small:
  (a) back-to-back stores to the same line — store B's S1 old-line read can
  race store A's S2 write (BRAM readSync returns pre-write data, losing A's
  bytes in B's merge): add an S2→S1 same-set/way line bypass, or a 1-cycle
  conflict hold (recommend the hold first — trivially correct, costs a cycle
  only on same-line pairs); (b) a drain that turns out to be a miss (first
  store to a cold line → the post-commit write-allocate) or a precise-path
  entry falls back to the serialized AXI path and stalls the drain pipeline
  behind it — an uncommon, self-limiting event (the allocate warms the line
  for the rest of the burst). The SQ pop and
  forwarding-retention logic generalize from "one in-flight drain"
  (`drainBusy`) to a small in-flight count; the existing hold-until-ack
  forwarding contract is preserved per entry. This is what actually converts
  MOVEM/memset-style bursts from B-latency-bound to cache-bandwidth-bound.

### 4.4 Performance posture (explicit invariants for review)

The design is built so that these hold, and each should be checked (IPC
bench + directed cycle counts) at the corresponding slice gate:

1. **The MMU-on cacheable store path is cycle-identical to today.** It
   takes the exact IDLE→XLATE→alloc+complete sequence it takes now;
   retirement timing, wakeups, and SQ occupancy are unchanged. The `fast`
   classification is a read of two already-latched facts (`s2Cmode`,
   CACR.DE) — no lookup, no CAM, no new stall condition anywhere on the
   fast path.
2. **The load path gains nothing on its hit arc.** The only load-side edits
   (inhibited no-allocate/bypass, eviction-writeback states) are in the
   miss FSM, behind the existing `busy` serialization; the S1 registered
   hit-compare and the shared read-port arbitration (the FMax-sensitive
   nets) are untouched.
3. **Costs are confined to events that are either already slow, off the
   retire path, or deliberate cold paths**: drain-miss write-allocate
   (post-commit, SQ-drain latency only, warms the line); dirty-victim
   eviction (adds a line writeback to a refill that already pays an AXI
   round trip); inhibited and MMU-off stores (at-head serialization is
   the *price of precision* where software has declared nothing
   trustworthy — unavoidable in any correct design, and explicitly a
   cold path per the §5.1 decision).
4. **Copyback is a net throughput improvement where it applies**: hit-drain
   ack in ~3 cycles vs a full AXI write round trip relieves the
   SQ-full/`WAIT_SQ` backpressure that bounds MOVEM-style store bursts
   today, and removes per-store bus traffic on CM=copyback pages.
5. **The end state is a modern write-back/write-allocate L1D as the
   operating mode for MMU-on cacheable pages** (per the §5.1 user decision,
   MMU-off is deliberately a conservative cold path): stores to warm pages
   resolve entirely
   on-chip at ~1 store/1–3 cycles (pipelined hit-drain), bus write traffic
   collapses to evictions + explicit pushes + uncached accesses, and the
   SQ stops being B-latency-bound. Adjacent levers deliberately *not* in
   scope here (so the plan doesn't conflate them): a non-blocking L1D /
   MSHRs and multi-outstanding LS EU (the single-outstanding LS pipe is a
   pre-existing IPC ceiling independent of this design), and pipelined
   multi-ID AXI write-through for the WT-page case. Both compose cleanly
   with this design later.

### 4.5 What deliberately does NOT change

- Rename/freelist/RAT, IQ, dispatch: untouched (no new wakeup semantics —
  the fast path completes exactly as today; slow paths complete later via
  the same robId-keyed ports, and a store wakes nothing).
- The ExceptionUnit frame formats, SSW builder, RTE, sysOp FSM: untouched
  except the CPUSH arm and the CACR wire-out.
- The SQ forwarding logic: untouched (entries stay resident until
  ack/pop as today; forwarding correctness is unchanged in all paths).
- Musashi oracle and the lock-step whitebox: untouched (Musashi models no
  cache and no bus errors in the lock-step programs).

---

## 5. Open questions / risks (need human decisions or sign-off)

1. **MMU-off treatment — DECIDED (user, 2026-07-19, reconfirmed
   2026-07-23): conservative cold path.** MMU-off stores never take the
   trusted fast path (`fast` requires `mmuEnabled`, §4.1): an identity
   translation that mechanically defaults to "cacheable" is not a
   software declaration and is not trusted. With the probe/filter
   machinery gone from the design entirely, the one remaining slow
   mechanism — the at-head drain awaiting a real B response — is what
   MMU-off stores use, sharing it verbatim with INHIBITED/DE=0. Honest
   consequence, stated for sign-off rather than hidden: every MMU-off
   store's retirement waits an AXI B round trip (~5–15 sim cycles), which
   slows the (entirely MMU-off) ported-test corpus and MMU-off lock-step
   programs at retirement; MOVEM-burst-heavy tests feel it most. This is
   the deliberate price of the decision (MMU-off = boot/bring-up regime,
   performance explicitly a non-goal there); per-test timeouts may need
   raising, and the §6.1 dual-posture sweep provides the DE=1/MMU-on
   fast-path coverage those same tests would otherwise never give.
   Identity default mode stays WRITETHROUGH, so MMU-off drains still
   write memory through — sentinel polling and end-of-run `checkMem`
   keep working unchanged; the cache-overlay peek helper is only needed
   for MMU-on copyback lock-step tests.
2. **CACR.DE=0 policy — DECIDED (user, 2026-07-19): literally fully
   uncached, matching real silicon** (no documented divergence). §4.3
   records the semantics. Consequences the plan pass must own: reset
   CACR=0 means every existing test/lock-step/fuzz program boots with the
   D-cache architecturally off — every load a no-allocate AXI read, every
   store an at-head uncached wait. To keep the existing suites meaningful
   (and their runtimes bounded), the harnesses should model
   "firmware already enabled the caches": poke `ss.cacr` (simPublic,
   `SystemState.scala:61`) to DE|IE at reset in the DUT init of the
   lock-step/fuzz/IPC harnesses — equivalent to a boot ROM's
   `movec #...,%cacr`, not a semantics change. Ported tests run un-poked
   (real-silicon contract): tests that need caching enable DE themselves;
   tests that don't will run uncached — correct but slower, so per-test
   timeouts may need raising, and the uncached at-head path will see heavy
   traffic (good incidental soak coverage for the §4.1 interlock). The
   IPC bench must enable caches in its preamble or poke, else its numbers
   become meaningless.
3. **`exc_partial_macro_move_mem_mem.s` will likely stay red.** This design
   makes the mid-macro store's fault precise (vector 2, frame PC = macro
   start, since crack µops share `pc` → `faultPcStore`), which is the
   test's *prerequisite* — but its PASS condition additionally encodes
   restart semantics for already-retired crack µops (the A0 increment
   retired before the store faulted; plain RTE replays the whole macro and
   double-increments). That is the separately-characterized A3
   multi-access-restartability gap (see memory:
   `a3-multiaccess-restartability-assessment`), not solvable by store
   retiming alone. Set expectations accordingly: 2 of the 3 named tests go
   green from this design; this one needs A3 work on top.
4. **Async diagnostic-fault surfacing — DECIDED (user, 2026-07-23).**
   The §4.2 channel is a **core fault**: a sticky fault record
   {valid, addr, resp, kind} observable via the JTAG/debug seam (exposed
   as a top-level status output now; foldable into the dbg_axi
   debug-slave register set the drop-in-replacement plan already scopes),
   and **not architecturally visible by default** — no exception, no
   frame; **the core hangs in this condition** (quiesces fetch/retire,
   frozen for post-mortem — §4.2's halt semantics; only debug/reset
   recovers it). An **optional NMI trigger**
   off the same record is a possible follow-up knob (software-observable
   crash reporting for systems that want it) — left out of the base
   slices; if added later it is a plain level-7 assertion into the
   existing `iplIn` recognition path, nothing new architecturally. Sim
   behavior is decided (fatal assert unless a test opts in); the sticky
   record itself lands with Layer 2 regardless of the final pin/register
   plumbing.
5. **MMIO read width.** An INHIBITED load still issues a 16-byte AXI read
   today (over-read of device registers with read side effects). Correct
   MMIO needs exact-size AR (`ar.size` = access size). No current test has
   a device model, so this can be deferred — but it should be written down
   as a known limitation of the INHIBITED load path (deferred slice).
6. **SQ same-line stall re-audit under copyback.** The
   `sameLine`/hold-until-ack reasoning (`StoreQueue.scala:193-201,
   DcachePlugin.scala:466-470`) was built for "store not yet in *memory*"
   windows. Under copyback the cache becomes the point of truth for hits
   (a load to a dirty line hits it; a load can only miss/refill if the line
   is absent, in which case no dirty copy exists and any un-drained SQ
   store to that line still correctly stalls it). I believe the logic
   remains conservative-correct unchanged, but this deserves a dedicated
   review pass + the existing `ls-store-drain-race` directed tests re-run,
   plus a new "dirty-hit vs refill" directed test.
7. **One-ack-per-store contract with multiple ack sources.** `storeAck`
   will now pulse from three places (copyback-hit S2, AXI B, and — never
   simultaneously — the flush engine must *not* pulse it). The
   ExceptionUnit's per-word E_STWAIT and the SQ's drainBusy both count acks;
   an accidental double-pulse (e.g. hit-write *and* a stale B from a prior
   store) would desynchronize them. The implementation needs a single
   arbitrated ack source with a sim assert (`ack ⇒ exactly one outstanding
   store`).
8. **Interrupt-vs-at-head-drain race discipline** (§4.1) — the design names
   the gates, but the exact cycle-accurate priority needs to be pinned in
   the implementation plan and covered by an IRQ-storm directed test.
9. **CPUSH/CINV exact encoding + invalidate-on-push behavior** — verify bit
   fields and whether CPUSH leaves the line valid-clean or invalid (CACR
   DPI bit? the 040's CPUSH invalidates unless… check UM) against the UM
   and the corpus tests before coding the decoder change.
10. **Lock-step and CINV**: Musashi (no cache) applies every store to memory
    immediately; a lock-step program that CINVs a dirty copyback line would
    diverge (by design — real data loss). No current lock-step program does
    this; add a guard note to the lock-step docs rather than machinery.
11. **Pre-existing drain-vs-refill same-set window — flag as its own bug
    candidate in TODAY'S code, independent of this design.** Found while
    analyzing §4.3's interlock: a committed store's drain (S1 tag read →
    S2 stale-registered hit-detect + write) can overlap a younger load's
    concurrent REFILL array-write into the same SET but a different LINE
    (the SQ `sameLine` stall is line-granular and does not stop that
    load), letting S2 merge old-line data into a way the refill just
    re-tagged — a cached-line corruption that today's write-through only
    partially masks (memory stays right; the cached copy of the refilled
    line is wrong and a later load HITS it). Deserves a directed repro +
    fix attempt on its own track (likely related to the known
    "D-cache coherency race" memory item); the §4.3 interlock this design
    mandates would close it as a side effect.

---

## 6. Rough scope estimate

This is a **genuinely major feature** — the largest LS-cluster change since
the original LS-1 slice — but it decomposes into two independently gateable
layers. Honest sizing (new/changed lines, excluding tests):

| File | Change | Size |
|---|---|---|
| `cache/IcacheTypes.scala` | CacheMode 3-way, TranslationRsp | ~15 |
| `cache/DcacheTypes.scala` | cmd cacheMode fields, service err/ack | ~30 |
| `cache/DcachePlugin.scala` | dirty vec, mode-aware drain, resp check + storeErr, store-side write-allocate refill, drain-vs-refill same-set interlock, eviction WB FSM, async diag-fault record, inhibited no-allocate/bypass, flush engine | ~320–470 (largest single piece) |
| `mmu/DtlbPlugin.scala` + `Tlb`/`TableWalker`/identity stubs | 3-way CM production | ~40–60 |
| `execute/LsEuPlugin.scala` | s2Cmode/vaddr/sup capture into SqAlloc, `fast` classification (two-term read), deferred-completion gating for precise-path stores | ~50–90 |
| `ls/StoreQueue.scala` | entry fields (cacheMode/vaddr/sup/precise), at-head drain trigger + interlock, completion/fault out-Flows (per-slot fault vaddr), error-pop | ~100–150 |
| `rob/RobPlugin.scala` | 5th completion port, 2nd lsFault port, IRQ/trace gates | ~40–60 |
| `exception/ExceptionUnit.scala` + `SystemState.scala` | CPUSH dispatch + wait, CacheControlService | ~50–80 |
| `decode/OperationDecoder.scala` + `MicroOpAssembler.scala` | CPUSH/CINV fields + An routing | ~40–60 |
| Top wirings (`FullCoreSynth.scala`, `FuzzDut.scala`, `ExecuteLockStepSpec.scala`, `IpcBenchSpec.scala` — all 4 per the task-#176 precedent) | new ports fan-out | ~80–120 total |
| `ls/StoreQueue.scala` + `cache/DcachePlugin.scala` (again) | pipelined hit-drain (in-flight count, same-line hold) | ~80–120 |
| Harness | CACR reset-poke in lock-step/fuzz/IPC DUT inits (§5.2), optional cache-overlay peek for MMU-on copyback lock-step tests, sim asserts | ~60–100 |

Total ≈ **900–1350 lines across ~14 files** (down from the previous
revision — the probe path and filter structure are gone entirely), i.e.
several sessions with the project's slice discipline. Suggested slicing for
the plan-writing pass:

1. Slice P1: CacheMode plumbing + `b.resp` check + `storeErr` (inert) +
   inhibited load no-allocate. Low risk, synth-gated.
2. Slice P2: precise path — deferred completion + SQ at-head drain +
   completion/fault return ports (MMU-off + INHIBITED share it) →
   `exc_ssw_size_field` / `ssw_atc_bus_error_rw_consistency` green
   (the harness's DECERR injection already exists).
3. Slice P3: preempt-interlock hardening (+ IRQ-race directed test —
   note the interlock is soaked constantly by every MMU-off store from
   P2 onward, so P3 is verification-hardening more than new mechanism).
4. Slice P4: dirty bits + copyback drain + drain-miss write-allocate +
   drain-vs-refill interlock + eviction writeback + the async
   diagnostic-fault record (§4.2 channel) — exercised via MMU-on
   DTT/page CM=copyback directed tests; identity default stays
   WRITETHROUGH per the §5.1 decision.
5. Slice P5: CPUSH/CINV + real CACR.DE=0-fully-uncached semantics (§5.2
   decision) + the harness CACR reset-pokes and ported-test timeout
   review that decision requires → cache-mode test cluster green.
6. Slice P6: pipelined hit-drain (measured on the IPC bench's store-burst
   kernels; MOVEM throughput is the acceptance metric).

Every slice ends with the standing full-core OOC synth gate (≥250 MHz) and,
given the D-cache-corridor congestion history, at least one post-route
`impl_FullCore.tcl` run on an uncontended machine before merge (see the open
FMax-discrepancy note in project memory).

### 6.1 Cross-cutting verification requirement: cache-mode sweep — USER
### DECISION (2026-07-23)

Per the §5.2 CACR.DE=0 decision, the bulk of the existing corpus will
exercise the D-cache in only ONE mode by default per test (uncached unless
that test's own author already set CACR/DTT bits). That under-exercises the
new fast/copyback path outside the small number of tests in §1's acceptance
list — real coverage of the new machinery (trusted fast path, dirty-bit
drain, drain-miss write-allocate, eviction writeback, pipelined hit-drain)
would otherwise rest on a handful of dedicated tests, which is thin for a
change of this size.

Decision: identify the existing ported tests that meaningfully stress the
LSU (predecrement/postincrement load-store bursts, MOVEM, memory-indirect
addressing, memcpy/block-move-style loops — a superset of, but not limited
to, the tests already named as touching CACR/MMU) and **run a designated
subset of them in BOTH cache postures**: (a) as today / DE=0-uncached
(unchanged, the existing regression signal), and (b) with a small harness-
injected prologue enabling DE=1 plus a cacheable identity or page mapping
covering the test's working set, exercising the new fast/copyback path with
the SAME store/load traffic patterns real LSU-stress tests already provide.
Implement as a harness-level sweep (parametrize `PortedTestRunner`/the
ported-test driver to re-run a named test-name list under an alternate
init posture) rather than duplicating `.s` source files, to avoid corpus
drift between the two variants. This is a PLAN-PASS deliverable, not
optional polish — fold it into whichever slice first makes the fast path
reachable (P2) and expand it as later slices (P4 copyback, P6 pipelined
drain) land, since each of those slices' real behavior is otherwise only
exercised by the narrow acceptance-test list.
