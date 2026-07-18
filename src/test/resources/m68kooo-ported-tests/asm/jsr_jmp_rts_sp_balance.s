| jsr_jmp_rts_sp_balance.s -- JSR -> JMP tailcall -> RTS must restore A7.
|
| Q700 ROM VBL dispatch reaches:
|   jsr (a3)       ; wrapper call pushes return PC
|   ...            ; dispatcher jumps through a handler pointer
|   jmp (a0)
|   rts            ; final handler returns to wrapper
|
| Live FPGA boot showed A7 one long too low at the post-JSR return point.
| This isolates that call shape without peripherals.
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00
    .equ FAIL_SP,   0xDEAD0001
    .equ FAIL_PC,   0xDEAD0002

_start:
    move.l  #0x00080000, %a7
    lea     _dispatch, %a3

    jsr     (%a3)
_after:
    cmp.l   #0x00080000, %a7
    bne     _fail_sp

    lea     PASS_SENT, %a0
    move.l  #PASS_VAL, %d0
    move.l  %d0, (%a0)
_halt:
    stop    #0x2700
    bra     _halt

_dispatch:
    lea     _handler, %a0
    jmp     (%a0)

_handler:
    rts

_fail_sp:
    lea     PASS_SENT, %a0
    move.l  #FAIL_SP, %d0
    move.l  %d0, (%a0)
    bra     _halt

_fail_pc:
    lea     PASS_SENT, %a0
    move.l  #FAIL_PC, %d0
    move.l  %d0, (%a0)
    bra     _halt
