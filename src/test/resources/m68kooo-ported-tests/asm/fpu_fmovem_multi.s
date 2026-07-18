| fpu_fmovem_multi.s — FMOVEM.X register list ↔ memory through
|                       (d16,An), -(An), (An)+, and indexed EA.
|
| Validates the FPSP-recursion fix (decode-1111: FMOVEM.X register
| list in memory EA modes, multi-µop crack into per-FP-register
| 12-byte memory traffic).
|
| Behavior model: this landing produces correct memory traffic
| (3 longs per FP register) but does NOT round-trip FP register data
| through memory.  The test verifies:
|   1. FMOVEM.X store does not trap (no F-line) and writes 12*N bytes
|      of zeros to memory.
|   2. FMOVEM.X load does not trap and reads 12*N bytes back.
|   3. The instruction stream advances correctly past the FMOVEM.X
|      opwords (no PC stall).
|
| FP register fidelity is a follow-up — this is the "FPSP recursion
| break" landing.
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
|   0xDEAD0F21 — memory not zeroed at offset 0 (store didn't fire)
|   0xDEAD0F22 — memory not zeroed at offset 44 (last byte slot)
|   0xDEAD0F23 — sentinel was trampled (load wrote past EA range)
|   0xDEAD0F24 — predecrement store did not update A0 by -12
|   0xDEAD0F25 — predecrement store did not zero the 12-byte slot
|   0xDEAD0F26 — postincrement load did not update A0 by +12
|   0xDEAD0F27 — FMOVE immediate failed to advance to following code
|   0xDEAD0F28 — indexed FMOVEM.X store did not zero first long
|   0xDEAD0F29 — indexed FMOVEM.X store did not zero last long
|   0xDEAD0F2A — indexed FMOVEM.X store overran the 24-byte range
|   0xDEAD0F2B — indexed FMOVEM.X corrupted base/index registers
|   0xDEAD0F01 — vec-11 F-line (decoder gap)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ FP_BUF,    0x00020000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line

    | Pre-fill the FP buffer with 0xDEADBEEF in every long so we can
    | tell whether the FMOVEM.X store actually wrote zeros.
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

    | FMOVEM.X FP0-FP3,(0,A0).
    .short  0xF228, 0xF0F0, 0x0000

    | Check first long is zero (proves store fired).
    move.l  0(%a0), %d0
    cmp.l   #0, %d0
    bne     _fail_first

    | Check last long (offset 44) is zero (proves all 12 phases fired).
    move.l  44(%a0), %d0
    cmp.l   #0, %d0
    bne     _fail_last

    | Check the past-end sentinel is intact (proves no over-store).
    move.l  48(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_overrun

    | FMOVEM.X (0,A0),FP0-FP3 — should not trap.  We don't check FP
    | register state (round-trip not implemented yet).
    .short  0xF228, 0xD0F0, 0x0000

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
    move.l  0(%a0), %d0
    cmp.l   #0, %d0
    bne     _fail_predec_data
    move.l  8(%a0), %d0
    cmp.l   #0, %d0
    bne     _fail_predec_data
    move.l  12(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_overrun

    | FMOVEM.X (A0)+,FP0 — should load and discard one 12-byte slot,
    | then postincrement A0 by 12.
    lea     FP_BUF+96, %a0
    move.l  #0x11111111, 0(%a0)
    move.l  #0x22222222, 4(%a0)
    move.l  #0x33333333, 8(%a0)
    .short  0xF218, 0xC001
    cmpa.l  #(FP_BUF+108), %a0
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
    .short  0xF230, 0xF0C0, 0x1004       | FMOVEM.X FP0-FP1,(4,A0,D1.W)
    cmpa.l  #(FP_BUF+128), %a0
    bne     _fail_index_regs
    cmp.l   #16, %d1
    bne     _fail_index_regs
    move.l  20(%a0), %d0
    cmp.l   #0, %d0
    bne     _fail_index_first
    move.l  40(%a0), %d0
    cmp.l   #0, %d0
    bne     _fail_index_last
    move.l  44(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_index_overrun

    | FMOVEM.X (4,A0,D1.W),FP0-FP1.  Load-list values are discarded by
    | the current stub, but this still verifies decode, PC length, memory
    | phase count completion, and A0/D1 architectural integrity.
    move.l  #0x11111111, 20(%a0)
    move.l  #0x22222222, 24(%a0)
    move.l  #0x33333333, 28(%a0)
    move.l  #0x44444444, 32(%a0)
    move.l  #0x55555555, 36(%a0)
    move.l  #0x66666666, 40(%a0)
    .short  0xF230, 0xD0C0, 0x1004       | FMOVEM.X (4,A0,D1.W),FP0-FP1
    cmpa.l  #(FP_BUF+128), %a0
    bne     _fail_index_regs
    cmp.l   #16, %d1
    bne     _fail_index_regs

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
