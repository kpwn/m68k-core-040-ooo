# ITLB — Instruction-side MMU (translation + fetch-fault delivery) — Design

**Status:** Draft (approved direction: separate walker, translation + I-fetch-fault delivery)
**Date:** 2026-06-05
**Parent specs:** `2026-06-03-mmu-tlb-walker-design.md` (the `Tlb`/`TableWalker` components + DTLB) + `2026-06-03-exception-subsystem-design.md` / `2026-06-04-mmu-fault-delivery-design.md` (format-$7 access-fault delivery). [[ls-cluster]], [[exception-subsystem]].

## 1. Purpose

Give the instruction fetch path a real 68040 MMU: a banked ITLB + hardware table-walker behind the I-side `TranslationService` (replacing the identity stub), sharing the one 68040 MMU control (TC/URP) with the DTLB. A non-resident / exec-protected instruction page raises a precise **format-$7 access fault** (vector 2) delivered at the faulting instruction's retirement, reusing the merged exception machinery. Honest MMU-live synth ≥250; report the LUT/FF impact (a 2nd `Tlb`+`TableWalker`).

## 2. Scope

**In:**
- **`ItlbPlugin`** (mirrors `DtlbPlugin`): a `Tlb` + a **separate** `TableWalker` instance behind the I-side `TranslationService`, replacing `IdentityTranslationPlugin`. ITLB hit → 1-cycle ppn/perms; miss → the walker runs (the I-cache stalls on it, as it already stalls on a cache miss) → fill → resume fetch. Dedicated ITLB-walker AXI read port to the (shared) page table. Speculative ITLB fill allowed.
- **Shared MMU control:** extract `mmuEnable`/`rootPtr` into a small **`MmuControlService`** (one owner, driven by the synth-top registered input / sim poke) that BOTH `ItlbPlugin` and `DtlbPlugin` read — one 68040 MMU, identical I+D enable/root. (Refactors the DTLB's currently-inlined regs.)
- **I-fetch fault delivery:** a non-resident / exec-protect / supervisor instruction page → `xlate.rsp.fault` → the I-cache sets `DecodePacket.fault` (already wired) → decode produces `DecodedUop{faulted, faultVector=2, faultAddr=fetch PC, sswInstr=1}` → the ROB captures it → the commit-side exception FSM stacks the **format-$7** frame with the SSW **instruction** bit set → handler maps the page → RTE → re-fetch resumes. The faulting fetch's bytes are don't-care (the µop is a faulted placeholder delivering the exception at retire).
- **Oracle extension:** extend the MAME-040 oracle (in Musashi) to also fault on **instruction fetch** through the MMU and build the $7 frame with the SSW instruction bit — so the I-fetch-fault program lock-steps byte-for-byte (the D-side already does).
- **Verification:** lock-step (a) a program running from a NON-identity instruction mapping (ITLB miss→walk→fill exercised) and (b) a jump to a non-resident I-page → format-$7 → handler → RTE → resume, both vs the extended oracle. Honest MMU-live synth gate ≥250; **report the LUT/FF delta** vs the current 35.7k/22k.

**Out (later):** shared/arbitrated single walker for I+D (the area lever — deferred per the area discussion); exec-protect distinct from non-resident if it needs separate SSW encoding beyond what MAME models; 8KB pages; TTR; ITLB-specific PFLUSH variants.

## 3. Components & dataflow

```
I-fetch (activePc) -> ItlbPlugin (TranslationService):
   hit  -> {ppn, perms}; miss -> ITLB TableWalker (own AXI) -> fill -> resume (I-cache stalls)
   fault (non-resident/protect) -> xlate.rsp.fault
I-cache -> DecodePacket.fault=true on a faulting fetch (already wired)
decode -> DecodedUop{faulted, faultVector=2, faultAddr=fetchPC, sswInstr=1}
ROB capture -> commit-side exception FSM -> format-$7 frame (SSW instr bit) -> handler -> RTE -> resume
MmuControlService{mmuEnable, rootPtr} read by BOTH ItlbPlugin + DtlbPlugin (one MMU)
```

The exception FSM + $7 stacker are unchanged (just fed an instruction-fault SSW). The new RTL is the `ItlbPlugin` (≈ `DtlbPlugin` minus the U/M-write-on-store path — instruction fetch sets U but not M; keep a U-only deferred write or, since fetch is a read, just U) + the `MmuControlService` extraction + the decode `DecodePacket.fault → faulted` wiring (vector 2, sswInstr).

## 4. Verification
- **Directed:** ITLB hit/miss/walk/fill (`ItlbSpec`, mirrors `DtlbSpec`); a faulting fetch sets `DecodePacket.fault` → the µop is `faulted`/vector 2.
- **Lock-step (the gate):** (a) translated I-mapping program runs correctly; (b) non-resident-I-page → $7 (SSW instr) → handler → RTE → resume, vs the extended MAME-040 oracle, byte-for-byte on the frame + step-for-step on PC/SR/A7. ×2 deterministic. MMU-disabled identity unchanged (existing tests green).
- **Honest synth gate:** `mmuEnable`/`rootPtr` stay live (shared control, not const-folded); ITLB walker FSM inferred; WNS ≥ 0 (≥250); report WNS+FMAX+critical-path AND the **LUT/FF utilization delta**.

## 5. Open items
- U-bit deferred-write for instruction fetch (fetch sets the descriptor U bit): reuse the DTLB's U/M-write queue path (U-only) or a small I-side equivalent — confirm vs the MAME oracle (does it set U on I-fetch? match it).
- ITLB-walker AXI: dedicated port vs sharing the I-cache AXI — dedicated for simplicity (area lever = the deferred shared walker).
- Whether `MmuControlService` is a new tiny plugin or the DtlbPlugin exposes the regs as a service — pick the lower-churn form in the plan.
