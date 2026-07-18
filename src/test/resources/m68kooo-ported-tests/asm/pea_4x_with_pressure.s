| pea_4x_with_pressure.s — 4 PEAs after fill the LSU with prior stores.
| Tries to reproduce HW store-loss by saturating the LSU before the PEAs.

    .text
    .org 0

_start:
    lea     0x000F0000, %a7
    | Pre-fill 0x00010100..0x00010120 with sentinel
    lea     0x00010100, %a0
    moveq   #7, %d1
_fill_loop:
    move.l  #0xAAAAAAAA, (%a0)+
    dbra    %d1, _fill_loop

    | Issue many stores to OTHER cache sets to fill the store buffer.
    lea     0x00020000, %a1
    moveq   #15, %d2
_warmup_stores:
    move.l  %d2, (%a1)+
    dbra    %d2, _warmup_stores

    | Now do the 4 PEAs at a fresh A7.
    lea     0x00010120, %a7
    pea     %pc@(_t1)
    pea     %pc@(_t2)
    pea     %pc@(_t3)
    pea     %pc@(_t4)

    | Verify all 4 slots
    cmp.l   #0x00010110, %a7
    beq     _check
    move.l  #0xDEAD00A7, %d7
    bra     _fail
_check:
    moveq   #0, %d6
    move.l  0(%a7), %d0
    cmp.l   #_t4, %d0
    beq     _s1
    bset    #0, %d6
_s1:
    move.l  4(%a7), %d0
    cmp.l   #_t3, %d0
    beq     _s2
    bset    #1, %d6
_s2:
    move.l  8(%a7), %d0
    cmp.l   #_t2, %d0
    beq     _s3
    bset    #2, %d6
_s3:
    move.l  12(%a7), %d0
    cmp.l   #_t1, %d0
    beq     _s4
    bset    #3, %d6
_s4:
    tst.l   %d6
    beq     _pass
    move.l  #0xDEAD0000, %d7
    or.l    %d6, %d7
    bra     _fail
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
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
