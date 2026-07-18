| scc_basic.s — Scc <ea>: set byte conditional.
|
| Scc writes 0xFF if cc_true, 0x00 otherwise, into the low byte of Dn
| preserving the upper 24 bits.  Condition code is op[11:8] (same
| encoding as Bcc / DBcc).  No CCR modification.
|
| Cases:
|   1. ST  D0 (always true)  → D0[7:0] = 0xFF (preserves upper bytes).
|   2. SF  D1 (always false) → D1[7:0] = 0x00.
|   3. SEQ D2 after TST that sets Z=1  → D2 = 0xFF.
|   4. SEQ D3 after TST that clears Z  → D3 = 0x00.
|   5. SGT D4 after CMP that yields signed-greater → D4 = 0xFF.
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | Preload D0..D4 with a sentinel pattern to verify upper-bytes preservation.
    move.l  #0x11223344, %d0
    move.l  #0x55667788, %d1
    move.l  #0x99aabbcc, %d2
    move.l  #0xddeeff11, %d3
    move.l  #0x22334455, %d4

    | Case 1: ST D0 — unconditional set.  D0 should become 0x112233FF.
    st      %d0
    move.l  #0x112233ff, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | Case 2: SF D1 — unconditional clear.  D1 should become 0x55667700.
    sf      %d1
    move.l  #0x55667700, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | Case 3: SEQ D2 after TST that sets Z=1.
    moveq   #0, %d5
    tst.l   %d5                  | Z=1
    seq     %d2
    move.l  #0x99aabbff, %d7
    cmp.l   %d7, %d2
    bne     _fail

    | Case 4: SEQ D3 after TST that clears Z.
    moveq   #1, %d5
    tst.l   %d5                  | Z=0
    seq     %d3
    move.l  #0xddeeff00, %d7
    cmp.l   %d7, %d3
    bne     _fail

    | Case 5: SGT D4 — signed-greater after CMP.
    |   CMP #5, D5 with D5=10 → D5 > 5 signed → SGT true.
    moveq   #10, %d5
    cmpi.l  #5, %d5              | sets Z=0, N=0 (10-5=5), V=0 → GT true
    sgt     %d4
    move.l  #0x223344ff, %d7
    cmp.l   %d7, %d4
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
