| pea_aline_store_loss_gap8.s -- PEA STORE loss probe, 8-NOP gap.

    .text
    .org 0

    .equ GAP_COUNT,    8
    .equ STACK_BASE,   0x001FE080
    .equ STACK_HI,     0x001FE08C
    .equ PASS_SENT,    0xFFFF0000
    .equ PASS_VALUE,   0xC0FFEE00
    .equ FAIL_VALUE,   0xDEAD0001

_start:
    move.w  #0x2700, %sr
    move.l  #0x00030000, %sp
    move.l  #_aline_dispatcher, 0x00000028

    move.l  #0x000FE020, %d7
    movec   %d7, %dtt0
    move.l  #0x400FE020, %d7
    movec   %d7, %itt0
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1
    move.l  #0x00008000, %d7
    movec   %d7, %tc
    move.l  #0x80000000, %d7
    movec   %d7, %cacr

    | Expected PEA stores: _sent1 -> 0x001FE088,
    | _sent2 -> 0x001FE084, _sent3 -> 0x001FE080.
    lea     STACK_BASE, %a0
    move.l  #0xBAD00000, (%a0)+
    move.l  #0xBAD00004, (%a0)+
    move.l  #0xBAD00008, (%a0)+
    move.l  #0xBAD0000C, (%a0)+
    move.l  #0xBAD00010, (%a0)+
    move.l  #0xBAD00014, (%a0)+
    move.l  #0xBAD00018, (%a0)+
    move.l  #0xBAD0001C, (%a0)+

    move.l  #0x12345678, %d5
    lea     0x001FF080, %a1
    move.l  %d5, (%a1)
    lea     0x00200080, %a1
    move.l  %d5, (%a1)
    lea     0x00201080, %a1
    move.l  %d5, (%a1)
    lea     0x00202080, %a1
    move.l  %d5, (%a1)
    lea     0x00203080, %a1
    move.l  %d5, (%a1)

    lea     STACK_HI, %a7
    pea     %pc@(_sent1)
    pea     %pc@(_sent2)
    pea     %pc@(_sent3)

    .rept GAP_COUNT
    nop
    .endr

    .short  0xA029

    cmp.l   #STACK_BASE, %a7
    bne     _fail
    move.l  (%a7)+, %d0
    cmp.l   #_sent3, %d0
    bne     _fail
    move.l  (%a7)+, %d0
    cmp.l   #_sent2, %d0
    bne     _fail
    move.l  (%a7)+, %d0
    cmp.l   #_sent1, %d0
    bne     _fail
    cmp.l   #STACK_HI, %a7
    bne     _fail

_pass:
    lea     PASS_SENT, %a0
    move.l  #PASS_VALUE, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     PASS_SENT, %a0
    move.l  #FAIL_VALUE, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_aline_dispatcher:
    move.l  2(%a7), %d6
    addq.l  #2, %d6
    move.l  %d6, 2(%a7)
    rte

    .align 4
_sent1:
    nop
_sent2:
    nop
_sent3:
    nop
