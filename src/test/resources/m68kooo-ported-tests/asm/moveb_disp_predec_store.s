| moveb_disp_predec_store.s -- MOVE.B (d16,An),-(Am)
|
| Covers the Q700 ROM interrupt-mask helper push:
|   1f2a 1c00  move.b 0x1c00(%a2),-(%a7)

    .text
    .org 0

_start:
    lea     0x00108000, %a1
    lea     0x00108104, %a2

    move.l  #0x80112233, 0x10(%a1)
    move.l  #0xaabbccdd, -4(%a2)
    .word   0x1529, 0x0010       | move.b 0x10(%a1),-(%a2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a2, %d0
    cmp.l   #0x00108103, %d0
    bne     _fail
    move.l  -3(%a2), %d1
    cmp.l   #0xaabbcc80, %d1
    bne     _fail

    | Exact ROM opword.  Byte predecrement through A7 steps by 2.
    lea     0x00109000, %a2
    lea     0x0010b204, %a7
    move.l  #0x12000000, 0x1c00(%a2)
    move.l  #0xaabbccdd, -4(%a7)
    .word   0x1f2a, 0x1c00       | move.b 0x1c00(%a2),-(%a7)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a7, %d2
    cmp.l   #0x0010b202, %d2
    bne     _fail
    move.l  -2(%a7), %d3
    cmp.l   #0xaabb12dd, %d3
    bne     _fail

_pass:
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, 0xFFFF0000
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0xFFFF0000
_fail_halt:
    bra     _fail_halt
