| rte_supervisor_only.s — Stage D-9i V2 RTE supervisor-only enforcement.
|
| RTE is privileged (PRM §4.171).  Attempting it in user mode must
| raise a vec-8 privilege-violation exception before any frame pop
| happens.  Verifies the V2 UOP_SYS / SYS_RTE emission carries
| requires_supervisor=1 and commit.v's take_priv_exc gate fires.
|
| Mechanic: install vec-8 handler, drop to user mode via ANDI #SR,
| then execute RTE.  Handler writes PASS sentinel and halts.
|
| PASS: vec-8 handler fires → writes 0xC0FFEE00.
| FAIL: any other vector fires (wrong handler writes 0xDEADBEE4) OR
|       RTE silently pops a (nonexistent) frame and drifts into the
|       fallthrough (writes 0xDEADBEEF).
|
| Vec 8 lives at 0x00000020.

    .text
    .org 0

_start:
    | Supervisor boot: a7 = SSP pointing high, vectors set.
    lea     0x00010000, %a7
    move.l  #_priv_handler,  0x00000020   | vec 8  — must fire
    move.l  #_wrong_handler, 0x00000010   | vec 4  — must NOT fire (illegal)
    move.l  #_wrong_handler, 0x00000008   | vec 2  — must NOT fire (bus err)
    move.l  #_wrong_handler, 0x0000000C   | vec 3  — must NOT fire (addr err)

    | Drop to user mode: clear SR.S (bit 13).  Mask IRQs so a stray
    | VIA timer tick can't sneak in.  0x2700 → 0x0700 clears S.
    andi.w  #0xDFFF, %sr

    | User mode RTE — must raise vec 8 before any frame pop.
    rte

    | If RTE fell through (vec 8 did not fire), we land here.  FAIL.
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_wrong_handler:
    | Any vector other than 8 firing for an RTE attempt is a bug.
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEE4, %d0
    move.l  %d0, (%a0)
_halt_wh:
    bra     _halt_wh

_priv_handler:
    | Vec 8 fired — supervisor-only check worked.  Write PASS.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
