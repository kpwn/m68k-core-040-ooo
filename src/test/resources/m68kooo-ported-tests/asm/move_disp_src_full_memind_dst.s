| move_disp_src_full_memind_dst.s -- MOVE.{B,W,L} (d16,An)/(An) -> ([bd.W,An],od)
|
| THE MISSING ROW.  MOVE's memory-indirect DESTINATION support is
| whitelisted by SOURCE FORM, and every neighbour was implemented while
| this one was not:
|     reg / immediate  task #202   move_reg_imm_src_memind_dst_supported
|     absolute         task #212   move_abs_src_memind_dst_supported
|     memind           task #238   move_memind_src_memind_dst_supported
|     (d16,An)         task #241   <- this file
|
| Caught on real hardware during the System 7.5.3 extension load
| (bitstream 0x08A6E284): vector 4 (illegal instruction) at logical PC
| 0x800A1DCE.  Bit 31 there is a Mac flag bit, not address -- the real
| code sits at 0x000A1DCE and reads:
|
|     000A1DCE:  1bad 004a 8161 0010     MOVE.B (74,A5),([16,A5])
|
| ext1 = 0x8161 decodes as full format, BS=0 (base register used),
| IS=1 (index suppressed), BD SIZE=10 (word), I/IS=001 (memory
| indirect, NULL outer displacement) -- i.e. the 4-phase no-index
| memind shape, dst_is_indexed=0.
|
| Case 1 below is that instruction verbatim, same opcode word and same
| A5-for-both-operands register choice, so this test fails if the exact
| ROM encoding ever stops decoding.  The later cases widen around it:
| W/L sizes, a non-null outer displacement, distinct src/dst base
| registers, and a plain (An) source (displacement 0, which takes the
| same crack path with src_displacement == 0).
|
| Flag checks matter here: ph0 is the LOAD that carries NZVC, so a crack
| that dropped flags_wr would still store the right bytes and pass a
| data-only check.  Each case asserts the condition codes too.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.
| FAIL sentinels: 0xDEAD0241 + case number.

    .text
    .org 0

    .equ SENT, 0xFFFF0000

_start:
    | ── Case 1: the ROM instruction verbatim ────────────────────────
    |   MOVE.B (74,A5),([16,A5])   = 1bad 004a 8161 0010
    | A5 = 0x00011000
    |   source byte at A5+74      = 0x0001104A
    |   inner pointer at A5+16    = 0x00011010 -> 0x00011200
    |   stored byte lands at        0x00011200 (od = null)
    move.l  #0x00011000, %a5
    move.l  #0x00011200, 0x00011010     | inner pointer
    move.b  #0x5A, 0x0001104A           | source byte (positive, non-zero)
    move.l  #0xAABBCCDD, 0x00011200     | destination pre-seed
    .word   0x1bad, 0x004a, 0x8161, 0x0010
    bmi     _f1                          | 0x5A -> N=0
    beq     _f1                          | Z=0
    bvs     _f1
    bcs     _f1
    move.l  0x00011200, %d7
    cmp.l   #0x5ABBCCDD, %d7             | only the high byte replaced
    bne     _f1

    | ── Case 2: MOVE.W (d16,A1) -> ([bd.W,A0]), word outer displacement
    |   opcode 0011 000 110 101 001 = 0x31A9
    |   ext1 = 0x8162 -> BS=0, IS=1, BD=word, I/IS=010 (word od)
    move.l  #0x00012000, %a1
    move.l  #0x00012400, %a0
    move.w  #0x8001, 0x00012040          | negative source word
    move.l  #0x00012600, 0x00012410      | inner pointer at A0+0x10
    move.l  #0x11223344, 0x00012604      | dst pre-seed at pointer+4
    .word   0x31a9, 0x0040, 0x8162, 0x0010, 0x0004
    bpl     _f2                          | 0x8001 -> N=1
    beq     _f2
    bvs     _f2
    bcs     _f2
    move.l  0x00012604, %d7
    cmp.l   #0x80013344, %d7
    bne     _f2

    | ── Case 3: MOVE.L (d16,A2) -> ([bd.W,A3]), null outer displacement
    |   opcode 0010 011 110 101 010 = 0x27AA
    move.l  #0x00013000, %a2
    move.l  #0x00013400, %a3
    move.l  #0xFF00AA55, 0x00013080      | negative source long
    move.l  #0x00013800, 0x00013420      | inner pointer at A3+0x20
    move.l  #0x00000000, 0x00013800
    .word   0x27aa, 0x0080, 0x8161, 0x0020
    bpl     _f3
    beq     _f3
    bvs     _f3
    bcs     _f3
    move.l  0x00013800, %d7
    cmp.l   #0xFF00AA55, %d7
    bne     _f3

    | ── Case 4: plain (An) source -- displacement 0, same crack path
    |   MOVE.L (A4) -> ([bd.W,A6])
    |   opcode 0010 110 110 010 100 = 0x2D94
    move.l  #0x00014000, %a4
    move.l  #0x00014400, %a6
    move.l  #0x00000000, 0x00014000      | source long == 0 -> Z=1
    move.l  #0x00014800, 0x00014410      | inner pointer at A6+0x10
    move.l  #0x55667788, 0x00014800
    .word   0x2d94, 0x8161, 0x0010
    bne     _f4                          | zero source -> Z=1
    bmi     _f4
    bvs     _f4
    bcs     _f4
    move.l  0x00014800, %d7
    cmp.l   #0x00000000, %d7
    bne     _f4

    | ── PASS ────────────────────────────────────────────────────────
    lea     SENT, %a1
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

_f1:
    lea     SENT, %a1
    move.l  #0xDEAD0241, %d7
    move.l  %d7, (%a1)
    bra     _f1
_f2:
    lea     SENT, %a1
    move.l  #0xDEAD0242, %d7
    move.l  %d7, (%a1)
    bra     _f2
_f3:
    lea     SENT, %a1
    move.l  #0xDEAD0243, %d7
    move.l  %d7, (%a1)
    bra     _f3
_f4:
    lea     SENT, %a1
    move.l  #0xDEAD0244, %d7
    move.l  %d7, (%a1)
    bra     _f4
