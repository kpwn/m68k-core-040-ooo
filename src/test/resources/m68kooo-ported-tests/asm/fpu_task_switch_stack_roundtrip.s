| fpu_task_switch_stack_roundtrip.s - System 7 FP task-switch frame.
|
| Mirrors the live sequence at 0x8fc98 which preceded an SSP collapse:
|   fsave -(sp)
|   fmovem.x fp0-fp7,-(sp)
|   fmovem.l fpiar/fpsr/fpcr,-(sp)
|   st -(sp)
|   ...
|   addq #2,sp
|   fmovem.l (sp)+,fpiar/fpsr/fpcr
|   fmovem.x (sp)+,fp0-fp7
|   frestore (sp)+
|
| The sequence must be stack-neutral and restore both FP data and control
| state.  Repetition catches a one-frame adjustment error as cumulative A7
| drift rather than allowing a single balanced-looking pass.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SRC,       0x00021000
    .equ OUT,       0x00021100
    .equ STACK_TOP, 0x00030000

_start:
    lea     STACK_TOP, %a7
    move.l  %a7, %a6

    | Seed eight valid 96-bit extended values.  The 16-bit pad in each
    | memory image is architecturally zero.
    lea     SRC, %a0
    moveq   #23, %d0
    move.l  #0x01020304, %d1
_fill:
    move.l  %d1, (%a0)+
    add.l   #0x11111111, %d1
    dbra    %d0, _fill
    lea     SRC+2, %a0
    moveq   #7, %d0
_clear_pad:
    clr.w   (%a0)
    adda.l  #12, %a0
    dbra    %d0, _clear_pad

    lea     SRC, %a0
    fmovem.x (%a0)+, %fp0-%fp7

    move.l  #0x00000010, %d0
    .short  0xF200, 0x9000             | fmove.l d0,fpcr
    move.l  #0x12340000, %d0
    .short  0xF200, 0x8800             | fmove.l d0,fpsr

    moveq   #3, %d7
_roundtrip:
    .short  0xF327                     | fsave -(sp), 52-byte IDLE frame
    tst.b   (%a7)
    beq     _fail_null
    .short  0xF227, 0xE0FF             | fmovem.x fp0-fp7,-(sp)
    move.l  (%a7), %d3
    cmp.l   SRC, %d3
    bne     _fail_save_data
    .short  0xF227, 0xBC00             | fmovem.l fpiar/fpsr/fpcr,-(sp)
    st      -(%a7)                     | A7 byte predecrement is two bytes

    | Clobber the live control state so the postincrement load direction
    | must consume memory data rather than passing by timing accident.
    move.l  #0x00000000, %d0
    .short  0xF200, 0x9000             | fmove.l d0,fpcr
    .short  0xF200, 0x8800             | fmove.l d0,fpsr

    addq.l  #2, %a7
    .short  0xF21F, 0x9C00             | fmovem.l (sp)+,fpiar/fpsr/fpcr
    .short  0xF21F, 0xD0FF             | fmovem.x (sp)+,fp0-fp7
    .short  0xF35F                     | frestore (sp)+

    cmpa.l  %a6, %a7
    bne     _fail_sp
    dbra    %d7, _roundtrip

    .short  0xF200, 0xB000             | fmove.l fpcr,d0
    cmp.l   #0x00000010, %d0
    bne     _fail_fpcr
    .short  0xF200, 0xA800             | fmove.l fpsr,d0
    and.l   #0xFFFF0000, %d0
    cmp.l   #0x12340000, %d0
    bne     _fail_fpsr

    lea     OUT, %a0
    fmovem.x %fp0-%fp7, (%a0)
    lea     SRC, %a0
    lea     OUT, %a1
    moveq   #23, %d0
_compare:
    cmpm.l  (%a0)+, (%a1)+
    dbne    %d0, _compare
    bne     _fail_data

    move.l  #0xC0FFEE00, %d2
    bra     _report

_fail_sp:
    move.l  #0xDEAD1401, %d2
    bra     _report
_fail_fpcr:
    move.l  #0xDEAD1402, %d2
    bra     _report
_fail_fpsr:
    move.l  #0xDEAD1403, %d2
    bra     _report
_fail_data:
    move.l  #0xDEAD1404, %d2
    bra     _report
_fail_null:
    move.l  #0xDEAD1405, %d2
    bra     _report
_fail_save_data:
    move.l  #0xDEAD1406, %d2

_report:
    move.l  %d2, PASS_SENT
_halt:
    bra     _halt
