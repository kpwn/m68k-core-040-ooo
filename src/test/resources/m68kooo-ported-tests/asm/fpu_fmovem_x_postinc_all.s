| fpu_fmovem_x_postinc_all.s - full-width FPSP/task-switch restore.
|
| System 7 emits F21F D0FF (fmovem.x (sp)+,fp0-fp7) while launching
| applications.  This is the largest FMOVEM.X crack: 64 data/pad uops plus
| the postincrement update.  Keep the exact shape covered so queue or rename
| pressure cannot turn it into a silent core deadlock.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SRC,       0x00021000
    .equ OUT,       0x00021100

_start:
    lea     SRC, %a0
    moveq   #23, %d0
    move.l  #0x01020304, %d1
_fill:
    move.l  %d1, (%a0)+
    add.l   #0x11111111, %d1
    dbra    %d0, _fill

    | A Motorola 96-bit memory image carries the 80-bit value as
    | {sign, exponent, 16'h0000, mantissa}.  The FPU canonicalizes that
    | padding word on store, so make every source slot architecturally valid.
    lea     SRC+2, %a0
    moveq   #7, %d0
_clear_pad:
    clr.w   (%a0)
    adda.l  #12, %a0
    dbra    %d0, _clear_pad

    lea     SRC, %a7
    fmovem.x (%a7)+, %fp0-%fp7

    cmpa.l  #SRC+96, %a7
    bne     _fail_sp

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
    move.l  #0xDEAD1301, %d2
    bra     _report
_fail_data:
    move.l  #0xDEAD1302, %d2

_report:
    move.l  %d2, PASS_SENT
_halt:
    bra     _halt
