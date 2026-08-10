# I-Cache Slice — Design

**Status:** Historical baseline; §13 is superseded by the binding 2026-08-10
parallel-VIPT amendment
**Date:** 2026-05-31
**Parent spec:** `docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md` (ch 1 Frontend, ch 7 LSU/MMU geometry, ch 9 Caches/Bus, invariants #2 FMax / #3 plugin boundaries)
**Slice position:** First slice of the frontend (chapter #1). Builds the L1 instruction cache datapath in isolation; the ITLB/MMU, fetch/align + instruction buffer, branch prediction, and SoC bus plumbing are later slices.

---

## 1. Scope

**In this slice:**
- A synthesizable SpinalHDL VIPT L1 I-cache: tag/valid/data arrays, hit detection, miss/refill FSM.
- A real `spinal.lib` **Axi4ReadOnly master** for line refill (256-bit data, 2-beat INCR burst per 64 B line).
- A **clean translation port**, driven this slice by an **identity stub** (PA == VA, cacheable, no fault).
- An `invalidateAll` control input.
- Packaged as an `IcachePlugin` (FiberPlugin) exposing a fetch service; geometry from `Global` keys.
- Standalone SpinalSim verification against a behavioral AXI RAM slave.

**Out of scope (explicitly deferred to later slices, must not be precluded):**
- Real ITLB / MMU page-table walk (implements the translation port later).
- Fetch/align stage, 24 B instruction buffer, length predecode, branch prediction.
- Per-line `CINV.IC` / `CPUSH.IC`; only `invalidateAll` exists now.
- Non-cacheable / cache-inhibited bypass (the translation stub always reports cacheable; the
  `cacheMode` field exists in the port so the bypass datapath drops in with the real MMU).
- Hit-under-miss / multiple outstanding misses (single in-flight miss this slice).

---

## 2. Geometry

Ported as a pure-Scala helper `CacheGeometry` (adapted from the 030 repo — address math + VIPT-safety
checks; pure Scala, no hardware). Fixed by the parent spec:

| Property | Value |
|---|---|
| Size | 16 KiB |
| Ways | 4 |
| Line | 64 B |
| Sets | 64 |
| Offset bits | 6 (`addr[5:0]`) |
| Index bits | 6 (`addr[11:6]`) |
| Tag bits | 20 (`addr[31:12]`) |
| Indexing | VIPT (index from VA offset bits; tag physical) |
| VIPT safety | index+offset = 12 bits ≤ 12-bit 4 KiB page offset → **no page coloring needed** |

`CacheGeometry` exposes `sets/offsetBits/indexBits/tagBits` and `index(addr)/tag(addr)/offset(addr)`
plus `requireViptSafe(pageBytes)`. The plugin asserts VIPT safety at elaboration.

## 3. Parameters (Global keys, produced by `ParamPlugin`)

Add to the `Global` registry (single producer = `ParamPlugin`, per invariant #3), sourced from
`M68kParams`: `L1I_KB` (16), `L1I_WAYS` (4), `L1I_LINE_BYTES` (64). Derived in `CacheGeometry`.
AXI refill width is a plugin parameter: `AXI_DATA_BITS = 256` ⇒ `beatsPerLine = lineBytes*8 / 256 = 2`.
Fetch window width is independent: `FETCH_BITS = 64` (8 B/cycle, per spec).

## 4. Ports (clean boundaries)

### 4.1 Fetch port (upstream — the cache's consumer-facing service)
- Request: `Stream(FetchCmd { pc: UInt(32) })`.
- Response: `Flow(FetchRsp { pc: UInt(32), data: Bits(FETCH_BITS=64), fault: Bool })`.
- Semantics: one outstanding request. On hit, response is produced at fixed latency. On miss, the
  request stalls (no response) until refill completes, then replays to a hit. `data` is the 64-bit
  window at `pc` (i.e. the line data selected by `pc[5:3]`, the 8-byte sub-block). `fault` is wired
  from the translation port (always false under the identity stub).
- Exposed as a `FetchService` trait (in `services/`): `def cmd: Stream[FetchCmd]; def rsp: Flow[FetchRsp]`.

### 4.2 Translation port (cache → MMU; identity stub this slice)
- `TranslationReq { vpn: UInt(20), supervisor: Bool }` → `TranslationRsp { ppn: UInt(20), cacheMode: CacheMode, fault: Bool }`.
- `CacheMode` enum: `{ CACHEABLE, INHIBITED }` (only CACHEABLE used now).
- Combinational identity stub: `ppn := vpn`, `cacheMode := CACHEABLE`, `fault := False`. Implemented
  as a small `IdentityTranslationPlugin` registering a `TranslationService`; the real ITLB registers
  the same service later. Because VIPT indexes from the page-offset bits (unchanged by translation),
  the set index is available immediately from the VA; only the tag compare needs the PA.

### 4.3 AXI4ReadOnly master (downstream refill)
- `spinal.lib.bus.amba4.axi.Axi4ReadOnly` with config: addr 32, data **256**, id width small (1–2),
  INCR bursts, `len = beatsPerLine-1 = 1` (2 beats), `size = log2(32) = 5` (32 B/beat).
- One outstanding read transaction (single in-flight miss). The master is owned by the I-cache
  (honors the "I-cache owns the instruction AXI master" boundary). A burst returns the full 64 B line.

## 5. Internal structure

- **Tag array:** `Vec(ways)` of async-read storage, `sets × tagBits`. Implement as registers or LUTRAM
  (async read on the hot path — invariant #2). 4 × 64 × 20 bits ≈ small.
- **Valid array:** `sets × ways` flip-flops (async read; `invalidateAll` clears in one cycle).
- **Data array:** `Vec(ways)` of BRAM, each `sets × lineBytes`. Written `AXI_DATA_BITS` (256) at a time
  during refill (2 writes/line); read a line on hit and mux the 64-bit window. (Sync BRAM read adds a
  cycle; acceptable on the data path — tags are async so hit *detection* is fast, and the spec accepts
  fixed load/fetch latency. Exact pipelining is a plan-time detail; baseline: 1-cycle BRAM read → hit
  data on the next cycle.)
- **Victim counter:** `sets` × 2-bit round-robin counter; increments on each refill into a set.

## 6. Data flow

**Hit path:**
1. `pc` arrives on the fetch cmd. `set = pc[11:6]`, `offset = pc[5:0]`.
2. Async-read the 4 tags + 4 valid bits for `set`. Concurrently request translation (`vpn = pc[31:12]`)
   → `ppn` (identity).
3. `hitWay = OHMasking.first(for each way: valid && tag == ppn)`. `hit = hitWay.orR`.
4. On hit: read the hit way's data line (BRAM), select the 64-bit window via `pc[5:3]`, drive `rsp`.

**Miss path (FSM): IDLE → REFILL → WRITE → REPLAY**
1. On miss, pick `victim = victimCounter[set]`; latch `lineBase = {ppn, set, 6'b0}`.
2. Issue AXI `AR` for `lineBase`, INCR, 2 beats of 256 bit.
3. Stream the 2 `R` beats into `dataArray(victim)(set)` (beat 0 → low 32 B, beat 1 → high 32 B).
4. On last beat: write `tag(victim)(set) := ppn`, `valid(victim)(set) := True`, increment
   `victimCounter[set]`.
5. Replay the latched fetch → now hits → drive `rsp`. Return to IDLE.

Single outstanding miss: the fetch cmd stream is back-pressured while the FSM is busy.

## 7. Replacement & invalidate

- **Replacement: round-robin per set** (2-bit victim counter per set, incremented on refill). Simple,
  deterministic, trivially testable. PLRU is a later optimization (the victim-select is a single
  function, swappable without touching the FSM).
- **`invalidateAll`:** a level/pulse input that clears every valid bit in one cycle (reset uses it;
  future `CINV.BC` drives it). Tags/data need not be cleared (validity gates them).

## 8. Plugin integration

- **`IcachePlugin` (FiberPlugin):** owns tag/valid/data arrays, the miss FSM, and the AXI master.
  Reads geometry from `Global` keys during `build`. Registers `FetchService`. Resolves
  `TranslationService` via `host[TranslationService]`.
- **`IdentityTranslationPlugin` (FiberPlugin):** registers a combinational identity `TranslationService`.
  Swapped for the ITLB plugin later — the I-cache is unchanged.
- **`Global` additions:** `L1I_KB`, `L1I_WAYS`, `L1I_LINE_BYTES` (producer: `ParamPlugin`).
- **Services added to `services/Services.scala`:** `FetchService`, `TranslationService`, `CacheMode`.

This keeps the slice within invariant #3: I-cache ↔ MMU communicate only through `TranslationService`;
the frontend consumes only `FetchService`.

## 9. Testing (standalone SpinalSim + behavioral AXI RAM slave)

A **behavioral AXI4 read slave** backed by a Scala `Array[Byte]` (or a small SpinalHDL AXI RAM)
answers AR/R bursts with the backing contents. A pure-Scala reference computes expected fetch bytes
from the same backing array. Test cases (all VerilatorTest-tagged where they elaborate RTL):

1. **Cold miss → refill → hit:** first fetch at PC misses, drives exactly one 2-beat INCR AR at the
   line base; returned 64-bit window equals the backing memory at PC.
2. **Same-line hit:** a second fetch to another offset in the same line returns correct data and
   issues **no** AXI traffic.
3. **Line-crossing sequential fetch:** stepping PC past the 64 B boundary triggers exactly one new
   refill for the next line.
4. **Eviction (round-robin):** fetch 4 distinct tags mapping to one set (fill all ways), then a 5th
   tag → victim is way 0 (counter order); re-fetching the evicted tag misses again.
5. **`invalidateAll`:** after caching, assert `invalidateAll`; next fetch to a cached line re-misses
   and re-refills.
6. **Data correctness sweep:** fetch a range of PCs across several lines; every window matches the
   reference.

Assertions check returned bytes vs the reference and AXI burst shape (one 2-beat INCR per refill,
correct address/len/size). No CPU or lock-step needed — this is a self-contained component test.

## 10. Files & structure

| File | Responsibility |
|---|---|
| `src/main/scala/m68k040/cache/CacheGeometry.scala` | pure-Scala geometry + VIPT-safety (ported/adapted from 030) |
| `src/main/scala/m68k040/cache/IcachePlugin.scala` | the I-cache: arrays, hit logic, miss FSM, AXI master |
| `src/main/scala/m68k040/cache/IcacheTypes.scala` | `FetchCmd`/`FetchRsp`/`TranslationReq`/`TranslationRsp`/`CacheMode` bundles |
| `src/main/scala/m68k040/mmu/IdentityTranslationPlugin.scala` | identity `TranslationService` stub |
| `src/main/scala/m68k040/services/Services.scala` (modify) | add `FetchService`, `TranslationService` |
| `src/main/scala/m68k040/Global.scala` (modify) | add `L1I_KB`/`L1I_WAYS`/`L1I_LINE_BYTES` |
| `src/main/scala/m68k040/core/ParamPlugin.scala` (modify) | set the new keys |
| `src/test/scala/m68k040/cache/AxiBehavioralRam.scala` | behavioral AXI4 read slave for tests |
| `src/test/scala/m68k040/cache/CacheGeometrySpec.scala` | geometry/address-math unit tests |
| `src/test/scala/m68k040/cache/IcacheSpec.scala` | the SpinalSim test cases (§9) |

## 11. Open items for the implementation plan

- Exact hit pipeline depth (async-tag hit detect + 1-cycle BRAM data read) and the cmd/rsp valid
  timing; settle in the plan with a waveform-checked smoke.
- AXI ID/outstanding config (single ID, one outstanding) and `spinal.lib` `Axi4ReadOnly` config knobs.
- Whether the data array is 4 separate BRAMs (per way) or one banked BRAM; baseline = per-way.
- Behavioral AXI slave: hand-rolled vs `spinal.lib` AXI RAM sim model; baseline = minimal hand-rolled
  read slave driven from the testbench.

## 12. Known divergences / deferrals (logged)
- Identity translation (no real address translation) until the ITLB slice.
- Cacheable-only (no inhibited/MMIO bypass) until the MMU provides cache modes.
- `invalidateAll` only (no per-line CINV.IC) until cache-control instructions are wired.
- Single outstanding miss (no hit-under-miss) — a later IPC optimization.

## 13. Binding resident-hit pipeline addendum (2026-08-09)

> **Superseded 2026-08-10:** the registered translation T-stage timing below is
> historical. `2026-08-10-icache-parallel-vipt-design.md` now binds a two-cycle
> resident path with parallel virtual-set BRAM read and ITLB/tag lookup. The
> geometry, handshake, refill, and safety rationale elsewhere in this document
> remain binding.

The geometry and VIPT safety argument above remain binding: virtual
`pc[11:6]` selects one of 64 sets, while the translated physical page number is
the 20-bit tag. With 64-byte lines, index plus offset is exactly the 12-bit
4-KiB page offset, so no coloring is required.

The original §6 timing sketch is historical. The live FPGA-oriented hit path is
a three-cycle, initiation-interval-one pipeline:

1. At cycle N, `cmd.fire` presents the VPN to the combinational ITLB lookup and
   captures `{pc, physical address, fault, cache mode}` into the translation
   T-stage.
2. At N+1, the registered physical tag is compared with the async tag/valid
   arrays while the synchronous per-way data BRAM read is armed from the
   page-invariant virtual set/offset. The selected way and raw prediction entries
   enter S1.
3. At N+2, registered BRAM data is way/lane selected and prediction metadata is
   windowed into the response register.
4. At N+3, `FetchRsp.valid` is visible. FetchAlign appends its four words at the
   edge; simple instructions can first become useful decode packets at N+4.

This is VIPT, but it intentionally does **not** start the cache tag/data lookup
in parallel with the live ITLB result. The translation register severs the
measured route-dominated ITLB-way-mux to cache-tag/data cone. Restoring the
original live parallel cone or removing the response register is not an
approved latency optimization without a new routed FMax result; either would
place tag-hit way selection and the wide data/prediction mux back into one
cycle.

The translation request is a demand handshake: `TranslationReq.valid` is high
only while `FetchService.cmd.valid` offers a real fetch. The combinational ITLB
response may still determine `cmd.ready` in that cycle, but an idle/stopped
frontend must not launch a table walk for an unowned `cmd.payload.pc` value.

Latency is hidden with credits rather than collapsed. T, S1, and response
advance every cycle on resident hits, so the port accepts and returns one fetch
window per cycle. FetchAlign owns a three-entry in-order record ring and may
consume the head response and allocate its replacement in the same cycle. The
ring therefore covers the full three-cycle hit latency without a periodic
full-ring issue bubble. Its conservative IBuf reservation may throttle excess
fetch bandwidth when four fetched words per cycle outrun decode, but a warm
two-wide stream of two-word instructions (four consumed words per cycle) must
remain continuously useful from the IBuf. This bandwidth-matched stream exposes
even a single missing response cycle instead of letting surplus words mask it.

A decode-directed taken redirect is a separate boundary. With a resident target
and no older miss, the current sequence is target command N+1, target response
N+4, first target packet N+5: four dead restart cycles after the branch packet.
Additional sequential ring depth cannot remove this because the target is not
known at fetch time. Fetch-directed prediction/replay is the appropriate lever;
collapsing the L1I stages is not.

The non-vacuous resident gates are:

- at least six distinct same-line commands accepted on consecutive cycles, with
  six ordered responses on consecutive cycles at exactly three-cycle latency
  and no new AXI read;
- after warm-up, at least eight consecutive two-slot, two-word `feed.fire`
  cycles with exact unique PCs/extension words and no lost or duplicated words;
  and
- a redirect colliding with a full-ring response/replacement, followed by only
  the resident target stream, at the N+5 restart boundary. Existing directed
  fault and stale/drop tests remain mandatory alongside this cadence test.
