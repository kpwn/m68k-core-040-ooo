| fpu_fpsp_a7_integrity.s — A7 must survive an FPSP-SHAPED vector-11
|                           episode, bit for bit, and must not DRIFT
|                           when episodes repeat.
|
| MOTIVATION (live hardware, Speedometer): the captured crash chain was
|   exc[6] vec=0x0b pc=0x408ecd3e     F-line, FPSP entry
|   exc[7] vec=0x0a pc=0x03d1bebc
|   exc[8] vec=0x34 pc=0x408ec668     vector 52 OPERR at an fdivx
|   exc[9] vec=0x04 pc=0x03bc8000     illegal instruction — the crash
| and the measured signature was "A7 drifts by exactly 2 after an FPSP
| episode", so the exit `rts` popped a 2-mod-4 longword and returned to
| garbage.  A 2-byte drift is invisible to every value-comparing test —
| only an explicit before/after A7 identity check catches it, and only a
| REPEATED episode catches a drift that compounds (the ROM's FPSP
| re-enters itself, and every re-entry uses FMOVEM.X).
|
| The handler below is the Q700 ROM's own FPSP prologue/epilogue,
| instruction for instruction, from ROM 0x4088D28C:
|     linkw   %fp,#-192
|     fsave   %sp@-
|     moveml  %d0-%d1/%a0-%a1,%fp@(-192)
|     fmovemx %fp0-%fp3,%fp@(-176)
|     fmoveml %fpiar/%fpsr/%fpcr,%fp@(-128)
|     ...                                    (decision logic omitted)
|     moveml  %fp@(-192),%d0-%d1/%a0-%a1
|     fmovemx %fp@(-176),%fp0-%fp3
|     fmoveml %fp@(-128),%fpiar/%fpsr/%fpcr
|     frestore %sp@+
|     unlk    %fp
|     rte
| Note it saves FPCR/FPSR/FPIAR SEPARATELY from the FSAVE frame — which
| is the ROM's own proof that FSAVE/FRESTORE do not carry them.
|
| The trapping instruction is FSIN FP0,FP0 (opword F200, ext 000E).
| FSIN is genuinely unimplemented on MC68040 silicon (M68040UM Table
| 9-10), so vector 11 with a format-$2 frame is the CORRECT behaviour
| here — this test deliberately does NOT depend on the separate
| memory-indirect-<ea> defect (see fpu_ea_memindirect.s), so it stays
| meaningful after that one is fixed.
|
| M68040UM 9.6.1: for an unimplemented floating-point instruction the
| processor "creates a format $2 stack frame" and "the saved PC value is
| the logical address of the instruction that FOLLOWS" it.  Both are
| asserted, because a wrong frame size is exactly how A7 drifts.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEADA701 — A7 changed across a single episode
|   0xDEADA702 — A7 drifted across 8 repeated episodes
|   0xDEADA703 — stacked frame format nibble was not $2
|   0xDEADA704 — stacked frame PC was not the instruction after the FSIN
|   0xDEADA705 — a callee-saved data register did not survive
|   0xDEADA706 — the episode count is wrong (handler ran the wrong
|                number of times / RTE resumed at the wrong place)
|   0xDEADA70A — vector 10 (A-line): wrong-vector dispatch

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_aline, 0x00000028        | vec 10, must NOT fire
    move.l  #_fline, 0x0000002C        | vec 11 — the FPSP stand-in
    moveq   #0, %d6                    | episode counter
    moveq   #0, %d5                    | frame-check failure code (0 = ok)
    move.l  #0x5A5A5A5A, %d3           | callee-saved witness

| ── Single episode: A7 must be bit-identical across it ──────────────
    move.l  %a7, %d7                   | snapshot A7
    lea     _after1, %a3               | the PC the frame must carry
_probe1:
    .short  0xF200, 0x000E             | FSIN FP0,FP0 -> vector 11
_after1:
    cmp.l   %a7, %d7
    bne     _f01
    tst.l   %d5
    bne     _frame_bad

| ── Eight repeated episodes: a 2-byte drift COMPOUNDS ───────────────
| A per-episode drift of 2 shows up here as 16, but this loop also
| catches a drift that only appears on a LATER episode (the ROM's FPSP
| re-enters itself, so the first episode is not representative).
    move.l  %a7, %d7
    moveq   #7, %d4
_loop:
    lea     _afterloop, %a3
    .short  0xF200, 0x000E             | FSIN FP0,FP0
_afterloop:
    dbra    %d4, _loop
    cmp.l   %a7, %d7
    bne     _f02
    tst.l   %d5
    bne     _frame_bad

| ── The handler must have run exactly 9 times ───────────────────────
    cmp.l   #9, %d6
    bne     _f06

| ── A register the handler does not save must still survive ─────────
    cmp.l   #0x5A5A5A5A, %d3
    bne     _f05

    move.l  #0xC0FFEE00, %d2
    bra     _done

_frame_bad:
    move.l  %d5, %d2
    bra     _done
_f01:
    move.l  #0xDEADA701, %d2
    bra     _done
_f02:
    move.l  #0xDEADA702, %d2
    bra     _done
_f05:
    move.l  #0xDEADA705, %d2
    bra     _done
_f06:
    move.l  #0xDEADA706, %d2
    bra     _done
_aline:
    move.l  #0xDEADA70A, %d2
    bra     _done
_done:
    lea     0x00010000, %a7
    lea     PASS_SENT, %a2
    move.l  %d2, (%a2)
_halt:
    bra     _halt

| ── The FPSP-shaped vector-11 handler (ROM 0x4088D28C's shape) ──────
| The frame checks run AFTER the register save, so the handler stays
| non-destructive to the interrupted code; the exception frame is still
| reachable because `link %a6,#-192` leaves the frame base at A6+4:
|   A6+4  SR      A6+6  PC      A6+10  format/vector      A6+12  address
_fline:
    addq.l  #1, %d6                    | episode counter
    linkw   %a6, #-192
    fsave   %sp@-
    moveml  %d0-%d1/%a0-%a1, %a6@(-192)
    fmovem.x %fp0-%fp3, %a6@(-176)
    fmovem.l %fpiar/%fpsr/%fpcr, %a6@(-128)

    | M68040UM 9.6.1: an unimplemented FP instruction gets a format-$2
    | frame whose PC is the address of the FOLLOWING instruction.
    move.w  %a6@(10), %d0
    and.w   #0xF000, %d0
    cmp.w   #0x2000, %d0
    beq     _fmt_ok
    move.l  #0xDEADA703, %d5
_fmt_ok:
    move.l  %a6@(6), %d0
    cmp.l   %a3, %d0
    beq     _pc_ok
    move.l  #0xDEADA704, %d5
_pc_ok:

    moveml  %a6@(-192), %d0-%d1/%a0-%a1
    fmovem.x %a6@(-176), %fp0-%fp3
    fmovem.l %a6@(-128), %fpiar/%fpsr/%fpcr
    frestore %sp@+
    unlk    %a6
    rte
