| movem_pc_idx_w.s — Phase-2 item #3: MOVEM.W (d8,PC,Xn) brief-indexed
| LOAD source.  Prior to this fix, `v2_movem_ea_ok_load`'s PC-indexed
| admission was gated `&& movem_is_size_l_f3` — .W PC-indexed fell
| through to vec-4 ILLEGAL (pinned in movem_idx_unimpl_traps.s's old
| _c4 case).  Investigated as part of item #2 (An-indexed load): the
| .W-vs-.L phase-count-width concern turned out to already be solved by
| the general EA-compute-prologue + N-register .W-vs-.L load loop that
| item #2 needed anyway, so the PC-indexed crack reuses it and the
| `&& movem_is_size_l_f3` restriction was dropped.
|
| MOVEM.W must sign-extend each loaded word into the 32-bit register
| (PRM §4.79) — this test checks both a positive and a negative word to
| catch a sign-extension regression, matching the style of
| b3_movem_pc_indexed_widening.s (manual .word opcode encoding since
| the cross-assembler doesn't know the PC-indexed MOVEM.W mnemonic).
|
| Brief-format MOVEM.W (d8,PC,Xn) opword: 0x4cbb (0x4cfb with the .L
| size bit, opword[6], cleared).
| Index ext layout (brief, same as the .L widening test):
|   ext[15]=D/A, ext[14:12]=reg, ext[11]=W/L, ext[10:9]=scale,
|   ext[8]=0 (brief), ext[7:0]=d8.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | D0 (index, unscaled .W) chosen so PC_base + 0 + D0.W = _table.
    | PC_base for (d8,PC,Xn) brief = pd_pc + 4 (opword + ext word).
    move.l  #(_table - (_movem + 4)), %d0

    | reg list mask: D2 (bit 2), D3 (bit 3) — mask = 0x000C
_movem:
    | ext[15]=0(D), ext[14:12]=000(D0), ext[11]=0(.W idx, sign-ext),
    | ext[10:9]=00(*1), ext[8]=0(brief), ext[7:0]=0x00 → ext2 = 0x0000
    .word   0x4cbb, 0x000c, 0x0000

    | D2 = sign-extend(mem.w[_table+0]) = sign-extend(0x1234) = 0x00001234
    | D3 = sign-extend(mem.w[_table+2]) = sign-extend(0xFFFE) = 0xFFFFFFFE
    cmp.l   #0x00001234, %d2
    bne     _fail
    cmp.l   #0xFFFFFFFE, %d3
    bne     _fail

    move.l  #0xc0ffee00, %d0
    move.l  %d0, 0xffff0000
    bra     .

_fail:
    move.l  #0xdeadbeef, %d0
    move.l  %d0, 0xffff0000
    bra     .

    .balign 4
_table:
    .word   0x1234
    .word   0xFFFE
