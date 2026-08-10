# L1I parallel-VIPT resident-hit amendment

**Date:** 2026-08-10  
**Status:** Binding amendment; authorizes Phase A RTL  
**Parent architecture:** `2026-05-31-m68k-040-ooo-architecture-design.md`  
**Supersedes:** `2026-05-31-icache-slice-design.md` §13 resident-hit timing and
`2026-08-09-frontend-throughput-audit.md`'s recommendation to retain the
translation T stage. Geometry, refill, fault, cache-mode, and speculation rules
from those documents remain binding.

## 1. Decision

The resident L1I hit path is a two-cycle, initiation-interval-one pipeline. A
single accepted fetch launches the ITLB lookup, async tag/prediction lookup, and
synchronous data-BRAM read in parallel from the same virtual address. The live
translation result is used only to qualify the physical tag and capture a small
registered response context; it does not feed the BRAM address or its enable.

This implements the canonical architecture's IF1 contract (PC select plus
I-cache and ITLB request) while preserving the FPGA-friendly wide-data boundary:

1. Cycle N, `FetchService.cmd.fire`: virtual `pc[11:6]` and `pc[5]` arm all four
   per-way BRAM reads. In parallel, the resident ITLB returns PPN, permission,
   and cache mode; async tags for virtual set `pc[11:6]` compare with that PPN.
   The edge captures `{pc, hit way, fault, raw per-way prediction entries}`.
2. Cycle N+1: registered way/lane control selects the synchronous BRAM output;
   registered raw prediction metadata is windowed. The edge captures the
   existing `FetchRsp` register.
3. Cycle N+2: `FetchRsp.valid` is visible. FetchAlign can make the words useful
   on N+3.

Resident commands and responses remain II=1. The clean redirect target command
still issues at redirect N+1, so the first useful target group moves from N+5 to
N+4.

## 2. Timing boundary and physical rationale

The rejected historical shape placed live translation, tag selection, BRAM
control, and wide response selection in one cycle. This amendment does not.

- The virtual set/beat drives BRAM address and enable without translation.
- The live ITLB result feeds only the four 20-bit tag compares, a 4-way hit
  reduction/encoder, and the S1 control registers.
- The 4 x 256-bit data mux, lane select, and prediction windowing remain after
  the S1 register and terminate in the existing response registers.
- Tag and prediction memories remain async LUTRAM; data remains four synchronous
  BRAMs. No new memory port, CAM, DSP, or wide data register is introduced.
- The old translation T-stage registers are removed. Expected area is neutral
  to slightly lower; the only physical risk is the live ITLB-to-hit-context
  register cone. That cone must be reported separately in the next 250-MHz
  route gate. The deployment floor remains 200 MHz, but acceptance is optimized
  for 250 MHz as requested.

If the new ITLB-to-S1 cone materially regresses routed FMax, the permitted
recovery is to pipeline the ITLB's internal hit-way result or improve placement.
Do not restore a translation-to-BRAM-address dependency or delete the response
register.

## 3. Handshake and ordering

### 3.1 Resident hit and translation fault

In IDLE, a command is accepted when its translation is resolved. A cacheable
resident hit captures a normal S1 context. A translation/permission fault
captures a fault placeholder and never launches AXI. The data BRAM may be read
speculatively for a fault or miss, but its output is ignored unless an S1 hit
context was captured.

### 3.2 Demand miss

A resolved demand miss is accepted and atomically captures the existing miss
context before the FSM closes the fetch port. Unlike the old T-stage pipeline,
the cache does not accept one extra younger command behind the newly discovered
miss. This is intentional: `FetchRsp` is untagged and must remain in acceptance
order. Refill, bus-fault, inhibited delivery, poison, predecode, and replay
semantics are unchanged.

### 3.3 Prefetch fill

During `PF_REFILL`, resolved hits to unrelated resident lines may still fire and
return in order. A command that misses, or a command whose set collides with the
prefetch array-commit cycle, holds `valid` and sees `ready = 0`; it is retried
after the fill installs. The producer must keep its payload stable under this
Stream backpressure. A speculative BRAM read made while the command is held has
no architectural effect.

Demand has absolute priority over starting a queued prefetch. Invalidation and
fill-poison behavior remain as previously specified: accepted responses are
retired through FetchAlign's stale mechanism, never silently deleted.

## 4. VIPT and MMU invariants

- L1I remains 16 KiB, 4-way, 64-byte lines, 64 sets. Offset plus index is exactly
  the 12-bit 4-KiB page offset; virtual indexing is synonym-safe.
- Physical PPN supplies the tag. Cache mode and permission come from the exact
  same live translation that qualifies the accepted command.
- `TranslationReq.valid` follows a real offered fetch. An idle frontend must not
  launch a walk for an unowned payload.
- A nonresident translation holds the command while the existing single ITLB
  walker runs. No speculative prefetch may start a walk or cross a page without
  a resident translation.
- ITLB/DTLB depth is unchanged. This amendment adds no associative structure.

## 5. Prefetch bandwidth finding and Phase B dependency

The current next-line prefetch is safe but cannot cover the measured cold-stream
case by timing alone. A 64-byte line lasts about 16 cycles in the 2-IPC,
mostly-16-bit independent-ALU kernel, while a cold `l2:5:70` line costs about 70
cycles. One singleton prefetch fill therefore cannot sustain that stream.

The real SoC L2 has eight MSHRs and accepts distinct AXI IDs, but its front door
blocks reuse of a live ID. The current SoC crossbar is one outstanding read
transaction per master, which prevents the core's distinct I-fetch IDs from
reaching those MSHRs concurrently. A long same-ID burst is functionally legal
but remains serialized by the L2 and is not an honest fix.

Phase B is therefore a coordinated fabric + core slice:

- fabric: allow at least four distinct-ID CPU-IF line reads outstanding;
- core: a shallow four-entry, same-page sequential fetch window with one demand
  owner and three silent speculative entries, distinct IDs, per-entry two-beat
  assembly/error state, one shared array-install/predecode scheduler, demand
  priority, resident/in-flight suppression, and ID-routed R beats;
- safety: rules P1-P4 remain binding (resident same-page translation, cacheable
  only, silent speculative error, real demand retry), and FetchAlign still sees
  demand responses strictly in order.

Phase B RTL in this repository is not authorized until the fabric contract and
its non-vacuous distinct-ID test are green. The existing singleton prefetch is
retained during Phase A, with no claim that it hides a 70-cycle cold stream.

## 6. Non-vacuous verification

Phase A must prove all of the following:

1. Warm at least six unique windows, then accept them on consecutive cycles and
   require associated responses on consecutive cycles at exactly N+2, with
   exact PC/data/prediction and zero AXI AR.
2. With the real MMU enabled and a resident non-identity mapping, require ITLB
   and cache lookup to apply to the same command, response N+2, eight consecutive
   useful two-wide decode groups after fill, and zero I-cache/walker AXI.
3. A clean redirect target command fires at N+1 and its first useful group at
   N+4. Full-ring consume/replace plus redirect must preserve ordering and stale
   suppression.
4. A resident permission fault returns at N+2 with the exact PC, ATC cause, no
   refill, and no stale data use.
5. While a prefetch fill is active, resident hits continue; a held miss remains
   stable and fires exactly once only when answerable. A same-set install
   collision cannot observe a half-written line.
6. Mutation control: restoring the old T-stage or expected latency three must
   fail the N+2 assertions.

The mandatory repository gate is `make SBT=~/sbt/bin/sbt test-fast`. Focused
Verilator suites must also be run when the PM-serialized simulator slot is free.

## 7. Acceptance evidence

Handoff records must include focused correctness results, the before/after
independent-ALU ideal and `l2:5:70` IPC rows, and a fresh 250-MHz physical gate.
Report global WNS/TNS/failing endpoints, L1I/ITLB endpoint groups, LUT/FF/BRAM/DSP
delta, and whether the existing decode+D-cache floorplan still helps. An area
increase is a review point, not an automatic rejection.

### 7.1 Phase-A simulation result (2026-08-10)

Focused resident-hit, prefetch/backpressure, redirect cadence, real-MMU, and
resident-permission suites are green.  The mandatory repository gate passes
147/147 tests across 156 completed suites.

Pinned `independent-ALU` seeds 1/2/3 give:

| memory model | pre-amendment mean cycles / IPC | Phase A cycles | Phase-A mean cycles / IPC | delta |
|---|---:|---:|---:|---:|
| `zero` | 214.333 / 1.998 | 214 / 218 / 214 | 215.333 / 1.989 | -0.47% |
| `l2:5:70` | 1129.000 / 0.379 | 1119 / 1123 / 1143 | 1128.333 / 0.380 | +0.06% |

This is deliberately reported as essentially IPC-neutral.  Removing the old
T stage also removes its ability to hide one younger accepted command behind a
newly discovered miss, which costs three cycles in ideal-memory seed 2.  The
one-cycle resident/redirect latency reduction is real, but under the L2 model
it is overwhelmed by the singleton refill engine.  These results strengthen,
rather than weaken, the Phase-B requirement for multiple distinct-ID line
requests in flight.

The fresh 250-MHz physical gate remains pending and is required before final
acceptance.
