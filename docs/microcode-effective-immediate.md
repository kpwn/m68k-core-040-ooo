# Precomputed microcode immediate selection

## Routed evidence

The completed AW-predecode 200 MHz SoC comparison has a -0.190 ns setup path
from microcode BRAM output to the registered decoded immediate. It traverses
eight LUT levels, with 4.957 ns data delay (1.770 ns logic, 3.187 ns route).
The first ROM-output net has 52 loads; later selectors drive 15 and 30 loads.
Source: `ipc-v2-debug-apply-cleanup/build/vivado/reports/timing_route.rpt`,
`DecodeStage_logic_ucRomMem` to `pushReg_payload_uops_0_imm[9]`.

This does not prove all eight levels come from immediate selection. The
experiment removes two identifiable runtime choices before measuring the
generated cone and strict routed result.

## Encoding contract

The software microcode `Desc` and reference `resolve(Desc, ...)` remain
unchanged. The hardware ROM `DescBits` stores the **effective** immediate
selector instead of copying the raw source descriptor selector:

| Descriptor | Effective selector |
|---|---|
| `UMiHostOp`, regardless of `useImm` | `SMiImm` (`ctx.miHostImm`) |
| Other row with `useImm=true` | Original `Desc.imm` |
| Other row with `useImm=false` | `SNone` (zero) |

Rename the hardware field to `effectiveImm` so no future caller mistakes it
for a one-to-one copy of `Desc.imm`. Keep the same enum, bit width, field order,
ROM depth, ports, and synchronous-read latency. `useImm` itself is unchanged:
the host-op output still uses `ctx.miOtherIsImm`, other rows retain their ROM
flag. The immediate *value* is independent of that output flag for host ops,
as in the existing reference. Flags, supervisor/fault handling, source/dest
validity, sequencer timing, flush behavior and context capture are unchanged.

The runtime resolver assigns `u.imm` once from that effective selector,
removing both the `d.useImm` zero mux and the later host-op immediate override.
This adds no metadata bits, registers, cycles, or Global/service keys. All
ROM initialization remains elaboration-time constants. No timing exception
is permitted. Source-time encoding and runtime resolver must change together.

## Verification

Use reserved CPU agent-14 from `0c0270e4`, branch
`perf/microcode-effective-imm`. Run the unmodified microcode equivalence,
sequencer and FP-control baseline before editing RTL. Preserve the trusted
software resolver unchanged. Compare all real ROM rows over the existing
directed/random full-context sweep. Add synthetic descriptor corners that
exercise a nonzero ignored selector and host-op override with either flag;
also compare a synchronous hardware ROM with the software oracle across
changing row addresses and contexts. Prove ROM width/depth unchanged.

Run the required fast gate and all 16 matched byte-copy IPC windows with
eight-way predecode, reduced debug, early store address/data options and
L2:5:70 seeds 1/17. Cold-path sequencer tests and context equivalence are
required even though the hot byte-copy loop need not use this ROM. Inspect
production generated RTL, then run SoC lints before queueing a separate
200 MHz comparison. Physical gain and board improvement are unproven until
measured; no board access is authorized by this experiment.

## Current evidence (2026-09-22)

Baseline microcode suites: 16/16 tests pass, including 476,252 whole-uop
comparisons (932 contexts × all 511 production ROM rows). The new synchronous
ROM fixture passes 19,548 comparisons over those rows plus 32 synthetic
descriptors. It exercises 432 host-op cases and 4,752 disabled-immediate cases,
varying current context and valid independently of the registered ROM address.
Measured descriptor width is 79 bits before the change.

The candidate implements the encoding contract above. Both equivalence sweeps
pass again, with identical coverage and the same 511-row/79-bit production
geometry; the new test asserts the width. The software `resolve(Desc, ...)`
oracle is untouched. All 17 focused tests pass, including sequencer/FP-control
tests. Required fast gate passes 396 tests / 2 ignored, with zero failures or
aborted suites (00:36:24). All 16 matched byte-copy rows equal the stash parent
exactly (00:37:45): IPC/cycles, retired macros, branches/misses and store
reservations/publications. This is simulation evidence, not a new board result.

Production generation and source hashes pass at 00:37:54. The generated ROM
remains `reg [78:0] ... [0:510]`; the immediate selector is driven by
`ucRowBits_effectiveImm`. Physical timing/area benefit remains unmeasured.

Logs: `/tmp/ipc-microcode-imm-{before,runtime-before,after,fast,ipc,generation}.log`.
Serialized runner `/tmp/run-ipc-microcode-imm-gates.sh`, service
`m68k-ipc-microcode-imm-gates.service`, master
`/tmp/ipc-microcode-imm-gates.log`. The CPU candidate is ready to pin for
SoC lint and a separate physical comparison, using the existing eight-way
predecode/reduced-debug 200 MHz profile. No timing or board acceptance is implied.
