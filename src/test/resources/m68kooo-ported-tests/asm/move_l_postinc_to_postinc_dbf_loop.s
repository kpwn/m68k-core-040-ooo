| move_l_postinc_to_postinc_dbf_loop.s — MOVE.L (An)+,(Am)+ in a DBF copy loop.
|
| WHY THIS EXISTS (task #140, 2026-07-28)
| ------------------------------------------------------------------------
| Captured on real hardware as a HARD PIPELINE WEDGE — not an OS stall.  After
| a reset the CPU froze with:
|
|     pc pinned at 0x40809A04 (and unchanged with the breakpoint disarmed)
|     exc_count = 0x00004421, FROZEN across 8 s  -> NO interrupts serviced
|     wedge: rob_pc=0x4083158A rob_hd=0 uop_t=0x3 (UOP_STORE) uop_op=0x00
|            lsu=0:IDLE dcache=0:IDLE
|            lsu_busy=0 mem_iss=0 dc_req=0 arvalid=0 rvalid=0
|
| i.e. the ROB head is the STORE half of the instruction at 0x4083158A, with
| NOTHING in flight anywhere — no memory request outstanding, nothing that
| could ever complete it.  The ROM there is:
|
|     40831584  701a        moveq #26,%d0
|     40831586  41fa 00c4   lea   %pc@(0x4083164C),%a0
|     4083158A  28d8        move.l (%a0)+,(%a4)+     <- the wedged instruction
|     4083158C  51c8 fffc   dbf   %d0,0x4083158A
|
| a 27-iteration long copy.  MOVE with BOTH operands in memory cracks into a
| LOAD followed by a STORE, and with both address registers post-incrementing;
| CLAUDE.md lists "dual-indexed long MOVE" as one of only two legacy decode
| surfaces still live BY DELIBERATE DEFERRAL, so this family is known-thin.
|
| DISTINCT FROM THE ioResult STALL: there the CPU kept servicing interrupts
| (exc_count climbing) while spinning in a poll.  Here interrupts are dead and
| the pipeline itself is stuck.  Do not conflate the two.
|
| This test reproduces the shape in isolation: same instruction, same DBF
| loop, same 27 iterations, source read from a PC-relative table like the ROM's
| and destination post-incrementing through RAM.  It also covers the aliasing
| corners a naive crack gets wrong — overlapping src/dst, and dst == src.
|
| A HANG/TIMEOUT here is the interesting result: it would mean the hardware
| wedge reproduces in sim and can be debugged with waveforms instead of JTAG.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD2E01 — copied data wrong (forward, non-overlapping)
|   0xDEAD2E02 — A0 not left at src_end after the loop
|   0xDEAD2E03 — A4 not left at dst_end after the loop
|   0xDEAD2E04 — overlapping copy (dst = src + 4) produced wrong data
|   0xDEAD2E05 — single MOVE.L (An)+,(An)+ with the SAME register wrong
|   0xDEAD2E06 — ROM->RAM copy wrong (the ROM's actual load/store direction)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SRC,       0x00030000
    .equ DST,       0x00031000

_start:
    lea     0x00010000, %a7

    | ── Fill the source with a distinguishable pattern ───────────────────
    lea     SRC, %a1
    move.l  #0x11110000, %d1
    moveq   #26, %d0
fill:
    move.l  %d1, (%a1)+
    add.l   #0x00010001, %d1
    dbf     %d0, fill

    | ── The ROM's exact shape: 27 x MOVE.L (An)+,(Am)+ under DBF ─────────
    lea     SRC, %a0
    lea     DST, %a4
    moveq   #26, %d0
copy:
    move.l  (%a0)+, (%a4)+
    dbf     %d0, copy

    | address registers must land exactly one past the last long
    cmp.l   #SRC + 27*4, %a0
    bne     fail_a0
    cmp.l   #DST + 27*4, %a4
    bne     fail_a4

    | ── Verify every long ────────────────────────────────────────────────
    lea     SRC, %a1
    lea     DST, %a2
    moveq   #26, %d0
verify:
    move.l  (%a1)+, %d1
    move.l  (%a2)+, %d2
    cmp.l   %d1, %d2
    bne     fail_data
    dbf     %d0, verify

    | ── Overlapping copy, dst = src + 4 (forward overlap) ────────────────
    | Each store lands on the long the NEXT load will read, so a crack that
    | reorders the load/store pair produces a different (wrong) result.
    lea     SRC, %a0
    lea     SRC + 4, %a4
    moveq   #3, %d0
copy_ov:
    move.l  (%a0)+, (%a4)+
    dbf     %d0, copy_ov
    | src[0] is untouched and every following long becomes src[0]
    move.l  SRC, %d1
    move.l  SRC + 4, %d2
    cmp.l   %d1, %d2
    bne     fail_overlap
    move.l  SRC + 16, %d2
    cmp.l   %d1, %d2
    bne     fail_overlap

    | ── Same register for both operands: MOVE.L (A3)+,(A3)+ ─────────────
    | 68k reads then writes, with A3 incremented ONCE per access, so this
    | copies a long onto ITSELF at +0 and leaves A3 at +8.
    lea     SRC + 0x100, %a3
    move.l  #0x5A5AA5A5, (%a3)
    move.l  (%a3)+, (%a3)+
    cmp.l   #SRC + 0x100 + 8, %a3
    bne     fail_samereg

    | ── ROM -> RAM, the ROM's ACTUAL direction ───────────────────────────
    | The wedged ROM loop sources from a PC-relative table IN ROM and stores
    | to RAM, so the load and the store go to different memories/paths.  The
    | RAM->RAM cases above cannot exercise that asymmetry.
    lea     romtab(%pc), %a0
    lea     DST + 0x200, %a4
    moveq   #26, %d0
copy_rom:
    move.l  (%a0)+, (%a4)+
    dbf     %d0, copy_rom

    lea     romtab(%pc), %a1
    lea     DST + 0x200, %a2
    moveq   #26, %d0
verify_rom:
    move.l  (%a1)+, %d1
    move.l  (%a2)+, %d2
    cmp.l   %d1, %d2
    bne     fail_romcopy
    dbf     %d0, verify_rom

    move.l  #0xC0FFEE00, %d1
    move.l  %d1, PASS_SENT
    bra     .

fail_romcopy:
    move.l  #0xDEAD2E06, %d1
    move.l  %d1, PASS_SENT
    bra     .

fail_data:
    move.l  #0xDEAD2E01, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_a0:
    move.l  #0xDEAD2E02, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_a4:
    move.l  #0xDEAD2E03, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_overlap:
    move.l  #0xDEAD2E04, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_samereg:
    move.l  #0xDEAD2E05, %d1
    move.l  %d1, PASS_SENT
    bra     .

    .align 2
romtab:
    .long 0xC0DE0000, 0xC0DE0101, 0xC0DE0202, 0xC0DE0303, 0xC0DE0404
    .long 0xC0DE0505, 0xC0DE0606, 0xC0DE0707, 0xC0DE0808, 0xC0DE0909
    .long 0xC0DE0A0A, 0xC0DE0B0B, 0xC0DE0C0C, 0xC0DE0D0D, 0xC0DE0E0E
    .long 0xC0DE0F0F, 0xC0DE1010, 0xC0DE1111, 0xC0DE1212, 0xC0DE1313
    .long 0xC0DE1414, 0xC0DE1515, 0xC0DE1616, 0xC0DE1717, 0xC0DE1818
    .long 0xC0DE1919, 0xC0DE1A1A
