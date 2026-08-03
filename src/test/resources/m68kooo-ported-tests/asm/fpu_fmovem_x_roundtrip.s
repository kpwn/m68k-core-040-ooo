| fpu_fmovem_x_roundtrip.s — FMOVEM.X actually MOVES DATA.
|
| Until 2026-08-03 the FMOVEM.X crack in decode_1111.vh emitted the
| right NUMBER of bytes with FAKE data: STORE.L #0 for every longword,
| and a LOAD.L whose result went to a discarded scratch register.  It
| retired cleanly and silently destroyed FP0-FP7 on every ROM F-line
| dispatch.  This test pins the real behaviour.
|
| Everything here is an INTEGER comparison of memory bytes, so it does
| not depend on any FPU arithmetic — only on the 96-bit extended-format
| memory image being moved verbatim in and out of the 80-bit register.
|
| 96-bit in-memory extended format (matches Musashi's
| load_extended_float80 / store_extended_float80, m68kfpu.c:64-86):
|     +0 : sign + exponent (16 bits), then a 16-bit ZERO PAD
|     +4 : mantissa[63:32]   (explicit integer bit at 63)
|     +8 : mantissa[31:0]
| The architectural register is those 80 bits with the pad removed, so a
| store must re-emit the pad as zero regardless of what was loaded.
|
| Register-list -> memory ordering (verified against Musashi's fmovem()
| at m68kfpu.c:1662 AND against what m68k-linux-gnu-as -m68040 emits):
|   control/postincrement list (ext1[12:11]=10): mask bit n selects FP(7-n)
|   predecrement list          (ext1[12:11]=00): mask bit n selects FPn
| and BOTH lay the same image down: the HIGHEST-numbered register in the
| list occupies the LOWEST address.  So with FP0-FP3:
|   FMOVEM.X (A0),FP0-FP3   -> FP3 <- (A0+0), FP2 <- +12, FP1 <- +24, FP0 <- +36
|   FMOVEM.X FP0-FP3,-(A0)  -> FP0 -> (A0-12) ... FP3 -> (A0-48)
| which is why an -(An) save and an (An)+ / control restore round-trip.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD1101 — vec 11 F-line (decode gap)
|   0xDEAD1102 — (d16,An) store/load round-trip mismatch
|   0xDEAD1103 — single-register store did not select FP0 == src slot 3
|                (register-list -> address ordering wrong)
|   0xDEAD1104 — the 16-bit pad was not re-zeroed on store
|   0xDEAD1105 — predecrement store laid the block down in the wrong order
|   0xDEAD1106 — predecrement store did not update An by -48
|   0xDEAD1107 — postincrement load round-trip mismatch
|   0xDEAD1108 — postincrement load did not update An by +48
|   0xDEAD1109 — empty static register list touched memory
|   0xDEAD110A — empty static register list changed An
|   0xDEAD110B — plain (An) multi-register round-trip mismatch
|   0xDEAD110C — the FMOVEM.X store wrote all zeros (the OLD stub's
|                signature — kept as an explicit, named check)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SRC,       0x00020000
    .equ DST,       0x00020100
    .equ DST2,      0x00020200
    .equ PRE,       0x00020300
    .equ PADBUF,    0x00020400

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line

    | ── Fill SRC with four distinct 96-bit extended images ──────────
    lea     SRC, %a0
    move.l  #0x3FFF0000, 0(%a0)        | slot 0
    move.l  #0x80000000, 4(%a0)
    move.l  #0x00000001, 8(%a0)
    move.l  #0xC0020000, 12(%a0)       | slot 1
    move.l  #0xABCDEF01, 16(%a0)
    move.l  #0x23456789, 20(%a0)
    move.l  #0x00010000, 24(%a0)       | slot 2
    move.l  #0x13572468, 28(%a0)
    move.l  #0x00000002, 32(%a0)
    move.l  #0x7FFF0000, 36(%a0)       | slot 3
    move.l  #0xFFFFFFFF, 40(%a0)
    move.l  #0xFFFFFFFE, 44(%a0)

    | Poison DST so a store of nothing is visible.
    lea     DST, %a1
    move.l  #0xDEADBEEF, %d0
    moveq   #11, %d1
_poison:
    move.l  %d0, (%a1)+
    dbra    %d1, _poison

    | ── (d16,An) round-trip: SRC -> FP0-FP3 -> DST ──────────────────
    | Nonzero displacements: gas folds %an@(0) down to the plain (An)
    | mode, which is a DIFFERENT decode path.
    lea     SRC-16, %a0
    lea     DST-32, %a1
    fmovem.x %a0@(16), %fp0-%fp3
    fmovem.x %fp0-%fp3, %a1@(32)

    | The old stub stored zeros.  Check that first, by name.
    move.l  DST+0, %d0
    or.l    DST+4, %d0
    or.l    DST+8, %d0
    tst.l   %d0
    beq     _fail_allzero

    | Full 48-byte compare.
    lea     SRC, %a0
    lea     DST, %a1
    moveq   #11, %d1
_cmp1:
    move.l  (%a0)+, %d0
    cmp.l   (%a1)+, %d0
    bne     _fail_d16
    dbra    %d1, _cmp1

    | ── Ordering: FP0 must hold SRC slot 3 ─────────────────────────
    | FMOVEM.X (A0),FP0-FP3 uses the control list (mask 0xF0), so the
    | HIGHEST-numbered register in the list sits at the LOWEST address:
    | FP3 <- slot 0 ... FP0 <- slot 3.  Store FP0 alone and compare.
    lea     DST2, %a1
    move.l  #0xDEADBEEF, 0(%a1)
    move.l  #0xDEADBEEF, 4(%a1)
    move.l  #0xDEADBEEF, 8(%a1)
    fmovem.x %fp0, %a1@
    move.l  0(%a1), %d0
    cmp.l   #0x7FFF0000, %d0
    bne     _fail_order
    move.l  4(%a1), %d0
    cmp.l   #0xFFFFFFFF, %d0
    bne     _fail_order
    move.l  8(%a1), %d0
    cmp.l   #0xFFFFFFFE, %d0
    bne     _fail_order

    | ── 16-bit pad must be re-zeroed on store ──────────────────────
    lea     PADBUF, %a0
    move.l  #0x4001AAAA, 0(%a0)        | pad = 0xAAAA in the SOURCE image
    move.l  #0xCAFEBABE, 4(%a0)
    move.l  #0x0BADF00D, 8(%a0)
    move.l  #0xDEADBEEF, 12(%a0)
    move.l  #0xDEADBEEF, 16(%a0)
    move.l  #0xDEADBEEF, 20(%a0)
    fmovem.x %a0@, %fp7
    lea     PADBUF+12, %a1
    fmovem.x %fp7, %a1@
    move.l  0(%a1), %d0
    cmp.l   #0x40010000, %d0           | pad zeroed, sign+exp preserved
    bne     _fail_pad
    move.l  4(%a1), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_pad
    move.l  8(%a1), %d0
    cmp.l   #0x0BADF00D, %d0
    bne     _fail_pad

    | ── Predecrement store: FMOVEM.X FP0-FP3,-(An) ─────────────────
    | FP0-FP3 still hold SRC slots 3,2,1,0 respectively.  The
    | predecrement list puts FP0 at the HIGHEST address, so the block
    | laid down from PRE upward must be an exact copy of SRC.
    lea     PRE, %a1
    move.l  #0xDEADBEEF, %d0
    moveq   #11, %d1
_poison2:
    move.l  %d0, (%a1)+
    dbra    %d1, _poison2

    lea     PRE+48, %a0
    fmovem.x %fp0-%fp3, %a0@-
    cmpa.l  #PRE, %a0
    bne     _fail_predec_wb

    lea     SRC, %a0
    lea     PRE, %a1
    moveq   #11, %d1
_cmp2:
    move.l  (%a0)+, %d0
    cmp.l   (%a1)+, %d0
    bne     _fail_predec_order
    dbra    %d1, _cmp2

    | ── Postincrement load: FMOVEM.X (An)+,FP0-FP3 ─────────────────
    | Reload from PRE (which now equals SRC) and store to DST2 through
    | a plain (An), then compare against SRC.
    lea     PRE, %a0
    fmovem.x %a0@+, %fp0-%fp3
    cmpa.l  #(PRE+48), %a0
    bne     _fail_postinc_wb

    lea     DST2, %a1
    move.l  #0xDEADBEEF, %d0
    moveq   #11, %d1
_poison3:
    move.l  %d0, (%a1)+
    dbra    %d1, _poison3

    lea     DST2, %a1
    fmovem.x %fp0-%fp3, %a1@
    lea     SRC, %a0
    lea     DST2, %a1
    moveq   #11, %d1
_cmp3:
    move.l  (%a0)+, %d0
    cmp.l   (%a1)+, %d0
    bne     _fail_postinc
    dbra    %d1, _cmp3

    | ── Plain (An) multi-register: already covered above by the store
    | side; now cover the LOAD side through (An) too.
    lea     SRC, %a0
    fmovem.x %a0@, %fp0-%fp3
    lea     DST2, %a1
    move.l  #0xDEADBEEF, %d0
    moveq   #11, %d1
_poison4:
    move.l  %d0, (%a1)+
    dbra    %d1, _poison4
    lea     DST2, %a1
    fmovem.x %fp0-%fp3, %a1@
    lea     SRC, %a0
    lea     DST2, %a1
    moveq   #11, %d1
_cmp4:
    move.l  (%a0)+, %d0
    cmp.l   (%a1)+, %d0
    bne     _fail_an
    dbra    %d1, _cmp4

    | ── Empty static register list = architectural no-op ───────────
    | F210 F000 = FMOVEM.X <empty>,(A0)   control list, mask 0
    | F220 E000 = FMOVEM.X <empty>,-(A0)  predecrement list, mask 0
    | Neither may touch memory, and neither may adjust An.
    lea     DST2, %a0
    move.l  #0x5A5A5A5A, 0(%a0)
    move.l  #0xA5A5A5A5, 4(%a0)
    .short  0xF210, 0xF000
    move.l  0(%a0), %d0
    cmp.l   #0x5A5A5A5A, %d0
    bne     _fail_empty_mem
    move.l  4(%a0), %d0
    cmp.l   #0xA5A5A5A5, %d0
    bne     _fail_empty_mem
    cmpa.l  #DST2, %a0
    bne     _fail_empty_an

    .short  0xF220, 0xE000
    cmpa.l  #DST2, %a0
    bne     _fail_empty_an
    move.l  0(%a0), %d0
    cmp.l   #0x5A5A5A5A, %d0
    bne     _fail_empty_mem

    | PASS.
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fline:
    move.l  #0xDEAD1101, %d2
    bra     _report
_fail_d16:
    move.l  #0xDEAD1102, %d2
    bra     _report
_fail_order:
    move.l  #0xDEAD1103, %d2
    bra     _report
_fail_pad:
    move.l  #0xDEAD1104, %d2
    bra     _report
_fail_predec_order:
    move.l  #0xDEAD1105, %d2
    bra     _report
_fail_predec_wb:
    move.l  #0xDEAD1106, %d2
    bra     _report
_fail_postinc:
    move.l  #0xDEAD1107, %d2
    bra     _report
_fail_postinc_wb:
    move.l  #0xDEAD1108, %d2
    bra     _report
_fail_empty_mem:
    move.l  #0xDEAD1109, %d2
    bra     _report
_fail_empty_an:
    move.l  #0xDEAD110A, %d2
    bra     _report
_fail_an:
    move.l  #0xDEAD110B, %d2
    bra     _report
_fail_allzero:
    move.l  #0xDEAD110C, %d2
    bra     _report

_report:
    lea     PASS_SENT, %a1
    move.l  %d2, (%a1)
_hr:
    bra     _hr
