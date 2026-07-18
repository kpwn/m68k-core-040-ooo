| exc_aline_odd_sp_mmu_dcache.s
|
| Reproduces the Q700 Sad Mac hypothesis (2026-05-14): the IRQ entry
| frame on HW (SP=0x1FDD9F, odd) showed corrupted SR/PC/format words.
| Frame pushes are SZ_WORD stores; an odd A7_new lands ea[1:0]={11,
| 01,11,01} so half the pushes split-store across cache lines.  All
| pushes go through the D-cache (`dc_wstrb`, `dc_*`).
|
| The existing odd-SP A-line tests pass with MMU OFF and D-cache OFF.
| HW Sad Mac path has MMU ON (TC=0xC000) and CACR.DE=1.  This test
| sets up both, takes an A-line trap from odd SP, and byte-verifies
| the resulting Format-0 frame in memory.
|
| Frame layout (8 bytes) at A7_new = 0x10005 - 8 = 0xFFFD:
|   byte 0:  SR[15:8]   = 0x20   (supervisor, IPL=0)
|   byte 1:  SR[7:0]    = 0x00
|   byte 2:  PC[31:24]  = high byte of _aline_site
|   byte 3:  PC[23:16]
|   byte 4:  PC[15:8]
|   byte 5:  PC[7:0]
|   byte 6:  fmt[15:8]  = 0x00   (Format-0, vec*4 high nibble)
|   byte 7:  fmt[7:0]   = 0x28   (vec 10 * 4 = 0x28)
|
| Frame address 0xFFFD is itself odd, so byte 0 is at ea[1:0]=01,
| byte 1 at ea[1:0]=10 — the SR word straddles two 32-bit words and
| forces the split-store path in lsu.v::m68k_mem_split_wstrb_*.
|
| FAIL sentinels distinguish where the corruption is:
|   0xDEAD0501  A7 not 0xFFFD after entry      (wrong frame address)
|   0xDEAD0510  SR[15:8]  wrong                (byte 0, split-first)
|   0xDEAD0511  SR[7:0]   wrong                (byte 1, split-second)
|   0xDEAD0512  PC[31:24] wrong                (byte 2)
|   0xDEAD0513  PC[23:16] wrong                (byte 3, split-second)
|   0xDEAD0514  PC[15:8]  wrong                (byte 4, split-first)
|   0xDEAD0515  PC[7:0]   wrong                (byte 5)
|   0xDEAD0516  fmt[15:8] wrong                (byte 6, split-first)
|   0xDEAD0517  fmt[7:0]  wrong                (byte 7, split-second)

    .text
    .org 0

    .equ PASS_SENT,   0xFFFF0000
    .equ VBR_BASE,    0x00100000
    .equ ODD_SP,      0x00010005          | matches existing odd-SP test

_start:
    | ── Vectors at high VBR=0x00100000 ──────────────────────────────
    | Park the whole table at _panic; install _aline_handler at vec 10.
    lea     VBR_BASE, %a1
    move.l  #_panic, %d0
    move.l  %d0, 0(%a1)                 | reset SSP
    move.l  %d0, 4(%a1)                 | reset PC
    move.l  %d0, 8(%a1)                 | bus error
    move.l  %d0, 12(%a1)                | addr error
    move.l  %d0, 16(%a1)                | illegal
    move.l  %d0, 20(%a1)                | zero div
    move.l  %d0, 24(%a1)                | CHK
    move.l  %d0, 28(%a1)                | TRAPV
    move.l  %d0, 32(%a1)                | priv viol
    move.l  %d0, 36(%a1)                | trace
    move.l  %d0, 44(%a1)                | F-line
    move.l  #_aline_handler, 40(%a1)    | vec 10 (A-line) @ VBR+0x28

    move.l  #VBR_BASE, %d0
    movec   %d0, %vbr

    | ── MMU TT setup — supervisor passthrough everywhere we touch. ──
    | ITT0 covers code (0x40000000..) for fetch.
    move.l  #0x4000C040, %d0
    movec   %d0, %itt0
    | DTT0 covers low DRAM (0x00000000..0x7FFFFFFF) — matches the
    | byte_lane_indexed_mmu_irq known-good setup.
    move.l  #0x007FA000, %d0            | base=0, mask=7F, S=01 sup, E=1
    movec   %d0, %dtt0
    | DTT1 covers 0xFFxxxxxx sentinel area, cache-inhibit serialized
    | (CM=10).  Cacheable would force a fill-from-AXI on the sentinel
    | line, which is unmapped → SLVERR → bus error.  Inhibit bypasses
    | the cache and writes directly to AXI where tb_top decodes 0xFFFF0000.
    move.l  #0xFF00A040, %d0            | base=FF, mask=00, E=1, S=01 sup, CM=10
    movec   %d0, %dtt1
    | URP/SRP point at a benign table — TTs cover the test region.
    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp
    | TC.E=1, 4K pages.  Walker not exercised — TTs cover everything.
    move.l  #0x00008000, %d0
    movec   %d0, %tc

    | ── D-cache enable: CACR.DE = 1 (bit 31) ────────────────────────
    move.l  #0x80000000, %d0
    movec   %d0, %cacr

    | ── Pre-dirty the frame region so the split push hits dirty lines.
    | Frame will land at 0xFFFD..0x10004 (8 bytes across two 32-bit
    | words — 0xFFFC..0xFFFF and 0x10000..0x10003 — and into a third
    | for byte 7).  Pre-write so split-store interacts with dirty data.
    lea     0x0000FFFC, %a2
    move.l  #0xA5A5A5A5, (%a2)+         | 0xFFFC..0xFFFF
    move.l  #0x5A5A5A5A, (%a2)+         | 0x10000..0x10003
    move.l  #0xC3C3C3C3, (%a2)          | 0x10004..0x10007

    | ── Setup odd SP via postinc moves (same pattern as the
    | known-working exc_aline_after_sr_byte_postinc_odd_sp test).
    | Plain `add.l #N, %a7` does not commit before the A-line
    | exception entry; postinc on %a7 with byte/word size does.
    lea     0x00010001, %a7
    move.w  #0x2000, (%a7)              | SR to restore via the SR pop
    move.b  #0x7e, 2(%a7)               | byte popped into D0
    move.l  #0x11223344, %d0

    move.w  (%a7)+, %sr                 | sr <- 0x2000; a7=0x10003
    move.b  (%a7)+, %d0                 | a7=0x10005 (odd, freshly renamed)
    | ── A-line trap site ─────────────────────────────────────────────
_aline_site:
    .short  0xa05d
_fallthrough:
    move.l  #0xDEAD0FFF, %d7
    bra     _fail


| ── A-line handler ─────────────────────────────────────────────────
| Entry: A7 = 0xFFFD (= 0x10005 - 8).  Verify frame bytes.

    .align 2
_aline_handler:
    cmp.l   #0x0000FFFD, %a7
    bne     _fail_sp_h

    | Move to a safe even SP for handler bookkeeping.
    move.l  #0x00020000, %a7

    | Compute expected PC value (= _aline_site, since RTL pushes
    | faulting PC for A-line per the existing odd-SP test).
    move.l  #_aline_site, %d1

    | ── Byte-verify the frame at 0xFFFD..0x10004 ─────────────────────
    | byte 0: SR[15:8] = 0x20
    moveq   #0, %d0
    move.b  0x0000FFFD, %d0
    cmp.b   #0x20, %d0
    bne     _fail_b0

    | byte 1: SR[7:0]  = 0x00
    move.b  0x0000FFFE, %d0
    cmp.b   #0x00, %d0
    bne     _fail_b1

    | byte 2: PC[31:24]
    move.l  %d1, %d2
    swap    %d2                          | d2.w = PC[31:16]
    move.b  0x0000FFFF, %d0
    move.l  %d2, %d3
    lsr.l   #8, %d3                      | d3.b = PC[31:24]
    cmp.b   %d3, %d0
    bne     _fail_b2

    | byte 3: PC[23:16]
    move.b  0x00010000, %d0
    cmp.b   %d2, %d0
    bne     _fail_b3

    | byte 4: PC[15:8]
    move.l  %d1, %d3
    lsr.l   #8, %d3                      | d3.b = PC[15:8]
    move.b  0x00010001, %d0
    cmp.b   %d3, %d0
    bne     _fail_b4

    | byte 5: PC[7:0]
    move.b  0x00010002, %d0
    cmp.b   %d1, %d0
    bne     _fail_b5

    | byte 6: fmt[15:8] = 0x00
    move.b  0x00010003, %d0
    cmp.b   #0x00, %d0
    bne     _fail_b6

    | byte 7: fmt[7:0] = 0x28 (vec 10 * 4)
    move.b  0x00010004, %d0
    cmp.b   #0x28, %d0
    bne     _fail_b7

    | All bytes match.
_pass:
    lea     PASS_SENT, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt_p:
    bra     _halt_p

| ── Failure sentinels ──────────────────────────────────────────────
_fail_sp_h:
    move.l  #0x00020000, %a7
    move.l  #0xDEAD0501, %d7
    bra     _fail
_fail_b0:
    move.l  #0xDEAD0510, %d7
    bra     _fail
_fail_b1:
    move.l  #0xDEAD0511, %d7
    bra     _fail
_fail_b2:
    move.l  #0xDEAD0512, %d7
    bra     _fail
_fail_b3:
    move.l  #0xDEAD0513, %d7
    bra     _fail
_fail_b4:
    move.l  #0xDEAD0514, %d7
    bra     _fail
_fail_b5:
    move.l  #0xDEAD0515, %d7
    bra     _fail
_fail_b6:
    move.l  #0xDEAD0516, %d7
    bra     _fail
_fail_b7:
    move.l  #0xDEAD0517, %d7
    bra     _fail

_fail:
    lea     PASS_SENT, %a0
    move.l  %d7, (%a0)
_halt_f:
    bra     _halt_f

_panic:
    move.l  #0xDEAD9999, %d7
    bra     _fail
