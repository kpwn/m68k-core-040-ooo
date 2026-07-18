| byte_lane_indexed_dbf_loop.s — Q700 byte-lane test EXACT recipe
|
| Faithful repro of the Q700 ROM byte-lane test (PC 0x4084bc04 in the
| Quadra 700 Universal ROM 420dbff3.rom).  Disassembled:
|
|   4084bc04: movel  (a0), d3            | save (a0)
|   4084bc06: movel  (a0,4), d4          | save (a0+4)
|   4084bc0a: movel  d1, (a0)            | write test pattern
|   4084bc0c: moveq  #3, d2
|   4084bc0e: movel  #-1, (a0,4)         | <-- loop entry: write -1 to (a0+4)
|   4084bc16: cmpb   (0,a0,d2:w), d1
|   4084bc1a: bnes   .fail
|   4084bc1c: notb   d1
|   4084bc1e: notb   (0,a0,d2:w)
|   4084bc22: cmpb   (0,a0,d2:w), d1
|   4084bc26: beqs   .skip
|   .fail: bset d2, d6                   | failure mask bit d2
|   .skip: rorl   #8, d1
|          dbf    d2, 4084bc0e
|
| On HW, D7=0x00020003 → d6 ended with bits 0&1 set → iters d2=0 and
| d2=1 failed (the bset ran).  Per analysis (CLAUDE.md/2026-05-12),
| the suspect is iq_mem load-store disambiguation: the brief-indexed
| LOAD uses TMP1 as base, the prior `move.l #-1, (a0,4)` store uses
| A0 as base.  Different physregs → no static-aliasing block → the
| cmpb may issue before the store buffer drains.
|
| Test pattern d1 = 0x54696E61 ("Tina") — matches the Q700 ROM byte
| pattern, so each byte is distinct.  The cmpb expects d1[7:0] = byte
| at (a0+d2).  After each iter, ror.l #8 advances d1 so its low byte
| matches the byte at the next d2 to test.

    .text
    .org 0

_start:
    lea     0x00104000, %a0

    | Pre-clear (a0..a0+3) to sentinel that mismatches every byte of d1.
    | If our LSU reads stale memory, the cmpb compares 0xAA against d1[7:0]
    | and mismatches.
    move.l  #0xAAAAAAAA, (%a0)
    move.l  #0x00000000, %d6        | failure mask
    move.l  #0x54696E61, %d1        | test pattern "Tina"

    | Save (a0) and (a0+4) per the ROM recipe.
    move.l  (%a0), %d3
    move.l  4(%a0), %d4

    | The store under test.
    move.l  %d1, (%a0)
    moveq   #3, %d2

.loop:
    | (a0+4) is written -1 EVERY iter — this is the An-based store
    | that does NOT alias (a0..a0+3), but uses the SAME An as the loop's
    | indexed byte access.  Stresses our pbase-aliasing check.
    move.l  #-1, 4(%a0)

    | FIRST byte CMP.
    cmp.b   (0,%a0,%d2:w*1), %d1
    bne     .fail_set

    not.b   %d1
    not.b   (0,%a0,%d2:w*1)

    | SECOND byte CMP — should still match after both flips.
    cmp.b   (0,%a0,%d2:w*1), %d1
    beq     .skip_fail

.fail_set:
    bset    %d2, %d6                | mark this byte lane as failed

.skip_fail:
    ror.l   #8, %d1
    dbra    %d2, .loop

    | Restore (a0) and (a0+4) per the ROM.
    move.l  %d3, (%a0)
    move.l  %d4, 4(%a0)

    | Check failure mask.  d6 == 0 means all 4 byte lanes worked.
    tst.l   %d6
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail:
    | Emit failure mask in d7 so we can see which lanes broke.
    move.l  %d6, %d7
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail
