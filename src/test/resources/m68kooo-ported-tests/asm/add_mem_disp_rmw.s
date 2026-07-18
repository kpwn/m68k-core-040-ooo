| add_mem_disp_rmw.s -- ADD.L Dn,(d16,An) read-modify-write
|
| Covers the Q700 ROM frame-helper relocation ops:
|   d3aa 0004  add.l %d1,4(%a2)
|   d3aa 0008  add.l %d1,8(%a2)

    .text
    .org 0

_start:
    lea     0x00104000, %a2

    move.l  #0x00000010, 4(%a2)
    move.l  #0x00000005, %d1
    .word   0xd3aa, 0x0004      | add.l %d1,4(%a2)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a2), %d0
    cmp.l   #0x00000015, %d0
    bne     _fail

    | Carry and zero flags from a 32-bit memory destination.
    move.l  #0xffffffff, 8(%a2)
    move.l  #0x00000001, %d1
    .word   0xd3aa, 0x0008      | add.l %d1,8(%a2)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcc     _fail
    move.l  8(%a2), %d2
    cmp.l   #0x00000000, %d2
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
