| bit_ops_dyn_mem.s -- dynamic bit ops on byte memory destinations
|
| Memory bit operations use byte operands and reduce dynamic bit numbers
| modulo 8.  This covers the ROM frontier shape BTST D0,(A0), plus the
| modifying siblings and a d16(An) addressing form.

    .text
    .org 0

_start:
    lea     0x00112100, %a0
    move.b  #0x40, (%a0)

    | Exact frontier shape: 0110 = btst D0,(A0).  14 mod 8 = 6.
    moveq   #14, %d0
    btst    %d0, (%a0)
    beq     _fail
    moveq   #0, %d1
    move.b  (%a0), %d1
    cmp.l   #0x40, %d1
    bne     _fail

    | Modifying siblings write the byte back and still report old-bit Z.
    bclr    %d0, (%a0)
    beq     _fail
    moveq   #0, %d1
    move.b  (%a0), %d1
    cmp.l   #0, %d1
    bne     _fail

    moveq   #9, %d0                  | 9 mod 8 = 1
    bset    %d0, (%a0)
    bne     _fail
    moveq   #0, %d1
    move.b  (%a0), %d1
    cmp.l   #2, %d1
    bne     _fail

    bchg    %d0, (%a0)
    beq     _fail
    moveq   #0, %d1
    move.b  (%a0), %d1
    cmp.l   #0, %d1
    bne     _fail

    | d16(An) shares the same dynamic modulo-8 path.
    lea     0x00112200, %a2
    move.b  #0x80, (4,%a2)
    moveq   #15, %d2                 | 15 mod 8 = 7
    btst    %d2, (4,%a2)
    beq     _fail
    bchg    %d2, (4,%a2)
    beq     _fail
    moveq   #0, %d3
    move.b  (4,%a2), %d3
    cmp.l   #0, %d3
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
