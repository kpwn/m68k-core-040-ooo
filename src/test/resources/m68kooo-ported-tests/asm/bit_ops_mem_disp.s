| bit_ops_mem_disp.s -- static bit ops on (d16,An) byte memory
|
| Covers the ROM shapes at 0x40846cc2..0x40846cce:
|   08aa 0000 0600    bclr #0, 0x600(%a2)
|   082a 0000 1e00    btst #0, 0x1e00(%a2)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0xFF008000, 0x00100120
    move.l  #0x00100100, %a2

    | BCLR clears a set low bit and reports old bit set (Z=0).
    bclr    #0, 0x20(%a2)
    beq     _fail
    move.b  0x20(%a2), %d0
    and.l   #0xFF, %d0
    cmp.l   #0xFE, %d0
    bne     _fail

    | BTST sees the bit as clear after BCLR and does not modify memory.
    btst    #0, 0x20(%a2)
    bne     _fail
    move.b  0x20(%a2), %d0
    and.l   #0xFF, %d0
    cmp.l   #0xFE, %d0
    bne     _fail

    | BSET sets a clear bit and reports old bit clear (Z=1).
    bset    #3, 0x21(%a2)
    bne     _fail
    move.b  0x21(%a2), %d1
    and.l   #0xFF, %d1
    cmp.l   #0x08, %d1
    bne     _fail

    | BCHG toggles a set high bit and reports old bit set (Z=0).
    bchg    #7, 0x22(%a2)
    beq     _fail
    move.b  0x22(%a2), %d2
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
