| link_unlk_basic.s — Test for LINK and UNLK instructions
|
| Verifies:
|   1. LINK An,#disp pushes An onto -(SP), copies SP into An, then
|      subtracts |disp| from SP to allocate a local frame.
|   2. UNLK An restores SP from An and pops the saved An from the stack.
|   3. Caller register state (D1 magic value) is preserved across the
|      call/return sequence.
|   4. SP is fully restored to its original value after RTS.
|
| Call tree:
|   _start → BSR _sub        (sets up SP, calls subroutine)
|       _sub: LINK A6,#-16   (allocate 16-byte frame)
|             body (D0 = 0x12345678)
|             UNLK A6        (restore frame)
|             RTS
|   _start: check D0, D1, SP → PASS or FAIL

    .text
    .org 0

_start:
    | Set stack pointer to safe scratch RAM
    lea     0x00010000, %a7     | A7 = 0x00010000 (grows downward)

    | Load canary into D1 — must survive the call
    move.l  #0xDEADCAFE, %d1

    | Call subroutine that uses LINK/UNLK
    bsr     _sub

    | ── Checks ────────────────────────────────────────────────────────
    | D0 must equal 0x12345678 (set inside subroutine)
    cmp.l   #0x12345678, %d0
    bne     _fail

    | D1 must still be the canary (subroutine must not clobber D1)
    cmp.l   #0xDEADCAFE, %d1
    bne     _fail

    | SP must be restored to 0x00010000
    cmp.l   #0x00010000, %a7
    bne     _fail

    | ── PASS ─────────────────────────────────────────────────────────
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)          | PASS sentinel
_halt:
    stop    #0x2700
    bra     _halt

    | ── FAIL ─────────────────────────────────────────────────────────
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)          | FAIL sentinel
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

    | ── Subroutine ───────────────────────────────────────────────────
    | Uses LINK to allocate a 16-byte frame on entry, UNLK to release it.
    | Sets D0 = 0x12345678 as a return value.
    | Does NOT touch D1 — tests that LINK/UNLK preserve caller state.
_sub:
    link    %a6, #-16           | allocate 16-byte frame; A6 = frame pointer
    move.l  #0x12345678, %d0    | return value in D0
    unlk    %a6                 | restore SP and A6 from saved frame pointer
    rts
