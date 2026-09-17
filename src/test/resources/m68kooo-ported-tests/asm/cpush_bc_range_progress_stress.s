| cpush_bc_range_progress_stress.s -- can the ROM's flush loop fail to
| make PROGRESS?
|
| The board symptom this targets is NOT a wrong value.  It is:
|   * 5 of 9 failing boots pinned in the ROM's flush-a-range loop
|     (exc ring 32/32 identical A-line traps, all registers valid,
|     SR=0x2700, PC executing ROM cleanly);
|   * 19,900 exceptions/sec instead of a desktop's 29,900;
|   * and, measured over 180 s, 2 of 3 stuck trials DECAY to 0/sec --
|     i.e. the machine stops entirely.  More time never rescues it.
|
| So the thing to look for is a COMPLETION/ORDERING hole, not a data
| bug: a CPUSH that never reports `maintDone` (ExceptionUnit parks in
| S_MAINTWAIT forever), a load/store ring slot that leaks until the LS
| pipe wedges, or a frontend restart arm that re-fetches the same PC.
|
| THE STRESS THIS APPLIES, and why it is not covered by a single-CPUSH
| test:
|   * 256 back-to-back maintenance ops over a full 4 KiB range, EVERY
|     line dirty, so every op has a real AXI writeback leg.
|   * Each op pulses `icMaintPulse` -> `maintInvalidateAll` ->
|     `icMaintFlush` -> the REGISTERED `icMaintFlushArm` (2cc79a48).
|     The arm is a term in `redirectThisCycle` / `issueBornStale` /
|     `ftbBlocked` / `ftqFlush` and performs a self-redirect to
|     `decodePc`.  256 arms in rapid succession is the pattern a
|     single-op test can never produce.
|   * The A-line trap wrapper is STAGED INSIDE THE FLUSHED RANGE, so
|     the loop is invalidating the very code it is about to call: each
|     iteration's `bsr` must re-fetch through a just-invalidated
|     I-cache.  Exception redirect and maintenance flush arm therefore
|     interleave on every single iteration.
|   * The loop counter and the trap counter are BOTH checked, so a
|     lost or duplicated iteration is a FAIL, not a silent pass.
|   * A wedge (lost `maintDone`, leaked LS ring slot) shows up as the
|     harness HANG verdict -- the direct analogue of the board freeze.
|
| POSTURE: installs its own TTRs + CACR; always runs cached/copyback.
|
| PASS: 0xC0FFEE00
| FAIL: 0xDEAD0E01  A-line trap count != iteration count
|       0xDEAD0E02  staged wrapper did not return the expected marker
|       0xDEAD0E03  CACR readback wrong (posture did not take)
|       0xDEAD0E05  unexpected exception vector
| HANG: the loop stopped making progress -- the board's freeze.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ VBR_BASE,  0x00010000
    .equ ARENA,     0x00030000
    .equ WRAPPER,   0x00030040          | inside the flushed range, line 4
    .equ NLINES,    256

_start:
    lea     0x00020000, %a7

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

    move.l  #0x007FE020, %d0            | low 2 GiB cacheable copyback, I + D
    movec   %d0, %itt0
    movec   %d0, %dtt0
    moveq   #0, %d0
    movec   %d0, %itt1
    move.l  #0x807FE060, %d0            | high 2 GiB inhibited (sentinel)
    movec   %d0, %dtt1
    move.l  #0x00008000, %d0            | TC.E = 1, 4K pages
    movec   %d0, %tc

    move.l  #0x80008000, %d7            | CACR = DE | IE
    movec   %d7, %cacr
    movec   %cacr, %d6
    cmp.l   %d7, %d6
    bne     _fail_cacr

    clr.l   %d5                         | A-line trap counter
    clr.l   %d4                         | loop iteration counter

    | ── Dirty every one of the 256 lines ────────────────────────────
    lea     ARENA, %a1
    move.w  #NLINES-1, %d3
_dirty:
    move.l  #0x5A5A0000, 8(%a1)
    lea     16(%a1), %a1
    dbra    %d3, _dirty

    | ── Stage the A-line wrapper INSIDE the flushed range ───────────
    |    moveq #0x37,d0 ; .word 0xA08D ; rts
    move.l  #0x7037A08D, WRAPPER
    move.w  #0x4E75, WRAPPER+4

    | Make it fetchable: it exists only as a dirty L1D line until pushed.
    lea     WRAPPER, %a2
    cpushl  %bc,(%a2)

    | ── The loop ────────────────────────────────────────────────────
    lea     ARENA, %a1
    move.w  #NLINES-1, %d3
_flush:
    jsr     WRAPPER                     | A-line trap, from inside the range
    cmpi.l  #0x37, %d0
    bne     _fail_marker
    nop
    cpushl  %bc,(%a1)
    lea     16(%a1), %a1
    addq.l  #1, %d4
    dbra    %d3, _flush

    cmpi.l  #NLINES, %d4
    bne     _fail_traps
    cmp.l   %d4, %d5
    bne     _fail_traps

    lea     PASS_SENT, %a4
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a4)
_halt:
    bra     _halt

| ── vec 10: step over the A-line opword and resume ──────────────────
_aline:
    addq.l  #1, %d5
    addq.l  #2, 2(%a7)
    rte

_fail_traps:
    move.l  #0xDEAD0E01, %d0
    bra     _write_fail
_fail_marker:
    move.l  #0xDEAD0E02, %d0
    bra     _write_fail
_fail_cacr:
    move.l  #0xDEAD0E03, %d0
    bra     _write_fail
_panic:
    move.l  #0xDEAD0E05, %d0
_write_fail:
    lea     PASS_SENT, %a4
    move.l  %d0, (%a4)
_halt_fail:
    bra     _halt_fail
