| fpu_unsupp_frame.s -- CPU_FP_UNSUPP_TRAP: the vector-55 (unsupported data type)
| delivery contract, checked field by field by a minimal handler (no FPSP):
|   * stack frame: format $0 / vector 55 (word $00DC), stacked PC = the NEXT instruction
|   * FPIAR = the trapping instruction
|   * FSAVE: 52-byte frame, header $41300000, CMDREG1B (+$10), STAG (+$0C [31:29]),
|     DTAG (+$14 [31:29], dyadic only), E1 (+$18 bit 26), FPTEMP (+$1C), ETEMP (+$28)
|     -- the fields x_unsupp's get_op/res_func ($4088E146/$4088E8D0) read
|   * pre-instruction semantics: no FPn and no FPSR update; but an (An)+ source EA has
|     completed (An advanced) -- the MC68040 finishes the EA before it reports
| Cases: A fmove.x (a0)+,fp1 unnormal / B fadd.x fp4,fp5 with an unnormal DESTINATION /
|        C fmove.s d0,fp3 single denormal (STAG 101) / D fmove.d 8(a2),fp3 double
|        denormal (STAG 101) / E ftst.x fp5 unnormal FPREG source.
| FAIL 0xBAD55cXX: case c (1..5), XX = which check.
    .text
    .org 0
    .equ PASS_SENT, 0xFFFF0000
    .equ SCR, 0x00020000
_start:
    lea     0x00011000, %a7
    move.l  #_h2,  0x08
    move.l  #_h3,  0x0C
    move.l  #_h4,  0x10
    move.l  #_h11, 0x2C
    move.l  #_v55, 0xDC
    | unnormal {$403E, 0, 100000}
    move.l  #0x403E0000, SCR
    clr.l   SCR+4
    move.l  #0x000186A0, SCR+8
    | unnormal {$4000, 0, 3}
    move.l  #0x40000000, SCR+0x10
    clr.l   SCR+0x14
    move.l  #3, SCR+0x18
    | double denormal 5 at SCR+0x28
    clr.l   SCR+0x28
    move.l  #5, SCR+0x2C

    | ── A ──
    moveq   #1, %d4
    lea     _tA, %a5
    moveq   #0, %d6
    fmove.l #7, %fp1
    fmove.l #0, %fpsr
    lea     SCR, %a0
_iA: fmove.x (%a0)+, %fp1
_nA:
    cmp.l   #1, %d6
    bne     _fc
    lea     SCR+12, %a1
    cmp.l   %a1, %a0
    bne     _fe
    fmove.l %fp1, %d0
    cmp.l   #7, %d0
    bne     _fv
    fmove.l %fpsr, %d0
    tst.l   %d0
    bne     _fs

    | ── B ──
    moveq   #2, %d4
    lea     _tB, %a5
    moveq   #0, %d6
    fmovem.x SCR+0x10, %fp5
    fmove.l #2, %fp4
    fmove.l #0, %fpsr
_iB: fadd.x %fp4, %fp5
_nB:
    cmp.l   #1, %d6
    bne     _fc
    fmove.l %fpsr, %d0
    tst.l   %d0
    bne     _fs

    | ── C ──
    moveq   #3, %d4
    lea     _tC, %a5
    moveq   #0, %d6
    fmove.l #9, %fp3
    move.l  #0x80000003, %d0
_iC: fmove.s %d0, %fp3
_nC:
    cmp.l   #1, %d6
    bne     _fc
    fmove.l %fp3, %d0
    cmp.l   #9, %d0
    bne     _fv

    | ── D ──
    moveq   #4, %d4
    lea     _tD, %a5
    moveq   #0, %d6
    lea     SCR+0x20, %a2
_iD: fmove.d 8(%a2), %fp3
_nD:
    cmp.l   #1, %d6
    bne     _fc
    fmove.l %fp3, %d0
    cmp.l   #9, %d0
    bne     _fv

    | ── E ──
    moveq   #5, %d4
    lea     _tE, %a5
    moveq   #0, %d6
    fmove.l #0, %fpsr
_iE: ftst.x %fp5
_nE:
    cmp.l   #1, %d6
    bne     _fc
    fmove.l %fpsr, %d0
    tst.l   %d0
    bne     _fs

    move.l  #0xC0FFEE00, PASS_SENT
_halt:
    bra     _halt

| per-case expectation tables:
|  +0 next PC, +4 instr PC, +8 CMDREG1B, +10 STAG<<5, +11 DTAG<<5 (0 = monadic, no check)
|  +12 ETEMP (3 longs), +24 FPTEMP (3 longs)
    .align 4
_tA: .long _nA, _iA
     .word 0x4880
     .byte 0x80, 0x00
     .long 0x403E0000, 0x00000000, 0x000186A0
     .long 0, 0, 0
_tB: .long _nB, _iB
     .word 0x12A2
     .byte 0x00, 0x80
     .long 0x40000000, 0x80000000, 0x00000000
     .long 0x40000000, 0x00000000, 0x00000003
_tC: .long _nC, _iC
     .word 0x4580
     .byte 0xA0, 0x00
     .long 0x80000000, 0x00000300, 0x00000000
     .long 0, 0, 0
_tD: .long _nD, _iD
     .word 0x5580
     .byte 0xA0, 0x00
     .long 0x00000000, 0x00000000, 0x00002800
     .long 0, 0, 0
_tE: .long _nE, _iE
     .word 0x143A
     .byte 0x80, 0x00
     .long 0x40000000, 0x00000000, 0x00000003
     .long 0, 0, 0

_v55:
    addq.l  #1, %d6
    moveq   #1, %d5
    cmp.w   #0x00DC, 6(%sp)
    bne     _fh
    moveq   #2, %d5
    move.l  2(%sp), %d0
    cmp.l   (%a5), %d0
    bne     _fh
    moveq   #3, %d5
    fmove.l %fpiar, %d0
    cmp.l   4(%a5), %d0
    bne     _fh
    fsave   -(%sp)
    moveq   #4, %d5
    cmp.l   #0x41300000, (%sp)
    bne     _fh
    moveq   #5, %d5
    move.w  0x10(%sp), %d0
    cmp.w   8(%a5), %d0
    bne     _fh
    moveq   #6, %d5
    move.b  0x0C(%sp), %d0
    andi.b  #0xE0, %d0
    cmp.b   10(%a5), %d0
    bne     _fh
    moveq   #7, %d5
    btst    #2, 0x18(%sp)
    beq     _fh
    moveq   #8, %d5
    move.l  0x28(%sp), %d0
    cmp.l   12(%a5), %d0
    bne     _fh
    move.l  0x2C(%sp), %d0
    cmp.l   16(%a5), %d0
    bne     _fh
    move.l  0x30(%sp), %d0
    cmp.l   20(%a5), %d0
    bne     _fh
    tst.b   11(%a5)
    beq     1f
    moveq   #9, %d5
    move.b  0x14(%sp), %d0
    andi.b  #0xE0, %d0
    cmp.b   11(%a5), %d0
    bne     _fh
    moveq   #10, %d5
    move.l  0x1C(%sp), %d0
    cmp.l   24(%a5), %d0
    bne     _fh
    move.l  0x20(%sp), %d0
    cmp.l   28(%a5), %d0
    bne     _fh
    move.l  0x24(%sp), %d0
    cmp.l   32(%a5), %d0
    bne     _fh
1:  frestore (%sp)+
    rte

_fh: moveq  #0, %d1
    move.b  %d5, %d1
    bra     _fx
_fc: move.l #0xC0, %d1
    bra     _fx
_fe: move.l #0xE0, %d1
    bra     _fx
_fv: move.l #0xF0, %d1
    bra     _fx
_fs: move.l #0xF5, %d1
_fx: lsl.l  #8, %d4
    or.l    %d4, %d1
    ori.l   #0xBAD55000, %d1
    move.l  %d1, PASS_SENT
    bra     _halt
_h2: move.l #0xBAD00002, PASS_SENT
    bra     _halt
_h3: move.l #0xBAD00003, PASS_SENT
    bra     _halt
_h4: move.l #0xBAD00004, PASS_SENT
    bra     _halt
_h11: move.l #0xBAD0000B, PASS_SENT
    bra     _halt
