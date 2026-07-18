| mmu_nested_fault.s — nested page-fault / double-fault handling
|
| Regression for nested vec-2 page-fault unwind with format-7 RTE retry.
| The sentinel page is supervisor-passthrough so PASS/FAIL reporting
| does not self-fault after the intended nested page-fault sequence.
|
| SCENARIO: User touches VA=A (unmapped) → vec 2 → handler touches
| VA=B (also unmapped) → nested vec 2 → second handler fixes BOTH
| mappings + RTEs back to the first handler → first handler patches
| A + RTEs to user → user retries, reads the right value.
|
| This exercises the exception-within-exception path that 68040
| implements by pushing a NEW format-7 frame on the supervisor stack.
|
| Expected PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    move.l  #_buserr_hdlr, 0x00100008    | vec 2
    move.l  #_trap_hdlr,   0x00100080    | vec 32 done
    | Use Dn source (decoder MOVEC An-source bug).
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    move.l  #0x4000C000, %d0
    movec   %d0, %itt0

    | DTT0 — supervisor-only passthrough for low memory so the handler
    | can patch page tables and seed backing pages without self-faulting.
    | This deliberately does NOT cover the 0x8000_0000 range used by the
    | nested probe, so the handler's second load still traps.
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0

    | DTT1 — supervisor-only passthrough for the test sentinel page.
    | Without this, the PASS/FAIL write at 0xFFFF0000 takes an unrelated
    | vec-2 after the nested unwind has already succeeded.
    move.l  #0xFF00A000, %d0
    movec   %d0, %dtt1

    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | Build page tables such that BOTH target VAs (A=0x80001000 and
    | B=0x80002000, same L1 slot 0x40, different L2 slots) are invalid.
    move.l  #0x0021000a, 0x00200100     | L1[0x40] -> L2 (table4, used)
    move.l  #0x0022000a, 0x00210000     | L2[0x00] -> L3 (table4 for A)
    move.l  #0x0023000a, 0x00210004     | L2[0x01] -> L3' (table4 for B)
    move.l  #0x00000000, 0x00220004     | L3[0x01]  = invalid (VA A leaf)
    move.l  #0x00000000, 0x00230008     | L3'[0x02] = invalid (VA B leaf)

    | Fault-count state in d7 so the handler can distinguish nest level.
    moveq   #0, %d7

    | Phase-A MMU uses bit 15 for the E flag.
    move.l  #0x00008770, %d0
    movec   %d0, %tc

    andi.w  #0xDFFF, %sr
    lea     0x00008000, %a7

    move.l  #0x80001000, %a1            | A
    move.l  (%a1), %d0                  | ← fault 1 → handler

    | After all unwinds, A's new content must be readable.
    cmp.l   #0x0000BEEF, %d0
    bne     _fail

    trap    #0

_fail:
    move.l  #0xDEADBEEF, 0xFFFF0000
_fail_halt:
    bra     _fail_halt

_buserr_hdlr:
    addq.l  #1, %d7
    cmp.l   #1, %d7
    bne     _nest2
    | Level-1: touch VA B while still in supervisor — triggers nested fault.
    move.l  #0x80002000, %a2
    move.l  (%a2), %d1                  | ← nested fault → _nest2
    | After nested RTE, THIS handler patches A and returns.
    | PFN A = 0x00300000 (low memory so DTT0 covers the seeding store).
    move.l  #0x00300009, 0x00220004     | L3[0x01] = PFN A, used, resident
    move.l  #0x0000BEEF, 0x00300000     | seed VA A's frame
    rte

_nest2:
    | Skip the faulting nested load in the nested frame; otherwise RTE
    | just re-enters the same move.l (%a2),%d1 forever.
    move.l  2(%a7), %d0                 | stacked PC of the nested load
    addq.l  #2, %d0
    move.l  %d0, 2(%a7)

    | Level-2: patch L3'[0x02] for VA B and its backing content.
    | PFN B = 0x00400000 (low memory, DTT0-covered).
    move.l  #0x00400009, 0x00230008     | L3'[0x02] = PFN B, used, resident
    move.l  #0x0000CAFE, 0x00402000     | seed VA B's frame (offset 0x2000)
    rte

_trap_hdlr:
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done
