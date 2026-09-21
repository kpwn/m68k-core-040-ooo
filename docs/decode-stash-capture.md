# Decode-stash capture-enable experiment

## Evidence and objective

The completed strict 200 MHz AW-predecode comparison has a -0.194 ns path from
slot-1's registered decoded operation to a decoded-stash payload clock enable.
It spans 12 logic levels and 5.053 ns data delay, including 4.008 ns routing.
Its final enable net drives 789 loads and costs 1.074 ns. Source:
`ipc-v2-debug-apply-cleanup/build/vivado/reports/timing_route.rpt`.
This is a concrete late-control/high-fanout target, not a claim that decode is
the only source of global congestion.

The proposed change retains the exact logical stash-valid transitions but
captures the wide slot-1 payload under the early condition `!stashValid`.
All capture arms write the same `a1raw` data. Payload contents while invalid
are not architectural state. No new registers, per-word predecode bits, ports,
pipeline stages or queue entries are proposed.

## Safety conditions

There are five original writers: normal deferred slot 1 and ordinary slot 1
behind a slot-0 MOVEM, FMOVEM.X, microcode or MOVEP entry. Each must imply the
stash was empty before the edge:

- Normal capture follows the `when(stashValid)` consume arm's `elsewhen`.
- Each engine's `EnterSlot0` excludes its pending-entry arm. Its remaining
  slot-0 entry condition requires `!stashValid`.

Validate this implication in simulation at every original writer. Preserve
stash-valid setters, the authoritative final flush clear, all engine entry
conditions, backpressure and replay scheduling. Once valid, the payload must
hold unchanged until consumption or flush. Capture count, all three uops,
FP-immediate pending flag and the 80-bit value together; splitting their
qualification could pair the wrong immediate with a stashed instruction.
The exact-qualification reference and broad-capture candidate must match all
payload bits whenever stash-valid is true. Reset/flush may leave arbitrary
invalid payload, but may never make it observable as a valid instruction.

## Gates

Before changing payload capture, establish the existing decode/crack,
pending-spec and coincident-flush baselines. Add directed non-vacuous coverage
for all five writer types, held-valid backpressure, flush on capture/replay,
and FP-immediate stashes. Compare emitted uops and side-table allocations
cycle-for-cycle, not just final register values. Run the required fast gate
and matched byte-copy IPC windows with `ICACHE_PREDECODE_WORDS=8`, reduced
debug, the same throughput options and L2:5:70 seeds 1/17.

Production RTL must show that the wide stash capture enable no longer depends
on crack count/engine arbitration. Synthesis/routing may still merge it with
other control, so final acceptance needs a strict 200 MHz physical comparison.
Broader invalid-payload activity can increase switching; do not claim a power
reduction. Implementation and verification status are recorded below; physical
benefit remains unmeasured.

## Implementation and validation in progress (2026-09-22)

Parent CPU `3c20c81e`; reserved workspace agent-15, branch
`perf/decode-stash-capture`. The RTL now has one empty-stash payload write
block; the five original valid setters and final flush clear are unchanged.
All functional payload consumers select it only under stash-valid. The raw
`fxDbg.stashN` diagnostic can differ while invalid, as permitted above.

Before editing RTL, the existing four-suite baseline passed 7/7 tests
(`/tmp/ipc-decode-stash-before.log`). The new exact-qualification shadow
test also passed against unchanged RTL. It exercises 10,800 cycles, all five
writers, blocked output, FP word-immediate stashes (the complete 80-bit
payload is compared), and capture/replay-coincident flushes. Long FP immediate
instructions were not admitted in slot 1 by the real frontend; the fixture
uses FADD.W instead of pretending those cases exercised a stash.

Both original and candidate report writers `56,33,34,31,32`, 1,156 held-valid
cycles, 582 FP-stash cycles, 16 side-table allocations, 18 capture flushes and
18 replay flushes. The candidate's qualified shadow comparison passes. An
initial raw-output signature differed. The detailed exact-write versus broad
reference traces have 19,254 rows each: all 6,880 differing rows differ ONLY
in bit 0, `DecodedUop.valid`; there are zero other differences. Generated RTL
shows frontend packet-valid driven as `1'bx`; the stream/second-lane signals
own validity, and `RenameStage` explicitly replaces both payload valid bits.
The test now normalizes exactly this redundant field to stream/lane validity,
as rename does, without masking other fields. The raw traces/diff are retained
in `/tmp/ipc-decode-stash-{exact,broad}-raw-valid-trace.log` and
`/tmp/ipc-decode-stash-raw-valid-trace.diff`. The final runner is restarted
after its first comparison stopped on this diagnostic difference. No production
test switch or extra state was added.

The candidate's five-suite decode regression passed 8/8 tests. The required
fast gate passed 396 tests, with 2 ignored and no failures/aborted suites,
at 00:17:59 CEST and again at 00:21:00 after the test correction. Exact versus
broad valid-output/FP-allocation traces match all 19,254 rows, verified by
`diff` at 00:21:43. Both have SHA-256
`769757b24e23178260ff8efb265f74f0c97bab27d7d5c27f2421393c9bf2d9fa`.
All 16 matched board-copy rows pass unchanged at 00:23:04: retired macros,
cycles/IPC, branches/misses and reserved/published stores match the eight-way
parent exactly for both option profiles, both lengths/readback variants and
seeds 1/17 (`IPC_MEM=l2:5:70`). This models the board's copy-loop dependency
shape, not a measurement of whole-board Dhrystone performance.

Production generation and source-hash checks pass at 00:23:12. The generated
`when_DecodeStage_l319` is simply `!DecodeStage_logic_stashValid`, and the
wide payload assignments use it. No late crack/engine arbitration remains
in that generated enable. This is structural evidence, not routed timing:
Vivado may change placement or merge/replicate logic. Timing closure remains
unproven.

Serialized final gate runner: `/tmp/run-ipc-decode-stash-gates.sh`, service
`m68k-ipc-decode-stash-gates.service`. It requires the broader decode regression,
fast gate, exact-vs-broad cycle trace comparison, all 16 matched board-copy IPC
rows against the eight-way parent, then production generation and source-hash
verification. Logs: `/tmp/ipc-decode-stash-{after,fast,exact-trace,broad-trace,ipc,generation}.log`.
The CPU candidate is ready to pin into a separate strict 200 MHz SoC
comparison after top-level lint. No board access is authorized by this
experiment.
