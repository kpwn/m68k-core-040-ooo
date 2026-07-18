| move_l_full_mem_src_to_dn.s -- MOVE.L (bd,An,Xn*scale),Dn
|
| Full-format extension word (68020+), no memory indirection (I/IS=000).
| Covers the brief-indexed sibling forms but with a widened base
| displacement (16-bit or 32-bit instead of brief 8-bit).
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Word bd + D1.W*4 + A0 — EA = A0 + 0x1234 + D1*4
    |   ext1=0x1520, ext2=0x1234 (word bd)
    |   D1 = 2, A0 = 0x00100000 → EA = 0x00100000 + 0x1234 + 0x8 = 0x0010123c
    move.l  #0x00100000, %a0
    moveq   #2, %d1
    move.l  #0xCAFEBABE, 0x0010123c
    moveq   #0, %d2
    .word   0x2430, 0x1520, 0x1234
    bpl     _fail1
    beq     _fail1
    cmp.l   #0xCAFEBABE, %d2
    bne     _fail1

    | Long bd + D1.L*2 + A0 — EA = A0 + 0x00012000 + D1*2
    |   ext1=0x1b30, ext2:ext3 = 0x00012000
    |   D1 = 4, A0 = 0x00100000 → EA = 0x00100000 + 0x00012000 + 0x8 = 0x00112008
    move.l  #0x00100000, %a0
    moveq   #4, %d1
    move.l  #0xDEADBEEF, 0x00112008
    moveq   #0, %d2
    .word   0x2430, 0x1b30, 0x0001, 0x2000
    bpl     _fail2
    beq     _fail2
    cmp.l   #0xDEADBEEF, %d2
    bne     _fail2

    | Null bd (bd_size=01) + A0 + D2.W*4 — EA = A0 + D2*4
    |   ext1=0x2510 (D/A=0, reg=010=D2, W/L=0, scale=10=x4, 1, BS=0, IS=0, BD=01=null, I/IS=000)
    |   = 0010_0101_0001_0000 = 0x2510
    |   D2 = 0x10, A0 = 0x00100000 → EA = 0x00100000 + 0x40 = 0x00100040
    move.l  #0x00100000, %a0
    moveq   #16, %d2
    move.l  #0x12345678, 0x00100040
    moveq   #0, %d3
    .word   0x2630, 0x2510
    bmi     _fail3
    beq     _fail3
    cmp.l   #0x12345678, %d3
    bne     _fail3

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d0
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d0
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d0

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
