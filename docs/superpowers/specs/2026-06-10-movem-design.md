# MOVEM (register-list load/store) — design

**Date:** 2026-06-10
**Status:** approved (brainstorm) — ready for implementation plan
**Branch (to be):** `feat/movem`

## Goal

Implement **MOVEM** — multi-register load/store — `MOVEM <list>,<ea>` (registers→memory) and
`MOVEM <ea>,<list>` (memory→registers), `.W`/`.L`, control modes + `(An)+` (load) + `-(An)`
(store). MOVEM is the core's **first variable-length, multi-cycle-emitted instruction**:
it moves 0–16 registers per the mask, far exceeding the 3-µop crack budget. It's the
primary consumer of the just-merged predec/postinc EA work.

## Context

`DecodeStage` today emits a fixed ≤3-µop crack per instruction (4-wide queue push); there is
**no multi-cycle/microcode decode path** (MOVEM introduces the first). The key idea: a
**micro-sequencer that emits over multiple cycles drives the queue push directly, cycle by
cycle**, sidestepping the 3-µop `AssembledUops` budget entirely (it is not a single fixed
crack). The emitted µops are plain loads/stores — no new µop type or EU datapath.

## Encoding

`0100 1 d 001 s mmmrrr` + a 16-bit register-mask extension word:
- **d (bit 10)** = direction: `0` = registers→memory (**store**), `1` = memory→registers (**load**).
- **s (bit 6)** = size: `0` = `.W`, `1` = `.L`.
- `mmmrrr` = the EA. **Store** allows control alterable modes + `-(An)` (mode 4). **Load**
  allows control modes + `(An)+` (mode 3) + `(d16,PC)`/`(xxx)`. (Indexed `(d8,An,Xn)` is the
  deferred indexed-modes slice — out of scope here.)
- 2-word instruction (opword + mask); `.W` load **sign-extends** each word to the full 32-bit
  register.

**Mask bit order:**
- Control modes + `(An)+`: bit 0 = D0 … bit 7 = D7, bit 8 = A0 … bit 15 = A7; **ascending
  addresses** (lowest register-number at the lowest address).
- `-(An)` predecrement (store only): the mask is **reversed** — bit 0 = A7 … bit 15 = D0;
  registers stored **high-to-low** with the address predecrementing.

## Architecture — MOVEM micro-sequencer in `DecodeStage`

A small FSM, emitting **2 register-moves per cycle**:
1. **Detect + latch.** When a MOVEM packet reaches `fed`, latch the 16-bit mask, base An (or
   the folded PC/abs address for control/PC modes), direction, size; enter `movemActive` and
   **hold `fed`** (`fed.ready := False`) so no new instruction decodes while emitting.
2. **Emit (each cycle, ≤2 µops).** Priority-encode the **lowest two** set mask bits (or the
   **highest two** for `-(An)` reverse order); drive `pushProduced.uops(0..1)` with their
   load/store µops + `count = 2` (or `1` for an odd tail), overriding the normal `a0` crack.
   - **Load** `<ea>,<list>`: `load.sz [addr] → reg` (`.W` → sign-extend to 32).
   - **Store** `<list>,<ea>`: `store.sz reg → [addr]`.
   - **Address:** running `base + offset` (control/postinc, offset += size per register) or
     predecrementing `base − k*size` (predec). Clear the two emitted mask bits.
3. **Finish.** When the mask reaches 0: for `(An)+`/`-(An)` emit a single **final An-update
   µop** (`An := base ± count*size` — one ADD, NOT a per-move fold, since MOVEM updates An
   once); then consume `fed` (`fed.ready := True`) and return to idle. Control modes leave An
   unchanged (no final µop).
4. **Flush.** `pipeFlush` mid-sequence aborts: reset the FSM to idle (the partially-emitted
   µops are squashed by the queue flush; MOVEM re-decodes from scratch on re-fetch).

The µops are the existing load/store µop shapes (`memOp` LOAD/STORE, the EA base/disp on the
LS path). No flags (MOVEM affects no CCR). No new EU work.

## Testing (gates, in order)

1. **Decode/predecode:** a `MovemDecodeSpec` (the FSM's per-cycle emission for representative
   masks: direction/size, ascending vs reversed order, the 2/cycle pairing + odd tail, empty
   mask); `PredecodeWord`/`PredecodeRef` frame MOVEM as a 2-word instruction (opword+mask) —
   re-run `PredecodeWordSpec` (parity over all opwords).
2. **Lock-step vs Musashi** (with `checkMem`):
   - **Prologue/epilogue:** `MOVEM.L D0-D7/A0-A6,-(A7)` then `MOVEM.L (A7)+,D0-D7/A0-A6` —
     the classic save/restore; verify all registers + A7 round-trip + memory image.
   - `MOVEM.W` (sign-extend on load), control-mode `MOVEM <list>,(d16,A0)` / `(xxx).L`,
     `MOVEM (d16,PC),<list>` load.
   - **Edge masks:** empty (0 registers — no moves; An unchanged for control, and per the
     68k for postinc/predec), single register, full 16, and **odd counts** (exercise the
     2/cycle tail), and sparse masks (non-contiguous bits → the priority-encode order).
   - `-(An)` reversed order + the final An; `(An)+` final An; address wraparound.
3. **`make test-fast`** + the regression (the FSM must not perturb non-MOVEM decode; the
   front-end stall while emitting must not deadlock — a MOVEM followed by more instructions).
4. **≥200 MHz synth gate** — the 2-way priority-encode + 2 address adders are shallow; expect
   FMax-neutral (D-cache still the limiter).

## Non-goals

- Indexed `(d8,An,Xn)`/`(d8,PC,Xn)` MOVEM EAs — deferred with the general indexed-modes slice.
- Faster emit (4/cycle) — 2/cycle chosen; revisit only if MOVEM ever shows on a hot path.
- MOVEP, MOVES.

## Risks

- **The 2/cycle mask extract** (lowest/highest two set bits) + the **reversed order** for
  predec + the **odd tail** (count=1 last cycle) — the priority-encode + address stepping
  must match Musashi exactly. Covered by sparse/odd/full-mask lock-step.
- **Front-end stall while emitting** — `fed` held for ~8 cycles; must not deadlock and must
  resume cleanly (a following instruction decodes after). Covered by a MOVEM-then-more
  lock-step + the flush-mid-sequence case.
- **The single final An update** (not a per-move fold) — `An := base ± count*size` once; an
  off-by-one on count or a per-move double-update desyncs An/stack. Covered by the
  prologue/epilogue round-trip with `checkMem` + An asserts.
- **Predecode framing** — MOVEM is opword+mask (2 words); a mis-frame → `nextPc` wrong.
  Covered by `PredecodeWordSpec` parity.
- **RAW on the loaded registers / An** — registers written by a MOVEM load are LS-produced;
  a following reader uses the `s0IsLsIntProd` dynamic wakeup (the predec/postinc path).
