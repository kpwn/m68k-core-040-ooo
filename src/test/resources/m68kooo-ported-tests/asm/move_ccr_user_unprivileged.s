| move_ccr_user_unprivileged.s — MOVE.W CCR,<ea> is UNPRIVILEGED.
|
| PRM §4.135: MOVE from CCR is a 68010+ instruction available in USER
| mode.  This is the one property that distinguishes it from its
| sibling MOVE.W SR,<ea> (0x40C0), which IS privileged on 68010+.
|
| Getting this backwards would be invisible to a supervisor-mode test —
| the whole directed suite boots in supervisor — so this test drops to
| user mode first and requires the instruction to execute normally.
|
| FAIL modes are distinguished by sentinel value so a regression says
| which way it broke:
|   0xDEAD0008 — took vector 8 (privilege violation): wrongly privileged
|   0xDEAD0004 — took vector 4 (illegal):             decode row missing
|   0xDEADBEEF — executed but produced the wrong value

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_priv_handler, 0x00000020     | vector 8  — privilege
    move.l  #_illegal_handler, 0x00000010  | vector 4  — illegal

    move.l  #0x00008000, %a0
    move.l  %a0, %usp
    andi.w  #0xDFFF, %sr                   | drop to user mode (clear S)

    | ── Now in USER mode ───────────────────────────────────────────
    move.l  #0xFFFFFFFF, %d0
    move.w  #0x001F, %ccr
    move.w  %ccr, %d0                      | must NOT trap
    move.l  %d0, %d7
    cmp.l   #0xFFFF001F, %d7
    bne     _fail

    | Memory destination in user mode too.
    lea     0x00107000, %a1
    move.l  #0xAAAAAAAA, (%a1)
    move.w  #0x000A, %ccr
    move.w  %ccr, (%a1)                    | must NOT trap
    move.w  (%a1), %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0x000A, %d1
    bne     _fail
    move.w  2(%a1), %d2
    and.l   #0xFFFF, %d2
    cmp.l   #0xAAAA, %d2
    bne     _fail

_pass:
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

_priv_handler:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0008, %d0
    move.l  %d0, (%a0)
_halt_priv:
    bra     _halt_priv

_illegal_handler:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_halt_ill:
    bra     _halt_ill
