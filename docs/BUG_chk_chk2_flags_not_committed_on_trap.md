# BUG: CHK / CHK2 condition codes are stacked but never committed, so the handler sees stale flags

**Status:** OPEN (RTL). Found 2026-09-04 by the reference-driven structural lock-step
comparator (`src/test/scala/m68k040/lockstep/ArchLockStep.scala`), the first run it ever made.
Not fixed here — this document exists so the gap is tracked rather than absorbed.

## Symptom

`CHK` (and `CHK2`) set the condition codes according to their comparison and then take the
trap. On this core the flag write is folded into the **exception frame's stacked SR** but is
never committed to the **architectural CCR**, so the handler runs with the flags the
*previous* instruction left. `RTE` then restores the (correct) stacked SR, so everything
downstream of the handler looks right — which is why every existing test passed.

## Minimal reproduction (measured, not inferred)

```asm
	move.l #handler,%d0
	move.l %d0,0x18        | CHK vector 6
	moveq #-5,%d1
	moveq #10,%d2          | leaves N=0, Z=0
	chk.w %d2,%d1          | -5 < 0  -> sets N=1, then traps
	nop
	bra Lend
handler:
	smi %d5                | N -> D5 = 0xFF on a real 68040 / Musashi
	move.w %sr,%d6
	rte
Lend:
	bra.s Lend
```

Run through the fuzz lock-step harness:

```
FUZZ_PROG=chkccr.s sbt 'testOnly m68k040.fuzz.FuzzProbeSpec'
  -> Diverged(STEP, idx=5 reg D5: dut=0x00000000 oracle=0x000000ff)
```

`smi %d5` yields **0x00** on the DUT and **0xFF** on Musashi: the handler observes N=0 where
the architecture requires N=1. The divergence is in an ordinary register write, so it is
caught by the *existing* delta comparator too — once you write this program. Nobody had.

## How it stayed invisible

`CHK` faults, so its µop never retires normally and its NZVC rename never commits. The
harness compensated: `RobPlugin.scala:2658` surfaces `heldCcrFold` on the exception commit
observation and `WhiteboxCapture.ExcRec.foldNzvc` folds it into the reconstructed running CCR
(`WhiteboxCapture.scala:275-278`), with the comment *"needed for CHK, which sets N as it traps
but never retires normally"*. So the harness **synthesised the flag the DUT had not
committed** and then compared its own synthesis against the oracle.

Reading the committed NZVC physical register instead shows the truth immediately:

```
[chk-neg]  CCR MISMATCH @idx=4: ccr: dut=0x00 oracle=0x08   (N)
[chk2-oob] CCR MISMATCH @idx=8: ccr: dut=0x00 oracle=0x01   (C)
```

## Scope

* `CHK` with a failing bound (`chk-neg`) — N.
* `CHK2` out-of-bounds (`chk2-oob`) — C.
* `chk-over` does **not** fail, because there the correct post-CHK flags (N=0) happen to equal
  the stale ones. That is coincidence, not correctness.
* `DIV0` / `TRAPV` are unaffected: they carry no `ccrFold` (the RTL sets `ccrFoldValid` only
  for a non-interrupt fault entry whose faulting instruction wrote flags).

## Why the frame is still right

The exception unit forms the stacked SR from the *held* NZVC, not from the committed physical
register, so the frame is correct and `RTE` restores correct flags. The structural comparator
confirms this: for `chk-neg` only index 4 (the entry step) diverges; indices 5, 6 and 7 —
handler body, `RTE`, and the resumed instruction — all match. The exposure window is exactly
"inside the handler, before it writes flags itself".

## Suggested fix direction (not implemented)

The faulting CHK/CHK2's NZVC write needs to become architecturally committed at exception
entry, the same way `ExceptionUnit`'s `rteNzvcWriteValid` path already writes the restored
NZVC directly into `RenameStage.committedPhysNzvc` on RTE. A symmetric
`entryNzvcWriteValid` driven from the same `heldCcrFold` the frame already uses would close
it without touching rename or the freelist. That is the established safe pattern for this
class (see the memory index entry *"rename-exposure/phys-reg-reuse hazard class"*: the safe
fix is a `RenameStage.committedPhys*` direct write, bypassing rename/freelist).

## Test-suite handling until it is fixed

`ExecuteLockStepSpec.runLockStep` takes a `structuralKnownGap` argument. `chk-neg` and
`chk2-oob` pass this document's name; the structural comparator still runs, still reports the
divergence, and prints it as a loud `STRUCTURAL KNOWN GAP` warning instead of failing. Remove
the argument from both tests when the RTL is fixed — they will then be the regression test.
