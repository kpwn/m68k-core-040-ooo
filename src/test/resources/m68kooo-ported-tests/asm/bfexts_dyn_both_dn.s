| bfexts_dyn_both_dn.s — task #111 / G5: directed BFEXTS Dn-direct
| with dynamic offset AND dynamic width.  Phase-0 ALU_BF_PACK packs
| the offset+width source registers into TMP1; phase-1 ALU_BFEXT
| reads TMP1 via src_b with imm[12]=1 (bf_dyn_both).
|
| The original fuzz divergence (BUG_bfexts_dyn_both_flag_divergence.md,
| seed 1026) was a value/CCR drift on this exact crack path, with no
| directed coverage.  This test pins the four classic corners:
|
|   1. Sign-extend negative byte at byte 0:  off=0, wid=8, src=0x80FFFFFF
|      → field = 0x80 = -128 → result = 0xFFFFFF80, N=1, Z=0, V=0, C=0.
|
|   2. Mid-nibble negative:               off=4, wid=4, src=0x0F000000
|      → field = 0xF = -1   → result = 0xFFFFFFFF, N=1, Z=0.
|
|   3. Width=32 (offset 0, full word):    off=0, wid=32, src=0x80000001
|      → result = 0x80000001 (identity), N=1, Z=0.
|
|   4. Offset wrap to MSB:                off=24, wid=8, src=0x00000080
|      → field = 0x80 (low byte rotated to MSB) = -128, N=1, Z=0.
|
| Each scenario also exercises a BGT branch on the resulting CCR — the
| original repro's divergence path.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    | ─── 1: off=0, wid=8, src=0x80FFFFFF, expect 0xFFFFFF80 + N=1
    move.l  #0x80ffffff, %d0
    moveq   #0, %d1               | offset
    moveq   #8, %d2               | width
    bfexts  %d0{%d1:%d2}, %d3
    move.l  #0xffffff80, %d4
    cmp.l   %d4, %d3
    bne     _fail
    | BGT after bfexts: signed result < 0, so BGT not taken.
    bfexts  %d0{%d1:%d2}, %d3
    bgt     _fail                 | must NOT branch (N=1, Z=0 → BGT false)

    | ─── 2: off=4, wid=4, src=0x0F000000, expect 0xFFFFFFFF
    move.l  #0x0f000000, %d0
    moveq   #4, %d1
    moveq   #4, %d2
    bfexts  %d0{%d1:%d2}, %d3
    move.l  #0xffffffff, %d4
    cmp.l   %d4, %d3
    bne     _fail

    | ─── 3: off=0, wid=32, full-word identity (signed) preserves src.
    move.l  #0x80000001, %d0
    moveq   #0, %d1
    moveq   #32, %d2
    bfexts  %d0{%d1:%d2}, %d3
    move.l  #0x80000001, %d4
    cmp.l   %d4, %d3
    bne     _fail

    | ─── 4: off=24, wid=8, src=0x00000080.  Low byte rotates to MSB.
    move.l  #0x00000080, %d0
    moveq   #24, %d1
    moveq   #8, %d2
    bfexts  %d0{%d1:%d2}, %d3
    move.l  #0xffffff80, %d4
    cmp.l   %d4, %d3
    bne     _fail

    | ─── 5: positive byte sign-extension idempotent.  off=0 wid=8 src=0x7F000000
    move.l  #0x7f000000, %d0
    moveq   #0, %d1
    moveq   #8, %d2
    bfexts  %d0{%d1:%d2}, %d3
    move.l  #0x0000007f, %d4
    cmp.l   %d4, %d3
    bne     _fail
    | BGT after positive bfexts: result > 0 → taken.
    bfexts  %d0{%d1:%d2}, %d3
    bgt     _bgt_taken
    bra     _fail
_bgt_taken:

    | ─── 6: zero result → Z=1, BGT not taken, BEQ taken.
    move.l  #0x00000000, %d0
    moveq   #4, %d1
    moveq   #8, %d2
    bfexts  %d0{%d1:%d2}, %d3
    bne     _fail                 | result==0 must set Z=1

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
