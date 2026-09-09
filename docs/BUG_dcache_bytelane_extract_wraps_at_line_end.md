# BUG: `DcacheByteLane.extract` wraps its byte index, so a non-AGU read at the end of a cache line returns bytes from the START of the same line

**Status**: ROOT CAUSE CONFIRMED and FIXED for the two reachable consumers
(`676db27b`, `9b9b6665`, merged to `integrate/cpu040-merged` as `c7fd193d`).
Two further consumers remain unguarded but are not reachable without an
architecturally misaligned VBR / URP / SRP; see §6.

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

## 6. Still open

Two `Size.LONG` reads on the same unguarded path:

* `ExceptionUnit.scala:1572` — the exception vector fetch. Needs `VBR mod 16`
  in {13, 14, 15}.
* `TableWalker.scala:121` — the table-walker root descriptor. Needs URP or SRP
  similarly misaligned.

Neither is reachable on this boot, because both require an architecturally
misaligned control register rather than merely an odd stack pointer. The right
permanent fix is to make `extract` unable to wrap (a simulation assertion at
minimum) instead of hand-splitting each consumer as it is discovered.

## 7. Verification

* Lock-step, fail-before / pass-after at every odd SSP alignment: A-line trap
  plus RTE 8/8; LINK-stretch straight-line 24 plus 8 data-cache/crossbar
  variants; format-7 page fault 5/5; odd-SP store-forwarding sweep 12/12.
* `FsaveFrestoreSpec` 18/18. `fastTest` 347 passed, 0 failed, 2 ignored.
* `ExecuteLockStepSpec` 521 tests, 0 failures.
* Hardware, same SoC, instruments disarmed: cold 8/8 alive, warm 9/10,
  and the owner reached the Finder and ran an application.

`RobPluginSpec` "preciseDrainBusyIn holds a debugPcApply off an in-flight
precise drain" is a pre-existing seed flake that fails roughly two runs in three
on the unmodified base; it is unrelated.
