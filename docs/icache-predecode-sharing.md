# Eight-wide refill predecode experiment

This explicitly extends the install schedule in the I-cache/MSHR design's
per-beat predecode (slice I2). The cache remains 16 KiB, four-way, with 64-byte
lines; its AXI interface still returns two 256-bit beats per line. The unified
data/predecode array layout and hit pipeline do not change.

The existing mode classifies sixteen 16-bit words per cycle and installs a
line in two PREDECODE cycles. The experimental mode classifies eight words
per cycle, reusing one classifier bank for four cycles. It stages only the
first half-beat's metadata; the already held fill data is not duplicated.

| Phase | Words classified | Array write | Publish tag/valid |
| --- | --- | --- | --- |
| 0 | 0–7 | none | no |
| 1 | 8–15 | low beat, all its metadata | no |
| 2 | 16–23 | none | no |
| 3 | 24–31 | high beat, all its metadata | yes, if allocation allowed |

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
pay two more cycles. No zero-IPC-cost claim is made in advance.

`IcachePlugin(predecodeWords = 16)` is the baseline. Only 8 and 16 are supported.
Production generation and selected test harnesses explicitly pass the validated
`ICACHE_PREDECODE_WORDS` setting; absence selects 16. The plugin itself does not
read the environment. Do not switch a release default without matched results.

Acceptance requires both modes' correctness gates, all new boundary/phase
tests, hot-loop and refill-heavy IPC measurements, generated-RTL checks for
eight actual classifier instances, required fast tests, production lint and
serialized matched 200 MHz SoC implementation. Keep a logically correct option
after a timing miss only with a concrete repair hypothesis; no timing waivers.
