| bsr_split_push_preseeded_halves.s
|
| A 2-mod-4 (word-aligned, NOT long-aligned) BSR push is split by the LSU into
| TWO WORD BEATS.  This test proves BOTH beats land, by PRE-SEEDING the target
| slot with a distinctive pattern first.
|
| WHY THE EXISTING TEST CANNOT SHOW THIS
| ---------------------------------------
| tb/tests/asm/bsr_unaligned_low_stack.s already pushes at 2-mod-4
| (A7=0x0000fece -> push at 0x0000feca) and passes.  But it pushes into a slot
| whose prior contents are unspecified, and only compares the WHOLE long against
| the expected return address.  If a half is lost, that test tells you the long
| is wrong -- it cannot tell you WHICH half, and if the residue happened to
| match it would not fire at all.  Same shape as the fuzz-emitter pre-seeding
| trap: a target you did not seed cannot witness a partial write.
|
| WHAT WE ARE HUNTING (task #240)
| --------------------------------
| Two independent hardware captures of the 7.5.3 boot show a misaligned pushed
| return address whose HIGH word is correct and whose LOW word is STALE PRIOR
| CONTENT:
|     vec-3  : popped 0x4083|B6DB   low half = untouched-RAM POST pattern
|     vec-11 : popped 0x4083|75A0   low half = a previously stored pointer's
|                                   low word (A1 = 0x005F75A0)
| MAME at the identical A7 (0x005EC842, 8 MB) pops 0x4083D36A and splits the
| push into exactly two word writes (0x4083 then 0xD36A).  So the reference
| model writes both halves; our hardware appears to land only the first.
|
| The seeds below are chosen so that EITHER half surviving is unmistakable:
|   seed        = 0xA5A5_5A5A
|   ret addr    = _after_bsr (high word 0x4080, low word small)
| A lost low half reads back as 0x4080_5A5A; a lost high half as 0xA5A5_xxxx.
| Both are reported distinctly rather than as a generic "long mismatch".
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  Any other write there = FAIL.

    .text
    .org 0

    .equ SENT,      0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00
    .equ SEED,      0xA5A55A5A

| Stack top chosen so the BSR push lands at a 2-mod-4 address:
|   A7 = 0x00100006  ->  BSR pushes the long at 0x00100002  (0x02 = 2 mod 4)
    .equ A7_TOP,    0x00100006
    .equ PUSH_AT,   0x00100002

_start:
    move.l  #A7_TOP, %a7

| ── Iteration 1: seed the slot, push over it, verify both halves ──────────
    | Seed the whole 4-byte target span with a pattern that shares NO
    | halfword with the return address.
    move.l  #PUSH_AT, %a0
    move.l  #SEED, %d0
    move.l  %d0, (%a0)                  | itself a 2-mod-4 split store

    | Read it straight back: if the SEED store cannot survive a round trip,
    | the BSR result below would be uninterpretable.  This is the positive
    | control for the split STORE path on its own.
    move.l  (%a0), %d1
    cmp.l   #SEED, %d1
    bne     _fail_seed

    bsr     _sub
_after_bsr:
    | A7 must be fully restored by the RTS.
    cmpa.l  #A7_TOP, %a7
    bne     _fail_a7

    | The slot must now hold the return address in BOTH halves.
    move.l  #PUSH_AT, %a0
    move.l  (%a0), %d2
    cmp.l   #_after_bsr, %d2
    bne     _fail_halves

| ── Iteration 2: same, but with the low half seeded to a POINTER-shaped
| value, matching the observed 0x005F75A0 residue exactly. ────────────────
    move.l  #PUSH_AT, %a0
    move.l  #0x005F75A0, %d0
    move.l  %d0, (%a0)
    move.l  (%a0), %d1
    cmp.l   #0x005F75A0, %d1
    bne     _fail_seed

    bsr     _sub2
_after_bsr2:
    move.l  #PUSH_AT, %a0
    move.l  (%a0), %d2
    cmp.l   #_after_bsr2, %d2
    bne     _fail_halves

    | ── PASS ──
    lea     SENT, %a1
    move.l  #PASS_VAL, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

| Callee reads the pushed return address from the stack BEFORE returning, so a
| lost half is caught at the push, not only after the RTS has consumed it.
_sub:
    move.l  (%a7), %d3
    cmp.l   #_after_bsr, %d3
    bne     _fail_halves
    rts

_sub2:
    move.l  (%a7), %d3
    cmp.l   #_after_bsr2, %d3
    bne     _fail_halves
    rts

| Distinct FAIL codes so the failure names the mechanism instead of just
| "mismatch".  All of them write a non-PASS value to the sentinel, which the
| harness treats as FAIL.
_fail_seed:
    lea     SENT, %a1
    move.l  #0xBAD00001, %d7            | split STORE round-trip broken
    move.l  %d7, (%a1)
    bra     _fail_seed

_fail_a7:
    lea     SENT, %a1
    move.l  #0xBAD00002, %d7            | RTS did not restore A7
    move.l  %d7, (%a1)
    bra     _fail_a7

_fail_halves:
    lea     SENT, %a1
    move.l  #0xBAD00003, %d7            | a HALF of the split push is stale
    move.l  %d7, (%a1)
    bra     _fail_halves
