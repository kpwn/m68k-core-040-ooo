| cpush_bc_page_range_smc.s -- the Q700 ROM's flush loop, PAGE scope.
|
| THE BOARD'S ACTUAL OPWORD IS 0xF4F1 = CPUSH*P* BC,(A1), NOT CPUSHL.
| Decode: 1111 0100 CC=11(BC) bit5=1(push) SS=10(PAGE) RRR=001(A1).
| (`cpushl bc,(a1)` would be 0xF4E9; `cpusha bc` 0xF4F8.)  This matters
| a great deal for what is under test:
|   * a LINE-scope push visits ONE set x 4 ways  (~12 cycles);
|   * a PAGE-scope push walks ALL 128 sets x 4 ways = 512 way-visits,
|     plus a writeback AXI round trip for every matching dirty line.
| So each iteration of this loop holds the whole D-cache array for
| >1500 cycles with the exception sequencer parked in S_MAINTWAIT
| waiting on `maintDone`, and then pulses the full I-cache invalidate
| + `icMaintFlushArm` frontend restart.  A lost `maintDone` here wedges
| the machine permanently -- which is exactly the board's "decays from
| 19,900 exc/s to 0/s" observation.
|
| The loop keeps the ROM's 16-byte stride, so all 8 ops name the same
| 4 KiB page. That is deliberate, not an oversight: the point of this
| file is 8 FULL-CACHE maintenance walks back to back -- eight
| S_MAINTWAIT parks and eight `icMaintFlushArm` re-arms in quick
| succession -- not coverage of 8 different pages.
|
| Otherwise identical in structure to cpush_bc_line_range_smc.s: staged
| copyback-only routine, per-line dirtying, A-line trap inside the loop
| body, in-place patch, re-flush, re-call.  See that file's header for
| the full rationale of each step.
|
| POSTURE: installs its own TTRs + CACR; always runs cached/copyback.
|
| PASS: 0xC0FFEE00
| FAIL: 0xDEAD0D01  staged routine not visible after the first push loop
|       0xDEAD0D02  patched routine not visible after the second push loop
|       0xDEAD0D03  CACR readback wrong (posture did not take)
|       0xDEAD0D04  A-line trap count wrong (traps lost or duplicated)
|       0xDEAD0D05  unexpected exception vector

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ VBR_BASE,  0x00010000
    .equ ARENA,     0x00030000
    .equ NLINES,    8

_start:
    lea     0x00020000, %a7

    | ── Vector table ────────────────────────────────────────────────
    lea     VBR_BASE, %a1
    move.l  #_panic, %d0
    move.l  %d0,  8(%a1)                | bus error
    move.l  %d0, 12(%a1)                | address error
    move.l  %d0, 16(%a1)                | illegal
    move.l  %d0, 20(%a1)                | zero divide
    move.l  %d0, 32(%a1)                | privilege violation
    move.l  %d0, 36(%a1)                | trace
    move.l  %d0, 44(%a1)                | F-line
    move.l  #_aline, 40(%a1)            | vec 10 (A-line) @ VBR+0x28
    move.l  #VBR_BASE, %d0
    movec   %d0, %vbr

    | ── Transparent translation: low 2 GiB cacheable COPYBACK on both
    |    sides (code at 0x40800000 AND the staged code at ARENA), high
    |    2 GiB inhibited so the AXI-observed sentinel is never retained.
    move.l  #0x007FE020, %d0
    movec   %d0, %itt0
    movec   %d0, %dtt0
    moveq   #0, %d0
    movec   %d0, %itt1
    move.l  #0x807FE060, %d0
    movec   %d0, %dtt1
    move.l  #0x00008000, %d0            | TC.E = 1, 4K pages
    movec   %d0, %tc

    | ── CACR = DE | IE ──────────────────────────────────────────────
    move.l  #0x80008000, %d7
    movec   %d7, %cacr
    movec   %cacr, %d6
    cmp.l   %d7, %d6
    bne     _fail_cacr

    clr.l   %d5                         | A-line trap counter

    | ── Dirty every line of the range ───────────────────────────────
    lea     ARENA, %a1
    moveq   #NLINES-1, %d3
_dirty1:
    move.l  #0x5A5A0000, 8(%a1)
    lea     16(%a1), %a1
    dbra    %d3, _dirty1

    | ── Stage version A of the routine: `moveq #0x11,d0 ; rts` ──────
    move.l  #0x70114E75, ARENA

    | ── ROM-shaped flush-a-range loop, pass 1 ───────────────────────
    lea     ARENA, %a1
    moveq   #NLINES-1, %d3
_flush1:
    nop
    cpushp  %bc,(%a1)
    lea     16(%a1), %a1
    dbra    %d3, _flush1

    | The staged routine is fetchable ONLY if the push really wrote the
    | dirty line back to memory.
    jsr     ARENA
    cmpi.l  #0x11, %d0
    bne     _fail_v1

    | ── Patch to version B: `moveq #0x22,d0 ; rts` ──────────────────
    move.l  #0x70224E75, ARENA

    | Re-dirty every line so pass 2 also has real writeback work.
    lea     ARENA, %a1
    moveq   #NLINES-1, %d3
_dirty2:
    move.l  #0xA5A50000, 8(%a1)
    lea     16(%a1), %a1
    dbra    %d3, _dirty2

    | ── Flush-a-range loop, pass 2, WITH an A-line trap in the body ──
    lea     ARENA, %a1
    moveq   #NLINES-1, %d3
_flush2:
    bsr     _atrap_wrapper              | takes + returns from vec 10
    nop
    cpushp  %bc,(%a1)
    lea     16(%a1), %a1
    dbra    %d3, _flush2

    | Exactly NLINES traps must have been taken and returned from.
    cmpi.l  #NLINES, %d5
    bne     _fail_traps

    | The PATCHED routine must now be the one that executes.
    jsr     ARENA
    cmpi.l  #0x22, %d0
    bne     _fail_v2

    lea     PASS_SENT, %a4
    move.l  #0xC0FFEE00, %d4
    move.l  %d4, (%a4)
_halt:
    bra     _halt

| ── The ROM's A-line wrapper, verbatim in shape ─────────────────────
_atrap_wrapper:
    moveq   #3, %d0
    .word   0xA08D
    rts

| ── vec 10: step over the A-line opword and resume ──────────────────
_aline:
    addq.l  #1, %d5
    addq.l  #2, 2(%a7)                  | stacked PC -> past the A-line word
    rte

_fail_v1:
    move.l  #0xDEAD0D01, %d4
    bra     _write_fail
_fail_v2:
    move.l  #0xDEAD0D02, %d4
    bra     _write_fail
_fail_cacr:
    move.l  #0xDEAD0D03, %d4
    bra     _write_fail
_fail_traps:
    move.l  #0xDEAD0D04, %d4
    bra     _write_fail
_panic:
    move.l  #0xDEAD0D05, %d4
_write_fail:
    lea     PASS_SENT, %a4
    move.l  %d4, (%a4)
_halt_fail:
    bra     _halt_fail
