| bfset_mem_static.s — Stage D-8 directed: static BFSET on memory (d16,An).
|
| Corner: memory RMW 3-µop crack (LOAD→BFSET→STORE) must route the
| displacement through ext2 (ext1 carries the bitfield descriptor).
| V2 initially took dst_ext1=ext1, which made the LOAD target the
| descriptor word instead of the real displacement — caught by this
| test.
|
| BFSET (A0){#3:#12} — sets bits [3..14] to 1.  Full longword at (A0)
| is read, bits 3..14 ORed with 1, written back.  Other bits preserved.
|
| Memory target at 0x117200 pre-loaded with 0x12345678.
|   Original = 0001_0010_0011_0100_...
|   Bits 3..14 set → mask from MSB: bit 3 = 0x10000000; 12 bits wide
|   covering bits 3..14 at positions [28..17] in 32-bit word numbering
|   from MSB.  Mask = 0x1FFE_0000.
|   Result = 0x12345678 | 0x1FFE0000 = 0x1FFE5678.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0
_start:
    | Set up A0 → 0x117200, write initial pattern.
    lea     0x00117200, %a0
    move.l  #0x12345678, (%a0)

    | BFSET (A0){#3:#12} — static offset 3, static width 12.
    .word   0xeed0, 0x00cc          | bfset (A0){3:12}

    | Verify memory content.
    move.l  (%a0), %d1
    move.l  #0x1ffe5678, %d2
    cmp.l   %d2, %d1
    beq     1f
    bra     _fail

1:
    | Bonus: BFSET (d16,A0){#0:#32} on a cleared longword should write
    | all-ones (full-width static set on (d16,An)).  Tests the
    | displacement-through-ext2 shift.
    move.l  #0x00000000, 16(%a0)
    .word   0xeee8, 0x0000, 0x0010  | bfset 16(A0){0:32}
    move.l  16(%a0), %d3
    move.l  #0xffffffff, %d4
    cmp.l   %d4, %d3
    beq     _pass
    bra     _fail

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a1
    move.l  %d7, (%a1)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a1
    move.l  %d7, (%a1)
    bra     _halt
