| rom_diag_mem_shape.s -- Q700 ROM RAM diagnostic memory-EA forms
|
| Mirrors the compact instruction cluster at 0x40847698:
|   2092       move.l (%a2),(%a0)
|   2152 0004  move.l (%a2),4(%a0)
|   2180 1000  move.l %d0,(0,%a0,%d1:w)
|   4cd0 000c  movem.l (%a0),%d2-%d3
|   3180 1000  move.w %d0,(0,%a0,%d1:w)
|   1180 1000  move.b %d0,(0,%a0,%d1:w)

    .text
    .org 0

_start:
    lea     0x00105000, %a0
    lea     0x00105100, %a1
    lea     0x00105200, %a2

    move.l  #0x00112233, (%a2)
    move.l  #0xdc001008, 4(%a2)
    move.l  #0xaaaaaaaa, (%a0)
    move.l  #0xbbbbbbbb, 4(%a0)

    .word   0x2092              | move.l (%a2),(%a0)
    .word   0x2152, 0x0004      | move.l (%a2),4(%a0)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    move.l  #0x00000004, %d1
    move.l  #0x80000004, %d0
    .word   0x2180, 0x1000      | move.l %d0,(0,%a0,%d1:w)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    .word   0x4cd0, 0x000c      | movem.l (%a0),%d2-%d3
    cmp.l   #0x00112233, %d2
    bne     _fail
    cmp.l   #0x80000004, %d3
    bne     _fail

    move.l  #0x11223344, 4(%a0)
    move.l  #0x00000006, %d1
    move.l  #0x00008001, %d0
    .word   0x3180, 0x1000      | move.w %d0,(0,%a0,%d1:w)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a0), %d4
    cmp.l   #0x11228001, %d4
    bne     _fail

    | The ROM's word phase also stores at odd byte offsets.  Offset 1
    | must update the middle two lanes of the first longword.
    move.l  #0x88888888, (%a0)
    move.l  #0x88888888, 4(%a0)
    move.l  #0x00000001, %d1
    move.l  #0x00000011, %d0
    .word   0x3180, 0x1000      | move.w %d0,(0,%a0,%d1:w)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    .word   0x4cd0, 0x000c      | movem.l (%a0),%d2-%d3
    cmp.l   #0x88001188, %d2
    bne     _fail
    cmp.l   #0x88888888, %d3
    bne     _fail

    move.l  #0x55667788, 4(%a0)
    move.l  #0x00000007, %d1
    move.l  #0x0000007f, %d0
    .word   0x1180, 0x1000      | move.b %d0,(0,%a0,%d1:w)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a0), %d5
    cmp.l   #0x5566777f, %d5
    bne     _fail

    | Word index must sign-extend.  D1.W = -4 stores before A0.
    move.l  #0x0000fffc, %d1
    move.l  #0x2468ace0, %d0
    move.l  #0x00000000, -4(%a0)
    .word   0x2180, 0x1000      | move.l %d0,(0,%a0,%d1:w)
    move.l  -4(%a0), %d6
    cmp.l   #0x2468ace0, %d6
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
