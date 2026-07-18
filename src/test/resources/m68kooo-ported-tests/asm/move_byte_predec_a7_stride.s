| move_byte_predec_a7_stride.s — MOVE.B Dn,-(A7) / -(An) byte strides
| through the fused predec-store crack (2026-07 decode µop-order fix:
| MOVE reg,-(An) is now a single STORE at [An - delta] with EA
| writeback instead of SUB-then-STORE).
|
| Musashi golden: EA_A7_PD_8 decrements A7 by TWO (stack stays word-
| aligned) and writes the byte at the NEW A7; plain An byte predec
| decrements by one.

    .text
    .org 0

    .equ SCRATCH, 0x00100000

_start:
    | ── Part 0: MOVE.B D0,-(A7) — A7 byte stride is 2 ──
    move.l  %sp, %d5                | save harness SP
    lea     SCRATCH+0x800, %sp
    clr.l   SCRATCH+0x7FC
    move.l  #0x5A, %d0
    move.b  %d0, -(%sp)
    move.l  %sp, %d1
    cmp.l   #SCRATCH+0x7FE, %d1     | A7 -= 2 (not 1)
    bne     _fail0
    move.b  SCRATCH+0x7FE, %d2
    and.l   #0xFF, %d2
    cmp.l   #0x5A, %d2              | byte lands at the NEW A7
    bne     _fail1
    move.b  SCRATCH+0x7FF, %d2
    and.l   #0xFF, %d2
    tst.l   %d2                     | pad byte untouched
    bne     _fail2
    move.l  %d5, %sp                | restore harness SP

    | ── Part 1: MOVE.B D0,-(A4) — plain An byte stride is 1 ──
    lea     SCRATCH+0x900, %a4
    clr.l   SCRATCH+0x8FC
    move.l  #0xA5, %d0
    move.b  %d0, -(%a4)
    move.l  %a4, %d1
    cmp.l   #SCRATCH+0x8FF, %d1     | A4 -= 1
    bne     _fail3
    move.b  SCRATCH+0x8FF, %d2
    and.l   #0xFF, %d2
    cmp.l   #0xA5, %d2
    bne     _fail4

    | ── Part 2: MOVE.W D0,-(A7) — word push, stride 2 ──
    move.l  %sp, %d5
    lea     SCRATCH+0xA00, %sp
    clr.l   SCRATCH+0x9FC
    move.l  #0x1234, %d0
    move.w  %d0, -(%sp)
    move.l  %sp, %d1
    cmp.l   #SCRATCH+0x9FE, %d1
    bne     _fail5
    move.w  SCRATCH+0x9FE, %d2
    and.l   #0xFFFF, %d2
    cmp.l   #0x1234, %d2
    bne     _fail6
    move.l  %d5, %sp

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail0:
    move.l  #0xDEAD0000, %d7
    bra     _fail
_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d7
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d7
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d7
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
