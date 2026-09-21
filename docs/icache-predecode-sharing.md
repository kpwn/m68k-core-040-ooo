# Eight-wide refill predecode experiment

This explicitly extends the install schedule in the I-cache/MSHR design's
per-beat predecode (slice I2). The cache remains 16 KiB, four-way, with 64-byte
lines; its AXI interface still returns two 256-bit beats per line. The unified
data/predecode array layout and hit pipeline do not change.

The existing mode classifies sixteen 16-bit words per cycle and installs a
line in two PREDECODE cycles. The experimental mode classifies eight words
per cycle, reusing one classifier bank. It opportunistically classifies each
incoming beat's lower eight words on its accepted AXI response cycle, storing
the resulting metadata alongside that MSHR's existing fill data. The lower
eight words need only words 0–10 of that beat, so their lookahead is complete.

During installation, the same classifier processes the upper eight words of
each beat. If both early results are present, the original two-cycle install
schedule is preserved even for consecutive response beats. Classification
and publication are separate: no cache line is published before the complete
burst is known to be successful.

The installer has priority over the classifier. An incoming beat that collides
with an active install is still accepted and stored normally, but its early
metadata-valid bit is cleared. When that entry is later installed, each missing
lower half costs one local preparation cycle. There is no added AXI backpressure
and no new queue for scheduling predecode work. The worst-case fallback is:

| Phase | Words classified | Array write | Publish tag/valid |
| --- | --- | --- | --- |
| 0 | 0–7 | none | no |
| 1 | 8–15 | low beat, all its metadata | no |
| 2 | 16–23 | none | no |
| 3 | 24–31 | high beat, all its metadata | yes, if allocation allowed |

Two small per-MSHR metadata memories hold lower-half results, with per-beat
valid bits. These bits are overwritten on EVERY owned beat acceptance, including
when the early computation is skipped. Stale/drained beats may update neither
metadata nor validity. A completed line therefore cannot inherit validity from
an older occupant. Reset clears the validity bits; metadata contents need no reset.
The only local staging holds a lower half computed during fallback. Existing
fill data is not duplicated.

Storage accounting matters even though cached metadata is unchanged. With the
current nine-bit `ChunkPredecode` and five MSHRs, the temporary memories hold
720 bits total (two 72-bit by five-entry arrays). Their synchronous read outputs
add 144 register bits, fallback staging 72, and validity/phase 11: 947 logical
state bits before synthesis mapping. Do not describe the sharing as free or
assume those small arrays map to BRAM; inspect implementation utilization.

Each classifier still sees the same three extension words and validity flags
as whole-line predecode. Crossing an eight-word boundary must not introduce
ambiguity. Only lookahead beyond word 31 is unavailable. Both refill beats
must have completed successfully before installation starts.

The installer retains its MSHR entry, set and way until the final phase.
Invalidate/poison guards remain authoritative at each write; a simultaneous
invalidate wins over tag-valid publication. Demand replay and inhibited/poisoned
bypass use matching complete data and metadata. Reset clears the phase; no
partially staged metadata may be published after reset or slot reuse.

Speculative installs retain the existing conservative same-set exclusion for
their entire dwell, including metadata-only phases. Other-set hits can still
use the existing acceptance path, while unresolved misses can wait longer.
Demand replay, inhibited fetches and blocked foreground requests may therefore
pay up to two more install cycles after classifier contention. Back-to-back,
gapped and interleaved RID returns must all be measured. No zero-IPC-cost claim
is made in advance, and the added R-data-to-classifier path needs physical timing.

`IcachePlugin(predecodeWords = 16)` is the baseline. Only 8 and 16 are supported.
Production generation and selected test harnesses explicitly pass the validated
`ICACHE_PREDECODE_WORDS` setting; absence selects 16. The plugin itself does not
read the environment. Do not switch a release default without matched results.

Acceptance requires both modes' correctness gates, all new boundary/phase
tests, hot-loop and refill-heavy IPC measurements, generated-RTL checks for
eight actual classifier instances, required fast tests, production lint and
serialized matched 200 MHz SoC implementation. Keep a logically correct option
after a timing miss only with a concrete repair hypothesis; no timing waivers.

## Initial simulation evidence (2026-09-21)

Matched board-byte-copy microbenchmarks (32/128 bytes, with/without readback,
baseline/combined socket options, seeds 1/17): all sixteen retirement windows
have exactly unchanged cycle counts, IPC, branch/mispredict counts and SQ
reservation/publication counts. These reproduce a board-observed instruction
shape; they are not measurements of the complete Mac Dhrystone executable.

Refill-heavy full-core measurements use combined throughput options, reduced
debug, and the same L2-hit=5/DDR=70 cycle model for both modes. Linear code
executes 8 KiB of MOVEQ instructions; sparse chains execute two instructions
per 64-byte line across 32 KiB in sequential or fixed-seed shuffled order.
Every architectural result and the sparse traversal order are checked.

| Workload | Seed | 16-way cycles | 8-way cycles | IPC change |
| --- | ---: | ---: | ---: | ---: |
| Linear 8 KiB | 1 | 2897 | 2897 | 0.000% |
| Linear 8 KiB | 17 | 2880 | 2883 | -0.104% |
| Sparse sequential 32 KiB | 1 | 11745 | 11745 | 0.000% |
| Sparse sequential 32 KiB | 17 | 11699 | 11773 | -0.629% |
| Sparse shuffled 32 KiB | 1 | 21776 | 21747 | +0.133% |
| Sparse shuffled 32 KiB | 17 | 22115 | 22310 | -0.874% |

The paired directed test compares 665 fetched windows byte-for-byte and
metadata-bit-for-metadata-bit, with independent byte expectations, extension
patterns across all boundaries, consecutive/gapped returns, reused MSHRs and
natural classifier contention. Uncontended installations take two cycles;
two accepted responses during another owner's install cause exactly two
fallback cycles. Generated standalone RTL contains sixteen/eight copies of
the classifier's FPU-immediate case table respectively, confirming the sharing.
This is structural evidence, not a synthesized LUT or routing result.

Logs: `/tmp/ipc-predecode8-paired.log`, baseline
`/tmp/ipc-predecode8-ipc-before.log`. The final eight-way cache regression passed
72 tests, including invalidation in all four fallback phases; default sixteen-way
passed all 70 existing cache tests. Required `make SBT=~/sbt/bin/sbt test-fast`
passed in both configurations: 396 succeeded, two ignored, zero failures/aborts.
Both production generations passed with throughput-v2, reduced debug and detailed
counters enabled. Full socket RTL contains 17/9 copies of the classifier's table
(sixteen/eight refill instances plus one unchanged frontend classifier).
Source receipts and gate results are in `/tmp/ipc-predecode8-final-gates.log`,
`/tmp/ipc-predecode8-candidate.sha256`, `/tmp/ipc-predecode8-netlists.sha256`.
Production SoC lint and physical comparison remain separate acceptance gates;
passing these measurements alone does not approve a release.
