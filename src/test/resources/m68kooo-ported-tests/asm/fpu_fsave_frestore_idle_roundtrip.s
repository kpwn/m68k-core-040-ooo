| FSAVE/FRESTORE non-null frame and explicit control-register persistence.
| Ratified scope: FRESTORE skips non-null frame bodies; it does not restore
| FPCR/FPSR/FPIAR. Software saves those separately with FMOVEM.L.
| The v1 parity contract emits a 52-byte, 0x41300000 frame even while idle.
| Checks exact frame/SP, unchanged controls after FRESTORE, then explicit
| control-register restore. No hidden address-keyed stash is expected.

    .text
    .org 0
    .equ PASS_SENT, 0xFFFF0000
    .equ SAVE_BUF,  0x00020000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C
    move.l  #0x11223344, 0x0000FFC8
    move.l  #0x55667788, 0x00010000

    move.l  #0x00000010, %d0
    fmove.l %d0, %fpcr
    move.l  #0x04000000, %d0
    fmove.l %d0, %fpsr
    move.l  #0x40801234, %d0
    fmove.l %d0, %fpiar
    fmovem.l %fpcr/%fpsr/%fpiar, SAVE_BUF
    cmp.l   #0x00000010, SAVE_BUF
    bne     _fail_fpcr
    cmp.l   #0x04000000, SAVE_BUF+4
    bne     _fail_fpsr
    cmp.l   #0x40801234, SAVE_BUF+8
    bne     _fail_fpiar

    fsave   -(%a7)
    cmpa.l  #0x0000FFCC, %a7
    bne     _fail_frame
    cmp.l   #0x41300000, (%a7)
    bne     _fail_frame
    cmp.l   #0x11223344, 0x0000FFC8
    bne     _fail_frame
    cmp.l   #0x55667788, 0x00010000
    bne     _fail_frame

    move.l  #0x00000020, %d0
    fmove.l %d0, %fpcr
    move.l  #0x08000000, %d0
    fmove.l %d0, %fpsr
    move.l  #0x40805678, %d0
    fmove.l %d0, %fpiar

    frestore (%a7)+
    cmpa.l  #0x00010000, %a7
    bne     _fail_frame
    fmove.l %fpcr, %d0
    cmp.l   #0x00000020, %d0
    bne     _fail_fpcr
    fmove.l %fpsr, %d0
    cmp.l   #0x08000000, %d0
    bne     _fail_fpsr
    fmove.l %fpiar, %d0
    cmp.l   #0x40805678, %d0
    bne     _fail_fpiar

    fmovem.l SAVE_BUF, %fpcr/%fpsr/%fpiar
    fmove.l %fpcr, %d0
    cmp.l   #0x00000010, %d0
    bne     _fail_fpcr
    fmove.l %fpsr, %d0
    cmp.l   #0x04000000, %d0
    bne     _fail_fpsr
    fmove.l %fpiar, %d0
    cmp.l   #0x40801234, %d0
    bne     _fail_fpiar
    move.l  #0xC0FFEE00, %d2
    bra     _report
_fail_fpcr:
    move.l  #0xDEAD0F07, %d2
    bra     _report
_fail_fpsr:
    move.l  #0xDEAD0F08, %d2
    bra     _report
_fail_frame:
    move.l  #0xDEAD0F09, %d2
    bra     _report
_fail_fpiar:
    move.l  #0xDEAD0F0A, %d2
    bra     _report
_fline:
    move.l  #0xDEAD0F01, %d2
_report:
    lea     PASS_SENT, %a1
    move.l  %d2, (%a1)
_halt:
    bra     _halt
