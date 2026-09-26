# OoO load issue + OoO prefetch — design rules

Owner-approved design, 2026-09-26. Two related but INDEPENDENT changes: the first
removes an issue-bandwidth limit, the second removes miss latency. Build in order.

## Why: the binding constraint is load ISSUE, not load latency

`IssueQueuePlugin.scala:680,729` — `ohL = ohLoldest & lsReady` — the LS port may
select only the OLDEST OCCUPIED LS slot, ready or not. Its own comment at `:700`
records the measurement:

> a younger READY load sits behind an older unready LS op for **22,999 of 27,617
> cycles**, and of the 23,006 blocked cycles only **1,535** have a STORE at the head

So ~83% of cycles have a ready load blocked, and 93% of the time the blocker is
another LOAD — between which no hazard exists at all. Board windows separately show
IQ-blocked dispatch 55-59% while ROB-full is 0-7 cycles in 300M: the 16-slot IQ is
the window, not the 32-entry ROB.

⚠️ This is why three latency optimisations measured ~0 on silicon (hit-under-miss
+5.1% sim / 0 board; speculative wakeup +14.4% chase-pure / 0 board; BTB 128->512
-11% mispredicts / 0 Dhrystone). **A load that cannot issue does not benefit from a
faster load pipeline.**

## Part 1 — OoO load issue (cacheable reorders freely)

The rule, as ONE invariant: **an INHIBITED access is a full memory barrier in BOTH
directions; cacheable accesses reorder freely.**

1. **At issue** (nothing translated, cacheability unknowable — the blocker is
   *unready*, so it has no address yet): a load MAY pass an older unready LOAD.
   Stores stay strict this phase. A load may NOT pass an older store whose address
   is not yet published (`ls/MemoryDependencyTracker.scala`'s "unknown" barrier) —
   that is the real disambiguation hazard the oldest-only rule was protecting.
   Stretch goal: a load MAY pass an older store with a KNOWN, provably non-aliasing
   address.
2. **At P3, on translating INHIBITED** (`LsEuPlugin.scala:~1220`, where cache mode
   is first known) that op becomes a barrier:
   - **before**: it does not launch until every older LS ticket is resolved;
   - **after**: no younger LS op may launch until it completes (ordinary
     back-pressure — no recovery needed);
   - **violation**: a younger op that ALREADY launched before the barrier was
     discovered. Set `orderViolated`; on its retire, flush to its fall-through PC
     via the existing `branchRedirect`/`mispredictStore` path (`RobPlugin.scala:1359`).
     Inhibited ops are already precise and at-head, so no new rollback machinery,
     and cacheable refills are side-effect free so a squashed load leaves no trace.

⚠️ Why the decision MUST live at P3 and not at issue: `loadBypassUnreadyLoad`
already exists (`IssueQueuePlugin.scala:708-727`) and **wedged silicon**. At issue
time nothing is translated (`DcachePlugin.scala:49-56`), so it could reorder a
device access. A hit gate does NOT rescue it — the hazard is the *older* op's
attribute, which is unknown at that point.

⚠️ Today's in-order LS issue may be providing "barrier-after" IMPLICITLY. Relaxing
issue is exactly what removes it; it must be made explicit.

⚠️ Deadlock: release the barrier on "no older UNRESOLVED ticket" AND on flush —
never on a ROB-head equality alone, or a squashed wrong-path ticket holds it forever.

Proofs that currently ASSUME in-order LS issue and must be re-derived, not assumed:
`StoreQueue.scala:437-470` (precise store reaching head), SQ allocation order,
`inhibitedLoadBusySig`, and the `ooOk` OoO-completion token path.

## Part 2 — OoO prefetch (replay in order)

For a blocked-but-ready load, issue it out of order in PREFETCH mode: warm L1, then
let the architectural load replay in program order and hit. A prefetch has no
architectural effect, so it needs NONE of Part 1's barrier/violation/flush machinery.

```
AGEN -> XLATE          (PA + cache mode)
  INHIBITED -> KILL    no bus access
  cacheable -> probe L1; on miss, start the refill
no writeback, no ROB completion; the demand load replays in order
```

- ⛔ **The kill is mandatory.** A refill to MMIO reads a device register — pops a
  FIFO, clears a status bit. That is the DMA/polling shape that wedged the board.
  An L1 *lookup* is harmless; the *bus transaction* is not.
### The side-effect rule (keep the machinery reusable)

**A prefetch may have exactly the side effects its backing instruction would have
had. A prefetch with NO backing instruction may have NONE.**

Side effects are therefore a property of the REQUEST, not of "prefetch" as a
mechanism. Carry a `demandBacked` bit on the prefetch request and gate on it, so the
same machinery serves a future sequential/stride prefetcher without being unsafe:

| | DEMAND-BACKED (a dispatched load is blocked) | SPECULATIVE (predicted address, no instruction) |
|---|---|---|
| table walk | allowed, PRECISE | **no walk** — drop on a DTLB miss |
| sets U | **yes, and it is correct** | **never** |
| refill on cacheable miss | yes | yes |
| bus access when INHIBITED | never | never |
| fault delivery | never (deferred to demand issue) | never |

The asymmetry is the whole point: a demand-backed prefetch is the SAME access the
load will make, merely early, so it may touch page tables exactly as that load
would. A speculative prefetch is a GUESS — walking a guessed address would mark
pages Used that nothing ever accessed, and could walk arbitrary page tables on a
bad prediction. Marking U there is not "a harmless mistake", it is unbacked state
change, and it is also the thing that makes a predictor hard to reason about later.

- ✅ **Table walks for a DEMAND-BACKED prefetch are ALLOWED, and may be PRECISE**
  (owner decision).
  The prefetch is for a load that is ALREADY DISPATCHED and sitting in the IQ -- not
  a wrong-path guess -- so it will execute unless a flush kills it. The walk is
  therefore the SAME walk the demand load would perform, just early: setting **U** is
  correct, not a tolerated error (M is write-side and does not arise for a load).
- ⚠️ The one thing that must NOT be early is **FAULT DELIVERY**. A mispredict flush
  can still remove the load, and an exception is not droppable the way a prefetch is.
  So: walk precisely, but on a not-present descriptor or a bus error, kill the
  prefetch silently and let the demand load raise the fault when it really issues.
  Never deliver an exception on a prefetch's behalf.
- ⚠️ A **demand walk must always win arbitration** over a speculative one. The
  descriptor port is 1-deep and this path has bitten twice: the deferred U/M drain
  once REVERTED software's page-table writes (stale descriptor byte rewritten
  whole), and separately DEADLOCKED on a lost wakeup.

The existing vehicle is `DcacheTypes.scala:278-280` — `loadProbe` ("virtual-set
read, before translation"), `loadProbeResolve` (matching registered PA/tag) and
`loadProbeCancel`, with a 5-entry queue recording hit AND data. Note this trio
already gives the VIPT overlap (array read in parallel with the DTLB); today a
probe NEVER refills, which is why it is MMIO-safe but also why it does not yet
prefetch.

## Expected payoff, and where it will NOT show

Part 1 attacks the 83% issue blockage — expect it on Dhrystone.
Part 2 converts misses to hits, so it is sized by miss rate:

| workload | dc-miss/kinst | prefetch upside |
|---|---|---|
| Dhrystone | 1.2 | ~nil |
| real OS boot code | 9.7-30 | substantial |

**Do not judge Part 2 by Speedometer.** It is the boot/OS-code lever.
