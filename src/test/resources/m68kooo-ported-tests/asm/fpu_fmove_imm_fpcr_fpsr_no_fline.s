| fpu_fmove_imm_fpcr_fpsr_no_fline.s — FMOVE.L #imm,FPCR / #imm,FPSR
|                                      (immediate source) must NOT
|                                      F-line trap.
|
| Root-cause context (2026-07-04 HW investigation, post-exc#4805 fix):
| real HW boots progress past the old lost-store wedge into a NEW
| deterministic wall — a storm of 32+ vec=0x0b (F-line) exceptions
| stuck at the identical PC 0x4088DB52, escalating to a vec=0x02
| double-fault with A7 wrapped to 0xFFFFFFFE.
|
| Disassembly of the ROM (files/420dbff3.rom) at that PC shows the
| trapping instruction is:
|
|   4088DB52:  F23C 8800 0000 0000   FMOVE.L #0,FPSR
|
| — sitting in the PROLOGUE of the ROM's own F-line (vector 11)
| exception handler (entered at ROM 0x4088DB1E), which begins with
| LINK/FSAVE/MOVEM/FMOVEM housekeeping and then clears FPSR/FPCR via
| exactly this immediate-source idiom before dispatching on the
| trapped opcode.
|
| Before this fix, decode_1111.vh's "FMOVE.L Dn,FPCR/Dn,FPSR" row
| required EA mode=000 (Dn direct) only — the immediate EA (mode=111
| reg=100) fell through to the F-line catch-all.  Effect: the F-line
| handler's OWN prologue re-triggered F-line on itself, recursing at
| the identical PC forever (A7 walks down one exception frame per
| re-entry, eventually wrapping through 0 -> double fault).  This
| test is the minimal directed repro + regression guard for that
| specific decode gap.
|
| Sequence:
|   1. FMOVE.L #0,FPSR / #0,FPCR (the exact recursion trigger).
|   2. FMOVE.L #imm,FPSR / #imm,FPCR with non-zero sentinel values,
|      read back via the existing (already-working) FMOVE.L FPx,Dn
|      read path and compare.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 — vec-11 F-line (immediate EA still undecoded — the
|                exact bug this test targets)
|   0xDEAD0F21 — FPCR mismatch after immediate write
|   0xDEAD0F22 — FPSR mismatch after immediate write

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line

    | Step 1: the exact recursion trigger — FMOVE.L #0,FPSR / #0,FPCR.
    | opword=F23C (mode=111 reg=100, immediate long EA), ext1=8800
    | (write dir, FPSR select), imm32=0.
    .short  0xF23C, 0x8800, 0x0000, 0x0000   | FMOVE.L #0,FPSR
    .short  0xF23C, 0x9000, 0x0000, 0x0000   | FMOVE.L #0,FPCR

    | Step 2: non-zero sentinel values, round-tripped through the
    | already-working FMOVE.L FPx,Dn read path.
    .short  0xF23C, 0x9000, 0x0000, 0x0021    | FMOVE.L #0x21,FPCR
    .short  0xF200, 0xB000                   | FMOVE.L FPCR,D0
    cmp.l   #0x00000021, %d0
    bne     _fail_fpcr

    .short  0xF23C, 0x8800, 0x1234, 0x0056    | FMOVE.L #0x12340056,FPSR
    .short  0xF200, 0xA800                    | FMOVE.L FPSR,D0
    cmp.l   #0x12340056, %d0
    bne     _fail_fpsr

    | PASS.
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_fpcr:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F21, %d2
    move.l  %d2, (%a1)
_halt_fpcr:
    bra     _halt_fpcr

_fail_fpsr:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F22, %d2
    move.l  %d2, (%a1)
_halt_fpsr:
    bra     _halt_fpsr

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_halt_fline:
    bra     _halt_fline
