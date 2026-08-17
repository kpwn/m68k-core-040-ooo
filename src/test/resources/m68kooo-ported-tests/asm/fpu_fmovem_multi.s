| fpu_fmovem_multi.s — FMOVEM.X register list ↔ memory through
|                       (d16,An), -(An), (An)+, and indexed EA.
|
| Validates the FPSP-recursion fix (decode-1111: FMOVEM.X register
| list in memory EA modes, multi-µop crack into per-FP-register
| 12-byte memory traffic).
|
| Behavior model — UPDATED 2026-08-03.  FMOVEM.X now moves REAL DATA
| (FPU_X2MX + SYS_X2M_RD0/1/2 on the store side, FPU_M2X0/1/2 on the
| load side).  This test previously asserted the OLD stub's behaviour —
| "writes 12*N bytes of ZEROS", "load data is discarded" — i.e. it
| pinned the bug.  Every structural assertion (EA generation, An
| writeback, over-store guards, instruction length, index-register
| integrity, trap-freedom) is kept; the zero assertions are now value
| assertions against a seed image.
|
| Register-list -> address rule (see fpu_fmovem_x_roundtrip.s for the
| Musashi/gas derivation): the HIGHEST-numbered register in the list
| takes the LOWEST address.  With FP0-FP3 that is FP3 at +0 ... FP0 at
| +36.
|
| Encodings (verified via m68k-linux-gnu-as -m68040):
|   F228 F0F0 0000   FMOVEM.X FP0-FP3,(0,A0)
|   F228 D0F0 0000   FMOVEM.X (0,A0),FP0-FP3
|   F220 E001        FMOVEM.X FP0,-(A0)      (Q700-style predec mask)
|   F218 C001        FMOVEM.X (A0)+,FP0      (postinc load, one FP slot)
|   F23C 5800 0012   FMOVE.B #0x12,FP0       (immediate EA)
|   F23C 4100 1234 5678  FMOVE.L #0x12345678,FP2
|   F230 F0C0 1004   FMOVEM.X FP0-FP1,(4,A0,D1.W)
|   F230 D0C0 1004   FMOVEM.X (4,A0,D1.W),FP0-FP1
|
| Use op=F228 (mode=101 reg=000 = (d16,A0)), ext2=disp16=0,
| op=F220 for -(A0), and op=F218 for (A0)+.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F21 — (d16,An) store did not reproduce the seed image
|   0xDEAD0F22 — (d16,An) load did not deliver the seed image into FPn
|   0xDEAD0F23 — sentinel was trampled (load wrote past EA range)
|   0xDEAD0F24 — predecrement store did not update A0 by -12
|   0xDEAD0F25 — predecrement store did not write FP0's actual value
|   0xDEAD0F26 — postincrement load did not update A0 by +12
|   0xDEAD0F27 — FMOVE immediate failed to advance to following code
|   0xDEAD0F28 — indexed FMOVEM.X store wrote the wrong first long
|   0xDEAD0F29 — indexed FMOVEM.X store wrote the wrong last long
|   0xDEAD0F2A — indexed FMOVEM.X store overran the 24-byte range
|   0xDEAD0F2B — indexed FMOVEM.X corrupted base/index registers
|   0xDEAD0F01 — vec-11 F-line (decoder gap)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ FP_BUF,    0x00020000
    .equ SEED,      0x00021000
    .equ CLOBBER,   0x00021040
    .equ READBACK,  0x00021080

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line

    | Pre-fill the FP buffer with 0xDEADBEEF in every long so we can
    | tell whether the FMOVEM.X store actually wrote anything.
    lea     FP_BUF, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0(%a0)
    move.l  %d0, 4(%a0)
    move.l  %d0, 8(%a0)
    move.l  %d0, 12(%a0)
    move.l  %d0, 16(%a0)
    move.l  %d0, 20(%a0)
    move.l  %d0, 24(%a0)
    move.l  %d0, 28(%a0)
    move.l  %d0, 32(%a0)
    move.l  %d0, 36(%a0)
    move.l  %d0, 40(%a0)
    move.l  %d0, 44(%a0)               | offset 44..47 = last long (3rd long of fp3)

    | Place a sentinel right past the end of the FMOVEM.X buffer
    | so we catch any over-store.
    move.l  #0xCAFEBABE, 48(%a0)

    | Seed FP0-FP3 from a known 48-byte image (zero 16-bit pads so a
    | store reproduces it exactly).
    lea     SEED, %a1
    move.l  #0x3FFF0000, 0(%a1)
    move.l  #0x80000001, 4(%a1)
    move.l  #0x00000011, 8(%a1)
    move.l  #0x40000000, 12(%a1)
    move.l  #0x90000002, 16(%a1)
    move.l  #0x00000022, 20(%a1)
    move.l  #0xC0010000, 24(%a1)
    move.l  #0xA0000003, 28(%a1)
    move.l  #0x00000033, 32(%a1)
    move.l  #0xBFFE0000, 36(%a1)
    move.l  #0xB0000004, 40(%a1)
    move.l  #0x00000044, 44(%a1)
    fmovem.x (%a1),%fp0-%fp3

    | FMOVEM.X FP0-FP3,(0,A0).
    .short  0xF228, 0xF0F0, 0x0000

    | The 48 bytes written must be a byte-for-byte copy of SEED.
    lea     SEED, %a1
    moveq   #0, %d1
_seedcmp:
    move.l  0(%a0,%d1.w), %d0
    cmp.l   0(%a1,%d1.w), %d0
    bne     _fail_first
    addq.l  #4, %d1
    cmp.l   #48, %d1
    bne     _seedcmp

    | Check the past-end sentinel is intact (proves no over-store).
    move.l  48(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_overrun

    | FMOVEM.X (0,A0),FP0-FP3 — must not trap, AND must put the image
    | back into the FP registers.  Clobber them first so the check is
    | not vacuous, then reload and read back.
    lea     CLOBBER, %a1
    move.l  #0x00000000, 0(%a1)
    move.l  #0x00000000, 4(%a1)
    move.l  #0x00000000, 8(%a1)
    move.l  #0x00000000, 12(%a1)
    move.l  #0x00000000, 16(%a1)
    move.l  #0x00000000, 20(%a1)
    move.l  #0x00000000, 24(%a1)
    move.l  #0x00000000, 28(%a1)
    move.l  #0x00000000, 32(%a1)
    move.l  #0x00000000, 36(%a1)
    move.l  #0x00000000, 40(%a1)
    move.l  #0x00000000, 44(%a1)
    fmovem.x (%a1),%fp0-%fp3

    .short  0xF228, 0xD0F0, 0x0000

    lea     READBACK, %a1
    fmovem.x %fp0-%fp3,(%a1)
    lea     SEED, %a2
    moveq   #0, %d1
_rbcmp:
    move.l  0(%a1,%d1.w), %d0
    cmp.l   0(%a2,%d1.w), %d0
    bne     _fail_last
    addq.l  #4, %d1
    cmp.l   #48, %d1
    bne     _rbcmp

    | FMOVEM.X FP0,-(A0) — Q700 ROM vector-11 handler shape
    | (fmovemx %fp0,%sp@- encodes as F227 E001).  The simplified store
    | writes one 12-byte zero slot and predecrements A0 by 12.
    lea     FP_BUF+76, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, -12(%a0)
    move.l  %d0, -8(%a0)
    move.l  %d0, -4(%a0)
    move.l  #0xCAFEBABE, 0(%a0)
    .short  0xF220, 0xE001
    cmpa.l  #(FP_BUF+64), %a0
    bne     _fail_predec_wb
    | FP0 was seeded from SEED slot 3.
    move.l  0(%a0), %d0
    cmp.l   #0xBFFE0000, %d0
    bne     _fail_predec_data
    move.l  4(%a0), %d0
    cmp.l   #0xB0000004, %d0
    bne     _fail_predec_data
    move.l  8(%a0), %d0
    cmp.l   #0x00000044, %d0
    bne     _fail_predec_data
    move.l  12(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_overrun

    | FMOVEM.X (A0)+,FP0 — should load and discard one 12-byte slot,
    | then postincrement A0 by 12.
    lea     FP_BUF+96, %a0
    move.l  #0x11110000, 0(%a0)
    move.l  #0x22222222, 4(%a0)
    move.l  #0x33333333, 8(%a0)
    .short  0xF218, 0xC001
    cmpa.l  #(FP_BUF+108), %a0
    bne     _fail_postinc_wb
    | ...and FP0 must actually hold those 12 bytes now.
    lea     READBACK, %a1
    fmovem.x %fp0,(%a1)
    move.l  0(%a1), %d0
    cmp.l   #0x11110000, %d0
    bne     _fail_postinc_wb
    move.l  4(%a1), %d0
    cmp.l   #0x22222222, %d0
    bne     _fail_postinc_wb
    move.l  8(%a1), %d0
    cmp.l   #0x33333333, %d0
    bne     _fail_postinc_wb

    | FMOVE.B #imm,FP0 and FMOVE.L #imm,FP2.  These immediate EA forms
    | must consume exactly 6 and 8 bytes respectively and must not F-line
    | trap.  The moveq markers verify that execution resumes after the
    | right number of extension words.
    moveq   #0, %d7
    .short  0xF23C, 0x5800, 0x0012       | FMOVE.B #0x12,FP0 (6 bytes)
    moveq   #0x11, %d7
    .short  0xF23C, 0x4100, 0x1234, 0x5678 | FMOVE.L #0x12345678,FP2 (8 bytes)
    moveq   #0x22, %d7
    cmp.l   #0x22, %d7
    bne     _fail_fmove_imm_len

    | FMOVEM.X FP0-FP1,(4,A0,D1.W).  Target = A0 + sx(D1.W) + 4.
    | The simplified store writes 24 bytes of zeros.  A0 and D1 are
    | visible architectural inputs and must not be clobbered by the
    | indexed EA crack.
    lea     FP_BUF+128, %a0
    move.l  #16, %d1
    move.l  #0xDEADBEEF, 20(%a0)
    move.l  #0xDEADBEEF, 24(%a0)
    move.l  #0xDEADBEEF, 28(%a0)
    move.l  #0xDEADBEEF, 32(%a0)
    move.l  #0xDEADBEEF, 36(%a0)
    move.l  #0xDEADBEEF, 40(%a0)
    move.l  #0xCAFEBABE, 44(%a0)
    | FP0-FP1 currently hold FP_BUF+96's image (FP0, from the postinc
    | load above) and SEED slot 2 (FP1, from the seed load).  Re-seed
    | both from a dedicated 24-byte image so the expected bytes are
    | explicit rather than inherited.
    lea     SEED+48, %a2
    move.l  #0x3FFD0000, 0(%a2)          | -> FP1 (lower address)
    move.l  #0x12345678, 4(%a2)
    move.l  #0x9ABCDEF0, 8(%a2)
    move.l  #0x40030000, 12(%a2)         | -> FP0
    move.l  #0x0F0F0F0F, 16(%a2)
    move.l  #0xF0F0F0F0, 20(%a2)
    fmovem.x (%a2),%fp0-%fp1

    .short  0xF230, 0xF0C0, 0x1004       | FMOVEM.X FP0-FP1,(4,A0,D1.W)
    cmpa.l  #(FP_BUF+128), %a0
    bne     _fail_index_regs
    cmp.l   #16, %d1
    bne     _fail_index_regs
    move.l  20(%a0), %d0
    cmp.l   #0x3FFD0000, %d0
    bne     _fail_index_first
    move.l  40(%a0), %d0
    cmp.l   #0xF0F0F0F0, %d0
    bne     _fail_index_last
    move.l  44(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_index_overrun

    | FMOVEM.X (4,A0,D1.W),FP0-FP1 — load direction through the indexed
    | EA.  Verifies decode, PC length, phase-count completion, A0/D1
    | architectural integrity, AND that the data reaches FP0/FP1.
    move.l  #0x11110000, 20(%a0)
    move.l  #0x22222222, 24(%a0)
    move.l  #0x33333333, 28(%a0)
    move.l  #0x44440000, 32(%a0)
    move.l  #0x55555555, 36(%a0)
    move.l  #0x66666666, 40(%a0)
    .short  0xF230, 0xD0C0, 0x1004       | FMOVEM.X (4,A0,D1.W),FP0-FP1
    cmpa.l  #(FP_BUF+128), %a0
    bne     _fail_index_regs
    cmp.l   #16, %d1
    bne     _fail_index_regs
    lea     READBACK, %a2
    fmovem.x %fp0-%fp1,(%a2)
    move.l  0(%a2), %d0
    cmp.l   #0x11110000, %d0
    bne     _fail_index_first
    move.l  20(%a2), %d0
    cmp.l   #0x66666666, %d0
    bne     _fail_index_last

    | PASS.
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_first:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F21, %d2
    move.l  %d2, (%a1)
_h1:
    bra     _h1

_fail_last:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F22, %d2
    move.l  %d2, (%a1)
_h2:
    bra     _h2

_fail_overrun:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F23, %d2
    move.l  %d2, (%a1)
_h3:
    bra     _h3

_fail_predec_wb:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F24, %d2
    move.l  %d2, (%a1)
_h4:
    bra     _h4

_fail_predec_data:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F25, %d2
    move.l  %d2, (%a1)
_h5:
    bra     _h5

_fail_postinc_wb:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F26, %d2
    move.l  %d2, (%a1)
_h6:
    bra     _h6

_fail_fmove_imm_len:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F27, %d2
    move.l  %d2, (%a1)
_h7:
    bra     _h7

_fail_index_first:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F28, %d2
    move.l  %d2, (%a1)
_h8:
    bra     _h8

_fail_index_last:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F29, %d2
    move.l  %d2, (%a1)
_h9:
    bra     _h9

_fail_index_overrun:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F2A, %d2
    move.l  %d2, (%a1)
_h10:
    bra     _h10

_fail_index_regs:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F2B, %d2
    move.l  %d2, (%a1)
_h11:
    bra     _h11

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_hf:
    bra     _hf
