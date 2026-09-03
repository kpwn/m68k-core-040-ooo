| tas_memind.s — fuzz-to-zero-divergences campaign, cluster B (2026-09-03)
|
| Directed test for TAS.B with a full-format MEMORY-INDIRECT destination.
|
| ROOT CAUSE this covers: routing a MEMINDIRECT EA into the µcode engine requires
| the instruction family to be named in DecodeStage's three parallel gates
| (slot0IsMemInd / slot1IsMemIndEarly / ucIsMemInd, all now derived from the
| shared miSingleEaFamily predicate).  DecOp.TAS was absent from all three, so
| TAS <mem-indirect> fell through to the ordinary non-microcoded fast path, whose
| EA machinery cannot walk a pointer chain -> GARBAGE address -> access fault ->
| format-$0 frame -> vector through an uninitialised vector -> wild PC.
| Fuzz seeds 3, 4, 21, 22, 41, 74, 103, 126 all reduced to this shape.
|
| This is the 4th instance of that class (cf. tasks #144/#145 MOVE/ADDA-src,
| #150 add_l_dn_memind_dst + b2_addq_subq_memind_null_od, #152 static bit-op).
| Every other family in those gates already has a directed memind test; TAS,
| uniquely, had none -- which is exactly why six weeks of regression runs never
| caught this and the fuzzer did.
|
| TAS semantics: read the byte at <ea>, set N/Z from that ORIGINAL byte
| (V=0, C=0), then unconditionally set bit 7 of it and write it back.
|
| Encoding: TAS <ea> = 0100 1010 11 mmm rrr.  mode=110 (full-format indexed),
| reg=100 (A4) -> 0x4AF4.  The extension word 0x0164 is the SAME proven
| no-index / word-bd / memory-indirect shape clr_l_memind.s uses:
|   bit8=1 (full format), BS=0 (base reg used), IS=1 (index suppressed),
|   BD SIZE=10 (word base displacement), I/IS=100 (memory indirect).
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | ── Case 1: TAS.B ([32,A4]) over a ZERO byte ────────────────────────────
    |   [A4+32] holds the pointer; the target byte starts 0x00.
    |   Expect: Z=1 from the original byte, and the byte becomes 0x80.
    lea     0x00115200, %a4
    lea     0x00115220, %a0
    move.l  #0x00115400, (%a0)       | [A4+32] = pointer -> 0x00115400
    lea     0x00115400, %a0
    move.l  #0x00BBBBBB, (%a0)       | target byte = 0x00, trailing bytes witness over-wide writes
    .word   0x4AF4, 0x0164, 0x0020   | TAS.B ([32,A4])
    beq     _tas1_zok                | TAS must have set Z (original byte was 0)
    move.l  #0xDEAD0011, %d7
    bra     _fail
_tas1_zok:
    lea     0x00115400, %a0
    move.l  (%a0), %d0
    cmp.l   #0x80BBBBBB, %d0         | byte 0 := 0x80; bytes 1..3 untouched
    beq     _case2
    move.l  #0xDEAD0012, %d7
    bra     _fail

    | ── Case 2: TAS.B ([32,A4]) over a NON-ZERO, bit7-clear byte ────────────
    |   0x41 -> Z=0, N=0, and the byte becomes 0xC1 (bit 7 set, rest kept).
_case2:
    lea     0x00115400, %a0
    move.l  #0x41BBBBBB, (%a0)
    .word   0x4AF4, 0x0164, 0x0020   | TAS.B ([32,A4])
    bne     _tas2_zok                | original byte 0x41 is non-zero -> Z=0
    move.l  #0xDEAD0021, %d7
    bra     _fail
_tas2_zok:
    lea     0x00115400, %a0
    move.l  (%a0), %d0
    cmp.l   #0xC1BBBBBB, %d0         | 0x41 | 0x80 = 0xC1; bytes 1..3 untouched
    beq     _case3
    move.l  #0xDEAD0022, %d7
    bra     _fail

    | ── Case 3: the pointer chain is actually FOLLOWED ──────────────────────
    |   Re-point [A4+32] at a DIFFERENT target and confirm TAS lands there and
    |   NOT at the old one.  A fast-path fall-through that ignores the
    |   indirection would keep hitting a stale/garbage address; this case makes
    |   that observable instead of accidentally passing.
_case3:
    lea     0x00115220, %a0
    move.l  #0x00115408, (%a0)       | [A4+32] now points at 0x00115408
    lea     0x00115400, %a0
    move.l  #0x11BBBBBB, (%a0)       | OLD target: must stay untouched
    lea     0x00115408, %a0
    move.l  #0x22BBBBBB, (%a0)       | NEW target
    .word   0x4AF4, 0x0164, 0x0020   | TAS.B ([32,A4])
    lea     0x00115408, %a0
    move.l  (%a0), %d0
    cmp.l   #0xA2BBBBBB, %d0         | 0x22 | 0x80 = 0xA2  -> landed at the NEW target
    beq     _case3_old
    move.l  #0xDEAD0031, %d7
    bra     _fail
_case3_old:
    lea     0x00115400, %a0
    move.l  (%a0), %d0
    cmp.l   #0x11BBBBBB, %d0         | OLD target must be byte-identical
    beq     _pass
    move.l  #0xDEAD0032, %d7
    bra     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
