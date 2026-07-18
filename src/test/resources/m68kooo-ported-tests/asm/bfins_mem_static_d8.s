| bfins_mem_static_d8.s — Stage D-8 directed: static BFINS into memory.
|
| Corner: BFINS is the unique bitfield op that reads a second register
| source (Dn_insert from ext1[14:12]) AND writes the EA destination.
| V2 must set imm_valid=0 so the dispatch gate doesn't short-circuit
| src_b_rdy (imm_valid=1 bypasses the tag-wait path).  Caught during
| D-8 implementation — earlier drafts set imm_valid=1 for BFINS and
| broke the RMW phase-1.
|
| Scenarios:
|   1. BFINS D0,(A0){#5:#10} — insert D0 low-10-bits into bits 5..14.
|      Initial (A0) = 0xFFFFFFFF.  D0 = 0x000003FF (all 10 bits set).
|      Field mask bits 5..14 (from MSB) = bit positions [26..17] of 32-bit
|      word = 0x07FE_0000 (10 bits).  Insert value 0x3FF left-shifted
|      to field position = 0x07FE_0000.  Old & ~mask | insert_rotated =
|      (0xFFFFFFFF & 0xF801_FFFF) | 0x07FE0000 = 0xFFFFFFFF.
|      Memory stays 0xFFFFFFFF.
|
|   2. BFINS D0,(A0){#5:#10} — same offset/width, insert D0 = 0.
|      Initial (A0) = 0xFFFFFFFF.  Result = 0xF801FFFF (mask cleared).
|
|   3. BFINS D0,16(A1){#4:#4} — exact ROM shape efea 0104 0010.
|      Initial 16(A1) = 0x12345678.  D0 = 0x0000_000A.
|      Field bits 4..7 (from MSB) = bits [27..24] of 32-bit word =
|      0x0F00_0000.  Insert 0xA << 24 = 0x0A00_0000.
|      Result = (0x12345678 & 0xF0FFFFFF) | 0x0A000000 = 0x1A345678.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0
_start:
    | Scenario 1 — insert all-1s into middle field, old all-1s.
    lea     0x00117300, %a0
    move.l  #0xffffffff, (%a0)
    move.l  #0x000003ff, %d0
    .word   0xefd0, 0x014a          | bfins D0,(A0){5:10}
    move.l  (%a0), %d1
    move.l  #0xffffffff, %d2
    cmp.l   %d2, %d1
    beq     1f
    bra     _fail

1:
    | Scenario 2 — insert zero into middle field, old all-1s.
    move.l  #0xffffffff, (%a0)
    moveq   #0, %d0
    .word   0xefd0, 0x014a          | bfins D0,(A0){5:10}
    move.l  (%a0), %d1
    move.l  #0xf801ffff, %d2
    cmp.l   %d2, %d1
    beq     2f
    bra     _fail

2:
    | Scenario 3 — ROM-frontier shape: bfins D0,16(A1){4:4}
    lea     0x00117400, %a1
    move.l  #0x12345678, 16(%a1)
    move.l  #0x0000000a, %d0
    .word   0xefe9, 0x0104, 0x0010  | bfins D0,16(A1){4:4}
    move.l  16(%a1), %d1
    move.l  #0x1a345678, %d2
    cmp.l   %d2, %d1
    beq     _pass
    bra     _fail

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a2
    move.l  %d7, (%a2)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a2
    move.l  %d7, (%a2)
    bra     _halt
