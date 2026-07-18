| pea_link_unlk.s — PEA (push effective address), LINK, UNLK (stack frame setup)
|
| NOTE: PEA, LINK, UNLK are NOT yet implemented in decode.v.
| This test is a placeholder for when they are.
|
| These instructions are critical for function prologues/epilogues:
|   - PEA — push effective address onto stack
|   - LINK — set up a stack frame
|   - UNLK — tear down a stack frame

    .text
    .org 0

_start:
    | Set up stack pointer
    lea     0x00010000, %a7         | A7 = 0x00010000 (top of stack)

    | Test 1: PEA — push effective address
    | PEA (address) pushes the address itself (not a value at that address)
    | PEA 0x12345678 → [A7] = 0x12345678, then A7 -= 4
    pea     0x12345678              | push address 0x12345678 onto stack

    lea     0x00100000, %a0

    | Test 2: LINK — establish stack frame
    | LINK A6, #-16 → save old A6, set A6=A7, then A7 -= 16
    move.l  #0xAABBCCDD, %a6        | old frame pointer
    link    %a6, #-16               | A6 = A7 (current), A7 -= 16

    lea     0x00200000, %a0

    | Test 3: Use the frame pointer to store local variables
    | [A6 - 4] = local1, [A6 - 8] = local2
    move.l  #0x11223344, %d0
    move.l  %d0, (-4,%a6)           | store local variable

    move.l  #0x55667788, %d1
    move.l  %d1, (-8,%a6)           | store another local variable

    lea     0x00300000, %a1

    | Test 4: UNLK — tear down stack frame
    | UNLK A6 → A7 = A6, restore old A6 from [A7]
    unlk    %a6                     | restore A7 and old A6

    lea     0x00400000, %a1

    | All stack frame tests done; signal PASS
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
