| fsqhdr_misaligned_queue_ops.s — the EXACT memory-access shape Mac OS uses on
| the File System queue header, which nothing else in the suite covers.
|
| WHY THIS EXISTS (task #140, 2026-07-28)
| ------------------------------------------------------------------------
| The ioResult stall was traced to the File System queue at FSQHdr = $0360.
| Its layout puts BOTH 32-bit fields at LONG-MISALIGNED addresses:
|     $0360 qFlags (word)   <- bit 0 = FSBusy semaphore
|     $0362 qHead  (long)   <- 2 mod 4
|     $0366 qTail  (long)   <- 2 mod 4
| so every queue operation is a split access, and the ROM interleaves them with
| a byte read-modify-write on the SAME longword:
|     ROM 0x4080F00E  bset #0,($0360).w    acquire FSBusy   (byte RMW)
|     ROM 0x4080F126  clrw  ($0360).w      release FSBusy   (word store)
|     ROM 0x4080F138  movel %a0@,0x362     dequeue          (misaligned long)
|     ROM 0x4080F15A  tstl  0x362          "more queued?"   (misaligned long)
|     ROM 0x4080F176  movel %a0,0x366      qTail update     (misaligned long)
|
| The suite already covers misaligned long load/store in isolation
| (unaligned_long_store_splits, lsu_unaligned_long_byte_aligned,
|  lsu_unaligned_long_load_xline) and all pass.  What is NOT covered is the
| OVERLAP: a word store followed immediately by a misaligned long store that
| writes into the SAME longword, then read back both misaligned and aligned.
| A byte-lane/strobe error on the split write is invisible to a misaligned
| read-back that makes the same mistake twice, so this test also reads the
| region back with ALIGNED loads, which is what actually pins the byte lanes.
|
| Precedent for this bug class in this codebase: axi_narrow_to_wide.v once
| unconditionally 4-byte-aligned read addresses (task #88), and the dcache
| snoop path once assembled a line in the wrong word order.  A surviving
| sibling on the partial-write path would corrupt exactly these fields, which
| would orphan queue entries and strand every caller polling ioResult.
|
| THIS TEST PASSING DOES NOT EXONERATE THE HYPOTHESIS -- it only rules out the
| single-threaded, cache-warm case.  It says nothing about the same accesses
| racing an interrupt or crossing a cache line.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD0F01 — word store did not clear qFlags
|   0xDEAD0F02 — misaligned long store/load to qHead ($+2) wrong
|   0xDEAD0F03 — misaligned long store/load to qTail ($+6) wrong
|   0xDEAD0F04 — ALIGNED read of $+0 wrong (byte lanes of the split write)
|   0xDEAD0F05 — ALIGNED read of $+4 wrong (byte lanes of the split write)
|   0xDEAD0F06 — ALIGNED read of $+8 wrong (split write ran past its field)
|   0xDEAD0F07 — bset did not set FSBusy, or reported the wrong prior value
|   0xDEAD0F08 — bset on an already-set bit reported the wrong prior value
|   0xDEAD0F09 — bset corrupted the adjacent byte
|   0xDEAD0F0A — release (clr.w) failed after the semaphore sequence

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ FSQ,       0x00030000      | scratch, long-aligned like the real $0360

_start:
    lea     0x00010000, %a7
    lea     FSQ, %a2

    | ── Prime with a known pattern so every byte lane is distinguishable ──
    move.l  #0x11223344, %d0
    move.l  %d0, (%a2)
    move.l  #0x55667788, %d0
    move.l  %d0, 4(%a2)
    move.l  #0x99AABBCC, %d0
    move.l  %d0, 8(%a2)
    |   bytes: 11 22 33 44 55 66 77 88 99 AA BB CC

    | ── The ROM's release: clr.w on the flags word ───────────────────────
    clr.w   (%a2)
    |   bytes: 00 00 33 44 55 66 77 88 99 AA BB CC

    | ── The ROM's dequeue: MISALIGNED long store overlapping that word ───
    move.l  #0xAABBCCDD, %d1
    move.l  %d1, 2(%a2)
    |   bytes: 00 00 AA BB CC DD 77 88 99 AA BB CC

    | ── The ROM's qTail update: second misaligned long store ─────────────
    move.l  #0x55667788, %d2
    move.l  %d2, 6(%a2)
    |   bytes: 00 00 AA BB CC DD 55 66 77 88 BB CC

    | ── Read back the way the ROM does (misaligned) ──────────────────────
    move.w  (%a2), %d3
    bne     fail_flags

    move.l  2(%a2), %d4
    cmp.l   #0xAABBCCDD, %d4
    bne     fail_qhead

    move.l  6(%a2), %d5
    cmp.l   #0x55667788, %d5
    bne     fail_qtail

    | ── Read back ALIGNED.  This is the part that actually pins the byte
    | ── lanes: a split-write strobe bug that a misaligned read-back would
    | ── mirror shows up here as a wrong lane.
    move.l  (%a2), %d6
    cmp.l   #0x0000AABB, %d6
    bne     fail_algn0

    move.l  4(%a2), %d7
    cmp.l   #0xCCDD5566, %d7
    bne     fail_algn4

    move.l  8(%a2), %d6
    cmp.l   #0x7788BBCC, %d6
    bne     fail_algn8

    | ── The ROM's acquire: byte RMW on the same longword ─────────────────
    | bset sets Z from the PREVIOUS value of the bit, so the first bset must
    | report "was clear" (Z=1) and the second "was set" (Z=0).  Getting this
    | backwards is what would make a semaphore hand out two owners.
    bset    #0, (%a2)
    bne     fail_bset1              | Z=0 means the bit was already set
    move.b  (%a2), %d0
    cmp.b   #0x01, %d0
    bne     fail_bset1

    bset    #0, (%a2)
    beq     fail_bset2              | Z=1 means it read the bit as clear

    | adjacent byte must be untouched by the byte RMW
    move.b  1(%a2), %d0
    bne     fail_bset_adj

    | qHead must survive the semaphore ops
    move.l  2(%a2), %d4
    cmp.l   #0xAABBCCDD, %d4
    bne     fail_qhead

    | ── Release again, exactly as ROM 0x4080F126 does ────────────────────
    clr.w   (%a2)
    move.w  (%a2), %d3
    bne     fail_release
    move.l  2(%a2), %d4
    cmp.l   #0xAABBCCDD, %d4
    bne     fail_release

    move.l  #0xC0FFEE00, %d1
    move.l  %d1, PASS_SENT
    bra     .

fail_flags:
    move.l  #0xDEAD0F01, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_qhead:
    move.l  #0xDEAD0F02, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_qtail:
    move.l  #0xDEAD0F03, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_algn0:
    move.l  #0xDEAD0F04, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_algn4:
    move.l  #0xDEAD0F05, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_algn8:
    move.l  #0xDEAD0F06, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_bset1:
    move.l  #0xDEAD0F07, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_bset2:
    move.l  #0xDEAD0F08, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_bset_adj:
    move.l  #0xDEAD0F09, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_release:
    move.l  #0xDEAD0F0A, %d1
    move.l  %d1, PASS_SENT
    bra     .
