| ptest_mmusr_tables.s -- PTEST against REAL page tables, with the MMU ENABLED.
|
| NEGATIVE CONTROL for the 2026-09-17 PTEST rework. Before it, S_APPLY set
|   MMUSR := (An & pageMask) | 1
| UNCONDITIONALLY -- never consulting the TTRs, the ATC or the tables, and
| reporting R=1 (resident) with the MMU on and the page absent. EVERY check in
| this program fails on that RTL: the old code would answer 0x20000001 for the
| first probe where the correct answer is 0x00500601, and would report R=1 for
| both of the not-present cases.
|
| The distinction that matters to a Unix page-fault handler is NOT-PRESENT
| (R=0) versus PROTECTION (R=1 with W or S set); cases 2/3 vs 4/5 below are
| exactly that pair, and the old code collapsed them into one wrong answer.
|
| Posture: TC.E=1, 4 KB pages. Code runs transparently through ITT0 and
| RAM/stack through DTT0, so the only table searches performed are PTEST's own
| -- no ordinary access ever walks, which keeps every MMUSR attributable.
|
|   ITT0 = 0x4000C000  code   0x40000000-0x40FFFFFF, cacheable, any FC
|   DTT0 = 0x000FC000  RAM    0x00000000-0x0FFFFFFF, cacheable, any FC
|   DTT1 = 0x807FC040  0x80000000-0xFFFFFFFF, non-cacheable (sentinel window)
|   ITT1 = 0
|
| Probe VAs are 0x20xxxxxx, covered by NO TTR, so they reach the tables.
|
| Table tree at 0x00200000 (SRP):
|   +0x0000 root[128]   root[0x10] -> ptr,  W=0
|   +0x0200 ptr[128]    ptr[0x00]  -> page, W=0
|                       ptr[0x01]  -> page2, W=1   (W ACCUMULATION test)
|                       ptr[0x10]  =  0            (invalid POINTER level)
|   +0x0400 page[64]    [0] resident G=1 U1=1      [1] resident W=1
|                       [2] resident S=1           [3] INVALID (PDT=00)
|   +0x0600 page2[64]   [0] resident, own W=0 (the W must come from ptr[1])
|
| URP is deliberately pointed at ZEROED memory, so a probe with a USER function
| code must report R=0 for the very same VA that a SUPERVISOR function code
| resolves -- which is what proves PTEST selects SRP vs URP from FC[2] (DFC)
| rather than from the current privilege level.
|
| PASS sentinel: 0xC0FFEE00 at 0xFFFF0000.
| FAIL sentinel: 0xDEAD0E00 + the check index in D6, stored at 0xFFFF0000.

    .text
    .org 0

    .set TBL,    0x00200000
    .set ROOTT,  TBL+0x0000
    .set PTRT,   TBL+0x0200
    .set PAGET,  TBL+0x0400
    .set PAGE2T, TBL+0x0600
    .set URPT,   0x00300000

_start:
    lea     0x00010000, %a7
    moveq   #0, %d6

    | ── vector table at 0x00100000; any fault lands on a distinct sentinel ──
    move.l  #0x00100000, %d0
    movec   %d0, %vbr
    move.l  #_panic_berr, 0x00100008
    move.l  #_panic_aerr, 0x0010000C
    move.l  #_panic_ill,  0x00100010
    move.l  #_panic_priv, 0x00100020

    | ── zero the table region and the (deliberately empty) URP root ──
    lea     TBL, %a0
    move.w  #(0x800/4)-1, %d0
.zt:
    clr.l   (%a0)+
    dbra    %d0, .zt
    lea     URPT, %a0
    move.w  #127, %d0
.zu:
    clr.l   (%a0)+
    dbra    %d0, .zu

    | ── build the tree ───────────────────────────────────────────────────
    | root[0x10] -> PTRT, UDT=10 (resident), W=0
    move.l  #PTRT+0x02, ROOTT+(0x10*4)
    | ptr[0x00] -> PAGET, UDT=10, W=0
    move.l  #PAGET+0x02, PTRT+(0x00*4)
    | ptr[0x01] -> PAGE2T, UDT=10, W=1 (bit 2) -- the accumulation source
    move.l  #PAGE2T+0x06, PTRT+(0x01*4)
    | ptr[0x10] stays 0 -> UDT=00, INVALID pointer level. That is the index
    | VA 0x20400000 selects: bits [24:18] of 0x20400000 are 0b0010000 = 0x10.

    | page[0]: PA 0x00500000, PDT=01, U=1(bit3), G=1(bit10), U1=1(bit9)
    move.l  #0x00500609, PAGET+(0*4)
    | page[1]: PA 0x00501000, PDT=01, U=1, W=1(bit2)
    move.l  #0x0050100D, PAGET+(1*4)
    | page[2]: PA 0x00502000, PDT=01, U=1, S=1(bit7)
    move.l  #0x00502089, PAGET+(2*4)
    | page[3]: PDT=00 -> INVALID page descriptor
    move.l  #0x00000000, PAGET+(3*4)
    | page2[0]: PA 0x00540000, PDT=01, U=1, own W=0
    move.l  #0x00540009, PAGE2T+(0*4)

    | ── install the TTRs, the roots, then enable paged translation ───────
    move.l  #0x4000C000, %d0
    movec   %d0, %itt0
    moveq   #0, %d0
    movec   %d0, %itt1
    move.l  #0x000FC000, %d0
    movec   %d0, %dtt0
    move.l  #0x807FC040, %d0
    movec   %d0, %dtt1
    move.l  #TBL, %d0
    movec   %d0, %srp
    move.l  #URPT, %d0
    movec   %d0, %urp
    move.l  #0x00008000, %d0          | TC: E=1, P=0 (4 KB pages)
    movec   %d0, %tc
    pflusha

    | ── 1. resident page, supervisor DATA function code ──────────────────
    | MMUSR = PA 0x00500 | G(bit10) | U1(bit9) | R = 0x00500601
    moveq   #1, %d6
    moveq   #5, %d0
    movec   %d0, %dfc
    move.l  #0x20000000, %a0
    .short  0xF568                    | ptestr (a0)
    movec   %mmusr, %d1
    cmp.l   #0x00500601, %d1
    bne     _fail

    | ── 2. WRITE-PROTECTED page: R=1 AND W=1 (protection, not absence) ───
    moveq   #2, %d6
    move.l  #0x20001000, %a0
    .short  0xF568
    movec   %mmusr, %d1
    cmp.l   #0x00501005, %d1
    bne     _fail

    | ── 3. SUPERVISOR-ONLY page: R=1 AND S=1 ─────────────────────────────
    moveq   #3, %d6
    move.l  #0x20002000, %a0
    .short  0xF568
    movec   %mmusr, %d1
    cmp.l   #0x00502081, %d1
    bne     _fail

    | ── 4. INVALID PAGE descriptor: R=0 (NOT PRESENT) ────────────────────
    moveq   #4, %d6
    move.l  #0x20003000, %a0
    .short  0xF568
    movec   %mmusr, %d1
    cmp.l   #0x00000000, %d1
    bne     _fail

    | ── 5. INVALID POINTER level: R=0, at a different level of the search ─
    moveq   #5, %d6
    move.l  #0x20400000, %a0
    .short  0xF568
    movec   %mmusr, %d1
    cmp.l   #0x00000000, %d1
    bne     _fail

    | ── 6. W ACCUMULATES DOWN THE SEARCH: the pointer descriptor carries W,
    | the page descriptor does not, and MMUSR must still report W=1.
    moveq   #6, %d6
    move.l  #0x20040000, %a0
    .short  0xF568
    movec   %mmusr, %d1
    cmp.l   #0x00540005, %d1
    bne     _fail

    | ── 7. FC[2] selects SRP vs URP: the SAME VA as check 1, probed with a
    | USER data function code, must resolve through URP -- which points at
    | zeroed memory -- and report R=0.
    moveq   #7, %d6
    moveq   #1, %d0
    movec   %d0, %dfc
    move.l  #0x20000000, %a0
    .short  0xF568
    movec   %mmusr, %d1
    cmp.l   #0x00000000, %d1
    bne     _fail

    | ── 8. TRANSPARENT translation, INSTRUCTION space: FC=6 consults ITT0,
    | which covers 0x40xxxxxx -> T=1, R=1, PA=VA, no table search.
    moveq   #8, %d6
    moveq   #6, %d0
    movec   %d0, %dfc
    move.l  #0x40800000, %a0
    .short  0xF568
    movec   %mmusr, %d1
    cmp.l   #0x40800003, %d1
    bne     _fail

    | ── 9. ...and FC[1:0] really does pick ITT vs DTT: the SAME VA with a
    | supervisor DATA code matches NO DTT (DTT0 covers 0x00-0x0F, DTT1
    | 0x80-0xFF), falls through to a table search, and root[0x20] is
    | invalid -> R=0.
    moveq   #9, %d6
    moveq   #5, %d0
    movec   %d0, %dfc
    move.l  #0x40800000, %a0
    .short  0xF568
    movec   %mmusr, %d1
    cmp.l   #0x00000000, %d1
    bne     _fail

    | ── 10. PTEST MUST NOT DISTURB WHAT IT MEASURES. Re-probe check 1: the
    | descriptor's U and M bits must read back EXACTLY as written, proving no
    | U/M writeback was performed, and MMUSR must be identical, proving the
    | first probe installed nothing that changed the second answer.
    moveq   #10, %d6
    move.l  PAGET+(0*4), %d1
    cmp.l   #0x00500609, %d1
    bne     _fail
    moveq   #5, %d0
    movec   %d0, %dfc
    move.l  #0x20000000, %a0
    .short  0xF568
    movec   %mmusr, %d1
    cmp.l   #0x00500601, %d1
    bne     _fail
    move.l  PAGET+(0*4), %d1
    cmp.l   #0x00500609, %d1
    bne     _fail

    | ── 11. PTESTW and PTESTR agree (PTESTW's only architectural difference
    | is the M-set side effect, which is deliberately not performed).
    moveq   #11, %d6
    move.l  #0x20000000, %a0
    .short  0xF548                    | ptestw (a0)
    movec   %mmusr, %d1
    cmp.l   #0x00500601, %d1
    bne     _fail
    move.l  PAGET+(0*4), %d1
    cmp.l   #0x00500609, %d1          | ...and it still did not set M
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0E00, %d7
    or.l    %d6, %d7
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail

_panic_berr:
    move.l  #0xDEAD0B02, %d7
    bra     _panic_common
_panic_aerr:
    move.l  #0xDEAD0B03, %d7
    bra     _panic_common
_panic_ill:
    move.l  #0xDEAD0B04, %d7
    bra     _panic_common
_panic_priv:
    move.l  #0xDEAD0B08, %d7
_panic_common:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_panic:
    bra     _halt_panic
