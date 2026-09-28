# `D3-BURST` (L1D line 16 B → 64 B) — feasibility assessment

Requested as an **assessment, not an implementation**. Naming per the plan's GC9:
this is **`D3-BURST`** (decision D4, §5.2, §9 slice row "D3"), never bare "D3" —
**`D3-SET`** is the unrelated one-outstanding-fill-per-set invariant.

Verified against the working tree, not the plan.

---

## Q3 first: is scheduling it a spec deviation? **No.**

It is decision **D4** of the ratified design proposal ("Widen the L1D line from 16 B
to 64 B … Treat this as *higher priority than MSHR count*; a 16-byte line is the real
anomaly") and slice row "D3" of its §9. §11.Q2 dropped it **on sequencing**:
"D3 (bursting to 64 B) is DROPPED from this near-term order per Q2 — the L1D line
stays 16 bytes for now; copyback P4 is unblocked and may proceed without waiting on
this design." Re-scheduling a slice the design doc contains is a sequencing change,
which AGENTS.md's "architecture is fixed by the design doc" does not restrict. **No
spec update needed to schedule it.** (A spec update *would* be needed to change the
target size, or to adopt sub-line dirty sectors — see §2.3.)

## Q1: has copyback **P4** landed? **YES. The "cheap now, expensive after P4" window has CLOSED.**

`DcachePlugin.scala` carries live, commented implementations of **P4.1 through P4.6**
plus P5.4/P5.5. Every element the design doc named as baking the line size in is
present and load-bearing:

| P4 element | Present as |
|---|---|
| per-line dirty bits | `dirtysMem` (per-way register array), `dirtysWrEn/Set/Data`, `dirtysVoteD1/D2` exclusivity tripwires |
| eviction writeback | the `EVICT_WR` FSM state (61 references), `victimEvictTag`, AXI id `D_EVICT` |
| store-side write-allocate | `pendingStoreMiss`/`pendingStorePaddr`/`pendingMergeData`/`pendingMergeStrb`, REPLAY's merge branch, `storeAllocAckReg` |
| async diagnostic fault | `diagFaultPulse`/`Addr`/`Resp`/`Kind` (32 references), `diagFaultKind1Fires` |
| CPUSH/CINV flush engine (P5) | the maintenance FSM, `maintBusyReg`, `wbAddrReg` |

So the design doc's timing argument no longer applies: this is now a widening
**through** landed copyback machinery, and the dirty/eviction granularity questions
below are real work rather than avoided work.

## Q2: what it would cost

### 2.1 The geometry itself is free, and cheaper than expected

```
lineBytes=16  4-way 8 KiB -> sets=128  offBits=4 setBits=7 tagBits=21   off+set=11
lineBytes=64  4-way 8 KiB -> sets= 32  offBits=6 setBits=5 tagBits=21   off+set=11
```

- **VIPT-safe at constant capacity** (`off+set = 11 ≤ 12` for 4 KB pages, unchanged).
- **`tagBits` is UNCHANGED at 21**, and `offBits+setBits` is unchanged at 11. That
  kills a risk I expected to find: the maintenance engine's page-scope predicate
  hardcodes bit positions derived from "with lineBytes=16 and sets=128 the tag is
  paddr[31:11]" (`pageHiMatch = rdTag(31 downto 2) === target(31 downto 13)`,
  `pageLoMatch = rdTag(1) === target(12)`). Because the tag is *still* paddr[31:11],
  **that arithmetic needs no change.**
- `CacheGeometry` already requires and computes all of this; `requireViptSafe` guards it.

### 2.2 The edit surface is wide but mechanical

- **17 sites** hardcode `128 bits` in `DcachePlugin.scala`; **5** in `LsEuPlugin.scala`;
  `DcacheTypes.scala` has `DLoadRsp.line = Bits(128 bits)` and `lineData = Bits(128 bits)`.
- Several byte-merge loops hardcode 16: `Vec(Bits(8 bits), 16)` at the store-S3 merge
  (`:1640`) and the write-allocate merge (`:2812`). Others are already
  `1 << offBits`-parametric (`:2712`, `:4370`) — a good sign, but the inconsistency
  means every one must be checked, not swept.
- `dataMem` must become per-beat-written (4 × 128-bit beats into one line). **The
  I-cache already does exactly this pattern**, so it is a port, not an invention.

### 2.3 The two real design decisions, not edits

1. **Dirty granularity → 16× write amplification.** One dirty bit per 64 B line means
   a 4-byte store dirties and later writes back 64 bytes. The design doc accepted this;
   it is worth re-deciding, because the alternative (4 dirty bits per line, one per
   16-byte sector) is cheap in flops and removes the amplification *and* the CINVL
   hazard below. **Sub-line dirty sectors are NOT in the ratified design** — adopting
   them would need a spec update.
2. **⚠ `CINVL` BECOMES DATA-DESTROYING WHERE IT IS NOT TODAY. This is the top
   conformance risk and it is software-visible.** The maintenance engine matches by
   *physical address* (`lineMatch`, then invalidate/push), which is the right shape —
   but the 68040's architectural cache line is **16 bytes**, so `CINVL` on address A is
   specified to discard exactly the 16 bytes containing A. At a 64-byte line it would
   discard 64, dropping up to **48 bytes of dirty data the programmer never asked to
   discard** (CINV discards dirty data by design; that is what distinguishes it from
   CPUSH). Software doing DMA-buffer invalidation at architectural granularity would
   silently lose neighbouring stores. `CPUSHL` has the mirror problem but is *safe* —
   over-pushing writes back correct data and costs only time. Mitigations, in
   preference order: (a) per-16-byte dirty sectors so `CINVL` drops only the addressed
   sector — needs the §2.3(1) spec update; (b) make `CINVL` on a dirty 64 B line
   push-then-invalidate, safe but architecturally divergent. **Neither is free, and
   this must be settled before any RTL.**

### 2.4 Critical-word-first becomes MANDATORY, and it now interacts with what I just landed

The implementation plan already warns: "D1.2's fill-forward extracts the response from
**the beat that carries `r.last`**. At `len = 0` that is the only beat and therefore
always the demanded one. **At `len = 3` it would be the LAST beat, which is usually the
wrong one**". Slice **D1.2 is now landed** (`fillForward`), so this is a live
dependency rather than a hypothetical: **`D3-BURST` must add critical-word selection to
the fill-forward path before it can widen the line, or every miss returns the wrong
bytes.** The comment saying so is in `DcachePlugin.fillForward`'s doc block, where a
future implementer will read it. The same applies to the `inhibitedResp` path.

### 2.5 The SQ `sameLine` stall coarsens 4×

`StoreQueue.scala` stalls a load if any older in-flight store touches the same line
(`sameLineA`/`sameLineB`). At 64 bytes that window is 4× wider on store-then-load-nearby
patterns — which is every stack frame. The design doc names this "the main technical
risk of D4" and prefers merging undrained SQ stores into the refilled line. **That
mitigation is part of the slice, not an optional follow-up.**

### 2.6 `MOVE16`

Architecturally a **16-byte** line move, currently microcoded into 4 LONG transfers
(`Microcode.scala`, "rows 242..251 … 4 LONG transfers"). Widening the L1D line does not
break it, but it stops being a whole-line operation — which matters for the
allocate-without-fetch lever (see `DcachePlugin`'s write-allocate comment), because at a
64 B line a one-store `MOVE16` would cover only a quarter of a line and could no longer
reach full-line coverage on its own. **So `MOVE16`-as-one-store and `D3-BURST` pull in
opposite directions on that specific lever, and the cheaper bandwidth win should be
taken first.**

---

## Why the bus arithmetic nevertheless says `D3-BURST` is the big one

At 128-bit AXI = 16 B/cycle, and a copy touching every byte twice, copy bandwidth is
capped at 0.8 GB/s at 100 MHz. Sustaining 16 B/cycle at a ~16-cycle L2 hit needs
`MLP = 16 × 16 / lineBytes`:

| line | MLP needed | available |
|---|---|---|
| 16 B | **16** | `AxiIds.dRefill` reserves **4** — infeasible |
| 64 B | **4** | exactly the 4 reserved |

The AXI ID pool was sized for 64-byte lines. **64 B lines and 4 MSHRs are the pair that
saturates the bus; neither alone gets close** — D2 at 16-byte lines tops out near
4 B/cycle with all four MSHRs busy. That is the quantitative form of the design doc's
"a 16-byte line is the real anomaly".

## Recommendation

Schedule `D3-BURST` as its own piece of work, **after** these two, both of which are
cheaper and neither of which it blocks:

1. **D1.2 fill-forward** — landed here; measured **+4.35%/+4.46% streaming bandwidth**
   (two seeds), 48.81 → 46.77 cycles per 16-byte line.
2. **Allocate-without-fetch on a fully-overwritten line** — removes one of the copy's
   three serialised trips, ~+49% on the same bench, with direct in-tree precedent in
   the L2 (`l2c_ctrl.v:171-185`, "FULL-LINE WRITE, NO FILL", whose own measurement
   calls the fill "a 4x tax on streaming writes"). Scoped in `DcachePlugin`'s
   write-allocate comment; needs either a write-combining buffer or a one-store MOVE16.

And settle §2.3(2) — the `CINVL` granularity hazard — **before** any `D3-BURST` RTL. It
is the one item in this assessment that is a correctness question rather than a cost.
