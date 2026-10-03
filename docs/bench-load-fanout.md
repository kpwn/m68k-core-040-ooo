# Load-result fanout microbenchmark

Run `MB_ONLY=loadfanout IPC_MEM=l2:5:70 tools/run_microbench.sh` to measure both
footprints. With the default zero-latency external memory model, the L2 case is
omitted because it cannot represent an L2 hit. The harness measures simulation
cycles between macro commits; the first full pass warms the cache hierarchy and
is excluded. Each reported row divides cycles for passes 2–4 by the fixed number
of retired loads in those passes.

Each inner iteration issues four loads from separate streams. The L1D case has
an 8-byte stride and a 4 KiB footprint; the L2 case has a 64-byte stride and a
32 KiB footprint. Both retire exactly 128 iterations per pass with the same
instruction count and loop structure. The stride is the only instruction
immediate that changes.
Every loaded word feeds three ALU results, and all three results contribute to
one of two running checksums. The next
addresses come from address-register increments, and the loop branch comes from
the independent `a6` counter and an explicit `cmpa`; neither waits for a loaded
word. The source memory contains nonconstant values determined by lane and line.
The test checks all four final loaded values, the final ALU results, and both
checksums over *every* word. It also checks that all expected cache lines were
visited. The 4 KiB set fits L1D, while the 32 KiB set exceeds L1D and fits the
memory model's L2 after warmup.

The result is steady-state **cycles per load for this entire loop**, not the
latency of one load. It includes instruction delivery, branch prediction, ALU
throughput, checksum recurrences, load issue, cache misses, and memory-model
backpressure. Compare it with the existing pure pointer chase and independent
load tests to see whether this practical amount of independent work hides much
of load-to-use latency. The checksum chains make the arithmetic meaningful but
may themselves limit throughput; a faster result is evidence of overlap, while a
slower result alone does not identify the limiting structure. The L2 model has
unbounded capacity and calibrated hit timing; it is not a board measurement.

## Compact next-iteration variant

Run `MB_ONLY=loadcompact MB_SEEDS=2 IPC_MEM=l2:5:70 tools/run_microbench.sh`
for a loop whose body is 19 macro-instructions: two loads, six consumer
instructions per load (three destination initializations and three ALU uses),
two pointer increments, and three counter/branch instructions. The branch uses
`cmpa` on the separate `a6` counter. A 32-entry ROB can therefore hold the next
iteration's two loads before this iteration retires. Both cases execute 128
iterations per pass: 8-byte strides give a 2 KiB L1D footprint and 64-byte
strides span 16 KiB. The latter visit eight lines per populated L1D set, more
than its four ways, so repeated passes miss L1D and hit the model L2. Pass 1 is
excluded, leaving 7,311 measured
macros and 768 measured loads in either case.

The complete commit trace checks every load and every initialized ALU result in
all four passes against the preloaded nonconstant source words. There is no
running checksum, so the only loop-carried register dependencies are the address
increments and branch counter. The same ALU work and branch count at both
footprints let their cycles/load be compared directly. The result still includes
frontend, issue, ROB and retire costs; it does not by itself identify one
bottleneck or guarantee that the next iteration actually issued early.
