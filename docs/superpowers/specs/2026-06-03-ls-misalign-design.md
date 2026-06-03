# LS Cluster — Misaligned / Line- & Page-Crossing Accesses — Design

**Status:** Draft for review
**Date:** 2026-06-03
**Parent spec:** `2026-05-31-m68k-040-ooo-architecture-design.md` ch 7.1.1 (misaligned/cross accesses — user-approved direction). Builds on the merged **LS cluster slice 1** (`LsEuPlugin`/`DcachePlugin`/`StoreQueue`, spec `2026-06-03-ls-cluster-1-design.md`) and **decode-matrix-2** (memory-EA cracking — provides the load/store µops to exercise). [[ls-cluster]].

## 1. Purpose

m68k (020+) permits misaligned word/long accesses, so a single memory access can split across a 16-byte L1D line and/or a 4 KB page. LS-1 assumed aligned-only. This slice adds the **two-access-slot** memory µop so a split access completes **in one µop** (no replay-to-microcode): optimistic single access when aligned, a bounded second access when it crosses.

## 2. Scope

**In:**
- **AGU cross-detection:** `crossLine = lineOffset + sizeBytes > lineBytes (16)`; `crossPage = pageOffset(va[11:0]) + sizeBytes > 4096`. A cross sets `twoAccess`. (Aligned/no-cross is the fast path, unchanged from LS-1.)
- **Two access slots** on the memory µop / LS EU: slot A = the low bytes (addr1), slot B = the high bytes (addr2 = next line / next page base). The LS EU performs A then B (bounded fixed extra latency; the existing variable-latency dynamic-completion handles it — completion fires when both halves are done).
- **Loads:** read both line slices, byte-merge the `size` bytes spanning the boundary into the single destination register (big-endian order in the byte-lane layer).
- **Stores:** the SQ holds **both halves** of a split store (two sub-entries or one entry with two {addr,bytes,strobe} slots) and **drains them atomically at commit** — both or neither.
- **Two translations on page-cross:** slot A and slot B each issue a `DTranslationService` request (slot B for the next page). Under the current **identity** stub both always succeed; the two-request structure is built so the real-DTLB slice slots precise page-fault-on-either-half in without restructuring.
- **Line-cross (same page):** reuse **one** translation for both slots (page unchanged); only the L1D index/tag differ.
- Verification: Musashi lock-step over misaligned + line-crossing load/store programs; directed LS-EU/L1D/SQ split-access tests; synth gate ≥250 MHz.

**Out (later slices, structure preserved):**
- **Precise page-fault on the second half** (requires the real DTLB+faults slice — identity never faults; this slice builds the two-translation path but cannot raise a fault yet).
- >2-way splits (a single m68k access spans at most two 16-byte lines / two pages — two slots suffice).
- Misaligned access to non-cacheable/MMIO pages (the MMIO slice).

## 3. Components & dataflow

```
AGU (S0): va = base + disp ; sizeBytes from size
          crossLine = va[3:0] + sizeBytes > 16
          crossPage = va[11:0] + sizeBytes > 4096 ; twoAccess = crossLine || crossPage
          addrA = va ; addrB = (va & ~15) + 16   (next line base; = next page base when crossPage)
          xlate req A (vpn=va[31:12]) ; if crossPage: xlate req B (vpn=addrB[31:12]) else reuse A
LS EU:    accessA (L1D/SQ at addrA) ; if twoAccess: accessB (at addrB)
   load:  merge sizeBytes from {A.lineData, B.lineData} at the split offset -> dst (byte-lane)
   store: SQ entry carries {addrA, bytesA, strobeA} + (twoAccess) {addrB, bytesB, strobeB}
          forward check covers both slots ; commit-drain emits both, atomically, in order
completion: fires when A (and B if twoAccess) are both resolved (variable latency; dynamic wakeup)
```

### 3.1 LS EU two-access sequencing
LS-1's load FSM (wait-on-refill) extends to a two-step sequence: issue access A; if `twoAccess`, issue access B (each can independently hit/miss-refill the L1D); the load result is the merged bytes; completion/dynamic-wakeup fires after the last access resolves. The EU stays occupied (`issue.ready` low) across the two accesses (consistent with LS-1's conservative non-pipelined LS pipe). Fast path (no cross) is one access, unchanged.

### 3.2 Byte-lane merge (split)
The byte-lane layer (LS-1) gains a split-merge: for a load, `size` bytes are gathered from `lineA[offsetA..]` ++ `lineB[0..]` at the boundary; for a store, the `size` data bytes are split into `{strobeA bytes in lineA, strobeB bytes in lineB}`. Big-endian order preserved. Aligned path = all bytes in slot A (strobeB=0).

### 3.3 Store queue two-slot atomic drain
An SQ entry gains an optional second slot. Forwarding checks a younger load against BOTH slots of every older store. Commit-drain writes slot A then slot B (both `DStoreCmd`s) before popping — atomic at the architectural level (the store retires once; both halves drain on its commit). Flush-squash unchanged (pointer rollback drops both slots).

## 4. Verification
- **Directed (×2 deterministic):** aligned (no-cross, fast path unchanged); word at line-offset 15 (line-cross within page); long at offset 14 (line-cross); access at `va[11:0]=0xFFE` size 4 (page-cross, two identity translations); split store then split load-back (merge); split store commit-drain writes both halves to the behavioral memory; mispredict flush squashes a split store (neither half drains).
- **Musashi lock-step:** misaligned/line-crossing load/store programs vs Musashi (decode-matrix-2 supplies the µops); ×2.
- **Synth gate:** `GenFullCoreSynthVerilog` OOC ≥250 MHz; the two-access sequencing must not regress FMax (it's latency, not a new critical comb path); report WNS + critical path.

## 5. Open items
- SQ entry second-slot encoding (two sub-entries vs one wide entry) — finalize against the LS-1 SQ structure in the plan.
- Whether crossLine and crossPage share the second-access path entirely (identity translation makes them identical now; they diverge only when the DTLB slice adds per-half faults).
- Exact extra-latency cycle count for the second access (bounded; measured at implementation).
