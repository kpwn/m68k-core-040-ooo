# Part 135 — the M=1 / format-`$1` throwaway interrupt frame: a negative on the stacks, and a real CCR bug found on the way

**Date:** 2026-09-04
**Branch:** `investigate/p135-m1-throwaway-frame` (worktree, **NOT merged**)
**Baseline:** `bb3bca1` (`fmax-closure-fanout`)
**Board / SD card / JTAG:** **not touched.** No board work of any kind.

---

## 1. Headline — two separate results

**(a) The stated hypothesis is a NEGATIVE. The M=1 / format-`$1` throwaway path does not
corrupt either stack pointer, under any condition tested.** Across 8 directed scenarios,
MMU off and MMU on, storm and per-cycle-enumerated arrival, bare and nesting and
stack-heavy handlers, **every frame word landed at exactly the right address on exactly
the right stack and both `MSP` and `ISP` returned to their entry values every time.** The
sim-side frame-address audit is exact to the word, so a 2-byte drift could not hide: it
would write 2 bytes off, cumulatively, and the second entry would already be visible.

**(b) A different, REAL defect was found and fixed: `RTE` restores the architectural CCR
into the flags PRF but leaves `RobPlugin.committedCcr` — the shadow the NEXT exception
entry builds the stacked SR's low byte from — holding the HANDLER's condition codes.** A
second interrupt taken at the same flag-consuming instruction therefore stacks the wrong
flags, and its `RTE` restores them, so the interrupted program's `Bcc` branches the wrong
way. It reproduces in **both** supervisor banks in well under 100 interrupt entries, is
**not** M=1-specific, and is invisible to any test whose handler is a bare `RTE` — which
is every interrupt test that existed, including all of Part 134's.

**This does not fix the boot, and no claim is made that it does.** §7 states plainly what
the connection to p133 is and is not.

---

## 2. Correcting the direction of the sequence

The brief states the throwaway frame goes on the **master** stack and the real frame on
the interrupt stack. That is backwards, and it matters for reading the results.

MC68040 UM §8.4.2/§8.4.3, and Musashi's `m68ki_exception_interrupt`: an interrupt taken
with `S=1, M=1`

1. stacks a NORMAL four-word format-`$0` frame on the currently-active stack — the
   **MASTER** stack, because M is still 1 — so `MSP -= 8`;
2. clears M, re-banking A7 to the **INTERRUPT** stack;
3. stacks the four-word format-`$1` **throwaway** frame there, so `ISP -= 8`;
4. runs the handler on the ISP with M=0 and the mask raised to the interrupt level.

`RTE`, inside one instruction: pops the `$1` frame off the ISP (`ISP += 8`), applies its SR
— which still carries M=1, so A7 re-banks back to the MSP — DISCARDS its PC, then loops
back and pops the real `$0` frame now on top of the MSP (`MSP += 8`), using its PC and SR
for the actual return.

`ExceptionUnit.scala:450-466` has this right. The tests here assert the correct direction
explicitly: T1 checks the format word at `ISP-2` is `$1064` and the one at `MSP-2` is
`$0064`, so a swapped implementation would fail loudly rather than pass silently.

---

## 3. What was built

`src/test/scala/m68k040/exception/M1ThrowawayFrameIrqSpec.scala` — 17 scenarios on the
shared full-core `FuzzCoreDut`, level-1 autovector IRQ driven on `iplIn`. Three
deliberately independent observation channels:

* **Guest-side architectural assertions.** The program re-derives every fact from `%a7`,
  `MOVEC %msp`, `MOVEC %isp`, `%sr` and the stacked frame words read back through
  `%a7@(n)`, and writes a DISTINCT numeric code plus **the value the failing check
  actually saw** to a watched address. Recording the observed value is what turned a
  failure from "something is wrong" into a diagnosis (§5).
* **Sim-side frame-address audit.** With the MMU off both stacks are writethrough, so
  every frame word is its own AXI AW beat. For a bare-`RTE` handler the only addresses
  that may ever be written in either stack's low window are `{SP-8, SP-6, SP-4, SP-2}`.
  This channel does not depend on the guest program being correct at all.
* **The committed `ss.msp` / `ss.isp` / `ss.srSys` bank registers**, read directly at the
  end, plus an architectural loop counter in `%d7` read through `ArchStateProbe` as a
  liveness bar (a clean result from a wedged guest is worth nothing).

Arrival is swept two ways, as in Part 134: a growing-gap storm, and a deterministic
per-cycle enumeration anchored on the guest's own per-iteration store, holding the level
until the ROB recognises it (a short pulse can LAPSE mid-macro and silently hollow out the
sweep — Part 134 measured only ~23% conversion without the hold; these runs convert
**384/384**).

---

## 4. The stack-pointer result — clean, on unmodified RTL

| # | scenario | entries | result |
|---|---|---|---|
| T1 | ONE M=1 interrupt, all 15 architectural facts of the two-frame entry checked | 1 | **PASS** (`0xC0FFEE00`) |
| T2 | M=1 storm, both banks re-checked every loop iteration, bare-`RTE` handler | 175 | **PASS** |
| T3 | deterministic per-cycle arrival enumeration under M=1 | **384/384** | **PASS** |
| T4 | entry state checked from INSIDE the handler on every interrupt | 155 | **PASS** |
| T5 | NESTED M=1 interrupts — a throwaway inside a throwaway | 141 | **PASS** |
| T9a | M=1 `RTE` whose immediately-preceding instruction changed A7 by 2 (register-only) | 175 | **PASS** |
| T10 | M=1 entry taken while the mainline moves A7 by 2 in tight pairs | 162 | **PASS** |
| T11 | the same A7 churn under per-cycle arrival enumeration | **384/384** | **PASS** |
| T7 | T1's full frame audit with the **MMU ON**, 8 KB pages, TTRs disabled | 1 | **PASS** |
| T8 | M=1 storm with the **MMU ON**, 8 KB pages | 174 | **PASS** |

T1's frame-address audit, verbatim from the run — note the exact word granularity, which
is what makes it a 2-byte-resolution detector:

```
MASTER-stack frame writes:    0x0011fff8 x2 0x0011fffa x1 0x0011fffc x2 0x0011fffe x1
INTERRUPT-stack frame writes: 0x000ffff8 x2 0x000ffffa x1 0x000ffffc x2 0x000ffffe x1
```

(the `x2` counts are the guest's own poison longwords at `-8`/`-4` plus the frame word.)

T2's, across 175 entries — one write per address per entry, no drift at all:

```
MASTER-stack frame writes:    0x0011fff8 x176 0x0011fffa x175 0x0011fffc x176 0x0011fffe x175
INTERRUPT-stack frame writes: 0x000ffff8 x176 0x000ffffa x175 0x000ffffc x176 0x000ffffe x175
```

**Both named secondary gaps were closed and neither changed anything.** The MMU-on arms
(T7/T8) run the guest's own three-level 8 KB page tables with **all four TTRs zeroed**, so
every first touch is a genuine table walk — the exact p133 silicon posture, `TC = 0x0000c000`
— and the harness fails closed on `mmuEnable`/`pageSize8K` readback so the arm cannot
silently degrade into an MMU-off run. The nesting (T5) and long stack-heavy (T6) handlers
close the "the handler was a bare `RTE`" gap.

**T5 deserves its own line.** `ExceptionUnit.scala:727-733` carries an explicit, still-open
caveat that ends: *"A RAPID M re-toggle (M=0->1->0 within the settle window) is NOT
validated here ... Add a directed test for that BEFORE Slice B (interrupt throwaway frame)
relies on cross-toggle preservation."* That test was never written. T5 is it: the handler
re-sets M=1 (re-banking onto the master stack, on top of the frame entry just pushed
there), opens the mask so a nested — itself M=1, itself a throwaway — interrupt lands, then
toggles back. 141 entries, both stacks exact.

---

## 5. The defect that IS real — `RTE` does not resync `committedCcr`

### 5.1 How it was isolated

Two scenarios failed where the others passed, and the failure code alone was ambiguous. The
fail path was changed to also record **the value the failing check compared**. That single
change resolved it:

```
T14 (M=1, handler pushes/pops a word):  result=0xbad00033 (_f_bal_sr)  observed=0x00103000
```

The check is `move.w %sr,%d0 ; andi.w #0xff00,%d0 ; cmp.w #0x3000,%d0 ; bne`. The observed
`%d0` low word is `0x3000` — **exactly the value the compare wanted**. The architectural
state was right and the branch went the wrong way. That is a condition-code failure, not a
value failure, and the observed-value channel is what distinguishes them.

### 5.2 It is NOT M=1-specific — the control says so

| # | arm | handler | result |
|---|---|---|---|
| T12 | **M=1** | forces `{N=1,Z=0}`, no memory, no A7, no MOVEC | **FAIL** `0xbad00033`, observed `0x00103000` (correct value), 96 entries |
| T13 | **M=0 CONTROL** | identical handler | **FAIL** `0xbad00032`, observed `0x00100000` (correct value), 63 entries |

The M=0 control was deliberately given **2x the pulse budget** of the M=1 arm — a control
that clears the bar only because it ran less is worth nothing — and it still failed
earlier. So the defect lives in the shared, M-agnostic `RTE` path.

An earlier, weaker revision of this handler (`tst.l %d6` with `%d6 == 0`) passed both arms
**vacuously**: it leaves `{N=0,Z=1,V=0,C=0}`, byte-for-byte what the mainline's own
successful `cmp` leaves, so it could not detect a lost CCR at all. Both the vacuous pass
and its correction are recorded here rather than dropped.

### 5.3 The mechanism, exactly

`RobPlugin.committedCcr` (`RobPlugin.scala:2020-2027`) is advanced ONLY by `retire0`/
`retire1`'s per-µop NZVC/X fold, plus `exc.obsSetCcr5Valid` (which before this change only
MOVE-to-SR and STOP ever pulsed). `ccrForException` (`:2037`) builds the STACKED frame's SR
low byte from it. `RTE`'s `rteNzvcWrite`/`rteXWrite` (`ExceptionUnit.scala:1775-1778`) write
the REAL flags PRF — correctly — but never touch that shadow. So:

```
  cmp.l #K,%d0     retires        -> committedCcr = {Z=1};  PRF = {Z=1}
  bne   ...        IRQ #1 preempts it; the frame stacks {Z=1}            (correct)
  <handler disturbs the flags>    -> committedCcr = {N=1,Z=0}
  rte                             -> PRF := {Z=1} (correct); committedCcr STILL {N=1,Z=0}
  bne   ...        IRQ #2 preempts the SAME bne; the frame now stacks {N=1,Z=0}
  <handler> rte                   -> PRF := {N=1,Z=0}
  bne   ...        branches the WRONG WAY, with %d0 still holding the correct value
```

It needs two interrupts at the same flag-consuming instruction with no flag-WRITING
instruction retiring in between — which is why it takes tens of entries to surface, and why
it is invisible when the handler is a bare `RTE` (which cannot disturb the flags). Every
interrupt test in the corpus, and every scenario in Part 134, used a bare-`RTE` handler.

The comment that this change replaces stated the exact assumption the bug violates: *"the
CCR is restored from the frame too, but the whitebox carries it (RTE restores the same CCR
the matching exception entry saved -> reconstructed CCR holds)."*

### 5.4 The fix

`ExceptionUnit.scala`, `R_REDIR`'s real (non-throwaway) pop — two lines plus the comment
block that explains them:

```scala
        obsSetCcr5Valid := True
        obsSetCcr5      := popSr(4 downto 0)
```

`obsSetCcr5` is exactly the right port: `RobPlugin.scala:2217` already applies it as
`committedCcr := exc.obsSetCcr5` (last-wins over the retire fold), and the lock-step
whitebox already treats it as "set the running CCR to this absolute value for this obs
step" — which is precisely `RTE`'s architectural semantics. MOVE-to-SR / STOP already use
it the same way from `S_REDIR`; this is a third producer inside the SAME `fsm`, so
`whenIsActive` keeps them mutually exclusive by construction.

The format-`$1` throwaway pass deliberately does NOT pulse it: that pass fires no obs and is
not an architectural completion, and the second (real) pop lands the final value a few
cycles later with `excIdle` low throughout, so no exception entry can read the shadow in
between.

`git diff bb3bca1 -- src/main` is **1 file, +41/-3 lines** (2 of which are logic).

---

## 6. Before / after

Both arms are the SAME worktree, so the only variable is the two-line `src/main` change;
the test file is byte-identical between them.

| # | scenario | BEFORE (pristine `src/main`) | AFTER (fix) |
|---|---|---|---|
| T9b | M=1, handler push/pops a word before `RTE` | **FAIL** `0xbad00033`, observed `0x00103000` (the CORRECT value), at 44 entries / 230 pulses | **PASS**, 150 entries / 700 pulses |
| T1 | one M=1 interrupt, 15 architectural checks | PASS | PASS |
| T2 | M=1 storm, both banks | PASS, 175 entries | PASS, 181 entries |
| T3 | per-cycle arrival enumeration | PASS, 384/384 | PASS, 384/384 |
| T4 | entry state checked inside the handler | PASS, 155 | PASS, 141 |
| T5 | nested M=1 (throwaway inside a throwaway) | PASS, 141 | PASS, 141 |
| T6 | long, nesting, stack-heavy handler | (2 harness defects, see below) | **PASS**, 116 entries / 61 iterations |
| T9a | M=1 `RTE` after a register-only A7 change | PASS, 175 | PASS, 182 |
| T12 | **M=1**, handler disturbs only the flags | **FAIL** `0xbad00033`, observed `0x00103000`, at 96 entries | *not yet re-run — see §9* |
| T13 | **M=0 CONTROL**, same handler | **FAIL** `0xbad00032`, observed `0x00100000`, at 63 entries | *not yet re-run — see §9* |
| T14 | M=1, push/pop handler (register-safe rewrite of T9b) | **FAIL** `0xbad00033`, observed `0x00103000`, at 44 entries | *not yet re-run — see §9* |
| T15 | M=0 CONTROL, push/pop handler | PASS, 403 entries / 1400 pulses | *not yet re-run — see §9* |

**Three harness defects were found and fixed during this work, and are reported rather
than quietly corrected**, because two of them produced failures that looked like RTL bugs:

1. **T4's first handler used `%d0`/`%a0`** — the same scratch register the mainline's
   checks use between their `movec`/`move` and their `cmp`/`bne`. An interrupt landing in
   that gap left the handler's value in `%d0`, failing a check that had nothing wrong with
   it (`_f_bal_msp`, `0xbad00031`). All handlers now leave `%d0-%d3`, `%d5`, `%d7` and
   `%a1` untouched on the success path and use `%d4`/`%d6`/`%a0` as scratch.
2. **T5/T6's stack-balance checks were not nesting-safe** — they compared against a
   snapshot in `%d4` (T5) or a hard-coded `ISP-8` (T6), both of which a NESTED invocation
   of the same handler invalidates. Replaced with a marker longword pushed on the
   handler's own stack, which is correct at every nesting depth.
3. **The first `flagOnlyHandler` (`tst.l %d6` with `%d6 == 0`) passed VACUOUSLY** — it
   leaves `{N=0,Z=1,V=0,C=0}`, byte-for-byte what the mainline's own successful `cmp`
   leaves, so it could not detect a lost CCR at all. Corrected to force the opposite
   (`moveq #-1`), after which it fails in both banks. A control that cannot fail is worth
   nothing, and this one nearly went in the report as a pass.

**On the positive control the brief asked for.** No deliberately-injected defect was
needed for the CCR finding: T12/T13/T14 are *themselves* real failures on unmodified RTL
that the two-line change turns green, which is a stronger demonstration than an injected
one. For the stack-pointer NEGATIVE the detector's sensitivity is argued differently and
must be read as such — see §9's "what the frame audit can and cannot catch".

---

## 7. What this does and does not say about p133

**It does not fix the boot, and it is not the mechanism the captured fault names.** p133's
signature is SP exactly 2 bytes low with an odd return address popped by an `RTS`. Nothing
here corrupts a stack pointer.

The honest connection, offered as a hypothesis and not a claim: a `Bcc` that branches the
wrong way **around a stack adjustment** produces a stack pointer off by exactly that
adjustment. `addq.l #2,%sp`, `move.w (%sp)+,%dn` and word-sized pops are extremely common in
68k ROM code, and the ROM's interrupt handlers do disturb the flags. So this defect is
*capable* of producing a 2-byte SP error at a distance, in a way that the interrupted
instruction itself would look innocent for. That is worth stating because it is the only
mechanism this investigation found that can produce the p133 signature at all — but it is
not evidence that it did. Confirming or refuting it needs a board measurement, not more
simulation.

The measurement Part 134 §9 asked for is still the discriminating one, and is still not
taken: catch a drain-loop IRQ on silicon and read `A7` immediately before entry and
immediately after the handler's `RTE`.

---

## 8. Open / not fixed

* **`RTE` does not restore the CCR on the format-`$1` throwaway pass**, where Musashi's
  `rte` `case 1` does (`m68ki_set_sr_noint(sr)` sets the FULL SR including the CCR before
  `goto rte_loop`). This is architecturally invisible here because the second pop lands the
  same CCR a few cycles later and nothing can observe the interval. Left alone deliberately:
  changing it is a behaviour change with no test that can see it.
* **`ss.msp` is transiently clobbered during a throwaway `RTE`.** After pass 1 restores M=1,
  `ss.writeA7` (which fires every cycle, routed by committed S,M) starts mirroring the
  live committed A7 — still the HANDLER's ISP value — into `ss.msp`, until pass 2's
  explicit `setMsp` overwrites it with the correct value. Nothing reads `ss.msp` in that
  window on the normal path (`frameBase` was already latched), so it is benign there. It is
  NOT benign on one path: if the second frame's format nibble is malformed, `R_FMTWAIT`
  synthesises a vector-14 entry that routes through `E_DRAIN`, which recomputes
  `frameBase` from `Mux(ss.m, ss.msp, ss.isp)` — and would read the clobbered value. That
  needs a hand-corrupted frame to reach, does not occur in the p133 boot, and is reported
  rather than fixed (fixing it is a `src/main` change for a defect nothing has observed).
* **`exc_partial_macro_move_mem_mem`** (sentinel `0xbad0a008`) still fails on baseline. Not
  re-litigated: Part 134 §6's reasoning that it is not the p133 blocker is sound.
* The `[Warning] Elaboration failed (20 errors).` line Part 134 §8 flagged appears in every
  run of this suite too. Same pre-existing `FuzzCoreDut` SpinalSim compile-path artefact;
  the positive/negative discrimination in §5 proves the netlist really is regenerated when
  the source changes. Flagged, not investigated.

---

## 9. Verification — and what was NOT run

### What the frame audit can and cannot catch

The stack-pointer negative rests on the sim-side audit, so its sensitivity has to be
stated rather than assumed. It records the address of **every** AXI write that lands in
either stack's low 256-byte window and asserts the set is exactly
`{SP-8, SP-6, SP-4, SP-2}`. T1's output shows the granularity is a single **word** — the
frame's four words appear as four distinct addresses two bytes apart — so a stack pointer
2 bytes off writes to an address that is not in the set, on its very first frame. And the
error would be cumulative: entry N's frame would sit 2N bytes low, so across T2's 175
entries a drift would produce 175 distinct addresses marching down the stack rather than
the 4 observed. This channel is independent of the guest program being correct at all.

What it cannot catch: a drift that is introduced AND undone entirely inside the FSM
without any frame word being written from the wrong base, and anything on a COPYBACK page
(which is why the MMU-on arms, whose pages are copyback per the p133 posture, rely on the
guest-side checks and the final bank readback instead).

### Runs completed

| suite | arm | result |
|---|---|---|
| `M1ThrowawayFrameIrqSpec` T1-T11 | pristine `src/main` | 8 pass / T9b fail (§6) / T5,T6 harness defects since fixed |
| `M1ThrowawayFrameIrqSpec` T12-T15 | pristine `src/main` | T12,T13,T14 **FAIL**, T15 pass (§5.2) |
| `M1ThrowawayFrameIrqSpec` T1-T6, T9a, T9b | with the fix | **8/8 PASS** — T9b flipped from FAIL |

### NOT RUN — stated plainly

The host filled up mid-campaign: a KU5P `full_impl` synth gate went to ~12.5 GB with 1 GB
free and 10 GB of swap in use, alongside two heavy test JVMs. **The in-flight run was
deliberately killed** (it was at ~7.9 GB resident and roughly 10 scenarios from done)
rather than risk OOM-killing a gate that had already waited hours for the Vivado mutex.
Available memory went 6 GB -> 13.9 GB immediately. That is the correct trade and it is why
the following are missing:

* **T12 / T13 / T14 / T15 on the FIXED arm.** These are the cleanest before/after pair for
  the CCR fix and they are NOT yet measured after the change. The evidence that the fix
  works currently rests on **T9b alone** (FAIL at 44 entries -> PASS at 150 entries with
  700 pulses), plus the mechanism being understood well enough to predict the failure
  before it was reproduced. That is real but it is one data point; treat the fix as
  **plausible and unregressed, not yet demonstrated**, until T12/T13 are re-run.
* **T16 / T17** (the CCR-survival probe that reads the corrupted CCR back architecturally
  instead of inferring it from a branch outcome) were written but have run on NEITHER arm.
* **T7 / T8 / T10 / T11 on the fixed arm** — they pass on the pristine arm and were not
  re-measured after the change.
* **`make test-fast`** — not run on either arm.
* **`ExecuteLockStepSpec`** — not run on either arm. This is the most important missing
  check for this change: the fix pulses `obsSetCcr5Valid` on `RTE`, which the lock-step
  whitebox consumes as "set the running CCR to this absolute value for this obs step". That
  is believed correct (it is exactly RTE's architectural semantics, and it replaces an
  assumption the whitebox was making implicitly), but it is a semantic change to the
  lock-step comparison path and it has not been measured.
* **`m68k040.ls.*` / `m68k040.cache.*`** — not run on either arm.
* **The 200-seed fuzz** — not run. The standing count is 3 (seeds 80, 109, 127). This
  change touches `src/main`, so the fuzz count is NOT provably unchanged by construction
  the way Part 134's was, and it must be measured before this goes anywhere.
* **The postroute synth gate** — not run. This IS a `src/main` change, so the standing rule
  requires it. The change adds two combinational assignments to two already-existing ports
  inside an already-existing FSM state (a third producer alongside `S_REDIR`'s), so the
  expected timing impact is a one-input widening of two small muxes — but that is an
  argument, not a measurement, and the rule asks for a measurement.
* **No board work.** SD card, JTAG lease and `hw_server` untouched. `/var/tmp/m68k-ooo-vivado.lock`
  was probed once (free at the time) and **never taken**.

**The branch is `investigate/p135-m1-throwaway-frame` and is deliberately NOT merged.**
Given the outstanding verification above, the `src/main` change on it should be treated as
a proposed fix with one supporting measurement, not as a landed one.
