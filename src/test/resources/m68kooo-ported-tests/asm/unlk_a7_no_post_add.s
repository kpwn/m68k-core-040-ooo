| unlk_a7_no_post_add.s — UNLK A7 must NOT add 4 after the pop.
|
| PRM §4.191 defines UNLK as:
|     An -> SP ;  (SP)+ -> An
| When An IS A7 both steps collapse onto the same register: the value
| popped from (SP) IS the new SP, so no post-increment survives.
|
| Musashi has a dedicated `unlk, 32, ., a7` handler that is exactly:
|     REG_A[7] = m68ki_read_32(REG_A[7]);
| with no adjustment.
|
| REGRESSION CONTEXT: the generic 3-phase crack is
|     A7 <- An ;  LOAD (A7) -> An ;  A7 += 4
| With An == A7 phase 1 loads into A7 and phase 2 then adds 4 to the
| value just loaded, leaving A7 four bytes too high.  Measured pre-fix:
| A7 = 0x11223348 where Musashi gives 0x11223344.  The fix terminates
| the crack after phase 1 for the A7 case.
|
| An != A7 was already correct and is re-checked below.
|
| Cross-checked against Musashi v4.60 (68040 mode): states matched.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | ── UNLK A7 ────────────────────────────────────────────────────
    | mem[0x00090000] = 0x11223344 ; A7 = 0x00090000
    |   A7 <- (A7) = 0x11223344, and nothing further.
    lea     0x00090000, %a7
    move.l  #0x11223344, (%a7)
    unlk    %a7
    move.l  %a7, %d0
    lea     0x00010000, %a7           | restore before anything faults

    cmp.l   #0x11223344, %d0          | NOT 0x11223348
    bne     _fail

    | ── UNLK A7 again with a different payload ─────────────────────
    | Guards against a fix that happened to land on the right answer
    | for one particular value (e.g. by masking rather than by dropping
    | the add).
    lea     0x00091000, %a7
    move.l  #0x0009ABC0, (%a7)
    unlk    %a7
    move.l  %a7, %d0
    lea     0x00010000, %a7

    cmp.l   #0x0009ABC0, %d0
    bne     _fail

    | ── Regression guard: UNLK A6 (An != A7) keeps the +4 ───────────
    | A6 = 0x00092000, mem[0x00092000] = 0x55667788
    |   A7 <- A6            = 0x00092000
    |   A6 <- (A7)          = 0x55667788
    |   A7 += 4             = 0x00092004
    move.l  #0x00092000, %a6
    move.l  #0x55667788, 0x00092000
    unlk    %a6
    move.l  %a7, %d0
    move.l  %a6, %d1
    lea     0x00010000, %a7

    cmp.l   #0x00092004, %d0
    bne     _fail
    cmp.l   #0x55667788, %d1
    bne     _fail

    | ── LINK A7,#0 / UNLK A7 round trip ────────────────────────────
    | End-to-end check that ties both A7 special cases together.
    |   link %a7,#0 :  A7 -= 4        -> 0x00092FFC
    |                  mem[A7] = A7   =  0x00092FFC   (post-decrement)
    |                  A7 += 0        -> 0x00092FFC
    |   unlk %a7    :  A7 <- (A7)     =  0x00092FFC
    | so A7 must come back as 0x00092FFC.
    | The displacement MUST be 0 here: with a non-zero frame size A7
    | ends up below the pushed slot and UNLK would pop an unrelated
    | word (A7 is its own frame pointer in this degenerate form).
    | Either defect alone shows up as 0x00093000:
    |   - LINK pushing the PRE-decrement SP -> UNLK pops 0x00093000
    |   - UNLK adding a stray +4            -> 0x00092FFC + 4
    lea     0x00093000, %a7
    link    %a7, #0
    unlk    %a7
    move.l  %a7, %d0
    lea     0x00010000, %a7

    cmp.l   #0x00092FFC, %d0
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
