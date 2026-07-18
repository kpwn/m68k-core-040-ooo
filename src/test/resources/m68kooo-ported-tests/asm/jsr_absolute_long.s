| jsr_absolute_long.s — JSR (xxx).L (opword 0x4EB9 + 32-bit addr).
|
| Validates the V2 JSR crack for the absolute-long addressing mode.
| Target = {ext1, ext2}.  Return addr = pd_pc + 6.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    move.l  #0xBAD00001, %d0          | canary
    jsr     _target                   | assembler picks (xxx).L form
                                      | (GAS uses absl for far labels)

    | After RTS, D0 must equal 0x12345678.
    cmp.l   #0x12345678, %d0
    bne     _fail

    | Stack pointer must be back to the initial value.
    move.l  %a7, %d1
    cmp.l   #0x00010000, %d1
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

_target:
    move.l  #0x12345678, %d0
    rts
