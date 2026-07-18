| fpu_faddb_post_fline_entry_repro.s — task #108 follow-up: test whether the
| BFEXTU/FADD.B dyadic-int-source hang requires a GENUINE exception-entry
| context (real flush + epoch increment + supervisor dispatch via commit.v's
| take_exc path) rather than running the loop cold from reset.
|
| fpu_faddb_dbf_loop_repro.s (task #108) runs the identical instruction
| shape starting cold from _start and PASSES in sim (713 cycles) even with
| the domain-mismatch disp_src_b_rdy fix applied.  On real hardware the
| loop hangs, but it is only ever reached via a genuine F-line trap into
| the ROM's FPSP handler — i.e. every real occurrence follows a hardware
| exception dispatch (ROB flush, alloc_epoch incremented, fresh supervisor
| entry), which the cold-start repro never exercises.  This test forces
| a real single-shot synchronous F-line trap (.short 0xFFFF, matches the
| fline_before_move_sp_postinc_sr_tmp1.s convention) immediately before
| entering the loop, executing the loop itself from INSIDE the trap
| handler so the loop's first BFEXTU/FADD.B pair dispatches right after a
| genuine flush/epoch-bump instead of from a clean boot state.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F02 — unexpected re-trap (should only trap once, at _start)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00018000, %a7
    move.l  #_fline_handler, 0x0000002C   | vec 11 (F-line)

    | THE genuine synchronous F-line trap — single shot.  Lands us in
    | _fline_handler with a real flush + epoch bump + supervisor entry,
    | matching how the ROM actually reaches the FPSP loop on hardware.
    .short  0xFFFF

    | Should never fall through to here — the handler does not RTE.
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F02, %d2
    move.l  %d2, (%a1)
_bad_fallthrough:
    bra     _bad_fallthrough

| F-line (vec 11) handler — runs the exact loop shape from
| fpu_faddb_dbf_loop_repro.s, but from INSIDE a genuine exception-entry
| context instead of cold from reset.
_fline_handler:
    move.l  #0x00000000, %d0
    .short  0xF200, 0x4000              | FMOVE.S D0,FP0

    move.l  #0x76543210, %d4            | source nibbles for bfextu
    moveq   #0, %d3
    moveq   #7, %d2
    moveq   #0, %d5
_loop:
    .short  0xF23C, 0x5823, 0x000A      | FMUL.B #10,FP0
    bfextu  %d4{%d3:#4}, %d0            | matches ROM's e9c4 08c4
    addq.l  #1, %d5
    addq.l  #1, %d5
    addq.l  #1, %d5
    addq.l  #1, %d5
    addq.l  #1, %d5
    addq.l  #1, %d5
    addq.l  #1, %d5
    addq.l  #1, %d5
    .short  0xF200, 0x5822              | FADD.B D0,FP0
    addq.b  #4, %d3
    dbf     %d2, _loop

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt
