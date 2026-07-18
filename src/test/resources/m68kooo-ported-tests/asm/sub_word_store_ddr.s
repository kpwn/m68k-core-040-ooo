| sub_word_store_ddr.s -- round-trip MOVE.B / MOVE.W / MOVE.L at every
| lane offset in DDR-backed RAM.
|
| This exercises the full LSU -> dcache (bypass) -> axi_narrow_to_wide
| -> ddr_ctrl path that the P1 audit flagged as SLVERR-prone on real
| hardware: the narrow AXI master emits a byte-granular awaddr (e.g.
| 0x..3) with a one- or two-bit wstrb, and the adapter used to forward
| that unchanged with awsize=2, violating AXI alignment rules.
|
| With the P1 fix, axi_narrow_to_wide now derives awsize from wstrb
| and aligns awaddr accordingly.  In sim this test goes through
| tb_top's AXI model (which implements big-endian byte-lane mapping
| and already handles sub-word stores), so the directed assertions
| here verify the 68k semantics end-to-end.  On HW the same instruction
| sequence will exercise the real adapter -> ddr_ctrl path that sim
| previously masked.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Base of a DDR-backed scratch buffer clear of code / stack / VIA /
    | peripheral windows.  Word-aligned longword at 0x00114400.
    lea     0x00114400, %a0

    | --------------------------------------------------------------
    | Section 1: MOVE.L at every 4-byte-aligned offset.  This is the
    | baseline — no sub-word semantics involved.
    | --------------------------------------------------------------
    move.l  #0xDEADBEEF, (%a0)
    move.l  (%a0), %d0
    cmp.l   #0xDEADBEEF, %d0
    bne     _fail

    move.l  #0xCAFEBABE, 4(%a0)
    move.l  4(%a0), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail

    | --------------------------------------------------------------
    | Section 2: MOVE.B at every lane offset within a longword.
    | Prime the longword, overwrite one byte, read back the full word
    | and verify neighbour lanes are preserved.
    | --------------------------------------------------------------

    | Offset 0 (high byte).  Prime 0x11223344; write 0xAA at offset 0.
    move.l  #0x11223344, (%a0)
    move.b  #0xAA, (%a0)
    move.l  (%a0), %d0
    cmp.l   #0xAA223344, %d0
    bne     _fail

    | Offset 1 (byte 1).  Prime again; write 0xBB at offset 1.
    move.l  #0x11223344, (%a0)
    move.b  #0xBB, 1(%a0)
    move.l  (%a0), %d0
    cmp.l   #0x11BB3344, %d0
    bne     _fail

    | Offset 2 (byte 2).  Prime; write 0xCC at offset 2.
    move.l  #0x11223344, (%a0)
    move.b  #0xCC, 2(%a0)
    move.l  (%a0), %d0
    cmp.l   #0x1122CC44, %d0
    bne     _fail

    | Offset 3 (low byte — the path the P1 audit specifically called
    | out: awaddr=base+3, wstrb=4'b0001 out of the LSU).
    move.l  #0x11223344, (%a0)
    move.b  #0xDD, 3(%a0)
    move.l  (%a0), %d0
    cmp.l   #0x112233DD, %d0
    bne     _fail

    | --------------------------------------------------------------
    | Section 3: MOVE.W at the two legal word-aligned offsets within
    | a longword (offsets 0 and 2).  Word at odd offset is an
    | address-error on 68040, so only 0 and 2 are tested.
    | --------------------------------------------------------------

    | Offset 0 (high half).  Prime; write 0xA5A5 at offset 0.
    move.l  #0x11223344, (%a0)
    move.w  #0xA5A5, (%a0)
    move.l  (%a0), %d0
    cmp.l   #0xA5A53344, %d0
    bne     _fail

    | Offset 2 (low half — two-bit wstrb from the LSU).
    move.l  #0x11223344, (%a0)
    move.w  #0x5A5A, 2(%a0)
    move.l  (%a0), %d0
    cmp.l   #0x11225A5A, %d0
    bne     _fail

    | --------------------------------------------------------------
    | Section 4: scattered neighbour-lane isolation.  Four byte
    | stores at offsets 0..3 in sequence, each into a fresh longword,
    | then verify all four lanes survive correctly.
    | --------------------------------------------------------------
    move.l  #0x00000000, 8(%a0)
    move.b  #0x11, 8(%a0)
    move.b  #0x22, 9(%a0)
    move.b  #0x33, 10(%a0)
    move.b  #0x44, 11(%a0)
    move.l  8(%a0), %d0
    cmp.l   #0x11223344, %d0
    bne     _fail

    | --------------------------------------------------------------
    | Section 5: word stores bracketing a byte store — three lanes
    | spanning a longword.  Exercises the full interleave.
    | --------------------------------------------------------------
    move.l  #0x00000000, 12(%a0)
    move.w  #0xBEEF, 12(%a0)
    move.b  #0xCA, 14(%a0)
    move.b  #0xFE, 15(%a0)
    move.l  12(%a0), %d0
    cmp.l   #0xBEEFCAFE, %d0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_fail_halt:
    bra     _fail_halt
