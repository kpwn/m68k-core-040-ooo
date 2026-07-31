| idx_alias_dst_pc_full.s — PC-relative and full-format (68020) indexed
| effective addresses where the INDEX register is ALSO the DESTINATION
| register.
|
| Companion to idx_alias_dst_brief.s, which covers the brief-format
| (d8,An,Xn) shapes.  This file exercises the other paths that share the
| indexed-EA crack in rtl/core/decode/decode_ea_v2.v:
|
|   - (d8,PC,Xn.SIZE*SCALE)          brief-format PC-relative
|   - (bd,An,Xn.SIZE*SCALE)          full-format, word / long bd
|   - (bd,ZAn,Xn.SIZE*SCALE)         full-format, base suppressed
|   - ([bd,An,Xn.SIZE*SCALE],od)     full-format pre-indexed memind
|   - ([bd,An],Xn.SIZE*SCALE,od)     full-format post-indexed memind
|
| In every case the EA must be built from the PRE-instruction value of
| Xn, never from the loaded result.

    .text
    .org 0

    .equ SCRATCH, 0x00100000

_start:
    | ── Pre-fill 0x2000 bytes of SCRATCH with 0xEE poison ──
    lea     SCRATCH, %a0
    move.w  #0x1FFF, %d0
_fill:
    move.b  #0xEE, (%a0)+
    dbf     %d0, _fill

    | ── Part 0: (d8,PC,Dn.W) with Dn == destination, byte operand ──
    | D6 = 4 -> pctab[4] = 0x5C.  A stale/post-load index would read
    | pctab[0]=0x11 or pctab[0x5C] (past the table).
    moveq   #4, %d6
    move.b  (_pctab,%pc,%d6.w), %d6
    cmp.l   #0x0000005C, %d6
    bne     _fail0

    | ── Part 1: (d8,PC,Dn.W*2) word operand, scale 2 ──
    | D5 = 3 -> byte offset 6 -> _pcwtab+6 = 0x3344.
    moveq   #3, %d5
    move.w  (_pcwtab,%pc,%d5.w*2), %d5
    cmp.l   #0x00003344, %d5
    bne     _fail1

    | ── Part 2: (d8,PC,Dn.L*4) long operand, .L index, scale 4 ──
    | D4 = 2 -> byte offset 8 -> _pcltab+8 = 0xC0DEC0DE.
    moveq   #2, %d4
    move.l  (_pcltab,%pc,%d4.l*4), %d4
    cmp.l   #0xC0DEC0DE, %d4
    bne     _fail2

    bra     _skiptabs
    .align  4
_pctab:
    .byte   0x11, 0x22, 0x33, 0x44, 0x5C, 0x66, 0x77, 0x88
    .align  4
_pcwtab:
    .short  0x1122, 0x2233, 0x3344 - 0x2222, 0x3344
    .align  4
_pcltab:
    .long   0x11111111, 0x22222222, 0xC0DEC0DE, 0x44444444
_skiptabs:

    | ── Part 3: full-format (bd16,An,Dn.L*4), index == destination ──
    | bd = 0x2000 does not fit in 8 bits -> full-format ext word.
    | A2 = SCRATCH; D3 = 3 -> EA = SCRATCH + 0x2000 + 12.
    lea     SCRATCH, %a2
    move.l  #0xFACEF00D, (SCRATCH+0x200C)
    moveq   #3, %d3
    move.l  (0x2000,%a2,%d3.l*4), %d3
    cmp.l   #0xFACEF00D, %d3
    bne     _fail3

    | ── Part 4: full-format with NEGATIVE .W index (sign extension) ──
    | D2 = 0x0001FFFC (low word = -4); EA = SCRATCH + 0x2100 - 4.
    move.l  #0x13571357, (SCRATCH+0x20FC)
    move.l  #0x0001FFFC, %d2
    move.l  (0x2100,%a2,%d2.w), %d2
    cmp.l   #0x13571357, %d2
    bne     _fail4

    | ── Part 5: full-format base-suppressed (bd,ZAn,Dn.L*2) ──
    | Base register suppressed -> EA = bd + Dn*2.
    | D1 = 0x40 -> EA = SCRATCH+0x2200 + 0x80.
    move.l  #0x24682468, (SCRATCH+0x2280)
    move.l  #0x00000040, %d1
    move.l  (SCRATCH+0x2200,%za2,%d1.l*2), %d1
    cmp.l   #0x24682468, %d1
    bne     _fail5

    | ── Part 6: full-format PRE-indexed memory indirect, index == dst ──
    | ([bd,A2,D0.L*4], od)
    |   inner = SCRATCH + 0x2300 + D0*4 ; ptr = mem[inner] ; EA = ptr + od
    | D0 = 2 -> inner = SCRATCH+0x2308, which holds SCRATCH+0x2400.
    | od = 8 -> EA = SCRATCH+0x2408 = 0x8ACE8ACE.
    move.l  #SCRATCH+0x2400, (SCRATCH+0x2308)
    move.l  #0x8ACE8ACE, (SCRATCH+0x2408)
    moveq   #2, %d0
    move.l  ([0x2300,%a2,%d0.l*4],8), %d0
    cmp.l   #0x8ACE8ACE, %d0
    bne     _fail6

    | ── Part 7: full-format POST-indexed memory indirect, index == dst ──
    | ([bd,A2], D7.L*4, od)
    |   ptr = mem[SCRATCH+0x2500] ; EA = ptr + D7*4 + od
    | ptr = SCRATCH+0x2600, D7 = 3, od = 4 -> EA = SCRATCH+0x2610.
    move.l  #SCRATCH+0x2600, (SCRATCH+0x2500)
    move.l  #0x1B2B3B4B, (SCRATCH+0x2610)
    moveq   #3, %d7
    move.l  ([0x2500,%a2],%d7.l*4,4), %d7
    cmp.l   #0x1B2B3B4B, %d7
    bne     _fail7

    | ── Part 8: full-format indexed STORE whose index is the source ──
    | move.l D6,(bd,A2,D6.L*4) — index and stored data alias.
    | D6 = 5 -> EA = SCRATCH + 0x2700 + 20.
    move.l  #5, %d6
    move.l  %d6, (0x2700,%a2,%d6.l*4)
    move.l  (SCRATCH+0x2714), %d6
    cmp.l   #0x00000005, %d6
    bne     _fail8

    | ── Part 9: different-register fence for the full-format path ──
    lea     SCRATCH, %a3
    move.l  #0x0F1E2D3C, (SCRATCH+0x2810)
    moveq   #4, %d5
    moveq   #0, %d4
    move.l  (0x2800,%a3,%d5.l*4), %d4
    cmp.l   #0x0F1E2D3C, %d4
    bne     _fail9
    cmp.l   #0x00000004, %d5
    bne     _fail10

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail0:
    move.l  #0xDEAD0000, %d3
    bra     _fail
_fail1:
    move.l  #0xDEAD0001, %d3
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d3
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d3
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d3
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d3
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d3
    bra     _fail
_fail7:
    move.l  #0xDEAD0007, %d3
    bra     _fail
_fail8:
    move.l  #0xDEAD0008, %d3
    bra     _fail
_fail9:
    move.l  #0xDEAD0009, %d3
    bra     _fail
_fail10:
    move.l  #0xDEAD000A, %d3
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d3, (%a0)
_halt_fail:
    bra     _halt_fail
