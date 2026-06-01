# I-cache BRAM Data Array — Design

**Status:** Draft for review
**Date:** 2026-06-01
**Parent spec:** `docs/superpowers/specs/2026-05-31-icache-slice-design.md` (the original VIPT L1I); `.../2026-05-31-m68k-040-ooo-architecture-design.md` ch 2 (frontend), **invariant #2 (FMax 250 MHz / FPGA-mappable)**.
**Builds on:** merged I-cache (`m68k040.cache.IcachePlugin`, IDLE→REFILL→PREDECODE→REPLAY, registered 1-cycle response) and `FetchAlignPlugin` (single-outstanding, latency-agnostic consumer).
**Motivates:** the first FPGA synth (2026-06-01) proved the backend meets 250 MHz, but the I-cache `dataMem` is `readAsync` 256-bit — it cannot map to block RAM (BRAM needs a registered read) and is far too wide for LUTRAM. See `memory/fpga-synth-multiwrite-mem.md`.

---

## 1. Purpose

Make the L1I **data array** a real synchronous-read **BRAM** so the I-cache is FPGA-mappable and timing-clean, absorbing the one extra read cycle into the **fetch→decode** pipeline boundary. The 256-bit-wide `readAsync` data array is the only structure that cannot be expressed as LUT/LUTRAM; the small single-write tag and predecode arrays stay async LUTRAM (they map to distributed RAM and keep hit/miss detection in the accept cycle).

## 2. Scope

**In:** convert `dataMem` (per-way `Mem(Bits(256), sets*beats)`) from `readAsync` to a **1-cycle synchronous read** (`readSync`, BRAM-inferable); split the hit and replay read paths into a 2-cycle S0(accept)/S1(respond) pipeline; keep cmd→rsp at **2 cycles** (was 1; +1 = the fetch→decode cycle). Update `IcacheSim` for the new latency. Re-synth to confirm BRAM inference.

**Out:** tags/pred stay async LUTRAM (no change). No geometry change (64 sets / 4 ways / 64 B). No change to AXI refill, predecode classification, victim policy, or `FetchRsp`/`FetchCmd` contract. No multi-outstanding fetch (the consumer is single-outstanding today; throughput tuning is a later slice). The backend register-file synthesizability fix (RAT/Freelist/ROB) is tracked separately, not here.

## 3. Latency model

Consumer (`FetchAlignPlugin.FetchControl`) is **single-outstanding and latency-agnostic**: it drives `cmd.valid`, latches `fetchInFlight`, and acts only on `rsp.valid` whenever it pulses; `rspStale` already drops an in-flight response after a redirect/resume. Therefore lengthening cmd→rsp by one cycle requires **no consumer change** — it is an extra in-flight cycle, exactly the "fetch→decode" cycle. (Throughput: still 4 words per fetch; single-outstanding means fewer fetches/100cyc than a pipelined cache, unchanged in *kind* from today — a separate optimization.)

## 4. Pipeline structure

Two register stages, S0→S1, replacing the single combinational accept-and-read.

### S0 — accept (in IDLE, on `cmd.fire`)
- Drive the data BRAM read: `dataMem(w).readSync(readAddr)` for each way, where `readAddr = set ## beatSel` from `cmd.pc` — the address is registered into the BRAM this cycle; data emerges in S1.
- **Async** tag read + compare (LUTRAM, unchanged): `isHit`, `hitWay`.
- **Async** pred read (LUTRAM) → `windowPred(...)`.
- On **hit**: register S1 latches `{s1Valid:=True, hitWay, pc, fault, predWindow, laneIdx}`. Stay in IDLE (can accept the next cmd only after S1 drains — see §5 hazard).
- On **miss**: latch miss state, `goto(REFILL)` (unchanged — miss detected here because tags are async).

### S1 — respond (the cycle after a hit accept)
- Data BRAM outputs are valid now: `beat(w) = dataMem(w).readSync(...)` registered value.
- Way-mux: `selBeat = beat(s1HitWay)`; lane-select: `window = selBeat.subdivideIn(64 bits)(s1LaneIdx)`.
- Drive the registered response: `rsp.valid` pulses, `rsp.data := window`, `rsp.pc/fault/pred` from the S1 latches.

`REPLAY` (miss path) uses the same S0/S1 read: REPLAY issues the `readSync` for the just-filled line, and a following respond cycle delivers it. (Implementation may reuse the S0/S1 registers with the FSM, or add a `REPLAY_READ`→`REPLAY_RESP` pair — chosen in the plan; behaviorally cmd→rsp = same 2-cycle shape.)

## 5. Hazards & correctness

- **Accept/respond overlap:** because rsp is now 2 cycles after accept, an FMax-clean structure must not let S0 accept a new cmd whose S1 would collide with the prior S1. Simplest correct first cut (matches today's effectively-single-issue behavior with the single-outstanding consumer): **accept at most one in-flight hit** — `cmd.ready` deasserts in the cycle S1 is pending. Since the consumer is single-outstanding anyway, this costs no real throughput. (A fully-pipelined accept-every-cycle hit path is a later optimization.)
- **`readSync` enable:** the data BRAM read must be enabled on the accept cycle (and REPLAY read cycle) so the BRAM captures the right address; otherwise it holds the last value. Use `readSync(addr, enable = <accept this cycle>)`.
- **No functional change** to which bytes are returned: same `set/beatSel/laneIdx` decode, same window extraction, same predecode window — only *when* (one cycle later) and *where the mux sits* (after the BRAM output register).
- **Redirect/resume during the 2-cycle window:** already handled by `rspStale` — verify the extra cycle widens the stale window correctly (a redirect in S0-or-S1 of an in-flight hit must drop that response). Add a directed test.

## 6. Verification

`IcacheSim` (SpinalSim vs the `Axi4ReadOnlySlaveAgent` + memByte/window64 golden model). Update/extend:
1. **Hit latency:** after a warm hit, `rsp.valid` pulses exactly 2 cycles after `cmd.fire` (was 1); data/pred/pc match golden. (Adjust the existing wait-for-rsp loops — they already poll `rsp.valid`, so most just see the later pulse.)
2. **Miss → refill → replay:** end-to-end still returns correct line; cmd→rsp shape holds; tag/valid committed in PREDECODE before REPLAY (unchanged).
3. **Sequential hits:** consecutive fetches to a warm line each return correct windows at the new latency (exercises S0/S1 hazard / single-in-flight accept).
4. **Redirect mid-flight:** a redirect during the 2-cycle in-flight window drops the stale response (no spurious enqueue) — directed test against the widened window.
5. **BRAM inference (non-gating, manual):** re-run `synth/` OOC on a top that includes the I-cache; confirm `dataMem` infers `RAMB*` (Block RAM Tile > 0) and no "Unsupported RAM template" for the data array.

## 7. Files

| File | Change |
|---|---|
| `src/main/scala/m68k040/cache/IcachePlugin.scala` | `dataMem` → `readSync`; S0/S1 pipeline for hit + replay reads; `cmd.ready` single-in-flight gate; rsp now 2-cycle |
| `src/test/scala/m68k040/cache/IcacheSim.scala` (or `IcacheSpec`) | latency bump in waits; add sequential-hit + redirect-mid-flight cases; (optional) BRAM-inference note |

## 8. Known divergences / deferrals (logged)

- Single-in-flight hit accept (no fully-pipelined accept-every-cycle) — matches the single-outstanding consumer; pipelined hit path deferred.
- Tags/pred remain async LUTRAM (distributed RAM) — intentional, keeps hit/miss detection in S0; moving them to BRAM (and the extra miss-detect latency) is out of scope.
- Multi-outstanding fetch / fetch-throughput tuning deferred (separate slice).
- predMem (128 b × 64, single-write) maps to distributed RAM today; if a future synth flags it, it can move to BRAM with the same S0/S1 treatment.
