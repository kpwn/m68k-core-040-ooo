# Prepared retirement experiment

This amendment implements proposal 3 in `ipc-design-options.md`. Production
remains two-wide. The first full-core experiment uses an eight-entry cap, with
four and sixteen as explicit comparison configurations, not production defaults.

While an ordinary ROB head is incomplete, choose the largest currently allocated
ordinary prefix up to the cap that ends at a macro boundary. Freeze that endpoint;
never wait for more allocations to fill it. Branches, faults, RTE, privileged and
system operations terminate the prefix. Trace/debug/interrupt boundaries retain
the ordinary conservative path. The ROB remains the only retirement decision
owner and retains all batch entries until actual publication.

Start when the current allocated window reaches the cap, or rename reports
register-resource pressure. The latter selects the available partial prefix
instead of waiting for allocations that need the batch's eventual reclamation.
Waiting to start never holds ordinary retirement. Once started, preparation
requires no new allocation or execution resource.

Two entries per cycle fold all five committed register maps into one unpublished
shadow image, starting from the current architectural maps. Mapping identity is
known at allocation, so preparation need not wait for execution. Fold in program
order: an older late completion cannot replace a younger mapping. No speculative
preparation may free a physical register, authorize a store/U/M write, advance the
architectural allocation boundary, or change architectural reads.

Ordinary two-wide retirement remains available throughout. A flush or disallowed
debug context cancels preparation. An ordinary head advance consumes the oldest
part of the pending batch without discarding its image: the image already
contains exactly those updates, now also present in the architectural base.
Keep the endpoint fixed, move the batch base with the head and reduce both the
remaining length and preparation cursor by the consumed count. Two-wide
preparation must never fall behind two-wide ordinary retirement. Discard the
image when ordinary retirement reaches/passes its endpoint. Publication is
allowed only after the image is fully registered, the current head still owns
the remaining batch, and every entry is complete and non-faulting under current eligibility
checks. The original prefix may therefore have become partly architectural, but
the unpublished suffix must remain contiguous and owned by the current head.
The precise-store completion guard is unchanged. Publish the complete
map image, all resource records, CCR/PC/system/FP effects, queue authorizations
and observations on the same edge. All architectural readers see that image on
the following cycle. Reset/flush discard unpublished preparation; already
committed frees and stores keep their existing recovery lifetime.

`PreparedCommitService` has one producer, `RenameStage`, owning the optional
begin/abort/two-record preparation/publish interface. `RobPlugin` drives it via
the service. No new Global key or cross-plugin internal access is introduced.
The RATs have two ordinary commit ports in this mode plus one whole-image
publication port; speculative rename still has two ports. Flush wins over a
publication request. The prepared-valid state is separate from architectural
map contents and is cleared on cancellation/publication.

For the initial correctness/IPC prototype, resource and observation lanes scale
to the batch cap using the already-tested same-edge interface. This is not a
claim of an area-efficient final implementation: narrow BRAM-backed reclamation
and queue watermarks remain timing/area refinements if the prototype earns them.
Do not claim reduced area or 200 MHz without measuring those results. The
architectural maps themselves must use the prepared image, not a renamed wide
same-cycle commit path. CCR values are still folded from actual completion data
in program order on publication, including the precise-store live bypass.

The sixteen-lane comparison cannot use the existing XOR-lowered multiwrite
freelist: seventeen writes including initialization exceed the supported read
port count. For that experimental width only, stripe the circular free-ID ring
across sixteen banks. Compacted reclamation writes occupy consecutive addresses,
so at most one write reaches each bank per edge. Multiplex initialization into
the same bank write port. Read selection follows the low pointer bits; pointer,
flush and one-cycle reclamation semantics stay unchanged. Single-entry banks
are registers. This enables a fair IPC comparison, not a claim of BRAM use or
acceptable implementation area; narrower deferred reclamation remains a separate
candidate if bulk publication demonstrates a useful gain.

Compare actual warmed IPC at caps 4/8/16 against normal two-wide and four-wide
retirement on both original and improved LSU/predictor configurations. Record
preparation starts, canceled batches, publication sizes and ordinary fallback
events so a zero effect cannot conceal an unexercised mechanism. Test WAW chains,
all map classes, late flags, faults/barriers at each position, canceled/reused
identities, immediate post-publication flush, queue ownership, macro boundaries,
interrupts, A7, debug stops and reset. Keep the production default unchanged.
