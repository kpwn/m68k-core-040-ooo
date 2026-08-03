| fpu_fmovem_x_an_indirect.s — FMOVEM.X through a plain (An) EA
|                              (68k addressing mode 010).
|
| Regression test for a real decode gap: decode_1111.vh's fmovemx_mem_ea
| crack matched EA modes 011 ((An)+), 100 (-(An)), 101 ((d16,An)) and
| 110 ((d8,An,Xn)) but NOT 010 ((An)).  Mode 010 fell through to the
| F-line trap.  It is the single most common FMOVEM.X EA in the Q700
| universal ROM (~93 static-list sites), and a live vec-11 was confirmed
| on FPGA hardware at ROM 0x40891196, opword pair F210 D080 =
| "FMOVEM.X (A0),FP0".
|
| Behaviour model — UPDATED 2026-08-03.  FMOVEM.X now moves REAL DATA in
| both directions (FPU_X2MX + SYS_X2M_RD0/1/2 on the store side,
| FPU_M2X0/1/2 on the load side).  Until then this crack was a
| memory-traffic-only bridge that stored 12 bytes of ZEROS per register
| and discarded every loaded byte, and the stages below asserted exactly
| that — i.e. this test used to pin the BUG.  Every structural assertion
| it made (address generation, slot advance, absence of writeback,
| instruction length, base-register selection, over/underrun guards) is
| kept verbatim; only the "must be zero" data assertions have been
| replaced by "must be the value that was put in the register".
|
| What mode 010 must do differently from its neighbours:
|   * EA is exactly An — no displacement, no index word.
|   * NO writeback: An is unchanged, unlike (An)+ and -(An).
|   * Instruction length is exactly 4 bytes (opword + list word), unlike
|     (d16,An) and (d8,An,Xn) which are 6.
|   * Consecutive listed registers occupy consecutive 12-byte slots at
|     ASCENDING addresses from An.  NOTE this repo's vendored Musashi
|     (tb/models/musashi/m68kfpu.c, WRITE_EA_FPE case 2) does NOT
|     advance the address for mode 010 — it writes every listed register
|     to An itself.  Real 68040 hardware advances (control addressing,
|     M68000PRM FMOVEM), so the RTL is right and Musashi is wrong here.
|     That is why the fuzz generator only ever emits SINGLE-register
|     lists for mode 010 (see emit_fpu_fmovem_x_store in
|     tools/fuzz/gen_program.py) and why the multi-register slot layout
|     has to be pinned by this directed test instead.
|
| Encodings (verified via m68k-linux-gnu-as -m68040 / objdump):
|   F210 F080   fmovem.x %fp0,(%a0)
|   F210 F0F0   fmovem.x %fp0-%fp3,(%a0)
|   F210 D080   fmovem.x (%a0),%fp0          <<< the live ROM instruction
|   F210 D0F0   fmovem.x (%a0),%fp0-%fp3
|   F213 F024   fmovem.x %fp2/%fp5,(%a3)
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD1A01 — single-reg store did not zero its 12-byte slot
|   0xDEAD1A02 — single-reg store overran past the slot
|   0xDEAD1A03 — single-reg store wrote BELOW An (phantom predecrement)
|   0xDEAD1A04 — (An) store clobbered An (phantom writeback)
|   0xDEAD1A05 — instruction length was not 4 bytes
|   0xDEAD1A06 — multi-reg store left a slot un-zeroed (advance is wrong)
|   0xDEAD1A07 — multi-reg store overran the 4-slot range
|   0xDEAD1A08 — multi-reg store clobbered An
|   0xDEAD1A09 — load direction wrote to memory
|   0xDEAD1A0A — load direction clobbered An
|   0xDEAD1A0B — non-A0 base register was mis-decoded, or the sparse
|                list did not pack into the first N slots in
|                highest-register-at-lowest-address order
|   0xDEAD1A0C — load direction did not deliver the memory image into
|                the FP registers
|   0xDEAD1A0D — single-register store did not write FP0's actual value
|   0xDEAD1A01+0xF0 (0xDEAD1AF1) — vec-11 F-line: the decode gap is back

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ FP_BUF,    0x00020000
    .equ VSRC,      0x00020400         | 48-byte FP0-FP3 seed image
    .equ V5,        0x00020440         | 12-byte FP5 seed image
    .equ VOUT,      0x00020480         | 48-byte scratch for read-back

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line

| ── Seed image ──────────────────────────────────────────────────────
| Four distinct 96-bit extended images with zero 16-bit pads, so a
| store must reproduce them byte for byte.
    lea     VSRC, %a1
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
    lea     V5, %a1
    move.l  #0x7FFE0000, 0(%a1)
    move.l  #0xF5F5F5F5, 4(%a1)
    move.l  #0x55555555, 8(%a1)

| Load FP0-FP3.  Control list, mask 0xF0: the HIGHEST-numbered register
| in the list takes the LOWEST address, so FP3 <- slot0 ... FP0 <- slot3.
    lea     VSRC, %a1
    fmovem.x (%a1),%fp0-%fp3

| ── Stage 1: FMOVEM.X FP0,(A0) — single register ────────────────────
| Guards sit immediately above AND below the 12-byte slot so a phantom
| predecrement (mode 100 behaviour) or an overrun is caught, not just
| "did something get written".

    lea     FP_BUF+16, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0(%a0)
    move.l  %d0, 4(%a0)
    move.l  %d0, 8(%a0)
    move.l  #0xCAFEBABE, 12(%a0)       | guard above
    move.l  #0xCAFEBABE, -4(%a0)       | guard below
    move.l  #0xCAFEBABE, -8(%a0)
    move.l  #0xCAFEBABE, -12(%a0)

    | Length probe.  D7 is architecturally undefined at reset in this
    | core (PRF warm-reset staleness is accepted), so seed it explicitly
    | — otherwise a decoder that reported len=6 and skipped the moveq
    | could still find the right value in D7 by luck.
    moveq   #0, %d7
    fmovem.x %fp0,(%a0)                | F210 F080
    moveq   #0x5A, %d7                 | length probe: must execute next

    cmp.l   #0x5A, %d7
    bne     _fail_len
    | FP0 was loaded from VSRC slot 3 (see the mask note above), so the
    | single-register store must reproduce that slot exactly.
    move.l  0(%a0), %d0
    cmp.l   #0xBFFE0000, %d0
    bne     _fail_s1_data
    move.l  4(%a0), %d0
    cmp.l   #0xB0000004, %d0
    bne     _fail_s1_data
    move.l  8(%a0), %d0
    cmp.l   #0x00000044, %d0
    bne     _fail_s1_data
    move.l  12(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_s1_over
    move.l  -4(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_s1_under
    move.l  -12(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_s1_under
    cmpa.l  #(FP_BUF+16), %a0
    bne     _fail_s1_wb

| ── Stage 2: FMOVEM.X FP0-FP3,(A0) — four ascending 12-byte slots ───
| This is the slot-advance assertion.  The 48 bytes written must be a
| byte-for-byte copy of VSRC (same mask, same list mode, so the same
| register<->slot assignment as the load that seeded FP0-FP3); the
| guards above and below the 48-byte range must survive.

    lea     FP_BUF+128, %a0
    move.l  #0xDEADBEEF, %d0
    moveq   #0, %d1
_s2_fill:
    move.l  %d0, 0(%a0,%d1.w)
    addq.l  #4, %d1
    cmp.l   #48, %d1
    bne     _s2_fill
    move.l  #0xCAFEBABE, 48(%a0)
    move.l  #0xCAFEBABE, -4(%a0)

    fmovem.x %fp0-%fp3,(%a0)           | F210 F0F0

    lea     VSRC, %a1
    moveq   #0, %d1
_s2_check:
    move.l  0(%a0,%d1.w), %d0
    cmp.l   0(%a1,%d1.w), %d0
    bne     _fail_s2_data
    addq.l  #4, %d1
    cmp.l   #48, %d1
    bne     _s2_check
    move.l  48(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_s2_over
    move.l  -4(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_s2_over
    cmpa.l  #(FP_BUF+128), %a0
    bne     _fail_s2_wb

| ── Stage 3: FMOVEM.X (A0),FPn — load direction, mode 010 ───────────
| This is the exact shape of the live ROM instruction.  Assert that it
| does not trap, does not WRITE memory, does not touch An — and that the
| 48 bytes it read actually reached FP0-FP3.

    | Each slot's FIRST long carries a ZERO low half: that half is the
    | 16-bit pad of the extended memory format, which the architecture
    | (and Musashi's store_extended_float80) re-emits as zero on store.
    | With a non-zero pad in the source, the read-back below would
    | legitimately differ and this test would fail for the wrong reason.
    lea     FP_BUF+256, %a0
    move.l  #0x11110000, 0(%a0)
    move.l  #0x22222222, 4(%a0)
    move.l  #0x33333333, 8(%a0)
    move.l  #0x44440000, 12(%a0)
    move.l  #0x55555555, 16(%a0)
    move.l  #0x66666666, 20(%a0)
    move.l  #0x77770000, 24(%a0)
    move.l  #0x88888888, 28(%a0)
    move.l  #0x99999999, 32(%a0)
    move.l  #0xAAAA0000, 36(%a0)
    move.l  #0xBBBBBBBB, 40(%a0)
    move.l  #0xCCCCCCCC, 44(%a0)

    fmovem.x (%a0),%fp0                | F210 D080 — the ROM instruction
    fmovem.x (%a0),%fp0-%fp3           | F210 D0F0

    move.l  0(%a0), %d0
    cmp.l   #0x11110000, %d0
    bne     _fail_s3_wrote
    move.l  20(%a0), %d0
    cmp.l   #0x66666666, %d0
    bne     _fail_s3_wrote
    move.l  44(%a0), %d0
    cmp.l   #0xCCCCCCCC, %d0
    bne     _fail_s3_wrote
    cmpa.l  #(FP_BUF+256), %a0
    bne     _fail_s3_wb

    | Read FP0-FP3 back out and compare against what stage 3 loaded.
    lea     VOUT, %a1
    move.l  #0xDEADBEEF, %d0
    moveq   #0, %d1
_s3_fill:
    move.l  %d0, 0(%a1,%d1.w)
    addq.l  #4, %d1
    cmp.l   #48, %d1
    bne     _s3_fill
    fmovem.x %fp0-%fp3,(%a1)
    moveq   #0, %d1
_s3_check:
    move.l  0(%a0,%d1.w), %d0
    cmp.l   0(%a1,%d1.w), %d0
    bne     _fail_s3_data
    addq.l  #4, %d1
    cmp.l   #48, %d1
    bne     _s3_check

| ── Stage 4: non-A0 base + non-contiguous list ──────────────────────
| Proves op[2:0] actually selects the base register (an implementation
| that hardwired A0 would pass every stage above) and that a sparse mask
| still lands in the first N slots, packed, not at mask-bit positions.
| ext1 = 0xF024 -> control list, mask bits 5 and 2 -> FP2 and FP5.  The
| higher-numbered register takes the LOWER address, so slot 0 must hold
| FP5 and slot 1 must hold FP2.
| FP2 currently holds stage 3's slot 1 (the load put FP3<-slot0,
| FP2<-slot1, FP1<-slot2, FP0<-slot3).
| FP5 is seeded here from V5.

    lea     FP_BUF+512, %a3
    move.l  #0xDEADBEEF, 0(%a3)
    move.l  #0xDEADBEEF, 4(%a3)
    move.l  #0xDEADBEEF, 8(%a3)
    move.l  #0xDEADBEEF, 12(%a3)
    move.l  #0xDEADBEEF, 16(%a3)
    move.l  #0xDEADBEEF, 20(%a3)
    move.l  #0xCAFEBABE, 24(%a3)

    lea     V5, %a1
    fmovem.x (%a1),%fp5                | seed FP5 (ext1 D004)

    fmovem.x %fp2/%fp5,(%a3)           | F213 F024 — two regs, packed

    | slot 0 = FP5 = V5
    move.l  0(%a3), %d0
    cmp.l   #0x7FFE0000, %d0
    bne     _fail_s4
    move.l  4(%a3), %d0
    cmp.l   #0xF5F5F5F5, %d0
    bne     _fail_s4
    move.l  8(%a3), %d0
    cmp.l   #0x55555555, %d0
    bne     _fail_s4
    | slot 1 = FP2 = stage-3 buffer slot 1
    move.l  12(%a3), %d0
    cmp.l   #0x44440000, %d0
    bne     _fail_s4
    move.l  16(%a3), %d0
    cmp.l   #0x55555555, %d0
    bne     _fail_s4
    move.l  20(%a3), %d0
    cmp.l   #0x66666666, %d0
    bne     _fail_s4
    move.l  24(%a3), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_s4
    cmpa.l  #(FP_BUF+512), %a3
    bne     _fail_s4

| ── PASS ────────────────────────────────────────────────────────────
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_s1_data:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1A01, %d2
    move.l  %d2, (%a1)
_h1:
    bra     _h1

_fail_s1_over:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1A02, %d2
    move.l  %d2, (%a1)
_h2:
    bra     _h2

_fail_s1_under:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1A03, %d2
    move.l  %d2, (%a1)
_h3:
    bra     _h3

_fail_s1_wb:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1A04, %d2
    move.l  %d2, (%a1)
_h4:
    bra     _h4

_fail_len:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1A05, %d2
    move.l  %d2, (%a1)
_h5:
    bra     _h5

_fail_s2_data:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1A06, %d2
    move.l  %d2, (%a1)
_h6:
    bra     _h6

_fail_s2_over:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1A07, %d2
    move.l  %d2, (%a1)
_h7:
    bra     _h7

_fail_s2_wb:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1A08, %d2
    move.l  %d2, (%a1)
_h8:
    bra     _h8

_fail_s3_wrote:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1A09, %d2
    move.l  %d2, (%a1)
_h9:
    bra     _h9

_fail_s3_wb:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1A0A, %d2
    move.l  %d2, (%a1)
_h10:
    bra     _h10

_fail_s4:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1A0B, %d2
    move.l  %d2, (%a1)
_h11:
    bra     _h11

_fail_s3_data:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1A0C, %d2
    move.l  %d2, (%a1)
_h12:
    bra     _h12

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1AF1, %d2
    move.l  %d2, (%a1)
_hf:
    bra     _hf
