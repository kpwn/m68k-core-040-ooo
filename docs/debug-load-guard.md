# Halt-after device-read launch interlock

Candidate based on the strict 200 MHz routed report of 2026-09-21:
`haltAfterEpoch[0] -> LSU P4 launch/ready -> P3 completion -> IQ dynWaitAny`:
21 LUT levels, 5.266 ns data delay (3.923 ns routing), -0.275 ns slack.

The precise ROB halt decision is unchanged, including epoch validation,
accepted target-write invalidation, macro boundary, one-shot consumption,
reset and resume behavior. Only the inhibited-load launch guard is simplified.

Let A=armed, C=registered comparison armed, P=comparison pending, H=registered
comparison hit, I=target invalidation, E=epoch match. The old LSU guard is:

```
A && ((!C || P) || (C && !P && !I && E && H))
```

The new guard is:

```
A && (!C || P || H)
```

The old guard implies the new for all input combinations. Differences are
restricted to A && C && !P && H && (I || !E): a stale or invalidated comparison
can conservatively postpone a device read while configuration refreshes, but
cannot authorize a halt. Disarm releases the guard immediately; a stable
configuration recovers the original guard once the comparison refreshes.

No extra register, pipeline cycle, clock constraint, ordinary-load stall or
change to store-queue ordering is introduced. The inhibited load remains
ROB-head/older-store/preemption gated. The already-launched device-read
interlock remains unchanged. This is not a remedy for other debug races.

RobPlugin exclusively produces DebugLoadPreemptService through a setup-allocated
wire; LsEuPlugin consumes the optional service. Standalone LSU tests without a
ROB retain their explicit input/default. Existing backend/test plumbing no longer
reconstructs the precise ROB expression through implementation fields.

Validation: exhaustive combinational guard test, actual ROB service refresh test,
precise halt/reprogram/resume tests, inhibited-load preemption tests, matched IPC
windows and required fast gate. Physical improvement remains unproven until routed.

The 91-test focused run passed, including configuration refresh and precise
halt/resume behavior. All 16 matched byte-copy IPC windows are unchanged from
parent 53e3f723; these are simulation regressions, not new board measurements.
The broad run also exposed a pre-existing free-loop test bug on that parent:
its completion-ID mask hardcoded six bits against the current five-bit port.
The fixture now derives the width from the port; production ROB sizing is unchanged.
