| sysop_move_absl_ccr_min.s — minimal test for MOVE.W (xxx).L,CCR

    .text
    .org 0
    .equ FAIL_ADDR, 0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00

_start:
    move.w  #0x0005, 0x00130000        | place CCR=0x05 at 0x130000
    | First clear CCR via ANDI to start known
    andi.w  #0xFFE0, %sr                | mask CCR to 0
    move.w  0x00130000, %ccr            | load CCR from abs.L
    | Read SR and check CCR portion
    move.w  %sr, %d1
    | Use TST.L not AND (ANDI updates CCR which we want to inspect)
    move.l  %d1, %d2                    | snapshot before AND
    andi.l  #0x001F, %d2
    cmp.l   #5, %d2
    bne     _fail

    move.l  #0x00200000, %a7
    lea     FAIL_ADDR, %a0
    move.l  #PASS_VAL, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    move.l  %d1, %d7                    | dump SR snapshot
    move.l  #0x00200000, %a7
    lea     FAIL_ADDR, %a0
    move.l  %d7, (%a0)
1:  bra     1b
