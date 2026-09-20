# Registered physical-register reclamation

Architectural commit remains two-wide and strictly in order. Committed RAT
updates and the freelist's committed-allocation pointer advance on the original
commit edge. Only recycling the old physical register is delayed by one cycle.
This supersedes the same-edge push description in the original rename spec.

Each of the integer, NZVC, X, FP and FPCC freelists holds two registered
`valid/id` reclamation lanes. The stage accepts two lanes every cycle and drains
two lanes every cycle without backpressure. No architectural destination or
commit order changes. Sparse lanes are compacted when written to the free ring.

The ring tail and available count advance when the registered frees drain, not
when commit occurs. The committed head advances immediately, so recovery never
reclaims a newly committed destination. With P pending frees and S speculative
allocations, `available + P + S = physCount - archCount`. This reduces the
temporarily available pool by at most two registers per class.

A flush forbids new commits/pops (existing ROB contract), but **must drain the
previous cycle's committed frees**, including their RAM writes and tail update.
The head restores to the immediate committed head and the available count becomes
`physCount - archCount`; pending lanes are now empty. Repeated flushes do not
duplicate frees. Reset clears lane valids and reinitializes the ring/RAT together.
WAW chains preserve lane order, including freeing slot 0's new destination when
slot 1 commits a newer mapping in the same cycle.

The intended timing cut is commit/ROB payload and valid -> reclamation registers
-> push compaction, ring address/data writes, tail and available-count updates.
The small committed-head increment still depends on current commit valids, and
committed RAT writes are unchanged. This is not a measured Fmax improvement.
Added cost is two ID/valid registers per class; reuse latency increases one cycle.

## Proposed follow-up: prepared bulk retirement

Status: investigation requested, not implemented. This is distinct from the
registered two-lane reclamation above and from a PNR safety frontier.

The proposed side buffer prepares a contiguous retirement batch while its oldest
instruction is blocked. Fold destination mappings in program order into a shadow
architectural map, retaining the last writer to each architectural register.
Mappings can be prepared before values exist: a late older result must not
overwrite a younger mapping. Track completion and exception obligations for
every member, plus the final macro-boundary PC/flags state and store boundary.

Once the entire batch is complete and safe, publish its prepared map and advance
the architectural/ROB boundary together. A bank switch or registered prepared
update could avoid a wide same-cycle scan and many committed-RAT write ports.
Physical values remain in PRFs; this is not copying all register data at once.
Retirement would still publish a program-order prefix, but potentially more than
two entries on a publication edge. Until publication, the batch is discardable.

The design must retain precise faults, 68040 macro boundaries, interrupts,
single-step/trace and debug recovery. Stores stay speculative until publication;
their later drain remains ordered. Physical-register reclamation records must
remain lossless and must not free any checkpoint-visible or live source. The
existing two-lane, one-cycle free stage cannot absorb a bulk publication without
new buffering and a revised recovery-accounting contract. Keeping ROB entries
until publication initially avoids inventing a second recovery structure merely
to reclaim ROB space speculatively.

Measure separately (1) head-blocked time, (2) the completed backlog behind head,
and (3) post-unblock cycles actually limited by two-wide retirement or ROB-full
dispatch. Bulk publication only attacks the latter bottleneck; it does not
complete the missing head result or eliminate PRF/SQ capacity requirements.
Compare with wider ordinary retirement using matched IPC, resource occupancy,
timing and area. No benefit is claimed before these measurements.

Related primary research: [Checkpoint Processing and Recovery (MICRO 2003)](https://www.microarch.org/micro36/html/pdf/akkary-CheckpointProcessing.pdf)
studies checkpoint-based bulk retirement and register reclamation. The proposal
here is a smaller ROB-preserving experiment, not an implementation of that paper.
