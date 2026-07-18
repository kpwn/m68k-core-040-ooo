| scc_abs_long.s -- Scc (xxx).L absolute-long corners (task #235 / F3)
|
| Validates the V2 2-µop crack for Scc (xxx).L.  Corners:
|   - has_src_a must be 0 for absolute (no base reg).  If it were 1
|     we'd read a stale An tag and the EA would be wrong.
|   - imm must be the FULL 32-bit address from {ext1,ext2}, not a sign-
|     extended 16-bit value.
|   - is_abs flag must be set so the LSU EA path uses the imm directly
|     and not An+disp.
|   - Cross-byte addressing within an aligned longword (lanes 0/1/2/3).
|
| All four byte lanes of an aligned longword are exercised, plus a
| second longword to catch any address-truncation bug.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Test 1: byte 0 (high byte) of the seed long at 0x00150000.
    lea     0x00150000, %a0
    move.l  #0xdeadbeef, (%a0)
    moveq   #0, %d5                  | Z=1
    tst.l   %d5
    seq     0x00150000.l             | EQ true → 0xff at byte 0
    move.l  (%a0), %d0
    cmp.l   #0xffadbeef, %d0
    bne     _fail1

    | Test 2: byte 1 of the same long.
    move.l  #0xaabbccdd, (%a0)       | reseed
    move.l  #1, %d5
    cmpi.l  #2, %d5                  | C=1, N=1
    scs     0x00150001.l             | CS true → 0xff at byte 1
    move.l  (%a0), %d0
    cmp.l   #0xaaffccdd, %d0
    bne     _fail2

    | Test 3: byte 2 of the same long, false condition writes 0x00.
    move.l  #0x11223344, (%a0)       | reseed
    moveq   #1, %d5                  | Z=0
    tst.l   %d5
    seq     0x00150002.l             | EQ false (Z=0) → 0x00 at byte 2
    move.l  (%a0), %d0
    cmp.l   #0x11220044, %d0
    bne     _fail3

    | Test 4: byte 3 (low byte) of a different long at 0x00150004.
    lea     0x00150004, %a1
    move.l  #0x55667788, (%a1)
    moveq   #1, %d5                  | Z=0, NE true
    tst.l   %d5
    sne     0x00150007.l             | NE true → 0xff at byte 3
    move.l  (%a1), %d0
    cmp.l   #0x556677ff, %d0
    bne     _fail4

    | Test 5: a high-address long that requires the FULL 32-bit imm
    | (low half = 0).  If we accidentally sign-extended a 16-bit value
    | the address would land at 0x10000 instead of 0x10010000.
    lea     0x00160000, %a2
    move.l  #0x99aabbcc, (%a2)
    moveq   #0, %d5                  | Z=1
    tst.l   %d5
    seq     0x00160003.l             | EQ true → 0xff at byte 3
    move.l  (%a2), %d0
    cmp.l   #0x99aabbff, %d0
    bne     _fail5

    | Test 6: SF (unconditional false) at byte 0 of a third long, just
    | to make sure the path stores 0x00 even when CCR has every flag set.
    lea     0x00170000, %a3
    move.l  #0xfedcba98, (%a3)
    | Set ALL ccr bits via a tricky CMP.
    move.l  #0x80000000, %d5
    cmpi.l  #1, %d5                  | 0x80000000 - 1 → V=1, C=0
    sf      0x00170000.l
    move.l  (%a3), %d0
    cmp.l   #0x00dcba98, %d0
    bne     _fail6

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_fail1:
    bra     _halt_fail1

_fail2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_halt_fail2:
    bra     _halt_fail2

_fail3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
_halt_fail3:
    bra     _halt_fail3

_fail4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_halt_fail4:
    bra     _halt_fail4

_fail5:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0005, %d0
    move.l  %d0, (%a0)
_halt_fail5:
    bra     _halt_fail5

_fail6:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0006, %d0
    move.l  %d0, (%a0)
_halt_fail6:
    bra     _halt_fail6
