| flush_younger_rts_bsr_reexec.s -- Part 29's LITERAL shape: the older
| mispredicting instruction is an `rts` (a RAS-predicted return, deliberately
| excluded from BTB training by BranchEuPlugin), and a younger `bsr` is already
| in flight behind it AND on the architecturally correct path.
|
| Construction: the callee rewrites its own return address on the stack, so the
| `rts` returns to _real_n while the RAS still predicts _wrong_n. The wrong path
| at _wrong_n is nothing but filler ops that fall straight through into _real_n,
| so the `bsr` at _real_n IS fetched/dispatched on the mispredicted path, gets
| squashed when the `rts` retires mispredicted, and must then be re-fetched and
| executed exactly once. RobPlugin's `flushPcReg := nextPcRd0` (the rts's
| resolved return address) lands exactly on the `bsr` opcode.
|
| D5 counts callee entries (must end at 3). D4 counts wrong-path-only fillers
| (must end at 0). The stack must also stay balanced: A7 is compared against its
| entry value.

    .text
    .org 0

_start:
    moveq   #0, %d5
    moveq   #0, %d4
    move.l  %sp, %a2            | remember the entry SP

    | ---- case 1: 1 filler between the RAS prediction and the real return ----
    bsr.w   _redir1
_wrong1:
    addq.l  #1, %d4
_real1:
    bsr.w   _bump

    | ---- case 2: 4 fillers ----
    bsr.w   _redir2
_wrong2:
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
_real2:
    bsr.w   _bump

    | ---- case 3: 9 fillers (deeper squash window) ----
    bsr.w   _redir3
_wrong3:
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
_real3:
    bsr.w   _bump

    | ---- verify ----
    cmp.l   #3, %d5
    bne     _fail
    tst.l   %d4
    bne     _fail
    cmp.l   %a2, %sp
    bne     _fail

    lea     0xFFFF0000, %a3
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a3)
_halt:
    bra     _halt

_redir1:
    move.l  #_real1, (%sp)      | real return != the RAS-predicted _wrong1
    rts
_redir2:
    move.l  #_real2, (%sp)
    rts
_redir3:
    move.l  #_real3, (%sp)
    rts

_bump:
    addq.l  #1, %d5
    rts

_fail:
    lea     0xFFFF0000, %a3
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a3)
_halt_fail:
    bra     _halt_fail
