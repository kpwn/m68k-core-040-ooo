# p133 (first CPU-hold-analysed bitstream) — decisive silicon test

**Date:** 2026-09-04
**Bitstream:** `/home/qwertyoruiop/macqd700-soc-worktrees/p133-hold-fixed-latest/build/vivado/fpga_top.bit`
**Live `build_id` read from silicon: `0x14F3C599`** — verified at the initial
12:10 load, again mid-session, again at the 12:52 reload, and by a final clean
read at 13:12 (`build_id = 0x14F3C599`, matches buildinfo) with the board sitting
at `pc = 0x40899706`.

*Precision note:* the `build_id` read fired automatically by the **13:01**
`load-bit` (boot D) returned `0x00000000` and logged a mismatch warning. That is
the known race where the first read lands before the debug CSR block leaves
reset (documented in `ioresult_stall_investigation.md:11168`), not a wrong
bitstream — the same p133 file was programmed, the boot proceeded normally, and
the final clean read confirms `0x14F3C599`.
**Control bitstream:** `.../m68k040ooo-integration/build/vivado_p123_100mhz/fpga_top.bit`,
live `build_id 0x1E0FB901`
**ROM the result was obtained on:** the SD card's ROM, verified this session as
**calibration-fix-only** (see §5). Not a patched ROM.

---

## 1. Headline

**p133 clears the `0x4084BECE` wedge. Decisively, and reproducibly across four
boots.** The same-session p123 control, on the same board and the same card,
still wedges at `0x4084BECE` with the exact documented signature.

This is the first time this campaign has produced **video output** on cpu040.
The machine now gets far enough to initialise the framebuffer, enable the MMU,
and run Mac OS Toolbox A-line traps.

It does **not** boot to Finder. It stops in one of several different places
depending on the boot — see §4. The stop is **non-deterministic**, which is
itself the most important new finding.

### Attribution — explicitly unresolved

p133 differs from p123 in **three** ways and this test cannot separate them:

1. CPU hold analysis enabled (the `set_max_delay -datapath_only` constraint fix)
2. `cpu040` bumped `7d74ba33 → bb3bca1` (merged night work)
3. The `CPUSHP`/`CINVP` page-scope granule fix (4 KB granule on an 8 KB-page machine)

**No claim is made here about which change is responsible.** The cheapest
discriminator is a build with the constraint fix alone. **That build was not run
(no builds this session — the mutex was held by the three-netlist hold-analysis
job throughout; verified with `flock -n /var/tmp/m68k-ooo-vivado.lock -c true` →
exit 1).**

---

## 2. The premise checked out independently

Before touching the board, p133's own artefacts were verified rather than assumed:

* `fpga_top.buildinfo` → `build_id=0x14f3c599`
* `timing_summary.rpt`, `fabric_clk100` intra-clock row:

```
fabric_clk100   WNS 1.105  TNS 0.000  0 failing of 271944
                WHS 0.011  THS 0.000  0 failing of 271896
Hold  :  0 Failing Endpoints,  Worst Slack  0.010ns,  Total Violation 0.000ns
```

Hold is **populated** — 271,896 endpoints analysed, closed at +0.011 ns. On
routed p123 the same row reads `Hold: NA`. The constraint fix did take effect.

---

## 3. The p123 control (same session, same board, same card)

Loaded ~35 minutes after p133, live `build_id 0x1E0FB901` confirmed.

`pc` sampled 22× over 3 minutes — **every sample** in the 3-instruction fixed
point, nothing else:

```
0x4084bece / 0x4084bed0 / 0x4084bed2   (429F clr.l (%sp)+ / 300F move.w %sp,%d0 / 66FA bne.s *-4)
```

Architectural state, matching the documented signature exactly:

| | p123 control | p133 |
|---|---|---|
| PC | `0x4084bed2` (fixed point) | varies, see §4 |
| D0 | `0x04000004` | — |
| A7 = ISP | `0x00000004` | `0x004007d0` / `0x00400842` |
| SR | `0x00002710` (IPL=7) | `0x00002708` / `0x00002000` (IPL=0) |
| VBR | `0x40846980` (**ROM** table) | `0x004007d0` / `0x00000000` (**RAM** table) |
| TC | `0x00000000` (**MMU off**) | `0x0000c000` (**MMU on**) |
| exc-ring | **completely empty**, head=0 | populated (A-line traps, IRQs) |
| inst-count | 3,046,275,349 | see §7 |

The control is clean. p133 changed the outcome; this is not environmental.

---

## 4. Where p133 actually stops — four boots, three different outcomes

**The stop is not reproducible.** This is the central new fact.

| # | Started | Path observed | Terminal state | Sad Mac (owner-read) |
|---|---|---|---|---|
| A | 12:10 `load-bit` | `0x408472fe` RAM-init loop → `0x40899664` → `0x4084a84a` → loop `0x4084afa6`/`0x4084a840`/`0x4084a966` | ROM serial/poll loop, MMU on, A-line traps running | checkerboard → **`0F 03`** |
| B | 12:40 reprogram | (not sampled early) | pinned `0x00300018`, ring 17× `vec=0x02` | **`0F 02`** |
| C | 12:52 reprogram | loop `0x4084afa6`/`0x4084a840`/`0x4084afca` | same ROM loop, A-line traps | **`0F 0A`** |
| D | 13:01 reprogram | `0x40847326` → **`0x4080a8e6`** (documented ADB busy-wait) → `0x40898eea` → **`0x40899706`** | pinned in 53C96 status poll, **no exception at all** | (none) |

### 4.1 Boot A/C — deep into Mac OS, then a fatal exception

At the `0x4084afa6` loop (boot A, halted 12:14):

```
VBR = 0x004007d0    TC = 0x0000c000 (MMU ENABLED)   SRP = 0x03feea00   MMUSR = 0x00400001
A2 = 0x50f00000   A3 = 0x50f0c020   A5 = 0x4084a840   A6 = 0x4084a848
A7 = ISP = 0x004007d0    SR = 0x00002708 (S=1, IPL=7)
```

`exc-ring` was **full of `vec=0x0a` (line-A / Toolbox trap)** entries, handler
`0x408099b0`, at PCs including `0x40802438`, `0x408024ca`, `0x4080b674`,
`0x4080b690` and **low-RAM PCs `0x0000a46e`, `0x0000a63c`, `0x0000a70a`,
`0x0000a754`**. The machine is executing real Toolbox trap dispatch.

Liveness: `inst-count` 6,542,967,456 → 7,768,530,369 over ~60 s — **~1.23 billion
instructions retired with zero new exceptions** in that window (ring head
unchanged). A live, exception-free loop, not a halt.

### 4.2 Boot D — the 53C96 poll, and it is a peripheral stall, not a CPU fault

`pc` pinned at `0x40899706` for >2 minutes. ROM code (verified against the board
byte-for-byte, then disassembled from `files/420dbff3.rom`):

```asm
40899704:  7a00           moveq  #0,%d5
40899706:  1a2b 0040      moveb  %a3@(64),%d5    ; 53C96 STATUS (reg 4)
4089970a:  0805 0007      btst   #7,%d5          ; bit 7 = INT
4089970e:  67f4           beqs   0x40899704      ; spin while INT clear
```

with `A3 = 0x50f0f000` → polling `0x50f0f040`. The INT bit never sets.

State: `SR = 0x00002000` (**IPL = 0, interrupts fully enabled**), `VBR = 0`,
`TC = 0x0000c000`, `CACR = 0x80008000` (both caches on), `A7 = 0x00400842`.

`exc-ring` shows **alternating `vec=0x19` (25, level-1 autovector) and `vec=0x1a`
(26, level-2 autovector)** at `pc=0x40899706`, handlers `0x40809b60` /
`0x40809b40`. **VIA interrupts are being taken and serviced normally** — the CPU
is healthy and responsive; only the SCSI interrupt never arrives.

This reproduces the "three boots earlier today wedged in a 53C96 status poll at
`0x40899706` (`A3=0x50F0F000`, reg 4, bit 7 = INT)" report exactly, so that PC is
now a reproducible p133 outcome. Adjacent prior art: `rtl/mac/scsi.v`'s
`c96_phase_bits()` gap.

### 4.3 Boot B — near the documented livelock

`pc` pinned at `0x00300018`. The documented p128 landmark is an illegal
instruction at `0x0030001e` → garbage handler `0x50300000` → access fault →
repeat, caused by vector-table stores stranded dirty in the D-cache. `exc-ring`
held 17 × `vec=0x02` (bus error / access fault) entries, all with
`pc=0`, `fa=0`, `handler=0`.

`D7 = 0x08000000` — the **Sad Mac fatal-bit** documented in
`docs/diag-buserror-frame-format.md`.

**Caveat, stated plainly:** in that same capture `PC` read `0x00000000`,
`inst-count` read `0`, and A3/A5/A7/VBR held garbage. I do **not** trust the
register file in that capture (see §7 on `inst-count`). The `pc = 0x00300018`
samples and the ring contents were read *before* halting and are trustworthy;
the post-halt `arch` dump is not.

---

## 5. ROM re-verification — calibration-fix only

Rather than spot-check six named sites, the board's ROM was read back over JTAG
and diffed **wholesale** against pristine `files/420dbff3.rom`.

**159,821 words compared (61.0% of the 1 MB image), contiguous over
`0x40800000..0x4089C130`** — a range that contains every known patch-site region
(the `0x4080390c` machine descriptor, the `0x4084xxxx` via-alias / io-oob probe
routines, and the `0x40899xxx` SCSI code).

**Exactly two differences:**

| address | stock | board | meaning |
|---|---|---|---|
| `0x40800000` | `0x420DBFF3` | `0x420D8602` | ROM checksum long, recomputed for the patch |
| `0x40800888` | `0x51C8FFFE` (`dbf %d0,…`) | `0x303CE799` (`movew #0xE799,%d0`) | **the approved `calibration-fix`** |

Both match the ~08:00 baseline values verbatim (`ROM[0] = 0x420d8602`,
`0x40800888 = 0x303ce799`).

**No unapproved patch was found anywhere in the compared 61%.** via-alias,
zonewalk, machine-descriptor-slot4, scsi-open-delay, bsrw-shim and io-oob-alias
sites are all **stock** — every byte in that range other than the two above is
byte-identical to the pristine image.

*Not covered:* `0x4089C134..0x40900000` (39% — high ROM, past all known patch
sites). The full read was stopped on the owner's instruction to save time.

---

## 6. The Sad Mac code table, derived from this ROM (authoritative)

The observed codes varied per boot (`0F 03`, `0F 02`, `0F 0A`), so the mapping
was derived from the ROM itself rather than from a remembered table.

Mechanism: the ROM vector table at **`0x40846980`** points each fatal vector at a
6-byte entry in a dispatch table beginning at **`0x40846a8a`**; entry *k* does
`oriw #((k+1)<<8),%d7`. All entries converge on `0x40846bf8` → `bset #24,%d7` →
`movel %sp,%d6` → `jmp 0x40849afa` (the Sad Mac entry). **D7 carries the code
(bits 8–15); D6 carries the stack pointer**, not the code.

| vector | meaning | Sad Mac code |
|---|---|---|
| 2 | Bus error | `0F 01` (via `0x40846A80`, after the `btst #27,%d7` recovery gate) |
| 3 | **Address error** | **`0F 02`** |
| 4 | **Illegal instruction** | **`0F 03`** |
| 5 | Zero divide | `0F 04` |
| 6 | CHK | `0F 05` |
| 7 | TRAPV | `0F 06` |
| 8 | Privilege violation | `0F 07` |
| 9 | Trace | `0F 08` |
| 10 | Line-A | `0F 09` |
| 11 | **Line-F** | **`0F 0A`** |
| 12 | reserved | `0F 0B` |
| 13 | Coprocessor protocol | `0F 0C` |
| 14 | Format error | `0F 0D` |
| 15 | Uninitialised interrupt | `0F 0E` |

So the three observed boots faulted with **three different exception vectors**:

* `0F 03` → **vector 4, illegal instruction**
* `0F 02` → **vector 3, address error**
* `0F 0A` → **vector 11, line-F**

### 6.1 Documentation correction

`docs/scsi_fuzz.md` (lines 406–455) records a historical "**Sad Mac `0F 02`**"
and labels it a **bus error**. Per this ROM's own tables that is wrong: `0F 02`
is **vector 3, address error**; a bus error paints `0F 01`. Either the recorded
screen digits or the label in that doc is mistaken. Flagged, not edited.

### 6.2 What the non-determinism means

Three different fault classes across three boots of the same bitstream, ROM and
card is **not** the signature of a single fixed decode gap. A specific missing
opcode would fault the same way every time. Landing on illegal-instruction,
address-error and line-F in turn is what you get when control flow reaches
*different wrong places* on different runs — i.e. a corrupted instruction stream
or branch target, not one bad instruction.

Because of that, the three known spurious-illegal candidates (**CMP2/CHK2
full-format EA, task #257**; **task #223 `move_idx_idx`**; **task #222 non-FPU
line-F format-0**) **could not be confirmed or excluded** — no faulting opword
was captured (§7), and none of them would explain an *address error* on a
different boot.

---

## 7. Instrumentation problems found (all real, all cost time)

Three tooling defects were hit. They matter for anyone repeating this.

### 7.1 `reset-and-break-pc` hangs the REPL hard — **needs a fix**

`reset-and-break-pc 0x40849afa 0 150000` on p133 (`0x14F3C599`) **never
returned**. The proc is a straight `unified_reset 1` → `arm_break_pc` →
`unified_reset_release` → `after $wait_ms` → print, so it should have printed at
+150 s. At +14 min it had emitted **nothing at all**, and every subsequent
command (`build-id`, `halt-status`) sat unread in `/tmp/jtag_in`. The Vivado
process was alive but blocked — i.e. a **JTAG-AXI transaction that never
completes**, with the CPU left held in reset.

Recovery required killing the REPL Vivado and reprogramming the FPGA.
(`hw_server` was left untouched throughout.)

This is consistent with the hazard already noted in `jtag_repl.tcl`'s own
`vio_reset_and_halt_after` comment: *"the current loaded bitstream cannot reliably
clear `cold_reset_hold` while the JTAG pulse path keeps `soc_full_rst`
asserted"*. The `reset-and-break-pc` path does not have the VIO workaround that
`vio_reset_*` has. **Handed to the main agent to fix.**

### 7.2 `vio-hard-reset` does not restart the CPU on p133

Contrary to the assumption that `vio-hard-reset` yields a fresh-DRAM cold boot,
after pulsing it the CPU **never started**: `inst-count = 0` and `pc = 0x00000000`
held for **3 minutes** (12 consecutive samples). Only a full `load-bit`
reprogram produced a working boot. Every p133 boot in §4 was therefore started by
reprogramming, not by `vio-hard-reset`.

### 7.3 `inst-count` is **not** reliable on this bitstream

The brief states `inst-count` is trustworthy and `exc_count` lies. Observed:
`inst-count` returned plausible, advancing values on boot A (6.54e9 → 7.77e9) but
returned **`0`** on boot D while the CPU was demonstrably executing (PC advancing,
IRQs being taken and logged in the ring). Treat `exc-ring` as the only trustworthy
liveness instrument; corroborate `inst-count` against `pc` movement before
quoting it.

---

## 8. What was NOT run

* **No Vivado build of any kind.** The mutex was held for the whole session.
* **No constraint-fix-only A/B build** — the one experiment that would settle
  attribution. Recommended as the immediate next step.
* **No capture of the faulting PC or opword** of any Sad Mac. The exception-halt
  arm (`halt-exc-mask raw 0 0x00000818`, vectors 3/4/11, enable confirmed
  `exc=1`) was live and correct, but the boot it was armed on took the **boot-D
  53C96 path and never faulted**. One attempt; not retried.
* **No MAME/Musashi cross-check** against the same ROM.
* **No `dis_at.sh`** — it produced no output (the JTAG side worked; the
  objdump/filter stage did not). Disassembly was done offline from
  `files/420dbff3.rom` after confirming the board's ROM matches it byte-for-byte.
* **ROM verification covers 61%** of the image, not 100% (§5).
* **Only one determinism pair of cold boots was of the same class** — boots A and
  C matched (same loop, Sad Mac), boots B and D did not. So "two cold boots
  confirm determinism" was **not** achieved; the opposite was observed.
* The SD card was not touched.

---

## 9. Recommended next steps

1. **Build with the constraint fix alone** against `cpu040 7d74ba33` — the only
   clean way to attribute the improvement.
2. **Fix `reset-and-break-pc`** (§7.1) — it is currently a board-wedging trap,
   and it blocked the faulting-opword capture this session.
3. Re-arm `halt-exc-mask` vectors 3/4/11 across **repeated** reprogram-boots
   until a fault boot is caught, then capture the faulting PC + opwords. This is
   the highest-value remaining measurement.
4. Investigate the `0x40899706` 53C96 INT-never-asserts stall (§4.2) — it is now
   a reproducible p133 outcome and is a peripheral-model gap, not a CPU fault.
