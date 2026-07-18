| rom_handler_jsr_rts_sp_balance.s -- Q700 interrupt-wrapper stack balance.
|
| Live boot divergence narrowed to the ROM handler shape:
|   40809b60: movem.l d0-d3/a0-a3,-(sp)
|   40809b64: lea     0x40809bc0(pc),a3
|   40809b6e: jsr     (a3)
|   40809b84: movem.l (sp)+,d0-d3/a0-a3
|   40809b88: rte
|
| MAME preserves SP across the jsr/rts body.  FPGA samples show SP one
| long too low before the epilogue, which would make MOVEM restore from
| the return-PC slot rather than the saved D0 slot.
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00
    .equ FAIL_SP1,  0xDEAD0001
    .equ FAIL_SP2,  0xDEAD0002
    .equ FAIL_REG,  0xDEAD0003

_start:
    move.l  #0x00080000, %a7

    move.l  #0x11111111, %d0
    move.l  #0x22222222, %d1
    move.l  #0x33333333, %d2
    move.l  #0x44444444, %d3
    move.l  #0xaaaaaaaa, %a0
    move.l  #0xbbbbbbbb, %a1
    move.l  #0xcccccccc, %a2
    lea     _body, %a3

    movem.l %d0-%d3/%a0-%a3, -(%a7)
    cmp.l   #0x0007ffe0, %a7
    bne     _fail_sp_after_movem

    jsr     (%a3)

    | JSR pushed one return long and RTS must have popped it.  The ROM
    | epilogue depends on SP still pointing at the MOVEM save area.
    cmp.l   #0x0007ffe0, %a7
    bne     _fail_sp_after_rts

    movem.l (%a7)+, %d0-%d3/%a0-%a3
    cmp.l   #0x00080000, %a7
    bne     _fail_sp_after_rts

    cmp.l   #0x11111111, %d0
    bne     _fail_reg
    cmp.l   #0x22222222, %d1
    bne     _fail_reg
    cmp.l   #0x33333333, %d2
    bne     _fail_reg
    cmp.l   #0x44444444, %d3
    bne     _fail_reg
    cmp.l   #0xaaaaaaaa, %a0
    bne     _fail_reg
    cmp.l   #0xbbbbbbbb, %a1
    bne     _fail_reg
    cmp.l   #0xcccccccc, %a2
    bne     _fail_reg

    lea     PASS_SENT, %a0
    move.l  #PASS_VAL, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_body:
    rts

_fail_sp_after_movem:
    lea     PASS_SENT, %a0
    move.l  #FAIL_SP1, %d0
    move.l  %d0, (%a0)
    bra     _halt

_fail_sp_after_rts:
    lea     PASS_SENT, %a0
    move.l  #FAIL_SP2, %d0
    move.l  %d0, (%a0)
    bra     _halt

_fail_reg:
    lea     PASS_SENT, %a0
    move.l  #FAIL_REG, %d0
    move.l  %d0, (%a0)
    bra     _halt
