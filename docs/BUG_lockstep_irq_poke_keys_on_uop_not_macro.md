# The IRQ lock-step harness keys its IPL poke on a UOP boundary, not a MACRO boundary

**Status: FIXED (2026-09-16).** This was a TEST-HARNESS defect, not a core defect. It
made all 22 `odd-ssp: ... IRQ at every boundary of the LINK #-75 stretch ...` tests fail
against a DUT that is architecturally correct at every step.

## 1. Symptom

```
[odd-ssp-irq1-0] boundary 9 (0x40800066): the DUT lock-stepped against NONE of the
                 boundary-9/10/11/8/7 oracles.
  ... lock-step diverged: Divergence(147, pc: dut=0x40800062 oracle=0x408000b6)
```

## 2. The DUT is CORRECT -- proven from the divergence dump

`odd-ssp-irq1-0`, boundary 9, retried against the boundary-11 oracle:

```
idx   DUT pc/sr/a7                      ORACLE(b11) pc/sr/a7
149   0x4080006a/0x2008/0x000fffb1  |   0x4080006a/0x2008/0x000fffb1
150   0x408000b6/0x2108/0x000fffa9  |   0x4080006c/0x2000/0x000fffb1   <- DUT enters handler
151   0x4080006a/0x2008/0x000fffb1  |   0x408000b6/0x2100/0x000fffa9   <- oracle enters handler
152   0x4080006c/0x2000/0x000fffb1  |   0x4080006c/0x2000/0x000fffb1   <- rejoined
```

* BOTH sides push the **same frame base** `0x000fffa9`.
* Each side's SR is **self-consistent with where it entered**: the DUT still has `N` set
  (`0x2108`) because it has not yet executed the instruction at `0x4080006a` that clears
  it; the oracle has executed it (`0x2100`).
* The DUT's RTE restores `0x4080006a` and the two streams **rejoin exactly**.

Nothing is corrupted. The ONLY difference is WHICH macro boundary recognised the
interrupt -- and the DUT lands strictly BETWEEN two adjacent oracles the retry window
offered, so no oracle in the window could match.

## 3. Root cause

`RobPlugin.scala:1472`

    val commitPc0 = Mux(p0.retireAlone, nextPcRd0, p0.predNextPc)

is the retiring **UOP's** next pc -- for a branch uop the RESOLVED target, for everything
else the sequential `pc + len`. `ExecuteLockStepSpec.runIrqLockStep` keyed its reactive
IPL poke on that, **per uop**.

`MicroOpAssembler` cracks JSR/BSR into `[push retPC]` + `[ibranch]`. The PUSH uop's
sequential next pc **IS the return address** = the pc of the instruction after the call.
So `eventPcs.contains(rawPc0)` matched on the PUSH uop's retire and raised IPL **one whole
macro early**, and the DUT took the interrupt before the callee's `rts`.

The odd-ssp program enters one `sub1: rts` TWICE (`jsr (%a1)` then `bsr.s sub1`), so the
early poke lands on "before the SECOND visit of sub1's rts" -- a boundary the pc-keyed
oracle cannot express at all (SS4).

## 4. Fix

The correct signal already existed three lines below the culprit:

    RobPlugin.scala:1475   debugMacroRetirePc(0).valid := retire0 && p0.last

but its `.valid` was not `simPublic`, so the harness could not read it. `9635f91c` added
that `simPublic`; `599180b7` reverted it with no stated reason. Re-applied, and the poke
is gated on `debugMacroRetirePc(k).valid`. Sim-only: a `simPublic` marks a net for the
simulator and generates no hardware.

## 5. Two traps fixed alongside, because they cost an investigation each

**(a) The failure dump lied by omission.** `ExecuteLockStepSpec.scala:1440-1446` printed
only `pc/sr/a7`, while `LockStep.compare` checks pc, ccr, full SR, a7, msp, isp AND the
committed architectural register write (plus the 2nd destination of a cracked DIV.L /
MUL.L). A genuine `reg D3` or `ccr` divergence therefore produced a dump in which EVERY
VISIBLE COLUMN MATCHED -- indistinguishable from "nothing wrong here". The dump now leads
with `res.firstDivergence.detail` (which already named the field and both values and was
being discarded) and carries the register write + CCR, with `<<<` on the diverging index.

**(b) `.distinct` broke window adjacency.** `boundaryPcs` was
`(firstOdd-1 to lastOdd+2).map(plain(_).pc).distinct`, and the retry window was index
arithmetic `i-2 .. i+2` over that DISTINCT list. Wherever the stretch re-enters a pc --
three `.short 0xa06e` A-line traps share one handler, and `sub1: rts` is entered twice --
the distinct list SKIPS boundaries, so an `i±2` LIST window does not cover the `i±1`
PROGRAM window. The window now walks a non-distinct, program-ordered `boundarySeq`;
`.distinct` survives only to avoid duplicate simulations.

## 6. Oracle limitation -- NOT fixed, documented loudly at the definition

`Musashi.assembleAndTrace(..., irqEvents = Seq((pc, level)))` names the interrupt by a
PROGRAM COUNTER. For any pc the program visits more than once, the oracle takes the
interrupt at the FIRST visit and there is no way to ask for the second. "Before the SECOND
visit of sub1's rts" is therefore inexpressible, and a DUT that legitimately lands there
can match NO oracle. Keeping the poke on MACRO retirement is what stops the DUT being
pushed onto such a boundary in the first place. A proper fix would need an
ordinal-aware (`pc`, `nth-visit`) event in the oracle.

## 7. Explicitly NOT claimed

* Not claimed that the core mishandles an odd SSP. The opposite: these sweeps were failing
  on a DUT whose every architectural value matched.
* The odd SSP itself is legitimate Mac OS behaviour (`linkw %fp,#-75`), see
  the project memory note `link-odd-frame-odd-ssp-2026-09-09`.
