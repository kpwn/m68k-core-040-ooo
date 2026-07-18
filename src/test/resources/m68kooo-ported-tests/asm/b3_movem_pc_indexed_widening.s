| b3_movem_pc_indexed_widening.s — V2 B3: MOVEM.L (d8,PC,Xn) source.
|
| movem_pc_indexed.s already covers the Q700 ROM frontier shape
| (D4.W index, scale=1, 3-reg list).  This test covers NEW corners:
|   * Long-index: D6.L
|   * Scale=2 (W index *2)
|   * 4-register list (mask)
|   * Different starting register selection (D2 instead of D3-D5)
|
| Brief-format MOVEM.L (d8,PC,Xn) opword: 0x4cfb.
| Index ext layout (no full-ext, brief):
|   ext[15]=D/A, ext[14:12]=reg, ext[11]=W/L, ext[10:9]=scale,
|   ext[8]=0 (brief), ext[7:0]=d8.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | -- MOVEM.L (d8,PC,D6.L*2),<D2,D3,D7,A0> ----------------------
    | D6 set s.t. PC+4+sx8(d8) + D6.L*2 = address of _table.
    | Use d8=0 for simplicity → base = PC+4 + D6*2 = _table.
    | Hence D6 = (_table - (_movem + 4)) / 2.
    move.l  #((_table - (_movem + 4)) / 2), %d6

    | reg list mask: D2 (bit 2), D3 (bit 3), D7 (bit 7), A0 (bit 8)
    | mask = 0000_0001_1000_1100 = 0x018c
_movem:
    | ext[15]=0(D), ext[14:12]=110(D6), ext[11]=1(L), ext[10:9]=01(*2),
    | ext[8]=0(brief), ext[7:0]=0x00
    | = 0_110_1_01_0_0000_0000 = 0x6A00 = 0xD200
    | wait recompute: 0_110_1_01_0_00000000 = 0110_1010_0000_0000 = 0x6A00
    .word   0x4cfb, 0x018c, 0x6a00

    | After: D2=mem[_table+0], D3=mem[_table+4], D7=mem[_table+8], A0=mem[_table+12].
    cmp.l   #0x22222222, %d2
    bne     _fail
    cmp.l   #0x33333333, %d3
    bne     _fail
    cmp.l   #0x77777777, %d7
    bne     _fail
    cmp.l   #0xaaaa0000, %a0
    bne     _fail

    | All passed.
    move.l  #0xc0ffee00, %d0
    move.l  %d0, 0xffff0000
    bra     .

_fail:
    move.l  #0xdeadbeef, %d0
    move.l  %d0, 0xffff0000
    bra     .

    .balign 4
_table:
    .long   0x22222222
    .long   0x33333333
    .long   0x77777777
    .long   0xaaaa0000
