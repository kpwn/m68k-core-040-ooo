| jmp_jsr_return.s — JMP, JSR, RTS (unconditional jump and subroutine calls)
|
| NOTE: JSR and RTS are NOT yet implemented in decode.v.
| JMP may be partially implemented. This test is a placeholder for when full support is added.
|
| Tests:
|   1. JMP — unconditional absolute jump (bypasses normal pipeline)
|   2. JSR — jump to subroutine (pushes return address on stack)
|   3. RTS — return from subroutine (pops return address)

    .text
    .org 0

_start:
    | Test 1: JMP — absolute jump
    | PC-relative jumps (BRA) are already tested; JMP is for absolute addresses.
    | For now, we'll skip this since JMP is tricky without proper linker support.

    | Test 2: JSR — jump to subroutine
    | JSR pushes the return address (PC) onto the stack and jumps.
    | We'll set up the stack pointer and call a subroutine.

    lea     0x00010000, %a7         | A7 = stack pointer at 0x00010000

    jsr     _subroutine             | call subroutine (pushes return address)

    | After JSR, we should return here
    lea     0x00100000, %a0         | padding

    | Check that subroutine ran by verifying D0 was modified
    cmp.l   #0x12345678, %d0        | did subroutine set D0?
    beq     _pass                   | if yes, PASS
    bra     _fail

_subroutine:
    | Subroutine body
    move.l  #0x12345678, %d0        | set D0 as a marker
    rts                             | return (pops return address into PC)

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)              | PASS

_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
    stop    #0x2700
    bra     _halt
