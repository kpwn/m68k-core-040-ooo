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
