# Exception Subsystem (slice 1: precise fault delivery) — Design

**Status:** Implemented. Later interrupt, debug, cache-maintenance, and ISA-completion
slices extend this original synchronous-exception foundation.
**Date:** 2026-06-03
**Parent spec:** `2026-05-31-m68k-040-ooo-architecture-design.md` (invariant #1 precise retire; ch 8 commit). Reuses the ROB **commit-time recovery** machinery (the registered `doFlush`/redirect + pointer squash from the branch slice [[branch-handling-direction]]). Unblocks real page-fault handling for the MMU slice. [[ls-cluster]].

## 1. Purpose

Deliver **precise exceptions**: at the retirement of a faulted instruction, squash all younger work, switch to supervisor mode, stack a 68040 exception frame, fetch the handler vector, and redirect fetch — then return via RTE. This is what turns the MMU's *fault flag* into an actual page-fault that runs a handler, and likewise delivers illegal-instruction / privilege / trap exceptions. Precise delivery reuses the existing commit-time flush/redirect (an exception is "a mispredict that also stacks a frame and vectors").

## 2. Scope

**In (exception slice 1 — synchronous faults):**
- **Architectural system state:** the SR **system byte** (S supervisor, I2–I0 interrupt mask, T1–T0 trace), the **VBR** (vector base register), and the **A7 bank split** (USP vs supervisor SP). Exceptions are rare + serializing, so these are **simple committed registers** (updated at commit), NOT renamed — no PRF/RAT cost. (The CCR user byte stays the existing NZVC+X RFs.)
- **Fault capture in the ROB:** each entry carries `{faulted, vectorNum, faultPc, faultInfo}`, set at execute (MMU access fault) or decode (illegal, privilege violation, or an unimplemented line-A/line-F opcode). Mirrors the branch `mispredictStore`. Unimplemented line-A and line-F opcodes use their architectural emulator vectors 10 and 11, not generic illegal-instruction vector 4.
- **Commit-side exception sequencer:** at retire of a `faulted` head entry → assert the registered redirect (reuse `doFlush`) to squash younger + drain older, AND run a **hardware exception FSM** that, non-speculatively at commit: (1) sets S=1 (enter supervisor), saves the old SR; (2) **stacks a frame** to the supervisor SP — a minimal 68040 format (e.g. format $0: SR, PC) or format $7 (access-fault) for MMU faults — as a sequence of stores through the LS/memory path; (3) **fetches the vector** `mem[VBR + vectorNum*4]` (a load); (4) redirects fetch to the vector target. Reuses the SQ/commit-drain + LS path (already built) for the stores/load.
- **RTE** (return from exception): restore SR + PC (+ pop the frame) and redirect back — a commit-side sequence; minimal frame-format support to match what slice-1 stacks.
- **Precise guarantee:** the faulting instruction does NOT commit its own result (the fault replaces its retire); all older instructions have committed; all younger are squashed (pointer reset) — exactly the branch-recovery invariant.
- Verification: lock-step / directed — an illegal instruction or MMU page fault raises, stacks the correct frame, vectors to a handler in the behavioral memory, the handler runs, RTE returns; vs Musashi (which models 68040 exception frames). Synth gate ≥250 MHz.

**Out (later slices):**
- **Interrupts** (asynchronous — autovector/IACK, raised between instructions at commit rather than tied to a faulted entry).
- **Trace** (T-bit single-step), full trap-family coverage (TRAP#n/TRAPV/CHK/DIV0 detail), bus-error format $B/$C nuances, double-fault/halt.
- The exception FSM as **microcode** (slice 1 uses a dedicated hardware FSM; folding into the microcode engine is a later unification when that engine exists).

## 3. Components & dataflow

```
ROB entry: {faulted, vectorNum, faultPc, ...}  (set at execute/decode)
retire(head) faulted ─▶ commit-time recovery (reuse doFlush): squash younger, drain older
                  └▶ ExceptionFSM (non-speculative, at commit):
                       S_old = SR; SR.S := 1
                       push frame to (supervisor SP) : stores via LS path  (SP -= frameBytes; write SR/PC/format)
                       vec = load mem[VBR + vectorNum*4]
                       redirect fetch -> vec ; (handler runs in supervisor)
RTE ─▶ ExceptionFSM-restore: pop frame -> SR, PC ; redirect -> PC
```

### 3.1 System-state registers (committed)
`SR.sysByte` (S/I/T), `VBR`, `USP`/`SSP` (A7 selected by S). Updated only at commit (exceptions + RTE + privileged moves). A7 reads/writes in the datapath select USP vs SSP by the *committed* S bit (rare to change mid-flight; serialize on S-changing ops). Architectural, not renamed.

### 3.2 Exception FSM
A commit-side FSM owning the stacking stores + vector load through the existing LS/SQ/commit-drain path (the stores are non-speculative — they ARE the committed effect of taking the exception). Bounded multi-cycle; commit pauses (it's a serializing event). Reuses the registered redirect for the fetch retarget.

### 3.3 Fault sources
- Decode: illegal opcode, privilege violation (privileged op in user mode), and unimplemented line-A / line-F opcodes. All 4096 line-A opwords route to vector 10. A line-F opword not claimed by an implemented integer/MMU/cache instruction routes to vector 11. These emulator traps are restartable and therefore stack the faulting instruction PC.
- Execute: MMU access fault (from the MMU slice's fault flag), address error, divide-by-zero (when DIV lands), TRAP/TRAPV/CHK (when those decode).

## 4. Verification
- **Directed (×2):** an illegal instruction at a known PC → frame stacked at SSP with correct SR/PC, vector fetched from VBR table, fetch redirected to the handler; RTE pops + returns; supervisor/user A7 switch.
- **Lock-step:** programs that fault (illegal, then handler+RTE; MMU page fault once the MMU slice lands) vs Musashi (68040 frame formats as oracle). ×2.
- **Decode/frontend partition:** exhaustively prove all 4096 line-A opwords reach vector 10 and every unimplemented line-F opword reaches vector 11. Predecode frames emulator traps as one word while retaining the implemented line-F multiword carve-outs (`0xF27F` at four words and `0xF620..0xF627` at two).
- **Synth gate:** the exception FSM is commit-side + latency (not a comb cloud); the per-ROB-entry fault fields are small; confirm ≥250 MHz, report WNS + critical path.

## 5. Implemented decisions
- Format $0 handles ordinary synchronous exceptions; access faults use the implemented format-$7 path.
- The implemented stack model includes USP/ISP/MSP selection and later interrupt work extends the same committed system-state owner.
- System-state-changing instructions serialize through the exception/system path.
- Illegal instruction, line-A, line-F, privilege, access, address, trap, CHK, and divide faults all converge on precise ROB retirement delivery.
