| addx_subx_predec_same_reg.s — ADDX/SUBX -(An),-(An) same-register
| degenerate form (audit follow-up to the MOVE.L A7,-(A7) decode-order
| bug family).
|
| Musashi golden (m68k_in.c addx/subx mm):
|     src = OPER_AY_PD()   -- Ay -= stride, read src at NEW Ay
|     ea  = EA_AX_PD()     -- Ax -= stride (again, if same reg)
|     dst = read(ea); write(ea, res)
| So for the same-register form the SOURCE is read at [An - stride]
| and the DEST at [An - 2*stride]; final An = orig - 2*stride.
| The old RTL crack emitted BOTH predec SUBs before the source LOAD,
| making a same-reg source read [An - 2*stride].
|
| Also fences the different-register form (must stay correct) and a
| word-size same-reg variant.

    .text
    .org 0

    .equ SCRATCH, 0x00100000

_start:
    | ── Part 0: SUBX.L -(A2),-(A2), X=0 ──
    | mem[F8]=0x11111111 (dst operand), mem[FC]=0x22222222 (src operand)
    lea     SCRATCH+0xF8, %a0
    move.l  #0x11111111, (%a0)+
    move.l  #0x22222222, (%a0)
    lea     SCRATCH+0x100, %a2
    move    #0, %ccr                | X=0
    subx.l  -(%a2), -(%a2)
    move.l  %a2, %d0
    cmp.l   #SCRATCH+0xF8, %d0      | A2 = orig - 8
    bne     _fail0
    move.l  SCRATCH+0xF8, %d1
    cmp.l   #0xEEEEEEEF, %d1        | 0x11111111 - 0x22222222 - 0
    bne     _fail1
    move.l  SCRATCH+0xFC, %d2
    cmp.l   #0x22222222, %d2        | src slot untouched
    bne     _fail2

    | ── Part 1: ADDX.L -(A3),-(A3), X=1 ──
    | mem[1F8]=0x00000003 (dst), mem[1FC]=0x00000005 (src)
    lea     SCRATCH+0x1F8, %a0
    move.l  #3, (%a0)+
    move.l  #5, (%a0)
    lea     SCRATCH+0x200, %a3
    move    #0x10, %ccr             | X=1
    addx.l  -(%a3), -(%a3)
    move.l  %a3, %d0
    cmp.l   #SCRATCH+0x1F8, %d0
    bne     _fail3
    move.l  SCRATCH+0x1F8, %d1
    cmp.l   #9, %d1                 | 3 + 5 + X(1)
    bne     _fail4

    | ── Part 2: ADDX.W -(A4),-(A4), X=0 (word strides) ──
    | mem16[2FC]=0x1111 (dst), mem16[2FE]=0x2222 (src)
    lea     SCRATCH+0x2FC, %a0
    move.w  #0x1111, (%a0)+
    move.w  #0x2222, (%a0)
    lea     SCRATCH+0x300, %a4
    move    #0, %ccr
    addx.w  -(%a4), -(%a4)
    move.l  %a4, %d0
    cmp.l   #SCRATCH+0x2FC, %d0     | A4 = orig - 4
    bne     _fail5
    move.w  SCRATCH+0x2FC, %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0x3333, %d1            | 0x1111 + 0x2222
    bne     _fail6

    | ── Part 3: different-register regression fence ──
    | SUBX.L -(A0),-(A1): mem[3F8]=7 via A0, mem[4FC]=0x20 via A1
    lea     SCRATCH+0x3F8, %a0
    move.l  #7, (%a0)
    lea     SCRATCH+0x4FC, %a1
    move.l  #0x20, (%a1)
    lea     SCRATCH+0x3FC, %a0
    lea     SCRATCH+0x500, %a1
    move    #0, %ccr
    subx.l  -(%a0), -(%a1)          | dst=mem[A1-4]=0x20, src=mem[A0-4]=7
    move.l  %a0, %d0
    cmp.l   #SCRATCH+0x3F8, %d0
    bne     _fail7
    move.l  %a1, %d0
    cmp.l   #SCRATCH+0x4FC, %d0
    bne     _fail8
    move.l  SCRATCH+0x4FC, %d1
    cmp.l   #0x19, %d1              | 0x20 - 7
    bne     _fail9

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
    bra     _fail
_fail7:
    move.l  #0xDEAD0007, %d7
    bra     _fail
_fail8:
    move.l  #0xDEAD0008, %d7
    bra     _fail
_fail9:
    move.l  #0xDEAD0009, %d7
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
