| fpu_fadd_exact_single.s — FADD/FSUB/FMUL/FDIV with exact-single inputs.
|
| Goal: do four arithmetic ops where every intermediate value AND the
| final result are exactly representable in IEEE-754 single, so round-
| trip via FMOVE.S to memory and back is bit-identical.
|
| Sequence (all values are integer-valued floats, exact in single):
|   FP0 := 1.0       (FMOVE.S #0x3F800000,FP0 — but immediate forms are
|                     decode-heavy; load via D0 + FMOVE.S Dn,FPn instead)
|   FP1 := 2.0
|   FP2 := FP0 + FP1 == 3.0    (verify == 0x40400000)
|   FP3 := FP2 - FP1 == 1.0    (verify == 0x3F800000)
|   FP4 := FP3 * #2 ... actually FP4 := FP2 * FP1 == 6.0 (0x40C00000)
|   FP5 := FP4 / FP1 == 3.0    (verify == 0x40400000)
|
| Each verify is an FMOVE.S FPn,(A0) followed by an integer cmp.l.
|
| FMOVE.S Dn,FPn ext = 0x4000 | (Dn<<10) | (FPn<<7)
| FMOVE.S FPn,(A0) opword = 0xF210 ; ext = 0x6400 | (FPn<<7)
| (bug fix 2026-07-11, found via fuzz-corpus widening: this FP->EA
| direction's source FPn is at ext[9:7], format specifier at
| ext[12:10] -- SYMMETRIC with the EA->FP load direction, confirmed
| against GNU as.  decode_1111.vh used to read FPn from ext[12:10]
| instead, invisible only for FPn==FP0.  This file's `.short` values
| below were originally hand-picked to match that bug (FPn<<10) and
| have been corrected to FPn<<7 with the base format=001/single
| (0x6400) matching what `fmove.s` actually assembles to.)
| FADD.X FPm,FPn ext = (FPm<<10) | (FPn<<7) | 0x22
| FSUB.X FPm,FPn ext = (FPm<<10) | (FPn<<7) | 0x28
| FMUL.X FPm,FPn ext = (FPm<<10) | (FPn<<7) | 0x23
| FDIV.X FPm,FPn ext = (FPm<<10) | (FPn<<7) | 0x20
|
| EXPECTED OUTCOME: Internal FADD/FSUB/FMUL/FDIV register-register
| paths exist (per fpu_reg_rr_smoke.s) so the arithmetic should
| commit.  The verification path uses FMOVE.S FPn,(An) which may
| F-line trap (decode hole) — handler writes DEAD0F01.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 — vec-11 F-line (no FMOVE.S FPn,(An) decode)
|   0xDEAD0F03..0F06 — arithmetic mismatch in step 3..6
|
| OBSERVED on main: assembles; runtime DEAD0F01 (FMOVE.S FPn,(An)
| undecoded).  Recorded as expected.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SCRATCH,   0x00020000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C
    lea     SCRATCH, %a0

    | FP0 := 1.0
    move.l  #0x3F800000, %d0
    .short  0xF200, 0x4000             | FMOVE.S D0,FP0

    | FP1 := 2.0
    move.l  #0x40000000, %d0
    .short  0xF200, 0x4080             | FMOVE.S D0,FP1 (Dn=0,FPn=1 -> ext=0x4080)

    | FP2 := FP0 + FP1.  ext = (1<<10)|(2<<7)|0x22 = 0x0522
    .short  0xF200, 0x0522             | FADD.X FP1,FP2 (FP2 := FP1; FP2 += FP0?)
    | NOTE: FADD.X FPm,FPn computes FPn := FPn + FPm.  We need FP2 to
    | start as FP0; do FMOVE FP0,FP2 first.
    | (1<<10)|(2<<7)|0x00 = 0x0500
    | But we already issued the FADD above with FP2 unset (FP2 reset = +0.0).
    | Re-do as: FP2 := FP0 (FMOVE), then FP2 := FP2 + FP1.
    | The earlier FADD set FP2 to 0+2=2.0; OK to overwrite below.

    | FP2 := FP0 (FMOVE.X FP0,FP2). ext = (0<<10)|(2<<7)|0x00 = 0x0100
    .short  0xF200, 0x0100             | FMOVE.X FP0,FP2
    | FP2 += FP1.  ext = (1<<10)|(2<<7)|0x22 = 0x0522
    .short  0xF200, 0x0522             | FADD.X FP1,FP2
    | Verify FP2 == 3.0 == 0x40400000 via FMOVE.S FP2,(A0)
    | FP→EA single: ext = 0x6400 | (FPn<<7).  FP2 = 2 → ext = 0x6500
    .short  0xF210, 0x6500
    move.l  (%a0), %d1
    cmp.l   #0x40400000, %d1
    bne     _fail_3

    | FP3 := FP2 - FP1.  FMOVE FP2,FP3 then FSUB FP1,FP3.
    | FMOVE.X FP2,FP3: ext = (2<<10)|(3<<7)|0 = 0x0980
    .short  0xF200, 0x0980             | FMOVE.X FP2,FP3
    | FSUB.X FP1,FP3: ext = (1<<10)|(3<<7)|0x28 = 0x05A8
    .short  0xF200, 0x05A8
    | Verify FP3 == 1.0 == 0x3F800000.  FMOVE.S FP3,(A0): ext = 0x6580 (FP3<<7)
    .short  0xF210, 0x6580
    move.l  (%a0), %d1
    cmp.l   #0x3F800000, %d1
    bne     _fail_4

    | FP4 := FP2 * FP1.  FMOVE FP2,FP4 then FMUL FP1,FP4.
    | FMOVE.X FP2,FP4: ext = (2<<10)|(4<<7)|0 = 0x0A00
    .short  0xF200, 0x0A00
    | FMUL.X FP1,FP4: ext = (1<<10)|(4<<7)|0x23 = 0x0623
    .short  0xF200, 0x0623
    | Verify FP4 == 6.0 == 0x40C00000.  FMOVE.S FP4,(A0): ext = 0x6600 (FP4<<7)
    .short  0xF210, 0x6600
    move.l  (%a0), %d1
    cmp.l   #0x40C00000, %d1
    bne     _fail_5

    | FP5 := FP4 / FP1.  FMOVE FP4,FP5 then FDIV FP1,FP5.
    | FMOVE.X FP4,FP5: ext = (4<<10)|(5<<7)|0 = 0x1280
    .short  0xF200, 0x1280
    | FDIV.X FP1,FP5: ext = (1<<10)|(5<<7)|0x20 = 0x06A0
    .short  0xF200, 0x06A0
    | Verify FP5 == 3.0 == 0x40400000.  FMOVE.S FP5,(A0): ext = 0x6680 (FP5<<7)
    .short  0xF210, 0x6680
    move.l  (%a0), %d1
    cmp.l   #0x40400000, %d1
    bne     _fail_6

    | PASS
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_3:
    move.l  #0xDEAD0F03, %d2
    bra     _do_fail
_fail_4:
    move.l  #0xDEAD0F04, %d2
    bra     _do_fail
_fail_5:
    move.l  #0xDEAD0F05, %d2
    bra     _do_fail
_fail_6:
    move.l  #0xDEAD0F06, %d2
    bra     _do_fail
_do_fail:
    lea     PASS_SENT, %a1
    move.l  %d2, (%a1)
_halt_fail:
    bra     _halt_fail

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_halt_fline:
    bra     _halt_fline
