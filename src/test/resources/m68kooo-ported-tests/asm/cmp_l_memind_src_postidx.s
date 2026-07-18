| cmp_l_memind_src_postidx.s -- Task #213 (D2)
|
| Directed: CMP.L with full-format memory-indirect SOURCE, POST-indexed.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | -- Post-idx memind src: CMP.L ([32,A4],D1.L,0),D0
    |    inner EA = 32 + A4          (D1 NOT in inner)
    |    target EA = *[inner_ea] + D1 + od (0)
    lea     0x00115200, %a4
    move.l  #0x00000010, %d1          | D1 = 0x10 (scaled *1, added AFTER indirect)
    lea     0x00115220, %a0           | A4+32 = 0x00115220 (inner_ea)
    move.l  #0x00115500, (%a0)        | [inner_ea] = pointer 0x00115500
    lea     0x00115510, %a0           | target = 0x00115500 + D1(0x10) = 0x00115510
    move.l  #0x00001234, (%a0)        | [target] = 0x00001234
    move.l  #0x00001234, %d0          | D0 = 0x00001234

    | CMP.L <ea>,Dn: opword = 1011_ddd_010_mmm_rrr. ddd=000, mmm=110, rrr=100
    |   = 1011_000_010_110_100 = 0xB0B4
    | ext1 post-indexed D1.L null-od word-bd:
    |   b15=0 b14:12=001 b11=1 b10:9=00 b8=1 b7=0 b6=0 b5:4=10 b3=0 b2:0=101
    |     (I/IS=101 means post-idx, null od; bit 3 reserved, ignored)
    |   = 0001_1001_0010_0101 = 0x1925
    .word   0xB0B4, 0x1925, 0x0020
    | After CMP equal, Z=1; BEQ taken → jump over _fail1.
    beq     _pass
    bra     _fail1

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
