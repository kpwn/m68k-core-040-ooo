| movea_areg_to_areg.s - MOVEA.L An,Am register-flow regression
|
| Mirrors the ROM allocator pattern seen near 0x4080cfbe:
|   movea.l (a1)+,fp
|   move.l  fp,<abs>
|   movea.l fp,a0
|   movea.l fp,a2
|   clr.l   (a0)+
|
| The ROM frontier failure showed the FP value being visible to a store,
| but the subsequent MOVEA.L FP,A0 path left A0 stale, so CLR.L (A0)+
| dirtied low vectors.  Use explicit opwords for the FP->A0/A2 copies so
| this test locks the exact decode slice.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Wrong-path destinations.  If MOVEA.L FP,A0/A2 does not update the
    | destination mapping, stores below will touch these locations instead.
    lea     0x00109000, %a0
    lea     0x00109010, %a2
    move.l  #0xBAD0A000, (%a0)
    move.l  #0xBAD0A222, (%a2)

    | Load FP from memory, matching the ROM's producer for %fp.
    lea     _fp_source, %a1
    movea.l (%a1)+, %fp

    | Prove the loaded FP is available as a source before the A-register
    | copies, matching the ROM's move.l fp,0x118 side effect.
    move.l  %fp, 0x00109020
    move.l  0x00109020, %d0
    cmp.l   #0x00109100, %d0
    bne     _fail

    | Raw ROM-pattern opwords:
    |   204e    movea.l %fp,%a0
    |   244e    movea.l %fp,%a2
    .word   0x204e
    .word   0x244e

    | If A0 stayed stale, this clears 0x00109000 instead of 0x00109100.
    clr.l   (%a0)+

    move.l  0x00109100, %d1
    bne     _fail
    cmpa.l  #0x00109104, %a0
    bne     _fail
    cmpa.l  #0x00109100, %a2
    bne     _fail

    | Wrong-path sentinels must be untouched.
    move.l  0x00109000, %d2
    cmp.l   #0xBAD0A000, %d2
    bne     _fail
    move.l  0x00109010, %d3
    cmp.l   #0xBAD0A222, %d3
    bne     _fail

    | High-tagged address values must survive the An->An MOVEA path.
    | Do not dereference this address in the directed test; the ROM run
    | will tell us whether MMU translation maps it to physical low RAM.
    movea.l #0x38000020, %fp
    .word   0x204e                 | movea.l %fp,%a0
    .word   0x244e                 | movea.l %fp,%a2
    cmpa.l  #0x38000020, %a0
    bne     _fail
    cmpa.l  #0x38000020, %a2
    bne     _fail

_pass:
    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a6)
_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a6)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

    .align 4
_fp_source:
    .long   0x00109100
