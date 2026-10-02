# Optional direct load response from a refill beat

The default D-cache path accepts a 16-byte cacheable refill beat, installs the
line, enters REPLAY, and then re-reads the line before responding to the load.
`fillForward` removes the reread but still responds in REPLAY. The optional
`directRefillResponse` experiment responds from the accepted AXI R beat itself.
It is disabled by default and applies only to unsectored, cacheable load misses.
The shipping build can opt in with `CPU_DCACHE_DIRECT_REFILL=1`.

The response retains the latched miss token and request ID. An AXI error is
reported as the same physical bus fault and does not allocate a line. Inhibited
reads, drain-miss stores, multi-hot fail-safe misses, and sectored fills keep
their existing paths. If a hit-under-miss response already occupies the single
D-cache response Flow, RREADY stays low and the AXI beat waits. The following
REPLAY cycle clears the miss barrier without replying again. Array allocation
remains on the accepted beat, under the existing refill/store write interlock.

The intended latency saving is the response-after-refill stage. The candidate
adds an AXI-R-to-LS-completion combinational path, so it is not eligible for a
shipping default until directed fault/collision/replay checks, lockstep, and a
matched timing result pass. `BoardMembenchSimSpec` can compare it with
`MB_DIRECT_REFILL=0` and `MB_DIRECT_REFILL=1` under the same memory model.

The directed D-cache simulation now covers successful and DECERR refills racing
a younger resident hit. It checks that the shared response port stalls AXI R,
that the held beat stays stable, and that each token responds exactly once with
the correct data or fault. The `test-fast` gate and full-core lockstep/timing
gates remain separate.

In the calibrated core-only `l2:6:33:4096` chase (2 KiB L1-resident and 64 KiB
L1-missing rings, three laps), baseline is 9.000/21.009 cycles per hop. Existing
fill-forward is 9.000/19.012. Direct refill is 9.000/18.024, with or without
fill-forward. This is a roughly three-cycle reduction for modeled L2 hits and
zero resident-hit reduction. The model connects the D-cache directly to memory;
it does not include the SoC crossbar or physical L2 pipeline. The requested
L1 ≤6 and L2-minus-L1 ≤6 targets remain unmet in this result.
