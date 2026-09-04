# p137 — three landed correctness fixes on silicon, and the settle-time artifact that nearly faked the result

**Date:** 2026-09-04
**SoC branch:** `build/p133-hold-fixed-latest` (worktree `p133-hold-fixed-latest`)
**cpu040 pin:** `bb3bca1` → `2db5bd3`
**Status:** build queued/【FILL】; boot test 【FILL】

---

## 1. What this was meant to test

Three fixes merged into `fmax-closure-fanout` today, each with a failing-before
test on unmodified RTL:

| commit | fix |
|---|---|
| `8887507` | no speculative instruction fetch into cache-inhibited pages |
| `7057e80` | `RTE` must resync `RobPlugin.committedCcr` |
| `2db5bd3` | MMU table walks routed through L1D |

The payoff question is narrow and was **not** assumed: do these change the Mac
boot outcome? They are correct on their own merits regardless of the answer.

---

## 2. Provenance

`bb3bca1..2db5bd3` is a clean fast-forward, 47 commits, nothing dropped —
p133's CPUSHP/CINVP page-granule fix (`bb3bca1`) is an **ancestor** of the new
pin and is retained. Verified with `git merge-base --is-ancestor`, and
`2db5bd3..bb3bca1` is empty.

The pin is recorded as a commit (`6fd2daa2`) because a previous build's recorded
pin disagreed with what was actually compiled.

**The socket-top port list is unchanged at 99 ports** (no additions, no
removals) across the bump, so the walker's removed AXI master — which changed
`fullcore_ports.golden` by 91 lines — does not disturb SoC wiring. That was
checked *before* Vivado, not discovered inside it.

---

## 3. The netlist guard is not theoretical here

`synth/vivado.tcl` regenerates `generated/M68kSocketTop.v` with sbt **inside**
the Vivado process, while the build mutex is held, and then checks only
`file exists`. A generation that fails while still exiting 0 therefore leaves a
**stale** netlist in place and Vivado compiles the OLD RTL under the NEW recorded
pin — exactly the "recorded pin disagreed with what was compiled" failure.

Mitigation used: the bb3bca1 netlist was **moved aside** before generating, so
freshness is proven by md5 rather than assumed.

```
fresh (2db5bd3): 20741064 bytes  md5 29a318a2a695d56b164fece00bae33c5
stale (bb3bca1): 20714858 bytes  md5 15c3746d617651574296e685e54a39c3
```

+26,206 bytes of real logic. Asserted `>= 1 MB` and non-identical before the
mutex was taken.

---

## 4. Two traps I walked into, recorded because both are cheap to repeat

1. **Nested backgrounding.** `nohup sbt … &` inside a `run_in_background` call
   made the completion notification fire for the *launcher*, not sbt. The first
   netlist assertion ran against a half-written file and reported a false
   `PREFLIGHT FAIL`. The assertion was right to fail loudly; the harness was
   wrong.
2. **`pgrep -f` self-match.** `pgrep -f GenSocketTopVerilog` matched the very
   shell running it. Both traps surfaced as assertion failures rather than false
   greens, which is the only reason they cost minutes and not a build.

---

## 5. THE MEASUREMENT DEFECT THAT NEARLY FAKED A RESULT

### 5.1 Settle time, not build quality

| settle | outcome |
|---|---|
| 150 s | **5/5** "bus-error livelock at `0x0030001x`" |
| 180 s | Happy Mac (SCSI INT-poll hang), with nothing else changed |

The 150 s samples were taken **mid-boot**. The ROM legitimately provokes ~15 bus
errors on its SCSI probe path, and those runs' exception rings held **13–14** bus
errors at `handler=0x00300000`. That is *normal boot progress*, misread as a
terminal failure because the PC happened to sit inside the probe loop across the
whole sampling window.

### 5.1a CORRECTION — there are TWO signatures under `0x0030001x`, and only one is an artifact

A later boot at **200 s** settle still failed, which the settle story alone does
not explain. Separating the exception rings shows two distinct things that my
classifier was lumping into one bucket:

| | signature (A) | signature (B) |
|---|---|---|
| PC | `0x00300014` | `0x0030001e` |
| ring | all `vec=0x02`, `fa=0x407fde2e`, `handler=0x00300000` | `vec=0x04` → `handler=0x50300000`, then `vec=0x02` **at** `0x50300000` |
| reading | the ROM's legitimate SCSI-probe bus errors — *boot in progress* | **wild jump into device space, livelocking** — a real terminal failure |
| survives 200 s? | no | **yes** |

So: the premature-sampling explanation covers (A) only. **(B) is a genuine
failure mode**, and it is the reference table's "Sad Mac `0F/02` → livelock at
`0x0030001x`".

Signature (B) is independently interesting: `handler=0x50300000` puts the
architectural control flow **inside the `0x50xxxxxx` device region**, which is
the address family this campaign has been trying to connect to the frontend.
Observed here on **p133**, i.e. *without* the speculative-fetch fix. A corrupt
exception-vector entry explains it without invoking speculation, so this is
**not** offered as evidence for the speculative-fetch story — but whether p137
removes it is exactly the discriminator to watch.

**A hang and a boot-in-progress are not distinguishable from PC samples alone.**
The harness now reads the exception-ring head before and after the sampling
window; if it advanced, the machine is still taking exceptions and is not wedged.

### 5.2 A retraction

From that 5/5 run I inferred that boot outcomes are strongly autocorrelated
within a session (5 consecutive draws from a 25 % reference category, p≈0.1 %).
**That inference is retracted.** The settle-time confound explains the run
without it. Interleaved A/B remains the better design, but for the ordinary
reason (pairing removes board/session/tooling differences), not the one I first
claimed.

### 5.3 A false positive I shipped and then removed

The first classifier's last branch was "no sample matched a ROM address" →
*candidate: reached the System/Finder*. A **stopped** CPU reports
`pc_live=0x00000000`, which is not a ROM address — so a dead machine classified
as a successful boot. One baseline boot did exactly this, with a corrupted icon
on a black screen and a ring full of bus errors. Success is now claimed only
from positive evidence.

### 5.4 I armed a vector in a run whose purpose was to arm nothing

The disarm loop used `halt-exc-mask 0`. That is the **set** form
(`halt-exc-mask <vec>`), so it armed exception vector 0, and the REPL's
enable-sync then turned `halt_exc_enable` on — every boot started with
`enables={… exc=1 …}`. Confirmed on the board:

```
BEFORE: lane0 = 0x00000001   active = 1   enables={ha=0 bp=0 exc=1 pcmis=0}
AFTER : lane0 = 0x00000000   active = 0   enables={ha=0 bp=0 exc=0 pcmis=0}
```

Vector 0 is never taken during execution, and the affected boots were discarded
anyway. The correct disarm is `halt-exc-mask raw <lane> 0` across all eight
lanes, and the harness now **asserts** the enables line rather than assuming it.

---

## 6. Screen signature key

Screen md5 alone classifies the outcome, which makes this cheap:

| md5 | meaning |
|---|---|
| `2f5de0f4` | Happy Mac, dithered desktop |
| `eed68f0c` | Sad Mac `0000000F`/`00000003` — address error |
| `feaec0e2` | Sad Mac `0000000F`/`0000000A` — A-line |
| `9086c68c` | partial/corrupt icon on black — early-failure family (no Sad Mac code drawn) |
| `f8e8fd03` | fully black — early-failure family (seen with BOTH signature (A) at 150 s and the genuine signature (B) at 200 s) |

The two black-ish screens do **not** map cleanly onto signatures (A) and (B) —
both were observed with `f8e8fd03`. Screen md5 is a reliable key for the three
*drawn* outcomes (Happy Mac, `0F/03`, `0F/0A`); for the early-failure family the
**exception ring**, not the screen, is what separates a boot in progress from a
wild-jump livelock.

Note the `vec=0x0a` entries in an A-line boot's ring are ordinary Toolbox
dispatch; only the **screen** disambiguates `0F/03` from `0F/0A`.

---

## 7. p133 baseline, this session — 12 boots, and it reproduces the reference

Live `build_id 0x14F3C599`, `cpu=m68k040`. Plain `reset`, nothing armed, settle
180 s (4 boots) and 200 s (8 boots).

| outcome | this session (12) | recorded reference (12) |
|---|---|---|
| Happy Mac → 53C96 INT-poll hang | **7 (58 %)** | 6 (50 %) |
| Sad Mac → ROM serial monitor (`0F/03`, `0F/0A`) | 3 | 3 |
| bus-error livelock `0x0030001x` (`0F/02`) | 2 | 3 |
| **Welcome to Macintosh / desktop / Finder** | **0** | **0** |

Close agreement. This matters more than it looks: it means the board is behaving
normally tonight, so a p137 number measured against it is interpretable. It also
retires the worry — raised and then disproved during this session — that the
board had degraded into a permanent failure mode.

Per-boot detail (PC / screen md5):

```
180s  1  0x40899706  2f5de0f4  Happy Mac
180s  2  0x40899706  2f5de0f4  Happy Mac
180s  3  0x4084afa6  eed68f0c  Sad Mac 0F/03 -> serial monitor
180s  4  0x4084afa6  feaec0e2  Sad Mac 0F/0A -> serial monitor
200s  1  0x40899706  2f5de0f4  Happy Mac
200s  2  0x40899706  2f5de0f4  Happy Mac
200s  3  0x40898cae  2f5de0f4  Happy Mac (SCSI poll, different PC window)
200s  4  0x40899706  2f5de0f4  Happy Mac
200s  5  0x4084afa6  eed68f0c  Sad Mac 0F/03 -> serial monitor
200s  6  0x40899706  2f5de0f4  Happy Mac
200s  7  0x0030001e  f8e8fd03  bus-error livelock, signature (B) -> 0x50300000
200s  8  0x00300018  f8e8fd03  bus-error livelock, ring degenerate (all-zero vectors)
```

Boot 8's ring is worth noting separately: every entry reads
`vec=0x02 pc=0x00000000 fa=0x00000000 handler=0x00000000`, i.e. the machine is
faulting through a **zeroed vector table** — a third variant under the same
`0x0030001x` bucket, distinct from both (A) and (B).

**The `0x40899704` SCSI INT-poll hang is present and dominant on p133**, exactly
as the reference says.

---

## 8. p137 result

### 8.1 The build

| field | value |
|---|---|
| live `build_id` | **`0x9C1FA4B5`** (verified twice; first read after `load-bit` returned `0x00000000` — the documented read-before-CSR-reset race — and the re-read corrected it) |
| `cpu` | `m68k040`, `DBG_VERSION = 0xDEB60100` (a real core, not the stub) |
| cpu040 pin compiled | **`2db5bd3710f6fa990b1fe0944fe5338d54da2418`** |
| timing | **WNS `+0.072` ns, TNS `0.000`, failing endpoints `0`** at the 100 MHz SoC target |

Timing was reported, not gated, per the owner's direction. It happens to pass
cleanly.

### 8.2 p137 wedges at `0x40806b68` with interrupts dead

Every p137 boot observed parked at `pc_live=0x40806b68`, an address that never
appeared in any of the 12 p133 boots. A 10-minute single-boot trajectory settles
what a single sample could not:

```
 t(s)   pc_live      head   screen
   34   0x40806b68     19   9086c68c
  ...   0x40806b68     19   9086c68c     <- unchanged for 9.5 minutes
  579   0x40806b68     19   9086c68c
```

**The exception-ring head never advances past 19.** On a healthy boot the 60 Hz
VIA1 level-1 interrupt churns `vec=0x19`/`0x1a` continuously — p133's Happy Mac
boots show exactly that. Here **no exception of any kind is taken for 9.5
minutes**, so this is not a slower boot; the machine is wedged and interrupts are
not being recognised.

The ring's last entries are all Toolbox A-line traps in ROM:

```
exc[18] vec=0x0a pc=0x4080589a fa=0x00000000 handler=0x408099b0
exc[17] vec=0x0a pc=0x408068c8 fa=0x00000000 handler=0x408099b0
...
```

i.e. the ROM was executing Toolbox dispatch normally and then stopped taking
exceptions altogether.

**This is a regression against p133 on the same board, same session, same
procedure.** It is the opposite of the hoped-for result and is reported as such.

### 8.3 The paired A/B control — p133 recovers immediately, p137 does not

The decisive objection to §8.2 is "the board drifted". So the two bitstreams were
alternated in one session, identical cycle per arm, with the p133 artifact
preserved (`md5 d809fd9f…`) before the build could overwrite it.

Three pairs, alternating, ~4 minutes apart. **The separation is total — 3/3.**

| pair | arm | live `build_id` | `pc_live` | screen | exception ring over the window |
|---|---|---|---|---|---|
| 1 | p133 | `0x14F3C599` | `0x40899706` | `2f5de0f4` | 26 → 11 (wrapped) — **alive** |
| 1 | p137 | `0x9C1FA4B5` | `0x40806b68` | `9086c68c` | **static at 13** |
| 2 | p133 | `0x14F3C599` | `0x40899706` | `2f5de0f4` | 21 → 12 — **alive** |
| 2 | p137 | `0x9C1FA4B5` | `0x40806b68` | `9086c68c` | **static at 16** |
| 3 | p133 | `0x14F3C599` | `0x40899706` | `2f5de0f4` | 23 → 15 — **alive** |
| 3 | p137 | `0x9C1FA4B5` | `0x40806b68` | `9086c68c` | **static at 16** |

p133: 3/3 Happy Mac, interrupts churning (`vec=0x19`/`0x1a` → handlers
`0x40809b60`/`0x40809b40`). p137: 3/3 wedged at the same PC with a frozen ring.

p133 booted normally minutes after p137 wedged, on the same board, same ROM,
same SD card, same procedure. **The regression is attributable to the cpu040
bump, not to board drift.**

The `liveness` line is what makes this readable at a glance, and it is the
instrument I did not have when I first misread p133's mid-boot probe loop as a
hang. `exc_count` and `inst-count` were not used — both are known to lie.

---

## 9. What this does and does not license

**Does not** invalidate the three fixes. Each has a failing-before test on
unmodified RTL, and `7057e80` is independently confirmed on silicon (Part 136).
The wedge is a *bring-up* failure of the combined bump on this SoC, not a
demonstration that any of the three is wrong.

**Does not** identify which of the three fixes causes it. Three commits landed
together (plus 44 intervening ones). The bisect is the obvious next step and was
not run — see §10.

**Does not** support the `0x50F0Fxxx` frontend-steering story. The one place
device-space control flow was observed tonight (`handler=0x50300000`, signature
(B)) was on **p133**, *without* the speculative-fetch fix, and a corrupt
exception-vector entry explains it without speculation. Nobody has yet shown the
frontend is steered into `0x50F0Fxxx` during boot, and this session does not
change that.

**Does not** speak to the CCR→SP-2-low link. No boot here exercised it, and
corrupted flags steering a branch around a stack adjustment remains unshown.

**Does** establish, with a paired within-session control, that the p137 bitstream
does not boot this Mac as far as p133 does.

## 10. What was NOT run

* **No bisect** of `bb3bca1..2db5bd3`. This is the single highest-value next
  step: three fixes plus 44 other commits landed together, and the wedge is
  100 % reproducible, so a bisect over ~6 bitstreams would isolate it cleanly.
* **No `axi_i` bus capture.** It was the planned next experiment if the SCSI hang
  persisted; the machine does not now reach the SCSI poll on p137, so the
  premise changed.
* **No disassembly of `0x40806b68`** to identify what the ROM is doing there, and
  no read of the interrupt-mask/SR state at the wedge — either would sharpen
  "interrupts are not being taken" into a mechanism.
* **The 12-boot p137 distribution was abandoned after 2 boots** in favour of the
  trajectory and the paired A/B, which answered the question more decisively.
  p137 was observed wedged at `0x40806b68` on **every** boot attempted.
* **No SD card access of any kind.** No ROM patch. The ROM baseline is untouched.
* `reset-and-break-pc` was never used (hangs the REPL). `vio-hard-reset` was
  never used (does not restart the CPU). No vector was armed for any measurement
  reported here.
* **No 200 MHz gate.** Timing was reported, not gated, per the owner's
  direction; the SoC build met its 100 MHz target with zero failing endpoints.
