| move_usp_roundtrip.s — MOVE An,USP + MOVE USP,An (Stage D-7)
|
| Supervisor-only.  Exercises both directions of the V2 MOVE USP
| emission shape:
|   MOVE A0, USP  (SYS_MOVE_AN_USP, arch_src_a = A0)
|   MOVE USP, A1  (SYS_MOVE_USP_AN, arch_dst   = A1)
|
| Load a distinctive value into A0, write it to USP, read it back into
| A1, and compare.  A1 must equal A0 or we emit the FAIL sentinel.
|
| Note: we stay in supervisor mode for the whole test; commit gates
| requires_supervisor=1 retires through the normal dispatch path when
| SR.S=1 (which it is on reset).
|
| PASS: A0 == A1, C0FFEE00 sentinel.
| FAIL: readback mismatch.

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    move.l  #0x12345678, %a0
    move.l  %a0, %usp

    movea.l #0, %a1
    move.l  %usp, %a1
    cmp.l   %a0, %a1
    bne     _fail

    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a2)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a2)
_halt_fail:
    bra     _halt_fail
