# Free-boot campaign on p133 — the Happy Mac is real, and so is the hang behind it

**Date:** 2026-09-04
**Bitstream:** `/home/qwertyoruiop/macqd700-soc-worktrees/p133-hold-fixed-latest/build/vivado/fpga_top.bit`
**Live `build_id`: `0x14F3C599`** — read from `OFF_BUILD_ID` and matched against
`build/vivado/fpga_top.buildinfo` at session start **and** at session end. Both reads
returned the value directly; the documented read-before-CSR-reset `0x00000000` race did
not occur (no `load-bit` was issued this session, which is presumably why).
**Vivado builds run:** none. `flock -n /var/tmp/m68k-ooo-vivado.lock -c true` reported the
mutex FREE at session start and it was never taken.
**SD card:** not touched. **ROM:** not touched, not patched, not re-verified.
**`hw_server`:** left alone. **MJPEG panel / `/dev/video0` producer:** left alone.
**JTAG lease:** acquired as `free-boot-p133`, released at end.

---

## 1. Headline

Three findings, in order of importance.

1. **The Happy Mac is real, it is the live framebuffer, and it is freshly drawn on every
   boot that reaches it.** Not a stale image, not the FPGA boot splash. Proven three
   independent ways (§3).

2. **The hang behind the Happy Mac is NOT an artefact of the armed exception halt.**
   With the halt mask verified all-zero across all eight lanes and every halt enable
   clear, the machine still stops dead. It stops in a ROM loop that has **no exit other
   than a 53C96 interrupt that never arrives** — a genuine, unbounded spin, not a
   debugger halt (§4).

3. **The boot is a coin-flip, and the good side of the coin is the Happy Mac.**
   12 free boots: **6 reach the Happy Mac and hang**, 6 die earlier with a Sad Mac (§5).
   No boot ever reached a "Welcome to Macintosh" box, a desktop, a cursor, or the Finder.

The brief's hypothesis — that vector 11 (line-F) was firing on legitimate FPSP dispatch
and halting a healthy machine — is **not what is happening.** In the Happy-Mac park the
exception ring contains **only** `vec=0x19` and `vec=0x1a` (autovector levels 1 and 2).
No line-F, no A-line, no vector 3 or 4 ever appears. The previously-armed vectors 3/4/11
would not have fired in this state at all. The hang is real and it is in the ROM's SCSI
driver.

---

## 2. Method

Every boot used exactly this sequence, driven through `tools/csc21_send.sh` (tagged
send/ack, so no output can be misattributed):

```
break-pc off
halt-exc-mask raw 0 0x00000000
halt-clear
halt-release
halt-status                     <- verified enables={ha=0 bp=0 exc=0 pcmis=0}
tcl ... paint framebuffer marker bar ...   (boots C..L only, see §3)
reset                           <- plain cold-reset pulse, "reset done (pulse, no hold)"
  then, every ~18 s: halt-status  (pc_live only; the CPU is NEVER halted)
  and in parallel, every 1 s: an HTTP GET of the display
finally: exc-ring 32, pc-trace 32, video-status
```

`halt-exc-mask` with no argument was dumped before the campaign and showed **all eight
lanes zero** and `active (non-zero mask) = 0`; it was re-checked at the end and was still
zero. `halt-status` reported `effective=0` (not halted) and `exc_halt=0` on **every one of
the ~150 samples taken across 12 boots**. Nothing was ever armed, and nothing ever halted.

### 2.1 The display was read without touching the board at all

`/dev/video0` is held by a long-running `fpga_mjpeg_server.py` (pid 2902294, listening on
`10.200.0.12:8080`, up since 2026-08-24) which the project owner uses as a live panel.
Rather than fight it for the device — `tools/fpga_video_capture.sh still` fails with
`Device '/dev/video0' is busy`, which is exactly right and it should not be killed — every
frame in this campaign was fetched with

```
curl -s http://10.200.0.12:8080/snapshot.jpg
```

That is a pure HTTP read of a frame the server already had. It touches neither JTAG nor
the FPGA, so display observation cost the experiment nothing and could run at 1 Hz
continuously, in parallel with the JTAG sampling, for the whole campaign.

`/healthz` was checked to confirm the capture was live rather than serving a cached still
(a documented 12.3-hour failure mode of this server): `frames_total` incremented
continuously and `age_s` stayed at ~0.003–0.014 s throughout.

**This is a reusable technique and it is strictly better than `fpga_video_capture.sh` for
agent work: it needs no device access, cannot disturb the owner's panel, and gives a
1 Hz change-log of the display for free.**

---

## 3. The Happy Mac is genuinely drawn by each boot — three proofs

This mattered because a plain `reset` is a **CPU** reset: it does not clear DDR, and the
DAFB keeps scanning out whatever was in video RAM. A Happy Mac left over from a previous
boot would look identical to a fresh one.

### 3.1 It is the framebuffer, not the FPGA boot splash

`video-status` on every Happy-Mac boot:

```
video mode  : COMMITTED hres=640 vres=480 bpp_shift=3 bytes_per_px=0 scale_sel=1
video source: dafb_live=1 (showing the DAFB framebuffer)
video place : COMMITTED fb_base=0x00001000 fb_stride=0x00000400 (1024 bytes/row)
```

`dafb_live=1` means the boot-splash substitution gate in
`rtl/board/video_phy/scanout_display.v` is **off** and the real framebuffer is on screen.
Independently, that file's `splash_bitmap` ROM is an Apple-logo-shaped bitmap — it is not
and cannot be a Happy Mac.

### 3.2 The icon is in video RAM, at the exact centre of a 640x480 1bpp screen

Low-memory `ScrnBase` (`0x0824`) reads **`0xF9001000`** — the Quadra 700 DAFB aperture
plus the same `0x1000` the DAFB reports. `0x0838` reads `0x01E00280` = 480, 640. Reading
the aperture over JTAG at row 240 (`0xF9001000 + 240*1024 = 0xF903D000`):

```
0xF903D000 = 0xaaaaaaaa      <- 50% gray desktop dither
...
0xF903D024 = 0xaaaab200      <- icon bits start
0xF903D028 = 0x009aaaaa
0xF903D02C = 0xaaaaaaaa
```

Row 240 of 480, byte offset 0x25 of 80 → pixel column ~296 of 640. Dead centre, both axes.

### 3.3 The screen is demonstrably repainted from scratch on every reset

Before each reset from boot C onwards, a **black marker bar** was painted straight into
video RAM over JTAG (rows 100..131, full 640-pixel width, 640 word writes issued inside
the REPL via its `tcl` passthrough so they cost one round trip, not 640). The bar is
plainly visible in the captured frame (`caps/marked.jpg`, md5 `134633ee`, 181511 bytes).

The 1 Hz change-log then shows the same four-step sequence at **every single reset**,
completing in about 3 seconds:

```
14:34:50  md5=134633ee  181511 bytes   <- previous screen WITH the marker bar
14:34:51  md5=9086c68c   45657 bytes   <- blanked
14:34:52  md5=8e1c2aef  188915 bytes   <- gray desktop, NO icon yet
14:34:53  md5=2f5de0f4  189361 bytes   <- gray desktop + Happy Mac
```

The marker bar is gone; a gray-desktop-without-icon frame exists as a distinct
intermediate state; then the icon appears. Boot E is even more conclusive — it started
from the *fully black* screen boot D had left behind (`f8e8fd03`, 43811 bytes) and still
produced the identical `188915 → 189361` sequence.

**The ROM clears the screen, fills the desktop, and draws the Happy Mac, ~3 s into every
boot that gets that far.** All six Happy-Mac frames are byte-identical (`2f5de0f4`,
189361 bytes).

---

## 4. Where a Happy-Mac boot actually stops

`pc_live` in all six Happy-Mac boots is confined to four addresses, and `pc-trace`
confirms the CPU is cycling among exactly those four:

```
40899704:  7a00           moveq  #0,%d5
40899706:  1a2b 0040      moveb  %a3@(64),%d5      ; 53C96 Status register (base+0x40)
4089970a:  0805 0007      btst   #7,%d5            ; bit 7 = INT
4089970e:  67f4           beqs   0x40899704        ; spin until INT is set
```

`%a3` is the 53C96 base (`0x50F0F000`); the Mac spaces the 53C9x registers 16 bytes apart,
so `+0x40` is register 4 = **Status**, and bit 7 is **INT**. `+0x30` = Command,
`+0x50` = Interrupt Status, `+0x70` = FIFO Flags — every access in the surrounding code
decodes consistently under that mapping.

**This loop has no timeout and no other exit.** It is not `dbne`-bounded, there is no tick
deadline, nothing. Contrast the *bounded* twin at `0x4089972a`, which does the same wait
under a two-level `dbne` counter seeded from low-memory `0xB24`, and the phase-wait at
`0x40898c9a` which is bounded by a `Ticks` (`0x16a`) deadline. The ROM has bounded waits
available and this call site is not one of them.

`0x40899704` is a shared helper with **15 `bsr.w` call sites** across the SCSI driver
(`0x408990b8`, `0x408990d6`, `0x4089938e`, `0x408993b8`, `0x40899464`, `0x40899478`,
`0x408994a2`, `0x40899576`, `0x408995a4`, `0x4089966e`, `0x408996b0`, `0x408997bc`,
`0x408997f0`, `0x408998f0`, `0x4089991a`). **Which one is on the stack was not
determined** — that needs a halt, and this campaign did not halt.

Boot A was left in this state for **360 seconds** and never moved. The exception ring over
that window is nothing but normal interrupts:

```
exc-ring tally (pc → count in window):
  0x40899706 × 28
  0x4089970a × 4
```

all `vec=0x19` (autovector 1, `handler=0x40809b60`) and `vec=0x1a` (autovector 2,
`handler=0x40809b40`). **Zero faults.** The machine is alive, correctly taking and
returning from VIA interrupts, and waiting forever for a SCSI interrupt.

So: the ROM completed POST, initialised video, drew the desktop and the Happy Mac, and
then wedged in its own SCSI driver waiting on an INT the 53C96 model never raises.

> **Caveat, stated plainly:** the Happy Mac being on screen is not by itself proof that
> boot blocks were read and the System file was reached. It proves the ROM got far enough
> to draw that icon. The stop is inside the SCSI driver, so a reading of "it drew Happy
> Mac and then failed on a *subsequent* SCSI transfer" is equally consistent with what was
> measured. Distinguishing those needs the caller identity from §4, which was not taken.

---

## 5. Boot-outcome distribution — 12 free boots, nothing armed

| boot | screen | Sad Mac code | final `pc_live` | outcome class |
|---|---|---|---|---|
| A | Happy Mac | — | `0x40899706` | 53C96 INT-poll hang |
| B | Sad Mac → black | `0000000F / 00000002` | `0x00300018` | wild jump, bus-error livelock |
| C | Sad Mac | `0000000F / 00000003` | `0x4084afa6` | ROM serial monitor |
| D | Sad Mac → black | `0000000F / 00000002` | `0x0030001e` | wild jump, bus-error livelock |
| E | Happy Mac | — | `0x40899706` | 53C96 INT-poll hang |
| F | Happy Mac | — | `0x40899706` | 53C96 INT-poll hang |
| G | Happy Mac | — | `0x40899706` | 53C96 INT-poll hang |
| H | Sad Mac → black | `0000000F / 00000002` | `0x00300018` | wild jump, bus-error livelock |
| I | Sad Mac | `0000000F / 0000000A` | `0x4084a840` | ROM serial monitor |
| J | Sad Mac | `0000000F / 0000000A` | `0x4084afa6` | ROM serial monitor |
| K | Happy Mac | — | `0x40899706` | 53C96 INT-poll hang |
| L | Happy Mac | — | `0x40899706` | 53C96 INT-poll hang |

| outcome | count | share |
|---|---|---|
| **Happy Mac, then unbounded 53C96 INT-poll hang** | **6** | 50% |
| Sad Mac `0F/02` (bus error) → bus-error livelock at `0x0030001x`, screen goes black | 3 | 25% |
| Sad Mac `0F/0A` (A-line) → ROM serial monitor `0x4084axxx` | 2 | 17% |
| Sad Mac `0F/03` (address error) → ROM serial monitor `0x4084afa6` | 1 | 8% |
| Welcome to Macintosh / desktop / Finder / cursor | **0** | 0% |

The second Sad Mac line is the exception vector number: `02` bus error, `03` address
error, `0A` line-1010. All three appeared. Observation windows were 150–360 s per boot;
in every case the state was reached within ~5 s and never changed afterwards.

Screen frames are byte-identical within each class, which is a useful classifier on its
own: `2f5de0f4`/189361 = Happy Mac, `4fc2e356`/45684 = Sad `0F/02`, `eed68f0c`/45666 =
Sad `0F/03`, `feaec0e2`/45687 = Sad `0F/0A`, `f8e8fd03`/43811 = fully black.

### 5.1 The `0x0030001x` livelock is the previously-documented boot-B class

Boots B/D/H end in a bus-error livelock that Part 131 §5.1 predicted offline and this
campaign observed live:

```
exc[0..31]  vec=0x02  pc=0x00300018  handler=0x00300000
            fa= 0x6055c27a, 0x605a363e, 0x605ec082, ... 0x60e9f1b6   (walking upward)
```

32 of 32 ring entries, fault address climbing monotonically — the `cmpa.w (a3)+,a3` /
`blt.s` self-sustaining loop that uninitialised DRAM (`B6DB6DB6…`) disassembles into.
Note `handler=0x00300000`: **the vector table itself is garbage**, pointing back into the
same DRAM. In these boots the DAFB also gets reprogrammed to a nonsense geometry
(`hres=1238 vres=0`, and once `hres=2810 vres=0`) — hence the black screen.

Boot H is a caution about sampling rate: at 18 s intervals it looked like it went straight
to black, but the 1 Hz display log caught the Sad Mac `0F/02` at +3 s and the blackout at
+12 s. **Sample the display faster than you sample the CPU.**

---

## 6. Why no halt was armed, even though the machine does hang

The brief permitted arming vectors 2 and 3 as a fallback. It was deliberately not done,
for a measured reason:

**Vector 2 is a legitimate, expected event on this exact ROM path.** Part 131 recorded 13
bus errors at `0x40899664` and 2 at `0x4089931c`, all `fa=0x50F4F100`, all correctly
recognised by the ROM as the expected result of probing a SCSI alias address. Arming
vector 2 would halt a *healthy* boot on a *deliberate* ROM probe — the identical mistake
the brief warns about for vector 11. Vector 3 would not have fired at all in the six
Happy-Mac boots, whose rings contain nothing but autovector interrupts.

The information an armed run would have added (the first fault's PC on the Sad Mac boots)
is largely already on record from Part 131's deterministic vector-3 capture at
`0x408999E2`. It was judged not worth halting healthy boots for. **This is a choice, not a
completed measurement, and §8 lists it as not run.**

---

## 7. Instrumentation notes (measured this session)

* **Plain `reset` reboots the CPU reliably** — 12/12, confirming the prior session's
  correction. `halt-status` reads `pc_live=0x00000000` immediately after the pulse and the
  CPU is executing ROM again within ~3 s. The "~15 s to first instruction" figure applies
  to a `load-bit` (MIG recalibration); a bare `reset` pulse is far faster. **A full free
  boot attempt including observation costs ~3 minutes, and the machine's final state is
  settled within ~5 seconds.**
* **The halt-exception mask survives `reset`** (debug reset domain) — re-confirmed.
* **`exc-ring` is the reliable liveness instrument**, as documented. `exc_count` read
  `0x00000000` throughout while the ring was demonstrably filling.
* `reset-and-break-pc` was **not** used (known REPL-hanging hazard on this bitstream).
* `vio-hard-reset` was **not** used.
* The REPL's `tcl` passthrough is the right tool for bulk memory writes — 640 writes in one
  round trip instead of 640. `csc21_send.sh` uses the same passthrough for its own ack
  tag, and the two coexist fine as long as your command's echoed result is distinct.
* `dbg-caps` on this bitstream: `perf_counters`, `watchpoints`, `atrap_bp`, `fault_snap`
  and `dcache_probe` are all **NO**. Do not plan a p133 experiment around them.

---

## 8. What was NOT run

* **No Vivado build of any kind** — no synthesis, no implementation, no bitstream.
* **No ROM patch**, no ROM read-back, no ROM re-verification. The prior session's
  calibration-fix-only verification is taken as given.
* **No SD-card access** of any kind.
* **No halt, ever.** No `break-pc`, no vector arming, no `advance`, no `live-arch`. Every
  CPU observation was a CSR read of a free-running core. Consequently:
  * **The caller of `0x40899704` was not identified** — that needs the stack, which needs
    a halt. This is the single most valuable next measurement.
  * **No register state, no stack frames, no faulting-PC capture** for any of the six Sad
    Mac boots. The `0F/02`, `0F/03` and `0F/0A` classifications come from the pixels of
    the Sad Mac's own hex display, not from a captured exception frame.
* **The 53C96 model was not read or investigated.** `rtl/mac/scsi.v`'s `c96_phase_bits()`
  was skimmed only far enough to confirm it *does* produce `3'b011` for `S_STATUS`, so the
  memory note about an unproducible phase value does not apply to the phase-3 wait at
  `0x40898cae`. Why the model never raises the Status-register INT bit in the parked state
  was **not** determined. Reading the device registers over JTAG was avoided because
  `+0x50` (Interrupt Status) is read-to-clear and a stray JTAG read could destroy the very
  state under observation.
* **No simulation, no MAME/Musashi cross-check, no lock-step run.**
* **The `exc_partial_macro_move_mem_mem` hypothesis was neither confirmed nor refuted.**
  This campaign produced no new evidence for or against it; the in-flight simulation work
  is unaffected by anything here.
* **No attempt to make the machine boot further.** No workaround, no nudge, no register
  poke to unstick the SCSI poll.
* Boots were run back-to-back over ~35 minutes on one thermal/power state. No claim is made
  about whether the 50/50 split holds cold, or over hours.

---

## 9. Recommended next steps

1. **Catch the caller.** Arm `break-pc 0x40899704` on a boot that reaches Happy Mac (it is
   a 50% chance per attempt and each attempt costs ~3 minutes), then read the stack. That
   names which of the 15 SCSI operations is waiting, which names the 53C96 command whose
   completion interrupt is missing. Everything downstream depends on this one fact.
2. **Then, and only then, look at `rtl/mac/scsi.v`'s interrupt generation** for that
   specific command. Do not audit the SCSI model speculatively first.
3. **Treat the Sad Mac boots and the Happy Mac boots as one bug, provisionally.** Six
   boots produce three different fault vectors at three different places, which is the
   signature of a control-flow divergence landing in three different garbage sites — the
   Part 131 reading. The Happy-Mac boots are the ones where the divergence did *not*
   happen; they get further and expose the *next* blocker, which is the SCSI INT wait.
   Fixing the divergence should raise the Happy-Mac rate toward 100% and make the SCSI
   INT wait the sole remaining blocker.
4. **Adopt the HTTP snapshot loop as standard board instrumentation** (§2.1). Its 1 Hz
   change-log caught a Sad Mac that the 18 s CPU sampling missed entirely.
