| rom_scc_mmio_btst_dbf_timeout.s -- BTST/DBF loop against Q700 SCC MMIO.
|
| Same shape as the ROM loop at 0x408478b4, but the polled byte is the
| real Q700 SCC mirror address observed on FPGA.  This isolates the
| bring-up hang where D4 stops decrementing after a BTST memory read from
| the peripheral window.
|
| ROOT-CAUSED 2026-07-07 (task #67): the ~806K-cycle "crash" was NOT a
| CPU bug.  This repo's test harness (mac_top / Vmac_top) is CPU-only --
| the real Q700 SCC (rtl/mac/scc.v) moved to the macqd700-soc repo in
| the platform split (see CLAUDE.md "Repo Split") and was never backed
| here.  0x50f0c022 is genuinely unmapped in tb/models/mem_model.h, so
| the BTST correctly bus-faults (SLVERR).  This test never installed a
| vector-2 (bus error) handler, and mem_model.h fills unmapped/unwritten
| RAM with 0xFF (not zero -- deliberate, for Musashi fuzz parity, see
| mem_model.h's header comment), so reading the vector-2 handler PC from
| address 0x00000008 (never written) yields 0xFFFFFFFF.  The CPU then
| correctly jumps to 0xFFFFFFFF, faults again fetching from there,
| re-vectors through the SAME 0xFFFFFFFF garbage handler PC, and repeats
| -- a real, but entirely EXPECTED, consequence of no vector table setup
| in a harness with no SCC to satisfy the poll.  (Confirmed via
| +dump_debug_state snapshots at increasing timeouts: d4 never
| decrements even once -- the loop body never re-executes after the
| first BTST -- and pc is frozen at 0xffffffff from the earliest
| checkpoint, consistent with an immediate, repeating garbage-vector
| refetch rather than a slow corruption building up over time.)
|
| Fix: install a real vector-2 handler so the EXPECTED bus fault (no SCC
| in this harness) reports a clean, immediate, diagnosable FAIL instead
| of a multi-hundred-thousand-cycle wild-jump crash.  If this test is
| ever run against a harness that DOES back 0x50f0c022 with a real SCC
| (e.g. macqd700-soc's fpga_top-based tb), the loop behaves exactly as
| originally intended and the vector-2 handler is simply never invoked.

    .text
    .org 0

    .equ PASS_SENT,     0xFFFF0000
    .equ FAIL_NO_SCC,    0xBADC5CC0   | bus fault on the SCC MMIO poll --
                                       | expected in this SCC-less harness
    .equ FAIL_MISMATCH,  0xBADC0C22   | real SCC present but loop state wrong

_start:
    lea     0x00020000, %a7
    move.l  #_buserr, 0x00000008      | vector 2 (bus error)
    lea     0x50f0c022, %a3
    move.w  #0x00ff, %d4
    moveq   #0, %d6

_loop:
    btst    #0, (%a3)
    bne     _done
    dbf     %d4, _loop
    moveq   #3, %d6

_done:
    cmpi.l  #3, %d6
    bne     _fail
    cmpi.l  #0x0000ffff, %d4
    bne     _fail

_pass:
    lea     PASS_SENT, %a0
    move.l  #0xc0ffee00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     PASS_SENT, %a0
    move.l  #FAIL_MISMATCH, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_buserr:
    | Reached when the SCC MMIO poll bus-faults -- expected in this
    | CPU-only harness (no SCC backing).  Report cleanly instead of
    | wild-jumping through an uninstalled vector table.
    lea     PASS_SENT, %a0
    move.l  #FAIL_NO_SCC, %d0
    move.l  %d0, (%a0)
_halt_buserr:
    bra     _halt_buserr
