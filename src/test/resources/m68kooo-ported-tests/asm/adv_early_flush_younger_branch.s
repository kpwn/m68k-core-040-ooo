| adv_early_flush_younger_branch.s — Part 29 regression: an OLDER mispredicting
| branch with a YOUNGER, already-in-flight, correctly-resolving branch sitting
| behind it in the ROB when the older one's misprediction is caught.
|
| BACKGROUND (docs/BUG_calibration_word_misplaced_0d00.md Part 29, real
| hardware): a real boot-blocking livelock traced to exactly this shape — the
| ROB's mispredict flush is retire-gated (fires only once the mispredicting
| branch reaches the ROB head), so a younger branch that has ALREADY been
| allocated and resolved by the branch EU while the older one is still
| in-flight gets collaterally flushed once the older branch's retire-time
| flush finally lands. RobPlugin.scala now also fires a SECOND, EARLIER flush
| trigger directly off the branch EU's S1 resolution (`earlyBranchMispredict`),
| targeting the current COMMITTED pc rather than the mispredicting branch's own
| resolved target. This test is a directed regression for that exact
| second-branch-in-flight shape, extending deep_mispredict.s's "many
| wrong-path ops behind a cold mispredicting branch" structure with a SECOND,
| younger branch planted mid-pile so the branch EU genuinely has two branches
| in flight (older mispredicting, younger resolving on its own terms) at once.
|
| PASS bar: this isn't just "doesn't hang" — D0..D6 (pre-set before the
| mispredicting branch) must survive completely intact, proving BOTH branches'
| wrong-path effects (including the second branch's own target, which must
| NEVER be reached) are fully discarded and the correct path re-executes
| cleanly to the same final architectural state the (always-correct, slower)
| retire-gated path would produce.

    .text
    .org 0

_start:
    | Pre-set arch D0..D6 to known good values. Must survive the mispredict
    | flush (both the OLD retire-gated path and the NEW early path) intact.
    move.l  #0xA0A0A0A0, %d0
    move.l  #0xB1B1B1B1, %d1
    move.l  #0xC2C2C2C2, %d2
    move.l  #0xD3D3D3D3, %d3
    move.l  #0xE4E4E4E4, %d4
    move.l  #0xF5F5F5F5, %d5
    move.l  #0x06060606, %d6

    | OLDER branch: cold forward BEQ, actually taken -> MISPREDICT.
    moveq   #1, %d7
    cmp.l   %d7, %d7        | Z=1
    beq     _target           | MISPREDICT: cold forward BEQ, actually taken

    | ── wrong-path poison, with a SECOND (younger) branch planted mid-pile ──
    | Everything from here to _halt_wrong is wrong-path: it dispatches into
    | the ROB (and, for the second branch, resolves in the EU) BEFORE the
    | older beq above is known mispredicted, exactly Part 29's shape.
    move.l  #0xBAD00000, %d0
    move.l  #0xBAD00001, %d1
    move.l  #0xBAD00002, %d2
    add.l   %d0, %d1

    | Younger, in-flight branch: its OWN local resolution is unconditional
    | (always "correctly" taken from the branch EU's point of view) — it is
    | still entirely wrong-path relative to the older beq's real outcome, and
    | must be discarded just as completely, including its own target below.
    bra     _wrong_target2

    | Should never be reached from here.
    move.l  #0xBAD00003, %d3
    move.l  #0xBAD00004, %d4
    move.l  #0xBAD00005, %d5
    move.l  #0xBAD00006, %d6
    add.l   %d2, %d3
    add.l   %d3, %d4
    add.l   %d4, %d5

    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a0)
_halt_wrong:
    bra     _halt_wrong

_wrong_target2:
    | The younger branch's own target: also wrong-path, must never commit.
    move.l  #0xBAD0BAD0, %d0
    move.l  #0xBAD0BAD1, %d1
    bra     _halt_wrong

_target:
    | Verify D0..D6 still hold their pre-branch values — proves both the
    | older branch's wrong path AND the younger branch's own (locally
    | "correct") wrong-path target were fully discarded, and the correct
    | path re-executed cleanly to the right final state.
    cmp.l   #0xA0A0A0A0, %d0
    bne     _fail
    cmp.l   #0xB1B1B1B1, %d1
    bne     _fail
    cmp.l   #0xC2C2C2C2, %d2
    bne     _fail
    cmp.l   #0xD3D3D3D3, %d3
    bne     _fail
    cmp.l   #0xE4E4E4E4, %d4
    bne     _fail
    cmp.l   #0xF5F5F5F5, %d5
    bne     _fail
    cmp.l   #0x06060606, %d6
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
