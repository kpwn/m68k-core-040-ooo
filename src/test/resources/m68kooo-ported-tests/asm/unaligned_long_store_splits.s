| unaligned_long_store_splits.s — 68040 unaligned .L store is split, not faulted
|
| Companion to exc_addr_error (which now asserts the load side).  Per
| MC68040UM §3.1.2, all operand misalignments are handled automatically;
| the LSU splits the store into aligned beats.  This test verifies the
| store side: a 4-byte long written at an odd address must land at the
| right bytes.  The vector-3 handler is installed as a regression canary
| — if it ever fires, we've reverted to 68000 semantics.

    .text

_start:
    lea     0x00010000, %a7
    move.l  #_addr_err, 0x0000000C       | vector 3 — regression canary

    | Seed memory with 0xFF so we can detect the 4 bytes the store
    | writes.
    lea     0x00100000, %a0
    move.l  #0xFFFFFFFF, (%a0)           | [0..3] = FF
    move.l  #0xFFFFFFFF, 4(%a0)          | [4..7] = FF

    | Unaligned LONG store at address 0x00100001: four bytes
    |   [0x00100001..0x00100004] = 0xDE 0xAD 0xBE 0xEF
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0x00100001              | unaligned .L store

    | Verify bytes one by one (read aligned .b from each offset).
    | Can't just do MOVE.L because splitting into aligned beats is the
    | whole point.
    move.b  0x00100000, %d1              | pre-byte unchanged
    cmp.b   #0xFF, %d1
    bne     _fail

    move.b  0x00100001, %d1
    cmp.b   #0xDE, %d1
    bne     _fail

    move.b  0x00100002, %d1
    cmp.b   #0xAD, %d1
    bne     _fail

    move.b  0x00100003, %d1
    cmp.b   #0xBE, %d1
    bne     _fail

    move.b  0x00100004, %d1
    cmp.b   #0xEF, %d1
    bne     _fail

    move.b  0x00100005, %d1              | post-byte unchanged
    cmp.b   #0xFF, %d1
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xBADBAD00, %d1
    move.l  %d1, (%a1)
_fhlt:
    bra     _fhlt

_addr_err:
    lea     0xFFFF0000, %a1
    move.l  #0xFEEDFACE, %d1
    move.l  %d1, (%a1)
_ahlt:
    bra     _ahlt
