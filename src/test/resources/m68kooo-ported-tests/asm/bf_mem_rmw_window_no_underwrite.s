| bf_mem_rmw_window_no_underwrite.s — memory-destination bitfield RMW
| ops must NEVER disturb the bytes BELOW the field's base byte, and the
| extra bytes inside the RTL's fixed 5-byte (win .. win+4) write window
| must come back with their ORIGINAL content.
|
| Why this test exists (2026-08-01, store-byte-range audit):
|
|   BUG_bitfield_mem_dst_5byte_write_window.md (task #182) documents
|   that BFCHG/BFCLR/BFSET/BFINS on a memory EA always read AND write
|   back the 5-byte window `win .. win+4`, where
|   `win = EA + (offset arithmetic>>3)`, regardless of how few bytes the
|   field actually covers.  Musashi writes only the field's bytes.  The
|   RTL's span is therefore a SUPERSET of the architectural span.
|
|   That "superset" claim is only benign if two things hold, and this
|   test pins BOTH of them so they cannot regress silently:
|
|     1. The superset never extends BELOW `win`.  `win` is also the
|        first byte Musashi writes (bit `r = off & 7` with r in 0..7
|        always lands in byte `win`), so no bitfield op may modify any
|        byte at a lower address than its own field base.  The only
|        legitimate way a bitfield reaches below its EA is a genuinely
|        NEGATIVE dynamic offset — case C exercises exactly that and
|        checks it lands byte-exactly where the PRM says.
|
|     2. The extra bytes are a value-preserving read-modify-write, not
|        zeros or garbage.  Every case below seeds a distinctive
|        non-zero pattern into the window's spill bytes and requires
|        them back unchanged.  If the RMW ever degraded into a
|        blind-write, these comparisons go red.
|
|   The seed pattern deliberately mimics the live-hardware corruption
|   shape this audit was opened against: a Mac OS memory-block header
|   (tag+size long 0x4E000020 followed by the heap-zone pointer
|   0x00002000) sitting immediately below a region the CPU legitimately
|   writes.  Cases A/B/D all write at buf+8 and re-verify that the two
|   header longs at buf+0 / buf+4 are byte-identical afterwards.
|
| Sibling of bf_mem_rmw_dyn_straddle.s (which pins the field VALUE math
| for straddling/negative/dynamic shapes).  This one pins the write
| SPAN, from below.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  FAIL: 0xDEADBEEF.

    .text
    .org 0
_start:
    move.l  #0x40800400, %a3
    bsr     _seed

    | ---- Case A: BFCLR of a whole longword one long above the header --
    | BFCLR (8,A3){#0:32}: win = A3+8, field = bytes 8..11 = 0x11223344.
    | RTL's window also RMWs byte 12 (0x55).  Nothing at A3+0..A3+7 may
    | move.  Flags: field non-zero -> Z=0; field MSB=0 -> N=0.
    bfclr   (8,%a3){#0:32}
    beq     _fail
    bmi     _fail
    cmp.l   #0x4E000020, (%a3)          | header long 0 intact
    bne     _fail
    cmp.l   #0x00002000, 4(%a3)         | header long 1 intact
    bne     _fail
    cmp.l   #0x00000000, 8(%a3)
    bne     _fail
    cmp.l   #0x55667788, 12(%a3)        | 5th window byte + tail intact
    bne     _fail
    cmp.l   #0x99AABBCC, 16(%a3)
    bne     _fail

    | ---- Case B: BFSET straddling into the next long ------------------
    | BFSET (8,A3){#28:8}: byte base = A3+8+3 = A3+11, r=4, field bits
    | 28..35 -> low nibble of byte 11 and high nibble of byte 12.
    | Window = A3+11..A3+15, so bytes 12..15 are RMW'd and must survive.
    | Flags: the field is NOT zero before the set — bits 32..35 are the
    | high nibble of byte 12 (0x55) — so Z=0, and N = bit 28 = 0.
    bfset   (8,%a3){#28:8}
    beq     _fail
    bmi     _fail
    cmp.l   #0x4E000020, (%a3)
    bne     _fail
    cmp.l   #0x00002000, 4(%a3)
    bne     _fail
    cmp.l   #0x0000000F, 8(%a3)
    bne     _fail
    cmp.l   #0xF5667788, 12(%a3)
    bne     _fail
    cmp.l   #0x99AABBCC, 16(%a3)
    bne     _fail

    | ---- Case C: NEGATIVE dynamic offset reaches below the EA ---------
    | A4 = A3+8; BFCLR (A4){D6=-64:8} -> byte base = A4-8 = A3+0, r=0,
    | field = byte 0 = 0x4E.  This is the ONLY architecturally-sanctioned
    | way a bitfield touches memory below its EA, and it must land
    | byte-exactly: only byte 0 changes, and the window's spill bytes
    | (A3+1..A3+4) come back unchanged.
    | Flags: field 0x4E non-zero -> Z=0; field MSB=0 -> N=0.
    lea     8(%a3), %a4
    moveq   #-64, %d6
    bfclr   (%a4){%d6:8}
    beq     _fail
    bmi     _fail
    cmp.l   #0x00000020, (%a3)          | only the tag byte cleared
    bne     _fail
    cmp.l   #0x00002000, 4(%a3)         | 5th window byte (A3+4) intact
    bne     _fail
    cmp.l   #0x0000000F, 8(%a3)
    bne     _fail

    | Restore the header tag byte via a 1-byte BFINS (also below the EA).
    move.l  #0x0000004E, %d1
    moveq   #-64, %d6
    bfins   %d1, (%a4){%d6:8}
    cmp.l   #0x4E000020, (%a3)
    bne     _fail
    cmp.l   #0x00002000, 4(%a3)
    bne     _fail

    | ---- Case D: 1-bit BFINS — worst case for the 5-byte window -------
    | BFINS D1,(8,A3){#7:1}: Musashi writes ONE byte (A3+8); the RTL
    | writes A3+8..A3+12.  All four extra bytes must be value-preserving.
    | Flags: inserted field = 1 -> N=1, Z=0.
    moveq   #1, %d1
    bfins   %d1, (8,%a3){#7:1}
    beq     _fail
    bpl     _fail
    cmp.l   #0x4E000020, (%a3)
    bne     _fail
    cmp.l   #0x00002000, 4(%a3)
    bne     _fail
    cmp.l   #0x0100000F, 8(%a3)
    bne     _fail
    cmp.l   #0xF5667788, 12(%a3)        | window spill byte 12 preserved
    bne     _fail
    cmp.l   #0x99AABBCC, 16(%a3)
    bne     _fail

    | ---- Case E: BFCHG at the very first byte of the buffer -----------
    | Re-seed, then BFCHG (A3){#0:8} — win = A3+0, the lowest address
    | the whole scenario ever touches.  Guards against any window
    | implementation that pre-decrements its base.  The 4 bytes below
    | A3 are seeded as a guard long and must not move.
    bsr     _seed
    move.l  #0x0BADF00D, -4(%a3)
    bfchg   (%a3){#0:8}
    beq     _fail
    bmi     _fail
    cmp.l   #0x0BADF00D, -4(%a3)        | guard long below the window
    bne     _fail
    cmp.l   #0xB1000020, (%a3)          | 0x4E ^ 0xFF = 0xB1
    bne     _fail
    cmp.l   #0x00002000, 4(%a3)
    bne     _fail

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

| Seed mimics the live-HW corruption shape: an 8-byte Mac OS block
| header (tag 0x4E + 24-bit size 0x20, then the heap-zone pointer)
| immediately below the long the CPU legitimately writes.
_seed:
    move.l  #0x4E000020, (%a3)
    move.l  #0x00002000, 4(%a3)
    move.l  #0x11223344, 8(%a3)
    move.l  #0x55667788, 12(%a3)
    move.l  #0x99AABBCC, 16(%a3)
    rts

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
