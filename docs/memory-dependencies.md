# Conservative memory-dependency tracking

Status: implementation in progress; not enabled in the CPU or a board build.
Base: 92184a7a (optional IPC diagnostics plus deferred register reclamation).
This amends the historical architecture and LS-pipeline specifications where
they require oldest-occupied-LS-only selection. Architectural retirement remains
strictly in order. No load-value prediction or memory-order violation rollback
is introduced by this change.

## Required ownership and lifetime

Memory operations must be represented before they can be overtaken: reserve a
memory-order record at dispatch, atomically with ROB/IQ allocation. A store's
address readiness and data readiness are independent. Address generation and
translation must be allowed without waiting for store data. Publication of an
address includes both translated fragments and byte masks for a split access;
one translated half is not a resolved store address.

Records and delayed responses use allocation identities, not bare wrapping ROB
indices. Committed stores retain their identities until their writes finish.
Flush cancels speculative records and responses but preserves already-committed
or irrevocably-launched stores. A pending old response cannot update a reused
record. The old physical store-data register must not be recycled before its
data has been captured.

## Conservative load permission

The dependency checker consumes a snapshot of older live memory records and a
fully translated load. Compare physical 16-byte line tags and byte masks, not
virtual addresses or starting addresses alone. Both fragments participate.

1. Any older unresolved address/attribute record blocks the load.
2. Any older serializing record blocks the load. A serializing load itself
   waits for all older records and for commit-side authorization.
3. With no older store byte overlap, a cacheable load may access the cache.
4. A known overlapping store with unavailable data blocks the load.
5. Initial implementation conservatively stalls overlapping loads. Existing SQ
   forwarding may be used only through an explicitly verified forwarding path;
   the checker must never turn an overlap into a cache-read permission.

This initially forgoes multi-store byte merging and speculative execution past
unknown store addresses. Those are separate optimizations, not prerequisites
for bypassing known-disjoint stores.

## Progress is part of correctness

Do not simply replace `ohLoldest & lsReady` with oldest-ready selection. An
overtaking load must not park in the existing single P4/front FIFO while the
older store it waits for is outside that pipeline. Failed permission must
return the load to a pending/retry structure and release the shared front.
There must always be capacity/admission for the oldest unresolved operation.

Cache-inhibited, locked/RMW, maintenance, exception-service, split and
translation-fault handling must be audited individually before enabling bypass.
An address probe must not emit a memory transaction, architectural completion,
flag write, or register-data wakeup. Faults from probes remain attached to the
original instruction and are delivered at its ordinary precise boundary.

## Boundaries and timing

Use typed services for dispatch reservation, address/data resolution, query,
retry/completion and flush. No new Global keys are required. The eventual table
plugin is the sole producer of memory-order state; the LS EU owns AGU/translation
and memory transactions; the IQ owns operand readiness and selection.

Physical-byte overlap checks terminate at a registered admission/retry boundary,
not the existing IQ select/wakeup cone. Report the added lookup latency and
measure routed Fmax before claiming a performance gain. Keep the strict path
available until full-core differential tests pass.

## Gates

Directed tests cover unknown store addresses, delayed store data, non-overlap,
same-byte and partial overlap, physical synonyms, both independently translated
split fragments, serializing accesses, flush with outstanding probes, allocation
reuse, precise faults and queue saturation. A fixed-seed reference model checks
the permission predicate independently using physical byte sets.

Integration tests must show a younger disjoint cacheable load actually finishing
before an older data-waiting store, and show that an overlapping/inhibited load
does not launch. They must also prove oldest-operation progress with a full retry
structure. A standalone predicate test is not proof of CPU integration.

## Implementation boundary

`MemoryDependencyCheck` and `MemoryDependencyTracker` are standalone RTL blocks;
no CPU plugin instantiates them yet. The tracker supports atomic one/two-entry
reservation, independent address/data publication, two commit/release notices,
and flush cancellation. Tickets have a slot and 32-bit allocation generation.
Generation matching rejects stale updates; the owner must still drain outstanding
responses before releasing a slot. Committed and irrevocable records precede new
speculative accesses even after their original ROB IDs have been reused.
Hard reset clears allocation generations too: the integrating reset protocol must
quiesce/reset response producers before accepting new reservations. Tickets do
not make a partial reset with surviving old responses safe. Address/data notices
refer to an existing reservation, not one allocating on that same clock edge.

Verification includes 4,096 fixed-seed physical-byte comparisons and 2,048
fixed-seed lifecycle transitions, plus directed split, overlap, saturation,
commit-with-flush, stale-ticket, ROB-wrap, irreversible and reset cases.

Remaining CPU integration, in dependency order:

1. Add a table-owning plugin/service and atomic reservation to `DispatchPlugin`.
   Carry allocation tickets through IQ cold payload and every LSU transaction.
2. Split store address readiness from store data readiness in `IssueQueuePlugin`
   without publishing completion/wakeup or freeing a source too early. Keep
   locked, maintenance, exception and other unsupported operations serial.
3. Add dispatch-reserved pending/retry capacity to `LsEuPlugin`. An unresolved
   younger load must release the shared front, while the oldest operation always
   retains a path to address generation, completion and draining.
4. Publish both physical fragments and final attributes; connect SQ drain,
   precise-store completion, translation fault and flush response lifetimes.
   Enable known-disjoint load bypass only after the integration tests above pass.

The existing strict issue path remains unchanged. These standalone tests imply
neither an IPC improvement nor a timing/area result for an integrated core.
