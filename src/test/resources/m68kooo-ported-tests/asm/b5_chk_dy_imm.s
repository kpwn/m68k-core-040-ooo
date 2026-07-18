| b5_chk_dy_imm.s — CHK.{W,L} Dy and #imm in-bound paths.
|
| Existing tests (chk_bounds.s, chk_l_basic.s) cover the trap path (Dn
| out of [0..bound] → vec-6).  This test covers the in-bound path —
| CHK falls through (no trap) — for all 4 V2 cells:
|   CHK.W Dy,Dn      with Dn in range
|   CHK.W #imm16,Dn  with Dn in range
|   CHK.L Dy,Dn      with Dn in range
|   CHK.L #imm32,Dn  with Dn in range
| Each cell completes without raising the trap; counter D7 advances
| only if all 4 in-bound checks pass.
|
| PASS: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Install vec-6 handler that signals FAIL (any trap = wrong).
    move.l  #_unexpected_trap, 0x00000018

    moveq   #0, %d7                  | counter

    | --- CHK.W D1,D0: D0=10, D1=100; 0 <= 10 <= 100 → no trap.
    move.l  #100, %d1
    move.l  #10, %d0
    chk.w   %d1, %d0
    addq.l  #1, %d7

    | --- CHK.W #100,D0: D0=10 → no trap.
    chk.w   #100, %d0
    addq.l  #1, %d7

    | --- CHK.L D1,D0: D0 = 0x12345, D1 = 0x100000 → no trap.
    move.l  #0x00100000, %d1
    move.l  #0x00012345, %d0
    chk.l   %d1, %d0
    addq.l  #1, %d7

    | --- CHK.L #imm32,D0: same value, immediate bound.
    chk.l   #0x00100000, %d0
    addq.l  #1, %d7

    cmp.l   #4, %d7
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_unexpected_trap:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_halt_trap:
    bra     _halt_trap
