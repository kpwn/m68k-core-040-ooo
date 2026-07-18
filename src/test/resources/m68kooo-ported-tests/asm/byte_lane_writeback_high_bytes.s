| byte_lane_writeback_high_bytes.s — Cache-writeback partial-write hunt
|
| Hypothesis: Q700 ROM byte-lane test HW failure (d6=0x03 = bits 0&1)
| means our LSU/dcache/AXI writes only the LOW 2 bytes of a longword,
| dropping the HIGH 2 bytes (m68k offsets 0&1, AXI bytes 2&3 in
| lane 0).  Simple directed tests pass in sim because:
|   - Cached store + cache-hit byte read returns the correct byte
|     (BRAM byte-lanes work correctly in sim)
|   - But the WRITEBACK path to DDR might drop bytes that BRAM holds
|
| This test exercises the WRITEBACK path:
|   1. `move.l d1, (a0)`   — writes longword into dcache (cache-allocate)
|   2. Pollute the cache set by reading many other addresses in the
|      same set index — forces eviction of (a0)'s line, triggering
|      a writeback to DDR
|   3. After eviction, the data is ONLY in DDR (not in cache anymore)
|   4. Read each byte at (a0+d2) — now goes through cache fill from DDR
|   5. If the writeback dropped high bytes, the fill from DDR returns
|      stale/wrong bytes for offsets 0&1
|
| To trigger eviction reliably: dcache is 4-way SA, 4KB, 32B line.
| That's 32 sets × 4 ways × 32B/line = 4096B.  To evict line at set N
| we need 5+ reads to addresses with the same set bits (addr[8:5])
| but different tags (addr[31:12]).
|
| We use A0=0x100000 (set 0, tag 0x100).  Pollution addresses use
| same set 0 but tags 0x101..0x108 (= addr 0x101000..0x108000).
| With 4 ways + LRU, 5 different tags forces eviction.

    .text
    .org 0

_start:
    | Park exception vectors at VBR.
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    | Test memory address (16-byte aligned, set 0, tag 0x100).
    lea     0x00200000, %a0

    | Pollution addresses — same set index as A0 but different tags.
    | A0[8:5] = 0 → set 0.  We need 5 more "set 0" addresses with
    | different tags.  Pick tags 0x201..0x208.
    lea     0x00201000, %a1
    lea     0x00202000, %a2
    lea     0x00203000, %a3
    lea     0x00204000, %a4
    lea     0x00205000, %a5

    | Pre-seed (a0..a0+3) with a known-bad sentinel.  If the writeback
    | drops high bytes, the sentinel will be visible in the readback.
    move.l  #0xDEADBEEF, %d6
    move.l  %d6, (%a0)

    | Now force eviction by reading 4 OTHER tags into the same set.
    | This populates ways 1, 2, 3 (way 0 holds A0's data).
    move.l  (%a1), %d6
    move.l  (%a2), %d6
    move.l  (%a3), %d6
    move.l  (%a4), %d6
    | Reading a 5th tag in the same set evicts way 0 → writeback to DDR.
    move.l  (%a5), %d6

    | At this point, A0's data is no longer in dcache.  It was written
    | to DDR via the cache writeback path.

    | Now do the byte-lane test — each cmpb refills the line from DDR.
    move.l  #0xDEADBEEF, %d1            | expected pattern (matches what we wrote)

    | Verify each byte position.  If writeback dropped high bytes
    | (m68k offsets 0&1 = bytes 0xDE and 0xAD), those bytes will read
    | back wrong.
    moveq   #0, %d0
    move.b  (0,%a0,%d0:w*1), %d2        | byte 0 (m68k MSB) — should be 0xDE
    cmp.b   #0xDE, %d2
    bne     _fail_b0_wb

    moveq   #1, %d0
    move.b  (0,%a0,%d0:w*1), %d2        | byte 1 — should be 0xAD
    cmp.b   #0xAD, %d2
    bne     _fail_b1_wb

    moveq   #2, %d0
    move.b  (0,%a0,%d0:w*1), %d2        | byte 2 — should be 0xBE
    cmp.b   #0xBE, %d2
    bne     _fail_b2_wb

    moveq   #3, %d0
    move.b  (0,%a0,%d0:w*1), %d2        | byte 3 (m68k LSB) — should be 0xEF
    cmp.b   #0xEF, %d2
    bne     _fail_b3_wb

    | Repeat the Q700 byte-lane recipe AFTER cache writeback round-trip.
    | The test pattern has been written, evicted, and re-read.
    move.l  #0x55555555, %d1            | uniform fill
    move.l  %d1, (%a0)

    | Force another writeback by polluting the set.
    move.l  (%a1), %d6
    move.l  (%a2), %d6
    move.l  (%a3), %d6
    move.l  (%a4), %d6
    move.l  (%a5), %d6

    moveq   #0, %d6
    moveq   #3, %d2
.loop:
    cmp.b   (0,%a0,%d2:w*1), %d1
    bne     .fail_set
    not.b   %d1
    not.b   (0,%a0,%d2:w*1)
    cmp.b   (0,%a0,%d2:w*1), %d1
    beq     .skip_fail
.fail_set:
    bset    %d2, %d6
.skip_fail:
    ror.l   #8, %d1
    dbra    %d2, .loop

    | If any partial-fail (d6 != 0 and != 0xF), fail the test.
    tst.l   %d6
    bne     _fail_partial

    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a6)
_halt:
    bra     _halt

_fail_b0_wb:
    move.l  #0xFB0000DE, %d7
    bra     _fail
_fail_b1_wb:
    move.l  #0xFB0000AD, %d7
    bra     _fail
_fail_b2_wb:
    move.l  #0xFB0000BE, %d7
    bra     _fail
_fail_b3_wb:
    move.l  #0xFB0000EF, %d7
    bra     _fail
_fail_partial:
    move.l  %d6, %d7
_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a6)
_halt_fail:
    bra     _halt_fail
