| bf_pcrel_read.s — (d16,PC)-relative bitfield operands.
|
| Guards TODO(bf-pcrel).  Pre-fix, (d16,PC) bitfield EAs never executed:
| decode_ea_v2 reports supported=0 for every dst-port PC-rel mode (the
| `!is_src` source-only guard), the v2_bf_mem_ok_ro fire gate required
| supported=1, and the shape fell to the vec-4 ILLEGAL default (NOT a
| decode wedge — the isa_status.md "core wedges" note did not reproduce
| on pristine 279f1168: all PC-rel bitfield shapes trapped cleanly).
| This test covers both halves of the fixed behaviour:
|
|   Part A — read-only sub-ops (BFTST/BFEXTU/BFEXTS/BFFFO) on (d16,PC)
|            EAs execute with real PRM §4.29 windowed semantics: static,
|            straddling (off+width > 32), offset >= 32, dynamic offset,
|            dynamic both, and negative dynamic offset shapes.
|   Part B — write sub-ops (BFCHG/BFCLR/BFSET/BFINS) on (d16,PC) are NOT
|            data-alterable per PRM §4.30-§4.36 and must raise a clean
|            vec-4 ILLEGAL trap (hand-encoded via .word — gas refuses to
|            assemble them).  The handler redirects to a continuation
|            label and the test proceeds — proving the core stays alive
|            and the trap is precise/recoverable.
|
| Expected field values are Musashi-validated (fuzz co-sim runs the same
| descriptor shapes; field arithmetic per PRM §4.29).
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    | Supervisor stack + vec-4 (ILLEGAL) handler at VBR=0, 4*4 = 0x10.
    lea     0x00010000, %a7
    move.l  #_ill_handler, 0x00000010
    moveq   #0, %d6              | ILLEGAL-trap counter
    | Part A must not trap: any vec-4 during Part A redirects to _fail
    | (pre-fix these decoded to the vec-4 default; a raw RTE loop or an
    | uninstalled vector would look like a hang instead of a FAIL).
    lea     _fail(%pc), %a5

    | ================= Part A: read-only (d16,PC) =================

    | ---- Case 1: BFTST static {0:8} -> field 0x81: N=1, Z=0 ----
    bftst   _bfdat(%pc){#0:#8}
    beq     _fail
    bpl     _fail

    | ---- Case 2: BFEXTU static {4:8} -> 0x12: N=0, Z=0 ----
    bfextu  _bfdat(%pc){#4:#8}, %d0
    bmi     _fail
    beq     _fail
    cmp.l   #0x12, %d0
    bne     _fail

    | ---- Case 3: BFEXTS static {0:4} -> 0x8 sign-ext = 0xFFFFFFF8 ----
    bfexts  _bfdat(%pc){#0:#4}, %d0
    bpl     _fail
    cmp.l   #0xFFFFFFF8, %d0
    bne     _fail

    | ---- Case 4: BFFFO static {8:8} -> field 0x23, first set bit at
    |      offset 8+2 = 10 ----
    bfffo   _bfdat(%pc){#8:#8}, %d0
    cmp.l   #10, %d0
    bne     _fail

    | ---- Case 5: BFEXTU static STRADDLE {28:8} -> 0x78 ----
    | (low nibble of L0 = 0x7, high nibble of L1 = 0x8)
    bfextu  _bfdat(%pc){#28:#8}, %d0
    cmp.l   #0x78, %d0
    bne     _fail

    | ---- Case 6: BFEXTU dynamic offset {D1=16:16} -> 0x4567 ----
    moveq   #16, %d1
    bfextu  _bfdat(%pc){%d1:#16}, %d0
    cmp.l   #0x4567, %d0
    bne     _fail

    | ---- Case 7: BFEXTU dynamic offset >= 32 {D1=32:8} -> 0x89 ----
    moveq   #32, %d1
    bfextu  _bfdat(%pc){%d1:#8}, %d0
    cmp.l   #0x89, %d0
    bne     _fail

    | ---- Case 8: BFEXTU dyn-BOTH {D1=36:D2=8} -> 0x9A ----
    moveq   #36, %d1
    moveq   #8, %d2
    bfextu  _bfdat(%pc){%d1:%d2}, %d0
    cmp.l   #0x9A, %d0
    bne     _fail

    | ---- Case 9: BFEXTS dyn NEGATIVE offset {D1=-8:8} from _bfdat2 ----
    | bits -8..-1 relative to _bfdat2 = last byte of L0 = 0x67
    | -> sign-extended 0x67 (MSB clear) = 0x00000067.
    moveq   #-8, %d1
    bfexts  _bfdat2(%pc){%d1:#8}, %d0
    cmp.l   #0x67, %d0
    bne     _fail

    | ---- Case 10: BFFFO dyn-both {D1=32:D2=4} -> field 0x8, ffo=32 ----
    moveq   #32, %d1
    moveq   #4, %d2
    bfffo   _bfdat(%pc){%d1:%d2}, %d0
    cmp.l   #32, %d0
    bne     _fail

    | ============ Part B: write sub-ops must ILLEGAL-trap ==========
    | PC-rel is not data-alterable; gas refuses these, so hand-encode.
    | Descriptor = static {0:4} = 0x0004; d16 = 0 (never dereferenced —
    | the trap fires at decode time).  The handler bumps D6 and
    | redirects the stacked PC to (A5).

    | ---- Case 11: BFCHG d16(PC) -> ILLEGAL (vec 4) ----
    lea     _cont1(%pc), %a5
    .word   0xEAFA, 0x0004, 0x0000    | bfchg (0,%pc){#0:#4}
    bra     _fail                     | fell through without trapping
_cont1:
    cmp.l   #1, %d6
    bne     _fail

    | ---- Case 12: BFCLR d16(PC) -> ILLEGAL (vec 4) ----
    lea     _cont2(%pc), %a5
    .word   0xECFA, 0x0004, 0x0000    | bfclr (0,%pc){#0:#4}
    bra     _fail
_cont2:
    cmp.l   #2, %d6
    bne     _fail

    | ---- Case 13: BFSET d16(PC) -> ILLEGAL (vec 4) ----
    lea     _cont3(%pc), %a5
    .word   0xEEFA, 0x0004, 0x0000    | bfset (0,%pc){#0:#4}
    bra     _fail
_cont3:
    cmp.l   #3, %d6
    bne     _fail

    | ---- Case 14: BFINS d16(PC) -> ILLEGAL (vec 4) ----
    lea     _cont4(%pc), %a5
    .word   0xEFFA, 0x0004, 0x0000    | bfins %d0,(0,%pc){#0:#4}
    bra     _fail
_cont4:
    cmp.l   #4, %d6
    bne     _fail

    | ---- Case 15: re-run a read after the traps (still alive) ----
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
_bfdat2:
    .long   0x89ABCDEF
    .long   0x11223344
