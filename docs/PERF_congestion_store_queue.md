# Routing congestion: the store queue, measured (2026-09-27)

Target named by the 200 MHz age-matrix A/B: `LsEuPlugin_logic_sq` appears in EVERY
congested window of BOTH arms (share 16%/14% -> 32%/28%) while `IssueQueuePlugin`
appears in none. This file is the characterisation that was asked for before any
restructuring, plus what was built, what was priced and rejected, and what cannot be
done without changing forwarding semantics.

## 1. Is the attributed scope actually the store queue?

**Partly. The registers are the SQ's; some of the combinational cells and at least
three of the widest nets in the scope are NOT.**

Routed scope census (`build/iqam200_off/checkpoints/route.dcp`):

```
LUT 4722   FF 1713   CARRY 81   MUXF 239   LUTRAM 0
cells 6758   pins 40568   (6.0 pins/cell)
```

* **The FF count proves the module boundary is real.** Counted from the RTL at
  `depth = 8` with `reserveLateStore` on (the shipped profile): 211 register bits per
  entry (valid, dataReady, committed, robId 5, paddr 32, data 32, size 2, useStrbA,
  validB, paddrB 32, maskA 16, maskB 16, vaddrA 32, vaddrB 32, cacheMode 2,
  cacheModeB 2, supervisor, precise, orphan) x 8 = 1,688, plus 22 bits of ring state
  (head/tail/sendPtr 3 each, sendPhaseB, ackPhaseB, acceptedHalves 5, count 4,
  preciseDrainBusyReg, preciseFinalAckD) = **1,710 predicted vs 1,713 measured.**
* **But `ADDRH[0..5]` at 971 fanout each (~5,800 sink pins) cannot be an SQ net.**
  `ADDRA..ADDRH` are the eight address ports of a Xilinx RAM32M/RAM64M distributed-RAM
  primitive. This module infers **no memory at all** — `drainRowMem` was deleted in task
  #252 and the scope reports `LUTRAM 0`. Six bits of address at ~971 primitives each is
  the **integer PRF**: `RegFilePluginInt_logic_ram` (`RamAsyncMwMux_1`) has 6-bit
  addresses, 5 write ports, and 4,584 LUTRAMs — 9,957 LUT in all, ~10% of the device.
* **`RobPlugin_logic_tail_reg[3]` at 1,240 and `RobPlugin_logic_faultCap` at 1,214
  cannot be SQ nets either.** The widest net inside the StoreQueue RTL is `qLineB`, at
  **896 pin-bits spread over 32 references — at most 32 sinks on any one bit.** Nothing
  in this module gets within an order of magnitude of 1,000.
* This is a **stable property of the flow**, not a one-off: the pre-existing
  `synth/lscensus_probe_ls_sq.rpt`, from an earlier and differently-configured build,
  already shows `LsEuPlugin_logic_sq/ADDRA[0]` at fo=135 and cells named
  `LsEuPlugin_logic_sq/RobPlugin_logic_exc_oldSr[4]_i_86`,
  `.../DcachePlugin_logic_s0Valid_i_2` and `.../DcachePlugin_logic_maint_sm_stateReg[2]_i_5`.
  `synth/impl_FullCore.tcl` leaves `-flatten_hierarchy` at Vivado's default `rebuilt`,
  which flattens for optimisation and then reassigns surviving cells to a hierarchy.

**Verdict.** The SQ's cells really are in the congested windows, so the attribution is
not a mirage — but it is an **upper bound**, and the ~1,000-fanout broadcasts that make
that region pin-dense belong to the PRF LUTRAM address and the ROB pointers. Also note
the scale: 4,722 LUT is ~5% of socket_core's ~95k. **No change confined to this module
can move a congestion level.** That bound should be read before spending on any of the
levers below.

## 2. What inside the SQ is pin-dense

Measured on the emitted Verilog (`generated/M68kFullCoreSynth.v`, module `StoreQueue`)
by summing, for every signal, the referenced width at every right-hand-side use —
"RTL pin-bit load". Total **13,324**, grouped by cone:

| cone | pin-bits | share |
|---|---:|---:|
| forward query (compare matrix, masks, youngest-select, reductions) | 4,164 | **31.3%** |
| per-entry register reads (ring storage, the 1-of-8 field muxes) | 3,666 | 27.5% |
| alloc / write path (mostly flop D-pins + the write-enable decode) | 2,654 | 19.9% |
| **drain payload (byte-lane spread + the 144-bit merge port)** | 1,930 | **14.5%** |
| fault / completion (vaddr muxes at head) | 520 | 3.9% |
| count / full / empty | 232 | 1.7% |
| barrier residency | 136 | 1.0% |
| flush keep-scan | 22 | 0.2% |

Answering the question as posed — address compare vs age network vs data mux vs barrier:

* **The address compare matrix is the pin-dense all-to-all structure: 32 x 28-bit line
  equality comparators**, four per entry — `{slot A line, slot B line}` x `{query line,
  query second-half line}`. Query side: `qLine` 448 pin-bits (16 references), `qLineB`
  896 (32 references, 16 distinct comparators after CSE). Entry side: `lineA`/`lineB` at
  84 pin-bits each x 16 = 1,344. **Total 2,688 pin-bits = 20.2% of the module** — the
  single largest structure in it. On top of that sit `qMaskThis` (16 bits x 16 = 256) and
  `qSpan` (19 bits, 8 x 19-bit equality for the full-forward test, 171).
* **The age / `olderThan` network is small: ~500 pin-bits, ~3.8%**, spread over TWO
  independent 8-entry matrices (the forward query and the barrier query), each a 5-bit
  subtract plus a 5-bit compare per entry — `robId` is 5 bits now, not 6. This
  **contradicts** the hypothesis that the ROB-pointer broadcasts make the age compare a
  bigger contributor than the address compare; in RTL terms it is 5x smaller, and the
  1,200-fanout ROB nets in the scope are not the SQ's (section 1).
* **The barrier logic is 1.0%.** Not worth touching.
* **The data mux is not where the comment says it is, and fixing it is a wash.** The
  comment at the `reduceBalancedTree` claims "one common late-data mux after winner
  selection, not eight 32-bit muxes ahead of the reduction tree", but `c.data :=
  datas(i)` does put 32 bits inside the `Cand` struct, so ~40 bits x 8 converge on the
  tree. Priced: carrying a 3-bit index instead and reading `datas(best.idx)` gives a
  9-bit tree (7 nodes x ~11 LUT = 77) plus one indexed 8:1 mux (32 x (2 LUT6 + MUXF7) =
  64 LUT + 32 MUXF7) against today's 7 x ~40 = ~280 LUT — about **-140 LUT and -130 pins,
  i.e. 0.3% of the module's pins and a sixth of the 800-LUT noise floor**, while adding
  two logic levels to the `fwdHit -> fwdData` path that used to be the worst post-route
  path in the design. Not built.

## 3. Why the compare matrix is irreducible at fixed semantics, priced against the literature

* **Bloom-filter membership (Sethumadhavan, Desikan, Burger, Moore & Keckler, MICRO-36
  2003).** The filter saves CAM *activations* — dynamic power, and search energy in a
  large SQ. It does not remove the CAM: a filter hit still needs the exact compare, so
  every comparator and every pin stays, and the filter itself is new LUTs. On a device
  where LUTs are the scarce resource (~68%) and power is not a constraint at all, this
  prices out negative.
* **Two-level / hierarchical store queue (Akkary, Rajwar & Srinivasan, MICRO-36).** This
  is a *capacity* technique: a small fast level plus a slow overflow level. An 8-entry SQ
  already IS the fast level, so "two-level" here degenerates to "make the fast level
  smaller" — the `storeQueueDepth` knob in section 5, which the occupancy measurement
  shows is not free.
* **Address banking.** Structurally impossible in this design, not merely expensive:
  **ring position IS program order.** Age ordering (`ageDist = i - head`, the
  youngest-overlap select) and the in-order drain cursor both depend on it, so an entry's
  slot is fixed by allocation order and cannot be steered to an address bank. A per-entry
  bank-match bit ANDed into the compare reduces nothing — the same total bits are still
  compared.
* **Dependence prediction (Roth).** Could skip a *lookup*, not a *comparator*: the cone is
  combinational and must exist for every load that is not predicted independent, and a
  misprediction changes what forwards.
* **Narrowing the compare (hashed or partial tags).** A partial tag over-approximates.
  Over-approximating `stall` is safe but changes cycles; over-approximating `hit` returns
  the wrong bytes. Either way it fails the gate.

## 4. The one change that WOULD halve the matrix — reported, not built (rule 4)

Per entry the matrix is 4 comparators because there are 2 entry lines (slot A, slot B)
and 2 query lines (own, second half). Collapse both sides and it becomes 2:

1. **Describe an entry as ONE line plus a 19-bit span in that line's frame** — exactly the
   representation the QUERY already uses (`qSpan`: 16 own lanes + 3 spill lanes). Then the
   only comparators needed are `lineA === qLine` and `lineA === qLine - 1` (`qLine - 1` is
   one query-side decrement, off the entry matrix). This also folds `validB`/`maskB` away,
   ~13 register bits per entry.
2. **Drop the query's second-line comparators** by conservatively raising `stall` for a
   split query (`q.splitB`) that has any older resident store, instead of comparing it
   exactly.

Both are **over-approximations** — extra stalls, never wrong data — and step 1 needs the
same treatment for the rare cross-page split STORE whose slot B is not at `lineA + 1`.
Result: **32 comparators -> 16, i.e. 20.2% of the module -> ~10%.** But both steps change
*what forwards*, so per the task's rule 4 this is reported rather than built. Note the
ceiling from section 1 before authorising it: ~10% of a module that is ~5% of socket_core.

## 5. What was built

Two knobs in `ShippingCoreConfig`, both default OFF, both env-overridable for an A/B.

### `sqNarrowDrainMerge` (`SQ_NARROW_MERGE=1`) — the only sound reduction found

The drain payload is the second-largest structure in the module (14.5%), and two of its
four byte-lane derivations are **redundant, not merely expensive**:

* `DcacheByteLane.storeStrbA(off,size)` is **identically equal** to `storeStrb(off,size)`:
  `storeStrb` builds `bits(i) = (i >= off) && (i < off +^ n)` over `i` in [0,16), which is
  already `storeStrbA`'s `pos < 16` guard, and the widening `+^` makes them agree at
  `off + n == 16` too.
* On the lanes the strobe enables, `storeDataA(off,size,data)` carries the same bytes as
  `storeData(off,size,data)`. `storeData` writes lane `(off+k) mod 16`, `storeDataA`
  writes lane `off+k` only while it is < 16, so they differ **only** on the wrapped lanes
  — exactly the lanes `storeStrbA` leaves clear, and the merge is
  `Mux(strb(i), mergeByte(i), oldByte(i))`.
* **`DcachePlugin` already computes both locally, at S2 and at S3**, for the
  `useStrb = false` path, from the payload's own `paddr(3:0)`, `size` and `data`.

So a split store's slot A can present `useStrb = false` like every aligned store already
does. The AXI sub-beat extent agrees as well: `stEndSz = MmioCover.clampedEnd(off, n)` is
clamped at 16, so for a split slot A (offset 13..15) the size-derived range `[off, 16)`
equals the strobe-run range — and DcachePlugin's existing permanent assert at the
`!useStrb` INHIBITED site now machine-checks that for split slot A too.

Slot B genuinely needs its explicit strobe (its `paddr` is the next line base, offset 0,
and its `size` is driven as a flat LONG whatever its true 1..3-byte extent), but not a
16-lane dynamic decode: a split occurs only at slot-A offsets 13..15 with WORD/LONG, so
slot B covers at most lanes 0..2 and each lane has at most three possible source bytes.
The specialised form is that enumeration, Scala-unrolled over the six `(offset, size)`
pairs that can spill. A permanent `GenerationFlags.simulation` tripwire asserts it is
bit-identical to `storeStrbB`/`storeDataB` every cycle — the same discipline as the
`fsNbytesA`/`fsPaddrHiA` forward-geometry shadows, for the same reason (a silently wrong
store, not a crash).

* Removes: `_zz_sendDataA` 640 + `sendDataA` 128 + `sendStrbA` (~768 pin-bits, 5.8%) and
  most of `_zz_sendDataB` 640 + `sendDataB` 128 (~768 pin-bits, 5.8%).
* **~12% of the module's pin-bit load, an estimated ~400 LUT — half the ~800 LUT noise
  floor.** Built because it is the only sound reduction in the block, NOT because it is
  expected to move a congestion level on its own.
* Semantics-preserving: it changes the ENCODING of the drain command, never what forwards
  and never which bytes reach memory.

### `storeQueueDepth` (`SQ_DEPTH=<n>`) — a calibration arm, NOT a shipping candidate

Depth is the only lever that scales the *whole* structure at once: 8 -> 4 halves the
comparator matrix, the youngest-overlap reduction, the per-entry register array (1,710 ->
~860 FF) and every 1-of-N drain mux, i.e. roughly -45% of the module.

**Measured not cycle-neutral.** The bench harness already reports peak occupancy per
kernel (`[store-path] maxSqResident`). Over the kernels that report it, seed 1,
`IPC_V2=1`: `0, 1, 2, 2, 2, 2, 3, 3, 4, 7, 7, 7` — **three kernels reach 7 of 8
entries** (`store-stream` among them). Depth 4 therefore changes cycles and fails the
"IPC must not regress" gate.

It is kept, default 8, for one reason: it is the cheapest way to **calibrate whether the
SQ is the congestion cause at all.** If halving the module does not move the congestion
level or its attribution, the SQ is exonerated and the search should move to the PRF
(section 1), which is the real ~1,000-fanout pin-density structure in that region.
`pendDepth` (the lock-step `pendMem` replay ring) is derived from the same value so the
two rings cannot desynchronise.

## 6. Carry-forward

1. **The SQ is 5% of socket_core's LUTs.** Its whole reducible content is ~12% of itself.
   Congestion level 5 is not going to be fixed inside it.
2. **The real pin-density structure in that window is the integer PRF**:
   `RegFilePluginInt_logic_ram`, 9,957 LUT (4,584 of them LUTRAM), ~10% of the device,
   with six 6-bit address nets at ~971 sinks each — ~5,800 sink pins from six nets, versus
   the SQ's widest net at 32 sinks per bit. That is the next lever.
3. **Attribute by name, then check the name.** Two of the three widest nets "in the SQ"
   are a ROB pointer and a LUTRAM address port. `-flatten_hierarchy rebuilt` makes the
   hierarchy in a routed checkpoint advisory for combinational cells; the FF count is the
   check that the boundary is real at all.
