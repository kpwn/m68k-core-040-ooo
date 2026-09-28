# Proposal: split the CPU's memory interfaces by CACHEABILITY — hot path direct to L2C, cold path arbitrated

**Status: PROPOSAL, owner-originated 2026-09-28. NOT ratified.** Filed because `AGENTS.md`
fixes architecture to the design doc. Supersedes the framing of
`2026-09-28-three-port-memory-interface-proposal.md`, which asked the same question in terms
of port COUNT; this asks it in terms of the right SPLITTING RULE, which is the better frame.

Owner's statement, across three messages:

> *"crossbar should be fixed or replaced with some crossbar that can deliver perf, or we
> keep cacheable straight to L2C and xbar only for inhibited accesses"*
> *"the ideal design is both on i and d side: ranges marked as cacheable go to the direct
> L2C path, everything else goes to arbitrated axi port which we could down the line convert
> to a 68040-style bus (since it will be used as coldpath)"*
> *"axi port will be also able to access L2C albeit at a slower pace"*

## The architecture

| | HOT path | COLD path |
|---|---|---|
| carries | cacheable accesses (COPYBACK / WRITETHROUGH) | INHIBITED, MMIO, everything else |
| route | **direct dedicated L2C port**, bypassing `axi_xbar.v` | arbitrated AXI -> `axi_xbar.v` -> L2C `s_axi_*` |
| wants | bandwidth, many outstanding, reordering | strict ordering, correct transfer semantics |
| both sides | I **and** D | I **and** D |
| reaches L2C? | yes, fast | **YES — just slower** |

## ⭐ The property that makes this safe: BOTH paths reach the same memory

The cold path already terminates at the L2's `s_axi_*` today. So this change is **purely
additive** — nothing loses reachability, and **a mis-routed access is SLOW, not WRONG**.
That turns the cacheability demux from a correctness decision into a performance one, which
is a very different risk profile from a static address split where a mis-decode is fatal.

**The one exception is ORDERING**, and it is the whole risk surface — see below.

## Precedent: the I side already does the hot half, and it ships

- `fpga_top_ddr.vh:141` — I-fetch uses a *"dedicated `f_axi_*` port … never through
  `axi_xbar.v`"* (bind `:275`, task #269). `l2c_ctrl.v:419` agrees from the other side.
- `l2c.v` exposes exactly three interfaces: `s_axi_*` (read **+ write**, from the crossbar),
  **`f_axi_*` (READ-ONLY — `ar`/`r`, no `aw`)**, and `m_axi_*` (downstream to DDR).
- ⛔ **So `f_axi_*` cannot be reused for D**: no write channels, and D does writebacks. The D
  hot path needs a **fourth interface, `d_axi_*`, read+write**.
- ⚠️ **The failure mode is already on record.** `l2c_ctrl.v:411-423`: the fetch-side AR
  tracker was originally single-burst-in-flight and *"re-serialised the CPU's 5-MSHR
  instruction prefetcher down to one fetch in flight"*, fixed 2026-09-02 with a one-entry
  pre-latch — *"the single limiter between a 5-deep prefetcher above and 8 MSHRs / an
  8-outstanding MIG bridge below."* A new `d_axi_*` will do the same if its tracker is
  single-burst. Note the subtlety recorded there: the slot frees when beats are walked into
  the L2, **not** when data returns — it throttles issue RATE, not concurrency.

## Why this beats reworking the crossbar

1. It does not touch the crossbar's **hand-written round-robin picker**, which
   `axi_xbar.v:70-80` flags as *"load-bearing, hand-written … not parameterized over width"*.
2. **Inhibited accesses are memory barriers BY DESIGN** (`GOAL.txt` keeps them so), so
   serialising them through a single-outstanding crossbar is **correct**, not a limitation.
   The crossbar leaves the performance path entirely.
3. It makes the L2's **8 MSHRs + up to 4 same-line secondary merges each (32 in flight)**
   reachable. The D side delivers **1** today — MLP measured at exactly 1.000 mean / 1 max
   at every injected miss rate to 100%.
4. The crossbar keeps **1 spare read slot and 2 spare write slots** (`NW=4, NR=4`, only 2
   write and 3 read live), so the cold path has headroom without an arbiter resize.

## The demux is by MMU CACHE MODE, not by static address range

"Ranges marked cacheable" is, on this architecture, the **MMU's CM field** — `CM[6:5]` in a
page descriptor (`MmuDesc.pgCacheMode`) or a TTR (`MmuTypes.cacheMode(ttr)`), decoded to
`CacheMode` = COPYBACK / WRITETHROUGH / INHIBITED. So the routing decision is **dynamic and
post-translation**, not an address decode. The D-cache already carries `cacheMode` on every
command (`DcacheTypes.scala:93/112/127`), so the information is present — but the demux is
**late**, and that has timing implications worth costing before building.

## Open questions — these are the work, not the port itself

1. **Cross-path ordering.** An inhibited access is a barrier; a cacheable access on the hot
   path must not slip past one on the cold path. The core enforces inhibited ordering
   internally today, but that becomes load-bearing **across two physical paths**. ⚠️ This
   core has a documented *"config LATCHED at one point, RE-DERIVED live at another"* MMU
   defect family — **four defects, one root cause, all giving a clean one-hot hit with no
   fault**. This is exactly that shape. **Verify, do not reason.**
2. **CM changes.** A page's cacheability can change. What flush/quiesce rule makes the
   in-flight split safe across such a change?
3. **The D-side analogue of `iFetchCanReachMmio = false`.** That guard exists *because*
   I-fetch bypasses the crossbar, which holds the only `0x5000_0000` decode. The D side
   genuinely needs MMIO, so it cannot take that route — work out the equivalent invariant.
4. **Boot / pre-MMU.** Before `TC.E` and the TTRs are armed, what is the routing default?
   (See the TC.E-arm entry in the MMU defect family.)
5. **Does the I side need a cold path at all? — ANSWERED: yes, but for DeclROMs, and NOT
   out of the box.** (Owner, 2026-09-28: *"it would be good to provide a cold path for
   declroms to I side but it may not be needed out of the box."*)

   NuBus declaration ROMs contain `sExec` blocks the Slot Manager **executes**, in
   uncacheable slot space — which is exactly what `iFetchCanReachMmio = false` forbids.

   ⭐ **The key insight is why that guard exists.** `ifetch_window_guard.v` was added
   2026-09-09 because **the hot path has NO address decode**: `l2c` forwards
   `f_axi_araddr` with no window check and the DDR side serves `addr mod 64 MB`, so a ROM
   slot-scan fetch to NuBus `0xFB0493AA` returned memory-test filler **and the CPU executed
   it** — the root of the odd-SSP / store-at-0 / vector-table crash family
   ([[ifetch-window-guard-fix-2026-09-09]]). The guard converts out-of-window fetches to a
   local DECERR, and its own note records the intent: *"the ROM's slot scan now takes bus
   errors in the first seconds (a real Q700 does), no execution in slot space."*

   **The crossbar HAS the decode the hot path lacks.** So an I-side cold path does not
   weaken the guard — it makes the decision **per-slot instead of blanket**: an empty slot
   still DECERRs (correct, and what real hardware does), while a slot with a card present
   becomes fetchable.

   ⛔ **Therefore: build it when there is a consumer, not before.** With no NuBus cards
   emulated, every slot fetch *should* DECERR, which the guard already does, more cheaply
   and with no new port. The I cold path is an **enabler for emulated cards with DeclROMs**,
   not a fix for anything currently broken — and `iFetchCanReachMmio` must flip in the same
   change, never independently.

## Future: the cold path as a 68040-style bus

Once the cold path carries only device traffic, converting it from AXI to a **native
68040 bus** becomes attractive for a *recreation* project specifically: MMIO is where the
68040's bus protocol is architecturally observable, it is low-bandwidth so protocol overhead
is irrelevant, and inhibited accesses are already strictly ordered. **Explicitly out of
scope here** — recorded so the split is designed without foreclosing it.
