| bsr_split_push_irq_in_gap.s
|
| Drives a 2-mod-4 BSR push (split into TWO WORD BEATS by the LSU) in a tight
| loop while external interrupts are injected with +ipl=<cycle>:<level>, so an
| IRQ lands at every phase relative to the split window.
|
| WHY (task #240)
| ---------------
| Two hardware captures of the 7.5.3 boot show a misaligned pushed return
| address whose HIGH word is correct and whose LOW word is STALE PRIOR CONTENT:
|     vec-3  : 0x4083|B6DB   low half = the POST pattern
|     vec-11 : 0x4083|75A0   low half = a previously stored region pointer
| MAME at the identical A7 (0x005EC842, 8 MB) splits the push into 0x4083 then
| 0xD36A and lands BOTH halves, 405 times with zero bad pops.
|
| Sim has already failed to reproduce under a WARM line
| (bsr_split_push_preseeded_halves.s) and a COLD line across seven memory
| latencies (bsr_split_push_cold_line_fill.s). The remaining difference between
| sim and the real machine is that hardware is taking interrupts continuously
| (~500/s from the VIA alone) while the ROM does these pushes. This test adds
| that variable.
|
| IMPORTANT — this test is only meaningful WITH +ipl= events. Run bare it is a
| plain loop and proves nothing about the race; that bare run is the positive
| control that the loop itself is sound. Drive it as:
|     Vmac_top +test=bsr_split_push_irq_in_gap +ipl=400:1 +ipl=403:1 ...
|
| Interrupts must not themselves fail the test, so autovector handlers are
| installed first (VBR = 0 at reset, so the table is at 0x00000000; autovector
| level N is vector 24+N, i.e. bytes (24+N)*4).
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

    .equ SENT,      0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00
    .equ ITERS,     200

| A7 = 0x00100006 -> BSR pushes the long at 0x00100002 (2 mod 4), both halves
| inside the single 32-byte line based at 0x00100000.
    .equ A7_TOP,    0x00100006
    .equ PUSH_AT,   0x00100002
    .equ LINE,      0x00100000

_start:
    | ── Install autovector handlers for levels 1..7 (vectors 25..31) ──
    | Every one points at a bare RTE, so a taken interrupt perturbs timing
    | without changing architectural state the test checks.
    move.l  #_irq_stub, %d0
    move.l  #0x64, %a0                  | vector 25 = level-1 autovector
    moveq   #6, %d1                     | 7 vectors: 25..31
_vecloop:
    move.l  %d0, (%a0)+
    dbra    %d1, _vecloop

    | Also fill the spurious-interrupt vector (24) for safety.
    move.l  #0x60, %a0
    move.l  %d0, (%a0)

    | Run at IPL 0 so injected interrupts are actually taken.
    move.w  #0x2000, %sr

    move.l  #ITERS, %d5

_loop:
    move.l  #A7_TOP, %a7

    | Seed the slot with the pointer-shaped value seen as the residue on HW,
    | then push the line out so beat 1 must MISS and fill.
    move.l  #PUSH_AT, %a0
    move.l  #0x005F75A0, %d0
    move.l  %d0, (%a0)
    move.l  #LINE, %a1
    cpushl  %dc, (%a1)

    bsr     _sub
_after_bsr:
    | DO NOT re-read PUSH_AT here.  Once the RTS has popped, A7 is back at
    | A7_TOP, and an 8-byte exception frame pushed at A7_TOP occupies
    | 0x000FFFFE..0x00100005 -- which COMPLETELY OVERLAPS PUSH_AT
    | (0x00100002..0x00100005).  So any interrupt taken in this window
    | legitimately overwrites the slot, and checking it here reports
    | 0xBAD00003 for a perfectly correct machine.  That false RED is exactly
    | what the first version of this test produced.
    |
    | The honest checks are: (a) the callee reads the pushed value while A7 is
    | still BELOW the slot, where no frame can reach it, and (b) arriving at
    | this label at all proves the RTS popped a correct return address -- a
    | lost low half would send it to garbage.

    | A7 fully restored by the RTS.
    cmpa.l  #A7_TOP, %a7
    bne     _fail_a7

    subq.l  #1, %d5
    bne     _loop

    | ── PASS ──
    lea     SENT, %a1
    move.l  #PASS_VAL, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

| Read the pushed return address before returning, so a lost beat is caught at
| the push and not only when the RTS consumes it.
_sub:
    move.l  (%a7), %d3
    cmp.l   #_after_bsr, %d3
    bne     _fail_halves
    rts

| Interrupt handler: do nothing, return. Present so the injected IRQ changes
| TIMING only.
_irq_stub:
    rte

_fail_halves:
    lea     SENT, %a1
    move.l  #0xBAD00003, %d7            | a HALF of the split push is stale
    move.l  %d7, (%a1)
    bra     _fail_halves

_fail_a7:
    lea     SENT, %a1
    move.l  #0xBAD00002, %d7            | RTS did not restore A7
    move.l  %d7, (%a1)
    bra     _fail_a7
