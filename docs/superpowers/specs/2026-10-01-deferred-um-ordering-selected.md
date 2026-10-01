# Deferred U/M ordering: selected implementation contract

Status: selected architecture for the D-side descriptor-visibility correction.
This supersedes the route comparison in
`2026-10-01-deferred-um-read-visibility.md`; its measured trace and acceptance
tests remain applicable. The queue bypass prerequisite is implemented by
`UmWriteQueue`, but none of the cross-plugin rules below are implemented yet.
The core architecture document's non-speculative descriptor-write rule is
retained. This specification changes when those writes are ordered with
ordinary memory operations and allows at-head authorization for a precise
store. Services, rather than references to another plugin's implementation
area, carry every new cross-plugin signal. No new `Global` key is required.

## Observable order

For successful accesses, a load of physical descriptor bytes observes every
architecturally older U/M update and software store to those bytes, in that
order, plus the U/M update required by that load's own translation. A later
software descriptor store may clear a previously set U/M bit;
an older deferred OR must not resurrect it. A later U/M update may set that
bit again. A wrong-path update never reaches memory, and a TLB fill whose
required update was discarded cannot remain usable. These requirements hold
for an ordinary program load, a DTLB descriptor read, and a two-page split.
`mmu_atc_write_hit_sets_modified`, `walk_vs_older_store_slow`, and
`walk_vs_older_store_uncommitted` are mandatory exact regressions. An
arbitrary delay in a test is not a repair.

The MC68040 User's Manual, pages 3-14 and 3-27, says that a clear U bit is
updated before page access and a write with clear ATC M suspends until its
descriptor M bit is set, then retries the original write. The core can retain
deferred, non-speculative writes while giving younger internal reads the same
ordered value via forwarding or replay. A *precise or device store* must not
start its externally visible data transfer before its own U/M update finishes.
At the ROB head, with IRQ/trace/debug preemption excluded, it authorizes its
metadata entry irrevocably. The metadata read/OR/store finishes first; only
then does the existing D4 fence wait for D-side quiescence and launch the data
store. Keep the owner in the ROB through both phases. Once authorized, hold
preemption until either the data transfer completes or a metadata fault is
reported against this owner. On metadata read/write error, suppress the data
store and return a precise fault; do not silently discard the update. The
current diagnostic-only behavior for an error on a deferred update *after a
nonprecise owner retired* is a pre-existing bus-error fidelity limitation and
is not claimed fixed by this ordering change.

For a fast store, its own U/M entry becomes committed on retirement, as today.
The SQ store, committed on that same edge, is held behind that metadata write.
Thus metadata precedes the same instruction's data in both precise and fast
postures. A descriptor-self-write test must include software clearing M in
the value it stores: the store's own metadata OR occurs first, and the later
software value wins. The at-head authorization, rather than waiting for the
store to retire, prevents a precise-store/metadata circular wait.

## D-side walk against older stores and metadata

The DTLB's physical descriptor command checks the SQ before entering the
registered `walkLoadCmd.m2sPipe`. If any older resident SQ store overlaps the
command's physical bytes, deny that command until the store's *terminal ack*;
the cache must not accept a stale read while the store is merely offered or
accepted. Include both split-store halves and actual byte strobes. A full
forward into the walker is an optional later optimization. IQ's present-store
barrier ensures every older store has issued and allocated to SQ before a
younger load may reach this check; see `IssueQueuePlugin`'s
`olderStorePresent` rule. An ordinary store already in SQ has completed its
own translation, so a younger walker waiting on its drain cannot block that
older store's translation.

Likewise, a DTLB descriptor command waits for overlapping older pending D-side
U/M bytes to drain. It need not hold unrelated walks. The U/M drain must be
able to preempt a walker *between* descriptor reads: expose TableWalker's
`cmdSent` as `readOutstanding`, including a command accepted into the
`m2sPipe` but not yet accepted by Dcache. Arm the U/M re-read whenever no
walker read is outstanding, even if the walker FSM is busy; hold the walker's
command ready low while U/M owns the request and response. If a walker read
was already accepted, let its single response return before preemption.
`DcachePlugin`'s nonblocking `serialPending` admits only one `ooOk=false`
walker/U/M command through its response, so there is still exactly one DTLB
response owner at a time. Assert this and test an error response and the last
byte of a split descriptor. Do not infer two concurrent serial cache requests
from the LS-EU's response FIFO capacity.

When an older owner allocates U/M *after* a younger walk or load read, its
retirement requests the existing macro-boundary order redirect. All younger
work re-executes after the owner retires. Invalidate DTLB ATC entries on this
redirect, and whenever a backend flush discards speculative queued U/M, so a
younger stale fill cannot claim U/M is already set and suppress the rewalk.
This may conservatively clear older correct ATC entries; their descriptor
writes are retained and a later walk recreates them. Measure redirect and
ATC-refill frequency on first-touch workloads.

## Ordinary loads and SQ forwarding

At each Dcache request admission, query both U/M queues by *physical byte*
address. Include all committed D-side entries, speculative D-side entries
whose ROB owner is older than the querying load, **and that load's own
translation entries**, plus ownerless I-side entries ordered before the
request by the epoch below. The own-entry
exception is required when the translated page maps page-table memory: the
walk sets U in the exact byte returned by the same load, before that access
architecturally occurs. A speculative younger owner's entry is excluded. OR
only bits `0x18`. Capture
the mask with that load's aligned-ring context, including both physical halves
of a split access; apply it to the response bytes before extraction, merge,
writeback, or wakeup. Querying only when the response returns loses an update
that popped while the read was outstanding. A subsequently allocated older
entry is handled by the owner redirect above.

An exact full SQ forward is a software-store value, so an older U/M OR must
not blindly modify it. If it overlaps any pending U/M entry from an **older
owner**, conservatively stall and re-query after those updates drain. This
also covers the reverse order (SQ store older than U/M) without adding an age
tag to the SQ forward result. In contrast, the querying load's own U/M entry
is logically after every older SQ store: apply its overlapping U/M bits to
the fully forwarded software value. Never wait for that entry to commit, or
a descriptor self-read deadlocks at its own retirement. The existing
partial-forward stall still waits for its SQ entries; a later cache read
receives the captured older-plus-own U/M mask. The P4 park and immediate
younger replay must leave older owners and both drain ports able to run while
a descriptor load waits.

## Ordering D-side metadata against SQ drains

On each normal U/M owner's retirement lane, record the terminal SQ-ack epoch
plus the number of *resident committed* older SQ entries, including entries
with accepted halves awaiting final ack and stores retired in earlier lanes
of that edge. A simultaneous SQ ack counts as completed on the next edge;
the snapshot target must give the same result with or without that collision.
The SQ is FIFO for stores, so this snapshot is an older prefix. Do not include
a store retired in a later lane. A store belonging to the same owner follows
its U/M entry, except that a precise store's early metadata authorization
establishes this order before its own retirement.

Let that captured older SQ prefix finish. Then prioritize the oldest eligible
U/M entry through re-read, merged store, and terminal ack. Do not present a
newer SQ stream offer until the metadata ack; an already presented SQ offer
must remain valid and finish before the new metadata grant. The SQ needs an
intent/grant split with a latched offered state, so the grant cannot withdraw
`Stream.valid`. The U/M queue's committed bypass allows this drain even if a
younger speculative walk allocated an earlier physical slot. Since at most
SQ depth eight older resident entries can drain while the U/M entry waits,
the SQ terminal-ack target can use four modular bits; assert the bounded span
and test ack/commit/offer collisions. If the oldest U/M waits for older SQ,
the SQ grant applies only to that older prefix. Younger SQ cannot get ahead.

The DTLB U/M re-read uses the same physical older-SQ overlap rule as a normal
walker read, using the *offered U/M owner's* age, not the current walker's
robId. This prevents an older software descriptor store from being overwritten
by a read/OR/write based on its previous value. Queue selection remains stable
across offer, flush-valid withdrawal, and ack.

## Ownerless I-side and exception walks

ITLB U/M writes and exception-owned DTLB walks have no comparable ordinary
ROB owner. Assign each such walk a registered physical epoch relative to SQ
drains. On an epoch request, snapshot the already-committed SQ prefix, keep
already offered/accepted stores valid through terminal ack, and stop *new*
SQ offers past that prefix. Grant the ownerless walk after the prefix drains;
hold later SQ offers until its descriptor read(s) and resulting U/M writes
finish. If an SQ offer or inhibited D4 transfer won first, let it finish and
grant the epoch afterward. This gives software descriptor clears and U/M ORs
a deterministic physical order without treating an uncommitted younger store
as an older dependency. The explicit serializing ATC invalidation remains the
software synchronization point for page-table rewrites that preceded fetch.

The D4 sequence is: metadata/ownerless prerequisite, then D4 walker fence and
bus-quiescence wait, then inhibited data transfer. `pendingAtHead` must not
fence the very metadata walk/drain that the head store awaits. Once the
prerequisite releases, the existing D4 fence runs unchanged through transfer
completion. Conversely a new ownerless epoch cannot start after D4 already
owns the fence. Assert a single registered grant winner at the crossing.
Exception entry's already-required SQ drain is part of its epoch prefix; its
walk and metadata must remain grantable while `excActive` owns the frontend.

## Credit, state, and validation bounds

Move DTLB U/M credit reservation from `walker.io.start` to translation miss
*capture*. With the **current leaf-only U/M producer**, a four-slot queue
reserves two credits for the current ROB-head uop's at-most-two page-crossing
walks. A non-head miss may capture only when more than two credits are free;
a head miss can use either reserved credit.
The single walker is then guaranteed its allocation slot. If a younger P4
translation is denied and an older LS op is upstream, request immediate P4
replay/backout before DTLB captures anything; the 255-cycle watchdog is not a
normal progress mechanism. A non-head access with no older LS op can wait
until it becomes the ROB head. Exception-owned admission follows the
exception epoch after younger speculative entries are flushed. Measure the
two-credit restriction; depth eight with the same two-credit reserve is an
optional area/throughput comparison, not an assumed fix.

This two-credit bound is *not* a claim of complete MC68040 U-bit behavior.
`TableWalker` currently emits only the final page descriptor's U/M entry;
it neither emits root- nor pointer-table U. The manual (§3.2.2.1, §3.2.5)
requires U in **each** encountered table descriptor before page access. A
complete three-level producer must emit as many as three U updates per walk,
including root and pointer, on both D- and I-side walkers; a two-page split
can demand six entries from one uop. That extension needs a bounded multi-entry
walker response, admission reservation for all six before the first walk,
and an eight-slot D U/M queue at minimum. A second walk may encounter a
descriptor whose U is already pending from the first; merging or duplicate
reservation must preserve the same six-entry worst-case guarantee. The
present leaf-only visibility correction must not be represented as closing
this separate architectural gap. Before enabling full 040 U behavior, size
and measure the expanded queues, including ownerless I-side updates, and
replace this paragraph's two-credit rule with the six-credit rule.

Worst-case incremental storage before synthesis: two 32-bit per-line U/M
masks for each of eight aligned response slots = 512 bits (a request-byte
encoding could reduce this to 128); four 4-bit D U/M SQ-target epochs plus a
4-bit SQ ack counter = 20 bits; a few ownerless grant/offer and credit bits.
The load query compares up to eight I+D U/M physical-byte entries against
two requested halves (16 address compares); the walker SQ query checks up
to eight resident stores against its physical descriptor bytes. These are
logical counts, not LUT, frequency, or post-route claims. Keep queries off
the IQ issue-select path and register the mask on Dcache admission.

Directed proof must cover: the three named baseline failures; two U/M writes
with a software clear between them; same-owner precise inhibited descriptor
self-write; a translated LOAD of its own leaf-descriptor byte with clear U,
a fully forwarded older SQ descriptor value plus the load's own U, and a
split partial access whose second physical half overlaps its own descriptor
update. Full three-level U support additionally requires root/pointer U on
both D/I walks, same-owner pointer-table access, and six-entry split pressure.
Cover IRQ during metadata
authorization, metadata read/write fault;
younger SQ already offered when U/M commits; simultaneous SQ ack plus commit;
walker blocked by older SQ while older U/M preempts between reads; response
attribution after descriptor fault and split last byte; four younger pending
updates versus oldest split walk with bounded immediate replay; wrong-path
U/M kill plus ATC refetch; D4/ownerless grant in both orders. Run the exact
paired OFF/ON corpus and lockstep gates after focused tests, and compare
first-touch throughput and route timing before enabling by default.
