# Memory-destination RMW (load-op-store crack) — Design

**Status:** Draft (feature-completion slice 7 — the foundational mem-dest unblock). User: "you pick / batch it, features first."
**Date:** 2026-06-07
**Parent:** [[ls-cluster]] (the store/load + SQ), [[decode-matrix-framework]] (the cracker), [[isa-completion-roadmap]].

## 1. Purpose

Enable **memory-destination** read-modify-write forms by cracking them into **load → op → store**. This unblocks everything deferred "to mem-RMW": ADD/SUB/AND/OR/EOR `Dn,<ea>`, line-0 immediates `#imm,<ea>`, ADDQ/SUBQ `#n,<ea>`, CLR/NEG/NEGX/NOT `<ea>`, and (later) bit-ops/shifts to memory. Lock-stepped vs Musashi. Gate: lock-step + OOC sanity.

## 2. Scope

**In (MEMSIMPLE destination EAs only: `(An)`, `(d16,An)`, `(xxx).W/.L` — NO side-effect modes):**
- **The RMW crack:** a memory-destination ALU/unary op cracks into **[load.sz `<ea>` → T0]** + **[ALU op: T0 (with Dn or #imm) → T1, set flags]** + **[store.sz T1 → `<ea>`]**. The EA is MEMSIMPLE (no `-(An)`/`(An)+` side effects, still deferred), so the load and store **independently recompute the same address** (same base/disp → same EA; no shared-address threading or double-side-effect hazard needed).
- **Op forms:** ADD/SUB/AND/OR/EOR `Dn,<ea>` (line 8/9/B(EOR)/C/D opmode 4/5/6 — the `isRmw` path currently illegal); immediates `#imm,<ea>` (line 0 ADDI/SUBI/ANDI/ORI/EORI/CMPI — CMPI-mem is load+compare, no store); ADDQ/SUBQ `#n,<ea>` (line 5); CLR/NEG/NEGX/NOT `<ea>` (line 4 unary, mem dest). Sizes .B/.W/.L.
- **No-store sub-cases:** **TST `<ea>`** = load + set flags (no store); **CMPI/CMP-mem source** already works (load source); **CLR** = store 0 (the 68040 does a dummy read then write — model as a plain store-0, or load+store-0; match Musashi's memory effect). Flags per the op (same as the register forms).
- **Ordering/forwarding:** the RMW load must observe the architectural memory value (the existing SQ store→load forwarding covers any prior pending store to the same address); the RMW store drains via the SQ as usual; the load→op→store µops carry the data dependency (T0→T1→store), so the OoO scheduler orders them correctly within the instruction. ONE instruction's load+store to the same address — confirm the SQ-forward + drain handle it (the store is younger than the load; no self-hazard since the load completes first via the T0 dependency).
- **Verification:** lock-step vs Musashi — each op to `(An)`/`(d16,An)`/abs, .B/.W/.L, with flag edges + the memory value correctly RMW'd; TST-mem (flags, no write); CLR-mem (zeroed). ALL existing UNCHANGED.

**Out:** `-(An)`/`(An)+`/indexed destination modes (need the general predec/postinc side-effect handling — a separate addressing-mode slice); TAS-mem (the indivisible atomic bus cycle — separate); bit-ops/shifts to memory (fast-follow once this crack exists); CAS/CAS2.

## 3. Components & dataflow

```
ADD Dn,(An)   : crack -> [load.sz (An) -> T0] [add T0,Dn -> T1 + NZVCX] [store.sz T1 -> (An)]
ADDI #x,(An)  : crack -> [load -> T0] [op T0,#x -> T1 + flags] [store T1 -> (An)]
CLR (An)      : -> [store.sz 0 -> (An)] (+ Z=1/N=0; 68040 dummy-read modelled as needed)
NEG/NOT (An)  : crack -> [load -> T0] [neg/not T0 -> T1 + flags] [store T1 -> (An)]
TST (An)      : -> [load.sz (An) -> T0] [flags from T0] (NO store)
(EA = MEMSIMPLE base+disp, recomputed identically by the load and the store -> same address)
```

## 4. Verification
- **Directed:** the crack (load→op→store µop sequence); the load+store hit the same EA; flags from the op; TST no-store; CLR zero.
- **Lock-step (the gate), ×2:** ADD/SUB/AND/OR/EOR Dn,(An)/(d16,An)/abs (.B/.W/.L, flag + carry/overflow edges); ADDI/SUBI/ANDI/ORI/EORI #imm,mem; ADDQ/SUBQ #n,mem; CLR/NEG/NEGX/NOT/TST mem — final memory value + NZVCX step-for-step vs Musashi. ALL existing UNCHANGED (ITLB seed flake → baseline-repro first).
- **`make test-fast`** + targeted verilator `-z` subsets.
- **OOC-synth sanity** (FMax-neutral; post-route non-deterministic — not gated): 0 err, no UNASSIGNED REGISTER. No new cross-module crossing.

## 5. Open items
- The crack mechanism: extend `MicroOpAssembler` (it already cracks memSimple SOURCE → load+op; the mem-DEST adds the trailing store). The op µop writes a TEMP (T1), the store µop reads T1 + recomputes the EA. Confirm AssembledUops width supports 3 µops (it went 2→3 for RTR/DIV64).
- EA recompute by the store: the store µop needs the same base/disp as the load (MEMSIMPLE — deterministic, no side effect). Thread the EA fields (base reg + disp) to the store µop, or have it carry the same MEMSIMPLE descriptor.
- Same-address load+store within one instruction: confirm the SQ-forward / single-outstanding LS pipe handle a store immediately following its producing load (the T0→T1→store dep serializes them; the store is to the just-loaded address). No self-forward hazard (load reads BEFORE the store allocates).
- CLR/NEG/NOT/NEGX-mem reuse the line-4 unary ALU ops on the loaded T0; the .B/.W partial-merge is NOT needed for memory (the store writes exactly `.sz` bytes).
- CMPI-mem / CMP-mem-source: load + compare, NO store (CMP writes no reg/mem) — likely already works via the memSimple source path; confirm.
