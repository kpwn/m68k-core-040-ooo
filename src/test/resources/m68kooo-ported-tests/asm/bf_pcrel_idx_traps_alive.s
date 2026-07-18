| bf_pcrel_idx_traps_alive.s — PC-indexed / full-format-PC bitfield
| reads: fail-SAFE contract.
|
| Companion to bf_pcrel_read.s (which covers the now-implemented plain
| (d16,PC) read shapes and the architecturally-illegal RMW × PC-rel
| trap).  The shapes here are ARCHITECTURALLY LEGAL read EAs that the
| decoder does not implement yet:
|
|   1. brief-format (d8,PC,Xn)
|   2. full-format (bd,PC,Xn) no-memind
|   3. full-format ([bd,PC,Xn],od) memind pre-indexed
|
| The contract this test pins is fail-SAFE, not fail-correct: each must
| raise a precise, recoverable vec-4 ILLEGAL trap — the core must NOT
| wedge (a hang is unrecoverable; a trap the OS can catch and report).
| The isa_status.md TODO(bf-pcrel) note claimed these shapes "wedge the
| core"; this test is the standing evidence that they trap cleanly and
| execution continues.
|
| When real support for a shape lands, WIDEN this test: replace its
| trap-expect with a value check (never delete the case).
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_ill_handler, 0x00000010
    moveq   #0, %d6              | vec-4 trap counter
    moveq   #0, %d3              | index reg for the encoded shapes

    | ---- Case 1: brief-format (d8,PC,Xn) read -> vec 4, continue ----
    lea     _c1(%pc), %a5
    bfextu  _bfdat(%pc,%d3.l){#0:#8}, %d0
_c1:
    cmp.l   #1, %d6
    bne     _fail

    | ---- Case 2: full-format (bd.w,PC,Xn.w) no-memind -> vec 4 ----
    | ext: 0x3120 = D3, Xn.W, x1, full-format, BS=0, IS=0, bd.w,
    | I/IS=000.  Descriptor 0x0008 = static {0:8}.
    lea     _c2(%pc), %a5
    .word   0xE9FB, 0x0008, 0x3120, 0x0100
_c2:
    cmp.l   #2, %d6
    bne     _fail

    | ---- Case 3: full-format ([bd.w,PC,Xn.w]) memind pre-idx -> vec 4 ----
    | ext: 0x3121 = same but I/IS=001 (pre-indexed memind, od=null).
    lea     _c3(%pc), %a5
    .word   0xE9FB, 0x0008, 0x3121, 0x0100
_c3:
    cmp.l   #3, %d6
    bne     _fail

    | ---- Still alive: a plain (d16,PC) read executes correctly ----
    bfextu  _bfdat(%pc){#4:#8}, %d0
    cmp.l   #0x12, %d0
    bne     _fail

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_ill_handler:
    | Format $0 frame: SR at (sp), PC at 2(sp), format/vec at 6(sp).
    addq.l  #1, %d6
    move.l  %a5, 2(%sp)
    rte

    .align  4
_bfdat:
    .long   0x81234567
    .long   0x89ABCDEF
