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

A sharpening worth stating explicitly, because it drives the whole design: in
both blocked SSW tests **the MMU is off**, so the identity translation path
(`DtlbPlugin.scala:366-371`) classifies *everything* — including the unmapped
probe address `0xAAAA0000` — as `CACHEABLE`. Cacheability alone therefore
cannot separate fast from slow stores. What separates them is **residency /
proven backing**: `0xAAAA0000` can never have a resident line (its refill
DECERRs, `BehavioralMem.scala:44-58,112-119`), while any line that *has* been
successfully refilled once is proven to be backed by decoded memory. That is
exactly the human engineer's principle — "cacheable writes don't need
write-ack if they fall on a resident line; uncached accesses always need to be
acked to commit" — and this document refines "resident" into "proven-backed"
(§4.2), which turns out to be both cheaper and sounder than a literal
same-cycle residency check.

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

### 3.1 Approach A — proven-backed fast path + at-head slow path (refined version of the proposed principle) — RECOMMENDED

Summary: classify every store **at execute time** (where the DTLB response
and the LS EU FSM already are) into:

- **Fast path** — cacheable (CM = write-through or copyback, and D-cache
  enabled) **and** the target line/page is *proven backed*: complete at
  SQ-alloc exactly as today; retire timing unchanged; drain post-commit into
  the cache (dirty for copyback, cache+AXI for write-through).
- **Slow path A (cacheable, not yet proven backed)**: before SQ-alloc, run a
  **probe-refill** — architecturally a *load* of the target line through the
  existing load LAUNCH/WAIT machinery. A refill that returns OKAY allocates
  the line (normal load refill) and *proves the page backed*; the store then
  proceeds down the fast path. A refill that errors takes the **existing**
  task-#189 execute-time bus-fault path: `captureFault(atc=false)` → precise
  vector-2, SSW W=1/SIZE/ATC=0 all correct, younger instructions never
  retire. This is the crucial trick: **the fault is discovered at execute
  time, pre-completion, so no new ROB retirement machinery is needed for the
  cacheable case at all.** A speculative refill *read* is side-effect-free by
  the cacheable contract, so doing it for a (possibly wrong-path,
  pre-commit) store is safe — it is just a prefetch.
- **Slow path B (cache-inhibited)**: the write itself is the only possible
  transaction and it must not happen speculatively (MMIO). The store
  allocates into the SQ but its **completion is withheld**; the SQ drains it
  **at ROB head** (non-speculative: all older instructions retired), waits
  for the real B response, then reports either a completion (retire proceeds)
  or a fault (`atc=0`) into the ROB. This is the only place new
  retirement-interlock machinery is needed, and inhibited accesses are rare.

Plus the copyback substrate (dirty bits, hit-drain without AXI, eviction
writeback, CPUSH/CINV/CACR) detailed in §4.

Tradeoffs, honestly:

- **Performance**: common-case stores (fast path) are *identical to today* —
  no probe, no wait, completion at SQ-alloc. Only the *first* store to an
  unproven page pays a probe (≈ one load-miss refill, which also warms the
  cache); a small "proven-backed pages" filter (§4.2) makes repeats free.
  Copyback additionally *improves* drain throughput: a hit-drain acks in ~3
  cycles instead of a full AXI write round trip, which directly relieves the
  SQ-full (`WAIT_SQ`) backpressure MOVEM-style store bursts hit today.
  Inhibited stores serialize at retirement — architecturally unavoidable for
  precise uncached writes.
- **Complexity**: the probe reuses the existing load LAUNCH/WAIT/WAIT_A/
  WAIT_B states nearly verbatim (a "probe, discard data, then SQ-alloc"
  flag); the fault path reuses `captureFault`/`lsFaultCompletion`/SSW wholesale.
  The genuinely new machinery is: dirty bits + eviction writeback FSM in
  `DcachePlugin`, the at-head-drain interlock for inhibited stores
  (§4.4 — including an interrupt-preemption gate that must be exactly
  right), and the CPUSH/CINV/CACR plumbing.
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
  (a waiting head blocks slot-1 retire entirely). And note it does **not**
  need less new machinery than Approach A's slow path B — it needs the *same*
  at-head-drain interlock and interrupt gating, just applied to every store.
  The only thing it saves is the probe/filter and the copyback substrate.
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

### 4.1 Layer 1 — precise store bus faults

**LS EU store flow (cacheable):** in XLATE, consult the classification:

```
fast  := mmuEnabled && cacheable(s2Cmode) && dcacheEnabled &&
         backedFilterHit(storePage)
```

(The `mmuEnabled` term is a USER DECISION, 2026-07-19: MMU-off stays
conservative/cold-path — identity-translated stores do NOT get the
proven-backed fast-path treatment; they take the slow paths below, which
makes them precise by construction. Rationale: MMU-off is a boot/bring-up
regime; the performance case for the fast path lives where real page
attributes exist. See §5.1.)

- `fast` → exactly today's path: SQ-alloc + `captureCompletion`
  (`LsEuPlugin.scala:946-951` unchanged).
- `!fast` (cacheable) → route through the existing RESOLVE→LAUNCH→WAIT load
  states with a new `probe` flag: drive `loadCmd` for the store's line
  (both lines via WAIT_A/WAIT_B when `s1TwoAccess` — the machinery exists,
  `:1077-1108`); on `loadRsp` OK → seed the backed filter, then SQ-alloc +
  `captureCompletion`; on `loadRsp.fault` → `captureFault(atc=false)`
  (`:710-732`) — **the identical path task #189 built for load bus errors**,
  which already produces the right SSW via `lsFaultCompletion` →
  `faultedStore` → format-$7. `compFaultWr := isStore` is already generic.
  One small fix rides along: for a slot-B (second line) fault,
  `compFaultAddr` must be `s1AddrB`, not `s1Va` (`:724` hardcodes `s1Va`).
  The `poisoned` suppression (`:811-816`) applies as-is to wrong-path
  probes.
- Probe misses allocate the line normally (it *is* a load refill), so the
  later drain hits — probe doubles as write-allocate.

**The proven-backed filter** (§4.2) keeps the probe off the common path.

**Inhibited stores (slow path B):** in XLATE, `s2Cmode === INHIBITED` (or
DE=0 if the human opts for real DE semantics, §5.2) →
SQ-alloc with `uncached := True` and **no `captureCompletion`**. The LS EU
frees (its single-outstanding contract doesn't depend on completion). Then:

- **SQ at-head drain trigger** (`StoreQueue.scala` around `:144-147`):
  ```
  headUncachedReady := valids(head) && !committed(head) && uncached(head) &&
                       (robIds(head) === robHeadIn) && robHeadValidIn &&
                       !io.flush && !irqPreemptPendingIn
  headReady := (valids(head) && committed(head) && !io.flush) || headUncachedReady
  ```
  New SQ inputs `robHeadIn`/`robHeadValidIn` (= `rob.logic.h0`,
  `count > 0`) and `irqPreemptPendingIn` come via top wiring. In-order SQ
  drain guarantees all older stores drained first; ROB in-order retire
  guarantees all older instructions retired — so the drain is
  non-speculative.
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
  preempted head re-executes after RTE. If the uncached write has been
  issued (or completed) when preemption fires, the store executes twice —
  fatal for MMIO. Gating, both directions:
  - SQ side: `headUncachedReady` includes `!irqPreemptPendingIn`
    (= `interruptPending || tracePendingFire` from the ROB) — don't launch
    into a cycle that wants to preempt.
  - ROB side: `normalIrqGate` (`:982-984`) and `traceNormalGate` (`:1050-52`)
    gain `&& !uncachedDrainBusyIn` (a registered busy from the SQ covering
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
- **Exception-FSM E_DRAIN compatibility**: a *faulted* uncached entry pops on
  its error before the exception FSM starts, so `sqEmpty`
  (`ExceptionUnit.scala:694-713`) still resolves; the `excEnteringSq`
  one-cycle flush (`FullCoreSynth.scala:226-227`) remains the backstop for
  a *never-launched* uncached orphan (e.g. its instruction was flushed) —
  uncommitted entries squash, which now includes uncached ones; verify the
  `keep` logic (`StoreQueue.scala:302-313`) counts them correctly.

**Also in Layer 1 (cheap, closes latent load gaps):** gate line *allocation*
on cacheability — an INHIBITED load must not allocate (add
`&& cacheable` to the REFILL allocate writes, `DcachePlugin.scala:353-363`)
and should bypass hit-detect (a stale resident alias must not be observed:
force `ldS1Hit := False` for an inhibited access). Same bypass for inhibited
stores at drain-S2 (`:431-439`): skip the line write, AXI-only. Exact-size
(non-16B) AXI reads for MMIO loads are deferred — §5.5.

### 4.2 The proven-backed filter (what "resident line" actually means)

A literal "resident right now" check is the wrong invariant: residency can
change between check and use (evictions by younger loads between a store's
execute and its post-commit drain), and chasing that race leads to
drain-time re-classification and at-head machinery for *every* store. The
invariant that is actually needed for precision is weaker and **monotone**:

> A store may retire un-acked iff *some* AXI transaction to its target page
> has previously completed OKAY — because the SoC address decode is static,
> a page that responded OKAY once can never DECERR/SLVERR later.

"Responded OKAY once" is established by any successful refill (load miss,
store probe). Because the property is monotone, the filter **never needs
invalidation** — eviction of the line afterwards is irrelevant to fault
precision (the post-commit write-through/write-allocate to that page cannot
error), only to data placement (handled at drain as normal hit/miss).

Concretely: a 4-entry, page-granular (VPN[31:12]… physical PPN, since backing
is physical — use `s2Paddr(31:12)`) register CAM in the LS EU, seeded on
every OKAY refill/probe response, checked combinationally in XLATE. A store
line already resident in the D-cache also implies proven-backed, but the
filter alone suffices and avoids adding a tag-probe read-port user at
execute time (the FMax retimes deliberately serialized that port —
`DcachePlugin.scala:261-275`). Sequential store bursts (MOVEM, memset) probe
once per new page, then fly. Reset clears the filter; nothing else touches it.

The one assumption to sign off: **the SoC decode map is static and
page-uniform** (a 4 KB page is either wholly backed or wholly not). The sim
harness's decode (`BehavioralMem.scala:53-58`, top-nibble + 0xFFFF page) is
coarser than 4 KB, so this holds there; a future SoC with sub-page holes
would need the granularity dropped to line (16B) at some filter-hit-rate
cost. Flagged in §5.4.

### 4.3 Layer 2 — copyback substrate + CPUSH/CINV/CACR

- **Dirty bits**: `val dirtys = Vec.fill(ways)(Vec.fill(sets)(RegInit(False)))`
  beside `valids` (`DcachePlugin.scala:78`). Set on a copyback store-hit
  drain; cleared on refill-allocate and on CPUSH writeback.
- **Drain behavior by mode** (store-S2, `DcachePlugin.scala:413-446`):
  - COPYBACK hit → merge + line write + `dirtys := True`; **no AXI write**;
    pulse `storeAck` immediately (S2 or the following cycle). This is the
    drain-throughput win.
  - WRITETHROUGH hit → today's behavior exactly (line write + AXI beat,
    ack on B). Retirement was never gated on this B (fast path), and the
    proven-backed invariant makes its resp architecturally ignorable —
    but still *check* it and (sim-only) assert/log, so the "can't happen"
    claim is machine-checked (§5.4).
  - Cacheable miss at drain (only reachable via the probe→evict race) →
    fall back to write-through-no-allocate exactly as today, resp logged
    not trapped (post-commit; proven-backed makes an error unreachable).
    Alternative — drain-time write-allocate — is more traffic-faithful to a
    real 040 but adds a post-commit refill FSM entanglement for a rare
    race; recommend the simple fallback, revisit if the cache-mode tests
    care (they don't appear to).
  - INHIBITED → AXI-only (Layer 1), no line touch.
- **Eviction writeback**: the load-refill FSM (`:331-367`) must, when the
  chosen victim way is valid+dirty, first read the victim line (one shared-
  port read — the FSM owns the port during REFILL, no new arbitration) and
  issue a line write to `tagOf(victim)`-derived address, wait B, then
  proceed with AR/refill. New states ≈ {EVICT_RD, EVICT_WR(aw/w/b)} ahead of
  the existing REFILL. Writeback B errors: USER DECISION (2026-07-19) —
  handle imprecisely with a **diagnostic crash**: sim-side `assert`/fatal,
  and in hardware a sticky fatal-error flag surfaced as a top-level output
  (for a future SoC to wire to NMI/reset) — never an architectural trap
  (the line was proven backed, so this fires only on a broken SoC decode
  contract; §5.4). Note the store-S2
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
  only on same-line pairs); (b) a drain that turns out to be a miss or
  inhibited falls back to the serialized AXI path and stalls the pipeline
  behind it (rare by construction — probes make drains hit). The SQ pop and
  forwarding-retention logic generalize from "one in-flight drain"
  (`drainBusy`) to a small in-flight count; the existing hold-until-ack
  forwarding contract is preserved per entry. This is what actually converts
  MOVEM/memset-style bursts from B-latency-bound to cache-bandwidth-bound.

### 4.4 Performance posture (explicit invariants for review)

The design is built so that these hold, and each should be checked (IPC
bench + directed cycle counts) at the corresponding slice gate:

1. **The common store path is cycle-identical to today.** A cacheable store
   to a proven-backed page takes the exact IDLE→XLATE→alloc+complete
   sequence it takes now; retirement timing, wakeups, and SQ occupancy are
   unchanged. The `backedFilterHit` check is a 4-entry 20-bit CAM compare in
   XLATE, off the IQ/retire cones — no new stall condition on the fast path.
2. **The load path gains nothing on its hit arc.** The only load-side edits
   (inhibited no-allocate/bypass, eviction-writeback states) are in the
   miss FSM, behind the existing `busy` serialization; the S1 registered
   hit-compare and the shared read-port arbitration (the FMax-sensitive
   nets) are untouched.
3. **Costs are confined to events that are either already slow or new
   correctness requirements**: first store to an unproven page (one
   refill, which also warms the line — amortized to ~1/page by the filter);
   dirty-victim eviction (adds a line writeback to a refill that already
   pays an AXI round trip); inhibited stores (at-head serialization is the
   *price of precise uncached writes*, unavoidable in any correct design).
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

1. **MMU-off treatment — DECIDED (user, 2026-07-19): conservative
   cold path.** An earlier revision of this document proposed
   copyback-as-the-MMU-off-default plus the proven-backed fast path for
   identity-translated stores; the user has overruled that: **MMU-off
   stores do NOT get the proven-backed fast-path treatment.** Recorded
   semantics: under identity translation the backed filter is bypassed
   (`fast` requires `mmuEnabled`, §4.1); every MMU-off cacheable store
   resolves through a slow path before completion. Interpretation left to
   the plan pass, with a recommendation: use the **probe path** (not
   at-head-ack) for MMU-off cacheable stores — it is equally precise, and
   a probe of an already-resident line is a ~3–4 cycle cache hit, which
   keeps whole-corpus sim time sane (at-head-ack for every MMU-off store
   would put an AXI B round trip on every store retirement across the
   entire MMU-off corpus). MMU-off performance is explicitly *not* a goal
   (boot/bring-up regime); the performance machinery (filter fast path,
   copyback hit-drain, pipelined drain) is scoped to MMU-on pages with
   real CM attributes. This also removes the copyback-by-default harness
   question: with MMU-off drains still writing memory through (probe →
   resident → drain hits; identity default mode stays WRITETHROUGH),
   sentinel polling and end-of-run `checkMem` keep working unchanged; the
   cache-overlay peek helper shrinks to a nice-to-have for MMU-on
   copyback lock-step tests only.
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
4. **The "proven-backed ⇒ no future bus error" assumption** (static,
   page-uniform SoC decode). Holds in the harness; must be stated in the
   top-level integration contract for the eventual SoC (the drop-in-
   replacement work). Mitigation if ever violated: drop filter granularity
   to 16B lines. Additionally: put sim-only asserts on every
   post-commit-resp-ignored path (WT hit write-through, drain-miss
   fallback, eviction writeback) so a violation screams in sim instead of
   silently corrupting.
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

---

## 6. Rough scope estimate

This is a **genuinely major feature** — the largest LS-cluster change since
the original LS-1 slice — but it decomposes into two independently gateable
layers. Honest sizing (new/changed lines, excluding tests):

| File | Change | Size |
|---|---|---|
| `cache/IcacheTypes.scala` | CacheMode 3-way, TranslationRsp | ~15 |
| `cache/DcacheTypes.scala` | cmd cacheMode fields, service err/ack | ~30 |
| `cache/DcachePlugin.scala` | dirty vec, mode-aware drain, resp check + storeErr, eviction WB FSM, inhibited no-allocate/bypass, flush engine | ~300–450 (largest single piece) |
| `mmu/DtlbPlugin.scala` + `Tlb`/`TableWalker`/identity stubs | 3-way CM production | ~40–60 |
| `execute/LsEuPlugin.scala` | s2Cmode capture, probe routing + flag, backed filter, uncached deferred-completion path, SqAlloc fields, slot-B faultAddr fix | ~120–200 |
| `ls/StoreQueue.scala` | entry fields, at-head drain trigger + interlock, completion/fault out-Flows, error-pop | ~100–150 |
| `rob/RobPlugin.scala` | 5th completion port, 2nd lsFault port, IRQ/trace gates | ~40–60 |
| `exception/ExceptionUnit.scala` + `SystemState.scala` | CPUSH dispatch + wait, CacheControlService | ~50–80 |
| `decode/OperationDecoder.scala` + `MicroOpAssembler.scala` | CPUSH/CINV fields + An routing | ~40–60 |
| Top wirings (`FullCoreSynth.scala`, `FuzzDut.scala`, `ExecuteLockStepSpec.scala`, `IpcBenchSpec.scala` — all 4 per the task-#176 precedent) | new ports fan-out | ~80–120 total |
| `ls/StoreQueue.scala` + `cache/DcachePlugin.scala` (again) | pipelined hit-drain (in-flight count, same-line hold) | ~80–120 |
| Harness | CACR reset-poke in lock-step/fuzz/IPC DUT inits (§5.2), optional cache-overlay peek for MMU-on copyback lock-step tests, sim asserts | ~60–100 |

Total ≈ **1000–1450 lines across ~14 files**, i.e. several sessions with the
project's slice discipline. Suggested slicing for the plan-writing pass:

1. Slice P1: CacheMode plumbing + `b.resp` check + `storeErr` (inert) +
   inhibited load no-allocate. Low risk, synth-gated.
2. Slice P2: backed filter + store probe path + slot-B faultAddr fix →
   `exc_ssw_size_field` / `ssw_atc_bus_error_rw_consistency` green
   (the harness's DECERR injection already exists).
3. Slice P3: uncached at-head drain interlock (+ IRQ-race directed test).
4. Slice P4: dirty bits + copyback drain + eviction writeback (with the
   decided diagnostic-crash error posture) — exercised via MMU-on
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
list — real coverage of the new machinery (backed filter, dirty-bit
drain, eviction writeback, pipelined hit-drain) would otherwise rest on a
handful of dedicated tests, which is thin for a change of this size.

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
