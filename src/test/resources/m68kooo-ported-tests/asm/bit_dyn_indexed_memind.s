| bit_dyn_indexed_memind.s -- DYNAMIC bit-ops (bit number in Dn) with
| brief-indexed (d8,An,Xn) and full-format memind dst EAs.
|
| Regression for the System 7.0.1 real-HW boot blocker (2026-07-22):
| the Q700 ROM Toolbox BitTst/BitSet/BitClr utilities are
|   btst %d0,(0,%a1,%d1:l)   = 0131 1800   (ROM 0x4082EAF4)
|   bset %d0,(0,%a1,%d1:l)   = 01F1 1800   (ROM 0x4082EB02)
|   bclr %d0,(0,%a1,%d1:l)   = 01B1 1800   (ROM 0x4082EB0C)
| and these decoded as ILLEGAL (vec 4) -> SysError ID 3 ("illegal
| instruction" bomb dialog) the first time System 7 called BitSet.
| Dynamic indexed/memind bit-ops now ride the V2 static cracks with the
| raw Dn bit number on src_b, masked mod 8 by alu.v at SZ_BYTE.
|
| Covers:
|   1. bset %d0,(0,%a1,%d1:l)      -- EXACT ROM BitSet shape, and the
|      ROM's real operand pattern: D0 = ~bitnum (0xffe2 -> mod 8 = 2).
|   2. btst %d0,(0,%a1,%d1:l)      -- EXACT ROM BitTst shape, set+clear.
|   3. bclr %d3,(4,%a2,%d4:w)      -- Xn.W with dirty upper half
|      (sign-extended word index), non-zero d8.
|   4. bchg %d2,(0,%a3,%d1:l*4)    -- scale-4 brief index.
|   5. bset %d2,([0.W,%a2],4.W)    -- dynamic pre-indexed memind, OD.W.
|   6. btst %d0,([0.W,%a2],null)   -- dynamic memind, NULL OD.
|
| PASS: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | --- 1. bset %d0,(0,%a1,%d1:l) — the ROM BitSet shape ------------
    lea     0x0011b000, %a1
    move.l  #0x18, %d1              | byte offset via index reg
    lea     0x0011b018, %a0
    move.b  #0x00, (%a0)
    move.l  #0xffe2, %d0            | raw ~bitnum, mod 8 = 2
    .word   0x01F1, 0x1800          | bset %d0,(0,%a1,%d1:l)
    bne     _fail1                  | old bit clear -> Z=1
    moveq   #16, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.b  (%a0), %d6
    cmp.b   #0x04, %d6              | bit 2 set
    bne     _fail1

    | --- 2. btst %d0,(0,%a1,%d1:l) — the ROM BitTst shape ------------
    .word   0x0131, 0x1800          | btst %d0,(0,%a1,%d1:l)
    beq     _fail2                  | bit 2 is now set -> Z=0
    move.l  #0x0003, %d0            | bit 3 (clear in 0x04)
    .word   0x0131, 0x1800          | btst %d0,(0,%a1,%d1:l)
    bne     _fail2                  | bit clear -> Z=1

    | --- 3. bclr %d3,(4,%a2,%d4:w) — Xn.W, dirty upper half ----------
    lea     0x0011c000, %a2
    move.l  #0xDEAD0010, %d4        | Xn.W -> sx16(0x0010) = +0x10
    lea     0x0011c014, %a0         | 0x0011c000 + 4 + 0x10
    move.b  #0xFF, (%a0)
    move.l  #0x0B, %d3              | mod 8 = 3
    .word   0x07B2, 0x4004          | bclr %d3,(4,%a2,%d4:w)
    beq     _fail3                  | old bit set -> Z=0
    moveq   #16, %d7
3:  subq.l  #1, %d7
    bne     3b
    move.b  (%a0), %d6
    cmp.b   #0xF7, %d6              | bit 3 cleared
    bne     _fail3

    | --- 4. bchg %d2,(0,%a3,%d1:l*4) — scale-4 brief index -----------
    lea     0x0011d000, %a3
    move.l  #0x3, %d1               | *4 = +12
    lea     0x0011d00c, %a0
    move.b  #0x00, (%a0)
    move.l  #0x1F, %d2              | mod 8 = 7
    .word   0x0573, 0x1C00          | bchg %d2,(0,%a3,%d1:l*4)
    bne     _fail4                  | old bit clear -> Z=1
    moveq   #16, %d7
4:  subq.l  #1, %d7
    bne     4b
    move.b  (%a0), %d6
    cmp.b   #0x80, %d6              | bit 7 toggled on
    bne     _fail4

    | --- 5. bset %d2,([0.W,%a2],4.W) — dynamic memind, OD.W ----------
    lea     0x0011e000, %a2
    move.l  #0x0011e100, (%a2)      | inner pointer
    lea     0x0011e104, %a0
    move.b  #0x40, (%a0)
    move.l  #0x21, %d2              | mod 8 = 1
    .word   0x05F2, 0x0162, 0x0000, 0x0004  | bset %d2,([0,%a2],4)
    bne     _fail5                  | old bit 1 clear -> Z=1
    moveq   #16, %d7
5:  subq.l  #1, %d7
    bne     5b
    move.b  (%a0), %d6
    cmp.b   #0x42, %d6              | 0x40 | 0x02
    bne     _fail5

    | --- 6. btst %d0,([0.W,%a2],null) — dynamic memind, NULL OD ------
    lea     0x0011e200, %a0
    move.l  %a0, (%a2)              | repoint inner pointer
    move.b  #0x01, (%a0)
    move.l  #0x8, %d0               | mod 8 = 0
    .word   0x0132, 0x0161, 0x0000  | btst %d0,([0,%a2],null)
    beq     _fail6                  | bit 0 set -> Z=0

_pass:
    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a6)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d7
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d7
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d7

_fail:
    lea     0xFFFF0000, %a6
    move.l  %d7, (%a6)
_halt_fail:
    bra     _halt_fail
