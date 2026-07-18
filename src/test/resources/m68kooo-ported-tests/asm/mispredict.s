| mispredict.s — Forward Bcc whose direction depends on a recent CMP
|
| A bimodal predictor with no history defaults to "not-taken" for
| forward conditional branches it has never seen. Here BEQ depends on
| a CMP that produces Z=1, so the branch is actually taken — a
| mispredict on cold start. The pipeline must squash the wrong-path
| uops AFTER the branch and resume at _target.
|
| The wrong path contains a poison MOVE that would put 0xBAD into D5;
| if mispredict recovery is broken, that store ends up at 0xFFFF0000.

    .text
    .org 0

_start:
    moveq   #0x42, %d0
    moveq   #0x42, %d1
    cmp.l   %d1, %d0            | equal → Z=1
    | padding so the CMP commits and arch_ccr is clean
    lea     0x00100000, %a0
    lea     0x00100010, %a1
    lea     0x00100020, %a2

    beq     _target             | Z=1 → taken; predictor likely says NTKN

    | wrong-path poison
    move.l  #0xBAD00BAD, %d5
    lea     0xFFFF0000, %a3
    move.l  %d5, (%a3)          | would FAIL if it commits
_halt_fail:
    bra     _halt_fail

_target:
    lea     0xFFFF0000, %a3
    move.l  #0xC0FFEE00, %d5
    move.l  %d5, (%a3)          | PASS store
_halt:
    bra     _halt
