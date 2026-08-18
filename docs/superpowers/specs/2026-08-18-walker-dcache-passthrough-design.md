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
(D7, D8, D9, D10, D20, D27) and the in-flight
`docs/superpowers/plans/2026-08-18-axi-socket-adapter-implementation-plan.md` (Tasks 4 and
5, both already merged). See §9.

**Numbering note.** Decisions here are numbered **W1-W22** rather than continuing the
socket spec's `D` series, because this spec *reworks* several `D` items and reusing the
same namespace would make "D9" ambiguous between two documents.

---

## 0. Decision summary

| # | Decision |
|---:|---|
| **W1** | Table-walk descriptor accesses (reads **and** the U/M writeback) use a **FIXED architectural cacheMode**, not a derived one: `CACR.DE ? WRITETHROUGH : INHIBITED`. Never COPYBACK. (§4.1) |
| **W2** | That cacheMode is **stamped by the port mux inside `LsEuPlugin`**, not carried on `WalkReq` and not resolved inside the MMU plugins. `WalkReq` gains no `cacheMode` field. (§4.1.4) |
| **W3** | The read and the write halves use the **same** cacheMode expression, from the same source signal. A per-half mode is forbidden. (§4.1.3) |
| **W4** | Arbitration is an **extension of `LsEuPlugin`'s existing override mux** (`LsEuPlugin.scala:2020-2078`) from 2 sources to 4 — *not* a new `Stream`-level arbiter component. `DcachePlugin` gains **zero** new ports and **zero** new client awareness. (§4.2.1) |
| **W5** | Ownership is tracked **independently per direction** (a load owner and a store owner). A single combined token is rejected: it deadlocks against the D9 `refillWriteHold` arc. (§4.2.2) |
| **W6** | Owner encoding is 2 bits over `{CORE, ITLB, DTLB}`. The ordinary LS pipe and the exception sequencer stay **one** owner (`CORE`), because their mutual exclusion is already proven and already implemented at `:2020-2078`. (§4.2.2) |
| **W7** | Grant hand-over requires the outgoing owner to have **zero outstanding accepted commands** on that direction. This is what makes the untagged `DLoadRsp`/`storeAck` unambiguous by construction — no response tag is added. (§4.2.3) |
| **W8** | Base priority is `CORE-EXC > CORE-LS > walkers`, i.e. **today's behaviour exactly** whenever no walker is pending. Fairness comes from an **aging counter** per walker: after `WALKER_AGE_LIMIT` un-granted cycles a walker's `force` bit outranks `CORE-LS` (never `CORE-EXC`'s own presented command). `WALKER_AGE_LIMIT = 64`, constructor-parameterised for directed tests. (§4.2.4) |
| **W9** | The two walkers tie-break against each other with a **1-bit round-robin**, per direction. (§4.2.4) |
| **W10** | **No separate starvation watchdog** (no D20 analogue) is built at this layer. Starvation is structurally bounded by W7 + W8 and the bound is *proved* in §4.2.5, not asserted. A simulation-only assertion pins the bound instead. (§4.2.5) |
| **W11** | Handing the load port to a walker also asserts `LsEuPlugin`'s **existing** `probeCancelAll` (`LsEuPlugin.scala:863`) and suppresses new probe launches, by folding the owner bit into the **same** `!excActive` conjunctions at `:863`, `:716` and `:1774-1776`. Without this the design **deadlocks** — see §4.2.6. This is a correctness requirement, not an optimisation. |
| **W12** | FMax mitigation is *structural*: every new term is folded into an **already-existing AND-conjunction that already contains `excActive`**, and the new owner state is a **register**. Net new logic levels on the high-fanout `rdSet`/`rdEn` BRAM read-address net: **zero**. (§5) |
| **W13** | The walkers' `storeAck`/`storeErr` are **demultiplexed by the latched store owner**, and the existing `sq.io.drainAck := dcache.storeAck && !excStoreOutstanding` (`LsEuPlugin.scala:270`) gains the walker term. This is mandatory: `StoreQueue.scala:518` already asserts on a stray ack. (§4.3) |
| **W14** | The load-response side gets **no** demux — it relies on the same temporal-exclusivity argument the exception mux already relies on, made checkable by a new simulation-only assertion. (§4.3) |
| **W15** | The walkers' client ports are plugin-level `var` hooks with default-idle `allowOverride` drives, wired by the **top-level/DUT wiring**, exactly mirroring the existing `umCommitValid`/`umFlush`/`excLoadCmdValid` idiom. No new `Plugin` service lookup, no new `FiberPlugin`. (§4.4) |
| **W16** | `TableWalker.selectWord` is **deleted**; the walker consumes `loadRsp.payload.data` directly. This removes a hand-rolled duplicate of `DcacheByteLane.extract`'s LONG case. (§4.5) |
| **W17** | The U/M writeback is emitted as `DStoreCmd` with `useStrb = True`, `strb = drainStrb`, `lineData = drainBeat`, `paddr = drainAddrReg`, `size = LONG` (don't-care under `useStrb`), `precise = False`. The whole `walkerAxi` AW/W/B drain FSM is deleted. (§4.6) |
| **W18** | A **new sim helper `DcacheClientMemAgent`** (a `Stream`/`Flow` analogue of `BehavioralMemAgent`) is built so the 5 MMU-only DUTs keep working without a real `DcachePlugin`. (§10.2) |
| **W19** | The exception sequencer's `S_DRAIN` quiesce is protected by a new `quiesceHold` gate that closes **walker** admission only (never `CORE`), wired from `ExceptionUnit`'s `S_DRAIN` state. Without it `ExceptionUnit.scala:1362-1381`'s written deadlock analysis ("With the LS EU flushed, nothing re-arms them") becomes false. (§6.2) |
| **W20** | Task 4's committed code (`38e6c31`, `56f2438`) is **not reverted**; the walker-side half is deleted along with its host (`walkerAxi` ceases to exist), the `RESET_VEC` ARID half survives untouched, and `WalkerIdGuardSpec` is deleted. `AxiIds.WALK_READ`/`WALK_WRITE` stay defined-but-unused (renumbering is forbidden). (§9.1) |
| **W21** | Task 5's `AxiDMerge` is **shrunk, not deleted and not repurposed**: read side 4 owners → 2 (`DCACHE`, `RESETVEC`), write side 3 owners → 1 (pass-through). The D20 watchdog stays on the read side. Its *code* is not reused for this design's mux; its *bounded-progress discipline* is (as W8's aging counter). (§9.2) |
| **W22** | This is a **separate plan** with its own file. A single **superseding Task 5R** is inserted into the axi-socket-adapter plan, and D7/D8/D9/D20/D27 get an addendum block in that spec. Tasks 6, 7, 8, 9, 10, 11, 12, 13, 14 are unaffected in interface shape. (§9.3) |

| # | Recorded non-decision |
|---:|---|
| **N1** | The descriptor-update **read-modify-write is still not atomic**. The walker computes `newByte` from a descriptor value it read earlier and stores it later, at commit. A real 68040 uses a locked RMW bus cycle. This race exists today and is neither fixed nor worsened here. Out of scope. (§7) |
| **N2** | Walker descriptor reads now **allocate L1D lines** (a WRITETHROUGH load miss allocates; `doAllocate` excludes only INHIBITED). Page-table lines therefore compete for D-cache capacity. Accepted; it is also what makes repeat walks cheap. (§4.1.2) |
| **N3** | The pre-existing `umQueueFull` → walk-launch-blocked → ROB-can't-commit → queue-never-drains cycle is **unchanged** by this work. It is a real (if unobserved) latent hazard that predates this design. (§6.4) |
| **N4** | A walker load is refused for the entire duration of a cache-maintenance walk (`loadCmdPort.ready` is gated on `!maintBusyReg`, `DcachePlugin.scala:981`). This is a new **stall** coupling (bounded by `sets*ways` = 512 iterations), not a deadlock — proof in §6.3. |

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
  DtlbPlugin.walker ──┤  P3 DTLB      ──┘     stOwner +                            │
                      │                       aging)                               │
                      └────────────────────────────────────────────────────────────┘
                                                     │
                                                     ▼
                                       DcachePlugin (UNCHANGED — still exactly
                                       one loadCmdPort / storePort, still has
                                       no idea walkers exist)
```

Responses (`loadRsp`, `storeAck`, `storeErr`) stay broadcast, exactly as today. Who is
allowed to *believe* them is settled by the latched owner (§4.3).

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
`host.get[CacheControlService]` (`:219`) and already computes this exact expression twice
(`:1738`, `:2061`). The MMU plugins gain no new service dependency and `WalkReq` gains no
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

### 4.2 W4-W11 — arbitration

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

The extension of `LsEuPlugin.scala:2020-2078` gets the same result with, in the limit,
two 2-bit registers, two small counters, and terms folded into conjunctions that already
exist. And it is not a novel pattern: that block *is* this codebase's established answer to
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

**Why independent per direction (W5).** A single combined token deadlocks, for a reason that
is already written down: socket spec **D9** cites `DcachePlugin.scala:818` /`:1258`'s
`refillWriteHold` arc — a load refill defers accepting its R beat until a colliding same-set
store drain's S1/S2 window closes. Under one token, a walker holding it for its three
descriptor reads would block the SQ drain, while its own refill waits on store-side
progress. Two independent owners make the dependency graph acyclic exactly as D9's argument
does one layer down. The two `DcacheService` ports are physically distinct with independent
readiness (`DcachePlugin.scala:83`, `:88`, `:981`, `:1865`), so this costs nothing structural.

It is also the *natural* shape: a walker's read phase and its U/M write phase are separated
by an entire ROB commit — the reads happen at walk time, the write drains at the triggering
instruction's retirement (`DtlbPlugin.scala:300-312`). Serialising them behind one token
would block LS loads on a store that is not going to be issued for hundreds of cycles.

**Why CORE is one owner (W6).** The ordinary LS pipe and the exception sequencer are already
temporally exclusive by construction and already muxed at `:2020-2078`, gated on the
exception's *per-port* valids so the SQ drain keeps the port on cycles the exception unit
does not want it. Splitting them into two arbiter owners would duplicate that reasoning for
zero benefit. `CORE` therefore means "whatever `:2020-2078` decides", unchanged.

#### 4.2.3 W7 — hand-over requires zero outstanding, and that is the whole response story

`DLoadRsp` has no token field (`DcacheTypes.scala:67-71`) and `storeAck` is a bare ordered
`Bool` (`:123`). Rather than add a tag, the grant makes ambiguity impossible: **the owner may
only change when the outgoing owner has no accepted command still owed a response on that
direction.**

This is a strictly stronger requirement than the scoping memo's framing, and it is the one
place this spec materially **corrects** it. The memo reasoned from "the walker is
single-outstanding" and implicitly treated the LS pipe as similar. It is not:

- **Loads.** `LsEuPlugin.scala:703-720` maintains a **4-deep** in-flight aligned-load queue
  (`alignedDepth = 4`, `alignedSendPtr`/`alignedRspPtr`), and `DcachePlugin` itself accepts a
  second command behind a miss (`loadShadowCmd`, `:981`, `:996-1002`). Up to four load
  responses can be owed at once, matched **positionally**. A per-command grant would be
  ambiguous immediately.
- **Stores.** `storeOutstanding` is a 3-bit count with capacity S0+S1+S2+S3 = 4
  (`DcachePlugin.scala:619`, asserted `<= 4` at `:2086`).

So the outstanding predicates are:

```
ldBusy(CORE)   = (alignedCount =/= 0) || bkBusy || excLoadOutstanding
ldBusy(ITLB)   = itlbLdOutstanding          // walker-issued read not yet responded
ldBusy(DTLB)   = dtlbLdOutstanding
stBusy(CORE)   = coreStOutstanding =/= 0    // counts CORE-issued store fires vs storeAcks
stBusy(ITLB/DTLB) = walkStOutstanding(i)
```

`excLoadOutstanding` is a new 1-bit register in `LsEuPlugin`, set on
`dcache.loadCmd.fire && excActive && excLoadCmdValid` and cleared on `dcache.loadRsp.valid`
— the direct load-side mirror of the **already existing** `excStoreOutstanding`
(`LsEuPlugin.scala:266-270`). `coreStOutstanding` likewise mirrors it, widened to a count.

Walker-side outstanding is trivially 1 bit each: `TableWalker`'s FSM never issues a second
read before the first responds (`arSent`/`issueRead()`, `:104-120` — the same serialisation,
retargeted), and the U/M drain is single-outstanding by its own `drainAwDone && drainWDone`
gate (`DtlbPlugin.scala:334`). Additionally, a WRITETHROUGH store is classified *serial* by
`DcachePlugin.scala:631-632` (`inputStoreSerial` is true for any non-COPYBACK cacheMode),
so a walker U/M store is already forced to be the sole accepted descriptor by
`storePort.ready`'s `!serialStoreInFlight && (storeOutstanding === 0)` term (`:1865-1867`).
The walker store side is single-outstanding **twice over**.

Responses are therefore unambiguous by construction, with **no new field on any bundle**.
This is the same call `AxiIds.scala:21-30` already made and documented one layer down
(declining V2a.2/V2a.3 ID-tagged routing "for zero measurable IPC on today's fabric").

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
miss latency of the walk it unblocks (worst case it adds `3 * 64 = 192` cycles to a walk,
against a walk that is already hundreds of cycles when it misses). Parameterised on the
plugin constructor **only** so directed tests can use a value like 4 — production is the
constant. This mirrors `AxiDMerge`'s `grantTimeout` parameterisation rationale verbatim
(`AxiDMerge.scala:88-96`).

W9: a 1-bit `walkRr` per direction, flipped on each walker grant, breaks ITLB-vs-DTLB ties.
With two requesters, round-robin and "the one that didn't go last" are the same thing, so
this is one flop, not a rotation base.

#### 4.2.5 W10 — why no watchdog, proved

The socket spec's **D20** built a bounded-grant watchdog because merging four AXI owners
created a new wedge mode: *an owner that never completes starves the others*. That mode
does not exist here, and the difference is structural, not optimistic:

1. **Every grant is bounded.** A walker's grant covers exactly one descriptor read or one
   U/M store. A `CORE` grant is bounded by W7's drain-to-zero, which is at most 4 loads or
   4 store descriptors, each of which completes in bounded time (an L1D hit in 2 cycles; a
   miss in one AXI round trip, whose boundedness is the fabric's obligation per socket spec
   **D19**, not this arbiter's).
2. **Requests are never withdrawn-and-reasserted in a way that resets the age.** `age(i)`
   resets only on *grant* or on the request genuinely going away.
3. **Therefore** any pending walker request reaches `age = LIMIT` within `LIMIT` cycles,
   after which `CORE-LS` admission stops and the port drains within a bounded number of
   cycles, after which the walker is granted. Starvation bound: `LIMIT + drain`, finite.
4. **The reverse direction is bounded too.** A forced walker takes exactly one command's
   worth of port, then `age(i)` resets and base priority restores `CORE-LS` above it. A
   walker cannot chain-hold: its next request is a *new* request that starts ageing from 0.

A watchdog would fire only on a wedge *inside* `DcachePlugin` or the fabric — cases already
owned by D19/D20 one layer down, where a duplicate timer would be the third repetition of
the mistake `AxiDMerge.scala:9-21` documents. Adding one here would be reflex, not design.

What is built instead: a `GenerationFlags.simulation` assertion in `LsEuPlugin` that no
walker request stays un-granted for more than `LIMIT + K` cycles (`K` sized from the drain
bound), in the style of `DcachePlugin.scala:2073-2087`'s existing assertion block. That pins
the proof to the netlist without spending a flop in production.

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

**Load side (W14) — no demux, by argument.** The core's load-response consumers are
*already* inert whenever it does not own the port:

- `alignedRspValid` requires `alignedSent(alignedRspPtr)` (`LsEuPlugin.scala:718-719`) —
  false when `alignedCount = 0`, which W7 requires before hand-over;
- the split BK FSM only samples `dcache.loadRsp` in `WAIT_A`/`WAIT_B`, which require
  `bkBusy`, likewise required false;
- `ExceptionUnit`'s `dcLoadRsp` is only sampled in states entered *after* it issued a load
  (`:1470`, `:1532`, `:1544`, `:1556`, `:1569`, `:2224`) — i.e. exactly when
  `excLoadOutstanding` is 1, likewise required false.

This is the identical temporal-exclusivity argument `ExceptionUnit.scala:216-217` already
records for the existing 2-source case ("`dcLoadRsp` is already wired straight from
`DcacheService.loadRsp`"). Adding a load-side demux would require touching
`FullCoreSynth.scala:350-351` **and every DUT that replicates it** for zero behavioural gain.

What is added instead is a `GenerationFlags.simulation` assertion in `LsEuPlugin`:

```
assert(!(ldOwner =/= CORE && (alignedCount =/= 0 || bkBusy || excLoadOutstanding)),
       "walker held the D-cache load port with a core load still outstanding", FAILURE)
```

so that if W7's hand-over condition is ever weakened, the netlist says so loudly rather than
silently mis-delivering a descriptor into an architectural register. Symmetrically for the
store side.

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
walkLoadCmd.payload.paddr := descAddr        // NOT line-aligned: the byte-lane needs paddr[3:0]
walkLoadCmd.payload.vaddr := descAddr        // identity-physical, per LsEuPlugin.scala:2029-2035
walkLoadCmd.payload.size  := Size.LONG
walkLoadCmd.payload.cacheMode := <don't care; the mux stamps it, W2>
walkLoadCmd.payload.token := <don't care; walkers never probe>
```

and each `RD_*` state's `when(io.axi.r.fire)` becomes `when(walkLoadRsp.valid)` with
`val d = walkLoadRsp.payload.data`.

**`selectWord` is deleted (W16).** `DcacheByteLane.extract(line, paddr[3:0], LONG)`
(`DcacheTypes.scala:186-190`) computes bit-for-bit what `selectWord` (`TableWalker.scala:86-94`)
computes, and `DcachePlugin.scala:511-513` already applies it to build `loadRsp.payload.data`.
Keeping a second copy of the task-#194 big-endian convention in the walker, now that the
walker is downstream of the first copy, would be a live drift hazard. Note the address passed
in `paddr` must be the **descriptor byte address**, not the line-aligned one the old AXI AR
used (`TableWalker.scala:107`), because the offset is what selects the lane.

`loadRsp.payload.fault` (a physical AXI refill error, `DcacheTypes.scala:63-71`) becomes
reachable for a walk for the first time. Today a walker read's AXI error is silently ignored
(`TableWalker` never inspects `r.resp`). Minimum viable handling, matching the existing
level of rigour and no more: treat it as `MmuFaultReason.NON_RESIDENT` and terminate the walk
via the existing `FINISH` path. Recorded as a small, genuine improvement, not new scope.

### 4.6 W17 — the U/M writeback after the change

`DtlbPlugin.scala:314-358` / `ItlbPlugin.scala:249-289` keep `drainByteOff`, `drainBeat`,
`drainStrb`, `drainAddrReg`, `drainBeatReg`, `drainStrbReg` **verbatim** — that construction
is already exactly `DStoreCmd`'s `useStrb` form (scoping memo §3). What is deleted is the
AW/W/B sequencing (`drainAwDone`/`drainWDone` and their three `when` blocks) and
`walkerAxi`. What replaces it:

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
- The single-entry elasticity (`drainArmed` set when `umq.io.drain.valid` and cleared on
  `walkStore.fire`) preserves the queue's existing "hold the entry until `drainAck`"
  contract (`DtlbPlugin.scala:334-340`), and matches `DcacheService.store`'s stated
  "elastic ordered drain; payload stable until fire" (`DcacheTypes.scala:122`).

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

**The mitigation is structural, and it is why W4 chose the `LsEuPlugin` mux.**

1. **`DcachePlugin.scala` is not edited.** `rdSet`/`rdEn`, `loadCmdPort.ready`,
   `storePort.ready` and the whole S0-S3 / S1-S2 machinery are byte-for-byte unchanged. The
   arbitration is entirely *upstream* of the cache boundary, ending at the same
   `dcache.loadCmd`/`dcache.store` drives that exist today.
2. **Every new term is folded into an existing conjunction that already contains
   `excActive`.** Specifically:
   - `:863` `probeCancelAll = sqFlushSig || excActive` → `|| walkerOwnsLoad`
   - `:716` `alignedSendValid = ... && !bkBusy && !excActive` → `&& !dcLoadHeldByOther`
   - `:1774-1776` `normalReqArm`/`splitReqArm`'s `!excActive` → `!dcLoadHeldByOther`
   with `dcLoadHeldByOther = excActive || walkerOwnsLoad` computed **once**, from a
   **register**. Substituting one register-sourced signal for another inside an existing
   AND-tree adds **zero logic levels**; it adds one 2-input OR *outside* the timing-critical
   path, feeding a signal that already fans out to those sites.
3. **The owner/`force`/age state is registered and never combinationally in a command path.**
   `ldOwner`, `stOwner`, `force(i)`, `walkRr` are all flops. The mux selects payloads from
   them, exactly as `:2027`'s `when(excActive && excLoadCmdValid)` selects from registered
   `excActive`.
4. **The walker's command payloads are registered at the source.** `descAddr`
   (`TableWalker.scala:54`) and `drainAddrReg`/`drainBeatReg`/`drainStrbReg`
   (`DtlbPlugin.scala:331-333`) are already `Reg`s. So the new mux input is flop → mux →
   the same flop-fed boundary the exception input uses. This preserves the "both addresses
   are therefore registered at this boundary and preserve FMax #1" property
   `LsEuPlugin.scala:746-749` states for the existing two load sources.
5. **The `probeCancelAll` route was chosen over gating `loadProbe.valid` directly** precisely
   because `loadProbePort.fire` drives `rdSet`/`rdEn` (`DcachePlugin.scala:950-956`) while
   the cancel path feeds only `earlyProbeValids` control registers (`:1976-1982`). Both
   would have worked functionally; only one stays off the protected net. (In fact W11 does
   both — but the *probe-suppression* half is folded into `normalReqArm`, which is already
   several levels upstream of `loadProbe.valid`, so it too adds no level at the boundary.)

**Mandatory synth gate** (`synth-gate-every-slice.md`, standing rule; current goal ≥200 MHz,
current measured 197.278 MHz per the task #218/#219 ledger — i.e. this change starts from a
netlist that is *already* 0.069 ns short of the goal, so there is no headroom to spend):

- Full-core OOC synth **before** the first RTL commit of the implementation, on an
  uncontended machine (`machine-resource-budget.md`: never during another heavy JVM;
  `ported-tests-triage-2026-07-17.md`: FMax measurement is unreliable under contention,
  always re-verify uncontended). This is the baseline, recorded in the plan.
- Full-core OOC synth **after** the mux change and again after the walker/MMU changes, as
  two separate checkpoints, so a regression is attributable.
- The gate **measures**: post-route WNS and Fmax, plus — because the specific worry is
  named — whether any of the top-10 failing paths newly traverses `rdSet`, `rdEn`,
  `loadCmdPort_ready`, `storePort_ready`, `loadProbePort_valid`, or the LS EU's aligned-queue
  pointers. A path list that is *shape-identical* to the baseline is the pass criterion, not
  merely a number that happens to hold.
- If FMax regresses, the first lever is moving the age/force comparison off the grant
  decision path (precompute `force(i)` a cycle early — it is a counter comparison against a
  constant and has a full cycle of slack by construction), **not** reverting the design.

---

## 6. Liveness

### 6.1 No circular translation dependency

Confirmed by the scoping memo §7 and re-confirmed here: `DcachePlugin.scala` contains no
`TranslationService`/`DTranslationService`/`MmuControlService` reference and never invokes
the MMU. It operates purely on pre-translated `paddr` supplied by its caller
(`DcacheTypes.scala:47-54`). Routing walker traffic *into* it therefore cannot create a
translate-inside-the-cache cycle: the walker supplies physical addresses it computed from
`rootPtr` + VA slices, with no translation involved.

### 6.2 W19 — the `S_DRAIN` quiesce hole (a real new hazard)

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

**W19:** `LsEuPlugin`'s mux takes a new `quiesceHold` input, wired from `ExceptionUnit`'s
`S_DRAIN` state (a new `Bool` on `ExceptionUnit` in the shape of the existing
`maintDoneIn`/`dcQuiesced` hooks, `:161-166`, `:390`, so a standalone DUT still elaborates).
While `quiesceHold` is high the mux **admits no new walker command** on either direction;
`CORE` is entirely unaffected.

Why this is safe and terminating:

- The hold is at **command** granularity, not walk granularity. A walker mid-walk simply
  stalls between descriptor reads. It does not need to complete the walk for the cache to
  quiesce — it needs only to have no command *in flight*, which is at most one AXI round
  trip away.
- Nothing the exception sequencer needs in `S_DRAIN` depends on a walk finishing. The ITLB
  walk gates instruction fetch, which is irrelevant while the frontend is squashed; the DTLB
  walk gates an LS EU translation, and the LS EU is flushed.
- `quiesceHold` is asserted **only in `S_DRAIN`**, not for all of `excActive`. That is the
  distinction that keeps §4.2.4's requirement intact: the exception sequencer's own
  FSAVE/FRESTORE DTLB misses happen in the *later* frame-access states, where `quiesceHold`
  is low and a walk can run.
- The hold is therefore bounded by `S_DRAIN`'s own bounded exit, which the existing analysis
  already establishes for the `CORE`-only terms.

**Verification obligation:** the implementation plan must update
`ExceptionUnit.scala:1362-1381`'s comment, because leaving a now-false deadlock proof in the
source is exactly the drift this project's review history keeps catching.

### 6.3 N4 — the maintenance-walk stall (bounded, not a deadlock)

Once `maintBusyReg` is set, `loadCmdPort.ready` is false for the whole maintenance walk
(`DcachePlugin.scala:981`). A walker load presented then simply waits. This is a **new stall
coupling** — today the walker has its own AXI master and is immune — bounded by the walk's
own `sets*ways = 128*4 = 512` iterations plus its writeback beats.

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
| `execute/LsEuPlugin.scala` | Extend the `:2020-2078` mux to 4 sources; add `ldOwner`/`stOwner`/age/`force`/`walkRr`, `excLoadOutstanding`, `coreStOutstanding`; fold `dcLoadHeldByOther` into `:716`, `:863`, `:1774-1776`; gate `sq.io.drainAck`/`drainErr` and `exc.dcStoreAck` (W13); the walker pass-through hooks; the simulation assertions |
| `exception/ExceptionUnit.scala` | Export the `S_DRAIN` `quiesceHold` bool (W19); correct the `:1362-1381` comment |
| `socket/AxiDMerge.scala` | Shrink to 2 read owners / 1 write owner (W21) |
| `socket/AxiDMergePlugin.scala` | Drop the `itlb`/`dtlb` connections and the `socketMerged` requirement on those two plugins |
| `top/FullCoreSynth.scala` | Wire the walker client hooks; remove the `itlbAxi`/`dtlbAxi` top-level ports |
| `cache/AxiIds.scala` | Comment `WALK_READ`/`WALK_WRITE` as unused-but-reserved (W20) |

**`src/main` — deliberately NOT modified:** `cache/DcachePlugin.scala`,
`cache/DcacheTypes.scala`, `ls/StoreQueue.scala`, `cache/IcachePlugin.scala`.

**`src/test` — 28 files reference `walkerAxi`/`itlbAxi`/`dtlbAxi`.** Two classes:

- **23 full-core / lock-step / fuzz DUTs** that attach a `BehavioralMemAgent` to the walker
  AXI, usually with `sharedMem = <the same memory the D-cache uses>` (e.g.
  `FsaveFrestoreSpec.scala:78-79`, `ExceptionStoreDrainArbSpec.scala:41-42`,
  `IpcBenchSpec.scala:593-594`). These lines are **deleted**: page-table memory now reaches
  the walker through the D-cache's own AXI and its existing agent. Where a spec attached a
  *separate, non-shared* memory to the walker (e.g. `EoriAddaDecodeTraceSpec.scala:68-69`,
  which passes no `sharedMem`), deletion is a behaviour change the plan must check per-site —
  those DUTs were relying on an all-zero page-table memory that is now the shared one.
- **5 MMU-only DUTs** with no `DcachePlugin` at all — `mmu/DtlbSpec`, `mmu/ItlbSpec`,
  `mmu/UmWriteSpec`, `mmu/MmuControlSpec`, `mmu/DtlbStreamPipelineSpec`. These need W18's
  `DcacheClientMemAgent`, plus rewrites of the assertions that count `walkerAxi.ar` fires
  (`DtlbSpec.scala:135,177,230`, `UmWriteSpec.scala:182,220,290,351`,
  `DtlbStreamPipelineSpec.scala:118`) to count `walkLoadCmd` fires instead.
- **`socket/WalkerIdGuardSpec`** is deleted outright (W20).

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
comment recording why, because the plan's own Global Constraint #3 forbids renumbering them
(spec §4.3) and removing them would shift nothing but would invite exactly that.

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
  reused — W8's aging counter is D20's "progress resets the count" shape, and W5's
  per-direction independence is D9's argument — but as ~15 lines inside an existing mux,
  not as a component.
- *Delete `AxiDMerge` entirely.* Rejected: `DCACHE` vs `RESETVEC` is a real 2-owner read
  merge (D13 deliberately routes the vector-0 fetch through `axi_d` rather than adding a
  third socket master), and D20's watchdog still covers it.

The read-side D20 watchdog stays. The write side, having one owner, needs no watchdog and
no grant state; it becomes a wire.

Downstream: `ResetVectorPlugin` (Task 9) rides flat `rv*` ports (`AxiDMerge.scala:110-127`)
and is **unaffected in interface shape** — confirmed. `M68kSocketTop` (Task 13) consumes the
single merged `axi_d` and never references `itlbAxi`/`dtlbAxi` — **unaffected**. Tasks 6, 7,
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

Two edits are required *in* the socket plan and spec, and they are not optional:

1. **A superseding `Task 5R`** inserted into
   `docs/superpowers/plans/2026-08-18-axi-socket-adapter-implementation-plan.md` after Task 5,
   stating that Task 5's merged output no longer matches its own spec sections and carrying
   the W21 shrink. Task 5 is **already merged**; leaving the plan claiming a 4-owner arbiter
   while the netlist has 2 is precisely the kind of stale-plan drift this project's process
   exists to prevent.
2. **An addendum block** in `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md`
   (in the style of its own two existing "Added by the ... pass" blocks) recording that D7's
   forcing constraint is dissolved, D8's ITLB-vs-DTLB disambiguation argument is moot, D9's
   write-side half is vacuous, D10 is unchanged, D20's read-side watchdog survives and its
   write-side does not, and D27's walker sites are removed with the principle relocated per
   §9.1. D19 is unaffected.

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
| `DcacheClientMemAgent` (W18) + reworked `DtlbSpec`, `ItlbSpec`, `UmWriteSpec`, `MmuControlSpec`, `DtlbStreamPipelineSpec` | Walks and U/M drains still work against a behavioural `DcacheService` client interface |
| New `WalkerDcachePortArbSpec` | (a) hand-over never occurs with a core load/store outstanding (W7); (b) a walker starved for `LIMIT` cycles under continuous SQ drain **does** get the port (W8) — the direct answer to the scoping memo §4's "a busy store queue could postpone a walker indefinitely"; (c) a walker cannot chain-hold against `CORE-LS` (W8.4); (d) ITLB/DTLB alternate (W9) |
| New `WalkerProbeDeadlockSpec` | The §4.2.6 deadlock: fill all 4 probe slots, then demand an ITLB walk, and require forward progress. Must be shown to **hang** without W11's `probeCancelAll` term |
| New `WalkerDescriptorCoherencySpec` | The §10.1 read-side test above, plus its write-side mirror (dirty resident descriptor line + U/M writeback ⇒ no lost update after a forced eviction) |
| New `WalkerCacheModeSpec` | `CACR.DE = 1` ⇒ walker commands carry WRITETHROUGH; `DE = 0` ⇒ INHIBITED; read and write halves always agree (W1/W3) |
| Reworked `AxiDMergeSpec` | 2 read owners, write pass-through, watchdog still fires on the read side (W21) |
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
   accepts a shadow command behind a miss. This is what forces W7's drain-to-zero rule rather
   than a simple per-command grant. **Most consequential correction in this document.**
2. **The early-probe deadlock (§4.2.6) is entirely new.** The memo does not mention
   `earlyProbeTokenPresent`'s gating of `loadCmdPort.ready`. A design that follows the memo's
   §4 recommendations faithfully and stops there **deadlocks**.
3. **The `S_DRAIN` quiesce hole (§6.2, W19) is new.** `ExceptionUnit.scala:1362-1381`'s
   written deadlock proof contains a clause this change falsifies. The memo's §7
   deadlock analysis correctly clears the *circular translation* question but does not reach
   this one.
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
8. **The memo's D20-analogue suggestion is declined with proof** (W10, §4.2.5) rather than
   adopted reflexively.

### 11.2 Blocking feasibility concerns found: **none**

Full unification is feasible as scoped. Three things that *look* like blockers were run to
ground and are not:

- The untagged-response problem is solved by W7's grant discipline without adding a tag.
- The early-probe deadlock (§4.2.6) is real but is closed by a two-term change to signals
  that already exist for the identical hand-over.
- The `S_DRAIN` quiesce hole (§6.2) is real but is closed by a command-granularity hold that
  is provably terminating.

The two genuine *costs* are stated plainly rather than hidden: a 28-file test-wiring churn
(§8) including 5 MMU-only DUTs that need a new sim helper, and an FMax risk on a netlist with
0.069 ns of headroom (§5) — mitigated structurally, gated three times, with a named first
lever if it regresses.

---

## 12. Self-review

Performed against this document before commit. Findings were fixed **inline** above; they
are recorded here rather than left as open items.

**Placeholder scan.** No `???`, `TBD`, `TODO`, `XXX`, `<fill in>` or unresolved bracket
survives. The three `<don't care ...>` markers in §4.5/§4.6 are deliberate specification
prose (they say *which* field is a don't-care and *why*), not placeholders; each is paired
with a concrete assigned value where one is needed (`Size.LONG`, `precise = False`).

**Internal consistency.**
- W1's expression appears once (§4.1.4) and is referenced, not restated, in §4.6 and §9.2.
  Checked that §9.2's claim ("walker traffic never engages the INHIBITED sequencer unless
  `CACR.DE = 0`") matches W1 exactly — it does.
- W5 (independent per-direction) is used consistently: §4.2.2 states it, §4.2.3 gives
  per-direction predicates, §6.5 relies on it, §4.2.4's aging is per-direction. No section
  assumes a single token.
- W7's drain-to-zero is the sole basis for W14's no-demux argument. Checked that W14 does not
  quietly need something stronger — it does not; it needs exactly "the core has nothing
  outstanding", which is W7's definition.
- W11 and §5.5 initially disagreed on whether probe *suppression* or probe *cancellation* was
  the mechanism. **Fixed:** §5 item 5 now states explicitly that W11 does both, and why each
  half stays off the protected net.
- §8's "`DcachePlugin.scala` deliberately NOT modified" is consistent with W4, W12 and §5
  item 1. Cross-checked every other section for an implied `DcachePlugin` edit; none found.
  (§4.2.3 reads `storeOutstanding` — but only via signals `LsEuPlugin` can reconstruct from
  `dcache.store.fire`/`dcache.storeAck`, which is why W7 defines `coreStOutstanding` as a
  *new `LsEuPlugin` counter* rather than "read `DcachePlugin`'s". Corrected during review; the
  earlier draft implied reading the cache's internal register.)

**Ambiguity check.**
- "The walker" was ambiguous between `TableWalker` (the component, one per TLB) and "a walker
  client" (an arbiter owner). Normalised: `TableWalker` for the component, "walker"/"ITLB
  walker"/"DTLB walker" for the owner.
- `WALKER_AGE_LIMIT`'s units (core clocks) and its reset condition (grant **or** request
  withdrawal) were unstated in the first draft. **Fixed** in §4.2.4; the reset-on-withdrawal
  clause matters for §4.2.5's step 2.
- §4.5's `paddr` was originally written as the line-aligned address, copying
  `TableWalker.scala:107`. That would have selected lane 0 of every line. **Fixed:** it must
  be the descriptor *byte* address, and §4.5 now says so with the reason.
- "Task 4 is deleted" was ambiguous between the commits and the code. **Fixed** in §9.1:
  commits are not reverted, code is removed with its host.

**Claims re-verified against the RTL while reviewing** (not merely against the memo):
`DcacheTypes.scala:67-71` (no token on `DLoadRsp`), `DcachePlugin.scala:981` (the probe term
in `loadCmdPort.ready`), `:1927-1935` (dirty is set, never cleared, on an S3 hit write),
`:631-632` + `:1865-1867` (WRITETHROUGH is serial), `:1602-1608` (`dcIdleForMaint`'s terms),
`:2073-2087` (the existing assertion-block style), `LsEuPlugin.scala:263-271` (the SQ
ack/ready drives), `:716` / `:863` / `:1774-1776` (the three `excActive` conjunctions W12
folds into), `StoreQueue.scala:446,518` (`drainAckFire`, the stray-ack assertion),
`ExceptionUnit.scala:1362-1381` (the `S_DRAIN` deadlock comment), `AxiDMerge.scala:131-232`
(the read-side grant shape), `AxiDMergePlugin.scala:36-52` (the `socketMerged` requirement
and walker connections).

**Unverified claim, flagged in place:** §4.1.4's remark about real 68040 table-search
cacheability. No primary source is present in this repository; the decision does not rest on
it and the implementation plan carries a non-blocking confirmation step.
