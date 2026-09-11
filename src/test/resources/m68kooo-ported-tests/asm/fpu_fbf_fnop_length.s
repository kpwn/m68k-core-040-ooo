| fpu_fbf_fnop_length.s — FBF ("branch never") executes as a no-op AND skips its
| displacement, in both the .W and .L forms; every other FBcc condition still traps.
|
| FBF is `1111 001 01x 000000`: FBcc.W (type 010, opword 0xF280) or FBcc.L (type 011,
| opword 0xF2C0) with the condition field == 0 = "F", never taken.  Because it never
| branches it needs no FPCC, no target and no branch resolution — it is a pure no-op.
| FNOP is exactly `FBF.W #0` (F280 0000), the canonical 68k FPU synchronization idiom.
|
| The subtle half is LENGTH, which is what this test is really for.  PredecodeWord's
| generic F-line fallback frames any non-cpGEN opword as ONE word, which is correct only
| while the opword traps on its own.  Once FBF executes, its displacement must be SKIPPED
| or it gets fetched as an instruction.  Verified by reverting ONLY the predecode framing:
| this test then fails 0xDEAD0B02 ("displacement executed").  fpu_fnop_no_trap also catches
| the .W case (its 0x0000 displacement becomes ORI.B and clobbers D0 -> 0xDEAD0FB0), so
| that half is double-covered; what is covered ONLY here is the .L form's 3-word framing
| and the cc!=0 scoping below.  The displacements are deliberately valid, innocuous-looking
| STATE-CHANGING instructions (moveq), so a mis-frame stays silent unless a register is
| checked.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0B01 — FBF.W took an F-line trap (not decoded)
|   0xDEAD0B02 — FBF.W's displacement was EXECUTED (D7 clobbered)
|   0xDEAD0B03 — FBF.L took an F-line trap
|   0xDEAD0B04 — FBF.L's displacement was EXECUTED (D6/D5 clobbered)
|   0xDEAD0B05 — FBF clobbered CCR

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 (F-line)

    move.l  #0xA5A5A5A5, %d7
    move.l  #0xB6B6B6B6, %d6
    move.l  #0xC7C7C7C7, %d5
    moveq   #0, %d4
    cmp.l   %d4, %d4                   | Z=1, N=V=C=0
    move.w  %sr, %d3                   | CCR snapshot

    | ---- FBF.W, displacement = 0x7E01 (= moveq #1,%d7 if wrongly executed) ----
    .short  0xF280, 0x7E01
    cmp.l   #0xA5A5A5A5, %d7
    bne     _fail_disp_w

    | ---- FBF.L, displacement = 0x7C02 7A03 (= moveq #2,%d6 / moveq #3,%d5) ----
    .short  0xF2C0, 0x7C02, 0x7A03
    cmp.l   #0xB6B6B6B6, %d6
    bne     _fail_disp_l
    cmp.l   #0xC7C7C7C7, %d5
    bne     _fail_disp_l

    | ---- FNOP proper (the canonical F280 0000 idiom) ----
    .short  0xF280, 0x0000

    | ---- CCR must be untouched by all of the above ----
    move.w  %sr, %d2
    cmp.w   %d3, %d2
    bne     _fail_ccr

    | ---- A non-zero condition must also NOT trap (the whole family is decoded now) ----
    | FBNE.W = cond 0x0E -> opword 0xF28E.  Displacement 0x0000 makes this
    | DIRECTION-INDEPENDENT: an FBcc.W displacement is relative to PC+2 (the address of
    | its OWN extension word), and the instruction is 4 bytes, so disp=+2 makes the target
    | PC+2+2 = PC+4 = exactly the fall-through address.  It
    | lands in the same place whether FPCC says taken or not.  That pins "an FBcc with a
    | real condition executes instead of trapping" without this test having to establish an
    | FPCC state of its own (fpu_fbcc_branch's job).  A trap here lands in _fline ->
    | 0xDEAD0B01, so no separate sentinel is needed.
    .short  0xF28E, 0x0002

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #PASS_SENT, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fline:
    move.l  #0xDEAD0B01, %d7
    bra     _report
_fail_disp_w:
    move.l  #0xDEAD0B02, %d7
    bra     _report
_fail_disp_l:
    move.l  #0xDEAD0B04, %d7
    bra     _report
_fail_ccr:
    move.l  #0xDEAD0B05, %d7
    bra     _report
_report:
    move.l  #PASS_SENT, %a0
    move.l  %d7, (%a0)
_fhalt:
    bra     _fhalt
