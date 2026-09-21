# Integer narrowing range checks

The strict 200 MHz whole-SoC route of 2026-09-21 reported
`FpNarrowPack.r.intLow32[0] -> legacyResult.data[19]` at -0.274 ns:
15 logic levels, 5.094 ns data delay, 3.907 ns routing. The path traverses
integer sign conversion, then the byte-range check and FP exception/issue
qualification. The range check need not depend on the converted data word.

For a signed N-bit destination (N=8 or 16), positive rounded magnitude fits
below 2^(N-1), while negative magnitude may also equal that boundary. Therefore:

```
outside = highMagnitudeNonzero
       || any(low32[31:N])
       || (low32[N-1] && (!negative || any(low32[N-2:0])))
```

This is equivalent to checking the old saturated signed 32-bit value against
the destination's min/max: any 32-bit overflow saturates to a value outside
both narrow ranges. No new arithmetic, state, pipeline stage, interface,
exception policy or clock exception is introduced. Long conversion/saturation,
returned integer data, OPERR first-chunk qualification and INEX2 rules are
unchanged. The converter's one-cycle latency and one-input-per-cycle capacity
are unchanged.

Test-only baseline 9f74a49d preserves the original RTL. New tests cover 6,768
exact quarter-unit vectors around zero, byte/word and 32-bit limits, both signs,
all four rounding modes, all three integer formats and chunk indices 0/1/2.
Expected values/flags are computed with independent integer arithmetic, and a
before/after digest checks the full result stream at the declared latency.
The added whole-core tests check enabled/disabled OPERR on positive byte/word
overflow and exact negative-limit acceptance. Existing SoftFloat-reference
random sweeps, stored-value/register-merge tests and FP control tests remain
required, as do matched IPC regression and the fast gate.

Physical timing/area improvement requires a matched implementation; source
structure and simulation equivalence alone are not timing closure.
