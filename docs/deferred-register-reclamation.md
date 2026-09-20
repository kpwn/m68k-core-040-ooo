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
