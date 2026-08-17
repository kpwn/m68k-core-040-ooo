| unaligned_a7_call_return.s — BSR/JSR push + RTS pop through a stack
| pointer that is EVEN but NOT long-aligned (SP % 4 == 2).
|
| Motivation (docs/handoff_shutdown_check_frontier.md §0.12-0.13, live HW):
| the Q700 ROM A-trap dispatcher
|     40809a22: 4eb0 2591   JSR ([A0 + D2.W*4])
|     40809a26: 225f        MOVEA.L (SP)+,A1     <- handler returned here
|     ...
|     40809a32: 4e75        RTS                  <- escaped to 0xA0222510
| left the CPU at a synthesized PC.  0xA0222510 exists nowhere in the ROM
| or in RAM, and EVERY stack pointer sampled at the failure was 2 mod 4
| (0x7bd522, 0x7bd942, 0x7bd2f2, 0x7bd31e, 0x7bd7e2, 0x7bd802).  RTS pops
| a longword, so at that alignment it is a SPLIT access.
|
| Existing coverage stops short of this: movem_postinc_odd_sp.s and
| irq_epilogue_movem_odd_sp.s cover MOVEM/RTE from an odd SP, and
| emit_misaligned_split_store covers misaligned (d16,An) DATA EAs — but
| nothing covered the CALL/RETURN path (BSR/JSR push, RTS pop, PEA+RTS,
| LINK/UNLK) at SP%4==2.  The fuzzer cannot reach it at all: gen_program.py
| pins STACK_TOP to a long-aligned 0x00080000, so A7 is never 2 mod 4.
|
| Every subroutine adds a distinct constant to D0, so a return to the
| wrong-but-valid place is caught by the cumulative check, and a return to
| a wild address fails by never reaching the sentinel.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  FAIL: 0xDEADnnnn in D7.

    .text
    .org 0

    .equ SP_UNALIGNED, 0x00020002       | even, NOT long-aligned
    .equ DISPTAB,      0x00030000

_start:
    lea     SP_UNALIGNED, %a7
    moveq   #0, %d0

    | ---- 1. BSR.W + RTS -------------------------------------------
    bsr.w   _sub_a
    cmp.l   #0x00000011, %d0
    bne     _fail1
    cmpa.l  #SP_UNALIGNED, %a7
    bne     _fail2

    | ---- 2. JSR (xxx).L + RTS -------------------------------------
    jsr     _sub_b
    cmp.l   #0x00000033, %d0
    bne     _fail3
    cmpa.l  #SP_UNALIGNED, %a7
    bne     _fail4

    | ---- 3. nested calls, 3 deep ----------------------------------
    |      return addresses land at both 2-mod-4 and 0-mod-4 slots
    bsr.w   _nest1
    cmp.l   #0x00000077, %d0
    bne     _fail5
    cmpa.l  #SP_UNALIGNED, %a7
    bne     _fail6

    | ---- 4. PEA + RTS: the most direct pop-what-was-pushed check --
    pea     _after_pea
    rts
_after_pea:
    add.l   #0x00000088, %d0
    cmp.l   #0x000000ff, %d0
    bne     _fail7
    cmpa.l  #SP_UNALIGNED, %a7
    bne     _fail8

    | ---- 5. memory-indirect JSR, base SUPPRESSED, null base disp ---
    |      the exact ROM shape at 40809a22: 4EB0 2591.
    |      ext 0x2591 has bit7 (BS) = 1, so An is SUPPRESSED: the EA is
    |      ([D2.W*4]) — the pointer is read from ABSOLUTE low memory, not
    |      from a base register.  (That is why the ROM dispatcher is fed
    |      by the low-memory vector/dispatch table, and why clobbering
    |      0x28/0x2C breaks it.)  D2 = 0x100 puts the slot at 0x400,
    |      clear of the CPU vector table.
    lea     0x00000400, %a0
    move.l  #_sub_c, (%a0)              | slot at absolute 0x400
    move.w  #0x0100, %d2                | D2.W * 4 = 0x400
    .word   0x4EB0, 0x2591
    cmp.l   #0x00000154, %d0
    bne     _fail9
    cmpa.l  #SP_UNALIGNED, %a7
    bne     _fail10

    | ---- 6. base SUPPRESSED + word base displacement ---------------
    |      sibling shape at ROM 40809a04: 4EB0 25A1 0400 =
    |      JSR ([$400 + D2.W*4]).  D2 = 3 -> slot 0x40C.
    lea     0x0000040C, %a0
    move.l  #_sub_d, (%a0)              | slot at absolute 0x40C
    moveq   #3, %d2
    .word   0x4EB0, 0x25A1, 0x0400
    cmp.l   #0x000001ba, %d0
    bne     _fail11
    cmpa.l  #SP_UNALIGNED, %a7
    bne     _fail12

    | ---- 7. LINK / UNLK over an unaligned SP ----------------------
    bsr.w   _sub_link
    cmp.l   #0x00000231, %d0
    bne     _fail13
    cmpa.l  #SP_UNALIGNED, %a7
    bne     _fail14

    | ---- 8. MOVEM.L push/pop straddling the unaligned SP ----------
    move.l  #0x11112222, %d1
    move.l  #0x33334444, %d2
    move.l  #0x55556666, %d3
    lea     0x00031100, %a1
    lea     0x00031200, %a2
    movem.l %d1-%d3/%a1-%a2, -(%sp)
    moveq   #-1, %d1
    moveq   #-1, %d2
    moveq   #-1, %d3
    suba.l  %a1, %a1
    suba.l  %a2, %a2
    movem.l (%sp)+, %d1-%d3/%a1-%a2
    cmp.l   #0x11112222, %d1
    bne     _fail15
    cmp.l   #0x33334444, %d2
    bne     _fail16
    cmp.l   #0x55556666, %d3
    bne     _fail17
    cmpa.l  #0x00031100, %a1
    bne     _fail18
    cmpa.l  #0x00031200, %a2
    bne     _fail19
    cmpa.l  #SP_UNALIGNED, %a7
    bne     _fail20

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

| ── failure tags ────────────────────────────────────────────────────
_fail1:  move.l #0xDEAD0001, %d7
    bra _fail
_fail2:  move.l #0xDEAD0002, %d7
    bra _fail
_fail3:  move.l #0xDEAD0003, %d7
    bra _fail
_fail4:  move.l #0xDEAD0004, %d7
    bra _fail
_fail5:  move.l #0xDEAD0005, %d7
    bra _fail
_fail6:  move.l #0xDEAD0006, %d7
    bra _fail
_fail7:  move.l #0xDEAD0007, %d7
    bra _fail
_fail8:  move.l #0xDEAD0008, %d7
    bra _fail
_fail9:  move.l #0xDEAD0009, %d7
    bra _fail
_fail10: move.l #0xDEAD000A, %d7
    bra _fail
_fail11: move.l #0xDEAD000B, %d7
    bra _fail
_fail12: move.l #0xDEAD000C, %d7
    bra _fail
_fail13: move.l #0xDEAD000D, %d7
    bra _fail
_fail14: move.l #0xDEAD000E, %d7
    bra _fail
_fail15: move.l #0xDEAD000F, %d7
    bra _fail
_fail16: move.l #0xDEAD0010, %d7
    bra _fail
_fail17: move.l #0xDEAD0011, %d7
    bra _fail
_fail18: move.l #0xDEAD0012, %d7
    bra _fail
_fail19: move.l #0xDEAD0013, %d7
    bra _fail
_fail20: move.l #0xDEAD0014, %d7
    bra _fail
_fail21: move.l #0xDEAD0015, %d7
    bra _fail

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_fail_halt:
    bra     _fail_halt

| ── subroutines (unreachable by fallthrough) ───────────────────────
_sub_a:
    add.l   #0x00000011, %d0
    rts

_sub_b:
    add.l   #0x00000022, %d0
    rts

_nest1:
    add.l   #0x00000011, %d0
    bsr.w   _nest2
    rts
_nest2:
    add.l   #0x00000011, %d0
    bsr.w   _nest3
    rts
_nest3:
    add.l   #0x00000022, %d0
    rts

_sub_c:
    add.l   #0x00000055, %d0
    rts

_sub_d:
    add.l   #0x00000066, %d0
    rts

| LINK/UNLK with a frame, exercised with SP%4==2 on entry.
_sub_link:
    link    %a6, #-16
    move.l  #0x5A5A5A5A, -4(%a6)
    move.l  #0xA5A5A5A5, -8(%a6)
    move.l  -4(%a6), %d1
    cmp.l   #0x5A5A5A5A, %d1
    bne     _fail21
    move.l  -8(%a6), %d1
    cmp.l   #0xA5A5A5A5, %d1
    bne     _fail21
    unlk    %a6
    add.l   #0x00000077, %d0
    rts
