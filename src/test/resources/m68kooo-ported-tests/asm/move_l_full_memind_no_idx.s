| move_l_full_memind_no_idx.s — Task #202 (B1)
|
| Directed test for MOVE.L with full-format memory-indirect NO-INDEX
| source, Dn-direct destination.  I/IS=100 with IS=1 (Xn suppressed).
|   EA = [bd + A1]        (od defaults to null / 0 — no od ext word)
|
| Opword / ext1:
|   opword = 0010_000_000_110_001    = 0x2031
|   ext1   = D_A=0 | idx=000 (don't care, IS=1) | WL=0 | SCALE=0
|            | bit8=1 | BS=0 | IS=1 | BD_SIZE=10 (word) | bit3=0
|            | I/IS=100 (memind no-index)
|          = 0b0_000_0_00_1_0_1_10_0_100 = 0x0164
|
| Memory layout:
|   A1 = 0x00114000
|   bd = +32 (0x0020)    |  [A1 + 32] holds the memory-indirect pointer
|   [0x00114020] = 0x00114200   | the indirect pointer
|   [0x00114200] = 0xDEADBEEF   | loaded into D0

    .text
    .org 0

_start:
    | Stage the memory slots.
    lea     0x00114020, %a6
    move.l  #0x00114200, (%a6)
    lea     0x00114200, %a6
    move.l  #0xDEADBEEF, (%a6)

    | Load base + clear destination.
    lea     0x00114000, %a1
    moveq   #0, %d0

    | MOVE.L ([+32,A1]), D0
    .word   0x2031, 0x0164, 0x0020

    cmp.l   #0xDEADBEEF, %d0
    bne     _fail1

    | After the memind load itself (checked by the MOVE, not by cmp):
    | N=1 (MSB set), Z=0, V=0, C=0.  Retest by copying D0 and using TST.
    move.l  %d0, %d1
    tst.l   %d1
    bpl     _fail2
    beq     _fail2

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
