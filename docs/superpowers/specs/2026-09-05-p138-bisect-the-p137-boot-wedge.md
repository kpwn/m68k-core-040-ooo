# p138 — bisecting the p137 boot wedge: which of three landed fixes stops the machine

**Date:** 2026-09-05
**SoC branch:** `build/p133-hold-fixed-latest` (worktree `p133-hold-fixed-latest`)
**Predecessor:** `2026-09-04-p137-three-fix-bitstream-boot-test.md` (Part 137), whose §10
named this bisect as the single highest-value next step and did not run it.
**Status:** 【FILL】

---

## 1. The question, narrowly

p137 (`cpu040 2db5bd3`, `build_id 0x9C1FA4B5`) wedges at `pc_live=0x40806b68` on every
boot with the exception ring frozen; p133 (`cpu040 bb3bca1`, `build_id 0x14F3C599`)
reaches the Happy Mac on the same board in the same session, 3/3 paired. `bb3bca1..2db5bd3`
is a clean fast-forward containing exactly three merges:

| arm | commit | fix |
|---|---|---|
| **A** | `8887507` | no speculative instruction fetch into cache-inhibited pages |
| B | `7057e80` | `RTE` must resync `RobPlugin.committedCcr` |
| C | `2db5bd3` | MMU table walks routed through L1D |

**This does not put the three fixes in doubt.** Each has a failing-before test on
unmodified RTL and `7057e80` is confirmed on silicon (Part 136). The question is which one
has an unintended interaction with the rest of the machine on a real ROM boot.

---

## 2. What `0x40806b68` is — and why the answer relocates the whole investigation

Disassembled offline from `files/420dbff3.rom` (ROM base `0x40800000`, offset `0x6b68`;
the file is the one Part 133 verified byte-for-byte against the live ROM). The routine
boundary is unambiguous — `0x40806b50` is a register-save prologue and the decode runs
cleanly from there:

```
40806b50:  48e7 6040        moveml %d1-%d2/%a1,%sp@-
40806b54:  1f28 0031        moveb  %a0@(49),%sp@-      ; save spSlot
40806b58:  303c feb6        movew  #-330,%d0
40806b5c:  0c28 00ff 0032   cmpib  #-1,%a0@(50)        ; spID == $FF ?
40806b62:  6700 0070        beqw   0x40806bd4
40806b66:  702e             moveq  #46,%d0             ; selector $2E
40806b68:  a06e             _SlotManager                ; <-- pc_live WEDGES HERE
40806b6a:  6600 0068        bnew   0x40806bd4
40806b6e:  702f             moveq  #47,%d0             ; selector $2F
40806b70:  a06e             _SlotManager
```

`$A06E` is the **Slot Manager** trap with the routine selector in `D0` — the campaign
already has this identified independently at other call sites (`docs/BUG_calibration_word_
misplaced_0d00.md:9667, 10023, 10038, 10058`: "`moveq #47,d0 ; trap $A06E` — csCode 47").
`A0` is an **SpBlock**: the offsets this routine touches are exactly the Slot Parameter
Block layout — `+49 spSlot`, `+50 spID`, `+53 spByteLanes`, `+54 spFlags`, `+4 spsPointer`,
`+12 spOffsetData`, `+24 spParamData`. So the machine stops in NuBus slot enumeration.

### 2.1 The wedge PC is the NEXT pc, so the A-line trap has NEVER executed

`pc_live` is **not** the last retired PC. `RobPlugin.scala:1448-1449` writes
`debugLivePcReg := commitPc0`, and `commitPc0` (`:1428`) is
`Mux(p0.retireAlone, nextPcRd0, p0.predNextPc)` — the *successor* address. The ROB's own
comment at `:2479` says it outright: "resume PC = the address AFTER the traced instr".

Therefore `pc_live = 0x40806b68` means the last macro to retire was `moveq #46,%d0` at
`0x40806b66`, and **the `A06E` at `0x40806b68` has never retired**. That is corroborated
independently: an A-line instruction's only possible outcome is a vector-10 exception, and
the exception ring is frozen with no vector-10 entry at `0x40806b68` (the ring's last two
A-line entries are at `0x4080589a` and `0x408068c8`).

**So this is not an exception-path failure and not a handler failure.** The trap never
fires because the *instruction is never delivered to the machine at all*. `0x40806b68` is
8-byte aligned — it is the base of a **new fetch window**. The machine is stopped waiting
for an instruction fetch that never arrives.

That is a fetch-side stall, and of the three arms only `8887507` touches instruction
fetch issue. It was tested first for that reason.

---

## 3. Arm A source analysis, done before the board result

`8887507` adds `inhibitedSpecBlock` to the I-cache's **command-accept** term
(`IcachePlugin.scala:1653`):

```scala
cmdPort.ready := xlate.rsp.ready && !setBlocked && !s1Unresolved && pfAcceptOk &&
                 !inhibitedSpecBlock
val inhibitedSpecBlock = !lookupCacheable && !nonSpecFetch
```

`nonSpecFetch` is driven by `SpeculativeFetchGate.wire` (`top/SpeculativeFetchGate.scala`):

```scala
ic.logic.nonSpecFetch := drainedQ && (fl.ringCount === 0)
// drainedQ = RegNext(frontendQuiet && decodeQuiet && renameQuiet && robQuiet)
```

Note first that this gates the **lookup command**, not the miss. An inhibited fetch is
refused entry to the fetch pipeline whether it would hit or miss in the I-cache.

### 3.1 Two terms of `frontendQuiet` are self-blocking

```scala
val frontendQuiet =
  (fl.ringCount === 0) && !fl.redirectThisCycle && !fl.predictPending &&
  !fl.ftqMismatchPending &&
  !fl.targetHoldValid &&        // <-- "no held (backpressured) fetch target"
  (fl.ftqCount === 0) &&        // <-- "no live fetch-target plan"
  !fl.res.slot0Valid && !fl.feed.valid &&
  !fl.stalled && !fl.quiesce && !fl.faultHold
```

Both of the marked terms describe **a fetch that is waiting to be issued**, and both are
cleared only by events that require the very fetch the gate is refusing:

* `targetHoldValid` is *set* by backpressure on the fetch command
  (`FetchAlignPlugin.scala:629-636`: `when(applyNow) { ... when(!ic.cmd.fire) {
  targetHoldValid := True; targetHoldPc := directTargetPc } }`) and *cleared* only by
  `ic.cmd.fire` (`:637-639`) or `ftqFlush` (`:1374-1375`). While it is set,
  `cmdWindowPc` is forced to `targetHoldPc` (`:586-588`) — so the frontend keeps
  presenting the same inhibited address and can present nothing else.
* `ftqCount` is incremented by `ftqPush := applyNow` (`:539`) and decremented only by
  `ftqPop := ftqConfirmFire = ftqConfirm && feed.fire` (`:1196-1200`) — i.e. the predicted
  branch actually reaching decode, which needs fetched bytes — or by `ftqFlush`.

So: refusing the command sets/holds the terms; the terms hold `nonSpecFetch` low;
`nonSpecFetch` low keeps `inhibitedSpecBlock` high; which refuses the command. **A closed
loop.**

The only escape is `ftqFlush = redirect.valid || (resume.valid && stalled) ||
mispredictRedirect.valid || predictFire || ftqMismatch` (`:544-545`). Every one of those
originates from an instruction already inside the machine. Blocking the fetch drains the
machine — which is the fix's own stated argument for why it is safe — and a drained
machine can produce none of them. **The drain that is supposed to open the gate is exactly
what removes the last thing that could open it.** Terminal.

This is confirmed in the compiled netlist, not just the Scala
(`cpu040/generated/M68kSocketTop.v`):

```verilog
assign _zz__zz_IcachePlugin_logic_nonSpecFetch_2 = (! FetchAlignPlugin_logic_targetHoldValid);
assign _zz__zz_IcachePlugin_logic_nonSpecFetch   = ((((… && …_2) &&
        (FetchAlignPlugin_logic_ftqCount == …)) && (! FetchAlignPlugin_logic_res_slot0Valid)) && …);
```

### 3.2 The fix's own no-deadlock argument names the right failure class and misses these two

`SpeculativeFetchGate.scala`'s "Why it cannot deadlock" section reasons: "Blocking the
fetch is what CAUSES the drain: nothing new enters the machine, so the IBuf, the decode
queue and the ROB all empty out and the predicate becomes true." That is true of
`decodeQuiet`, `renameQuiet` and `robQuiet`. It is **not** true of the two frontend terms
above, which are *not* machine occupancy — they are the record of the blocked fetch itself.

The comment even enumerates two traps of exactly this class that were deliberately avoided
(`ibuf.io.cnt === 0` is not a term; `pendingDrop === 0` is not a term) — the author was
looking for precisely this bug and stopped one term short.

---

## 4. Why the fix's verification could not have caught it

`ExecuteLockStepSpec`'s three `spec-mmio I-side` probes are the tests that gate this fix.
Their program is `nop ; nop ; nop ; loop: bra.s loop` at `0x4FFFFFF0`, and the assertion is
`arHits.isEmpty` — no AXI AR into the inhibited window.

**A deadlocked frontend passes that assertion more easily than a working one.** The test
has no liveness or progress assertion at all: "the fetch was blocked" and "the machine
stopped forever" produce identical evidence under it. It also cannot deadlock in practice,
because the architectural program is a 2-byte `bra.s` self-loop whose bytes are already
resident — the machine keeps retiring out of the IBuf/I-cache with no further fetch needed,
so `robQuiet` is never true and the gate's release path is never exercised.

Real ROM code is straight-line. When the gate blocks there, nothing keeps retiring.

That is why 509/509 lock-step, 341 `test-fast`, and an unchanged 200-seed fuzz result are
all consistent with this defect: none of those workloads combine a run-ahead into inhibited
space with an outstanding fetch-target plan and a straight-line architectural stream.

---

## 5. What plausibly steers the frontend into device space here — hypothesis, not measurement

The gate can only engage on an address the ITLB reports `INHIBITED`. An ITLB *miss* does
not do it: `ItlbPlugin.scala:446-451` drives `cacheMode := WRITETHROUGH` with
`rsp.ready := False` on a miss, so a miss stalls the accept without asserting the gate.
The presented address therefore has to genuinely map to a cache-inhibited page — on this
machine, the `0x5xxxxxxx` device/slot region.

The routine the machine dies in is full of the classic Mac ROM indirect-dispatch idiom,
which is a **return-address-stack poison** pattern:

```
40806b98:  487a 000c        pea    %pc@(0x40806ba6)     ; push the RETURN address
40806b9c:  2f30 81e2 0db8 00dc  movel @(db8)@(dc),%sp@- ; push the TARGET from a table
40806ba4:  4e75             rts                         ; "return" to the target
```

The same idiom appears at `0x40806848`, `0x4080685c`, `0x40806c16` — three more inside the
0x100 bytes around the wedge. To the RAS, `pea` is not a call and `rts` is a return, so
every one of these mispredicts to whatever stale address the RAS holds. And this is Slot
Manager code, so the surrounding data being walked is slot-space pointers.

The fix's own evidence is the other half of this: its sibling test **fails on unmodified
RTL with a real wrong-path 64-byte burst at `0x50000000`**, i.e. the frontend demonstrably
does run ahead into device space in this design. p133 issues those bursts (harmfully, which
is why the fix exists); p137 blocks one and dies.

The board evidence fits this shape unusually well: §6.1's full ring shows the routine at
`0x40806b50` **completed a whole pass earlier in the same boot** and the machine then died
on a later re-entry at the first `a06e`. A first pass is exactly what *trains* the FTB and
poisons the RAS with this routine's dispatch idiom; a later pass is what *consumes* that
trained state. It also explains the determinism — same PC, and `exc_head=19` identical
across two independent programming cycles — better than a race would.

**Stated as a hypothesis.** No frontend probe exists on this bitstream, so which term of
the predicate is actually stuck, and what address is held, were not measured. See §8.

---

## 6. Board evidence

### 6.1 p137 wedge characterisation — the machine is not retiring, and everything but the frontend has drained

Fresh `load-bit` of the preserved p137 artifact (`md5 b394e3e2…`, live `build_id 0x9C1FA4B5`
verified on the second read), all eight halt-exception lanes disarmed with
`halt-exc-mask raw <lane> 0` and `enables={ha=0 bp=0 exc=0 pcmis=0}` **asserted**, plain
`reset`, 200 s settle. `tools/p137_wedge_characterise.sh`.

```
  t0  exc_head=19  retired_macros=0x00000000:0x054202c3  pc_live=0x40806b68
  t30 exc_head=19  retired_macros=0x00000000:0x054202c3  pc_live=0x40806b68
  t60 exc_head=19  retired_macros=0x00000000:0x054202c3  pc_live=0x40806b68

  VERDICT retire:   macros 0x054202c3 -> 0x054202c3  FROZEN
  VERDICT exc-ring: head   19 -> 19  FROZEN
  screen md5: 9086c68c…   (the recorded p137 wedge signature)
```

**The retired-macro counter is the new instrument here, and it settles the question the
Part 137 write-up left open.** `OFF_INST_LO/HI` (`0x50901008`/`0x5090100C`) is live,
free-running and needs no halt, unlike `inst-count`. It does not move by a single macro in
60 s. **The CPU retires nothing.** So "interrupts are not being recognised" is not a
masking story — the machine is not executing at all, and an interrupt has nothing to be
taken at. (`exc_head=19` is the same value the previous session recorded, on a separate
programming cycle: the wedge is deterministic to the exception count.)

The exception ring is entirely Toolbox A-line traps in the Slot Manager region, which is
the same routine family the wedge PC belongs to:

```
exc[18] vec=0x0a pc=0x4080589a   exc[17] vec=0x0a pc=0x408068c8
exc[16] vec=0x0a pc=0x40806964   exc[15] vec=0x0a pc=0x4080614e
exc[14] vec=0x0a pc=0x40806d34   exc[13] vec=0x0a pc=0x40806d02   (…alternating)
```

#### RETRACTED: the `wedge-status` reading. `OFF_WEDGE0..3` are not implemented on cpu040

This run also captured:

```
wedge: rob_pc=0x00000000 lsu_addr=0x00000000 rob_hd=0 last=0 lsu=0:IDLE dcache=0:IDLE
       flags={… dmmu_req=0 dmmu_walk=0 arvalid=0 arready=0 rvalid=0}
       raw={w0=0x00000000 w1=0x00000000}
```

and I initially read it as "ROB empty, LSU idle, no MMU walk, no AXI handshake — everything
except the frontend has drained", and used it both to support the fetch-gate story and to
rule out a walker/store-queue story. **That reading is withdrawn. It is worthless.**

`wedge-status` decodes `OFF_WEDGE0..3` (`DebugRegMap.scala:172-175`). Those offsets are
**declared in the register map and never decoded in `DebugCtrlPlugin`'s read mux** —
`is(DebugRegMap.OFF_WEDGE0)` appears **zero** times. They are legacy v1 `debug_ctrl` probes.
On a cpu040 bitstream they return `0x00000000`, and the decoder turns all-zero into a
confident, fully-populated "everything is idle" line.

The tell was in the output I printed and did not act on: `raw={w0=0x00000000 w1=0x00000000}`.

Checked against the same read mux, the instruments this document *does* rely on are real:

| register | `is(DebugRegMap.…)` in the read mux | used for |
|---|---|---|
| `OFF_WEDGE0` | **0 — NOT IMPLEMENTED** | withdrawn |
| `OFF_CYCLE_LO` | **0 — NOT IMPLEMENTED** | withdrawn (see below) |
| `OFF_INST_LO` | 1 ✓ | the retired-macro liveness verdict |
| `OFF_LIVE_VBR` / `OFF_LIVE_SR` / `OFF_LIVE_MMU_TC` | 1 ✓ each | the register sweep |

The live sweep is independently corroborated anyway: `SRP=0x03feea00` and `TC=0x0000c000`
match Part 134's values exactly, and the page-table walk driven from that `SRP` lands on a
descriptor whose PA equals the LA being translated. Unimplemented registers do not do that.

**Consequences, stated plainly.** I can no longer claim the ROB is empty, the LSU is idle,
or that there is no MMU walk or bus activity in flight. In particular this **removes my
rebuttal** of the "exception entry stuck in `E_DRAIN` waiting on `sqDrained`" hypothesis,
which predicts a *non-empty* ROB and store queue — the measurement I thought contradicted it
was not a measurement.

**`halt` does not land** (`ERROR halt timeout after 3000ms`, `effective=0`), so `SR`, `VBR`
and the vector-10 table entry could **not** be read — `live-arch`, `coherent-dump` and
`dcache-op` all require an acknowledged halt. That failure is itself consistent: the stop
manager waits for the current macro to commit and nothing commits, and
`RobPlugin.scala:2345` gates `stoppedNext` on `!interruptPending`, so a pending IRQ the
machine can never take also blocks the stop.

#### The full 32-entry ring: this exact trap fired successfully earlier in the same boot

```
exc[ 2] vec=0x0a pc=0x40806b88      exc[ 1] vec=0x0a pc=0x40806b70
exc[ 0] vec=0x0a pc=0x40806b68      <-- THE WEDGE ADDRESS, taken normally
exc[19] vec=0x0a pc=0x40806b88      (oldest retained; head=19)
```

`0x40806b68`, `0x40806b70` and `0x40806b88` are exactly the three `a06e` Slot Manager traps
in the routine at `0x40806b50`, and they appear in program order. **The routine ran to
completion at least once, so neither the A-line decode, the vector-10 path, nor the handler
at `0x408099b0` is broken.** The machine dies on a later re-entry, at the first `a06e`,
having retired the `moveq` in front of it. Whatever stops it is not the trap itself.

**All 32 retained entries are `vec=0x0a`. There is not one interrupt in the window.** That
is suggestive but not conclusive on its own: it is consistent with the ROM raising the IPL
across slot enumeration, and `SR` could not be read (the halt does not land). It is *not*
needed for the verdict — the frozen retired-macro counter already says the machine executes
nothing, and an interrupt cannot be taken by a machine that does not retire.

#### An I-cache probe that starved, reported as inconclusive

`icache-lookup 0x40806B68` (and `0x40806B60`, the same 16-byte line, set 54, tag `0x10201A`)
both returned `DID NOT COMPLETE (flags=0x00000000)`. The probe borrows the fetch read path
on an **idle** cycle, and the tool's own message says not to read a timeout as "the line is
not cached" — so **nothing was sampled and nothing is claimed**. Noted only because the
direction is suggestive: a frontend endlessly asserting a fetch command that is endlessly
refused would leave the fetch path never idle, which is what a starved probe looks like.
Confirming that needs a frontend probe this bitstream does not carry.

#### Live architectural and MMU state of the wedged machine — measured, not inferred from the ROM image

`live-arch` needs a halt that will not land, but the registers it consumes are plain debug
registers at fixed offsets (`jtag_repl.tcl:857-876`, `DBG_BASE=0x50900000`) reachable with a
plain `r`. The standard warning about forced reads — "read register-by-register while the
pipeline retires, so values are stale, torn, or both" — has a premise that is **false on
this machine**: nothing retires. That is an argument, so it was *checked*: every register
was read twice and had to agree, and the retired-macro counter was read before and after
the whole sweep and was **unchanged** (`0x054202c3` both times). All values below are
`stable`.

| register | value | reading |
|---|---|---|
| live `PC` | `0x40806b68` | agrees with `pc_live` |
| `VBR` | **`0x00000000`** | vector table is in **low RAM**, not the ROM table |
| `SR` | **`0x00002700`** | T=0, **S=1**, M=0, **IPL=7**, CCR=`0x00` |
| `A7` / `ISP` | `0x0017ff3a` | M=0, so ISP is the active bank |
| `CACR` | `0x80008000` | both caches enabled |
| `TC` | **`0x0000c000`** | **MMU ON**, E=1 P=1 → 8K pages |
| `ITT0/ITT1/DTT0/DTT1` | all `0x00000000` | no transparent translation at all |
| `SRP` / `URP` | `0x03feea00` / `0x00000000` | |
| `MMUSR` | `0x00000000` | no `PTEST` has run |

**Interrupts are genuinely masked: `IPL=7`.** This is a correction to Part 137's framing.
"The machine is stopped with interrupts not being recognised" reads as though interrupt
recognition were broken; at this instant the ROM is simply running masked, which is ordinary
for Slot Manager work, and it fully explains why all 32 retained ring entries are `vec=0x0a`
with no `0x19`/`0x1a`. **So the frozen exception ring is not independent evidence of a wedge
— it is implied by `IPL=7`.** What carries the verdict is the frozen retired-macro counter:
the machine executes nothing, and a machine that retires nothing cannot take an interrupt
whatever the mask says.

**The MMU is on with all four TTRs zero**, so `ItlbPlugin.scala:421-435`'s `ttHit` and
`!mmuEnable` arms are both out and cache mode comes from real page descriptors. That
matters because it is a test the fetch-gate hypothesis could have failed and did not: with
the MMU off, every fetch would report `WRITETHROUGH` and `inhibitedSpecBlock` could never
assert at all.

#### Vector 10 is correct and its target is valid — measured live

`VBR+0x28 = 0x00000028` reads **`0x408099b0`**, which is exactly the `handler=` field in
**all 32** of the machine's own exception-ring entries. The target is valid ROM:

```
408099b0:  2f0a          movel %a2,%sp@-
408099b2:  2f02          movel %d2,%sp@-
408099b4:  246f 000a     moveal %sp@(10),%a2      ; the stacked PC
408099b8:  341a          movew %a2@+,%d2          ; fetch the trap word
408099ba:  0c42 a800     cmpiw #$A800,%d2         ; OS trap vs Toolbox trap split
408099be:  6530          bcss  …
```

That is the textbook Mac A-line dispatcher. **Neither the vector nor its target is corrupt**,
so the "fault is upstream of the trap" branch is eliminated on live evidence rather than on
the ROM image. (The vector-table read itself is a RAM read over JTAG-AXI and therefore
**not** D-cache-coherent — `dcache-op push` needs the halt that will not land. It is
corroborated by the ring, which is core-side state and needs no coherence at all. The
handler bytes are ROM, which the CPU never writes, so no dirty line can shadow them.)

#### The wedge PC's own page is CACHEABLE — so the gate is not refusing *that* address

Walked live from `SRP=0x03feea00` for `LA=0x40806b68` (8K pages: root `[31:25]`=32,
pointer `[24:18]`=32, page `[17:13]`=3):

```
root    @ 0x03FEEA80 = 0x03fee80a   UDT=2
pointer @ 0x03FEE880 = 0x03fec70a   UDT=2
PAGE    @ 0x03FEC70C = 0x40806039
  PA=0x40806000   CM=01 Cachable Copyback   M=1 U=1 W=0 PDT=1
```

Self-check: the descriptor's PA equals the page of the LA (identity mapped), so this is the
right entry, and the surrounding table is the contiguous ROM run `0x40800039, 0x40802039,
0x40804039, 0x40806039, …`.

**`CM=01` — cacheable copyback. `lookupCacheable` is TRUE at `0x40806b68`, so
`inhibitedSpecBlock` cannot possibly be refusing a fetch of the wedge PC itself.**

This is a real constraint on the mechanism, and it *narrows* rather than refutes §3.1: the
gate's deadlock does not require the stalled address to be the architectural one. When
`targetHoldValid` latches, `cmdWindowPc` is **forced** to `targetHoldPc`
(`FetchAlignPlugin.scala:586-588`), so the frontend presents the held — inhibited — target
and can never present `0x40806b68` at all. A cacheable wedge PC is what that failure looks
like from outside.

Residual, stated because it is exactly where an arm-A/arm-C *interaction* would live:
`MMUSR=0x00000000` (no `PTEST` has run), so **the core's runtime ITLB contents were not
observed**. The page *tables* say cacheable; what the ITLB actually holds for this page was
not measured, and `2db5bd3` changes how the walker sources the data that gets installed
there.

##### A walk error I made and caught, worth recording

The first attempt masked the **page**-table base with `~0x1FF`. A 68040 8K-page table is 32
entries × 4 B = **128 bytes**, so the mask is `~0x7F`; only the *pointer* table (128 × 4 =
512 B) takes `~0x1FF`. The wrong mask landed on a different, entirely well-formed page table
(the one covering `0x40840000`) and **passed the cross-check I had written** — "the table
ends after exactly 32 entries, followed by uninitialised `0xdb6db6db` DRAM" — because that
is true of *any* correctly-formed table. The check that actually discriminates is "does the
entry hold the physical address we are translating", which is what the corrected script
asserts. Part 134's walk used the right base and is unaffected.

#### A measurement defect caught in this run, before it reached a conclusion

The first revision of the script also read `OFF_CYCLE_LO/HI` (`0x50901000`/`0x1004`) to
separate "stopped clock" from "not retiring", and duly printed **`VERDICT clock: STOPPED`**.
That is false. Those two registers are **declared in `DebugRegMap.scala:97-98` and never
implemented in `DebugCtrlPlugin`'s read mux** — they read `0x00000000` on a perfectly
healthy running CPU, as this campaign already recorded once
(`BUG_calibration_word_misplaced_0d00.md:28613`). The fabric was demonstrably alive: every
other JTAG read in the same run returned real data. The reading was removed from the tool
rather than footnoted, with the reason written into its header so it cannot be re-added.

### 6.2 Arm A (`8887507`) BOOTS. The speculative-fetch gate is not the culprit.

**Build provenance.** `build_id 0x32eafe6c` (the SoC pin commit's short sha, so the live
board read names the arm unambiguously); `cpu=m68k040`; **cpu040 pin compiled
`888750776bef3e01bb3c7632d6fefd2abc050d77`, read back from the compiled submodule HEAD, not
the intended one**; netlist `md5 f89f18f7…`, generated *before* the mutex and proven
non-identical to p137's `68e2e38f…`; Vivado's own in-process regeneration reproduced the
same md5 bit-for-bit. Timing reported, not gated: **WNS +0.009 ns, TNS 0.000, 0 failing
endpoints** at 100 MHz. All three arms carry distinct build IDs and distinct bitstream md5s:

| arm | cpu040 pin | `build_id` | bitstream md5 |
|---|---|---|---|
| p133 | `bb3bca1` | `0x14f3c599` | `d809fd9f…` |
| p138a | `8887507` | `0x32eafe6c` | `33c47403…` |
| p137 | `2db5bd3` | `0x9c1fa4b5` | `b394e3e2…` |

The gate's presence was also confirmed *in the compiled netlists* rather than assumed:
`inhibitedSpecBlock` occurs 4× in both the arm-A and p137 netlists and **0×** in the
`bb3bca1` (p133) one — exactly the intended bisect delta.

**Result — 3 pairs, interleaved in one session, identical cycle per arm, settle 200 s,
nothing armed (`enables={ha=0 bp=0 exc=0 pcmis=0}` asserted each cycle):**

| pair | p133 | p138a (`8887507`) |
|---|---|---|
| 1 | `0x0030001e` bus-error livelock | **`0x40899706` Happy Mac, ring 11→4 ALIVE** |
| 2 | `0x40899706` Happy Mac, ring 13→2 ALIVE | `0x0030001e` bus-error livelock |
| 3 | `0x0030001e` bus-error livelock | **`0x40899706` Happy Mac, ring 2→17 ALIVE** |

Arm A: **2/3 Happy Mac** (screen `2f5de0f4`, the 53C96 INT-poll hang), 1/3 bus-error
livelock. p133 this session: 1/3 Happy Mac, 2/3 bus-error livelock. **Both arms draw from
the same reference distribution, and the two Happy Mac boots have a churning exception ring
— the machine is alive and taking 60 Hz interrupts.**

**`0x40806b68` did not appear in a single one of the 24 `pc_live` samples taken across arm
A's three boots.** Against p137, which has now produced that address on *every* boot ever
attempted — the 3 paired A/B cycles and 2 distribution boots of Part 137, plus today's
characterisation boot, ≥6/6.

**`8887507` alone does not reproduce the wedge.** My prime suspect, and the one the symptom
analysis pointed at, is exonerated. The culprit is `7057e80` or `2db5bd3`.

Small-n caveat stated rather than buried: n=3 per arm. That is ample against a defect
observed at 6/6 and nil at 0/3, but it does not resolve p133's own Happy-Mac rate (1/3 here
versus 7/12 in Part 137's larger sample) — nor does it need to.

### 6.2b How far does p137 get, in retired instructions? — and a finding that cuts against my own framing

p137 freezes at **88,212,163** retired macros (`0x054202c3`). A bare count means nothing
without a scale, so the same live counter was sampled against wall clock from `reset` on a
build that keeps executing (`tools/retire_trajectory.sh`, HI-LO-HI bracketed because the LO
half wraps well inside a 200 s boot).

Arm A, a boot that ran the whole window (this one landed in the ROM serial monitor,
`pc_live=0x4084afa6` — an ordinary member of p133's reference distribution):

```
t=  0s  macros=59,774,588      pc_live=0x4084a966
t= 16s  macros=457,055,381     pc_live=0x4084afa6   >>> crossed p137's frozen count
t= 97s  macros=2,442,698,209
t=210s  macros=5,219,433,599
```

A machine that keeps running retires **~24.8 million macros per second**, so p137's
88.2 M is **≈3.5 seconds of CPU execution**, and any live machine passes that mark inside
the first ~16 s of a 215 s boot.

**What that does and does not establish.** It confirms *frozen* beyond doubt. It does **not**
cleanly separate "diverged early" from "did the real work and then stopped", because a
machine that reaches the SCSI probe spends most of its instruction budget spinning in poll
loops — 5.2 billion macros in 210 s is overwhelmingly spin, not boot progress. p137's ring
shows deep Slot Manager enumeration, which is genuinely late work. So the honest reading is:
88.2 M is a plausible amount of *productive* boot work, and the counter cannot arbitrate
further without knowing the ROM's productive-instruction budget.

#### A frozen retired-macro counter is NOT unique to p137 — recorded against my own argument

A fourth p133 boot, run for this trajectory measurement, **also froze**: counter static at
19,917,763 and `pc_live` static at `0x408046aa` for 212 s.

```
408046a8:  7400        moveq #0,%d2
408046aa:  4a32 2800   tstb %a2@(0,%d2:l)     <-- p133 froze HERE
408046ae:  1212        moveb %a2@,%d1
```

That is a **byte read through a device pointer**, inside a routine full of
`st %a2@(16)` / `clrb %a2@(16)` / `tstb %a2@` — hardware register poking, the classic
probe-and-expect-a-bus-error idiom. It is a different address and a different instruction
class from p137's `a06e` fetch-boundary stall, and it belongs to p133's own documented
early-failure family.

Recorded because it weakens a claim I was leaning on: **"the machine retires nothing" is not
by itself a p137-specific signature.** p133 can do it too. What separates the two arms is the
*address* and the *rate*: p133 was alive in 3 of its 4 boots tonight (rings 5→13, 13→2, 7→13)
and froze once, at `0x408046aa`; p137 has frozen at `0x40806b68` on **every boot ever
attempted** (≥6/6, across three separate programming cycles, with `exc_head=19` reproducing
exactly). Arm A has never produced `0x40806b68` at all.

### 6.3 The remaining question needs exactly one more bitstream

`bb3bca1..2db5bd3` is a **linear chain** of three merges, and p137 — cumulative through
`2db5bd3` — already wedges. So there is no separate "test `2db5bd3` alone" experiment:
cumulative-through-`2db5bd3` **is** p137. The single discriminating build is
cumulative-through-`7057e80`:

* if it **wedges** → `7057e80` is the culprit;
* if it **boots** → `2db5bd3` is the culprit, by elimination against p137.

That build (arm B, `build_id 0x789a3549`) was launched in parallel with arm A's boot test.

---

## 7. Verdict

【FILL】

---

## 8. What was NOT run

【FILL】
