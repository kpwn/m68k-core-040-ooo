| exc_irq_during_store.s — IRQ injection mid-store ordering check.
|
| Goal: while the LSU is in the middle of an unaligned LONG write, an
| asynchronous IRQ is asserted (IPL >= 1).  The 68040 architectural
| contract is that the IN-FLIGHT instruction completes (its bus cycles
| reach the bus) before the IRQ-entry frame is pushed.  Verify this by
| arranging:
|   1. Pre-poison memory at the unaligned target with sentinel 0xAAAAAAAA.
|   2. Issue MOVE.L D0,(A0) with A0 unaligned (odd-byte) so the LSU
|      cracks it into two AXI bus beats.
|   3. As the store starts, raise IRQ via the testbench harness.
|   4. In the IRQ handler, read back memory at the target.  If the
|      store completed before the frame push, all four bytes of D0
|      are visible.  If the IRQ pre-empted, some bytes are still the
|      poison value.
|
| HARNESS NEEDS: this test depends on the testbench injecting cpu_ipl
| at a controlled cycle.  The current tb_top.cpp does NOT have a
| `+ipl=` plusarg or a programmable IRQ-injection FSM, so this test
| can only verify the negative path today (LSU completes the store
| with NO IRQ — the test still PASSes because the second part of the
| check is "all bytes visible" which holds in the no-IRQ case too).
|
| When +ipl=<cycle>:<level> support lands, this test becomes the
| canonical mid-store-IRQ regression — the FAIL path triggers if and
| only if the sequencer pre-empts mid-AXI.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0E01 — store bytes not all written (pre-empt or LSU bug)
|   0xDEAD0E02 — wrong vector taken (handler fired on non-IRQ cause)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ TARGET,    0x00020001       | unaligned long target (odd)
    .equ POISON,    0xAAAAAAAA
    .equ STORED,    0x12345678

_start:
    lea     0x00010000, %a7
    move.l  #_lvl1_handler, 0x00000064  | vec 25 (level-1 IRQ) @ 0x64
    move.l  #_lvl2_handler, 0x00000068  | vec 26 (level-2 IRQ)
    move.l  #_lvl4_handler, 0x00000070  | vec 28 (level-4 IRQ)
    move.l  #_aerr_handler, 0x0000000C  | vec 3 (address error fallback)

    | Pre-poison the target longword at the aligned base 0x00020000.
    move.l  #POISON, 0x00020000
    move.l  #POISON, 0x00020004

    | Disable IRQ mask via SR.IPL=0 so injected IPL>0 will fire.
    | (We're in supervisor mode.)
    move.w  #0x2000, %sr                 | S=1, IPL=0, T=0

    | The unaligned LONG write — LSU cracks across the odd address.
    | Note: exc_addr_error is a DEFER on this build because the LSU
    | intentionally allows unaligned LONG for the A-line hot path,
    | so this should NOT fault — it just generates two bus beats
    | with strobes covering 4 bytes total.
    move.l  #STORED, %d0
    move.l  #TARGET, %a0
    move.l  %d0, (%a0)                   | unaligned LONG store

    | Without harness IRQ injection, control returns here directly.
    | Verify all 4 bytes of STORED are visible at TARGET..TARGET+3.
    move.b  TARGET, %d1
    cmp.b   #0x12, %d1
    bne     _fail_bytes
    move.b  (TARGET+1), %d1
    cmp.b   #0x34, %d1
    bne     _fail_bytes
    move.b  (TARGET+2), %d1
    cmp.b   #0x56, %d1
    bne     _fail_bytes
    move.b  (TARGET+3), %d1
    cmp.b   #0x78, %d1
    bne     _fail_bytes

_pass:
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_bytes:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0E01, %d2
    move.l  %d2, (%a1)
_halt_fb:
    bra     _halt_fb

| ─── IRQ handlers (entered iff harness injected an IPL) ──────────────
| Each verifies all 4 bytes of STORED are visible BEFORE the frame
| push (i.e. the store actually committed to memory before IRQ entry).
| Then RTE.  Mainline picks up after the store and writes PASS.
_lvl1_handler:
_lvl2_handler:
_lvl4_handler:
    | Read back all four bytes; if any is still POISON, the IRQ
    | pre-empted the store.  We don't have a clean way to fail-fast
    | from a handler context (we'd corrupt the RTE frame), so just
    | tag a FAIL sentinel and halt — testbench picks it up.
    move.l  TARGET, %d3
    cmp.l   #STORED, %d3
    beq     _irq_ok
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0E01, %d2
    move.l  %d2, (%a1)
_irq_halt_fail:
    bra     _irq_halt_fail
_irq_ok:
    rte

_aerr_handler:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0E02, %d2
    move.l  %d2, (%a1)
_aerr_halt:
    bra     _aerr_halt
