# L1I prefetch on/off sweep — first execution, 2026-09-03

**Status:** MEASURED. This is the `prefetch ∈ {on, off}` sweep that
`docs/superpowers/specs/2026-05-31-icache-slice-design.md` §8.3 has required since the
slice landed. **No results existed anywhere in the repo before today.**

**Harness:** `src/test/scala/m68k040/bench/IpcBenchSpec.scala`
**Invocation:** `IPC_SEED=1 [IPC_MEM=l2] [IPC_PREFETCH=off] sbt "testOnly m68k040.bench.IpcBenchSpec"`
**Core:** `057a41b1` (cpu040, `feat/soc-fabric-concurrency`).

---

## 0. The methodology trap — read this before quoting any older frontend number

`IpcBenchSpec`'s **default memory model is zero-latency**, and the bench's own source
says why that is disqualifying here:

> `IPC_MEM=l2` … "Applied to BOTH the D-side and the I-side (an I-fetch refill is the
> dominant memory stall for these kernels, so an I-side zero-latency model would make
> the measurement **meaningless**)."

A prefetch on/off comparison under zero-latency memory measures almost nothing, because
there is no fill latency to hide. My own first run made exactly this mistake and showed a
0.6 % aggregate difference. Under the L2-faithful model the same comparison is **26.9 %**.

**Consequence for the existing literature.** `docs/superpowers/specs/2026-08-09-frontend-throughput-audit.md`
concludes that "the normal resident L1I path is also internally II=1" and that "the
dominant measured frontend loss is correctly predicted taken control flow". The first
clause is about *hits* and is not contradicted. The second is a claim about **dominance**,
and it is inconsistent with the numbers below unless it was measured with an I-side model
that hid fill cost. **Which memory model that audit used is not recorded and has not been
confirmed.** Do not treat its dominance claim and this sweep as jointly true without
settling that.

---

## 1. Aggregate result

L2-faithful model (`IPC_MEM=l2` → 5-cycle L2 hit / 70-cycle DDR), seed pinned:

| | prefetch ON | prefetch OFF | delta |
|---|---|---|---|
| aggregate IPC | **0.542** | **0.427** | **+26.9 %** |
| window cycles | 9,943 | 12,616 | −21.2 % |

**The next-line prefetcher is already load-bearing.** It is not a marginal feature.

For completeness, the same sweep under the (inappropriate) zero-latency model:
ON 0.669 / OFF 0.665 — a 0.6 % difference, i.e. the configuration that would have
concluded prefetch does nothing.

## 2. Per-kernel, L2-faithful

| kernel | ON cyc | OFF cyc | prefetch speedup |
|---|---|---|---|
| independent-ALU | 280 | 1,308 | **4.67×** |
| dependent-ALU | 401 | 1,224 | **3.05×** |
| mixed | 786 | 1,232 | 1.57× |
| hot-loop | 315 | 396 | 1.26× |
| branchy | 443 | 522 | 1.18× |
| shift-stream | 500 | 573 | 1.15× |
| shift-mixed | 467 | 533 | 1.14× |
| call-return | 2,970 | 3,063 | **1.03×** |
| load/store, load-stream, store-stream, same-line-copyback | — | — | ~1.00× (D-side bound) |

## 3. The residual — what prefetch does NOT hide

Comparing L2-faithful against ideal memory **with prefetch ON in both** isolates the
memory stall the prefetcher fails to cover: **1,887 cycles, ~19 % of runtime.**

Backing out the four D-side-bound kernels (load/store 644, store-stream 216,
same-line-copyback 138, load-stream 128 ≈ **1,126 cyc**) leaves roughly **760 cycles of
I-side residual (~7.7 % of runtime)**, concentrated as:

| kernel | L2+pf | ideal+pf | residual | prefetch speedup |
|---|---|---|---|---|
| call-return | 2,970 | 2,446 | **524** | 1.03× |
| mixed | 786 | 615 | **171** | 1.57× |
| independent-ALU | 280 | 214 | 66 | 4.67× |
| branchy | 443 | 443 | **0** | 1.18× |

**Caveat:** call-return's 524 cycles include stack push/pop traffic, which is D-side.
Treat 760 as an *upper bound* on the I-side share, not a measured I-side figure.

### Why: the prefetcher is blind to control flow

`IcachePlugin`'s frontier is purely sequential and is seeded only from the demand line
(`seedPfWindow`, one call site, from S1 dispatch):

```
seed:     pfNextPa  := line + 64 ;  pfLimitPa := line + 5*64
advance:  pfNextPa  := pfNextPa + 64
gate:     pfNextPa(31:12) === pfDemandLine(31:12)      // never crosses a 4 KiB page
```

`IcachePlugin`'s only `Btb`/`Ftb` references are **invalidation** plumbing, and
`FetchCmd` is `{ pc: UInt(32) }` — no prediction field. Meanwhile both predictors *do*
carry 32-bit targets (`BtbEntry`, `FtbEntry`), and `FtbPlugin` is indexed one entry per
aligned 8-byte fetch window.

So on a predicted-taken branch the prefetcher keeps walking the **fall-through**,
issuing fills that will be discarded, until the demand miss at the target reseeds it.
It also stops dead at every page boundary regardless of what the predictor knows.

That is exactly the shape of the table above: 4.67× where fetch is sequential, 1.03×
where it is a call chain. `branchy`'s zero residual is the instructive counter-case — its
loop stays resident, so there is nothing to prefetch. **The addressable target is control
flow that leaves the resident working set, not "branchy code" generally.**

Scoped separately in `2026-09-03-ftb-directed-prefetch-design.md`.

## 4. The FTB is live, and accurate

The frontend audit §2.1 warned that a fetch-directed feature could "pass tests while
doing nothing" — a registered-result protocol whose freshness check is false every cycle.
The bench's per-kernel telemetry settles it: **the shipped FTB applies, and it is right.**

| kernel | apply | confirm | mismatch |
|---|---|---|---|
| branchy | 298 | 178 | 0 |
| call-return | 184 | 183 | 0 |
| hot-loop | 106 | 102 | 0 |
| same-line-copyback | 69 | 66 | 0 |
| store-stream / shift-stream | 64 | 63 / 62 | 0 |
| load-stream | 63 | 62 | 0 |
| shift-mixed | 41 | 40 | 0 |

**Zero mismatches everywhere.** One number stands out: `branchy` confirms only
**178 of 298** applies (60 %), against ~97-99 % for every other kernel. That is the one
FTB figure worth chasing; it is not explained here.

## 5. Known gap in the instrumentation

`pfHitUseful` (IcachePlugin ~:207-209, :879) is the telemetry that would say whether a
prefetch was *useful* or merely early, and its own comment records that it has
**"exactly ONE reader in the whole repo — `IcachePrefetchSpec`'s 'M4: pfHitUseful
telemetry' test."** No benchmark reads it. So this sweep can show that prefetch *helps*,
but not the useful-vs-wasted split. Wiring it into `IpcBenchSpec` is a prerequisite for
tuning prefetch depth or evaluating a target-directed frontier.

## 6. Do not tune prefetch depth before C6

Contract clause C6 (`docs/fabric_concurrency_contract.md` in the SoC repo): a 64 B line
fill costs a measured **4.97 cyc against a 4.0 floor**, because L2C serves each 256-bit
fetch beat as **two serialized 128-bit quadrant lookups** held apart by `pipe_id_haz_c`.
More prefetch aggression — depth or branch-following — pushes harder on that door without
widening it. C6 raises the ceiling for the prefetcher that already exists.

## 7. Reproduce

```
cd cpu040
IPC_SEED=1 IPC_MEM=l2                    sbt "testOnly m68k040.bench.IpcBenchSpec"   # ON
IPC_SEED=1 IPC_MEM=l2 IPC_PREFETCH=off   sbt "testOnly m68k040.bench.IpcBenchSpec"   # OFF
```
Pin `IPC_SEED`: the bench notes ~1 % aggregate run-to-run jitter unpinned. Always pass
`IPC_MEM=l2` for any I-side question (§0).
