| divu_word_by_zero.s — DIVU.W #0 → integer divide-by-zero exception
|
| PRM §4.44 DIVU.W: 32÷16 → 16:16.  Divisor == 0 raises vector 5
| (integer divide-by-zero).  CCR undefined; Dn unchanged.
|
| This test verifies the exception path still fires correctly after
| the D-5 V2 migration of DIVU.W / DIVS.W.  The V2 crack is 2-µop
| (pre-ext dst Dn → TMP1, then DIVU TMP1,imm).  The divide-by-zero
| decision happens inside mul_div.v at the MUL/DIV phase, so the
| exception must still go through the ALU/ROB exception pipe.
|
| Strategy: put a trap vector pointing at _caught ahead of DIVU.W #0.
| If the exception fires correctly, control resumes at _caught and we
| write PASS.  Absence of the trap would continue past DIVU and hit
| the sentinel _fail branch.

    .text
    .org 0

_start:
    | ── Install vector 5 handler ──────────────────────────────────
    | Vector 5 lives at VBR + 5*4 = 0x14.  RESET_PC = 0x40800000; VBR
    | defaults to 0 after reset in our model.  We install our handler
    | address into memory[0x14], using move.l — the exception.v state
    | machine reads from VBR+20 to find the handler entry.
    lea     0x14, %a0
    move.l  #_caught, (%a0)

    | ── Test 1: DIVU.W #0, D0 — must trap ─────────────────────────
    | If the exception fires, PC jumps to _caught and we write PASS.
    | If it silently passes through, we fall into _fail.
    move.l  #12345, %d0
    divu.w  #0, %d0                  | vec 5 trap
    bra     _fail                     | unreachable if trap fires

_caught:
    | ── Exception handler: write PASS sentinel and halt ───────────
    | We can't execute RTE cleanly without a proper supervisor stack
    | setup, so just drop into the sentinel write and halt.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
    bra     _halt
