| fpu_fmovem_x_fpsp_epilogue.s — structural mirror of the Q700 ROM's
|   FPSP F-line handler prologue/epilogue, asserting FP0-FP3 SURVIVE.
|
| The Q700 universal ROM's F-line handler (entry 0x4088D9FE) is:
|
|   4088d9fe:  link.w  %fp,#-192
|              fsave   %sp@-
|              movem.l %d0-%d1/%a0-%a1,%fp@(-192)
|              ...
|              fmovem.x %fp0-%fp3,%fp@(-176)     <- save
|              ...
|   4088e0f0:  movem.l %fp@(-192),%d0-%d1/%a0-%a1
|   4088e0f6:  fmovem.x %fp@(-176),%fp0-%fp3     <- restore
|   4088e0fc:  fmovem.l %fp@(-128),%fpiar/%fpsr/%fpcr
|   4088e102:  frestore %sp@+
|   4088e104:  unlk    %fp
|
| While FMOVEM.X moved no data, the restore put garbage back into
| FP0-FP3 on every dispatch, the emulation never took effect, and 7.5.3
| span in the handler forever (PC trace: 0x4088E0F0 -> 0x408375A0 ->
| 0x4088D9FE -> ...).  This test reproduces the SHAPE of that sequence —
| link / fsave / FMOVEM.X save / clobber / FMOVEM.X restore / frestore /
| unlk — and asserts the FP registers come back bit-identical.
|
| Note the save and the restore use DIFFERENT list-mode encodings in the
| ROM (F0xx = FPn->mem, D0xx = mem->FPn) but the SAME mask (0xF0), so
| this also pins that the two directions agree on register<->address
| assignment.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD1201 — vec 11 F-line
|   0xDEAD1202 — FP0-FP3 did not survive the save/clobber/restore
|   0xDEAD1203 — the clobber itself did not take (test is vacuous)
|   0xDEAD1204 — A6/A7 not restored by unlk

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SRC,       0x00021000
    .equ CLOB,      0x00021040
    .equ OUT,       0x00021080

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line
    suba.l  %a6, %a6

    | Known FP0-FP3 image.
    lea     SRC, %a0
    move.l  #0x3FFF0000, 0(%a0)
    move.l  #0x8000000F, 4(%a0)
    move.l  #0x0000000A, 8(%a0)
    move.l  #0x40000000, 12(%a0)
    move.l  #0xC90FDAA2, 16(%a0)
    move.l  #0x2168C235, 20(%a0)
    move.l  #0xBFFE0000, 24(%a0)
    move.l  #0xB17217F7, 28(%a0)
    move.l  #0xD1CF79AC, 32(%a0)
    move.l  #0x00000000, 36(%a0)
    move.l  #0x00000000, 40(%a0)
    move.l  #0x00000000, 44(%a0)

    | A deliberately different image used to clobber FP0-FP3 between
    | the save and the restore.
    lea     CLOB, %a0
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

    lea     SRC, %a0
    fmovem.x %a0@, %fp0-%fp3

    move.l  %a7, %d6                   | remember SP for the unlk check

    | ── ROM-shaped prologue ────────────────────────────────────────
    link.w  %fp, #-192
    fsave   %sp@-
    fmovem.x %fp0-%fp3, %fp@(-176)

    | Clobber FP0-FP3 with a different image.
    lea     CLOB, %a0
    fmovem.x %a0@, %fp0-%fp3

    | Prove the clobber actually landed, or the restore check below is
    | vacuous.  (This is the positive control for THIS test.)
    lea     OUT, %a1
    fmovem.x %fp0-%fp3, %a1@
    | OUT is now a byte-for-byte copy of CLOB: OUT+0 holds FP3 (the
    | highest-numbered register in the list, hence the lowest address),
    | which the clobber loaded from CLOB slot 0.  If FMOVEM.X moved
    | nothing this would still read the 0xDEADBEEF poison or zeros, and
    | the "did FP0-FP3 survive" check further down would be vacuous.
    move.l  OUT+0, %d0
    cmp.l   #0x11110000, %d0
    bne     _fail_vacuous
    move.l  OUT+36, %d0
    cmp.l   #0xAAAA0000, %d0           | FP0 -> highest address
    bne     _fail_vacuous

    | ── ROM-shaped epilogue ────────────────────────────────────────
    fmovem.x %fp@(-176), %fp0-%fp3
    frestore %sp@+
    unlk    %fp

    cmp.l   %a7, %d6
    bne     _fail_sp

    | FP0-FP3 must be bit-identical to SRC.
    lea     OUT, %a1
    move.l  #0xDEADBEEF, %d0
    moveq   #11, %d1
_poison:
    move.l  %d0, (%a1)+
    dbra    %d1, _poison

    lea     OUT, %a1
    fmovem.x %fp0-%fp3, %a1@

    lea     SRC, %a0
    lea     OUT, %a1
    moveq   #11, %d1
_cmp:
    move.l  (%a0)+, %d0
    cmp.l   (%a1)+, %d0
    bne     _fail_survive
    dbra    %d1, _cmp

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fline:
    move.l  #0xDEAD1201, %d2
    bra     _report
_fail_survive:
    move.l  #0xDEAD1202, %d2
    bra     _report
_fail_vacuous:
    move.l  #0xDEAD1203, %d2
    bra     _report
_fail_sp:
    move.l  #0xDEAD1204, %d2
    bra     _report

_report:
    lea     PASS_SENT, %a1
    move.l  %d2, (%a1)
_hr:
    bra     _hr
