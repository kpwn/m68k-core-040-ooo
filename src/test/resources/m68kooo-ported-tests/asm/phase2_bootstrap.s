| phase2_bootstrap.s — end-to-end phase-2 rehearsal modelling a
| ~30-line "ROM bootstrap": set VBR, install vectors, configure
| ITT0, enable TC, drop to user, trigger TRAP, handler calls RTE,
| user code resumes.
|
| DEFERRED: requires all three of
|   - MOVEC (movec-vbr agent)
|   - RTE   (movec-vbr agent)
|   - MMU integration (mmu-integrate agent) for TC/ITT0 to matter
| Currently each of these stubs as NOP; the test will hang/fail.
|
| Once all blockers land this should PASS end-to-end.

    .text
    .org 0

_start:
    | ── supervisor bootstrap ────────────────────────────────────
    lea     0x00010000, %a7              | SSP

    | Install vectors at VBR = 0x00100000
    move.l  #_trap_hdlr, 0x00100080       | vec 32 TRAP #0
    move.l  #_bus_hdlr,  0x00100008       | vec 2  bus err
    move.l  #_bus_hdlr,  0x0000000C       | vec 3  addr err (VBR=0 fallback)

    | VBR
    move.l  #0x00100000, %a0
    movec   %a0, %vbr

    | ITT0 transparent translate 0x40000000..0x4FFFFFFF
    move.l  #0x4000C000, %d0
    movec   %d0, %itt0

    | TC enable
    move.l  #0x80000000, %d0
    movec   %d0, %tc

    | ── drop to user ────────────────────────────────────────────
    andi.w  #0xDFFF, %sr

    | User code: install a user stack then TRAP
    lea     0x00008000, %a7
    move.l  #0xF00DF00D, %d4            | marker pre-TRAP
    trap    #0                           | → _trap_hdlr (supervisor)

    | ── post-RTE resume in user mode ───────────────────────────
    cmp.l   #0xF00DF00D, %d4            | marker survived RTE?
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_trap_hdlr:
    | Supervisor; just return.
    rte

_bus_hdlr:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEE2, %d0
    move.l  %d0, (%a0)
_halt_bh:
    bra     _halt_bh
