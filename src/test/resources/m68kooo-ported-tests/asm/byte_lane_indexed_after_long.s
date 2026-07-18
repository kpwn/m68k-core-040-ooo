| byte_lane_indexed_after_long.s — Q700 RAM byte-lane test repro (minimal)
|
| Mirrors the Q700 ROM byte-lane test at 0x4084bc0a, which fails on HW
| with D7=0x00020003 (RAM byte-lane test, failure mask bits 0&1):
|
|   move.l  d1, (a0)                       | write test pattern
|   moveq   #3, d2
| .loop:
|   cmp.b   (0,a0,d2:w*1), d1              | read byte at (a0+d2), compare with d1[7:0]
|   bne     .fail                          | mismatch → fail
|   not.b   d1                             | flip d1 low byte
|   not.b   (0,a0,d2:w*1)                  | flip byte at (a0+d2)
|   cmp.b   (0,a0,d2:w*1), d1              | should match again (both flipped)
|   bne     .fail
|   ror.l   #8, d1                         | rotate to next byte
|   dbra    d2, .loop
|
| The test uses a uniform-byte pattern (0x55555555) so every byte read
| should equal d1[7:0] = 0x55.  After the not.b sequence, both d1[7:0]
| and the memory byte become 0xAA — still equal.
|
| Why this exposes the suspected bug
| ---------------------------------
| The byte CMP is via brief-indexed addressing: `(0,a0,d2:w*1)`.
| Our decoder cracks this into multiple µops; the final LOAD uses TMP1
| as its base register, NOT A0.  iq_mem's load-store aliasing check
| (iq_mem.v:343-348) compares pbase + disp statically — TMP1 != A0
| means the load is NOT serialised behind the older `move.l d1, (a0)`
| store, so the load can issue OoO of the still-buffered store and
| read stale memory.
|
| If this test fails, it isolates a real OoO ordering hazard for any
| brief-indexed load after an An-based store.

    .text
    .org 0

_start:
    | Use 16-byte-aligned low-DRAM scratch.
    lea     0x00104000, %a0
    | Pre-clear (a0..a0+3) to a sentinel that DIFFERS from the test
    | pattern so a stale-read returns the sentinel and fails.
    move.l  #0xDEADBEEF, (%a0)

    | The test pattern.  Uniform 0x55 in every byte.
    move.l  #0x55555555, %d1

    | Write the test pattern via the SAME An-based store the Q700 ROM
    | uses — this is the store that the brief-indexed load must see.
    move.l  %d1, (%a0)

    | Loop d2 = 3 → 0, checking each byte lane.
    moveq   #3, %d2

.loop:
    | First CMP.B: byte at (a0+d2) must equal d1[7:0].
    cmp.b   (0,%a0,%d2:w*1), %d1
    bne     _fail_first_cmp

    | RMW: not.b d1, then not.b (a0+d2).  After both flips, the byte
    | and d1[7:0] are both ~original, so still equal.
    not.b   %d1
    not.b   (0,%a0,%d2:w*1)

    | Second CMP.B: must still match.
    cmp.b   (0,%a0,%d2:w*1), %d1
    bne     _fail_second_cmp

    | Restore d1 for next iter (un-flip the byte we flipped).
    not.b   %d1

    ror.l   #8, %d1
    dbra    %d2, .loop

    | All 4 byte lanes passed → write PASS sentinel.
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail_first_cmp:
    | D7 = encoded failure: high word = subtest, low word = d2 + 0x100
    move.l  #0x00010100, %d7
    or.w    %d2, %d7
    bra     _fail_emit

_fail_second_cmp:
    move.l  #0x00010200, %d7
    or.w    %d2, %d7

_fail_emit:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail
