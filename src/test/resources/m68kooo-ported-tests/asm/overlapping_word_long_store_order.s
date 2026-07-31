| overlapping_word_long_store_order.s — a WORD store and a LONG store to the
| SAME address must retire in program order.
|
| WHY THIS EXISTS (task #140, 2026-07-28)
| ------------------------------------------------------------------------
| The ioResult stall leaves a File Manager param block with
|     [A0+4] = 0x0016002E     (qType = 0x0016, ioTrap = 0x002E)
| where the setup routine _FSSyncIOCall at 0x0002E874 executes
|     0002E882  317C 0005 0004   move.w #5,(0x04,%a0)
| so qType should read 5.  Measured on hardware with a before/after capture on
| the SAME block: the field was 0x00000000 before the call and 0x0016002E after,
| while ioResult in the same sequence went 0xFFFF -> 0x0001 CORRECTLY.  So the
| block really is the one the setup wrote, and 0x0016 was actively written --
| it is not a stale leftover.
|
| _FSSyncIOCall writes qType at +4 as a WORD and never touches ioTrap at +6, so
| a different writer owns +6.  If that writer stores a LONGWORD at +4
| (qType<<16 | trapword) then the two overlap and the surviving qType is decided
| purely by the order the stores become visible:
|
|     long @+4 then word @+4  ->  0x0005002E   correct
|     word @+4 then long @+4  ->  0x0016002E   what hardware shows
|
| This test pins that ordering down in isolation.  It uses the REAL values from
| the failing block so a failure reproduces the exact observed corruption.
|
| A PASS DOES NOT CLEAR THE HYPOTHESIS: this is the single-threaded, cache-warm,
| back-to-back case.  It does not model an interrupt landing between the two
| stores, nor real DDR latency, nor the two stores being separated by the trap
| dispatch.  It only rules out the simplest form.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD5701 — long-then-word: word store did not win (qType != 5)
|   0xDEAD5702 — long-then-word: the low half (ioTrap) was disturbed
|   0xDEAD5703 — word-then-long: long store did not win
|   0xDEAD5704 — separated by other memory traffic: word store did not win
|   0xDEAD5705 — byte-store variant: byte store did not win
|   0xDEAD5706 — reversed field halves (a byte-lane error, not an ordering one)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SCR,       0x00030000

_start:
    lea     0x00010000, %a7
    lea     SCR, %a0

    | ── Case 1: LONG then WORD — the word must win the high half ─────────
    | This is the ordering that produces the CORRECT value on a good machine.
    move.l  #0x0016002E, 4(%a0)
    move.w  #5, 4(%a0)
    move.l  4(%a0), %d0
    cmp.l   #0x0005002E, %d0
    bne     fail_c1
    | and specifically: low half (ioTrap) must be untouched by the word store
    move.w  6(%a0), %d1
    cmp.w   #0x002E, %d1
    bne     fail_c1_low

    | ── Case 2: WORD then LONG — the long must win, clobbering qType ─────
    | This is the ordering that reproduces the OBSERVED corruption.  It is the
    | CORRECT result for this program order; the test asserts the machine does
    | not "helpfully" preserve the earlier word.
    move.w  #5, 4(%a0)
    move.l  #0x0016002E, 4(%a0)
    move.l  4(%a0), %d0
    cmp.l   #0x0016002E, %d0
    bne     fail_c2

    | ── Case 3: the two stores SEPARATED by unrelated memory traffic ─────
    | The LSU handles one memory op at a time; putting loads and stores to
    | other addresses between them exercises the ordering across a drain.
    move.l  #0x0016002E, 4(%a0)
    move.l  #0xAAAABBBB, 0x100(%a0)
    move.l  0x100(%a0), %d2
    move.l  #0xCCCCDDDD, 0x200(%a0)
    move.l  0x200(%a0), %d3
    move.w  #5, 4(%a0)
    move.l  4(%a0), %d0
    cmp.l   #0x0005002E, %d0
    bne     fail_c3

    | ── Case 4: BYTE store overlapping a LONG store ──────────────────────
    | qType's high byte alone, to catch a narrower lane/merge error.
    move.l  #0x0016002E, 4(%a0)
    move.b  #0x00, 4(%a0)
    move.l  4(%a0), %d0
    cmp.l   #0x0016002E & 0x00FFFFFF, %d0
    bne     fail_c4

    | ── Case 5: halves not swapped ───────────────────────────────────────
    | A byte-lane bug would show as 0x002E0016 rather than a lost store.
    move.l  #0x0016002E, 4(%a0)
    move.l  4(%a0), %d0
    cmp.l   #0x002E0016, %d0
    beq     fail_c5

    move.l  #0xC0FFEE00, %d1
    move.l  %d1, PASS_SENT
    bra     .

fail_c1:
    move.l  #0xDEAD5701, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c1_low:
    move.l  #0xDEAD5702, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c2:
    move.l  #0xDEAD5703, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c3:
    move.l  #0xDEAD5704, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c4:
    move.l  #0xDEAD5705, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c5:
    move.l  #0xDEAD5706, %d1
    move.l  %d1, PASS_SENT
    bra     .
