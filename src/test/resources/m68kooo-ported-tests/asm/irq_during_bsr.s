| irq_during_bsr.s — IRQ raised between the two µops of a BSR crack
| (audit bug #1).
|
| Per `CLAUDE.md` decision #11, BSR is decoded as a 2-µop crack:
|   (a) UOP_STORE writing the return-PC to A7-4 (and updating A7).
|   (b) UOP_BRANCH/BR_BRA jumping to the target.
|
| Both µops carry the same macro PC (= the BSR opcode address).
| Today's `take_irq` lacks a `rob_is_last_uop` term, so an IRQ
| arriving after µop (a) retires but before µop (b) does will:
|   1. Push the BSR's ret_pc to the supervisor stack (frame's
|      saved-PC = BSR macro address).
|   2. Squash µop (b) via flush.
|   3. Run the IRQ handler, RTE.
|   4. Re-execute the BSR from scratch.
|   5. Push ANOTHER ret_pc to the user stack.
|   6. Take the BSR.
|
| Net: subroutine sees A7 with TWO copies of ret_pc on top of stack;
| the matching RTS pops the WRONG one (the older, duplicated
| copy) and execution flies off into the weeds.
|
| ─────────────────────────────────────────────────────────────────────
| What this test does
| ─────────────────────────────────────────────────────────────────────
| 1. Set up vec 25 handler that increments a counter.
| 2. Pre-poison the user-stack-frame area below the working A7.
| 3. Drop SR.IPL=0 to allow IPL=1 injection.
| 4. Call BSR `subr` while testbench raises IPL=1 mid-BSR.
| 5. Inside `subr`, snapshot A7's value and the LONG it points at —
|    that's the (claimed) return PC.
| 6. RTS back.
| 7. Validate:
|    (a) subr's snapshot showed exactly one ret_pc on the stack
|        — i.e. the byte just BELOW subr's snapshot A7 is still
|        the poison sentinel, NOT a duplicate ret_pc.
|    (b) IRQ counter ≤ 1 (one or zero clean fires, never two).
|    (c) Mainline resumed correctly (executed the post-BSR
|        sentinel write).
|
| ─────────────────────────────────────────────────────────────────────
| HARNESS NEEDS: same as irq_during_movem.s — needs +ipl=cycle:level
| plusarg + cpu_ipl_ext exposed on mac_top.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0201 — duplicate ret_pc on stack (mid-BSR IRQ corruption).
|   0xDEAD0202 — IRQ counter > 1 (re-fire bug #2).
|   0xDEAD0203 — RTS landed at wrong PC (bus visible only via main fall-through).

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ STACK_BASE, 0x00012000
    .equ POISON,    0xAAAAAAAA
    .equ IRQ_COUNT, 0x00011000
    .equ A7_SNAP,   0x00011004
    .equ RETPC_SNAP,0x00011008
    .equ POISON_SNAP,0x0001100C

_start:
    lea     STACK_BASE, %a7
    move.l  #_lvl1_handler, 0x00000064  | vec 25 (autovec lvl 1)

    move.l  #0, IRQ_COUNT

    | Pre-poison the area BELOW the working A7 so we can detect
    | a duplicated push.
    move.l  #POISON, (STACK_BASE-4)
    move.l  #POISON, (STACK_BASE-8)
    move.l  #POISON, (STACK_BASE-12)
    move.l  #POISON, (STACK_BASE-16)
    move.l  #POISON, (STACK_BASE-20)

    | Drop SR.IPL=0.
    move.w  #0x2000, %sr

    | The BSR under test — testbench raises IPL between the two
    | µops via a +ipl=cycle:level pulse aligned with the BSR
    | opcode's retire window.
    bsr     _subr
_post_bsr_actual:                       | the actual ret_pc that BSR pushed.

    | Mainline resumed.  Validate IRQ counter ≤ 1.
    move.l  IRQ_COUNT, %d0
    cmp.l   #1, %d0
    bhi     _fail_count

    | Validate the snapshot taken inside _subr.  RETPC_SNAP must
    | equal the actual post-BSR PC (the next instruction after BSR
    | _subr — = _post_bsr).  POISON_SNAP must still be POISON
    | (i.e., subr's A7-4 still holds the original poison value,
    | proving exactly ONE push, not two).
    move.l  POISON_SNAP, %d1
    cmp.l   #POISON, %d1
    bne     _fail_dup

    move.l  RETPC_SNAP, %d2
    | RETPC_SNAP should equal _post_bsr_actual (= the address of the
    | instruction immediately after `bsr _subr`).  Compute the
    | address via lea, copy to D3, compare against D2 (the snapshot).
    lea     _post_bsr_actual, %a0
    move.l  %a0, %d3
    cmp.l   %d3, %d2
    bne     _fail_retpc

_pass:
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d4
    move.l  %d4, (%a1)
_halt:
    bra     _halt

_fail_dup:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0201, %d4
    move.l  %d4, (%a1)
_halt_fd:
    bra     _halt_fd

_fail_count:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0202, %d4
    move.l  %d4, (%a1)
_halt_fc:
    bra     _halt_fc

_fail_retpc:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0203, %d4
    move.l  %d4, (%a1)
_halt_fr:
    bra     _halt_fr

_subr:
    | Snapshot A7 and the LONG it points at, plus the LONG below A7
    | (which should still hold the pre-BSR poison value if exactly
    | one push happened).
    move.l  %a7, A7_SNAP
    move.l  (%a7), RETPC_SNAP
    move.l  -4(%a7), POISON_SNAP
_post_bsr_in_subr:
    rts

_post_bsr:
    | Continuation after subr's RTS.  Used as a label so the test
    | can compare the snapshotted ret_pc against this address.
    nop
    rts

_lvl1_handler:
    move.l  IRQ_COUNT, %d6
    addi.l  #1, %d6
    move.l  %d6, IRQ_COUNT
    rte
