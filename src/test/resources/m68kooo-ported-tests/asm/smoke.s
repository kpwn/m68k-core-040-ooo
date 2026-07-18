| smoke.s — Minimal bring-up test for m68k-ooo
|
| Exercises the narrow decoder subset (MOVEQ, MOVE.L #imm32,Dn,
| ADD.L Dn,Dm, LEA (xxx).L,An, MOVE.L Dn,(An), STOP) and signals
| PASS by writing 0xC0FFEE00 to 0xFFFF0000.
|
| Assembled flat (no ELF), loaded at 0x40800000 (reset PC).

    .text
    .org 0

_start:
    moveq   #1, %d0             | D0 = 1
    moveq   #2, %d1             | D1 = 2
    add.l   %d0, %d1            | D1 = 3  (exercise ALU + CDB wake-up)
    add.l   %d1, %d0            | D0 = 4
    move.l  #0xC0FFEE00, %d2    | D2 = magic PASS value
    lea     0xFFFF0000, %a0     | A0 = magic address
    move.l  %d2, (%a0)          | store → TB sees it, declares PASS
_halt:
    stop    #0x2700             | halt (decoded as NOP here, harmless)
    bra     _halt
