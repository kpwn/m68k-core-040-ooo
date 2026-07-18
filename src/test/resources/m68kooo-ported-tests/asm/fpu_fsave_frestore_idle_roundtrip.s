| fpu_fsave_frestore_idle_roundtrip.s — FSAVE/FRESTORE IDLE-frame
|                                       FPCR/FPSR persistence.
|
| Goal: fsave_frestore_basic.s already proves the NULL frame writes
| 4 zeroed bytes and FRESTORE accepts it.  This test layers on:
| program FPCR & FPSR via FMOVE.L Dn,FPCR / Dn,FPSR (system-control
| forms), FSAVE the state, mutate FPCR+FPSR, then FRESTORE the saved
| frame — and verify FPCR/FPSR returned to the saved values via
| FMOVE.L FPCR,Dn / FPSR,Dn.
|
| FMOVE.L Dn,FPCR is opword 0xF200 + ea, ext = 100_xxxxxxx_x_x ...
| Specifically:
|   FMOVE.L Dn,FPCR : F200 | mode/reg(Dn=0..7) ; ext = 1001_REG_KFAC_000_0000
|   register-select bits in ext[12:10] = 100 -> FPCR, 010 -> FPSR
|
|   FMOVE.L D0,FPCR : opword=0xF200, ext = 0x9000
|   FMOVE.L D0,FPSR : opword=0xF200, ext = 0x8800
|   FMOVE.L FPCR,D0 : opword=0xF200, ext = 0xB000
|   FMOVE.L FPSR,D0 : opword=0xF200, ext = 0xA800
|
| EXPECTED OUTCOME: FMOVE.L Dn,FPCR/FPSR is not yet decoded (the
| F200 register-control set in the decode_1111.vh window matches
| only ext[15:13]=000 register-register ALU ops, not 100/010
| system-control).  The opword will fall to vec-11 F-line trap.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 — vec-11 F-line (FMOVE.L Dn,FPCR/FPSR undecoded)
|   0xDEAD0F07 — FPCR mismatch after FRESTORE
|   0xDEAD0F08 — FPSR mismatch after FRESTORE
|
| OBSERVED on main: assembles; runtime DEAD0F01 (system-control FMOVE.L
| not decoded).  Recorded as expected.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SAVE_BUF,  0x00020000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line

    | Program FPCR with a non-zero value (round-to-zero; precision=ext)
    move.l  #0x00000010, %d0           | RND=01 (zero), PREC=00 (extended)
    .short  0xF200, 0x9000             | FMOVE.L D0,FPCR
    | Program FPSR with a sentinel-shaped non-zero value.
    move.l  #0x12340000, %d0
    .short  0xF200, 0x8800             | FMOVE.L D0,FPSR

    | FSAVE -(A7) — IDLE frame (4 bytes).
    .short  0xF327                     | FSAVE -(A7)

    | Mutate FPCR/FPSR to bogus values.
    move.l  #0xFFFFFFFF, %d0
    .short  0xF200, 0x9000             | FMOVE.L D0,FPCR
    move.l  #0xCCCCCCCC, %d0
    .short  0xF200, 0x8800             | FMOVE.L D0,FPSR

    | FRESTORE (A7)+ — pop the saved frame, restoring FPCR/FPSR.
    .short  0xF35F                     | FRESTORE (A7)+

    | Read back FPCR; should be 0x00000010 again.
    .short  0xF200, 0xB000             | FMOVE.L FPCR,D0
    cmp.l   #0x00000010, %d0
    bne     _fail_fpcr

    | Read back FPSR; should be 0x12340000 again.
    .short  0xF200, 0xA800             | FMOVE.L FPSR,D0
    cmp.l   #0x12340000, %d0
    bne     _fail_fpsr

    | PASS
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_fpcr:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F07, %d2
    move.l  %d2, (%a1)
_halt_fpcr:
    bra     _halt_fpcr

_fail_fpsr:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F08, %d2
    move.l  %d2, (%a1)
_halt_fpsr:
    bra     _halt_fpsr

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_halt_fline:
    bra     _halt_fline
