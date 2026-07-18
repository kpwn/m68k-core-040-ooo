| moves_b_to_user_space.s — supervisor writes a byte to user space via MOVES Rn,(An).
|
| Programs DFC = 1 (user data space, FC = %001) and SFC = 5 (supervisor data
| space, FC = %101) so a MOVES.B Dn,(An) drives DFC=1 on AWPROT, while a
| follow-up MOVES.B (An),Dn drives SFC=5 on ARPROT.  The architectural
| effect is identical to a plain MOVE.B (the bus is unified and the AXI
| slave does not gate on FC), so the CORRECTNESS check is byte-equality
| via plain MOVE.B (An),Dn — the FC bits are observability-only on the
| bus today.
|
| This widens the existing moves_basic coverage with the byte size
| (which exercises the size-byte AXI lane mapping in the LSU) and with
| the SFC≠DFC scenario (so a future agent can grep for ARPROT=5/AWPROT=1
| traces and confirm the FC plumbing).
|
| PASS: 0xC0FFEE00 sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | Program DFC = 1 (user data), SFC = 5 (supervisor data).
    move.l  #1, %d0
    movec   %d0, %dfc
    move.l  #5, %d0
    movec   %d0, %sfc

    | Confirm round-trip via MOVEC reads.
    movec   %dfc, %d1
    cmp.l   #1, %d1
    bne     _fail
    movec   %sfc, %d2
    cmp.l   #5, %d2
    bne     _fail

    | MOVES.B D0,(A0) — supervisor writes a single byte to user space.
    | Pre-fill the target longword with a recognisable pattern so we can
    | confirm only byte 3 (lane 0 in big-endian) is overwritten.
    lea     0x00020200, %a0
    move.l  #0x11223344, %d0
    move.l  %d0, (%a0)

    | Drive byte 0xA5 to (A0)+3 via MOVES.B D3,(A0).  Use offset 3 by
    | adding 3 to a copy of A0 — keeps the test on the (An) addressing
    | mode the decoder supports today.
    move.l  %a0, %a1
    addq.l  #3, %a1
    move.l  #0x000000A5, %d3
    moves.b %d3, (%a1)

    | Verify via plain MOVE.L (A0),Dn that ONLY the targeted byte was
    | written (DFC=1 is observability today; the slave answers the
    | write the same way).
    move.l  (%a0), %d4
    cmp.l   #0x112233A5, %d4
    bne     _fail

    | MOVES.B (A1),D5 — supervisor reads back the byte using SFC=5 on the bus.
    moves.b (%a1), %d5
    andi.l  #0xFF, %d5
    cmp.l   #0xA5, %d5
    bne     _fail

    | Now flip SFC to 1 (user data) and re-read; data must be identical
    | because there's no per-FC isolation in the slave today.  This step
    | is here so a future "FC-aware DDR slave" landing can extend the
    | check to assert the read VECTOR for FC=1 vs FC=5 differs.
    move.l  #1, %d0
    movec   %d0, %sfc
    moves.b (%a1), %d6
    andi.l  #0xFF, %d6
    cmp.l   #0xA5, %d6
    bne     _fail

    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a2)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a2)
_halt_fail:
    bra     _halt_fail
