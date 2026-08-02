| store_line_straddle_writeback_span.s — a store that straddles a 32-byte
| D-cache line boundary must modify EXACTLY its own bytes, in cache AND
| after the dirty lines are written back to memory.
|
| Why (2026-08-01, store-byte-range audit):
|
|   The LSU bounds a store's byte span structurally: `dc_wstrb` comes
|   only from `m68k_mem_strb(size, ea[1:0])` (or the split-beat
|   variants) with size in {BYTE, WORD, LONG}, so no single store can
|   strobe more than its operand size.  The ONE place in the design
|   where more bytes than the instruction asked for actually reach
|   memory is the D-cache eviction path: `dcache.v` S_EVICT_RD drives
|   `w_strb <= 4'b1111` for all eight beats of a dirty line, so a
|   32-byte line is written back in full regardless of how few of its
|   bytes the CPU stored.  That is correct write-back behaviour ONLY if
|   the other 28+ bytes in the line still hold the values the fill
|   brought in.
|
|   This test pins that invariant end-to-end from the ISA side: seed
|   three consecutive 32-byte lines with distinct patterns, dirty them
|   with a line-straddling misaligned LONG store (which splits into two
|   aligned beats landing in two DIFFERENT cache lines), CPUSH the
|   whole D-cache so every dirty line is written back, then re-read
|   everything from memory and require byte-exactness.
|
|   A regression in split-store strobes, in write-allocate merging, or
|   in write-back line composition all show up here as a neighbouring
|   byte that changed — including bytes BELOW the store address, which
|   is the shape the live-hardware Memory-Manager corruption had.
|
| Layout (D-cache is 4 KB / 4-way / 32-byte lines / 32 sets, so
| addr[9:5] selects the set):
|
|   0x00100000 .. 0x0010001F   line L0  — guard, must never change
|   0x00100020 .. 0x0010003F   line L1  — holds the low half of the store
|   0x00100040 .. 0x0010005F   line L2  — holds the high half of the store
|
| The straddling store is MOVE.L D0,0x0010003E: bytes 0x3E,0x3F land in
| L1 and bytes 0x40,0x41 in L2.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  FAIL: 0xDEADBEEF.

    .text
    .org 0
_start:
    lea     0x00010000, %a7
    lea     0x00100000, %a0

    | ---- Seed three lines with per-longword-distinct patterns --------
    move.l  #24, %d1                | 24 longs = 96 bytes = 3 lines
    move.l  #0xA0A00000, %d2
    lea     0x00100000, %a1
_seed_loop:
    move.l  %d2, (%a1)+
    addq.l  #1, %d2
    subq.l  #1, %d1
    bne     _seed_loop

    | Push + invalidate so the seed is definitely in memory and the
    | lines below are re-filled from memory, not left as write-allocate
    | residue.
    cpusha  %dc

    | ---- The straddling misaligned LONG store ------------------------
    | 0x0010003E is 2 bytes before the L1/L2 boundary, so the LSU splits
    | it into a 2-byte beat at 0x0010003C (strb 0011) and a 2-byte beat
    | at 0x00100040 (strb 1100).  Two different cache lines, two
    | different sets.
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0x0010003E

    | ---- Also dirty L0 with a single BYTE, to make it a write-back
    | candidate whose other 31 bytes must survive untouched.
    move.b  #0x5A, 0x00100011

    | ---- Force every dirty line out to memory ------------------------
    cpusha  %dc

    | ---- Verify from memory, longword by longword --------------------
    | L0: longs 0..7 = 0xA0A00000..0xA0A00007, except long 4
    | (0x00100010..13) whose byte 1 became 0x5A.
    lea     0x00100000, %a1
    move.l  #0xA0A00000, %d2
    moveq   #7, %d1                 | check longs 0..7 of L0 (8 longs)
_chk_l0:
    move.l  (%a1)+, %d3
    cmp.l   %d2, %d3
    bne     _l0_special
_chk_l0_next:
    addq.l  #1, %d2
    dbra    %d1, _chk_l0
    bra     _chk_l1

_l0_special:
    | Only long index 4 (address 0x00100010) is allowed to differ, and
    | only in byte 1: 0xA0A00004 -> 0xA05A0004.
    cmp.l   #0x00100014, %a1
    bne     _fail
    cmp.l   #0xA05A0004, %d3
    bne     _fail
    bra     _chk_l0_next

_chk_l1:
    | L1 = longs 8..15 (0x00100020..0x0010003F).  Only the last long
    | (0x0010003C) changes, and only in its low half-word:
    | 0xA0A0000F -> 0xA0A0DEAD.
    lea     0x00100020, %a1
    move.l  #0xA0A00008, %d2
    moveq   #6, %d1                 | longs 8..14 must be untouched
_chk_l1_loop:
    move.l  (%a1)+, %d3
    cmp.l   %d2, %d3
    bne     _fail
    addq.l  #1, %d2
    dbra    %d1, _chk_l1_loop
    cmp.l   #0xA0A0DEAD, (%a1)      | 0x0010003C — low word replaced
    bne     _fail

    | L2 = longs 16..23 (0x00100040..0x0010005F).  Only the first long
    | changes, and only in its high half-word: 0xA0A00010 -> 0xBEEF0010.
    lea     0x00100040, %a1
    cmp.l   #0xBEEF0010, (%a1)+
    bne     _fail
    move.l  #0xA0A00011, %d2
    moveq   #6, %d1                 | longs 17..23 must be untouched
_chk_l2_loop:
    move.l  (%a1)+, %d3
    cmp.l   %d2, %d3
    bne     _fail
    addq.l  #1, %d2
    dbra    %d1, _chk_l2_loop

    | ---- Second pass: BYTE store at the very first byte of a line ----
    | Guards against an off-by-one that would push the strobe into the
    | previous line.  0x00100040 is the L2 base; its neighbour below,
    | 0x0010003F, is the last byte of L1 and must not move.
    move.b  #0x3C, 0x00100040
    cpusha  %dc
    cmp.l   #0xA0A0DEAD, 0x0010003C | L1 tail byte 0x3F still 0xAD
    bne     _fail
    cmp.l   #0x3CEF0010, 0x00100040
    bne     _fail
    cmp.l   #0xA0A00011, 0x00100044
    bne     _fail

    | ---- Third pass: WORD store at the last byte of a line -----------
    | 0x0010005F is the final byte of L2; a WORD there splits across the
    | L2/L3 boundary.  L2's byte 0x5E and L3's byte 0x60 change; nothing
    | else may.
    move.l  #0x00001234, %d0
    move.w  %d0, 0x0010005F
    cpusha  %dc
    cmp.l   #0xA0A00016, 0x00100058 | long before the split, untouched
    bne     _fail
    cmp.l   #0xA0A00012, 0x0010005C | byte 0x5F -> 0x12
    bne     _fail
    move.b  0x00100060, %d3
    and.l   #0xFF, %d3
    cmp.l   #0x34, %d3              | byte 0x60 -> 0x34
    bne     _fail

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
