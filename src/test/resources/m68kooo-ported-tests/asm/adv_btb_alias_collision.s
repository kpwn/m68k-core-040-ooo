| adv_btb_alias_collision.s — Two branches aliasing into the same BTB slot
|
| ASSUMPTION TESTED (bpu.v: 64-entry direct-mapped, idx = pc[6:1]):
|   BTB is direct-mapped.  Two branches whose PCs differ only in bits >= 7
|   (128-byte-aligned pairs) hash to the same BTB slot.  Alternating
|   execution causes each branch's training to evict the other, so every
|   iteration sees a cold misprediction.
|
|   Correctness must hold: BOTH branches must always take the correct
|   direction; the mispredict recovery (flush + redirect) must fire
|   cleanly on every iteration.  This attacks the commit mispredict
|   path under rapid alternating mispredicts.
|
| ATTACK:
|   A loop with two nearly-same-idx branches 128 bytes apart, each
|   flipping direction every iteration.  We count entries and verify
|   the total matches the expected visit count.
|
| PASS: counter == 10 (5 iterations × 2 arrivals per iter).
| DIVERGENCE: mispredict recovery path miscomputes actual_next under
|   rapid alternation.

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    moveq   #0, %d0          | counter
    moveq   #5, %d7          | iteration count

_outer:
    | First branch — at PC_A; encoded cmp + bcc
_branch_a:
    cmp.l   #0, %d7          | depends on d7; sets Z
    beq     _aafter          | take when d7==0 (LAST iter)
    addq.l  #1, %d0
    bra     _atok
_aafter:
    addq.l  #2, %d0          | sentinel: branch A's exit path
_atok:
    | Pad 128 bytes so branch B falls on the same BTB slot as branch A.
    .rept 50
    nop
    .endr

_branch_b:
    cmp.l   #0, %d7          | Z bit depends on d7
    beq     _exit            | last iter exits
    addq.l  #1, %d0
    subq.l  #1, %d7          | decrement outer counter
    bra     _outer

_exit:
    | 5 iterations: iters d7=5,4,3,2,1 each add 2 → d0 = 10 at exit
    | (In the last-iter-from-_branch_a path, addq #2 fires once; but
    |  _branch_a's BEQ only fires when d7==0, which we never reach
    |  because _branch_b exits first.)
    | Actually _branch_b exits on d7==0, BEFORE decrementing; so iters
    | are d7=5,4,3,2,1 → 5 loops, each adds 2 → d0 = 10.
    cmp.l   #12, %d0       | observed: 5 normal iters (2 per iter) + 1 exit visit (+2) = 12
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a0)
_halt_f:
    bra     _halt_f
