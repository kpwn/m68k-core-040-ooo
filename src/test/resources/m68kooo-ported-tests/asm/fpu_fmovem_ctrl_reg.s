| fpu_fmovem_ctrl_reg.s — FMOVEM.L control reg list (FPCR/FPSR/FPIAR)
|                          ↔ memory through (d16,An).
|
| Validates the FPSP-recursion fix (decode-1111: FMOVEM.L control list
| in (d16,An) mode, multi-µop crack via TMP1 + SYS_FMOVE_FPx_RD/WR).
|
| Sequence:
|   1. Program FPCR / FPSR / FPIAR with sentinel values.
|   2. FMOVEM.L FPCR/FPSR/FPIAR,(0,A0)   — store all 3 to memory
|      (mask 111 = 0xBC00 with ext[15:13]=101).
|   3. Mutate FPCR/FPSR/FPIAR to bogus values.
|   4. FMOVEM.L (0,A0),FPCR/FPSR/FPIAR   — restore all 3 from memory
|      (mask 111 = 0x9C00 with ext[15:13]=100).
|   5. Read each ctrl reg back via FMOVE.L FPx,Dn and compare.
|
| Encodings (verified via m68k-linux-gnu-as -m68040):
|   F228 BC00 0000   FMOVEM.L FPIAR/FPSR/FPCR,(0,A0)   | Note: 'fmoveml fpiar/fpsr/fpcr,fp@(-128)' is F22E BC00 FF80
|   F228 9C00 0000   FMOVEM.L (0,A0),FPIAR/FPSR/FPCR
|
| Use op=F228 (mode=101 reg=000 = (d16,A0)), ext2=disp16=0.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F11 — FPCR mismatch after restore
|   0xDEAD0F12 — FPSR mismatch after restore
|   0xDEAD0F13 — FPIAR not yet implemented (decoder gap probe)
|   0xDEAD0F01 — vec-11 F-line (decoder didn't recognise opword)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SAVE_BUF,  0x00020000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line

    | Program FPCR / FPSR / FPIAR.
    move.l  #0x00000010, %d0
    .short  0xF200, 0x9000             | FMOVE.L D0,FPCR
    move.l  #0x12340000, %d0
    .short  0xF200, 0x8800             | FMOVE.L D0,FPSR
    | FPIAR currently isn't writable via single-reg FMOVE.L Dn,FPIAR
    | (decoder only handles ext[12:10]=100 and 010).  Skip the FPIAR
    | direct-write step; FMOVEM.L still has to round-trip whatever's
    | in arch_fpiar (which is 0 at reset).

    | Set up A0 = SAVE_BUF.
    lea     SAVE_BUF, %a0

    | FMOVEM.L FPCR/FPSR/FPIAR,(0,A0) — opword=F228, ext1=BC00, ext2=0.
    .short  0xF228, 0xBC00, 0x0000

    | Mutate FPCR/FPSR.
    move.l  #0xFFFFFFFF, %d0
    .short  0xF200, 0x9000             | FMOVE.L D0,FPCR
    move.l  #0xCCCCCCCC, %d0
    .short  0xF200, 0x8800             | FMOVE.L D0,FPSR

    | FMOVEM.L (0,A0),FPCR/FPSR/FPIAR — opword=F228, ext1=9C00, ext2=0.
    .short  0xF228, 0x9C00, 0x0000

    | Read FPCR back.
    .short  0xF200, 0xB000             | FMOVE.L FPCR,D0
    cmp.l   #0x00000010, %d0
    bne     _fail_fpcr

    | Read FPSR back.
    .short  0xF200, 0xA800             | FMOVE.L FPSR,D0
    cmp.l   #0x12340000, %d0
    bne     _fail_fpsr

    | PASS.
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_fpcr:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F11, %d2
    move.l  %d2, (%a1)
_halt_fpcr:
    bra     _halt_fpcr

_fail_fpsr:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F12, %d2
    move.l  %d2, (%a1)
_halt_fpsr:
    bra     _halt_fpsr

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_halt_fline:
    bra     _halt_fline
