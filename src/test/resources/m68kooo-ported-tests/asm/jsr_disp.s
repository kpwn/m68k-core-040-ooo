| jsr_disp.s — JSR / JMP with (d16,An) addressing must decode + run.
|
| decode.v §2476: the V2 call/return family only accepts the JSR EA
| subset (An)/(xxx).W,.L/(d16,PC); JSR (d16,An) "stays on legacy (and
| therefore on the vec-4 ILLEGAL path today, since legacy doesn't cover
| them)".  (d16,An) is a valid 68040 control-alterable EA for JSR/JMP.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0004  vec-4 illegal-instruction trap — THE BUG
|   0xDEAD0001  JSR (d16,An) did not transfer control / D7 wrong
|   0xDEAD0002  JMP (d16,An) did not transfer control / D6 wrong

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010        | vector 4 (illegal instr) @ 0x10
    moveq   #0, %d7
    moveq   #0, %d6

    | ── JSR (8,A1) ── A1 = _jsr_target - 8, so 8(A1) = _jsr_target
    lea     _jsr_target-8, %a1
    jsr     8(%a1)                        | JSR (d16,An) → _jsr_target, rts back
    cmp.l   #0x5A, %d7
    bne     _f1

    | ── JMP (4,A2) ── A2 = _jmp_target - 4
    lea     _jmp_target-4, %a2
    jmp     4(%a2)                        | JMP (d16,An) → _jmp_target
_after_jmp:
    cmp.l   #0xA5, %d6
    bne     _f2

    | PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_jsr_target:
    move.l  #0x5A, %d7
    rts

_jmp_target:
    move.l  #0xA5, %d6
    bra     _after_jmp

_f1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_h1:
    bra     _h1

_f2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_h2:
    bra     _h2

_illegal:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0             | vec-4 illegal-instruction trap — THE BUG
    move.l  %d0, (%a0)
_h7:
    bra     _h7
