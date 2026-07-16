| jsr_a7_indirect.s — JSR (A7): the jump target must be the PRE-push
| A7 value (audit follow-up to the MOVE.L A7,-(A7) decode-order bug
| family).
|
| Musashi golden (m68k_in.c jsr):
|     ea = EA;  push_32(REG_PC);  jump(ea);
| — the EA is read BEFORE the return-address push decrements A7.  The
| old RTL 2-µop crack (STORE push, then BR_JMP src_a=An) renamed A7 in
| the push µop, so JSR (A7) jumped to the POST-push A7 (target - 4).
|
| Degenerate-but-defined shape: execution lands at the address that SP
| held.  We point SP at a stub inside the test image (moveq #2,%d6 ;
| rts).  The push writes the return address at stub-4 (a padding long
| we never execute), and RTS pops it back — returning with SP == stub.

    .text
    .org 0

_start:
    moveq   #0, %d6
    move.l  %sp, %d5                | save harness SP
    lea     _jsr_stub(%pc), %a0
    movea.l %a0, %sp                | SP = stub address
    jsr     (%sp)                   | must jump to PRE-push SP = stub
_ret_point:
    | Back here via the stub's RTS.  SP = stub-4 + 4 = stub.
    cmp.l   %a0, %sp                | SP must equal the stub address
    bne     _fail0
    cmp.l   #2, %d6                 | stub executed?
    bne     _fail1
    move.l  %d5, %sp                | restore harness SP

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail0:
    move.l  #0xDEAD0000, %d7
    bra     _fail
_fail1:
    move.l  #0xDEAD0001, %d7
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail

    | Push landing pad — the JSR's return-address write lands in this
    | long.  Never executed.
    .long   0
_jsr_stub:
    moveq   #2, %d6
    rts
