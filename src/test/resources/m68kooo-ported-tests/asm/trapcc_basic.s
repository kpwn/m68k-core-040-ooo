| trapcc_basic.s — TRAPcc conditional trap (vec 7).
|
| TRAPcc raises exception vector 7 iff the condition code is true.
| Forms:
|   TRAPcc           (no operand,    2-byte opcode)
|   TRAPcc.W #imm16  (4-byte total)
|   TRAPcc.L #imm32  (6-byte total)
|
| The immediate (if present) is skipped by the CPU; a handler can pick
| it up from the stacked PC if it wants.  The PRM says post-instruction
| PC (fall-through) is stacked, so RTE resumes at the instruction AFTER
| the TRAPcc regardless of trap/no-trap.
|
| Cases:
|   1. TRAPT  (always traps) — handler fires, increments counter.
|   2. TRAPF  (never traps)  — no handler fire.
|   3. TRAPNE after Z=0  — should trap.
|   4. TRAPNE after Z=1  — should NOT trap.
|   5. TRAPT.W #0x1234  — handler fires, counter increments; fall-through
|      resumes at the instruction after the full 4-byte TRAPcc.W.
|   6. TRAPT.L #0xdeadbeef — handler fires, 6-byte resume.
|
| Final count: expect D7 = 4 (cases 1, 3, 5, 6).
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x0000001C   | vec 7 @ 0x1C
    moveq   #0, %d7                  | D7 = trap counter

    | Case 1: TRAPT — unconditional trap.
    trapt
    | After RTE, we resume here.  D7 should be 1.

    | Case 2: TRAPF — never traps.
    trapf
    | D7 should still be 1 (no handler fire).

    | Case 3: TRAPNE after moveq #1,D0 + tst.l D0 → Z=0.
    moveq   #1, %d0
    tst.l   %d0                      | Z=0, so NE is true
    trapne
    | D7 should be 2.

    | Case 4: TRAPNE after moveq #0 + tst → Z=1.
    moveq   #0, %d0
    tst.l   %d0                      | Z=1, so NE is false
    trapne
    | D7 should still be 2.

    | Case 5: TRAPT.W #0x1234 — 4-byte with word imm.
    trapt.w #0x1234
    | D7 should be 3.

    | Case 6: TRAPT.L #0xdeadbeef — 6-byte with long imm.
    trapt.l #0xdeadbeef
    | D7 should be 4.

    | Final check: D7 == 4?
    cmpi.l  #4, %d7
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    addq.l  #1, %d7
    rte
