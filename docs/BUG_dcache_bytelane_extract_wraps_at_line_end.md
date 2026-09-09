# BUG: `DcacheByteLane.extract` wraps its byte index, so a non-AGU read at the end of a cache line returns bytes from the START of the same line

**Status**: ROOT CAUSE CONFIRMED and FIXED. The two reachable consumers were closed
first (`676db27b`, `9b9b6665`, merged to `integrate/cpu040-merged` as `c7fd193d`);
the two remaining latent ones and a permanent tripwire followed on branch
`fix/dcache-line-wrap-extract` (`487a23b1`, `b03c51be`). See §6 and §8.

**Severity**: CRITICAL, silent data corruption. This was the terminal blocker on
the Quadra 700 boot campaign. Fixing it took cold-boot success from **0 of 6** to
**8 of 8** on real hardware and produced the project's first boot to the Finder.

---

## 1. The defect

`src/main/scala/m68k040/cache/DcacheTypes.scala:205`

```scala
val b1 = bytes(off + 1)   // 4-bit index -- WRAPS inside the 16-byte line
```

`off` indexes a byte inside a 16-byte cache line, so `off + 1` at `off == 15`
wraps to 0. A WORD read whose base sits at line-relative offset 15 therefore
returns `{byte[15], byte[0]}` **of the same line** instead of crossing into the
next line. The value is silently wrong; nothing faults.

Loads WRAP where stores DROP. The store side of exactly this case was closed
long ago (task #163, `stSplitLow` / `fsSplitLow`); only the read sides were open.

## 2. Why most code never sees it

Ordinary loads go through the AGU cross-line splitter in `LsEuPlugin.scala`
(~745-747), which breaks a line-crossing access into two before it reaches the
cache. The exposed consumers are the ones that drive `dcache.loadCmd` directly
and bypass that splitter — the exception sequencer and the table walker.

## 3. Why it fired on every Mac OS boot

Mac OS legitimately runs long stretches with an **odd supervisor stack pointer**.
The System's patch installer does `linkw %fp,#-75` (RAM 0x00009008 on this ROM,
SSP 0x0017fec7 observed), an odd frame size, and then executes A-line Toolbox
traps, JSR/RTS and 60 Hz tick interrupts inside that stretch. A real 68040 is
fine with this: misaligned DATA accesses are legal on 020 and later, only
instruction fetch must be even, and the matching `unlk` restores an even SP.

With an odd SSP the format-$0 exception frame base (SSP-8) is odd, so depending
on `SSP mod 16` one of the popped words lands at line offset 15:

| SSP mod 16 | word that straddles | consequence |
|---|---|---|
| 7 | SR | RTE restores the WRONG CCR |
| 5 | PC high | RTE resumes at a WRONG address |
| 3 | PC low  | RTE resumes at a WRONG address |
| 1 | format  | wrong frame size popped |

Measured in lock-step: the DUT restored CCR `0x05` where the oracle had `0x1f`,
and resumed at `0x40a50032` instead of `0x40800032`.

## 4. What it looked like on hardware

A wrong resume address is a jump into memory nothing ever wrote. On p167,
**5 of 6 cold boots** ended with `pc_live` parked in 0x009xxxxx-0x00bxxxxx
executing the ROM memory test's filler pattern (`0x6db6` disassembles as
`blts -0x4a`, hence the characteristic 72-byte backward stride), with video
live at 640x480 and the 60 Hz tick still firing. The sixth was a vector-4 bomb.

The wild jump was caught at the instruction with a purpose-built PC-RANGE halt
lane (`OFF_PCRANGE_*`, see `docs/debug-trace-taps.md`):

```
pc-range: pc0=0x0086c3f8  pc1=0x4080b69a  pc2=0x4080b69a  count=2
A2=0x0086c3e0  D1=0x18   ->  ROM 0x4080b69a = jsr %a2@(0,%d1:w)
DRAM at 0x0086c3e0: db6db6db 6db6db6d b6db6db6 ...   (pure filler)
```

i.e. a Toolbox dispatch jumped through a master pointer addressing memory that
was never written — the downstream shape of a corrupted stack.

## 5. The fix

Mirror the store-side split on the read side. A word that straddles is read as
two BYTE loads (high at `addr`, low at `addr+1`) and reassembled, with the second
half's VPN derived from the split-aware address so a straddle at page offset
0xFFF genuinely re-translates.

* `676db27b` — RTE frame-word reads: `frameWordReq` / `frameWordWait`,
  `rdSplitLow`, `rdHiByte`, covering R_SRREQ / R_PCREQ / R_PCREQ2 / R_FMTREQ.
* `9b9b6665` — FRESTORE header word: `F_HDRREQ` / `F_HDRWAIT`, `fsRdSplitLow`.
  Before the fix, `frestore (%a0)+` on a header at offset 15 popped **226 bytes
  instead of 52**, which destroys stack discipline for everything after it.
  `frestore (%a7)+` on an odd SSP is what Mac OS does on an FPU context switch.

## 6. The two latent consumers — CLOSED (`487a23b1`)

Two `Size.LONG` reads sat on the same unguarded path. Neither is reachable on this
boot, because both need an architecturally misaligned control register rather than
merely an odd stack pointer — but a 68040 permits those register values, MOVEC on
this core stores them unmasked (as real silicon does), and a LONG straddles in three
distinct shapes rather than the WORD's one.

* **Exception vector fetch** — `ExceptionUnit.scala`, `E_VECREQ` / `E_VECWAIT`.
  `VBR + vector*4` is 16-byte aligned only when VBR is, so `VBR mod 16` in
  {13, 14, 15} makes EVERY vector fetch straddle and the core redirects to an address
  built partly out of unrelated memory — the worst outcome in the whole exception path.
* **Table-walker root descriptor** — `TableWalker.scala`, the shared `issueRead`
  micro-sequence. `rootPtr` is URP/SRP straight out of MOVEC, so `rootPtr mod 16` in
  {13, 14, 15} makes every root read straddle. The two deeper levels provably cannot:
  `MmuDesc.tblNextBase` masks the table base to 16 bytes and every index is a multiple
  of 4, so their offsets are always 0/4/8/12.

**Why four sub-accesses, not two.** A WORD straddles in exactly one way (1 + 1 bytes),
which is why the two shipped fixes are a clean high/low byte pair. A LONG straddles as
**3 + 1, 2 + 2 or 1 + 3**, and `Size` has no 3-byte encoding — no two-command shape can
express offsets 13 and 15. Both fixes therefore read a straddling access as FOUR BYTE
loads at `addr+0..+3`, shifted together big-endian (byte at the lowest address is the
MSB). The vector fetch re-presents each sub-access's own address to the translate port,
so a vector word that also straddles a page re-translates for the far-side bytes; the
table walk is physical (vaddr == paddr) and has nothing to re-translate.

Fail-before / pass-after:

| test | unfixed | fixed |
|---|---|---|
| `ExceptionEntrySpec`, VBR mod 16 = 13 | redirect `0x4000903f` (trailing byte garbage, seed-dependent) | `0x40009000` |
| `ExceptionEntrySpec`, VBR mod 16 = 12 (control) | `0x40009000` | `0x40009000` |
| `TableWalkerSpec`, rootPtr mod 16 = 13 | root descriptor reads `0x000110DE` for `0x00011003` -> NON_RESIDENT | PPN `0xABCDE`, no fault |

(The vector-fetch garbage rather than the planted decoy is itself informative: that DUT
has no `CacheControlService`, so the exception sequencer's loads run cache-INHIBITED and
the wrapped byte comes from the never-filled remainder of the miss line.)

## 7. The permanent guard (`487a23b1`)

Hand-splitting each consumer as it is discovered is not a fix for the class, so
`DcacheByteLane.extract` now carries a `GenerationFlags.simulation` assertion that fires
whenever `off + size > 16`. The synthesised behaviour is deliberately **unchanged** — the
wrap is still what the hardware computes — so this costs nothing in the netlist and turns
any future unguarded consumer into a loud simulation failure instead of silent wrong data.

The one legitimate wrapping producer is slot A of an LS EU cross-line split pair, which is
presented at the ORIGINAL crossing offset/size purely to obtain `DLoadRsp.line` and
discards `DLoadRsp.data`. It is exempted by a new, documented contract bit —
`DLoadCmd.lineOnly` — rather than by weakening the assertion. `DcachePlugin` pipes that bit
S0 -> S1 -> S2 and through the miss latches; every other `dcache.loadCmd` driver stamps it
False explicitly (the exception leg and the walker leg of `LsEuPlugin`'s arbitration mux,
`ExceptionUnit.dcLoadCmd`, `TableWalker.io.loadCmd`).

A harness fix was needed before any of this was testable: `DcacheClientMemAgent.readBE`
read FLAT memory, silently CROSSING the 16-byte boundary the real cache cannot cross, so a
straddling walker read looked correct in simulation and was wrong only on hardware. It now
models `extract` exactly, wrap included.

### Complete sweep of `dcache.loadCmd` drivers (2026-09-09)

| driver | AGU-split? | can present a straddling multi-byte access? | status |
|---|---|---|---|
| `LsEuPlugin` core LS path (aligned ring) | yes (`s1CrossLine`) | slot A of a split pair only, by design | safe; `lineOnly` True |
| `LsEuPlugin` early VIPT probe (`loadProbe`) | same predicate | same, flagged by the pre-existing `needsLine` | safe |
| `LsEuPlugin` exception leg | no | forwards `ExceptionUnit`'s command | see below |
| `LsEuPlugin` walker leg | no | forwards `TableWalker`'s command | see below |
| `ExceptionUnit` RTE frame words (4x WORD) | no | yes, offset 15 | FIXED `676db27b` |
| `ExceptionUnit` FRESTORE header (WORD) | no | yes, offset 15 | FIXED `9b9b6665` |
| `ExceptionUnit` vector fetch (LONG) | no | yes, offsets 13/14/15 | FIXED `487a23b1` |
| `TableWalker` descriptor reads (LONG) | no | yes at ROOT, offsets 13/14/15 | FIXED `487a23b1` |
| `DcachePlugin` `loadShadowCmd` replay | n/a | replays the original command verbatim | inherits its `lineOnly` |
| `DcachePlugin` store-miss refill (`pendingStorePaddr`, LONG) | n/a | no — the SQ splits cross-line stores | `lineOnly` True; data never consumed |
| `ResetVectorFsm` (2x LONG via `extract`) | n/a | no — constant offsets 0 and 4 | safe by construction |
| `IcachePlugin` / `FetchAlignPlugin` window assembly | n/a | no — fixed compile-time word indices with an explicit next-beat source, no runtime wrapping index | safe by construction |

The STORE side of the byte lane is not affected: `storeStrb` computes its enable with a
WIDENING add, so a crossing store DROPS the far bytes rather than wrapping them, and every
store consumer already splits (`stSplitLow` / `fsSplitLow` / the SQ's explicit-strobe form).

## 8. Verification

Of the original two fixes (`676db27b`, `9b9b6665`):

* Lock-step, fail-before / pass-after at every odd SSP alignment: A-line trap
  plus RTE 8/8; LINK-stretch straight-line 24 plus 8 data-cache/crossbar
  variants; format-7 page fault 5/5; odd-SP store-forwarding sweep 12/12.
* `FsaveFrestoreSpec` 18/18. `fastTest` 347 passed, 0 failed, 2 ignored.
* `ExecuteLockStepSpec` 521 tests, 0 failures.
* Hardware, same SoC, instruments disarmed: cold 8/8 alive, warm 9/10,
  and the owner reached the Finder and ran an application.

Of the latent-consumer fixes plus the tripwire (`487a23b1`, `b03c51be`):

* `ByteLaneSplitSpec` 9/9 — including the tripwire's own four cases (silent at
  off 12/LONG and at off 15/WORD with `lineOnly`; kills the simulation at
  off 15/WORD and off 13/LONG).
* `ExceptionEntrySpec` 2/2 and `TableWalkerSpec` 3/3, each with the four
  offsets 12/13/14/15 and the fail-before shown in §6's table.
* `FsaveFrestoreSpec` 18/18.

`RobPluginSpec` "preciseDrainBusyIn holds a debugPcApply off an in-flight
precise drain" is a pre-existing seed flake that fails roughly two runs in three
on the unmodified base; it is unrelated.
