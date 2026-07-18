| move_mem_to_disp_dest.s -- MOVE.{B,W} memory sources into displaced destinations.

    .text
    .org 0

_start:
    lea     0x00108400, %a1
    lea     0x00108410, %a2
    move.l  #0x80000000, (%a1)
    move.l  #0xaaaaaaaa, 8(%a2)
    .word   0x1551, 0x0008      | move.b (%a1),8(%a2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  8(%a2), %d0
    cmp.l   #0x80aaaaaa, %d0
    bne     _fail

    | Exact Q700 ROM frontier shape:
    |   1169 0009 0035  move.b 9(A1),53(A0)
    lea     0x00108480, %a1
    lea     0x001084c0, %a0
    move.l  #0x11802233, 8(%a1)
    move.l  #0xaabbccdd, 52(%a0)
    .word   0x1169, 0x0009, 0x0035
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  52(%a0), %d2
    cmp.l   #0xaa80ccdd, %d2
    bne     _fail

    | Sign-extended source and destination displacements; source byte is zero.
    lea     0x00108540, %a3
    lea     0x00108580, %a4
    move.l  #0x11223300, -4(%a3)
    move.l  #0xaabbccdd, -4(%a4)
    .word   0x196b, 0xffff, 0xfffd      | move.b -1(A3),-3(A4)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  -4(%a4), %d2
    cmp.l   #0xaa00ccdd, %d2
    bne     _fail

    lea     0x00108430, %a3
    lea     0x00108440, %a4
    move.l  #0x00001234, (%a3)
    move.l  #0xbbbbbbbb, -2(%a4)
    .word   0x3953, 0xfffe      | move.w (%a3),-2(%a4)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  -2(%a4), %d1
    cmp.l   #0x0000bbbb, %d1
    bne     _fail

    | Exact Q700 ROM frontier shape:
    |   3368 0026 0004  move.w 38(A0),4(A1)
    lea     0x001085c0, %a0
    lea     0x00108600, %a1
    move.l  #0x22228001, 36(%a0)
    move.l  #0xaaaa5555, 4(%a1)
    .word   0x3368, 0x0026, 0x0004
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a1), %d2
    cmp.l   #0x80015555, %d2
    bne     _fail

    | Sign-extended source and destination displacements for the word form.
    lea     0x00108680, %a0
    lea     0x001086c0, %a1
    move.l  #0x00001122, -4(%a0)
    move.l  #0x33334444, -8(%a1)
    .word   0x3368, 0xfffc, 0xfffa      | move.w -4(A0),-6(A1): word=0 → Z=1 N=0
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  -8(%a1), %d2
    cmp.l   #0x33330000, %d2
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
