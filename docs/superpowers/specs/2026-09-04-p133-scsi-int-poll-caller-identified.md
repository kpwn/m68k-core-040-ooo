# The 53C96 INT-poll caller, identified: a single-byte SCSI **DATA IN** transfer

**Date:** 2026-09-04
**Bitstream:** `/home/qwertyoruiop/macqd700-soc-worktrees/p133-hold-fixed-latest/build/vivado/fpga_top.bit`
**Live `build_id`: `0x14F3C599`** — verified against `fpga_top.buildinfo` at session start **and**
again at session end. Both matched.
**Vivado builds run:** none. `flock -n /var/tmp/m68k-ooo-vivado.lock -c true` reported the mutex
FREE at session start; it was never taken.
**SD card:** not touched. **ROM:** not touched, not patched. **`hw_server`:** left alone.
**MJPEG panel / `/dev/video0`:** left alone (display read only over HTTP).
**JTAG lease:** acquired as `scsi-caller-hunt`, released at end.
**53C96 registers read over JTAG: ZERO.** See §7.

---

## 1. Headline

**The caller is `jsr %pc@(0x40899704)` at `0x40899276`.** The waiting operation is a
**single-byte SCSI DATA IN transfer**: the ROM has written command `0x10`
(**TRANSFER INFORMATION**) to the 53C96 Command register at `0x50F0F030` and is waiting for the
completion interrupt so it can read **one byte** out of the FIFO at `0x50F0F020`.

Four further results, each independently checkable:

2. **There are 18 call sites to `0x40899704`, not 15.** The prior session's scan looked only for
   `bsr.w` and missed three `jsr %pc@(...)` sites — `0x40899224`, `0x40899246`, `0x40899276`. **The
   actual caller is one of the three that were missing from the list**, which is why it could not
   have been identified by elimination against the old list.

3. **The ROM's timeout for this wait is structurally unreachable.** The outer loop *is*
   `Ticks`-bounded, and its deadline had already passed by **~32,405 ticks (~9 minutes)** at the
   moment of capture. It can never fire, because the inner poll at `0x40899704` never returns.

4. **The cacheable-MMIO hypothesis — that the 53C96 page is cacheable, freezing a cached
   `INT=0` in the D-cache forever — is REFUTED (§9.2).** The ROM marks the page `CM=10`
   (noncacheable, serialized), decoded straight from the raw descriptor `0x50F0E059` against the
   manual; the core decodes `CM=10` as INHIBITED; and no TTR is enabled. Together with §9.1 this
   **eliminates every CPU-side "malformed write / stale read" explanation.**

5. **A methodology trap that nearly produced a completely wrong answer (§6).** `CACR = 0x80008000`
   (both caches on) and Mac RAM is copyback. A plain `dump-mem` of the stack returned **stale DDR**
   and decoded to garbage — no valid return address anywhere in a 560-byte window. After a D-cache
   push the *same addresses* returned a clean, fully-validated three-frame chain. `coherent-dump`
   (or `dcache-op push` + `dump-mem`) is mandatory for any JTAG read of CPU-written RAM here.

**No claim is made that the boot is fixed.** Nothing was fixed. The Happy Mac still proves only
that the ROM drew the icon.

---

## 2. Method, and how cheap this turned out to be

The brief budgeted several ~3-minute boots at ~50% hit rate. **No boots were needed for the
primary result.** `halt-status` at session start read `pc_live=0x40899706` — the board was *still
parked* in the hang from the previous session, ~25 minutes on. The display was confirmed as the
Happy Mac by HTTP snapshot (`md5 2f5de0f4`, 189361 bytes — the documented Happy-Mac signature) and
`pc-trace 16` showed the CPU cycling the four loop PCs.

`break-pc 0x40899704 3000` was armed against that already-parked machine and **hit on the first
arm** (`effective=1 hit=0x40899704`). There was no arming race to lose: the loop is infinite, so
the target is hit thousands of times a second.

`reset-and-break-pc` — the documented REPL-hanging hazard on this bitstream — **was never used.**
It was not needed and is not needed for this class of target.

---

## 3. The stack, and how each frame was proven

At the halt: `A7 = ISP = 0x00400842`, `MSP = USP = 0`, `SR = 0x2004` (S=1, M=0 → the active stack
pointer is the **ISP**, which `regs` confirms equals A7).

Every frame below was validated by a rule stronger than "it looks like a ROM address": for each
ROM-range longword on the stack, the ROM was disassembled at `value − k` for every call-instruction
length, and the frame was accepted **only if a real call instruction ends exactly at that
address**. Three frames validated; nothing else in the window did.

| stack addr | value | proven provenance |
|---|---|---|
| `0x00400842` (= A7) | **`0x4089927A`** | return of `jsr %pc@(0x40899704)` at **`0x40899276`** |
| `0x00400846` | **`0x40899008`** | return of `bsr.w 0x40899270` at **`0x40899004`** |
| `0x0040084A` | **`0x40898D68`** | return of `jsr %a4@(0x1dc)@(0)` at **`0x40898D62`** |

Both indirect calls were resolved against live memory, not guessed:

* `0x40898D62: jsr %a4@(0x1dc)@(0)` with `A4 = 0x000088B0` → `[0x00008A8C]` = **`0x40898FA6`** ✓
* (the adjacent dispatch `0x40898C20: moveal %a4@(412),%a0; jsr %a0@` → `[0x00008A4C]` =
  **`0x408991D2`**, the routine that contains all three `jsr` sites) ✓

### 3.1 The chain, top to bottom

```
0x40898C90  phase-wait driver — Ticks-BOUNDED
  40898c92  movel 0x16a,%d4        ; D4 = Ticks
  40898c96  addl  %fp@(8),%d4      ; D4 = deadline  (captured value 0x2B6B)
  40898c9a  <-- the "Ticks-bounded wait" the brief refers to
  ...
  40898d62  jsr %a4@(1dc)@(0)      -> 0x40898FA6
  40898d68  <-- LIVE return address
  40898d6c  cmpl 0x16a,%d4
  40898d70  bhiw 0x40898c9a        ; keep waiting while deadline not yet reached

0x40898FA6  SCSI phase-service dispatcher
  40898fae  moveq #7,%d0
  40898fb0  andb  %a3@(64),%d0     ; D0 = 53C96 Status & 7 = SCSI bus phase
  40898fb4  cmpiw #1,%d0 / beq     ; phase 1 = DATA IN   <-- TAKEN
  40898fba  cmpiw #0,%d0 / beq     ; phase 0 = DATA OUT
  40898fc0  cmpiw #2 -> COMMAND    40898fc6  cmpiw #6 -> MESSAGE OUT
  40898fce  cmpiw #7 -> MESSAGE IN 40898fd6  cmpiw #3 -> STATUS
  ...
  40898ffe  cmpib #0,%d0
  40899002  beqs 0x40899010        ; phase 0 (DATA OUT) path, not taken
  40899004  bsrw 0x40899270        ; phase 1 (DATA IN): issue transfer, wait
  40899008  <-- LIVE return address
  4089900a  moveb %a3@(32),%d0     ; read ONE byte out of the 53C96 FIFO
  4089900e  bras 0x40898fae        ; re-read phase and loop

0x40899270  "issue Transfer Information and wait" — three instructions
  40899270  moveb #16,%a3@(48)     ; 53C96 Command reg (base+0x30) = 0x10 TRANSFER INFORMATION
  40899276  jsr %pc@(0x40899704)   ; <-- THE CALL SITE
  4089927a  <-- LIVE return address
  4089927a  rts

0x40899704  unbounded INT poll  <-- PARKED HERE, forever
```

### 3.2 The helper in full (it has no prologue, which is what makes A7 the return address)

`0x40899702` is `rts`, so `0x40899704` is a clean entry point that pushes nothing:

```
40899704  moveq #0,%d5
40899706  moveb %a3@(64),%d5   ; reg 4 Status
4089970a  btst  #7,%d5         ; INT
4089970e  beqs  0x40899704     ; spin — no timeout, no other exit
40899710  swap  %d5
40899712  moveb %a3@(112),%d5  ; reg 7 FIFO Flags
40899716  lslw  #8,%d5
40899718  moveb %a3@(80),%d5   ; reg 5 Interrupt Status  (READ-TO-CLEAR)
4089971c  movel %d5,%d0
4089971e  swap  %d5
40899720  andib #48,%d0        ; & 0x30
40899724  cmpib #16,%d0        ; == 0x10 ?
40899728  rts
```

---

## 4. Machine state at the capture

```
D0=0x00000001  D1=0x00000010  D2=0x8000E2E0  D3=0x00000001
D4=0x00002B6B  D5=0x00000011  D6=0x00000005  D7=0x00000000
A0=0x40898C90  A1=0x0000ECF0  A2=0x0000BE3E  A3=0x50F0F000
A4=0x000088B0  A5=0x0000ED46  A6=0x0040087E  A7=0x00400842
SR=0x00002004  VBR=0x00000000  USP=0  MSP=0  ISP=0x00400842  PC=0x40899704
CACR=0x80008000   TC=0x0000C000 (MMU ON, 8K pages)   SRP=0x03FEEA00  URP=0
ITT0=ITT1=DTT0=DTT1=0   MMUSR=0x00012001
```

**`D5 = 0x11` is the 53C96 Status register value, read by the CPU itself** (`moveb %a3@(64),%d5`),
not by me. Decoded:

| bit | value | meaning |
|---|---|---|
| `[2:0]` | `001` | SCSI bus phase = **DATA IN** |
| `3` VGC | 0 | — |
| `4` TC | **1** | **terminal count reached** |
| `5` PE / `6` GE | 0 | no parity / gross error |
| `7` INT | **0** | **no interrupt — this is what the loop spins on** |

Self-consistency check: `btst #7` on `0x11` sets Z, and the captured `SR` CCR is `0x04` (Z set).
The register file and the ROM's control flow agree exactly.

**The oddity worth naming: `TC=1` with `INT=0`.** The chip's transfer counter has reached zero but
no completion interrupt is asserted.

### 4.1 The unreachable timeout, quantified

`Ticks` (low memory `0x16A`) read **`0x0000AA00` = 43,520** (~723 s of uptime at 60.15 Hz).
The deadline in `D4` was **`0x00002B6B` = 11,115**. The outer `cmpl 0x16a,%d4 / bhiw` at
`0x40898D6C` would therefore have *exited* — it was overdue by **32,405 ticks ≈ 9 minutes**.
It never gets the chance. The ROM does have a timeout for this wait; the inner unbounded spin
sits underneath it and starves it.

---

## 5. A second, independent sample — a genuinely useful *control*

One reset was run with `break-pc` left armed (the arm survives a CPU reset on this bitstream —
`dbg-caps` reports `dbg_reset_domain yes`, so `reset-and-break-pc` is unnecessary). The breakpoint
fired **within 12 s**, long before the Happy Mac (display hash `58dffe84`, not the Happy-Mac
`2f5de0f4`).

**That hit is an early, healthy call, not the hang** — and it decodes to a *different* caller:

| stack addr | value | proven provenance |
|---|---|---|
| `0x0040076A` (= A7) | `0x408993BC` | return of `bsr.w 0x40899704` at **`0x408993B8`** |
| `0x0040076E` | `0x4089920E` | return of `jsr %a0@` at `0x4089920C` |

This is worth three things:

1. It **reproduces the method** on independent data with a clean two-frame validated decode.
2. It **proves the helper is genuinely shared** across call sites, so the §3 identification is
   specific to the hang state rather than "the only site that ever runs".
3. It is a **warning for the next session**: arming `break-pc 0x40899704` and taking the *first*
   hit does **not** catch the hang. That boot was released and the CPU ran on normally
   (`pc_live=0x408992E6`), confirming the early call completed healthily. **The authoritative
   sample is §3's, taken on a machine already wedged.** To catch the hang from a cold boot you
   must let the helper be entered many times and only inspect once the machine has *stopped
   advancing*.

---

## 6. The measurement trap that nearly produced a wrong answer

**Read this before any future JTAG memory read on this platform.**

`CACR = 0x80008000` → both caches enabled, and Mac RAM is **copyback** on the 040. JTAG-AXI reads
go to DDR and **bypass the CPU's D-cache**. On the first attempt, `dump-mem` of the stack returned
stale DDR:

```
before D-push:   0x00400840 = 0x00689276   -> LW@A7 = 0x92760064   (garbage)
after  D-push:   0x00400840 = 0x00684089   -> LW@A7 = 0x4089927A   (the answer)
```

An exhaustive search of the *stale* 560-byte window for any of the return addresses, **at every
byte alignment**, found **none**. That negative was an artefact. The fix is one command:
`dcache-op push` (enabled here by feature bit 21 `cache_maint_only`, even though `dcache_probe` is
**NO** on this bitstream), or equivalently the REPL's purpose-built **`coherent-dump <addr>
<words>`** / `coherent-r` / `coherent-w`.

Two honest notes on scope:

* **Part 131 is not affected** — it explicitly used `coherent-dump` (halted D-push), so its
  "corrupt return address" finding stands. I checked this specifically rather than assuming.
* **Part 132 is not affected either** — it never halted, so it made no stack reads. Its
  low-memory reads (`ScrnBase` etc.) are values written long before and long since written back.

The trap is nonetheless live, undocumented in the campaign doc as a standing rule, and cost this
session a substantial detour. It is now recorded as one.

---

## 7. Which 53C96 registers were read, and by whom

**I read zero 53C96 registers over JTAG.** Specifically I did **not** read `+0x50` (Interrupt
Status), which is read-to-clear and whose value is the state under investigation. Every 53C96 fact
in this document comes from one of two sources:

1. **`D5 = 0x11`**, which the *CPU* loaded from `+0x40` (Status). Status is not read-to-clear, and
   the CPU was going to read it anyway on the next loop iteration.
2. **Static disassembly of the ROM**, which required no bus access at all.

This also sidestepped the documented JTAG wedge hazard: VIA/SCC/**SCSI** live on xbar slave **S1**,
and `u_jtag_n2w`'s `TIMEOUT_CYCLES` is ~128× shorter than S1's legitimate stall — a JTAG access to
`0x50F0Fxxx` is exactly the shape that can park `sw_owned[1]` high and wedge the bridge until a
bitstream reload.

The register map is confirmed from the ROM's own use (Mac spaces 53C9x registers 16 bytes apart):
`+0x20` FIFO, `+0x30` Command, `+0x40` Status, `+0x50` Interrupt Status (RTC), `+0x70` FIFO Flags,
`+0x100` pseudo-DMA/DREQ port. `A1 = A3 + 0x40000 = 0x50F4F000` is the pseudo-DMA alias base, set
at `0x408991F2` (`addal #262144,%a1`).

---

## 8. Bus errors on this path are deliberate — now proven from the ROM, not inferred

The standing instruction never to arm vector 2 here is upgraded from an empirical observation to a
proof. The dispatch routine at `0x408991D2` **installs its own bus-error handler around the
transfer**:

```
408991f8  movel 0x8,%a4@(448)      ; save the vector-2 handler
408991fe  movel %a4@(428),0x8      ; INSTALL the driver's own bus-error handler
40899204  lea   %a4@(164),%a0
40899208  moveal %a0@(0,%d4:l),%a0
4089920c  jsr   %a0@               ; do the transfer (may fault on the pseudo-DMA alias)
4089920e  movel %a4@(448),0x8      ; RESTORE the vector-2 handler
```

So the 15 bus errors at `fa = 0x50F4F100` that Part 131 recorded are the ROM's own pseudo-DMA
DREQ-timeout mechanism, caught by a handler it installed on purpose. **Arming vector 2 on this path
halts a healthy machine.** Confirmed independently: a format-7 access-error frame with
`PC=0x40899664`, `EA=0x50F4F100` was found in stack residue, exactly matching Part 131.

---

## 9. On the "the 53C96 model is not the problem" reframe

Mid-session the coordinator relayed that **v1 boots to Finder on the same SoC RTL**, so the defect
must be cpu040-side, with the prime suspect being the 2026-08-18 socket-adapter gaps (32-bit-lane
byte-order mismatch; no byte-granular MMIO `AxSIZE`) corrupting byte writes to the 53C96.

**What the captured evidence says about that, stated plainly:**

**Against a wholesale byte-write failure.** The chip is in **DATA IN phase with a live target**.
Getting there requires arbitration, selection, a full COMMAND phase and the CDB bytes — all
delivered by byte writes to `+0x30` (Command) and `+0x20` (FIFO) at the *same* base address that
the suspect path uses. `TC=1` means a transfer counter was loaded and counted down. And byte
*reads* plainly decode: `+0x40` returns a structured `0x11`, not `0x00`/`0xFF`. **If byte accesses
to `0x50F0F0x0` were systematically mis-laned or mis-sized, the boot could not have reached this
point at all.** A blanket "byte writes are broken" hypothesis is **not supported** by this capture.

**What remains genuinely open.** The failure is at the *last* step, and `TC=1` with `INT=0` is a
real anomaly: the counter is exhausted but no completion interrupt is raised. That is consistent
with several distinct causes this session did **not** discriminate between — the command write not
landing *on this particular occasion* (a race, not a systematic lane bug); an interrupt-generation
gap specific to a 1-byte Transfer Information in DATA IN; or an interrupt *delivery/recognition*
seam between model and core. Naming which requires §10.1.

**One caveat on the reframe's premise, offered for accuracy, not argument.** "The same exact SoC
RTL" deserves a check: the cpu040 integration deliberately changed the socket
(`axi_i` widened to 256-bit per SOC-1; `cpu_peripheral_reset` added per SOC-2; `if_to_axi.v` and
`axi_narrow_to_wide.v` dropped per SOC-3). Those are real SoC-side differences between the
v1-booting configuration and this one, so "identical fabric" should be verified rather than
assumed before it is used to exonerate the peripheral model.

**A specific, checkable cpu040-vs-v1 divergence worth a look (hypothesis, untested).**
`cpu_peripheral_reset` — the 68040 `RESET` instruction output — is driven by cpu040 as a
518-core-clock level (D22), and the RESET-semantics decision was deliberately changed relative to
v1 (memory: "RESET is output-only in both cores", v1's fix tracked separately as task #235). If
the ROM executes `RESET` anywhere in SCSI bring-up and cpu040 asserts that line at a different time
or width than v1 did, the 53C96 would be reset out from under an operation in flight — producing
exactly "counter exhausted, no interrupt". This is a **hypothesis**, not a finding; it is cheap to
test in simulation and is squarely in the "what does cpu040 put on the bus that v1 does not" frame.

### 9.1 Static RTL check of the socket path — the byte-write hypothesis is refuted a second time

A read-only, offline audit of the RTL that actually built this bitstream was run. **Both suspect
gaps are genuinely implemented, are present in the emitted netlist, and the netlist is not stale.**
This independently corroborates the hardware-side reasoning above, from a completely different
direction.

* **D1/D2 byte permutation — implemented, applied exactly once per master, to `w.data`/`w.strb`/
  `r.data` only.** `SocketTop.scala:322,340,341,358` are the *only* four call sites of
  `permuteData`/`permuteStrb` in `src/main`; no address/id/len/size/burst/resp/last is permuted.
  The transform (`socket/SocketByteOrder.scala:59,80`) is a per-32-bit-lane byte reversal plus a
  per-nibble strobe reversal, lane order preserved — not a full-width reverse. (That exact error
  *was* made in an early draft and caught by tests; see the file's own comment at `:62-68`.)
* **D4/D5/D6 byte-granular MMIO `AxSIZE` + exact-cover decomposition — implemented**, in
  `socket/MmioCover.scala` (`sizeBytes:72`, `clampedEnd:94`, `stepLog2:107`, `MAX_SUBS=3:69`) and
  wired on both paths in `DcachePlugin.scala` (stores `:2843-2859`, `:2984-3002`; loads
  `:1646-1651`, `:1855-1867`). The store ack is correctly deferred to the last sub-transaction's B
  (`:2963`, `:3055`). The size derivation runs **before** the permutation, which is the D5 hazard.
* **Netlist present and current.** `generated/M68kSocketTop.v:883,888,861-868,903-906` (permutation)
  and `:42579,99031,99155,99323` (sizing). No `src/main` Scala file is newer than the netlist
  (`11:31:06` vs `11:31:43`), the bitstream is later still (`12:05:48`), and `synth/vivado.tcl:112-131`
  regenerates the netlist via sbt inside every Vivado run. **Not stale.**
* **End-to-end trace of `move.b #16,(0x50F0F030)` — correct.** Core side: `stSubLog2 = 0` ⇒
  **`AWSIZE=0`**, `AWADDR=0x50F0F030`, `AWLEN=0`, core-side `WSTRB=0x0001`, one sub-transaction,
  one B, one ack. Socket: `wdata[31:24]=0x10`, `wstrb=0x0008`. SoC: the xbar forwards addr/size
  verbatim and its only byte swap is on **S3 (VRAM) only**, so there is no double swap in the
  peripheral aperture; `peripheral_bus.v:709-717` extracts `scsi_wdata = 0x10` into NCR5380
  register 3, with the address-derived and strobe-derived byte conventions **agreeing**.

**So the "byte-order / `AxSIZE` corruption" hypothesis is now refuted twice over** — once by the
captured architectural state (the chip reached DATA IN phase, which is unreachable if byte writes
to this base are broken), and once by the RTL itself.

### 9.2 The page-table walk: **the cacheable-MMIO hypothesis is REFUTED**

Run on a fresh boot caught at `0x40899704` (`break-pc` armed, one reset), with a `dcache-op push`
first — the page tables are CPU-written, so this walk would itself have read stale DDR without it.

**Transparent translation: none.** `ITT0 = ITT1 = DTT0 = DTT1 = 0x00000000` on this boot (and on the
original capture). The manual (§3.1.3, "E—Enable: 0 = Transparent translation disabled") makes an
all-zero TTR unambiguously disabled. **No TTR covers `0x50F0Fxxx`**, so translation is entirely by
table walk.

**The walk**, from `SRP = 0x03FEEA00`, `TC = 0x0000C000` (E=1, P=1 → 8K pages). For
`LA = 0x50F0F030`: root index `LA[31:25]` = 40, pointer index `LA[24:18]` = 60, page index
`LA[17:13]` = 7, offset `LA[12:0]` = `0x1030`.

| level | address | raw descriptor | decode |
|---|---|---|---|
| root | `0x03FEEAA0` | **`0x03FEE80A`** | UDT=`10` resident; pointer table @ `0x03FEE800` |
| pointer | `0x03FEE8F0` | **`0x03FEAB0A`** | UDT=`10` resident; page table @ `0x03FEAB00` |
| **page** | **`0x03FEAB1C`** | **`0x50F0E059`** | see below |

**Independent decode of `0x50F0E059` against the MC68040 UM (Figure 3-12 + §3.2.2.3), not via our
RTL:**

```
PA[31:13] = 0x50F0E000   identity mapped, correct
bit12 UR=0  bit11 G=0  bit10 U1=0  bit9 U0=0  bit8 S=0
bits[6:5] CM = 0b10  -> "10 = Noncachable, Serialized"
bit4 M=1  bit3 U=1  bit2 W=0
bits[1:0] PDT = 0b01 -> Resident
```

**`CM = 10` = NONCACHEABLE, SERIALIZED. The ROM marks the 53C96 page cache-inhibited, correctly.**
All 32 entries of the table are `...059`, i.e. the whole `0x50F00000–0x50F3FFFF` aperture is
inhibited. (Self-check on the walk arithmetic: the table ends exactly after 32 entries — `0x03FEAB80`
onward is uninitialised `0xDB6DB6DB` DRAM — which is precisely the 128-byte page table an 8K-page
configuration requires.)

**And cpu040 decodes it correctly.** `TableWalker.scala:209` does
`rCmode := CacheMode.decode(MmuDesc.pgCacheMode(d))`, `MmuTypes.scala:73` takes `d(6 downto 5)`,
and `IcacheTypes.scala:76-86` maps `when(cm2(1)) { m := INHIBITED }` — bit 6 is set for `CM=10`, so
it lands on **INHIBITED**. The enum deliberately collapses `10` and `11` to one `INHIBITED` value,
with the reasoning and the manual citation recorded in the source comment (`:70-75`). This was the
one plausible mis-decode — an `INHIBITED` test that matched only `CM=11` and missed the
`CM=10` the ROM actually uses — and it is **not** what the code does.

**So neither branch of the question holds:** the ROM did not fail to mark the page, and we do not
mis-decode it. The page is inhibited, the byte-granular MMIO path *is* selected, the Status
register is *not* cached, and the "frozen cached `INT=0` forever" mechanism does not occur.

**A second, behavioural refutation of the same hypothesis, independent of the tables.** The helper
is called many times per boot and **succeeds** — proven directly by the §5 control sample
(`0x408993B8`, which completed and let the CPU run on) and by the boot reaching the Happy Mac at
all. Every one of those successes requires observing `INT` transition 0→1 at `0x50F0F040`. A frozen
cached line would have wedged the **first** call, not the late one. The poll demonstrably works
until it doesn't.

**The last configuration-dependent failure mode — now CLOSED by §9.2.** The byte-granular path is
taken only when `cacheMode === INHIBITED` (`LsEuPlugin.scala:2448-2449`, which additionally fails
*safe*, defaulting to INHIBITED). §9.2 establishes that the page is marked `CM=10` by the ROM and
decoded as INHIBITED by the core, so `stSubActive` is 1 and the store goes out as `AWSIZE=0`. There
is no surviving path by which a byte write to `0x50F0F030` is emitted wrong.

Two caveats recorded by the audit, neither of which breaks this write:
`SocketByteOrder.scala:41` claims `tools/socket/check_socket_netlist.py` machine-checks the
applied-exactly-once rule — **it does not** (that script only checks port shape); "exactly once"
was verified by hand. And the post-permutation strobe is deliberately *not* literal AXI4 — with
`AWSIZE=0` and `AWADDR[3:0]=0` this bus asserts `wstrb[3]`, matching v1's `if_to_axi.v` convention;
every `axi_d` slave honours it, but a stock third-party AXI slave would mis-lane. Separately, the
plan's Task-14 acceptance gate was never recorded, and `check_socket_netlist.py` currently **fails
D23** because 30 `dbg040_*` ILA probe ports were added to the socket top after the plan — unrelated
to byte order or sizing, but it means the port freeze is no longer green.

---

## 10. Recommended next steps

0. ~~Walk the page tables for `0x50F0F030`.~~ **DONE — see §9.2. Result: `CM=10` (noncacheable,
   serialized), correctly set by the ROM and correctly decoded by the core. Hypothesis refuted.**
   With this closed, **every CPU-side "the write is malformed / the read is stale" explanation has
   now been eliminated**, from three directions: captured chip state, the socket RTL, and the MMU
   page attributes. The remaining explanations all live in *timing/sequencing or the device model* —
   which is exactly what step 1 measures.
1. **Capture the MMIO transaction stream to `0x50F0F0xx` in the run-up to the hang** — offsets,
   sizes, values, in order. That is the evidence that decides §9, and it is the coordinator's
   step 2. The Happy-Mac hang is reproducible (~50% of boots, and the board sits in it
   indefinitely), so an ILA capture triggered on writes to `0x50F0F030` is feasible; a prior
   session already built a working Xilinx ILA pipeline for this board.
2. **Diff that stream against v1's** for the same ROM and card. v1 boots, so its stream is the
   reference and **the first divergence is the bug**. Worth more than any further reasoning.
3. **Focus any 53C96-model reading on one narrow question**, only after (1): what raises INT at the
   end of a **1-byte TRANSFER INFORMATION in DATA IN phase**, and can it produce `TC=1, INT=0`?
   Do not audit the model broadly.
4. **Do not** re-derive the caller. It is `0x40899276`, and §5 shows that a naive
   `break-pc` + first-hit will mislead you to `0x408993B8`.

## 11. What was NOT run

* **No Vivado build of any kind** — no synthesis, no implementation, no bitstream. The mutex was
  probed once and never taken. `pgrep -af 'vivado.*-mode batch'` was **not** used.
* **No SD-card access. No ROM patch, no ROM write.** The ROM image was read *from a file on disk*
  (`files/420dbff3.rom`) for offline disassembly, after verifying it matches the live ROM
  byte-for-byte at the hang address.
* **No 53C96 register read over JTAG** (§7). In particular `+0x50` was never touched.
* **No vector arming of any kind** — no vector 2, 3, 4 or 11. `halt-exc-mask` was left all-zero
  and untouched throughout.
* **`reset-and-break-pc` was not used.** `vio-hard-reset` was not used. `load-bit` was not used.
* **Two resets total** — one for §5's control, one to re-establish a halt for the §9.2 page-table
  walk. The primary result (§3) needed none.
* **No simulation, no MAME/Musashi cross-check, no lock-step run, no JVM/sbt work** (three sibling
  agents were running simulation work and host memory was at its ceiling).
* **The MMIO transaction stream was NOT captured** — no ILA, no bus trace. §9's conclusions are
  inferences from architectural state, not from observed bus traffic. This is the single most
  valuable thing still missing.
* The static RTL verification of the socket byte-order / MMIO-`AxSIZE` path **was** run (read-only,
  offline) and is reported in §9.1. It did **not** include running any test — no sbt, no JVM, no
  simulation — so the specs named there are known to *exist*, not known to *pass*.
* **No v1 comparison run.** v1 was not built, booted, or traced.
* **The Sad Mac boots were not investigated** at all this session.
* **The `c96_phase_bits()` question was deliberately not pursued**, per the reframe.
* **No attempt to unstick the machine** — no register poke, no workaround, no nudge.
