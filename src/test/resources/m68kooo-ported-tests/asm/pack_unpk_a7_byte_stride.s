| pack_unpk_a7_byte_stride.s — A7 on the BYTE side of PACK / UNPK
| adjusts by 2, not 1.
|
| PRM §3.2: a BYTE access through -(An) where An is A7 adjusts the
| stack pointer by 2 so it stays word-aligned.  That rule applies to
| whichever operand of PACK / UNPK is the byte:
|     PACK -(Ay),-(Ax)  — the DESTINATION is a byte
|     UNPK -(Ay),-(Ax)  — the SOURCE      is a byte
|
| Musashi agrees on both of these (EA_A7_PD_8() is REG_A[7] -= 2), so
| every expectation in THIS file is cross-validated against Musashi
| v4.60 in 68040 mode — states matched exactly.
| (The WORD side of the same two instructions is a different story;
|  see pack_unpk_a7_word_stride.s.)
|
| REGRESSION CONTEXT: decode_uop_assemble.v hard-coded the PACK/UNPK
| strides as 2/1 and 1/2 with no A7 case, even though the ABCD/SBCD arm
| six lines below already applied the rule.  Measured pre-fix:
|   lea 0x00100060,%a7 ; pack -(%a3),-(%a7),#0  -> A7 = 0x0010005F
|                                        correct  A7 = 0x0010005E
|
| DATA CHOICE: the operand values here are deliberately PALINDROMIC
| (0x3535 packs to 0x55; byte 0x55 unpacks to 0x3535) so that this test
| measures ADDRESS arithmetic only.  PACK/UNPK memory byte ORDER is a
| known deliberate deviation from Musashi documented in
| docs/isa_status.md, and is covered by pack_mem_mem.s / unpk_mem_mem.s;
| keeping it out of this file lets these expectations be Musashi-clean.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | ── PACK with A7 as DESTINATION (byte side) ────────────────────
    | Source: word 0x3535 at 0x00110000, A3 = 0x00110002 (word, -2).
    | Dest:   A7 = 0x00100060 -> must become 0x0010005E (byte via A7, -2).
    | packed = ((0x3535>>4)&0xF0) | (0x3535&0x0F) = 0x50 | 0x05 = 0x55.
    lea     0x00110000, %a2
    move.w  #0x3535, (%a2)
    move.l  #0xCCCCCCCC, 0x0010005C   | poison 0010005C..5F
    lea     0x00110002, %a3
    lea     0x00100060, %a7
    pack    -(%a3), -(%a7), #0
    move.l  %a7, %d0
    move.l  %a3, %d1
    lea     0x00010000, %a7

    cmp.l   #0x0010005E, %d0          | NOT 0x0010005F
    bne     _fail
    cmp.l   #0x00110000, %d1          | word source: -2
    bne     _fail
    move.b  0x0010005E, %d2
    and.l   #0xFF, %d2
    cmp.l   #0x55, %d2
    bne     _fail
    | The byte the pre-fix stride WOULD have written must be untouched.
    move.b  0x0010005F, %d3
    and.l   #0xFF, %d3
    cmp.l   #0xCC, %d3
    bne     _fail

    | ── UNPK with A7 as SOURCE (byte side) ─────────────────────────
    | Source: byte 0x55 at 0x00100200; A7 = 0x00100202 -> 0x00100200
    |         (byte via A7, -2 — so the byte MUST sit at A7-2).
    | Dest:   A4 = 0x00110082 -> 0x00110080 (word, -2).
    | word = ((0x55<<4)&0x0F00)|(0x55&0x0F) + 0x3030
    |      = 0x0505 + 0x3030 = 0x3535.
    move.b  #0x55, 0x00100200
    move.b  #0x99, 0x00100201         | must NOT be the byte read
    move.l  #0xDDDDDDDD, 0x00110080
    lea     0x00110082, %a4
    lea     0x00100202, %a7
    unpk    -(%a7), -(%a4), #0x3030
    move.l  %a7, %d0
    move.l  %a4, %d1
    lea     0x00010000, %a7

    cmp.l   #0x00100200, %d0          | NOT 0x00100201
    bne     _fail
    cmp.l   #0x00110080, %d1          | word dest: -2
    bne     _fail
    move.w  0x00110080, %d2
    and.l   #0xFFFF, %d2
    cmp.l   #0x3535, %d2
    bne     _fail

    | ── Regression guard: neither side A7 keeps strides 2/1 and 1/2 ─
    | PACK -(A2),-(A3): source word -2, dest byte -1.
    lea     0x00110200, %a2
    move.w  #0x3939, (%a2)
    move.l  #0xEEEEEEEE, 0x00110300
    lea     0x00110202, %a2
    lea     0x00110301, %a3
    pack    -(%a2), -(%a3), #0
    move.l  %a2, %d0
    move.l  %a3, %d1
    cmp.l   #0x00110200, %d0
    bne     _fail
    cmp.l   #0x00110300, %d1          | byte dest, non-A7: -1
    bne     _fail
    move.b  0x00110300, %d2
    and.l   #0xFF, %d2
    cmp.l   #0x99, %d2                | (0x3939>>4)&0xF0 | 0x09 = 0x99
    bne     _fail

    | UNPK -(A2),-(A3): source byte -1, dest word -2.
    move.b  #0x55, 0x00110400
    move.l  #0xEEEEEEEE, 0x00110500
    lea     0x00110401, %a2
    lea     0x00110502, %a3
    unpk    -(%a2), -(%a3), #0x3030
    move.l  %a2, %d0
    move.l  %a3, %d1
    cmp.l   #0x00110400, %d0          | byte source, non-A7: -1
    bne     _fail
    cmp.l   #0x00110500, %d1
    bne     _fail
    move.w  0x00110500, %d2
    and.l   #0xFFFF, %d2
    cmp.l   #0x3535, %d2
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
