| moves_l_from_user.s — supervisor reads a long from "user space" via MOVES (An),Rn.
|
| Programs SFC = 1 (user data space) and DFC = 5 (supervisor data) so a
| MOVES.L (An),Dn drives SFC=1 (FC=%001 → ARPROT[2:0]=001) on the read
| bus.  Architecturally the read returns the same long as a plain MOVE.L
| because the slave is unified.  The test therefore asserts:
|   (a) MOVEC SFC programming round-trips.
|   (b) MOVES.L (An),Dn returns the long that a prior MOVE.L wrote.
|   (c) MOVES.L (An),Dn followed by MOVES.L Dn,(An) — read-then-write
|       round-trip with SFC≠DFC, asserting the LSU does NOT confuse
|       SFC vs DFC selection between phases.
|   (d) MOVES.L Dn,(An) with predec-style addressing fallback — uses
|       LEA+constant arithmetic to land at a different An, since the
|       legacy MOVES decoder only accepts (An).
|
| PASS: 0xC0FFEE00 sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | SFC = 1 (user data), DFC = 5 (supervisor data).
    move.l  #1, %d0
    movec   %d0, %sfc
    move.l  #5, %d0
    movec   %d0, %dfc

    | Confirm round-trip — defensive against a movec-broadcast latency
    | regression (Task #47).
    movec   %sfc, %d1
    cmp.l   #1, %d1
    bne     _fail
    movec   %dfc, %d2
    cmp.l   #5, %d2
    bne     _fail

    | Stage data: plain MOVE.L writes the canonical sentinel.
    lea     0x00020300, %a0
    move.l  #0xDEADC0DE, %d0
    move.l  %d0, (%a0)

    | (b) supervisor reads "user space" via MOVES.L (A0),D3.
    moves.l (%a0), %d3
    cmp.l   #0xDEADC0DE, %d3
    bne     _fail

    | (c) round-trip — read with SFC=1, write back with DFC=5 to a
    | second slot.  The LSU must use SFC for the read and DFC for the
    | write; if it confused them the data could end up at a wrong
    | address (today's slave is FC-agnostic, but the per-µop direction
    | must still be honoured).
    lea     0x00020310, %a1
    moves.l (%a0), %d4         | SFC read
    moves.l %d4, (%a1)         | DFC write

    move.l  (%a1), %d5
    cmp.l   #0xDEADC0DE, %d5
    bne     _fail

    | Read-back via MOVES.L too — second SFC read after the DFC write
    | exercises the LSU's S_IDLE → S_MMU_WAIT → S_LD_WAIT path with
    | the side-channel FC override surviving the inter-op gap.
    moves.l (%a1), %d6
    cmp.l   #0xDEADC0DE, %d6
    bne     _fail

    | (d) MOVES.L Dn,(An) with a fresh An (post-LEA) — sanity that the
    | LSU is not pinning FC to the previous in-flight µop's value.
    lea     0x00020320, %a2
    move.l  #0xCAFEF00D, %d7
    moves.l %d7, (%a2)
    move.l  (%a2), %d0
    cmp.l   #0xCAFEF00D, %d0
    bne     _fail

    lea     0xFFFF0000, %a3
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a3)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a3
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a3)
_halt_fail:
    bra     _halt_fail
