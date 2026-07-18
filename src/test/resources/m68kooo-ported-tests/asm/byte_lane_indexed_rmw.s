| byte_lane_indexed_rmw.s — Brief-indexed byte RMW (NOT.B) round-trip
|
| Tests `not.b (0,a0,dn:w*1)` and verifies that the byte LSU stores
| matches the byte that a subsequent byte LOAD returns at the same
| indexed EA — and that nearby byte lanes are NOT corrupted.
|
| Specifically guards against:
|   - Brief-indexed RMW computing wrong EA for the STORE half
|     (the crack uses TMP1 for both LOAD and STORE — they MUST match)
|   - wstrb bit position mismatch between byte LOAD and byte STORE
|   - The STORE half corrupting adjacent byte lanes via wide wstrb leak

    .text
    .org 0

_start:
    lea     0x00104000, %a0

    | Seed all 4 byte lanes with distinct, non-self-symmetric values.
    move.l  #0x10203040, (%a0)

    | Iter d0 = 0: NOT.B (a0+0)  → byte 0 flips from 0x10 to 0xEF
    moveq   #0, %d0
    not.b   (0,%a0,%d0:w*1)

    | Iter d0 = 1: NOT.B (a0+1)  → byte 1 flips from 0x20 to 0xDF
    moveq   #1, %d0
    not.b   (0,%a0,%d0:w*1)

    | Iter d0 = 2: NOT.B (a0+2)  → byte 2 flips from 0x30 to 0xCF
    moveq   #2, %d0
    not.b   (0,%a0,%d0:w*1)

    | Iter d0 = 3: NOT.B (a0+3)  → byte 3 flips from 0x40 to 0xBF
    moveq   #3, %d0
    not.b   (0,%a0,%d0:w*1)

    | Expected: (a0..a0+3) now = 0xEFDFCFBF.  Read back and compare.
    move.l  (%a0), %d1
    cmp.l   #0xEFDFCFBF, %d1
    bne     _fail_longword

    | Now verify each byte lane via brief-indexed BYTE READ.
    moveq   #0, %d0
    cmp.b   #0xEF, (0,%a0,%d0:w*1)
    bne     _fail_b0
    moveq   #1, %d0
    cmp.b   #0xDF, (0,%a0,%d0:w*1)
    bne     _fail_b1
    moveq   #2, %d0
    cmp.b   #0xCF, (0,%a0,%d0:w*1)
    bne     _fail_b2
    moveq   #3, %d0
    cmp.b   #0xBF, (0,%a0,%d0:w*1)
    bne     _fail_b3

    | Adjacent longwords must NOT be corrupted by the byte RMWs above.
    | Reset (a0..a0+3) and (a0+4..a0+7) with distinct sentinels and
    | retest with a different An so the path is exercised fresh.
    lea     0x00104020, %a1
    move.l  #0x11223344, (%a1)
    move.l  #0xAABBCCDD, 4(%a1)

    | Flip a single byte in (a1+0..a1+3).
    moveq   #2, %d0
    not.b   (0,%a1,%d0:w*1)        | (a1+2) = ~0x33 = 0xCC

    | (a1+0..a1+3) should be 0x1122CC44; (a1+4..a1+7) untouched.
    move.l  (%a1), %d1
    cmp.l   #0x1122CC44, %d1
    bne     _fail_adj_target
    move.l  4(%a1), %d1
    cmp.l   #0xAABBCCDD, %d1
    bne     _fail_adj_neighbor

    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a2)
_halt:
    bra     _halt

_fail_longword:
    move.l  #0xFA111110, %d7
    bra     _fail
_fail_b0:
    move.l  #0xFA111120, %d7
    bra     _fail
_fail_b1:
    move.l  #0xFA111121, %d7
    bra     _fail
_fail_b2:
    move.l  #0xFA111122, %d7
    bra     _fail
_fail_b3:
    move.l  #0xFA111123, %d7
    bra     _fail
_fail_adj_target:
    move.l  #0xFA111130, %d7
    bra     _fail
_fail_adj_neighbor:
    move.l  #0xFA111131, %d7
_fail:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a2)
_halt_fail:
    bra     _halt_fail
