| move_l_fullext_src_d16_dst.s
|
| The Quadra 700 ROM A-line (toolbox) trap dispatcher at 0x408099b0 ends
| with a table lookup whose SOURCE uses a FULL-FORMAT extension word with
| the base register suppressed, and whose DESTINATION is (d16,A7):
|
|   408099c6:  2f70 25a0 1e00 0008
|              MOVE.L ($1E00,ZA0,D2.W*4),$8(A7)     traps $A800..$ABFF
|   408099e0:  2f70 25a0 0e00 0008
|              MOVE.L ($0E00,ZA0,D2.W*4),$8(A7)     traps $AC00..$AFFF
|
| Total length is 8 bytes: opword + full-format ext1 + word base
| displacement + the DESTINATION's d16.  That destination displacement is
| the THIRD extension word, which is the interesting part: a MOVE whose
| source consumes two extension words pushes the destination's own
| extension word out to ext3.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0004  vec-4 illegal-instruction trap  (decode gap)
|   0xDEAD0002  vec-2 bus error                 (wrong EA computed)
|   0xDEAD0003  vec-3 address error             (wrong EA computed)
|   0xDEAD0011  case 1 (negative index, $1E00 base) wrong value
|   0xDEAD0012  case 1 stored to the wrong place / guard below clobbered
|   0xDEAD0013  case 1 guard above clobbered
|   0xDEAD0021  case 2 (positive index, $0E00 base) wrong value
|   0xDEAD0031  case 3 (IS=1, index suppressed) wrong value
|   0xDEAD0041  case 4 (BS=0, real base register) wrong value

    .text
    .org 0

_start:
    move.l  #_illegal, 0x00000010     | vector 4  — illegal instruction
    move.l  #_buserr,  0x00000008     | vector 2  — bus error
    move.l  #_adrerr,  0x0000000C     | vector 3  — address error
    lea     0x00115f00, %a7

| ── Case 1: the exact ROM instruction at 0x408099c6, exercised with the
|    exact operand the _InitGraf ($A86E) dispatch produces.
|      D2.W = $A86E - $AC00 = $FC6E  (sign-extends to -0x392)
|      EA   = $1E00 + (-0x392)*4 = $1E00 - $E48 = $0FB8
|    A0 is loaded with garbage: BS=1 must suppress it entirely.
    move.l  #0x11223344, %d3
    move.l  #0x00000FB8, %a1
    move.l  #0x5A5A5A5A, -4(%a1)      | guard below
    move.l  %d3, (%a1)                | table entry
    move.l  #0xA5A5A5A5, 4(%a1)       | guard above
    move.l  #0xDEADBEEF, %a0          | must be ignored (BS=1)
    move.l  #0x0000FC6E, %d2
    .word   0x2F70, 0x25A0, 0x1E00, 0x0008
    move.l  8(%a7), %d0
    cmp.l   #0x11223344, %d0
    beq     1f
    move.l  #0xDEAD0011, %d7
    bra     _fail
1:  move.l  -4(%a1), %d0
    cmp.l   #0x5A5A5A5A, %d0
    beq     2f
    move.l  #0xDEAD0012, %d7
    bra     _fail
2:  move.l  4(%a1), %d0
    cmp.l   #0xA5A5A5A5, %d0
    beq     3f
    move.l  #0xDEAD0013, %d7
    bra     _fail
3:

| ── Case 2: the ROM instruction at 0x408099e0 ($0E00 base), positive
|    index — the >= $AC00 toolbox half of the same dispatcher.
|      D2.W = $0100 → EA = $0E00 + $0100*4 = $1200
    move.l  #0x778899AA, %d3
    move.l  #0x00001200, %a1
    move.l  %d3, (%a1)
    move.l  #0xCAFEBABE, %a0          | must be ignored (BS=1)
    move.l  #0x00000100, %d2
    .word   0x2F70, 0x25A0, 0x0E00, 0x0008
    move.l  8(%a7), %d0
    cmp.l   #0x778899AA, %d0
    beq     4f
    move.l  #0xDEAD0021, %d7
    bra     _fail
4:

| ── Case 3: same shape but IS=1 (index suppressed) — ext1 = 0x25E0.
|    EA = $1E00 + 0 = $1E00, index register ignored.
    move.l  #0x0BADF00D, %d3
    move.l  #0x00001E00, %a1
    move.l  %d3, (%a1)
    move.l  #0x7FFFFFFF, %d2          | must be ignored (IS=1)
    .word   0x2F70, 0x25E0, 0x1E00, 0x0008
    move.l  8(%a7), %d0
    cmp.l   #0x0BADF00D, %d0
    beq     5f
    move.l  #0xDEAD0031, %d7
    bra     _fail
5:

| ── Case 4: BS=0 — a real base register participates.  ext1 = 0x2520.
|    EA = A0 + $1000 + D2.W*4.  A0 = $00010000, D2.W = 8 → $00011020.
    move.l  #0x13579BDF, %d3
    move.l  #0x00011020, %a1
    move.l  %d3, (%a1)
    move.l  #0x00010000, %a0
    move.l  #0x00000008, %d2
    .word   0x2F70, 0x2520, 0x1000, 0x0008
    move.l  8(%a7), %d0
    cmp.l   #0x13579BDF, %d0
    beq     6f
    move.l  #0xDEAD0041, %d7
    bra     _fail
6:

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_illegal:
    move.l  #0xDEAD0004, %d7
    bra     _fail

_buserr:
    move.l  #0xDEAD0002, %d7
    bra     _fail

_adrerr:
    move.l  #0xDEAD0003, %d7
    bra     _fail

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
