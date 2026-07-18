| bf_mem_rmw_dyn_straddle.s — memory-EA READ-MODIFY-WRITE bitfields
| (BFCHG/BFCLR/BFSET/BFINS) with dynamic offset/width whose field
| STRADDLES the first longword (residual+width > 32), starts whole
| bytes past the EA (offset >= 32), or uses a NEGATIVE offset — plus
| one static-form straddle probe.
|
| PRM §4.29-§4.36: for a MEMORY operand the bitfield offset addresses
| an unbounded bit array anchored at the EA — byte address = EA +
| (offset arithmetically >> 3), residual bit offset = offset & 7.  The
| field may span up to 5 bytes and the WRITE-BACK must cover the same
| window without clobbering neighbouring bits.
|
| Sibling of bfextu_mem_dyn_offset_straddle.s (read-only shapes, the
| Q700 boot-icon blit fix).  This guards the RMW window write-back
| path: decode_uop_assemble.v byte-phase/long-phase RMW cracks +
| alu.v imm[22]/imm[23] bf_win_rmw math.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  FAIL: 0xDEADBEEF.

    .text
    .org 0
_start:
    | Data buffer at 0x40800400: bytes 01 23 45 67 89 AB CD EF 11 22 33 44
    move.l  #0x40800400, %a3
    bsr     _seed

    | ---- Case A: BFCLR dyn-off, byte-aligned 32-bit straddle ----
    | BFCLR (A3){D6=16:32}: field = bytes 2..5 = 0x456789AB (Z=0, N=0).
    | After: long0=0x01230000 long1=0x0000CDEF.
    moveq   #16, %d6
    bfclr   (%a3){%d6:32}
    beq     _fail                   | field was non-zero -> Z=0
    bmi     _fail                   | field MSB (bit16=0x45 b7) = 0 -> N=0
    cmp.l   #0x01230000, (%a3)
    bne     _fail
    cmp.l   #0x0000CDEF, 4(%a3)
    bne     _fail
    cmp.l   #0x11223344, 8(%a3)
    bne     _fail

    | ---- Case B: BFSET dyn-wid, straddle at static offset 28 ----
    | BFSET (A3){#28:D5=8}: field bits 28..35 (all 0 after case A) ->
    | Z=1.  After: long0=0x0123000F long1=0xF000CDEF.
    moveq   #8, %d5
    bfset   (%a3){#28:%d5}
    bne     _fail                   | field was zero -> Z=1
    cmp.l   #0x0123000F, (%a3)
    bne     _fail
    cmp.l   #0xF000CDEF, 4(%a3)
    bne     _fail

    | ---- Case C: BFCHG dyn-both, residual 7 + width 28 (3-bit spill) ----
    | BFCHG (A3){D6=39:D5=28}: field bits 39..66 = 0x0066F778 (Z=0,
    | N=bit39=0).  After: long1=0xF1FF3210 long2=0xF1223344.
    moveq   #39, %d6
    moveq   #28, %d5
    bfchg   (%a3){%d6:%d5}
    beq     _fail
    bmi     _fail
    cmp.l   #0xF1FF3210, 4(%a3)
    bne     _fail
    cmp.l   #0xF1223344, 8(%a3)
    bne     _fail

    | ---- Case D: BFINS dyn-off, residual 7 + width 32 (max spill) ----
    | BFINS D1=0xDEADBEEF,(A3){D6=31:32}: field bits 31..62.
    | N=1 (insert MSB), Z=0.  After: long0 keeps LSB=1 (insert bit31=1),
    | long1 = 0xBD5B7DDE.
    move.l  #0xDEADBEEF, %d1
    moveq   #31, %d6
    bfins   %d1, (%a3){%d6:32}
    beq     _fail
    bpl     _fail
    cmp.l   #0x0123000F, (%a3)
    bne     _fail
    cmp.l   #0xBD5B7DDE, 4(%a3)
    bne     _fail

    | ---- Case E: BFINS dyn-both, offset >= 32 (window is unaligned) ----
    | BFINS D1=0x00000ABC,(A3){D6=40:D5=12}: bits 40..51 <- 0xABC.
    | After: long1=0xBDABCDDE, long2 untouched (guards the 5th-byte
    | write-back from clobbering).  N=1, Z=0.
    move.l  #0x00000ABC, %d1
    moveq   #40, %d6
    moveq   #12, %d5
    bfins   %d1, (%a3){%d6:%d5}
    beq     _fail
    bpl     _fail
    cmp.l   #0xBDABCDDE, 4(%a3)
    bne     _fail
    cmp.l   #0xF1223344, 8(%a3)
    bne     _fail

    | ---- Case F: BFCLR with NEGATIVE dynamic offset ----
    | A4 = A3+8; BFCLR (A4){D6=-8:8}: field = byte7 = 0xDE (Z=0, N=1).
    | After: long1=0xBDABCD00.
    lea     8(%a3), %a4
    moveq   #-8, %d6
    bfclr   (%a4){%d6:8}
    beq     _fail
    bpl     _fail
    cmp.l   #0xBDABCD00, 4(%a3)
    bne     _fail
    cmp.l   #0xF1223344, 8(%a3)
    bne     _fail

    | ---- Case G: BFINS dyn-wid with width register = 0 (means 32) ----
    | BFINS D1=0xCAFEBABE,(A3){#0:D5=0}: whole long0 replaced.
    | N=1, Z=0.
    move.l  #0xCAFEBABE, %d1
    moveq   #0, %d5
    bfins   %d1, (%a3){#0:%d5}
    beq     _fail
    bpl     _fail
    cmp.l   #0xCAFEBABE, (%a3)
    bne     _fail

    | ---- Case H: BFCLR dyn-off, 1-bit spill (residual 7 + width 26) ----
    | Re-seed, then BFCLR (A3){D6=7:26}: field bits 7..32 =
    | {long0&0x01FFFFFF, byte4 MSB} (Z=0, N=bit7=1).
    | After: long0=0x00000000, long1=0x09ABCDEF.
    bsr     _seed
    moveq   #7, %d6
    bfclr   (%a3){%d6:26}
    beq     _fail
    bpl     _fail
    cmp.l   #0x00000000, (%a3)
    bne     _fail
    cmp.l   #0x09ABCDEF, 4(%a3)
    bne     _fail
    cmp.l   #0x11223344, 8(%a3)
    bne     _fail

    | ---- Case I: STATIC-form RMW straddle (off+width > 32) ----
    | Re-seed, then BFCLR (A3){#24:#16}: field = bytes 3..4 = 0x6789
    | (Z=0, N=bit24=0).  After: long0=0x01234500, long1=0x00ABCDEF.
    bsr     _seed
    bfclr   (%a3){#24:#16}
    beq     _fail
    bmi     _fail
    cmp.l   #0x01234500, (%a3)
    bne     _fail
    cmp.l   #0x00ABCDEF, 4(%a3)
    bne     _fail

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_seed:
    move.l  #0x01234567, (%a3)
    move.l  #0x89ABCDEF, 4(%a3)
    move.l  #0x11223344, 8(%a3)
    rts

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
