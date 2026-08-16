# FPU/FPSP ported-test-corpus triage (Task 14)

Run after Tasks 1-13 landed. Corpus re-derived live (Step 1) rather than trusting the design
spec's "35" or the hardware design doc's "48" figures — see Task 14 for why both are stale.

**Real corpus count (re-derived live, 2026-08-16): 46 files.** Matches the plan-writing-time
figure quoted as "confirmed during plan-writing research, 2026-08-15" in the task brief, but
that match was *verified independently by re-running the command*, not assumed — the corpus
has not grown or shrunk since. Both older figures are confirmed stale: the design spec's
Decision 8 "35" and the hardware design doc's "48" (which folds in a broader glob against the
sibling m68k-ooo repo, not this repo's actual `fpu_`/`fpsp_`-prefixed file count).

Command used:
```
ls src/test/resources/m68kooo-ported-tests/asm | grep -iE '^(fpu|fpsp)_.*\.s$' | sort > \
  docs/superpowers/sdd/fpu-corpus-manifest.txt
wc -l docs/superpowers/sdd/fpu-corpus-manifest.txt   # -> 46
```

Test run: `sbt "testOnly m68k040.fuzz.PortedM68kOooSpec -- -z fpu_"` (44 tests; `fpu_` is a
substring prefix of the whole non-`fpsp_` set) + `-z fpsp_` (5 tests: the 2 genuine
`fpsp_`-prefixed files plus 3 `fpu_*fpsp_*` names that also contain the substring `fpsp_` —
deterministic overlap, both runs agree on the 3 shared verdicts). Union of both runs = 46
unique tests, matching the manifest exactly.

## Legend
- **(a)** now passing / genuine acceptance target still failing, in-scope per the design docs
- **(b)** still failing, explicitly out-of-scope per the design spec — cited
- **(c)** still failing, genuine unexpected bug — needs a dedicated future fix task

## Results

| Test | Result | Bucket | Citation / root cause |
|---|---|---|---|
| fpsp_dec2bin_sp_integrity | FAIL (`0xBAD0000B`) | (a) | Stage 0 of this test is `FMOVEM.X FP0,-(SP)` — the FP-data-register-list `FMOVEM` form (ext opclass `110`/`111`). Confirmed unimplemented: `OperationDecoder.scala:1183` names it "unowned"; `MicroOpAssembler.scala`'s `fpEmit` (1592-1595) has no arm for it. `FMOVEM` is named in Global Constraints' hardware-native op list — genuine target, needs a dedicated decode+microcode task (none of Tasks 1-13 covered the data-register-list form; Task 9b only covered the FPCR/FPSR/FPIAR *control*-register list). |
| fpsp_packed_kernel_e2e | FAIL (`0xBAD00033`) | **(c)** | **CORRECTED — sentinel was mis-decoded; this is not a vector/UNFL code at all.** `0xBAD00033` is emitted by the test's own `_v11_second` fail site (`fpsp_packed_kernel_e2e.s:140-147`), reached when the F-line vector-11 handler (`_v11_gate`, line 128) is entered a **second** time — the file's own comment: *"A second entry is a re-trap loop — the pre-frame-population failure mode (kernel found a zero UNIMP frame, emulated nothing, RTE'd without progress). Hard FAIL."* So the real embedded Q700 FPSP kernel re-traps on its own re-entry instead of completing the packed-decimal `FMOVE.P` and RTEing once, as Decision 8's acceptance target requires. Likely root cause (already documented elsewhere in the plan, not re-derived here): `docs/superpowers/plans/2026-08-15-fpu-fpsp-implementation-plan.md:29-37` records that Task 10's `fpuSoftwareComplete`/`fpuCmdWord` FSAVE-frame trigger is deliberately narrower than Task 6's `faultUsesNextPc` trap gate — **register-to-register form ONLY** — so a memory-source `FMOVE.P` (packed decimal) trap still traps correctly but does not get a populated unimplemented-instruction FSAVE frame; the kernel finds a zero/unpopulated frame and has nothing to work with, hence the re-trap loop. Genuine, real finding — correctly a bucket-(c) unexpected bug once the sentinel is decoded correctly (not the vector-49/UNFL misreading in the prior draft of this row). |
| fpu_divbyzero_exception | FAIL (`0xDEAD0F0A`) | **(c)** | See "Headline findings" below — FPCR exception-enable bits never reach the fault-escalation gate (`DivEuPlugin.scala:182,219-221,855`, `fpExcEnableIn` stuck at its `allowOverride` default, never driven by any other file). FDIV-by-zero silently completes instead of trapping. Independently, the test's own PASS condition targets vector 49, which Decision 7 explicitly flags as **m68k-ooo's confirmed-wrong vector for DZ** ("do not port this mistake" — real vector is 50) — so even a fully-correct future implementation could not satisfy this exact test as literally written; its expected vector needs updating alongside any future fix. |
| fpu_dyadic_leak_stress | PASS | (a) | — |
| fpu_ea_pcrel_disp_base | FAIL (`0xDEAD0F01`) | (a) | `(d16,PC)`/`(d8,PC,Xn)` memory-source EA for F-line arithmetic ops still traps via vector 11 before reaching the disp-base-arithmetic assertions this file targets. Memory-source forms are named in-scope ("Task 6b" per plan); PC-relative addressing within that band is a genuine still-open target, needs diagnosis of `ucBegin`'s EA-mode acceptance for mode 7/reg 2-3. |
| fpu_ea_src_matrix | PASS | (a) | — |
| fpu_faddb_dbf_loop_repro | PASS | (a) | — |
| fpu_faddb_post_fline_entry_repro | PASS | (a) | — |
| fpu_faddb_tight_bypass_repro | PASS | (a) | — |
| fpu_fadd_exact_single | FAIL (`0xDEAD0F01`) | (a) | Arithmetic (FADD/FSUB/FMUL/FDIV register-register) all execute; the failure is the file's own *verification* step, `.short 0xF210,0x6500` = `FMOVE.S FP2,(A0)` — the FP-register-to-memory **store direction** of FMOVE (ext opclass `011`). See "Headline findings" below: this whole direction is unimplemented. |
| fpu_fbcc_branch | FAIL (`0xDEAD0F01`) | (a) | `FBcc.W` (opword `0xF280`\|cond) is not decoded — `OperationDecoder.scala:1241` comment confirms it "still F-line vec 11". FBcc is explicitly named in the brief as in-scope ("NOT in Decision 2's deferred list — only FScc/FDBcc/FTRAPcc are") — genuine target, no task in the 18-task plan currently owns it. |
| fpu_fcmp_fpcc_sign_of_zero | FAIL (`0xDEADFC22`) | **(c)** | See "Headline findings" below — same FPCR-rounding-mode-never-wired root cause as `fpu_divbyzero_exception`. Case 1 (round-to-nearest) passes; case 2 (same compare under round-to-minus-infinity) fails because the FPU datapath never sees the FPCR write that selects RM. |
| fpu_fdiv_flush_survive | FAIL (`0xDEAD0F01`) | (a) | Same FMOVE-store-direction gap as `fpu_fadd_exact_single` — verification step is `.short 0xF210,0x6480` (`FMOVE.S FP1,(A0)`). |
| fpu_fint_basic | FAIL (`0xDEAD0F01`) | (a) | Same FMOVE-store-direction gap — verification step is `FMOVE.S FPn,(A0)`. |
| fpu_fintrz_basic | FAIL (`0xDEAD0F01`) | (a) | Same FMOVE-store-direction gap — verification step is `FMOVE.S FPn,(A0)`. |
| fpu_fmove_dn_fpn_roundtrip | FAIL (`0xDEAD0F01`) | (a) | Directly exercises `FMOVE.S FPn,(An)` (store direction) as its own subject under test, not just a verify step — same root-cause gap. |
| fpu_fmove_fpn_mem_nonzero_reg | FAIL (`0xDEAD0F01`) | (a) | Directly exercises `FMOVE.S FPn,(An)` for `FPn != FP0` — same store-direction gap. |
| fpu_fmove_fp_to_ea_matrix | FAIL (`0xDEAD0F01`) | (a) | **Not the narrow bucket-(b) case the design spec predicted.** The file's very first `FPn->EA` line (`fmove.l %fp0,%d1`, a plain register-direct destination — no memory, no packed decimal) already traps, before ever reaching the predicted `fmove.p %fp0,ABSL:l{#0}` packed-decimal line. See "Headline findings" below. |
| fpu_fmove_imm_fpcr_fpsr_no_fline | FAIL (`0xDEAD0F01`) | (a) | `FMOVE.L #imm,FPCR/FPSR` (immediate source into a control register). `MicroOpAssembler.scala:1573-1582`'s own comment documents this as a **deliberate, evidence-based scope narrowing** (not an oversight): the immediate value and the 3-bit register-select mask cannot both fit through the one existing `sysRc` ROB side-channel, and the comment explicitly defers it to "Task 9b['s]... FMOVEM-control work, which needs a genuine multi-field side-channel regardless." Confirmed still absent from `fpCtrlEmit`'s conditions (only register-direct `<ea>` — mode 0/1 — is accepted). Genuine, well-understood, still-open target. |
| fpu_fmovem_ctrl_ea_matrix | FAIL (`0xDEAD2001`) | (a) | **CORRECTED — not a Task 9b bug; the vendored test's own expectation is wrong for mode `010` (An)-direct.** The failing check (`_fail_20_01`, `fpu_fmovem_ctrl_ea_matrix.s:84-89`) expects `FMOVEM.L FPCR/FPSR/FPIAR,(A0)` to leave FPCR (the first-processed register) at `(A0)`. The test's own header comment (lines 21-27) explains why that expectation is itself derived from a model artifact: *"(An) direct — mode=010: Musashi's `WRITE_EA_32`/`READ_EA_32` case 2 always computes `ea = REG_A[reg]` with NO increment and NO per-register offset — every present register in the list targets the identical address. Store: only the LAST-processed present register (FPIAR...) survives in memory."* Real 68040 hardware has no such artifact: `Microcode.scala`'s `fpCtrlStoreBaseGroup` (`Microcode.scala:1949-1954`, used for the non-auto-increment `(An)` EA) issues one `MStore` per register at ascending `+0/+4/+8` offsets from the same base — exactly the architectural image M68000PRM p.5-91 requires (Divergence Register D9) — so this core correctly stores FPCR/FPSR/FPIAR at three distinct addresses, not one clobbered address. The test is non-conformant; this core's RTL is correct. (The same reasoning applies to the `(An)+`/`(xxx).W`/`(xxx).L` checks later in the file, which the test itself already gets right per Musashi's *forward*-packing WRITE_EA_32 cases 3/7 and pass here.) |
| fpu_fmovem_ctrl_predec | FAIL (`0xDEAD1003`) | (a) | **CORRECTED — not a Task 9b bug; the vendored test's own expectation encodes Musashi's confirmed-wrong `-(An)` register order.** The core correctly stores FPCR at the lowest address (then FPSR, then FPIAR at the highest) for `-(An)`, per M68000 Family PRM p.5-91: *"the floating-point control register is moved first... with successive registers located up through higher addresses"* — implemented as `Microcode.scala`'s `fpCtrlStorePreGroup` (`Microcode.scala:1967-1978`, explicitly commented `← D9 verbatim`), and asserted end-to-end by `ExecuteLockStepSpec.scala`'s `"FMOVEM.L control-register list round-trips -(A0)/(A0)+ in FPCR/FPSR/FPIAR order"` test (`ExecuteLockStepSpec.scala:4117-4219`, the D9 order assertions at lines 4196-4206). The vendored test (`fpu_fmovem_ctrl_predec.s:20-32`) instead derives its expectation from Musashi's `fmove_fpcr()`, whose `WRITE_EA_32`/`READ_EA_32` case 4 re-decrements `An` by 4 before *each individual* register access — Divergence Register **D9** (design spec `docs/superpowers/specs/2026-08-14-fpu-fpsp-design.md:432-486`) documents this as Musashi's confirmed divergence from the manual ("Musashi is wrong here; do NOT implement the core to match it") and **D9a** (spec lines 487-499) further corrects a related, now-superseded brief-era claim about a "reversed processing order" mechanism. `task-9b-report-v2.md` independently confirms the same fact at §5.2 (line 354): *"Musashi's `fmove_fpcr` is confirmed wrong for `-(An)` with popcount ≥ 2."* The test is non-conformant; this core's RTL is correct. |
| fpu_fmovem_ctrl_reg | PASS | (a) | The `(d16,An)` control-list form (Task 9b's baseline) is correct. |
| fpu_fmove_mem_ea_fpcr_fpsr_no_fline | PASS | (a) | — |
| fpu_fmovem_multi | FAIL (`0xDEAD0F01`) | (a) | First check fails via vec-11 — FMOVEM data-register-list gap (same as `fpsp_dec2bin_sp_integrity`, see above). |
| fpu_fmovem_x_an_dynlist_fline | FAIL (`0xDEAD1B01`) | (a) | "static (An) form trapped F-line (the mode-010 gap is back)" — FMOVEM data-register-list gap. |
| fpu_fmovem_x_an_indirect | FAIL (`0xDEAD1AF1`) | (a) | "vec-11 F-line: the decode gap is back" — FMOVEM data-register-list gap. |
| fpu_fmovem_x_fpsp_epilogue | FAIL (`0xDEAD1201`) | (a) | vec-11 on the first `FMOVEM.X` — same data-register-list gap. |
| fpu_fmovem_x_idx_pcdi_data | FAIL (`0xDEAD1301`) | (a) | vec-11 — same data-register-list gap. |
| fpu_fmovem_x_pcdi | FAIL (`0xDEAD19F1`) | (a) | "a POSITIVE stage trapped F-line: the decode gap is back" — same data-register-list gap. |
| fpu_fmovem_x_roundtrip | FAIL (`0xDEAD1101`) | (a) | vec-11 on the first stage — same data-register-list gap. |
| fpu_fmove_x_fp0_d16_a6_repro | FAIL (`0xDEAD0F01`) | (a) | `fmovex %fp0,%a6@(-204)` — `FMOVE.X FPn,d16(An)`, the store direction with a displacement EA. Same root-cause gap as `fpu_fadd_exact_single` et al. |
| fpu_fnop_no_trap | FAIL (`0xDEAD0F01`) | (a) | FNOP = `FBF.W` (FBcc with cond=0) — same FBcc decode gap as `fpu_fbcc_branch`. |
| fpu_fp_rat_leak_regression | PASS | (a) | — |
| fpu_fpsp_entry_smoke | FAIL (`0xDEAD0F31`) | (a) | "FMOVEM.X / FMOVEM.L (control list) trap as F-line" at the stage the test uses `FMOVEM.X FP0-FP3,-(SP)` — FMOVEM data-register-list gap. Related to Decision 8's self-recursion-protocol acceptance surface but not itself one of the two explicitly-named tests. |
| fpu_fpsp_selfrecursion_repro | PASS | (a) | One of Decision 8's two explicitly-named acceptance targets — **passes**. |
| fpu_fpsr_fpcc_after_arith | PASS | (a) | — |
| fpu_fpsr_fpcc_readback | PASS | (a) | — |
| fpu_fsave_frestore_idle_roundtrip | FAIL (`0xDEAD0F07`) | (a) | **CORRECTED — stale citation removed.** (Do not cite "Decision 7 says there is no 52-byte frame" — Task 11's own later, already-landed fix, commit `ee91d51` ("fix(fpu): FSAVE unimplemented-instruction frame is 52 bytes (mask rev B)"), implements the real MC68040 UM 26-word/52-byte unimplemented-instruction frame with header `0x41300000`, superseding that spec claim.) Real root cause, per `task-11-report.md:794-798`: this test programs FPCR/FPSR then immediately FSAVEs — but **no floating-point instruction has ever executed**, so the correct frame is the 4-byte **NULL** frame (MC68040 UM: *"A null state frame is saved if no floating-point instructions have been executed since the last hardware reset or FRESTORE of a null state frame"*; independently cross-checked against MAME's `m68040_do_fsave`/`m_fpu_just_reset` logic, same report line 401). A real NULL-frame FSAVE/FRESTORE does not save or restore FPCR/FPSR at all — `0xDEAD0F07` ("FPCR mismatch after FRESTORE") fires because the test instead depends on m68k-ooo's non-conformant `SYS_FSAVE_STASH` FPCR/FPSR side-channel and its always-IDLE frame assumption, matching m68k-ooo's own "always UNIMP/IDLE" behavior rather than real hardware. The test is non-conformant; this core's RTL is correct. |
| fpu_fsave_idle_format_byte | FAIL (`0xDEAD1801`) | (a) | **CORRECTED — stale citation removed.** (Do not cite "Decision 7 says $40 not $41" — Task 11's `ee91d51` landing supersedes that spec text; the real frame header is `0x41300000`/version `$41` for an actual IDLE/UNIMP frame.) Real root cause, per `task-11-report.md:396-404` (first-pass finding) and `:792-793` (post-52-byte confirmation): the test does `lea`, then `FSAVE -(A7)` immediately — **no FP instruction has ever run** — so a real 68040 (and this core) correctly emits the **NULL** frame with version `$00`, not `$41`. Quoting the report directly: *"no floating-point instruction has executed at that point, so a real 68040 emits the NULL frame with version $00... this core is right and the test is non-conformant... the brief's 'expected PASS' assumed FSAVE emits IDLE unconditionally, which is exactly the m68k-ooo behaviour the task was told not to chase."* The test is non-conformant; this core's RTL is correct. |
| fpu_fsave_null_frame | FAIL (`0xDEAD0F41`) | (a) | **CORRECTED — stale citation removed.** (Do not cite "Decision 7 says there is no 52-byte frame" — superseded by Task 11's landed `ee91d51` fix, which does implement a real 52-byte unimplemented-instruction frame with header `0x41300000`/version `$41`.) Real root cause, per `task-11-report.md:780-791`: despite its name, this test's own program does `lea …,%a7` then FSAVE with **no floating-point instruction executed at any point**, and hardcodes the expectation that FSAVE unconditionally emits the 52-byte UNIMP frame (`cmp.l #0x41300000,%d0`). A real 68040 — and this core — correctly emits the 4-byte **NULL** frame (header `0x00000000`) in this no-prior-FP-op state (MC68040 UM NULL-frame rule, MAME `m_fpu_just_reset` agreement, same as the two rows above). The test matches m68k-ooo's own non-conformant "always UNIMP" behavior, not real 68040 semantics. The test is non-conformant; this core's RTL is correct. |
| fpu_fsqrt_basic | FAIL (`0xDEAD0F01`) | (a) | Same FMOVE-store-direction gap — verify step is `FMOVE.S FP1,(A0)`. |
| fpu_fsqrt_flush_survive | FAIL (`0xDEAD0F01`) | (a) | Same FMOVE-store-direction gap. |
| fpu_fsqrt_neg | FAIL (`0xDEAD0F01`) | (a) | Same FMOVE-store-direction gap. |
| fpu_fsqrt_zero | FAIL (`0xDEAD0F01`) | (a) | Same FMOVE-store-direction gap. |
| fpu_reg_rr_smoke | PASS | (a) | — |
| fpu_x2s_stash_back_to_back | **HANG** (200000 cycles) | (a) | Same FMOVE-store-direction gap (two back-to-back `FMOVE.S FPn,(An)`), but this test installs **no vector-11 handler at all** (it assumes the store direction just works, per its own post-fix-on-m68k-ooo framing), so the undecoded trap has nowhere sane to land and the core wanders instead of writing a clean FAIL sentinel — manifests as a HANG rather than a `PortedFail`. Same root cause, different failure signature; flagged separately since a HANG is a qualitatively different signal than a sentinel mismatch. |

## Summary

**Real corpus: 46 files** (2 `fpsp_*`, 44 `fpu_*`) — matches the plan-writing-time figure,
confirmed by live re-run rather than trusted from the doc.

**Totals: 12 PASS / 34 FAIL** (1 of the 34 is a HANG, not a `PortedFail` sentinel mismatch;
these totals are unchanged by the post-review corrections below — only bucket assignments
moved, not PASS/FAIL verdicts).
- **Bucket (a): 31** (revised from an original count of 27 — see "Fix" note below) — genuine
  in-scope targets still failing (plus the 12 passes, for 43 bucket-(a) rows total), each with
  an identified, well-understood root cause.
- **Bucket (b): 0.** The design spec's own pre-run prediction — "the true bucket-(b) surface
  in this corpus is narrow, likely limited to at most one line inside
  `fpu_fmove_fp_to_ea_matrix.s`" (the `fmove.p %fp0,ABSL:l{#0}` packed-decimal-destination
  sub-case) — **could not be observed as its own failure**, because a broader, more
  fundamental bucket-(a) gap (see Headline Finding 1 below) traps on that file's very *first*
  `FPn->EA` instruction, long before execution ever reaches the predicted packed-decimal line.
  The predicted narrow bucket-(b) surface is real in principle but currently masked by a
  bigger gap; it cannot be confirmed to actually exist as literal out-of-scope behavior until
  the store-direction gap below is closed.
- **Bucket (c): 3** (revised from an original count of 7 — see "Fix" note below). Genuine,
  unexpected findings not covered by (a) or (b). Full characterization below (this is Task 14
  working correctly, not a task failure).

**Revision note (post-review correction):** the original pass over this corpus put 7 tests in
bucket (c). An independent review found that 5 of those 7 were misdiagnosed — 2 falsely
accused already-approved Task 9b work of a bug that doesn't exist (`fpu_fmovem_ctrl_predec`,
`fpu_fmovem_ctrl_ea_matrix` — the vendored tests encode Musashi's own confirmed-wrong behavior,
not an RTL bug), and 3 cited design-spec text that Task 11's own later, already-landed fix
(`ee91d51`) had already superseded (`fpu_fsave_null_frame`, `fpu_fsave_idle_format_byte`,
`fpu_fsave_frestore_idle_roundtrip` — all three actually fail because no FP instruction ever
ran before the FSAVE, so the correct frame is NULL, not IDLE/UNIMP). All 5 moved to bucket (a).
Separately, `fpsp_packed_kernel_e2e`'s sentinel was mis-decoded in the original pass (read as
vector `0x33`/UNFL; it is actually a re-trap-loop marker from the test's own `_v11_second` fail
site) and has moved from (a) to (c) now that its real, genuine-bug diagnosis is understood
(Task 10's FSAVE-frame trigger not covering memory-source FP traps). Net: bucket (c) is now
`fpu_divbyzero_exception`, `fpu_fcmp_fpcc_sign_of_zero` (both untouched, both real, both
still explained by Headline Finding 2 below), plus `fpsp_packed_kernel_e2e` (re-diagnosed) = 3.

### Headline findings (the two structurally dominant root causes behind most bucket-(a) FAILs)

These aren't new buckets — every affected row above is still classified per the brief's
literal (a)/(b)/(c) rules — but they are the two single facts that explain the *large majority*
of the bucket-(a) failures, and are worth surfacing prominently rather than leaving buried
across ~20 near-identical table rows:

1. **`FMOVE FPn,<ea>` (the entire register-to-EA "store" direction of FMOVE, extension-word
   opclass `011`) has zero decode coverage, including the simplest register-direct case
   (`fmove.l %fp0,%d1`, no memory at all).** Confirmed at `MicroOpAssembler.scala:1533-1536`
   (comment: *"FMOVE-to-`<ea>` remains unowned by any task in this plan"*; `fpEmit`'s
   conditions at `MicroOpAssembler.scala:1592-1595` test opclass `000`/`010` and the
   FPCR/FPSR/FPIAR opclass `100`/`101` band, but never `011`) and `OperationDecoder.scala:1181-
   1183` (comment: `` `FMOVE FPn,<mem>` (011, store, NOT this task) `` — real memory-mode forms
   route through the µcode ROM's placeholder and are deliberately rejected back to vector-11 by
   `ucBegin`; register-direct forms never even reach that gate and fall straight to the
   vector-11 default). **This is a previously-flagged, deliberately-unowned scope item, not
   something that escaped review.** The plan document's own "Honest scope qualification"
   section already says so in as many words: `docs/superpowers/plans/2026-08-15-fpu-fpsp-
   implementation-plan.md:22-27` — *"`FMOVE FPn,<ea>` (the store direction, opclass `011`)...
   [is] unowned by any task in this plan."* `task-6-brief.md`'s own scope table records the
   same decision, per-encoding, at line 67: *"`FMOVE FPn,<ea>` (store direction) | `011` | any
   | No — remains an unowned open gap; explicitly out of scope for both this task and Task 6b."*
   Cross-checking the 18-task plan's own task list confirms **no task ever claims this
   direction**: Task 6b's title and scope are explicitly the *load* direction
   (`F<op> <mem>,FPn`); Task 9/9b own only the FPCR/FPSR/FPIAR control-register band, a
   different opclass. This single gap directly causes **13** of the bucket-(a) FAILs (recounted
   directly from this doc's own table rows, correcting an earlier miscount of 14): the five
   tests whose *own subject* is the store direction (`fpu_fmove_fp_to_ea_matrix`,
   `fpu_fmove_dn_fpn_roundtrip`, `fpu_fmove_fpn_mem_nonzero_reg`, `fpu_fmove_x_fp0_d16_a6_repro`,
   `fpu_x2s_stash_back_to_back`) plus eight tests that merely *use* `FMOVE.S FPn,(An)` as their
   own result-verification idiom (`fpu_fadd_exact_single`, `fpu_fdiv_flush_survive`,
   `fpu_fint_basic`, `fpu_fintrz_basic`, `fpu_fsqrt_basic`, `fpu_fsqrt_flush_survive`,
   `fpu_fsqrt_neg`, `fpu_fsqrt_zero`) — the latter group would very plausibly *pass* if the
   store direction existed, since their actual arithmetic is never reached as the point of
   failure. This falsifies the design spec's own pre-run bucket-(b) prediction (see above) and
   is still by far the single highest-value close for whoever picks up the next FPU task, even
   though it is a known, already-scoped-out gap rather than a fresh discovery.

2. **FPCR's rounding-mode field and exception-enable byte are never wired from
   `FpuControlPlugin`'s live FPCR into the FPU execution datapath at all.**
   `DivEuPlugin.scala:176-221` declares `fpRmodeIn`/`fpExcEnableIn` as `allowOverride` input
   seams with a comment that reads, verbatim: *"THIS IS THAT TASK'S INTEGRATION POINT: drive
   it from FpuControlPlugin's live FPCR and nothing else here changes"* — but a repo-wide
   search confirms **no other file in `src/main/scala/` ever assigns either var**; they sit
   permanently at their elaboration defaults (`rmode = 00` round-to-nearest,
   exception-enable = all-clear), fed into `FpuCore.scala:93/98/103/110` unconditionally. It
   means **every FP operation on this branch always computes as if FPCR were reset**,
   regardless of what the program writes to it, and no FPCR-enabled numeric exception
   (BSUN/INEX2/DZ/UNFL/OPERR/OVFL/SNAN — vectors 48-55) can ever fire via the enable-bit path.
   **This is not a previously-uncaught gap — Task 9's own report already states it.**
   `task-9-report.md:313-320` records it explicitly, under "a real gap this task exposes":
   *"`orFpsrExc` has no producer, and `roundingMode`/`excEnable` have no consumer... today the
   FP EU uses a hardcoded rounding mode and never reports exception status... This should be
   scheduled before the FPSP integration tasks."* This directly explains both
   `fpu_fcmp_fpcc_sign_of_zero` (round-to-minus-infinity never takes effect) and
   `fpu_divbyzero_exception` (DZ-enable never gates trap escalation) — bucketed (c) above since
   the root cause is a cross-cutting wiring gap, not an op-specific decode/arithmetic issue.
   Task 9's report also flags a sibling gap in the same paragraph: `orFpsrExc` (the FP EU's
   exception-status-report path back into FPSR) likewise has no producer, so FPSR's EXC/AEXC
   bytes only ever change via an architectural FMOVE-to-FPSR write, never as a side effect of
   an actual arithmetic op — the same wiring task should close both at once.

### Bucket (c) findings — full list (3, revised from an original count of 7)

**Fix: corrected 4 misdiagnosed bucket-(c) findings + reframed 2 headline claims.** An
independent review of the original 7-item list below found that 5 of the 7 rows were actually
misclassified (moved to bucket (a) in the table above, with corrected diagnoses), and that a
6th row elsewhere in the table (`fpsp_packed_kernel_e2e`, originally bucket (a)) had a
mis-decoded sentinel hiding a genuine bucket-(c) finding. See the table rows above for the
full corrected citations; only real, re-confirmed bucket-(c) findings remain here:

1. **`fpu_divbyzero_exception`** (`0xDEAD0F0A`) — FPCR.DZ-enable never reaches the fault-
   escalation gate (Headline Finding 2, `DivEuPlugin.scala:182,219-221,855`; also
   `task-9-report.md:313-320`); FDIV-by-zero completes silently instead of trapping.
   Independently, the test's own PASS condition targets vector 49, which Decision 7 explicitly
   documents as m68k-ooo's confirmed-wrong vector for divide-by-zero (real vector is 50) — the
   test's expected value needs correcting regardless of the wiring fix.
2. **`fpu_fcmp_fpcc_sign_of_zero`** (`0xDEADFC22`) — same root cause (Headline Finding 2):
   FPCR rounding-mode field never reaches the FPU datapath, so round-to-minus-infinity mode
   never takes effect and the sign-of-exact-cancellation-zero case (a real, previously-fixed
   m68k-ooo bug class per the test's own header, task #155) cannot be exercised at all on this
   branch.
3. **`fpsp_packed_kernel_e2e`** (`0xBAD00033`) — **re-diagnosed from the original pass, which
   mis-decoded the sentinel as a vector/UNFL code.** It is actually the test's own
   `_v11_second` re-trap-loop marker (`fpsp_packed_kernel_e2e.s:140-147`): the real embedded
   Q700 FPSP kernel re-enters the vector-11 handler a second time instead of completing and
   RTEing once. Likely cause: Task 10's `fpuSoftwareComplete`/`fpuCmdWord` FSAVE-frame trigger
   only populates the unimplemented-instruction frame for the register-to-register FP form
   (`docs/superpowers/plans/2026-08-15-fpu-fpsp-implementation-plan.md:29-37`), so a
   memory-source `FMOVE.P` (packed decimal) trap gets an unpopulated (zero) frame and the FPSP
   kernel has nothing to work with.

**The following 5 findings from the original pass were corrected and moved to bucket (a)
(see the corresponding table rows above for full citations) — recorded here only as a
pointer, not re-duplicated:** `fpu_fmovem_ctrl_predec` and `fpu_fmovem_ctrl_ea_matrix` (the
vendored tests encode Musashi's own confirmed-wrong `-(An)`/`(An)`-direct FMOVEM-control-list
behavior, per Divergence Register D9/D9a and each test's own header comment — this core's
`Microcode.scala` `fpCtrlStorePreGroup`/`fpCtrlStoreBaseGroup` are correct); `fpu_fsave_null_frame`
and `fpu_fsave_idle_format_byte` (both cited spec text — "$40 not $41" / "no 52-byte frame" —
that Task 11's own later, already-landed fix `ee91d51` superseded; the real root cause,
per `task-11-report.md`, is that no FP instruction ever ran before the FSAVE in either test,
so the correct real-68040 frame is NULL, not IDLE/UNIMP, and this core gets that right); and
`fpu_fsave_frestore_idle_roundtrip` (same "no FP op ran, NULL frame expected" root cause,
moved to bucket (a) alongside the other two FSAVE tests, even though the original row for it
did not cite the same "$40/$41" superseded text).

None of the 3 remaining findings were fixed inside this task, per the brief's explicit
instruction — they are handed off as characterized findings for a dedicated future fix task
(or Task 16's whole-branch review).
