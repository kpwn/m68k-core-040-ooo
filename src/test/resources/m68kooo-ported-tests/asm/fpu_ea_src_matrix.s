| fpu_ea_src_matrix.s — EA-source FPU decode matrix smoke.
|
| This is intentionally a decode/retire/addressing test.  The current
| EA-source bridge converts raw 32-bit data through FPU_S2X, so integer
| and extended-memory source values are not numerically exact yet.  The
| assertions here cover the shapes that must not F-line trap in the Q700
| FPSP path and verify auto-adjust/index side effects.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 — vec-11 F-line trap
|   0xDEAD0F41 — postincrement byte count wrong
|   0xDEAD0F42 — predecrement byte count wrong
|   0xDEAD0F43 — indexed form clobbered base/index

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ BUF,       0x00022000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C

    lea     BUF, %a0
    move.b  #0x01, 0(%a0)
    move.w  #0x3F80, 2(%a0)
    move.l  #0x3F800000, 4(%a0)
    move.l  #0x40000000, 16(%a0)
    move.l  #0x3F800000, 32(%a0)
    move.l  #0x40000000, 36(%a0)
    move.l  #0x40400000, 40(%a0)

    | Seed FP0/FP1 with a real single conversion.
    move.l  #0x3F800000, %d0
    .short  0xF200, 0x4000             | FMOVE.S D0,FP0
    move.l  #0x40000000, %d0
    .short  0xF200, 0x4080             | FMOVE.S D0,FP1

    | Required live-ROM shapes.
    move.l  #0x3F800000, %d0
    .short  0xF200, 0x5822             | FADD.B D0,FP0
    .short  0xF23C, 0x5823, 0x000A     | FMUL.B #10,FP0
    lea     BUF, %a1
    move.l  #32, %d3
    .short  0xF231, 0x48A3, 0x3000     | FMUL.X (0,A1,D3.W),FP1 (scale=0)
    .short  0xF200, 0x001A             | FNEG.X FP0,FP0 regression guard

    | Additional format/EA breadth.
    .short  0xF210, 0x5022             | FADD.W (A0),FP0
    .short  0xF228, 0x4028, 0x0004     | FSUB.L (4,A0),FP0

    lea     BUF+16, %a2
    .short  0xF21A, 0x4423             | FMUL.S (A2)+,FP0
    cmpa.l  #(BUF+20), %a2
    bne     _fail_postinc

    lea     BUF+24, %a2
    .short  0xF222, 0x4422             | FADD.S -(A2),FP0
    cmpa.l  #(BUF+20), %a2
    bne     _fail_predec

    | Extended-format memory source: decoded/retired as a first-long bridge
    | approximation.  This covers the addressing shape without asserting
    | numeric fidelity.
    lea     BUF+32, %a1
    move.l  #0, %d3
    .short  0xF231, 0x48A3, 0x3000     | FMUL.X (0,A1,D3.W),FP1
    cmpa.l  #(BUF+32), %a1
    bne     _fail_index_regs
    cmp.l   #0, %d3
    bne     _fail_index_regs

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_postinc:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F41, %d2
    move.l  %d2, (%a1)
_h1:
    bra     _h1

_fail_predec:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F42, %d2
    move.l  %d2, (%a1)
_h2:
    bra     _h2

_fail_index_regs:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F43, %d2
    move.l  %d2, (%a1)
_h3:
    bra     _h3

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_hf:
    bra     _hf
