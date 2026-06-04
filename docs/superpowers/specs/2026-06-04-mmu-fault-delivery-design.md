# MMU Page-Fault Delivery (format-$7 access-fault frame) — Design

**Status:** Draft for review (fast-follow of the exception subsystem + MMU)
**Date:** 2026-06-04
**Parent specs:** `2026-06-03-exception-subsystem-design.md` (format-$0 delivery, the commit-side exception FSM, SR/VBR/USP-SSP, RTE) + `2026-06-03-mmu-tlb-walker-design.md` (the DTLB flags `rsp.fault`). [[exception-subsystem]], [[ls-cluster]].

## 1. Purpose

Turn the MMU's *flagged* faults into *delivered* page faults: an MMU access fault (non-resident page / write-protect / supervisor violation from the DTLB) raises a **68040 access-fault exception (vector 2)** via the **format-$7** stack frame, runs a handler, and RTEs. This is the combination that makes demand paging real. Reuses the exception subsystem's precise commit-time delivery; the new work is the format-$7 frame (vs format-$0) and the access-fault info (SSW + fault address).

## 2. Scope

**In:**
- **Fault capture:** the LS-EU access that takes a DTLB `rsp.fault` flags its ROB entry as faulted with **vector 2** + the **fault address** (the VA that faulted) + access attributes (read/write, size, supervisor) needed for the SSW. (The exception slice already captures `{faulted, vectorNum, faultPc}`; extend with `faultAddr` + SSW attrs for access faults.)
- **Format-$7 frame:** when the faulting vector is the access-fault type, the commit-side exception FSM stacks the 68040 **format-$7** access-fault frame (vs the 4-word format-$0). Match the **MAME m68040 model** (`thirdparty/mame/.../m68000-sdp.cpp`) frame builder byte-for-byte: SR, PC, format/vector word (`0x7<<12 | vec*4`), **SSW** (special status word — R/W, size, fault class; build from the captured attrs + the model's `m_base_ssw` bits SSW_R/SSW_N/etc.), the **fault address**, and the remaining internal fields (effective address, output/input buffers, write-back status) — set to the values the MAME 040 model produces for a simple (no-pending-writeback) data access fault.
- **RTE from format-$7:** the 68040 RTE inspects the stacked format and, for $7, restores SR/PC and continues (match the MAME model's RTE-from-$7 behavior; in the common case the handler has fixed the mapping and execution resumes). If the model re-runs the faulted access, match that.
- **Verification:** lock-step — a load/store to a **non-resident** page (a page table with a missing/invalid descriptor) faults → access-fault delivered → format-$7 frame stacked (compared vs MAME 040) → a handler maps the page (writes a valid descriptor) → RTE → the access succeeds → program continues; vs the MAME 040 oracle step-for-step (PC/SR/A7/fault-frame). MMU-live **honest** synth gate ≥250 (mmuEnable a live input — no const-fold).

**Out (later):** bus errors from real external memory (vs MMU faults); the full write-back-pending frame fields (our core has no deferred copyback at bring-up → those fields are the no-pending values); double access fault; instruction-side (ITLB) faults; misaligned-fault interactions beyond what the existing two-access path covers.

## 3. Components & dataflow

```
LS-EU access -> DTLB rsp.fault (non-resident/WP/super) -> flag ROB entry:
   {faulted, vector=2 (access fault), faultPc, faultAddr=VA, sswAttrs={rw,size,super}}
retire(faulted head) -> reuse the exception FSM (slice-1): squash younger (doFlush),
   S:=1, bank A7->SSP, then stack FORMAT $7 (vs $0):
     [SSP-..] = SR, PC, (0x7<<12|vec*4), SSW, faultAddr, <internal fields = MAME-040 values>
   fetch vec = mem[VBR + 2*4]; redirect to the handler (supervisor).
RTE -> read frame format ($7) -> restore SR/PC/A7 (and re-run/continue per MAME 040).
```

The exception FSM (slice 1) already drives the D-cache store/load for the frame + vector and reuses `doFlush`; this slice adds the **$7 frame layout** (more words, the SSW + fault-addr fields) selected by the fault vector, and the **$7-aware RTE**.

## 4. Verification
- **Directed:** a DTLB-faulting access flags the ROB entry with vector 2 + faultAddr; the FSM stacks a $7 frame whose SR/PC/SSW/faultAddr match expectations.
- **Lock-step (the gate):** non-resident-page fault → handler fixes the descriptor → RTE → resume, vs the MAME 040 model (it builds the same $7 frame + RTEs). The committed PC/SR/A7 and the stacked frame match step-for-step. ×2 deterministic.
- **Honest synth gate:** `mmuEnable`/`rootPtr` stay live OOC inputs (no const-fold); WNS ≥ 0 (≥250); report WNS+FMAX+critical path. The wider $7 frame is commit-side serializing latency (not a comb cloud).

## 5. Open items
- Exact SSW bit layout + the format-$7 internal-field values for the MAME 040 model (the implementer decodes `m68000-sdp.cpp`); if a field is microarchitecture-specific and can't be matched, document it + assert the architecturally-meaningful subset (SR/PC/SSW/faultAddr) — do not fake a match.
- RTE-from-$7 resume semantics (continue vs re-run the access) — match the MAME model; pick the handler test accordingly.
- Whether the fault address is the VA or PA in the frame (68040 stacks the logical/effective address — confirm vs the model).
