| jmp_pc_areg_index_preserves_an.s -- JMP (d8,PC,An.L) must not clobber An
|
| Covers the Q700 ROM chime helper shape:
|   41f9 ffff ed94  lea 0xffffed94,%a0
|   4efb 88f8       jmp (-8,PC,A0.L)
|
| The indexed JMP consumes A0 as an index register only.  It must not write
| any architectural address register, especially A3 which the ROM keeps as
| the ASC base across this jump.

    .text
    .org 0

_start:
    lea     0x00120000, %a3
    move.b  #0x5a, 0x0800(%a3)

    | Exact ROM LEA immediate value.  This is not used as the branch index in
    | the directed test because the ROM value is only meaningful at ROM PC
    | 0x40846e76, but the write itself must only update A0.
    .word   0x41f9, 0xffff, 0xed94
    cmpa.l  #0x00120000, %a3
    bne     _fail_a3

    lea     (_target - (_rom_shape + 2 - 8)), %a0
_rom_shape:
    .word   0x4efb, 0x88f8

_fail_fallthrough:
    move.l  #0xDEAD0001, %d0
    bra     _fail

_target:
    cmpa.l  #0x00120000, %a3
    bne     _fail_a3
    move.b  0x0800(%a3), %d0
    cmp.b   #0x5a, %d0
    bne     _fail_mem

_pass:
    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a6)
_halt:
    bra     _halt

_fail_a3:
    move.l  #0xDEAD0003, %d0
    bra     _fail

_fail_mem:
    move.l  #0xDEAD0004, %d0
    bra     _fail

_fail:
    lea     0xFFFF0000, %a6
    move.l  %d0, (%a6)
_halt_fail:
    bra     _halt_fail
