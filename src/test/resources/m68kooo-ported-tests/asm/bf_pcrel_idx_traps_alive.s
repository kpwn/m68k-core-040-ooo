| bf_pcrel_idx_traps_alive.s — PC-indexed / full-format-PC bitfield
| reads.
|
| Companion to bf_pcrel_read.s (which covers the plain (d16,PC) read
| shapes and the architecturally-illegal RMW x PC-rel trap).  The shapes
| here are ARCHITECTURALLY LEGAL read EAs:
|
|   1. brief-format (d8,PC,Xn)                  -- implemented: value check
|   2. full-format (bd,PC,Xn) no-memind         -- implemented: value check
|   3. full-format ([bd,PC,Xn],od) memind pre-indexed, STATIC offset
|                                               -- implemented: value check
|
| No case may trap (vec 4 is counted in D6 and fails the test).
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_ill_handler, 0x00000010
    moveq   #0, %d6              | vec-4 trap counter
    moveq   #0, %d3              | index reg

    | ---- Case 1: brief-format (d8,PC,Xn) read -> 0x81 ----
    lea     _c1(%pc), %a5
    moveq   #0, %d0
    bfextu  _bfdat(%pc,%d3.l){#0:#8}, %d0
_c1:
    tst.l   %d6
    bne     _fail
    cmp.l   #0x81, %d0
    bne     _fail

    | ---- Case 2: full-format (0x100,PC,D3.W) no-memind -> 0x81 ----
    | D3 = _bfdat - (_e2 + 0x100), so the EA lands exactly on _bfdat.
    lea     _bfdat, %a1
    lea     _e2+0x100, %a2
    sub.l   %a2, %a1
    move.l  %a1, %d3
    lea     _c2(%pc), %a5
    moveq   #0, %d0
    .word   0xE9FB, 0x0008
_e2:
    .word   0x3120, 0x0100
_c2:
    tst.l   %d6
    bne     _fail
    cmp.l   #0x81, %d0
    bne     _fail

    | ---- Case 3: ([0x100,PC,D3.W]) memind pre-indexed, static offset -> 0x81 ----
    | D3 = _ptr3 - (_e3 + 0x100): the pointer is read from _ptr3 (= _bfdat).
    lea     _ptr3, %a1
    lea     _e3+0x100, %a2
    sub.l   %a2, %a1
    move.l  %a1, %d3
    lea     _c3(%pc), %a5
    moveq   #0, %d0
    .word   0xE9FB, 0x0008
_e3:
    .word   0x3121, 0x0100
_c3:
    tst.l   %d6
    bne     _fail
    cmp.l   #0x81, %d0
    bne     _fail

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
_ptr3:
    .long   _bfdat
_bfdat:
    .long   0x81234567
    .long   0x89ABCDEF
