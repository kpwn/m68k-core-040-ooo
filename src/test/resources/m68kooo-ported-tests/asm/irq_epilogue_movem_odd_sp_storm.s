| irq_epilogue_movem_odd_sp_storm.s -- repeated ROM-shaped handler epilogue.
|
| Live FPGA boot wedge:
|   40809b60: movem.l d0-d3/a0-a3,-(sp)
|   ...
|   40809b84: movem.l (sp)+,d0-d3/a0-a3
| with SSP % 4 == 2 after thousands of level-1 handler entries.  The
| single-shot tests cover one IRQ and one isolated MOVEM.  This keeps the
| same misaligned SSP shape and runs the MOVEM save/restore pair repeatedly
| through exception entry/RTE.
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SSP_BASE,  0x00080002
    .equ COUNT,     0x00010000
    .equ TARGET,    0x00001080

_start:
    lea     SSP_BASE, %a7

    move.l  #0, COUNT.l
    move.l  #_handler, 0x00000084      | TRAP #1 vector

    move.l  #TARGET, %d7
_loop:
    trap    #1
    subq.l  #1, %d7
    bne     _loop

    move.l  COUNT.l, %d0
    cmp.l   #TARGET, %d0
    bne     _fail

    lea     PASS_SENT, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     PASS_SENT, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

_handler:
    movem.l %d0-%d3/%a0-%a3, -(%a7)
    move.l  COUNT.l, %d0
    addq.l  #1, %d0
    move.l  %d0, COUNT.l
    movem.l (%a7)+, %d0-%d3/%a0-%a3
    rte
