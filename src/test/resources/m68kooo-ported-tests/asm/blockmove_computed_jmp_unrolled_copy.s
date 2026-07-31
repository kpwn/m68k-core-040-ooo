| blockmove_computed_jmp_unrolled_copy.s — the ROM BlockMove shape: a computed
| PC-relative indexed JMP landing INTO an unrolled move.l (A0)+,(A1)+ run.
|
| WHY THIS EXISTS (2026-07-29, ioResult investigation)
| ------------------------------------------------------------------------
| The Q700 ROM's BlockMove is on the completion path of the stalling File
| Manager request (docs/ioresult_stall_investigation.md 6al).  Its shape:
|
|   4080C9C4  4EFB 220A   jmp (0x4080C9D0,PC,D2.w*2)   ; COMPUTED entry
|   4080C9CA  12D8        move.b (A0)+,(A1)+           ; byte tail x3
|   4080C9CC  12D8
|   4080C9CE  12D8
|   4080C9D0  D082        add.l  D2,D0
|   ...
|   4080C9DC  22D8        move.l (A0)+,(A1)+           ; unrolled long body x N
|
| i.e. a Duff's-device copy whose entry point is computed at run time.  This
| combines TWO things this CPU treats carefully:
|
|   1. JMP (d8,PC,Xn.w*SCALE) — a computed PC-relative INDEXED jump.
|   2. move.l (An)+,(Am)+ — memory-to-memory MOVE, which decode cracks into
|      LOAD -> STORE micro-ops.  CLAUDE.md lists this as one of only TWO
|      legacy decode surfaces still live BY DELIBERATE DEFERRAL.
|
| The existing move_l_postinc_to_postinc_dbf_loop.s covers the MOVE crack in a
| DBF loop.  It does NOT cover entry via a computed jump into the middle of an
| unrolled run, where the number of copies executed depends on the jump target
| and every landing offset must be exact.
|
| Also relevant: the only LSU wedge ever captured on hardware was at
| rob_pc = 0x4080C9E8 — inside this very loop (docs/debug_watchpoint_arm_wedge.md).
|
| A FAILURE HERE WOULD BE A REAL FIND: it would mean BlockMove can copy the
| wrong number of longs, or land wrong, which on the live machine would corrupt
| whatever the File Manager was moving and could explain a completion that never
| finishes.  A PASS does NOT clear the hypothesis — this is single-threaded and
| cache-cold; the live case runs with interrupts firing continuously.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEADB001 — computed jump landed on the wrong entry (copy count wrong)
|   0xDEADB002 — copied data mismatched
|   0xDEADB003 — A0 not left at the expected source end
|   0xDEADB004 — A1 not left at the expected destination end
|   0xDEADB005 — byte-tail variant copied wrong
|   0xDEADB006 — scale factor ignored (D2*2 not applied)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SRC,       0x00030000
    .equ DST,       0x00031000

_start:
    lea     0x00010000, %a7

    | ── zero the destination so "untouched" checks are meaningful ────────
    | (Without this, DST+16 holds whatever was already in RAM and the
    | copy-count assertion below is testing garbage, not the CPU.)
    lea     DST, %a1
    move.w  #255, %d0
zap:
    clr.l   (%a1)+
    dbf     %d0, zap

    | ── seed the source with a distinguishable pattern ───────────────────
    lea     SRC, %a1
    move.l  #0x11110000, %d1
    moveq   #15, %d0
fill:
    move.l  %d1, (%a1)+
    add.l   #0x00010001, %d1
    dbf     %d0, fill

    | ── Case 1: computed jump selecting 4 longs of an 8-long unrolled run ──
    | D2 = 4 -> lands 4 entries in -> copies (8-4) = 4 longs.
    lea     SRC, %a0
    lea     DST, %a1
    moveq   #4, %d2
    bsr     unrolled8

    cmp.l   #SRC + 16, %a0
    bne     fail_a0
    cmp.l   #DST + 16, %a1
    bne     fail_a1
    | first 4 longs must match, the 5th must be untouched
    move.l  SRC + 0,  %d3
    cmp.l   DST + 0,  %d3
    bne     fail_data
    move.l  SRC + 12, %d3
    cmp.l   DST + 12, %d3
    bne     fail_data
    move.l  DST + 16, %d3
    bne     fail_count

    | ── Case 2: D2 = 0 -> the full 8-long copy ───────────────────────────
    lea     SRC, %a0
    lea     DST + 0x100, %a1
    moveq   #0, %d2
    bsr     unrolled8
    cmp.l   #SRC + 32, %a0
    bne     fail_a0
    cmp.l   #DST + 0x100 + 32, %a1
    bne     fail_a1
    move.l  SRC + 28, %d3
    cmp.l   DST + 0x100 + 28, %d3
    bne     fail_data

    | ── Case 3: scale really applied — D2 = 7 copies exactly 1 long ──────
    lea     SRC, %a0
    lea     DST + 0x200, %a1
    moveq   #7, %d2
    bsr     unrolled8
    cmp.l   #SRC + 4, %a0
    bne     fail_scale
    move.l  DST + 0x200 + 4, %d3
    bne     fail_scale

    | ── Case 4: the byte tail, entered the same way ──────────────────────
    lea     SRC, %a0
    lea     DST + 0x300, %a1
    moveq   #1, %d2
    bsr     tail4
    cmp.l   #SRC + 3, %a0
    bne     fail_tail
    move.b  SRC + 0, %d3
    cmp.b   DST + 0x300, %d3
    bne     fail_tail

    move.l  #0xC0FFEE00, %d1
    move.l  %d1, PASS_SENT
    bra     .

| ── an 8-deep unrolled move.l run entered by computed jump ───────────────
| jmp (disp,PC,D2.w*2): each entry is 2 bytes, so D2 selects how many of the
| eight moves are SKIPPED.  Encoded by hand so the addressing mode matches the
| ROM exactly rather than whatever the assembler picks.
    .align 2
unrolled8:
    .short  0x4EFB, 0x2202          | jmp (2,PC,D2.w*2) -> u8_0 + D2*2
u8_0:
    .short  0x22D8                  | move.l (a0)+,(a1)+
    .short  0x22D8
    .short  0x22D8
    .short  0x22D8
    .short  0x22D8
    .short  0x22D8
    .short  0x22D8
    .short  0x22D8
    rts

| ── a 4-deep unrolled move.b run, same entry style ───────────────────────
    .align 2
tail4:
    .short  0x4EFB, 0x2202          | jmp (2,PC,D2.w*2) -> t4_0 + D2*2
t4_0:
    .short  0x12D8                  | move.b (a0)+,(a1)+
    .short  0x12D8
    .short  0x12D8
    .short  0x12D8
    rts

fail_count:
    move.l  #0xDEADB001, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_data:
    move.l  #0xDEADB002, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_a0:
    move.l  #0xDEADB003, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_a1:
    move.l  #0xDEADB004, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_tail:
    move.l  #0xDEADB005, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_scale:
    move.l  #0xDEADB006, %d1
    move.l  %d1, PASS_SENT
    bra     .
