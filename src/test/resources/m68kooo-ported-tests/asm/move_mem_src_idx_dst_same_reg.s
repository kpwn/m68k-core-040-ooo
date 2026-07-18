| move_mem_src_idx_dst_same_reg.s — MOVE (An)+/-(An) → (d8,An,Xn) with
| the source register aliasing the destination base or index register
| (audit follow-up to the MOVE.L A7,-(A7) decode-order bug family).
|
| Musashi golden (m68k_in.c move ix,.):
|     res = OPER_AY_*()    -- source read AND predec/postinc update FIRST
|     ea  = EA_AX_IX()     -- dst EA computed with the UPDATED register
|     write(ea, res)
| The old RTL crack computed the dst indexed EA in the leading phases,
| BEFORE the source predec/postinc µop — so a same-register dst base
| (or index) observed the stale pre-update value.
|
| Also fences the different-register form and a predec-src variant.

    .text
    .org 0

    .equ SCRATCH, 0x00100000

_start:
    | ── Part 0: MOVE.L (A1)+,(4,A1,D0.L) — src reg == dst BASE ──
    | A1=SCRATCH+0x400, D0=0; mem[400]=0xCAFEBABE.
    | Musashi: res=mem[400]; A1->404; ea=404+4+0=408.
    lea     SCRATCH+0x400, %a0
    move.l  #0xCAFEBABE, (%a0)
    clr.l   SCRATCH+0x404
    clr.l   SCRATCH+0x408
    lea     SCRATCH+0x400, %a1
    moveq   #0, %d0
    move.l  (%a1)+, (4,%a1,%d0.l)
    move.l  %a1, %d1
    cmp.l   #SCRATCH+0x404, %d1     | A1 incremented
    bne     _fail0
    move.l  SCRATCH+0x408, %d1
    cmp.l   #0xCAFEBABE, %d1        | landed at UPDATED base + 4
    bne     _fail1
    move.l  SCRATCH+0x404, %d1
    tst.l   %d1                     | stale-base slot must be untouched
    bne     _fail2

    | ── Part 1: MOVE.L -(A1),(8,A1,D0.L) — predec src, same dst base ──
    | A1=SCRATCH+0x504; mem[500]=0x12345678.
    | Musashi: A1->500; res=mem[500]; ea=500+8+0=508.
    lea     SCRATCH+0x500, %a0
    move.l  #0x12345678, (%a0)
    clr.l   SCRATCH+0x508
    clr.l   SCRATCH+0x50C
    lea     SCRATCH+0x504, %a1
    moveq   #0, %d0
    move.l  -(%a1), (8,%a1,%d0.l)
    move.l  %a1, %d1
    cmp.l   #SCRATCH+0x500, %d1     | A1 decremented
    bne     _fail3
    move.l  SCRATCH+0x508, %d1
    cmp.l   #0x12345678, %d1        | ea from UPDATED (decremented) base
    bne     _fail4
    move.l  SCRATCH+0x50C, %d1
    tst.l   %d1                     | stale-base slot (504+8) untouched
    bne     _fail5

    | ── Part 2: MOVE.L (A2)+,(0,A3,A2.L) — src reg == dst INDEX ──
    | A2=SCRATCH+0x600, A3=0; mem[600]=0x0BADF00D.
    | Musashi: res=mem[600]; A2->604; ea=0+0+604=SCRATCH+0x604.
    lea     SCRATCH+0x600, %a0
    move.l  #0x0BADF00D, (%a0)
    clr.l   SCRATCH+0x604
    lea     SCRATCH+0x600, %a2
    suba.l  %a3, %a3                | A3 = 0
    move.l  (%a2)+, (0,%a3,%a2.l)
    move.l  %a2, %d1
    cmp.l   #SCRATCH+0x604, %d1
    bne     _fail6
    move.l  SCRATCH+0x604, %d1
    cmp.l   #0x0BADF00D, %d1        | index observed UPDATED A2
    bne     _fail7

    | ── Part 3: different-register regression fence ──
    | MOVE.W (A4)+,(2,A5,D0.W): A4=SCRATCH+0x700, A5=SCRATCH+0x710.
    lea     SCRATCH+0x700, %a0
    move.w  #0x5A5A, (%a0)
    clr.l   SCRATCH+0x710
    lea     SCRATCH+0x700, %a4
    lea     SCRATCH+0x710, %a5
    moveq   #0, %d0
    move.w  (%a4)+, (2,%a5,%d0.w)
    move.l  %a4, %d1
    cmp.l   #SCRATCH+0x702, %d1
    bne     _fail8
    move.w  SCRATCH+0x712, %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0x5A5A, %d1
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
