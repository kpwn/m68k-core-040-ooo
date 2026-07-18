| jsr_an_indirect.s — JSR (An) register-indirect.
|
| Validates V2 JSR (An) crack — target is the value of An, read at
| execute time via src_a=An.  Legacy and V2 both use BR_JMP for this.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    lea     _target, %a0              | A0 = &_target
    move.l  #0xDEAD1111, %d0          | canary

    jsr     (%a0)                     | JSR (A0) — opword 0x4E90

    cmp.l   #0xFACEB00C, %d0
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
    move.l  #0xFACEB00C, %d0
    rts
