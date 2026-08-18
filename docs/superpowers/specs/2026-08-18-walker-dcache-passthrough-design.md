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

**Numbering note.** Decisions here are numbered **W1-W26** rather than continuing the
socket spec's `D` series, because this spec *reworks* several `D` items and reusing the
same namespace would make "D9" ambiguous between two documents.

**Revision note (review-driven fix pass, 2026-08-18).** This document was reviewed
independently after `fc90cf5` and returned *changes requested* with 2 Critical, 8 Important
and 7 Minor findings. Every one is folded in here. The two Critical findings became
**W23** and **W24** (two unconditional `ready` leaks in `LsEuPlugin` that this design turns
into silent-corruption bugs), and the single largest substantive change is **W7**, which
replaces the original drain-to-zero hand-over on the load direction with an
**ownership-tag FIFO**. §13 records the review disposition item by item.

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
| **W7** | **Load direction:** a **depth-4 ownership-tag FIFO** inside the arbiter — the issuing owner is pushed on every accepted `loadCmd` and popped in lockstep with every `loadRsp` — so `CORE` and a walker may be concurrently outstanding, with **no** `DcacheService` bundle change. Drain-to-zero survives **only** at the walker ↔ `CORE-EXC` boundary. **Store direction:** drain-to-zero, unchanged. (§4.2.3) |
| **W8** | Base priority is `CORE-EXC > CORE-LS > walkers`, i.e. **today's behaviour exactly** whenever no walker is pending. Fairness comes from an **aging counter** per walker: after `WALKER_AGE_LIMIT` un-granted cycles a walker's `force` bit outranks `CORE-LS` (never `CORE-EXC`'s own presented command). `WALKER_AGE_LIMIT = 64`, constructor-parameterised for directed tests. Under W7's FIFO the force path is a rare fallback (probe-window/slot contention only), not the common case. (§4.2.4) |
| **W9** | The two walkers tie-break against each other with a **1-bit round-robin**, per direction. (§4.2.4) |
| **W10** | **No bounded-grant timer** (no D20 analogue) is built at this layer: starvation is structurally bounded by W7 + W8 and the bound is *proved* in §4.2.5. But the *observability* half of D20 is **not** declined — see W26. (§4.2.5) |
| **W11** | Handing the load port to a walker also asserts `LsEuPlugin`'s **existing** `probeCancelAll` (`LsEuPlugin.scala:863`) and suppresses new probe launches, by folding the owner bit into the **same** `!excActive` conjunctions at `:863`, `:716` and `:1774-1776`. Without this the design **deadlocks** — see §4.2.6. This is a correctness requirement, not an optimisation. |
| **W12** | FMax mitigation is *structural* but **not costless**, and this spec does not claim it is. Three control-signal sites are pure `excActive`-conjunction substitutions (zero new levels). Two sites are genuinely new fan-in: the `loadCmdPort.payload.vaddr` mux widens 3-way → 5-way on the net that feeds `cmdSet` → `rdSet`, and `probeCancelAll` gains an OR term. Those are **mitigated, not eliminated**, and the mandatory 3-checkpoint synth gate is the control. (§5) |
| **W13** | The walkers' `storeAck`/`storeErr` are **demultiplexed by the latched store owner**, and the existing `sq.io.drainAck := dcache.storeAck && !excStoreOutstanding` (`LsEuPlugin.scala:270`) gains the walker term. This is mandatory: `StoreQueue.scala:518` already asserts on a stray ack. (§4.3) |
| **W14** | The load-response side gets **no bundle-level demux and no top-level rewiring**: `exc.dcLoadRsp` (`FullCoreSynth.scala:350-351`) and every DUT that replicates it stay byte-for-byte untouched. Routing is by W7's FIFO head (`rspOwner`) *inside* `LsEuPlugin`; the exception path keeps the pure temporal-exclusivity argument. (§4.3) |
| **W15** | The walkers' client ports are plugin-level `var` hooks with default-idle `allowOverride` drives, wired by the **top-level/DUT wiring**, exactly mirroring the existing `umCommitValid`/`umFlush`/`excLoadCmdValid` idiom. No new `Plugin` service lookup, no new `FiberPlugin`. (§4.4) |
| **W16** | `TableWalker.selectWord` is **deleted**; the walker consumes `loadRsp.payload.data` directly. This removes a hand-rolled duplicate of `DcacheByteLane.extract`'s LONG case. (§4.5) |
| **W17** | The U/M writeback is emitted as `DStoreCmd` with `useStrb = True`, `strb = drainStrb`, `lineData = drainBeat`, `paddr = drainAddrReg`, `size = LONG` (don't-care under `useStrb`), `precise = False`. The whole `walkerAxi` AW/W/B drain FSM is deleted. (§4.6) |
| **W18** | A **new sim helper `DcacheClientMemAgent`** (a `Stream`/`Flow` analogue of `BehavioralMemAgent`) is built so the DUTs with no `DcachePlugin` keep working. That is **7 files / 8 DUT classes**, not the 5 the original draft counted. (§8.1, §10.2) |
| **W19** | The exception sequencer's maintenance quiesce is protected by a new `quiesceHold` gate that closes **walker** admission only (never `CORE`), asserted over `S_DRAIN` **and** `S_APPLY` — the latter because `maintCmdOut` pulses in `S_APPLY` while `maintBusyReg` only rises a cycle later. Without it `ExceptionUnit.scala:1362-1381`'s written deadlock analysis ("With the LS EU flushed, nothing re-arms them") becomes false. (§6.2) |
| **W20** | Task 4's committed code (`38e6c31`, `56f2438`) is **not reverted**; the walker-side half is deleted along with its host (`walkerAxi` ceases to exist), the `RESET_VEC` ARID half survives untouched, and `WalkerIdGuardSpec` is deleted. `AxiIds.WALK_READ`/`WALK_WRITE` stay defined-but-unused (renumbering is forbidden). (§9.1) |
| **W21** | Task 5's `AxiDMerge` is **shrunk, not deleted and not repurposed**: read side 4 owners → 2 (`DCACHE`, `RESETVEC`), write side 3 owners → 1 (pass-through). The D20 watchdog stays on the read side. Its *code* is not reused for this design's mux; its *bounded-progress discipline* is (as W8's aging counter). (§9.2) |
| **W22** | This is a **separate plan** with its own file. A single **superseding Task 5R** is inserted into the axi-socket-adapter plan, and D7/D8/D9/D19/D20/D27 get an addendum block in that spec. Tasks 6, 7, 8, 9, 10, 11, 12, 14 are unaffected in interface shape; Task 13 needs two textual citation updates. (§9.3) |
| **W23** | **CRITICAL.** `LsEuPlugin.scala:2115`'s `excLoadCmdReady := dcache.loadCmd.ready` is **unconditional** and must be owner-qualified, as must `excLoadOutstanding`'s set term. Today both are safe only because nothing but the exception sequencer can own that `ready`. This is a *silent* corruption once walkers exist, not a hang. (§4.2.7) |
| **W24** | **CRITICAL.** `LsEuPlugin.scala:265`'s `sq.io.drain.ready := dcache.store.ready` is **unconditional**. The existing exception mux already closes exactly this hole at `:2068`; that hold must be extended to cover "a walker owns the store port", or a walker's U/M store silently consumes the SQ drain's `ready` and a core store is permanently lost. (§4.2.7) |
| **W25** | Walker `loadCmd.payload.token` is a **reserved value**, never a don't-care: `ITLB = 0x81`, `DTLB = 0x82`, disjoint from LS-EU probe tokens and from the exception sequencer's existing `0x80`. A don't-care token can collide with a live early-probe entry on token **and** vaddr and silently mis-answer the walker's read. (§4.5) |
| **W26** | The arbitration point gets a **production** (not sim-only) stall counter reporting on **D28's halt-reason channel**. W10 declines D20's *timer*; it does **not** decline D20's *observability*, because a wedge at this new merge point produces no AXI grant to time out and would otherwise be invisible to D19, D20 **and** D28. (§4.2.5) |

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
                      │                       (push on cmd fire,                   │
                      │                        pop on rsp ⇒ rspOwner)              │
                      └────────────────────────────────────────────────────────────┘
                                                     │
                                                     ▼
                                       DcachePlugin (UNCHANGED — still exactly
                                       one loadCmdPort / storePort, still has
                                       no idea walkers exist)
```

Responses (`loadRsp`, `storeAck`, `storeErr`) stay broadcast on the wire, exactly as today —
no bundle gains a field and no DUT is rewired. Who is allowed to *believe* them is settled
**inside `LsEuPlugin`**: on loads by the ownership FIFO's head (`rspOwner`, W7), on stores by
the latched `stOwner` (W13). See §4.2.3 and §4.3.

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

### 4.2 W4-W11, W23, W24, W26 — arbitration

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
ldOwnerFifo : depth-4 FIFO of the 2-bit owner code       // lives entirely in LsEuPlugin
  push  <= grantedOwner        when dcache.loadCmd.fire
  pop                          when dcache.loadRsp.valid
  rspOwner = ldOwnerFifo.head                            // routes THIS response
```

Depth 4 (one more than the proven maximum of 3) so the FIFO can never be the binding
constraint; `loadCmdPort.ready` is `False` when it is full, which by the bound above cannot
occur. **`DcacheService` is unchanged, `DcachePlugin` is unchanged, and no DUT is rewired.**

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

##### W7 (the surviving drain-to-zero): the walker ↔ `CORE-EXC` boundary

**A walker and the exception sequencer may still never be concurrently outstanding on the
load port.** This is deliberately *not* folded into the FIFO, and it is what preserves W14:

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
excLoadAdmit       = excActive && excLoadCmdValid && (ldOwnerFifo holds no WALKER entry)

stBusy(CORE)       = coreStOutstanding =/= 0    // CORE store fires vs storeAcks
stBusy(ITLB/DTLB)  = walkStOutstanding(i)
```

`excLoadOutstanding` is a new 1-bit register in `LsEuPlugin`, the direct load-side mirror of
the **already existing** `excStoreOutstanding` (`LsEuPlugin.scala:266-270`) — but see **W23**
(§4.2.7): its set term must be owner-qualified, or it is itself a bug. `coreStOutstanding`
likewise mirrors `excStoreOutstanding`, widened to a count.

Walker-side outstanding stays 1 bit each: `TableWalker`'s FSM never issues a second read
before the first responds (`arSent`/`issueRead()`, `:104-120` — the same serialisation,
retargeted), and the U/M drain is single-outstanding by its own `drainAwDone && drainWDone`
gate (`DtlbPlugin.scala:334`).

##### Two caveats this design owes explicitly, and does not gloss

**(a) Core-side response consumers that were previously inert now need owner qualification.**
Under drain-to-zero, `CORE`'s consumers were guaranteed idle whenever a walker response
arrived. Under the FIFO they are **not**, so they must be qualified by `rspOwner === CORE`:

- `alignedRspFire = alignedRspValid && dcache.loadRsp.valid` (`LsEuPlugin.scala:720`) becomes
  `alignedRspValid && dcache.loadRsp.valid && (rspOwner === CORE)`. This is not cosmetic:
  `alignedRspFire` decrements `alignedCount` and advances `alignedRspPtr`, so an
  unqualified version would pop a core load off the aligned queue on the walker's descriptor
  response and hand a page-table word to an architectural register.
- The **split BK FSM**'s `WAIT_A`/`WAIT_B` sampling of `dcache.loadRsp` takes the identical
  qualification, for the identical reason.

These two sites are a **required part of W7**, not an optimisation, and the implementation
plan must list them explicitly in its `LsEuPlugin` change list (§8).

**(b) The latency win is real but it is not "a free slot".** `loadCmdPort.ready` also
requires `(!earlyProbeTokenPresent || earlyProbeOwnsCmd)` (`DcachePlugin.scala:983`), so a
walker command still cannot interleave into a stream with live probe tokens unless W11's
`probeCancelAll` extension is in place. That cost is paid under the original drain-to-zero
W7 **too** — W11 is mandatory either way (§4.2.6) — so the FIFO is strictly better than what
it replaces, just not unconditionally free. W25 (§4.5) closes the second half of the same
question: the walker's token must be a *reserved* value, not a don't-care, or it can match a
resident probe entry by accident.

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
decision, and the switch still obeys W7 (drain to zero first). Concretely, `force` means
"stop admitting new `CORE-LS` commands on this direction and take the port the moment it
drains".

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
   after which `CORE-LS` admission stops and the port drains within a bounded number of
   cycles, after which the walker is granted. Starvation bound: `LIMIT + drain`, finite.
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

#### 4.2.7 W23/W24 — two unconditional `ready` drives that this design turns into bugs

*Both added in the fix pass as CRITICAL findings. They are stated here as DECIDED items with
the exact one-line RTL fix, even though this is a spec and not an implementation, because a
plan-writer working only from the sections above would not encounter either site and both
failures are **silent** — no assertion fires, no hang, no bus error.*

Both are the same shape: a `ready` drive that is correct today **only** because nothing but
its own client can ever own that port, written before a second (and now a fourth) client
existed.

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

**Fix:**

```scala
excLoadCmdReady := dcache.loadCmd.ready && (ldOwner === CORE) && excActive && excLoadCmdValid
```

(substituting whichever name the implementation gives the load-direction owner register if
it is not `ldOwner`). Note the `excActive && excLoadCmdValid` conjunction is part of the fix,
not decoration: within `CORE`, the SQ/LS side can also own the accepted command, so
owner-qualification alone is insufficient — the exception sequencer must additionally be the
one presenting.

**The same bug, second site.** `excLoadOutstanding`'s set term as specified in §4.2.3 —
`dcache.loadCmd.fire && excActive && excLoadCmdValid` — carries the **identical** unqualified
`fire`: `dcache.loadCmd.fire` is `valid && ready`, and under a walker grant that `fire` is
the walker's. Setting `excLoadOutstanding` on a walker's fire corrupts `ldBusy(CORE)`, which
is **W7's own input** — so the bug feeds straight back into the hand-over rule that is
supposed to prevent it. Fix identically:

```scala
when(dcache.loadCmd.fire && (ldOwner === CORE) && excActive && excLoadCmdValid) {
  excLoadOutstanding := True
}
```

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

##### Why both must appear in the plan's `LsEuPlugin` change list

§8's `LsEuPlugin` row did not previously mention either site. It now does, and the plan
**must** carry them as named steps with their own directed checks:

- W23: a directed test in `WalkerDcachePortArbSpec` that presents an exception-sequencer load
  and a walker load in the same cycle and asserts the exception sequencer's FSM does **not**
  advance and `excLoadOutstanding` does **not** set.
- W24: a directed test that drains a non-empty `StoreQueue` while a walker U/M store owns the
  port, and asserts every SQ entry reaches the cache — asserted against a byte-write observer
  on the cache side, not against the SQ's own pointers, since it is precisely the pointers
  that lie.

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
- **Inside `LsEuPlugin`, routing is by `rspOwner`** — W7's FIFO head — for the two `CORE`-side
  consumers that are no longer guaranteed inert. Per §4.2.3 caveat (a), `alignedRspFire`
  (`LsEuPlugin.scala:720`) and the split BK FSM's `WAIT_A`/`WAIT_B` sampling each gain
  `&& (rspOwner === CORE)`. That is two AND terms on already-registered control signals, not
  a demux of the response bundle.
- **The exception sequencer alone keeps the pure temporal-exclusivity argument**, because W7
  keeps drain-to-zero at exactly that boundary. `ExceptionUnit`'s `dcLoadRsp` is only sampled
  in states entered *after* it issued a load (`:1470`, `:1532`, `:1544`, `:1556`, `:1569`,
  `:2224`) — i.e. exactly when `excLoadOutstanding` is 1 — and W7 forbids a walker from being
  outstanding at all in that window. This is the identical argument
  `ExceptionUnit.scala:216-217` already records for the existing 2-source case
  ("`dcLoadRsp` is already wired straight from `DcacheService.loadRsp`"), unweakened.

What is added alongside is a `GenerationFlags.simulation` assertion in `LsEuPlugin`, narrowed
to the boundary that is still drain-to-zero:

```
assert(!(ldOwnerFifoHoldsWalker && (excLoadOutstanding || (excActive && excLoadCmdValid))),
       "a walker held a D-cache load in flight against the exception sequencer", FAILURE)
assert(!(stOwner =/= CORE && (coreStOutstanding =/= 0)),
       "a walker held the D-cache store port with a core store still outstanding", FAILURE)
```

so that if W7's surviving hand-over condition is ever weakened, the netlist says so loudly
rather than silently mis-delivering a descriptor into an architectural register.

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
mux drives each walker's `walkLoadRsp` (W15) already qualified by `rspOwner`, so the walker
sees a `valid` only for its own response. The `rspOwner` qualification lives entirely in
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

- `:716` `alignedSendValid = ... && !bkBusy && !excActive` → `&& !dcLoadHeldByOther`
- `:1774-1776` `normalReqArm`/`splitReqArm`'s `!excActive` → `!dcLoadHeldByOther`
- `sq.io.drain.ready` / `sq.io.drainAck` / `excLoadCmdReady` (W23/W24, §4.2.7) —
  owner-qualification terms on low-fanout control signals, not on any BRAM address.

with `dcLoadHeldByOther = excActive || walkerOwnsLoad` computed **once**, from a **register**.

Also genuinely free, for a structural reason rather than an accounting one:

- **`DcachePlugin.scala` is not edited.** `rdSet`/`rdEn`, `loadCmdPort.ready`,
  `storePort.ready` and the whole S0-S3 / S1-S2 machinery are byte-for-byte unchanged. The
  arbitration is entirely *upstream* of the cache boundary.
- **The owner/`force`/age/FIFO state is registered and never combinationally in a command
  path.** `ldOwner`, `stOwner`, `force(i)`, `walkRr`, the W7 ownership FIFO and its
  `rspOwner` head are all flops. The mux selects payloads from them, exactly as `:2027`'s
  `when(excActive && excLoadCmdValid)` selects from registered `excActive`.
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
| `execute/LsEuPlugin.scala` | Extend the `:2020-2078` mux to 4 sources; add `ldOwner`/`stOwner`/age/`force`/`walkRr`, the **W7 depth-4 ownership FIFO** + `rspOwner`, `excLoadOutstanding`, `coreStOutstanding`; fold `dcLoadHeldByOther` into `:716`, `:863`, `:1774-1776`; qualify `alignedRspFire` (`:720`) and the split BK FSM's `WAIT_A`/`WAIT_B` sampling by `rspOwner === CORE` (§4.2.3 caveat (a)); **owner-qualify `excLoadCmdReady` (`:2115`) and `excLoadOutstanding`'s set term — W23**; **owner-qualify `sq.io.drain.ready` (`:265`) — W24**; gate `sq.io.drainAck`/`drainErr` and `exc.dcStoreAck` (W13); the W26 wedge counters; the walker pass-through hooks; the simulation assertions |
| `exception/ExceptionUnit.scala` | Export the `quiesceHold` bool over `S_DRAIN \|\| S_APPLY` (W19/M1); correct the `:1362-1381` comment |
| `cache/DcacheTypes.scala` | **Declaration-only:** add `DLoadToken.WALK_ITLB = 0x81` / `WALK_DTLB = 0x82` next to the existing width constant (W25). No bundle field, no behavioural change — see the note below |
| `socket/AxiDMerge.scala` | Shrink to 2 read owners / 1 write owner (W21) |
| `socket/AxiDMergePlugin.scala` | Drop the `itlb`/`dtlb` connections and the `socketMerged` requirement on those two plugins |
| `top/FullCoreSynth.scala` | Wire the walker client hooks; remove the `itlbAxi`/`dtlbAxi` top-level ports |
| `cache/AxiIds.scala` | Comment `WALK_READ`/`WALK_WRITE` as unused-but-reserved (W20) |

**`src/main` — deliberately NOT modified:** `cache/DcachePlugin.scala`, `ls/StoreQueue.scala`,
`cache/IcachePlugin.scala`. (`cache/DcacheTypes.scala` receives a *declaration-only* addition
for W25 — two `Int` constants beside the existing `DLoadToken.Width`. No bundle field is
added or changed, so W4's "`DcachePlugin` gains zero new client awareness" and W7's "no
`DcacheService` bundle change" both stand. If a plan prefers to keep `DcacheTypes.scala`
byte-identical, the two constants may instead live in `LsEuPlugin` beside the existing `0x80`
literal; the *disjointness* is the decision, its file is not.)

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

**W18's `DcacheClientMemAgent` is needed by 7 files / 8 DUT classes, not 5.** The original
counted only the MMU-only bucket. Add:

- `cache/IcacheParallelViptSpec` — `walkerAxi.ar`-fire counters at `:111` and `:166`;
- `frontend/FetchAlignResidentCadenceSpec` — `walkerAxi.ar`-fire counter plus an address
  trace at `:139`/`:141`;
- `ls/DtlbMissFlushSpec`'s **first** `Dut` class (`:97`, `DtlbCleanMissSerializationSpec`),
  which has no `DcachePlugin` even though the file's *other* DUT does — hence 8 DUT classes
  across 7 files.

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

- **read side** — `AxiDMerge.scala:222-230` (`progress = out.ar.fire || out.r.fire`);
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
   `R` consumer) as one of its sites (socket design spec `:1145`). This redesign **deletes
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
| `DcacheClientMemAgent` (W18) + reworked `DtlbSpec`, `ItlbSpec`, `UmWriteSpec`, `MmuControlSpec`, `DtlbStreamPipelineSpec`, `IcacheParallelViptSpec`, `FetchAlignResidentCadenceSpec`, `DtlbMissFlushSpec`'s first `Dut` — **7 files / 8 DUT classes**, per §8.1 | Walks and U/M drains still work against a behavioural `DcacheService` client interface |
| New `WalkerDcachePortArbSpec` | (a) the surviving drain-to-zero holds: no walker load is outstanding while the exception sequencer has one, and no store hand-over occurs with a core store outstanding (W7); (a′) the **W7 ownership FIFO** routes correctly with `CORE` and a walker concurrently outstanding, including the ≤3-deep case, and `alignedCount`/`alignedRspPtr` are untouched by a walker response (§4.2.3 caveat (a)); (b) a walker starved for `LIMIT` cycles under continuous SQ drain **does** get the port (W8) — the direct answer to the scoping memo §4's "a busy store queue could postpone a walker indefinitely"; (c) a walker cannot chain-hold against `CORE-LS` (W8.4); (d) ITLB/DTLB alternate (W9); (e) **W23** — a walker `loadCmd.ready` in the same cycle as a presented exception-sequencer load does not advance the exception FSM and does not set `excLoadOutstanding`; (f) **W24** — every `StoreQueue` entry reaches the cache while a walker U/M store owns the store port, asserted against a **cache-side byte-write observer**, never against the SQ's own pointers |
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

### 11.2 Blocking feasibility concerns found: **none**

Full unification is feasible as scoped. Five things that *look* like blockers were run to
ground and are not:

- The untagged-response problem is solved by W7's ownership FIFO (loads) and latched-owner
  demux (stores) without adding a field to any bundle.
- The early-probe deadlock (§4.2.6) is real but is closed by a two-term change to signals
  that already exist for the identical hand-over; W25 closes the token-collision half.
- The `S_DRAIN`/`S_APPLY` quiesce hole (§6.2) is real but is closed by a command-granularity
  hold that is provably terminating.
- The two Critical `ready` leaks (W23/W24, §4.2.7) are real, silent, and closed by one-line
  owner-qualifications each, both following patterns already present in the file.
- The D19 obligation (§9.3 item 3) is real but is discharged by W16, which is strictly
  stronger than the site it replaces.

The genuine *costs* are stated plainly rather than hidden, with the fix-pass corrections:

- **Test-wiring churn: 24 files**, not 28 — but materially *more* expensive than the original
  implied. **7 files / 8 DUT classes** (not 5) need W18's new sim helper, and the
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

Performed against this document before each commit — first for `fc90cf5`, then again after
the review-driven fix pass. Findings were fixed **inline** above; they are recorded here
rather than left as open items. Fix-pass additions are marked **[FP]**.

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

**Internal consistency.** All **26** DECIDED items (W1-W26) cross-checked pairwise for the
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
folds into), `StoreQueue.scala:446,518` (`drainAckFire`, the stray-ack assertion),
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

**Unverified claim, flagged in place:** §4.1.4's remark about real 68040 table-search
cacheability. No primary source is present in this repository; the decision does not rest on
it and the implementation plan carries a non-blocking confirmation step.

---

## 13. Review disposition (fix pass, 2026-08-18)

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

**Status after this pass:** still **PROPOSED / DESIGN ONLY**, awaiting re-review. No RTL has
been touched by this document at any point.
