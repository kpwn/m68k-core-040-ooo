| trapcc_false_then_true.s — TRAPcc conditional trap (no-operand form).
|
| Stage D-6 (agent/decode-v2-branches): validates the V2 TRAPcc path.
| The V2 assembler produces UOP_INT / ALU_TRAPCC with flags_rd = cccc;
| the ALU raises exception vector 7 when cc_true.
|
| Sequence:
|   Part 1 — TRAPNE with Z=1 (NE condition FALSE): must NOT trap.
|   Part 2 — TRAPEQ with Z=1 (EQ condition TRUE):  must trap (vec 7).
|             Handler increments D7 and RTEs.
|
| PASS iff D7 == 1 at end (exactly one trap fired).

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x0000001C   | vec 7 @ 0x1C
    moveq   #0, %d7                 | trap counter

    | Seed Z=1 via moveq #0 (N=Z=0→1, V=0, C=0).
    moveq   #0, %d0                 | Z=1

    | Part 1 — TRAPNE: cc NE is ~Z.  Z=1 → cc false → NO trap.
    | Opword = 0x56FC (cccc=0x6=NE, mmm=111, rrr=100).
    .short 0x56FC

    | Part 2 — TRAPEQ: cc EQ is Z.  Z=1 → cc true → TRAP vec 7.
    | Opword = 0x57FC (cccc=0x7=EQ, mmm=111, rrr=100).
    .short 0x57FC

    | Check D7 == 1.
    cmpi.l  #1, %d7
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

_handler:
    addq.l  #1, %d7
    rte
