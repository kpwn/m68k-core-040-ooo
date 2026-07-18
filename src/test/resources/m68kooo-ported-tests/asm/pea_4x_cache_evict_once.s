| pea_4x_cache_evict_once.s -- one-iteration version of
| pea_4x_cache_evict.s.  Keeps the cache-evict + four consecutive
| PEA store check under the default directed-test timeout.

    .text
    .org 0

    .equ STACK_LO,    0x00104000
    .equ STACK_HI,    0x00104020

_start:
    lea     0x00010000, %a7

    | MMU + dcache setup.
    move.l  #0x000FE020, %d0
    movec   %d0, %dtt0
    move.l  #0x400FE020, %d0
    movec   %d0, %itt0
    move.l  #0xFF00E060, %d0
    movec   %d0, %dtt1
    move.l  #0x00008000, %d0
    movec   %d0, %tc
    move.l  #0x80000000, %d0
    movec   %d0, %cacr

    | Fill the future stack line with stale data.
    lea     STACK_LO, %a0
    move.l  #0xCAFEBA00, (%a0)+
    move.l  #0xCAFEBA01, (%a0)+
    move.l  #0xCAFEBA02, (%a0)+
    move.l  #0xCAFEBA03, (%a0)+
    move.l  #0xCAFEBA04, (%a0)+
    move.l  #0xCAFEBA05, (%a0)+
    move.l  #0xCAFEBA06, (%a0)+
    move.l  #0xCAFEBA07, (%a0)+

    | Five other lines in the same dcache set as STACK_LO force
    | replacement pressure before the four PEA stores.
    move.l  #0x12345678, %d5
    lea     0x00105000, %a1
    move.l  %d5, (%a1)
    lea     0x00106000, %a1
    move.l  %d5, (%a1)
    lea     0x00107000, %a1
    move.l  %d5, (%a1)
    lea     0x00108000, %a1
    move.l  %d5, (%a1)
    lea     0x00109000, %a1
    move.l  %d5, (%a1)

    lea     STACK_HI, %a7
    pea     %pc@(_t1)
    pea     %pc@(_t2)
    pea     %pc@(_t3)
    pea     %pc@(_t4)

    cmp.l   #STACK_LO+0x10, %a7
    bne     _fail_a7
    move.l  0(%a7), %d0
    cmp.l   #_t4, %d0
    bne     _fail_s0
    move.l  4(%a7), %d0
    cmp.l   #_t3, %d0
    bne     _fail_s1
    move.l  8(%a7), %d0
    cmp.l   #_t2, %d0
    bne     _fail_s2
    move.l  12(%a7), %d0
    cmp.l   #_t1, %d0
    bne     _fail_s3

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail_a7:  move.l #0xDEAD00A7, %d7 ; bra _fail
_fail_s0:  move.l #0xDEAD0001, %d7 ; bra _fail
_fail_s1:  move.l #0xDEAD0002, %d7 ; bra _fail
_fail_s2:  move.l #0xDEAD0004, %d7 ; bra _fail
_fail_s3:  move.l #0xDEAD0008, %d7 ; bra _fail
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail

    .align 4
_t1: nop
_t2: nop
_t3: nop
_t4: nop
