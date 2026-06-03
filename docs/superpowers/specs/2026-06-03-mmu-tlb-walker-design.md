# MMU / TLB / Hardware Table-Walker (slice 1: DTLB) — Design

**Status:** Draft for review (QUEUED — dispatch after decode-matrix-2 + misalign land)
**Date:** 2026-06-03
**Parent spec:** `2026-05-31-m68k-040-ooo-architecture-design.md` ch 7 (translate-at-execute, speculative TLB fill, deferred U/M descriptor writes, stores-visible-at-commit) + ch 7.2 (68040 MMU: HW table walk, TLB refill, permission validation, precise page-fault-at-retire; banked ITLB/DTLB). Replaces the `DIdentityTranslationPlugin` stub behind `DTranslationService`. Builds on the merged LS cluster + misalign slices. [[ls-cluster]].

## 1. Purpose

Replace identity translation with a real 68040 D-side MMU: a **banked set-associative DTLB** + a **hardware table-walker** that, on a TLB miss, walks the in-memory translation tables, validates permissions, and refills the TLB. Translate-at-execute; a TLB miss is a bounded variable-latency event funneled through the LS EU's existing dynamic-completion/stall path. U/M descriptor writes are deferred to commit (consistent with stores-visible-at-commit). **Fault detection** is in scope; **fault delivery** (precise exception raise + handler) is deferred to the (not-yet-existing) exception subsystem — this slice flags a faulting translation and the harness asserts the flag.

## 2. Scope

**In (MMU slice 1 — D-side):**
- **TLB component** (`Tlb`): banked, set-associative ATC (parametric: e.g. 32 entries, 4-way, banked by a VPN-bit subset — shallow per-bank lookup, NOT one deep CAM). Entry = `{valid, vpn-tag, ppn, perms(s/u, write-protect), cacheMode}`. Lookup is combinational/1-cycle; fill from the walker; invalidate-all (PFLUSH-style) by clearing valid.
- **Hardware table-walker** (`TableWalker`): on a DTLB miss, walk the 68040 3-level table for **4 KB pages** — VA = `[root idx (7) | pointer idx (7) | page idx (6) | offset (12)]`; root from a **URP/SRP root-pointer register**; read root→pointer→page descriptors via a dedicated AXI read port; extract PPN + permissions + cacheMode; fill the TLB. Multi-cycle FSM. Page-size parametric (4 KB first; 8 KB later).
- **Translate-at-execute + miss latency:** `DTranslationService` becomes TLB-backed. On hit → 1-cycle ppn/perms. On miss → the LS EU stalls (reuses the LS-1 `loadBusy`/variable-latency path) while the walker runs, then completes. **Speculative TLB fill is permitted** (a TLB is a cache).
- **U/M descriptor writes deferred to commit:** the walk sets the descriptor **U** (used) bit, and **M** (modified) on a write access — these are *memory writes* (architectural side effects), so they are **queued and performed at commit**, like a store (reuse the SQ/commit-drain discipline or a small descriptor-writeback queue). Not performed speculatively.
- **Permission/fault detection:** invalid descriptor / write-protect / supervisor violation → the translation response carries `fault` + fault info; the LS µop is flagged faulted. (No exception raise yet — see Out.)
- **Minimal control:** a root-pointer register (URP/SRP) + MMU-enable, driven by test pokes (MOVEC/MMU-register decode deferred). MMU-disabled = identity passthrough (keeps existing behavior).
- Verification: directed walker/TLB tests + lock-step with a real small page table in the behavioral memory (non-identity mapping; TLB miss→walk→fill→hit; write sets M at commit; permission fault flags). Synth gate ≥250 MHz.

**Out (later slices, structure preserved):**
- **Fault DELIVERY / precise exceptions** — needs the exception subsystem (vector fetch, SR/PC frame, handler dispatch, retire-time raise). This slice only *flags* faults. **This is the natural next big subsystem after the MMU.**
- **ITLB** (I-side) — fast-follow reusing `Tlb` + `TableWalker` behind the I-side `TranslationService`.
- 8 KB pages, transparent translation registers (TTR), full PFLUSH variants, copyback interaction.

## 3. Components & dataflow

```
LS EU translate req (vpn, write?) ─▶ DTLB lookup (banked, 1-cyc)
   hit  → {ppn, perms, cacheMode}; perm check → ok | fault-flag
   miss → TableWalker FSM: read root[VAroot] → ptr[VAptr] → page[VApage] descriptors (AXI)
            → perms/cacheMode/ppn → fill TLB → (retry lookup) → complete
            (speculative fill OK; U/M descriptor writes QUEUED for commit, not written now)
LS EU stalls (loadBusy-style) across a walk; dynamic completion fires when translation resolves.
ROB commit ─▶ drain queued U/M descriptor writes (+ the store, if any) — non-speculative.
mispredict flush ─▶ discard speculative U/M-write queue entries (TLB fills may stay — a cache).
```

### 3.1 Tlb
Banked set-associative: `bank = vpn[bankBits]`, set within bank = `vpn[setBits]`, way-compare the tag. Parametric `{entries, ways, banks}`. RegInit'd valid bits; fill writes one entry; lookup is a shallow per-bank way-mux (FPGA-friendly — no deep CAM). `DTranslationService` (and later the I-side) is implemented by a `Tlb` + walker plugin replacing the identity stub.

### 3.2 TableWalker
An FSM issuing 2–3 dependent AXI reads (root/pointer/page descriptors) from the root pointer + VA index slices, decoding the 68040 descriptor format (resident bit, PPN, WP, U/M, descriptor-type). On a non-resident/invalid descriptor → `fault`. Bounded latency (a few memory reads); shares or owns an AXI read port to the same backing memory.

### 3.3 U/M descriptor-write queue
A small queue of `{descAddr, newBits}` produced by a walk (set U; set M on write). Entries are speculative until the triggering µop commits; on commit they drain (RMW the descriptor byte in memory); on flush they're discarded. Mirrors the SQ commit-drain/flush discipline.

## 4. Verification
- **Directed (×2):** TLB hit; TLB miss → walker reads a hand-built 3-level table in the behavioral memory → fills → hit returns the correct non-identity PPN + perms; write-protect descriptor → fault flag; a write access queues an M-bit descriptor write that drains at commit (memory descriptor byte updated) and is discarded on flush; MMU-disabled → identity passthrough.
- **Lock-step:** load/store programs under a non-identity page table vs Musashi (Musashi MMU as oracle), translating to the same physical data; TLB-miss-then-hit sequences. ×2.
- **Synth gate:** `GenFullCoreSynthVerilog` OOC ≥250 MHz; the DTLB lookup (banked, shallow) must not be the critical path; the walker FSM is latency, not a comb cloud. Report WNS + critical path.

## 5. Open items
- TLB geometry defaults (entries/ways/banks) — parametric; tune at synth.
- Whether the walker shares the D-cache AXI port or owns a dedicated one (descriptor reads are uncached/cacheable? — 040 walks are cacheable; decide vs the L1D).
- U/M-write queue: standalone vs folded into the SQ.
- Root-pointer register source until MOVEC decode exists (test poke; later a real control-register path).
- Sequencing the dependency: **fault delivery requires the exception subsystem** — flag this as the next major slice after the MMU.
