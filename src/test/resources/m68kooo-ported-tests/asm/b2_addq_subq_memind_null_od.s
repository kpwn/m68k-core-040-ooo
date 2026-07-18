| b2_addq_subq_memind_null_od.s — V2 B2: ADDQ memind null-od + BS=0.
|
| addq_subq_mem_rmw.s already covers ADDQ/SUBQ memind in the legacy
| BS=1 (base suppressed) + W-od shape (ext1=0x81e2).  This test covers
| TWO new corners:
|   1. BS=0 (base reg used) — ext1[7]=0, so pointer is at (An + bd).
|   2. null OD (ext1[3:0]=0001) — no outer displacement word.
|
| Encoding for BS=0, IS=1, BD.W, preindexed null od:
|   ext1 = 0_000_0_00_1_0_1_10_0_001 = 0x0161
|
| EA = (memory[An + bd]) + od = (memory[An + bd]) since od=0.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | -- ADDQ.W #1, ([bd=0x100, A0]) — BS=0, null OD ----------------
    | A0=0x114000; A0+0x100=0x114100; pointer there → 0x114200; EA=0x114200.
    lea     0x00114000, %a0
    move.l  #0x00114200, 0x100(%a0)
    move.w  #0x0005, 0x114200
    | ADDQ.W #1: opword 0x5270, ext1=0x0161 (BS=0, IS=1, BD.W, null od).
    .word   0x5270, 0x0161, 0x0100
    | After: 0x114200 = 6.  Z=0, N=0, V=0, C=0.
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.w  0x114200, %d0
    cmp.w   #0x0006, %d0
    bne     _fail

    | -- SUBQ.L #1, ([bd=0x108, A0]) — BS=0, null OD, value=1 → 0 ----
    move.l  #0x00114220, 0x108(%a0)
    move.l  #0x00000001, 0x114220
    | SUBQ.L #1: opword 0x53b0 (quick=001=1, d=1=SUB, ss=10=L, mode=110, reg=000=A0).
    .word   0x53b0, 0x0161, 0x0108
    | After: 0x114220 = 0, Z=1, N=0, V=0, C=0, X=0.
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  0x114220, %d0
    cmp.l   #0x00000000, %d0
    bne     _fail

    | All passed.
    move.l  #0xc0ffee00, %d0
    move.l  %d0, 0xffff0000
    bra     .

_fail:
    move.l  #0xdeadbeef, %d0
    move.l  %d0, 0xffff0000
    bra     .
