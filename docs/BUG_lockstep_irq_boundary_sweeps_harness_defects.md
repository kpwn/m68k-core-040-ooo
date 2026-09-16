# The IRQ boundary-sweep lock-step tests fail for HARNESS reasons, not core reasons

**Status: partially fixed (2026-09-16). Test-only changes; `src/main` is untouched.**

Applies to `odd-ssp: ... IRQ at every boundary of the LINK #-75 stretch ...` (22 tests,
all failing on `closure-200mhz` @ `817bf43d`) and to `a7-byte: IRQ at every boundary of
the SwapMMUMode byte push/pop idiom`.

## 0. What is NOT true

* `a7-byte: IRQ at every boundary ...` is **NOT a merge regression.** Measured on
  `817bf43d`: passes solo (1/1) and in-suite. Whole-suite on `817bf43d`: 779 tests,
  757 passed, **22 failed, and all 22 are the odd-ssp LINK family** -- no a7-byte
  failure, no board-repro failure, nothing else.
* Keying the IPL poke on MACRO retirement instead of per-uop -- what `9635f91c` did and
  `599180b7` reverted -- **DOES NOT FIX THIS.** Re-applied and measured: it REGRESSES
  `a7-byte` (b5 diverges on pc, b7-late on pc) and leaves odd-ssp boot-0/2/4/6/8/10/12
  still failing. Net loss. Do not re-try it without new evidence. (The reasoning behind
  it is sound and is recorded in the code comment; it is simply not sufficient.)

## 1. DEFECT A -- uninitialised data registers (REAL, fixed)

Musashi **zeroes every register at reset**; SpinalSim **randomises the PRF per seed**. A
BYTE or WORD write leaves the upper bits at that random value, and the lock-step compares
the committed 32-bit value -- so the test is red whenever the random init is non-zero.

Measured, three runs of the SAME test, three different garbage uppers:

    reg D1: dut=0x0d7c003f / 0xa98e003f / 0xccf9003f   oracle=0x0000003f   (index 4)
    reg D2: dut=0x4c1b9d00   oracle=0x00000000
    reg D3: dut=0xb058b600   oracle=0x00000000
    reg D1: dut=0xf6e68601   oracle=0x00000001         (a7-irq)

`0x3f` = 63, at index 4, is `oddSspFill`'s own first instruction:

    private val oddSspFill = Seq("move.w #63,%d1", ...)   // WORD write, D1 never initialised

so EVERY odd-ssp test built on that fill was seed-dependently red **before the interrupt
was even involved**. `a7IrqSrc` has the same hole in D1/D2 (idiom) and D5/D6 (handlers);
it initialises only D0.

**Fix:** `moveq #63,%d1` in the fill (a long write; D1 is only a loop counter, so nothing
the test exercises changes), plus `moveq #0` for D2/D3 in `oddSspLinkSrc` and for
D1/D2/D5/D6 in `a7IrqSrc`. `Pre` in the a7-irq test is a hardcoded preamble count and was
bumped 15 -> 19 to match.

**This is very likely the explanation for a7-byte "failing" for one person and passing for
another: it is a seed-dependent flake, not a regression.**

## 2. DEFECT B -- `.distinct` broke retry-window adjacency (REAL, fixed)

    val boundaryPcs = (firstOdd - 1 to lastOdd + 2).map(plain(_).pc).distinct
    val lateWindow  = ((i to i+2) ++ ((i-1) to (i-2) by -1))    // INDEX arithmetic

The window was index arithmetic over the DISTINCT list. Wherever the stretch re-enters a
pc -- three `.short 0xa06e` A-line traps share one handler, and `sub1: rts` is entered by
BOTH `jsr (%a1)` and `bsr.s sub1` -- the distinct list SKIPS boundaries, so an `i±2` LIST
window does not cover the `i±1` PROGRAM window.

**Fix:** the window now walks a non-distinct, program-ordered `boundarySeq`; `.distinct`
survives only to avoid duplicate simulations.

## 3. DEFECT C -- the failure dump lied by omission (REAL, fixed)

`ExecuteLockStepSpec.scala:1440-1446` printed only `pc/sr/a7`, while `LockStep.compare`
checks pc, ccr, full SR, a7, msp, isp AND the committed architectural register write (plus
the 2nd destination of a cracked DIV.L/MUL.L). A genuine `reg D3` divergence therefore
produced a dump in which **every visible column matched** -- indistinguishable from "no
divergence here". Defect A was invisible behind this for the entire investigation.

**Fix:** the dump now leads with `res.firstDivergence.detail` -- which already named the
field and both values and was being discarded -- and carries the register write and CCR,
with `<<<` marking the diverging index.

## 4. DEFECT D -- the oracle cannot name a re-visited pc (REAL, NOT fixed, documented)

`Musashi.assembleAndTrace(..., irqEvents = Seq((pc, level)))` names the interrupt by a
PROGRAM COUNTER. For any pc the program visits more than once the oracle takes the
interrupt at the FIRST visit, and there is no way to ask for the second. "Before the
SECOND visit of `sub1`'s rts" is therefore inexpressible, and a DUT that legitimately
lands there can match NO oracle. A real fix needs an ordinal-aware `(pc, nth-visit)` event
in the oracle. Documented loudly at the `boundarySeq` definition.

## 5. The residual, and why it is NOT a core defect

After A/B/C the remaining odd-ssp failures are `pc` divergences -- the interrupt landing on
a different macro boundary than any oracle in the window. The DUT is architecturally
correct at every one of them. Worked example (boundary 9 vs the boundary-11 oracle):

    idx   DUT pc/sr/a7                      ORACLE pc/sr/a7
    149   0x4080006a/0x2008/0x000fffb1  |   0x4080006a/0x2008/0x000fffb1
    150   0x408000b6/0x2108/0x000fffa9  |   0x4080006c/0x2000/0x000fffb1   DUT enters handler
    151   0x4080006a/0x2008/0x000fffb1  |   0x408000b6/0x2100/0x000fffa9   oracle enters handler
    152   0x4080006c/0x2000/0x000fffb1  |   0x4080006c/0x2000/0x000fffb1   rejoined

Both push the SAME frame base `0x000fffa9`; each side's SR is self-consistent with where it
entered (DUT still has N set, having not yet run the N-clearing insn at `0x4080006a`); the
RTE restores `0x4080006a` and the streams rejoin exactly. Nothing is corrupted. The odd SSP
itself is legitimate Mac OS behaviour (`linkw %fp,#-75`).
