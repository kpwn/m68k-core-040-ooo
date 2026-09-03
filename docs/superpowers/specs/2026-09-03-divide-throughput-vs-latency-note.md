# Divide: pipeline it? — deferred, with the analysis recorded

**Status: DEFERRED by the project owner (2026-09-03).** Not a correctness issue.
Recorded so a future session does not re-derive it. No RTL change proposed.

## The question

The divider is single-outstanding. Should it be pipelined so multiple divides can
be in flight?

## Why the answer is probably "no", and what would actually decide it

**Pipelining buys throughput. This core is out-of-order, so it already tolerates
poor EU throughput** — while a divide grinds for its 20-40 cycles, the ROB/IQ keep
issuing independent work to the ALU/LSU/branch units. An in-order design (v1) would
care about this; we largely do not.

**What OoO cannot hide is latency, and pipelining does not reduce latency.** Two
real costs survive, and neither is fixed by more stages:

1. **True dependent consumers.** Anything needing `Dq`/`Dr` waits the full latency
   regardless of stage count. Note the one divide-dense workload we have actually
   identified — the ROM's `_SlotManager $2C` / `SCalcsPointer` (`0x40805C80`), which
   issues a `divsl.l` per sResource step (see `BUG_calibration_word_misplaced_0d00.md`
   Part 116) — is a **pure dependency chain**: each result computes the next pointer.
   Pipelining would overlap nothing there.
2. **In-order retire pressure.** A long-latency op at the ROB head blocks retire and
   the ROB fills behind it, stalling dispatch. Real OoO cost, but a *latency* problem.

**Therefore, if area is ever spent on the divider, the lever that matches the cost is
lower latency (higher-radix SRT), not more parallelism.** Different change.

## Measured cost evidence already on record

Part 117's synth A/B is a useful proxy for "extra divider state is not free": holding
tagged state for 63 additional (unreachable) divides cost **0.533 ns WNS**
(175.25 -> 160.28 MHz) for zero functional benefit. The landed 1-entry tagged form is
178.44 MHz. A real pipeline replicates iteration hardware, not just storage — strictly
worse. We are currently at 178.44 MHz against a 200 MHz goal, so timing headroom is
the scarce resource.

## The one live throughput argument, worth checking before dismissing

`MUL` and `DIV` share the same CPLX EU (`DivEuPlugin`). If a long divide occupies that
port, **multiplies may stall behind it** — and multiplies are common enough in real
code to matter. If that turns out to be real, the cheap fix is **splitting MUL out of
the CPLX EU**, not pipelining the divider.

(Part 117's fix was careful here: it restricted age-ordered issue to the DIV/DIVREM
family specifically, so it did not make this worse.)

## The three measurements that would settle it

No RTL change needed; instrumented sim over the ported corpus plus a boot trace.
Reuse the occupancy-histogram technique from the early-flush A/B
(`2026-09-03-early-flush-ipc-ab-measurement.md`), which built exactly this shape.

1. How often is **>1 divide concurrently in flight**? (throughput demand — expected
   ~never for integer 68k code)
2. How often does a **busy divider block a `MUL`** from issuing? (the live argument;
   if yes -> split MUL/DIV, not pipeline)
3. How often does a divide sit at the **ROB head blocking retire**? (the real OoO
   cost; if yes -> reduce latency, not add stages)

## Counter-evidence to weigh

Part 61 measured the backend **idle 58.7%** of the window on the branch-heavy kernel
(IPC 0.508) — i.e. this core is branch/front-end bound, not EU-throughput bound. That
is inference from a different workload, not a measurement of divide behaviour, but it
tilts against all three questions above mattering much.

## Where it could genuinely pay

FPU / FPSP-emulation code, where divides are more frequent and sometimes genuinely
independent. Currently unmeasured.

## Standing caution

The early-flush episode this same day is the reason to measure rather than reason from
intuition: an "obviously beneficial" OoO improvement measured **-44.9% IPC** on the
branch-heavy kernel and introduced 69 reproducible hangs. Do not build this on
plausibility.
