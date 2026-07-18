| movem_word_store.s — MOVEM.W D0/D1, -(A7)
|
| Stage D-9a V2 corner: MOVEM.W STORE writes only the LOW 16 bits of
| each register per PRM §4.79.  Predec form: bits-reversed mask means
| bit 8 = D0, bit 9 = D1 in the predec ext word, so the stored order is:
|   (A7 - 2) : D1[15:0]   ← written FIRST (lowest bit in reversed mask)
|   (A7 - 4) : D0[15:0]
| ...after instruction, A7 = original - 4 (stride=2 × 2 regs).
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.
| FAIL: 0xDEADBEEF at 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7              | initial SP
    move.l  %a7, %d7                     | save original SP

    | Values whose HIGH halves are distinctive so we can catch an
    | erroneous .L store masquerading as .W.
    move.l  #0xDEAD1234, %d0            | expected stored low half 0x1234
    move.l  #0xBEEFABCD, %d1            | expected stored low half 0xABCD

    movem.w %d0/%d1, -(%a7)

    | After push, A7 = original - 4.
    move.l  %a7, %d2
    sub.l   #4, %d7                      | expected = original - 4
    cmp.l   %d7, %d2
    bne     _fail

    | Reversed mask: low-bit first → arch 15 = A7, but D1 is arch 1.
    | For predec MEM STORE: reg_k = 15 - bit_scan(k-1).  The PRM order
    | for reg-to-mem predec is D1 first (highest address), then D0.
    |   - word @ (original-2) = D1[15:0]  = 0xABCD
    |   - word @ (original-4) = D0[15:0]  = 0x1234
    move.w  0x0000FFFE, %d2
    andi.l  #0xFFFF, %d2                 | isolate 16 bits
    cmp.l   #0x0000ABCD, %d2
    bne     _fail

    move.w  0x0000FFFC, %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0x00001234, %d2
    bne     _fail

    | Confirm the adjacent BELOW-our-stores byte was NOT disturbed.
    | After one MOVEM.W push, SP = 0xFFFC, stores live at FFFC+FFFD
    | (D0 low half) and FFFE+FFFF (D1 low half).  Write a sentinel
    | at 0xFFFA (below the stored region) and confirm it persists.
    move.w  #0xBABE, 0x0000FFFA
    | Verify the bytes we wrote ABOVE are still intact.
    move.w  0x0000FFFA, %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0x0000BABE, %d2
    bne     _fail
    move.w  0x0000FFFE, %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0x0000ABCD, %d2
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
