| bsr_split_push_cold_line_fill.s
|
| A 2-mod-4 BSR push is split into TWO WORD BEATS.  This test forces the FIRST
| beat to MISS a COLD D-cache line, so beat 2 is issued while that line's fill
| may still be outstanding.
|
| THE MECHANISM UNDER TEST (task #240)
| -------------------------------------
| dcache.v's write-allocate keeps ONE pending store per fill: "On fill complete,
| apply the pending store (if a store triggered [the fill])" (dcache.v:98), and
| merges it into fill_crit_word at dcache.v:1089-1095.  If beat 1 receives its
| bvalid before its fill completes, the LSU advances (S_ST_WAIT -> S_ST_GAP ->
| S_ST_REQ2) and issues beat 2 into the same line.  When the fill data then
| lands and only beat 1 is merged, beat 2 is overwritten by STALE MEMORY
| CONTENT.
|
| That predicts precisely the hardware signature: the HIGH word (beat 1)
| correct, the LOW word (beat 2) equal to the line's prior memory content.
| Observed twice on 7.5.3:
|     vec-3  : popped 0x4083|B6DB   low half = the POST pattern
|     vec-11 : popped 0x4083|75A0   low half = a previously stored pointer
| MAME at the identical A7 splits the push into 0x4083 then 0xD36A and lands
| BOTH.
|
| WHY THE SIBLING TEST CANNOT SEE IT
| -----------------------------------
| bsr_split_push_preseeded_halves.s seeds the slot with a store, which leaves
| the line WARM -- beat 1 then hits and there is no fill to race.  It passes,
| and that is the point: it is the positive control.  Here we seed and then
| CPUSHL the line, so memory holds the seed and the cache does not.  Beat 1 is
| forced to miss.
|
| Run with a large +ddr_read_delay to widen the fill window; the default
| near-zero-latency memory model may close the race by itself.  A pass at low
| latency therefore does NOT clear the design -- sweep the delay.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

    .equ SENT,      0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00

| A7 = 0x00100006 -> BSR pushes the long at 0x00100002 (2 mod 4).
| Both halves live in the same 32-byte line based at 0x00100000, so beat 1 and
| beat 2 target ONE line -- exactly the hardware case.
    .equ A7_TOP,    0x00100006
    .equ PUSH_AT,   0x00100002
    .equ LINE,      0x00100000

_start:
    move.l  #A7_TOP, %a7

| ── Iteration 1: seed -> push line to memory -> cold-miss push ────────────
    | Seed with the pointer-shaped value actually observed as the residue.
    move.l  #PUSH_AT, %a0
    move.l  #0x005F75A0, %d0
    move.l  %d0, (%a0)

    | Flush the line to memory AND invalidate it, so memory holds the seed and
    | the next access must FILL.  cpushl (not cinvl) -- we need the seed to
    | survive in memory so a lost beat shows up as that exact value.
    move.l  #LINE, %a1
    cpushl  %dc, (%a1)

    bsr     _sub
_after_bsr:
    cmpa.l  #A7_TOP, %a7
    bne     _fail_a7

    move.l  #PUSH_AT, %a0
    move.l  (%a0), %d2
    cmp.l   #_after_bsr, %d2
    bne     _fail_halves

| ── Iteration 2: same, but the callee also touches the line, so the fill and
| a second access to the same line overlap more aggressively. ─────────────
    move.l  #PUSH_AT, %a0
    move.l  #0xA5A55A5A, %d0
    move.l  %d0, (%a0)
    move.l  #LINE, %a1
    cpushl  %dc, (%a1)

    bsr     _sub_touch
_after_bsr2:
    move.l  #PUSH_AT, %a0
    move.l  (%a0), %d2
    cmp.l   #_after_bsr2, %d2
    bne     _fail_halves

| ── Iteration 3: cold line AND a long-aligned control push, to prove the
| cold-fill path itself is fine when the push is NOT split. ───────────────
    move.l  #0x00100008, %a7            | push lands at 0x00100004 (aligned)
    move.l  #0x00100004, %a0
    move.l  #0x5A5AA5A5, %d0
    move.l  %d0, (%a0)
    move.l  #LINE, %a1
    cpushl  %dc, (%a1)

    bsr     _sub3
_after_bsr3:
    move.l  #0x00100004, %a0
    move.l  (%a0), %d2
    cmp.l   #_after_bsr3, %d2
    bne     _fail_aligned_control

    | ── PASS ──
    lea     SENT, %a1
    move.l  #PASS_VAL, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

| Read the pushed return address before returning, so a lost beat is caught at
| the push rather than only when the RTS consumes it.
_sub:
    move.l  (%a7), %d3
    cmp.l   #_after_bsr, %d3
    bne     _fail_halves
    rts

| Touch another word of the SAME line first, then check the pushed value.
_sub_touch:
    move.l  #0x0010001C, %a2
    move.l  (%a2), %d4
    move.l  (%a7), %d3
    cmp.l   #_after_bsr2, %d3
    bne     _fail_halves
    rts

_sub3:
    move.l  (%a7), %d3
    cmp.l   #_after_bsr3, %d3
    bne     _fail_aligned_control
    rts

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

_fail_aligned_control:
    lea     SENT, %a1
    move.l  #0xBAD00004, %d7            | ALIGNED push over a cold line broke
    move.l  %d7, (%a1)                  | -> not split-specific; wider bug
    bra     _fail_aligned_control
