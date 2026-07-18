| bf_memind_dyn_straddle.s — memory-INDIRECT ([bd,An] / indexed)
| bitfields with dynamic offset/width whose field straddles the first
| longword, starts past it (offset >= 32), or is fully static with
| off+width > 32.  Covers read-only (BFEXTU/BFEXTS/BFFFO) and RMW
| (BFCLR/BFSET/BFINS) sub-ops, including one pre-indexed and one
| post-indexed full-format shape.
|
| PRM §4.29-§4.36 unbounded-bit-array semantics — sibling of
| bfextu_mem_dyn_offset_straddle.s (plain-EA read-only) and
| bf_mem_rmw_dyn_straddle.s (plain-EA RMW).  Guards the windowed
| memind common tails in decode_uop_assemble.v.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  FAIL: 0xDEADBEEF.

    .text
    .org 0
_start:
    | Pointer slot at A3+8 -> data buffer at 0x40800500.
    | Data: bytes 01 23 45 67 89 AB CD EF 11 22 33 44.
    move.l  #0x40800480, %a3
    move.l  #0x40800500, %a4
    move.l  %a4, 8(%a3)
    move.l  #0x01234567, (%a4)
    move.l  #0x89ABCDEF, 4(%a4)
    move.l  #0x11223344, 8(%a4)

    | ---- Case 1: RO dyn-off straddle ----
    | BFEXTU ([8,A3]){D6=16:32} -> 0x456789AB.
    moveq   #16, %d6
    bfextu  ([8,%a3]){%d6:32}, %d0
    cmp.l   #0x456789AB, %d0
    bne     _fail

    | ---- Case 2: RO dyn-both straddle ----
    | BFEXTS ([8,A3]){D6=36:D5=16} -> 0xFFFF9ABC.
    moveq   #36, %d6
    moveq   #16, %d5
    bfexts  ([8,%a3]){%d6:%d5}, %d0
    cmp.l   #0xFFFF9ABC, %d0
    bne     _fail

    | ---- Case 3: RO dyn-wid straddle ----
    | BFEXTU ([8,A3]){#28:D5=8} -> bits 28..35 = 0x78.
    moveq   #8, %d5
    bfextu  ([8,%a3]){#28:%d5}, %d0
    cmp.l   #0x78, %d0
    bne     _fail

    | ---- Case 4: RO fully-STATIC straddle ----
    | BFEXTU ([8,A3]){#16:#32} -> 0x456789AB.
    bfextu  ([8,%a3]){#16:#32}, %d0
    cmp.l   #0x456789AB, %d0
    bne     _fail

    | ---- Case 5: RMW dyn-off straddle ----
    | BFCLR ([8,A3]){D6=16:32}: field 0x456789AB (Z=0, N=0).
    | After: L0=0x01230000 L1=0x0000CDEF.
    moveq   #16, %d6
    bfclr   ([8,%a3]){%d6:32}
    beq     _fail
    bmi     _fail
    cmp.l   #0x01230000, (%a4)
    bne     _fail
    cmp.l   #0x0000CDEF, 4(%a4)
    bne     _fail

    | ---- Case 6: RMW dyn-both, offset >= 32 ----
    | BFSET ([8,A3]){D6=40:D5=12}: bits 40..51 (0x00C pre -> Z=0, N=0).
    | After: L1=0x00FFFDEF.
    moveq   #40, %d6
    moveq   #12, %d5
    bfset   ([8,%a3]){%d6:%d5}
    beq     _fail
    bmi     _fail
    cmp.l   #0x00FFFDEF, 4(%a4)
    bne     _fail

    | ---- Case 7: BFINS memind dyn-wid straddle ----
    | BFINS D1=0x5A,([8,A3]){#28:D5=8}: bits 28..35 <- 0x5A.
    | After: L0=0x01230005 L1=0xA0FFFDEF.  N=0, Z=0.
    move.l  #0x5A, %d1
    moveq   #8, %d5
    bfins   %d1, ([8,%a3]){#28:%d5}
    beq     _fail
    bmi     _fail
    cmp.l   #0x01230005, (%a4)
    bne     _fail
    cmp.l   #0xA0FFFDEF, 4(%a4)
    bne     _fail

    | ---- Case 8: PRE-INDEXED memind RMW, offset >= 32 ----
    | D2=1: EA = mem[A3 + 4 + D2.l*4] = mem[A3+8].
    | BFCLR ([4,A3,D2.l*4]){D6=40:8}: field = byte5 = 0xFF (Z=0, N=1).
    | After: L1=0xA000FDEF.
    moveq   #1, %d2
    moveq   #40, %d6
    bfclr   ([4,%a3,%d2.l*4]){%d6:8}
    beq     _fail
    bpl     _fail
    cmp.l   #0xA000FDEF, 4(%a4)
    bne     _fail

    | ---- Case 9: POST-INDEXED memind RO dyn-both ----
    | EA = mem[A3+8] + D2.l*2 - 2 = data base.
    | BFEXTU ([8,A3],D2.l*2,-2){D6=16:D5=16} -> bits 16..31 of L0 =
    | 0x0005.
    moveq   #16, %d6
    moveq   #16, %d5
    bfextu  ([8,%a3],%d2.l*2,-2){%d6:%d5}, %d0
    cmp.l   #0x0005, %d0
    bne     _fail

    | ---- Case 10: BFINS memind DYN-BOTH, no-index, straddle ----
    | BFINS D1=0xFFFF1234,([8,A3]){D6=28:D5=16}: bits 28..43 <- 0x1234.
    | Before: L0=0x01230005 L1=0xA000FDEF.
    | After:  L0=0x01230001 L1=0x2340FDEF.  N=0, Z=0.
    move.l  #0xFFFF1234, %d1
    moveq   #28, %d6
    moveq   #16, %d5
    bfins   %d1, ([8,%a3]){%d6:%d5}
    beq     _fail
    bmi     _fail
    cmp.l   #0x01230001, (%a4)
    bne     _fail
    cmp.l   #0x2340FDEF, 4(%a4)
    bne     _fail

    | ---- Case 11: BFINS memind DYN-BOTH, PRE-INDEXED, residual 7 ----
    | BFINS D1=0xABC,([4,A3,D2.l*4]){D6=39:D5=12}: bits 39..50 <- 0xABC.
    | After: L1=0x23579DEF.  N=1 (insert MSB), Z=0.
    move.l  #0xABC, %d1
    moveq   #39, %d6
    moveq   #12, %d5
    bfins   %d1, ([4,%a3,%d2.l*4]){%d6:%d5}
    beq     _fail
    bpl     _fail
    cmp.l   #0x23579DEF, 4(%a4)
    bne     _fail

    | ---- Case 12: BFINS memind DYN-BOTH, POST-INDEXED, negative off ----
    | EA = mem[A3+8] + D2.l*2 - 2 = data base.
    | Seed the long below the buffer first (bits -4..-1 land there).
    | BFINS D1=0x5A,([8,A3],D2.l*2,-2){D6=-4:D5=8}: bits -4..3 <- 0x5A.
    | After: [-4(A4)]=0xDEADBE75 (byte[-1] 0x77 -> 0x75),
    | L0=0xA1230001.  N=0, Z=0.
    move.l  #0xDEADBE77, -4(%a4)
    move.l  #0x5A, %d1
    moveq   #-4, %d6
    moveq   #8, %d5
    bfins   %d1, ([8,%a3],%d2.l*2,-2){%d6:%d5}
    beq     _fail
    bmi     _fail
    cmp.l   #0xA1230001, (%a4)
    bne     _fail
    cmp.l   #0xDEADBE75, -4(%a4)
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
