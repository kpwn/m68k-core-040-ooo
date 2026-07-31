| tst_abs_beqw_mmu_on.s — TST.B (xxx).W + word-displacement Bcc, with
| the 68040 MMU ENABLED and doing REAL page-table walks (not just
| TT-transparent passthrough).
|
| MOTIVATION.  tst_abs_beqw.s proves the isolated pair correct with the
| MMU off.  The live HW that hangs at 0x0002E88E runs with the MMU ON
| (TC=0x0000C000: E=1, 8K pages, per live-HW JTAG readback recorded in
| mmu_swapmode_cpush_race.s / mmu_swapmode_dual_srp_walk.s) and SRP
| pointed at a real 3-level page table — every operand fetch for the
| 0x0349 probe byte and every instruction fetch of the beq.w's target
| goes through the ATC (fill-on-miss from the table walker, hit
| thereafter), not a flat identity map.  This test reproduces that
| exact MMU configuration (1:1 8K-page identity map for RAM 0x000000-
| 0x3FFFFF and code 0x40800000-0x4083FFFF, TTRs disabled so nothing is
| transparent — a genuine table walk on first touch of any page) and
| repeats the isolated pair inside it, including a COLD first touch of
| the 0x0349 page (forces a real walker invocation + ATC fill) followed
| by repeats (ATC hit path).
|
| Table-build recipe and REC_A/TC values are lifted verbatim from the
| proven-working mmu_swapmode_cpush_race.s "_build_tables" routine
| (mode A: TTR0/TTR1 disabled, 8K pages, descriptors |0x0A / |0x39
| matching the live-HW page descriptor 0x40802039 read back over JTAG).
|
| PASS: 0xC0FFEE00 sentinel.
| FAIL sentinels:
|   0xDEAD0B02 — bus error during setup/run (unexpected fault)
|   0xDEAD0B04 — illegal instruction during setup/run
|   0xDEAD0601 — beq.w NOT taken while Z=1, MMU-walked operand (COLD touch)
|   0xDEAD0602 — beq.w WAS taken while Z=0, MMU-walked operand
|   0xDEAD0603 — beq.w NOT taken while Z=1, MMU-walked operand (ATC-hit repeat)
|   0xDEAD0604 — beq.w WAS taken while Z=0, MMU-walked operand (ATC-hit repeat)

    .text
    .org 0

    .set SRP_A, 0x00200000

_start:
    lea     0x00010000, %a7

    move.l  #_panic_berr, 0x00100008  | bus error
    move.l  #_panic_ill,  0x00100010  | illegal instruction

    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    | ── Build one 1:1 8K-page table set (verbatim recipe) ────────────
    lea     SRP_A, %a5
    bsr     _build_tables

    move.l  #SRP_A, %d0
    movec   %d0, %urp

    | TTRs disabled — real table walk on every first touch.
    moveq   #0, %d0
    movec   %d0, %itt0
    movec   %d0, %itt1
    movec   %d0, %dtt0
    movec   %d0, %dtt1

    move.l  #SRP_A, %d0
    movec   %d0, %srp
    move.l  #0x0000C000, %d0          | TC: E=1, 8K pages
    movec   %d0, %tc
    pflusha

    | ---- COLD: first-ever touch of the 0x0349 page -> real walker ---
    lea     0x00000349, %a0
    move.b  #0x00, (%a0)
    .short  0x4a38, 0x0349          | tst.b 0x0349.W  (Z := 1, cold walk)
    .short  0x6700
    .short  (_cold_z_ok - .)
    bra     _fail_cold_z_not_taken
_cold_z_ok:
    move.b  #0x01, (%a0)
    .short  0x4a38, 0x0349          | tst.b 0x0349.W  (Z := 0, ATC hit now)
    .short  0x6700
    .short  (_fail_cold_z_taken - .)
    | correct: fall through

    | ---- REPEAT: same page, now definitely ATC-resident ------------
    moveq   #0, %d6
_repeat_loop:
    move.b  #0x00, (%a0)
    .short  0x4a38, 0x0349          | tst.b 0x0349.W  (Z := 1)
    .short  0x6700
    .short  (_rep_z_ok - .)
    bra     _fail_rep_z_not_taken
_rep_z_ok:
    move.b  #0x01, (%a0)
    .short  0x4a38, 0x0349          | tst.b 0x0349.W  (Z := 0)
    .short  0x6700
    .short  (_fail_rep_z_taken - .)
    | correct: fall through

    addq.l  #1, %d6
    cmp.l   #32, %d6
    blt     _repeat_loop

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail_cold_z_not_taken:
    move.l  #0xDEAD0601, %d7
    bra     _fail_common
_fail_cold_z_taken:
    move.l  #0xDEAD0602, %d7
    bra     _fail_common
_fail_rep_z_not_taken:
    move.l  #0xDEAD0603, %d7
    bra     _fail_common
_fail_rep_z_taken:
    move.l  #0xDEAD0604, %d7
_fail_common:
    lea     0xFFFF0000, %a1
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail

_panic_berr:
    move.l  #0xDEAD0B02, %d7
    bra     _panic_common
_panic_ill:
    move.l  #0xDEAD0B04, %d7
_panic_common:
    lea     0xFFFF0000, %a1
    move.l  %d7, (%a1)
_halt_panic:
    bra     _halt_panic

| ── _build_tables: build one 1:1 8K-page 3-level table set at (%a5) ──
| Verbatim from mmu_swapmode_cpush_race.s.  Layout (offsets from a5):
|   +0x0000 root   (128 x 4B)
|   +0x0200 L2_ram (128 x 4B)   root[0x00]
|   +0x0400 L2_rom (128 x 4B)   root[0x20]
|   +0x0600 L3_ram (512 x 4B)   L2_ram[0x00..0x0F], 16 tables x 32
|   +0x0E00 L3_rom (32 x 4B)    L2_rom[0x20]
|   +0x0E80 L2_hi  (128 x 4B)   root[0x7F]
|   +0x1080 L3_hi  (32 x 4B)    L2_hi[0x3F] — maps VA 0xFFFE0000-
|                               0xFFFFFFFF 1:1 so the sentinel PA
|                               0xFFFF0000 is reachable through the walk.
_build_tables:
    move.l  %a5, %a0
    move.w  #(0x1100/4)-1, %d0
.zero_loop:
    clr.l   (%a0)+
    dbra    %d0, .zero_loop

    move.l  %a5, %d0
    addi.l  #0x0200+0x0A, %d0
    move.l  %d0, 0x000(%a5)
    move.l  %a5, %d0
    addi.l  #0x0400+0x0A, %d0
    move.l  %d0, 0x080(%a5)

    lea     0x0200(%a5), %a0
    move.l  %a5, %d0
    addi.l  #0x0600+0x0A, %d0
    moveq   #15, %d2
.l2ram_loop:
    move.l  %d0, (%a0)+
    addi.l  #0x80, %d0
    dbra    %d2, .l2ram_loop

    move.l  %a5, %d0
    addi.l  #0x0E00+0x0A, %d0
    move.l  %d0, 0x0400+0x080(%a5)

    lea     0x0600(%a5), %a0
    move.l  #0x00000039, %d0
    move.w  #511, %d2
.l3ram_loop:
    move.l  %d0, (%a0)+
    addi.l  #0x2000, %d0
    dbra    %d2, .l3ram_loop

    lea     0x0E00(%a5), %a0
    move.l  #0x40800039, %d0
    moveq   #31, %d2
.l3rom_loop:
    move.l  %d0, (%a0)+
    addi.l  #0x2000, %d0
    dbra    %d2, .l3rom_loop

    move.l  %a5, %d0
    addi.l  #0x0E80+0x0A, %d0
    move.l  %d0, 0x1FC(%a5)
    move.l  %a5, %d0
    addi.l  #0x1080+0x0A, %d0
    move.l  %d0, 0x0E80+0x1FC(%a5)
    lea     0x1080(%a5), %a0
    move.l  #0xFFFC0059, %d0
    moveq   #31, %d2
.l3hi_loop:
    move.l  %d0, (%a0)+
    addi.l  #0x2000, %d0
    dbra    %d2, .l3hi_loop
    rts
