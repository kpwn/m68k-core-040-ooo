| andi_l_memind_dst_preidx.s -- Task #213 (D2)
|
| Directed: ANDI.L #imm, <memind-dst> pre-indexed.  Uses D1 as the index
| register with scale=1.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | -- Pre-idx memind dst: ANDI.L #0x0000FFFF,([32,A4,D1.L])
    |    inner EA = 32 + A4 + D1.  target EA = *[inner_ea] + od (0).
    lea     0x00115200, %a4
    move.l  #0x00000010, %d1
    | inner EA = A4 + 0x20 + D1 = 0x00115230
    lea     0x00115230, %a0
    move.l  #0x00115500, (%a0)        | [inner_ea] = pointer 0x00115500
    lea     0x00115500, %a0
    move.l  #0xDEADBEEF, (%a0)        | [target] initial

    | ANDI.L opword = 0000_0010_10_mmm_rrr.  mode=110, rrr=100 => 0x02B4.
    | ext1 pre-indexed D1.L null-od word-bd =
    |   b15=0 b14:12=001 b11=1 b10:9=00 b8=1 b7=0 b6=0 b5:4=10 b3=0 b2:0=001
    |   = 0001_1001_0010_0001 = 0x1921
    | Ext layout: <imm_hi><imm_lo><ext1><bd_word>
    .word   0x02B4, 0x0000, 0xFFFF, 0x1921, 0x0020
    | Post-RMW: [target] must be 0x0000BEEF.
    lea     0x00115500, %a0
    move.l  (%a0), %d0
    cmp.l   #0x0000BEEF, %d0
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
