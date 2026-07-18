| move_w_memind_no_idx_to_memind_pre_idx.s — Task #238 (G1)
|
| MOVE.W mem-indirect-src (no-idx) → mem-indirect-dst (pre-idx).
| Strategy (b) of the G1 crack.
|
|   src EA = ([bd.W=+0x20, A1])             (no-idx, no od)
|     ext1 (src) = 0x0164.  2 ext words.
|   dst EA = ([bd.W=+0x40, A2, D0.W*1], od.W=+0x4)  (pre-idx, bd.W, od.W)
|     ext1 (dst): D/A=0 REG=000 W/L=0 SCALE=00 FF=1 BS=0 IS=0 BD=10 0 I/IS=010
|       = 0_000_0_00_1_0_0_10_0_010 = 0x0122
|     ext2 (dst) = bd.W = 0x0040
|     ext3 (dst) = od.W = 0x0004
|     3 ext words.
|
|   Total = 2 + 3 = 5 ext words.  Fits in predecode window.
|
| Opword: size=11 (.W), dst_reg=010 (A2), dst_mode=110, src_mode=110, src_reg=001
|   = 0011_010_110_110_001 = 0x35B1
|
| Memory layout:
|   A1 = 0x00114000
|   [A1 + 0x20] = 0x00115006   (src inner ptr; src EA)
|   [0x00115006] = 0x????BEEF  (src data — we read .W = 0xBEEF from low half)
|
|   A2 = 0x00114800
|   D0 = 0x10  (sx16 → 0x00000010, scaled *1)
|   inner = (A2 + 0x40 + 0x10) = 0x00114850
|   [inner] = 0x00116000  (dst inner ptr after pre-idx LOAD)
|   [0x00116000 + 0x4] = 0x????  (where word lands; read with .W)
|
|   Word semantics: MOVE.W writes only the low word, leaves upper word.

    .text
    .org 0

_start:
    | Seed src inner ptr
    lea     0x00114020, %a6
    move.l  #0x00115006, (%a6)
    | Seed src data: word at [0x00115006] = 0xBEEF, but the load reads
    | a 16-bit word starting at 0x00115006.  We seed via long: place
    | 0xBEEF at 0x00115006:  use (xxx).L absolute.W and write a long
    | that has 0xBEEF in the high word and 0xCAFE in the low (don't
    | care).  But we want the WORD READ at 0x00115006 → so write a
    | long at 0x00115004 with high=0xDEAD, low=0xBEEF: read at offset
    | +2 gives the low word 0xBEEF.  Easier: write long at 0x00115006:
    |   high word = 0xBEEF, low word = 0xCAFE → word read at 0x00115006
    |   returns 0xBEEF (big-endian, MSB-first).
    lea     0x00115006, %a6
    move.l  #0xBEEFCAFE, (%a6)

    | Seed dst inner ptr: at A2+0x40+0x10 = 0x00114850
    lea     0x00114850, %a6
    move.l  #0x00116000, (%a6)
    | Final dst at 0x00116004, prime with marker
    lea     0x00116004, %a6
    move.l  #0x11112222, (%a6)

    move.l  #0x00000010, %d0      | sx16 → 0x10, scaled *1 = 0x10
    lea     0x00114000, %a1
    lea     0x00114800, %a2

    | MOVE.W ([+0x20,A1]), ([+0x40,A2,D0.W*1], +0x4)
    .word   0x35B1, 0x0164, 0x0020, 0x0122, 0x0040, 0x0004

    | Verify the WORD at 0x00116004 is 0xBEEF (upper word of the
    | pre-existing long should remain 0x1111, low word now 0xBEEF →
    | seeded 0x11112222, after .W store low word = 0xBEEF, upper word
    | preserved 0x1111).  PRM big-endian: WORD store at 0x00116004
    | writes bytes 0x00116004..0x00116005 — that is the HIGH word of
    | the long stored at 0x00116004.  So expected long = 0xBEEF2222.
    lea     0x00116004, %a0
    move.l  (%a0), %d1
    cmp.l   #0xBEEF2222, %d1
    bne     _fail

    | Pass
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d7
    move.l  %d7, (%a0)
_halt_f:
    bra     _halt_f
