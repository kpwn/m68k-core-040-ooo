| scc_d16_an_disp.s -- Scc (d16,An) sign-extension corners (task #235)
|
| Validates the V2 2-µop crack for Scc (d16,An), focusing on the
| sign-extension of the 16-bit displacement.  The corner I almost got
| wrong here was relying on dst_displacement being already sign-extended
| (it is — decode_ea_v2 emits long16 = {{16{ext1[15]}},ext1}).  This
| test exercises both positive and negative d16 plus EA bytes that span
| more than one longword.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Test 1: small positive d16, byte 0 of target longword.
    lea     0x00130000, %a0
    move.l  #0x11223344, 8(%a0)
    moveq   #0, %d5                  | Z=1
    tst.l   %d5
    seq     8(%a0)
    bne     _fail1
    move.l  8(%a0), %d0
    cmp.l   #0xff223344, %d0
    bne     _fail1

    | Test 2: large positive d16 (just under 0x7FFF) so we know the
    | high byte of d16 is non-zero — catches any "byte sign-extend" bug.
    lea     0x00132000, %a1
    move.l  #0x44556677, 0x4000(%a1) | EA = a1 + 0x4000
    moveq   #1, %d5                  | Z=0
    tst.l   %d5
    sne     0x4000(%a1)
    beq     _fail2
    move.l  0x4000(%a1), %d0
    cmp.l   #0xff556677, %d0
    bne     _fail2

    | Test 3: negative d16 — bytes BEFORE the An.  Set up a high An so
    | -0x100 lands inside our test region.
    lea     0x00134200, %a2
    move.l  #0x99aabbcc, -0x100(%a2) | EA = a2 - 0x100 = 0x00134100
    moveq   #0, %d5                  | Z=1, NE false
    tst.l   %d5
    sne     -0x100(%a2)
    bne     _fail3                   | Scc must preserve Z=1
    move.l  -0x100(%a2), %d0
    cmp.l   #0x00aabbcc, %d0
    bne     _fail3

    | Test 4: byte 3 (low byte) of an aligned longword via d16 = 0x103.
    lea     0x00136000, %a3
    move.l  #0x12345678, 0x100(%a3)
    move.l  #1, %d5
    cmpi.l  #2, %d5                  | C=1, N=1
    scs     0x103(%a3)               | true → 0xff into byte 3
    move.l  0x100(%a3), %d0
    cmp.l   #0x123456ff, %d0
    bne     _fail4

    | Test 5: A7 with d16 — byte target through the SP register, no
    | predec/postinc, so A7 stride doesn't apply.  Exercise that the
    | same V2 path handles A7 just like any other An.
    lea     0x00138000, %a7
    move.l  #0xaabbccdd, 0x10(%a7)
    moveq   #0, %d5                  | Z=1
    tst.l   %d5
    sf      0x11(%a7)                | unconditional 0x00, byte 1
    move.l  0x10(%a7), %d0
    cmp.l   #0xaa00ccdd, %d0
    bne     _fail5

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_fail1:
    bra     _halt_fail1

_fail2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_halt_fail2:
    bra     _halt_fail2

_fail3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
_halt_fail3:
    bra     _halt_fail3

_fail4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_halt_fail4:
    bra     _halt_fail4

_fail5:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0005, %d0
    move.l  %d0, (%a0)
_halt_fail5:
    bra     _halt_fail5
