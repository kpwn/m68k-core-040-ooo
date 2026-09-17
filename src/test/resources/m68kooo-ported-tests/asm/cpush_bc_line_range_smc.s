| cpush_bc_line_range_smc.s -- the Q700 ROM's cache-flush-a-range loop.
|
| BOARD SHAPE BEING MODELLED (measured 2026-09-17, 200 MHz bitstream,
| 5 of 9 failing boots pinned here):
|
|   40887116:  d0c2        adda.w d2,a0        | walking an address
|   40887118:  e842        lsr.w  #4,d2        | /16 -- CACHE LINE stride
|   4088711a:  0c42 0010   cmpi.w #16,d2
|   4088711e:  6d06        blt.s
|   40887120:  4e71        nop
|   40887122:  f4f1        CPUSH  BC,(A1)
|
| ...with an A-LINE trap wrapper (`moveq #3,d0 ; A08D ; rts`) being
| re-entered over and over (exc ring: 32/32 identical vec=0x0a).
|
| WHAT THIS TEST PROVES, and why each step is here:
|   1. A staged subroutine is written into RAM through the D-cache in
|      COPYBACK mode, so it exists ONLY as a dirty L1D line.  The
|      harness mirrors D-side AXI writes into the I-side image, so the
|      patch becomes fetchable ONLY when a writeback actually happens.
|      => step 3's `jsr` can only succeed if CPUSH really pushed.
|   2. Every line of the range is separately dirtied, so the range loop
|      has REAL writeback work on every iteration (not a no-op walk).
|   3. The range loop is the ROM's exact shape: `nop ; cpushl bc,(a1) ;
|      lea 16(a1),a1 ; dbra`, i.e. many BACK-TO-BACK maintenance ops.
|      Each one pulses `icMaintPulse` -> IcachePlugin.maintInvalidateAll
|      -> FetchAlignPlugin.icMaintFlush -> the REGISTERED `icMaintFlushArm`
|      (2cc79a48).  A single-CPUSH test never produces rapid re-arms;
|      this one produces NLINES of them per loop.
|   4. An A-line trap is taken INSIDE the loop body, between maintenance
|      ops, so the exception sequencer's redirect and the maintenance
|      flush arm interleave the way they do on the board.
|   5. The routine is then PATCHED in place and the loop re-run; the
|      second `jsr` must observe the NEW body.  Stale => the I-side
|      flush/ordering is broken.  No progress => a maintenance
|      completion was lost (the board's "decays to frozen").
|
| POSTURE: this program installs its own TTRs + CACR, so it runs with
| I-cache + D-cache ON and COPYBACK regardless of the harness sweep
| posture.  That is deliberate and it is stated here rather than
| claimed as a caches-off arm: a "caches-off" run of this file would be
| vacuous and is not offered.
|
| PASS: 0xC0FFEE00
| FAIL: 0xDEAD0C01  staged routine not visible after the first push loop
|       0xDEAD0C02  patched routine not visible after the second push loop
|       0xDEAD0C03  CACR readback wrong (posture did not take)
|       0xDEAD0C04  A-line trap count wrong (traps lost or duplicated)
|       0xDEAD0C05  unexpected exception vector

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ VBR_BASE,  0x00010000
    .equ ARENA,     0x00030000
    .equ NLINES,    16

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
    cpushl  %bc,(%a1)
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
    cpushl  %bc,(%a1)
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
    move.l  #0xDEAD0C01, %d4
    bra     _write_fail
_fail_v2:
    move.l  #0xDEAD0C02, %d4
    bra     _write_fail
_fail_cacr:
    move.l  #0xDEAD0C03, %d4
    bra     _write_fail
_fail_traps:
    move.l  #0xDEAD0C04, %d4
    bra     _write_fail
_panic:
    move.l  #0xDEAD0C05, %d4
_write_fail:
    lea     PASS_SENT, %a4
    move.l  %d4, (%a4)
_halt_fail:
    bra     _halt_fail
