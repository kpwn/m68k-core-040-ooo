| exc_partial_macro_movem_predec.s — sync exception during a MOVEM crack.
|
| Bug shape (partial-macro replay): MOVEM.L D0-D2,-(A7) cracks into:
|   ph0: MOV A7 → TMP1            (stash original An — done in case An is in mask)
|   ph1: A7 -= 12                 (predec the base)
|   ph2: STORE D2 → (A7+8)        ← if mapped, these proceed in phase order
|   ph3: STORE D1 → (A7+4)
|   ph4: STORE D0 → (A7+0)        (last)
|
| If any of ph2/ph3/ph4 STOREs faults, ph0 + ph1 have already retired —
| A7 is permanently decremented by 12.  After RTE the macro re-executes
| from start: A7 -= 12 again → A7 ends up 24 below original.
|
| THIS TEST uses a setup where the second store of MOVEM.L will hit
| an unmapped page.  We seed memory with sentinels at the expected
| addresses, capture A7, and after the handler RTEs we check whether
| A7 was decremented twice.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    | Use a low-RAM A7 so we can place A7-1 in unmapped territory by
    | choosing the boundary deliberately.  The simpler setup: aim
    | the MOVEM such that the THIRD store crosses into unmapped space.
    |
    | Strategy: A7 starts pointing INTO an unmapped page, so the FIRST
    | store of the predec sequence faults.  ph0 (MOV) and ph1 (SUB) have
    | retired by then.  Handler skips +4 (MOVEM is 4 bytes), checks A7.

    | Use VBR-relocated handler so we have a clean low memory map.
    move.l  #0x00010000, %d0
    movec   %d0, %vbr
    move.l  #_handler, 0x00010008    | vec 2

    | Seed Dn with known values
    move.l  #0xAAAA0000, %d0
    move.l  #0xBBBB0001, %d1
    move.l  #0xCCCC0002, %d2

    | The bug we want to repro: MOVEM.L predec stores the FIRST
    | register at the highest address (last byte of the predecremented
    | range).  The exception-entry stack is itself A7, so we cannot
    | use A7 as the predecrement target without inducing a double-
    | fault HALT (which the testbench treats as PASS).  Use a NON-A7
    | base register and a separate kernel stack so the exception
    | frame push goes somewhere safe.
    |
    | Setup:
    |   - A7 = clean kernel stack at 0x00020000 (mapped)
    |   - A3 = base register for MOVEM, points to 0xAAAA0000
    |          (unmapped — the FIRST predec store at A3-4=0xAA9FFFFC
    |          will fault)
    |
    | But MOVEM.L Dn,-(An) only allows An, NOT A7 — wait, A7 is allowed.
    | Use A3 specifically.

    move.l  #0x00020000, %a7         | clean kernel stack
    move.l  #0xAAAA0000, %a3
    move.l  %a3, %a4                 | A4 = A3_init for delta check

    | MOVEM.L D0-D2,-(A3)  →  0x48E3 0xE000
    movem.l %d0-%d2, -(%a3)

    | Reach here only if the handler skipped past the MOVEM and RTEd.
    | Check A3 delta vs init.
    move.l  %a3, %d3
    sub.l   %a4, %d3                 | D3 = A3 - A3_init

    | Three valid endings (any non-bug semantics → PASS):
    |   * Silicon continuation (Path B): macro resumes at the faulting
    |     STORE post-RTE, A3 decremented exactly once → delta = -12.
    |   * Atomic restart (Path A — what this CPU implements): every
    |     RTE re-executes the macro from start.  The macro never
    |     completes (A3 stays unmapped), the handler counts faults
    |     and skips past the MOVEM on the second entry.  A3 ends up
    |     UNCHANGED (every speculative decrement was rolled back via
    |     ratmap → cRAT on take_exc).  delta = 0.
    |   * Bug RTL (pre-fix): ph1's SUB retired BEFORE the faulting
    |     ph2 STORE.  RTE re-executes the macro start, ph1 fires
    |     SUB on already-decremented A3, faults again.  After
    |     handler skips, A3 has been decremented TWICE → delta = -24.
    | Bug-RTL signature is delta = -24; everything else is acceptable.
    cmp.l   #-12, %d3
    beq     _pass
    cmp.l   #0, %d3
    beq     _pass                    | Path A — atomic restart, delta=0
    cmp.l   #-24, %d3
    beq     _fail_double_decrement

    | Some other delta — record D3 in failure tag.
    lea     PASS_SENT, %a4
    move.l  #0xBADAA700, %d4
    move.l  %d4, (%a4)
_hf3: bra _hf3

_pass:
    lea     PASS_SENT, %a4
    move.l  #0xC0FFEE00, %d4
    move.l  %d4, (%a4)
_halt:
    bra     _halt

_fail_double_decrement:
    | The signature of partial-macro-replay: A7 decremented twice.
    lea     PASS_SENT, %a4
    move.l  #0xBAD0A724, %d4         | "BAD A7 -24" tag
    move.l  %d4, (%a4)
_hfd: bra _hfd

| ── Bus-error handler ────────────────────────────────────────────────
| For the bug-RTL path the handler is entered TWICE:
|   First entry  : the original fault.  A7 has been decremented once.
|                  Handler does plain RTE — macro re-executes.
|   Second entry : the same macro re-executes; ph1 fires SUB on already-
|                  decremented A7.  This time we skip past the MOVEM.
|
| Use a counter in D5.
_handler:
    addq.l  #1, %d5
    cmp.l   #1, %d5
    beq     _first_entry

    | Second entry — break out by skipping past the 4-byte MOVEM.
    move.l  2(%a7), %d6              | D6 = saved PC
    addq.l  #4, %d6                  | skip 4-byte MOVEM.L Dn,-(An)
    move.l  %d6, 2(%a7)
    rte

_first_entry:
    | Plain RTE — let the macro re-execute.  Silicon-correct: macro
    | resumes from the faulting STORE, no re-decrement.  Bug RTL:
    | A7 -= 12 fires AGAIN.
    rte
