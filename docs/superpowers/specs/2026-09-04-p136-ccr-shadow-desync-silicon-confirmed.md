# Part 136 — the RTE CCR-shadow desync is CONFIRMED ON SILICON, and the measurement Part 134 §9 / Part 135 §7.5 asked for is DEGENERATE as specified

**Date:** 2026-09-04
**Bitstream:** p133, `build_id 0x14F3C599`, `cpu=m68k040` (verified from
`macqd700-soc-worktrees/p133-hold-fixed-latest/build/vivado/fpga_top.buildinfo`)
**Scope:** board/JTAG only. No Vivado build, no SD-card write, no ROM patch.

---

## 1. Headline

1. **The CCR bug reproduces on silicon.** Two independent in-window captures, at
   two different PCs, show an interrupt frame stacking a CCR that is
   architecturally impossible at that PC — and the wrong value is exactly the
   *previous handler's* exit CCR, as Part 135 predicted.
2. **CRITICAL CORRECTION — the proposed measurement cannot work as specified.**
   Part 135 §7.5 proposed "read the **CCR** at the same two points". On this
   bitstream that is **structurally incapable of detecting the bug**: the debug
   port's SR and the exception frame's SR are built from *the same register*.
   Run naively it returns a clean bill of health. I ran it first, got **12/12
   MATCH**, and those 12 results are **vacuous** — reporting them as a negative
   would have been a false exoneration of the leading hypothesis.
3. **Measurement (A) — A7 across entry/RTE — is EXACT in, EXACT out.** Branch 1
   of the three: the entry/`RTE` pair is sound, corruption is downstream.
   Consistent with the CCR hypothesis.
4. This does **not** mean the boot is fixed. Nothing was fixed this session.

---

## 2. Why the specified measurement (B) is degenerate

`live-arch`'s SR and the stacked frame's SR low byte both come from
`RobPlugin.committedCcr`:

| path | source |
|---|---|
| debug `OFF_LIVE_SR` | `RobPlugin.scala:2373` — `debugSystemSr = (exc.ss.srSys ## B(0,3 bits) ## committedCcr.asBits)` → `:2821` `override def sr` → `DebugCtrlPlugin.scala:862` |
| stacked frame SR | `RobPlugin.scala:2037` `ccrForException := committedCcr` → `:2153` → `ExceptionUnit.scala:1353` `oldSr := (ss.srSys ## committedCcr.resize(8 bits))` |

For an interrupt, `ccrForException` is `committedCcr` **unmodified** (the
`faultRetire &&` folds at `RobPlugin.scala:2038-2039` do not fire). Same
register, same cycle-invariant value. The comparison can never disagree.

Evidence it is degenerate rather than merely clean: across 12 captures the live
SR and stacked SR differed **only** in the IPL field, never once in the CCR —
including captures with four distinct non-zero CCR values (`0x04`, `0x08`,
`0x14`, `0x00`).

> **Standing note for the next session:** on cpu040, *any* comparison of a debug
> register against an exception frame's SR low byte is a comparison of
> `committedCcr` with itself. Ground truth must come from the instruction stream
> or from a second frame — never from `live-arch`.

`committedCcr`'s only write sites (`RobPlugin.scala`): `:2020` reset, `:2027`
the per-µop retire fold, `:2217` `obsSetCcr5Valid` (MOVE-to-SR / STOP only),
`:2382` host arch-apply. **`RTE` writes the flags PRF** (`ExceptionUnit.scala:1770-1774`
`rteNzvcWrite`/`rteXWrite` → `RenameStage.committedPhysNzvc/X`) **and never the
shadow.** Confirmed independently of the Part 135 doc.

---

## 3. The construction that does discriminate

The bug needs an exception entry with **no flag-writing instruction retired
since the previous handler's `RTE`**. That is manufacturable for free on this
board:

> Halting for several seconds at a **level-2** (vec `0x1a`, IPL 2) entry
> guarantees the 60 Hz VIA1 **level-1** (vec `0x19`) timer IRQ goes pending and
> is **masked** by the handler's IPL 2. On release the level-2 handler runs to
> completion, executes its `RTE`, and the pending level-1 IRQ is taken
> **immediately — at the same instruction, before anything retires.**

Per trial: arm `halt-exc-mask 0x1a`; at the halt read frame A at `A7`; disarm
the exception halt; `break-pc` at frame A's return PC; at that halt read the
frame now sitting at `A7` — that is frame B.

**IN-WINDOW iff `A7_B == A7_A` and `retPC_B == retPC_A`**: the level-1 frame
occupies the exact bytes the level-2 frame did and targets the same
instruction, so only the handler and its `RTE` ran in between.

Architecturally frames A and B **must** carry the same CCR.

Every JTAG read of a frame was preceded by `dcache-op push` — Mac RAM is
copyback and JTAG reads bypass the D-cache.

---

## 4. Result — measurement (B)

| pair | PC | frame A (vec) CCR | frame B (vec) CCR | interrupted instruction |
|---|---|---|---|---|
| 1 | `0x408072ce` | `0x1a` → `0x00` | `0x19` → `0x04` | `moveq #0,%d0` |
| 2 | `0x408071fc` | `0x1a` → `0x00` | `0x19` → `0x04` | `moveml %d1-%fp,%sp@-` |

Both in-window pairs show **CCR `0x00` → `0x04` with nothing retired between
them.** Only 2 of 8 completed trials landed in-window; the rest degenerated into
a repeating *nested* pattern (level-2 preempting the level-1 handler's first
instruction at `0x40809b60`), which is not the window.

### Where the wrong value comes from

Every autovector funnels into one shared handler tail (ROM `420dbff3`):

```
40809b68: tstb 0xcb2
40809b6e: jsr  %a3@              <- device-specific handler
40809b70: tstl 0xd94             <- LAST flag write on the common path
40809b74: beqs 0x40809b84
...
40809b84: moveml %sp@+,%d0-%d3/%a0-%a3   <- writes NO flags
40809b88: rte
```

`tstl 0xd94` on a zero long leaves **CCR = `0x04` (Z=1)**, and neither `moveml`
nor `rte` disturbs it. `0x04` is therefore precisely the handler's exit CCR —
the stale shadow value the next entry stacks. Exactly Part 135's predicted
mechanism, observed on hardware.

### Confounds addressed

* **"The handler rewrote its own stacked SR."** Refuted from the ROM: the
  handler only *reads* the frame (`andb %sp@(32),%d0`, extracting IPL). There is
  no write to the stacked SR anywhere in the tail.
* **"The interrupted instruction retired and set the flags."** This is a real
  alternative at pair 1, because `moveq #0,%d0` also yields `0x04` — an unlucky
  coincidence. It is **dead at pair 2**: `moveml` writes no flags at all, so it
  cannot produce `0x04` under any circumstances. Pair 2 is why a second PC was
  required.
* **Frame internally inconsistent regardless.** Both frames' stacked PC says the
  interrupted instruction had *not* retired. So even under the alternative, the
  frame's PC and its CCR disagree about which side of the instruction boundary
  they are on — still a defect of the same family.

### Verdict

**CONFIRMED on silicon**, with the caveat that it rests on 2 in-window pairs.
The signature, the magnitude, and the identified source (`tstl 0xd94`) all match
Part 135's simulation prediction.

---

## 5. Result — measurement (A): A7 across entry and `RTE`

**EXACT in, EXACT out — branch 1 of the three.**

Worked example (vec `0x1a` at `0x408072ce`):

* At entry: `A7 = 0x004007f8`, frame `SR=0x2000 PC=0x408072ce fmt/vec=0x0068`
  (format `$0`, vec 26, **M=0**) ⇒ **pre-entry A7 = `0x00400800`**.
* After the handler's `RTE`: the frame at `0x004007f8` is now
  `SR=0x2004 PC=0x408072ce fmt/vec=0x0064` — **vector 25**, a level-1 frame ⇒
  its pre-entry A7 = `0x004007f8 + 8` = **`0x00400800`**. Identical.

So the level-2 `RTE` restored A7 exactly; the level-1 frame simply re-occupies
the same bytes.

**Trap for the next session:** a naive first pass reported "A7 OFF BY -8" on
5 of 6 trials. That was **entirely an artifact of the nested level-1 interrupt
my own halting provoked** — the `-8` is one pending timer frame, not corruption.
Any A7 measurement on this board must decode the frame actually present at `A7`
and check its vector, not assume the stack is bare.

This is consistent with the CCR hypothesis, which predicts A7 diverges only
later, at the mispredicted `Bcc`. It does not by itself confirm it.

---

## 6. Boot-outcome distribution

See §8 — **4/4 Happy Mac, 0/4 Sad Mac, 0/4 reached Finder**, all parked in the
ROM SCSI poll region. Measurements (A) and (B) were taken on an **already-running** machine
parked in the ROM SCSI poll/drain region (`pc_live` cycling
`0x40898d5e`–`0x40898fa6`) — i.e. the region the failure was originally
correlated with — not on a fresh boot.

---

## 7. What was NOT run

* **No Vivado build.** The mutex (`/var/tmp/m68k-ooo-vivado.lock`) was held
  throughout; two queued synth gates were left alone. The Part 135 fix was
  therefore **not** built or tested in a bitstream.
* **No SD-card write, no ROM patch.** The card remains calibration-fix-only.
* **`reset-and-break-pc` was never used** (documented to hang this bitstream's
  REPL hard). Only plain `reset`.
* **Vector 2 was never armed** (the ROM provokes ~15 deliberate bus errors on
  this path). Neither were line-F or A-line.
* **`irq-inject` does not work as a latched IRQ here.** `irq-inject 5` with
  `halt-exc-mask 0x1d` armed produced **no** vector-29 entry, on a running CPU.
  The planned deterministic forcing of the window via injection was abandoned;
  the pending-timer construction of §3 replaced it. Worth a proper look — the
  CSR write at `OFF_IRQ_INJECT` (0x020) appears not to latch.
* **No fix verification.** Whether the Part 135 patch changes the boot rate is
  untested; that needs a bitstream.
* The `exc_count` field was ignored throughout (known to lie); `exc-ring` and
  `pc-trace` were used for liveness.

---

## 8. Boot-outcome distribution (measured)

4 cold boots via plain `reset` (all halt sources disarmed first — the debug
reset domain makes masks survive the reset, which would otherwise halt a
healthy boot).

| boot | `pc_live` (8 samples) | screen | outcome |
|---|---|---|---|
| 1 | 8× `0x40899706` | Happy Mac | parked in ROM SCSI poll region |
| 2 | 7× `0x40898ea8`, 1× `0x40898eae` | Happy Mac | parked in ROM SCSI poll region |
| 3 | 8× `0x40899706` | Happy Mac | parked in ROM SCSI poll region |
| 4 | 8× `0x40899706` | Happy Mac | parked in ROM SCSI poll region |

**Distribution: 4/4 Happy Mac, 0/4 Sad Mac, 0/4 reached Finder.** Re-sampled
several minutes after boot 4: still `0x40899706`, still Happy Mac — so this is a
stable park, not a slow boot.

This does **not** match the "~50% Sad Mac" premise. Two caveats before anyone
reads a trend into it:

* n=4 is small, and the settle was 140 s against a ~180 s boot, so boots 1–4
  were first sampled early (the post-hoc re-sample confirms the park, though).
* **All four JPEG snapshots are byte-identical** (`md5 2f5de0f4…`), including
  one captured mid-boot. That is consistent with "Happy Mac appears early and
  never changes", but it does **not** exclude a stale/cached MJPEG frame. The
  screen evidence should be treated as weaker than the `pc_live` evidence.

The failure mode seen here is the Part 133 park — waiting on the 53C96 — not
the vector-3 address error at `0x408999E2`. **No Sad Mac was reproduced this
session, so no boot in this set exercised the SP-2-low failure.**

---

## 9. Next steps

1. **Build a bitstream with the Part 135 fix** (`ExceptionUnit.scala` `R_REDIR`:
   `obsSetCcr5Valid := True; obsSetCcr5 := popSr(4 downto 0)`) and re-run the §3
   construction. Under the fix, in-window pairs must show CCR **preserved**.
   That is now a *falsifiable, cheap* board test with a known-good instrument.
2. Re-run §3 for more in-window pairs to move off n=2. The yield is limited by
   how often the release lands in the window rather than in nested preemption;
   arming **only** the second vector after capturing frame A is what makes it
   work — arming both vectors at once never produces the window.
3. Investigate why `irq-inject` does not latch; a working injection would make
   the window deterministic instead of opportunistic.
