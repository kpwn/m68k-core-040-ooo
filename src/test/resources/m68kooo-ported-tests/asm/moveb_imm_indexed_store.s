| moveb_imm_indexed_store.s -- MOVE.B #imm8,(d8,An,Xn)
|
| Covers the Q700 ROM SCC probe:
|   17bc 0030 3800  move.b #0x30,(0,%a3,%d3.l)

    .text
    .org 0

_start:
    lea     0x00107000, %a3
    moveq   #0, %d3
    move.l  #0xaaaaaaaa, (%a3)
    .word   0x17bc, 0x0030, 0x3800      | move.b #0x30,(0,%a3,%d3.l)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a3), %d0
    cmp.l   #0x30aaaaaa, %d0
    bne     _fail

    | Word index variant: D1.W=4 plus displacement 2 -> A2+6.
    lea     0x00107100, %a2
    moveq   #4, %d1
    move.l  #0xaaaa5555, 4(%a2)
    .word   0x15bc, 0x0081, 0x1002      | move.b #0x81,(2,%a2,%d1.w)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a2), %d2
    cmp.l   #0xaaaa8155, %d2
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
