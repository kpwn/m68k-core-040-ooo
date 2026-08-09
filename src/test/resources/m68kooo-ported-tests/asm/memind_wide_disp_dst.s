| memind_wide_disp_dst.s -- task #242's DESTINATION-side + boundary cases.
|
| memind_wide_disp_gap.s covers the 18 wide-displacement cells on the
| SOURCE side (MOVEA.L <memind>,An).  Three things it does NOT reach, and
| all three are corners this landing had to get right:
|
|   Stage 1-3  wide-displacement memind DESTINATIONS.  The dst-side EA
|              decoder is fed through a completely different ext-word
|              slide (v2_dst_ext_shift) than the src side, so src-side
|              green says nothing about it.
|
|   Stage 4    the v2_dst_ext3 ALIASING FIX.  The old dst ext ladder
|              saturated at ext5, so once the source consumed 3+
|              half-words the destination's THIRD ext word silently read
|              ext5 again instead of ext6.  Stage 4 is a full-format
|              bd.L source feeding a full-format bd.L destination: 7
|              words / 14 bytes, the longest instruction this decoder
|              can encode, whose dst bd LOW half lives in ext6.  With the
|              old ladder the destination address comes out as
|              0x00360000 instead of 0x00362008 -- a wrong-address STORE,
|              silent, no exception.  The guard long at 0x00360000
|              catches exactly that.
|
|   Stage 5    the FAIL-SAFE boundary.  len_bytes is 4 bits, so opword +
|              6 extension half-words (14 bytes) is the longest encodable
|              instruction; decode.v's v2_ext_window_ok blocks anything
|              wider.  A 5-half-word memind source paired with an
|              (xxx).L destination needs 7 and must still trap vector 4
|              rather than decode with a truncated length.  If a future
|              landing widens len_bytes, this stage is the one to update.
|
| Every exception vector 2..11 is trapped into _exc, which reports
| 0xEEEE0000 | <vector offset> so a surprise fault names itself.

    .text
    .org 0

_start:
    lea     _exc, %a1
    move.l  %a1, 0x08
    move.l  %a1, 0x0C
    move.l  %a1, 0x10
    move.l  %a1, 0x14
    move.l  %a1, 0x18
    move.l  %a1, 0x1C
    move.l  %a1, 0x20
    move.l  %a1, 0x24
    move.l  %a1, 0x28
    move.l  %a1, 0x2C

    move.l  #0x00300000, %a3
    move.l  #0x00000020, %d1

| ─────────────────────────────────────────────────────────────────────
| Stage 1 -- MOVE.L D0, ([bd.L,A3], od.L)      no-index (IS=1)
|   ext1 = 0x0173 : full fmt, BS=0, IS=1, BD SIZE=11 (long),
|                   I/IS=011 (memory indirect, LONG outer displacement)
|   frame = op + ext1 + bd.hi + bd.lo + od.hi + od.lo = 6 words / 12 B
| ─────────────────────────────────────────────────────────────────────
    move.l  #0x00320000, %a6
    move.l  %a6, 0x00301000          | inner pointer
    move.l  #0x00000000, 0x00320040  | pre-clear the target
    move.l  #0xCAFE0001, %d0
    .short  0x2780, 0x0173, 0x0000, 0x1000, 0x0000, 0x0040
    move.l  0x00320040, %d2
    cmp.l   #0xCAFE0001, %d2
    bne     _f1

| ─────────────────────────────────────────────────────────────────────
| Stage 2 -- MOVE.L D0, ([bd.L,A3,D1.L*2], od.W)   pre-indexed
|   ext1 = 0x1B32 : D1 index, W/L=1, SCALE=x2, BS=0, IS=0,
|                   BD SIZE=11 (long), I/IS=010 (pre-indexed, WORD od)
|   frame = op + ext1 + bd.hi + bd.lo + od = 5 words / 10 B
|   inner = A3 + 0x00002000 + D1*2 (0x40) = 0x00302040
| ─────────────────────────────────────────────────────────────────────
    move.l  #0x00330000, %a6
    move.l  %a6, 0x00302040
    move.l  #0x00000000, 0x00330200
    move.l  #0xCAFE0002, %d0
    .short  0x2780, 0x1B32, 0x0000, 0x2000, 0x0200
    move.l  0x00330200, %d2
    cmp.l   #0xCAFE0002, %d2
    bne     _f2

| ─────────────────────────────────────────────────────────────────────
| Stage 3 -- MOVE.L D0, ([bd.W,A3], D1.L*4, od.L)  post-indexed
|   ext1 = 0x1D27 : D1 index, W/L=1, SCALE=x4, BS=0, IS=0,
|                   BD SIZE=10 (word), I/IS=111 (post-indexed, LONG od)
|   frame = op + ext1 + bd + od.hi + od.lo = 5 words / 10 B
|   inner = A3 + 0x3000 = 0x00303000; final = *inner + D1*4 + od
| ─────────────────────────────────────────────────────────────────────
    move.l  #0x00340000, %a6
    move.l  %a6, 0x00303000
    move.l  #0x00000000, 0x00340180
    move.l  #0xCAFE0003, %d0
    .short  0x2780, 0x1D27, 0x3000, 0x0000, 0x0100
    move.l  0x00340180, %d2
    cmp.l   #0xCAFE0003, %d2
    bne     _f3

| ─────────────────────────────────────────────────────────────────────
| Stage 4 -- the v2_dst_ext3 aliasing fix.
|   MOVE.L ([bd.L,A4] no-memind), ([bd.L,A5] no-memind)
|   both sides full format, IS=1, BD SIZE=11, I/IS=000
|   ext = 0x0170 on each side
|   frame = op + s_ext1 + s_bd.hi + s_bd.lo + d_ext1 + d_bd.hi + d_bd.lo
|         = 7 words / 14 bytes  (v2_ext_total_words == 6, the cap)
|   src EA = A4 + 0x00001004 = 0x00351004
|   dst EA = A5 + 0x00002008 = 0x00362008
|   With the pre-#242 ladder the dst bd read {ext5,ext5} = 0x00000000 and
|   the store landed on 0x00360000 -- which is why that address carries a
|   guard value here.
| ─────────────────────────────────────────────────────────────────────
    move.l  #0x00350000, %a4
    move.l  #0x00360000, %a5
    move.l  #0xCAFE0004, 0x00351004
    move.l  #0x5AFE0000, 0x00360000  | guard: the OLD wrong destination
    move.l  #0x00000000, 0x00362008
    .short  0x2BB4, 0x0170, 0x0000, 0x1004, 0x0170, 0x0000, 0x2008
    move.l  0x00362008, %d2
    cmp.l   #0xCAFE0004, %d2
    bne     _f4
    move.l  0x00360000, %d2
    cmp.l   #0x5AFE0000, %d2         | guard must be UNTOUCHED
    bne     _f4g

| ─────────────────────────────────────────────────────────────────────
| Stage 5 -- the EXACT window cap, memind SOURCE + a memory destination.
|   MOVE.L ([bd.L,A3], od.L), (d16,A5)
|   src ext = ext1 + bd.hi + bd.lo + od.hi + od.lo = 5
|   dst ext = d16                                  = 1
|   v2_ext_total_words == 6 == the cap; frame = 7 words / 14 bytes.
|   One half-word wider -- e.g. the same source with an (xxx).L
|   destination -- needs 7, exceeds the 4-bit len_bytes, and is
|   deliberately left reporting unsupported so it traps vector 4 rather
|   than decoding with a truncated length.  That shape is NOT executed
|   here because a correct vector-4 trap is terminal for this harness;
|   it is asserted by construction in decode.v's v2_ext_window_ok, and
|   this stage is the positive control that the cap itself is not off by
|   one in the blocking direction.
|
|   inner = A3 + 0x00004000 = 0x00304000
|   src EA = *inner + od = 0x00380000 + 0x40 = 0x00380040
|   dst EA = A5 + 0x0100 = 0x00360100
| ─────────────────────────────────────────────────────────────────────
    move.l  #0x00380000, %a6
    move.l  %a6, 0x00304000
    move.l  #0xCAFE0005, 0x00380040
    move.l  #0x00360000, %a5
    move.l  #0x00000000, 0x00360100
    .short  0x2B73, 0x0173, 0x0000, 0x4000, 0x0000, 0x0040, 0x0100
    move.l  0x00360100, %d2
    cmp.l   #0xCAFE0005, %d2
    bne     _f5

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_f1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_f2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_f3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_f4:
    move.l  #0xDEAD0004, %d7
    bra     _fail
_f4g:
    move.l  #0xDEAD4747, %d7
    bra     _fail
_f5:
    move.l  #0xDEAD0005, %d7
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail

_exc:
    moveq   #0, %d7
    move.w  6(%sp), %d7
    andi.l  #0x00000FFF, %d7
    ori.l   #0xEEEE0000, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_exc:
    bra     _halt_exc
