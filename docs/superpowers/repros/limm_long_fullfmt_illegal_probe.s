| Isolation probe #3: is the line-0 full-format illegal CMPI-specific, or does any
| LONG (.L) immediate with a full-format EA trip it?  Ordered so the answer is the
| FIRST sentinel written.  Encodings identical to probe #2, only the order changed.
    .text
    .org 0
_start:
    lea     0x00115f00, %a7
    lea     0x00000010, %a0
    move.l  #_illegal, (%a0)
    moveq   #0, %d0

    | A: ORI.W #imm, FULL format, IS=0, index D0.L, LONG bd -- the KNOWN-GOOD control
    |    (this is the shape imm_src_fullfmt_long_bd_dst.s case C already passes).
    |    ORI.W = 0x0040 | mode=110 reg=000 -> 0x0070 ; ext 0x0930 = IS=0, BDSIZE=11
    move.l  #0xAAAA00A0, %d6
    lea     0x00100000, %a0
    .word   0x0070, 0xF000, 0x0930, 0x0001, 0x5400

    | B: ORI.L #imm, FULL format, IS=0, LONG bd -- same op, LONG immediate (shift 2)
    |    ORI.L = 0x0080 | mode/reg -> 0x00B0
    move.l  #0xAAAA00B0, %d6
    lea     0x00100000, %a0
    .word   0x00B0, 0x0000, 0x0001, 0x0930, 0x0001, 0x5400

    | C: ADDI.L #imm, FULL format, IS=1, LONG bd -- different op, LONG immediate
    move.l  #0xAAAA00C0, %d6
    lea     0x00100000, %a0
    .word   0x06B0, 0x0000, 0x0001, 0x8170, 0x0001, 0x5400

    | D: CMPI.L #imm, FULL format, IS=0, WORD bd -- probe #2's failing case
    move.l  #0xAAAA00D0, %d6
    lea     0x00115400, %a0
    .word   0x0CB0, 0x316D, 0x6567, 0x0920, 0x0000

    move.l  #0xC0FFEE00, %d6
_pass:
    lea     0xFFFF0000, %a0
    move.l  %d6, (%a0)
_halt:
    bra     _halt
_illegal:
    lea     0xFFFF0000, %a0
    move.l  %d6, (%a0)
_halt_ill:
    bra     _halt_ill
