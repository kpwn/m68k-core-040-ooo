| add_l_memind_src_preidx.s -- Task #213 (D2)
|
| Directed: ADD.L with full-format memory-indirect SOURCE, pre-indexed
| (IS=0, I/IS in 001..011).  Dn destination.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | -- Pre-idx: ADD.L ([32,A4,D1.L],0),D0
    |    inner EA = bd + An + Xn*sc = 32 + A4 + D1
    |    target EA = *[inner_ea] + od (0)
    lea     0x00115200, %a4          | A4 base
    move.l  #0x00000010, %d1         | D1 = 0x10 (scale=00 means *1)
    | inner EA = A4 + 0x20 + D1 = 0x00115200 + 0x20 + 0x10 = 0x00115230
    lea     0x00115230, %a0
    move.l  #0x00115500, (%a0)       | [inner_ea] = pointer 0x00115500
    lea     0x00115500, %a0
    move.l  #0x00002000, (%a0)       | [target] = 0x00002000
    move.l  #0x00004000, %d0         | D0 = 0x00004000

    | ADD.L ([bd,An,Xn],od),D0
    |   opword: 1101_ddd_010_mmm_rrr. dst D0=000, size=10(.L), mode=110(ext)
    |           rrr=100 (A4). => 0xD0B4
    |   ext1: RIIS bd_size od_size
    |         R=0 I I=0 S=0 (0),  D1.L (R=0, idx=001, W/L=1 L, scale=00)
    |         Full-format: b[8]=1; b[7]=BS=0; b[6]=IS=0; b[5:4]=bd_size=10 (word);
    |         b[3]=0; b[2:0]=I/IS=001 (pre-idx, null od)
    |         idx register bits b[15]=D/A=0, b[14:12]=reg=001 (D1), b[11]=L=1,
    |         b[10:9]=scale=00
    |         So ext1 = 0 001 1 00 1 0 0 1 0 0 001 = 0001_1001_0010_0001
    |                 = 0x1921
    |   bd = 0x0020 (word)
    .word   0xD0B4, 0x1921, 0x0020
    | D0 should be 0x4000 + 0x2000 = 0x6000
    cmp.l   #0x00006000, %d0
    bne     _fail1

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
