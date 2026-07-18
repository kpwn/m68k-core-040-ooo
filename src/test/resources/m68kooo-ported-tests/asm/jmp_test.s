| jmp_test.s — Test for JMP (xxx).L and JMP (An)
|
| 1. JMP (xxx).L to _jmp1 (absolute long form, 0x4EF9)
| 2. From _jmp1, set A0 to address of _jmp2, then JMP (A0) (register indirect)
| 3. From _jmp2, write PASS sentinel

    .text
    .org 0

_start:
    jmp     _jmp1                   | JMP (xxx).L  — absolute jump

    | FAIL path: should not reach here
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

_jmp1:
    | JMP (An): load address of _jmp2 into A0, then jump
    lea     _jmp2, %a0
    jmp     (%a0)                   | JMP (An) — register-indirect jump

    | FAIL path
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a1)
_halt_fail2:
    stop    #0x2700
    bra     _halt_fail2

_jmp2:
    | PASS
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    stop    #0x2700
    bra     _halt
