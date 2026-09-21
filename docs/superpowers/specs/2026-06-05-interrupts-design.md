# Interrupt subsystem (autovector + vectored, faithful IACK) — Design

**Status:** ACTIVE (slice B — implementing now, 2026-06-05). Model: both autovector + vectored. **Protocol: SIMPLE** (combinational `iackAvec`/`iackVector` inputs the SoC drives) — the faithful IACK bus-cycle state machine (multi-cycle handshake) is POSTPONED (user's call: soft hardware only, no real 68k IACK peripheral, so the SoC just presents avec/vector for the active level). No new exc-FSM `I_ACK` state — `curVec` is computed combinationally at interrupt entry.
**Date:** 2026-06-05
**Parent:** the exception subsystem ([[exception-subsystem]]) — interrupts reuse the commit-side entry FSM + format-$0 delivery + RTE.

## 1. Purpose

Add asynchronous interrupt delivery: an external interrupt-priority-level (IPL) raises a precise interrupt taken BETWEEN instructions when `IPL > SR I-mask` (or NMI level 7), delivering a format-$0 frame via the existing commit-side exception FSM. Vector source is both **autovector** (24+level) and **vectored** (a faithful 68k interrupt-acknowledge bus cycle returns the vector). Lock-stepped vs Musashi (which has `set_irq`/`set_interrupt_ack_response`/`cb_int_ack` + `run_until_sentinel_or_pc_with_irq_events`).

## 2. Scope

**In:**
- **IPL input:** a 3-bit `iplIn` into the core (0=none, 1-7=level, 7=NMI). In `FullCoreSynth` a registered OOC input (like `mmuEnableIn`); a real input on the core. Sampled at the commit stage.
- **Recognition (precise, macro-instruction boundary):** raise `interruptPending` when ALL hold: (a) `iplIn > srSys[2:0]` (the SR I-mask) OR `iplIn == 7` (NMI always); (b) the ROB head is the **first µop of a macro-instruction** (do NOT interrupt mid-cracked-instruction — use the existing instruction-boundary / first-µop marker); (c) the head is NOT faulted (an instruction's own fault/trap takes priority — 68k group order); (d) `excIdle` (the exc-FSM is not already running). The head instruction does NOT commit; its instruction PC is the stacked PC; it (and younger) are flushed and re-execute after RTE.
- **Simple vector protocol (soft hardware only — NO bus handshake):** core inputs `iackAvec` (in — autovector mode for the active level) and `iackVector` (in, 8b — the vectored vector when `!iackAvec`), driven by the SoC for the current `iplIn` level. On taking an interrupt the vector is selected combinationally: `curVec = iackAvec ? (24 + level) : iackVector` — NO multi-cycle IACK cycle, NO `iackValid`/`iackReady` handshake. (Optionally `iackLevel` out for the SoC to mux its avec/vector per level, but no handshake.) In `FullCoreSynth` these are registered OOC inputs. The exc-FSM needs no extra IACK state — it computes `curVec` at the interrupt-entry and proceeds straight to the format-$0 stack states.
- **Delivery (reuse the exc-FSM):** the captured `curVec` drives the existing format-$0 entry (stack {SR, PC=head instr PC, vec<<2}, vector to `VBR + curVec*4`, flush via RedirectService). **NEW in the entry SR-write:** set the I-mask to the interrupt level — `newSys = ((srSys | 0x20) & 0x3f)` becomes `... with bits[2:0] := level` (currently the entry only sets S=1 / clears T1T0; interrupts must also raise the mask so equal/lower interrupts are blocked until RTE). NMI (7) sets mask=7. RTE restores the old SR (mask included) — already works.
- **Priority:** a faulted/RTE head (its own exception) takes priority over an interrupt at the same boundary (recognition gates on head-not-faulted). NMI (7) is always recognized regardless of mask. Trace is out of scope here.
- **Verification:** lock-step vs Musashi — schedule IRQ events at PCs; the harness raises `iplIn` + models the IACK (returns avec or `set_interrupt_ack_response`'s vector to match `cb_int_ack`); both core + oracle take the same vector at the same boundary; handler → RTE → resume; PC/SR(mask)/A7 + frame match step-for-step. Autovector AND vectored programs; a masked interrupt (IPL ≤ mask) NOT taken; NMI taken through a mask; nested (higher level preempts handler). Honest MMU-live synth ≥250 (the new IPL compare + IACK state are at commit, off the D-cache cone; should not regress the ~257).

**Out (later):** trace-vs-interrupt interaction (vector 9 trace); the spurious-interrupt vector (24) on a bus-error-during-IACK; interrupt-during-exception-frame-write edge cases beyond the excIdle gate; per-level autovector configuration registers (the environment drives avec/vector).

## 3. Components & dataflow

```
iplIn -> (commit) recognize: ipl>mask||ipl==7, head=first-uop-of-instr, !faulted, excIdle
       -> interruptPending (async, between instructions)
vector (combinational, simple protocol): curVec = iackAvec ? (24+level) : iackVector
exc-FSM entry: stack format-$0 {SR, PC=head instr PC, curVec<<2}; SR := S=1,T=0, I-mask:=level
             ; vector to VBR+curVec*4; flush head+younger (RedirectService)
handler runs (mask now >= level -> same/lower IRQ held); RTE -> restore SR(mask), PC -> resume
```

## 4. Verification
- **Directed:** recognition gate (ipl>mask vs ≤mask vs NMI; first-µop-of-instruction only; not-faulted priority); the IACK handshake (avec → 24+level; vectored → iackVector); the SR I-mask update on entry.
- **Lock-step (the gate):** vs Musashi via `run_until_sentinel_or_pc_with_irq_events` — autovector IRQ → handler → RTE → resume; vectored IRQ (set_interrupt_ack_response) ; masked IRQ not taken; NMI through mask; a higher-level IRQ preempting a handler (nested). Commit PC/SR(incl. mask)/A7 + $0 frame step-for-step, ×2.
- **Honest MMU-live synth ≥250** (mmuEnable live, TLBs+walkers inferred); the IPL-compare/IACK is commit-side, off the D-cache cone — confirm ~257 non-regress. Report WNS+FMAX.

## 5. Open items
- The macro-instruction-boundary marker for recognition: reuse the cracker's first-µop / instruction-PC field (find it; the ROB has per-entry PC for fault stacking — confirm it's the instruction PC, not a µop PC).
- `interruptPending` vs the existing `exceptionPending`/`faultRetire` into the FSM entry: both feed `entryTrigger` — interrupt is a 2nd async source; encode the entry kind (fault vs interrupt) so the FSM applies the I-mask-update + the simple-protocol vector select only for interrupts.
- Lock-step vector modeling: the harness drives `iackAvec`/`iackVector` to match what it told Musashi (`cb_int_ack`: autovector default / `set_interrupt_ack_response`); no handshake to model (simple protocol).
- `iplIn` sampling/synchronization: sample at commit; a registered input (no async metastability concern in lock-step; a real SoC would add a synchronizer — note but out of scope).

## 6. Retirement-boundary preservation (2026-09-21 amendment)

An unmasked level-sensitive request, or the existing latched level-7 NMI,
must not lose every recognition opportunity because a multi-uop retirement
group straddles instruction boundaries. While either request is active, finish
the macro already being retired, but do not retire a younger macro in the same
cycle. Its first uop must remain available as ROB head for the existing precise
recognition gate on a subsequent cycle. This is a combinational cold-path
retirement limit, not a new exception-entry path or a second pending-IRQ latch.

Apply the limit to all ordinary retirement widths. Prepared bulk publication
is conservatively aborted/disabled while the request is active; ordinary
retirement may still finish the current macro. No architectural map may publish
updates beyond the actual retired prefix. Masked requests do not limit retire.

Keep recognition's existing fault/trace/RTE/system/debug priorities and
precise-store/inhibited-load busy gates. The boundary limit does not recognize
an IRQ while a device operation is in flight, shorten required draining, or
change frame contents. With no active request, retirement and IPC are unchanged.

Verify the observed odd-SSP LINK boundary-33 failure with its exact register,
CCR, frame and memory comparisons unchanged; cover multi-uop straddling,
masked IRQs, NMI, precise memory and wider/prepared retirement separately.
