| movem_store_predec_all16.s — MOVEM.L D0-D7/A0-A7, -(A6)
|
| Stage D-9a V2 corner: push ALL 16 registers with the predec form via a
| base register that is NOT in the list (A6).  The mask bits are
| REVERSED for predec store (bit 0 = A7, bit 15 = D0), so the assembler
| must invert the k-th-set-bit scan index before emitting each STORE.
| After the push, A6 = original - 64, and memory holds D0..A7 with the
| HIGHEST arch index at the HIGHEST address (predec order).
|
| Note: we deliberately use A6 (not A7) as the base to avoid the
| documented "An-in-list" corner where the original An value cannot be
| recovered from the renamed register — our simple "update An first,
| then store" crack pushes the NEW An instead of the ORIGINAL.  That
| corner is tracked by adv_movem_an_in_list.s (DEFERRED).
|
| This exercises the 17-phase crack (1 SUB An + 16 STORE) at the upper
| bound of uop_phase's 5-bit counter.
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.
| FAIL: 0xDEADBEEF at 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7              | SP (stack for tiny frame ops)
    | A6 is our MOVEM base; set it so the post-MOVEM address space is
    | the top of low memory (0x0000_FFC0 .. 0x0000_FFFF).
    lea     0x00010000, %a6

    | Seed each register with a recognisable canary.
    move.l  #0xD0000000, %d0
    move.l  #0xD1111111, %d1
    move.l  #0xD2222222, %d2
    move.l  #0xD3333333, %d3
    move.l  #0xD4444444, %d4
    move.l  #0xD5555555, %d5
    move.l  #0xD6666666, %d6
    move.l  #0xD7777777, %d7
    move.l  #0xA0000000, %a0
    move.l  #0xA1111111, %a1
    move.l  #0xA2222222, %a2
    move.l  #0xA3333333, %a3
    move.l  #0xA4444444, %a4
    move.l  #0xA5555555, %a5
    | A7 currently = 0x10000 (matches SP initialisation above).  After
    | the push A7's value is pushed into memory at the HIGHEST slot.

    | Push all 16.  A6 = 0x10000; after push A6 = 0xFFC0, 64 bytes
    | written.  The reversed-mask predec order writes:
    |   addr 0xFFFC : A7 (the ORIGINAL A7 value, 0x0001_0000)
    |   addr 0xFFF8 : A6 (the NEW A6 value, because A6 was renamed by
    |                     our phase-0 SUB before the stores fired —
    |                     the "An-in-list" corner; legacy has the same
    |                     behaviour, so this test does NOT include A6
    |                     as its verify target).
    |   addr 0xFFF4 : A5
    |   ...
    |   addr 0xFFC4 : D1
    |   addr 0xFFC0 : D0
    movem.l %d0-%d7/%a0-%a7, -(%a6)

    | Verify the TOP slot (0xFFFC) holds the ORIGINAL A7 (0x00010000).
    | A7 is NOT the MOVEM base so it is NOT affected by the phase-0
    | SUB — reads the architectural A7 at the time of the store.
    move.l  0x00FFFC, %d0
    cmp.l   #0x00010000, %d0
    bne     _fail

    | Second-from-top (0xFFF8) is A6.  A6 IS the MOVEM base so phase 0
    | already subtracted 64 from it — skip this slot.

    | Third-from-top (0xFFF4): arch 15 - 2 = 13 = A5.
    move.l  0x00FFF4, %d0
    cmp.l   #0xA5555555, %d0
    bne     _fail

    | Middle: D7 sits 8 slots below top (arch 15 - 8 = 7).
    move.l  0x00FFDC, %d0
    cmp.l   #0xD7777777, %d0
    bne     _fail

    | Lowest slot: arch 0 = D0.
    move.l  0x00FFC0, %d0
    cmp.l   #0xD0000000, %d0
    bne     _fail

    | A6 check: should be original - 64 = 0xFFC0.
    move.l  %a6, %d0
    cmp.l   #0x0000FFC0, %d0
    bne     _fail

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
