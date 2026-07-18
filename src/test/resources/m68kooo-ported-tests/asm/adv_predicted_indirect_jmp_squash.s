| adv_predicted_indirect_jmp_squash.s
|
| A warmed BPU hit for a taken indirect JMP must redirect fetch without
| allowing the sequential fall-through uop sitting at decode to enter the
| dispatch register.  The Q700 ROM hits this shape with:
|   4efb 88f8    jmp (-8,PC,A0.L)
|
| First trip trains the BPU.  Second trip should be predicted-taken; if
| dispatch latches the fall-through packet on that predicted redirect,
| D0 increments or the FAIL path stores.

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    moveq   #0, %d0
    moveq   #2, %d7

_loop:
    move.l  #(_target - (_jmp + 2 - 8)), %a0
_jmp:
    .word   0x4efb, 0x88f8        | jmp (-8,PC,A0.L)

_fallthrough:
    addq.l  #1, %d0
    bra     _fail

_target:
    subq.l  #1, %d7
    bne     _loop
    cmp.l   #0, %d0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a0)
_halt_f:
    bra     _halt_f
