| prm_super_eori_sr_sets_t1.s — supervisor EORI #imm,SR updates T1.
|
| Spec: M68000 PRM, EORI to SR and SR bit definitions.  In supervisor
| mode, EORI #imm,SR updates the full SR, including trace bits.

    .text
    .org 0

    .equ COUNTER, 0x00000420

_start:
    lea     0x00010000, %a7
    move.l  #_trace_handler, 0x00000024
    move.l  #0, COUNTER.l

    eori.w  #0x8000, %sr       | set T1 while remaining supervisor
    nop                         | traced after retirement

    move.l  COUNTER.l, %d0
    cmp.l   #1, %d0
    bne     _fail1

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d7
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail

_trace_handler:
    addq.l  #1, COUNTER.l
    move.w  (%a7), %d0
    andi.w  #0x7FFF, %d0       | clear T1 in stacked SR
    move.w  %d0, (%a7)
    rte
