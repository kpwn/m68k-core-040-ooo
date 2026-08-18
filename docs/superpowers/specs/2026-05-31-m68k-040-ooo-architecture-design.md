# m68k-core-040-ooo — Architecture Design

**Status:** Historical baseline; the day-1 "FPU deferred" decision below was later reversed —
the FPU is implemented and merged (see `docs/superpowers/specs/2026-08-14-fpu-fpsp-design.md`)
**Date:** 2026-05-31
**Supersedes:** the `m68k-ooo` / `m68k-ooo-v2` / `m68k-ooo-v3` prototypes
**Sibling reference:** `m68k-core-030-inorder` (faithful in-order 68030; source of the
verification harness, the engineering process, and the worktree-pool tooling reused here)

---

## 0. Purpose and bar

A from-scratch out-of-order, superscalar Motorola 68000–68040 core that is simultaneously:

- **Correct / faithful** — trusted against real software (Classic MacOS System 6/7) via
  instruction-level lock-step against a reference model.
- **Timing-clean** — closes **250 MHz** on the target FPGA (200 MHz floor), by design, not by luck.
- **Maintainable / elegant** — composable plugins with single-owner interfaces, so the design
  stays in-head and keeps absorbing future optimizations.
- **High-IPC** — modern OoO internals (rename, clustered OoO issue, optimistic loads) behind a
  precise 68k instruction boundary.

The previous OoO prototypes had the right framework (SpinalHDL FiberPlugin) but grew breadth-first
into a 79-file tangle that could not close timing or be trusted, which forced the in-order 030
reset. This core keeps the framework **and** the discipline the 030 reset taught us, and is mapped
down in full **before** RTL (deliberate spec-down-then-implement, per the project owner).

---

## 1. Fixed envelope

| Dimension | Decision |
|---|---|
| Target device | Xilinx XCKU5P UltraScale+, speed grade −2 |
| FMax | **250 MHz goal, 200 MHz floor** (hard design constraint, budgeted per chapter) |
| ISA | M68000 → **M68040** integer + MMU, day-1. Full 020+ addressing/branch formats. |
| FPU | **Deferred**, but FP0–FP7 rename slots + ROB/cluster hooks reserved day-1 |
| Width | **2-wide** decode / rename / retire; **wider parallel issue** across clusters |
| Software target | Primary **Classic MacOS (System 6/7)**; secondary Amiga/Atari, NetBSD/Linux 68k |
| Cache coherence | **Software** (CINV / CPUSH); no hardware I/D snooping |
| Backbone | **NaxRiscv-style SpinalHDL Fiber + Database plugin framework** |

---

## 2. Cross-cutting invariants

Every plugin/subsystem obeys these. They are the guardrails that keep the core elegant, fast, and
correct. Violations are review-blocking.

1. **One precise retire boundary, at instruction granularity.** Architectural state changes *only*
   at commit. The ROB is keyed by **instruction**, never by µop. µops produce only internal state
   (EA, load data, ALU result, flags, store descriptor) and never architecturally write back. An
   instruction commits when `outstanding_uops == 0`. Exceptions / interrupts / CINV / CPUSH / SR
   writes serialize here.

2. **250 MHz FMax discipline (FPGA-shaped, not aspirational).** Codified rules:
   - **No global completion broadcast.** Fixed-latency producers use static latency tokens
     (`ready_cycle = issue_cycle + latency`); only variable-latency *replay events* cause targeted
     recovery activity.
   - **No global wakeup network / no monolithic issue CAM.** Clustered wakeup domains (ch 5) with
     local select + limited, *registered* inter-cluster forwarding.
   - **Many small CAMs, banked structures, registered boundaries.** No large priority encoders, no
     wide bypass crossbars, no fully-associative ROB/TLB searches.
   - **Async LUTRAM / FF reads on every hot path** (PRF, RAT, dependency state, ROB status, TLB
     tags). No BRAM read latency on a cycle-critical path.
   - **Trade latency for critical path.** Extra pipeline stages are acceptable; deep combinational
     chains are not. Every chapter states its hot path and how it stays within budget.

3. **Plugin boundary discipline — the anti-tangle rule.** Plugins communicate *only* through
   (a) typed **Services** resolved at elaboration and (b) named **Database payload keys**. No plugin
   reaches into another's internals. **Every Database key has exactly one documented producer
   plugin.** A key registry (ch 4 / appendix B) lists every key, its producer, and its consumers.

4. **The µop bundle and the instruction/ROB entry are the keystone contracts** (ch 4). They are
   defined once and everything negotiates around them. The µop is *internal scheduling only*; the
   instruction is the *architectural unit*.

5. **Observability is a first-class interface.** A fixed **commit-trace port** (retire PC, opword,
   arch-register writes, CCR writes, memory writes, exception vector) exists from commit #1. The
   lock-step harness is a designed-in consumer, not bolted on. The compare granularity *is* the
   instruction retire boundary — the same granularity the reference model ticks at.

6. **Everything sized parametrically** (appendix A) so IPC / area / FMax sweep without
   rearchitecting.

---

## 3. Pipeline overview

```
            ┌────────────────────────── FRONTEND ──────────────────────────┐
 IF1  PC select, I-cache + ITLB request (VIPT: index from page offset)
 IF2  fetch response, align 8B bundle into instruction window
 PD   length predecode (parallel, all start positions) + rich metadata
 DEC  2-wide decode → µop expansion (simple direct; complex via microcode ROM)
            └───────────────────────────────────────────────────────────────┘
 REN  dual-RAM RAT lookup/alloc (int + NZVC + X), freelist, intra-group hazards
 DIS  ROB alloc (instruction-level), steer µops to clusters
            ┌────────────── CLUSTERS (local wakeup/select) ─────────────────┐
 ISS  INT cluster │ EA cluster │ Load-Store cluster │ Complex/microcode cluster
 EX   ALU/shift/mul/div │ complex-mode AGU │ AGU+DTLB+D$+SQ │ microcode seq
 VAL  (LS only) hit/translation/permission/forwarding validation → replay or commit-ok
            └───────────────────────────────────────────────────────────────┘
 CMT  instruction-level retire (2-wide), RAT/CCR/PC update, physreg release,
      store-queue drain, precise exception/interrupt boundary
```

Mispredict/exception flush: dual-RAM RAT location bits reset to *committed* in 1 cycle; ROB tail
snaps to checkpoint; re-fetch begins. Load replay does **not** flush — see ch 5/6.

---

## 4. Chapter #0 — Keystone contracts

### 4.1 Renamed architectural resources

| Resource | Shape | Renamed? | Notes |
|---|---|---|---|
| D0–D7, A0–A7 | 16 × 32b | yes | A7 = active SP. USP/SSP/ISP/MSP bank switches are **serialized at commit**, not renamed. |
| NZVC | 4b | yes (own PRF) | Partial writers take previous physical NZVC as implicit source (see 4.6). |
| X | 1b | yes (own PRF) | Written only by arithmetic + `ADDX`/`SUBX`/`NEGX`/extend-rotate family. |
| FP0–FP7 | 8 × 80b | **slots reserved**, not built | Rename namespace + ROB/cluster hooks allocated day-1 so the FPU drops in later. |

Non-renamed special state — SR system byte, VBR, CACR, SFC/DFC, MMU registers (TC, TTRs, root
pointers, MMUSR) — is read/written only by **serializing** instructions at the commit boundary.

### 4.2 Physical register files (all async LUTRAM/FF read)

- **Int PRF:** `PHYS_INT_REGS` (baseline **48**) × 32b.
- **NZVC-PRF:** `PHYS_NZVC_REGS` (baseline **16**) × 4b.
- **X-PRF:** `PHYS_X_REGS` (baseline **16**) × 1b.
- Separate freelists per PRF. Release at commit using the ROB's old-mapping fields.

### 4.3 Dual-RAM RAT

Two parallel RATs (speculative + committed) over {16 int, NZVC, X, 8 FP-reserved = 26 entries}.
One **location bit** per resource selects which RAM is current. Flush = reset location bits to
*committed* in **1 cycle** (O(1)); no ROB walk for RAT restore. The ROB still stores old mappings —
but only to release physregs at commit, not for recovery.

### 4.4 The µop bundle (internal scheduling unit — never commits architecturally)

```
robId          instruction this µop belongs to
uopIdx,lastUop position within instruction; lastUop drives the completion counter
cluster        {INT | EA | LS | CPLX} steering target
opClass        ALU op / shift / mul / div / agu / load / store / branch / microcode step
staticLatency  latency-class token (4.7); drives ready_cycle, no broadcast
psrc0,psrc1    int source physregs + per-source ready bits
pNZVC,pX       flag source physregs + valid/ready bits (only if this µop reads flags)
pdst           int dest physreg (+ archDst id for RAT/ROB write)
pNZVC',pX'     flag dest physregs + write masks (only if this µop writes flags)
size           B | W | L
imm/disp       immediate or displacement
eaSpec         complex-mode address spec (EA cluster only)
memOp          none | load | store
twoAccess      second access active — misaligned line/page crossing (see 7.1.1)
sqPtr0,sqPtr1  store-queue slot(s); sqPtr1 valid only when twoAccess.
               misaligned loads merge both read slices into the single int pdst
mayTrap        carries predecode may-trap hint for scheduling/retire
```

### 4.5 The instruction / ROB entry (the architectural unit — the lock-step compare point)

```
valid
pc, predNextPc, length
type, simple/complex, retireAlone, serialize
{archDst, newPdst, oldPdst} × {int, NZVC, X}     mappings for commit + physreg release
ccrWriteMask                                     which of {X,N,Z,V,C} this insn writes
sqPtrRange                                       store-queue slots owned by this insn
outstandingUops  OR  singleUop bit               completion bookkeeping (4.5.1)
completeState
excState {vector, faultAddr, stackFormat, ssw}
branchResolved, mispredicted
```

**4.5.1 Single-µop fast path.** Most instructions (`ADD`, `SUB`, `MOVE`, `CMP`, `TST`, `MOVEQ`,
logicals, simple shifts) are single-µop: `singleUop=1`, `instruction_complete = uop_complete` — the
`outstanding_uops` counter is bypassed entirely, keeping the completion path off the critical path.
The counter is used only for MOVEM / CAS(2) / bitfield / MOVES / CHK2/CMP2 / microcoded / FPU.

### 4.6 CCR / flags model (baseline; lazy-flag tokens are a future optimization)

- **Split rename:** NZVC is one renamed resource, X another. A `Bcc` waiting on NZVC does not depend
  on an X-only writer (e.g. `ROL`), and an `ADDX` chain does not depend on an NZVC-only writer.
- **Flags travel with the producing µop** (result + flags + write mask); there are no separate
  CCR-update µops. Commit writes architectural CCR.
- **Partial writes take the previous physical group as an implicit source** (read-modify-write):
  - `BTST/BSET/BCLR/BCHG` write **only Z** → take prev pNZVC as source.
  - `ADDX/SUBX/NEGX` compute **Z = prevZ AND (result==0)** → read prev pNZVC.
  - X-write set **excludes** `CMP`, `MOVE`, `ROL`, `ROR`, `TST`, logicals.
- **Lazy flags (future):** producers emit a flag *token*; `Bcc/Scc/DBcc` consume it directly; CCR is
  materialized only for `MOVE SR/CCR`, exceptions, `RTE`. Reserved, not built day-1.

### 4.7 Static latency classes (baseline, parametric)

| Class | Latency | Notes |
|---|---|---|
| ALU, logical | 1 | |
| Shift (barrel) | 1 | |
| EA (complex) | 1 | |
| Branch resolve | 1 | |
| MUL | 7 datapath stages, **fully pipelined**, II=1 | two DSP input levels plus a registered multiply and four post-multiply levels; one shared DSP48 chain; completion is elastically arbitrated on the CPLX result port |
| DIV | fixed cycle-count (data-independent) | iterative but always N cycles; div0/overflow via ROB |
| Load (L1 + TLB hit) | fixed (~2–3) | optimistic; miss → replay (ch 6), no broadcast |
| Store | addr+data at execute; write at commit | translation at execute is replayable |

`ready_cycle = issue_cycle + staticLatency` for producers using the INT static
wakeup network.  MUL is the deliberate exception: its arithmetic latency is fixed,
but the one area-efficient DSP chain shares CPLX's single PRF/ROB result port with
CHK/CMP2 and the iterative divider.  It therefore uses the existing dynamic CPLX
wakeup after shallow result arbitration.  This keeps the multiplier II=1 without
adding an IQ port, a PRF write port, or a ROB completion port.

### 4.8 Cluster steering and inter-cluster forwarding

- **INT:** ALU, logical, shift, MOVEQ, condition evaluation (`Scc/DBcc/Bcc` test).
- **EA:** **complex** address generation only — `(d8,An,Xn*scale)`, full 020+ indexed/memory-indirect
  modes, PC-relative-indexed. **Simple modes collapse into the LS µop** (see ch 6).
- **LS:** load/store µops, own base+displacement AGU, store queue, store→load forwarding, D-cache +
  DTLB, replay validation.
- **CPLX:** the single shared DSP multiplier, iterative divider, CHK/CHK2/CMP2,
  microcoded expansions (MOVEM, CAS/CAS2, bitfield, MOVES), MMU ops, cache
  control, privileged/serializing ops.  MUL and DIV share issue/result wiring but
  do not share occupancy: fixed-latency MUL may remain II=1 while DIV iterates.

**Inter-cluster forwarding (limited, registered — accept +1 cycle):**
- `LS load-data → INT/EA` (primary path: a loaded value feeding compute/address).
- `EA → LS` (complex-mode effective address into the load/store µop — *not* used for simple modes).
- Flags stay **INT-local**. No global bypass crossbar.

**Hot-path budget:** wakeup fan-out is bounded *per cluster* (small local queues), so wider issue
stays FMax-safe. Cross-cluster hops are pipelined, never combinational.

---

## 5. Chapter #1–#3 — Frontend, decode, rename

### 5.1 Frontend (ch #1)

- **Fetch:** 8 bytes (4 words) per cycle from L1I; 24-byte instruction buffer (LUTRAM shift) presents
  a stable ≥32-byte window so two max-length instructions are always available.
- **L1I:** VIPT, **16 KiB, 4-way, 64-byte lines** (geometry in ch 7); ITLB banked.
- **Length predecode (PD):** combinational length scanner over all candidate start positions in
  parallel (length is a function of the opword + EA-mode bits + extension-word count). Produces the
  full **predecode metadata** set: `length, simple/complex, branchType, call/return, usesMem,
  writesMem, mayTrap, ccrReadMask, ccrWriteMask, eaClass`.
- **Branch prediction:** BTB (BRAM, async read at fetch s0, partial tag), GShare BHT (2-bit
  counters), RAS (FF stack, push BSR/JSR, pop RTS/RTR). DBcc handled by the BHT naturally.
- **Loop-stream detector:** µop FIFO; on a backward branch whose target is in-buffer, replay from the
  FIFO, bypassing fetch/predecode/decode. Flushed on mispredict / CINV.IC / CPUSH.IC / context flush.

### 5.2 Decode & µop expansion (ch #2)

- **Fast path** (`MOVE/ADD/SUB/AND/OR/EOR/CMP/MOVEQ/LEA/PEA/branches/DBcc/Scc/simple shifts`):
  decoded directly into 1–4 µops, packetized, issued through the normal backend.
- **Complex path** (020+ full indexed, memory-indirect, CAS/CAS2, bitfield, MOVES, CHK2/CMP2, F-line,
  MMU instructions): **single decoder ownership**, expanded via a **microcode ROM** sequencer
  (1× BRAM, microword), emitting µops one per cycle. Complex may stop packetization. *Complex is not
  interpreted.*

### 5.3 Rename (ch #3)

- Dual-RAM RAT (4.3); freelists per PRF (4.2).
- **Intra-dispatch-group hazards** (2-wide): RAW — slot-1 sources compare against slot-0 dest, bypass
  to slot-0's new mapping; WAW — sequential RAT write ordering makes slot-1's mapping final.
- CCR rename is split per 4.6.

---

## 6. Chapter #4–#5 — Issue clusters and execute

### 6.1 Clustered scheduler (ch #4)

Four clusters (INT / EA / LS / CPLX), each with **local compacting issue queues**, **local
time-based wakeup** (static latency), **local select** (`OHMasking.first` over a dense array). No
global wakeup network. Inter-cluster forwarding per 4.8.

- **Compacting queues:** entries dense at the bottom; ≤2 shift/cycle; select is a 1-LUT-level
  priority encoder. Depths parametric (appendix A).
- **DependencyStorage:** PRF-indexed (busy bit + producing-ROB id) rather than searching queues on
  completion.
- **Load replay = issue-queue retention (no squash).** Consumers speculatively woken at expected
  L1-hit latency **stay in their cluster queues** (not deallocated) until the load is *validated*
  (hit + translation + permission + forwarding all OK). On a miss/fault/forward-failure they simply
  re-wait and re-fire when data arrives. No pipeline flush, no shadow tracking. Cost: queue entries
  held a few cycles longer.

### 6.2 Execute (ch #5)

- **INT:** ALU, barrel shifter, condition eval.
- **EA:** complex-mode address arithmetic only.
- **LS:** AGU (simple modes), DTLB, L1D, store queue, forwarding — see ch 7.
- **CPLX:** one **fully-pipelined II=1 MUL** DSP48 chain, a separately occupied
  fixed-cycle iterative DIV, CHK/CHK2/CMP2, microcode sequencer datapath, and
  serializing/privileged ops.  The fixed and iterative engines feed one shallow
  elastic result arbiter; div0/overflow raise via the normal ROB exception path.
- **FPU:** not built; rename/ROB/cluster slots reserved.

---

## 7. Chapter #6–#7 — LSU and MMU

### 7.1 Memory model (decided)

- **VIPT L1I + L1D: 16 KiB, 64 sets × 4 ways × 64-byte lines.** With 4 KiB pages: 12 offset bits =
  6 line-offset + 6 index → index stays within the page offset ⇒ **no page coloring required**.
- **Translate-at-execute, replayable (not translate-at-commit).** The LS µop computes its address and
  translates it (DTLB) at execute. TLB miss / page fault / protection failure / cache miss /
  store-forward failure all funnel through the **single unified replay machinery**. The scheduler sees
  only "fixed-latency load"; it never distinguishes TLB-hit from cache-hit. Resolved physical address
  + permissions are written into the load result / store-queue entry. *Rationale for not translating
  at commit:* commit must stay a fixed-latency, fault-free drain, and faults must be known before
  retire so they raise precisely with younger work already squashable.
- **Stores visible only at commit.** A store is issued carrying its VA *spec* (base+disp); the AGU
  computes the VA and the DTLB translates to **physical** at **execute**, allocating store-queue
  entry/entries holding the *physical* address. At **commit** the SQ drains to cache/L2 —
  **fixed-latency**, no re-translation (PFLUSH is serializing, so no in-flight translation goes stale).
- **Speculative translation, deferred descriptor writes.** A speculative **TLB fill** (page-table
  walk) is permitted — a TLB is just a cache. But the 68040 walk sets the descriptor **U/M
  (used/modified) bits in memory**, an architectural side effect, so those descriptor writes are
  **deferred to non-speculative execution (commit-side)**, consistent with stores-visible-at-commit.
- **L1D = write-through, write-no-allocate.**
  - Store **hits** L1D → update the line in place **and** write through to L2.
  - Store **misses** L1D → write only L2, leave L1D alone (no allocate).
  - ⇒ **L1D never holds a line newer than L2** ("never dirty"): eviction is a silent drop,
    `CPUSH.DC` is invalidate-only, **no copyback engine** at bring-up.
- **L2 = write-back** to main memory (the bandwidth filter).
- **Store→load forwarding:** younger loads check the store queue for address overlap and forward;
  A7-relative store→load is the hottest path and must resolve fast. Forward-failure = replay event.
- **Endianness:** handled in the **LS byte-lane layer at the cache interface** — load align/extend/
  swap and store format. The register file and datapath are **endian-neutral** (hold integer values);
  physical cache/AXI lanes are LE-friendly; the swap converts to/from big-endian memory order. No
  swap logic on the GP register ports.
- **Cache mode bits honored from day-1:** *non-cacheable / serialized* pages (MMIO) bypass L1D, and
  loads to them execute **non-speculatively (perform-at-retire)** — a speculative load from an I/O
  register with side effects is a correctness bug. Optimistic-load applies to cacheable memory only.

### 7.1.1 Misaligned / line- and page-crossing accesses

m68k (020+) permits misaligned word/long accesses, so a single access can split. A memory µop carries
**two access slots** to handle this in one µop — no replay-to-microcode:

- **Optimistic single access.** Assume aligned; the AGU computes whether the access crosses a
  boundary. No cross → one access, base fixed latency, `twoAccess=0`.
- **Line cross (same page):** two cache accesses, **one** translation reused. The second access is a
  bounded validation/replay event adding fixed extra latency.
- **Page cross:** two accesses to two pages → **two** translations, each independently TLB-missable /
  faultable. Either half faulting → precise exception (restartable).
- **Stores buffer both halves in the SQ and drain atomically at commit.** A page-crossing store that
  faults on the second page has drained *neither* half (precise, restartable) — a direct benefit of
  stores-visible-at-commit. Loads merge both read slices into the single destination register.

### 7.2 MMU (ch #7)

- **68040 MMU**: hardware table walk on TLB miss, TLB refill, permission validation. Page fault →
  precise exception at the faulting instruction's retirement.
- **Banked TLBs** (ITLB/DTLB): split into banks/ways selected by a VPN-bit subset, shallow associative
  lookup per bank — not one deep CAM.

### 7.3 Deferred (do not preclude)

Per-page copyback / write-allocate (real 68040 feature). Logged divergence: software relying on
copyback runs *correctly* under write-through (CPUSH sees nothing dirty); only the perf profile
differs. Structure (SQ + commit-drain) is unchanged when this is added later.

---

## 8. Chapter #8 — Commit and exceptions

- **Instruction-level, 2-wide retire.** Retire two if: head0 complete, head1 complete, no exception,
  no serialization, no pairing restriction; else retire one.
- **Retire-alone** instructions: branches (resolved), `RTE`, `STOP`, `RESET`, SR writes, MMU ops,
  cache control, large MOVEM, microcoded.
- **Commit performs:** RAT (committed) update, physreg release (old mappings), CCR write, store-queue
  commit (drain), PC update, exception-state update. **No µop-level architectural commit.**
- **Precise exceptions:** a faulted instruction blocks retirement; younger instructions stay
  speculative and are squashed. Exception entry builds the correct **m68k stack frame format** for
  the vector. A-trap (`TRAP #$A`) is the MacOS OS-call path → low-latency precise entry.
- **Serialization:** CINV/CPUSH (cache control, incl. LSD flush on I-cache ops), PFLUSH, SR/SSP bank
  switches, MOVES, and other privileged/serializing ops drain and execute at the retire boundary.

---

## 9. Chapter #9–#10 — Caches/bus and top/SoC

- **L1I / L1D:** ch 7 geometry; **separate AXI masters** (I-cache owns the instruction master, D-cache
  owns the data master — non-negotiable boundary inherited from the 030 ADR).
- **L2:** URAM-backed, **1 MiB** baseline (up to ~2 MiB), write-back to main memory.
- **Top / SoC:** AXI integration, clocking. **Reset fetches initial SSP and PC from vector-table
  entries 0 and 1** through the normal big-endian memory path (fixed `resetVector` only as a
  temporary bring-up aid, not the architectural model).
- Endianness boundary lives at the LSU (ch 7); top-level buses expose internal LE lanes only after
  that boundary.

---

## 10. Chapter #11 — Verification

- **Lock-step vs reference model (primary).** Co-simulate against **Musashi** (reused from
  `m68k-core-030-inorder/tools/musashi/`). Compare architectural state — D0–D7/A0–A7, CCR, PC, and
  memory writes — at **every retired instruction** via the commit-trace port (invariant #5). First
  mismatch pinpoints the bug. Compare granularity = instruction retire = ROB boundary.
- **Self-checking directed + random (secondary).** Curated directed tests with expected results, plus
  a randomized instruction-stream generator with self-checking, covering the m68k correctness
  checklist (ch 11).
- **Boot-real-software (acceptance).** Boot MacOS / diagnostics in sim as the coarse end-goal signal.

### 10.1 m68k correctness checklist (must be covered by directed/random + lock-step)

X-flag semantics · MOVE CCR/SR rules · MOVEA rules (sign-extension, no CCR) · address-register special
cases · odd-alignment behavior · 020+ indexed modes · 020+ branch formats · **MOVEM restartability** ·
TAS (atomic) · bitfield ops · CAS/CAS2 (atomic RMW) · F-line handling · MMU instructions · exception
stack frame formats · self-modifying code (CINV/CPUSH).

---

## 11. Engineering process & repo infrastructure

Ported from `m68k-core-030-inorder` (the discipline that made the reset trustworthy):

- **Fixed agent worktree pool** (`tools/agent_worktree_pool.sh`): a **fixed-size, slot-reused** pool
  (default 10) of detached worktrees at `…/m68k-core-040-ooo-worktrees`. `reserve` re-syncs an
  existing slot to `HEAD` rather than creating a worktree per task ⇒ **disk bounded at POOL_SIZE**
  regardless of task count (this is the HDD-safety property). `flock`-protected; `free` requires a
  clean worktree. Env: `M68K040_WORKTREE_POOL`, `M68K040_WORKTREE_POOL_SIZE`.
- **PM gate** (`tools/pm_gate.sh`): serialized required full-core verification owned by the PM.
- **Verification ownership:** engineer agents run `compile` / `test-fast` / focused specs in their
  reserved slot; the PM serializes `test-verilator`, program-sim suites, trace capture/analysis, and
  AXI/board integration. Verilator builds are global and expensive — run centrally.
- **Roles:** PM owns integration quality; engineer agents own bounded module slices and do not revert
  neighbors' work.

---

## Appendix A — Parametric sizing (baselines)

| Param | Baseline | Notes |
|---|---|---|
| `ROB_DEPTH` | 64 | power of 2 |
| `PHYS_INT_REGS` | 48 | 16 arch + ~32 in flight |
| `PHYS_NZVC_REGS` / `PHYS_X_REGS` | 16 / 16 | small |
| `INT_RS_DEPTH` / `EA_RS_DEPTH` / `MEM_RS_DEPTH` / `CPLX_RS_DEPTH` | 8 / 8 / 8 / 4 | compacting |
| `LOAD_Q_DEPTH` / `STORE_Q_DEPTH` | 8 / 8 | |
| `L1I_KB` / `L1D_KB` | 16 / 16 | VIPT, 4-way, 64B line, 64 sets |
| `L2_KB` | 1024 | URAM, write-back |
| `BTB_ENTRIES` | 256 | |
| `GSHARE_HISTORY_BITS` / `GSHARE_ENTRIES` | 16 / 2048 | |
| `ITLB`/`DTLB` ways × sets | 4 × 16 | banked |
| `DECODE_WIDTH` / `RETIRE_WIDTH` | 2 / 2 | issue is per-cluster, wider in aggregate |

## Appendix B — Database key registry & service catalog

*(To be enumerated as the first implementation-plan artifact: every Database payload key with its
single producer plugin and consumer list; every Service interface. This registry is the concrete
form of invariant #3 and must exist before backend plugins are written.)*

---

## Open items to resolve per-chapter (during each chapter's implementation plan)

- **Auto-inc/dec µop form:** baseline emits a separate address-update µop (preserve one-arch-dest-per-µop);
  dual-dest LS µop is a later optimization.
- **Reference model choice confirmation:** Musashi (baseline). Moira as alternative if cycle-level
  fidelity is later needed.
- **Inter-cluster forwarding latency** exact staging; **DependencyStorage** width.
- **Microcode ROM encoding** (microword fields, entry count).
- **Exception stack-frame format generation** table (per vector / per fault type).
- **L2 allocation policy** on L2 store miss.
- Appendix B contents (key registry / service catalog).

## Known divergences from a literal 68040 (logged, intentional, revisitable)

- Write-through/no-allocate L1D at baseline (copyback deferred) — behaviorally safe, perf differs.
- Software cache coherence only (no hw I/D snoop) — matches 68040 model.
- FPU deferred (slots reserved).
