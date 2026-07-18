| fpu_ea_pcrel_disp_base.s -- FPU EA-source (d16,PC) / (d8,PC,Xn) base fix.
|
| Code review (this session) found the FPU EA-source decode class
| (decode_1111.vh's `fpu_ea_src` block, added by the "EA-source FPU
| decode matrix" landing) computed the PC-relative EA base as
| pd_pc+2+disp instead of pd_pc+4+disp for both mode 7/reg 2
| ((d16,PC)) and mode 7/reg 3 ((d8,PC,Xn) brief).
|
| Why +4 and not +2: per Motorola's addressing-mode rule, the "PC" used
| in (d16,PC)/(d8,PC,Xn) is the address of the extension word ITSELF.
| For a plain single-extension-word instruction (opword + disp16, no
| other extension words) that address is pd_pc+2 -- see this repo's
| movem_pc_disp.s / movea_w_pcrel_d16.s, and MOVEC/MOVEM non-FPU cracks.
| But every F-line FPU op in the `fpu_ea_src` class carries an extra
| FPU opmode extension word (ext1) ahead of the address extension word
| (ext2), so ext2 -- the actual disp16 / brief-index word -- sits at
| pd_pc+4 (opword + ext1), not pd_pc+2.  This repo's OWN MOVEM (d16,PC)
| crack (decode_uop_assemble.v, identical opword+ext1(reg mask)+ext2
| shape) already gets this right: see its comment "(d16,PC) loads: ...
| pd_pc + 4 (past opword+ext1) + d16".
|
| Cross-checked against Musashi (tb/models/musashi/m68kcpu.h
| m68ki_get_ea_pcdi()/m68ki_get_ea_ix()): both capture `old_pc =
| REG_PC` (or pass REG_PC into m68ki_get_ea_ix) BEFORE reading the
| address extension word.  By the time an FPU op (m68kfpu.c) calls
| EA_PCDI_32()/EA_PCIX_32(), REG_PC has already been advanced past
| both the opword and the ext1 opmode word read earlier in
| fpgen_rm_reg(), so REG_PC == pd_pc+4 exactly at that call -- NOT
| pd_pc+2.
|
| Test technique: each sub-test places a 2-byte "poison" filler word
| immediately before its real 4-byte payload.  The pre-fix (pd_pc+2)
| EA reads exactly 2 bytes early, landing on the poison, and produces
| a mismatched 32-bit pattern (poison bytes + only the top half of the
| real payload, big-endian) -- guaranteed to differ from the exact
| single-precision sentinel placed at the correct address.  The
| post-fix (pd_pc+4) EA reads the real payload exactly.
|
| FMOVE.L (d16,PC),FP0 / FMOVE.L (d8,PC,Xn),FP0 load a 32-bit pattern
| through the FPU_S2X bridge into FP0; FMOVE.S FP0,(An) writes it back
| out for a bit-exact memory compare.  This round-trip is bit-exact
| for exactly-representable single-precision patterns -- already
| proven in fpu_fmove_dn_fpn_roundtrip.s (round-trips +3.0f bit for
| bit) -- so this test does not depend on the FPU_S2X bridge's
| documented numeric approximation for non-single-representable
| values; it only depends on which address got read.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 -- vec-11 F-line trap (regression: EA-source class
|                 broken entirely, not just the PC-relative base)
|   0xDEAD0FD1 -- (d16,PC) load read the wrong (poisoned/off-by-2) EA
|   0xDEAD0FD2 -- (d8,PC,Xn) load read the wrong (poisoned/off-by-2) EA

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SCRATCH,   0x00021000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 (F-line)

    | ── (d16,PC): FMOVE.L (d16,PC),FP0 ──────────────────────────────
    | opword 0xF23A = 1111_0010_00_111_010 (EA mode=111, reg=010 ->
    |                 (d16,PC)); bits[7:6]=00 selects the generic
    |                 fpgen_rm_reg word class.
    | ext1   0x4000 = R/M=1 (bit14), format=000 (long, bits[12:10]),
    |                 FPn=0 (bits[9:7]), opmode=0000000 (FMOVE).
    | ext2   is the disp16, filled in below via a link-time label
    |        difference so this test needs no knowledge of the
    |        runtime load address.
_pcrel_d16:
    .short  0xF23A, 0x4000, (_d16_payload - (_pcrel_d16 + 4))

    lea     SCRATCH, %a0
    .short  0xF210, 0x6000              | FMOVE.S FP0,(A0)
    move.l  SCRATCH, %d0
    cmp.l   #0x3F800000, %d0             | +1.0f
    bne     _fail_d16

    | ── (d8,PC,Xn): FMOVE.L (d8,PC,D2.L),FP0 ────────────────────────
    | opword 0xF23B = EA mode=111, reg=011 -> (d8,PC,Xn) brief format.
    | ext1   0x4000 = same FMOVE.L opmode word as above.
    | ext2 (brief) = D/A=0 (bit15), reg=D2 (bits[14:12]=010), W/L=1
    |                (long, bit11), scale=00 (bits[10:9], required
    |                scale=0 by this decode class's brief-indexed
    |                gate), brief-format bit=0 (bit8), disp8=0
    |              = 0x2800.
    | D2 carries the link-time label-difference offset directly (as a
    | full 32-bit value) instead of the brief format's +-127 disp8, so
    | the payload can live anywhere in this file regardless of size.
    move.l  #(_d8xn_payload - (_pcrel_d8xn + 4)), %d2
_pcrel_d8xn:
    .short  0xF23B, 0x4000, 0x2800

    lea     SCRATCH, %a0
    .short  0xF210, 0x6000              | FMOVE.S FP0,(A0)
    move.l  SCRATCH, %d0
    cmp.l   #0x40200000, %d0             | +2.5f
    bne     _fail_d8xn

    | PASS
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_d16:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0FD1, %d2
    move.l  %d2, (%a1)
_halt_d16:
    bra     _halt_d16

_fail_d8xn:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0FD2, %d2
    move.l  %d2, (%a1)
_halt_d8xn:
    bra     _halt_d8xn

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_halt_fline:
    bra     _halt_fline

    .align  2
_d16_poison:
    .word   0x1111
_d16_payload:
    .long   0x3F800000                  | +1.0f

    .align  2
_d8xn_poison:
    .word   0x2222
_d8xn_payload:
    .long   0x40200000                  | +2.5f
