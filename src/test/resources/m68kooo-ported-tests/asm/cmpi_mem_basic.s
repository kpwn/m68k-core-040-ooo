| cmpi_mem_basic.s — CMPI.{B,W,L} #imm,<ea> plain memory forms
|
| Exercises the CMPI.{B,W,L} #imm,(An) decode path plus the
| pre-existing CMPI register forms as shadowing regression coverage.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | ── CMPI.L #imm,Dn (regression) ─────────────────────────────────────
    moveq   #7, %d0
    cmpi.l  #7, %d0                 | Z=1
    bne     _fail
    cmpi.l  #9, %d0                 | 7-9 negative → N=1, Z=0
    beq     _fail
    bpl     _fail                   | N must be set

    | ── CMPI.L #imm,(An) match ──────────────────────────────────────────
    move.l  #0x00100100, %a0
    move.l  #0xA5A5A5A5, %d0
    move.l  %d0, (%a0)              | [A0] = 0xA5A5A5A5
    cmpi.l  #0xA5A5A5A5, (%a0)      | Z=1
    bne     _fail

    | ── CMPI.L #imm,(An) mismatch ──────────────────────────────────────
    cmpi.l  #0xDEADBEEF, (%a0)
    beq     _fail

    | ── Mismatch corner: 0 - 1 underflow → C=1, N=1 ────────────────────
    move.l  #0, %d0
    move.l  %d0, (%a0)              | [A0] = 0
    cmpi.l  #1, (%a0)               | 0 - 1 underflow
    bcc     _fail                   | C must be 1
    bpl     _fail                   | N must be 1

    | ── CMPI.W #imm,(An) match and mismatch ───────────────────────────
    move.l  #0x00100120, %a1
    move.l  #0x12340000, %d0
    move.l  %d0, (%a1)
    .word   0x0c51, 0x1234          | cmpi.w #0x1234,(%a1)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    .word   0x0c51, 0x1235          | 0x1234 - 0x1235 underflows
    bcc     _fail
    bpl     _fail
    beq     _fail
    bvs     _fail

    | ── CMPI.B #imm,(An) match and mismatch ───────────────────────────
    move.l  #0x00100140, %a1
    move.l  #0x7f000000, %d0
    move.l  %d0, (%a1)
    .word   0x0c11, 0x007f          | cmpi.b #0x7f,(%a1)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    .word   0x0c11, 0x0080          | 0x7f - 0x80 sets N/V/C
    bcc     _fail
    bpl     _fail
    beq     _fail
    bvc     _fail

    | ── PASS ───────────────────────────────────────────────────────────
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

    | ── FAIL ───────────────────────────────────────────────────────────
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail
