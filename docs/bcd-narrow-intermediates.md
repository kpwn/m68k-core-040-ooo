# Ten-bit BCD intermediates

## Target and invariant

The AW-cleanup routed ALU-op -> ROB NZVC path misses by 0.190 ns through BCD
`sAdj` / `sFull`, with 14 logic levels. Keep its existing arithmetic, opcode
selection, S1 latency, issue/wakeup/writeback and byte merge. Reduce only the
unsigned intermediate width from 32 to 10. No new registers, tables, metadata,
ports or Global keys; no sharing or extra pipeline stage. Both ALU copies use
the same bounded arithmetic. The physical saving is an experiment: synthesis
may already prune some of the redundant upper bits.

## Bounds, including non-decimal input digits

For all byte operands and X in {0,1}, addition's low sum is 0..31; after decimal
adjust it is 0..37, and the high-nibble addition produces 0..517. Ten bits
cannot wrap this positive range. Nine bits can and is incorrect (e.g. FA+FF+X).
Subtraction's low difference is -16..15; after correction it is -22..9, and
the high-nibble difference produces -262..249. Negative values represented
modulo 1024 are 762..1023, all greater than 0x99, just as their modulo-2^32
representatives are. Likewise negative low differences still compare >9.
The final correction and V/N only require the low eight bits; modulo 1024 and
modulo 2^32 preserve them equally. Clear-only Z and decimal C/X are unchanged.
NBCD uses dx=0 and is included in the subtraction domain.

This is not permission to mask intermediates to a byte/nibble or to discard
the host oracle's N/V behavior. The former wide-lane algorithm remains the
test reference. Update the original BCD design's >=9-bit recommendation to
the proven >=10-bit requirement before implementation.

## Validation

Reserved agent-07, parent CPU 76c00f70, branch `perf/bcd-narrow`. Test-only ALU
subclass injects S1 operands/flags but observes the actual unmodified production
arithmetic and final byte/flag muxes. Sweep 524288 add/sub operand-X-Z cases
plus 1024 NBCD cases, comparing result including upper-byte preservation and
all NZVCX bits with the original unsigned-32 software equations. Run before
and after narrowing. Existing decode, fast/slow ALU and BCD register/memory
lock-step tests cover the unchanged issue/PRF/decode paths independently.
Required fast gate, 16 matched IPC rows, production generation, SoC lint and
serialized strict 200 MHz implementation follow; no automatic board operation.

## Focused evidence

Original 32-bit and candidate 10-bit production ALU cones both pass all
525312 result/NZVCX comparisons, including the upper-byte merge. All 20 focused
tests pass before/after. A nine-bit RTL mutation fails at dx=250, dy=255, X=1,
old-Z=0, addition, matching the independently enumerated model counterexample.
Ten bits was restored and all 20 tests passed again. Logs:
`/tmp/ipc-bcd-narrow-{before,after,ninebit-mutant,restored}.log`.

The first baseline run exposed invalid ROB IDs 40..50 in the existing slow-ALU
flush fixture; its port is five bits. Test-only commit 69e910da uses distinct
legal IDs 8..12, preserving the same killed-token and destination-reuse checks.
No production interface was widened. The original failed run is archived as
`/tmp/ipc-bcd-narrow-before-invalid-robids.log`.

The continuing gate runner `/tmp/run-ipc-bcd-narrow-gates.sh` adds all BCD
lock-step cases, including NBCD register/CCR checks, then required fast tests,
matched IPC and production generation. Its source hash includes production
RTL and all changed tests. Physical timing/area benefit remains unproven.
