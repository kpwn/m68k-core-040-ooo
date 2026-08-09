| cas_mismatch_memory.s — CAS.{B,W,L} MISMATCH must leave memory UNCHANGED.
|
| PRM §4.37 / Musashi m68k_in.c M68KMAKE_OP(cas,*): on a compare MISMATCH
| the memory operand is NOT written — only Dc receives the loaded value.
| Musashi writes memory exclusively inside the `else` of `if(COND_NE())`.
|
| cas_basic.s exercises the mismatch path but only ever checks Dc and the
| CCR, so it cannot see a memory clobber.  This test closes that hole: it
| asserts the memory CONTENTS after every mismatch, and also asserts the
| neighbouring cell is untouched so a wrong-width store is caught too.
|
| PASS = store 0xC0FFEE00 to 0xFFFF0000.

    .text
    .org 0

_start:
    | ── Test 1: CAS.L mismatch — memory must still hold the loaded value.
    lea     0x50100, %a0
    move.l  #0xCAFEBABE, (%a0)
    move.l  #0x11223344, %d1          | Dc — does NOT match memory
    move.l  #0x55555555, %d2          | Du — must NOT reach memory
    cas.l   %d1, %d2, (%a0)
    bne     _t1_cc_ok
    bra     _fail
_t1_cc_ok:
    move.l  (%a0), %d0
    move.l  #0xCAFEBABE, %d3
    cmp.l   %d3, %d0
    bne     _fail                     | memory clobbered by Du → FAIL
    move.l  #0xCAFEBABE, %d3
    cmp.l   %d3, %d1                  | Dc must be the loaded value
    bne     _fail

    | ── Test 2: CAS.W mismatch — the addressed word AND its neighbour
    |   must both be untouched.
    lea     0x50110, %a1
    move.l  #0x1234ABCD, (%a1)        | word0 = 0x1234, word1 = 0xABCD
    move.l  #0xFFFF9999, %d1          | Dc low word 0x9999 ≠ 0x1234
    move.l  #0x00007777, %d2          | Du low word 0x7777
    cas.w   %d1, %d2, (%a1)
    bne     _t2_cc_ok
    bra     _fail
_t2_cc_ok:
    move.l  (%a1), %d0
    move.l  #0x1234ABCD, %d3
    cmp.l   %d3, %d0
    bne     _fail                     | either half changed → FAIL
    move.l  #0xFFFF1234, %d3
    cmp.l   %d3, %d1                  | Dc.W := loaded, upper half kept
    bne     _fail

    | ── Test 3: CAS.B mismatch — addressed byte and its 3 neighbours
    |   must all be untouched.
    lea     0x50120, %a2
    move.l  #0x8899AABB, (%a2)        | byte0 = 0x88
    move.l  #0xAABBCC42, %d1          | Dc low byte 0x42 ≠ 0x88
    move.l  #0x11223377, %d2          | Du low byte 0x77
    cas.b   %d1, %d2, (%a2)
    bne     _t3_cc_ok
    bra     _fail
_t3_cc_ok:
    move.l  (%a2), %d0
    move.l  #0x8899AABB, %d3
    cmp.l   %d3, %d0
    bne     _fail
    move.l  #0xAABBCC88, %d3
    cmp.l   %d3, %d1                  | Dc.B := loaded byte
    bne     _fail

    | ── Test 4: CAS.L mismatch through (An)+ — memory unchanged, and the
    |   post-increment still happens (A3 advances by 4).
    lea     0x50130, %a3
    move.l  #0x0BADF00D, (%a3)
    move.l  #0x00000001, %d1
    move.l  #0xEEEEEEEE, %d2
    cas.l   %d1, %d2, (%a3)+
    move.l  #0x50134, %d0
    cmp.l   %d0, %a3
    bne     _fail                     | post-increment lost
    lea     0x50130, %a4
    move.l  (%a4), %d0
    move.l  #0x0BADF00D, %d3
    cmp.l   %d3, %d0
    bne     _fail

    | ── Test 5: CAS.L mismatch where Du happens to equal the loaded
    |   value — this must STILL leave Dc holding the loaded value and
    |   memory intact (guards a "compare against Du" mix-up).
    lea     0x50140, %a5
    move.l  #0x13579BDF, (%a5)
    move.l  #0x2468ACE0, %d1          | Dc mismatches
    move.l  #0x13579BDF, %d2          | Du == memory
    cas.l   %d1, %d2, (%a5)
    bne     _t5_cc_ok
    bra     _fail
_t5_cc_ok:
    move.l  (%a5), %d0
    move.l  #0x13579BDF, %d3
    cmp.l   %d3, %d0
    bne     _fail
    move.l  #0x13579BDF, %d3
    cmp.l   %d3, %d1
    bne     _fail

    | ── PASS ─────────────────────────────────────────────────────────
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
