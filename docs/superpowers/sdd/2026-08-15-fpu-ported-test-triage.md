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
| fpsp_packed_kernel_e2e | FAIL (`0xBAD00033`) | (a) | One of Decision 8's two explicitly-named acceptance targets ("validates the *trap delivery* mechanism, not hardware packed-decimal arithmetic" — still an in-scope target, not deferred). Sentinel `0xBAD00033` = vector `0x33` = **51 decimal = FP Underflow (UNFL)**, per Decision 7's own vector table. The real embedded Q700 ROM FPSP kernel, executing at its native address after the genuine `FMOVE.P` vector-11 trap, hits an unexpected UNFL exception partway through. Root cause not further isolated in this read-only task; next step is tracing which kernel instruction raises UNFL and whether Decision 10's hardware-native OVFL/UNFL substitution path is interacting correctly with a real in-kernel FP op. |
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
| fpu_fmovem_ctrl_ea_matrix | FAIL (`0xDEAD2001`) | **(c)** | `FMOVEM.L` control-register-list (FPCR/FPSR/FPIAR) via `(An)`/`(An)+`/`(xxx).W`/`(xxx).L`. Decode succeeds (no `0xDEAD20xx`-family vec-11 code was hit); a per-mode value assertion fails ("see comments at each check site" per the file's own generic legend). Same family of bug as `fpu_fmovem_ctrl_predec` below — Task 9b's control-list *store* path produces wrong values for some EA modes even though `(d16,An)` (`fpu_fmovem_ctrl_reg.s`, passes) is correct. Not further isolated per-mode in this read-only pass. |
| fpu_fmovem_ctrl_predec | FAIL (`0xDEAD1003`) | **(c)** | `FMOVEM.L` control-register-list via `-(An)`. Decode succeeds (did not hit `0xDEAD1001`, the vec-11 code); fails at "store: mem[final+0] (FPIAR slot) wrong" — a genuine value-computation bug in Task 9b's predecrement store ordering/content, distinct from any decode gap. |
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
| fpu_fsave_frestore_idle_roundtrip | FAIL (`0xDEAD0F07`) | **(c)** | Programs FPCR/FPSR, FSAVEs an IDLE frame, mutates FPCR/FPSR, FRESTOREs, and expects FPCR/FPSR to come back to the saved values. Per this project's own Decision 7, the NULL/IDLE frame is 4 bytes total (a format header only) — there is no room in that frame for FPCR/FPSR content, and Decision 5 treats FPCR/FPSR as ordinary always-live architectural registers independent of FSAVE/FRESTORE. This test's premise looks like it is checking an m68k-ooo-specific historical behavior (their frame grew to a 52-byte UNIMP-shaped frame that did carry these fields — see the null_frame/idle_format_byte rows below) rather than real-68040 semantics. Flagged as likely-stale-test-expectation, **not independently confirmed** against this project's actual FSAVE/FRESTORE RTL in this read-only session — needs a dedicated look before concluding either way. |
| fpu_fsave_idle_format_byte | FAIL (`0xDEAD1801`) | **(c)** | Hardcodes the expected IDLE-frame format byte to `0x41`. Decision 7 explicitly corrects the "$40/$41 format-type enum" reading of the manual: NULL forces version `$00`; IDLE uses "the real version number" (the spec's own closing note suggests `$40` as "a reasonable default choice", not `$41`). `0x41` looks like m68k-ooo's own non-manual-derived null/idle encoding scheme, not the real 68040 semantics this project's Decision 7 locks. Same "possibly-stale-test-expectation" caveat as the row above — not independently re-verified against the actual RTL output in this session. |
| fpu_fsave_null_frame | FAIL (`0xDEAD0F41`) | **(c)** | Hardcodes the expected frame as 52 bytes with header long `0x41300000`. Decision 7 explicitly states "there is no 52-byte frame" — a real corrected reading of the manual that supersedes m68k-ooo's own (acknowledged-by-them, in the file's own "HISTORICAL NOTE"/"SECOND IN-PLACE UPDATE" comments) frame-size history. This test's expected value is almost certainly stale relative to this project's own real-manual-verified design, not an RTL bug — but not independently confirmed against the actual FSAVE output in this session. |
| fpu_fsqrt_basic | FAIL (`0xDEAD0F01`) | (a) | Same FMOVE-store-direction gap — verify step is `FMOVE.S FP1,(A0)`. |
| fpu_fsqrt_flush_survive | FAIL (`0xDEAD0F01`) | (a) | Same FMOVE-store-direction gap. |
| fpu_fsqrt_neg | FAIL (`0xDEAD0F01`) | (a) | Same FMOVE-store-direction gap. |
| fpu_fsqrt_zero | FAIL (`0xDEAD0F01`) | (a) | Same FMOVE-store-direction gap. |
| fpu_reg_rr_smoke | PASS | (a) | — |
| fpu_x2s_stash_back_to_back | **HANG** (200000 cycles) | (a) | Same FMOVE-store-direction gap (two back-to-back `FMOVE.S FPn,(An)`), but this test installs **no vector-11 handler at all** (it assumes the store direction just works, per its own post-fix-on-m68k-ooo framing), so the undecoded trap has nowhere sane to land and the core wanders instead of writing a clean FAIL sentinel — manifests as a HANG rather than a `PortedFail`. Same root cause, different failure signature; flagged separately since a HANG is a qualitatively different signal than a sentinel mismatch. |

## Summary

**Real corpus: 46 files** (2 `fpsp_*`, 44 `fpu_*`) — matches the plan-writing-time figure,
confirmed by live re-run rather than trusted from the doc.

**Totals: 12 PASS / 34 FAIL** (1 of the 34 is a HANG, not a `PortedFail` sentinel mismatch).
- **Bucket (a): 27** — genuine in-scope targets still failing (plus the 12 passes), each with
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
- **Bucket (c): 7** — genuine, unexpected findings not covered by (a) or (b). Full
  characterization below (this is Task 14 working correctly, not a task failure).

### Headline findings (the two structurally dominant root causes behind most bucket-(a) FAILs)

These aren't new buckets — every affected row above is still classified per the brief's
literal (a)/(b)/(c) rules — but they are the two single facts that explain the *large majority*
of the 27 bucket-(a) failures, and are worth surfacing prominently rather than leaving buried
across ~20 near-identical table rows:

1. **`FMOVE FPn,<ea>` (the entire register-to-EA "store" direction of FMOVE, extension-word
   opclass `011`) has zero decode coverage, including the simplest register-direct case
   (`fmove.l %fp0,%d1`, no memory at all).** Confirmed at `MicroOpAssembler.scala:1592-1595`
   (`fpEmit`'s conditions test opclass `000`/`010` and the FPCR/FPSR/FPIAR opclass `100`/`101`
   band, but never `011`) and `OperationDecoder.scala:1181-1216` (explicit comment: `` `FMOVE
   FPn,<mem>` (011, store, NOT this task) `` — real memory-mode forms route through the µcode
   ROM's placeholder and are deliberately rejected back to vector-11 by `ucBegin`; register-
   direct forms never even reach that gate and fall straight to the vector-11 default).
   Cross-checking the 18-task plan's own task list (`docs/superpowers/plans/2026-08-15-fpu-fpsp-implementation-plan.md`),
   **no task ever claims this direction**: Task 6b's title and scope are explicitly the *load*
   direction (`F<op> <mem>,FPn`); Task 9/9b own only the FPCR/FPSR/FPIAR control-register
   band, a different opclass. This single gap directly causes 14 of the 27 bucket-(a) FAILs
   (every test whose *own subject* is the store direction, e.g. `fpu_fmove_fp_to_ea_matrix`,
   `fpu_fmove_dn_fpn_roundtrip`, `fpu_fmove_fpn_mem_nonzero_reg`, `fpu_fmove_x_fp0_d16_a6_repro`,
   `fpu_x2s_stash_back_to_back`) plus every test that merely *uses* `FMOVE.S FPn,(An)` as its
   own result-verification idiom (`fpu_fadd_exact_single`, `fpu_fdiv_flush_survive`,
   `fpu_fint_basic`, `fpu_fintrz_basic`, `fpu_fsqrt_basic/_flush_survive/_neg/_zero`) — the
   latter group would very plausibly *pass* if the store direction existed, since their actual
   arithmetic is never reached as the point of failure. This falsifies the design spec's own
   pre-run bucket-(b) prediction (see above) and is by far the single highest-value close for
   whoever picks up the next FPU task: it is currently unowned by any task in the plan.

2. **FPCR's rounding-mode field and exception-enable byte are never wired from
   `FpuControlPlugin`'s live FPCR into the FPU execution datapath at all.**
   `DivEuPlugin.scala:176-221` declares `fpRmodeIn`/`fpExcEnableIn` as `allowOverride` input
   seams with a comment that reads, verbatim: *"THIS IS THAT TASK'S INTEGRATION POINT: drive
   it from FpuControlPlugin's live FPCR and nothing else here changes"* — but a repo-wide
   search confirms **no other file in `src/main/scala/` ever assigns either var**; they sit
   permanently at their elaboration defaults (`rmode = 00` round-to-nearest,
   exception-enable = all-clear), fed into `FpuCore.scala:93/98/103/110` unconditionally. This
   is a real, previously-uncaught gap: it means **every FP operation on this branch always
   computes as if FPCR were reset**, regardless of what the program writes to it, and no
   FPCR-enabled numeric exception (BSUN/INEX2/DZ/UNFL/OPERR/OVFL/SNAN — vectors 48-55) can
   ever fire via the enable-bit path. This directly explains both `fpu_fcmp_fpcc_sign_of_zero`
   (round-to-minus-infinity never takes effect) and `fpu_divbyzero_exception` (DZ-enable never
   gates trap escalation) — bucketed (c) above since the root cause is a cross-cutting wiring
   gap, not an op-specific decode/arithmetic issue, and directly contradicts Task 8/9's
   "integration point" comment implying this was to be completed by a later, now-landed task.

### Bucket (c) findings — full list (7)

1. **`fpu_divbyzero_exception`** (`0xDEAD0F0A`) — FPCR.DZ-enable never reaches the fault-
   escalation gate (Headline Finding 2, `DivEuPlugin.scala:182,219-221,855`); FDIV-by-zero
   completes silently instead of trapping. Independently, the test's own PASS condition
   targets vector 49, which Decision 7 explicitly documents as m68k-ooo's confirmed-wrong
   vector for divide-by-zero (real vector is 50) — the test's expected value needs correcting
   regardless of the wiring fix.
2. **`fpu_fcmp_fpcc_sign_of_zero`** (`0xDEADFC22`) — same root cause (Headline Finding 2):
   FPCR rounding-mode field never reaches the FPU datapath, so round-to-minus-infinity mode
   never takes effect and the sign-of-exact-cancellation-zero case (a real, previously-fixed
   m68k-ooo bug class per the test's own header, task #155) cannot be exercised at all on this
   branch.
3. **`fpu_fmovem_ctrl_predec`** (`0xDEAD1003`) — `FMOVEM.L` control-register-list via `-(An)`
   decodes correctly but stores the wrong FPIAR-slot value; a genuine Task 9b value-
   computation bug, distinct from any decode gap (the sibling `(d16,An)` form,
   `fpu_fmovem_ctrl_reg.s`, is correct).
4. **`fpu_fmovem_ctrl_ea_matrix`** (`0xDEAD2001`) — same family as #3: `(An)`/`(An)+`/
   `(xxx).W`/`(xxx).L` control-list forms decode but fail a per-mode value assertion. Not
   further isolated per-mode in this read-only pass.
5. **`fpu_fsave_frestore_idle_roundtrip`** (`0xDEAD0F07`) — expects FPCR/FPSR to persist
   across an IDLE-frame FSAVE/FRESTORE round-trip; Decision 7's 4-byte NULL/IDLE frame has no
   room for FPCR/FPSR content and Decision 5 treats them as FSAVE/FRESTORE-independent —
   likely a stale test premise inherited from m68k-ooo's own historically-larger frame, but
   **not independently confirmed** against this project's actual FSAVE/FRESTORE RTL this
   session.
6. **`fpu_fsave_idle_format_byte`** (`0xDEAD1801`) — hardcodes IDLE format byte `0x41`;
   Decision 7 corrects the "$40/$41 enum" reading of the manual (NULL forces `$00`, IDLE uses
   "the real version number", spec's own suggested default `$40`) — likely stale test
   expectation, same not-independently-confirmed caveat as #5.
7. **`fpu_fsave_null_frame`** (`0xDEAD0F41`) — hardcodes a 52-byte `0x41300000`-header frame;
   Decision 7 explicitly states "there is no 52-byte frame" on real hardware — likely stale
   test expectation (the file's own comments admit this shape came from tracking an
   m68k-ooo-specific historical frame-size bug), same not-independently-confirmed caveat.

None of these 7 were fixed inside this task, per the brief's explicit instruction — they are
handed off as characterized findings for a dedicated future fix task (or Task 16's whole-
branch review).
