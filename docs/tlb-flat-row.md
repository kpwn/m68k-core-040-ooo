# Flatten TLB lookup row selection

## Motivation and contract

The routed AW-cleanup comparison reports a -0.195 ns path from the branch
predictor through live ITLB lookup/cacheability to I-cache speculative MSHR
state (21 levels, 4.992 ns data delay). The TLB selects a bank of per-set
payloads before selecting a set, creating nested wide muxes. Test a single
lookup index over a flattened bank/set row list instead. The storage remains
`[bank][way][set]`; fill, replacement, invalidation and duplicate purge are
unchanged. This is a mux-topology experiment, not a proven physical saving.

Use row index `bank * setsPerBank + set`, concatenating only real address bits
(no synthetic bit for a singleton bank/set). For each way, flatten rows in
bank-major order and select valid, tag, FC2 and all entry payload fields at the
same row. Still compare only the selected four tags, not a full CAM. No new
register, RAM bit, pipeline cycle, service or Global key. Lookup hit/miss,
permissions, cache mode, modified status, supervisor address-space separation,
multi-hot fail-safe/purge and diagnostic evidence must remain cycle-identical.

## Verification

Start from CPU 867883a8 in reserved pool agent-06 (`perf/tlb-flat-row`). Add a
test-only original nested-selector reference against the real storage and
compare every selected field, hit vector and resulting entry. Cover all rows
and ways, both FC2 values, tag mismatches, randomized fill/lookup/invalidate
traffic, and two-/four-bank geometries with the same tag width.
Run it before and after the RTL change, along with existing TLB duplicate,
multi-hot and page-size tests. Follow with required fast correctness gate,
matched 16-row BoardStringCopy IPC, production generation and top-level lint.
Keep candidates pinned for serialized strict 200 MHz physical comparisons.
No timing acceptance or board improvement follows from simulation alone.

The initial baseline probe found existing singleton-bank/singleton-set
elaboration failures in dynamic Vec indexing, including the unchanged fill/
purge paths. Those unsupported geometries are not repaired by this lookup-only
experiment. Evidence: `/tmp/ipc-tlb-flat-geometry-probe.log`. The matched gate
therefore uses the baseline-supported two-/four-bank configurations; production
uses two banks. The flat helper still handles the address-width edge cases, but
that is not a claim that the complete TLB supports those configurations.

## Focused results

Before/after: all 10 focused tests pass. The two row-reference runs compare
12,128 cycles total and report identical hit/miss counts (two banks 292/5772;
four banks 255/5809). Every selected way's tag, PPN, protection, cache mode and
modified flag is compared with the original nested read, including on misses.
Existing duplicate-fill/purge and 4K/8K re-key tests also pass. Generated test
RTL shows one `case(lkRow)` for all selected fields; the reference retains the
old `case(lkSet)` and `case(lkBank)` pair. No register is added by the helper.

The AW baseline hierarchy report attributes 993 LUTs to DTLB and 1358 to ITLB.
These are attribution totals, not a predicted saving. Four matched full-core
MMU/exception postures, required fast gate, 16 matched IPC rows and production
generation are serialized by `/tmp/run-ipc-tlb-flat-gates.sh`; results live in
`/tmp/ipc-tlb-flat-*.log`. Physical evidence and board acceptance remain pending.
