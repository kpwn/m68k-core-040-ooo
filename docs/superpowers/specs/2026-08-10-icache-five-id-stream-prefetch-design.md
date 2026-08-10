# Five-ID I-cache stream-prefetch design

**Date:** 2026-08-10  
**Status:** Draft; RTL is blocked until the SoC CPU-IF five-ID contract passes  
**Parent:** `2026-08-10-icache-parallel-vipt-design.md`  
**Supersedes on activation:** the parent's Phase-B four-entry sketch and
`2026-07-30-mshr-multi-outstanding-design-proposal.md`'s two-entry I-side
recommendation.  Rules P1-P4 from the older proposal remain binding.

## 1. Decision and measured reason

The next I-side performance slice is one demand refill slot plus four silent
speculative refill slots.  The five slots use five distinct original AXI IDs.
Responses are routed by RID and may arrive out of order across IDs; only the
one demand owner may generate `FetchRsp`, so the external response stream stays
strictly in acceptance order and FetchAlign is unchanged.

This is a latency-bandwidth design, not an attempt to make a warm hit faster.
The two-cycle resident L1I is already II=1.  The pinned `independent-ALU`
benchmark retires about two 16-bit instructions per cycle, so a 64-byte line
lasts about 16 cycles.  The realistic memory posture has a 70-cycle cold-line
latency:

```
required live lines = ceil(70 / 16) = 5
```

Four total IDs would leave a roughly six-cycle gap every four lines in the
ideal straight-line case.  Five cover the stated latency.  The L2 has eight
MSHRs; this slice does not increase that structure.

The existing singleton prefetch is measurably insufficient.  Phase A changed
the realistic independent-ALU mean only from 1129.000 to 1128.333 cycles.  A
resident register cut cannot hide serialized 70-cycle fills.

## 2. External dependency

No RTL under this document may land until the sibling SoC proves all of:

- CPU instruction master M2 accepts five distinct original ARIDs and presents
  five downstream S0/L2 AR handshakes before any response;
- reuse of a live ID and a sixth live transaction are backpressured;
- downstream XID is `{physicalMaster=2, originalArid}`;
- cross-ID responses may reorder and are returned to M2 by original RID;
- beats within one ID remain ordered and terminate exactly at RLAST;
- other masters, non-S0 M2 traffic, writes, and JTAG retain their legacy
  serialized behavior.

The SoC table must be a compile-time parameter supporting at least 4..8 and
defaulting to five.  The core remains a legal single-outstanding AXI master
when prefetch is disabled.

## 3. Slot and ID ownership

`AxiIds` is the only ID owner.  The instruction range is:

| ID | owner | architectural response |
|---:|---|---|
| 0 | reserved demand slot | exactly one `FetchRsp` |
| 1..4 | silent speculative slots | none |

When a held demand address matches a speculative slot, the command remains
stable under backpressure and re-looks up after that slot installs.  It must not
issue another AR.  If the speculative fill fails, it is discarded and the held
demand then allocates ID 0 as a new architectural transaction.  This retains
the already-proven singleton-prefetch retry contract and avoids turning a
silent request into a faulting request halfway through an AXI burst.

Slot state is registered and minimal:

```
FREE -> AR_PENDING -> FILL -> COMPLETE -> INSTALL -> FREE
```

Each live slot carries line VA/PA, set, physical tag, reserved victim way,
expected beat, accumulated error, and poison.  Slot index is the AXI ID; no
response CAM is added in the core.  ID 0 retains the existing demand miss PC,
cache-mode, line, and fault context.  A response with no live matching slot is
a fatal simulation error.

ID 0 remains available for a new redirect-target demand even while IDs 1..4
carry wrong-path speculative fills.  Outstanding speculation is poisoned or
allowed to finish; it is never aborted on AXI.

## 4. Five-line sequential window

On every accepted cacheable demand lookup, the prefetch sequencer records that
demand's 64-byte line, PPN, and page.  It may allocate same-page candidates up
to five lines ahead, one candidate per cycle, into free speculative slots.
Candidates are skipped when resident or already live.  A same-set different-
line live fill is held until that set is free.

Only four speculative bus slots are needed to maintain a five-line lookahead:
the current demand line uses ID 0 during bootstrap; as completed speculative
lines install and free IDs 1..4, those IDs fetch the farther fifth line before
it is needed.  The sequencer advances its allowed frontier when demand moves
to the next line.  It must not run unbounded through the page.

A nonsequential demand resets the frontier to the new line.  Old speculative
transactions may finish silently, but demand ID 0 is reserved so they cannot
block allocation of the new target.  Demand always wins AR and install
arbitration.

P1-P4 are unchanged:

- candidates use only the already resolved translation of the same 4-KiB
  page; speculation never starts an ITLB walk;
- candidates must be cacheable and may not cross a page;
- speculative bus errors are silent and allocate nothing;
- a later real demand retries or promotes precisely and receives its own
  architectural success or fault result.

## 5. Fill storage and shared install pipe

Do not replicate the 512-bit `lineReg` five times and do not replicate the
predecoder.  ID 0 retains the existing demand `lineReg`.  Use two shallow,
ID-indexed memories, each four entries by 256 bits, for the low and high beats
of speculative IDs 1..4.  One R beat is accepted per cycle and written to the
destination selected by RID and the slot's registered beat phase.

These shallow stores are expected to map to LUTRAM, not BRAM.  A completed
speculative slot is selected only when the demand FSM does not need the shared
installer; both halves are read into the existing registered 512-bit
`lineReg`.  The existing 16-word `classifyBeat` hardware is reused over two
registered commit cycles:

1. install low data beat and its prediction half using the high beat for the
   three-word lookahead;
2. install high data beat and its line-end prediction half, then write tag,
   valid, prefetched provenance, and advance the victim pointer.

Only the shared installer writes cache data/prediction/tag state.  A lookup to
the install set is held across those commit cycles, preventing old-tag/new-data
or new-tag/partial-data observations.  At most one live fill may reserve a
given set; other sets remain usable.

The victim line stays valid until installation starts.  An errored or poisoned
fill therefore need not destroy it.  A successful fill changes visibility
atomically at the final install edge.

For a poisoned but otherwise successful demand, its `lineReg` and
classified prediction carry the response through the existing miss bypass,
but no array/tag/valid update occurs.  A speculative poisoned fill is simply
freed after its beats drain.

Expected incremental storage is roughly 2,048 fill bits in shallow LUTRAM plus
four narrow speculative contexts; the demand context and shared install
register already exist.  There is no new cache port, data BRAM, classifier
copy, TLB entry, DSP, or ROB/IQ port.

## 6. AXI protocol

AR arbitration is registered-priority demand then speculative slot number.
Payload stays stable until `ar.fire`.  A slot changes from AR_PENDING to FILL
only on that handshake.

R routing is solely by RID.  The slot's expected phase determines low/high
storage; neither FSM state nor request order may attribute a beat.  The pool
accepts cross-ID reorder and legal beat interleaving.  For each ID:

- first accepted beat is phase zero and not last;
- second accepted beat is phase one and last;
- error is sticky across both beats;
- no ID is reused before the slot returns FREE.

`r.ready` is asserted only for a live FILL slot with the expected RID/phase and
available fill-memory write port.  Invalid, duplicate, early-last, late-last,
or extra beats are assertion failures; tests may not discard them to stay
green.

## 7. Ordering, faults, and invalidation

- There is at most one architectural demand waiter.  It is delivered exactly
  once after its slot installs, bypasses, or faults.
- Resident hits may continue while only speculative slots are live, except for
  a same-set install collision.
- Once a demand waiter attaches, new commands wait; no younger response can
  pass it.
- A speculative error never raises an architectural or diagnostic fault and
  never writes cache state.
- A speculative error is discarded.  A held real demand then issues one new
  ID-0 transaction whose own response determines its precise result.
- `invalidateAll` clears resident valids and poisons every live slot.  Late
  completion cannot repopulate the cache.  An already accepted demand still
  receives one response so FetchAlign can retire its stale ring entry.
- Cache mode and physical tag are captured from the exact demand translation
  that seeds the same-page window.  A mapping change cannot relabel a live
  entry.

## 8. Non-vacuous verification

The implementation gate must include:

1. Hold all R responses; require five distinct AR fires (IDs 0..4, consecutive
   line PAs) before any R, then prove the sixth request is not accepted.
2. Return the five lines in a deliberately different cross-ID order, with beat
   interleaving.  Check exact data/address/ID association, one demand response,
   four silent fills, and no unexpected-ID acceptance.
3. Run at least sixteen sequential lines under a 70-cycle per-ID reordering
   model.  After bootstrap, require no demand refill after the first, bounded
   line-boundary bubbles, and a measured peak of five live ARIDs.  Mutation to
   one live ID or the singleton FSM must fail both concurrency and cadence.
4. Hold a demand on an in-flight speculative line.  Require no duplicate AR,
   stable command payload until install, and one exact eventual hit response.
5. Fill IDs 1..4 with wrong-path traffic, redirect to an unrelated cold line,
   and prove reserved ID 0 issues and completes without waiting for those
   slots to become free.
6. Inject an error into a silent fill while a matching demand is held.  Require
   the silent request to allocate nothing, followed by exactly one new ID-0
   architectural transaction whose response determines success or fault.
7. Exercise real nonidentity ITLB mappings, page-end suppression, inhibited
   mode, resident suppression, same-set exclusion, and mapping association.
8. Pulse invalidation at AR_PENDING, each R beat, COMPLETE, and both install
   phases.  No poisoned line may become valid and the accepted demand must not
   wedge the fetch ring.
9. Disable prefetch.  Require only ID 0, legacy demand/fault behavior, and no
   change to resident N+2 latency or II=1 cadence.
10. Run the mandatory `test-fast`, focused Verilator suites, paired pinned IPC
    under `zero` and `l2:5:70`, and a fresh 250-MHz global plus floorplanned
    physical gate.

Tests count handshakes and check payload stability.  They must not infer
success from elapsed time, a `valid` pulse without `ready`, or final memory
contents alone.

## 9. Physical acceptance

The 250-MHz optimization target and 200-MHz deployment floor both remain.
Report global and Icache endpoint WNS/TNS/failing counts, the RID-to-fill-store
path, fill-store-to-install-register path, install classifier path, and
AR-ready live-ID cone.  Compare the existing decode+D-cache floorplan and an
unfloorplanned route because the enlarged frontend may change placement.

Area is a review point, not an automatic rejection.  Report LUT/FF/LUTRAM/BRAM/
DSP deltas before changing the design.  If timing fails, first add registered
arbitration boundaries or improve placement; do not collapse RID routing,
replicate the classifier, or make the ITLB drive the data-BRAM address.
