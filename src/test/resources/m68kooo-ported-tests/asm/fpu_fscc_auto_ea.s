| fpu_fscc_auto_ea.s — FScc <ea> with the two AUTO-UPDATE addressing modes.
|
| FScc is a BYTE operation: it writes 0x00 (false) or 0xFF (true) to a
| single byte at <ea>.  With -(An)/(An)+ that makes the An adjustment
| exactly 1 byte — except on A7, where the 68k byte rule rounds the
| adjustment to 2 so the stack pointer stays even.
|
| This pins the An delta, which the integer `Scc <ea>` sibling already
| has a directed fix-up for (OperationDecoder's line-5 `ss == 3` arm
| forces `o.size := Size.BYTE`, because `o.size` is what the offloaded
| EaDecoder call turns into `autoDelta`).  The F-line FScc arm did NOT
| carry that fix-up, so an FScc predecrement/postincrement stepped An by
| the OpSpec WORD default (2) and A7 never got the byte rule at all.
| MicroOpAssembler already forced the trailing STORE to BYTE, so the
| stored byte and its neighbours were right and only An was wrong —
| which is why no existing test caught it (fpu_fscc_matrix only uses
| plain (An)).
|
| Encodings (hand-assembled; `1111 001 001 mmmrrr` + a condition word):
|   opword = 0xF240 | ea       ext = 0x000F (FST, always true)
|                              ext = 0x0000 (FSF, always false)
|   0xF258 = fst  (%a0)+       (mode 011 reg 000)
|   0xF260 = fsf  -(%a0)       (mode 100 reg 000)
|   0xF25F = fst  (%a7)+       (mode 011 reg 111)
|   0xF267 = fsf  -(%a7)       (mode 100 reg 111)
| FST/FSF are the two FPCC-independent predicates, so no FP state has to
| be set up and neither can raise BSUN.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEADFC01 — (A0)+ did not step A0 by exactly 1
|   0xDEADFC02 — (A0)+ wrote the wrong byte value
|   0xDEADFC03 — (A0)+ disturbed a neighbouring byte (not a BYTE store)
|   0xDEADFC04 — -(A0) did not step A0 by exactly 1
|   0xDEADFC05 — -(A0) wrote the wrong byte value
|   0xDEADFC06 — -(A0) disturbed a neighbouring byte
|   0xDEADFC07 — (A7)+ did not step A7 by exactly 2 (byte-on-A7 rule)
|   0xDEADFC08 — (A7)+ wrote its byte at the wrong address
|   0xDEADFC09 — -(A7) did not step A7 by exactly 2 (byte-on-A7 rule)
|   0xDEADFC0A — -(A7) wrote its byte at the wrong address
|   0xDEADFC0B — vec 11 F-line: the FScc memory form is not decoded

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ BUF,       0x00020000
    .equ ALTSP,     0x00013000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line

    | Fill BUF..BUF+31 with 0x55 so any byte the instruction must NOT
    | touch is detectable.
    lea     BUF, %a1
    move.l  #0x55555555, %d0
    moveq   #0, %d1
_fill:
    move.l  %d0, 0(%a1,%d1.w)
    addq.l  #4, %d1
    cmp.l   #32, %d1
    bne     _fill

| ── (A0)+ : A0 must advance by exactly 1 ────────────────────────────
    lea     BUF+4, %a0
    .short  0xF258, 0x000F             | fst (%a0)+
    cmpa.l  #(BUF+5), %a0
    bne     _f01
    move.b  BUF+4, %d0
    cmp.b   #0xFF, %d0
    bne     _f02
    move.b  BUF+5, %d0                 | a WORD-sized step would have moved A0 here,
    cmp.b   #0x55, %d0                 | and a WORD store would have written it
    bne     _f03
    move.b  BUF+3, %d0
    cmp.b   #0x55, %d0
    bne     _f03

| ── -(A0) : A0 must retreat by exactly 1 ────────────────────────────
    lea     BUF+16, %a0
    .short  0xF260, 0x0000             | fsf -(%a0)
    cmpa.l  #(BUF+15), %a0
    bne     _f04
    move.b  BUF+15, %d0
    cmp.b   #0x00, %d0
    bne     _f05
    move.b  BUF+14, %d0
    cmp.b   #0x55, %d0
    bne     _f06
    move.b  BUF+16, %d0
    cmp.b   #0x55, %d0
    bne     _f06

| ── (A7)+ : the byte-on-A7 rule -> A7 advances by 2, byte at OLD A7 ──
    lea     BUF+20, %a7
    .short  0xF25F, 0x000F             | fst (%a7)+
    move.l  %a7, %d0
    cmp.l   #(BUF+22), %d0
    bne     _f07_sp
    move.b  BUF+20, %d0
    cmp.b   #0xFF, %d0
    bne     _f08_sp
    move.b  BUF+21, %d0                | the odd byte of the word slot is NOT written
    cmp.b   #0x55, %d0
    bne     _f08_sp

| ── -(A7) : A7 retreats by 2, byte at the NEW (even) A7 ─────────────
    lea     BUF+28, %a7
    .short  0xF267, 0x0000             | fsf -(%a7)
    move.l  %a7, %d0
    cmp.l   #(BUF+26), %d0
    bne     _f09_sp
    move.b  BUF+26, %d0
    cmp.b   #0x00, %d0
    bne     _f0a_sp
    move.b  BUF+27, %d0
    cmp.b   #0x55, %d0
    bne     _f0a_sp

    lea     0x00010000, %a7
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

| The A7 stages ran with A7 inside BUF; restore a real stack before the
| reporting stores so nothing else can fault on the way out.
_f07_sp:
    lea     0x00010000, %a7
    bra     _f07
_f08_sp:
    lea     0x00010000, %a7
    bra     _f08
_f09_sp:
    lea     0x00010000, %a7
    bra     _f09
_f0a_sp:
    lea     0x00010000, %a7
    bra     _f0a

_f01:
    move.l  #0xDEADFC01, %d2
    bra     _report
_f02:
    move.l  #0xDEADFC02, %d2
    bra     _report
_f03:
    move.l  #0xDEADFC03, %d2
    bra     _report
_f04:
    move.l  #0xDEADFC04, %d2
    bra     _report
_f05:
    move.l  #0xDEADFC05, %d2
    bra     _report
_f06:
    move.l  #0xDEADFC06, %d2
    bra     _report
_f07:
    move.l  #0xDEADFC07, %d2
    bra     _report
_f08:
    move.l  #0xDEADFC08, %d2
    bra     _report
_f09:
    move.l  #0xDEADFC09, %d2
    bra     _report
_f0a:
    move.l  #0xDEADFC0A, %d2
    bra     _report
_fline:
    lea     0x00010000, %a7
    move.l  #0xDEADFC0B, %d2
    bra     _report

_report:
    lea     PASS_SENT, %a1
    move.l  %d2, (%a1)
_hr:
    bra     _hr
