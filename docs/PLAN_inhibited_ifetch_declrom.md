# PLAN: executable declROMs — cache-inhibited instruction fetch via the D port

**Status: DEFERRED.** Agreed 2026-09-22 to schedule this after the fmax/area
recovery. It depends on nothing in that campaign and blocks nothing in it.

## The problem

Executable declaration-ROM code living in NuBus slot / I/O space cannot be
fetched today. A fetch there is answered with DECERR by `ifetch_window_guard`
(SoC), which is correct present behaviour — the guard exists because the fetch
master is bound straight to l2c's `f_axi` with no window decode, so an unowned
address used to be served from DRAM at `addr mod DRAM size` and executed.

Data-side access to slot space already works; only *execution* is missing.

## What already exists — do not rebuild any of this

1. **The speculation gate.** `IcachePlugin` computes
   `lookupPageCacheable = lookupCmode =/= CacheMode.INHIBITED` and carries
   `nonSpecFetch`, which refuses to *accept* an inhibited fetch command until it
   is architectural. It is an accept-side gate at the Stream boundary rather than
   an S1 hold, for the reason written at the site: a held command still owes
   FetchAlign a response forever. It was built after a measured defect — a
   wrong-path run-ahead fetch into a CM=10 page issued a real 64-byte INCR burst
   spanning four Quadra 53C96 registers including the read-to-clear Interrupt
   Status. **The hard part of this feature is therefore already done and tested.**
2. **The arbiter.** `AxiDMergePlugin` already merges requesters onto `axi_d`
   inside `socket_core`. This is a CPU-side change, not an SoC one.
3. **The narrowing.** `axi_pb_s1_cdc` already contains
   `axi_pb_lane_narrow` (128 -> 32) on the D path at the peripheral bridge.
4. **The guard.** `ifetch_window_guard` stays exactly as strict as it is. The
   inhibited path does not use `axi_i` at all, so nothing about the cacheable
   fetch path changes.

## Why the D port and not the I port

| | `axi_i` | `axi_d` |
|---|---|---|
| data width | **256 bits** | 128 bits |
| narrowing to device width | none, anywhere | **128 -> 32 at the pb bridge** |
| binding | native, straight to l2c `f_axi` | through the crossbar |

- **Width decides it.** Only the D path can express a device-width access. Sending
  a device fetch down a 256-bit port means either making the slot decode answer
  32-byte reads — across whatever else lives in that span — or bolting a
  narrowing stage onto the I port, which is this plan with extra structure.
- **Bandwidth.** Putting the I port through the crossbar would add an arbitration
  hop to every cacheable fetch, permanently, to serve a cold path. The inhibited
  path is by construction never the steady-state stream and `nonSpecFetch`
  already serialises it to one transaction at the ROB head.
- **Timing.** The steering predicate is already computed and already on the accept
  path, so no new physical-address decode enters the fetch cone — which matters
  because that cone carries the frontend's worst paths.

## Design

- Steer on the ITLB cache mode (`lookupPageCacheable`), never on a new physical
  address window.
- Issue a **single narrow beat**: no line burst, no allocate, no prefetch of the
  rest of the line.
- Route through `AxiDMergePlugin` onto `axi_d`.
- **Dedicated, disjoint AXI ID**; exactly one outstanding.
- Only ever present a non-withdrawable request.
- Never replay.

### Principal open question: the fetch granule

The frontend's window is 8 bytes (`cmdWindowPc = pc(31 downto 3) @@ U(0,3)`) and
the IBuf push carries up to 4 words. A 32-bit device read returns 2 words, i.e.
half a window. This is already expressible — `io.push.payload.n` is `UInt(3 bits)`
and the IBuf write decode admits any `n` in 0..4, while FetchAlign's issue
reservation charges a full 4 words per window as a safe upper bound — so a
2-word push needs no capacity or interface change. Decide explicitly between
one 2-word push per 32-bit beat and two beats per 8-byte window, and write the
choice down before touching RTL; do not let it fall out of the implementation.

## Risks, all from this repository's own history

- **AXI ID overlap.** Two prior defects: `BUG_dstore_axi_id_overlap_race.md`, and
  the confirmed I-cache duplicate-outstanding-ID-after-CPUSHA that executed wrong
  bytes. Adding a fetch requester to the D port's ID space is exactly that shape.
- **AxiDMerge grant leak** on a request withdrawn before AR issues — fixed once,
  then recurred. `nonSpecFetch` makes this requester the safe kind; assert it
  rather than relying on it.
- **Inhibited-MMIO replay.** An IRQ replaying an inhibited load was a real wedge
  because the device had already been read. A replayed inhibited *fetch* is the
  same defect.
- **Speculative I-fetch into device space** — the defect `nonSpecFetch` exists to
  prevent. Any new issue path must sit behind it, not beside it.

## SoC side

- There is **no declROM in the SoC today** (no `declrom`/`slot_rom`/`sResource`
  anywhere in `rtl/`). This is greenfield, so author the image **32 bits wide**
  and Apple's byte-lane mechanism never enters the picture — byte lanes exist for
  cards whose physical ROM is 8 or 16 bits wide.
- If a real card's image is ever hosted verbatim, lane gathering belongs in the
  slot decode, which is where real hardware puts it. The MC68040 has **no dynamic
  bus sizing** (that was the 020/030); the CPU always issues 32-bit or line
  transfers and the bridge does the gathering.

## Test plan

- Extend the existing `ExecuteLockStepSpec` "spec-mmio I-side THE RULE" probe,
  which already demonstrates the speculative-burst defect on unmodified RTL.
- Assert: single beat, no allocate, no line prefetch, ID disjoint from every data
  ID, at most one outstanding, and no issue while speculative.
- Negative control: a wrong-path fetch into CI space must still issue nothing.
- SoC testbench for the declROM itself once it exists.

## Sequencing

After the fmax/area recovery. Nothing here is on that campaign's critical path,
and nothing in that campaign changes the analysis above.
