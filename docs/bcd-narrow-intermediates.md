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
The legacy NBCD implementation used dx=0 and is included in that equivalence
domain; the later NBCD correction below intentionally supersedes that behavior.

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

## NBCD correction and removal of early opcode mux

The new full-core NBCD check fails on both 10-bit and original 32-bit RTL:
NBCD of zero with CCR=4 returns CCR=4 versus oracle CCR=12. The original
sharing assumption was wrong: NBCD is not the oracle's SBCD(0, operand, X).
Evidence `/tmp/ipc-bcd-narrow-nbcd-wide-reference.log`; all 16 existing
ABCD/SBCD lock-step cases pass. The stopped build gate did not launch synthesis.

Correct NBCD in a separate follow-up change. Let r=(0x9a-byte-X) modulo 256.
If r=0x9a, preserve the operand and old Z, clear V/C/X, and set N from r[7].
Otherwise adjust a low nibble equal to 0xa by clearing it and adding 0x10
modulo 256; write that byte, compute N from its bit 7, V from complement of
the original r AND corrected result bit 7, clear-only Z, and set C/X.
This matches the local host oracle's dedicated NBCD operation, including the
invalid ff+X=1 no-change case. No memory-form/decode change.

Compute this small byte path in parallel and select it at the result/flags.
The generic ABCD/SBCD operands can then read srcA directly, deleting the
early `isNbcd ? 0 : srcA` mux from the critical arithmetic cone. This adds a
small cold combinational expression, not state or cycles. Keep the 10-bit
ABCD/SBCD proof intact; replace only the exhaustive NBCD test reference with
the dedicated oracle semantics. Unlike narrowing alone, this intentionally
corrects NBCD flags/invalid-digit behavior; do not claim complete NBCD parity
with the old implementation. Run the full exhaustive and lock-step gates again.
