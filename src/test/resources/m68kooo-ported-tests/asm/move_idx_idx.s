| move_idx_idx.s — MOVE with indexed source AND indexed destination.
|
| Q700 ROM 0x4083c1e0:  move.b (0xa,A2,D1.w*8),(0,A3,D0.w)  → vec-4.
| The V2 move-family assembler handles a brief-indexed EA on the src
| OR the dst side, but not both at once.  (d8,An,Xn) on both sides is
| a valid 68040 MOVE EA pair.
|
| 2026-07-30 widening (BUG_seed_999_1391_move_idx_idx_disp0_scale1_long):
| the original three cases below covered MOVE.B src-scale-8/dst-scale-1,
| MOVE.B scale-1/scale-1 and MOVE.W scale-2/scale-4 — but NEVER MOVE.L,
| and NEVER displacement 0 on both sides at once.  Those were exactly the
| two axes fuzz seeds 999/1391 diverged on.  The `DII` / `DIIS` matrix
| below sweeps size x src-scale x dst-scale x displacement (including 0
| and negative) x index width (.w/.l) x index sign x misalignment, plus
| the shared-index-register and shared-base-register shapes the fuzz
| seed actually used (MOVE.L (0,A1,D2.W*1),(0,A0,D2.W*1)).
|
| Each case verifies three things, not just "the value arrived":
|   * the destination longword/word/byte holds the payload  -> the STORE
|     landed at all, at the right address;
|   * the byte immediately BELOW the destination is untouched;
|   * the byte immediately ABOVE the destination is untouched
|     -> catches an over-wide store as well as a dropped one.
| The source window is pre-poisoned with 0x99 and the destination window
| with 0x77, so a read from the wrong address or a write to the wrong
| address both fail loudly instead of silently reading back a stale
| copy of the right value.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0004  vec-4 illegal-instruction trap
|   0xDEAD00B1  MOVE.B (d8,An,Dn.w*8) -> (d8,An,Dn.w*1) wrong
|   0xDEAD00B2  MOVE.B (d8,An,Dn.w*1) -> (d8,An,Dn.w*1) wrong
|   0xDEAD00B3  MOVE.W (d8,An,Dn.w*2) -> (d8,An,Dn.w*4) wrong
|   0xDEAD00B4  same-An-both-sides MOVE.L wrong
|   0xDEAD00B5  An-as-index (.l*1) MOVE.L wrong
|   0xDEAD00B6  An-as-index (.w*8) MOVE.L wrong
|   0xDEADnnn0  matrix case nnn: destination payload wrong / STORE dropped
|   0xDEADnnn1  matrix case nnn: byte below the destination clobbered
|   0xDEADnnn2  matrix case nnn: byte above the destination clobbered

    .equ    SRC_BASE, 0x00021000       | 256 B poisoned with 0x99
    .equ    DST_BASE, 0x00022000       | 256 B poisoned with 0x77

| ── DII: independent src/dst index registers (D1 src, D0 dst) ────────
| args: size nb sdisp ssz ssc sidx ddisp dsz dsc didx val tag
|   size = b|w|l, nb = 1|2|4 (bytes moved)
|   sdisp/ddisp = brief 8-bit displacement, ssc/dsc = scale 1|2|4|8
|   ssz/dsz = w|l index width, sidx/didx = index-register value
| Requires  8 <= sdisp + sidx*ssc <= 0xE0  and likewise on the dst side,
| so the poisoned windows and the guard bytes stay in range.
    .macro  DII size, nb, sdisp, ssz, ssc, sidx, ddisp, dsz, dsc, didx, val, tag
    move.l  #SRC_BASE, %a2
    move.l  #DST_BASE, %a3
    move.l  #\sidx, %d1
    move.l  #\didx, %d0
    | Re-poison a 16-byte window around each EA so neighbouring cases
    | cannot leave a stale payload that masks a dropped store.
    lea     SRC_BASE + \sdisp + \sidx * \ssc, %a4
    move.l  #0x99999999, -8(%a4)
    move.l  #0x99999999, -4(%a4)
    move.l  #0x99999999, (%a4)
    move.l  #0x99999999, 4(%a4)
    lea     DST_BASE + \ddisp + \didx * \dsc, %a5
    move.l  #0x77777777, -8(%a5)
    move.l  #0x77777777, -4(%a5)
    move.l  #0x77777777, (%a5)
    move.l  #0x77777777, 4(%a5)
    move.\size  #\val, (%a4)           | payload at the exact src EA
    move.\size  (\sdisp,%a2,%d1.\ssz*\ssc), (\ddisp,%a3,%d0.\dsz*\dsc)
    move.\size  (%a5), %d2
    cmp.\size   #\val, %d2
    beq     1f
    move.l  #\tag + 0, %d7
    bra     _fail
1:  move.b  -1(%a5), %d2
    cmp.b   #0x77, %d2
    beq     2f
    move.l  #\tag + 1, %d7
    bra     _fail
2:  move.b  \nb(%a5), %d2
    cmp.b   #0x77, %d2
    beq     3f
    move.l  #\tag + 2, %d7
    bra     _fail
3:
    .endm

| ── DIIS: ONE index register shared by src and dst (the fuzz shape) ──
| MOVE.L (0,A1,D2.W*1),(0,A0,D2.W*1) reuses D2 on both sides; the crack
| stages src and dst EAs through TMP1/TMP2, so a shared index register
| is a distinct rename/scratch-reuse corner from two different ones.
| args: size nb sdisp ssz ssc ddisp dsz dsc idx val tag
    .macro  DIIS size, nb, sdisp, ssz, ssc, ddisp, dsz, dsc, idx, val, tag
    move.l  #SRC_BASE, %a2
    move.l  #DST_BASE, %a3
    move.l  #\idx, %d2
    lea     SRC_BASE + \sdisp + \idx * \ssc, %a4
    move.l  #0x99999999, -8(%a4)
    move.l  #0x99999999, -4(%a4)
    move.l  #0x99999999, (%a4)
    move.l  #0x99999999, 4(%a4)
    lea     DST_BASE + \ddisp + \idx * \dsc, %a5
    move.l  #0x77777777, -8(%a5)
    move.l  #0x77777777, -4(%a5)
    move.l  #0x77777777, (%a5)
    move.l  #0x77777777, 4(%a5)
    move.\size  #\val, (%a4)
    move.\size  (\sdisp,%a2,%d2.\ssz*\ssc), (\ddisp,%a3,%d2.\dsz*\dsc)
    move.\size  (%a5), %d3
    cmp.\size   #\val, %d3
    beq     1f
    move.l  #\tag + 0, %d7
    bra     _fail
1:  move.b  -1(%a5), %d3
    cmp.b   #0x77, %d3
    beq     2f
    move.l  #\tag + 1, %d7
    bra     _fail
2:  move.b  \nb(%a5), %d3
    cmp.b   #0x77, %d3
    beq     3f
    move.l  #\tag + 2, %d7
    bra     _fail
3:
    .endm

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010

    | ── Poison both windows (256 B each) ────────────────────────────
    lea     SRC_BASE, %a0
    move.w  #63, %d3
_poison_src:
    move.l  #0x99999999, (%a0)+
    dbra    %d3, _poison_src
    lea     DST_BASE, %a0
    move.w  #63, %d3
_poison_dst:
    move.l  #0x77777777, (%a0)+
    dbra    %d3, _poison_dst

    | ── Original three cases (kept verbatim — never delete coverage) ─
    | MOVE.B (0xa,A2,D1.w*8),(0,A3,D0.w*1) — exact ROM shape
    move.l  #0x00021000, %a2
    move.l  #0x00022000, %a3
    moveq   #1, %d1
    moveq   #3, %d0
    move.b  #0x5A, 0x00021012           | src EA = A2+0xa+1*8 = 0x21012
    move.b  (0xa,%a2,%d1.w*8), (0,%a3,%d0.w)   | dst EA = A3+0+3 = 0x22003
    move.b  0x00022003, %d2
    cmp.b   #0x5A, %d2
    bne     _f1

    | MOVE.B (8,A2,D1.w*1),(4,A3,D0.w*1) — scale 1 both sides
    moveq   #5, %d1
    moveq   #6, %d0
    move.b  #0xA5, 0x0002100D           | src EA = A2+8+5 = 0x2100D
    move.b  (8,%a2,%d1.w), (4,%a3,%d0.w)        | dst EA = A3+4+6 = 0x2200A
    move.b  0x0002200A, %d2
    cmp.b   #0xA5, %d2
    bne     _f2

    | MOVE.W (8,A2,D1.w*2),(4,A3,D0.w*4) — word, mixed scales
    moveq   #2, %d1
    moveq   #2, %d0
    move.w  #0x1234, 0x0002100C         | src EA = A2+8+2*2 = 0x2100C
    move.w  (8,%a2,%d1.w*2), (4,%a3,%d0.w*4)   | dst EA = A3+4+2*4 = 0x2200C
    move.w  0x0002200C, %d2
    cmp.w   #0x1234, %d2
    bne     _f3

    | ══ LONG matrix — the size axis that had ZERO coverage ══════════
    | disp 0 on BOTH sides, scale 1 on BOTH sides: the exact corner
    | fuzz seeds 999/1391 diverged on.
    DII l, 4,   0, w, 1, 16,   0, w, 1, 32, 0x3926A67D, 0xDEAD0100
    DII l, 4,   0, l, 1, 20,   0, l, 1, 36, 0xDEADBEEF, 0xDEAD0110
    | disp 0 / scale 1 with MISALIGNED effective addresses (2 mod 4 and
    | 1 mod 4) — a LONG access that splits in the LSU.
    DII l, 4,   0, w, 1, 10,   0, w, 1, 14, 0x12345678, 0xDEAD0120
    DII l, 4,   0, w, 1,  9,   0, w, 1, 13, 0xA5A5A5A5, 0xDEAD0130
    | one side 0, the other non-zero
    DII l, 4,   4, w, 1, 16,   0, w, 1, 40, 0x0F0F0F0F, 0xDEAD0140
    DII l, 4,   0, w, 1, 24,   4, w, 1, 44, 0xF0F0F0F0, 0xDEAD0150
    | scale sweep with disp 0 (no doubling / 1 / 2 / 3 doublings)
    DII l, 4,   0, w, 2, 16,   0, w, 2, 20, 0x11223344, 0xDEAD0160
    DII l, 4,   0, w, 4,  8,   0, w, 4, 12, 0x55667788, 0xDEAD0170
    DII l, 4,   0, w, 8,  4,   0, w, 8,  6, 0x99AABBCC, 0xDEAD0180
    | asymmetric scales with disp 0 — src doublings != dst doublings,
    | which is what shifts the dst phase numbering in the crack.
    DII l, 4,   0, w, 1, 16,   0, w, 8,  5, 0x01020304, 0xDEAD0190
    DII l, 4,   0, w, 8,  3,   0, w, 1, 48, 0x05060708, 0xDEAD01A0
    DII l, 4,   0, l, 2, 12,   0, l, 4, 10, 0x7F7F7F7F, 0xDEAD01B0
    | negative displacement both sides
    DII l, 4,  -8, w, 1, 32,  -8, w, 1, 48, 0xCAFEBABE, 0xDEAD01C0
    | negative index (word index sign-extends through ALU_EXT)
    DII l, 4,  32, w, 1, -8,  32, w, 1, -4, 0x13579BDF, 0xDEAD01D0
    DII l, 4,  64, w, 2, -8,  64, w, 4, -6, 0x2468ACE0, 0xDEAD01E0
    | full 32-bit long index with disp 0
    DII l, 4,   0, l, 1, 100,  0, l, 1, 104, 0xFFFFFFFF, 0xDEAD01F0

    | ══ WORD matrix ════════════════════════════════════════════════
    DII w, 2,   0, w, 1, 18,   0, w, 1, 22, 0x1234, 0xDEAD0200
    DII w, 2,   0, w, 1, 17,   0, w, 1, 23, 0xBEEF, 0xDEAD0210
    DII w, 2,   4, w, 2, 16,   0, w, 4,  8, 0xC0DE, 0xDEAD0220
    DII w, 2,   0, w, 8,  2,   8, w, 1, 36, 0x0FF0, 0xDEAD0230
    DII w, 2,  40, w, 2, -4,  40, w, 4, -2, 0x2468, 0xDEAD0240

    | ══ BYTE matrix (widened beyond the original two shapes) ═══════
    DII b, 1,   0, w, 1, 19,   0, w, 1, 27, 0x5A, 0xDEAD0300
    DII b, 1,   0, w, 8,  2,   0, w, 1, 33, 0xA5, 0xDEAD0310
    DII b, 1,   8, w, 1, 20,   4, w, 8,  3, 0x7E, 0xDEAD0320
    DII b, 1,   0, l, 4,  7,   0, l, 2, 17, 0x3C, 0xDEAD0330

    | ══ Shared index register on both sides — the fuzz-seed shape ══
    | MOVE.L (0,A1,D2.W*1),(0,A0,D2.W*1), disp 0 / scale 1 / LONG.
    DIIS l, 4,  0, w, 1,  0, w, 1,  4, 0x3926A67D, 0xDEAD0400
    DIIS l, 4,  0, w, 1,  0, w, 1, 12, 0xFFFFFFFF, 0xDEAD0410
    DIIS l, 4,  0, l, 1,  0, l, 1, 24, 0x24681357, 0xDEAD0420
    DIIS l, 4,  0, w, 2,  0, w, 4,  8, 0x0BADF00D, 0xDEAD0430
    DIIS w, 2,  0, w, 1,  0, w, 1, 16, 0x8001,     0xDEAD0440
    DIIS b, 1,  0, w, 1,  0, w, 1, 20, 0xE7,       0xDEAD0450
    DIIS l, 4,  0, w, 1,  8, w, 1, 32, 0x76543210, 0xDEAD0460

    | ══ Same base register on BOTH sides, disp 0, scale 1, LONG ════
    | src and dst differ only in the index value — the crack rebuilds
    | TMP1 from the same An twice.
    move.l  #DST_BASE, %a3
    moveq   #16, %d1
    moveq   #32, %d0
    move.l  #0x0BADCAFE, 0x00022010
    move.l  #0x77777777, 0x00022020
    move.l  (0,%a3,%d1.w*1), (0,%a3,%d0.w*1)
    move.l  0x00022020, %d2
    cmp.l   #0x0BADCAFE, %d2
    bne     _f4

    | ══ Address register as the index (Xn D/A bit = 1), disp 0 ═════
    move.l  #SRC_BASE, %a2
    move.l  #DST_BASE, %a3
    move.l  #40, %a1
    move.l  #0x5EEDF00D, 0x00021028
    move.l  #0x77777777, 0x00022028
    move.l  (0,%a2,%a1.l*1), (0,%a3,%a1.l*1)
    move.l  0x00022028, %d2
    cmp.l   #0x5EEDF00D, %d2
    bne     _f5

    move.l  #4, %a1
    move.l  #0x1DEA0BED, 0x00021020
    move.l  #0x77777777, 0x00022020
    move.l  (0,%a2,%a1.w*8), (0,%a3,%a1.w*8)   | 4*8 = 32 both sides
    move.l  0x00022020, %d2
    cmp.l   #0x1DEA0BED, %d2
    bne     _f6

    | PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_hfail:
    bra     _hfail

_f1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD00B1, %d0
    move.l  %d0, (%a0)
_h1:
    bra     _h1

_f2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD00B2, %d0
    move.l  %d0, (%a0)
_h2:
    bra     _h2

_f3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD00B3, %d0
    move.l  %d0, (%a0)
_h3:
    bra     _h3

_f4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD00B4, %d0
    move.l  %d0, (%a0)
_h4:
    bra     _h4

_f5:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD00B5, %d0
    move.l  %d0, (%a0)
_h5:
    bra     _h5

_f6:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD00B6, %d0
    move.l  %d0, (%a0)
_h6:
    bra     _h6

_illegal:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_h7:
    bra     _h7
