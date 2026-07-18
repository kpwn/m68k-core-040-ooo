| move_sp_postinc_sr_tmp1_dbgflush_loop.s — plain repeated `move (sp)+,sr`
| ((An)+ form, the 3-uop TMP1 crack) loop, with NO synchronous/async
| exception injected from the .s side.  Companion tb_top.cpp instruments
| a NEW +tmp1_flush_delay=<n> probe: it watches ratmap[REG_TMP1] (arch 17)
| for writes and fires a 1-cycle dbg_precise_stop_req+dbg_core_halt pulse
| exactly N cycles after each write is observed — the SAME "debug overlay
| flush" mechanism m68k_core_rename.vh's own header comment documents
| (dbg_precise_stop_req/dbg_arch_apply_en bypass the ROB-program-order-
| gated `flush_en_to_ports` net but DO roll back rat.v's `ratmap` via the
| wider `flush_en` term).  See task #91 lockstep-deepdive continuation
| notes for the full mechanism writeup.
|
| This test's OWN pass/fail check (SR read back must equal SR_PATTERN
| every iteration) is a sufficient corruption detector on its own — no
| F-line/IRQ needed, since we're injecting the flush directly from the
| tb side at a precisely targeted cycle instead of hoping a program-order
| exception lands in the right window.

    .text
    .org 0

    .equ INIT_SSP,        0x00018000
    .equ PASS_SENT,       0xFFFF0000
    .equ ITER_COUNT_ADDR, 0x000F0000
    .equ TARGET_ITERS,    400
    .equ SR_PATTERN,      0x2000        | S=1, ipl=0 — keeps all IRQ levels unmasked

_start:
    move.w  #0x2700, %sr
    move.l  #INIT_SSP, %sp
    clr.l   ITER_COUNT_ADDR

_iter_loop:
    move.l  %sp, %d1

    move.w  #SR_PATTERN, -(%sp)   | push known SR pattern
    move    (%sp)+, %sr            | THE CRACK UNDER TEST (3 uops, TMP1)

    move    %sr, %d0

    cmp.l   %d1, %sp
    bne     _fail_sp_drift

    cmp.w   #SR_PATTERN, %d0
    bne     _fail_sr_corrupt

    addq.l  #1, ITER_COUNT_ADDR
    move.l  ITER_COUNT_ADDR, %d0
    cmp.l   #TARGET_ITERS, %d0
    blt     _iter_loop

    move.l  #0xC0FFEE00, PASS_SENT
    bra     .

_fail_sp_drift:
    move.l  %sp, PASS_SENT
    bra     .

_fail_sr_corrupt:
    move.l  %d0, PASS_SENT
    bra     .
