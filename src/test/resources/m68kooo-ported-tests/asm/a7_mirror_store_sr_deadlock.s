| a7_mirror_store_sr_deadlock.s — regression for store-fire+SYS_MOVE_SR race.
|
| Goal: lock down the cycle-window race between (a) a still-draining
| commit_store_en pulse and (b) a SYS_MOVE_SR retire that drops SR.S
| (1->0) and therefore fires an a7_writeback (USP load).  The
| a7_mirror_pending one-cycle-lag latch could in principle race with
| store_commit_wait clearing in the next cycle, leaving the next
| instruction's ROB head stuck (NOP never retires).
|
| The prompt's hypothesised 5-instruction repro (lea / move.l / move.l
| to 0xFFFF0000 / move.w / nop) wrote through 0xFFFF0000 — which the
| testbench treats as the PASS sentinel and ends the run early — so
| we use a non-sentinel scratch address for the store and write the
| PASS sentinel only after the NOP retires.
|
| Construction:
|   1. Aim a memory write at SCRATCH (NOT the sentinel) so
|      commit_store_en pulses during the SYS_MOVE_SR retire window.
|   2. Issue MOVE.W #imm,SR with S=1 -> S=0 — this triggers the
|      a7_writeback to the USP slot AND a7_mirror_pending interactions.
|   3. Follow with NOPs whose retires require the head to advance.
|   4. Eventually write 0xC0FFEE00 to PASS sentinel (from S=0 user mode).
|
| PASS: 0xC0FFEE00 written to 0xFFFF0000 (NOPs retired).
| FAIL: timeout (deadlock) or any other sentinel value.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SCRATCH,   0x00000400

_start:
    lea     0x00010000, %a7

    | Force a fresh commit_store_en pulse RIGHT before SYS_MOVE_SR retire.
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, SCRATCH.l           | store-fire — store_commit_wait active

    | Drop S=1 -> S=0 with the prior store still possibly draining.
    move.w  #0x0000, %sr             | SYS_MOVE_SR: S=1 -> S=0

    nop                              | the wedged μop in the original deadlock
    nop
    nop

    | If we get here the deadlock is gone — write PASS sentinel.
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt
