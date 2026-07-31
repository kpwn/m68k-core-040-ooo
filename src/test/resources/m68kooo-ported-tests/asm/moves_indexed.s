| moves_indexed.s — Phase-2 item #5: MOVES.{B,W,L} (d8,An,Xn)
| brief-indexed.  Prior to this fix, v2_moves_fire's EA list only
| covered (An)/(An)+/-(An)/(d16,An)/(xxx).W/.L — no indexed form — and
| decode_0000.vh's legacy MOVES casez is fully empty for this shape, so
| it fell through to vec-4 ILLEGAL.
|
| Also regression-covers a real bug found WHILE fuzzing this fix (seed
| 9027): a MOVES.{B,W} LOAD landing directly in Rn zero-extended
| instead of preserving Rn's upper bits (every MOVES EA form had this
| bug, not just the new indexed one — see the ALU_MOV_MERGE fix in
| decode.v's v2_moves_fire block).  Test 2 below seeds D5 with
| distinguishing upper bits before a MOVES.B load specifically to catch
| a merge regression.
|
| Runs from cold-boot supervisor state (no MOVE-to-SR needed).
|
| PASS: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_unexpected_trap, 0x00000020    | vec 8 (priv violation) guard
    move.l  #_unexpected_trap, 0x00000010    | vec 4 (illegal) guard

    lea     0x00020000, %a0

    | --- Test 1: MOVES.L D1,(4,a0,d2.w) then MOVES.L (4,a0,d2.w),D3 —
    | indexed store + load round-trip, long size (no merge needed).
    moveq   #0, %d2
    move.l  #0xCAFEBABE, %d1
    moves.l %d1, (4,%a0,%d2.w)
    move.l  #0, %d3
    moves.l (4,%a0,%d2.w), %d3
    cmp.l   #0xCAFEBABE, %d3
    bne     _fail1

    | --- Test 2: MOVES.B (d8,An,Xn) LOAD with distinguishing upper bits
    | already in the destination register — this is the exact shape
    | that caught the merge bug (fuzz seed 9027).
    move.l  #0x76ebd200, %d5                 | upper 3 bytes must survive
    moveq   #0, %d6
    move.b  #0x12, (6,%a0,%d6.w)
    moves.b (6,%a0,%d6.w), %d5
    cmp.l   #0x76ebd212, %d5
    bne     _fail2

    | --- Test 3: MOVES.W (d8,An,Xn*4) LOAD, scaled long index, upper
    | word of the destination must also survive a .W merge.
    move.l  #0x1234FFFF, %d4
    lea     0x00000003, %a1
    move.w  #0xBEEF, (8,%a0,%a1.l*4)          | a0 + 8 + 3*4 = a0+20
    moves.w (8,%a0,%a1.l*4), %d4
    cmp.l   #0x1234BEEF, %d4
    bne     _fail3

    | --- Test 4: MOVES.B Dn,(d8,An,Xn) STORE — indexed store direction,
    | verify only the target byte changes (adjacent bytes untouched).
    move.b  #0x55, (10,%a0,%d6.w)
    move.b  #0x66, (11,%a0,%d6.w)
    move.l  #0x000000AA, %d0
    moves.b %d0, (11,%a0,%d6.w)
    move.b  (10,%a0,%d6.w), %d1
    andi.l  #0xFF, %d1
    cmp.l   #0x55, %d1
    bne     _fail4
    move.b  (11,%a0,%d6.w), %d1
    andi.l  #0xFF, %d1
    cmp.l   #0xAA, %d1
    bne     _fail4

_pass:
    move.l  #0xC0FFEE00, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_unexpected_trap:
    move.l  #0xDEAD0000, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail2:
    move.l  #0xDEAD0002, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail3:
    move.l  #0xDEAD0003, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail4:
    move.l  #0xDEAD0004, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
