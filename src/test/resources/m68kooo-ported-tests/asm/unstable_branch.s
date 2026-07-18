| unstable_branch.s — Loop with per-iteration-alternating branch direction
|
| A counter D0 drives 8 iterations.  Inside each iteration, we compare
| D0 against a mask that toggles every iteration and take a Bcc that
| flips direction each pass.  The bimodal BTB counter will ping-pong,
| forcing retraining on alternate iterations — exactly the pathological
| case the predictor hates.
|
| We accumulate a deterministic sum in D2 whose value depends on hitting
| the correct branch direction every iteration.
|
| Per iteration i = 7..0:
|   if (i & 1) == 0  → even: add 10 to D2
|   else             → odd : add 1  to D2
| Running from i=7 down to 0 (DBRA-style):
|   iterations: 7,6,5,4,3,2,1,0
|   odd (7,5,3,1) × 1  = 4
|   even (6,4,2,0) × 10 = 40
|   total D2 = 44

    .text
    .org 0

_start:
    moveq   #7, %d0                 | outer counter: 7..0
    moveq   #0, %d2                 | accumulator

_loop:
    | Test low bit of D0: AND with 1 leaves Z=1 on even, Z=0 on odd
    move.l  %d0, %d1
    andi.l  #1, %d1                 | sets Z = (low bit == 0)
    beq     _even                   | taken iff D0 even
    | odd path
    addq.l  #1, %d2
    bra     _next
_even:
    addi.l  #10, %d2
_next:
    dbra    %d0, _loop

    | Expected: 4 odd × 1 + 4 even × 10 = 44
    cmp.l   #44, %d2
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d3
    move.l  %d3, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d3
    move.l  %d3, (%a0)
_halt_fail:
    bra     _halt_fail
