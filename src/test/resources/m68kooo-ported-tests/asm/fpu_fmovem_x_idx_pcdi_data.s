| fpu_fmovem_x_idx_pcdi_data.s — FMOVEM.X real data through the two EA
|   modes gas will not assemble: brief-indexed (d8,An,Xn) and (d16,PC).
|
| Both were previously covered only for "did it emit the right NUMBER of
| bytes"; this pins the DATA and the base-register integrity.
|
| Encodings (hand-assembled; gas -m68040 rejects both spellings):
|   F230 D0C0 1004   FMOVEM.X (4,A0,D1.W),FP0-FP1
|   F230 F0C0 1004   FMOVEM.X FP0-FP1,(4,A0,D1.W)
|   F23A D080 <d16>  FMOVEM.X (d16,PC),FP0      <- load direction only
|
| For (d16,PC) the displacement base is the address of the disp16 word
| ITSELF, i.e. opword_pc + 4 (past the opword AND the register-list
| word) — this is the rule the sibling FMOVEM.L (d16,PC) block and the
| live ROM sites at 0x4089097C / 0x4089142A follow.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD1301 — vec 11 F-line
|   0xDEAD1302 — indexed round-trip mismatch
|   0xDEAD1303 — indexed EA clobbered A0 or D1
|   0xDEAD1304 — indexed store overran its 24-byte range
|   0xDEAD1305 — (d16,PC) load did not deliver the right data
|   0xDEAD1306 — (d16,PC) form trapped or used the wrong base

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ IBUF,      0x00022000
    .equ OBUF,      0x00022100

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line

    | ── Brief-indexed: EA = A0 + sx(D1.W) + 4 ──────────────────────
    lea     IBUF, %a0
    move.l  #16, %d1                   | so the EA is IBUF+20

    move.l  #0x3FFE0000, 20(%a0)       | slot 0
    move.l  #0xC0DEC0DE, 24(%a0)
    move.l  #0x1BADB002, 28(%a0)
    move.l  #0xBFFF0000, 32(%a0)       | slot 1
    move.l  #0xFEEDFACE, 36(%a0)
    move.l  #0x8BADF00D, 40(%a0)
    move.l  #0xCAFED00D, 44(%a0)       | past-end sentinel

    .short  0xF230, 0xD0C0, 0x1004     | FMOVEM.X (4,A0,D1.W),FP0-FP1

    cmpa.l  #IBUF, %a0
    bne     _fail_idx_regs
    cmp.l   #16, %d1
    bne     _fail_idx_regs

    | Store back through the SAME indexed EA into a different buffer.
    lea     OBUF, %a0
    move.l  #0xDEADBEEF, 20(%a0)
    move.l  #0xDEADBEEF, 24(%a0)
    move.l  #0xDEADBEEF, 28(%a0)
    move.l  #0xDEADBEEF, 32(%a0)
    move.l  #0xDEADBEEF, 36(%a0)
    move.l  #0xDEADBEEF, 40(%a0)
    move.l  #0xCAFED00D, 44(%a0)

    .short  0xF230, 0xF0C0, 0x1004     | FMOVEM.X FP0-FP1,(4,A0,D1.W)

    cmpa.l  #OBUF, %a0
    bne     _fail_idx_regs
    cmp.l   #16, %d1
    bne     _fail_idx_regs

    move.l  44(%a0), %d0
    cmp.l   #0xCAFED00D, %d0
    bne     _fail_idx_overrun

    lea     IBUF+20, %a0
    lea     OBUF+20, %a1
    moveq   #5, %d2
_cmpi:
    move.l  (%a0)+, %d0
    cmp.l   (%a1)+, %d0
    bne     _fail_idx
    dbra    %d2, _cmpi

    | ── (d16,PC) load ──────────────────────────────────────────────
    | FP0 must come back holding the 12 bytes at _pcdata.
    bra     _pcgo
    .align  2
_pcdata:
    .long   0x40020000
    .long   0xDEC0DE01
    .long   0x0FF1CE02
_pcgo:
_pcref:
    .short  0xF23A, 0xD080
    .short  _pcdata - (_pcref + 4)

    lea     OBUF+64, %a1
    move.l  #0xDEADBEEF, 0(%a1)
    move.l  #0xDEADBEEF, 4(%a1)
    move.l  #0xDEADBEEF, 8(%a1)
    fmovem.x %fp0, %a1@
    move.l  0(%a1), %d0
    cmp.l   #0x40020000, %d0
    bne     _fail_pcdi
    move.l  4(%a1), %d0
    cmp.l   #0xDEC0DE01, %d0
    bne     _fail_pcdi
    move.l  8(%a1), %d0
    cmp.l   #0x0FF1CE02, %d0
    bne     _fail_pcdi

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fline:
    move.l  #0xDEAD1301, %d2
    bra     _report
_fail_idx:
    move.l  #0xDEAD1302, %d2
    bra     _report
_fail_idx_regs:
    move.l  #0xDEAD1303, %d2
    bra     _report
_fail_idx_overrun:
    move.l  #0xDEAD1304, %d2
    bra     _report
_fail_pcdi:
    move.l  #0xDEAD1305, %d2
    bra     _report

_report:
    lea     PASS_SENT, %a1
    move.l  %d2, (%a1)
_hr:
    bra     _hr
