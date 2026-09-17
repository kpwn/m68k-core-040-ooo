| cpush_split_fault_ring_leak.s -- LsEuPlugin aligned-ring accounting
| under a SPLIT-LOAD BUS FAULT, interleaved with cache maintenance.
|
| THE SUSPECTED DEFECT (LsEuPlugin.scala, `switch(alignedEnq ##
| alignedEnqSplit ## alignedRspFire)`):
|
|     is(B"100") { alignedCount := alignedCount + 1 }
|     is(B"001") { alignedCount := Mux(abortsPair, count-2, count-1) }
|     is(B"010") { alignedCount := alignedCount + 2 }
|     is(B"011") { alignedCount := Mux(abortsPair, count,   count+1) }
|                                            <-- NO B"101" CASE
|
| B"101" is "an ordinary push AND a response pop in the same cycle".
| For an ordinary pop that is +1-1 = 0, and "no case" (hold the
| register) is accidentally correct.  But when the pop is an
| `alignedRspAbortsPair` -- slot A of a split pair took a real bus
| fault, so BOTH slot A and slot B retire together -- the correct
| update is +1-2 = -1, and the register instead holds.  `alignedCount`
| is then permanently one higher than the true occupancy.
|
| `alignedDepth` is 4, and `alignedCanEnq = !bkBusy && (!alignedFull ||
| alignedRspFire)` with `alignedFull = alignedCount === 4`.  So FOUR
| such coincidences leave `alignedCount` pinned at 4 with the ring
| actually EMPTY: `alignedRspFire` can never fire again (no valid entry
| to respond), `alignedCanEnq` is dead, no further load can ever be
| admitted, and the core stops retiring -- silently, with every
| architectural register intact.
|
| That shape is why this test exists: the board's dominant failure
| DECAYS to a full freeze (19,900 exc/s -> 0/s over 180 s) with A0..A7
| all valid and no corruption anywhere.  A leaked LS ring slot produces
| exactly that signature, and it had never been tested under cache
| maintenance.
|
| HOW THE COINCIDENCE IS PROVOKED:
|   * `move.l (%a0),%d0` with A0 = 0xDEAD000E.  The longword spans
|     0xDEAD000E..0xDEAD0011, i.e. it CROSSES the 16-byte line at
|     0xDEAD0010, so the LS EU pushes a two-slot split pair.  The
|     harness's AXI model decodes only top nibble 0/4/5/6 and
|     0xFFFFxxxx, so slot A takes a real DECERR -> `loadRsp.fault`,
|     with `alignedRspIsSplitA` true -> `alignedRspAbortsPair`.
|   * Ordinary loads are issued densely on BOTH sides of the faulting
|     one, so an ordinary `alignedEnq` is live in the same cycle the
|     abort response fires (that coincidence is what selects B"101").
|   * A `cpushl bc,(a1)` after each probe drains and re-arms the whole
|     LS/D-cache path, which is the posture the defect was never tried
|     under.
|   * 64 iterations, so four coincidences are likely even though each
|     one individually depends on AXI response timing.
|
| A leak shows up as the harness HANG verdict (the core stops retiring
| and the sentinel is never written) -- the direct analogue of the
| board freeze.  A wrong handler count shows up as 0xDEAD0F01.
|
| POSTURE: installs its own TTRs + CACR; always runs cached/copyback
| for the low half, inhibited for the probed high half.
|
| PASS: 0xC0FFEE00
| FAIL: 0xDEAD0F01  bus-error handler count != iteration count
|       0xDEAD0F03  CACR readback wrong (posture did not take)
|       0xDEAD0F05  unexpected exception vector
| HANG: the LS pipe stopped admitting loads -- the ring leak.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ VBR_BASE,  0x00010000
    .equ ARENA,     0x00030000
    .equ SPLITFLT,  0xDEAD000E          | crosses the line at 0xDEAD0010
    .equ NITER,     64

_start:
    lea     0x00020000, %a7

    lea     VBR_BASE, %a1
    move.l  #_panic, %d0
    move.l  %d0, 12(%a1)                | address error
    move.l  %d0, 16(%a1)                | illegal
    move.l  %d0, 20(%a1)                | zero divide
    move.l  %d0, 32(%a1)                | privilege violation
    move.l  %d0, 36(%a1)                | trace
    move.l  %d0, 40(%a1)                | A-line
    move.l  %d0, 44(%a1)                | F-line
    move.l  #_buserr, 8(%a1)            | vec 2 (bus error) @ VBR+0x08
    move.l  #VBR_BASE, %d0
    movec   %d0, %vbr

    move.l  #0x007FE020, %d0            | low 2 GiB cacheable copyback, I + D
    movec   %d0, %itt0
    movec   %d0, %dtt0
    moveq   #0, %d0
    movec   %d0, %itt1
    move.l  #0x807FE060, %d0            | high 2 GiB inhibited (sentinel + probes)
    movec   %d0, %dtt1
    move.l  #0x00008000, %d0            | TC.E = 1, 4K pages
    movec   %d0, %tc

    move.l  #0x80008000, %d7            | CACR = DE | IE
    movec   %d7, %cacr
    movec   %cacr, %d6
    cmp.l   %d7, %d6
    bne     _fail_cacr

    clr.l   %d7                         | bus-error handler counter
    clr.l   %d6                         | iteration counter

    | Working set for the ordinary loads: 16 distinct lines.
    lea     ARENA, %a2
    moveq   #15, %d3
_seed:
    move.l  #0x1234, 0(%a2)
    move.l  #0x5678, 8(%a2)
    lea     16(%a2), %a2
    dbra    %d3, _seed

    lea     ARENA, %a1                  | maintenance walk pointer
    move.w  #NITER-1, %d3

_iter:
    | ── dense ordinary loads BEFORE the fault ───────────────────────
    lea     ARENA, %a2
    move.l   0(%a2), %d0
    move.l   8(%a2), %d1
    move.l  16(%a2), %d0
    move.l  24(%a2), %d1
    move.l  32(%a2), %d0
    move.l  40(%a2), %d1

    | ── the split load whose slot A bus-faults ──────────────────────
    lea     SPLITFLT, %a0
    move.l  (%a0), %d0                  | 2-byte faulting MOVE

    | ── dense ordinary loads immediately AFTER, so an ordinary push is
    |    live in the cycle the abort response fires ───────────────────
    move.l  48(%a2), %d1
    move.l  56(%a2), %d0
    move.l  64(%a2), %d1
    move.l  72(%a2), %d0
    move.l  80(%a2), %d1
    move.l  88(%a2), %d0

    | ── cache maintenance in the same loop body ─────────────────────
    nop
    cpushl  %bc,(%a1)
    lea     16(%a1), %a1
    cmpa.l  #ARENA+256, %a1
    bne     _no_wrap
    lea     ARENA, %a1
_no_wrap:

    addq.l  #1, %d6
    dbra    %d3, _iter

    cmp.l   %d6, %d7
    bne     _fail_count
    cmpi.l  #NITER, %d7
    bne     _fail_count

    lea     PASS_SENT, %a4
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a4)
_halt:
    bra     _halt

| ── vec 2: step over the 2-byte faulting MOVE and resume ────────────
_buserr:
    move.l  2(%a7), %d0
    addq.l  #2, %d0
    move.l  %d0, 2(%a7)
    addq.l  #1, %d7
    rte

_fail_count:
    move.l  #0xDEAD0F01, %d0
    bra     _write_fail
_fail_cacr:
    move.l  #0xDEAD0F03, %d0
    bra     _write_fail
_panic:
    move.l  #0xDEAD0F05, %d0
_write_fail:
    lea     PASS_SENT, %a4
    move.l  %d0, (%a4)
_halt_fail:
    bra     _halt_fail
