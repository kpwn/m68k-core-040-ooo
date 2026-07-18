| link_unlk_frame.s — LINK.W A6,#-8 frame discipline corner
|
| D-9d corner: verify the frame-pointer chain when locals are written
| inside the frame and the frame pointer (A6) gets perturbed between
| LINK/UNLK.  The 3-phase crack must leave:
|   * A7 restored to its pre-LINK value (0x00010000)
|   * A6 restored to its pre-LINK value (0xA6A6A6A6 canary)
|   * Saved frame contents visible across the call (locals readback)
|
| What the bundled landing almost got wrong: the LINK.W phase-0 STORE
| uses `arch_src_b = An (data to push)` while `arch_src_a = A7 (base,
| also the writeback target)`.  A src_a/src_b swap would push the old
| A7 instead of the old A6 — UNLK would then read back A7's old value
| into A6 and the canary check below would fail.  We seed A6 with a
| distinctive canary so any mis-pushed value is immediately visible.

    .text
    .org 0

_start:
    | Set SP to safe scratch RAM (grows downward from 0x00010000).
    lea     0x00010000, %a7

    | Seed A6 with a distinctive canary.  This value must be restored
    | by UNLK or the test fails.
    move.l  #0xA6A6A6A6, %a6

    | Canary in D7 — must survive the call / LINK / UNLK sequence.
    move.l  #0xDEADBEEF, %d7

    | Call subroutine that uses LINK/UNLK with an 8-byte frame.
    bsr     _sub

    | ── Post-return checks ───────────────────────────────────────────
    | D0 must carry the locals-readback value the subroutine wrote.
    cmp.l   #0x11112222, %d0
    bne     _fail
    | D1 must carry the second local the subroutine read back.
    cmp.l   #0x3344FFEE, %d1
    bne     _fail

    | A6 must be restored to the canary.
    cmpa.l  #0xA6A6A6A6, %a6
    bne     _fail

    | A7 must be fully restored to 0x00010000 (pre-BSR value minus the
    | 4-byte return slot that BSR pushed, which RTS pops — so exactly
    | 0x00010000 after RTS).
    cmpa.l  #0x00010000, %a7
    bne     _fail

    | D7 canary preserved.
    cmp.l   #0xDEADBEEF, %d7
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

    | ── Subroutine ───────────────────────────────────────────────────
    | LINK A6,#-8 should:
    |   1. push saved A6 (canary 0xA6A6A6A6) to -(A7)
    |   2. copy new A7 into A6 (A6 becomes frame pointer)
    |   3. subtract 8 from A7 (allocate 8-byte frame)
    |
    | Then we write two 4-byte locals via the frame pointer:
    |   (A6 - 4) ← 0x11112222   [low 4 bytes of frame]
    |   (A6 - 8) ← 0x3344FFEE   [high 4 bytes of frame]
    |
    | Read them back via (A6, -4) and (A6, -8), XOR them into D0.
    | UNLK A6 must then:
    |   1. move A6 (frame pointer) into A7 (pops the locals)
    |   2. load saved A6 from (A7) (restores canary)
    |   3. add 4 to A7 (pop past the saved A6 slot)
_sub:
    link    %a6, #-8
    | Write two locals into the frame.  (A6) points at the saved A6
    | slot; locals live below that at (A6 - 4) and (A6 - 8).
    move.l  #0x11112222, -4(%a6)
    move.l  #0x3344FFEE, -8(%a6)
    | Read them back from the frame — the call-site checks D0 and D1.
    | If LINK's phase-2 ADD-disp went wrong (or UNLK phase-1 LOAD read
    | the wrong slot) the locals wouldn't be at -4/-8 off the frame
    | pointer.
    move.l  -4(%a6), %d0            | D0 = 0x11112222 (low local)
    move.l  -8(%a6), %d1            | D1 = 0x3344FFEE (high local)
    unlk    %a6
    rts
