| andi_l_memind_dst.s — Task #208 (C2)
|
| Directed test for ANDI.L #imm, <memind-dst>.  Covers no-index memind
| destination RMW crack.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | -- No-index: ANDI.L #0x0000FFFF,([32,A4])
    |    target = [A4 + 32] + 0; pre-load with 0xDEADBEEF; result 0x0000BEEF.
    lea     0x00115200, %a4
    lea     0x00115220, %a0
    move.l  #0x00115400, (%a0)       | [A4+32] = pointer 0x00115400
    lea     0x00115400, %a0
    move.l  #0xDEADBEEF, (%a0)       | [target] initial value
    | ANDI.L opword = 0000_0010_10_mmm_rrr.  mode=110 rrr=100 (A4) => 0x02B4.
    | imm.L follows as 2 ext words, then memind ext words.
    | ext_order: <imm_hi><imm_lo><memind_ext1><memind_bd>
    | 0x02B4  0x0000 0xFFFF  0x0164  0x0020
    .word   0x02B4, 0x0000, 0xFFFF, 0x0164, 0x0020
    | Post-RMW, [target] must be 0x0000BEEF.
    lea     0x00115400, %a0
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
