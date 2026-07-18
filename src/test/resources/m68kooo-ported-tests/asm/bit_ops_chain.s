| bit_ops_chain.s — BTST/BSET/BCLR/BCHG ping-pong on the same bit
|
| Each bit op writes CCR Z based on the PRE-operation value of the
| tested bit (Z = ~old_bit).  Chaining operations that alternately
| read and write the same bit forces the CCR-stall path to serialise
| reader-after-writer correctly for bit ops too.
|
| Sequence on D0 (starting at 0), probing bit 5:
|   1. BTST  #5, D0   → Z=1 (bit was 0), D0 unchanged
|   2. BSET  #5, D0   → Z=1 (bit was 0), D0 = 0x20
|   3. BTST  #5, D0   → Z=0 (bit now 1), D0 unchanged
|   4. BCHG  #5, D0   → Z=0 (bit was 1), D0 = 0x00
|   5. BTST  #5, D0   → Z=1 (bit back to 0), D0 unchanged
|   6. BSET  #5, D0   → Z=1, D0 = 0x20
|   7. BCLR  #5, D0   → Z=0 (bit was 1), D0 = 0x00
|   8. BTST  #5, D0   → Z=1, D0 unchanged
|
| Each step does a Bcc right after to check Z, plus CMP D0 vs expected.

    .text
    .org 0

_start:
    moveq   #0, %d0

    btst    #5, %d0                  | Z=1
    bne     _fail
    cmp.l   #0, %d0
    bne     _fail

    bset    #5, %d0                  | Z=1, D0 = 0x20
    bne     _fail
    cmp.l   #0x20, %d0
    bne     _fail

    btst    #5, %d0                  | Z=0
    beq     _fail
    cmp.l   #0x20, %d0
    bne     _fail

    bchg    #5, %d0                  | Z=0, D0 = 0
    beq     _fail
    cmp.l   #0, %d0
    bne     _fail

    btst    #5, %d0                  | Z=1
    bne     _fail
    cmp.l   #0, %d0
    bne     _fail

    bset    #5, %d0                  | Z=1, D0 = 0x20
    bne     _fail
    cmp.l   #0x20, %d0
    bne     _fail

    bclr    #5, %d0                  | Z=0, D0 = 0
    beq     _fail
    cmp.l   #0, %d0
    bne     _fail

    btst    #5, %d0                  | Z=1
    bne     _fail
    cmp.l   #0, %d0
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a0)
_halt_fail:
    bra     _halt_fail
