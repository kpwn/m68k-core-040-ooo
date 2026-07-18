| exc_partial_macro_move_mem_mem.s — sync exception during a multi-µop crack.
|
| Bug shape (sibling of Bug B): MOVE.L (A0)+,(A1)+ cracks into 4 μops:
|   ph0: LOAD (A0) → TMP1
|   ph1: A0 += 4
|   ph2: STORE TMP1 → (A1)        ← this faults (A1 is unmapped)
|   ph3: A1 += 4
|
| Every μop in the crack carries the SAME pc field (= macro start).
| When ph2 STOREs and bus-errors, `rob_exc=1` reaches commit and
| `take_exc` fires with saved_pc = rob_pc = macro start.  But ph0 and
| ph1 already retired — A0 has been incremented.
|
| If the handler does a plain RTE (the OS pattern for resumable
| bus errors — used by demand paging), the macro re-executes from
| its start.  A0 advances another 4.  Net A0 delta = 8 instead of 4.
|
| THIS TEST: builds two seeded source longs at A0_INIT and A0_INIT+4.
| The first carries the "expected" payload, the second a "trap" value.
| The handler patches the FRAME's PC to skip the MOVE.L on the SECOND
| entry only (counting trap entries in D7), and patches A1 to a mapped
| address before the second RTE.  Then we read what got stored at the
| (now mapped) destination:
|   - If A0 advanced by 4 (silicon-correct continuation):
|       The STORE reads TMP1 = the LOADed long at A0_INIT (= 0xCAFEBABE).
|   - If A0 advanced by 8 (today's bug — partial-macro replay):
|       After RTE the macro re-LOADs from A0+4 (= 0xDEADBEEF, the
|       trap value), then STOREs that.  Net: dst = 0xDEADBEEF.
|
| PASS  : dst == 0xCAFEBABE → silicon-correct
| FAIL  : dst == 0xDEADBEEF → bug observed (partial-macro replay)
| FAIL  : anything else     → unexpected exit
|
| This test currently FAILs on this RTL — it's added to deferred.txt
| with a pointer to docs/sync_exc_partial_macro.md.

    .text
    .org 0

    .equ PASS_SENT,  0xFFFF0000
    .equ A0_INIT,    0x00030000
    .equ A1_BAD,     0xAAAA0000      | unmapped → AXI DECERR
    .equ DST_GOOD,   0x00040000      | mapped — handler patches A1 here

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000008    | vec 2

    | Seed source RAM:
    |   A0_INIT+0 = 0xCAFEBABE  (the "correct" payload — what should be
    |                            stored if A0 advanced by 4 and we then
    |                            re-LOAD from A0+4=A0_INIT+4 — wait,
    |                            think again.  Actually: the LOAD
    |                            executes BEFORE the postinc.  So:
    |                              ph0 LOADs from (A0)         = init+0
    |                              ph1 A0 += 4                 (A0=init+4)
    |                              ph2 STORE faults
    |                              [handler patches A1 → DST_GOOD, RTEs]
    |                              [macro re-executes — bug RTL]
    |                              ph0' LOADs from (A0)        = init+4 ← BAD
    |                              ph1' A0 += 4                (A0=init+8)
    |                              ph2' STORE TMP1 = 0xDEADBEEF
    |                              ph3' A1 += 4
    |                            silicon-correct: macro RESUMEs from
    |                            the STORE, no re-LOAD.  Stored value
    |                            = 0xCAFEBABE.
    move.l  #0xCAFEBABE, A0_INIT
    move.l  #0xDEADBEEF, A0_INIT+4

    | Pre-zero the destination so we can detect the stored value.
    move.l  #0, DST_GOOD

    moveq   #0, %d7                  | trap-entry counter

    lea     A0_INIT, %a0             | source = mapped
    lea     A1_BAD,  %a1             | dest   = unmapped (will fault)

    | Snapshot init values for handler comparison
    move.l  %a0, %d6                 | D6 = A0_init

    | The 2-byte MOVE.L mem,mem crack.  Encoding:
    |   move.l (a0)+, (a1)+   →   0x22d8
    move.l  (%a0)+, (%a1)+

    | After the second RTE the macro completes (bug or no bug).  Read
    | DST_GOOD and check.
_check:
    move.l  DST_GOOD, %d2

    | Also check A0 didn't advance more than once.
    move.l  %a0, %d0
    sub.l   %d6, %d0                 | D0 = A0_delta

    | Path A — silicon-correct (A0 += 4, dst = 0xCAFEBABE)
    cmp.l   #4, %d0
    bne     _fail_a0
    cmp.l   #0xCAFEBABE, %d2
    bne     _fail_dst

    lea     PASS_SENT, %a4
    move.l  #0xC0FFEE00, %d4
    move.l  %d4, (%a4)
_halt:
    bra     _halt

_fail_a0:
    | A0 advanced by something other than 4 — partial-macro replay
    | most likely advanced it twice (delta=8).  Report.
    lea     PASS_SENT, %a4
    move.l  #0xBAD0A000, %d4         | "BAD A0" tag (low byte holds delta)
    or.l    %d0, %d4                 | encode actual delta
    move.l  %d4, (%a4)
_hf1: bra _hf1

_fail_dst:
    | A0 advanced by 4 but dst is wrong — also a bug, different shape.
    lea     PASS_SENT, %a4
    move.l  #0xBADD5700, %d4         | "BAD DST" tag
    move.l  %d4, (%a4)
_hf2: bra _hf2

| ── Bus-error handler ────────────────────────────────────────────────
| Format-7 frame layout:
|   A7+0   SR (long, low half)
|   A7+2   PC (long)            — saved_pc.  We keep it on plain-RTE so
|                                 the macro re-executes.
|   A7+6   format/vec word (long, low half)
|   A7+8   effective address    — fault_addr (= A1)
|
| Handler logic:
|   - First entry (D7 == 0): patch A1 (live arch reg) → DST_GOOD,
|     RTE plain — macro will re-execute from its start.
|     Bug RTL: A0 already advanced once at this point → second
|     execution makes A0 = A0_init + 8 (BAD).
|   - Second entry (D7 == 1): we got here because the bug caused
|     a SECOND fault (A1 was patched to DST_GOOD which is mapped, so
|     it shouldn't fault — but if it does, that's a third bug).
|     Just skip past the macro to break out.
|
| To patch A1 mid-flight we must use the only mechanism available:
| set A1 directly in the handler.  Since the bus-error frame doesn't
| carry it, we use the side-channel: we KNOW A1 = A1_BAD here.
_handler:
    addq.l  #1, %d7
    cmp.l   #1, %d7
    beq     _first_entry

    | Second entry — break out by skipping.
    move.l  2(%a7), %d3              | D3 = saved PC
    addq.l  #2, %d3                  | skip the 2-byte MOVE.L mem,mem
    move.l  %d3, 2(%a7)
    rte

_first_entry:
    | Patch A1 → DST_GOOD so the second execution will succeed.
    lea     DST_GOOD, %a1
    rte
