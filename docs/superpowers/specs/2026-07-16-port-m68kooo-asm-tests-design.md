# Port m68k-ooo's Directed ASM Tests — Design

## Goal

Bring m68k-ooo's hand-authored directed test suite (`tb/tests/asm/`, 828 self-checking
68040 assembly programs) into this project as an independent, additional verification
corpus, complementary to the existing Musashi-lock-step and fuzz-campaign harnesses.
This phase covers the 766 tests (later verified as 763 — see docs/superpowers/ported-tests-results-2026-07-18.md) that need no interrupt-timing sweep; the remaining 62
`+ipl=`-sweep/other-flag tests are explicitly out of scope, deferred to a future,
separately-brainstormed phase.

## Background

m68k-ooo's `tb/tests/asm/*.s` tests are standalone, self-checking assembly programs:
each one computes something, verifies the result with branches/compares baked into the
program itself, and writes a sentinel value to a fixed MMIO address before halting —
no external oracle needed. m68k-ooo's own `Makefile`/C++ testbench (`tb/tb_top.cpp`)
detects test completion this way:

> Any write to `0xFFFF0000` finishes the test: `0xC0FFEE00` → PASS, anything else → FAIL.

m68k-ooo assembles these with `m68k-linux-gnu-as -m68040`, links at `0x40800000`, and
`objcopy`s to a flat binary. This project's own `ProgramAssembler.scala`
(`src/test/scala/m68k040/oracle/ProgramAssembler.scala`) already does the **exact same
three-command pipeline at the exact same load address** — the `.s` files should
assemble through it verbatim, with zero syntax translation needed.

Of the 828 tests, 98 have a companion `.args` file passing extra plusargs to m68k-ooo's
C++ testbench. Auditing all 98:

- 730 tests have no `.args` file at all (pure defaults).
- 36 more have `.args` containing only `+timeout=<N>` (later verified as 30 — see docs/superpowers/ported-tests-results-2026-07-18.md).
- 58 use `+ipl=<cycle>:<level>` — cycle-keyed interrupt injection, sometimes 500+
  injection points across one test, timeouts up to 50,000,000 cycles.
- A handful use other flags: `+ddr_read_delay=`, `+post_sentinel_drain=`,
  `+divergence_check`, `+expect_dbl_fault`, `+nowaves`.

**766 tests (730 + 36) need nothing beyond "assemble, boot, run until sentinel-write-or-
timeout."** (Note: actual verified count is 763 total with 30 timeout sidecars, per ported-tests-results-2026-07-18.md.) That's this phase's scope. The 62 IPL-sweep/other-flag tests need a
materially different mechanism (cycle-keyed interrupt injection, much larger timeout
budgets) and are deferred to a separate design pass.

This project's memory map has no existing claim on `0xFFFF0000` (the fuzz sandbox lives
at `0x4000`, far away), and the top-level (`FullCoreSynth.scala`) has no special AXI
address decode — any address is just a normal memory-agent write in the test harness,
confirmed by grep. No conflict.

## Non-goals

- The 62 `+ipl=`-sweep / other-flag tests (separate future phase).
- The `third_party/singlesteptests` TomHarte-style JSON single-step vectors (68000-
  targeted, needs a completely different per-instruction execute-and-compare harness;
  explicitly out of scope for this design).
- Any change to m68k-ooo's own repo — this is a one-way vendoring copy.

## Vendoring

Copy the 766 target `.s` files (actual verified count: 763) into a new resource directory:

```
src/test/resources/m68kooo-ported-tests/asm/<name>.s
src/test/resources/m68kooo-ported-tests/asm/<name>.timeout   (only for the 36 with a timeout override)
```

A one-off script (not part of the permanent build, run once to populate the directory)
does the copy, applying the "no `.args`, or `.args` containing only `+timeout=`" filter
derived above. The `.timeout` sidecar is just the bare cycle count (stripped of plusarg
syntax) so the harness can read a per-test override; tests without one fall back to a
project-wide default.

These are the user's own IP across two of their own repositories — vendoring is a
straightforward copy, no licensing concern.

## Harness architecture

New suite: `src/test/scala/m68k040/fuzz/PortedM68kOooSpec.scala` (co-located with
`FuzzLockStepSpec`/`FuzzDut`, reusing their infrastructure directly).

**Compile once, run many.** A `lazy val compiled = M68kSim().withVerilator.compile(new
FuzzCoreDut)` shared across every test in the JVM, mirroring `FuzzRunner.compiled`.
This sidesteps the documented per-test-full-compile JVM-OOM issue (project memory:
`lockstep-jvm-oom-hang.md`) entirely, since there is exactly one compile regardless of
how many tests run in a JVM.

**Per-test execution**, for each vendored `.s` file:
1. Assemble via the existing `ProgramAssembler.assemble(source, loadAddress =
   0x40800000L)` — same toolchain, same load address, already verified compatible.
2. Load into the icache via the existing `FuzzDut.attachProgram` pattern.
3. Run the same boot sequence `FuzzLockStepSpec` already uses (SR=0x2700, SSP seed via
   `wire.logic.seedValid`/`seedAddr=15`/`seedData`, icache invalidate,
   `cd.waitSampling(80)` settle).
4. Let it run under a `cd.onSamplings` hook watching `dut.dcache.logic.axi`'s write
   channel (same tap style as the existing whitebox-capture infrastructure) for any
   write with `addr == 0xFFFF0000`. On the first such write: capture `w_data`, end the
   run immediately. `PASS` iff `w_data == 0xC0FFEE00`, `FAIL` otherwise.
5. If no sentinel write occurs before the test's cycle budget (from the `.timeout`
   sidecar, or a project-wide default), the run is reported `HANG` — a distinct
   category from `FAIL`, matching the existing fuzz-campaign HANG bucket.

**Default timeout — explicitly flagged as a pilot risk, not a settled number.**
m68k-ooo's own testbench (`tb/tb_top.cpp:53`) falls back to 10,000,000 cycles when no
`+timeout=` is given, and that's what all 730 plain (no-`.args`) tests implicitly rely
on. Matching that number is the *correct*-by-fidelity default, but this project's Scala/
SpinalSim `doSim` loop may have very different cycles/second throughput than m68k-ooo's
native C++/Verilator harness — paying a 10M-cycle ceiling on every genuine hang could be
expensive in aggregate across 766 tests (actual: 763). **The pilot's job includes measuring actual
cycles/second for this harness** and deciding whether the default needs scaling down
(with the `.timeout` sidecar mechanism already in place to raise it back up for any
specific test that genuinely needs the full 10M). Do not guess this number before the
pilot has real throughput data.

**Test structure**: one named ScalaTest case per vendored file, generated in a loop over
the resource directory listing at suite-construction time — gives per-test PASS/FAIL/
HANG visibility in sbt's own test reporter, matching both m68k-ooo's own one-test-per-
corner-case convention and this project's `ExecuteLockStepSpec` convention.

**Running the full set**: `testOnly` against all 766 generated cases (actual: 763) in one JVM works
(compile-once avoids the OOM issue), but for practical batching (progress visibility,
resuming after an interruption) a `tools/fuzz/ported-sweep.sh` script will be added,
matching `tools/fuzz/sweep.sh`'s existing batch-per-JVM convention.

## Failure triage

Every `FAIL` or `HANG` is treated as a real divergence from correct 68040 behavior in
*this* core — these are ISA-behavior tests, not m68k-ooo-core-specific quirk tests, so a
failure here means a genuine bug. No deferred-list import from m68k-ooo's own
`deferred.txt` (their list reflects known issues in *their* core, not a baseline for
this one). Each failure gets the same treatment as this session's fuzz-campaign
findings: reproduce standalone (the vendored `.s` file already *is* the standalone
repro), root-cause, fix, verify against that specific test, re-run the batch to confirm
no regression, record in project memory and the task list.

## Rollout plan

1. **Pilot** (~20-30 tests spanning categories: plain ALU/flags tests like `neg_not.s`,
   a few addressing-mode tests, a couple of exception-related tests) — validates the
   harness end-to-end: sentinel detection actually fires, timeout path actually works,
   assembly/load actually matches m68k-ooo's own expectations. Fix any harness-level
   surprises before scaling.
2. **Bulk port** — once the pilot is clean, script vendoring of the remaining ~740
   tests, wire them into the same suite, run the full batch via the sweep-style script.
3. **Deferred, separate future phase**: the 62 `+ipl=`-sweep / other-flag tests (needs
   its own design pass on cycle-keyed interrupt injection and much larger timeout
   budgets) and the TomHarte-style JSON single-step vectors (needs a per-instruction
   execute-and-compare harness).
