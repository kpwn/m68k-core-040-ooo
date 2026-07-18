| andi_ori_byte_reg.s -- byte immediate logical ops on Dn
|
| Covers the ROM sequence after 0x4084b398:
|   ea59        ror.w  #5, %d1
|   0201 0007   andi.b #7, %d1
|   0001 00b8   ori.b  #0xb8, %d1
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | ROM-shaped low-byte path.  ROR.W now preserves the upper word while
    | the byte logical ops update only D1[7:0].
    move.l  #0x000000e0, %d1
    ror.w   #5, %d1
    andi.b  #7, %d1
    ori.b   #0xb8, %d1
    andi.l  #0xff, %d1
    cmp.l   #0xbf, %d1
    bne     _fail1

    | Byte logical ops preserve upper Dn bytes.
    move.l  #0x123456f0, %d2
    andi.b  #0x0f, %d2
    cmp.l   #0x12345600, %d2
    bne     _fail2

    | Same convention for ORI.B.
    move.l  #0x89abcd10, %d3
    ori.b   #0x0f, %d3
    cmp.l   #0x89abcd1f, %d3
    bne     _fail3

    | Zero-result flags for the low-risk byte case used by ROM logic.
    moveq   #0, %d4
    andi.b  #0, %d4
    bne     _fail4

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_fail1:
    bra     _halt_fail1

_fail2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_halt_fail2:
    bra     _halt_fail2

_fail3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
_halt_fail3:
    bra     _halt_fail3

_fail4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_halt_fail4:
    bra     _halt_fail4
