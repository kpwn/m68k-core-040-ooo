| fpu_divbyzero_exception.s — FDIV by 0 routes to vec 49 (div-by-zero).
|
| Goal: with FPCR.DZ-enable bit set, FDIV FP0,FP1 where FP1 is +0.0
| should raise the IEEE divide-by-zero exception, dispatching to
| vector 49 ("FP divide by zero" — 68040 PRM §B.1.6).  Frame format
| should be type 3 (4 longwords beyond the format word) per 68040 UM
| §8.5.
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
|
| OBSERVED on main: assembles; runtime DEAD0F01.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_dz_handler,    0x000000C4   | vec 49 = 0xC4 (49*4)
    move.l  #_fline,         0x0000002C   | vec 11 (F-line)
    move.l  #_fail_handler,  0x00000010   | vec 4 (illegal — fallback)

    | Enable DZ exception in FPCR.  FPCR.DZ-enable is bit 10 (0x400).
    move.l  #0x00000400, %d0
    .short  0xF200, 0x9000               | FMOVE.L D0,FPCR

    | Load FP0 := 1.0
    move.l  #0x3F800000, %d0
    .short  0xF200, 0x4000               | FMOVE.S D0,FP0

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
    | vec 49 fired — PASS.
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

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
