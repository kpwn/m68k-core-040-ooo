| shift_mem_word.s — memory-form shift ops for Stage D-3.
|
| Stage D-3 corner: memory-form shift/rotate emits a 3-µop RMW crack
| (LOAD→INT→STORE) with size=WORD and count=1, regardless of what the
| programmer wrote.  Exercises LSL.W (An), ASR.W (An)+, ROL.W (An).
|
| Notes
|  * Read-back uses the same An (or a copy of An) to dodge a pre-existing
|    iq_mem alias-detection limitation where `abs.L_addr` and
|    `An_base+disp` appear as different addresses even when they alias
|    the same EA.  Musashi fuzz (N=500 clean) covers the cross-mode
|    aliased case.

    .text
    .org 0

_start:
    | ── 1. LSL.W (An): word 0x1234 → 0x2468 ──
    move.w  #0x1234, %d0
    move.l  #0x40810010, %a0
    move.w  %d0, (%a0)
    lsl.w   (%a0)
    move.w  (%a0), %d1
    andi.l  #0x0000FFFF, %d1
    move.l  #0x00002468, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | ── 2. ASR.W (An)+: word 0xFFFE → 0xFFFF, An += 2 ──
    move.w  #0xFFFE, %d2
    move.l  #0x40810014, %a1
    move.w  %d2, (%a1)
    asr.w   (%a1)+
    | Check A1 post-incremented.
    move.l  #0x40810016, %d7
    cmp.l   %d7, %a1
    bne     _fail
    | Read memory back via a different register (A2 = A1-2).
    move.l  %a1, %a2
    subq.l  #2, %a2
    move.w  (%a2), %d3
    andi.l  #0x0000FFFF, %d3
    move.l  #0x0000FFFF, %d7
    cmp.l   %d7, %d3
    bne     _fail

    | ── 3. ROL.W (An): word 0x8001 → 0x0003 ──
    move.w  #0x8001, %d4
    move.l  #0x40810020, %a3
    move.w  %d4, (%a3)
    rol.w   (%a3)
    move.w  (%a3), %d5
    andi.l  #0x0000FFFF, %d5
    move.l  #0x00000003, %d7
    cmp.l   %d7, %d5
    bne     _fail

    | Pass sentinel.
    move.l  #0xC0FFEE00, %d0
    move.l  #0xFFFF0000, %a0
    move.l  %d0, (%a0)
    bra     .

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  #0xFFFF0000, %a0
    move.l  %d0, (%a0)
    bra     .
