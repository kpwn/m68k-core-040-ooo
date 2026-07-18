| bsr_rts_basic.s — Test for BSR (branch to subroutine) and RTS (return)
|
| Verifies:
|   1. BSR pushes the return address onto the stack and jumps to the subroutine.
|   2. RTS pops the return address and resumes the caller.
|   3. The subroutine can pass a return value in D0.
|   4. A second (nested) BSR from within the first subroutine proves that the
|      stack discipline is correct for two levels of call depth.
|
| Call tree:
|   _start → BSR _sub_outer
|               _sub_outer sets D1 = 0xABCD1234, then BSR _sub_inner
|                   _sub_inner sets D0 = 0x12345678, RTS
|               _sub_outer checks D0 == 0x12345678, then RTS
|   _start checks D0 == 0x12345678 and D1 == 0xABCD1234 → PASS or FAIL

    .text
    .org 0

_start:
    | Initialise stack pointer to safe scratch RAM before any BSR
    lea     0x00010000, %a7     | A7 = 0x00010000 (grows downward)

    | Call outer subroutine
    bsr     _sub_outer          | push PC, jump to _sub_outer

    | Back from _sub_outer — verify D0 and D1
    cmp.l   #0x12345678, %d0
    bne     _fail               | D0 wrong → FAIL

    cmp.l   #0xABCD1234, %d1
    bne     _fail               | D1 wrong → FAIL

    | ── PASS ────────────────────────────────────────────────────────────
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)          | PASS sentinel
_halt:
    stop    #0x2700
    bra     _halt

    | ── FAIL ────────────────────────────────────────────────────────────
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)          | FAIL sentinel
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

    | ── Outer subroutine ─────────────────────────────────────────────────
    | Sets D1 = 0xABCD1234, then calls _sub_inner which sets D0.
    | Returns with D0 = 0x12345678, D1 = 0xABCD1234.
_sub_outer:
    move.l  #0xABCD1234, %d1    | canary value — must survive nested call
    bsr     _sub_inner          | nested BSR: push PC, jump to _sub_inner
    | D0 = 0x12345678 (set by _sub_inner) on return here
    rts                         | return to _start

    | ── Inner subroutine ─────────────────────────────────────────────────
    | Loads the expected magic value into D0 and returns.
_sub_inner:
    move.l  #0x12345678, %d0    | return value
    rts                         | return to _sub_outer
