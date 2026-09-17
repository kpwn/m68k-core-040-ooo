| fpu_divbyzero_exception.s — FDIV by 0 routes to vec 49 (div-by-zero).
|
| Goal: with FPCR.DZ-enable bit set, FDIV FP0,FP1 where FP1 is +0.0
| should raise the IEEE divide-by-zero exception, dispatching to
| vector 50 ("Floating-Point Divide by Zero", offset $0C8).
|
| ⚠ CORRECTED 2026-09-17.  This test used to install its handler at
| offset 0xC4 and its header claimed "vector 49".  That is WRONG, and it
| is the reason the test HUNG rather than failing: vector 49 is FP
| INEXACT RESULT, so the DZ trap dispatched through an UNINITIALISED
| vector 50 slot and ran off into memory.  MC68040UM Table 8-1 (the
| floating-point block of the vector assignments) is explicit:
|
|     48  $0C0  Floating-Point Branch or Set on Unordered Condition
|     49  $0C4  Floating-Point Inexact Result
|     50  $0C8  Floating-Point Divide by Zero
|     51  $0CC  Floating-Point Underflow
|     52  $0D0  Floating-Point Operand Error
|     53  $0D4  Floating-Point Overflow
|     54  $0D8  Floating-Point SNAN
|     55  $0DC  Floating-Point Unimplemented Data Type
|
| The RTL already agreed with the manual (`FpVector.Dz == 50`,
| DivEuPlugin.scala); only the test disagreed.  Vector 49 is now wired to
| its own reporter so a genuine mis-vector is DIAGNOSED instead of
| hanging, and so is the generic catch-all.
|
| Setup:
|   FPCR.DZ-enable = bit 10 (per 68881 PRM §1.2.1, propagated by
|   68040 FPU support).  Set FPCR = 0x00000400.
|   FP0 := some non-zero (e.g. 1.0 = 0x3F800000)
|   FP1 := +0.0  (already after reset)
|   FDIV FP1,FP0 -> FP0 / FP1 -> div-by-zero.
|
| Note FDIV.X FPm,FPn computes FPn := FPn / FPm.  So FDIV FP1,FP0
| computes FP0/FP1 = 1.0 / 0.0 -> +inf with DZ raised.
|
| EXPECTED OUTCOME: With current decoder, FMOVE.L Dn,FPCR is not
| decoded (vec-11 F-line); the test cannot get to the divide-by-zero
| state.  Even if it did, the FPU back-end may not raise IEEE flags
| or route to vec 49.  Recorded as expected DEAD0F01 today.
|
| PASS sentinel: 0xC0FFEE00 when vec-49 fires.
| FAIL sentinels:
|   0xDEAD0F01 — vec-11 F-line (FPCR setup undecoded)
|   0xDEAD0F0A — FDIV completed without trap (no DZ exception)
|   0xDEAD0F49 — vector 49 (FP INEXACT) taken instead of 50 (FP DZ)
|   0xDEAD0FFE — vector 4 (illegal) taken — wrong vector entirely

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_dz_handler,    0x000000C8   | vec 50 = 0xC8 (50*4) — FP divide by zero
    move.l  #_inex_handler,  0x000000C4   | vec 49 = 0xC4 (49*4) — FP inexact (must NOT fire)
    move.l  #_fline,         0x0000002C   | vec 11 (F-line)
    move.l  #_fail_handler,  0x00000010   | vec 4 (illegal — fallback)

    | Enable DZ exception in FPCR.  FPCR.DZ-enable is bit 10 (0x400).
    move.l  #0x00000400, %d0
    .short  0xF200, 0x9000               | FMOVE.L D0,FPCR

    | Load FP0 := 1.0.  The SOURCE SPECIFIER (ext[12:10]) must be 001 = SINGLE,
    | i.e. ext 0x44xx.  0x4000 is specifier 000 = LONG-WORD INTEGER and would
    | load the bit pattern as the integer 1065353216 -- the documented
    | "wrong-encoding family" that produced six spurious FP reds in this corpus.
    | (The DZ outcome is the same for any non-zero dividend, but the comment
    | above must not lie about what is in FP0.)
    move.l  #0x3F800000, %d0
    .short  0xF200, 0x4400               | FMOVE.S D0,FP0

    | FP1 stays at +0.0 from reset.

    | FDIV FP1,FP0 -> FP0 := FP0 / FP1 -> divide-by-zero
    | ext = (1<<10) | (0<<7) | 0x20 = 0x0420
    .short  0xF200, 0x0420

    | If we reach here, no exception was raised.
_no_trap:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F0A, %d2
    move.l  %d2, (%a1)
_halt_no_trap:
    bra     _halt_no_trap

_dz_handler:
    | vec 50 fired — PASS.
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_inex_handler:
    | vec 49 fired: the DZ condition was delivered on the INEXACT vector.
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F49, %d2
    move.l  %d2, (%a1)
_halt_inex:
    bra     _halt_inex

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_halt_fline:
    bra     _halt_fline

_fail_handler:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0FFE, %d2             | wrong vector taken
    move.l  %d2, (%a1)
_halt_fh:
    bra     _halt_fh
