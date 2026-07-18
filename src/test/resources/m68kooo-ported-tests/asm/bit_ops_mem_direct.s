| bit_ops_mem_direct.s -- static bit ops on (An) byte memory
|
| Covers the ROM shape at 0x4084b394:
|   0892 0002    bclr #2, (%a2)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0x14008001, 0x00100100
    move.l  #0x00100100, %a2

    | BCLR clears a set bit in (A2) and reports old bit set.
    bclr    #2, (%a2)
    beq     _fail
    move.b  (%a2), %d0
    and.l   #0xFF, %d0
    cmp.l   #0x10, %d0
    bne     _fail

    | BTST sees the cleared bit and leaves memory unchanged.
    btst    #2, (%a2)
    bne     _fail
    move.b  (%a2), %d0
    and.l   #0xFF, %d0
    cmp.l   #0x10, %d0
    bne     _fail

    | BSET sets a clear bit in the next byte.
    move.l  #0x00100101, %a3
    bset    #3, (%a3)
    bne     _fail
    move.b  (%a3), %d1
    and.l   #0xFF, %d1
    cmp.l   #0x08, %d1
    bne     _fail

    | BCHG toggles a set high bit in the third byte.
    move.l  #0x00100102, %a3
    bchg    #7, (%a3)
    beq     _fail
    move.b  (%a3), %d2
    and.l   #0xFF, %d2
    cmp.l   #0x00, %d2
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
