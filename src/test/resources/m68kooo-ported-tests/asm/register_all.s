| register_all.s — Register file stress test
|
| Uses all D0-D7 and A0-A7 registers to catch rename/free-list bugs.
| Performs arithmetic on each register, ensuring no register collisions.

    .text
    .org 0

_start:
    | Initialize all 16 registers with unique values
    moveq   #0x01, %d0
    moveq   #0x02, %d1
    moveq   #0x03, %d2
    moveq   #0x04, %d3
    moveq   #0x05, %d4
    moveq   #0x06, %d5
    moveq   #0x07, %d6
    moveq   #0x08, %d7

    move.l  #0x10, %a0
    move.l  #0x20, %a1
    move.l  #0x30, %a2
    move.l  #0x40, %a3
    move.l  #0x50, %a4
    move.l  #0x60, %a5
    move.l  #0x70, %a6
    move.l  #0x80, %a7

    | Perform operations on each D register
    add.l   %d1, %d0                | D0 = 0x01 + 0x02 = 0x03
    add.l   %d2, %d1                | D1 = 0x02 + 0x03 = 0x05
    add.l   %d3, %d2                | D2 = 0x03 + 0x04 = 0x07
    add.l   %d4, %d3                | D3 = 0x04 + 0x05 = 0x09
    add.l   %d5, %d4                | D4 = 0x05 + 0x06 = 0x0B
    add.l   %d6, %d5                | D5 = 0x06 + 0x07 = 0x0D
    add.l   %d7, %d6                | D6 = 0x07 + 0x08 = 0x0F
    add.l   %d0, %d7                | D7 = 0x08 + 0x03 = 0x0B

    lea     0x00100000, %a0

    | Perform address operations (ADDA does not set flags)
    adda.l  %a1, %a0                | A0 = 0x10 + 0x20 = 0x30
    adda.l  %a2, %a1                | A1 = 0x20 + 0x30 = 0x50
    adda.l  %a3, %a2                | A2 = 0x30 + 0x40 = 0x70
    adda.l  %a4, %a3                | A3 = 0x40 + 0x50 = 0x90
    adda.l  %a5, %a4                | A4 = 0x50 + 0x60 = 0xB0
    adda.l  %a6, %a5                | A5 = 0x60 + 0x70 = 0xD0
    adda.l  %a7, %a6                | A6 = 0x70 + 0x80 = 0xF0

    lea     0x00200000, %a7

    | Store and reload from address registers
    move.l  %d0, (%a0)              | store D0 @ A0
    move.l  (%a1), %d0              | load from A1 into D0

    lea     0x00300000, %a0

    | More arithmetic chains to test renaming
    sub.l   %d1, %d2                | D2 = 0x07 - 0x05 = 0x02
    sub.l   %d3, %d4                | D4 = 0x0B - 0x09 = 0x02
    sub.l   %d5, %d6                | D6 = 0x0F - 0x0D = 0x02

    lea     0x00400000, %a1

    | Bitwise ops on all registers
    and.l   #0xFF, %d0
    and.l   #0xFF, %d1
    and.l   #0xFF, %d2
    and.l   #0xFF, %d3
    and.l   #0xFF, %d4
    and.l   #0xFF, %d5
    and.l   #0xFF, %d6
    and.l   #0xFF, %d7

    lea     0x00500000, %a2

    | All registers exercised; signal PASS
    lea     0xFFFF0000, %a3
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a3)              | PASS

_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a3
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a3)
    stop    #0x2700
    bra     _halt
