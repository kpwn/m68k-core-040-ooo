# Amendment: L1D as 64-byte lines with 16-byte SECTORS (quadrants)

**Status:** proposal, owner-originated 2026-09-27. **Not ratified.** Filed because
`AGENTS.md` fixes architecture to the design doc and the ratified MSHR design
(`2026-07-30-mshr-multi-outstanding-design-proposal.md`) does **not** consider
sectoring. This amends its decision **D4 / §11.Q2** (`D3-BURST`).

**Motivating target:** the owner has set the goal at **GB/s of copy bandwidth**.
Measured today (`bench/memcpy-bandwidth`, `969db588`): **48.8 cycles per 16-byte
line, 0.328 copy-B/cyc ~ 32.8 MB/s at 100 MHz**, identical across a 4x
working-set change and both seeds. The 128-bit D master caps copy bandwidth at
**0.8 GB/s at 100 MHz / 1.6 GB/s at 200 MHz**, so we are at **4.1% of the bus**.

## The proposal

L1D becomes **64-byte lines divided into four 16-byte sectors**, each sector
carrying its **own valid and dirty bit**. Geometry stays 8 KB / 4-way.

## Why this is better than plain `D3-BURST` (unsectored 64 B lines)

1. **It preserves 68040 conformance, which unsectored 64 B lines break.** The
   architectural line is **16 bytes**: `CINVL`/`CPUSHL` operate on a 16-byte line
   and `MOVE16` moves exactly one. With 16-byte sectors, maintenance and dirty
   granularity stay at 16 bytes — a `CINVL` invalidates one sector. Unsectored
   64 B lines force either over-invalidation (4x the architected extent) or a
   read-modify-write on push. **This is the conformance trap `D3-BURST` carries,
   and sectoring removes it rather than mitigating it.**
2. **No-write-allocate becomes sound, not a special case.** A store that fully
   covers a sector marks it valid+dirty **without fetching it**. There is no
   partial-validity problem because the sector *is* the unit of validity.
3. **`MOVE16` maps exactly onto one sector** — 16 bytes, no allocate. That is what
   the instruction exists for. (It is currently microcoded into 4 LONG transfers,
   `Microcode.scala:2417` — 8 D-cache accesses per 16 bytes.)
4. **The L2 already thinks in quadrants.** Design doc §6: the L2 "write miss
   allocates, fetches the line, merges the **store quadrant** at install". L1
   sectors and the L2's sub-line merge are then the same unit.

## Measured cost (computed from the real geometry)

| | tag bits | state bits | total | per-way bytes |
|---|---:|---:|---:|---:|
| today, 16 B lines, 512 lines | 10,752 | 1,024 | **11,776** | 2,048 |
| sectored, 64 B/4x16 B, 128 lines | 2,688 | 1,024 | **3,712** | 2,048 |

**-68.5% of tag+state storage.** Tag width is **unchanged at 21 bits** (index
shrinks 7->5 as the offset grows 4->6), and **per-way bytes are identical**, so the
**set-aliasing stride stays exactly 2 KB** — conflict behaviour does not change.
State bits are a wash: 4 valid + 4 dirty per 64 B line equals 1 valid + 1 dirty
per 16 B line.

## Bandwidth mechanism

Per 16 bytes copied, today: **three serialised transactions** — source refill,
destination **write-allocate** refill, dirty-line writeback. (Hypothesis from the
48.8-cycle measurement; to be confirmed from counters.)

Sectored with no-write-allocate on a fully-written sector: **two** — source fetch
and writeback. **-33% of transactions and -33% of bus traffic**, because the
destination fetch moves 16 bytes that are immediately overwritten.

Then Little's Law, `bandwidth = bytes_per_miss x MLP / latency`, at the 16 B/cycle
bus: **64-byte granularity needs MLP 4 to saturate; 16-byte needs MLP 16.**
`AxiIds.dRefill` reserves exactly **4** D-side refill IDs. The ID pool was sized
for this.

## ⛔ The honest risk: sectored caches lose capacity on scattered access

A tag now covers 64 bytes. A workload touching **one** sector per line gets
`128 x 16 B = 2 KB` of effective capacity instead of 8 KB — **up to 4x worse**.
The board workload already runs **24.43 D-miss/kinst**, so this can hurt real code
while helping streaming. This is the classic sectored-cache trade and it must be
measured, not assumed away.

**Mitigation from the literature:** Seznec, *"Decoupled sectored caches"*
(ISCA 1994) breaks the one-tag-per-line binding by giving each sector a small
tag-selector, letting sectors from different lines share tag-array entries; it
recovers most of the lost capacity for a few bits per sector. (The original
sectored cache is the IBM 360/85, Liptay 1968 — the first commercial cache, which
sectored precisely to hold tag cost down.) If plain sectoring measures a real
scattered-access regression, this is the known fix.

## ⛔ Coverage hole this exposes (the sixth found in this corpus)

**There is no scattered-access kernel with a footprint larger than L1.**
`chase-128`'s working set is ~2 KB and fits. So the corpus **cannot see the
capacity regression** and sectoring would measure as free when it is not. A
large-footprint pointer-chase kernel is a **prerequisite** for evaluating this,
not a follow-up. Prior holes: zero A6/A7 operands, zero store->load pairs, zero
load->compare->branch chains, 25 total suite mispredicts vs the board's 24.4 MPKI,
zero DBcc instructions.

## Open decisions

1. **Read allocation.** Fetch the whole 64 B line on a read miss (one 4-beat
   burst; best for streaming, amortises latency 4x) or only the needed sector
   (lazy; best for scattered)? The owner proposes **lazy allocate on reads**. The
   usual resolution is a hybrid: fetch the demanded sector, then prefetch the rest
   of the line only on a detected sequential stream.
2. **Plain sectored or decoupled sectored**, decided on the measured scattered
   regression once a kernel exists that can show it.
3. **Interaction with copyback P4** — the design doc warns line-size changes are
   "cheap now and expensive after P4" because dirty-bit and eviction granularity
   bake in. Sectoring keeps dirty granularity at 16 bytes, which may **neutralise
   that dependency entirely**. Needs confirming against the current tree.
4. Whether `MOVE16` should become a single full-sector no-allocate store.
