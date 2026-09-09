# KNOWN DEVIATION: the ATC is not filled on a FAULTING table search

**Status**: PRE-EXISTING, deliberate for now, NOT changed by the address-space slice
that recorded it. Needs an owner decision; it is not a correctness bug.

**Severity**: performance and fidelity, not correctness. No wrong data, no missing
fault, no missing exception. It changes how OFTEN a fault costs a table search, and it
makes one architectural state that exists on silicon unreachable here.

---

## 1. What the core does

`DtlbPlugin` and `ItlbPlugin` both gate the ATC fill on the walk having SUCCEEDED:

```scala
when(!walker.io.rsp.fault && !walkFlushPoison && !flushAll && !walkUmPoison) {
  tlb.io.fillValid := True
}
```

So a table search that ends in a fault -- non-resident, write-protected or
supervisor-protected -- leaves NO entry behind. The next access to that page repeats
the entire three-level search, and so does the one after that, indefinitely.

## 2. What an MC68040 does

Real silicon caches the faulting translation. The ATC entry has a resident/valid
encoding that records the violation, so the SECOND and subsequent accesses to a
protected or non-resident page HIT the ATC and take their fault immediately, with no
table search at all. That is why "a resident permission fault, answered at the ATC's
own latency, with no re-walk" is a state a 68040 can be in.

## 3. Why it surfaced now

It surfaced as a consequence of adding FC2 to the ATC tag (`Tlb.tagSup`, 2026-09-09),
which was required to make MOVES honour SFC/DFC at all -- see
`docs/BUG_*`/the commit for that argument. Before the tag, a USER access to a page
whose only ATC entry had been filled by a SUPERVISOR access HIT that entry and faulted
off its cached `supervisor` attribute, with no walk. That looked like the silicon
behaviour above, but it was reached by an unsound route: it answered a user request out
of the supervisor tree, and with URP =/= SRP (the live board: SRP = 0x03FFFA00,
URP = 0) the two trees are independent, so the correct answer might have been a
successful translation to an entirely different page rather than a fault.

With FC2 tagged, the user access correctly misses. And because a faulting walk does not
fill, it will miss again next time, and every time.

Net effect: the two behaviours interact. Either one alone is fine.
  * FC2 tag + fill-on-fault  = silicon behaviour (fault cached per address space).
  * no FC2 tag + no fill     = the old behaviour (fault cached, but in the wrong space).
  * FC2 tag + no fill        = where we are now: correct answers, re-walked every time.

## 4. What it costs

A supervisor-only or non-resident page that user code touches repeatedly pays a full
three-level table search per touch. On a paging OS that is the normal steady state for
a demand-zero page before it is mapped, and for any access-control probe. It is a real
slowdown on a workload that faults in a loop; it is invisible on one that does not.

It also removes an ATC state from reachability, which cost one test its scenario:
`IcacheParallelViptSpec`'s permission-fault case used to assert a two-cycle resident
fault with no re-walk. That test now asserts the fault ARRIVES, is attributed to the
ATC, launches no refill and produces exactly one response -- and asserts the walk
explicitly, where it used to assert its absence. The cycle count was dropped because the
state it measured no longer exists, not because the property stopped mattering.

## 5. Why it is NOT being changed here

Filling the ATC on a faulting walk is a real change to the fault path with its own
hazards, none of which belong in an address-space slice:

  * The entry must record WHICH fault (write-protect vs supervisor vs non-resident);
    `TlbEntry` has `writeProt` and `supervisor` but no non-resident encoding, so a
    non-resident fill needs a new field and a new lookup term on the FMax-critical
    hit path.
  * A cached non-resident entry MUST be invalidated when the OS makes the page
    resident. Real 68040 software issues PFLUSH for exactly this reason, and the Mac
    ROM's habits here are not characterised in this project. Getting it wrong turns a
    performance fix into a hang: a page that has been paged in still faults forever.
  * The deferred U/M write queue's allocation is gated on `!walker.io.rsp.fault` on the
    same expression; changing the fill gate without re-deriving that one risks queueing
    a descriptor write for a walk that faulted.

## 6. The decision needed

Someone should decide deliberately between:

  (a) Leave it. Correct today, slower on fault-heavy paths, one unreachable ATC state.
  (b) Cache faulting translations, matching silicon -- costs a fault-kind field in the
      entry, a wider lookup compare, and a hard look at PFLUSH coverage.

This file exists so (a) is a choice rather than an accident. It is currently (a).
