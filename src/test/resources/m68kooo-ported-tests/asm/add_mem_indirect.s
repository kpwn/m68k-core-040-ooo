| add_mem_indirect.s -- ADD.{B,W,L} (An),Dn memory-source forms
|
| Covers the Q700 ROM ASC mixer shape:
|   40807102: d810    add.b (%a0),%d4
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00105000, %a0
    move.l  #0x01000000, (%a0)

    | Byte memory source: preserve upper Dn bits, set N/V, clear C/X.
    move.l  #0x1234007f, %d4
    add.b   (%a0), %d4
    bpl     _fail
    bvc     _fail
    bcs     _fail
    cmp.l   #0x12340080, %d4
    bne     _fail

    | Byte carry path writes C/X and wraps the low byte only.
    move.l  #0xaa5500ff, %d0
    add.b   (%a0), %d0
    bne     _fail
    bcc     _fail
    cmp.l   #0xaa550000, %d0
    bne     _fail

    | Word memory source.
    lea     0x00105010, %a1
    move.l  #0x00010000, (%a1)
    move.l  #0xabcd0002, %d1
    add.w   (%a1), %d1
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xabcd0003, %d1
    bne     _fail

    | Long memory source.
    lea     0x00105020, %a2
    move.l  #0x00000005, (%a2)
    move.l  #0x00000007, %d2
    add.l   (%a2), %d2
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x0000000c, %d2
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
