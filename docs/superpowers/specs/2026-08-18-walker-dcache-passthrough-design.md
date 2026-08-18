# Routing ITLB/DTLB table-walk traffic through `DcacheService` — design

**Status:** PROPOSED / DESIGN ONLY. No RTL touched by this document. Direct input to a
future `writing-plans` pass.

**Date:** 2026-08-18
**Branch:** `fmax-closure-fanout`
**HEAD this spec was written against:** `bb7774c` plus the merged socket Tasks 1-8
(`4445b2b`, `60aee93`, `75cd1b6`, `38e6c31`, `56f2438`, `cf56ed1`, `b23f7cc`, `1f2f13b`).

**Scope, decided by the user and NOT up for relitigation here:** *full unification*.
**Both** halves of table-walk memory traffic — the `TableWalker`'s three dependent
descriptor **reads** and the ITLB/DTLB **U/M-bit descriptor writeback** — stop being raw
AXI transactions on per-walker `Axi4` masters and become ordinary `DcacheService` client
traffic (`loadCmd`/`loadRsp`, `store`/`storeAck`). This is option 1 of the scoping memo's
four (`.superpowers/sdd/2026-08-18-walker-dcache-passthrough-scoping.md`, "Options").

**Primary source:** that scoping memo. Its §§1-7 are treated as verified grounding and are
cited rather than re-derived. Every place this spec **extends or corrects** it is called
out explicitly in §11.

**Documents this reworks:** `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md`
(D7, D8, D9, D10, **D19**, D20, D27) and the in-flight
`docs/superpowers/plans/2026-08-18-axi-socket-adapter-implementation-plan.md` (Tasks 4 and
5, both already merged, plus two textual citations in Task 13). See §9.

**Numbering note.** Decisions here are numbered **W1-W30** rather than continuing the
socket spec's `D` series, because this spec *reworks* several `D` items and reusing the
same namespace would make "D9" ambiguous between two documents.

**Revision note (review-driven fix pass, 2026-08-18).** This document was reviewed
independently after `fc90cf5` and returned *changes requested* with 2 Critical, 8 Important
and 7 Minor findings. Every one is folded in here. The two Critical findings became
**W23** and **W24** (two unconditional `ready` leaks in `LsEuPlugin` that this design turns
into silent-corruption bugs), and the single largest substantive change is **W7**, which
replaces the original drain-to-zero hand-over on the load direction with an
**ownership-tag FIFO**. §13 records the review disposition item by item.

**Revision note (round-3 fix pass, 2026-08-18).** `ca850ea` was re-reviewed and returned
*changes requested* again, with 2 Critical, 3 Important and 4 Minor findings. The two Critical
findings are both about round 2's own work rather than about the original design:

- **Round 2 fixed 2 sites of a defect that has 9.** W23/W24 named `:2115` and `:265`; the
  identical "a handshake drive that is correct today only because nothing but its own client
  can own that port" shape occurs at seven further sites, including the store-side exact twin
  of W23. The round-3 answer is **W27**: an **exhaustive**, per-site table of *every*
  `dcache.*` handshake driver and consumer in `LsEuPlugin.scala` — re-derived from the file
  rather than from the review — each row carrying either its owner-qualification or an
  explicit justification for needing none. Per-finding patching of this defect class is
  what round 2 got wrong and W27 exists to stop it recurring.
- **W23's own prescribed fix line reopened the bug it closed**, in a different timing window,
  because `(ldOwner === CORE)` is a *current-grant* fact while the hazard is an *in-flight*
  fact. Corrected in §4.2.7 (`excLoadAdmit`, not a weaker proxy for it).

Round 3 also adds **W28** (the same-cycle owner-coherence invariant that every one of these
owner qualifications silently depends on). §14 records the round-3 disposition item by item.

**Revision note (round-4 fix pass, 2026-08-18).** `e74a7a6` was re-reviewed and returned
*changes requested* a third time, with 2 Critical and 3 Minor findings. **Both Criticals are
the same recurring shape as rounds 2 and 3 — a fix that closes every site it enumerates while
leaving a hole in the *mechanism it just introduced*** — and both fall in the one place round
3's exhaustiveness discipline did not reach: **state writes**, as opposed to the qualification
terms that read that state.

- **The ownership FIFO's PUSH PAYLOAD was never covered.** W28 scoped itself to "the owner
  state that qualification terms *read*". The value pushed into the FIFO is a *write*, and it
  was specified as `grantedOwner` — a register no admission predicate updates. The mux legs
  are gated by **admission predicates**, so the pushed tag could name a client that did not
  fire. That mis-tags an in-flight response and re-delivers round-1's C1 corruption through a
  new door. Closed by **W29**: the push payload is *derived from the admission predicates
  themselves*, and it becomes a **four**-valued tag (`CORE_LS`/`CORE_EXC`/`ITLB`/`DTLB`), not
  a copy of the three-valued arbiter owner.
- **`excLoadOutstanding` — a register this design creates — had no stated CLEAR condition
  anywhere**, while §4.3's whole W14 argument rests on it. The document even pointed a
  plan-writer at the wrong answer (an unqualified `dcache.loadRsp.valid` mirror of
  `excStoreOutstanding`), which would have been a brand-new instance of exactly the defect
  class W27 exists to eliminate. Closed by **W30**, which specifies the clear term, *proves*
  it against the RTL, and — because the investigation showed the invariant a naive mirror
  would need is **false** — additionally strengthens `excLoadAdmit`. §11.1 item 9 records the
  **pre-existing HEAD bug** that investigation turned up.

§15 records the round-4 disposition item by item.

**Revision note (round-5 fix pass, 2026-08-18).** `aab837e` was re-reviewed and returned
*changes requested* a fourth time, with **1 Critical, 1 Important and 4 Minor** findings — a
narrow, mechanical pass, not a structural one. The review independently re-verified **W29, W30,
W28 clauses (b)/(c), all three of round 4's new W27 rows, §6.6's boundedness proof, §4.3's
second (`ldOwnerFifoOccupancy`) assertion and the 59-reference W27 coverage sweep as correct**;
none of that is reopened. The Critical is the mirror image of the previous rounds' pattern:
rather than an under-specified mechanism, it is an over-eager **check** —

- **§4.3's mandated load-side assertion fired on CORRECT behaviour.** Round 4 wrote it against
  the exception sequencer's **presentation** signal (`excActive && excLoadCmdValid`, which is
  `ExceptionUnit`'s *held* `ldoValidReg`) when the property it needs is about **admission**
  (`excLoadAdmit`). Its failure condition is therefore *exactly* the waiting state W30 part (2)
  exists to create, which three other places in this document already specify as correct
  (§6.6, §10.2's `(e′)` row, §10.2's W30 row — all three would trip it). The danger was not the
  spurious failure but the obvious wrong way to silence it: weakening `excLoadAdmit` back toward
  round 2's `!ldOwnerFifoHoldsWalker` form, which reopens C2 *and* re-inherits §11.1 item 9's
  live production bug. Replaced with a tautology-against-W30 **structural tripwire**, which is
  a property `LsEuPlugin` can actually hold by construction.

The Important reclassifies §11.1 item 9: the pre-existing HEAD bug is **live, real and being
fixed as its own standalone task**, not "out of scope". §16 records the round-5 disposition
item by item.

**Revision note (round-6 fix pass, 2026-08-18).** `f96b2ea` was re-reviewed and returned
**1 Important and 2 Minor** findings — no Critical, and the review independently re-confirmed
**W1-W30 in full, and all of round 5's other fixes, as sound**. None of that is reopened. This
pass is three localised corrections; it adds **no** new design content and re-derives nothing.

- **The Important is a coverage claim, and it is the *second* time the same claim has been
  re-pointed rather than fixed.** Round 3 said an unqualified exception **presentation** header
  at `:2027` would trip §4.3's assertion; C-R5-1 showed that was false and round 5 substituted
  "§10.2's row `(e′)` catches it". Round 6 found **that substitute is also false** under W23's
  own `CORRECT` shape: with a walker in the FIFO, `excLoadAdmit` is correctly low, so
  `excLoadCmdReady` stays low and the exception FSM correctly does not advance — **all three of
  `(e′)`'s assertions pass** — while the unqualified header still drives `dcache.loadCmd.valid`
  into a high `ready` and fires the port spuriously every cycle, pushing bogus `CORE_LS` entries
  past the FIFO's proven 3-entry bound. `(e′)` read only `excLoadCmdReady` and FSM state, never
  `loadCmd.valid`/`fire`. **Both** checks are strengthened rather than the claim being re-aimed
  a third time: row `(e′)` now asserts `loadCmd.valid` low / no `fire` / FIFO occupancy
  unchanged across the withheld window, and §4.2.3's exclusivity assertion becomes a
  **fire-qualified `CountOne(...) === 1`** (the old `<= 1` was satisfied by the zero-predicate
  spurious-fire state, which is precisely the failure). Neither is claimed sufficient alone.

The two Minors: M-R5-4's vector-index rename was **incomplete** at three sites (including a
verbatim-copyable test prescription) and §16 overstated it as "everywhere" — both corrected;
and §6.6's round-5 "at most one walker command interposed per exception load" does not follow
from its own 1-2 cycle window (`!ldOwnerFifo.full`, plus W9's ITLB/DTLB rotation, permits one
per window cycle), so the **count** is corrected to at most two per exception load. The
boundedness conclusion is untouched. §17 records the round-6 disposition item by item.

**Citation convention (fix pass).** `DcachePlugin.scala` line numbers in the original draft
mixed a pre-Task-8 and a post-Task-8 snapshot (a `+5` and a `+43` shift respectively), and
§9.3 deliberately sequences this work *after* Tasks 7/8. Every `DcachePlugin.scala` citation
below is therefore anchored **by symbol or function name first**, with the current-HEAD
(post-Task-8, `1f2f13b`) line number given only as a locator. A plan-writer must re-resolve
by symbol, never by raw line.

---

## 0. Decision summary

| # | Decision |
|---:|---|
| **W1** | Table-walk descriptor accesses (reads **and** the U/M writeback) use a **FIXED architectural cacheMode**, not a derived one: `CACR.DE ? WRITETHROUGH : INHIBITED`. Never COPYBACK. (§4.1) |
| **W2** | That cacheMode is **stamped by the port mux inside `LsEuPlugin`**, not carried on `WalkReq` and not resolved inside the MMU plugins. `WalkReq` gains no `cacheMode` field. (§4.1.4) |
| **W3** | The read and the write halves use the **same** cacheMode expression, from the same source signal. A per-half mode is forbidden. (§4.1.3) |
| **W4** | Arbitration is an **extension of `LsEuPlugin`'s existing override mux** (`LsEuPlugin.scala:2020-2078`) from 2 sources to 4 — *not* a new `Stream`-level arbiter component. `DcachePlugin` gains **zero** new ports and **zero** new client awareness. (§4.2.1) |
| **W5** | Ownership is tracked **independently per direction** (a load owner and a store owner). A single combined token is rejected — **not** on D9's deadlock grounds, which do not transfer to this layer, but because a walker's read phase and its write phase are separated by an entire ROB commit, so one token needlessly serialises two temporally distant operations. (§4.2.2) |
| **W6** | Owner encoding is 2 bits over `{CORE, ITLB, DTLB}`. The ordinary LS pipe and the exception sequencer stay **one** owner (`CORE`), because their mutual exclusion is already proven and already implemented at `:2020-2078`. (§4.2.2) |
| **W7** | **Load direction:** a **depth-4 ownership-tag FIFO** inside the arbiter — a tag is pushed on every accepted `loadCmd` and popped in lockstep with every `loadRsp` — so `CORE-LS` and a walker may be concurrently outstanding, with **no** `DcacheService` bundle change. Drain-to-zero survives at the `CORE-EXC` boundary (*round 4: against **every** other client, not only walkers — see W30*). **Store direction:** drain-to-zero, unchanged. *(Round 4: the pushed tag itself is W29's, not W7's original `grantedOwner`.)* (§4.2.3) |
| **W8** | Base priority is `CORE-EXC > CORE-LS > walkers`, i.e. **today's behaviour exactly** whenever no walker is pending. Fairness comes from an **aging counter** per walker: after `WALKER_AGE_LIMIT` un-granted cycles a walker's `force` bit outranks `CORE-LS` (never `CORE-EXC`'s own presented command). `WALKER_AGE_LIMIT = 64`, constructor-parameterised for directed tests. Under W7's FIFO the force path is a rare fallback (probe-window/slot contention only), not the common case. (§4.2.4) |
| **W9** | The two walkers tie-break against each other with a **1-bit round-robin**, per direction. (§4.2.4) |
| **W10** | **No bounded-grant timer** (no D20 analogue) is built at this layer: starvation is structurally bounded by W7 + W8 and the bound is *proved* in §4.2.5. But the *observability* half of D20 is **not** declined — see W26. (§4.2.5) |
| **W11** | Handing the load port to a walker also asserts `LsEuPlugin`'s **existing** `probeCancelAll` (`LsEuPlugin.scala:863`) and suppresses new probe launches, by folding the owner bit into the **same** `!excActive` conjunctions at `:863`, `:716` and `:1774-1776`. Without this the design **deadlocks** — see §4.2.6. This is a correctness requirement, not an optimisation. *(Round 3: the `:716` conjunction is **factored up** one level into `coreLsLoadAdmit` so it covers the split leg of `:756` too — W27. `alignedSendValid` keeps its meaning and `:766` is unaffected.)* |
| **W12** | FMax mitigation is *structural* but **not costless**, and this spec does not claim it is. Three control-signal sites are pure `excActive`-conjunction substitutions (zero new levels). Two sites are genuinely new fan-in: the `loadCmdPort.payload.vaddr` mux widens 3-way → 5-way on the net that feeds `cmdSet` → `rdSet`, and `probeCancelAll` gains an OR term. Those are **mitigated, not eliminated**, and the mandatory 3-checkpoint synth gate is the control. (§5) |
| **W13** | The walkers' `storeAck`/`storeErr` are **demultiplexed by the latched store owner**, and the existing `sq.io.drainAck := dcache.storeAck && !excStoreOutstanding` (`LsEuPlugin.scala:270`) gains the walker term. This is mandatory: `StoreQueue.scala:518` already asserts on a stray ack. (§4.3) |
| **W14** | The load-response side gets **no bundle-level demux and no top-level rewiring**: `exc.dcLoadRsp` (`FullCoreSynth.scala:350-351`) and every DUT that replicates it stay byte-for-byte untouched. Routing is by W7's FIFO head (`ldRspTag`, W29) *inside* `LsEuPlugin`; the exception path keeps the pure temporal-exclusivity argument, which **W30** is what makes true. (§4.3) |
| **W15** | The walkers' client ports are plugin-level `var` hooks with default-idle `allowOverride` drives, wired by the **top-level/DUT wiring**, exactly mirroring the existing `umCommitValid`/`umFlush`/`excLoadCmdValid` idiom. No new `Plugin` service lookup, no new `FiberPlugin`. (§4.4) |
| **W16** | `TableWalker.selectWord` is **deleted**; the walker consumes `loadRsp.payload.data` directly. This removes a hand-rolled duplicate of `DcacheByteLane.extract`'s LONG case. (§4.5) |
| **W17** | The U/M writeback is emitted as `DStoreCmd` with `useStrb = True`, `strb = drainStrb`, `lineData = drainBeat`, `paddr = drainAddrReg`, `size = LONG` (don't-care under `useStrb`), `precise = False`. The whole `walkerAxi` AW/W/B drain FSM is deleted. (§4.6) |
| **W18** | A **new sim helper `DcacheClientMemAgent`** (a `Stream`/`Flow` analogue of `BehavioralMemAgent`) is built so the DUTs with no `DcachePlugin` keep working. That is **8 files / 8 DUT classes**, not the 5 the original draft counted. (§8.1, §10.2) |
| **W19** | The exception sequencer's maintenance quiesce is protected by a new `quiesceHold` gate that closes **walker** admission only (never `CORE`), asserted over `S_DRAIN` **and** `S_APPLY` — the latter because `maintCmdOut` pulses in `S_APPLY` while `maintBusyReg` only rises a cycle later. Without it `ExceptionUnit.scala:1362-1381`'s written deadlock analysis ("With the LS EU flushed, nothing re-arms them") becomes false. (§6.2) |
| **W20** | Task 4's committed code (`38e6c31`, `56f2438`) is **not reverted**; the walker-side half is deleted along with its host (`walkerAxi` ceases to exist), the `RESET_VEC` ARID half survives untouched, and `WalkerIdGuardSpec` is deleted. `AxiIds.WALK_READ`/`WALK_WRITE` stay defined-but-unused (renumbering is forbidden). (§9.1) |
| **W21** | Task 5's `AxiDMerge` is **shrunk, not deleted and not repurposed**: read side 4 owners → 2 (`DCACHE`, `RESETVEC`), write side 3 owners → 1 (pass-through). The D20 watchdog stays on the read side. Its *code* is not reused for this design's mux; its *bounded-progress discipline* is (as W8's aging counter). (§9.2) |
| **W22** | This is a **separate plan** with its own file. A single **superseding Task 5R** is inserted into the axi-socket-adapter plan, and D7/D8/D9/D19/D20/D27 get an addendum block in that spec. Tasks 6, 7, 8, 9, 10, 11, 12, 14 are unaffected in interface shape; Task 13 needs two textual citation updates. (§9.3) |
| **W23** | **CRITICAL.** `LsEuPlugin.scala:2115`'s `excLoadCmdReady := dcache.loadCmd.ready` is **unconditional** and must be qualified — by **`excLoadAdmit`**, the §4.2.3 admission predicate, *not* by the weaker `(ldOwner === CORE) && excActive && excLoadCmdValid` the round-2 text prescribed. `excLoadOutstanding`'s set term takes the identical predicate. Round-2's weaker form **reopened** the very bug it closed, because `dcache.loadCmd.ready` never references `valid`. *(Round 4: W23 specified only the **set** side of `excLoadOutstanding`; its **clear** side is W30.)* (§4.2.7) |
| **W24** | **CRITICAL.** `LsEuPlugin.scala:265`'s `sq.io.drain.ready := dcache.store.ready` is **unconditional**. The existing exception mux already closes exactly this hole at `:2068`; that hold must be extended to cover "a walker owns the store port", or a walker's U/M store silently consumes the SQ drain's `ready` and a core store is permanently lost. (§4.2.7) |
| **W25** | Walker `loadCmd.payload.token` is a **reserved value**, never a don't-care: `ITLB = 0x81`, `DTLB = 0x82`, disjoint from LS-EU probe tokens and from the exception sequencer's existing `0x80`. A don't-care token can collide with a live early-probe entry on token **and** vaddr and silently mis-answer the walker's read. `DcacheTypes.scala:6-8`'s token-layout doc comment is amended in the same edit, or the tree ends up with a now-false comment beside true constants. (§4.5) |
| **W26** | The arbitration point gets a **production** (not sim-only) stall counter reporting on **D28's halt-reason channel**. W10 declines D20's *timer*; it does **not** decline D20's *observability*, because a wedge at this new merge point produces no AXI grant to time out and would otherwise be invisible to D19, D20 **and** D28. (§4.2.5) |
| **W27** | **CRITICAL, round 3.** Owner-qualification is settled **exhaustively**, by a per-site table covering **every** `dcache.{loadCmd, store, loadRsp, storeAck, storeErr, loadProbe*}` driver and consumer in `LsEuPlugin.scala` (§4.2.7's table), each row carrying either its fix or an explicit justification for needing none. W23/W24 named **2** sites; the round-3 sweep produced a **29-row** table in which **10 further rows are new fixes no round-2 decision reached at all** (plus one recorded ordering constraint). Those ten include the split-load BK FSM's two `loadCmd.fire` samplings, `bkCompletes`'s `loadRsp` arm, the **store-side exact twin of W23** (`:2069`/`:2076`), the exception load/store **presentation** `when` headers (`:2027`, `:2067`) which nothing previously bound to an admission predicate, and the split leg of `dcache.loadCmd.valid` (`:756`), which no owner or arbitration term reaches at all. **No further per-finding patching of this class: the table is the unit of correctness.** *(Round 4 extends the table to **34 rows**: 2 pre-existing sites round 3's sweep missed (`:886`, `:757-764`) and 3 rows for **this design's own new logic** — W29's FIFO push, the FIFO pop, and W30's clear term — because new RTL that touches a `dcache.*` handshake is exactly as subject to the table as old RTL is.)* (§4.2.7) |
| **W28** | **Same-cycle owner coherence is an INVARIANT, not an implementation detail.** Every owner qualification in this spec is sound only if the mux's payload/valid **selection** and the qualification's **comparison** read the *identical* owner value on the *identical* cycle. The owner registers must therefore be updated in the same cycle, off the same signal, as the selection they gate — never a cycle later, and never with the mux selecting off a combinational grant while the comparison reads a registered owner. Otherwise C1/C2-class corruption reappears through a one-cycle timing gap instead of a missing site. **Round 4 adds two clauses that the round-3 wording left outside its scope and that were live bugs: (b) the invariant binds owner-state WRITES — above all the ownership FIFO's push payload — exactly as it binds the qualification terms that read them; and (c) `ldOwner`/`stOwner` get an explicit, stated update rule, which no section previously supplied at all.** (§4.2.8) |
| **W29** | **CRITICAL, round 4.** The ownership FIFO's **push payload** is a **four-valued admission tag** `ldRspTag ∈ {CORE_LS, CORE_EXC, ITLB, DTLB}`, encoded **combinationally at the cycle of `dcache.loadCmd.fire` from the four admission predicates that actually gate the mux legs** (`coreLsLoadAdmit`, `excLoadAdmit`, `walkerLoadAdmit(WALKER_ITLB_IDX)`, `walkerLoadAdmit(WALKER_DTLB_IDX)`) — **never** read from a separately-clocked `ldOwner`/`grantedOwner` register, under **either** of W28's two admissible implementations. The four predicates are **proven pairwise exclusive** (§4.2.3), so the encode is a well-defined one-hot. The three-valued arbiter owner `rspOwner` is **derived** from the tag, not stored beside it; W6 is unchanged (arbitration still sees one `CORE`). (§4.2.3, §4.2.8) |
| **W30** | **CRITICAL, round 4.** `excLoadOutstanding`'s **clear** term is `dcache.loadRsp.valid && (ldRspTag === CORE_EXC)` — W29's tag, **not** an unqualified `dcache.loadRsp.valid` mirror of `excStoreOutstanding` (`LsEuPlugin.scala:2073`), which would be a new instance of W27's defect class. The store-side invariant that makes the `excStoreOutstanding` mirror safe (`E_DRAIN`/`R_DRAIN` wait on `sqDrained`) has **no load-side analogue** — investigated and **disproved** against the RTL, §4.2.3 — so the clear must be qualified rather than justified. Because W14 keeps `ExceptionUnit.dcLoadRsp` **unqualified** by design, W30 additionally strengthens `excLoadAdmit`'s FIFO term from `!ldOwnerFifoHoldsWalker` to **`ldOwnerFifoEmpty`**: the exception sequencer's load-side drain-to-zero holds against **every** other client, not only walkers. This is strictly stronger than the round-3 C2 predicate and preserves that fix a fortiori. (§4.2.3, §4.3) |

| # | Recorded non-decision |
|---:|---|
| **N1** | The descriptor-update **read-modify-write is still not atomic**. The walker computes `newByte` from a descriptor value it read earlier and stores it later, at commit. A real 68040 uses a locked RMW bus cycle. This race exists today and is neither fixed nor worsened here. Out of scope. (§7) |
| **N2** | Walker descriptor reads now **allocate L1D lines** (a WRITETHROUGH load miss allocates; `doAllocate` excludes only INHIBITED). Page-table lines therefore compete for D-cache capacity. Accepted; it is also what makes repeat walks cheap. (§4.1.2) |
| **N3** | The pre-existing `umQueueFull` → walk-launch-blocked → ROB-can't-commit → queue-never-drains cycle is **unchanged** by this work. It is a real (if unobserved) latent hazard that predates this design. (§6.4) |
| **N4** | A walker load **and** a walker U/M store are both refused for the entire duration of a cache-maintenance walk: `loadCmdPort.ready` and `storePort.ready` carry the **identical** `!maintBusyReg` gate (`DcachePlugin.scala:981` and `:1865-1866`). This is a new **stall** coupling on both directions (bounded by `sets*ways` = 512 iterations), not a deadlock — proof in §6.3. |

---

## 1. Problem statement

### 1.1 The documented bug (write side)

`mmu_atc_write_hit_sets_modified` still fails with the M-bit *logic* itself already
correct. The tracked record is
`~/.claude/projects/-home-qwertyoruiop-m68k-core-040-ooo/memory/mmu-atc-m-bit-fix-2026-08-02.md`:
the correct fix on `mmu-atc-m-bit-tracking` (`c629bec`) was additionally blocked on a synth
gate. That memory also names a "separate D-cache/DTLB-AXI coherency gap, out of scope" —
this spec is that gap's closure.

Mechanism, re-confirmed against HEAD:

- `DtlbPlugin.scala:341-358` / `ItlbPlugin.scala:273-289` issue the U/M byte update as a
  raw AXI `AW`+`W`+`B` sequence on `walkerAxi`, a physically separate `Axi4` master
  (`DtlbPlugin.scala:75`, `ItlbPlugin.scala:71`).
- `DcachePlugin` has **no** snoop port and **no** external-write invalidate path. It is not
  told this write happened.
- If the descriptor's 16-byte physical line is resident **clean**, L1D silently holds the
  pre-update byte and a later read of that descriptor — by a subsequent walk, or by
  ordinary supervisor code reading its own page tables — hits the stale copy.
- If the line is resident **COPYBACK-dirty**, the eventual eviction writes the *older*
  cached image back over the walker's update: a genuine **lost update**, not mere
  staleness. That is the observed `mmu_atc_write_hit_sets_modified` shape.

### 1.2 The undocumented bug (read side)

Independent of the write mechanism and, per the scoping memo §6, not named in any tracked
task or test today. `TableWalker.scala:104-120` issues every descriptor read on its own AXI
master straight to backing memory. If the descriptor's line is COPYBACK-dirty in L1D — which
needs nothing more exotic than an ordinary supervisor `move.l` into page-table memory that
has not yet evicted — the walk reads **stale backing memory** while the newer data sits only
in L1D. The resulting translation is built from a descriptor the program has already
overwritten.

This is the mirror image of §1.1 and the write-side fix does nothing for it. Closing only
one half leaves a real hole; that is precisely why the user chose full unification.

### 1.3 Why routing through `DcacheService` closes both

Because `DcachePlugin`'s S0→S3 store pipe and S1/S2 load pipe resolve hit/miss against the
**same array** every other client consults. A store that hits merges into the resident line
(`DcachePlugin.scala:1927-1935`); a store that misses under WRITETHROUGH writes through to
memory with no stale resident copy to contradict it; a load that hits a dirty line returns
the merged, current data. There is exactly one place the truth lives, and every client
reads it.

---

## 2. What the existing interface already gives us for free

Verified, not assumed:

| Need | Already expressible? | Evidence |
|---|---|---|
| Read one 16-byte-aligned line | Yes, that **is** the D-cache line | `TableWalker.scala:106-111` (`addr[31:4]##0`, `len=0`, `size=4`) vs `DcachePlugin.scala:43-44` (`lineBytes = 16`) |
| Extract the 32-bit descriptor big-endian | Yes, identically | `TableWalker.selectWord` (`:86-94`) is a hand copy of `DcacheByteLane.extract`'s LONG case (`DcacheTypes.scala:186-190`); the walker's own doc comment at `:80-85` says so |
| Single-byte descriptor update with an explicit strobe | Yes, exactly | `DStoreCmd.useStrb`/`strb`/`lineData` (`DcacheTypes.scala:83-95`) vs the hand-built `drainBeat`/`drainStrb` at `DtlbPlugin.scala:320-330` |
| Physical-only client (no virtual index) | Yes, precedented | The exception sequencer's identity-physical convention, `LsEuPlugin.scala:2029-2035` |
| Single-outstanding walker | Yes, by construction | `TableWalker.scala:35` ("Single-outstanding (one walk at a time)"), plus `missReqReg`/`missPending` serialisation at `DtlbPlugin.scala:182-189,217-226` and `ItlbPlugin.scala:169-181` |
| Elastic, payload-stable-until-fire store | Yes, same contract | `DcacheTypes.scala:122` vs the `drainAwDone && drainWDone` gate at `DtlbPlugin.scala:334` |

**Nothing in the command bundles needs to change.** The gaps are all on the *arbitration*
and *response-identification* side, which is what §4.2/§4.3 build.

---

## 3. Architecture

```
                      ┌───────────────────── LsEuPlugin.logic ─────────────────────┐
  aligned load queue  │  P0 CORE-LS   ──┐                                          │
  + split BK FSM      │                 │                                          │
                      │  P1 CORE-EXC  ──┤  (existing 2-source override mux,        │
  ExceptionUnit  ─────┤                 │   :2020-2078, UNCHANGED semantics)       │
                      │                 ├──► dcPortMux ──► dcache.loadCmd          │
  ItlbPlugin.walker ──┤  P2 ITLB      ──┤    (NEW: 2-bit  ──► dcache.store         │
                      │                 │     ldOwner /                            │
  DtlbPlugin.walker ──┤  P3 DTLB      ──┘     stOwner,                             │
                      │                       aging, and a                         │
                      │                       depth-4 LOAD                         │
                      │                       OWNERSHIP FIFO ◄── dcache.loadRsp    │
                      │                       (push the 4-way                      │
                      │                        ldRspTag on cmd                     │
                      │                        fire — W29; pop                     │
                      │                        on rsp ⇒ ldRspTag)                  │
                      └────────────────────────────────────────────────────────────┘
                                                     │
                                                     ▼
                                       DcachePlugin (UNCHANGED — still exactly
                                       one loadCmdPort / storePort, still has
                                       no idea walkers exist)
```

Responses (`loadRsp`, `storeAck`, `storeErr`) stay broadcast on the wire, exactly as today —
no bundle gains a field and no DUT is rewired. Who is allowed to *believe* them is settled
**inside `LsEuPlugin`**: on loads by the ownership FIFO's head (`ldRspTag`, W7 + W29 — a
**four**-valued tag, `CORE_LS`/`CORE_EXC`/`ITLB`/`DTLB`, because the LS pipe and the exception
sequencer are one arbiter *owner* but two response *identities*; `rspOwner` is the three-valued
view derived from it), on stores by the latched `stOwner` (W13). See §4.2.3 and §4.3.

The walkers' AXI masters (`itlbAxi`, `dtlbAxi`) and `TableWalker.io.axi` cease to exist.

---

## 4. The decisions

### 4.1 W1/W2/W3 — cacheMode policy for table-walk accesses

#### 4.1.1 Why a fixed policy is unavoidable

`WalkReq` carries `vpn`, `rootPtr`, `isWrite`, `isSuper` and nothing else; `TableWalker` has
no cacheMode concept at all (scoping memo §2, confirmed at `TableWalker.scala:39-46`). The
instinct "look up the containing page's MMU attribute" is **circular**: the descriptor reads
target the page table's *own* physical pages, and the walker *is* the attribute-resolution
mechanism. Real 68040 table searches are physical-only for the same reason.

This does **not** violate the standing
`feedback-no-soc-address-map-assumptions.md` rule. That rule forbids reasoning about a *data*
access from a cached or assumed address-map property instead of the access's own resolved
page attribute. Here there is no page attribute in existence to consult, for anyone, ever —
so a fixed architectural policy is the only well-defined option, and the same situation
already has a precedent in this codebase: `LsEuPlugin.scala:2061-2063` forces the exception
sequencer's frame/vector cacheMode from `CACR.DE` rather than deriving it from a page.

#### 4.1.2 Why WRITETHROUGH specifically (not merely "cacheable")

This is the load-bearing part of the decision, so it is argued per residency state. Let
"the line" mean the 16-byte physical line containing the descriptor.

**U/M writeback under WRITETHROUGH:**

| Line state at write | What `DcachePlugin` does | Result |
|---|---|---|
| Not resident | Write-no-allocate: AXI write-through beat only (`DcachePlugin.scala:1942-1956`) | Memory correct; nothing stale resident |
| Resident clean | S3 merges the byte into the array (`:1927-1935`) **and** writes through | Array correct **and** memory correct |
| Resident COPYBACK-dirty | S3 merges into the array; `dirtys` is **not cleared** (only `when(stS3Copyback)` *sets* it, `:1933`) **and** the beat writes through | Array correct, memory correct, and the eventual eviction writes back the *merged* line — no lost update |

Under WRITETHROUGH the update lands in **both** places in **every** state, with no dependence
on eviction ordering. The bug is closed *by construction*.

**Under COPYBACK** the same three cases are also correct (a hit merges + marks dirty and
resolves entirely on-chip, `:1999`; a miss write-allocates, `:1434-1445`) — but correctness
now *depends* on the eviction/writeback path being right, and backing memory is transiently
stale. A page-table descriptor sitting dirty-only in L1D is precisely the state that created
§1.1 and §1.2 in the first place. Choosing COPYBACK would be trading a proof for a
dependency, for no benefit: descriptor updates are rare (one U/M transition per page per
access class), so the write-through traffic is negligible and the dirty-line savings are
nil.

**Under INHIBITED it is not merely "wrong because table walks aren't MMIO"** — it is wrong
because it *structurally reintroduces the bug this work exists to fix*. `DcachePlugin.scala:714-717`:
an INHIBITED store never touches the array at all. So an INHIBITED U/M write to a resident
line updates memory and leaves L1D stale — byte for byte the §1.1 clean-resident failure,
just with the raw AXI master replaced by an in-cache bypass. `LsEuPlugin.scala:2044-2059`
records this exact hazard being hit for real on the exception path (P5.7), including the
resulting deterministic hang.

#### 4.1.3 W3 — one mode for both halves

If the read used WRITETHROUGH (allocating the line) and the write used INHIBITED (bypassing
the array), or vice versa, we would have manufactured the P5.7 mismatch inside the MMU. The
two halves therefore take their cacheMode from the *same signal expression*, not from two
copies of the same idea.

#### 4.1.4 W1/W2 — the exact expression, and where it lives

```
walkCacheMode = Mux(cacheCtrl.map(_.dcacheEnabled).getOrElse(False),
                    CacheMode.WRITETHROUGH,
                    CacheMode.INHIBITED)
```

— textually the same expression as `LsEuPlugin.scala:2061-2063`, deliberately, so a future
change to the DE policy cannot drift between the two sites.

`CACR.DE = 0` must fold to INHIBITED for the same reason P5.6/P5.7 do: with DE clear,
*every* ordinary access is INHIBITED and nothing allocates, so a cacheable walker access
would be the lone allocator and the lone stale-copy source. INHIBITED walker accesses are
safe **only** in that regime, where they are consistent with everything else.

**W2 — stamped by the mux, not carried on `WalkReq`.** `LsEuPlugin` already holds
`host.get[CacheControlService]` (`:219`) and already computes **this exact expression** once,
at `:2061-2063` (the exception sequencer's `loadCmd.cacheMode`). *Citation corrected in the
fix pass:* `:1738`'s `txEffectiveCmode` is **not** the same expression — it is
`Mux(dcacheEnabled, xlate.rsp.payload.cacheMode, INHIBITED)`, i.e. the *page's* mode when
`DE` is set, not a hardcoded `WRITETHROUGH`. It shares only the `DE ? … : INHIBITED` shape.
So this design adds the **second** instance of the walk/frame constant form, not the third,
and W3's "one signal expression" obligation is discharged by deriving both walker halves
from that one new signal — not by pointing at a pre-existing duplicate. The MMU plugins gain
no new service dependency and `WalkReq` gains no
field; the walker emits its command with `cacheMode` assigned don't-care and the mux
overwrites it on the way through. This also makes it structurally impossible for the two
halves to disagree (W3 becomes true by construction rather than by discipline).

**Real-hardware corroboration.** No MC68040 User's Manual text on table-search cacheability
was findable in this repository (searched `docs/`, `src/` for *table search* / *CACR.DE*
commentary; the only `CACR.DE` material is this project's own P5.6/P5.7 work). The decision
above is therefore justified **on this core's own mechanics**, as argued in §4.1.2, and is
*consistent with* — but not derived from — the widely-reported 68040 behaviour of performing
table searches as cachable write-through accesses. The implementation plan should carry a
non-blocking step to confirm the wording against the real UM and record the section number;
if the UM says otherwise, only §4.1.4's constant changes, not the architecture.

### 4.2 W4-W11, W23, W24, W26, W27, W28 — arbitration

#### 4.2.1 W4 — why the `LsEuPlugin` mux, and not a new arbiter component

A standalone `Stream`-level arbiter (the `AxiDMerge` shape ported down a layer) was
evaluated first and **rejected**. It would have to own `dcache.loadCmd`/`dcache.store` and
re-export client-side copies, which means:

- every `dcache.*` reference in `LsEuPlugin` re-points at the arbiter (~12 sites), and the
  `DcacheService` provider question becomes ambiguous (two plugins implementing it, or a new
  service trait);
- `exc.dcLoadRsp` / `exc.dcStoreAck` at `FullCoreSynth.scala:350-353` and in every DUT
  re-point at the arbiter's demuxed outputs;
- the arbiter's `Stream` wrapper inserts a combinational `valid`/`ready` layer between
  `LsEuPlugin`'s registered command drive and `DcachePlugin`'s `loadCmdPort.ready`
  (`DcachePlugin.scala:981`) — a new logic level on a net the design has repeatedly been
  tuned to keep short (§5);
- it is strictly *more* new hardware for the same function.

The extension of `LsEuPlugin.scala:2020-2078` gets the same result with two 2-bit owner
registers, a depth-4 × 2-bit ownership FIFO (W7), a handful of small counters (W8's aging,
W26's wedge detector), and terms folded into conjunctions that already exist — all of it
inside a plugin that already owns every signal involved. And it is not a novel pattern: that
block *is* this codebase's established answer to
"more than one logical requester, one `DcacheService` port", and it already carries the
physical-only-client convention a walker needs (`:2029-2035`).

**`DcachePlugin.scala` is not modified at all by this design.** That is the single largest
risk reduction available and it is why W4 is worth stating as a decision rather than an
implementation detail.

The one thing the existing block must **not** be reused for is its *fairness model*: it is a
bare last-assignment-wins priority chain, safe today only because there are two sources and
the higher-priority one is short and self-bounding. Adding two more tiers naively reopens
exactly the two-directional starvation the scoping memo §4 flags. W8 is the answer.

#### 4.2.2 W5/W6 — owner shape

Two registers: `ldOwner` and `stOwner`, each `UInt(2 bits)` over `{CORE=0, ITLB=1, DTLB=2}`,
each with its own `busy`/`force`/round-robin state.

**Why independent per direction (W5).** *This justification was rewritten in the fix pass.*
The original draft borrowed socket spec **D9**'s deadlock argument (`refillWriteHold`: a load
refill defers accepting its R beat until a colliding same-set store drain's S1/S2 window
closes) and asserted that a single combined token would deadlock against it. **That argument
does not transfer to the `DcacheService` layer, and the spec must not claim it does.** At
this layer a walker never contends for `DcachePlugin`'s own AXI write channel — `DcachePlugin`
owns `axi` exclusively and no client of `DcacheService` can hold it. A combined read+write
token therefore blocks only **SQ-drain admission**, never descriptors already in flight: the
`EVICT_WR`/refill neighbourhood states this in its own words
(`DcachePlugin.scala:1250-1256`, the `refillWriteHold` commentary — "once a refill waits,
`refillNeedsStoreDrain` stops new store admission and the finite S0/S1/S2/S3 pipe drains").
A finite already-admitted pipe that drains autonomously is a **throughput** cost, not a
cycle.

**W5's conclusion is unchanged; only its reason is.** The real reason independent
per-direction owners are right here is the one the original draft filed as a secondary
"natural shape" remark, and it is sufficient on its own: a walker's read phase and its U/M
write phase are separated by **an entire ROB commit** — the descriptor reads happen at walk
time, the U/M byte drains at the triggering instruction's *retirement*
(`DtlbPlugin.scala:300-312`, `umq.io.commit`). A combined token would serialise two
operations that are already hundreds of cycles apart, blocking LS loads behind a store that
has not been issued yet and will not be for a long time, **for no correctness benefit
whatsoever**. That is a pure, avoidable throughput loss, and avoiding it costs nothing
structural: the two `DcacheService` ports are physically distinct with independent readiness
(`DcacheService.loadCmd`/`store` at `DcachePlugin.scala:83`, `:88`; `loadCmdPort.ready` at
`:981`, `storePort.ready` at `:1865`).

**Knock-on:** §4.2.5's W10 step 1 (the boundedness proof) originally leaned on the same
mis-transferred D9 framing; it is corrected there too.

**Why CORE is one owner (W6).** The ordinary LS pipe and the exception sequencer are already
temporally exclusive by construction and already muxed at `:2020-2078`, gated on the
exception's *per-port* valids so the SQ drain keeps the port on cycles the exception unit
does not want it. Splitting them into two arbiter owners would duplicate that reasoning for
zero benefit. `CORE` therefore means "whatever `:2020-2078` decides", unchanged.

#### 4.2.3 W7 — response identification: an ownership FIFO on loads, drain-to-zero on stores

`DLoadRsp` has no token field (`DcacheTypes.scala:67-71`) and `storeAck` is a bare ordered
`Bool` (`:123`), so a response carries no identity of its own and the arbiter must supply
one. **No field is added to either bundle** — that call stands, for the same reason
`AxiIds.scala:21-30` already declined V2a.2/V2a.3 ID-tagged routing one layer down. What
*changes* in the fix pass is **how** the identity is supplied on the load direction.

##### The problem the original W7 got wrong

The original W7 said: *the owner may only change when the outgoing owner has no accepted
command still owed a response on that direction.* Drain-to-zero, both directions. The
underlying observation that forced it is correct and remains the most consequential
correction to the scoping memo — the LS pipe is **not** single-outstanding:

- **Loads.** `LsEuPlugin.scala:703-720` maintains a **4-deep** in-flight aligned-load queue
  (`alignedDepth = 4`, `alignedSendPtr`/`alignedRspPtr`), and `DcachePlugin` itself accepts a
  second command behind a miss (`loadShadowCmd`, `loadCmdPort.ready`'s `!loadShadowValid`
  term at `:981`, capture at `:996-1002`). Multiple load responses can be owed at once,
  matched **positionally**.
- **Stores.** `storeOutstanding` is a 3-bit count with capacity S0+S1+S2+S3 = 4
  (`DcachePlugin.scala:619`, asserted `<= 4` at `:2086`).

But drain-to-zero **over-waits on the load side, badly**, and the review pinned why with two
facts the original draft did not surface:

1. `ldBusy(CORE)`'s `alignedCount =/= 0` term counts **enqueued** loads
   (`alignedCount` is bumped by `alignedEnq`, `LsEuPlugin.scala:1270-1272`), not
   accepted-but-unresponded ones. `DcachePlugin` itself accepts far fewer: at most ~2-3
   outstanding, because `loadCmdPort.ready` is gated on `!loadShadowValid`
   (`DcachePlugin.scala:981`).
2. Consequently, under **any** sustained load stream the aligned queue rarely empties, so
   drain-to-zero rarely becomes true on its own and W8's `force` path becomes the **common**
   case rather than the rare fallback. §4.2.4's "worst case adds `3 * 64 = 192` cycles"
   framing described the exception; it was in fact describing the rule.

##### What makes the FIFO possible: `DcachePlugin` completes loads strictly in order

Verified directly, not assumed — this is the load-bearing premise, so it is spelled out:

- `loadCmdPort.ready` is `False` outside the load FSM's `IDLE`, and gated by
  `!loadShadowValid` (`DcachePlugin.scala:981`) — at most **one** command is accepted behind
  an in-flight miss.
- The shadow-capture site's own doc comment (`:1014-1018`) states it exists precisely so
  that "**no untagged younger response can pass the older refill**", and the load-FSM
  commentary at `:440-450` says the younger request "must not enter the untagged response
  pipe ahead of the miss… re-launches it in order".
- The early-probe bypass cannot jump an older command either: `useEarlyProbe = earlyProbeHit
  && !ldS1Valid` (`DcachePlugin.scala:441`) — it is inert whenever an older command occupies
  S1.
- Every accepted command yields **exactly one** `loadRsp`.

Maximum simultaneously accepted-but-unresponded loads: **3**.

##### W7 (load direction) — a depth-4 ownership-tag FIFO inside the arbiter

Strict in-order completion means a **positional** tag is sufficient, and positional tagging
needs no bundle field at all:

```
ldOwnerFifo : depth-4 FIFO of the 2-bit ldRspTag         // lives entirely in LsEuPlugin
  push  <= ldPushTag           when dcache.loadCmd.fire  // W29 — see below
  pop                          when dcache.loadRsp.valid // deliberately UNQUALIFIED
  ldRspTag = ldOwnerFifo.head                            // tags THIS response (PRE-pop)
  rspOwner = (ldRspTag === CORE_LS || ldRspTag === CORE_EXC) ? CORE
           : (ldRspTag === ITLB) ? ITLB : DTLB           // DERIVED, not stored

  ldOwnerFifoEmpty / ldOwnerFifoHoldsWalker / ldOwnerFifoOccupancy
                                                         // all PRE-pop/PRE-push; see below
```

Depth 4 (one more than the proven maximum of 3) so the FIFO can never be the binding
constraint. **`DcacheService` is unchanged, `DcachePlugin` is unchanged, and no DUT is
rewired.**

##### W29 — what is pushed (round 4: this was the FIFO's own blind spot)

*Round-3's text specified the push as `grantedOwner`. That was a live bug, and W28 as
round 3 wrote it could not see it: W28 scoped itself to the owner state that qualification
terms **read**, and a push payload is a **write**. §12's round-3 self-check even recorded the
abstraction gap — its W28-vs-W7/W13/W23/W24/W27 bullet reads "it constrains *when* the owner
value is sampled, never *which* value is required" — without recognising it as reachable. It is reachable, in the ordinary way.*

**Why `grantedOwner` is the wrong source.** None of the mux legs is selected by an owner
comparison. They are selected by **admission predicates**, and only `walkerLoadAdmit(i)`
contains a grant term at all:

```
coreLsLoadAdmit    = coreLsLoadReq && !dcLoadHeldByOther            // no owner term
excLoadAdmit       = excActive && excLoadCmdValid && ldOwnerFifoEmpty   // no owner term (W30)
walkerLoadAdmit(i) = grant(i) && !ldBusyExc && !quiesceHold && !ldOwnerFifo.full
```

So a separately-updated `ldOwner`/`grantedOwner` register can be **stale relative to which
leg actually fired**. The worked failure — any exception raised right after a walker was
granted:

1. **Cycle N-1.** The walker arbiter's grant/`ldOwner` register lands on `ITLB` (the ITLB
   walker is requesting; no fire yet, because `loadCmdPort.ready` is low that cycle on
   `!loadShadowValid`).
2. **Cycle N.** An exception raises `excActive && excLoadCmdValid`. `ldBusyExc` goes true, so
   `walkerLoadAdmit(WALKER_ITLB_IDX)` goes **false**; the FIFO is still empty, so the exception is
   admitted and the **CORE-EXC** leg — the last driver in the last-assignment-wins chain —
   drives `dcache.loadCmd.valid`/payload. `dcache.loadCmd.fire` happens, and it is the
   exception sequencer's command.
3. **But the FIFO pushes `grantedOwner`, still `ITLB` from cycle N-1** — nothing updates that
   register off `excLoadAdmit` firing.
4. When the response arrives, `rspOwner === ITLB` routes a stacked-frame word / vector word /
   FRESTORE header to the **ITLB walker** as a page-table descriptor. A wrong translation is
   installed. No assertion fires.

The mirror case (grant on `CORE` at N-1, a walker actually firing at N) delivers a walker's
descriptor to `alignedRspFire` / the exception sequencer — which is **byte-for-byte round-1's
C1 failure**, arriving through the tagging mechanism built to prevent it.

**W28's round-3 directed check does not catch this**, and that is the point: it compares "the
payload presented" against "the owner qualified", and under both of W28's admissible
implementations those are the same admit-predicate-derived value compared against itself. The
FIFO push reads a **third** register the check never touches. W28 clause (b) (§4.2.8) closes
that by bringing the push payload inside the same invariant.

**W29 (decision) — the push payload is derived from the admission predicates themselves:**

```scala
// Four-valued tag. CORE_LS and CORE_EXC are ONE arbiter owner (W6) but TWO response
// identities — see W30 for why that distinction is load-bearing and not cosmetic.
object LdRspTag { val CORE_LS = 0; val CORE_EXC = 1; val ITLB = 2; val DTLB = 3 }

// Chain order MIRRORS the payload mux's own last-assignment-wins order (§4.2.7 `:757-764`,
// §4.2.4's base priority CORE-EXC > CORE-LS > walkers, so CORE-EXC is the LAST driver):
//   CORE-LS base  ->  walker legs  ->  CORE-EXC leg last.
val ldPushTag = UInt(2 bits)
ldPushTag := LdRspTag.CORE_LS                                            // base — CORE-LS leg
when(walkerLoadAdmit(WALKER_ITLB_IDX)) { ldPushTag := LdRspTag.ITLB }
when(walkerLoadAdmit(WALKER_DTLB_IDX)) { ldPushTag := LdRspTag.DTLB }
when(excLoadAdmit)                     { ldPushTag := LdRspTag.CORE_EXC } // LAST, as in the mux

when(dcache.loadCmd.fire) { ldOwnerFifo.push(ldPushTag) }
```

*Round 5 fixed two things in this snippet, both cosmetic-looking and neither cosmetic.*

- **The chain order was inverted relative to the mux it must agree with.** Round 4 wrote it
  `CORE_LS < CORE_EXC < ITLB < DTLB`, so under last-assignment-wins a walker leg would have
  overridden the exception leg — the **opposite** of the real payload mux, where the exception
  leg is the last driver (§4.2.7's `:757-764` row; §4.2.4's base priority). This was harmless
  *only* because property 2 below proves the four predicates pairwise exclusive, so at most one
  `when` is ever true and the order never resolves anything. But "correct because a separate
  proof happens to agree with it" is exactly the C-R4-1 shape: had that exclusivity ever been
  violated by a later edit, the tag would have mis-attributed in the **opposite direction** from
  the payload actually presented — the worst possible failure mode, and one the exclusivity
  assertion would catch only if it were enabled. Reordered so the tag is correct **by
  construction** and agrees with the mux under *any* number of simultaneously-true predicates.
- **The walker vector index and the walker tag value are given visibly distinct names.**
  `WALKER_ITLB_IDX`/`WALKER_DTLB_IDX` are **positional indices into the per-walker
  `walkerLoadAdmit` vector** (2 walkers, so `0`/`1`); `LdRspTag.ITLB`/`LdRspTag.DTLB` are **tag
  values** in the four-valued response encoding (`2`/`3`). They are different numbers and must
  never be used interchangeably. Round 4's snippet wrote `ITLB_ID`/`DTLB_ID` and §12's
  placeholder note called them "the existing owner-code constants", which conflates the vector
  position with the three-valued owner code with the four-valued tag; under the owner-code
  convention `{CORE=0, ITLB=1, DTLB=2}` the index and the tag are off by one from each other.
  Naming, not logic — but the plan must carry the distinct names.

Three properties make this correct, and each is a requirement on the plan, not an observation:

1. **Same predicates, same cycle, same expressions.** `ldPushTag` is a pure combinational
   function of the *identical* signal instances that gate the mux legs — not of any register
   updated alongside them. This holds under **both** of W28's admissible implementations
   (registered-grant and consistent-combinational), because in both cases the *predicates* are
   the selection mechanism and `ldPushTag` reads them directly. Under neither is a
   `grantedOwner` register permitted as the push source.
2. **The four predicates are pairwise exclusive, so the one-hot is well-defined.** Proved,
   not assumed:
   - `coreLsLoadAdmit` vs `excLoadAdmit`: `coreLsLoadAdmit` carries `!dcLoadHeldByOther` and
     `dcLoadHeldByOther = excActive || walkerOwnsLoad`, so it requires `!excActive`;
     `excLoadAdmit` requires `excActive`. Disjoint.
   - `coreLsLoadAdmit` vs `walkerLoadAdmit(i)`: `walkerLoadAdmit(i)` requires `grant(i)`,
     which implies `walkerOwnsLoad`, which implies `dcLoadHeldByOther`, which negates
     `coreLsLoadAdmit`. Disjoint under **either** W28 shape, because W28 forces `grant(i)`
     and `walkerOwnsLoad` to be the same-cycle reading of the same state.
   - `excLoadAdmit` vs `walkerLoadAdmit(i)`: `walkerLoadAdmit(i)` requires `!ldBusyExc`, and
     `ldBusyExc ⊇ (excActive && excLoadCmdValid)`, which `excLoadAdmit` requires. Disjoint.
   - `walkerLoadAdmit(WALKER_ITLB_IDX)` vs `walkerLoadAdmit(WALKER_DTLB_IDX)`: W9's round-robin
     grants one walker per direction per cycle. Disjoint.

   The plan must carry this as a `GenerationFlags.simulation` assertion in the same style as
   §4.3's, because the proof is what makes the `when`-chain above a one-hot select rather than
   a priority encoder:
   ```
   when(dcache.loadCmd.fire) {
     assert(CountOne(Cat(coreLsLoadAdmit, excLoadAdmit,
                         walkerLoadAdmit(WALKER_ITLB_IDX),
                         walkerLoadAdmit(WALKER_DTLB_IDX))) === 1,
            "load-admission predicates are not one-hot at loadCmd.fire", FAILURE)
   }
   ```
   *Round-6 correction: this was `... <= 1`, unqualified by `fire`. That form is strictly
   weaker in the direction that matters. `<= 1` is satisfied by **zero** predicates true, and
   "a `dcache.loadCmd.fire` happened with **no** admission predicate authorising it" is a real,
   reachable failure state, not a vacuous one — it is exactly what an **unqualified presentation
   header** produces (§4.2.7's W23 `:2027` row: a `when(excActive && excLoadCmdValid)` header
   that was never narrowed to `when(excLoadAdmit)` still drives `dcache.loadCmd.valid := True`
   as the mux's last driver, so with `loadCmd.ready` high the port fires while every admission
   predicate is false). Under that shape `ldPushTag` falls through to its `CORE_LS` base and
   pushes spurious `CORE_LS` entries behind a walker's genuine one, breaking both the FIFO's
   proven 3-entry bound and response routing. The **fire-qualified `=== 1`** form catches that
   on the first offending cycle. It is still a property `LsEuPlugin` holds by construction under
   a correct implementation — every one of the four predicates is `LsEuPlugin`'s own expression,
   and a correct mux presents `valid` only on a leg whose predicate is true — which is the
   [R5] standard the §4.3 rewrite established. It also makes W27's `:2027`/`:2067` rows a **live
   check** rather than a completeness listing.*
   *Round-5 note, still standing: the chain's reordering does **not** make this assertion
   optional. What the reordering buys is that if the proof were ever falsified, the chain would
   resolve the same way the payload mux does instead of the opposite way — a
   degraded-but-consistent outcome rather than a silent mis-tag. The assertion is still the
   thing that says so out loud.*
3. **`rspOwner` is derived from the tag, never stored beside it.** A second register holding
   "the owner of the head entry" would be a second source of truth and would reintroduce
   exactly the drift W29 exists to remove. **W6 is untouched:** the *arbiter* still has three
   owners and the LS pipe and exception sequencer are still one of them; `CORE_LS`/`CORE_EXC`
   is a sub-tag used only for **response identification inside `LsEuPlugin`**, which is a
   different question from arbitration and is the question W6 never addressed.

**Knock-on to W27's `rspOwner === CORE` rows.** Every CORE-**LS** response consumer
(`:720`, `:1520`, `:1541`, `:1574-1575`) takes `(ldRspTag === CORE_LS)`, which is **strictly
stronger** than `(rspOwner === CORE)` and is what the rows now say. Round 3's `rspOwner ===
CORE` form was sound only via a subtle secondary argument — that `alignedSent`/`aDone` happen
to exclude a CORE-EXC response from being mistaken for a CORE-LS one — which is precisely the
kind of load-bearing cross-section reasoning §4.2.7's `:2073` row already refuses to rely on.
The tag removes the reliance for one AND-term's cost on an already-registered control signal.

**Where the FIFO-full term lives — corrected in the round-3 fix pass.** The previous wording
said "`loadCmdPort.ready` is `False` when it is full". That is a **misattribution to the
protected file**: `loadCmdPort.ready` is driven entirely inside `DcachePlugin.scala:981`, the
file W4/§5/§8/§12 all require to receive **zero** edits, and it is the signal
`DcachePlugin.scala:759`'s own comment names at the head of the protected `rdSet` fan-in arc
("breaking the post-route critical path (`loadCmdPort.ready` → arbiter → tag/dataMem
read-address)"). Adding a FIFO-full term there is precisely the edit §5's entire FMax argument
exists to prevent. The condition belongs on **`LsEuPlugin`'s own side of the boundary**: it is
an *admission* term on `dcache.loadCmd.valid`, exactly where `walkerLoadAdmit` already carries
its analogous `!ldOwnerFifo.full` term below. `DcachePlugin` is never told the FIFO exists.
(By the ≤3 bound above the term can never actually fire; it is present as a structural
guarantee, not as a live constraint.)

Consequences:

- `CORE` and a walker may be **concurrently outstanding** on the load port. A walker
  descriptor read can be admitted into a live load stream without first stalling it to
  empty. This removes the systematic over-wait above and is the reason W8's `force` path
  reverts to being the rare fallback it was always described as.
- The store direction keeps **drain-to-zero unchanged**. It needs no FIFO: a WRITETHROUGH
  store is classified *serial* by `inputStoreSerial` (`DcachePlugin.scala:631-632`, true for
  any non-COPYBACK cacheMode), so a walker U/M store is already forced to be the sole
  accepted descriptor by `storePort.ready`'s `!serialStoreInFlight && (storeOutstanding ===
  0)` term (`:1865-1867`). The walker store side is single-outstanding **twice over**, and
  W13's latched-`stOwner` demux is all the identity the untagged `storeAck` needs.

##### W7 (the surviving drain-to-zero): the `CORE-EXC` boundary

**A walker and the exception sequencer may still never be concurrently outstanding on the
load port.** This is deliberately *not* folded into the FIFO, and it is what preserves W14.
*Round-4 extension (W30, below): the same exclusion holds against **`CORE-LS`** as well, so the
boundary is "`CORE-EXC` vs. everything", not "`CORE-EXC` vs. walkers". The reason is that W6
makes `CORE-LS` and `CORE-EXC` one owner, so a `CORE-LS` straggler is exactly as indistinguishable
to `ExceptionUnit`'s unqualified `dcLoadRsp` as a walker's descriptor would be — and, unlike the
walker case, that one is reachable at HEAD today (§11.1 item 9). Everything below is unchanged;
only the set of excluded clients grows.*

- `ExceptionUnit` samples `dcLoadRsp` in states entered *after* it issued a load
  (`:1470`, `:1532`, `:1544`, `:1556`, `:1569`, `:2224`). It has no `rspOwner` input and is
  not given one.
- Keeping `exc.dcLoadRsp` (`FullCoreSynth.scala:350-351`) wired straight from
  `DcacheService.loadRsp` means **every DUT that replicates that line stays untouched** —
  which was the whole strength of the original W14 argument, and is preserved exactly.
- The cost is nil in practice: exception-sequencer loads are already rare and already
  serialising, and `excActive` already suppresses the ordinary LS pipe wholesale.

Concretely, the admission rules are:

```
ldBusyExc          = excLoadOutstanding || (excActive && excLoadCmdValid)
walkerLoadAdmit(i) = grant(i) && !ldBusyExc && !quiesceHold && !ldOwnerFifo.full
excLoadAdmit       = excActive && excLoadCmdValid && ldOwnerFifoEmpty    // W30, round 4
                     // was: && !ldOwnerFifoHoldsWalker  (round 3) — strictly weaker

coreLsLoadAdmit    = coreLsLoadReq && !dcLoadHeldByOther     // see W27 rows :716 / :756
excStoreAdmit      = excActive && excStoreValid && (stOwner === CORE)
walkerStoreAdmit(i)= grantSt(i) && !quiesceHold && !stBusy(CORE)   // drain-to-zero survives

stBusy(CORE)       = coreStOutstanding =/= 0    // CORE store fires vs storeAcks
stBusy(ITLB/DTLB)  = walkStOutstanding(i)
```

The three named FIFO-state quantities, all read-only views of `ldOwnerFifo` and all living
inside `LsEuPlugin` (round 5 adds the third, which §4.3's second assertion used without ever
defining it):

```
ldOwnerFifoEmpty      = no entry is occupied
ldOwnerFifoHoldsWalker = OR over the occupied entries of "this entry is ITLB or DTLB"
ldOwnerFifoOccupancy   = the number of occupied entries (0..4)
```

**All three are read PRE-pop and PRE-push** — i.e. they are the FIFO's state *entering* the
cycle, before that cycle's `dcache.loadCmd.fire` push and before that cycle's
`dcache.loadRsp.valid` pop, including on a cycle where a push and a pop happen simultaneously.
Combinationally this means they are functions of the FIFO's registered state only, never of
`ldOwnerFifo.io.push/pop` in the same cycle — which is also the cheap implementation.
The pre-pop reading is **load-bearing for §4.3's second assertion** and must not be
"simplified" to a post-pop one: on the cycle the exception sequencer's own response arrives,
`excLoadOutstanding` is still `True` (W30's clear term takes effect at the *next* edge) and the
entry it names is still the FIFO's registered head, so `ldOwnerFifoOccupancy === 1 && ldRspTag
=== CORE_EXC` holds. Under a post-pop reading the occupancy would already read `0` on that same
cycle and the assertion would fire on correct behaviour. `ldRspTag` (`= ldOwnerFifo.head`) is
pre-pop for the identical reason — it is what tags *this* cycle's response.

None of the three is the same fact as `ldOwner =/= CORE`, and that distinction is what
W23's round-2 fix line got wrong (§4.2.7): `ldOwner` records who holds the *current grant*,
while the FIFO records what is *still in flight*. A walker command accepted one cycle and the
grant reverting to `CORE` the next leaves `ldOwner === CORE` **true** while a walker response
is still owed. Every predicate that must exclude an in-flight client therefore reads the FIFO,
never `ldOwner`.

**These predicates are the mux's own drive conditions, not commentary.** `excLoadAdmit` gates
`LsEuPlugin.scala:2027`'s `when(excActive && excLoadCmdValid)` header (which drives
`dcache.loadCmd.valid := True` at `:2028`) and `excStoreAdmit` gates `:2067`'s
`when(excActive && excStoreValid)` header; `coreLsLoadAdmit` gates the base drive at `:756`
including its split leg. W27's table (§4.2.7) binds each predicate to its exact site — the
round-2 text defined `excLoadAdmit` without ever saying which line it drives, which left the
exception sequencer presenting its command into a walker-occupied FIFO, a direct W7 violation.

##### W30 — `excLoadOutstanding`'s CLEAR term (round 4), and why the obvious mirror is a bug

*Round 3 specified this register's **set** term twice, with care, precisely because getting it
wrong reopens C1 (W23's second site). It never specified the **clear** term anywhere — in any
of the register's occurrences. That is not cosmetic: `excLoadOutstanding` is the load-side half
of `ldBusyExc`, which is the **entire** enforcement mechanism for the one surviving load-side
drain-to-zero rule, which is in turn the **entire** basis for W14's "no top-level rewiring;
`exc.dcLoadRsp` stays wired straight to `DcacheService.loadRsp` unqualified" argument. §4.3
asserted the equivalence ("`ExceptionUnit`'s `dcLoadRsp` is only sampled in states entered
after it issued a load … i.e. exactly when `excLoadOutstanding` is 1") without ever specifying
the mechanism that makes it true.*

**The document pointed a plan-writer at the wrong answer.** §4.2.3 calls this register "the
direct load-side mirror of the already-existing `excStoreOutstanding`", and the real store-side
clear is `LsEuPlugin.scala:2073`:

```scala
when(dcache.storeAck && excStoreOutstanding) { excStoreOutstanding := False }
```

so the obvious mirror is:

```scala
// DO NOT IMPLEMENT — a new instance of exactly the defect class W27 exists to eliminate.
when(dcache.loadRsp.valid && excLoadOutstanding) { excLoadOutstanding := False }
```

That is a **bare, unqualified `dcache.loadRsp.valid` consumer**. It could not appear in W27's
round-3 table because the register — and therefore this site — did not exist yet when the table
was written, which is precisely why W27 now carries rows for this design's *own* new logic
(§4.2.7, round-4 additions).

**Why the mirror is unsound: the store-side invariant it borrows has no load-side twin.**
Investigated directly against the RTL rather than assumed:

- **Store side — the invariant is real and is what makes `:2073` safe.** `ExceptionUnit`'s
  entry and RTE paths both park in a drain state that waits on `sqDrained`
  (`ExceptionUnit.scala:1389-1390` `E_DRAIN`, `:1519-1520` `R_DRAIN`; `S_DRAIN` at `:1382-1383`
  waits on `sqDrained && dcQuiesced`), and `sqDrained` is `lsEu.sqEmptySig`
  (`FullCoreSynth.scala:417`) = `sq.io.empty` (`LsEuPlugin.scala:272`) =
  `!valids.reduce(_||_) && !drainBusy` (`StoreQueue.scala:576`) with
  `drainBusy = acceptedHalves =/= 0` (`:211`). **`sqDrained` therefore means literally "zero
  CORE-LS store halves accepted-but-unacked".** The exception sequencer cannot issue a store
  until that holds, so no stale SQ `storeAck` can be owed when `excStoreOutstanding` is set.
  `LsEuPlugin.scala:2023-2026`'s own comment states this ("the exception is serializing, so any
  older LS store has already committed/drained by the time the exc stores"), and `:266-267`'s
  comment states the residual it does *not* cover ("`excActive` is already high while `E_DRAIN`
  still lets ordinary SQ stores finish").
- **Load side — there is no analogue, and the naive invariant is FALSE.** No drain state waits
  on any load-side condition. `E_DRAIN`/`R_DRAIN` wait on `sqDrained` only, and `sqDrained` is
  purely a `StoreQueue` fact. On the LS side, `alignedSendValid` (`LsEuPlugin.scala:716`)
  carries `!excActive` so no *new* CORE-LS load is **presented** once `excActive` rises — but
  `alignedRspValid` (`:718-719`) deliberately carries **no** `excActive` term, because
  already-accepted loads must still be **consumed**. In-flight CORE-LS load responses therefore
  genuinely outlive the rise of `excActive`.

**The concrete failure, traced end to end** (no walker required — this is a `CORE-LS` ↔
`CORE-EXC` failure, which is why `rspOwner` cannot see it):

1. An ordinary CORE-LS load is accepted by `DcachePlugin` and **misses**. The load FSM leaves
   `IDLE` (`DcachePlugin.scala`, the `IDLE`/`REFILL`/`REPLAY` machine), so `loadCmdPort.ready`
   is low for the refill's duration.
2. An RTE retires. `excActive` rises; `R_DRAIN` passes immediately (`sqDrained` is true — no
   stores pending); `R_SRREQ` presents a load. `dcLoadCmd.valid` is a **registered** output
   (`ExceptionUnit.scala:798-803`, `ldoValidReg`, held until fire), so the exception's request
   simply waits, asserted, for the whole refill.
3. `REPLAY`'s ordinary arm re-launches the filled line's read (`ldS1Valid := True`) and
   `goto(IDLE)` **in the same cycle**. So on the next cycle, call it `R`, the FSM is in `IDLE`
   with `loadCmdPort.ready` high **while the replayed CORE-LS read is still in S1** — its
   response lands at `R+1`.
4. At `R`, `excLoadAdmit` holds (under the round-3 predicate: `excActive`, `excLoadCmdValid`,
   and no *walker* in the FIFO) and the exception's command **fires**.
   `excLoadOutstanding := True`, visible at `R+1`.
5. At `R+1` the **CORE-LS** load's response arrives. Under the naive mirror,
   `excLoadOutstanding` clears on someone else's response. `ldBusyExc` drops.
   `walkerLoadAdmit` opens while the exception sequencer's own load is genuinely still in
   flight. The walker is admitted, its descriptor response arrives, and `ExceptionUnit`'s
   `dcLoadRsp` — wired straight through, **unqualified, by W14's own decision** — consumes it
   as the exception sequencer's answer. **That is C1, through a different door.**

**And `rspOwner` cannot save this one.** W6 makes the LS pipe and the exception sequencer one
owner (`CORE`) because they are temporally exclusive at **presentation** time, which `excActive`
does enforce. `rspOwner === CORE` is therefore true for *both* an ordinary LS response and an
exception-sequencer response: the three-valued owner is structurally unable to tell them apart.
W6's justification was never about **in-flight-response** exclusivity, and that is exactly what
this clear term needs.

**W30 (decision), in two parts. Both are required; neither alone closes the failure.**

**(1) The clear term is tagged, not mirrored:**

```scala
when(dcache.loadRsp.valid && (ldRspTag === LdRspTag.CORE_EXC)) {
  excLoadOutstanding := False
}
```

This is sound **directly**, with no cross-section invariant behind it, and the proof is short
because W29 did the work: the FIFO holds exactly one entry per accepted `loadCmd`
(`push` on `dcache.loadCmd.fire`), `DcachePlugin` completes loads **strictly in order** and
emits **exactly one** `loadRsp` per accepted command (proved earlier in this section), and the
FIFO pops one entry per `loadRsp`. Therefore the head tag at any `loadRsp` names *the very
command this response answers*. Since the tag `CORE_EXC` is pushed **iff** `excLoadAdmit` gated
the fire, and `excLoadOutstanding` is **set** by that same `excLoadAdmit`-gated fire (W23), set
and clear reference the identical transaction, one response apart. A CORE-LS straggler carries
`CORE_LS` and cannot clear it. A walker response carries `ITLB`/`DTLB` and cannot clear it.

**(2) `excLoadAdmit`'s FIFO term is strengthened to `ldOwnerFifoEmpty`.** Part (1) fixes the
register; it does **not** fix step 5's other half — `ExceptionUnit`'s own `dcLoadRsp` sampling,
which W14 requires to stay **unqualified** and which lives at the top level
(`FullCoreSynth.scala:350-351`), outside `LsEuPlugin`'s reach. In the step-1-to-4 trace above,
the exception FSM sits in `R_SRWAIT` at `R+1` and samples `dcLoadRsp.valid` — and gets the
**CORE-LS** straggler's data as `popSr`. Qualifying it would mean re-wiring 8 DUTs and
reopening W14. Making the boundary genuinely drain-to-zero costs one term:

```
excLoadAdmit = excActive && excLoadCmdValid && ldOwnerFifoEmpty
```

i.e. **the exception sequencer's load is admitted only when nothing at all is in flight on the
load port** — not merely "no walker". Consequences, each checked:

- **It restores W14's argument to actually-true.** §4.3's claim that `ExceptionUnit`'s
  `dcLoadRsp` sampling window coincides with `excLoadOutstanding === 1` now holds against every
  client, because no other client's response can be outstanding when the exception's command is
  admitted, and no other client can be admitted afterwards (`coreLsLoadAdmit` carries
  `!excActive`; `walkerLoadAdmit` carries `!ldBusyExc`).
- **It is strictly stronger than round 3's C2 predicate**, so W23/C2's closure is preserved a
  fortiori: `ldOwnerFifoEmpty ⟹ !ldOwnerFifoHoldsWalker`. Every sentence round 3 wrote about
  the round-2 line remains true; §4.2.7's W23 fix line (`&& excLoadAdmit`) is unchanged in form
  and only becomes harder to satisfy.
- **W7's FIFO keeps its point.** The latency win W7 exists for is `CORE-LS` ↔ **walker**
  concurrency, which is untouched. Only the exception sequencer — already rare, already
  serialising, and already single-outstanding by its own `REQ → WAIT → REQ` FSM shape
  (`ExceptionUnit.scala:1526-1570`) — waits for the port to empty.
- **Bounded, and not a deadlock.** Every accepted command yields exactly one response and
  `DcachePlugin` makes autonomous progress (a refill terminates; a bus fault still produces a
  response via `busFaultResp`), so `ldOwnerFifoEmpty` becomes true within one refill. Nothing
  in the FIFO waits on the exception sequencer, so there is no cycle. The exception cannot be
  starved either: `excLoadCmdValid` is **held** until fire (`ldoValidReg`,
  `ExceptionUnit.scala:798-803`), so `ldBusyExc` stays true from the moment it is presented and
  no new walker can enter behind it.
- **Cost.** One extra AND term on a low-fanout control signal, on the same register-sourced
  arc §5 already prices; the exception sequencer's first load can wait up to one refill. §5's
  accounting is unchanged (no new BRAM-address fan-in).

**Recorded as a correction, not a new hazard:** the step-1-to-4 trace above is reachable at
**current HEAD**, before any of this design's changes — it needs only an in-flight CORE-LS load
miss at exception entry, and there is no walker in it. §11.1 item 9 records it. This design must
therefore not *inherit* the assumption; W30 part (2) closes it as a side effect, and §10.2's
directed test is written so it can be demonstrated failing at HEAD.

`excLoadOutstanding` is a new 1-bit register in `LsEuPlugin`, the load-side counterpart of
the **already existing** `excStoreOutstanding` (`LsEuPlugin.scala:268`) — *counterpart, not
mirror*: see **W23** (§4.2.7) for its set term, which must carry `excLoadAdmit`, and **W30**
above for its clear term, which must carry W29's tag. `coreStOutstanding`
likewise mirrors `excStoreOutstanding`, widened to a count, and — because W6 makes the LS pipe
and the exception sequencer **one** owner — it must count the exception sequencer's accepted
stores **as well as** the SQ drain's. §4.2.7's `:2073` row depends on that.

Walker-side outstanding stays 1 bit each: `TableWalker`'s FSM never issues a second read
before the first responds (`arSent`/`issueRead()`, `:104-120` — the same serialisation,
retargeted), and the U/M drain is single-outstanding by its own `drainAwDone && drainWDone`
gate (`DtlbPlugin.scala:334`).

##### Two caveats this design owes explicitly, and does not gloss

**(a) Core-side response consumers that were previously inert now need owner qualification.**
Under drain-to-zero, `CORE`'s consumers were guaranteed idle whenever a walker response
arrived. Under the FIFO they are **not**, so they must be qualified — *round 4: by
`ldRspTag === CORE_LS`, W29's four-valued tag, not by the three-valued `rspOwner === CORE` the
round-3 text used; see W29's "knock-on" paragraph for why the weaker form was sound only via a
secondary `alignedSent`/`aDone` argument this document should not be resting on.*
Two representative sites:

- `alignedRspFire = alignedRspValid && dcache.loadRsp.valid` (`LsEuPlugin.scala:720`) becomes
  `alignedRspValid && dcache.loadRsp.valid && (ldRspTag === CORE_LS)`. This is not cosmetic:
  `alignedRspFire` decrements `alignedCount` and advances `alignedRspPtr`, so an
  unqualified version would pop a core load off the aligned queue on the walker's descriptor
  response and hand a page-table word to an architectural register.
- The **split BK FSM**'s `WAIT_A`/`WAIT_B` sampling of `dcache.loadRsp` (`:1520`, `:1541`)
  takes the identical qualification, for the identical reason.

**These are examples, not the list.** *Round-3 correction: the round-2 wording said "these two
sites", which a plan-writer reading it literally would take as complete — and it is not.
`bkCompletes` (`:1574-1575`) is a third `loadRsp` consumer, deliberately defined **outside**
both `whenIsActive` bodies (see its own comment, "Derived from `bkFsm.isActive` rather than
from an assignment inside either FSM"), so an editor touching only the two FSM bodies misses
it entirely — and an unqualified `bkCompletes` releases `S1`/`backCompFires` on a walker's
mid-flight response.* **The complete, exhaustive list is W27's table in §4.2.7**, which is the
authority; this caveat exists only to explain *why* the qualification is needed. Owner
qualification is a **required part of W7**, not an optimisation, and the implementation plan
must carry W27's table verbatim in its `LsEuPlugin` change list (§8).

**(b) The latency win is real but it is not "a free slot".** `loadCmdPort.ready` also
requires `(!earlyProbeTokenPresent || earlyProbeOwnsCmd)` (`DcachePlugin.scala:983`), so a
walker command still cannot interleave into a stream with live probe tokens unless W11's
`probeCancelAll` extension is in place. That cost is paid under the original drain-to-zero
W7 **too** — W11 is mandatory either way (§4.2.6) — so the FIFO is a **latency** improvement
over what it replaces, not unconditionally free, and not better on every axis. *Round-3
correction: the round-2 text said "strictly better", which the same paragraph's own honest
framing ("paid, not eliminated") contradicts.* Concretely, the trade is:

- **Latency genuinely improves.** A walker no longer waits for a live load stream to empty.
- **Probe-destruction frequency genuinely rises.** Under drain-to-zero a walker was only ever
  admitted into a **quiet** port, so `probeCancelAll` had little or nothing resident to
  destroy and the cost was rare. Under the FIFO the walker interleaves into a **live** stream,
  so each of a walk's up to three descriptor reads can individually trigger `probeCancelAll`
  and destroy up to 4 resident early probes. That is a real, repeated IPC cost on the LS pipe,
  paid to buy the latency, and the synth/IPC evidence in §10.3 is what settles whether the
  trade is net positive on real workloads.

W25 (§4.5) closes the second half of the same question: the walker's token must be a
*reserved* value, not a don't-care, or it can match a resident probe entry by accident.

#### 4.2.4 W8/W9 — priority and the fairness primitive

```
                          ┌──────────────────────────────────────┐
   base priority:  CORE-EXC  >  CORE-LS  >  {ITLB, DTLB} (rr)
   forced:         CORE-EXC  >  forced walker  >  CORE-LS  >  other walker
```

Per direction, per walker `i`:

```
age(i) := 0                       when granted, or when !req(i)
age(i) := age(i) + 1              when req(i) && !granted && age(i) =/= LIMIT
force(i) := (age(i) === LIMIT)
```

`force(i)` does **not** yank the port mid-transaction. It only changes the *next* grant
decision.

**What `force` means, per direction — rewritten in the round-3 fix pass.** *The round-2 text
said `force` "still obeys W7 (drain to zero first)" and meant "stop admitting new `CORE-LS`
commands and take the port the moment it drains". That was the pre-I4 model and it directly
contradicts §4.2.3's current admission rules, which have **no drain term on the load
direction at all** — eliminating exactly that wait is the whole point of the ownership FIFO.
§12's fix-pass sweep touched the "pressure" paragraph below but not this one.* The current
semantics:

- **Load direction — no drain, ever.** `force(i)` raises walker `i` above `CORE-LS` in the
  grant priority for the *next* grant cycle. Admission is then exactly
  `walkerLoadAdmit(i) = grant(i) && !ldBusyExc && !quiesceHold && !ldOwnerFifo.full`
  (§4.2.3) — a walker command may be accepted with `CORE-LS` loads still outstanding, which is
  precisely what the FIFO makes identifiable. `CORE-LS` is not held to zero, is not drained,
  and loses only the *next* command slot. The only load-side wait `force` can encounter is
  `!ldBusyExc` — the surviving `CORE-EXC` boundary — and that is a `CORE-EXC` fact,
  not a `CORE-LS` drain.
- **Store direction — drain-to-zero survives, unchanged.** W7 keeps it there, so on stores
  `force` does mean "stop admitting new `CORE` stores and take the port once
  `stBusy(CORE)` reaches zero". The store direction is single-outstanding twice over
  (§4.2.3), so this drain is short and bounded by at most 4 accepted store descriptors.

**`WALKER_AGE_LIMIT` is unaffected by this correction and stays at 64 in this spec.** No
finite `LIMIT` value can break §4.2.5's starvation bound — the bound is stated *in terms of*
`LIMIT`, and W26's wedge bound likewise — so 64 is SAFE, merely possibly non-optimal. The
constant was never the defect here; the stale paragraph above was. (The non-blocking
re-derivation step below still stands, unchanged: it is a *tuning* step, not a correctness
one.)

`force` never outranks `CORE-EXC`'s **presented** command, but — critically — it does take
the port on cycles the exception sequencer is not presenting one. That is required, not
optional: since Task 11 the exception sequencer performs genuinely virtual FSAVE/FRESTORE
accesses through the DTLB (`LsEuPlugin.scala:2100-2107`), so a DTLB miss *raised by the
exception sequencer itself* must be able to run its walk while `excActive` is high. A blanket
"walkers may not run during `excActive`" rule would deadlock. (§6.2 handles the one
sub-phase where walker admission genuinely must close.)

`WALKER_AGE_LIMIT = 64`. Rationale: a hand-over costs one `probeCancelAll` (W11) plus the
refill of the aligned-load pipeline, so preempting on a short burst is pure loss; 64 cycles
is comfortably longer than any ordinary L1D-hit burst and far shorter than the 3-dependent-
miss latency of the walk it unblocks. Parameterised on the plugin constructor **only** so
directed tests can use a value like 4 — production is the constant. This mirrors
`AxiDMerge`'s `grantTimeout` parameterisation rationale verbatim (`AxiDMerge.scala:88-96`).

**Fix-pass knock-on from W7's ownership FIFO.** The aging counter **stays** — it is still
needed, because the FIFO does not make a walker command *admissible*, only *identifiable*.
A walker can still be refused admission by the probe-token gate (`(!earlyProbeTokenPresent ||
earlyProbeOwnsCmd)`, `DcachePlugin.scala:983`), by `!loadShadowValid`, by `!maintBusyReg`
(N4), or by a full FIFO. What changes is the **pressure**: under drain-to-zero the aging
counter was, per the review, the common path to a grant under any sustained load stream;
under the FIFO it is the rare fallback the original text always described it as. The old
"worst case `3 * 64 = 192` cycles per walk" figure is therefore an over-estimate of the
typical cost and is retained only as a bound.

Because the pressure drops sharply, `WALKER_AGE_LIMIT` **could likely be smaller** than 64.
This spec deliberately does **not** name a new number: the 64 was derived against the
drain-to-zero cost model, and picking a replacement without re-deriving it against the FIFO
model would just be a different unjustified constant. The implementation plan carries an
explicit, non-blocking step to re-derive it (or to record that 64 is retained on purpose)
once `WalkerDcachePortArbSpec` can measure the real grant latency distribution.

W9: a 1-bit `walkRr` per direction, flipped on each walker grant, breaks ITLB-vs-DTLB ties.
With two requesters, round-robin and "the one that didn't go last" are the same thing, so
this is one flop, not a rotation base.

#### 4.2.5 W10/W26 — no bounded-grant *timer*, but a production *observability* counter

The socket spec's **D20** built a bounded-grant watchdog because merging four AXI owners
created a new wedge mode: *an owner that never completes starves the others*. That specific
mode does not recur here, and the difference is structural, not optimistic:

1. **Every grant is bounded.** A walker's grant covers exactly one descriptor read or one
   U/M store. A `CORE` load grant is no longer bounded by drain-to-zero at all under W7's
   FIFO — it is bounded by `DcachePlugin`'s own ≤3 accepted-and-unresponded loads, each of
   which completes in bounded time (an L1D hit in 2 cycles; a miss in one AXI round trip,
   whose boundedness is the fabric's obligation per socket spec **D19**, not this
   arbiter's). The store direction is bounded by its surviving drain-to-zero, at most 4
   store descriptors. *(Fix-pass correction: the original step 1 also leaned on the
   mis-transferred D9 framing corrected in §4.2.2. The boundedness above does not depend on
   D9 in any form.)*
2. **Requests are never withdrawn-and-reasserted in a way that resets the age.** `age(i)`
   resets only on *grant* or on the request genuinely going away.
3. **Therefore** any pending walker request reaches `age = LIMIT` within `LIMIT` cycles,
   after which it outranks `CORE-LS` and is granted the next command slot on that direction.
   Starvation bound: `LIMIT + drain`, finite — where, per §4.2.4's corrected `force`
   semantics, `drain` is **zero on the load direction** (the FIFO removed that wait; the only
   residual load-side wait is the `!ldBusyExc` boundary, itself bounded by the exception
   sequencer's own bounded access) and is at most 4 store descriptors on the store direction,
   where drain-to-zero survives.
4. **The reverse direction is bounded too.** A forced walker takes exactly one command's
   worth of port, then `age(i)` resets and base priority restores `CORE-LS` above it. A
   walker cannot chain-hold: its next request is a *new* request that starts ageing from 0.

A **timer** would fire only on a wedge *inside* `DcachePlugin` or the fabric — cases already
owned by D19/D20 one layer down, where a duplicate would be the third repetition of the
mistake `AxiDMerge.scala:9-21` documents. That part of W10 stands.

##### W26 — the observability gap W10 originally left open, and why it must be closed

*Added in the fix pass; the original W10 stopped at "sim assertion, no watchdog" and this is
a real hole.* The proof above covers starvation *given a correct arbiter*. It does not cover
a **wedge at the new merge point itself** — and §4.2.6 is direct evidence that this design
can contain exactly that class of bug, since the early-probe deadlock is one and was found
only by tracing. Such a wedge produces:

- **no AXI grant to time out** — D20's watchdog is one layer down and never sees it;
- **no abandoned transaction** — D19's terminate-on-any-response invariant is not violated,
  because no transaction was ever issued;
- **no halt reason** — D28's kind-coded halt-reason channel, which this project built
  specifically so that hangs are *attributable* rather than silent, is never driven.

The result is a hang that is **less observable than the mechanism it displaces**. That is
the wrong direction of travel, and the socket spec's own stated posture makes the point:
*"an arbiter cannot construct a truthful completion on behalf of its owner"* — D20 answers
that by reporting, not by fabricating. Here the report is being replaced by a *proof*, and
if the proof has a hole there is nothing left.

**W26 (decision): build option (a) — a cheap PRODUCTION counter, reusing D28's existing
halt-reason channel, scoped to this arbitration point.** Shape:

- One counter per direction, incremented while `req(i)` is high with no grant and no
  `loadCmd`/`store` fire on that direction at all; cleared on any fire or grant.
- On reaching a bound it latches the sticky `coreHalted` state with a **new, distinct
  D28 halt-reason kind** (`WALKER_PORT_WEDGE`), exactly as D15 and D20 already do
  (socket spec `:1184`). It **never** fabricates a `loadRsp` or a `storeAck` — that is the
  D20 posture preserved verbatim.
- The bound is *not* D20's 2e9. This counter is a structural-wedge detector on an on-chip
  arbiter, not an off-chip fabric timeout: its expiry can only mean an arbiter bug, so it is
  sized generously against `LIMIT + drain + maintenance walk` (N4's 512 iterations plus
  writeback beats dominates) and the plan derives the constant explicitly rather than
  inheriting one.

Cost: two counters and a comparator, in a `LsEuPlugin` area that is already registered and
off the protected `rdSet`/`rdEn` net (§5). This is the *only* new production state W10's
proof does not already justify, and it buys the difference between a silent hang and an
attributable one.

The `GenerationFlags.simulation` assertion the original W10 proposed is **also** kept — that
no walker request stays un-granted for more than `LIMIT + K` cycles (`K` sized from the drain
bound), in the style of `DcachePlugin.scala:2073-2087`'s existing assertion block. It fires
far earlier and far more precisely than W26's counter, which is the backstop for silicon.

#### 4.2.6 W11 — the early-probe deadlock, and the mandatory fix

**This is a real deadlock that a naive implementation of this design walks straight into,
and it is not in the scoping memo.**

`DcachePlugin.scala:981` gates load-command admission on
`(!earlyProbeTokenPresent || earlyProbeOwnsCmd)`: a resident early-VIPT probe token blocks
any load command that does not own it. Now suppose the mux hands the load port to a walker:

1. The mux deasserts `CORE`'s `loadCmd.valid`.
2. `loadProbePort.ready` (`:945-951`) is gated on `(!loadCmdPort.valid || useEarlyProbe)` —
   with `CORE`'s `loadCmd.valid` now low, that term is **satisfied**, so the LS EU keeps
   launching probes.
3. Probe slots fill (`earlyProbeDepth = 4`). `earlyProbeTokenPresent` is now high with four
   tokens whose matching load commands the mux will never let through.
4. The walker's `loadCmd` carries a token matching none of them → `earlyProbeOwnsCmd` is
   false → `loadCmdPort.ready` is **false forever**. Neither side can advance.

The fix reuses machinery that already exists for exactly this hand-over:

- `LsEuPlugin.scala:863` already computes `probeCancelAll = sqFlushSig || excActive` — i.e.
  the design *already* cancels every resident probe when the port is handed to the exception
  sequencer. It becomes `sqFlushSig || excActive || walkerOwnsLoad`.
- `LsEuPlugin.scala:1774-1776`'s `normalReqArm`/`splitReqArm` already contain `!excActive`,
  and that is what suppresses new probe launches (`probeWanted = normalReqArm && tIsLoad`,
  `:861`). The `!excActive` term becomes `!dcLoadHeldByOther` where
  `dcLoadHeldByOther = excActive || walkerOwnsLoad`.

Cancelling a probe is functionally free: `DcacheTypes.scala:12-18` and `:36-45` state that an
unusable/absent probe qualification simply makes "the later resolved `DLoadCmd` use the
ordinary path". It is a performance event, not a correctness one. And
`DcachePlugin.scala:1973-1983`'s own comment ("Their explicit cancel releases the matching
tokenized entry **and lets maintenance quiesce**") confirms the same mechanism is already
load-bearing for the analogous quiesce problem.

Note the happy coincidence that makes this cheap in the common case: while a **DTLB** walk is
in flight, `DtlbPlugin.scala:154` holds `_req.ready` low (`!missPending`), so
`dcache.loadProbe.valid := probeWanted && xlate.req.ready` (`:869`) is already false. The
deadlock is only genuinely reachable via the **ITLB** walker, which leaves the DTLB free.
The fix is written to cover both regardless — relying on that asymmetry would be exactly the
kind of implicit invariant this codebase's review history keeps catching.

#### 4.2.7 W23/W24/W27 — the ownerless-handshake defect class, swept exhaustively

*W23/W24 were added in the round-2 fix pass as CRITICAL findings. Round 3 found two things
about that work: they named **2** sites where an exhaustive sweep of the same file finds
**10 more** that need a fix, and **W23's own prescribed fix line reopened the very defect it
closed**, in a different timing window. W27 is round 3's answer to the first: the sweep is
done once, completely, as a table, and the table — not any individual finding — is the unit
of correctness from here on. The corrected W23 below is the answer to the second.*

**The defect class, stated precisely.** A `valid`, `ready` or `fire` reference on a
`DcacheService` port that is correct today **only** because nothing but its own client can
ever own that port. Every such reference was written when the port had one (later two)
clients whose mutual exclusion was proven elsewhere. This design adds a third and fourth
client and dissolves that proof. Every one of these failures is **silent**: no assertion
fires, no hang, no bus error — a descriptor word simply lands somewhere architectural, or a
core store simply disappears.

**Why a table and not a list of findings.** Round 2 patched the two sites a review named and
shipped. Two rounds of review then produced two more rounds of the same class. The sites are
not conceptually hard — they are *numerous and scattered*, spread across a base drive, two
FSM bodies, a signal deliberately defined outside both of them, and a 90-line exception
override block at the far end of the file. The only reliable closure for that shape is
enumeration, so §4.2.7 enumerates. **A plan-writer must re-derive the table against the
then-current file (the line numbers are locators, the *sites* are the decision) and must not
add a `dcache.*` reference to `LsEuPlugin` without adding a row.**

##### W23 — `excLoadCmdReady` and `excLoadOutstanding` (`LsEuPlugin.scala:2115`)

```scala
excLoadCmdReady := dcache.loadCmd.ready        // LsEuPlugin.scala:2115 — UNCONDITIONAL
```

Once W7 lets a walker own the load port, `dcache.loadCmd.ready` may be `True` for a
**walker's** command while the exception sequencer is presenting its own. The exception
sequencer sees its `ready` and believes its command was accepted. It advances its FSM —
`F_HDRREQ → F_HDRWAIT` on FRESTORE, `E_VECREQ → E_VECWAIT` on a vector fetch, or any of the
`R_*` RTE frame-pop states — and then consumes the **next** `loadRsp`, which is the walker's
page-table descriptor. A descriptor word is written into a vector address, a stacked SR, or
a restored FPU frame field. There is no error path; the core simply runs on with corrupt
architectural state.

**Fix — corrected in the round-3 fix pass. The round-2 fix line was wrong and reopened this
exact bug in a different window:**

```scala
// ROUND-2 TEXT — DO NOT IMPLEMENT. Short one term; see below.
excLoadCmdReady := dcache.loadCmd.ready && (ldOwner === CORE) && excActive && excLoadCmdValid
```

```scala
// CORRECT (W23):
excLoadCmdReady := dcache.loadCmd.ready && excLoadAdmit        // §4.2.3
```

**Why the round-2 line is a bug.** §4.2.3's load-admission rule for the exception path is
**strictly stronger** than "the owner is `CORE`":
`excLoadAdmit = excActive && excLoadCmdValid && !ldOwnerFifoHoldsWalker` *(round-3 form;
**W30 strengthens the FIFO term to `ldOwnerFifoEmpty`** — see §4.2.3. The round-3 form is
retained verbatim in this argument because it is the **weakest** predicate that closes C2, and
showing the fix works at that strength shows it works a fortiori at W30's. Nothing below
changes under the stronger term.)*. W7 keeps
drain-to-zero at the walker ↔ `CORE-EXC` boundary specifically, so while **any** walker entry
sits in the ownership FIFO the arbiter must *withhold* the exception sequencer's command and
`dcache.loadCmd.valid` is deliberately held **low**. And `dcache.loadCmd.ready`
(`DcachePlugin.scala:981`) **does not reference `valid` at all** — `DcachePlugin` asserts it
on its own schedule, independent of whether anything is being presented.

So in exactly the window the design most needs protected — a walker response outstanding, the
exception command correctly withheld — all of `dcache.loadCmd.ready`, `(ldOwner === CORE)`
(nothing walker-owned holds the *current grant*; the walker's claim is in the FIFO, not in
`ldOwner` — see §4.2.3), `excActive` and `excLoadCmdValid` are simultaneously **true**. The
round-2 line pulses `excLoadCmdReady` **with no command issued**. The exception sequencer's
FSM advances (`E_VECREQ → E_VECWAIT`, `F_HDRREQ → F_HDRWAIT`, any `R_*` RTE-pop state)
believing its command fired; `excLoadCmdValid` drops; `ldBusyExc` drops; the walker is
unblocked; and the exception sequencer, now waiting on `dcLoadRsp.valid`, consumes the
**walker's** response. That is byte-for-byte the round-1 C1 failure — a page-table descriptor
word landing in a vector fetch, a stacked SR, or a restored FPU frame — arriving *through* the
fix meant to close it. It is reachable in the ordinary way: any exception raised while an
ITLB/DTLB walk is already outstanding.

**The rationale W23 states must change with it.** Round 2 said "the exception sequencer must
additionally be the one **presenting**". That is insufficient: presentation can be *correctly
withheld* while `ready` is high for unrelated reasons. The rule is that the exception
sequencer must be the one **ADMITTED** — which is what `excLoadAdmit` names, and why the fix
reuses that predicate rather than re-deriving a weaker proxy for it. Equivalently and
preferably, derive `excLoadCmdReady` from the **actual fire condition of the exception leg of
the mux** rather than from any re-derivation at all; whichever form the implementation picks,
it must be **at least as strong as `excLoadAdmit`**.

**The same bug, second site.** `excLoadOutstanding`'s set term as specified in §4.2.3 —
`dcache.loadCmd.fire && excActive && excLoadCmdValid` — carries the **identical** unqualified
`fire`: `dcache.loadCmd.fire` is `valid && ready`, and under a walker grant that `fire` is
the walker's. Setting `excLoadOutstanding` on a walker's fire corrupts `ldBusyExc`, which
is **W7's own input** — so the bug feeds straight back into the hand-over rule that is
supposed to prevent it. Fix with the same predicate, for the same reason:

```scala
when(dcache.loadCmd.fire && excLoadAdmit) {
  excLoadOutstanding := True
}
```

**And the presentation site itself.** *Round-3 addition.* Nothing in the round-2 text ever
bound `excLoadAdmit` to a line of RTL — it was defined in §4.2.3 and then never used. The
withholding C2's argument assumes therefore did not exist: `LsEuPlugin.scala:2027`'s
`when(excActive && excLoadCmdValid)` header drives `dcache.loadCmd.valid := True` at `:2028`
with **no** admission term, so the exception sequencer would present its command straight into
a walker-occupied FIFO — a direct W7 violation, mis-delivering a descriptor in silicon.
*(Round-5 correction to this sentence, **amended in round 6**: round 3 wrote "tripping §4.3's
assertion in sim". After C-R5-1 that is no longer true and must not be relied on — §4.3's
assertions deliberately test **admission**, not **presentation**, so an unqualified presentation
header leaves `excLoadAdmit` itself correct and fires nothing there. Round 5 then named §10.2's
directed row `(e′)` as the replacement coverage; **round 6 found that substitute claim was also
false as `(e′)` was then written**, and both sites are fixed rather than the claim being
re-pointed a third time. The failure mode is worth stating once, precisely, because it is what
makes this a genuine gap rather than a wording problem: under W23's `CORRECT` shape
`excLoadCmdReady := dcache.loadCmd.ready && excLoadAdmit`, with a walker in the FIFO and the
`:2027` header left unqualified, `excLoadAdmit` is **false**, so `excLoadCmdReady` stays low,
the exception FSM does **not** advance, and `ExceptionUnit` never samples a response — i.e. all
three of `(e′)`'s original assertions **pass** — yet `dcache.loadCmd.valid` is still driven
`True` by the unqualified header (the mux's last driver) into a high `ready`, so the port
**fires spuriously every cycle**, `ldPushTag` falls through to its `CORE_LS` base, and bogus
`CORE_LS` entries pile in behind the walker's genuine one, past the FIFO's proven 3-entry bound
and into mis-routed responses. `(e′)` observed none of that because none of its assertions read
`loadCmd.valid`/`fire`. (Round 5's claim would have held under W23's **other** admissible shape
— deriving `excLoadCmdReady` from the exception leg's actual fire condition — but a coverage
claim that holds for only one of two admissible implementations cannot be stated
unconditionally.) **What catches this shape now is two checks, and neither is claimed sufficient
alone:** §4.2.3's exclusivity assertion, strengthened in round 6 to a **fire-qualified
`CountOne(...) === 1`**, which fires on the first cycle a `loadCmd.fire` happens with no
admission predicate true — the state an unqualified header creates — and §10.2's row `(e′)`,
strengthened in round 6 to assert `dcache.loadCmd.valid` **low** / no `fire` / `ldOwnerFifo`
occupancy unchanged for the whole withheld window. The division of labour is therefore: a
runtime assertion that catches the *unauthorised fire* generically wherever it arises, and a
directed test that pins this specific missing gate and must be shown to fail without it.)* That
header becomes `when(excLoadAdmit)`, and its
store-side twin at `:2067` becomes `when(excStoreAdmit)`. Both are rows in W27's table below.

##### W24 — `sq.io.drain.ready` (`LsEuPlugin.scala:265`)

```scala
sq.io.drain.ready := dcache.store.ready        // LsEuPlugin.scala:265 — UNCONDITIONAL
```

**The codebase already knows this is a hazard and already closes it for the exception case.**
`LsEuPlugin.scala:2068` does exactly that, with a comment saying why:

```scala
when(excActive && excStoreValid) {
  sq.io.drain.ready := False        // :2068
  // "...explicitly hold the SQ side so a command cannot be accepted under
  //  the exception payload."
}
```

That protection does **not** extend to a walker. When a walker's U/M store owns the store
port, `dcache.store.ready` pulses for the *walker's* command, `sq.io.drain.ready` goes high
under it, and the `StoreQueue` advances `sendPtr` / `acceptedHalves` for a store that was
never sent to the cache. That store is **permanently lost** — the SQ believes it drained,
the cache never saw it, and nothing anywhere notices. A dropped architectural store is the
most damaging failure in this document.

**Fix — extend the existing hold pattern rather than inventing a second one:**

```scala
sq.io.drain.ready := dcache.store.ready && (stOwner === CORE)
```

with the existing `:2068` `sq.io.drain.ready := False` inside the exception override left
exactly as it is (it is the same rule expressed at the finer `CORE`-internal granularity,
and last-assignment-wins already orders them correctly).

##### W27 — the exhaustive `dcache.*` handshake table

**Derivation.** Re-derived directly from `LsEuPlugin.scala` at this spec's HEAD by sweeping
`dcache.loadCmd`, `dcache.store`, `dcache.loadRsp`, `dcache.storeAck`, `dcache.storeErr` and
`dcache.loadProbe*` across the whole file, **not** transcribed from the review. Line numbers
are locators only; a plan-writer re-resolves by symbol. Rows marked **NEW** were not covered
by any round-2 decision.

**Round-4 extension — 29 rows → 34.** The round-3 arithmetic (10 `NEW — FIX` / 1 `NEW —
stated` / 18 already-covered-or-justified = **29**) was independently recounted and confirmed
correct, and is untouched. Round 4 adds **five** rows, in two groups:

- **Two pre-existing sites the round-3 sweep missed** — `:886`'s `parallelViptLaunch` (a
  `.fire` on `dcache.loadProbe`; disposition: no term needed) and `:757-764`'s CORE-LS base
  **payload** drive (disposition: `NEW — stated`, driver ordering, the load-side counterpart of
  the row `:263-264` already had on the store side). Neither is a new bug; both are
  bookkeeping the table owes.
- **Three rows for logic this document itself creates** — W29's FIFO push, the FIFO pop, and
  W30's `excLoadOutstanding` clear. Round 3 swept the file as it stands and stopped there,
  which is exactly how a brand-new bare `dcache.loadRsp.valid` consumer (W30's site) came to be
  specified nowhere. **Rule, stated so it does not have to be rediscovered: a `dcache.*`
  handshake reference introduced by this design is a row in this table on the same terms as one
  already in the file.**

Round-4 totals: **34 rows — 11 `NEW — FIX` (10 round-3 + W30's clear), 3 `NEW — stated`
(`:263-264`, `:757-764`, the FIFO pop), 1 `NEW — carries its own qualification` (the FIFO
push), and 19 already-covered / justified-needs-no-term** (18 from round 3 + `:886`), plus the
sim-only exclusivity assertion listed for completeness rather than as a defect-class site.

*Two locator corrections the round-3 derivation produced, recorded so the plan does not edit
the wrong line:* the split/aligned `dcache.loadCmd.valid` drive is at **`:756`**, not `:758`
(`:758` is `payload.paddr`); and `excStoreOutstanding` is declared at **`:268`**, not
`:266-270`.

**Load command port — `dcache.loadCmd`**

| Line | Site | Disposition |
|---|---|---|
| `:756` | `dcache.loadCmd.valid := Mux(useSplitCmd, llReg.valid, alignedSendValid)` — the CORE-LS base drive | **NEW — FIX.** See "the split leg" below. Becomes `coreLsLoadAdmit`-gated on **both** legs, and **both** legs must feed the arbiter's CORE-LS request. Today only the `alignedSendValid` leg is reached by any owner term (via `:716`). |
| `:757-764` | `dcache.loadCmd.payload.{vaddr, paddr, size, cacheMode, token}` — the CORE-LS base **payload** drive | **NEW — stated (round 4), no new term needed.** *Round 3 gave the store base (`:263-264`) a row whose entire content is its driver-ordering constraint, but gave the load base only a `.valid` row — leaving the load payload's identical dependence unrecorded, even though §5 item (1) names this exact mux the highest-risk net in the document.* Safe for the same reason and **only** for the same reason: the exception leg (`:2029-2064`) and the two new walker legs are *later* drivers in the same scope, and last-assignment-wins overrides this base. **The plan must preserve that ordering** — walker legs after the base, ordered against the exception leg per the priority chain of §4.2.4. It is load-bearing, not incidental. Note that after W29 the *tagging* no longer depends on this ordering (the tag is derived from the admission predicates, which are pairwise exclusive), but the *payload selection* still does. |
| `:766` | `alignedCmdFire = alignedSendValid && dcache.loadCmd.ready` | **Covered, W11.** `alignedSendValid` already carries `!dcLoadHeldByOther` via `:716`, and it is the *same* signal that gates the port's `valid` on this leg — so `ready` can only be believed on a cycle CORE-LS is actually presenting. This is the pattern the other `ready` sites must copy, and the reason it is safe is worth stating: **the fire qualifier and the valid drive read one signal.** |
| `:1508` | BK FSM `LAUNCH`: `when(dcache.loadCmd.fire) { llReg.valid := False; aDone := False; goto(WAIT_A) }` | **NEW — FIX.** `&& coreLsLoadAdmit`. Unqualified, a walker's fire advances the split FSM to `WAIT_A` with slot A never issued. |
| `:1536` | BK FSM `WAIT_A`: `when(dcache.loadCmd.fire) { llReg.valid := False; goto(WAIT_B) }` | **NEW — FIX.** `&& coreLsLoadAdmit`. This is the reviewer's worked scenario: see "the split leg" below. |
| `:2027-2028` | `when(excActive && excLoadCmdValid) { dcache.loadCmd.valid := True; … }` — the CORE-EXC presentation header and payload block | **NEW — FIX.** Header becomes `when(excLoadAdmit)`. Nothing previously bound `excLoadAdmit` to any RTL site, so the withholding W23's own argument assumes did not exist. |
| `:2115` | `excLoadCmdReady := dcache.loadCmd.ready` | **W23, fix corrected in round 3.** `&& excLoadAdmit` — *not* the weaker `(ldOwner === CORE) && excActive && excLoadCmdValid` round 2 prescribed. |
| (new) | `excLoadOutstanding`'s set term, `dcache.loadCmd.fire && …` | **W23, second site, predicate corrected.** `when(dcache.loadCmd.fire && excLoadAdmit)`. |
| (new) | The two walker legs' `loadCmd.valid` / payload drives | **W7/W8.** Gated by `walkerLoadAdmit(i)`; lowest-priority legs of the last-assignment-wins chain (§5 item (1)). |

**Store port — `dcache.store`**

| Line | Site | Disposition |
|---|---|---|
| `:263-264` | `dcache.store.valid := sq.io.drain.valid` / `payload := sq.io.drain.payload` — the CORE-LS base drive | **NEW — stated, no new term needed.** Safe **only** because the exception override (`:2067`) and the new walker legs are *later* drivers in the same scope and last-assignment-wins overrides this base — the property `:2020-2026`'s own comment already relies on. The plan must **preserve that ordering** (walker legs after the base, before/after the exception leg per the priority chain); it is load-bearing, not incidental. |
| `:265` | `sq.io.drain.ready := dcache.store.ready` | **W24.** `&& (stOwner === CORE)`, with `:2068`'s existing `:= False` hold left in place. |
| `:2067` | `when(excActive && excStoreValid) { … }` — the CORE-EXC presentation header | **NEW — FIX.** Becomes `when(excStoreAdmit)`. Unqualified, an exception store's `valid`/`payload` jump ahead of a walker's in-flight U/M store; the resulting `storeAck` is demuxed by `stOwner` (still WALKER) back to the walker, and `umq.io.drainAck` retires a U/M entry whose descriptor byte was never written — **§1.1's original coherency bug, reintroduced by the redesign that exists to fix it.** |
| `:2069` | `excStoreReady := dcache.store.ready` | **NEW — FIX, via its header.** *Round-4 correction: round 3 called this "the store-side exact twin of W23" and prescribed an extra `&& excStoreAdmit` term on the line itself. The characterisation was wrong and the extra term is redundant.* W23's real site (`:2115`) is unconditional at **file scope**; `:2069` already sits **inside** `when(excActive && excStoreValid)` with `excStoreReady := False` defaulted outside it. Once `:2067`'s header becomes `when(excStoreAdmit)` (this design's own fix, the row above), `:2069` **inherits that predicate automatically** — so no separate term, exactly as the `:2070-2071` row already says. Two rows describing sites inside one `when` block must not give contradictory guidance. What *does* survive from round 3's note is the substantive point, restated here because it is still true and still load-bearing: `storePort.ready` (`DcachePlugin.scala:1865`) no more references `valid` than `loadCmdPort.ready` does, and on stores `excStoreAdmit` *is* the owner form because store admission is drain-to-zero plus owner, with no in-flight-vs-granted gap to fall through. That asymmetry with the load side is exactly why C2 happened, and is why every site cites its **admission predicate** rather than an owner comparison. |
| `:2070-2071` | `dcache.store.valid := True` / `payload := excStorePayload` | **NEW — FIX.** Inside `:2067`'s corrected header; no separate term. |
| `:2076` | `when(dcache.store.fire && excActive && excStoreValid) { excStoreOutstanding := True }` | **NEW — FIX.** `when(dcache.store.fire && excStoreAdmit)`. Same class as W23's second site: an unqualified `fire` under a walker grant sets a CORE-side outstanding flag on someone else's transaction, corrupting the very `stBusy(CORE)` the hand-over rule reads. |
| (new) | The two walker legs' `store.valid` / payload drives | **W7/W13.** Gated by `walkerStoreAdmit(i)`. |

**Load response — `dcache.loadRsp` (a `Flow`; no backpressure, so every consumer is a pure sample)**

| Line | Site | Disposition |
|---|---|---|
| `:720` | `alignedRspFire = alignedRspValid && dcache.loadRsp.valid` | **W7 caveat (a).** `&& (ldRspTag === CORE_LS)`. |
| `:1476`, `:1480` | `dcache.loadRsp.payload.fault` / `.data` inside `when(alignedRspFire && !alignedRspIsPoison)` | **Covered transitively by `:720`.** No own term: these are nested under the qualified `alignedRspFire`. Stated explicitly so a plan-writer does not "fix" them a second time and does not assume they are a gap. |
| `:1520` | BK FSM `WAIT_A`: `when(dcache.loadRsp.valid && !aDone)` (and `:1521`/`:1529`'s payload use) | **W7 caveat (a).** `&& (ldRspTag === CORE_LS)`. |
| `:1541` | BK FSM `WAIT_B`: `when(dcache.loadRsp.valid)` (and `:1546`/`:1548`'s payload use) | **W7 caveat (a).** `&& (ldRspTag === CORE_LS)`. |
| `:1574-1575` | `bkCompletes = (bkInWaitB && dcache.loadRsp.valid) \|\| (bkInWaitA && dcache.loadRsp.valid && …fault)` | **NEW — FIX.** `&& (ldRspTag === CORE_LS)` on **both** arms. **Structurally the easiest row in this table to miss**, and it is why W27 is a table: it is deliberately defined *outside* both `whenIsActive` bodies (its own comment: "Derived from `bkFsm.isActive` rather than from an assignment inside either FSM"), so an editor implementing caveat (a)'s literal wording — which named only "WAIT_A/WAIT_B sampling" — touches the two FSM bodies and leaves this untouched. Unqualified, the `WAIT_B` arm treats a **walker's** response as slot B's completion, releasing `S1` and `backCompFires` (`:1579`) on a mid-flight response and completing the split load with slot B never received. |
| `:1579` | `backCompFires = (bkCompletes && !bkPoisoned) \|\| (alignedRspFire && !alignedRspIsPoison)` | **Covered transitively** by the two rows above, once both are qualified. No own term. |

**Store responses — `dcache.storeAck` / `dcache.storeErr` (bare ordered `Bool`s)**

| Line | Site | Disposition |
|---|---|---|
| `:270-271` | `sq.io.drainAck` / `sq.io.drainErr := dcache.storeAck/Err && !excStoreOutstanding` | **W13.** `&& (stOwner === CORE) && !excStoreOutstanding`. Mandatory: `StoreQueue.scala:518` asserts on a stray ack. |
| `:2073` | `when(dcache.storeAck && excStoreOutstanding) { excStoreOutstanding := False }` | **NEW — FIX (defence in depth), with its safety argument stated.** In principle safe without a term: `excStoreOutstanding` set implies `stBusy(CORE) =/= 0`, and the store direction's surviving drain-to-zero forbids a hand-over in that window — **but only if `coreStOutstanding` counts the exception sequencer's accepted stores as well as the SQ drain's** (W6 makes them one owner; §4.2.3 now says so explicitly). That is a cross-section invariant holding up a silent-corruption site, so the row takes `&& (stOwner === CORE)` anyway: one AND term on a low-fanout control register (§5), and the argument stops being load-bearing. |
| (new) | `umq.io.drainAck := dcache.storeAck && (stOwner === i)`, per walker | **W13.** Already specified in §4.3. |
| (new) | `exc.dcStoreAck` | **W13.** Gated at the mux, not at the top level — no DUT wiring changes. |

**Probe ports — `dcache.loadProbe`, `loadProbeCancel`, `loadProbeResolve`**

| Line | Site | Disposition |
|---|---|---|
| `:869-878` | `dcache.loadProbe.valid := probeWanted && xlate.req.ready` + payload | **No owner qualification needed, and the reason is a decision, not an accident: walkers never launch a probe** (W25). The probe ports have exactly one client and keep the pre-existing proof. What they *do* take is W11's suppression: `probeWanted` derives from `normalReqArm` (`:1775-1776`), whose `!excActive` becomes `!dcLoadHeldByOther`. |
| `:879-881` | `dcache.loadProbeCancel.valid := probeCancel \|\| probeCancelAll` | **No owner qualification needed** (same reason). Takes W11's `probeCancelAll = sqFlushSig \|\| excActive \|\| walkerOwnsLoad` (`:863`) — an added OR term, counted as a real addition in §5. |
| `:882-885`, `:1740-1745` | `dcache.loadProbeResolve` drives | **No owner qualification needed** (same reason). Keyed off `txRspFire`, a CORE-only translation event. |
| `:1776` | `dcache.loadProbe.ready` inside `normalReqArm` | **No owner qualification needed** (same reason); takes W11's `!dcLoadHeldByOther` substitution in the same expression. |
| `:886-887` | `parallelViptLaunch = dcache.loadProbe.fire && xlate.req.fire && (dcache.loadProbe.payload.vaddr(31 downto 12) === xlate.req.payload.vpn)` | **NEW row (round 4) — no owner qualification needed.** *This is a `.fire` reference on a `DcacheService` port and round 3's sweep did not produce a row for it; it is a **missing row**, not a missing fix.* Its disposition is its CORE-only neighbours': walkers never launch a probe (W25), `dcache.loadProbe.valid` is already CORE-gated via W11's `!dcLoadHeldByOther` fold into `normalReqArm`, and `xlate.req.fire` is CORE-only by construction (`lsXlateReqValid` carries `!excActive`, `:2079`). So `.fire` on this port stays a CORE-only event and the signal — a `simPublic` observability probe for the parallel-VIPT tests — keeps its meaning unchanged. |

**Round-4 additions: this design's OWN new logic.** *Round 3's table swept the file as it
stands. It did not cover the RTL **this document creates**, which is how `excLoadOutstanding`'s
clear term (a bare `dcache.loadRsp.valid` consumer) got specified nowhere and reviewed never.
New logic that touches a `dcache.*` handshake is subject to the table exactly as old logic is,
and the plan must add a row for any further site it introduces.*

| Site | Introduced by | Disposition |
|---|---|---|
| `when(dcache.loadCmd.fire) { ldOwnerFifo.push(ldPushTag) }` | **W29** (round 4) | **NEW — carries its own qualification, by construction.** The push must happen on **every** accepted command regardless of owner (that is what makes the tag positional), so the `fire` is correctly unqualified; the *qualification lives in the payload*, `ldPushTag`, which is the one-hot encode of the four admission predicates. This is the row that would have caught C-R4-1 had it existed: the defect was never "an unqualified `fire`", it was "a qualified `fire` paired with an unqualified value". |
| `when(dcache.loadRsp.valid) { ldOwnerFifo.pop() }` | **W7** (specified round 2, never given a row) | **NEW — stated: DELIBERATELY UNQUALIFIED, and it must stay that way.** This is the single site in the file where a bare `dcache.loadRsp.valid` consumer is *required*: positional tagging is correct only if exactly one entry is popped per response, whoever the response belongs to. Adding an owner term here would desynchronise the FIFO from the response stream and mis-tag every subsequent response — i.e. the "fix" would be the bug. Recorded explicitly so a future sweep of this defect class does not "correct" it. Its soundness rests on the two premises proved in §4.2.3: exactly one `loadRsp` per accepted `loadCmd`, and strict in-order completion. |
| `when(dcache.loadRsp.valid && (ldRspTag === CORE_EXC)) { excLoadOutstanding := False }` | **W30** (round 4) | **NEW — FIX.** The site C-R4-2 found missing entirely. Qualified by W29's tag, **not** by an `excStoreOutstanding`-style bare mirror. See W30 (§4.2.3) for the trace showing the bare form is reachable at HEAD. |
| `when(dcache.loadCmd.fire) { assert(CountOne(Cat(coreLsLoadAdmit, excLoadAdmit, walkerLoadAdmit(WALKER_ITLB_IDX), walkerLoadAdmit(WALKER_DTLB_IDX))) === 1) }` | **W29** (round 4) | **NEW — round 4 listed this for completeness as sim-only and outside the defect class; round 6 makes it a LIVE CHECK.** The predicates themselves are still `LsEuPlugin`-local, but the assertion is now **qualified by `dcache.loadCmd.fire`**, so the row does read a `dcache.*` handshake and is squarely inside the table's remit. The round-4 `<= 1` form was satisfied by **zero** predicates true, which is exactly the state an **unqualified presentation header** (`:2027`/`:2067`) produces — a `fire` no admission predicate authorised. `=== 1` at `fire` catches it; see §4.2.3's round-6 correction. Same `GenerationFlags.simulation` style as §4.3's block. *(Round 5: vector indices renamed per M-R5-4 — `WALKER_*_IDX` is a position in the `walkerLoadAdmit` vector, not the tag value `LdRspTag.ITLB`/`LdRspTag.DTLB`.)* |

**Not a handshake, listed for completeness:** `dcache.loadBusy` is referenced only in the
file's header doc comment (`:68-70`), never in `logic`. `host[DcacheService]` at `:197` is the
service handle itself. *Round-4 correction: round 3's closing claim — "no other `dcache.*`
reference exists in the file" — was **false**; `:886`'s `parallelViptLaunch` did, and now has
the row above. The claim that is actually true, and the one a plan-writer should hold this
table to, is: **every `dcache.*` reference in `LsEuPlugin.scala` is accounted for above,
including `:886`'s `parallelViptLaunch` and the doc-comment/service-handle non-sites named in
this paragraph.** A plan-writer must re-derive that property against the then-current file
rather than trusting this sentence.*

##### The split leg (`:756` / `:1508` / `:1536`) — the worked failure, and the two-part fix

`useSplitCmd = bkBusy` (`:749`), and `:756` reads
`dcache.loadCmd.valid := Mux(useSplitCmd, llReg.valid, alignedSendValid)`. W11's
`dcLoadHeldByOther` fold reaches only the `alignedSendValid` leg, at `:716`. **When a split is
in flight the arbiter-gated leg is bypassed entirely and `llReg.valid` reaches the port with
no owner or arbitration term anywhere in its cone.**

The failure, in order:

1. A split load reaches `WAIT_A`. `aDone` is still false, so `llReg.valid` is low (`:1534-1535`
   only raises it once `aDone` sets).
2. With `llReg.valid` low and `alignedSendValid` low (`:717`'s `!bkBusy`), the arbiter sees
   **no CORE-LS load request pending** and may grant a walker — or `force`-grant one under W8,
   since CORE-LS looks idle and its age counter never resets against a request it cannot see.
3. Slot A's response lands, `aDone` rises, `WAIT_A` raises `llReg.valid` and waits for
   `dcache.loadCmd.fire` to launch slot B.
4. The walker's own command fires in that window. `:1536` sees `dcache.loadCmd.fire` — true
   because of the **walker's** fire — clears `llReg.valid`, and goes to `WAIT_B`. **Slot B was
   never issued.**
5. Either the FSM waits forever in `WAIT_B` for a response nobody will send (a hang), **or**
   `bkCompletes`'s unqualified `WAIT_B` arm (`:1574`) sees the walker's `loadRsp` and treats it
   as slot B's completion — releasing `S1`/`backCompFires` and completing a cross-line load
   from a page-table descriptor line. `:1508` (`LAUNCH`, one step earlier) has the identical
   shape for slot A.

**The fix has two halves, and only doing one of them is worse than doing neither** (gating
without requesting turns the silent corruption into a livelock):

```scala
// (i) VISIBILITY: the split leg is a first-class CORE-LS request to the arbiter.
val coreLsLoadReq   = Mux(useSplitCmd, llReg.valid, alignedSendValidRaw)
// (ii) ADMISSION: the same gate the aligned leg already gets, applied to BOTH legs.
val coreLsLoadAdmit = coreLsLoadReq && !dcLoadHeldByOther
dcache.loadCmd.valid := coreLsLoadAdmit
// ...and every CORE-LS fire qualifier reads coreLsLoadAdmit, never bare `dcache.loadCmd.fire`:
//   :1508  when(dcache.loadCmd.fire && coreLsLoadAdmit) { … }
//   :1536  when(dcache.loadCmd.fire && coreLsLoadAdmit) { … }
```

where `alignedSendValidRaw` is `:716`'s expression **without** the `!dcLoadHeldByOther` term
(the term moves up to `coreLsLoadAdmit` so it applies once, to both legs, instead of being
duplicated). `alignedSendValid` itself keeps its current meaning — `alignedSendValidRaw &&
!dcLoadHeldByOther` — so `:766`'s `alignedCmdFire` is unchanged and still reads the same
signal that drives the port. **Half (i) is what keeps §4.2.5's starvation proof true for the
split path**: without it a split load is invisible to the arbiter and the aging counters
reason about a requester that is not in their request set.

##### Why every one of these must appear in the plan's `LsEuPlugin` change list

§8's `LsEuPlugin` row carries the whole table, not a selection from it. The plan **must**
carry each **NEW — FIX** row as a named step with its own directed check (§10.2), and the
**no-new-term** rows as explicitly-recorded no-ops with their justifications, so a later
reviewer can tell "considered and justified" from "not looked at" — which is the distinction
round 2 could not support and round 3 exists to make possible.

#### 4.2.8 W28 — the same-cycle owner-coherence invariant

Every owner qualification in this document — `ldOwner === CORE`, `stOwner === CORE`,
`rspOwner === CORE`, `!ldOwnerFifoHoldsWalker`, and every predicate built on them in W7, W13,
W23, W24 and W27 — rests on a premise that §5 only gestures at ("registered and never
combinationally in a command path") and that nothing states as a requirement:

> **The arbiter's payload/`valid` MUX selection and the qualification terms that gate belief in
> that port's handshake MUST read the identical owner value on the identical cycle.**

If, say, `ldOwner` is a register updated on the *grant edge* while the mux itself selects off a
*combinational* grant signal, there is a one-cycle window in which the port is physically
driven by one client while every qualification term says it belongs to another. Every C1/C2
failure in this document then reappears — a descriptor into an architectural register, a lost
core store, a split FSM advancing on someone else's fire — through a **timing gap** instead of
a **missing site**. W27's table cannot detect that class; only this invariant can.

**W28 (decision), clause (a) — READS.** The owner-tracking state that qualification terms read
(`ldOwner`, `stOwner`, the ownership FIFO and its `ldRspTag`/`rspOwner` head, `walkerOwnsLoad`,
`dcLoadHeldByOther`) **is the same state the mux selects on, sampled in the same cycle, off the
same signal.** Two admissible implementations, and no third:

- **Registered-grant (preferred).** The grant decision is registered; the mux's walker legs
  select off the registered grant; the qualification terms read that same register. The command
  presented in cycle *N* is selected by `owner[N]` and qualified by `owner[N]`. This is what
  §5's FMax argument already assumes (a registered owner code compared against a constant, "one
  LUT from a flop"), and it costs nothing extra.
- **Combinational-grant, used consistently.** The mux selects off the combinational grant and
  *every* qualification term reads that same combinational signal, with the registered owner
  used **only** for reporting (W26's counters). *Round-4 correction: round 3's wording here read
  "used only for FIFO push payloads and for reporting" — which is precisely the licence that
  made C-R4-1 legal. It is withdrawn. Under **neither** implementation may a separately-clocked
  owner register be the FIFO's push source; see clause (b).* Permitted, but it puts a computed
  condition on the boundary mux's select and therefore trades against §5 item (1); if the plan
  reaches for it, the synth gate is the arbiter.

**W28 (decision), clause (b) — WRITES, and specifically the FIFO push payload. Round 4.**
*Round 3 scoped W28 to the state qualification terms **read**, and §12's own round-3 self-check
recorded the resulting gap in the abstract — "it constrains *when* the owner value is sampled,
never *which* value is required" — without recognising that the gap was itself a reachable
silent-corruption bug. It was. The full trace is C-R4-1 in §4.2.3's W29 block.*

> **Owner-state WRITES are subject to the identical same-cycle-coherence requirement as the
> qualification terms that read them. In particular, the value pushed into the ownership FIFO
> on `dcache.loadCmd.fire` MUST be derived, combinationally and in that same cycle, from the
> ADMISSION PREDICATES that actually gated the mux leg that fired — never from any separately
> clocked `ldOwner`/`grantedOwner` register, under either implementation above.**

This is one invariant with the read side, not a footnote beside it, because the failure mode is
identical: the port is physically driven by one client while the design's record of ownership
names another. The only difference is that the read side loses coherence for **one cycle**,
while a mis-pushed tag stays wrong for the **entire lifetime of the in-flight response** — which
is why W28's round-3 directed check (payload-presented vs. owner-qualified) passes trivially
while the bug is live: under both admissible implementations those two are the same
admit-predicate-derived value compared against itself, and the FIFO push reads a third register
the check never touches. **W29 (§4.2.3) is the concrete realisation of this clause**; W28
clause (b) is the *rule*, W29 is the *encoding*.

**W28 (decision), clause (c) — `ldOwner`/`stOwner`'s update rule. Round 4.**
*§4.2.2 declares these registers and §4.2.4 gives a priority **ordering** for grants, but no
section in this document ever stated what **loads** them, or on what edge. That omission is the
same species as clause (b)'s: an unstated write rule behind a carefully-stated read rule.*

```
ldOwner := ldGrantNext        every cycle          // registered-grant shape
stOwner := stGrantNext        every cycle
```

where `ldGrantNext`/`stGrantNext` are the combinational outputs of §4.2.4's priority + aging +
round-robin arbiter for that direction, evaluated over the current request set. Explicitly:

- The registers are **unconditionally reloaded every cycle** from the arbiter's decision — they
  are not "latched on a grant and held", because a held owner is exactly the *current-grant*
  vs. *in-flight* confusion that produced C2. Anything that must persist across cycles lives in
  the FIFO (loads) or in `stBusy`/`walkStOutstanding` (stores), never in these registers.
- Under the registered-grant implementation, `walkerLoadAdmit(i)`'s `grant(i)` term **is**
  `ldOwner === i` (and `walkerOwnsLoad` **is** `ldOwner =/= CORE`), so clause (a)'s "identical
  value, identical cycle" is satisfied by construction rather than by review.
- Under the combinational-grant implementation, `grant(i)`/`walkerOwnsLoad` read `ldGrantNext`
  directly and `ldOwner` is reporting-only (W26). Mixing the two is what clause (a) forbids.
- Neither register is read by W29's push encode. That is the point of W29.

**What is forbidden** is the mixture: mux selecting off one, qualifying off the other, or
tagging off a third. The plan must state which of the two implementations it uses, in one
place, and `WalkerDcachePortArbSpec` must carry the directed checks §10.2 specifies for both
clauses — the round-3 check (payload presented vs. owner qualified) **and** the round-4 check
(pushed FIFO tag vs. the admit predicate that actually drove the fire), because the round-4
review demonstrated the first passes while the second fails. A `GenerationFlags.simulation`
assertion in the same style as §4.3's block is the natural home for both, alongside W29's
one-hot exclusivity assertion.

### 4.3 W13/W14 — response routing

**Store side (W13) — mandatory demux.** `StoreQueue.scala:518` already carries
`assert(!(io.drainAck && !drainBusy), "StoreQueue: drainAck arrived with no accepted drain
half")`. A walker `storeAck` reaching the SQ is therefore not a subtle corruption but an
immediate simulation failure — and in synthesis, a spurious pop of the SQ head, which
`DcachePlugin.scala:2038-2046` documents as a known-catastrophic failure class. The existing
line

```scala
sq.io.drainAck := dcache.storeAck && !excStoreOutstanding     // LsEuPlugin.scala:270
sq.io.drainErr := dcache.storeErr && !excStoreOutstanding     // LsEuPlugin.scala:271
```

generalises to `&& (stOwner === CORE) && !excStoreOutstanding`. The walkers' own acks are
`umq.io.drainAck := dcache.storeAck && (stOwner === i)`. `exc.dcStoreAck`
(`FullCoreSynth.scala:353`) is gated the same way, at the mux, not at the top level — so no
DUT wiring changes.

**Load side (W14) — no *bundle-level* demux and no top-level rewiring.** *Reworked in the fix
pass to match W7's ownership FIFO.* The distinction W14 turns on is **where** routing
happens, not whether it happens:

- **Nothing outside `LsEuPlugin` changes.** `exc.dcLoadRsp` (`FullCoreSynth.scala:350-351`)
  stays wired straight from `DcacheService.loadRsp`, as does every DUT that replicates that
  line. `DLoadRsp` gains no field. This was the whole strength of the original W14 argument
  and it is preserved intact.
- **Inside `LsEuPlugin`, routing is by `ldRspTag`** — W7's FIFO head, four-valued per W29 — for
  every `CORE`-side
  consumer that is no longer guaranteed inert. `alignedRspFire` (`LsEuPlugin.scala:720`), the
  split BK FSM's `WAIT_A`/`WAIT_B` sampling (`:1520`, `:1541`) and **`bkCompletes`
  (`:1574-1575`)** each gain `&& (ldRspTag === CORE_LS)`; `:1476`/`:1480` and `:1579` are covered
  transitively by their qualified parents. **W27's table (§4.2.7) is the authoritative list**
  — round 2's "two sites" wording here was incomplete and is what let `bkCompletes` slip. Even
  complete, this is four AND terms on already-registered control signals, not a demux of the
  response bundle, so W14's argument is unchanged in substance.
- **The exception sequencer alone keeps the pure temporal-exclusivity argument** — *and round 4
  is what makes that argument true rather than merely stated.* `ExceptionUnit`'s `dcLoadRsp` is
  only sampled in states entered *after* it issued a load (`:1470`, `:1532`, `:1544`, `:1556`,
  `:1569`, `:2224`), and the claim W14 needs is that this window coincides with
  "`excLoadOutstanding === 1` **and nothing else is in flight**". Round 3 asserted that
  coincidence without specifying the mechanism, and there was none: the register had no clear
  term at all, and the drain-to-zero boundary excluded only *walkers*, not the ordinary LS pipe
  — which W6 makes the **same owner**, so `rspOwner` could never have separated them. **W30
  (§4.2.3) supplies both halves**: the tagged clear term, and `ldOwnerFifoEmpty` in
  `excLoadAdmit` so no client of any kind can have a response owed when the exception
  sequencer's own command is admitted. Only with both does this bullet's argument hold. It is
  then the identical argument `ExceptionUnit.scala:216-217` already records for the existing
  2-source case ("`dcLoadRsp` is already wired straight from `DcacheService.loadRsp`") — and,
  per §11.1 item 9, *stronger* than what HEAD actually implements today.

What is added alongside are `GenerationFlags.simulation` assertions in `LsEuPlugin`, narrowed
to the boundaries that are still drain-to-zero. *Round 5 corrects the first of them. Round 4
wrote it against **presentation** (`excActive && excLoadCmdValid`) when the property it needed
is about **admission** (`excLoadAdmit`), and that is not a wording nicety: `excLoadCmdValid` is
`ExceptionUnit`'s **held, registered** `ldoValidReg` (`ExceptionUnit.scala:798-803`), asserted
continuously from the cycle the FSM enters a `*REQ` state until `dcLoadCmd.fire`. Under W30
part (2) the exception's command is **correctly withheld** for exactly as long as the FIFO is
non-empty, with `excLoadCmdValid` high and `excLoadOutstanding` still `False` — which is
literally the round-4 predicate. The assertion therefore fired on the very waiting behaviour
W30 exists to create; §6.6's starvation argument, §10.2's row (e′) and §10.2's W30 row all
specify that same state as correct, and all three would have tripped it. Both superseded forms
are kept as comments, because an assertion that is wrong in the **weakening** direction and one
that is wrong in the **firing-on-correct-behaviour** direction fail differently and a
plan-writer should be able to recognise either:*

```
// ROUND-3 FORM (superseded — walker-only, cannot see a CORE-LS straggler):
//   assert(!(ldOwnerFifoHoldsWalker && (excLoadOutstanding || (excActive && excLoadCmdValid))), ...)
// ROUND-4 FORM (superseded — WRONG, fires on correct behaviour: `excLoadCmdValid` is the HELD
// `ldoValidReg`, so this predicate IS the W30 wait, not a violation of it. `LsEuPlugin` also
// cannot make it true by construction — `ldoValidReg` is driven by `ExceptionUnit`):
//   assert(!(!ldOwnerFifoEmpty && (excActive && excLoadCmdValid) && !excLoadOutstanding), ...)
assert(!(excLoadAdmit && !ldOwnerFifoEmpty),
       "excLoadAdmit was weakened: the exception's command can be admitted with a response in flight",
       FAILURE)
assert(!(excLoadOutstanding && !(ldOwnerFifoOccupancy === 1 && ldRspTag === CORE_EXC)),
       "excLoadOutstanding is set without exactly its own command in the ownership FIFO", FAILURE)
assert(!(stOwner =/= CORE && (coreStOutstanding =/= 0)),
       "a walker held the D-cache store port with a core store still outstanding", FAILURE)
```

so that if W7's or W30's surviving hand-over conditions are ever weakened, the netlist says so
loudly rather than silently mis-delivering a descriptor into an architectural register.

The first assertion is deliberately **tautological against W30 part (2)'s own predicate**
(`excLoadAdmit = excActive && excLoadCmdValid && ldOwnerFifoEmpty`), and that is the point: it
is a **structural tripwire against a future weakening**, not a runtime timing check. It can
never fire under a correct implementation, and it fires immediately if anyone ever relaxes
`excLoadAdmit`'s FIFO term — e.g. back toward round 2's `!ldOwnerFifoHoldsWalker` form — which
would simultaneously reopen C2 and re-inherit §11.1 item 9's live HEAD bug. A property that
`LsEuPlugin` **can** hold by construction is the only kind worth asserting here: `excLoadAdmit`
is `LsEuPlugin`'s own expression, whereas `excLoadCmdValid` is not.

The second assertion is the direct, checkable statement of W30's soundness proof: while the
exception sequencer has a load outstanding, the FIFO holds exactly its own entry and nothing
else — which is what licenses both the tagged clear term *and* `exc.dcLoadRsp` staying
unqualified. It reads `ldOwnerFifoOccupancy` **pre-pop**, per §4.2.3's definition; that reading
is load-bearing (see there).

### 4.4 W15 — where the walker client ports live

`ItlbPlugin` / `DtlbPlugin` replace their `var walkerAxi: Axi4` hook with four plugin-level
`var` hooks of exactly the same species:

```scala
var walkLoadCmd:  Stream[DLoadCmd] = null   // driven by the plugin, consumed by the mux
var walkLoadRsp:  Flow[DLoadRsp]   = null   // driven by the mux
var walkStore:    Stream[DStoreCmd]= null   // driven by the plugin
var walkStoreAck: Bool             = null   // driven by the mux
```

allocated in `during setup`, default-idle with `allowOverride` in `logic` (byte for byte the
pattern `umAccessRobId`/`umCommitValid`/`umFlush` already use, `DtlbPlugin.scala:100-108`),
and wired by the top-level/DUT wiring exactly as `walkerAxi` is wired today. `LsEuPlugin`
gains matching pass-through hooks in the shape of `excLoadCmdValid`/`excStorePayload`
(`LsEuPlugin.scala:127-152`).

Deliberately **not** a new `FiberPlugin` service trait: `host[TranslationService]` /
`host[DTranslationService]` do not expose walker ports, a shared trait would need an
I-vs-D discriminator for W9's round-robin, and this codebase's established answer for
"sibling plugin needs a wire" is the `var` hook. No new plugin, no new service-resolution
question, no elaboration-ordering dependency.

### 4.5 W16 — `TableWalker` after the change

`io.axi` is deleted. `issueRead()` becomes a `walkLoadCmd` drive:

```
walkLoadCmd.valid       := !cmdSent
walkLoadCmd.payload.paddr := descAddr        // NOT line-aligned; see the note below
walkLoadCmd.payload.vaddr := descAddr        // identity-physical, per LsEuPlugin.scala:2029-2035
walkLoadCmd.payload.size  := Size.LONG
walkLoadCmd.payload.cacheMode := <don't care; the mux stamps it, W2>
walkLoadCmd.payload.token := DLoadToken.WALK_ITLB / WALK_DTLB   // RESERVED value, W25
```

and each `RD_*` state's `when(io.axi.r.fire)` becomes `when(walkLoadRsp.valid)` with
`val d = walkLoadRsp.payload.data`. `TableWalker` itself needs **no** owner awareness: the
mux drives each walker's `walkLoadRsp` (W15) already qualified by `ldRspTag === ITLB` / `=== DTLB`
(W29), so the walker sees a `valid` only for its own response. That qualification lives entirely in
`LsEuPlugin` (§4.2.3).

**Why `paddr` is not line-aligned — corrected in the fix pass.** The original draft said the
byte lane is selected from `paddr[3:0]`. It is not: `DcachePlugin` derives the lane offset
from the **virtual** address, `cmdOff = cmdVaddr(offBits-1 downto 0)` (`DcachePlugin.scala:321-324`),
i.e. `vaddr[3:0]`. The distinction is harmless *in practice* here only because the walker is
an identity-physical client and sets `vaddr := paddr := descAddr`, so the two are the same
value by construction. The stated invariant is corrected anyway, because a future reader
following the old wording could set `vaddr` to a line-aligned address and `paddr` to the byte
address, believe the lane still resolves, and silently read lane 0 of every descriptor line.
**The load-bearing requirement is: `vaddr` must carry the descriptor's byte offset.** `paddr`
carries the same value because the client is identity-physical, not because the lane needs
it.

**W25 — the walker's `token` must be a RESERVED value, not a don't-care.** *Added in the fix
pass; the original draft assigned it don't-care, which is a silent-corruption bug.*
`DcachePlugin` matches a resident early-probe entry on **both** token **and** vaddr:

```scala
earlyProbePresentVec(i) := earlyProbeValids(i) &&
                           (earlyProbeTokens(i) === loadCmdPort.payload.token) &&
                           (earlyProbeVaddrs(i) === cmdVaddr)      // :420-422
```

The walker's `vaddr` is `descAddr` — a *physical* page-table address. Under identity-mapped
supervisor page tables, which is not an exotic case but the ordinary one in this project's
own MMU tests, that value can equal a live LS-EU probe's `vaddr` exactly. A don't-care token
that happens to equal that probe's token then makes `earlyProbeOwnsCmd` true for the
**walker's** command, and the walker is answered from a stale probe snapshot with
`earlyProbeHitData` extracted at the *probe's* size and offset. Wrong descriptor, no fault,
no assertion.

The fix follows the precedent already in the tree: the exception sequencer stamps
`U(0x80, DLoadToken.Width bits)` (`LsEuPlugin.scala:2064`), deliberately disjoint from LS-EU
probe tokens, which are `(False ## False ## robId)` (`LsEuPlugin.scala:867`) and so always
have their top two bits clear. Walkers take their own reserved values in the same
above-`robId` space:

```
DLoadToken.WALK_ITLB = 0x81
DLoadToken.WALK_DTLB = 0x82
```

declared next to the existing width constant so the disjointness is visible in one place
rather than being an invariant spread over three files. Two distinct values, not one shared
"walker" token, so that an ITLB command can never alias a DTLB command's entry either.
Walkers still never *launch* a probe — the reserved token exists purely so their commands
cannot **match** one.

**`DcacheTypes.scala`'s token-layout doc comment must be amended in the same edit.** *Added in
the round-3 fix pass.* The constants are genuinely disjoint — every existing token has bit 7
clear except the exception sequencer's `0x80` — but `DcacheTypes.scala:6-8` currently defines
the layout as:

```scala
// [7] source (0=LS ROB, 1=serializing exception unit), [6] split half,
// [5:0] ROB id. Kept as a plain UInt field in all public bundles.
```

Under **that** layout `0x81` and `0x82` read as "serializing exception unit, split half 0,
robId 1" and "…robId 2" — which is **false**; they are walker tokens belonging to no ROB entry
at all. Landing correct constants beside a now-false comment describing them is exactly the
citation/comment drift this spec objects to elsewhere (§6.2's obligation to rewrite
`ExceptionUnit.scala:1362-1381`, §9.3's Task 13 citation updates). The comment must be
rewritten to describe bit 7 as "non-LS source" with the three reserved values enumerated
(`0x80` exception sequencer, `0x81` ITLB walker, `0x82` DTLB walker) and to state that
`[6]`/`[5:0]` are meaningful **only** when bit 7 is clear. This is a required part of W25, not
optional tidying.

**`selectWord` is deleted (W16).** `DcacheByteLane.extract(line, paddr[3:0], LONG)`
(`DcacheTypes.scala:186-190`) computes bit-for-bit what `selectWord` (`TableWalker.scala:86-94`)
computes, and `DcachePlugin.scala:511-513` already applies it to build `loadRsp.payload.data`.
Keeping a second copy of the task-#194 big-endian convention in the walker, now that the
walker is downstream of the first copy, would be a live drift hazard. Note the address passed
in `paddr` must be the **descriptor byte address**, not the line-aligned one the old AXI AR
used (`TableWalker.scala:107`), because the offset is what selects the lane.

`loadRsp.payload.fault` (a physical AXI refill error, `DcacheTypes.scala:63-71`) becomes
reachable for a walk for the first time. Today a walker read's AXI error is silently ignored
(`TableWalker` never inspects `r.resp`). Handling: treat it as `MmuFaultReason.NON_RESIDENT`
and terminate the walk via the existing `FINISH` path.

**This is MANDATORY, not the "small, genuine improvement" the original draft called it.**
*Reclassified in the fix pass.* Socket spec **D19**'s evidence list explicitly names
`TableWalker.scala:114` — the descriptor `R` consumer this redesign deletes — as one of the
sites establishing its "every transaction terminates on any response code" invariant, and the
socket spec's §12 requires that invariant to be re-checked by any work that changes a
response consumer. §9.3 item 3 records the re-establishment; this paragraph is its RTL half.
A plan that treats it as optional polish would silently retire a cited D19 site and put
nothing in its place.

### 4.6 W17 — the U/M writeback after the change

`DtlbPlugin.scala:314-358` / `ItlbPlugin.scala:249-289` keep the **combinational** descriptor
construction — `drainByteOff`, `drainBeat`, `drainStrb` — verbatim; that construction is
already exactly `DStoreCmd`'s `useStrb` form (scoping memo §3). The three holding registers
`drainAddrReg`/`drainBeatReg`/`drainStrbReg` are kept **as registers with the same payloads**,
but their **latch condition necessarily changes**. What is deleted is the AW/W/B sequencing —
`drainAwDone`/`drainWDone` and their three `when` blocks — and `walkerAxi`.

**Internal inconsistency corrected in the fix pass.** The original draft said all six signals
were kept "verbatim" while simultaneously deleting `drainAwDone`/`drainWDone` "and their
three `when` blocks". One of those blocks (`DtlbPlugin.scala:334-340`) is the **only writer**
of `drainAddrReg`/`drainBeatReg`/`drainStrbReg`:

```scala
when(umq.io.drain.valid && drainAwDone && drainWDone) {   // :334 — the ONLY writer
  drainAddrReg := (umq.io.drain.payload.addr(31 downto 4) ## U(0, 4 bits)).asUInt
  drainBeatReg := drainBeat
  drainStrbReg := drainStrb
  drainAwDone  := False
  drainWDone   := False
}
```

Deleting it verbatim would leave the three registers permanently uninitialised — an
undriven-`Reg` bug, and per this project's own sim-poke gotchas one that randomises per seed
rather than reading zero. The replacement latch condition is the single-entry arm the new
`drainArmed` bit provides:

```scala
when(umq.io.drain.valid && !drainArmed) {
  drainAddrReg := (umq.io.drain.payload.addr(31 downto 4) ## U(0, 4 bits)).asUInt
  drainBeatReg := drainBeat
  drainStrbReg := drainStrb
  drainArmed   := True
}
when(walkStore.fire) { drainArmed := False }
```

`drainArmed` replaces `drainAwDone && drainWDone` as the "no drain currently presented"
predicate — same single-outstanding discipline, one bit instead of two, because there is now
one handshake instead of two. What replaces the AXI drive:

```
walkStore.valid            := drainArmed
walkStore.payload.paddr    := drainAddrReg          // 16-byte aligned
walkStore.payload.useStrb  := True
walkStore.payload.strb     := drainStrbReg
walkStore.payload.lineData := drainBeatReg
walkStore.payload.data     := <don't care under useStrb>
walkStore.payload.size     := Size.LONG             // don't care under useStrb, see below
walkStore.payload.cacheMode:= <don't care; the mux stamps it, W2>
walkStore.payload.precise  := False
umq.io.drainAck            := walkStoreAck
```

- `size` is genuinely a don't-care under `useStrb`: both merge sites select on it
  (`DcachePlugin.scala:678-684` for S3, `:716-721` for S2) and the AXI beat is built from
  `stS3MergeData`/`stS3MergeStrb` (`:1937-1938`). `LONG` is chosen as a legal, obviously-safe
  value rather than leaving it unassigned.
- `precise = False` is correct and deliberate: `precise` routes a bus error to the SQ's
  precise-fault path (`DcacheTypes.scala:91-94`), and a U/M writeback has no owning SQ entry.
  It therefore lands on the async diagnostic channel (`diagFault`), which is the right place
  for it.
- The single-entry elasticity (`drainArmed`, above) preserves the queue's existing "hold the
  entry until `drainAck`" contract (`DtlbPlugin.scala:334-340`), and matches
  `DcacheService.store`'s stated "elastic ordered drain; payload stable until fire"
  (`DcacheTypes.scala:122`).

---

## 5. W12 — FMax risk and its concrete mitigation

The scoping memo §7 is right that this neighbourhood has already cost FMax once
(`mmu-atc-m-bit-fix-2026-08-02.md`: the correct M-bit fix "blocked on synth gate
(reintroduced a critical-path regression)"), and that `DcachePlugin.scala:744-754` goes out
of its way to keep even the *second* existing client off the high-fanout `rdSet`/`rdEn` BRAM
read-address net:

> the store base adds NO arbiter cone to that fo=high net. The "did the store actually get
> the port?" question (`loadUsesPort`) feeds ONLY the low-fanout `stS2Valid` control
> register, NOT the BRAM address.

**The mitigation is structural, and it is why W4 chose the `LsEuPlugin` mux. It is
*mitigation*, not elimination, and this section was rewritten in the fix pass to say so.**
The original W12 claimed "net new logic levels on the high-fanout `rdSet`/`rdEn` net: zero",
supported by an accounting of three control-signal substitutions. That accounting was
**incomplete**: it did not cover the `loadCmdPort.payload.vaddr` mux, which is the one path
that actually reaches that net. On a netlist with 0.069 ns of headroom an unqualified
zero-cost claim is the single most dangerous sentence this document could contain, so it is
withdrawn and replaced with a measured one.

##### What genuinely costs nothing (three pure substitutions)

Each of these replaces one register-sourced signal with another *inside an existing
AND-tree*. Substitution adds no level; it adds one 2-input OR **outside** the timing path,
feeding a signal that already fans out to these sites:

- `:716` `alignedSendValid = ... && !bkBusy && !excActive` → the `!dcLoadHeldByOther` term
  moves up one level into `coreLsLoadAdmit`, where it covers the split leg too (W27); the
  level count at `:716` is unchanged and `:756`'s existing 2-way `Mux` gains one AND input.
- `:1774-1776` `normalReqArm`/`splitReqArm`'s `!excActive` → `!dcLoadHeldByOther`
- **All of W27's owner-qualification terms** — `sq.io.drain.ready`, `sq.io.drainAck`/`drainErr`,
  `excLoadCmdReady`, `excStoreReady`, the `:2027`/`:2067` presentation headers, the two BK-FSM
  `loadCmd.fire` qualifiers, the three `loadRsp` samplings plus `bkCompletes`, and `:2073`/`:2076`
  (W23/W24/W27, §4.2.7). Every one is an AND term on a **low-fanout control signal**, sourced
  from a register, feeding a control flop — **none touches a BRAM address**. That the round-3
  sweep grew this list from 3 sites to 15 therefore does **not** change §5's risk picture: the
  two real additions below are still the only two, and they are unchanged by round 3.
- **[Round 4] W29's push encode and W30's clear term, priced explicitly.** W29's `ldPushTag` is
  a 4-input one-hot encode of signals that already exist and already fan out to the mux legs,
  producing a **2-bit** value that feeds only the FIFO's write port — a control flop, not the
  boundary. It adds **no** term to `dcache.loadCmd.valid`/payload and therefore nothing to §5
  item (1)'s protected arc. Widening the FIFO entry from 2 bits to 2 bits is a no-op (the
  four-valued tag fits the same width the three-valued owner code used). W30's clear term is one
  AND of an equality against a constant on a low-fanout control register, and W30's
  `ldOwnerFifoEmpty` replaces `!ldOwnerFifoHoldsWalker` inside `excLoadAdmit` — an OR-reduction
  over 4 valid bits in place of an OR-reduction over 4 valid bits AND-ed with a tag compare,
  i.e. **strictly less** logic than the term it replaces. Round 4 therefore also leaves the two
  real additions below as the only two.

with `dcLoadHeldByOther = excActive || walkerOwnsLoad` computed **once**, from a **register**
(and read on the same cycle the mux selects on — W28, §4.2.8).

Also genuinely free, for a structural reason rather than an accounting one:

- **`DcachePlugin.scala` is not edited.** `rdSet`/`rdEn`, `loadCmdPort.ready`,
  `storePort.ready` and the whole S0-S3 / S1-S2 machinery are byte-for-byte unchanged. The
  arbitration is entirely *upstream* of the cache boundary.
- **The owner/`force`/age/FIFO state is registered and never combinationally in a command
  path.** `ldOwner`, `stOwner`, `force(i)`, `walkRr`, the W7 ownership FIFO and its
  `ldRspTag` head are all flops.

  *Round-4 correction — this bullet previously said "the mux selects payloads from them",
  which contradicted §4.2.3 and every W27 row, both of which say the legs are gated
  `when(coreLsLoadAdmit)` / `when(excLoadAdmit)` / `when(walkerLoadAdmit(i))`. Two
  silently-conflicting descriptions of the same mux is exactly the ambiguity that produced
  C-R4-1, so they are reconciled here into one:*

  **The mux legs are selected by the ADMISSION PREDICATES, not by an owner comparison.** The
  owner registers above are *inputs to* those predicates (`walkerLoadAdmit(i)`'s `grant(i)`,
  `coreLsLoadAdmit`'s `!dcLoadHeldByOther`) and are read by W26's reporting — they are not
  themselves the select. The FMax point this bullet is making survives the correction intact
  and is in fact what matters: each predicate's *owner-derived* term is a **registered** owner
  code compared against a constant, so the select term is one LUT from a flop, exactly as
  `:2027`'s `when(excActive && excLoadCmdValid)` selects from a registered `excActive`.

  Two consequences worth stating together, because keeping them apart is what let them drift:
  **(i)** how the mux *selects* — admission predicates, this bullet and W27's rows; and
  **(ii)** how the FIFO *tags* — W29, a one-hot encode of those same predicates. These are
  different questions with the **same** answer signal set, and that identity is the whole
  content of W28 clause (b). Neither is "select off `ldOwner`".
- **The walker's command payloads are registered at the source.** `descAddr`
  (`TableWalker.scala:54`) and `drainAddrReg`/`drainBeatReg`/`drainStrbReg`
  (`DtlbPlugin.scala:331-333`) are already `Reg`s. So the new mux input is flop → mux →
  the same flop-fed boundary the exception input uses, preserving the "both addresses are
  therefore registered at this boundary and preserve FMax #1" property
  `LsEuPlugin.scala:746-749` states for the existing two load sources.

##### What genuinely costs something (two real additions)

**(1) The `loadCmdPort.payload.vaddr` mux widens 3-way → 5-way, on the protected net.**
This is the highest-risk item in this document. `DcachePlugin` derives its BRAM read address
from that payload directly:

```scala
val cmdVaddr = loadCmdPort.payload.vaddr          // DcachePlugin.scala:321
val cmdSet   = cmdVaddr(offBits + setBits - 1 downto offBits)
...
} elsewhen(loadCmdPort.fire) { rdSet := cmdSet; rdEn := True }   // :1034
```

`cmdSet` **is** `rdSet` — the exact high-fanout BRAM read-address net that
`DcachePlugin.scala:749-759`'s own comment says is deliberately kept clear of a second
client's arbiter cone ("*the store base adds NO arbiter cone to that fo=high net… breaking
the post-route critical path (`loadCmdPort.ready` → arbiter → tag/dataMem read-address)*").
Today `LsEuPlugin` drives that payload from **three** sources (aligned-load queue, split BK
FSM, exception sequencer). W4 makes it **five** (+ ITLB, + DTLB). That is real added fan-in
on a wide (set-index) bus feeding a fo=high net, and the payload registers being flops at the
source does not remove the mux itself.

Mitigations available, in the order the plan should reach for them if the gate moves:

- The two walker inputs are the **lowest-priority** legs, so a priority-encoded
  last-assignment-wins chain puts them at the *far* end of the select tree rather than in
  series with the `CORE` path — the existing `:2020-2078` block already has this shape and
  the walker legs simply extend it.
- The walker select condition is a **registered** owner code compared against a constant, so
  the select term itself is one LUT from a flop, not a computed condition.
- If it still moves: precompute a single registered `walkVaddrSel` payload (a 2-way
  ITLB/DTLB pre-mux, one cycle early — a walker command is registered and stable, so this
  costs no throughput) so the boundary mux returns to 4-way with one flop-fed leg.

**(2) `probeCancelAll` gains an OR term — an addition, not a substitution.**
`:863` `probeCancelAll = sqFlushSig || excActive` becomes
`sqFlushSig || excActive || walkerOwnsLoad`. The original framing filed this under
"substitution, not addition"; it is not a substitution and the framing is corrected. The cost
is one 3-input OR from registers instead of a 2-input OR, and it stays **off** the protected
net by construction: `probeCancelAll` feeds only the `earlyProbeValids` control registers
(`:1976-1982`), whereas `loadProbePort.fire` is what drives `rdSet`/`rdEn` (`:950-956`).
Choosing the cancel route over gating `loadProbe.valid` directly was made for exactly this
reason; both work functionally, only one stays off the protected net. (W11 does both — but
the *probe-suppression* half is folded into `normalReqArm`, several levels upstream of
`loadProbe.valid`, so it too adds no level at the boundary.)

##### The honest claim

Three of five new sites are free by substitution. Two are real new fan-in/depth, one of them
on the protected BRAM read-address net. **The claim this spec makes is that the cost is
small, localised to two named nets, and has a named first lever each — not that it is
zero.** The control is the synth gate below, and the gate is mandatory, not advisory.

**Mandatory synth gate** (`synth-gate-every-slice.md`, standing rule; current goal ≥200 MHz,
current measured 197.278 MHz per the task #218/#219 ledger — i.e. this change starts from a
netlist that is *already* 0.069 ns short of the goal, so there is no headroom to spend):

- Full-core OOC synth **before** the first RTL commit of the implementation, on an
  uncontended machine (`machine-resource-budget.md`: never during another heavy JVM;
  `ported-tests-triage-2026-07-17.md`: FMax measurement is unreliable under contention,
  always re-verify uncontended). This is the baseline, recorded in the plan.
- Full-core OOC synth **after** the mux change and again after the walker/MMU changes, as
  two separate checkpoints, so a regression is attributable.
- The gate **measures**: post-route WNS and Fmax, plus — because the specific worries are
  now named — whether any of the top-10 failing paths newly traverses `rdSet`, `rdEn`,
  `loadCmdPort_payload_vaddr`, `loadCmdPort_ready`, `storePort_ready`, `loadProbePort_valid`,
  or the LS EU's aligned-queue pointers. A path list that is *shape-identical* to the
  baseline is the pass criterion, not merely a number that happens to hold. **The
  `loadCmdPort_payload_vaddr` → `cmdSet` → `rdSet` arc is checked first and explicitly**, per
  §5's item (1); it is the one the design knowingly widens.
- If FMax regresses, the levers in order are: (i) the §5 item (1) list — reorder the mux legs,
  then pre-mux the two walker `vaddr`s a cycle early; (ii) move the age/`force` comparison off
  the grant decision path (precompute `force(i)` a cycle early — it is a counter comparison
  against a constant and has a full cycle of slack by construction). **Reverting the design is
  not a lever**; it is the outcome only if every lever above is exhausted and measured.

---

## 6. Liveness

### 6.1 No circular translation dependency

Confirmed by the scoping memo §7 and re-confirmed here: `DcachePlugin.scala` contains no
`TranslationService`/`DTranslationService`/`MmuControlService` reference and never invokes
the MMU. It operates purely on pre-translated `paddr` supplied by its caller
(`DcacheTypes.scala:47-54`). Routing walker traffic *into* it therefore cannot create a
translate-inside-the-cache cycle: the walker supplies physical addresses it computed from
`rootPtr` + VA slices, with no translation involved.

### 6.2 W19 — the `S_DRAIN`/`S_APPLY` quiesce hole (a real new hazard)

`ExceptionUnit.scala:1362-1381` carries a written deadlock analysis for `S_DRAIN`, whose
final clause is:

> `dcQuiesced` is a conjunction of "no transaction in flight" terms, every one of which is
> cleared by a bounded, self-driving completion... **With the LS EU flushed, nothing re-arms
> them.**

After this change that last sentence is **false**: a walker can re-arm them. `dcIdleForMaint`
(`DcachePlugin.scala:1602-1608`) requires `!ldS1Valid && !ldS2Valid && !loadShadowValid &&
!earlyProbeValid && ... && (storeOutstanding === 0)`, and a walker issuing descriptor reads
keeps those set. `S_DRAIN` waits on `sqDrained && dcQuiesced` before `S_APPLY` pulses
`maintCmdOut`; if walkers keep the cache busy, quiesce may never be observed.

**W19:** `LsEuPlugin`'s mux takes a new `quiesceHold` input, wired from `ExceptionUnit` (a new
`Bool` in the shape of the existing `maintDoneIn`/`dcQuiesced` hooks, `:161-166`, `:390`, so
a standalone DUT still elaborates). While `quiesceHold` is high the mux **admits no new
walker command** on either direction; `CORE` is entirely unaffected.

**`quiesceHold` spans `S_DRAIN || S_APPLY`, not `S_DRAIN` alone.** *Corrected in the fix
pass.* `S_APPLY` (`ExceptionUnit.scala:1735`) is where `maintCmdOut` is actually **pulsed**,
but `DcachePlugin`'s `maintBusyReg` only rises on the maintenance FSM's own `IDLE → WAIT`
edge (`DcachePlugin.scala:1682`). That leaves a one-cycle window, entirely inside `S_APPLY`
and after `S_DRAIN` has released the hold, during which the cache is **fully open** and a
walker command can be admitted — reintroducing exactly the transaction the quiesce just
finished waiting out, one cycle before the maintenance FSM latches its own protection.
The consequence is bounded (the maintenance walk then completes with one extra walker
transaction in flight, not a deadlock — N4/§6.3's dependency graph is unchanged), but it
defeats the point of the quiesce and is free to close: extend the hold by one state.

`S_MAINTWAIT` and later need no hold: by then `maintBusyReg` is set and
`loadCmdPort.ready`/`storePort.ready` refuse walker commands on their own (N4).

Why this is safe and terminating:

- The hold is at **command** granularity, not walk granularity. A walker mid-walk simply
  stalls between descriptor reads. It does not need to complete the walk for the cache to
  quiesce — it needs only to have no command *in flight*, which is at most one AXI round
  trip away.
- Nothing the exception sequencer needs in `S_DRAIN` depends on a walk finishing. The ITLB
  walk gates instruction fetch, which is irrelevant while the frontend is squashed; the DTLB
  walk gates an LS EU translation, and the LS EU is flushed.
- `quiesceHold` is asserted **only in `S_DRAIN`/`S_APPLY`**, not for all of `excActive`. That
  is the distinction that keeps §4.2.4's requirement intact: the exception sequencer's own
  FSAVE/FRESTORE DTLB misses happen in the *later* frame-access states, where `quiesceHold`
  is low and a walk can run.
- The hold is therefore bounded by `S_DRAIN`'s own bounded exit (which the existing analysis
  already establishes for the `CORE`-only terms) plus `S_APPLY`'s single cycle.

**Verification obligation:** the implementation plan must update
`ExceptionUnit.scala:1362-1381`'s comment, because leaving a now-false deadlock proof in the
source is exactly the drift this project's review history keeps catching.

### 6.3 N4 — the maintenance-walk stall (bounded, not a deadlock)

Once `maintBusyReg` is set, `loadCmdPort.ready` is false for the whole maintenance walk
(`DcachePlugin.scala:981`, modulo the `resolveOldProbeDuringMaint` bypass, which a walker can
never take since it owns no probe). A walker load presented then simply waits.

**The store direction carries the identical gate, and the original N4 named only the load
side.** *Corrected in the fix pass:* `storePort.ready` has its own `!maintBusyReg` term
(`DcachePlugin.scala:1865-1866`), so a walker's U/M writeback stalls during a maintenance
walk in exactly the same way and for exactly the same duration. Same bounded class, same
proof, and it belongs alongside the load half rather than being discovered later.

This is a **new stall coupling on both directions** — today the walker has its own AXI master
and is immune — bounded by the walk's own `sets*ways = 128*4 = 512` iterations plus its
writeback beats.

It is not a deadlock because the maintenance walk's completion depends on nothing the walker
holds: it owns the array read port and the AXI write channels for its own duration and
completes autonomously (`DcachePlugin.scala:1481-1800`). The dependency graph is
walker → maintenance, never the reverse.

### 6.4 N3 — the pre-existing `umQueueFull` cycle

`DtlbPlugin.scala:238` (`walker.io.start := missReqReg.valid && !umQueueFull && ...`) blocks
a walk launch on a full 4-entry U/M queue; the queue drains only at commit
(`umq.io.commit`, `:308-311`); commit requires the ROB to retire, which can require the
translation the blocked walk would provide. This latent cycle exists at HEAD and is
**unchanged** by this design (it is upstream of every port this work touches). Recorded so a
reviewer does not attribute it to this change; not fixed here.

### 6.5 Store-side liveness under a held walker grant

A walker U/M store holds `stOwner` for one store. The SQ drain is blocked for that duration.
`DcachePlugin.scala:1865-1867` will not accept the walker's serial store until
`storeOutstanding === 0`, so the walker's own grant only becomes *useful* once the older
core stores have drained — which W7 already requires before hand-over anyway. There is no
cycle: the core's accepted store descriptors complete without needing the store *port*
(they are past admission), and the walker's store completes in one AXI B round trip.

### 6.6 Load-side liveness under W30's strengthened `excLoadAdmit` (round 4)

W30 makes the exception sequencer's load wait for `ldOwnerFifoEmpty` — i.e. for **every** other
client's in-flight response, not just a walker's. The full argument lives with the decision
(§4.2.3, W30 part (2)); it is restated here so §6 remains the single place a reader can check
that no wait added by this design can hang:

- **The wait terminates.** Every accepted `loadCmd` yields exactly one `loadRsp` (§4.2.3's
  in-order premise), and `DcachePlugin` produces those responses autonomously — a refill
  terminates, and an AXI error still produces a response via `busFaultResp` rather than
  swallowing the command. `ldOwnerFifoEmpty` therefore becomes true within at most one refill,
  bounded by the same AXI-completion assumption every other wait in this document rests on.
- **There is no cycle.** Nothing the FIFO is waiting on depends on the exception sequencer:
  `CORE-LS` commands are already suppressed by `coreLsLoadAdmit`'s `!excActive`, and walker
  commands by `walkerLoadAdmit`'s `!ldBusyExc`. The exception sequencer waits on responses to
  commands accepted *before* it presented, never on new work it is itself blocking.
- **The exception sequencer cannot be starved by walkers. Stated PER-LOAD, which is the scope
  in which it is true** *(round 5: round 4's wording read as if it held continuously across a
  whole multi-load exception episode; it does not, and the difference matters to a plan-writer
  who might otherwise treat the episode-level reading as an invariant to lean on).*
  `excLoadCmdValid` is `ExceptionUnit`'s **held**, registered `ldoValidReg`
  (`ExceptionUnit.scala:798-803`), asserted continuously from the cycle the FSM enters a `*REQ`
  state until **that load's** `dcLoadCmd.fire`. So for each individual exception load, from its
  first presenting cycle `ldBusyExc` is true, no *new* walker command can be admitted behind it,
  and the FIFO can only shrink — so **that** load's wait is bounded and it is guaranteed the
  port. What is **not** true is that `ldBusyExc` stays high for the whole episode: between two
  consecutive loads of one exception sequence there is a real 1-2 cycle window in which it is
  low (`excLoadOutstanding` clears one cycle after the response lands, per W30 part (1), and
  `ldoValidReg` does not rise again until the **next** `*REQ` state is entered), and a walker
  **can** be admitted in it. That is deliberate and does not break the bound: whatever is
  admitted in that window must complete before the next exception load is admitted (W30 part
  (2)'s `ldOwnerFifoEmpty`), and each interposition costs one bounded D-cache access. The
  worst case is **at most one walker command per cycle of the window — i.e. at most two per
  exception load** (two walkers, a window of at most two cycles), not per episode. *(Round-6
  correction: round 5 wrote "at most one walker command interposed per exception load". That
  count does not follow from the window as stated. `walkerLoadAdmit(i)` carries
  `!ldOwnerFifo.full`, **not** an empty-or-single-slot term, and W9's round-robin rotates the
  grant between ITLB and DTLB — so ITLB can be granted and fire on the window's first cycle and
  DTLB on its second, the FIFO not yet being full. Back-to-back accepts of exactly this kind are
  what the FIFO's proven max-of-3-concurrent-entries bound already presupposes. Only the count
  was wrong; the boundedness conclusion this bullet exists to establish is unaffected, since a
  bounded window admitting at most one command per cycle interposes a bounded number of bounded
  D-cache accesses either way.)* The per-load form is the load-side counterpart of §6.5's
  argument and relies on the same "past admission completes without the port" property.
- **N4 interaction unchanged.** A maintenance walk still refuses both directions for its
  bounded duration (`!maintBusyReg`); W30 adds no new interaction with it, because the
  exception sequencer's wait is on the FIFO, not on `loadCmdPort.ready`.

---

## 7. Out of scope

Explicitly **not** addressed by this design, listed so no reviewer or plan-writer assumes
otherwise:

1. **Atomic descriptor RMW (N1).** No bus lock, no `LOCK`/`LOCKE` emulation. The
   read-then-update-later race on a descriptor byte is unchanged.
2. **I-cache coherency with page tables.** `IcachePlugin` is untouched; it never reads
   descriptors.
3. **External-agent (SoC/DMA) coherency.** No snoop port is built. W1's WRITETHROUGH choice
   *helps* here (memory is never behind the array for descriptors) but this design makes no
   coherency guarantee to non-CPU bus masters.
4. **Indirect descriptors.** `TableWalker.scala:185-187` still treats them as non-resident.
5. **Multi-outstanding walks.** Both walkers stay single-outstanding. The MSHR plan's "V2c
   (walker fold)" remains unbuilt and is not made easier or harder by this.
6. **The `mmu-atc-m-bit-tracking` branch's own M-bit logic fix.** That branch (`c629bec`)
   contains a *separate*, correct fix that was blocked on a synth gate. This design closes
   the coherency half. Whether `mmu_atc_write_hit_sets_modified` passes with only this half
   is an empirical question the verification plan (§10.1) answers; if it does not, the
   remaining delta is that branch's work, not new scope here.
7. **`axi_i` and the socket byte-order/MMIO-sizing work.** Untouched. D1-D6, D11, D21-D26,
   D29-D30 and SOC-1..SOC-4 are all unaffected.
8. **Changing scope to read-only or write-only.** The mandate is both. No blocking
   feasibility concern was found that would justify narrowing (§11.2).

---

## 8. Blast radius (file inventory)

**`src/main` — modified:**

| File | Change |
|---|---|
| `mmu/TableWalker.scala` | Delete `io.axi`, `axiCfg`, `selectWord`; add the four client hooks; retarget `issueRead()`/`RD_*`; handle `loadRsp.fault` (W16) |
| `mmu/ItlbPlugin.scala` | Delete `walkerAxi`, its D27 guard, the AW/W drain FSM; add client hooks; keep `drainBeat`/`drainStrb` (W17) |
| `mmu/DtlbPlugin.scala` | Same as ITLB |
| `execute/LsEuPlugin.scala` | Extend the `:2020-2078` mux to 4 sources; add `ldOwner`/`stOwner`/age/`force`/`walkRr`, the **W7 depth-4 ownership FIFO** + `ldRspTag`/`rspOwner`/`ldOwnerFifoEmpty`/`ldOwnerFifoHoldsWalker`/`ldOwnerFifoOccupancy` (every one of these read **pre-pop/pre-push**, §4.2.3 — `ldOwnerFifoOccupancy` is what §4.3's second assertion reads and the pre-pop reading is load-bearing there), `excLoadOutstanding`, `coreStOutstanding` (counting the exception sequencer's stores too); the admission predicates `coreLsLoadAdmit`/`excLoadAdmit`/`excStoreAdmit`/`walkerLoadAdmit`/`walkerStoreAdmit` (§4.2.3); fold `dcLoadHeldByOther` into `:863`, `:1774-1776` and (via `coreLsLoadAdmit`) `:716`/`:756`; **implement W27's table (§4.2.7) in full — all 34 rows: the 11 `NEW — FIX` rows as named steps, the 3 `NEW — stated` rows (`:263-264`/`:757-764` driver ordering, and the deliberately-unqualified FIFO pop) as recorded constraints, the 1 `NEW — carries its own qualification` row (W29's FIFO push), and the 19 already-decided / needs-no-term rows as recorded no-ops**, which subsumes W23 (`:2115` + `excLoadOutstanding`'s set term, qualified by `excLoadAdmit`, **not** by an owner comparison), W24 (`:265`), the split-leg visibility+admission fix (`:756`/`:1508`/`:1536`), `bkCompletes` (`:1574-1575`), the store-side W23 twin (`:2069`/`:2076`), the `:2027`/`:2067` presentation headers, `:2073`, the `ldRspTag === CORE_LS` qualifications at `:720`/`:1520`/`:1541`/`:1574-1575`, and the round-4 rows (`:886`, `:757-764`, the FIFO push/pop, W30's clear); implement **W29**'s four-valued `ldRspTag` push encode + the one-hot exclusivity assertion, and **W30**'s tagged `excLoadOutstanding` clear term + `ldOwnerFifoEmpty` in `excLoadAdmit`; satisfy **W28**'s same-cycle owner-coherence invariant in **all three clauses** (reads, the FIFO push payload, and `ldOwner`/`stOwner`'s update rule) and state which of its two admissible shapes is implemented; gate `sq.io.drainAck`/`drainErr` and `exc.dcStoreAck` (W13); the W26 wedge counters; the walker pass-through hooks; the simulation assertions |
| `exception/ExceptionUnit.scala` | Export the `quiesceHold` bool over `S_DRAIN \|\| S_APPLY` (W19/M1); correct the `:1362-1381` comment |
| `cache/DcacheTypes.scala` | **Declaration-only:** add `DLoadToken.WALK_ITLB = 0x81` / `WALK_DTLB = 0x82` next to the existing width constant, **and amend the `:6-8` token-layout doc comment** so it describes the walker values rather than mis-reading them as exception-unit tokens (W25). No bundle field, no behavioural change — see the note below |
| `socket/AxiDMerge.scala` | Shrink to 2 read owners / 1 write owner (W21) |
| `socket/AxiDMergePlugin.scala` | Drop the `itlb`/`dtlb` connections and the `socketMerged` requirement on those two plugins |
| `top/FullCoreSynth.scala` | Wire the walker client hooks; remove the `itlbAxi`/`dtlbAxi` top-level ports |
| `cache/AxiIds.scala` | Comment `WALK_READ`/`WALK_WRITE` as unused-but-reserved (W20) |

**`src/main` — deliberately NOT modified:** `cache/DcachePlugin.scala`, `ls/StoreQueue.scala`,
`cache/IcachePlugin.scala`. (`cache/DcacheTypes.scala` receives a *declaration-only* addition
for W25 — two `Int` constants beside the existing `DLoadToken.Width`. No bundle field is
added or changed, so W4's "`DcachePlugin` gains zero new client awareness" and W7's "no
`DcacheService` bundle change" both stand. If a plan prefers to keep the *constants* out of
`DcacheTypes.scala`, they may instead live in `LsEuPlugin` beside the existing `0x80`
literal; the *disjointness* is the decision, its file is not. **The `:6-8` doc-comment
amendment is required either way** — the token field it documents is the one the walker
values travel in, so the comment is false wherever the constants are declared, and
`DcacheTypes.scala` cannot be left byte-identical.)

### 8.1 Test-wiring churn — corrected inventory

*The original §8 stated 28 files / 23 full-core DUTs and characterised `sharedMem` as the
usual case. All three numbers and the characterisation were wrong. Corrected here against a
direct sweep of the tree; the corrected shape is materially more expensive than the original
implied, and the expensive half is the one the original filed as the exception.*

**24 files** reference `walkerAxi`/`itlbAxi`/`dtlbAxi` in `src/test` (not 28). Four buckets:

| Bucket | Count | Files |
|---|---:|---|
| Full-core / lock-step / fuzz DUTs | **12** | `bench/IpcBenchSpec`, `lockstep/ExecuteLockStepSpec`, `exception/FsaveFrestoreSpec`, `exception/ExceptionStoreDrainArbSpec`, `execute/FpuControlWiringSpec`, `fuzz/FuzzLockStepSpec`, `fuzz/PortedTestRunner`, `fuzz/Cmp2HangTraceSpec`, `fuzz/EoriAddaDecodeTraceSpec`, `fuzz/MiHangTraceSpec`, `fuzz/P27HangTraceSpec`, `fuzz/WildPcA7TraceSpec` |
| **LS-cluster DUTs** (a bucket the original spec did not have at all) | **5** | `ls/DtlbCrossPageSplitSpec`, `ls/DtlbMissFlushSpec`, `ls/DtlbViptChangedVpnSpec`, `ls/LsEuFastPreciseSpec`, `ls/PreciseDrainIrqRaceSpec` |
| MMU-only DUTs, no `DcachePlugin` | **5** | `mmu/DtlbSpec`, `mmu/ItlbSpec`, `mmu/UmWriteSpec`, `mmu/MmuControlSpec`, `mmu/DtlbStreamPipelineSpec` |
| Other DUTs with no `DcachePlugin` (missed entirely by the original) | **2** | `cache/IcacheParallelViptSpec`, `frontend/FetchAlignResidentCadenceSpec` |

**The `sharedMem` characterisation was INVERTED, and this is the expensive correction.** The
original said the attached walker memory is "usually `sharedMem = <the same memory the
D-cache uses>`", with non-shared memory as a rare exception needing per-site review. The
truth is the opposite:

- Only **6 of 24** files pass `sharedMem` at *every* walker attach site:
  `exception/FsaveFrestoreSpec`, `exception/ExceptionStoreDrainArbSpec`,
  `execute/FpuControlWiringSpec`, `fuzz/PortedTestRunner`, `fuzz/P27HangTraceSpec`,
  `ls/PreciseDrainIrqRaceSpec`.
- `lockstep/ExecuteLockStepSpec` — by far the largest single site — passes `sharedMem` at only
  **5 of its 19** walker attach sites.
- `bench/IpcBenchSpec.scala:593-594` was cited by the original as a `sharedMem` example. It is
  **not**: it is a plain `AxiMemModel.attachFull(dut.dtlb.walkerAxi, cd, memCfg)` /
  `attachFull(dut.itlb.walkerAxi, …)` pair with no `sharedMem` argument at all. **Citation
  withdrawn.**

So the **dominant** case is the one the original flagged as rare: a *separate, non-shared,
all-zero* page-table memory attached to the walker. Deleting those attach lines is a genuine
behaviour change per site — those DUTs read all-zero descriptors today and will read the
shared D-cache memory afterwards — and each needs individual review and, in many cases, a
page-table image to be planted where none existed. **This is the expensive part of the churn
and the plan must budget for it as such.** The 6 fully-shared files (plus
`ExecuteLockStepSpec`'s 5 shared sites) are the cheap deletions.

**W18's `DcacheClientMemAgent` is needed by 8 files / 8 DUT classes, not 5.** The original
counted only the MMU-only bucket. Add:

- `cache/IcacheParallelViptSpec` — `walkerAxi.ar`-fire counters at `:111` and `:166`;
- `frontend/FetchAlignResidentCadenceSpec` — `walkerAxi.ar`-fire counter plus an address
  trace at `:139`/`:141`;
- `ls/DtlbMissFlushSpec`'s **first** `Dut` class (`:97`, `DtlbCleanMissSerializationSpec`),
  which has no `DcachePlugin` even though the file's *other* DUT (`DtlbFlushReuseSpec`) does.

**The count is 8 files / 8 DUT classes, not 7/8.** *Arithmetic corrected in the round-3 fix
pass; the enumeration itself was right, only the total was wrong.* Seven files contribute one
whole DUT each — `DtlbSpec`, `ItlbSpec`, `UmWriteSpec`, `MmuControlSpec`,
`DtlbStreamPipelineSpec`, `IcacheParallelViptSpec`, `FetchAlignResidentCadenceSpec`, each with
exactly one DUT class and no `DcachePlugin` reference — and `ls/DtlbMissFlushSpec` is an
**eighth** file contributing the eighth DUT class. That file is not *wholly* in this bucket
(its sibling DUT does instantiate `DcachePlugin`), which is what produced the miscount, but it
is still a file the plan must open and edit.

Assertions counting `walkerAxi.ar` fires are rewritten to count `walkLoadCmd` fires:
`DtlbSpec.scala:135,177,230`, `UmWriteSpec.scala:220,290,351`,
`DtlbStreamPipelineSpec.scala:118`, plus the four new sites above. **`UmWriteSpec.scala:182`
is NOT one of them** — the original cited it as an ar-fire counter; it is actually a
`while (!(walkerAxi.ar.valid && walkerAxi.ar.ready) …)` **wait loop** guarding a flush
injection. It still needs retargeting, but as a *wait predicate* on `walkLoadCmd.fire`, which
is a different edit with different failure modes (a wrong predicate hangs the test rather
than mis-counting). **Citation corrected.**

**`socket/WalkerIdGuardSpec`** is deleted outright (W20).

---

## 9. Interaction with the axi-socket-adapter plan

### 9.1 W20 — Task 4 (`38e6c31`, `56f2438`)

Task 4 delivered two separable things:

1. Fail-closed ID guards on the walkers' own `B`/`R` channels — `ItlbPlugin.scala:92-93`,
   `DtlbPlugin.scala:97-98`, `TableWalker.scala:114-119` — plus the dynamic
   `WalkerIdGuardSpec`.
2. The `RESET_VEC` ARID (`AxiIds.scala`), used by `ResetVectorPlugin` / Task 9.

Disposition: **do not revert either commit.** (1) is deleted *along with its host* — the
guards guard `walkerAxi`, and `walkerAxi` ceases to exist, so this is not "dead code left
with a comment", it is code whose subject is removed. `WalkerIdGuardSpec` is deleted with it.
(2) is entirely untouched and Task 9 still needs it. A `git revert` would take out (2) as
collateral and would rewrite the history of merged, reviewed work for no benefit.

D27's *principle* — fail closed, never fail open, on an unrecognised response — survives and
relocates: it is exactly what W13's `stOwner` demux does, and what
`DcacheTypes.scala:126-133`'s existing `storeAck` id-demux already embodies inside
`DcachePlugin`. The scoping memo §5 predicted this ("the PRINCIPLE survives inside
`DcachePlugin` regardless"); this spec makes it concrete.

`AxiIds.WALK_READ` / `WALK_WRITE` become unreferenced. They are **kept defined**, with a
comment recording why, because the socket plan's global constraint forbids renumbering them
and removing them would shift nothing but would invite exactly that. *Citation corrected in
the fix pass:* the constraint is **bullet #11** of the plan's `## Global Constraints` list
(`docs/superpowers/plans/2026-08-18-axi-socket-adapter-implementation-plan.md:23`,
"`AxiIds.scala` is the single source of truth for AXI IDs"), quoting socket spec §4.3. It is
not numbered "Global Constraint #3" anywhere; that label does not exist in the plan.

### 9.2 W21 — Task 5 (`cf56ed1`, `AxiDMerge`)

| Side | Today (Task 5 as merged) | After |
|---|---|---|
| Read | 4 owners: `DCACHE`, `ITLB`, `DTLB`, `RESETVEC` | **2**: `DCACHE`, `RESETVEC` |
| Write | 3 owners: `DCACHE`, `ITLB`, `DTLB` | **1**: `DCACHE` — a pass-through, not an arbiter |

Decision: **shrink it.** The alternatives were considered and rejected:

- *Leave the walker slots wired to constant-`False` valids.* Rejected: two permanently-dead
  owner slots are unreachable silicon and an unreachable arbitration path no test can
  exercise, which is worse than absent — a future reader would have to re-derive why they
  never fire.
- *Repurpose `AxiDMerge` as the new `DcacheService`-level arbiter.* Rejected by W4: its code
  operates on `Axi4` bundles, and W4 does not build a component at all. Its *discipline* is
  reused — W8's aging counter is D20's "progress resets the count" shape, and W26 reuses
  D20's report-don't-fabricate posture — but as a handful of lines inside an existing mux,
  not as a component. **W5's per-direction independence is deliberately NOT filed here as
  "D9's argument"**: D9's deadlock reasoning does not transfer down a layer, and W5 rests on
  its own (throughput) grounds instead — see §4.2.2.
- *Delete `AxiDMerge` entirely.* Rejected: `DCACHE` vs `RESETVEC` is a real 2-owner read
  merge (D13 deliberately routes the vector-0 fetch through `axi_d` rather than adding a
  third socket master), and D20's watchdog still covers it.

##### There are TWO D20 watchdogs, not one — and dropping one is a deliberate deviation

*Corrected and expanded in the fix pass. The original text ("The read-side D20 watchdog
stays") read as though a read-side watchdog were the only one that exists.* `AxiDMerge`
carries **two**, one per direction, exactly as D20-as-written requires ("**each direction** of
`AxiDMergePlugin` carries a bounded-grant watchdog", socket design spec `:1162`):

- **read side** — `AxiDMerge.scala:225-230` (`progress = out.ar.fire || out.r.fire`);
  *(locator corrected twice: the round-2 text said `:222-230`, and the round-3 review proposed
  `:224-231`, which is itself off by one at both ends — `:223-224` are the block's comment and
  `:231` closes the enclosing `Area`. `:225-230` is `progress`→`wedge`, exactly parallel to the
  write side's `:298-303` below, which was already exact.)*
- **write side** — `AxiDMerge.scala:298-303` (`progress = out.aw.fire || out.w.fire ||
  out.b.fire`);
- combined at `:306-307` — `io.wedge := rd.wedge || wr.wedge`, `io.wedgeIsRead := rd.wedge`.

The read-side watchdog **stays**. The write side, dropping to a single owner, becomes a
pass-through and needs no grant state — and therefore no bounded-grant watchdog, since there
is no other owner left to starve. **That is a deliberate deviation from D20 as written, and
W21 states it as one rather than quietly satisfying half of a requirement.** The
justification is that D20's own stated scope is the wedge mode *this design creates*
("three owners now share one port, so an owner that never completes starves the other two",
socket spec §8.3); with one write owner that mode is structurally absent, and D19 —
not D20 — is what covers a single master's transaction terminating.

**Named consequences the original spec did not mention:**

1. **`io.wedgeIsRead` becomes a constant `True`** (a dead port), because `wr.wedge` ceases to
   exist and `io.wedge` reduces to `rd.wedge`. The plan must decide explicitly between
   keeping it tied `True` with a comment (preserving D28's halt-reason payload shape) and
   removing it (a port-surface change on `AxiDMerge`, which `AxiDMergePlugin` and
   `AxiDMergeSpec` then both follow). **Recommendation: keep it, tied `True` with a comment**
   — D28's reason encoding is shared with D15 and a shape change there is gratuitous scope.
2. **`AxiDMerge.scala:313-337`'s section-4.4 assertion block goes largely vacuous.** With
   2 read owners and 1 write owner: the `bHot <= 1` assertion becomes trivially true (one
   `b.valid` source), `!(wr.grant && wr.busy)` becomes vacuous with no grant state, the
   write-grant-cleared-on-`b.fire` assertion loses its subject, and `rHot <= 1` shrinks from
   4 terms to 2. Leaving vacuous assertions in place is worse than removing them: they read
   as coverage that does not exist. §10.2's "Reworked `AxiDMergeSpec`" line therefore
   explicitly covers **pruning and adjusting this assertion block**, not merely surviving
   unchanged.

Downstream: `ResetVectorPlugin` (Task 9) rides flat `rv*` ports (`AxiDMerge.scala:110-122`;
*citation corrected — `:123-125` are the `wedge`/`wedgeIsRead`/`out` fields, not part of the
flat reset-vector port block*) and is **unaffected in interface shape** — confirmed.
`M68kSocketTop` (Task 13) consumes the single merged `axi_d` and never references
`itlbAxi`/`dtlbAxi` *in its RTL* — **unaffected in interface shape**, but **not textually
untouched**: the socket plan's Task 13 cites `itlbAxi_aw_payload_len` twice as a
netlist-naming example (`…-implementation-plan.md:4717` and the same text inside the
prescribed `SocketPorts` doc comment at `:4855`). Those examples name a signal that ceases to
exist once Task 5R lands, so both must be re-pointed at a surviving example (e.g.
`DcachePlugin_logic_axi_aw_payload_len`). Small, but exactly the kind of stale citation this
spec's §9.3 exists to prevent. Tasks 6, 7,
8 (`MmioCover`, the INHIBITED load/store sequencers) are internal to `DcachePlugin`'s own
issuers and have no notion of caller identity — **unaffected**; note in passing that W1's
WRITETHROUGH policy means walker traffic never engages the INHIBITED sequencer at all unless
`CACR.DE = 0`, in which case it engages it identically to every other access. Tasks 10, 11,
12, 14 are orthogonal.

### 9.3 W22 — plan mechanics

This work is a **separate implementation plan** (`docs/superpowers/plans/2026-08-18-walker-
dcache-passthrough-implementation-plan.md`, to be written), not new tasks bolted onto the
socket plan: it has its own correctness thesis, its own verification set, and it touches the
MMU/LSU rather than the socket boundary.

**Four** edits are required *in* the socket plan and spec, and none is optional. (The original
draft listed two; the fix pass adds items 3 and 4.)

1. **A superseding `Task 5R`** inserted into
   `docs/superpowers/plans/2026-08-18-axi-socket-adapter-implementation-plan.md` after Task 5,
   stating that Task 5's merged output no longer matches its own spec sections and carrying
   the W21 shrink. Task 5 is **already merged**; leaving the plan claiming a 4-owner arbiter
   while the netlist has 2 is precisely the kind of stale-plan drift this project's process
   exists to prevent.
2. **An addendum block** in `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md`
   (in the style of its own two existing "Added by the ... pass" blocks) recording that D7's
   forcing constraint is dissolved, D8's ITLB-vs-DTLB disambiguation argument is moot, D9's
   write-side half is vacuous **and its deadlock argument does not transfer down a layer**
   (§4.2.2), D10 is unchanged, D20's read-side watchdog survives while its **write-side
   watchdog is deliberately dropped** (§9.2, a stated deviation), D27's walker sites are
   removed with the principle relocated per §9.1, and **D19 is re-established for a new
   consumer** per item 3 below.

3. **A D19 re-establishment note.** *Added in the fix pass; §9.3 previously claimed "D19 is
   unaffected", which is wrong on D19's own terms.* D19's evidence list — the set of citations
   that make "every AXI transaction this core issues terminates on a response of *any* resp
   code, and every FSM that waits on a response treats SLVERR/DECERR as terminating" a stated
   invariant rather than a hope — **explicitly names `TableWalker.scala:114`** (the descriptor
   `R` consumer) as one of its sites (socket design spec `:1146` — *locator corrected in the
   round-3 fix pass; `:1145` is the preceding `:1210-1216` eviction-B evidence line*). This redesign **deletes
   that exact site**. Worse, the socket spec's own §12 states the invariant "must be
   re-checked, not assumed, by any future work that adds an AXI response consumer" — and this
   work both removes one consumer and adds a new response path at a different layer.

   The re-establishment is mostly already written, in **W16** (§4.5): the walker's new
   terminating-response handling is `loadRsp.payload.fault` → `MmuFaultReason.NON_RESIDENT` →
   the existing `FINISH` path, which is strictly *stronger* than what it replaces (today a
   walker read's AXI error is silently ignored — `TableWalker` never inspects `r.resp` at
   all). What was missing is the **connection**: W16 and D19 were two disconnected sections,
   and the original §4.5 called the fault handling a "small, genuine improvement, not new
   scope" — wording a plan-writer could reasonably read as optional. §4.5 is corrected to say
   it is mandatory, and the addendum block must say the same. §4.5's W16 is D19's replacement
   evidence for the descriptor-read path: `DcachePlugin`'s refill R consumer (`:1258-1272`,
   already on D19's own list) now terminates the walk's transaction, and `loadRsp.fault`
   carries that termination to the walker's FSM.

4. **Two textual citation updates in the socket plan's Task 13.** Task 13 cites
   `itlbAxi_aw_payload_len` as a netlist-naming example at
   `…-axi-socket-adapter-implementation-plan.md:4717` and again inside the prescribed
   `SocketPorts` doc comment at `:4855`. Both name a signal that ceases to exist once Task 5R
   lands. Task 13's *interface shape* is unaffected (§9.2), but leaving it citing a deleted
   net is the same stale-citation drift item 1 exists to prevent. Re-point both at a
   surviving example, e.g. `DcachePlugin_logic_axi_aw_payload_len`.

**Sequencing.** This work should land **after** the socket plan's Tasks 7/8 (the INHIBITED
sequencers, in flight in `DcachePlugin.scala`) and **before** Task 13 (`M68kSocketTop`
assembly), because it changes the master count `M68kSocketTop` assembles and because
`DcachePlugin.scala` being a moving target during Tasks 7/8 is exactly the hazard the
scoping memo §7 flagged ("do not rely on its current shape").

---

## 10. Verification strategy

### 10.1 The bug this exists to fix

- **`mmu_atc_write_hit_sets_modified`** (ported corpus) is the acceptance test for §1.1. It
  must move from fail to pass, and the plan must say what to do if it does not: the residual
  is `mmu-atc-m-bit-tracking`'s own M-bit logic fix (`c629bec`), which was blocked on a synth
  gate — not new design work here.
- **A new directed test for §1.2** (the undocumented read-side bug), because no existing test
  covers it. Shape: MMU on, `CACR.DE = 1`, a COPYBACK page mapping page-table memory; ordinary
  supervisor `move.l` stores rewrite a leaf descriptor (leaving the line dirty in L1D, not
  evicted); then touch a VA that forces a walk of that descriptor; assert the resulting
  translation reflects the **stored** descriptor, not the pre-store backing-memory image.
  This test must be shown to **fail at HEAD** before the fix, or it proves nothing.

### 10.2 Directed unit tests (new / reworked)

| Test | What it pins |
|---|---|
| `DcacheClientMemAgent` (W18) + reworked `DtlbSpec`, `ItlbSpec`, `UmWriteSpec`, `MmuControlSpec`, `DtlbStreamPipelineSpec`, `IcacheParallelViptSpec`, `FetchAlignResidentCadenceSpec`, `DtlbMissFlushSpec`'s first `Dut` — **8 files / 8 DUT classes**, per §8.1 | Walks and U/M drains still work against a behavioural `DcacheService` client interface |
| New `WalkerDcachePortArbSpec` | (a) the surviving drain-to-zero holds: no walker load is outstanding while the exception sequencer has one, and no store hand-over occurs with a core store outstanding (W7); (a′) the **W7 ownership FIFO** routes correctly with `CORE` and a walker concurrently outstanding, including the ≤3-deep case, and `alignedCount`/`alignedRspPtr` are untouched by a walker response (§4.2.3 caveat (a)); (b) a walker starved for `LIMIT` cycles under continuous SQ drain **does** get the port (W8) — the direct answer to the scoping memo §4's "a busy store queue could postpone a walker indefinitely"; (c) a walker cannot chain-hold against `CORE-LS` (W8.4); (d) ITLB/DTLB alternate (W9); (e) **W23** — a walker `loadCmd.ready` in the same cycle as a presented exception-sequencer load does not advance the exception FSM and does not set `excLoadOutstanding`; (e′) **W23's round-3 correction, the C2 regression test** — with a **walker entry sitting in the ownership FIFO** and the exception sequencer's command therefore correctly *withheld* (`loadCmd.valid` low) while `DcachePlugin` holds `loadCmd.ready` **high**, assert `excLoadCmdReady` never pulses, the exception FSM does not advance out of `E_VECREQ`/`F_HDRREQ`/`R_*`, and the walker's `loadRsp` is not consumed by the exception path. **This is the case round 2's fix line passed and round 3's must fail without `excLoadAdmit`** — the test must be shown to fail against the round-2 line. **Round-6 addition, mandatory and not optional colour: the row must additionally assert, for every cycle of the withheld window, that `dcache.loadCmd.valid` is LOW — equivalently that no `dcache.loadCmd.fire` occurs at all and `ldOwnerFifo` gains no new entry (occupancy is unchanged across the whole window).** The three original assertions all read either `excLoadCmdReady` or the exception FSM's own state, and **none of them reads `loadCmd.valid`/`fire`**, so as originally written this row was blind to the failure it is most often cited as covering: an **unqualified presentation header** at `:2027` leaves `excLoadAdmit` correct and low (so `excLoadCmdReady` stays low, so the FSM correctly does not advance, so `ExceptionUnit` never samples a response — all three original assertions **pass**) while still driving `dcache.loadCmd.valid := True` into a high `ready`, firing the port spuriously every cycle and pushing bogus `CORE_LS` entries behind the walker's. The `valid`-low / no-`fire` / occupancy-unchanged assertion is what observes that directly, and the test must be shown to **fail** against an unqualified `when(excActive && excLoadCmdValid)` presentation header, not only against the round-2 `excLoadCmdReady` line; (f) **W24** — every `StoreQueue` entry reaches the cache while a walker U/M store owns the store port, asserted against a **cache-side byte-write observer**, never against the SQ's own pointers |
| New `WalkerSplitLoadRaceSpec` (**W27**, round 3) | (a) **slot-B launch vs a walker grant** — drive a cross-line split load to `WAIT_A` with `aDone` low so the arbiter sees CORE-LS idle, grant a walker, then let slot A land so `llReg.valid` rises in the same cycle the walker's command fires; assert the BK FSM does **not** leave `WAIT_A` and that slot B is genuinely issued to the cache (asserted on a cache-side command observer, not on the FSM's own state). Must be shown to advance-without-issuing before the `:1536` fix; the `:1508` `LAUNCH` twin gets the same shape for slot A. (b) **`bkCompletes`'s `WAIT_B` arm fed a walker response** — with the FSM in `WAIT_B`, deliver a walker `loadRsp`; assert `bkCompletes`/`backCompFires` stay low, `S1` is not released, and the merged load still waits for its own slot-B line. Must be shown to complete the load from the descriptor word before the `:1574-1575` fix. (c) **arbiter visibility** — a split load pending in `WAIT_A` registers as a CORE-LS request (half (i) of the fix), so a walker's aging counter does not free-run against an invisible requester |
| New coverage in `WalkerDcachePortArbSpec` (**W27** store side, round 3) | **An exception-sequencer store racing a walker's in-flight U/M store**: with `stOwner === WALKER` and a walker U/M store accepted but not yet acked, raise `excActive && excStoreValid`; assert the exception payload does **not** reach `dcache.store`, `excStoreReady` does **not** pulse, `excStoreOutstanding` does **not** set, and the walker's `storeAck` is demuxed to `umq.io.drainAck` with the **descriptor byte actually observed in memory/array**. Without the `:2067`/`:2069`/`:2076` fixes this retires a U/M queue entry whose byte was never written — §1.1's bug reintroduced — so the test must be shown to fail first |
| New coverage in `WalkerDcachePortArbSpec` (**W28** clause (a), round 3) | **Same-cycle owner coherence, READ side**: assert there is no cycle in which a `dcache.loadCmd.fire` (or `dcache.store.fire`) presents client X's payload while the owner value every qualification term reads names client Y. Implemented as the `GenerationFlags.simulation` assertion §4.2.8 requires, plus a directed grant-edge test that walks the owner through every transition with a command presented on each side of it. **Round-4 note: this check alone is NOT sufficient and must not be treated as if it were** — the round-4 review demonstrated it passes trivially (it compares an admit-predicate-derived value against itself) while C-R4-1 is live. It is necessary, not complete; the row below is its other half |
| New coverage in `WalkerDcachePortArbSpec` (**W28** clause (b) / **W29**, round 4) | **Same-cycle owner coherence, WRITE side — the pushed FIFO tag vs. the leg that actually fired.** Not "payload presented vs. owner qualified": on every `dcache.loadCmd.fire`, sample which of `coreLsLoadAdmit` / `excLoadAdmit` / `walkerLoadAdmit(WALKER_ITLB_IDX)` / `walkerLoadAdmit(WALKER_DTLB_IDX)` was true that cycle and assert the value pushed into `ldOwnerFifo` is that leg's tag. Plus the one-hot assertion in its round-6 form (**exactly** one admission predicate true on every `dcache.loadCmd.fire`, §4.2.3 — not "at most one per cycle", which the zero-predicate spurious-fire state satisfies). **The directed interleaving is mandatory and is the specific C-R4-1 scenario**: grant a walker but withhold its fire (hold `loadCmdPort.ready` low via `loadShadowValid`) so the grant register lands on `ITLB`; on the **very next cycle** raise `excActive && excLoadCmdValid` and release `ready`, so the CORE-EXC leg fires while the grant register still reads `ITLB`; assert the FIFO entry is `CORE_EXC`, that the response is delivered to the exception path, and that **no** walker `loadRsp` demux fires. Then the mirror (grant on `CORE` at N-1, a walker firing at N; assert the entry is the walker's and that `alignedRspFire` / the exception path do not consume it). **Both must be shown to fail against the `push <= grantedOwner` form**, or the test is not pinning the bug |
| New coverage in `WalkerDcachePortArbSpec` (**W30**, round 4) | **`excLoadOutstanding`'s clear term vs. a CORE-LS straggler.** Force the §4.2.3 W30 trace: issue an ordinary CORE-LS load that **misses** (long refill); raise an RTE/exception so `excActive` rises with that response still owed; let `REPLAY` return the load FSM to `IDLE` with the replayed read still in S1 so `loadCmdPort.ready` goes high one cycle before the CORE-LS response lands; let the exception sequencer's load fire in that cycle. Then assert **(1)** `excLoadOutstanding` does **not** clear on the CORE-LS response (it carries `ldRspTag === CORE_LS`), **(2)** no walker is admitted while the exception's own load is still owed, and **(3)** the exception sequencer's captured word (`popSr`/`vecTarget`/the FRESTORE header) is its **own** data, not the LS load's. Test (1) must be shown to fail against the naive `when(dcache.loadRsp.valid && excLoadOutstanding)` mirror. Test (3) is the part W30 part (2) closes: with `excLoadAdmit`'s FIFO term at round-3 strength the exception's command is admitted in that cycle and (3) fails — **and (3) can be demonstrated failing at current HEAD too** (§11.1 item 9), which is the strongest available evidence that the invariant a naive mirror would need is genuinely false rather than merely unproven |
| New `WalkerProbeDeadlockSpec` | The §4.2.6 deadlock: fill all 4 probe slots, then demand an ITLB walk, and require forward progress. Must be shown to **hang** without W11's `probeCancelAll` term. Plus **W25**: a walker command whose `vaddr` equals a live probe's `vaddr` (identity-mapped supervisor page tables) is answered from memory, not from the probe entry — must be shown to return the *wrong* descriptor with a don't-care token |
| New `WalkerDescriptorCoherencySpec` | The §10.1 read-side test above, plus its write-side mirror (dirty resident descriptor line + U/M writeback ⇒ no lost update after a forced eviction) |
| New `WalkerCacheModeSpec` | `CACR.DE = 1` ⇒ walker commands carry WRITETHROUGH; `DE = 0` ⇒ INHIBITED; read and write halves always agree (W1/W3) |
| Extended `ExceptionUnit`/maintenance coverage | **M1/W19**: a walker command presented during `S_APPLY` — the one cycle after `S_DRAIN` releases and before `maintBusyReg` rises — is refused |
| Reworked `AxiDMergeSpec` | 2 read owners, write pass-through, read-side watchdog still fires (W21). **Explicitly includes pruning/adjusting the section-4.4 assertion block** (`AxiDMerge.scala:313-337`) that goes vacuous at 2 read / 1 write owner, and settling `io.wedgeIsRead`'s disposition — this file does **not** survive unchanged (§9.2) |
| Deleted | `WalkerIdGuardSpec` (W20) |

### 10.3 Regression sweeps (mandatory, not optional)

This change sits on the MMU + D-cache hot path, so unit tests are not sufficient evidence.

- **`ExecuteLockStepSpec`** full suite — the primary correctness net. It already attaches
  walker memory agents (`ExecuteLockStepSpec.scala`), so it exercises the new path on every
  MMU-enabled test.
- **`test-fast`** green (237/237 at Task 3's report; the plan must record the current number
  as its own baseline rather than inheriting this one).
- **Full ported-corpus sweep.** Per `ported-tests-triage-2026-07-17.md`, the corpus record is
  *already* 9-28 days stale and predates the FPU + frontend-restructure + FMax campaigns, so
  the plan must capture a **fresh pre-change baseline** and diff against it. Diffing against
  the recorded 713/728 would attribute pre-existing drift to this work.
- **Fuzz sweep.** Same reasoning; the last full fuzz sweep is 2026-07-21.
- Both sweeps run in a **`git worktree`** (standing operational rule,
  `ported-tests-triage-2026-07-17.md`), never in the working tree.

### 10.4 Synth gate

Per §5. Three checkpoints (baseline / post-mux / post-MMU), uncontended, with the top-10
failing-path *shape* compared, not just the number.

---

## 11. What this spec changes relative to its sources

### 11.1 Corrections and extensions to the scoping memo

1. **The LS pipe is NOT single-outstanding on loads.** The memo's arbitration analysis
   (§4) reasons from the walker's single-outstanding nature and the existing exception mux's
   temporal exclusivity, but does not surface that `LsEuPlugin.scala:703-720` keeps a 4-deep
   in-flight aligned-load queue with positionally-matched responses, or that `DcachePlugin`
   accepts a shadow command behind a miss. This is what rules out a simple per-command grant.
   **Most consequential correction in this document.** *(Fix pass: the original draft's answer
   to it — drain-to-zero on both directions — was itself replaced by W7's ownership FIFO,
   because `alignedCount` counts *enqueued* rather than *accepted* loads and drain-to-zero
   therefore over-waits systematically. See §4.2.3.)*
2. **The early-probe deadlock (§4.2.6) is entirely new.** The memo does not mention
   `earlyProbeTokenPresent`'s gating of `loadCmdPort.ready`. A design that follows the memo's
   §4 recommendations faithfully and stops there **deadlocks**.
3. **The `S_DRAIN`/`S_APPLY` quiesce hole (§6.2, W19) is new.** `ExceptionUnit.scala:1362-1381`'s
   written deadlock proof contains a clause this change falsifies. The memo's §7
   deadlock analysis correctly clears the *circular translation* question but does not reach
   this one. *(Fix pass: the hold must span `S_APPLY` too — `maintCmdOut` pulses there while
   `maintBusyReg` only rises on the maintenance FSM's own `IDLE → WAIT` edge, leaving a
   one-cycle fully-open window.)*
4. **The `StoreQueue` stray-ack assertion (`StoreQueue.scala:518`) is new evidence.** It
   upgrades W13's ack demux from "prudent" to "required".
5. **The `inputStoreSerial` observation (§4.2.3) is new and load-bearing in our favour:**
   a WRITETHROUGH walker store is *already* forced single-outstanding by
   `DcachePlugin.scala:631-632` + `:1865-1867`, independent of anything the arbiter does.
6. **The memo's "WRITETHROUGH vs COPYBACK" question is answered by construction, not by
   preference** (§4.1.2): WRITETHROUGH makes the fix hold in all three residency states
   without depending on the eviction path, and INHIBITED is not merely inappropriate but
   *reintroduces the bug* via `stS2Inhibited`.
7. **The memo's suggested arbiter site is narrowed.** It offered "extend `LsEuPlugin`'s mux,
   or a new sibling plugin". W4 picks the first and gives the reasons the second was
   rejected, the decisive one being that `DcachePlugin.scala` then needs no edit at all.
8. **The memo's D20-analogue suggestion is split, not simply declined** (W10 + W26, §4.2.5).
   Its *timer* half is declined with proof; its *observability* half is **adopted**, because
   a wedge at the new merge point itself produces no AXI grant to time out and would
   otherwise be invisible to D19, D20 and D28 alike. *(Fix pass: the original draft declined
   both halves and did not address the observability gap at all.)*
9. **[Round 4] A pre-existing HEAD bug turned up by W30's investigation, recorded here rather
   than silently inherited.** Neither the memo nor this document's earlier rounds noticed it,
   and it is the reason W30 part (2) exists rather than being optional polish.

   **Claim:** at current HEAD — no walkers, no part of this design applied — an
   exception-sequencer load can consume an *ordinary LS pipe* load's response as its own.

   **Mechanism** (each step cited; the full trace is in §4.2.3's W30 block):
   `ExceptionUnit`'s entry/RTE drain states wait on `sqDrained` **only**
   (`ExceptionUnit.scala:1389-1390`, `:1519-1520`), which is a pure `StoreQueue` fact
   (`sq.io.empty` = `!valids && !drainBusy`, `StoreQueue.scala:576`/`:211`). **Nothing anywhere
   waits on the load side.** `alignedSendValid` (`LsEuPlugin.scala:716`) carries `!excActive`
   so no *new* LS load is presented, but `alignedRspValid` (`:718-719`) deliberately does not,
   because already-accepted loads must still be consumed. So an LS load accepted before
   `excActive` rose can still owe a response afterwards. `DcachePlugin`'s `REPLAY` state
   re-launches the refilled read (`ldS1Valid := True`) and `goto(IDLE)` in the **same** cycle,
   so on the next cycle `loadCmdPort.ready` is high while that response is still one cycle
   away; the exception's registered, held `dcLoadCmd.valid` (`ExceptionUnit.scala:798-803`)
   fires into that cycle; and the following cycle its `R_SRWAIT`/`E_VECWAIT`/`F_HDRWAIT` state
   samples `dcLoadRsp.valid` and captures the **LS load's** word. The `ldoValidReg` comment at
   `ExceptionUnit.scala:793-798` describes exactly this observable ("RTE then consumes shifted
   frame words") from a *different* root cause, so the failure signature is already known to
   the codebase.

   **Disposition** *(corrected in round 5 — round 4 wrote "out of scope to fix as a standalone
   task", which understated both the urgency and the independence of the fix)*: this is a
   **real, currently-live, silent-corruption bug in already-shipped production code**,
   independently re-verified against the RTL, not a hypothetical and not something this
   redesign introduces. It is **tracked and fixed as its own standalone task**, in
   `ExceptionUnit.scala`/`LsEuPlugin.scala` alone, **completely independent of whether this
   design is ever built** — no walker, no port mux, no ownership FIFO and no other part of this
   document is required to close it. A reader must **not** conclude that the bug is only
   closeable via this redesign landing, nor that closing it is gated on this design's timeline.
   What this document owes the bug is the separate obligation that **this design must not build
   on the assumption it violates**: W30 part (2) (`excLoadAdmit`'s `ldOwnerFifoEmpty` term)
   closes it *again*, structurally, as a by-product, which is why that term is mandatory rather
   than defensive.

   §10.2's W30 test row is written so its assertion (3) can be demonstrated failing at HEAD —
   the evidence standard §10.1 already requires of the §1.2 test. A plan-writer who finds this
   already fixed upstream must re-verify the trace rather than assume it, and must **not**
   weaken W30 part (2) on that basis: W30 part (2) is also what restores W14's argument for the
   walker-vs-`CORE-EXC` case, which no upstream fix would cover.

### 11.2 Blocking feasibility concerns found: **none**

Full unification is feasible as scoped. Five things that *look* like blockers were run to
ground and are not:

- The untagged-response problem is solved by W7's ownership FIFO (loads) and latched-owner
  demux (stores) without adding a field to any bundle.
- The early-probe deadlock (§4.2.6) is real but is closed by a two-term change to signals
  that already exist for the identical hand-over; W25 closes the token-collision half.
- The `S_DRAIN`/`S_APPLY` quiesce hole (§6.2) is real but is closed by a command-granularity
  hold that is provably terminating.
- The **ownerless-handshake defect class** (W23/W24/W27, §4.2.7) is real, silent, and reaches
  **fifteen** sites in `LsEuPlugin.scala` plus this design's own new logic, not the two round 2
  found: the 2 round-2 sites, **10**
  further sites no round-2 decision reached (§4.2.7's `NEW — FIX` rows), three that are safe
  only by driver ordering or by construction and are now recorded as constraints rather than
  left as accidents (`:263-264`, `:757-764`, the FIFO pop), and — round 4 — **the sites this
  document itself creates**: W29's FIFO push payload (a *qualified* `fire` paired with an
  *unqualified value*, the one variant no "sweep the file" method can find) and W30's
  `excLoadOutstanding` clear term (a site that did not exist when the sweep ran). Every one is
  closed by an
  AND term on a low-fanout control signal, following patterns already present in the file.
  What round 3 changed is not the difficulty of any individual fix but the **method**: the
  sites are enumerated exhaustively in one table, each with a fix or a justified no-op, rather
  than patched as reviews name them. The one genuinely structural item among them is the split
  leg's arbiter *visibility* (§4.2.7's half (i)), without which §4.2.5's starvation proof does
  not hold for split loads.
- **W23's round-2 fix line was itself an instance of the class it closed** — `(ldOwner ===
  CORE)` is a current-grant fact where the hazard is an in-flight fact — and is corrected to
  `excLoadAdmit`. That an owner *comparison* is not interchangeable with an *admission
  predicate* is the single most transferable lesson in this document, and W28 (§4.2.8) states
  the invariant the whole scheme silently depended on. **[Round 4] The same lesson recurred
  twice more, in the one place three rounds of sweeping could not reach: the mechanisms the
  fixes themselves introduced.** The FIFO's push payload (W29) and `excLoadOutstanding`'s clear
  term (W30) were both specified by rounds 2/3 on one side only — when to sample, and when to
  set — leaving the other side (which value; when to clear) unstated and, in both cases,
  defaulting to something reachable and wrong. The generalisation, now written into W28 as
  clauses (b) and (c): **a state element is not specified until both its write value and every
  one of its write conditions are**, and new logic is subject to W27's table on exactly the
  same terms as the logic that was already there. A third form, from how C-R4-1 was actually
  missed rather than from what it was: **a scope statement that names what an invariant does
  *not* cover is a finding until something else covers it.** Round 3's own self-review wrote
  "W28 constrains *when* the owner value is sampled, never *which* value is required" as a
  reassurance; read as a gap report, which is what it was, it names C-R4-1 exactly.
- The D19 obligation (§9.3 item 3) is real but is discharged by W16, which is strictly
  stronger than the site it replaces.

The genuine *costs* are stated plainly rather than hidden, with the fix-pass corrections:

- **Test-wiring churn: 24 files**, not 28 — but materially *more* expensive than the original
  implied. **8 files / 8 DUT classes** (not 5) need W18's new sim helper, and the
  non-`sharedMem` case — which the original filed as the rare exception needing per-site
  review — is in fact the **dominant** case (only 6 of 24 files share memory at every walker
  site; `ExecuteLockStepSpec` at 5 of 19 sites). That per-site review is the bulk of the work.
  See §8.1.
- **FMax risk on a netlist with 0.069 ns of headroom** (§5), of which two sites are genuinely
  new fan-in — the `loadCmdPort.payload.vaddr` mux widening 3-way → 5-way onto the protected
  `rdSet` net, and `probeCancelAll`'s added OR term. Mitigated, **not** eliminated; gated
  three times; with a named lever list per site.
- **One new production counter** (W26) that the original spec did not have, buying an
  attributable halt in place of a silent one at the new merge point.

---

## 12. Self-review

Performed against this document before each commit — first for `fc90cf5`, then after the
review-driven fix pass (`ca850ea`), then for the round-3 fix pass (`e74a7a6`), then again for
the round-4 fix pass. Findings were fixed
**inline** above; they are recorded here rather than left as open items. Round-2 fix-pass
additions are marked **[FP]**; round-3 additions are marked **[R3]**; round-4 additions are
marked **[R4]**; round-5 additions **[R5]**; round-6 additions **[R6]**.

**Placeholder scan.** No `???`, `TBD`, `TODO`, `XXX`, `<fill in>` or unresolved bracket
survives. The `<don't care ...>` markers in §4.5/§4.6 are deliberate specification prose
(they say *which* field is a don't-care and *why*), not placeholders; each is paired with a
concrete assigned value where one is needed (`Size.LONG`, `precise = False`). **[FP]** The
one marker that was a genuine under-specification — `loadCmd.payload.token := <don't care>` —
has been **removed**: it is now a reserved constant (W25), because "don't care" was a real
silent-corruption bug, not prose. The surviving markers were each re-checked against this
standard: `cacheMode` is stamped by the mux and therefore genuinely irrelevant at the source
(W2); `DStoreCmd.data`/`size` are genuinely ignored under `useStrb` and `size` is still
assigned a concrete legal value.

**[R3] Placeholder scan, round 3.** Re-run over the new §4.2.7 table, §4.2.8, §4.5's
doc-comment paragraph and §10.2's three new test rows plus the (e′) sub-case. No `???`/`TBD`/`TODO`/`XXX` and no
unresolved bracket. The `(new)` entries in W27's table's `Line` column are deliberate — those
rows describe signals this design *creates*, so no current line number exists for them; each
still names its exact site by symbol. The one place a value is deliberately left unpinned
(W26's counter bound, "the plan derives the constant explicitly") is unchanged from round 2
and was affirmed by the review as correct to leave to the plan.

**Internal consistency.** All **30** DECIDED items (W1-W30) cross-checked pairwise for the
claims each makes about another:
- W1's expression appears once (§4.1.4) and is referenced, not restated, in §4.6 and §9.2.
  Checked that §9.2's claim ("walker traffic never engages the INHIBITED sequencer unless
  `CACR.DE = 0`") matches W1 exactly — it does. **[FP]** Its supporting citation was wrong
  (`:1738` is not the same expression); corrected in §4.1.4 without disturbing W1/W2/W3.
- W5 (independent per-direction) is used consistently: §4.2.2 states it, §4.2.3 gives
  per-direction predicates, §6.5 relies on it, §4.2.4's aging is per-direction. No section
  assumes a single token. **[FP]** Its *justification* changed (D9 does not transfer down a
  layer); swept for every restatement of the old reason and found two — §4.2.5 step 1 and
  §9.2's rejected-alternatives list — both corrected. §0's W5 row corrected to match.
- **[FP]** W7 changed shape entirely (drain-to-zero → ownership FIFO on loads). Swept every
  section that leaned on drain-to-zero: §0 (W7/W8/W10/W14 rows), §3 (diagram), §4.2.3
  (rewritten), §4.2.4 (aging pressure), §4.2.5 step 1 (boundedness), §4.3 (W14), §10.2 (test
  obligations), §11.1 item 1, §11.2. The one place drain-to-zero **survives** — the
  walker ↔ `CORE-EXC` load boundary and the whole store direction — is now stated in every
  one of those sections identically, and W14's no-rewiring argument is scoped to exactly that
  surviving boundary rather than to the general case.
- **[FP]** W23/W24 are new and touch two lines (`:2115`, `:265`) that no other section
  previously mentioned. Checked that neither fix contradicts the existing `:2068` exception
  hold (it does not — same rule, finer granularity, correctly ordered by last-assignment-wins)
  and that both appear in §8's `LsEuPlugin` row and §10.2's directed tests.
- **[FP]** W26 adds production state, which W10 previously said would not exist. §0's W10 row
  and §4.2.5's heading both reworded so the document never claims "no new production state at
  this layer" while W26 builds some. W10 now declines the *timer* only, explicitly.
- **[FP]** §8's "`DcachePlugin.scala` deliberately NOT modified" is still true and still
  consistent with W4/W12/§5. But `DcacheTypes.scala` moved from that list into the modified
  table (W25's two constants) — checked that this does **not** weaken W4 ("`DcachePlugin`
  gains zero new client awareness") or W7 ("no `DcacheService` bundle change"), because the
  addition is two `Int` constants and no bundle field. §8 states the alternative placement so
  a plan may keep the file byte-identical if it prefers.
- §8's cross-check for an implied `DcachePlugin` edit found none. (§4.2.3 reads
  `storeOutstanding` — but only via signals `LsEuPlugin` can reconstruct from
  `dcache.store.fire`/`dcache.storeAck`, which is why W7 defines `coreStOutstanding` as a
  *new `LsEuPlugin` counter* rather than "read `DcachePlugin`'s".)
- W11 and §5 initially disagreed on whether probe *suppression* or probe *cancellation* was
  the mechanism. **Fixed:** §5 states explicitly that W11 does both, and why each half stays
  off the protected net. **[FP]** §5 further corrected: the `probeCancelAll` change is an
  **addition**, not a substitution, and is now counted as such.
- **[R3] W27 is new and subsumes W23/W24 rather than sitting beside them.** Checked that no
  section still presents W23/W24 as the complete treatment of their defect class: §0's W23/W24
  rows now point at W27, §4.2.3 caveat (a) is relabelled "examples, not the list" and defers to
  W27's table, §4.2.7's own heading and framing changed from "two unconditional `ready`
  drives" to the class-plus-sweep framing, §8's `LsEuPlugin` row carries the whole table, and
  §11.2 states twelve reached sites rather than two. Checked the reverse direction too: W27 does not
  contradict W13 (its `:270-271` and `umq.io.drainAck` rows restate W13's own rule verbatim),
  W11 (its four probe rows record W11's substitutions and add nothing), or W7 (its `rspOwner`
  rows are caveat (a)'s, extended by `bkCompletes`).
- **[R3] W23's corrected fix predicate vs §4.2.3.** `excLoadAdmit` is now used at four sites
  (`:2027`'s header, `:2115`, `excLoadOutstanding`'s set term, and §4.3's assertion, which
  already read the FIFO rather than `ldOwner`). Checked that §4.3's assertion and W23's fix now
  express the **same** condition — they did not before, which is precisely how the round-2 bug
  survived its own section: the assertion would have caught the failure the fix line allowed.
  Also checked the store-side asymmetry is stated rather than left implicit (§4.2.7's `:2069`
  row): `excStoreAdmit` *is* the owner form on stores because store admission is drain-to-zero,
  so there is no in-flight-vs-granted gap on that direction. The asymmetry is real and both
  halves are now written down.
- **[R3] W28 vs §5.** W28's preferred (registered-grant) shape is exactly what §5 already
  assumes when it prices the walker select term as "one LUT from a flop"; checked that
  adopting W28 therefore adds no cost to §5's accounting and that §5's second admissible shape
  (combinational grant) is explicitly priced against §5 item (1) rather than silently allowed.
- **[R3] W28 vs W7/W13/W23/W24/W27.** Checked that every owner-reading term in the document
  falls under W28's scope, and that W28 does not weaken any of them: it constrains *when* the
  owner value is sampled, never *which* value is required.
  **[R4] This sentence was correct and was also, unrecognised, the statement of C-R4-1.**
  Round 3 wrote it as a reassurance ("W28 does not weaken anything") when it was in fact a gap
  report ("nothing in this document constrains *which* value is written"), and the FIFO's push
  payload was a `which`. Left in place verbatim as the record of how the miss happened; closed
  by W28 clause (b) and W29. The transferable form is now in §11.2: **a scope statement that
  names what an invariant does *not* cover is a finding until something else covers it.**
- **[R3] I1's `force` correction vs §4.2.5.** §4.2.5 step 3 restated the same drain-to-zero
  language §4.2.4 did; both are corrected together, and step 1's "a `CORE` load grant is no
  longer bounded by drain-to-zero at all under W7's FIFO" was already consistent with the new
  wording. Checked no third site restates the old model — §4.2.4's "pressure" paragraph
  discusses drain-to-zero only in the past tense, which is correct.
- **[R3] I2's fix vs W4/§5/§8/§12.** Removing the "`loadCmdPort.ready` is `False` when it is
  full" sentence restores consistency with the four sections that require `DcachePlugin.scala`
  to receive zero edits. Re-swept the document for any other sentence implying an edit to that
  file and found none; §12's pre-existing cross-check (the `storeOutstanding` note) still
  holds.

**Ambiguity check.**
- "The walker" was ambiguous between `TableWalker` (the component, one per TLB) and "a walker
  client" (an arbiter owner). Normalised: `TableWalker` for the component, "walker"/"ITLB
  walker"/"DTLB walker" for the owner.
- `WALKER_AGE_LIMIT`'s units (core clocks) and its reset condition (grant **or** request
  withdrawal) were unstated in the first draft. **Fixed** in §4.2.4; the reset-on-withdrawal
  clause matters for §4.2.5's step 2. **[FP]** §4.2.4 now also states explicitly that the
  constant is *retained pending re-derivation* rather than silently re-justified under the
  new cost model — an unchanged number with a changed reason is exactly the drift this
  section exists to catch.
- §4.5's `paddr` was originally written as the line-aligned address. **Fixed:** it must be the
  descriptor *byte* address. **[FP]** The *reason* given was also wrong (the lane comes from
  `vaddr[3:0]` via `cmdOff`, not `paddr[3:0]`); corrected, and the corrected text names
  `vaddr` as the load-bearing field so a future reader cannot follow the old wording into a
  lane-0 bug.
- "Task 4 is deleted" was ambiguous between the commits and the code. **Fixed** in §9.1:
  commits are not reverted, code is removed with its host.
- **[FP]** §4.6 said the drain registers were kept "verbatim" while deleting their only
  writer. Fixed with an explicit replacement latch condition (`umq.io.drain.valid &&
  !drainArmed`), and `drainArmed`'s role as the successor to `drainAwDone && drainWDone`
  stated once rather than implied twice.
- **[FP]** §9.2's "the read-side D20 watchdog stays" implied only one watchdog exists. Fixed:
  both are named with line numbers, and dropping the write-side one is labelled a **deviation
  from D20 as written**, with its two named consequences (`io.wedgeIsRead` dead, assertion
  block vacuous).
- **[R3]** §4.2.3 caveat (b) claimed the FIFO is "**strictly** better than what it replaces",
  which the same paragraph's own "paid, not eliminated" framing contradicts. Fixed: the trade
  is stated in both directions — latency improves, probe-destruction **frequency rises**,
  because a walker now interleaves into a live stream rather than being admitted only into a
  quiesced port. The superlative is gone and the claim now matches the evidence.
- **[R3]** "Owner" was ambiguous between *who holds the current grant* (`ldOwner`) and *what is
  still in flight* (the FIFO). That ambiguity is not stylistic — it **is** C2. Normalised:
  `ldOwner`/`stOwner` name the grant, `ldOwnerFifoHoldsWalker` names in-flight, and every
  qualification cites an **admission predicate** by name rather than open-coding an owner
  comparison. §4.2.3 states the distinction explicitly where the predicates are defined.

**Citation hygiene. [FP]** Every line-number citation in the document was re-resolved against
current HEAD during the fix pass. `DcachePlugin.scala` citations are now anchored by symbol
name first (see the citation convention in the header) because the original draft mixed a
pre- and post-Task-8 snapshot. Six citations were found wrong and corrected in place, each
flagged where it appears rather than only listed here: `LsEuPlugin.scala:1738` (§4.1.4),
`IpcBenchSpec.scala:593-594` (§8.1), `UmWriteSpec.scala:182` (§8.1), `AxiDMerge.scala:110-127`
(§9.2), "Global Constraint #3" (§9.1), and the §8 file/bucket counts (§8.1).

**Claims re-verified against the RTL while reviewing** (not merely against the memo):
`DcacheTypes.scala:67-71` (no token on `DLoadRsp`), `DcachePlugin.scala:981` (the probe term
in `loadCmdPort.ready`), `:1927-1935` (dirty is set, never cleared, on an S3 hit write),
`:631-632` + `:1865-1867` (WRITETHROUGH is serial; **[FP]** and the `!maintBusyReg` term N4
originally attributed to the load side only), `:1602-1608` (`dcIdleForMaint`'s terms),
`:2073-2087` (the existing assertion-block style), `LsEuPlugin.scala:263-271` (the SQ
ack/ready drives), `:716` / `:863` / `:1774-1776` (the three `excActive` conjunctions W12
folds into — **[R3]** `:716`'s is now factored up one level into `coreLsLoadAdmit` per W27,
so it also covers `:756`'s split leg), `StoreQueue.scala:446,518` (`drainAckFire`, the
stray-ack assertion),
`ExceptionUnit.scala:1362-1381` (the `S_DRAIN` deadlock comment), `AxiDMerge.scala:131-232`
(the read-side grant shape), `AxiDMergePlugin.scala:36-52` (the `socketMerged` requirement
and walker connections).

**[FP] Additionally verified during the fix pass:** `LsEuPlugin.scala:2115`
(`excLoadCmdReady`, unconditional — W23), `:265` (`sq.io.drain.ready`, unconditional — W24),
`:2068` (the existing exception-side hold this extends), `:2064` (the exception sequencer's
`U(0x80)` token) and `:867` (LS-EU probe token shape) for W25's disjointness,
`:720`/`:1270-1272` (`alignedRspFire`, `alignedCount`'s enqueue-based update — W7's
over-wait), `DcachePlugin.scala:321-324` (`cmdVaddr`/`cmdOff` — M5), `:420-422` (early-probe
token **and** vaddr match — W25), `:435-441` (`useEarlyProbe`'s `!ldS1Valid` gate — in-order
completion), `:1010-1018` (shadow capture and its in-order doc comment), `:1030-1040`
(`cmdSet` → `rdSet` — §5's widened mux), `:1250-1256` (the refill/store-drain commentary —
§4.2.2), `:1682` (`maintBusyReg`'s rising edge — M1), `ExceptionUnit.scala:1735` (`S_APPLY`'s
`maintCmdOut` pulse — M1), `AxiDMerge.scala:222-230`/`:298-303`/`:306-307` (both watchdogs)
and `:313-337` (the section-4.4 assertion block), plus a direct sweep of all 24
walker-AXI-referencing test files for §8.1's counts and `sharedMem` distribution.

**[R3] Round-3 RTL re-derivation (the C1 sweep).** W27's table was produced by grepping
`dcache.loadCmd`, `dcache.store`, `dcache.loadRsp`, `dcache.storeAck`, `dcache.storeErr` and
`dcache.loadProbe*` across the **whole** of `LsEuPlugin.scala` and reading every hit in
context, then cross-checking the result against the review's list rather than transcribing it.
Outcome:

- The review's eight named items are all real and all in the table.
- **Four further sites the review did not name were found and are fixed:** `:2027-2028` (the
  exception's `loadCmd.valid` presentation header, which nothing bound to `excLoadAdmit` — the
  withholding C2's own argument *assumes*), `:2067`/`:2070-2071` (its store-side twin),
  `:2073` (`when(dcache.storeAck && excStoreOutstanding)`, a `storeAck` consumer), and
  `:263-264` (the SQ store base drive, safe by driver ordering — recorded as a no-op with the
  ordering named as load-bearing so the plan cannot reorder it away).
- **Four further sites were found and justified as needing no term:** `:1476`/`:1480` and
  `:1579` (transitively covered by their qualified parents), and the four probe-port sites
  `:869-878`/`:879-881`/`:882-885`+`:1740-1745`/`:1776` (walkers never touch the probe ports —
  a W25 decision, not an accident).
- **Two locator corrections** relative to the review's own table: the `loadCmd.valid` drive is
  `:756`, not `:758`; `excStoreOutstanding` is declared at `:268`, not `:266-270`.
- Confirmed no other `dcache.*` reference exists in the file: the only two remaining hits are
  the header doc comment's mention of `dcache.loadBusy` (`:70`) and the service handle itself
  (`:197`).

**[R3] Additionally verified against the RTL during round 3:** `LsEuPlugin.scala:716-720`
(`alignedSendValid`/`alignedRspValid`/`alignedRspFire`), `:749`/`:756`/`:766` (`useSplitCmd`,
the `Mux`, `alignedCmdFire`), `:863`/`:867`/`:869-887` (probe drives and token shape),
`:1507-1537` (the BK FSM's two `loadCmd.fire` samplings and `WAIT_A`'s `aDone` handshake),
`:1570-1581` (`bkInWaitA`/`bkInWaitB`/`bkCompletes`/`backCompFires` and the comment explaining
why they live outside the FSM), `:1774-1776` (`splitReqArm`/`normalReqArm`), `:2027-2078` (the
whole exception override block), `:2115`; `DcacheTypes.scala:6-8` (the token-layout comment —
M2); `AxiDMerge.scala:221-231` and `:296-307` (both watchdog bodies, for M4's locator);
the socket design spec's D19 evidence line (for M4's other locator); and a direct DUT-class
and `DcachePlugin`-reference count over all eight W18 files (for M1's arithmetic).

**Unverified claim, flagged in place:** §4.1.4's remark about real 68040 table-search
cacheability. No primary source is present in this repository; the decision does not rest on
it and the implementation plan carries a non-blocking confirmation step.

**[R3] Nothing established by W1-W22 or by the round-2 fixes is reopened.** Explicitly
re-checked: W1/W2/W3's cacheMode policy (untouched), W7's FIFO **concept** (untouched — only
the FIFO-full term's *location* moved, off `DcachePlugin` and onto `LsEuPlugin`'s own valid
drive, which is where §4.2.3 always intended it), W8's `WALKER_AGE_LIMIT = 64` (untouched;
I1's resolution confirms the constant was never the defect), W11's early-probe fix (untouched;
W27's probe rows record it rather than change it), W13 (untouched; W27 restates it), W14's
no-rewiring argument (untouched — every round-3 fix is inside `LsEuPlugin`, no bundle field
and no DUT wiring), W16/W19/W20/W21/W26 (untouched), and W25's file placement (untouched; only
M2's doc-comment sentence added). The round-2 fixes W23 and W24 are both **strengthened**, not
weakened: W24's `(stOwner === CORE)` stands exactly as written, and W23's predicate is replaced
by a strictly stronger one.

**[R4] Placeholder scan, round 4.** Re-run over everything this pass touched: §4.2.3's W29 and
W30 blocks, §4.2.8's three W28 clauses, §4.2.7's five new rows and its corrected closing claim,
§4.3's reworked W14 bullet and assertion block, §5's reconciled mux bullet and the round-4
cost bullet, §10.2's three test rows, §11.1 item 9, §11.2, §0's W29/W30 rows, and §15. No
`???`/`TBD`/`TODO`/`XXX`/`<fill in>` and no unresolved bracket. The one deliberately-unpinned
value remains W26's counter bound, unchanged since round 2 and affirmed twice. W29's
`ITLB_ID`/`DTLB_ID` in the push-encode snippet are the existing owner-code constants, not
placeholders. *(Round-5 correction to this sentence: they are **not** the owner-code constants,
and calling them that conflated three distinct encodings. They are **positional indices into
the `walkerLoadAdmit` vector** and are renamed `WALKER_ITLB_IDX`/`WALKER_DTLB_IDX` in §4.2.3 to
keep them visibly distinct from the tag values `LdRspTag.ITLB`/`LdRspTag.DTLB` and from the
three-valued arbiter owner code. Still not placeholders — the point of the correction is
naming, not an unresolved value.)* The `// DO NOT IMPLEMENT` block in W30 is the same deliberate
device round 3 introduced for W23's superseded line, and is marked identically.

**[R4] Internal consistency, round 4.** All **30** DECIDED items (W1-W30) re-cross-checked;
only the pairs this pass could disturb are recorded:

- **W29 vs W6.** W29 makes the FIFO tag four-valued while W6 keeps the arbiter's owner
  three-valued. Checked this is an extension, not a contradiction: W6 is a statement about
  **arbitration** (the LS pipe and the exception sequencer never contend, so they need not be
  separate arbiter owners) and W29 is a statement about **response identification** (they *can*
  both have a response owed, so they must be separately tagged). §4.2.3 now says both in one
  place, and `rspOwner` is derived from `ldRspTag` so there is exactly one source of truth.
  Swept for any sentence claiming the FIFO stores "the owner": §0's W7 row, §3's diagram
  narrative, §4.2.3, §4.3, §5 and §8 all corrected to `ldRspTag`.
- **W29 vs W28.** W29 is the concrete realisation of W28 clause (b); checked the two do not
  drift by making clause (b) state the *rule* and W29 the *encoding*, with W29 cited from
  clause (b) and clause (b) cited from W29. Also checked W28 clause (a)'s round-3 text no
  longer contains the sentence that licensed C-R4-1 ("the registered owner used only for FIFO
  push payloads and for reporting") — it is corrected in place with the correction called out,
  not silently deleted.
- **W29 vs W27.** The four `ldRspTag === CORE_LS` rows are strictly stronger than round 3's
  `rspOwner === CORE`; checked no other section still prescribes the weaker form (§4.3 and
  §4.2.3 caveat (a) both updated; §12's round-3 record left intact as history).
- **W30 vs W7.** W30 strengthens the *surviving* drain-to-zero boundary from "walker ↔
  CORE-EXC" to "CORE-EXC ↔ everything". Checked W7's actual purpose is untouched: the
  `CORE-LS` ↔ walker concurrency the FIFO exists for is unaffected, and §4.2.4's aging /
  §4.2.5's boundedness reason about walker-vs-`CORE-LS` pressure, not about the exception
  sequencer. §0's W7 row updated to say "CORE-EXC boundary", not "walker ↔ CORE-EXC".
- **W30 vs W23/C2.** `ldOwnerFifoEmpty ⟹ !ldOwnerFifoHoldsWalker`, so C2's closure is
  preserved a fortiori. §4.2.7's W23 argument is deliberately left stated at the *round-3*
  (weaker) strength with a pointer, because demonstrating the fix at the weakest sufficient
  predicate is what makes it a proof rather than a coincidence — and the round-4 review
  confirmed that argument correct, so it must not be perturbed.
- **W30 vs W14.** Checked in both directions. W14 is not weakened (no bundle field, no
  top-level rewiring, no DUT churn — W30 is two terms inside `LsEuPlugin`); and W14's argument
  is *strengthened from asserted to true*, which §4.3's bullet now says explicitly instead of
  claiming the coincidence without a mechanism.
- **W30 vs §6 liveness.** The new `ldOwnerFifoEmpty` wait is a wait on responses that arrive
  autonomously from `DcachePlugin`, with no dependence on the exception sequencer, so it adds
  no cycle to §6.1's or §6.5's graphs. Starvation of the exception sequencer is excluded by
  `ldoValidReg` holding `excLoadCmdValid` asserted until fire, which keeps `ldBusyExc` true and
  bars new walkers. Both stated in W30 **and** restated as new **§6.6**, so §6 remains the one
  place a reader can check every wait this design adds.
- **W29/W30 vs §5.** Priced explicitly in §5's new round-4 bullet; neither touches the
  protected `rdSet` arc, and W30's replacement term is strictly *less* logic than the one it
  replaces. §5's two "real additions" are still the only two.
- **§4.2.7 arithmetic.** Round 3's 29-row count and its 10/1/18 split were independently
  recounted and are untouched; round 4's five rows are added as a clearly-labelled extension
  with its own arithmetic (11/3/1/19 = 34) so neither count can be mistaken for the other.

**[R4] The round-4 self-check the process required: does this round's own fix need new W27
rows?** Performed deliberately, because rounds 2 and 3 each closed their findings while leaving
a hole in the mechanism they introduced. Every piece of RTL-shaped logic W29 and W30 add was
enumerated and checked against W27's defect class:

1. `when(dcache.loadCmd.fire) { ldOwnerFifo.push(ldPushTag) }` — **touches `dcache.loadCmd`.
   Row added** (round-4 sub-table). Unqualified `fire` is *correct* here and the row says why.
2. `when(dcache.loadRsp.valid) { ldOwnerFifo.pop() }` — **touches `dcache.loadRsp`. Row added.**
   This one is the important catch: it is a bare, unqualified `dcache.loadRsp.valid` consumer,
   the exact shape W27 hunts, and it must **stay** unqualified. It has been specified since
   round 2 and never had a row; without one, a future sweep would "fix" it and desynchronise
   the FIFO. The row records the justification so that cannot happen.
3. `when(dcache.loadRsp.valid && (ldRspTag === CORE_EXC)) { excLoadOutstanding := False }` —
   **touches `dcache.loadRsp`. Row added**, marked `NEW — FIX`.
4. W29's one-hot exclusivity assertion — reads only `LsEuPlugin`-local admission predicates, no
   `dcache.*` port. **No row required**; listed in the sub-table anyway so the enumeration is
   visibly complete rather than silently truncated.
5. `ldOwnerFifoEmpty` (new) and `ldRspTag`/`rspOwner` (reshaped) — FIFO-internal state, read by
   admission predicates that already have rows. **No new `dcache.*` reference**, so no row; they
   are covered by W28 clause (a) as owner-tracking state, and by clause (c) for their writers.
6. W28 clause (c)'s `ldOwner := ldGrantNext` / `stOwner := stGrantNext` — register writes from
   arbiter logic, no `dcache.*` port read or written. **No row required.**

Result: **three new W27 rows were required and have been added; one further item was listed for
completeness; two were checked and correctly need none.** Nothing in this round's own fix is
left as an unqualified handshake reference without either a qualification or a written
justification.

**[R4] Ambiguity check, round 4.**
- "The mux selects payloads from `ldOwner`" (§5) vs "the legs are gated `when(…Admit)`"
  (§4.2.3, every W27 row) were two literally-incompatible descriptions of one mux, in one
  document. **Fixed** by reconciling §5 to the admit-predicate form and, more importantly, by
  separating the two questions that were being conflated — how the mux *selects* (§5 item
  (i)) and how the FIFO *tags* (W29, §5 item (ii)) — and stating that they take the **same**
  signals. Leaving them merged is what allowed "the FIFO pushes the owner" to read as
  consistent with "the legs are gated by predicates".
- "Owner" was normalised in round 3 between *current grant* and *in flight*. Round 4 adds the
  third sense the FIFO needed: *which client a given response belongs to*. `ldOwner`/`stOwner`
  = grant, `ldOwnerFifoEmpty`/`ldOwnerFifoHoldsWalker` = in flight, `ldRspTag` = response
  identity, `rspOwner` = the three-valued view of `ldRspTag` kept only for the rows that
  genuinely mean "not a walker".
- `excLoadOutstanding` was described as a "mirror" of `excStoreOutstanding`. That word did real
  damage — it is what pointed at the unqualified clear term. Changed to "counterpart", with
  §4.2.3's W30 block stating exactly which property of the store side does **not** transfer and
  why (`sqDrained` has no load-side analogue).

**[R4] Additionally verified against the RTL during round 4** (the C-R4-2 investigation, which
had to settle a proof obligation rather than locate sites): `ExceptionUnit.scala:2329`
(`active := !fsm.isActive(IDLE)`), `:1382-1383`/`:1389-1390`/`:1519-1520` (`S_DRAIN`/`E_DRAIN`/
`R_DRAIN` and exactly what each waits on), `:785-828` (`ldoVld`/`ldoValidReg` — the **registered,
held** `dcLoadCmd.valid` and its own "shifted frame words" comment), `:1526-1570` (the RTE
`REQ → WAIT` alternation proving the exception sequencer is single-outstanding on loads),
`:2211-2224` (FRESTORE's `F_HDRREQ`/`F_HDRWAIT`, the same shape); `FullCoreSynth.scala:417`
(`exc.sqDrained := lsEu.sqEmptySig`), `:423` (`exc.dcQuiesced := dc.maintQuiesced`);
`LsEuPlugin.scala:263-272` (the SQ drives and `sqEmptySig`), `:716-720` (`alignedSendValid`
carries `!excActive`, `alignedRspValid` deliberately does **not**), `:886-887`
(`parallelViptLaunch` — M-R4-1's missing row), `:2023-2026` (the exception mux's own comment on
store serialisation), `:2067-2071` (confirming `:2069` sits **inside** the `:2067` header —
M-R4-3), `:2073`/`:2076`/`:2115`; `StoreQueue.scala:211`/`:576` (`drainBusy = acceptedHalves
=/= 0`, `io.empty` includes it — the fact that makes the store-side invariant real);
`DcachePlugin.scala:981-983` (`loadCmdPort.ready`'s exact terms, and that it is inside
`IDLE.whenIsActive`), `:1010-1018` (shadow capture), `REPLAY`'s ordinary arm (`ldS1Valid :=
True` **and** `goto(IDLE)` in the same cycle — the cycle that opens the C-R4-2 window),
`:500-509` (`ldS2Valid := ldS1Valid`, `loadRspPort.valid`), `:1602-1608` (`dcIdleForMaint`).
A full re-sweep of `dcache.*` across `LsEuPlugin.scala` (63 hits) was run to settle M-R4-1
rather than trusting round 3's closing sentence.

**[R4] Nothing established by W1-W28 or by the round-3 fixes is reopened.** Explicitly
re-checked, item by item: W1/W2/W3, W4, W5, W8, W9, W10, W11, W12, W15, W16, W17, W18, W19,
W20, W21, W22, W25, W26 — all untouched. **W6 untouched** (arbitration still has three owners;
W29 adds a response sub-tag, which is a different question). **W7's FIFO concept untouched** —
only its *push payload* is respecified (W29) and its *exception-side admission* strengthened
(W30); depth 4, positional tagging, in-order completion, `DcacheService` unchanged,
`DcachePlugin` unchanged, no DUT rewired, all stand. **W13 untouched.** **W14 untouched in
substance and strengthened in argument** — still no bundle field, no top-level rewiring, no DUT
churn. **W23 untouched and strengthened** (its predicate is only made harder to satisfy; its
round-3 argument is deliberately preserved at the weaker strength that proves it). **W24
untouched** (`(stOwner === CORE)` stands exactly as written). **W27's 29 round-3 rows untouched
in disposition** — the four `rspOwner === CORE` rows are *tightened* to `ldRspTag === CORE_LS`
(strictly stronger, same site, same intent), `:2069`'s row is corrected per M-R4-3 to match its
neighbour `:2070-2071` rather than contradict it, and the count/arithmetic round 4 adds is
additive and separately labelled. **W28 extended, not replaced** — clause (a) is round 3's
invariant verbatim apart from the one sentence that licensed C-R4-1, with clauses (b) and (c)
added beside it. Round 3's I1/I2/M1/M2/M3/M4 resolutions are all untouched, and the
locator corrections round 3 made (`:756` not `:758`, `excStoreOutstanding` at `:268`,
`AxiDMerge.scala:225-230`) were re-confirmed correct against the file during this pass.

**[R5] Placeholder scan, round 5.** Re-run over exactly what this pass touched and nothing
else, since round 5 is a targeted fix pass rather than a structural one: §4.2.3's FIFO
pseudocode, the three FIFO-state definitions, the W29 push-encode snippet and its two
round-5 notes, property 2's exclusivity assertion; §4.3's assertion block and the paragraphs
around it; §6.6's third bullet; §8's `LsEuPlugin` symbol list; §11.1 item 9's disposition
sentence; §12's round-4 `ITLB_ID` sentence; the round-5 revision note in the header; and §16.
No `???`/`TBD`/`TODO`/`XXX`/`<fill in>` and no unresolved bracket in any of them. The one
deliberately-unpinned value remains W26's counter bound, unchanged since round 2 and now
affirmed three times. `WALKER_ITLB_IDX`/`WALKER_DTLB_IDX` are **not** placeholders — they are
the `walkerLoadAdmit` vector's positional indices, named this way precisely so they cannot be
confused with `LdRspTag.ITLB`/`LdRspTag.DTLB` (M-R5-4). The superseded round-3 **and** round-4
assertion forms in §4.3 are commented-out on purpose, the same device round 3 introduced for
W23's superseded line and round 4 used for W30's `// DO NOT IMPLEMENT` mirror, and each carries
a one-line statement of *how* it was wrong so neither can be mistaken for a live prescription.

**[R5] Internal consistency, round 5.** No DECIDED item is added, removed or changed in
substance, so the pairwise cross-check is limited to the pairs this pass could disturb:

- **§4.3's corrected assertion vs W30 part (2).** The assertion is now
  `!(excLoadAdmit && !ldOwnerFifoEmpty)`, and W30 part (2) states
  `excLoadAdmit = excActive && excLoadCmdValid && ldOwnerFifoEmpty`. The assertion is therefore
  *entailed by* the decision it guards — checked deliberately, because that entailment is what
  makes it a tripwire (it can only fire if the decision's own text stops being implemented) and
  is the specific property the round-4 form lacked.
- **§4.3's corrected assertion vs §6.6, §10.2 `(e′)`, §10.2's W30 row.** All three specify a
  state in which the exception's command is presented-but-withheld with the FIFO non-empty.
  Re-checked that the new assertion is **false** in that state for all three (it reads
  `excLoadAdmit`, which is low exactly then), so none of the three trips it. This is the check
  round 4 did not make, and the three sites are the evidence that the document's *other* three
  statements were right and the assertion was the outlier.
- **M-R5-2's reorder vs §4.2.7's `:757-764` row and §4.2.4's base priority.** The chain now
  reads CORE-LS base → walkers → CORE-EXC last, which is the same order those two places
  require of the payload mux. Checked in the direction that matters: the reorder cannot change
  behaviour while W29 property 2 holds, and it makes tag and payload agree if it ever fails.
- **M-R5-1's pre-pop rule vs W30 part (1) and §4.3's second assertion.** W30 part (1) clears
  `excLoadOutstanding` on the *edge* after `dcache.loadRsp.valid && (ldRspTag === CORE_EXC)`,
  so on the response cycle itself the register is still set; the pre-pop reading is what makes
  the second assertion true on that cycle. Also checked against `ldRspTag = ldOwnerFifo.head`,
  which must be pre-pop for the same reason and already was.
- **I-R5-1 vs W30 part (2) and §10.2's W30 row.** Item 9's disposition now decouples the bug's
  fix from this design's timeline, so re-checked that nothing downstream depended on the old
  "out of scope" framing: W30 part (2) is justified independently (it is what restores W14's
  argument for the walker-vs-`CORE-EXC` case, which no upstream fix covers), and item 9's
  existing instruction to a plan-writer who finds the bug already fixed upstream — re-verify the
  trace, and do **not** weaken W30 part (2) on that basis — is unchanged and now reads as the
  expected case rather than the surprising one.

**[R6] Placeholder scan, round 6.** Re-run over exactly the six places this pass touched and
nothing else, round 6 being the narrowest pass yet: §4.2.3's exclusivity assertion block and its
round-6 note; §4.2.7's W23 "presentation site itself" parenthetical; §4.2.7's W27 table row for
that assertion; §6.6's third bullet; §10.2's `(e′)` sub-case and its W28/W29 row; §0's W29 row
and §4.2.3's W29 worked-failure step 2 (rename only); §16's M-R5-4 disposition; the round-6
revision note in the header; and §17. No `???`/`TBD`/`TODO`/`XXX`/`<fill in>` and no unresolved
bracket in any of them. The one deliberately-unpinned value remains W26's counter bound,
unchanged since round 2 and now affirmed four times.

**[R6] Whole-document rename sweep (M-R6-1).** The old ambiguous forms `walkerLoadAdmit(ITLB)`
and `walkerLoadAdmit(DTLB)` were grepped across the **entire** document, not only the sites the
review named, precisely because round 5's own claim to have renamed them "everywhere" was what
failed. Three survivors were found and fixed (§0's W29 DECIDED row, §4.2.3's W29 worked-failure
step 2, §10.2's W28/W29 test row — the last mattering most, being a test prescription a
plan-writer copies verbatim). The post-fix sweep returns **zero prescriptive hits**: every
`walkerLoadAdmit` occurrence that specifies RTL, a proof step or a test now reads
`walkerLoadAdmit(WALKER_ITLB_IDX)`/`walkerLoadAdmit(WALKER_DTLB_IDX)` (or the generic
`walkerLoadAdmit(i)` where the index is quantified). The only surviving instances of the old
string anywhere in the document are the three **disposition rows that name the form being
retired** — §16's M-R5-4 finding statement and §17's `[R6]`/M-R6-1 entries, including this
sentence — which quote it rather than prescribe it and must keep quoting it to stay legible. The
three encodings M-R5-4 separated —
the `walkerLoadAdmit` vector position, the four-valued `LdRspTag`, and the three-valued arbiter
owner code — stay textually distinct everywhere.

**[R6] Internal consistency, round 6.** No DECIDED item is added, removed or changed in
substance; the cross-check is limited to the pairs this pass could disturb:

- **§4.2.3's strengthened assertion vs the [R5] "hold by construction" standard.** Round 5
  established that an assertion must be written against predicates the asserting module can hold
  by construction. Re-checked in that light: all four admission predicates *and*
  `dcache.loadCmd.fire`'s `valid` side are `LsEuPlugin`'s own expressions, and a correct mux
  asserts `valid` only on a leg whose predicate is true, so `=== 1` at `fire` is a property
  `LsEuPlugin` holds by construction. It is **not** the round-4 mistake in a new place: it does
  not read any `ExceptionUnit`-driven signal, and its failure condition is unreachable under a
  correct implementation rather than being the design's own intended waiting state.
- **§4.2.3's strengthened assertion vs W30 part (2), §6.6 and §10.2's `(e′)`/W30 rows.** All of
  those specify the presented-but-withheld state, in which `dcache.loadCmd.valid` is **low** for
  the exception leg and hence `loadCmd.fire` is low for it. The new assertion is `when`-gated on
  `fire`, so it is not even evaluated in that state — the C-R5-1 failure mode cannot recur
  through it. Checked in the other direction too: on a **walker's** fire during that same window
  exactly one predicate (`walkerLoadAdmit(WALKER_ITLB_IDX)` or `..._DTLB_IDX`) is true, so the
  assertion holds there as well.
- **§4.2.3's strengthened assertion vs §4.2.7's W27 table.** The row for this assertion was
  round-4 prose saying it "reads no `dcache.*` port at all, so it is outside the defect class".
  That is no longer true once the assertion is `fire`-qualified, so the row is rewritten rather
  than left contradicting the assertion it describes — it now records the row as a live check
  and names what the `<= 1` form missed.
- **§10.2 `(e′)`'s new assertion vs W23's two admissible shapes.** The added `loadCmd.valid`-low
  / no-`fire` / occupancy-unchanged assertion is written against the **port**, not against
  `excLoadCmdReady`, so it holds identically under both W23 shapes (the `excLoadAdmit`-qualified
  form and the fire-condition-derived form). That is deliberate: the round-5 claim failed
  precisely because it held under only one of the two.
- **§6.6's corrected count vs the FIFO's 3-entry bound and W9.** The new "at most two per
  exception load" is checked against both: `walkerLoadAdmit(i)`'s FIFO term is `!ldOwnerFifo.full`
  (not empty-or-single-slot), and W9 rotates the grant, so ITLB-then-DTLB in successive window
  cycles is admissible — and the 3-entry bound already presupposes exactly such back-to-back
  accepts, so the corrected count is consistent with it rather than newly straining it. §6.6's
  boundedness and acyclicity conclusion is unchanged and was **not** re-derived.

**[R6] Nothing established by W1-W30 or by the round-5 fixes is reopened.** Explicitly affirmed
by the review and left untouched here: every DECIDED item W1-W30 in substance; §4.3's replaced
assertion and its second (`ldOwnerFifoOccupancy`) assertion; W29's push-payload derivation and
pairwise-exclusivity **proof** (only the assertion *form* that reports a violation of that proof
changed, not the proof); W30 parts (1) and (2); W28 clauses (b)/(c); W27's table coverage; §6.6's
boundedness/acyclicity **proof**; §11.1 item 9's reclassification. The count stays at **30
(W1-W30)** and 4 recorded non-decisions (N1-N4).

---

## 13. Review disposition (round-2 fix pass, 2026-08-18)

An independent review of `fc90cf5` returned **changes requested — implementation must not
start until re-reviewed**, with 2 Critical, 8 Important and 7 Minor findings. Disposition:

| Finding | Disposition | Where |
|---|---|---|
| **C1** `excLoadCmdReady` leak (`LsEuPlugin.scala:2115`) | **Closed.** New DECIDED item **W23**, with the exact one-line fix and the identical fix for `excLoadOutstanding`'s set term; added to §8's change list and §10.2's directed tests | §4.2.7, §0, §8, §10.2 |
| **C2** `sq.io.drain.ready` leak (`LsEuPlugin.scala:265`) | **Closed.** New DECIDED item **W24**, extending the existing `:2068` hold pattern to the walker case; added to §8 and §10.2 | §4.2.7, §0, §8, §10.2 |
| **I1** W12's FMax claim incomplete | **Adopted.** §5 rewritten: three free substitutions vs two real additions (the 3→5-way `vaddr` mux onto `rdSet`, and `probeCancelAll`'s OR term), with per-site levers; the absolute "zero new logic levels" claim withdrawn | §5, §0 (W12) |
| **I2** walker `token` must be reserved | **Adopted.** New DECIDED item **W25** (`0x81`/`0x82`), following the `0x80` precedent | §4.5, §0, §10.2 |
| **I3** W5's D9 justification does not transfer | **Adopted.** Conclusion kept, justification replaced with the ROB-commit separation argument; W10 step 1 and §9.2's alternatives list corrected too | §4.2.2, §4.2.5, §9.2, §0 |
| **I4** ownership-FIFO alternative | **Adopted — the largest single change.** W7 rewritten as a depth-4 ownership FIFO on loads with drain-to-zero surviving only at the walker ↔ `CORE-EXC` boundary; both caveats stated; W8's pressure knock-on recorded | §4.2.3, §4.2.4, §4.3, §3, §0 |
| **I5** W10's watchdog gap | **Adopted, option (a).** New DECIDED item **W26**: a production counter on D28's halt-reason channel, scoped to this arbitration point | §4.2.5, §0 |
| **I6** §8 blast-radius numbers wrong | **Adopted.** §8.1 rewritten with corrected counts (24/12/5/5/2), the new LS-cluster bucket, the **inverted** `sharedMem` characterisation, 7 files / 8 DUT classes for W18, and both citation fixes | §8.1, §11.2 |
| **I7** two D20 watchdogs, not one | **Adopted.** Both named; the write-side drop labelled a deviation; `io.wedgeIsRead` and the vacuous assertion block called out; §10.2 updated | §9.2, §10.2 |
| **I8** D19 not "unaffected" | **Adopted.** §9.3 gains a D19 re-establishment item connected explicitly to W16, and W16 is reclassified from optional improvement to mandatory | §9.3, §4.5 |
| **M1** `quiesceHold` must span `S_APPLY` | **Adopted** | §6.2 |
| **M2** mixed `DcachePlugin` line snapshots | **Adopted.** Citation convention stated in the header; citations anchored by symbol | header, throughout |
| **M3** `:1738` miscited | **Adopted** | §4.1.4 |
| **M4** §4.6 internal inconsistency | **Adopted.** Replacement latch condition specified | §4.6 |
| **M5** paddr/vaddr lane reason wrong | **Adopted** | §4.5 |
| **M6** N4 omits `storePort.ready` | **Adopted** | §0 (N4), §6.3 |
| **M7** three citation fixes | **Adopted.** Bullet #11 not "Global Constraint #3"; `:110-122` not `:110-127`; Task 13's two `itlbAxi` textual references flagged | §9.1, §9.2 |

**Affirmed by the review and deliberately left unchanged in substance:** W1/W2/W3 (the
WRITETHROUGH cacheMode policy and its cost analysis), W11's early-probe deadlock fix (modulo
W25's token addition), W13 (mandatory, per `StoreQueue.scala:518`), W19's premise and its
`S_DRAIN`-scoped (not blanket-`excActive`) hold shape (modulo M1's one-state extension),
W16's `selectWord` deletion (byte-identical to `DcacheByteLane.extract`'s LONG case), W20
(Task 4's disposition — `38e6c31` genuinely bundles the guards with `RESET_VEC`, so a `git
revert` is impossible), and §6.1's no-circular-translation-dependency finding.

---

## 14. Review disposition (round-3 fix pass, 2026-08-18)

`ca850ea` was re-reviewed and returned **changes requested** again, with 2 Critical, 3
Important and 4 Minor findings. Both Critical findings are about round 2's *own* work, not
about the original design. Disposition:

| Finding | Disposition | Where |
|---|---|---|
| **C1** W23/W24 cover 2 of 9 sites of one defect shape; plus one entirely ungated command-`valid` drive (the split leg) | **Closed, and closed by method rather than by patch.** New DECIDED item **W27**: an exhaustive per-site table of **every** `dcache.*` handshake driver and consumer in `LsEuPlugin.scala`, re-derived from the file, each row carrying a fix or a justified no-op. **29 rows in total: 10 marked `NEW — FIX`** (`:756`, `:1508`, `:1536`, `:1574-1575`, `:2027-2028`, `:2067`, `:2069`, `:2070-2071`, `:2076`, `:2073`), **1 marked `NEW — stated`** (`:263-264`, safe only by driver ordering, which is now recorded as load-bearing), and **18 already-decided or justified-needs-no-term rows** — the last group deliberately included so a later reviewer can tell "considered and justified" from "not looked at". The split leg gets a two-part fix — arbiter **visibility** *and* admission gating — because gating without requesting converts silent corruption into livelock. Three new directed-test rows in §10.2 | §4.2.7 (table + the split-leg worked failure), §0 (W27), §4.2.3 caveat (a), §8, §10.2, §11.2 |
| **C2** W23's own fix line is short one term and reopens C1 in a new window | **Closed.** `excLoadCmdReady := dcache.loadCmd.ready && excLoadAdmit`, and `excLoadOutstanding`'s set term likewise — **not** `(ldOwner === CORE) && excActive && excLoadCmdValid`, which is satisfiable in exactly the window the design must protect, because `dcache.loadCmd.ready` never references `valid` and `ldOwner` records the *current grant* while the hazard is *in flight*. W23's rationale is rewritten from "must be the one PRESENTING" to "must be the one ADMITTED". The round-2 line is left in the document, explicitly marked **DO NOT IMPLEMENT**, so the distinction is not re-lost. §10.2 gains test (e′), which must be shown to **pass** against the round-2 line and fail without `excLoadAdmit`. The round-3 sweep also found that `excLoadAdmit` had never been bound to any RTL site at all (`:2027`), so the withholding C2 assumes did not exist — fixed in the same pass | §4.2.7 (W23), §4.2.3, §0 (W23), §10.2 |
| **I1** §4.2.4's `force` semantics are stale and contradict §4.2.3 | **Adopted.** The "drain to zero first" paragraph is rewritten per direction: **no drain at all** on the load direction (the FIFO removed it), drain-to-zero surviving on stores. §4.2.5 step 3 restated the same stale model and is corrected with it. `WALKER_AGE_LIMIT` explicitly **not** touched — it stays 64, SAFE-but-possibly-non-optimal, with the non-blocking re-derivation step intact as *tuning*, not correctness | §4.2.4, §4.2.5 |
| **I2** §4.2.3 misattributes a FIFO-full edit to the protected `DcachePlugin.scala` | **Adopted.** The sentence is replaced with an explicit statement that the term belongs on `LsEuPlugin`'s own `dcache.loadCmd.valid` admission, naming `DcachePlugin.scala:759`'s own comment about the protected `rdSet` arc as the reason. Document re-swept for any other implied edit to that file: none | §4.2.3 |
| **I3** the same-cycle owner-coherence invariant is only implied | **Adopted.** New DECIDED item **W28** with its own section: the mux's selection and every qualification term read the identical owner value on the identical cycle. Two admissible implementations named (registered-grant preferred, consistent-combinational permitted-but-priced), the mixture forbidden, and a directed check plus a simulation assertion required | §4.2.8, §0 (W28), §10.2 |
| **M1** W18's file count arithmetic | **Adopted.** **8 files / 8 DUT classes**, not 7/8 — `DtlbMissFlushSpec` is an eighth *file* contributing the eighth DUT even though only one of its two DUTs is in the bucket. Fixed at all four sites; enumeration itself was already right. Re-verified by direct DUT-class and `DcachePlugin`-reference counts over all eight files | §0 (W18), §8.1, §10.2, §11.2 |
| **M2** W25's constants need the `DcacheTypes.scala` doc comment amended too | **Adopted.** The constants are genuinely disjoint, but `DcacheTypes.scala:6-8`'s layout comment makes `0x81`/`0x82` read as "exception unit, robId 1/2", which is false. The comment amendment is made a required part of W25, and §8's "byte-identical alternative" note is corrected — the comment must change wherever the constants are declared | §4.5, §0 (W25), §8 |
| **M3** unqualified superlative in §4.2.3 caveat (b) | **Adopted.** "Strictly better" removed; the trade is stated in both directions — latency improves, probe-destruction **frequency rises** (a walker now interleaves into a live stream instead of being admitted only into a quiesced port, so each of up to three descriptor reads can destroy up to 4 resident probes) | §4.2.3 |
| **M4** two locator drifts | **Adopted, with one of the two proposed values itself corrected.** The socket spec's D19/`TableWalker.scala:114` evidence is at `:1146` (was `:1145`) — as proposed. The `AxiDMerge` read-side watchdog is at **`:225-230`**, not the `:224-231` proposed: `:223-224` are the block's comment and `:231` closes the `Area`, and `:225-230` is exactly parallel to the already-exact write-side `:298-303`. Both corrections are recorded in place | §9.2, §9.3 |

**Affirmed by the review and deliberately NOT reopened in round 3:** W1-W22 and the I4
ownership-FIFO **concept**, all independently re-verified against the RTL by the reviewer;
W25's file placement; `WALKER_AGE_LIMIT = 64`; and W26's bound being left for the plan to
derive numerically (its optional polish — that `AxiDMerge`'s precedent is about copying a bad
*derivation* rather than a bad *value* — was noted and the conclusion is unchanged either way,
so §4.2.5 is left as written).

**Status after this pass:** still **PROPOSED / DESIGN ONLY**, awaiting re-review. No RTL has
been touched by this document at any point.

---

## 15. Review disposition (round-4 fix pass, 2026-08-18)

`e74a7a6` was re-reviewed and returned **changes requested** a third time, with 2 Critical and
3 Minor findings. The review independently re-verified and **confirmed correct** the whole of
round 3's substantive work — the 29-row arithmetic by direct recount, all four newly-found
sites, C2's corrected predicate by trace, every locator correction, the `DO NOT IMPLEMENT`
marking, I1/I2/M1/M2/M3, and the placeholder scan. None of that is reopened here. Both
Criticals are, once again, about **the mechanism the previous round's fix introduced**, and
both fall on the **write** side of state whose **read** side was specified carefully.
Disposition:

| Finding | Disposition | Where |
|---|---|---|
| **C-R4-1** W28 covers *when* the owner is sampled, never *which* value is pushed into the ownership FIFO — and the mux legs are gated by admission predicates that never write `grantedOwner`, so the pushed tag can name a client that did not fire | **Closed, by respecifying the payload rather than adding a guard around it.** New DECIDED item **W29**: the push payload is a **four-valued** `ldRspTag` (`CORE_LS`/`CORE_EXC`/`ITLB`/`DTLB`) encoded **combinationally from the four admission predicates themselves** at the cycle of `dcache.loadCmd.fire`, never from any separately-clocked owner register, under **either** of W28's admissible implementations. The four predicates are **proved pairwise exclusive** (four short arguments, one per pair, each holding under both implementations) so the one-hot is well-defined, with a `GenerationFlags.simulation` assertion making the proof falsifiable. Brought inside **W28 as clause (b)** — the same invariant, not a footnote — and W28 clause (a)'s round-3 sentence that explicitly licensed the bug ("the registered owner used **only** for FIFO push payloads and for reporting") is withdrawn in place with the withdrawal called out. `rspOwner` becomes **derived** from the tag so there is one source of truth, and the four W27 `rspOwner === CORE` rows tighten to `ldRspTag === CORE_LS`. §10.2 gains the directed test the review specified — the granted-but-not-fired walker followed by a next-cycle exception, plus its mirror, both required to fail against `push <= grantedOwner` | §4.2.3 (W29), §4.2.8 (W28 clause (b)), §0, §4.2.7, §4.3, §5, §8, §10.2 |
| **C-R4-1** sub-item: §5 and §4.2.3/W27 gave two silently-conflicting descriptions of the same mux ("selects payloads from `ldOwner`" vs. "gated `when(…Admit)`") | **Closed by reconciling to one description, and by separating the two questions that were being conflated.** §5's bullet now states plainly that **the legs are selected by the admission predicates, not by an owner comparison**, that the owner registers are *inputs to* those predicates and to W26's reporting, and that the FMax claim the bullet exists to make (registered code vs. constant, one LUT from a flop) survives the correction intact. The distinction the review asked to be made explicit — "how the mux selects" (i) vs. "how the FIFO tags" (ii) — is stated as two numbered consequences with the **same** answer signal set, which is the entire content of W28 clause (b) | §5, §4.2.3 |
| **C-R4-1** sub-item: `ldOwner`/`stOwner` have no stated update rule anywhere | **Closed.** **W28 clause (c)**: unconditionally reloaded every cycle from the arbiter's combinational decision (`ldOwner := ldGrantNext`), explicitly *not* "latched on a grant and held" — because a held owner is precisely the current-grant-vs-in-flight confusion that produced C2 — with the relationship to `grant(i)`/`walkerOwnsLoad` spelled out separately for each of W28's two implementations, and an explicit note that **neither register is read by W29's push encode** | §4.2.8 |
| **C-R4-2** `excLoadOutstanding` has no stated clear condition anywhere, while W7/W14's whole argument rests on it — and the document points a plan-writer at an unqualified `dcache.loadRsp.valid` mirror | **Closed, as shape (b): the invariant a naive mirror would need was investigated against the RTL and is FALSE.** New DECIDED item **W30**, in two mandatory parts. **(1)** The clear term is `dcache.loadRsp.valid && (ldRspTag === CORE_EXC)` — W29's tag, proved sound *directly* (one FIFO entry per accepted command, strictly in-order completion, exactly one response per command ⇒ the head tag names the command this response answers; the tag is pushed iff `excLoadAdmit` gated the fire, which is the same predicate that **sets** the register) with no cross-section invariant behind it. **(2)** `excLoadAdmit`'s FIFO term is strengthened from `!ldOwnerFifoHoldsWalker` to **`ldOwnerFifoEmpty`**, because part (1) fixes the register but not `ExceptionUnit`'s own `dcLoadRsp` sampling, which W14 requires to stay unqualified and which lives at the top level. Both parts are needed; neither alone closes the failure. **Why shape (a) was rejected:** `E_DRAIN`/`R_DRAIN` wait on `sqDrained` **only**, which is a pure `StoreQueue` fact (`sq.io.empty` = `!valids && !drainBusy`, `drainBusy = acceptedHalves =/= 0`) — that is exactly the invariant that makes the *store*-side `:2073` mirror safe, and it has **no load-side analogue**: `alignedRspValid` deliberately carries no `excActive` term. A concrete reachable trace is written out (CORE-LS miss → `REPLAY` returns to `IDLE` with the replayed read still in S1 → `loadCmdPort.ready` high one cycle before that response lands → exception's held, registered `dcLoadCmd.valid` fires into it). `rspOwner` provably cannot separate the two, because W6 makes them one owner. §10.2 gains the directed test, and §11.1 item 9 records the **pre-existing HEAD bug** the investigation exposed (an exception-sequencer load consuming an LS load's response), which W30 part (2) closes as a by-product and which is why that part is mandatory rather than defensive | §4.2.3 (W30), §4.3 (W14), §0, §4.2.7, §10.2, §11.1 item 9, §11.2 |
| **M-R4-1** W27's table is not fully exhaustive and its "no other `dcache.*` reference exists in the file" claim is false (`LsEuPlugin.scala:886`'s `parallelViptLaunch`) | **Adopted.** A 30th row added with the disposition its CORE-only neighbours have (walkers never launch a probe per W25; `loadProbe.valid` is already CORE-gated via W11; `xlate.req.fire` is CORE-only via `lsXlateReqValid`'s `!excActive`) — a missing **row**, not a missing fix. The false closing claim is replaced with one that is actually true and checkable ("every `dcache.*` reference is accounted for above, including `:886`"), with the instruction to re-derive it rather than trust the sentence. Re-verified by a fresh full sweep of the file (63 hits) | §4.2.7 |
| **M-R4-2** asymmetric row coverage: the store base payload (`:263-264`) has a driver-ordering row, the load base payload (`:757-764`) has none | **Adopted.** Row added, recording last-assignment-wins as load-bearing for the load payload exactly as `:263-264` does for the store, and noting that after W29 the *tagging* no longer depends on that ordering while the *payload selection* still does — which is the only reason the asymmetry was survivable at all | §4.2.7 |
| **M-R4-3** `:2069`'s row is mischaracterised as "the store-side exact twin of W23" and prescribes a redundant `&& excStoreAdmit`, contradicting the adjacent `:2070-2071` row | **Adopted.** `:2115` is unconditional at **file** scope; `:2069` already sits inside `when(excActive && excStoreValid)` with `excStoreReady := False` defaulted outside, so once `:2067`'s header becomes `when(excStoreAdmit)` it inherits the predicate. The row now says "no separate term needed", matching its neighbour. The substantive half of round 3's note (that `storePort.ready` no more references `valid` than `loadCmdPort.ready` does, and the load/store asymmetry that produced C2) is retained, because it is still true and still load-bearing | §4.2.7 |

**The recurring pattern, and what was done about it this round.** Rounds 1→2→3→4 all have the
same shape: the fix closes every site it enumerates, and leaves a hole in the mechanism it just
introduced. Round 4 therefore added a step the earlier rounds did not take — **after** writing
W29 and W30, W23/W24/W27 were re-swept a second time asking specifically whether *this round's
own new logic* introduces an unqualified handshake reference. It does: the FIFO **pop** is a
bare `dcache.loadRsp.valid` consumer that has existed unrowed since round 2 and that must
**stay** unqualified, and W30's clear term is a new `loadRsp` consumer that needed one. Three
rows were added, one item listed for completeness, two checked and correctly needing none; the
enumeration is written out in §12 under **[R4]**. The generalisation is written into W28 as
clauses (b) and (c) and into W27's derivation note as a standing rule, so the next round does
not have to rediscover it: **a state element is not specified until both its write value and
every one of its write conditions are, and new logic is subject to W27's table on the same
terms as the logic already in the file.**

**Affirmed by the review and deliberately NOT reopened in round 4:** the 29-row arithmetic and
its 10/1/18 split; all four sites round 3 newly found (`:756`, `:1508`/`:1536`, `:1574-1575`,
`:2027-2028`) and their store-side counterparts (`:2067`, `:2076`) and `:2073`'s
conditional-safety argument and `:263-264`'s last-assignment-wins claim; C2's corrected
predicate (confirmed by trace — and only *strengthened* here, never replaced); the
`DO NOT IMPLEMENT` marking on round 2's superseded line; every locator correction round 3 made
(`:756` not `:758`, `excStoreOutstanding` at `:268`, `AxiDMerge.scala:225-230`); round 3's
I1/I2/M1/M2/M3; and **W28's scope on the READ side**, which is correct as written — the gap was
specifically the write side, and clause (a) is preserved verbatim apart from the single
sentence that licensed C-R4-1.

**Status after this pass:** still **PROPOSED / DESIGN ONLY**, awaiting re-review. No RTL has
been touched by this document at any point. **30** DECIDED items (W1-W30), 4 recorded
non-decisions (N1-N4).

---

## 16. Review disposition (round-5 fix pass, 2026-08-18)

`aab837e` was re-reviewed and returned **changes requested** a fourth time, with 1 Critical,
1 Important and 4 Minor findings. The review independently re-verified and **confirmed correct**
W29 (the push-payload redefinition and its pairwise-exclusivity proof, under *both* W28 shapes),
W30 parts (1) and (2), W28 clauses (b)/(c) (including that round 3's licensing sentence was
genuinely withdrawn rather than left contradicting the new rule), all three W27 rows round 4
added (including the "the FIFO pop must **stay** unqualified" reasoning), §6.6's
boundedness/acyclicity proof, §4.3's **second** assertion, and W27's table coverage against a
fresh independent 59-reference count. **None of that is reopened here.** No DECIDED item is
added, removed or changed in substance by this pass: the count stays at **30 (W1-W30)**.

| Finding | Disposition | Where |
|---|---|---|
| **C-R5-1** §4.3's mandated load-side simulation assertion fires on **correct** behaviour. It tests `!(!ldOwnerFifoEmpty && (excActive && excLoadCmdValid) && !excLoadOutstanding)` — but `excLoadCmdValid` is `ExceptionUnit`'s **held, registered** `ldoValidReg`, high from the `*REQ` state until fire, so under W30 part (2) that predicate *is* the intended wait, held continuously for the whole drain. §6.6, §10.2's `(e′)` row and §10.2's W30 row all specify that same state as correct, and running them as written would abort the simulation | **Adopted; the assertion is replaced, not weakened around.** The property actually wanted is about **admission**, not presentation: `assert(!(excLoadAdmit && !ldOwnerFifoEmpty), "excLoadAdmit was weakened: …", FAILURE)`. It is deliberately **tautological against W30 part (2)'s own predicate** — a *structural tripwire* against a future weakening of `excLoadAdmit`'s FIFO term, not a runtime timing check. It cannot fire under a correct implementation and fires immediately if anyone relaxes that term (which would reopen C2 *and* re-inherit §11.1 item 9's live bug). It is also the only form `LsEuPlugin` can hold **by construction**: `excLoadAdmit` is `LsEuPlugin`'s own expression, whereas `ldoValidReg` is driven by `ExceptionUnit` and is outside its control. Both superseded forms are retained as comments with their distinct failure modes named, since §8 carries this block into the implementation list. The **second** assertion (`ldOwnerFifoOccupancy`) is confirmed correct and is untouched | §4.3 |
| **I-R5-1** §11.1 item 9's disposition ("out of scope to fix as a standalone task") under-escalates a confirmed **live** production bug and scope-ties its fix to this redesign's timeline | **Adopted.** The technical trace and every citation are preserved verbatim (independently re-confirmed correct); only the disposition sentence changes. It now states plainly that this is a real, currently-live, silent-corruption bug in already-shipped code, **tracked and fixed as its own standalone task** in `ExceptionUnit.scala`/`LsEuPlugin.scala` alone, independent of whether this design is ever built — and that a reader must not conclude it is only closeable via this redesign landing. What this document still owes it is unchanged: this design must not *build on* the assumption it violates, which is why W30 part (2) is mandatory | §11.1 item 9 |
| **M-R5-1** `ldOwnerFifoOccupancy` is used by §4.3's second assertion but defined nowhere | **Adopted.** Defined in §4.2.3 beside `ldOwnerFifoEmpty`/`ldOwnerFifoHoldsWalker`, added to §4.2.3's FIFO pseudocode and to §8's symbol list. All three views (and `ldRspTag`/`rspOwner`) are specified **pre-pop and pre-push** — the FIFO's registered state entering the cycle, on a simultaneous push+pop cycle too. That reading is stated as load-bearing: on the cycle the exception's own response lands, `excLoadOutstanding` is still `True` and its entry is still the registered head, so the assertion holds; under a post-pop reading occupancy would read `0` and the assertion would fire on correct behaviour | §4.2.3, §4.3, §8 |
| **M-R5-2** `ldPushTag`'s `when`-chain is ordered `CORE_LS < CORE_EXC < ITLB < DTLB`, the **opposite** of the payload mux's own last-assignment-wins order (CORE-LS base → walkers → CORE-EXC last) | **Adopted.** Reordered to mirror the mux. Harmless today only because W29's exclusivity proof means at most one leg is ever true — i.e. correct by a *separate* proof that happens to agree, which is the C-R4-1 shape. Now correct **by construction**: had the exclusivity ever been violated, the old chain would have mis-attributed in the opposite direction from the payload actually presented. The one-hot assertion is explicitly **not** made optional by the reorder; what the reorder buys is that a falsified proof degrades consistently instead of silently mis-tagging | §4.2.3 |
| **M-R5-3** §6.6's third bullet reads as if `ldBusyExc` stays high across a whole exception episode; it is only true **per load** | **Adopted, wording only — the proof's substance is unchanged and was confirmed correct.** The bullet now states the per-load scope explicitly and names the real 1-2 cycle window between consecutive exception loads (`excLoadOutstanding` clears one cycle after the response lands; `ldoValidReg` does not rise again until the next `*REQ` state) in which a walker **can** be admitted. The bound still holds and is restated in its true form: at most a bounded number of walker commands interposed per exception load, not per episode. *(Round-6 amendment: round 5 wrote that as "**at most one** walker command interposed per exception load". The scoping fix was right; the **count** was not, and it is corrected by M-R6-2 to **at most one per window cycle, i.e. at most two per exception load**. See §6.6.)* | §6.6 |
| **M-R5-4** `walkerLoadAdmit(ITLB_ID)` is a positional **vector index** while `LdRspTag.ITLB` is a **tag value**; §12 called `ITLB_ID` "the existing owner-code constants", conflating three encodings that are off by one from each other | **Adopted, naming only.** The vector positions are renamed `WALKER_ITLB_IDX`/`WALKER_DTLB_IDX` at the sites round 5 touched — the push encode, the exclusivity proof and the exclusivity assertion — so they cannot be read as interchangeable with `LdRspTag.ITLB`/`LdRspTag.DTLB` or with the three-valued arbiter owner code. §12's round-4 placeholder sentence is corrected in place rather than silently rewritten. *(Round-6 amendment: this disposition originally read "renamed **everywhere they appear**", which overstated it — three further sites kept the old ambiguous form (§0's W29 row, §4.2.3's W29 worked-failure step 2, §10.2's W28/W29 test row). Those are renamed by M-R6-1 and the claim is now scoped to what round 5 actually touched. The rename is complete as of round 6, verified by a whole-document sweep — see §17.)* | §4.2.3, §12, §0, §10.2 |

**What this round did *not* find, and why that is worth recording.** Rounds 2→3→4 each closed
their findings while leaving a hole in the mechanism they introduced. Round 5 found no such
hole: W29's and W30's mechanisms are intact, and the one Critical was in a **check** written
*about* those mechanisms rather than in the mechanisms themselves — an assertion phrased against
the wrong one of two signals that differ only in *when* they are true. The generalisation worth
carrying forward is narrower than the previous rounds' and is stated here so it is not
rediscovered: **an assertion must be written against a predicate the asserting module can hold
by construction.** `excLoadAdmit` is `LsEuPlugin`'s; `excLoadCmdValid` is `ExceptionUnit`'s, and
no edit inside `LsEuPlugin` could ever have made the round-4 form true.

**Affirmed by the review and deliberately NOT reopened in round 5:** W29 in full (push payload
+ pairwise-exclusivity proof, verified under both W28 shapes); W30 parts (1) and (2); W28
clauses (b) and (c); round 4's three new W27 rows including the FIFO pop's "must stay
unqualified" justification; §6.6's boundedness and acyclicity proof (substance — only its
per-load scoping wording changed); §4.3's second (`ldOwnerFifoOccupancy`) assertion, confirmed
to hold at every cycle; W27's table coverage, re-confirmed against a fresh independent
59-reference sweep; and all of round 3's work, re-affirmed a second time.

**Status after this pass:** still **PROPOSED / DESIGN ONLY**, awaiting re-review. No RTL has
been touched by this document at any point. **30** DECIDED items (W1-W30), unchanged in count
and in substance by this pass, and 4 recorded non-decisions (N1-N4).

---

## 17. Review disposition (round-6 fix pass, 2026-08-18)

`f96b2ea` was re-reviewed and returned **1 Important and 2 Minor** findings — **no Critical**,
the first round of this document's seven passes to return none. The review independently
re-verified and **confirmed correct the entire substantive design, W1-W30**, together with all
of round 5's other fixes: §4.3's replaced admission-side assertion and its second
(`ldOwnerFifoOccupancy`) assertion, W29's push-payload derivation and pairwise-exclusivity proof
under both W28 shapes, W30 parts (1) and (2), W28 clauses (b)/(c), W27's table coverage, §6.6's
boundedness and acyclicity proof, and §11.1 item 9's reclassification. **None of that is
reopened, and this pass adds no new design content and re-derives nothing.** No DECIDED item is
added, removed or changed in substance: the count stays at **30 (W1-W30)**.

| Finding | Disposition | Where |
|---|---|---|
| **I-R6-1** §4.2.7's knock-on correction cites a coverage claim that is **false** under the document's own `CORRECT (W23)` implementation shape. Round 5 replaced round 3's withdrawn "trips §4.3's assertion" claim with "§10.2's directed row `(e′)` catches it" — but row `(e′)`'s three assertions read only `excLoadCmdReady` and the exception FSM's state, never `dcache.loadCmd.valid`/`fire`. Under `excLoadCmdReady := dcache.loadCmd.ready && excLoadAdmit`, with a walker in the FIFO and the `:2027` header left **unqualified**, `excLoadAdmit` is correctly false, so `excLoadCmdReady` stays low (assert 1 passes), the FSM does not advance (assert 2 passes) and `ExceptionUnit` never samples a response (assert 3 passes) — while the unqualified header still drives `dcache.loadCmd.valid := True` as the mux's last driver into a high `ready`, firing the port **spuriously every cycle**, so `ldPushTag` falls through to `CORE_LS` and bogus entries pile in behind the walker's, past the FIFO's proven 3-entry bound and into mis-routed responses | **Adopted, and fixed in BOTH places the review offered as alternatives — deliberately, not belt-and-braces.** The review noted either alone would suffice; both are taken because the *second* restores runtime coverage two earlier rounds believed they already had, and because this is the **second** time this one claim has been re-pointed rather than repaired (round 3 → §4.3; round 5 → row `(e′)`), so a third re-aim would be the wrong move. **(1)** §10.2's row `(e′)` gains an explicit assertion that `dcache.loadCmd.valid` stays **low** — equivalently no `dcache.loadCmd.fire` at all and `ldOwnerFifo` occupancy unchanged — for the **entire** withheld window, and must be shown to fail against an unqualified `when(excActive && excLoadCmdValid)` presentation header, not only against round 2's `excLoadCmdReady` line. This is written against the **port**, so it holds under **both** of W23's admissible shapes — the exact defect in round 5's claim, which held only under the fire-condition-derived shape. **(2)** §4.2.3's one-hot exclusivity assertion becomes a **fire-qualified equality**: `when(dcache.loadCmd.fire) { assert(CountOne(Cat(coreLsLoadAdmit, excLoadAdmit, walkerLoadAdmit(WALKER_ITLB_IDX), walkerLoadAdmit(WALKER_DTLB_IDX))) === 1, …) }`. The old `<= 1` passes when **zero** predicates are true, which *is* the unqualified-presentation failure state — a `fire` no admission predicate authorised. `=== 1` at `fire` catches it on the first offending cycle, is still a property `LsEuPlugin` holds by construction (all four predicates are its own expressions), and satisfies the [R5] "assert only what the asserting module controls" standard. §4.2.7's W27 table row for that assertion is rewritten accordingly — round 4 had listed it as "reads no `dcache.*` port, outside the defect class", which stops being true once it is `fire`-qualified, so it is now a **live check** rather than a completeness listing. The `:1279`-area parenthetical is rewritten to cite **both**, to state the failure trace explicitly, and to **drop** the claim that either one alone was ever sufficient | §4.2.7 (W23), §4.2.3, §4.2.7's W27 table, §10.2 |
| **M-R6-1** M-R5-4's vector-index-vs-tag-value rename is **incomplete**, and §16 overstates it as "renamed everywhere they appear" | **Adopted.** Three surviving sites used the old ambiguous `walkerLoadAdmit(ITLB)`/`walkerLoadAdmit(DTLB)` form and are renamed: **§0's W29 DECIDED summary row** (the most-read part of the document), **§4.2.3's W29 worked-failure walkthrough step 2**, and **§10.2's W28/W29 directed-test row** — the last being the one that matters most, since it is a **test prescription a plan-writer copies verbatim**. §16's M-R5-4 disposition is scoped precisely to the sites round 5 actually touched, with the overclaim removed and a round-6 amendment recording the completion. Verified by a **whole-document** sweep (not just the three named sites — round 5's failure *was* an unverified "everywhere" claim), which now returns **zero prescriptive hits** for either old form — the only surviving instances of the string are the disposition rows that quote the retired form by name (§16's M-R5-4 statement and this row), which must keep quoting it to remain legible; see §12's `[R6]` sweep entry | §0, §4.2.3, §10.2, §16 |
| **M-R6-2** §6.6's round-5 "at most **one** walker command interposed per exception load" does not follow from its own stated 1-2 cycle window. `walkerLoadAdmit(i)` carries `!ldOwnerFifo.full` — **not** an empty-or-single-slot exclusion — and W9's round-robin rotates the grant, so ITLB can fire on the window's first cycle and DTLB on its second. The FIFO's own proven max-of-3-concurrent-entries bound presupposes exactly such back-to-back accepts | **Adopted; the count is corrected, not the argument.** The bullet now reads **"at most one walker command per cycle of the window — i.e. at most two per exception load"** (two walkers, a window of at most two cycles), with a round-6 note naming *why* the round-5 count was wrong (`!ldOwnerFifo.full`, plus the rotation) so it cannot be re-derived. §6.6's boundedness and acyclicity **proof is untouched and was not re-derived**: a bounded window admitting at most one command per cycle interposes a bounded number of bounded D-cache accesses either way, which is the only thing the bullet exists to establish. §16's M-R5-3 disposition, which restated the wrong count, carries the same amendment | §6.6, §16 |

**What this round says about the document's trajectory.** Rounds 2→3→4 each closed their
findings while leaving a hole in the mechanism they had just introduced; round 5 found no such
hole but had one over-eager **check**; round 6 found no hole in any mechanism **and** no
mis-firing check — its Important is in a **claim about** a check's coverage, and its two Minors
are a count and an incomplete rename. The generalisation worth carrying forward, and the reason
I-R6-1 was fixed in two places rather than one: **when a coverage claim is withdrawn, re-pointing
it at a different existing check is not a fix unless that check is re-read against the failure
trace.** Both rounds 3 and 5 named a check that looked adjacent to the failure and neither
re-traced it; the second attempt failed the same way as the first. The other half of it is
narrower and worth stating separately: **`<= 1` and `=== 1` are not interchangeable for a
one-hot.** The inequality admits the zero case, and for an admission one-hot the zero case is not
vacuous — it is a live failure mode with a name (`fire` with nothing admitted).

**Affirmed by the review and deliberately NOT reopened in round 6:** the whole substantive
design, **W1-W30**, explicitly confirmed sound; every round-5 fix other than the two claims
corrected above (C-R5-1's replaced §4.3 assertion, I-R5-1's §11.1 item 9 reclassification,
M-R5-1's `ldOwnerFifoOccupancy` definition and pre-pop rule, M-R5-2's `ldPushTag` reorder); and
all of rounds 3 and 4's work, re-affirmed again.

**Status after this pass:** still **PROPOSED / DESIGN ONLY**, awaiting re-review. No RTL has
been touched by this document at any point. **30** DECIDED items (W1-W30), unchanged in count
and in substance by this pass, and 4 recorded non-decisions (N1-N4).
