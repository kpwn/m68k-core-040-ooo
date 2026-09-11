| fpu_fdbcc_ftrapcc.s — FDBcc Dn,disp16 and FTRAPcc, the rest of F-line type 001.
|
| Both mirror their line-5 twins; the only differences are that the condition comes from
| the FP extension word (cc[3:0]) instead of the opword's cccc field, and that the branch
| EU reads FPCC instead of the integer CCR.
|
|   FDBcc   = 1111 001 001 001rrr , ext = cc , then disp16   -> 3 words
|   FTRAPcc = 1111 001 001 111 0tt, ext = cc , tt: 4=no operand, 2=#data16, 3=#data32
|
| FDBcc semantics (same as DBcc): if the condition is TRUE, fall through; otherwise
| Dn.W -= 1 and branch unless Dn.W went to -1.  Note the sense — the condition TERMINATES
| the loop, it does not continue it.
|
| PASS sentinel: 0xC0FFEE00.  FAIL: 0xDEAD0D<nn>.  0xDEAD0D7F = unexpected F-line trap.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line
    move.l  #_trap7, 0x0000001C        | vec  7 (TRAPcc / conditional trap)

    | FP0 = 1.0, FP1 = 2.0 -> FCMP FP1,FP0 : N=1, Z=0, NAN=0
    moveq   #1, %d0
    moveq   #2, %d1
    .short  0xF200, 0x4000             | FMOVE.L D0,FP0
    .short  0xF201, 0x4080             | FMOVE.L D1,FP1
    .short  0xF200, 0x0438             | FCMP.X  FP1,FP0

    | ---- FDBcc with a FALSE condition loops until Dn.W hits -1 ----
    | FDBOGT D3 (0xF248|reg, reg=3 -> 0xF24B): OGT is FALSE here, so it decrements and
    | branches every iteration.  D3.W = 3 -> iterations at 3,2,1,0, then D3.W = -1 and it
    | falls through.  D4 counts iterations and must end at 4; D3's UPPER word must survive.
    move.l  #0x00070003, %d3
    moveq   #0, %d4
_loop3:
    addq.l  #1, %d4
    .short  0xF24B, 0x0002             | FDBOGT D3,disp
    .short  _loop3 - .
    cmp.l   #4, %d4
    bne     _f01
    cmp.l   #0x0007FFFF, %d3           | upper word preserved, low word = -1
    bne     _f02

    | ---- FDBcc with a TRUE condition falls through IMMEDIATELY, Dn untouched ----
    move.l  #0x00070003, %d3
    moveq   #0, %d4
    .short  0xF24B, 0x0004             | FDBOLT D3 : OLT is TRUE -> fall through
    .short  _f03 - .
    cmp.l   #0x00070003, %d3           | Dn must be UNCHANGED when the condition is true
    bne     _f04

    | ---- FTRAPcc, no-operand form (reg 4), condition FALSE -> no trap ----
    moveq   #0, %d5
    .short  0xF27C, 0x0002             | FTRAPOGT : false here -> must NOT trap
    tst.l   %d5
    bne     _f05

    | ---- FTRAPcc, no-operand form, condition TRUE -> vector 7 ----
    .short  0xF27C, 0x0004             | FTRAPOLT : true here -> must trap
    tst.l   %d5
    beq     _f06                       | handler sets D5; still 0 means it did not fire

    | ---- FTRAPcc with a #data16 operand (reg 2): condition FALSE, must skip 3 words ----
    moveq   #0, %d5
    .short  0xF27A, 0x0002, 0x1234     | FTRAPOGT.W #0x1234 : false -> no trap, skip operand
    tst.l   %d5
    bne     _f07

    | ---- FTRAPcc with a #data32 operand (reg 3): condition FALSE, must skip 4 words ----
    .short  0xF27B, 0x0002, 0xDEAD, 0xBEEF   | FTRAPOGT.L #imm32 : false -> no trap
    tst.l   %d5
    bne     _f08

_pass:
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

| vector 7: record it and resume after the trapping instruction.  The frame's stacked PC
| is already nextPc (faultUsesNextPc), so a plain RTE resumes correctly.
_trap7:
    moveq   #1, %d5
    rte

_f01: moveq #0x01, %d2
    bra _report
_f02: moveq #0x02, %d2
    bra _report
_f03: moveq #0x03, %d2
    bra _report
_f04: moveq #0x04, %d2
    bra _report
_f05: moveq #0x05, %d2
    bra _report
_f06: moveq #0x06, %d2
    bra _report
_f07: moveq #0x07, %d2
    bra _report
_f08: moveq #0x08, %d2
    bra _report
_fline: moveq #0x7F, %d2

_report:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0D00, %d3
    or.l    %d2, %d3
    move.l  %d3, (%a1)
_fhalt:
    bra     _fhalt
