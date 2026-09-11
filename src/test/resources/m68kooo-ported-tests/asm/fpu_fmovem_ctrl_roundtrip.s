| fpu_fmovem_ctrl_roundtrip.s — FMOVEM.L control regs must survive a
|                               -(A7) push / (A7)+ pop pair.
|
| This is the Q700 ROM's own FPSP idiom, byte-for-byte.  Verified in
| "420DBFF3 - Quadra 700&900 & PB140&170.ROM":
|
|   entry 0x408ed8e6  moveml  %d0-%d2/%a0-%a1,%sp@-
|         0x408ed8ea  f227 bc00  fmoveml %fpcr/%fpsr/%fpiar,%sp@-
|   exit  0x408ede90  f21f 9c00  fmoveml %sp@+,%fpcr/%fpsr/%fpiar
|         0x408ede94  moveml  %sp@+,%d0-%d2/%a0-%a1
|
| 8 push sites converge on that single pop.  The whole point of the
| sequence is to restore FPCR — rounding mode and precision control —
| unchanged across an FP exception, so this MUST round-trip on real
| silicon.  The two FMOVEM opwords below are copied verbatim from those
| ROM addresses.
|
| WHY THIS TEST EXISTS, and why it is the right kind of test:
| it is ORACLE-FREE.  It does not assert any particular memory layout,
| so it cannot encode someone's convention by mistake — it asserts only
| the invariant that a push followed by the mirrored pop returns the
| values you started with.  That invariant holds on ANY correct
| implementation regardless of which end FPCR lands on.
|
| It is also a real discriminator, not a tautology: this repo's vendored
| Musashi FAILS it.  m68kfpu.c fmove_fpcr() calls WRITE_EA_32() in the
| order FPCR,FPSR,FPIAR (so -(An) puts FPCR highest) but READ_EA_32() in
| that same order (so (An)+ reads FPCR from lowest), swapping FPCR and
| FPIAR across a save/restore.  Do NOT "fix" this test to match Musashi.
|
| The companion layout assertions live in fpu_fmovem_ctrl_predec.s.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD2000 — setup vacuous (references zero or indistinguishable);
|                the test would not be able to detect a swap
|   0xDEAD2001 — FPCR did not survive the round-trip  <-- the swap bug
|   0xDEAD2002 — FPSR did not survive the round-trip
|   0xDEAD2003 — A7 not restored by the push/pop pair
|   0xDEAD2004 — vec 11 F-line fired (control-list EA not decoded)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C
    move.l  %a7, %a3                   | remember SP

    move.l  #0x00000030, %d0
    .short  0xF200, 0x9000             | FMOVE.L D0,FPCR
    move.l  #0x0F000000, %d0
    .short  0xF200, 0x8800             | FMOVE.L D0,FPSR
    .short  0xF203, 0xB000             | FMOVE.L FPCR,D3   (reference)
    .short  0xF204, 0xA800             | FMOVE.L FPSR,D4   (reference)

    | Guard against a vacuous run: if FPCR reads back 0, or FPCR and FPSR
    | are indistinguishable, a swap would be invisible and a PASS here
    | would mean nothing.
    tst.l   %d3
    beq     _fail_vacuous
    cmp.l   %d3, %d4
    beq     _fail_vacuous

    .short  0xF227, 0xBC00             | FMOVEM.L FPCR/FPSR/FPIAR,-(A7)  [ROM 0x408ed8ea]

    moveq   #0, %d0                    | clobber both, so the pop must do the work
    .short  0xF200, 0x9000
    .short  0xF200, 0x8800

    .short  0xF21F, 0x9C00             | FMOVEM.L (A7)+,FPCR/FPSR/FPIAR  [ROM 0x408ede90]

    .short  0xF205, 0xB000             | FMOVE.L FPCR,D5
    .short  0xF206, 0xA800             | FMOVE.L FPSR,D6

    cmp.l   %d3, %d5
    bne     _fail_fpcr
    cmp.l   %d4, %d6
    bne     _fail_fpsr
    cmp.l   %a3, %a7
    bne     _fail_sp

    move.l  #0xC0FFEE00, %d2
    bra     _done

_fail_vacuous:
    move.l  #0xDEAD2000, %d2
    bra     _done
_fail_fpcr:
    move.l  #0xDEAD2001, %d2
    bra     _done
_fail_fpsr:
    move.l  #0xDEAD2002, %d2
    bra     _done
_fail_sp:
    move.l  #0xDEAD2003, %d2
    bra     _done
_fline:
    move.l  #0xDEAD2004, %d2
_done:
    lea     PASS_SENT, %a2
    move.l  %d2, (%a2)
_halt:
    bra     _halt
