# Conservative memory-dependency tracking

Status: implementation in progress; not enabled in the CPU or a board build.
Base: 92184a7a (optional IPC diagnostics plus deferred register reclamation).
This amends the historical architecture and LS-pipeline specifications where
they require oldest-occupied-LS-only selection. Architectural retirement remains
strictly in order. No load-value prediction or memory-order violation rollback
is introduced by this change.

## Execution and retirement separation

Ordinary cacheable stores execute in the speculative domain: resolve translation
and permissions, capture address/byte mask/data in the SQ, and expose the resident
entry to younger-load forwarding on the next cycle. They do not wait to become
ROB head. In-order retirement authorizes draining; draining into D-cache may
follow later while the entry remains resident and forwardable. Backpressure is
an SQ-capacity condition, not a reason to put D-cache acceptance on retirement's
critical path. Architectural faults must be resolved before irreversible commit;
device accesses and other precise/irreversible operations retain separate rules.

Load/SQ ordering queries should use stable allocation-age metadata rather than
combinational distances from the live ROB head. Wrap, flush, ROB-slot reuse and
committed-but-undrained stores must be covered explicitly; a bare signed compare
of existing wrapping ROB indices is not sufficient. Already committed or
irrevocable stores remain older than speculative queries even across reuse.
The tracker records pairwise allocation order, independently of ROB IDs and the
moving ROB head. For N slots this requires N*(N-1)/2 bits: allocate rewrites only
the relations involving the new slots, with lane 0 preceding lane 1. Release
does not reorder survivors; reuse overwrites the reused slot's relations before
it can be queried. Occupancy and cancellation still qualify every comparison.
This removes the head-relative subtractors and avoids a wrapping age counter
whose oldest live record could survive indefinitely. At eight entries the order
state is 28 bits, not a duplicated full 64-bit relation matrix.

### Reservation-backed early store readiness

Separate SQ capacity reservation from payload publication. Reserving a slot
consumes a credit and gives the store an allocation identity; the reserved slot
is not a forwarding hit until address, attributes, byte coverage and data have
been published. Capacity accounting includes reserved-but-unfilled entries.
Flush cancels speculative reservations without reclaiming committed/irrevocable
entries, and a delayed publication cannot fill a reused reservation.

An early memory-dependency wake may announce a guaranteed future publication:
reserve capacity at least one stage before filling, resolve translation and
permissions, and ensure the load cannot reach forwarding lookup before the
promised fill edge. If store data arrives through an already-guaranteed PRF
writeback, its read/capture edge must be included in this timing proof. This is
not a prediction of translation success, operand arrival or queue readiness.

SQ capacity alone is insufficient in the current LSU: P3 fast-store allocation
also waits when an older completion wins the shared completion port. The
integrated path must decouple payload publication from that arbitration using
reserved completion-buffer capacity, or explicitly reserve the completion slot
too. An announced wake may not be revoked merely because that port became busy.
Flush must squash the announcement and its speculative consumers coherently;
inhibited/precise and unsupported split paths retain their existing handling.

Required tests include full SQ with reservations, simultaneous reserve/fill/drain,
older completion collisions, producer-data backpressure, translation faults,
flush on each reservation/publication edge, and stale identity rejection. Assert
that every live early-wake promise publishes the correct store by its deadline,
and that no dependent load reads cache while that promised store is unresolved.
This reservation-backed path is a design requirement, not integrated RTL yet.

Reference checked against local NaxRiscv revision
`9f452d50560d02fb391bc8039f5453c54e0911af`,
`src/main/scala/naxriscv/lsu/LsuPlugin.scala`: `store.allocate` reserves SQ
identities at dispatch, independently of address and store-data readiness.
`store.readData.arbitration` sets `loadedAhead` before the following read stage
writes SQ data memory; `load.sqWakes` uses translated-address plus that ahead
bit to release waiting loads. A separate registered `loadedDone` qualifies
later consumers requiring completed data. The transferable mechanism is the
separation of capacity, promised readiness and actual data availability, not
NaxRiscv's speculative memory-order rollback policy.

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

### Optional resident-SQ subword forwarding

Before memory-order-table integration, `sqSubwordForwarding` may independently
enable byte/word forwarding from a naturally aligned, non-split LONG store.
Both accesses must lie in the same physical four-byte word; a word load at byte
offset 3 is excluded. Select the youngest older overlapping entry first. Only
that entry may supply the result: a younger partial overwrite still stalls.
Extract the requested bytes in 68040 big-endian order and return them in the low
bits, leaving the LSU's existing sign/partial-register handling unchanged.

Device barriers, age qualification, commit/drain, flush and all split accesses
retain their existing rules. This is address-verified forwarding of data already
captured in the SQ, not PRF renaming or speculative issue past unknown addresses.
The option defaults off pending matched IPC, correctness and routed timing gates.

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

### Dispatch ownership step

`MemoryOrderPlugin` is the sole owner of the dependency table and its typed
service. `DispatchPlugin` optionally consumes that service and reserves records
on the **same** fire as ROB/IQ allocation. Only LS-cluster LOAD/STORE operations
reserve; an isolated memory operation in lane 1 is compacted into reservation
lane 0 while retaining its real ROB ID. Two memory operations preserve program
order. No reservation is consumed when rename is invalid, either destination
is blocked, the table is full, or a flush is active. Non-memory pairs need no
table capacity but must still respect flush cancellation.

This step is verified with the real dispatch plugin and table plugin. It is not
enabled in a full CPU until LSU publication, release, cancellation-drain and
retry ownership are connected; merely reserving records is not memory
disambiguation. The future LSU uses tickets for all delayed notices. No new
Global key or plugin-internal cross-reference is authorized by this step.

`MemoryOrderPlugin` now instantiates `MemoryDependencyTracker` and its checker;
no full-core builder installs that plugin yet. The tracker supports atomic one/two-entry
reservation, independent address/data publication, two commit/release notices,
and flush cancellation. Tickets have a slot and 32-bit allocation generation.
Generation matching rejects stale updates; the owner must still drain outstanding
responses before releasing a slot. Committed and irrevocable records precede new
speculative accesses even after their original ROB IDs have been reused.
Hard reset clears allocation generations too: the integrating reset protocol must
quiesce/reset response producers before accepting new reservations. Tickets do
not make a partial reset with surviving old responses safe. Address/data notices
refer to an existing reservation, not one allocating on that same clock edge.

Verification includes 4,096 fixed-seed physical-byte comparisons and 6,144
fixed-seed lifecycle transitions across 2/4/8-entry tables, plus directed split, overlap, saturation,
commit-with-flush, stale-ticket, ROB-wrap, irreversible and reset cases.

Remaining CPU integration, in dependency order:

1. Connect the table-owning plugin/service and atomic dispatch reservation to the
   full CPU. Carry allocation tickets through IQ cold payload and every LSU
   transaction, or specify a lifetime-safe lookup at the initial issue boundary
   before carrying tickets through every delayed transaction. The latter must
   prove no bare-ROB-ID lookup can bind a late response to a reused allocation.
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
