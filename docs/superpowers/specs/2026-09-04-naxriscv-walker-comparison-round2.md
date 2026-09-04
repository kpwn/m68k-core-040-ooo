# NaxRiscv walker→D-cache comparison, round 2 — the questions round 1 did not ask

**Status:** RESEARCH ONLY. No RTL touched, in this repo or in the reference tree.
**Date:** 2026-09-04
**Branch:** `fmax-closure-fanout`
**Reference tree:** `/home/qwertyoruiop/m68k-ooo-v2/thirdparty/NaxRiscv` @ `9f452d5` — **read-only, third-party, under another project.**
**Companion to:** `docs/superpowers/specs/2026-09-03-walker-dcache-routing-revalidation-and-itlb-prefetch-design.md`
**Extends and partially corrects:** `.superpowers/sdd/naxriscv-walker-dcache-comparison-2026-08-19-report.md`

---

## Why a second study

The 2026-08-19 report is accurate. I re-read every NaxRiscv file it cites, independently, and
confirmed every claim and every line number in it. Nothing below overturns its verdict on **W4**
(arbitrate one layer above a client-unaware cache; add no tag to the cache's own response bundle) —
that verdict is correct and NaxRiscv's RTL does support it.

But round 1 asked four questions, all of them about the **load** path, and read three files
(`MmuPlugin.scala`, `DataCache.scala`'s load sections, `DataCachePlugin.scala`). It never opened
`FetchCachePlugin.scala`, never opened either LSU, and never asked what NaxRiscv does about
**A/D bits**. Those omissions matter, because:

- Our walker is **read *and* write** (W17 makes it a second store client). NaxRiscv's is read-only.
  Round 1's "NaxRiscv validates this design" generalizes silently from one to the other.
- Our design creates an **I-side cross-path** it describes as a non-event. NaxRiscv's I-side
  arrangement is materially different from ours and worth knowing before we commit.
- Round 1 read `DataLoadCmd.redoOnDataHazard` as *corroboration* of the shared-port pattern
  (its §5.2). It is in fact a **hazard mitigation**, and we have no counterpart. Round 1 inverted
  the sign of that finding.

This document answers the six questions the project owner posed, flags the four places round 1
was incomplete, and separates "borrow this" from "this does not survive our architecture."

---

## 1. Port structure

**NaxRiscv.** The walker takes a **dedicated extra load port** on the D-cache, acquired through
the same public API the LSU uses: `cache.newLoadPort(priority = 1)`
(`misc/MmuPlugin.scala:157`) against `lsu/LsuPlugin.scala:417` / `lsu2/Lsu2Plugin.scala:244`
(`priority = 0`). `DataCachePlugin` keeps an `ArrayBuffer[LoadPortSpec]`
(`lsu/DataCachePlugin.scala:69-83`) and muxes them onto the one physical `DataCache.io.load`
(`:166-185`).

**Requester count and policy.** Exactly **two** load requesters, ever: LSU and MMU. Arbitration is
**pure fixed priority, re-evaluated every cycle, no aging, no round-robin** —
`sorted = loadPorts.sortBy(_.priority).reverse` then `OHMasking.firstV2(hits)`
(`DataCachePlugin.scala:168-176`) — and the **walker outranks the LSU**. Response routing is
positional: `ohHistory = History(oh, 0 to loadRspAt, …)` (`:172`), read back `loadRspAt` cycles
later (`:181-184`).

On the **store** side there is exactly **one** requester, and it is asserted:
`assert(storePorts.size == 1)` (`DataCachePlugin.scala:188`). Nothing but the LSU may write the
cache. See §5 — this is not an accident.

**Ours.** W4 puts the mux in `LsEuPlugin`'s existing override mux instead, `DcachePlugin` gains
zero ports, four requesters (`CORE-EXC > CORE-LS > {ITLB, DTLB}` round-robin, aging counter
`WALKER_AGE_LIMIT = 64`), and a depth-4 ownership-tag FIFO for response routing (W7/W29).

**Assessment.** Round 1's analysis of this stands and I will not repeat it. Two refinements:

- The positional `History` register is not merely "simpler"; it is *only available* because
  `DataCache`'s load pipe is fixed-latency. See §2 for the mechanism that buys that.
- Round 1 framed the priority difference as a fairness-policy choice. It is partly that, but it is
  also downstream of §2: NaxRiscv can give the walker permanent top priority **at zero starvation
  risk to the LSU** because a de-prioritized LSU load is not stalled — it is bounced with `REDO`
  and re-enters as a new command. Fixed priority with no aging is only cheap in a redo-based pipe.
  Our shadow-accept pipe cannot copy the policy without copying the pipe. Round 1's recommendation
  (measure removing the aging counter after the plan lands) is still a reasonable experiment, but
  it should be understood as *not* the like-for-like comparison round 1 presented it as.

---

## 2. Deadlock avoidance — NaxRiscv reaches the same conclusion, by a stronger route

Our revalidation §5.1 concludes there is no circular translation dependency, because
`DcachePlugin` contains zero MMU references and works on pre-translated physical addresses. That
reasoning is sound and NaxRiscv agrees with the conclusion. But NaxRiscv gets there through four
independent structural properties, and the difference is instructive.

**(a) The cache's load pipeline cannot back-pressure. At all.**

```scala
io.load.cmd.ready := True     // lsu/DataCache.scala:1324
```

Readiness at the client boundary is *purely* the arbiter's one-hot grant
(`DataCachePlugin.scala:176`). Nothing inside the cache can refuse a command. Every condition that
would otherwise require holding a command — miss, way hazard, bank busy, refill-slot collision,
line locked, unique-miss — is instead answered as `REDO` in the fixed response window
(`DataCache.scala:1456`, `:1528`). A command therefore occupies the port for exactly one cycle and
occupies the pipe for exactly `loadRspAt` cycles. **No client can ever hold a cache resource.**

**(b) The access that triggered the walk releases the cache immediately.**

This is the direct answer to the question our design spends the most effort on. On a translation
miss, the LSU does not stall and does not hold its in-flight cache access — it **aborts** it:

```scala
setup.cacheLoad.translated.abord := stage(tpk.IO) || tpk.PAGE_FAULT ||
                                    tpk.ACCESS_FAULT || !tpk.ALLOW_READ || tpk.REDO
// lsu/LsuPlugin.scala:986 ; lsu2/Lsu2Plugin.scala:1005
```

and inside the cache `ABORD` forces `REDO := False; MISS := False; askUpgrade := False`
(`DataCache.scala:1466-1470`), so the aborted access allocates **no refill slot** and leaves
nothing behind. The op then parks on a wake flag in the LQ/SQ — `waitOn.translationWakeAnySet`
(`LsuPlugin.scala:1166-1167` load, `:1488-1492` store; Lsu2's `CTRL_ENUM.MMU_REDO`,
`Lsu2Plugin.scala:1203-1204`, `:1296-1299`) — released by `translationPort.wake`, which is simply
`isActive(IDLE)` on the refill FSM (`MmuPlugin.scala:385-386`). The only resource held is the
queue entry itself.

The I-side does the same thing more bluntly: on `tpk.REDO` the fetch pipeline is **flushed and the
PC redirected back to itself** (`fetch/FetchCachePlugin.scala:548-563`, `redoJump.pc := FETCH_PC`),
and stage 0 is halted for the duration of the walk
(`:583`, `haltIt(!translationPort.wake)`).

So the dependency graph is not "chain instead of cycle" (our framing) but "**the requesting edge
is deleted before the walk begins**." Both are correct; NaxRiscv's is the stronger property,
because it holds even for a cache that *did* consult the MMU.

**(c) Retry waits on a named event, not a timer.** When the walker's own read is REDO'd, the cache
tells it *which MSHR it collided with* — `refillSlot` / `refillSlotAny`
(`DataCache.scala:63-69`, driven `:1518-1519`, `:1530-1539`). The walker latches that
(`MmuPlugin.scala:362-368`, `:396-399`) and gates re-issue on
`when(cacheRefill === 0 && cacheRefillAny === False)` (`:460-466`). It does not spin.

**(d) Request-storm damper.** A per-stage sticky register stops a stalled entry re-requesting a
refill it already asked for, ANDed with a global `refillOngoing`:

```scala
stage.overloaded(ALLOW_REFILL) := stage(ALLOW_REFILL) && !storage.refillOngoing && reg
reg := stage.overloaded(ALLOW_REFILL)
when(stage.isRemoved || !stage.isStuck){ reg := True }
// misc/MmuPlugin.scala:263-271
```

**What this says about W11.** Round 1 (§5.5) said NaxRiscv "cannot corroborate or contradict" our
early-VIPT probe deadlock. That is right but understates it. NaxRiscv is immune to the *entire
class*, and for a nameable reason: it has **no allocating structure that outlives one pass through
the port**. Our early-probe token array is exactly such a structure — 4 slots, allocated at probe
launch, matched at `loadCmd` time, and stranded if the mux hands the port to a client whose
`loadCmd` matches no resident token (`DcachePlugin.scala:1546`). **W11's `probeCancelAll`
extension is therefore not defensive gold-plating; it is the price of a feature NaxRiscv does not
have.** Nothing here suggests dropping it. If anything it should be raised in the plan's ordering:
it is the one deadlock in this design with no outside precedent to lean on.

---

## 3. The I-side cross-path — NaxRiscv has no cross-path, because it has no second walker

This is the largest structural divergence, and round 1 mentioned it only in passing (its §5.1).

**NaxRiscv has exactly one page-table walker in the whole core.** `MmuPlugin` exposes
`newTranslationPort(stages, preAddress, allowRefill, usage, portSpec, storageSpec)`
(`MmuPlugin.scala:126-145`; service trait `interfaces/Service.scala:405-412`) and there are three
callers:

| Caller | `usage` | `allowRefill` | Storage priority |
|---|---|---|---|
| `fetch/FetchCachePlugin.scala:311-317` | `FETCH` | `null` (⇒ always `True`, `MmuPlugin.scala:262`) | 0 (`Gen.scala:137`) |
| `lsu/LsuPlugin.scala:852-859` (load) | `LOAD_STORE` | `null` | 1 (`Gen.scala:217`) |
| `lsu/LsuPlugin.scala:1375-1382` (store) | `LOAD_STORE` | `null` | 1 (`Gen.scala:259`) |
| `lsu2/Lsu2Plugin.scala:794-801` (shared) | `LOAD_STORE` | `NEED_TRANSLATION` | 1 |

Each port gets its own TLB storage array — so there *are* a separate ITLB and DTLB — but they all
feed **one** refill FSM (`MmuPlugin.scala:344-493`) driving **one** D-cache load port.
Walk-request arbitration is again strict priority over storage priority descending
(`portSpecs.sortBy(_.ss.p.priority).reverse`, `:255`; `OHMasking.first(portsRequests)`, `:359`),
so **a D-side walk request beats an I-side one**.

The ITLB walk reaching the data cache is therefore not a cross-path at all. It is the only path,
and it costs nothing extra because the engine is shared.

**Ours.** Two walkers, one per TLB plugin, each with its own `Axi4` master today
(`DtlbPlugin.scala:77-83`, `ItlbPlugin.scala:77`). Our revalidation §5.4 is right that the ITLB
walker is *already* a D-side master (`AxiDMerge.scala:44-48`: it cannot join `axi_i` because it
issues AXI writes) and that this design moves an existing path rather than creating one. That
argument holds.

**But there is a simplification here our design does not consider, and our own code already flags
the liability.** `cache/AxiIds.scala:1-33` records that the DTLB and ITLB walkers **both emit
AR id 2**, "harmless only because they sit on separate physical masters today — and it becomes a
hard bug the instant those masters are folded (which the ratified slice V2c does)." This design
folds them onto one cache port. Unifying to a single walk engine with per-side storage — exactly
NaxRiscv's shape, with `usage` as the parameter that carries the asymmetry — would:

- delete one of the two `TableWalker` instances and its FSM,
- dissolve the ID collision rather than working around it,
- collapse W6's 3-valued owner and W9's inter-walker round-robin into nothing, and
- reduce W29's tag from four values to three.

Honest counterweights: our two walkers are **not** symmetric (D-side does U *and* M and has
`needsMRefresh` re-walks; I-side does U only and carries the `walkUmPoison`/`missPending`
machinery, `ItlbPlugin.scala:200-242`); 68040 root-pointer selection differs per side; and W5
deliberately chose independent engines so ITLB and DTLB can walk concurrently — a real throughput
property NaxRiscv gives up. This is a **separate piece of work with its own risk**, not a change to
fold into the 19-task plan. It should be recorded as a candidate, sized, and decided on its own.

---

## 4. Cacheability of the descriptor fetch — same answer, but do not borrow the mechanism

**NaxRiscv does not decide.** `DataLoadCmd` has **no `io` or cacheable field at all**
(`DataCache.scala:50-56`) — only `DataStoreCmd` does (`:88`). The walk's read is unconditionally
cacheable because there is no knob to set. The walker drives
`setup.cacheLoad.translated.physical := address` and `translated.abord := False` directly
(`MmuPlugin.scala:408-409`), bypassing its port's translate stage.

NaxRiscv's cacheability for *ordinary* accesses comes from `ioRange` / `memRange` / `fetchRange`,
which are **generation-time Scala predicates** `UInt => Bool` baked in at elaboration
(`MmuPlugin.scala:85-89`, applied at `:315` `IO := ioRange(TRANSLATED)` and `:331`;
instantiated `Gen.scala:88-99`). They are never applied to the walk.

**Assessment.** This **confirms the shape** of W1/W2/W3 — a fixed policy stamped by the layer above
the walker, never derived from the descriptor, because that would be circular. Our revalidation
§4's phrasing ("there is no page attribute in existence to consult, for anyone, ever") is exactly
right and NaxRiscv is a working instance of it.

**Do not borrow the mechanism.** NaxRiscv's static `ioRange`/`memRange` predicates are precisely
the compile-time address-decode assumption the project owner has ruled out
(standing rule: *never assume a static SoC address decode map*), and our revalidation §4.1.1
already handles this correctly. Flagging it because it is the most tempting-looking thing in
`MmuPlugin.scala` and it is off-limits for us.

The *value* also does not transfer. NaxRiscv can say "always cacheable" because its walker never
writes. Our W1 value (`CACR.DE ? WRITETHROUGH : INHIBITED`) is driven by the U/M **store** needing
to land in both array and memory in all three residency states — a constraint NaxRiscv does not
have. W1 stands on its own reasoning, unassisted by this precedent.

---

## 5. A/D bits — NaxRiscv designs the problem away, and we cannot

**This question was absent from round 1, and it is the most consequential finding here.**

NaxRiscv performs **no hardware A/D update whatsoever**. The evidence is structural, not
inferential:

- `MmuPlugin` acquires **only** `cache.newLoadPort` (`:157`). It never calls `newStorePort`.
  `grep -rn "cacheStore\|newStorePort" src/main/scala/naxriscv/misc/` returns nothing.
- `DataCachePlugin.scala:188` asserts `storePorts.size == 1` — structurally refusing any second
  writer to the cache.
- A/D are **read only**, and turned into permission denials:
  ```scala
  data.pageFault  := load.exception || load.levelException(levelId) || !load.flags.A  // :446
  data.allowWrite := load.flags.W && load.flags.D                                     // :451
  ```
  `A == 0` ⇒ page fault. `D == 0` ⇒ page is read-only, so a store page-faults. The S-mode handler
  sets the bits, `sfence.vma`s, and retries.

This is the RISC-V **Svade** (software-managed A/D) model taken in full. The entire speculation
problem — "how do you stop a speculative walk performing an architectural write" — **does not
exist in NaxRiscv, by construction.** There is nothing to prevent.

**We cannot copy this.** The 68040 mandates hardware U/M update; there is no software-managed
alternative in the architecture, so `UmWriteQueue`'s robId-tagged, commit-gated, flush-discarding
deferral (`UmWriteQueue.scala:20-30`, `:90-117`) is **irreducible**, not over-engineering. Round 1
never examined this and its blanket "the design holds up, NaxRiscv validates it" therefore did not
price it.

**Three consequences the plan should absorb:**

1. **W17 has no precedent.** Making the walker a **second store client** on the D-cache is
   something NaxRiscv structurally forbids. So are W13 (store-ack demux by latched owner) and W24
   (`sq.io.drain.ready` qualification). These are the highest-risk items in the design and they are
   *unprecedented*, not merely *adapted*. They deserve the heaviest directed testing, and the
   round-1 verdict should not be read as covering them.
2. **The one transferable idea is the encoding trick.** NaxRiscv expresses "the architectural
   bookkeeping has not been done yet" as a **missing permission in the TLB entry**
   (`allowWrite := W && D`), which forces the next real access back through the walker
   automatically, with no extra state. That is structurally identical to our `needsMRefresh`
   (`DtlbPlugin.scala:265,291`) and to the revalidation's **S8** fix for prefetched entries
   (install with a `speculative` bit; a demand hit is treated as miss-for-U-refresh). NaxRiscv is
   independent confirmation that this pattern is the right shape for S8. Worth citing in §9.3.
3. **Our I-side U-write is the genuinely exotic part.** A 68040 instruction fetch through the MMU
   sets U, so `ItlbPlugin` must tag a *fetch-side* walk with a robId (`:234`, `:256-259`) — and the
   long comment at `:200-242` records that this has already produced two real bugs (the C6 late
   allocation draining on a recycled robId, and the C1 mirror where a poisoned cold-miss fill
   permanently loses the page's U bit). NaxRiscv has no analogue and offers no help. This is the
   part of our MMU with the least outside support and the worst bug history; it deserves saying out
   loud in the plan's risk section.

---

## 6. Things NaxRiscv does that our design does not consider

### 6.1 `redoOnDataHazard` — round 1 read this backwards, and it is a real gap

Round 1's §5.2 cites this field as *corroborating evidence* that the shared-port pattern is
intentional. It is that, but that is not what the field is **for**. Its declaration:

```scala
val redoOnDataHazard = Bool() //Usefull for access not protected by the LSU (ex MMU refill)
// lsu/DataCache.scala:53
```

consumed at `:1327` and used at `:1342`:

```scala
overloaded(BANK_BUSY)(bankId) := BANK_BUSY(bankId) || bank.write.valid && REDO_ON_DATA_HAZARD
```

The MMU sets it **`True`** (`MmuPlugin.scala:404`). Both LSUs set it **`False`** for their own
accesses (`LsuPlugin.scala:966`, `:1963`; `Lsu2Plugin.scala:987`, `:1731`). The reason is stated in
the comment: the LSU enforces store→load ordering for its own traffic through its disambiguation
logic; **the walker's read is outside that net**, so the cache must conservatively bounce the walk
whenever a bank write is in flight against it.

**We have no counterpart, and the underlying hazard is real for us too.** Our walker's descriptor
read is not visible to `StoreQueue`'s forwarding/disambiguation either — today over AXI, and after
this design through the D-cache. A program store to a page-table line sitting in the SQ, not yet
drained, is invisible to a concurrent walk. `UmWriteQueue.pageQuery`/`pageHazard`
(`UmWriteQueue.scala:47-59`) covers **our own deferred U/M writes** against later translations; it
does not cover ordinary program stores against a walk.

To be precise about scope: this is **pre-existing, not introduced by the routing change** — the
walker bypasses the SQ today as well. And it is arguably 68040-legal, since page-table edits
require a PFLUSH and software that edits a descriptor without one is already outside the contract.
So this is *not* a blocker. But the design doc treats the cache-vs-memory staleness direction
thoroughly and does not mention the store-queue direction at all, and NaxRiscv shows the mitigation
is nearly free (one flag on the command, one OR term in the bank-busy computation, retry via the
existing redo path). **Recommend: add it to the design as an explicit non-decision (an N5) with the
above reasoning, so the omission is recorded as considered rather than missed.**

### 6.2 `allowRefill` as a first-class per-port gate

`newTranslationPort` takes an `allowRefill : Stageable[Bool]` (`MmuPlugin.scala:128`) meaning
"this access is permitted to *trigger* a walk," consumed at `:262` and `:302`
(`askRefill := needRefill && overloaded(ALLOW_REFILL)`). Two things worth noting:

- **It is not a speculation gate in NaxRiscv.** All three v1 ports pass `null` ⇒ always `True`;
  Lsu2 passes `NEED_TRANSLATION`, which only means "this entry has not already got a physical
  address" (`Lsu2Plugin.scala:873`, `:897`, `:1052-1061`). NaxRiscv lets *any* speculative access
  trigger a walk — safe precisely because of §5 (a walk has no architectural side effect).
- **The hook shape is nonetheless what our §9.4 prefetch design needs.** Our S1 threads a
  `speculative : Bool` through `WalkReq`. A per-port `allowWalk` input on the translation service is
  the same idea expressed at the interface instead of in the payload, and it composes better with a
  fifth requester. Worth a look when S1 is implemented; not a change to W-items.

### 6.3 Wait on a named event rather than a timer

§2(c). Our W8 aging counter is a *fairness* mechanism; NaxRiscv's `refillSlot` back-channel is a
*wait-for-the-right-thing* mechanism, and the two are not substitutes. If a walker's D-cache read
is refused because it collided with an in-flight refill, waiting on that refill's completion is
strictly better than aging. Cheap to add later; not required for correctness.

### 6.4 `PostCommitBusy` gates TLB invalidation

`sfence.vma` may not start while committed-but-undrained stores exist:

```scala
when(refill.busy || getServicesOf[PostCommitBusy].map(_.postCommitBusy).orR){ canStart := False }
// misc/MmuPlugin.scala:519-522   (trait: interfaces/Service.scala:592-594; driven LsuPlugin.scala:689)
```

Our PFLUSH/PFLUSHA path should be checked for the equivalent property — a flush that lands while an
undrained store is still in the SQ, or while a `UmWriteQueue` entry is still queued, has the same
shape of hazard. **This is a check item, not a finding**; I did not verify our behaviour either way.

### 6.5 `hitsWithTranslationWays` — a timing idiom, I-side

`FetchCachePlugin` compares I-cache tags against **every TLB way's** physical address in parallel
(`tpk.WAYS_PHYSICAL`, `tpk.WAYS_OH`, exported at `interfaces/Service.scala:390-392`; used
`FetchCachePlugin.scala:510-517`; enabled `Gen.scala:120`), rather than serialising
TLB-mux → tag-compare. Given §7's concern about the `loadCmdPort.payload.vaddr → cmdSet → rdSet`
net, the idiom is worth knowing. It is an I-side trick and our concern is D-side, so it is
noted, not recommended.

### 6.6 The walk respects the cache line lock

`MmuPlugin.scala:406` sets `cmd.unlocked := False`, so a walk that collides with the LSU's
`lockPort` line is REDO'd (`DataCache.scala:1429`, `:1456`). That is safe only because the lock is
taken exclusively by the AMO/LR-SC FSM (`LsuPlugin.scala:1938-1945`) for accesses that are
**already translated** — so the lock holder never needs a walk, and there is no cycle. A designed-in
ordering property worth being aware of if we ever add a lock-like structure.

---

## 7. Things our design does that NaxRiscv deliberately avoids

| Ours | NaxRiscv | Is the divergence forced? |
|---|---|---|
| Walker is a **store** client (W17), plus W13/W24 | `assert(storePorts.size == 1)` — LSU only | **Yes.** 68040 mandates hardware U/M; RISC-V Svade does not. Irreducible. |
| `UmWriteQueue` robId/commit/flush deferral | No such queue; no PTE write path at all | **Yes**, same reason. |
| Two independent walk engines (ITLB + DTLB) | One shared engine, per-side storage, `usage` parameter | **No.** A deliberate throughput choice of ours; see §3 for the case to revisit. |
| Depth-4 ownership FIFO (W7/W29) | Fixed-latency one-hot `History` shift register | **Yes.** Our shadow-accept variable-latency pipe cannot use a static scheme. Round 1's analysis of this is correct. |
| Arbitration outside the cache (W4) | Arbitration inside `DataCachePlugin` | **No**, but ours is the lower-risk placement given a 3226-line `DcachePlugin` we do not want to edit. Both are one layer above the raw cache. Keep W4. |
| Aging counter (W8/W9) | Pure fixed priority, walker on top | **Partly.** See §1 — the policy is downstream of the redo-based pipe, so this is less of a like-for-like than round 1 suggested. |
| Early-VIPT probe tokens ⇒ W11 deadlock | No structure that outlives one port pass | **Yes.** W11 is the price of a feature NaxRiscv lacks. Keep it, and rank it first among the design's own-goal risks. |

---

## 8. Bottom line

Nothing here changes the recommendation in the revalidation doc's §11: build Stages A+B, gate
Stage C. Four things to carry forward:

1. **Round 1's verdict covers the read path only.** W17/W13/W24 — the walker as a *store* client —
   have no NaxRiscv precedent because NaxRiscv's walker never writes. Treat them as unprecedented.
2. **Record the store-queue-vs-walk hazard as N5** (§6.1), with the "pre-existing, PFLUSH-covered,
   cheap to fix if we want to" reasoning. It is currently absent, not decided.
3. **Single-walker unification is a real candidate** (§3) that our own `AxiIds.scala` header already
   argues for on ID-collision grounds. Size it separately; do not fold it into the 19 tasks.
4. **Do not borrow `ioRange`/`memRange`** (§4) — static generation-time address decode, ruled out
   here.

And one thing to stop worrying about: the circular-dependency question is settled twice over.
Our answer (the D-cache never consults the MMU) and NaxRiscv's answer (the requesting access is
aborted before the walk starts, and the pipe cannot back-pressure) are independent, and both hold.

---

## Evidence index

| Claim | Citation |
|---|---|
| Walker takes a dedicated D-cache load port, priority 1 | `misc/MmuPlugin.scala:157` |
| LSU load port, priority 0 | `lsu/LsuPlugin.scala:417`; `lsu2/Lsu2Plugin.scala:244` |
| Fixed-priority one-hot arbiter, 2 load requesters | `lsu/DataCachePlugin.scala:167-176` |
| Positional response routing via `History` | `lsu/DataCachePlugin.scala:172,181-184` |
| Exactly one store client, asserted | `lsu/DataCachePlugin.scala:188` |
| Cache load pipe never back-pressures | `lsu/DataCache.scala:1324` |
| Everything becomes `REDO` | `lsu/DataCache.scala:1456,1528` |
| Requesting access aborts its cache access on TLB miss | `lsu/LsuPlugin.scala:986`; `lsu2/Lsu2Plugin.scala:1005`; `lsu/DataCache.scala:1466-1470` |
| LSU parks on `translationWakeAny`, holds no cache resource | `lsu/LsuPlugin.scala:1166-1167,1488-1492`; `lsu2/Lsu2Plugin.scala:1296-1299` |
| Wake = refill FSM idle | `misc/MmuPlugin.scala:385-386` |
| I-side TLB miss ⇒ pipeline flush + PC redirect | `fetch/FetchCachePlugin.scala:548-563` |
| Fetch stage 0 halted during a walk | `fetch/FetchCachePlugin.scala:583` |
| `refillSlot`/`refillSlotAny` back-channel; walker gates re-issue on it | `lsu/DataCache.scala:63-69,1518-1519`; `misc/MmuPlugin.scala:362-368,396-399,460-466` |
| Per-stage refill re-request damper | `misc/MmuPlugin.scala:263-271` |
| One walker, three translation ports | `misc/MmuPlugin.scala:126-145`; `fetch/FetchCachePlugin.scala:311`; `lsu/LsuPlugin.scala:852,1375`; `lsu2/Lsu2Plugin.scala:794` |
| D-side walk request beats I-side | `misc/MmuPlugin.scala:255,359`; `Gen.scala:137,217,259` |
| `DataLoadCmd` has no io/cacheable field | `lsu/DataCache.scala:50-56` vs `:88` |
| Walker bypasses its port's translate stage | `misc/MmuPlugin.scala:408-409` |
| Static generation-time address map | `misc/MmuPlugin.scala:85-89,315,331`; `Gen.scala:88-99` |
| No hardware A/D update; A/D only read | `misc/MmuPlugin.scala:416,446,451`; no `newStorePort` call in `misc/` |
| `redoOnDataHazard` exists because the walk is outside LSU disambiguation | `lsu/DataCache.scala:53,1327,1342`; `misc/MmuPlugin.scala:404`; `lsu/LsuPlugin.scala:966,1963` |
| `allowRefill` per-port gate | `misc/MmuPlugin.scala:128,262,302`; `lsu2/Lsu2Plugin.scala:794,873,897,1052-1061` |
| `PostCommitBusy` gates `sfence.vma` | `misc/MmuPlugin.scala:519-522`; `interfaces/Service.scala:592-594`; `lsu/LsuPlugin.scala:689` |
| Parallel TLB-way tag compare | `interfaces/Service.scala:390-392`; `fetch/FetchCachePlugin.scala:510-517`; `Gen.scala:120` |
| Walk respects the cache line lock; lock holder is pre-translated | `misc/MmuPlugin.scala:406`; `lsu/DataCache.scala:1429,1456`; `lsu/LsuPlugin.scala:1938-1945` |
| Our two walkers collide on AR id 2 | `src/main/scala/m68k040/cache/AxiIds.scala:1-33,96-98` |
| Our ITLB U-write bug history (C6, C1 mirror) | `src/main/scala/m68k040/mmu/ItlbPlugin.scala:200-242` |
| `UmWriteQueue` commit-gated deferral | `src/main/scala/m68k040/mmu/UmWriteQueue.scala:20-30,90-117` |
| Our early-probe token gate on `loadCmdPort.ready` | `src/main/scala/m68k040/cache/DcachePlugin.scala:1546` |
