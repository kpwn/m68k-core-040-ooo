# BUG: the I-cache presents an AXI read AR for an id still outstanding on the bus,
# when a CPUSHA/CINVA invalidate lands mid-prefetch

**Status**: FIXED 2026-09-08 on branch `fix/store-addr-zero-tripwire` (`f679d8a1`,
off `4aaee2b1` + the triage-13 harness fixes `6d394e39`). Sim-reproduced on the
unfixed RTL (`925a5f64`) and fixed; the store-address tripwire this campaign built
NEVER fired, so this is offered as the **leading, but not confirmed**, mechanism for
the p163 board symptom. Read section 5 before claiming it IS the store bug.

## The p163 board symptom (what we were hunting)

Quadra 700 SoC, SoC `1cad2cbf` + cpu040 `4aaee2b1`, ~1 boot in 10-15 (warm and cold):
a ~10-byte STORE lands at RAM `0x0..0x9` = `[ff ff][00 00 00 00][00 00 00 00]` in a
DIRTY L1D line (DRAM reads back correct until `dcache-op push`), then the ROM
dereferences a NIL pointer through the corrupted `mem[0]` at `0x40832caa`, bus-errors
at `0xffff0062` (vec 2), the LONG vector fetch reads the truncated `0x000026f0`, the
CPU jumps into low RAM, illegal-instruction vec 4, Sad Mac. The 10-byte shape is
QuickDraw's picture-header "ruined" marker written by the ROM's picture-append
routine `0x40831ab0` on the `_SetHandleSize` FAILURE arm:

```
40831b02  moveal %a3@,%a0        ; a0 := *pictureHandle (master pointer)
40831b04  movew  #-1,%a0@+       ; picSize := -1
40831b08  clrl   %a0@+           ; picFrame.topLeft
40831b0a  clrl   %a0@+           ; picFrame.botRight
```
executed with `a0 == 0`.

## What the CPU-only sim proved, and did not

Playing that routine BYTE-EXACT at its real PC under Musashi lock-step and the new
`StoreAddrTripwire` (fail on any committed store below physical `0x10`), across a
wide sweep -- D-side latency {zero, todaysCrossbar, dram60, storeSlow, chaosDram} x
CACR {DE=1, DE=0} x nop-phase 0..5 x MMU {off, on via TT + TC=0xC000 fast stores} x
handler {6-instr, ~210-instr so the ROB wraps and robIds repeat, + master-pointer
rewrite across the exit} x dispatcher-exit {RTE, and the REAL `tstw/addqw/rts`} x
{deterministic, phase-swept level-1 IRQ storm} -- **35/35 of the store-shaped
variants are CLEAN**: the tripwire never fires, the picture bytes are correct, final
A0 is the picture pointer. The errant-store-to-0, as a straight-line or
IRQ-interleaved data-path event on this routine, does NOT reproduce.

What DID reproduce is on the INSTRUCTION-FETCH side, and only once the test executed
the REAL Memory-Manager relocation shape. The ROM's `_BlockMove` (`0x4080ca10`)
copies >12 bytes and then "returns" (via a pushed address) into jCacheFlush
(`0x40885030: nop ; cpusha bc ; rts`), so a heap grow inside `_SetHandleSize` runs a
long burst of `cpusha bc` interleaved with instruction fetch. Emulating that shape
(`blockMove` flavour: 16-long copy burst -> `pea`/`rts` into `.short 0xf4f8` ->
master-pointer rewrite -> the ROM routine) killed 5 of 8 variants on an I-SIDE AXI
protocol violation.

## The bug (CONFIRMED in simulation, waveform)

`IcachePlugin.scala`, the AR-issue block (`when(!arHoldValid)`, ~L2344 on `f679d8a1`).

1. `cpusha bc` / `cinva bc` reaches the I-cache as `maintInvalidateAll`
   (`IcachePlugin.scala:92`, folded into `anyInvalidate` at `:232`). The poison loop
   (`:2291`, `when(anyInvalidate && mshrValid(i)) { mshrPoison(i) := True }`) marks
   every live MSHR slot -- demand AND the four speculative prefetch slots.
2. The prefetcher re-arms a poisoned speculative slot as soon as it completes
   (`mshrValid(e) && mshrComplete(e) && (mshrErr(e) || mshrPoison(e))` frees it,
   `:2264`; the pf-alloc arm at `:2207` re-uses the same slot index, `mshrArSent :=
   False`, `mshrGen + 1`). **The slot index IS the AXI id** (`I_SPEC_BASE + slot`).
3. The AR-issue arm then presents a NEW AR for that id (`:2350` demand / `:2352` pf)
   while the OLD transaction's R beats have not yet returned. The waveform
   (`scratchpad/p163_itrace.log`, unfixed `c6636147`) shows, e.g. at cyc ~166420,
   `AR id=1 addr=0x40802b80` fired with slot 1 freshly re-armed to gen 11 while id=1
   had an older read still owed -- and the AxiMemModel protocol checker fires:
   `AR id=1 presented while ALREADY outstanding`.

Two consequences:

- **(a) Protocol violation.** Two outstanding reads share an AXI id. The sim checker
  kills the run; the SoC's L2 `id_busy` CAM enforces the same rule in hardware
  (unconditionally for reads).
- **(b) Silent I-cache data corruption -- the dangerous one, NOT caught by the
  existing `mshrGen` gate.** AXI returns same-id responses in order, so the OLD
  (wrong-address) response arrives first. But `ar.fire` for the NEW AR already ran
  `mshrArSentGen(id) := mshrGen(id)` (`:2371`), so when the old data lands the R
  consumer's `genMatch = mshrArSentGen(rIdx) === mshrGen(rIdx)` (`:2427`) reads TRUE,
  `pfRspMatch`/`demandRspMatch` credit it, and the re-armed slot's line is installed
  with **another address's bytes**. The core then fetches and executes the wrong
  instructions. This is exactly the hole `IcacheIdReuseWhiteboxSpec` documents as the
  "generation-reuse-across-an-outstanding-transaction" class; the `mshrGen` fix does
  NOT close it because the generation only lives in the slot, never on the bus, and
  is refreshed at the new `ar.fire`.

## The fix (minimal)

Track bus-outstanding per AXI id and never present an AR for an id still owed a
response:

```scala
val arOutstanding = Vec.fill(MSHR_N)(RegInit(False))
when(axi.ar.fire)                          { arOutstanding(arHoldId.resize(mshrIdxBits)) := True }
when(axi.r.fire && axi.r.payload.last)     { arOutstanding(axi.r.payload.id.resize(mshrIdxBits)) := False }
// AR-issue arms additionally gated on !arOutstanding(DEMAND_IDX) / !arOutstanding(pfChosenArMshr)
```

The re-arm's AR is delayed until the old response returns. On the unfixed RTL the old
response was credited to the re-armed slot; now, because the delayed re-arm has
`mshrArSent == False` when the old response lands, it fails `pfRspMatch` and takes the
existing `staleDrain` accept-and-discard path (`:2437`), unblocking the bus. The new
AR issues afterward and its own response is the one installed -- correct data.

**Termination-safe.** An outstanding read always completes (the slave owes its
beats), so `arOutstanding(id)` always clears; the gate can only delay an AR, never
block it. `demandStuckQ` (`:1443`) is a structural set-busy hold, not a timer, so the
added delay cannot trip the deadlock-defense path.

## Fail-before / pass-after

`ExecuteLockStepSpec` test family
`"p163 pic-header NIL store: BlockMove burst + CPUSHA BC + master-pointer write"`
(MMU on/off x D-side {xbar, chaos} x {quiet, storm}) plus the `"REAL dispatcher
exit"` family. Unfixed `925a5f64`/`c6636147`: 5 of 8 BlockMove variants die on the
AXI protocol checker (`p163_run7.log`, `p163_itrace.log`). Fixed `f679d8a1`: all
pass (`p163_run8.log`), tripwire clean, done flag stored, final picture bytes and A0
correct.

## Why this fits the board's rarity (corroboration, not proof)

The coordinator's board data: with `halt-on-vec-2`/`vec-4` armed at +10 s, **25
consecutive p163 boots showed NO corruption** (only 3 benign ROM probe bus errors,
line intact after push); unarmed, p163 crashes ~1 in 10-15. The armed runs pause
~30 s at each benign vec-2 freeze, shifting SD/interrupt timing after the probe --
i.e. a **timing race that the pauses suppress**. This bug is exactly such a race:
it needs a `cpusha bc` (a CPUSHA-heavy `_BlockMove` inside a heap grow) to coincide
with a speculative prefetch slot being live+ARsent for a still-outstanding read. Any
perturbation of fetch/flush interleaving changes whether the poison-and-re-arm lands
inside the outstanding window.

## Residual risk / honest gaps

- The store-to-0 itself was NOT reproduced. The bridge "wrong I-fetch -> the CPU
  executes an errant store" is a code-reading inference, not a captured waveform of a
  store to `0x0`. It is PLAUSIBLE (a corrupted fetch can decode to any instruction,
  including a `move`/`clr` through an uninitialised address register) but UNCONFIRMED.
- On the real SoC the L2 CAM may serialise the duplicate AR rather than mispair it in
  the sim's way; consequence (b) then depends on whether the re-armed slot is credited
  with the delayed old response, which the fix closes regardless.
- The full-SoC boot sim reaches the writer far too slowly (~3.2k inst/s; the writer
  is System code well past the ~11M-instruction mark to the first SCSI command) to
  confirm on the board's own instruction stream within any practical budget.
- NEXT EXPERIMENT on the board: with the `f679d8a1` bitstream, run the unarmed
  ~1-in-10-15 boot loop and confirm the corruption rate drops to zero; if it does
  not, capture `break-pc 0x40831b04` with A0/A3/D0 and `mem[(A3)]` to see whether A0
  is genuinely 0 (this bug) or A3's master pointer was read stale (a different,
  D-side mechanism the tripwire would need to catch under the real interleaving).
