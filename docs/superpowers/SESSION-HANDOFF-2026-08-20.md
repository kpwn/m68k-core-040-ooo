# Session handoff — 2026-08-20

## Continuation update — 2026-08-21 (read this first)

The original ledger below is historical. Debug Stages 2 through 5 have now landed;
do not resume at its old “Task 5” pointer.

### Current integrated state

- Core branch `fmax-closure-fanout` is at `443761c` (`debug: add precise Stage 5
  stop points`). The preceding introspection commits are `8795fb4`, `3bac56c`, and
  `d866d8f`.
- SoC integration branch `feat/m68k040ooo-socket-integration` is at `1581ca9` and
  pins `cpu040` to `443761c`.
- Manual halt and single-step stop only after the complete preceding macro has
  committed and recovery has flushed younger/in-flight work. Effective halt is the
  stable stop-the-world point used by every architectural/debug operation.
- Complete integer/system register dump and atomic dirty-shadow register apply are
  exposed through JTAG. Apply remains halted. This includes D0-D7, A0-A7, USP,
  MSP/SSP, ISP, SR, VBR, CACR, TC, ITT0/1, DTT0/1, URP/SRP, PC, SFC/DFC, plus
  read-only MMUSR. FP0-FP7 and FPCR/FPSR/FPIAR are **not yet exposed**.
- Three 32-entry retirement-history rings are real: last retired macro PCs (one
  entry only on the last retired uop), retired branches, and completed exceptions.
- Stage 4 provides halted D-cache push/invalidate and I-cache/predictor invalidate.
  It waits for LSU/store-queue/cache quiescence, returns BUSY/DONE/REJECTED/ERROR,
  and does not claim coherence after a failed AXI writeback. A failed push preserves
  the valid dirty line so it can be retried without losing the only current copy.
- `tools/jtag_repl.tcl` now provides `regs`, `reg-set`, `last-pcs`,
  `last-branches`, `last-exceptions`, `coherent-r`, `coherent-dump`, and
  `coherent-w`. Coherent memory is physical: JTAG-AXI transports the read/write,
  while halted cache maintenance establishes a shared DDR view. There is no direct
  debugger-injected LSU request/virtual-address path yet.
- The REPL version-gates this contract at debug epoch `0xDEB60100`; it still decodes
  legacy feature bit 19 as `icache_probe` instead of misreporting halted apply.
  GDB staged-register writes now apply while halted and exact single-step remains
  exact after a register write.
- Stage 5 provides four precise pre-effect PC breakpoint slots and a 256-vector
  halt-on-completed-exception mask. Breakpoints stop with the matching macro at
  the ROB head before any of its uops retire, flush all in-flight work, restart at
  the hit PC, and auto-arm one-shot skip so resume crosses the breakpoint.
  Exception halts materialize only after architectural exception entry completes;
  the atomic descriptor reports vector, faulting PC, fault address, and installed
  handler/live PC.
- Stage 5 is wired through the JTAG REPL, OpenOCD/Vivado bring-up TUI, and
  `m68kctl`. Core-epoch halt detection uses STATUS bit 0 and primary reason codes;
  legacy bitstream decoding remains intact. Low-level commands now write
  `BREAK_PC_CTRL` and the eight exception-mask lanes instead of the reserved
  legacy single-vector register.

### Verification evidence

- Required core gate: `make SBT=~/sbt/bin/sbt test-fast` — 310/310 tests,
  203 suites, zero failures.
- Directed D-cache DECERR test proves error reporting, dirty-line preservation, and
  a successful retry.
- Core regmap/protocol/generated/sibling checks all pass.
- SoC Stage 5 host/TUI/debug tests: 40/40 pass.
- `make lint-fpga-top CPU=m68k040` regenerates the Stage 5 socket and passes full-SoC
  Verilator lint.
- Fresh synth-only Stage 4 full-SoC Vivado gate: PASS, exit 0, output in
  `build/vivado_debug_stage4`. Fabric 100 MHz WNS is +1.317 ns. Aggregate WNS is
  -0.669 ns only in the same unrelated 333 MHz MIG clock domain seen at Stage 3.
  Utilization is 180,001 LUTs, 116,195 registers, 145 BRAM tiles, 64 URAMs, and
  34 DSPs. Checkpoint SHA-256:
  `ec01742e7a0fd158079a0f1556af121d39be83e8ab6e2d1d4b8a112e193c58a4`.
  No bitstream was generated.
- Fresh Stage 5 **full implementation + bitstream**: PASS, exit 0, output in
  `/home/qwertyoruiop/macqd700-soc-worktrees/m68k040ooo-integration/build/vivado_debug_stage5_bitstream`.
  `fpga_top.bit` SHA-256 is
  `401c11808d95b472dcd4e6bcdb35611c56cd200cf8f8ff85a48e08aa3f451cf2`;
  matching `fpga_top.ltx` SHA-256 is
  `241e8bff575a523c9d06724a65d0cfd0e7f991d045511b60313bd953e4a23085`.
  Routed setup WNS/TNS are +0.023 ns / 0; routed hold WHS/THS are +0.011 ns / 0.
  Fabric 100 MHz WNS is +0.695 ns; the tight 333 MHz MIG domain closes at
  +0.023 ns. Bitgen and its precondition DRC completed with zero errors.

### First Stage 5 hardware session — blockers found 2026-08-21

The bitstream above was loaded successfully. Vivado enumerated one JTAG-AXI core,
one real MIG core, and one VIO core. The live debug ABI is `0xDEB60100`, DDR traffic
was active, both HDMI clocks were locked, and `boot_error=0`. The new persistent
REPL is PID/PGID rooted at `3091875`, attached with `JTAG_REPL_NO_PROGRAM=1` to the
new bit/ltx. The FPGA is currently left in an effective manual halt.

Hardware immediately disproved boot correctness and exposed these concrete gaps.
The initial reset-vector diagnosis below has since been corrected by a full-SoC
reproduction; retain the original symptom only as investigation history:

1. **CORRECTED — reset is not the mismatch; instruction-word assembly is.** The Q700
   ROM bytes at offsets `+0..+3` are the initial SP (`0x420DBFF3`), bytes `+4..+7`
   are the reset PC (`0x0000002A`), and `0x2A` is the real entry point. The existing
   `ResetVectorFsm` reads those fields correctly. A full 256-bit-L2 SoC simulation
   proved the vector path and socket byte permutation correct, then reproduced the
   hardware runaway: `FetchRsp.data` held the real big-endian bytes in its documented
   byte-at-address convention, but refill predecode and FetchAlign consumed each raw
   16-bit slice directly, numerically reversing every 68k opword. Test image loaders
   had hidden the RTL bug by storing instruction words low-byte-first.
   The fix is implemented in the reserved core worktree `agent-02`: preserve raw
   I-cache data, assemble a numeric big-endian opword at both opcode consumers, and
   change all I-fetch test loaders to real architectural byte order. The mandatory
   core gate passes 310/310 tests (203 suites).
2. **Pre-fix hardware failure signature.** The last-32 exception ring was 32 identical vector-4
   entries: fault PC `0x04000029`, handler `0x007C15F1`, fault address zero. The
   last-32 retired-PC ring contained odd PCs advancing by four. D0-D7/A0-A6 were all
   zero; ISP/A7 was being consumed by repeated frames. The branch ring was empty.
3. **Halted PC apply/resume is not yet trustworthy on the old bitstream.** `reg-set PC
   0x4000002A` completes and reports effective halt preserved, but resume never reaches
   a breakpoint armed at that exact PC and immediately returns to random odd PCs.
   Do not claim register-set is hardware-validated until this is fixed/retested.
4. **Core build ID is unsourced.** The manifest correctly says `build_id=0x1581ca92`,
   but live `OFF_BUILD_ID` reads zero because generated `M68kSocketTop` has no build-ID
   parameter and constructs `DebugCtrlPlugin(buildId=0)`. The exact programmed
   bitstream is identified by its hash above, but the readback endpoint must be wired.
5. **Host feature-name drift.** The core feature map defines bit 19 as
   `arch_apply_stays_halted`, while the SoC REPL still labels it `icache_probe`.
   Therefore the failed `icache-lookup` experiment was a host capability-decoding bug,
   not proof of an outstanding I-cache request. Fix the epoch-specific feature names.
6. **Breakpoint continue/slot management needs a core-epoch audit.** The REPL's
   `continue` path still keys precise-breakpoint state from legacy `HALT_CTL` bit 5,
   which is RAZ on this core. Adding a second breakpoint while stopped on the first did
   not release the original halt. Update the host path to use the Stage 5 primary reason
   and `BP_SKIP_ONCE`/`BREAK_PC_CTRL` semantics.

### Still missing from the debug/boot objective

- Stage 6 physical load/store watchpoints and A-trap breakpoints.
- Architectural FPU register dump/set, direct cache tag/data probes, and an optional
  debugger-injected LSU transaction path (needed only if physical coherent JTAG
  memory is insufficient).
- Land/pin the instruction-word fix, fix the remaining build-ID/host feature/continue
  gaps, rebuild and reload a full-SoC bitstream, validate the hardware trace begins at
  reset PC `0x0000002A`, and perform the actual Mac OS 7.5.3 boot-to-Finder run. The
  first Stage 5 bitstream is timing-clean and highly diagnostic, but predates the
  instruction-word fix and cannot boot correctly.

### 2026-08-21 full-SoC proof of the instruction-word fix

- Synthetic ROM: SP `0x00500000`, reset PC `0x40000008`, then three `NOP`s and
  `BRA.S -2` at `0x4000000E`.
- Before the fix, the full SoC read the vector and raw I-cache data correctly but
  redirected into low zero memory and retired the bogus linear stream seen on FPGA.
- After the fix, the same generated core through the real SoC L2/ROM path retired the
  NOPs and then remained at `0x4000000E`; 64 retirements completed, no exceptions were
  observed, and the ROM-fold checker reported zero mismatches.
- `tb/tb_fpga_top_rom.cpp` in the SoC integration worktree now exposes reset-vector,
  first fetch-response, I-cache-response, and retirement-PC diagnostics and uses the
  socketized 040's real retirement pulse for `+max_insts` (the legacy top-level debug
  counter is tied off for this CPU selection).

---

Written because this session is nearing its token budget. Two repos/worktrees are
in play simultaneously; read both sections before resuming either thread.

## Standing goal

Set via `/goal` this session (Stop-hook enforced, still active unless it auto-cleared):

> turn the CPU into something we can introspect and get the SoC with V2 booting to
> Finder in 7.5.3

Two halves: **introspection** (the debug-ctrl Stage 2 plan below) and **boot to
Finder** (the SoC ROM-mirror fix + real-hardware/sim verification below). Both are
mid-flight, neither is close to done — this is a multi-session effort.

---

## Thread A: cpu040 core repo — debug-ctrl Stage 2 (introspection)

**Repo:** `/home/qwertyoruiop/m68k-core-040-ooo`, branch `fmax-closure-fanout` (this
is the repo you're reading this file from).

**Why:** `tools/jtag_repl.tcl`'s `halt-status`/`pc`/`sweep` commands all read back
zero/uninformative against a real CPU=m68k040 bitstream — not because the CPU is
hung, but because none of the halt/PC-readback RTL exists yet. Stage 1
(already shipped) only implements version/build/features/control/status/reset-count.

**Plan doc (the source of truth for this thread):**
`docs/superpowers/plans/2026-08-20-debug-ctrl-stage2-halt-resume-step.md` — 14
tasks, each with exact code/tests/commit-message text. **Read its Global
Constraints section before dispatching any further task** — it has been patched
several times this session with real corrections (see below) and is now accurate;
don't trust anything you remember about it from before, re-read the file.

**Execution method:** hand-rolled subagent-driven-development (the
`superpowers:subagent-driven-development` skill was NOT loadable this session —
plugin not registered — so tasks are dispatched manually via the `Agent` tool,
fresh implementer per task, fresh reviewer per task, following the same
discipline). If the skill becomes available in a future session, prefer it; the
manual pattern used here is a reasonable fallback either way.

### Progress ledger (commit hashes, in order, all on `fmax-closure-fanout`)

- `95045cb` — plan doc committed.
- `e25641b` — **Task 1 DONE, reviewed, Approved.** Gates `DebugCtrlPlugin` behind a
  real `enable: Boolean = true` flag (mirrors `VioProbePlugin`'s pattern).
  `enable=false` build (`GenFullCoreSynthNoDebugVerilog`/`M68kFullCoreSynthNoDebug`)
  carries zero of this plugin's CSR hardware — verified via netlist signal-count
  diff (133→0). `enable=true` default netlist is byte-identical to pre-Task-1
  modulo comment-line-shift auto-renaming.
- `6698d61` — doc fix: the plan's baseline `ExecuteLockStepSpec` claim (390/394,
  STOP/ITLB failures) was **stale project memory, not the real baseline**. Real
  baseline, re-verified twice independently since: **399/400**, exactly one known
  failure, `CMP2.W (d8,An,Xn) indexed bounds pointer` (task #257, already tracked,
  not caused by anything in this plan).
- `78581c4` — doc fix: plan cited a nonexistent object `GenFullCoreSynth`; real one
  is `GenFullCoreSynthVerilog`.
- `448ec46` — **Task 2 DONE, reviewed, Approved.** `DebugCommitService` trait +
  `RobPlugin` setup-allocated-wire skeleton (mirrors the existing
  `FrontendQuiesceService` pattern exactly). Pure plumbing, 7 inert fields.
- `429b07b` — doc fix: every verification command in the plan used the wrong FQN
  (`m68k040.ExecuteLockStepSpec` — silently resolves to "no tests to run" instead
  of erroring). Real FQN: `m68k040.lockstep.ExecuteLockStepSpec`. All 12
  occurrences fixed.
- `b627f84` — **Task 3 DONE.** Free-running macro-retire counter
  (`debugMacroCountReg`, counts macros not uops) + last-committed-PC capture
  (`debugLastPcReg`, raw `payload.pc`, **not** the `pc+2` commit-PC convention used
  elsewhere in this file — the plan now has a clarifying note about this, added
  after the implementer got tripped up by it once).
- `93942bc` — Task 3 reviewer found one real coverage gap (dual-retire `+2`
  increment case verified only via the reviewer's own unrecorded probe, not a
  committed assertion) — closed directly rather than via a new dispatch.
- `9bdc1d5` — doc fix: added the `OFF_LAST_PC` semantics clarification above.
- **`a8e8a56` — Task 4 DONE (REVISED SCOPE), implementer reported
  DONE_WITH_CONCERNS, reviewer dispatched but NOT YET REPORTED as of this
  writing.** This was the highest-risk task in the plan. Full story:
  - The plan's ORIGINAL Task 4 text derived macro-boundary detection
    (`h0IsMacroLast`) from ring occupancy: `(count<=1) || p1.first`. First
    implementer dispatch correctly investigated instead of coding blind, found
    this is **provably wrong**: `MicroOpQueue` pops at most 2 uops/cycle
    (`decode/MicroOpQueue.scala:66,75`) and `DispatchPlugin` gates 2-wide dispatch
    on ROB/IQ backpressure (`dispatch/DispatchPlugin.scala:24-31`), so a 3+-uop
    crack (e.g. a memory-destination RMW: load/op/store) can reach `count==1`
    with `h0` = a MIDDLE uop, mid-macro, before its real last uop is even
    allocated. A debug stop landing there would have silently dropped the store.
    Reported NEEDS_CONTEXT rather than guessing — correct call.
  - I independently re-verified both structural facts myself before accepting the
    finding, then rewrote the plan's Task 4 in full (and the Global Constraints
    bullet that had claimed "no new field needed") to instead thread a real
    `lastOfInstr: Bool` field through `DecodedUop`→`RenamedUop`→`RobPayload`,
    mirroring how `firstOfInstr` already flows.
  - Second implementer dispatch (opus model, given the stakes) did this
    correctly and found the plan's own "~20 sites" was an undercount (real count:
    **34 assignment statements across 24 distinct µop identities, 5 independent
    producers** — MOVEM FSM, µcode sequencer, MOVEP FSM, FMOVEM.X FSM, plain
    `assembleImpl` crack tree — not just `MicroOpAssembler.scala`). Made a
    deliberate, well-reasoned deviation from the plan's literal "set it at every
    site" instruction: derives `lastOfInstr` for the plain crack tree centrally
    from `out.count` (`for (i <- 0 until 3) { out.uops(i).lastOfInstr :=
    out.count === U(i+1, 2 bits) }`) rather than per-site flags, specifically
    because the crack-selection conditions are provably not mutually exclusive,
    and a per-site approach could emit a macro with NO true last uop under a
    condition collision — a worse failure mode (permanent stop-boundary miss)
    than what it was fixing. Also flagged a **related, NOT-fixed, unconfirmed**
    pre-existing hazard in `firstOfInstr`'s own logic (`opUop.firstOfInstr :=
    !opHasLeadingLoad`) that could theoretically leave a macro with no true FIRST
    uop either, if `bad && crackRmw` is reachable — correctly left alone as
    out-of-scope, worth a dedicated future investigation.
  - Tests: `RobPluginSpec` 27/27, `MovemDecodeSpec` 14/14, `ExecuteLockStepSpec`
    399/400 (exact expected baseline), plus 59/59 across several other decode
    specs as extra belt-and-braces, plus independent reproduction (via an
    isolated worktree at clean pre-Task-4 HEAD) that 4 failing tests in the
    broader `m68k040.decode.*` package are pre-existing/unrelated
    (`PackUnpkDecodeSpec` ×2, `FullExtDecodeSpec` ×2).
  - **RESOLVED — reviewer (opus, `a432b911654d9713f`) reported back:
    `## Verdict: Approved`.** Task 4 is DONE, no fix loop needed. Full review
    highlights (worth reading in full via that agent's output file if it's
    still retrievable, but the essentials are captured here since that file
    may not survive):
    - Independently re-traced (not taken on faith) that the central
      `out.count`-derived stamp in `assembleImpl` sits after every one of the
      39 `out.count :=` sites and every whole-bundle `out.uops(n) :=`
      assignment, so no arm can read a stale `out.count`. Hand-checked ~12
      distinct crack arms including the RMW 3-way, the `srcAuto`
      2-vs-3 case, and nested `Mux` chains for MOVE-from-SR/CCR.
    - Wrote and ran (then deleted, tree left clean) a throwaway decode-level
      spec against the real `DecodeStage`+`MicroOpQueue` DUT: confirmed
      `ADD.L D0,(A0)` produces 3 µops with `last` on the *third* (not
      `!first`, which would get the RMW case wrong), confirmed two
      single-µop instructions packed into one 4-wide push are each
      independently first+last (proves per-`assemble`-call scoping).
    - Confirmed all 5 producers (MOVEM FSM, µcode sequencer, MOVEP FSM,
      FMOVEM.X FSM, `assembleImpl`) have independently correct, traced
      drivers — not just the one central-derivation producer.
    - Confirmed decode-time fixation genuinely closes the original RMW
      counterexample structurally (the bit is fixed at alloc, before ring
      occupancy or dispatch-width variance can matter).
    - Re-ran all gates independently and got matching numbers:
      `RobPluginSpec` 27/27, `MovemDecodeSpec` 14/14, `ExecuteLockStepSpec`
      399/400 (same sole failure, task #257).
    - Independently re-derived the 4 "pre-existing, not caused by this
      change" decode-spec failures via its own throwaway worktree pinned at
      the pre-Task-4 parent commit — confirmed byte-identical failures at
      parent and at `a8e8a56` (2/8 `PackUnpkDecodeSpec`, 2/13
      `FullExtDecodeSpec`).
    - On the related `opUop.firstOfInstr := !opHasLeadingLoad` hazard the
      implementer flagged as "unreachable, out of scope": the reviewer
      independently assessed it as **"more likely reachable than not"** (not
      unreachable) but agrees it correctly should NOT have blocked this
      commit — a missing-`first` macro only delays IRQ recognition by one
      macro and is self-clearing (bounded), whereas a missing-`last` macro
      (the thing THIS commit fixes) would leave Task 5's future
      `STOP_PENDING` unable to ever fire (unbounded hang) — categorically
      worse. **Recommends filing this as its own follow-up task** — not yet
      done, should be filed before/during Stage 2's later tasks.
    - **3 non-blocking nits, none require action before Task 5:**
      (1) a comment at `DecodeStage.scala:428-430` overclaims a
      single-shared-definition guarantee for `movemRemainingAfter` that the
      FSM transition block (line 2619) doesn't actually use — cosmetic only,
      values are identical today; (2) no full-core OOC synth gate was run for
      this task specifically (correctly deferred — the plan doesn't ask for
      one here and the added bit is unread until Task 5 — but flagged as
      "worth doing before Stage 2 as a whole lands," especially since this
      branch already sits 0.069ns from the 200MHz goal); (3) an empty-mask
      MOVEM/FMOVEM.X emits zero µops (no `first`, no `last` in the ROB for
      that macro) — harmless for Task 5 but **Task 8/9 (single-step) should
      be aware of it** when defining exact step-boundary semantics.
    - **Net: Task 4 is safe to build on. Task 5 (the halt state machine) is
      now unblocked and is the correct next dispatch.**

### Remaining tasks (5-14), NOT STARTED

Task 5 (the halt state machine itself — `RUNNING`/`STOP_PENDING`/`RECOVER`/
`HALTED`) is next. **Task 4's review is now resolved (Approved) — Task 5 is
unblocked and is the correct next dispatch when resuming.** Tasks 6-14 per the
plan doc: wire manual halt/resume through `dbg_axi`, halt-after, single-step,
`OFF_HALT_REASON` + fatal-halt distinctness, `OFF_HALT_HIT_INST_*`, required
assertions, ship (`stage=2`), full regression, synth gate.

**Two follow-up items surfaced by Task 4's reviewer, not yet filed/done:**
1. File a proper task for the `opUop.firstOfInstr := !opHasLeadingLoad`
   reachability hazard (reviewer assessed likely-reachable, bounded/
   self-clearing impact, correctly out of scope for Task 4 itself).
2. Fix or soften the overclaiming comment at `DecodeStage.scala:428-430`
   (cosmetic).
Also keep Task 4's nit #3 in mind when writing Tasks 8/9 (single-step): an
empty-mask MOVEM/FMOVEM.X macro has neither a `first` nor a `last` µop in the
ROB.

**Synth gate note (Task 14):** per explicit user directive this session, the gate
criteria are asymmetric — `enable=true` (JTAG-capable debug bitstream) targets
~100MHz with real margin (not a knife-edge pass), `enable=false` (production)
targets the current ~200MHz-class FMax-campaign reference. This supersedes the
plan's own literal spec-citation of a stricter uniform +2% ceiling for the
`enable=true` build specifically. Full reasoning is already in the plan's Global
Constraints section — don't re-litigate it, just follow it.

---

## Thread B: macqd700-soc worktree — ROM-mirror fix + verification (boot to Finder)

**Repo:** `/home/qwertyoruiop/macqd700-soc-worktrees/m68k040ooo-integration`,
branch `feat/m68k040ooo-socket-integration`. **Different repo from Thread A** —
`cd` there explicitly, don't assume you're in the right place.

### What's done and solid

- **`d3b21e3`** — `boot_fsm.v` now dual-writes every streamed ROM byte to both its
  native `ROM_BASE_ADDR` (0x40000000) and the identical relative offset from
  address 0 (`MIRROR_LOW_RAM=1`), replacing a crossbar-ROM-overlay redirect
  mechanism that's structurally blind to cpu040's fetch traffic (Level A bypasses
  the crossbar entirely — see the commit message for the full root-cause chain,
  it's thorough). Also added a VRAM zero-pass. Verified via:
  - `make tb-sd-boot-mirror` (dedicated boot_fsm-only unit test, 2/2 new
    scenarios, existing `tb-sd-boot`/`tb-sd-boot-zero` unaffected).
  - Direct JTAG-AXI reads on **real hardware**: address 0 and address 0x40000000
    both independently read back `0x420dbff3`/`0x0000002a` (the real Q700 ROM's
    known first bytes) — confirmed the write lands correctly on real silicon.
  - Confirmed (by reading the actual address-decode RTL, not assumed) that no
    hidden CPU-address-vs-DDR-address translation exists on this path for
    cpu040 — `apply_cpu_overlay` only ever applied to a different legacy master
    index cpu040 doesn't use, and Level A's fetch/LSU/boot_fsm-write paths all
    share one address-indexed L2C tag/data array.

### What's NOT done

- **No new bitstream has been built/flashed with `d3b21e3` in it.** The bitstream
  currently on the real FPGA predates this fix (still has the "hung"/no-video
  symptom this fix targets). Building+flashing a fresh bitstream and confirming
  real video/boot progress on hardware is the most direct next step for the
  "boot to Finder" half of the goal, and hasn't been attempted since the fix
  landed.
- **Full-chip simulation verification is IN PROGRESS, not complete.** Long story,
  condensed:
  1. First attempt used `make tb-fpga-top-rom CPU=m68k040 SIM_L2C_ENABLE=1` — got
     very excited about 800K+ "cleanly retiring" instructions, but the user
     correctly caught that this was bogus: `tb_fpga_top_rom.cpp`'s
     `preload_rom()` backdoor-pokes ROM into the DDR model at `RAM_BYTES +
     byte_off = 0x04000000 + byte_off` (a legacy convention, NOT address 0 or
     0x40000000), and separately `` `ifdef FPGA_ROM_SIM `` in
     `rtl/soc/fpga_top_clocks.vh` (~line 385-411) hardcodes a simulation-only
     boot-bypass (`vio_boot_ctrl = 5'b00011`) that skips `boot_fsm` and
     force-releases `cpu_rst` regardless of real ROM state. So that whole run
     was the CPU fetching **uninitialized/zero memory** and free-running through
     a degenerate non-branching decode stream — not real code. Lesson recorded
     for next time: a suspiciously *perfectly linear* PC-vs-fetch-count
     relationship (no branch-driven jitter at all) is the tell.
  2. Rebuilt `Vfpga_top` without `-DFPGA_ROM_SIM` (kept `-DSIM_MODEL`) to get real
     boot_fsm gating. Compiled fine, but `preload_rom()`'s unconditional call in
     `main()` immediately aborted the program: the DDR model's array depth is
     ALSO coupled to `FPGA_ROM_SIM` (tiny 4096-beat placeholder without it, real
     ROM needs ~4.4M beats).
  3. User approved patching the harness properly rather than working around it.
     An agent was dispatched (`a13bd40413cfc6e33`) and, across a long run
     (~1.4M ms / ~3.6M ms across its full multi-report lifetime — it kept
     going and self-reported multiple times), **committed its work as
     `460a834`** ("sim: real boot_fsm-gated fpga_top harness
     (tb-fpga-top-rom-realboot)") on top of `d3b21e3`. Tree is clean — confirmed
     via `git log`/`git status` directly, don't trust a stale "uncommitted"
     characterization from earlier in this doc's edit history. Files touched:
     `Makefile`, `rtl/soc/fpga_top_boot_master.vh`, `rtl/soc/fpga_top_clocks.vh`,
     `rtl/soc/fpga_top_sd.vh`, `tb/tb_fpga_top_rom.cpp`. Contents:
     - New `FPGA_ROM_SIM_REALBOOT` ifdef path: keeps `FPGA_ROM_SIM`'s
       real-sized DDR model but sets `vio_boot_ctrl=0` instead of the bypass
       value `5'b00011`, so `boot_rom_ready` genuinely waits on boot_fsm.
       Shrinks `boot_fsm`'s reset-time zero-pass window 256MiB→1MiB under this
       ifdef only (never on the real FPGA path or default `tb-fpga-top-rom`)
       so a smoke run doesn't cost tens of minutes on the (already-covered by
       `tb-sd-boot-zero`) zero pass alone.
     - **A real finding, not just plumbing**: the agent tried speeding up the
       modelled SD/SPI clock dividers to shorten the ~1MiB streaming time
       (`rtl/soc/fpga_top_sd.vh`, realboot-only ifdef) and at the floor value
       (`FAST_HALF=HS_HALF=1`) hit a **genuine 2M+ cycle stall** — real SD
       command traffic went dead, well past any legitimate timeout. Backed off
       to `HALF=2` (the same ratio already proven for real hardware's HS mode,
       just applied earlier) and confirmed healthy again (CMD9→CMD59→CMD6→
       CMD18 firing back-to-back, matching the unmodified-divider baseline).
       Worth remembering as a real hazard if anyone else is tempted to push
       this divider further.
     - Investigated (and correctly declined) a "fast-forward N SD blocks"
       shortcut — SPI is 1-bit/edge and RTL-clocked, so skipping blocks would
       need coordinated pokes into boot_fsm's sector counter + CRC accumulator
       + AXI address counter + retry state simultaneously; the HALF=1 stall
       was itself a live demonstration of how easy a silent hang is to
       introduce right at that kind of boundary. Left as real-time streaming.
     - `+no_preload` (skip the DDR-backdoor write) and `boot_rom_ready_probe()`
       (the instant `boot_rom_ready` rises, dumps DDR content at both `0x0`
       and `0x40000000` for direct comparison against the known real ROM bytes
       `420dbff3 0000002a` — **this is the single line to grep for to get a
       definitive yes/no on the whole ROM-mirror fix**).
     - New Makefile target `tb-fpga-top-rom-realboot`
       (`SD_CARD_IMAGE=<path> FPGA_TOP_ROM_EXTRA="..."`).
  4. **A verification run is LIVE RIGHT NOW, detached from this session
     (`nohup`, will keep running after the session ends):** PID `1754946`,
     started ~20:mumble this session, confirmed still running via `ps`
     (399% CPU, healthy) as of this edit. Log:
     `.../374c5f2c-.../scratchpad/build_run5.log`. At last check: ~11.2M
     sim-time units in, `retired_sum=0`/`pc=0x0`/`cpu_rst` still correctly
     held (exactly expected — the CPU stays in reset until boot_fsm finishes
     streaming). The agent's own throughput estimate (~24k sim-time/sec) put
     full completion (`CMD18` drain done) at roughly 35-40 more minutes **from
     the point it last checked** — so check elapsed wall-clock against that
     when resuming, don't assume it's still running forever.
     **A fresh `Monitor` (task `boit1pfc5`) is armed against `build_run5.log`**
     watching for `boot_rom_ready RISE` (the definitive success signal — grep
     `-A2` after it for the DDR-dump comparison line), or `Killed`/`error:`/
     `Segmentation`/`TIMEOUT`/`max_insts reached` as failure/inconclusive
     signals, 1hr timeout. (An earlier Monitor, `basx0uxpu`, was watching a
     now-superseded `build_run3.log` from before the SD-divider fix landed —
     it was stopped; ignore any stray notifications tagged with that task id
     from before this point in the doc.) **Check this first when resuming** —
     if the monitor fired, the notification has the answer; if not, `tail`/
     `grep build_run5.log` by hand, or just re-run the reproduction command
     the agent gave:
     ```
     make tb-fpga-top-rom-realboot CPU=m68k040 \
       SD_CARD_IMAGE=<16MiB image, real files/420dbff3.rom at offset 0, zero-padded> \
       FPGA_TOP_ROM_EXTRA="+sd_trace +timeout=200000000 +max_insts=50000"
     ```
     Two earlier build attempts (before the SD-divider fix) were OOM-`Killed`
     mid-compile under heavier `-j`/`--threads` settings while other
     concurrent load was on the machine (peer session, Vivado, this session's
     own reviewer agent) — per [[machine-resource-budget]], if re-building,
     check `free -g` first and prefer lower parallelism.
  4. **The synthetic SD card image used for this**:
     `/home/qwertyoruiop/tmp/claude-1000/-home-qwertyoruiop-m68k-core-040-ooo/374c5f2c-f0cd-4208-8a9e-23690599d409/scratchpad/sdcard_rom_only.img`
     — a 16MB file with the real ROM (`files/420dbff3.rom`) at byte offset 0,
     zero-padded after. (`attach_raw` semantics: file byte 0 = SD LBA 0; card
     capacity must report ≥`CARD_LBAS_MIN`=8192 sectors≈4MB or `boot_fsm` rejects
     it as garbage — 16MB has comfortable headroom.) This scratchpad directory is
     session-scoped and may be gone in a new session — regenerate if needed:
     ```bash
     cd /home/qwertyoruiop/macqd700-soc-worktrees/m68k040ooo-integration
     dd if=/dev/zero of=/tmp/sdcard_rom_only.img bs=1M count=16
     dd if=files/420dbff3.rom of=/tmp/sdcard_rom_only.img conv=notrunc
     ```

### Immediate next steps for Thread B

1. Find the isolated worktree the harness-patch agent used (it will have its own
   path, likely under a `worktrees`-style directory this session's Agent
   tool created — check `git worktree list` from the main worktree, or search
   for recently-modified `tb_fpga_top_rom.cpp`/`Makefile` copies) and check
   whether it committed. If yes, review its diff and merge/cherry-pick it into
   `feat/m68k040ooo-socket-integration`. If it's still running or never finished,
   either resume it (if the agent ID is still valid this session) or redo the
   work directly using the investigation notes above — the DDR-sizing coupling
   and `preload_rom` gating are now well-understood, a fresh attempt should be
   faster.
2. Once a real boot_fsm-gated sim run is confirmed working (real `boot_rom_loaded`
   transition, CPU fetching real branching 68k code, byte-exact ROM content
   readable at address 0), that's the sim-side proof the ROM-mirror fix works
   end-to-end — record it and move on.
3. Build + flash a fresh bitstream incorporating `d3b21e3` to real hardware
   (reuse this session's earlier bitstream-build methodology — the
   `launch_m68k040_bitstream.sh`-style symlink-workaround wrapper is likely
   stale/unnecessary now per `docs/agent_policy.md`'s fix, but double check
   `git submodule status cpu040` first) and confirm real video/boot progress via
   the JTAG REPL (`tools/jtag_repl.tcl`, `/tmp/jtag_in`/`/tmp/jtag_out` FIFOs were
   the working convention this session) — this is the actual "does the real
   hang symptom go away" test, and it's still outstanding.
4. Note: **debug-ctrl Stage 2 (Thread A) is a prerequisite for confidently
   diagnosing step 3 on real hardware** — without it, `halt-status`/`pc` read
   back zero/uninformative and you're back to inferring liveness indirectly via
   `vio_l2c_stats` deltas, exactly the awkward position this session was in
   before starting Thread A. Worth landing at least through Task 6 (manual
   halt/resume wired end-to-end) before the next real-hardware debugging push.

---

## Medium-term (beyond the immediate next steps above)

- Finish the debug-ctrl Stage 2 plan (Tasks 5-14), land it, then decide whether
  Stage 3 (full architectural register readback — needed for real GDB-style
  debugging, not just halt/PC) is the next increment or whether boot-to-Finder
  work takes priority for a while.
- The user's broader "unified debug API with v1" request from earlier this
  session is still open and unscoped — worth a dedicated brainstorming pass once
  Stage 2 lands, since Stage 2's real register offsets (already frozen in
  `tools/debug/debug_regmap.def`) were explicitly designed to mirror the deployed
  v1 `debug_ctrl.v` contract where possible.
- Once boot_fsm/ROM-mirror is confirmed working end-to-end (sim + hardware), the
  natural next blocker for "boot to Finder" is almost certainly further up the
  boot sequence — SCSI/ADB/video peripheral wiring — not yet scoped this session.
