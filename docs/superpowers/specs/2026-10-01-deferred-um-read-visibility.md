# Deferred U/M descriptor read visibility

## Observed failure and contract

In the shipping throughput posture, `mmu_atc_write_hit_sets_modified` reads a
leaf descriptor as ordinary supervisor data after a mapped-page store. The
store's DTLB M-refresh allocates a deferred U/M byte update at cycle 574 and
retires around cycle 585. The younger descriptor load is accepted at cycle 587,
while the U/M queue is draining; it returns the old descriptor `0x00300009` at
cycle 592 and retires. The physical byte becomes `0x19` only at cycle 608. The
program consequently stores the failure sentinel `0xdead0903`. The write-hit
M-refresh predicate and eventual metadata write both work; ordinary load
visibility is wrong.

An ordinary load of a physical descriptor byte must observe U/M bits produced
by architecturally older walks, even if their deferred cache write has not
finished. It must not observe U/M bits owned solely by younger speculative
operations. A flushed walk must never write metadata. A software descriptor
store's value, including protection and cache-mode bits, must not be replaced
by an old sampled descriptor; the drain remains a read/OR/write of only bits
`0x18`.

## Candidate read-side mechanism; implementation blocked on store ordering

1. The DTLB's U/M queue exports a physical-byte visibility query through a
   service. For each valid, non-dead entry, compare its physical descriptor
   byte address to each byte in the requested cache line. A committed or
   pre-committed entry qualifies; a speculative entry qualifies only when its
   owner is older than the querying load in ROB-head-relative modular order.
   OR only `newByte & 0x18`. Never OR another field from the sampled byte.
2. The LSU captures that query result when it admits an ordinary D-cache load
   into its response ring. Carry the mask with the descriptor across delayed
   send, hit, miss, out-of-order parked response, early writeback, and both
   halves of a split load. Apply it to the corresponding response bytes before
   extraction/merge and before any PRF write or dynamic wakeup. A response-time
   query alone is insufficient: the queue may pop between the cache read and
   response, leaving a stale result with no pending entry to query.
3. With LS out-of-order issue enabled, a younger load can run before an older
   unready load performs its first U-setting walk. At U/M allocation, mark that
   walk's owner in the existing ROB macro order-redirect mechanism. After the
   owner retires, restart all younger speculative work; a re-executed load now
   encounters either the queued older byte or its completed cache write.
   This conservative restart is only needed for a normal ROB-owned walk and
   must be suppressed for poisoned and exception-owned walks. It can be
   optimized later with a precise bypass/alias tracker after measuring its
   frequency. In the in-order LS issue posture, no younger LSU access can
   precede the older walk's allocation, so the redirect is unnecessary.
4. A full store-queue forward retains its software-store value; it does not
   consume a cache response mask. However, this alone does **not** establish
   correct order against a younger software descriptor store already drained
   to the D-cache. If the earlier U/M entry drains afterward, an OR-forwarded
   or physical bit may resurrect a U/M bit that the younger software store
   deliberately cleared. The reader must not claim global U/M coherency until
   this store-ordering case is solved.

The queue is allocated in walk-completion order, which need not be ROB order.
Therefore waiting for its head to drain is not a valid replacement for
forwarding: an uncommitted younger entry may sit ahead of an older committed
entry and prevent its drain until the waiting older load retires. The proposed
query is combinational and never stalls the owner, walker, or drain. No global
page-number interlock is used; the comparison is between **physical byte**
addresses.

One possible complete design holds an SQ descriptor-byte drain while an
architecturally older matching U/M entry remains pending, then lets the U/M
write finish before the software store. That requires the U/M queue to drain
committed entries past uncommitted younger entries: its current ring head can
otherwise block the needed update and deadlock the held SQ store. The walker
must still be able to issue its drain re-read/store through the shared D-cache
arbiter while the SQ store is held. This is an architectural ordering change
across three components, not a local forwarding mux; its queue selection,
same-byte priority, cancellation, and flush invariants need a separate review
and directed tests before RTL implementation. An alternative is age-aware
merging/cancellation of the older U/M entry by a committed younger software
store, but that too must handle a drain already in flight. No variant is yet
ratified by this draft.

## Two complete D-side implementation routes under review

**Selective visibility and physical-byte ordering.** Keep the read-side
snapshot/restart scheme above. Compare each committed SQ drain's physical
byte strobes and ROB owner against all older pending U/M bytes; hold a matching
SQ store until those U/M writes acknowledge. The queue must select an eligible
committed entry even when an uncommitted younger entry occupies its ring head.
The selected slot is held through re-read, store and ack; if it is not the ring
head, mark it dead after ack and reclaim it once the head advances. For a
same-byte pair of U/M entries, preserve the older-before-younger order relative
to an intervening software store; OR updates may coalesce only when no
software-store boundary lies between them. A held SQ store must leave the
walker's load and store arbiter paths available, or the interlock self-deadlocks.
This route preserves steady-state retirement and overlap but adds two line
queries for the load ring, per-byte mask state, SQ-to-U/M comparators, and
multi-slot U/M drain selection. Directed tests must include younger software
clear, a younger speculative U entry ahead of an older committed entry, a
drain already in flight, and flush while the SQ is held.

**Serialize at the U/M owner.** The ROB can instead authorize a D-side U/M
entry only when its owner is the irrevocable head, wait for the descriptor
write ack, then retire the owner and restart every younger instruction at the
next macro boundary. A younger software store cannot drain before the older
owner retires, and every younger load that read stale data is squashed before
it can retire. This removes load-mask and SQ-byte comparators but still needs
the queue's eligible-committed bypass: a younger speculative entry can precede
the head owner's entry in walk-completion order. It also needs an atomic
at-head authorization/retirement hold so an interrupt or fault cannot cancel
an owner after its metadata write has started; multi-uop macros need a
precise last-uop redirect. This route serializes a whole descriptor RMW and
frontend restart at every U/M-producing D walk with younger work. Its cycle
cost must be measured on the MMU first-touch/PFLUSHA workload before calling
it viable for a throughput-focused core. ITLB fetch U writes are born
committed without a ROB owner and need separate ordering analysis whichever
route is chosen.

Neither complete route is approved for RTL by this draft. The queue-bypass
prerequisite is separately authorized and under test; the SQ interlock and LSU
overlay are not. The current SQ and walker ports arbitrate independently. The
apparently small owner-serialization change would deadlock without queue
bypass and a nonblocking walker path.

The queue-bypass prerequisite can be validated independently: four slots are
individually allocated/freed, each newly committed owner receives an ordered
commit stamp, and the oldest committed stamp is offered until terminal ack.
Retirement lanes that repeat the same owner count once. Only a matching new
commit or born-committed allocation advances the stamp counter, so unrelated
ROB retirements cannot invalidate a held old entry's modular age. With at most
four live slots, the stamp width must have modulus greater than eight. A
one-cycle flush may withdraw `drain.valid` as the current interface does, but
the selected slot and payload must reappear unchanged afterward; simultaneous
ack cannot free a replacement allocation. This prerequisite makes physical
ordering possible but does not itself repair the failing descriptor load.

## Validation and cost

The exact failing program must pass with the load still issued before the
physical byte write finishes; adding a test delay does not count. Directed
tests must cover an older committed byte during drain, an older speculative
byte, a younger speculative byte, a queue pop while a read is outstanding,
two-access split loads, a flushed walk, and a software descriptor write that
changes W/PDT without being clobbered. LS out-of-order tests must show that
the owner macro restart catches a younger load that issued before U/M
allocation and that the replay reaches completion. Run `test-fast` before
handoff. Compare OFF/ON cycles for first-touch MMU workloads; a redirect per
new U/M entry is a correctness cost, not a free optimization.

The paired full-ordering gate must also include the existing
`walk_vs_older_store_slow` and `walk_vs_older_store_uncommitted` ported cases.
Both fail in the frozen shipping baseline alongside
`mmu_atc_write_hit_sets_modified`; they exercise the opposite direction,
where a walker descriptor read overtakes an older ordinary software store.
Passing only the M-bit readback case would leave that direction unfixed.
These are acceptance tests for the complete cross-queue change, not for the
standalone U/M queue bypass prerequisite.

For those two cases, the walker's *physical descriptor read* must query older
overlapping SQ bytes before the read enters the D-cache. It may either consume
the SQ's correctly assembled forwarded bytes or wait until those older stores
drain; a single-port walker and a distinct store drain port make a wait
plausible, provided the wait never blocks the SQ's store grant. The query must
compare physical addresses and use the walk's ROB owner for D-side age;
exception-owned and I-side walks have no comparable ROB owner and require a
conservative ordering rule. This is separate from forwarding a *deferred U/M
write* to a younger ordinary program load.

The candidate ring mask is 32 bits per 16-byte cache line (two U/M bits per byte), or
256 state bits at ring depth eight, plus a four-entry physical-byte compare
per queried line and ROB-age qualification. These are pre-synthesis estimates,
not LUT or timing claims. The cross-queue store-ordering invariant above is a
prerequisite, not a deferred cleanup, for claiming this architecture complete.
