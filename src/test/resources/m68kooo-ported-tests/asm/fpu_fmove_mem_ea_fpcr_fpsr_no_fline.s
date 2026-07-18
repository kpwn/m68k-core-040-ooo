| fpu_fmove_mem_ea_fpcr_fpsr_no_fline.s — FMOVE.L <ea>,FPCR/FPSR and
|                                         FMOVE.L FPCR/FPSR,<ea> for
|                                         memory addressing modes
|                                         (An), (An)+, -(An), (d16,An)
|                                         must NOT F-line trap.
|
| Root-cause context (2026-07-04 HW investigation, post-exc#4805 fix,
| companion to fpu_fmove_imm_fpcr_fpsr_no_fline.s): the immediate-EA
| form of this same instruction family (FMOVE.L #imm,FPCR/FPSR) was
| found undecoded and caused a deterministic F-line self-recursion
| wedge in the ROM's own FPSP handler prologue.  A full-ROM disassembly
| sweep for the same mnemonic turned up 100+ additional occurrences
| using memory-source/dest forms — (An), (An)+, -(An), (d16,An) — e.g.:
|
|   0x408164b4  FMOVE.L FPSR,-(SP)
|   0x408164cc  FMOVE.L (SP,8),FPSR
|   0x40848702  FMOVE.L (A0),FPCR
|   0x40848706  FMOVE.L FPCR,(A4)
|   0x4088d43c and 150+ more FMOVE.L (SP)+,FPCR / FPCR,-(SP) pairs
|     bracketing subroutine calls throughout the FPSP support code.
|
| These were equally undecoded before this fix (decode_1111.vh's row
| only recognised mode=000 Dn-direct) and would very likely have been
| the NEXT F-line-storm landmine once boot progressed past the
| immediate-EA fix.  This test is the directed repro + regression guard
| for that decode gap.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels (also 0xDEAD0F01 if a vec-11 F-line still fires):
|   0xDEAD0F31 — write (An)     mismatch
|   0xDEAD0F32 — write (An)+    mismatch or A0 not post-incremented
|   0xDEAD0F33 — write -(An)    mismatch or A0 not pre-decremented
|   0xDEAD0F34 — write (d16,An) mismatch
|   0xDEAD0F35 — read  (An)     mismatch
|   0xDEAD0F36 — read  (An)+    mismatch or A0 not post-incremented
|   0xDEAD0F37 — read  -(An)    mismatch or A0 not pre-decremented
|   0xDEAD0F38 — read  (d16,An) mismatch

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ BUF,       0x00020000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line

    | ── Test 1: FMOVE.L (A0),FPCR (write, (An)) ──────────────────────
    move.l  #0x11111111, BUF
    lea     BUF, %a0
    .short  0xF210, 0x9000              | FMOVE.L (A0),FPCR
    .short  0xF200, 0xB000               | FMOVE.L FPCR,D0
    cmp.l   #0x11111111, %d0
    bne     _fail_w_an

    | ── Test 2: FMOVE.L (A0)+,FPSR (write, (An)+) ────────────────────
    move.l  #0x22222222, BUF
    lea     BUF, %a0
    .short  0xF218, 0x8800               | FMOVE.L (A0)+,FPSR
    cmpa.l  #(BUF+4), %a0
    bne     _fail_w_anp
    .short  0xF200, 0xA800               | FMOVE.L FPSR,D0
    cmp.l   #0x22222222, %d0
    bne     _fail_w_anp

    | ── Test 3: FMOVE.L -(A0),FPCR (write, -(An)) ────────────────────
    move.l  #0x33333333, BUF
    lea     (BUF+4), %a0
    .short  0xF220, 0x9000               | FMOVE.L -(A0),FPCR
    cmpa.l  #BUF, %a0
    bne     _fail_w_pan
    .short  0xF200, 0xB000               | FMOVE.L FPCR,D0
    cmp.l   #0x33333333, %d0
    bne     _fail_w_pan

    | ── Test 4: FMOVE.L (4,A0),FPSR (write, (d16,An)) ────────────────
    move.l  #0x44444444, BUF+4
    lea     BUF, %a0
    .short  0xF228, 0x8800, 0x0004       | FMOVE.L (4,A0),FPSR
    .short  0xF200, 0xA800               | FMOVE.L FPSR,D0
    cmp.l   #0x44444444, %d0
    bne     _fail_w_d16an

    | ── Test 5: FMOVE.L FPCR,(A0) (read, (An)) ───────────────────────
    move.l  #0x55555555, %d0
    .short  0xF200, 0x9000               | FMOVE.L D0,FPCR
    clr.l   BUF
    lea     BUF, %a0
    .short  0xF210, 0xB000               | FMOVE.L FPCR,(A0)
    move.l  BUF, %d1
    cmp.l   #0x55555555, %d1
    bne     _fail_r_an

    | ── Test 6: FMOVE.L FPSR,(A0)+ (read, (An)+) ─────────────────────
    move.l  #0x66666666, %d0
    .short  0xF200, 0x8800               | FMOVE.L D0,FPSR
    clr.l   BUF
    lea     BUF, %a0
    .short  0xF218, 0xA800               | FMOVE.L FPSR,(A0)+
    cmpa.l  #(BUF+4), %a0
    bne     _fail_r_anp
    move.l  BUF, %d1
    cmp.l   #0x66666666, %d1
    bne     _fail_r_anp

    | ── Test 7: FMOVE.L FPCR,-(A0) (read, -(An)) ─────────────────────
    move.l  #0x77777777, %d0
    .short  0xF200, 0x9000               | FMOVE.L D0,FPCR
    clr.l   BUF
    lea     (BUF+4), %a0
    .short  0xF220, 0xB000               | FMOVE.L FPCR,-(A0)
    cmpa.l  #BUF, %a0
    bne     _fail_r_pan
    move.l  BUF, %d1
    cmp.l   #0x77777777, %d1
    bne     _fail_r_pan

    | ── Test 8: FMOVE.L FPSR,(4,A0) (read, (d16,An)) ─────────────────
    move.l  #0x88888888, %d0
    .short  0xF200, 0x8800               | FMOVE.L D0,FPSR
    clr.l   BUF+4
    lea     BUF, %a0
    .short  0xF228, 0xA800, 0x0004       | FMOVE.L FPSR,(4,A0)
    move.l  BUF+4, %d1
    cmp.l   #0x88888888, %d1
    bne     _fail_r_d16an

    | PASS.
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_w_an:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F31, %d2
    move.l  %d2, (%a1)
    bra     _halt

_fail_w_anp:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F32, %d2
    move.l  %d2, (%a1)
    bra     _halt

_fail_w_pan:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F33, %d2
    move.l  %d2, (%a1)
    bra     _halt

_fail_w_d16an:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F34, %d2
    move.l  %d2, (%a1)
    bra     _halt

_fail_r_an:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F35, %d2
    move.l  %d2, (%a1)
    bra     _halt

_fail_r_anp:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F36, %d2
    move.l  %d2, (%a1)
    bra     _halt

_fail_r_pan:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F37, %d2
    move.l  %d2, (%a1)
    bra     _halt

_fail_r_d16an:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F38, %d2
    move.l  %d2, (%a1)
    bra     _halt

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
    bra     _halt
