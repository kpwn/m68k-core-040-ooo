# FMOVEM.X data-register-list decode — design spec

**Task**: #229. **Scope directive (user, 2026-08-19)**: match v1's own current lower bar for
FMOVEM.X data-register-list support — implement exactly what v1's RTL (`m68k-ooo`,
`decode_1111.vh`, HEAD `4eab900`) admits today, no more. The dynamic (Dn-count) register-list
form and the packed-decimal→FP4-7 FPSP interaction stay explicitly out of scope (v1 doesn't
support them either — confirmed fresh against v1's current RTL and test corpus, not stale docs).

## 1. What's in scope

FMOVEM.X `<list>,<ea>` (store, opclass 111) and FMOVEM.X `<ea>,<list>` (load, opclass 110),
**static register list only** (`ext1[11] == 0`), across exactly these EA modes (matching v1's
own admission gate in `decode_1111.vh` line ~2216 onward):

| EA mode | Load (`<ea>,<list>`) | Store (`<list>,<ea>`) |
|---|---|---|
| `(An)` | ✅ | ✅ |
| `(An)+` | ✅ | ✅ |
| `-(An)` | ✅ | ✅ |
| `(d16,An)` | ✅ | ✅ |
| `(d8,An,Xn)` brief, scale=0 only | ✅ | ✅ |
| `(d16,PC)` | ✅ (load-only; PC-relative isn't an alterable destination) | ❌ |

**Stays trapped (matches v1, zero ROM hits in v1's own scan)**: dynamic lists
(`ext1[11]==1`), `(xxx).W`/`(xxx).L`, `(d8,PC,Xn)`, full-format indexed (`ext2[8]==1`),
nonzero index scale, FPn→`(d16,PC)` store.

## 2. ISA semantics (from v1's `docs/isa_status.md`, cross-checked against real Q700 ROM +
Musashi `fmovem()`/`m68kfpu.c:1662`)

- **Register-list → address ordering**: predecrement list (`ext1[12:11]==00`) maps mask bit
  `n` → `FPn`; control/postincrement list (`ext1[12:11]==10`) maps mask bit `n` → `FP(7-n)`.
  Both lay the **highest-numbered listed register at the lowest address** — this is why an
  `-(An)` save round-trips through an `(An)+`/control restore without a separate reversal.
- **Empty list (`ext1[7:0]==0`) is an architectural no-op**: no memory traffic, `An`
  unchanged. NOT illegal (unlike the control-register-list crack's mask==000, which IS
  illegal for a different reason — D9b divergence register, no consumer expects an empty
  control-register op).
- **FPCC is not updated**: FMOVEM is not a compute instruction; Musashi's `fmovem()` never
  touches FPSR.
- **Data format**: always Extended (96-bit in-memory, 3×32-bit chunks) — this is the ONLY
  format FMOVEM.X data-register-list transfers (unlike FMOVE.<fmt> single-register moves,
  which support 6 formats). This core already has the Extended in-memory layout nailed down
  from the existing scalar FMOVE.X crack: `long0 = {sign,exp[14:0],16'h0000}`,
  `long1 = mant[63:32]`, `long2 = mant[31:0]`.
- **An auto-update delta**: ±12×(listed register count) for `(An)+`/`-(An)` (12 bytes/element,
  Extended's full 96-bit footprint — no 16-bit pad truncation in memory, matching the scalar
  crack's existing `fpMemFormats` Extended entry).

## 3. Why this needs a new FSM (not a ROM-crack row-budget extension)

The register mask is a **decode-time-known 8-bit immediate** in `ext1[7:0]` (only the
*dynamic* form reads it from `Dn` at runtime) — so, unlike a true dynamic list, this is NOT a
"decode-time crack fundamentally cannot express this" problem. It's the same problem integer
MOVEM already solved: up to 8 elements, each needing the existing scalar Extended crack's
per-element row sequence — `[LOAD ×3 chunks] + [UFpIssue]` (4 rows) for load, or
`[UFpStoreCvt ×3 chunks] + [MStore ×3 chunks]` (6 rows) for store (`Microcode.scala`'s
`fpMemBaseGroup`/`fpMemAutoPostGroup`/`fpMemAutoPreGroup`/`fpStoreCvtRows`) — which at 8
elements (up to 48 rows for an all-8 store) exceeds this core's fixed `AssembledUops` crack
row budget, the exact same reason `DecodeStage.scala`'s `movemActive` FSM exists for integer
MOVEM ("moves 0..16 registers — far beyond the 3-µop crack budget").

**Architecture**: a new `fmovemxActive` FSM, structurally modeled on `movemActive`
(`DecodeStage.scala:314-480`), holding `fed` and directly pushing µops to the queue
(bypassing `AssembledUops`) each cycle, entered/exited the same way MOVEM is. Differences
from `movemActive`:

- **8-bit mask, not 16-bit** (`FP0`-`FP7` only) — simpler priority-encode, no reverse-bit
  16-vs-8 distinction needed beyond what's already spelled out in §2's ordering rule.
- **Multi-cycle per element**, not 1-2 elements per cycle: each listed FPn needs the full
  3-chunk load-or-store-cvt sub-sequence, so the FSM needs a **sub-phase counter per
  element** (mirroring the existing scalar crack's own row sequence, driven cycle-by-cycle
  instead of ROM-unrolled) — the closest existing precedent for a *multi-phase-per-element*
  FSM in this core is v1's own phase-layout doc description (8 phases/register: 6 real + 2
  pad on load, 7 real + 1 pad on store) — NOT literally portable (different microarchitecture)
  but useful as a cross-check that "several phases per FP element" is the right order of
  magnitude, not a red flag that something is over-complicated.
- **Reuses `UFpIssue`/`UFpStoreCvt` op semantics verbatim** per element — these already do
  exactly the "3 chunks in/out of a temp, one FP-register-wide issue/convert" job; the FSM's
  job is sequencing which FPn is the source/dest for the current element and driving the
  chunk loads/stores' addresses, not reinventing the conversion datapath.
- **Base/index snapshot hazard** (task #200/movem-agu-index-hazard precedent): a
  `(d8,An,Xn)` base `An` or index `Xn` that also appears in the FP list is NOT possible
  (integer and FP register files are disjoint — an FPn can never alias An/Xn), so this FSM
  does **not** need MOVEM's snapshot sub-phase at all. This is a genuine simplification
  vs. the integer MOVEM FSM, not an oversight — call this out explicitly in the
  implementation so nobody "fixes" its absence later.
- **No lock-step "drop moves, keep final µop" complication for control-mode (non-auto)
  EAs**: unlike integer MOVEM (which needs a final no-op `An := An + 0` µop to carry the
  macro's lock-step commit for `(An)`/`(d16,An)`/`(d8,An,Xn)`/`(d16,PC)`), the *existing*
  scalar Extended crack's `UFpIssue`/`fpStoreCvtRows`'s last row already carries
  `isLast`/`keepCommit` semantics (see Microcode.scala's "LOCK-STEP MACRO COMMIT" comment
  for the store direction) — reuse that same convention: the FSM's last-emitted element's
  last µop carries the kept commit; only `(An)+`/`-(An)` get an *additional* real An-update
  µop after the mask drains (same shape as MOVEM's `movemAnUop`, but unconditionally kept
  since there's no "abs/PC mode has no final µop" carve-out — FMOVEM.X data-list never
  targets an abs EA at all).

## 4. Decode-admission gate

New gate in `DecodeStage.scala`, alongside the existing `ucFpCtrlOk`/`ucFpStoreOk`
(§ "Task 9b"/"Task 14b" comments), call it `ucFpDataListOk`, keyed on `ucFpOpClass ===
B"3'b110"` (load) / `B"3'b111"` (store) *distinct from* the pre-existing `ucFpCtrlOk`'s
100/101 and `ucFpStoreOk`'s 011 — **confirm this doesn't collide with FMOVECR**, which the
existing scoping investigation flagged as also living at opclass 111 for the register-direct
case (`OperationDecoder.scala`'s `fpMemIsMemEa` already excludes register-direct/immediate
from ever reaching this engine, so the memory-EA-only data-list gate and FMOVECR's
register-direct-only gate should already be mutually exclusive by EA-class alone — verify
this explicitly with a decode-matrix test before relying on it silently).

`OperationDecoder.scala`'s cpGEN admission table needs opclass 110/111 added (currently
routes unconditionally to `FP_MEM_TRAP_ENTRY` per the scoping investigation's finding at
`OperationDecoder.scala:1194-1236`) — gated the same way the FSM entry itself is gated
(static list only; dynamic stays on the trap path).

## 5. Non-goals (explicit, matches v1's own current limitation — do not silently expand)

- Dynamic register lists (`ext1[11]==1`) — stays F-line-trapped, unconditionally, for every
  EA mode, matching v1's own current RTL exactly (confirmed 2026-08-19, not stale).
- The packed-decimal→FP4-7 FPSP interaction (real Q700 ROM FPSP code path, confirmed via
  v1's `docs/isa_status.md` + fresh RTL/test-source cross-check) — tracked as a known,
  separate, NOT-closed gap in both cores. Out of scope for this task.
- `(xxx).W`/`(xxx).L`/`(d8,PC,Xn)`/full-format-indexed/nonzero-scale — zero ROM hits in v1's
  own scan across two independent full-ROM disassembly sweeps; stays trapped.

## 6. Verification plan (mirrors v1's own test list, ported where this project's corpus
already has the equivalent asset)

Already in this project's ported corpus (currently failing, would be closed by this task):
`fpu_fmovem_multi`, `fpu_fmovem_x_an_indirect`, `fpu_fmovem_x_fpsp_epilogue`,
`fpu_fmovem_x_idx_pcdi_data`, `fpu_fmovem_x_pcdi` (partial), `fpu_fmovem_x_postinc_all`,
`fpu_fmovem_x_roundtrip`, `fpu_fmovem_x_an_dynlist_fline` (partial — its POSITIVE control
stage requires the static form to succeed; its negative-assertion stages must keep passing,
i.e. the dynamic-trap behavior must NOT regress).

Additional coverage to add (matching v1's own `make tb-decode-fpu`-class exhaustive
per-phase assertion, adapted to this core's own decode-matrix-test convention): a decode
matrix test enumerating mask popcount 0/1/2/8 (empty, single, small, full) × the 6 admitted
EA modes × load/store direction, checking phase-shape (which FPn each chunk-sequence binds
to, in the correct ordering per §2) without requiring full functional simulation for every
cell — mirrors this project's existing `PredecodeRefSpec`/decode-matrix test style.

## 6.5. Hot-case cost (user-raised, 2026-08-19 — binding constraint, not just an FMax flag)

The new FSM's own internal logic only costs anything while `fmovemxActive` is true. The real
risk is its **entry-detection and fetch-hold signals**, which sit on shared combinational
paths every decode cycle evaluates regardless of instruction — that's the part that can
regress the hot (non-FMOVEM-data-list) case, and it's a stricter bar than "don't regress
FMax overall": the goal is not to worsen the hot case AT ALL.

This codebase already has the right pattern, used by all three existing multi-cycle-emit
FSMs (`movemActive`, the MOVEP FSM, the µcode sequencer) — **fold in, don't parallel-build**:
- `DecodeStage.scala` (~line 2274): `val movemHoldsFed = movemActive || movemPendValid` —
  each FSM's "am I holding" is one more OR term in a shared aggregate.
- `DecodeStage.scala` (~line 2293): `when(!movemActive && !movemBegin && !ucBegin &&
  !ucActive && !movepActive && !movepBegin && pushProduced.ready) { ... }` — the "can the
  normal fast path proceed" gate is a single wide AND across every FSM's active/begin flags.

The new FSM's `fmovemxActive`/`fmovemxBegin` (or equivalent) must become additional terms
folded into these SAME existing aggregate expressions, not a separate parallel hold-decision
tree that then gets combined with the existing one (which WOULD add real depth on the hot
path). Widening an already-wide gate by one more term is close to free; a second independent
gate is not. Check for any other shared aggregate signals in the file (anything gating
`fed.ready`, pipeline `ready`, a "some sequencer active" catch-all) and fold into those the
same way. This applies to every task in the breakdown below, not just Task 1 — Tasks 2-4
extend the same FSM and must not introduce a second hold path either.

## 7. Suggested task breakdown (for the implementation plan)

1. FSM skeleton + non-auto EA modes ((An), (d16,An)) + load direction only — proves the core
   multi-element-multi-phase mechanism works before adding auto-update/store/indexed
   complexity.
2. Store direction + `(An)+`/`-(An)` auto-update.
3. `(d8,An,Xn)` indexed EA (scale=0 only) — both directions.
4. `(d16,PC)` load-only.
5. Decode-matrix regression test + full corpus closure (the 8 tests in §6) + mandatory
   elaboration/synth-only WNS check (this touches `DecodeStage.scala`'s frontend-adjacent
   decode cone — flag for the standard FMax-sensitivity gate this session has applied to
   every other frontend-touching change).
