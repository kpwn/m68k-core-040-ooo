# v1 / shared-infrastructure bug fixes (tasks #225, #226) — design

**Status:** PROPOSED / DESIGN ONLY. All decisions in §0 are locked; none is implemented.
Direct input to a future `writing-plans` pass.

**Date:** 2026-08-18

**Scope:** two specific, independently-confirmed bugs found during this session's spec work,
both living in infrastructure that the deployed **v1** core (`m68k-ooo`, bound into
`macqd700-soc` as the `cpu` submodule) shares with this core:

1. **Task #225** — `macqd700-soc/rtl/soc/peripheral_bus.v` mishandles any write whose
   active-lane `WSTRB` has more than one bit hot. Found and characterised as **SOC-4** while
   writing the axi-socket-adapter spec.
2. **Task #226** — v1's hardware VIO CPU-liveness probes `dbg_pc` / `dbg_committed` are tied
   to `32'd0`, so the documented primary "is the CPU alive, and where" escalation procedure
   reads two constants. Characterised, and split into a SoC half and a CPU half, while
   writing the VIO spec.

This spec designs the fixes for **both**, including the cross-repository sequencing and the
regression obligations that a change to a *currently-deployed, System-7-booting* core carries.

**Dispatched under** the standing `/goal` session directive: *"feel free to modify the SoC if
it gets in the way, but you need to plan updates for v1 m68k-ooo, too."*

**Decision prefix:** decisions are numbered **SI1…** (*shared infra*), deliberate exclusions
**SI-OUT-n**, and open questions **SI-OPEN-n**, so that no number here
can be confused with the axi-socket spec's `D1`-`D30` / `SOC-1`-`SOC-4`, the VIO spec's
`V1`-`V28` / `VSOC-1`-`VSOC-4`, or the debug-ctrl spec's numbering. Cross-spec references
always carry the owning spec's prefix.

**Primary sources, and how they were treated.** Both bugs were *found* by earlier passes; this
spec re-verified every load-bearing citation against the **live working trees** of both sibling
repositories rather than copying prior investigations' line numbers. Six places where this
spec **corrects, sharpens or contradicts** its sources are called out in §7 — including one
that materially changes what the fix for task #225 must be.

- `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md` — owns **SOC-4** (the
  original statement of bug 1) and the whole `axi_i`/`axi_d` adapter design. This spec
  **supersedes SOC-4's prescribed fix** (§2.2) and leaves everything else in that spec alone.
- `docs/superpowers/specs/2026-08-18-vio-jtag-debug-design.md` — owns **VSOC-1…VSOC-4** (the
  SoC-side half of bug 2) and §6, which did the investigation this spec builds on. This spec
  supplies the CPU-side half that §6.2 explicitly handed off as task #226, and **corrects one
  ordering claim** in that hand-off (§3.4).
- `~/.claude/projects/-home-qwertyoruiop-m68k-core-040-ooo/memory/axi-socket-adapter-design-spec-2026-08-18.md`
  — the memory record of SOC-4's discovery.

**Repository state this spec was written against** (both re-checked live while writing):

| Repo | Path | State |
|---|---|---|
| `macqd700-soc` | `/home/qwertyoruiop/macqd700-soc` | HEAD `15e4650`, working tree dirty |
| `cpu` submodule (v1 / `m68k-ooo`) | `/home/qwertyoruiop/macqd700-soc/cpu` | **detached HEAD `4eab9008`**, one staged uncommitted change |
| this core | this worktree | branch `fmax-closure-fanout` |

The submodule state is not clean and is itself a coordination hazard — see **SI-OPEN-1** (§4.3).

---

## 0. Decision summary

Every numbered **SI** item is citable by a future implementation plan without re-deriving it.

### Task #225 — `peripheral_bus.v` multi-hot-strobe writes

| # | Decision |
|---:|---|
| **SI1** | The bug is **real and still present** at `macqd700-soc` HEAD `15e4650`; re-verified line-by-line against the live file, not inherited from the SOC-4 write-up. The file still documents its own gap at `peripheral_bus.v:586-590`. (§2.1) |
| **SI2** | **SOC-4's prescribed fix is wrong and is superseded here.** SOC-4 says: *"serialize … for **every** `pb_*` slot — generalizing the ASC-only `wr_asc_*` FSM"*. Applying that uniformly would be a **new, worse bug** on VIA1/VIA2/IWM/SCSI-register space, because those slots' device-local address does not depend on `AWADDR[1:0]` at all: serializing there emits *N repeated writes to the same stateful register* (VIA `IFR`/`IER`/`SR`/`T1`, SWIM phase latches) instead of N writes to N registers. (§2.2, §2.3) |
| **SI3** | The single defect splits into **two defects with two different fixes**, decided per slot by whether `AWADDR[1:0]` participates in that slot's device-local address decode. The discriminator is a property of the code, tabulated in §2.3, not a guess about silicon. |
| **SI4** | **Defect A — byte-granular slots** (`ENET`, `ORWELL`, `ADBINJ`, and `SONIC` when it does *not* take its own word path). Consecutive byte addresses are distinct device locations, so bytes are genuinely lost. **Fix: serialize** — generalize the existing `wr_asc_*` strobe-walk FSM (`peripheral_bus.v:776-849`, `:1007-1022`) from ASC-only to a slot-parametric `wr_ser_*` FSM covering ASC plus this family. This is SOC-4's mechanism, applied only where it is correct. (§2.4) |
| **SI5** | **Defect B — strided/aliased slots** (`VIA1`, `VIA2`, `IWM`, `SCSI` register space). `AWADDR[1:0]` is not in the decode, so the device can only ever see one write per bus cycle. The defect is therefore **not a dropped byte — it is the *wrong* byte**: today's fallback (`wr_strb_byte`, `:632-636`) is a priority encoder that scans from `strb[0]` upward, i.e. it picks the **lowest** hot strobe bit, which is the **highest** byte address, i.e. the **last** byte of a big-endian m68k store. A `move.w` to a VIA register writes the *second* byte, not the byte at the named address. **Fix: select the byte at `AWADDR`**, single pulse, no serialization. (§2.5) |
| **SI6** | The Defect-B fix is a change to `wr_byte` (`:637-638`) only, and only on the `!wr_lane_strb_onehot` arm. Concretely: when the strobe bit that guards the `AWADDR`-selected byte is hot, use `wr_addr_byte`; otherwise fall back to today's `wr_strb_byte`. The strobe-bit-for-offset mapping is `strb[3 - AWADDR[1:0]]`, derived from the file's own big-endian lane layout (`:627-636`, cross-checked against the ASC comment at `:568-577`). The fallback covers strobe/address disagreement, including the documented host/debug convention of *"the byte in the strobed lane with a word-aligned AWADDR"* (`:617-619`). (§2.5) |
| **SI7** | **Blast radius is exactly the broken cases.** Both fixes leave the `wr_lane_strb_onehot` arm untouched, so **every single-byte write behaves bit-identically to today**. Since every access that boots System 7 today either is single-byte or is already handled by one of the three existing serializers, the change cannot alter any access that currently works — it can only alter accesses that are currently wrong. This is the core safety argument and it must be stated in the implementing commit. (§2.5, §5.1) |
| **SI8** | **`SCC` is neither family and is deliberately treated as Defect B, conservatively.** Its decode is `{addr[5:4], addr[1], addr[2]}` (`:1514`): `addr[0]` is absent, `addr[1]`/`addr[2]` are present. So a WORD write at an even offset aliases both bytes onto one register, while a LONG write spans two *different* registers. Serializing an SCC write would push the Z85C30's **shared, cross-channel register pointer** twice — the exact corruption mechanism already documented in this repo's own known-red finding (`tb/tb_peripheral_bus.cpp:2715-2726`, `scc.v:438`, `:732-745`). SCC therefore takes the addressed-byte single-pulse fix and **nothing else**, pending SI-OPEN-2. (§2.6) |
| **SI9** | The three **existing** serializers stay exactly as they are and are **not** folded into the generalized FSM: SONIC-word (`:754-775`) keys on `wr_size_q`/`addr[0]` and reorders bytes for a word-addressed register file; the SCSI DMA shim (`:850-898`, `:1063-1075`) carries `drq`-gated pulse/ack sequencing whose comment explicitly forbids simplification. Only the ASC FSM is generalized, because it is the one whose mechanism *is* the generic one. (§2.4) |
| **SI10** | **Verification is a real fails-then-passes evidence pair**, not an assertion. `tb/tb_peripheral_bus.cpp` already has the exact harness (`axi_write_multi(addr, lane_data, strb)`) and the exact template (`test_asc_multi_byte_word_off2` / `_off0` / `_long_off0` / `_followup_single_byte`, `:1228-1301`). The implementing task must capture `make tb-peripheral-bus` output **red before the RTL change and green after**, both in the task report. (§2.8) |
| **SI11** | **Real-world reachability must be answered with a real measurement, not an argument**, before merge — and the primary instrument is a **MAME System 7 boot capture**, not the RTL ROM boot. The repository asserts in three separate places that real Mac code never word-writes these slots; **all three assertions are unsourced, and the identical assertion has already been empirically falsified twice** (ASC, SCSI DMA shim). MAME can boot System 7 with `-hard`, and both `tools/mame_axi_capture.lua` and `tools/mame_q700_rtl_overlay.py` emit an explicit per-access architectural **size**. Required measurements and the pre-committed consequence table are in §2.7. (§2.7) |

### Task #226 — v1's dead `dbg_pc` / `dbg_committed`

| # | Decision |
|---:|---|
| **SI12** | The bug is **real and still present**; all of the VIO spec's §6.1 citations were independently re-confirmed against the live trees (§3.1). The tie-offs are `rtl/soc/fpga_top_debug_ctrl.vh:46-47`; the probes are `rtl/soc/fpga_top_debug_vio.vh:433,437`; the runbook's primary CPU-liveness procedure is `docs/bringup_runbook.md:268-283`. |
| **SI13** | **The VIO spec's correction to the historical record is confirmed:** v1's `dbg_pc` was never a committed PC. Its driver is `cpu/rtl/core/fetch/if_stage.v:359` — `assign dbg_pc = pc;`, the IF-stage fetch PC. Re-verified at source. Restoring the probe by re-binding `dbg_pc` would restore a *mislabelled* signal, which is worse than a constant because it reads plausibly. (§3.1) |
| **SI14** | **v1 already has a real committed PC, and it is not a new tap.** `cpu/rtl/core/commit.v:652` `dbg_boundary_next_pc` is the post-instruction next PC of the last **macro-instruction** to retire (`:3193` `<= actual_next` under `rob_is_last_uop`; `:3229`, `:3425` on the other retire/redirect paths). It is already routed to wrapper scope and is presently **unused** — sunk into the explicit unused-signal list at `m68k_axi_wrapper.v:1227`. Exporting it costs zero new sequential logic. (§3.2) |
| **SI15** | **`dbg_boundary_next_pc` is chosen over `dbg_last_pc` and over `dbg_boundary_pc`, and the reason is cross-core semantic parity, not convenience.** The VIO spec's **V12** locks this core's `last_commit_pc` to the *post-instruction* PC (`CommitTrace.scala:10`). `dbg_boundary_next_pc` is the same quantity in v1. `dbg_last_pc` (`commit.v:648`) is µop-granular and pre-instruction; `dbg_boundary_pc` (`:651`) is macro-granular but pre-instruction. Since `probe_in5`, the dashboard row, the runbook prose and `jtag_repl.tcl`'s decoder are **single shared host-side artefacts** (VSOC-4), a probe whose meaning changes with which core is in the bitstream is precisely the 3am-wrong-conclusion hazard the VIO spec's §6.1 exists to prevent. (§3.2) |
| **SI16** | **The retire counter is `dbg_committed`, exported as-is.** It already is what it claims (`commit.v:638`, incremented at every retire: `:3184`, `:3216`, `:3415`, `:3500`, `:4005`), it is already at wrapper scope and already consumed by `debug_ctrl` and the memory-access unit, and exporting it adds nothing. Its two divergences from this core's `retire_count` — it **wraps** rather than saturating, and it is **host-writable** through `DBG_CTRL_COMMITTED` (`commit.v:2403`) — are real and are handled by documentation (SI17), not by adding new sequential logic to a deployed core. (§3.2) |
| **SI17** | **The semantic divergences are discharged in the shared host-side artefacts, and that is a required deliverable of this fix, not a nicety.** VSOC-4's dashboard row, `jtag_repl.tcl` decoder and `docs/bringup_runbook.md:273,275,282-283` must say *post-instruction committed PC (address of the next instruction)* and must state that the retire counter is µop-granular and wraps on v1 / saturates on this core. A restored probe with a wrong label is a regression dressed as a fix. (§3.3) |
| **SI18** | **`dbg_pc` itself must not be re-pointed.** It has two live functional consumers inside v1 that expect the IF PC: `debug_ctrl.v:1584` (`OFF_PC` CSR read-back) and `debug_ctrl.v:2227` (`halt_hit_pc_r <= dbg_pc`). Task #226 **adds ports**; it changes no existing signal's meaning. Whether `OFF_PC`/`halt_hit_pc_r` should themselves be a committed PC is a separate, real question and is explicitly **not** answered here (SI-OUT-4). (§3.3) |
| **SI19** | **The v1-side and SoC-side halves are NOT independently orderable, contrary to the VIO spec's hand-off.** `fpga_top_debug_ctrl.vh` has exactly **one** `u_cpu` port-connection list (`:139-205`), with `` `ifdef CPU_M68K `` selecting only the *module name*. Adding group-8 connections there (VSOC-2) makes elaboration of the `CPU_M68K` build **hard-fail** on unknown ports until v1's wrapper declares them. The correct order is: v1 ports first (safe alone — an unconnected output port is legal Verilog), then a single atomic SoC commit carrying the gitlink bump *and* VSOC-1/VSOC-2 together. (§3.4, §4.2) |
| **SI20** | **v1 must declare the *whole* of socket group 8, not just two ports.** The same single-connection-list argument applies to every member of the group the VIO spec defines (`vio_cpu_snapshot` 96b, `vio_heartbeat` 32b, `vio_build_id` 32b, `vio_boot_pc` 32b out, `vio_ctl` 4b out, plus the two liveness ports). v1 drives the two it can drive meaningfully (SI14, SI16) and **ties the remainder off to constants**, exactly as `cpu_stub.v` does under VSOC-1. This is a larger obligation than the VIO spec's hand-off states and must be planned as such. (§3.4) |

### Cross-repo, risk, and scope

| # | Decision |
|---:|---|
| **SI21** | **Both fixes land in repositories this one does not own, and neither is a submodule-pointer bump alone.** Task #225 is a single-repo change in `macqd700-soc` (no submodule involvement). Task #226 is a two-repo change: a commit in the `cpu` repo followed by an atomic `macqd700-soc` commit carrying both the gitlink bump and the SoC-side wiring. Full ordering in §4. |
| **SI22** | **Neither fix may merge on simulation evidence alone.** v1 boots System 7.0.1 to Finder on real hardware today. Task #225 changes RTL on the live peripheral write path; task #226 adds ports and nets that re-time and re-route a closed design. Both carry an on-hardware boot-to-Finder obligation before merge, enumerated per-fix in §5. |
| **SI-OUT-1** | **Not a v1 audit.** This spec fixes two already-found bugs. It does not survey v1 for others, and finding a third during implementation is a new task, not scope creep into this one. |
| **SI-OUT-2** | **Not the SoC-side VIO wiring.** VSOC-1…VSOC-4 belong to the VIO spec. This spec depends on VSOC-1's group-8 definition and states the ordering constraint (SI19); it does not redesign it. |
| **SI-OUT-3** | **Not the `axi_i`/`axi_d` adapter work.** D1-D30 / SOC-1…SOC-3 belong to the axi-socket-adapter spec. The one exception is SOC-4, whose *fix* this spec supersedes (SI2) while leaving that spec's core-side derivations (D24, D25, D30) untouched. |
| **SI-OUT-4** | **Not v1's `OFF_PC` / halt-PC semantics.** `debug_ctrl.v:1584,2227` report an IF PC under a name that suggests otherwise. Real, adjacent, out of scope (SI18). |
| **SI-OUT-5** | **Not the peripheral-bus *read* path.** The read side's one-`pb_rd`-pulse-plus-byte-broadcast behaviour (recorded as `U4` in the axi-socket spec) is a separate, already-characterised issue. This spec touches only write-path logic. |

---

## 1. What these two bugs have in common, and why they share a spec

Nothing architecturally. They share a spec because they share a **constraint**: each one's fix
lands in a repository this project does not own, on a core that is deployed and working, where
the cost of a regression is a machine that no longer boots. Designing them together forces one
consistent answer to *"what evidence is required before touching v1?"* rather than two
inconsistent ones (§5).

They also share a discovery pattern worth recording: both were found as *side effects* of
specifying something else, by re-reading real source instead of trusting a summary. In both
cases the summary was wrong in a way that mattered — SOC-4's fix would have introduced a new
bug (§2.2), and the VIO spec's hand-off understates both the port count and the ordering
constraint (§3.4).

---

## 2. Task #225 — `peripheral_bus.v` drops or misdelivers multi-hot-strobe writes

### 2.1 Re-verification against live source (SI1)

Re-checked against `/home/qwertyoruiop/macqd700-soc/rtl/soc/peripheral_bus.v` at HEAD
`15e4650`. The defect is present and the mechanism is exactly as SOC-4 described:

- `pb_wr_active` (`:993-1002`) is a **single-cycle** pulse per AXI write beat, suppressed only
  for the three slots that have their own serializer (SONIC-word, ASC-multi, SCSI-DMA-shim).
- the byte it presents is chosen by `wr_byte` (`:637-638`), which for a multi-hot strobe falls
  through to the `wr_strb_byte` priority encoder (`:632-636`).
- the file states the gap itself, at **`:586-590`**:

  > *"Only ASC participates; other `pb_*` slots keep the single-byte fast path (multi-byte
  > writes to e.g. SCC would fall through the priority encoder and silently drop bytes, but the
  > boot path doesn't exercise that — extend on-the-fly if a future regression turns one up)."*

**Citation drift, recorded rather than propagated.** The axi-socket spec cites this comment as
`:587-592`; it is at `:586-590` in the live file. Its `:632-638` and `:993-1002` citations are
exact. The ASC FSM it cites as `:780-840` is at `:776-849`. (§7, item 1.)

And v1 does emit multi-hot strobes as its *normal* encoding — the file's own byte-lane note
documents `WORD off=2 → strb=4'b0011` (`:568-577`). So the exposure is not hypothetical in the
sense of "can the bus carry such a beat"; it is only a question of whether any driver issues
one (§2.7).

### 2.2 The correction: SOC-4's prescribed fix would introduce a worse bug (SI2)

SOC-4 (axi-socket-adapter spec, §0 and `:1446`) prescribes:

> *"serialize any write whose active-lane WSTRB has >1 bit hot into one `pb_wr` pulse per hot
> bit, on **every** `pb_*` slot. Generalize the existing ASC `wr_asc_*` FSM."*

The ASC serializer works by **incrementing the peripheral-local byte address** once per hot
strobe bit — `asc_addr = wr_asc_word_active ? {wr_addr_q[11:2], wr_asc_addr_lsb} : …`
(`:1573-1577`), where `wr_asc_addr_lsb = wr_asc_addr_base_q + wr_asc_addr_off_q` (`:1021`).
That is correct *only if `AWADDR[1:0]` is part of the slot's device-local address*.

For VIA1, VIA2 and IWM it is not: their device-local address is `wr_addr_q[12:9]` (`:1463`,
`:1470`, `:1617`) — a 512-byte register stride, in which `addr[1:0]` is a don't-care. For SCSI
register space it is `{5'b0, addr[7:4]}` (`:1531`, via `scsi_local_addr` at `:1525-1533`) — a
16-byte stride, same story. On those
slots, "increment the byte address and pulse again" resolves to **pulse the same register
again**, N times, with N different bytes. On a 6522 VIA that means writing `IFR`/`IER`/`SR` or
a timer latch twice per bus cycle; on the SWIM/IWM register file, whose writes *are* the
phase-line latches, it means driving a state machine twice.

Today's behaviour on those slots is one write of the wrong byte. SOC-4's fix would make it N
writes, the last of which is the wrong byte — strictly worse, and worse in a way that only
shows up as intermittent device misbehaviour on real hardware.

**SI2 supersedes SOC-4's fix.** SOC-4's *finding* stands; its prescription does not.

### 2.3 The discriminator, tabulated from real code (SI3)

The question that decides a slot's treatment is mechanical: **does `AWADDR[1:0]` reach the
device-local address?** Every row below is read off the live `peripheral_bus.v`.

| Slot | device-local address expression | line | `addr[1:0]` in decode? | family |
|---|---|---:|---|---|
| `VIA1` | `wr_addr_q[12:9]` | `:1463` | **no** (0x200 stride) | B — strided |
| `VIA2` | `wr_addr_q[12:9]` | `:1470` | **no** | B — strided |
| `IWM`/SWIM | `wr_addr_q[12:9]` | `:1617` | **no** | B — strided |
| `SCSI` regs | `{5'b0, addr[7:4]}` | `:1525-1535` | **no** (0x10 stride) | B — strided |
| `SCC` | `{addr[5:4], addr[1], addr[2]}` | `:1514` | **partly** (`[1]`,`[2]` yes; `[0]` no) | see §2.6 |
| `ENET` | `wr_addr_q[2:0]` | `:1478` | **yes** | A — byte-granular |
| `ORWELL` | `wr_addr_q[7:0]` | `:1503` | **yes** | A |
| `ADBINJ` | `wr_addr_q[7:0]` | `:1628` | **yes** | A |
| `SONIC` (non-word path) | `wr_addr_q[12:0]` | `:1490` | **yes** | A |
| `ASC` | `wr_addr_q[11:0]` | `:1573-1577` | **yes** | A — *already serialized* |
| `SONIC` (word path) | `wr_addr_q[12:0] + phase` | `:1490` | — | already serialized, leave alone (SI9) |
| `SCSI` DMA shim | `addr[8:0]` | `:1527-1529` | **yes** | already serialized, leave alone (SI9) |

### 2.4 Fix A — generalize the ASC serializer to the byte-granular family (SI4, SI9)

**Mechanism: the existing one, made slot-parametric. No new mechanism is invented.**

The ASC FSM is already exactly the right machine; it is merely hard-wired to one slot. Its
parts:

| Part | Where |
|---|---|
| capture + multi-byte detect (`wr_lane_strb_count > 1`) | `:776-793` |
| two-phase pulse/await-ack walk, HIGH→LOW strobe | `:798-838` |
| highest-hot-bit index function `wr_asc_hi_bit_idx` | `:1008-1015` |
| byte select / address LSB / pulse | `:1016-1022` |
| generic-pulse suppression terms | `:989-990`, `:996-997` |
| per-slot address mux term | `:1573-1577` |

The change is to rename `wr_asc_*` → `wr_ser_*`, replace the `wr_slot_q == SLOT_ASC` guards
with a `slot_serializes(wr_slot_q)` predicate covering `{ASC, ENET, ORWELL, ADBINJ, SONIC}`,
and add the same `wr_ser_word_active ? {<slot's upper addr bits>, wr_ser_addr_lsb} : …` term
to each participating slot's `*_addr` mux and `wr_ser_byte` to its `*_wdata` mux. The FSM's
ack source becomes a per-slot mux over `{asc,enet,orwell,adbinj,sonic}_ack`, which the file
already builds in the same shape for `wr_b_done` (`:908-919`).

**HIGH→LOW walk order is preserved verbatim and must not be "simplified".** The file derives it
at `:568-577` from the LSU's `m68k_mem_strb`/`m68k_mem_wdata` layout: the first big-endian byte
(the one at `AWADDR`) sits at the *highest* hot strobe bit, so walking high→low emits
`AW, AW+1, AW+2, …` in memory order. That derivation is shared by the ASC and SCSI-DMA
serializers and is confirmed independently below (§2.5).

**SONIC needs care and is the one non-mechanical part of Fix A.** `SONIC` already has a word
serializer, but it is entered only on `wr_size_q == 3'd1 && !wr_addr_q[0]` (`:754`, `:971-974`);
a multi-hot beat that misses that predicate (any `size != 1`, or an odd address) falls to the
generic path today. Fix A must catch exactly that residual without shadowing the word path.
The suppression term at `:994-995` already expresses the word path's predicate and is the
natural guard.

**Why the other two serializers are not folded in (SI9).** SONIC-word reorders bytes for a
word-addressed register file and keys on transfer size, which the generic walk has no concept
of. The SCSI DMA shim gates each pulse on `scsi_dma_wr_ready` and re-arms only after `pb_ack`,
and its own comment (`:1027-1044`) explicitly forbids collapsing it into a simpler pattern
because doing so previously produced an unresolvable-B wedge. Both stay.

### 2.5 Fix B — deliver the byte the transaction actually names (SI5, SI6, SI7)

**The sharper statement of the defect.** On a strided slot the device can only ever be written
once per bus cycle, so "bytes are dropped" is not the interesting part — *which* byte survives
is. Deriving it from the live code:

- `wr_addr_byte` (`:627-631`) maps `AWADDR[1:0] = k` to `wr_lane_data[31-8k : 24-8k]`.
- `wr_strb_byte` (`:632-636`) is a priority chain starting at `wr_lane_strb[0]`, which guards
  `wr_lane_data[7:0]`.
- so byte-offset `k` is guarded by strobe bit `3-k`. Cross-check against the file's own worked
  example at `:568-577`: *"WORD off=2: strb=4'b0011 … bit 1 guards wdata[15:8] = high byte →
  addr AW (2)"*. `3-2 = 1`. ✓ And *"bit 0 guards wdata[7:0] = low byte → addr AW+1 (3)"*.
  `3-3 = 0`. ✓

Therefore `wr_strb_byte`'s lowest-bit-first priority picks the **highest** byte address in the
access — the **last** byte of the store, not the byte the transaction named. A
`move.w #$1234, via1_reg` delivers `$34`, not `$12`.

**The fix, stated precisely.** Only the `!wr_lane_strb_onehot` arm of `wr_byte` (`:637-638`)
changes. The addressed byte is used when its own strobe bit is hot; otherwise today's
behaviour is kept:

```
wire       wr_addr_strb_hot = wr_lane_strb[3 - wr_addr_q[1:0]];
wire [7:0] wr_byte =
    wr_lane_strb_onehot ? (wr_addr_byte | wr_strb_byte)
                        : (wr_addr_strb_hot ? wr_addr_byte : wr_strb_byte);
```

**Why the fallback arm is required and is not defensive padding.** It covers the case where a
multi-hot beat's `AWADDR` names a byte whose own strobe bit is *not* asserted — i.e. the strobe
pattern and the address disagree about where the access starts. Such a beat is ill-formed under
AXI4 for the transfer it claims, but it is reachable: the file documents a second live
convention at `:617-619` (*"Some host/debug paths instead put the byte in the strobed lane with
a word-aligned AWADDR"*), and this core's own in-group-misaligned shapes — the ones the
axi-socket spec's **D30** exists to eliminate — have the same signature. For those, preserving
today's arbitrary-but-defined behaviour is strictly safer than inventing a new one, and it is
what keeps the existing `test_granularity_per_device` VIA1 case green (§2.8). The arm must be
accompanied by a `translate_off` warning so any real occurrence is loud rather than silent.

**Blast radius (SI7).** `wr_lane_strb_onehot` is unchanged, so every one-hot write — which is
every write that boots System 7 today outside the three existing serializers — produces a
bit-identical `wr_byte`. Neither Fix A nor Fix B can perturb an access that currently works.
That is the whole safety argument, and it is a property of where the change sits in the
expression, not a claim about workload.

### 2.6 SCC — the slot that is neither, and why it is deliberately left short of a full fix (SI8)

`scc_addr = {wr_addr_q[5:4], wr_addr_q[1], wr_addr_q[2]}` (`:1514`). `addr[0]` is absent;
`addr[1]` and `addr[2]` are present. So:

- a WORD write at an even offset covers bytes `{k, k+1}` which, when `k` is even, differ only
  in `addr[0]` → **one** SCC register, addressed twice;
- a LONG write covers `{k…k+3}` → **two** different SCC registers.

Neither Fix A nor Fix B is obviously right. Fix A would pulse the SCC twice for the WORD case,
and the Z85C30's register pointer is **shared across both channels** and is reset by any
control-port access — this repository has already characterised that exact corruption
mechanism, in `tb/tb_peripheral_bus.cpp:2715-2726` (the `KNOWNRED` block), citing
`scc.v:438,732-745`. Double-pulsing an SCC control port is a known way to corrupt live
AppleTalk/serial traffic.

**SI8: SCC takes the Fix-B treatment only** — one pulse, carrying the byte at `AWADDR`. That is
identical to what a `move.b` does today, so it is the minimum-divergence choice, and it strictly
improves the multi-hot case (right byte instead of last byte). Whether the LONG case *should*
reach two registers is a real question that requires MAME cross-validation of what a real Q700
SCC sees on a longword bus cycle, and is recorded as **SI-OPEN-2** rather than guessed.

### 2.7 Real-world reachability — what is known, and what must be measured (SI11)

This section was researched specifically, across both repositories, rather than reasoned about.
The result is worth stating bluntly: **the question is unanswered, every in-tree claim that it
is answered is unsourced, and the same claim has already been proven wrong twice.**

#### 2.7.1 What the repository asserts (three times, none of it sourced)

| Assertion | Where |
|---|---|
| *"multi-byte writes to e.g. SCC would … silently drop bytes, but **the boot path doesn't exercise that**"* | `rtl/soc/peripheral_bus.v:586-590` |
| finding **B4**, *"Sub-word peripheral writes lose data on word-strided stores … **This is fine for the Mac ROM, which only does byte stores to peripherals**"* | `docs/endianness_audit.md:166-190` |
| *"which matters for any CPU that accidentally emits a word store to an 8-bit peripheral (**the real Mac doesn't**, but we document the behaviour)"* | `tb/tb_peripheral_bus.cpp:1901-1906` |

**This is a third independent recording of the same bug** — B4 has been sitting in the
endianness audit as a known "latent" finding, unconnected to SOC-4. Its worked example is
itself wrong (§7, item 7), and its file path (`rtl/sys/peripheral_bus.v`) is stale, but the
mechanism it describes is the one fixed here.

**And one of the three assertions is load-bearing in a way that hides evidence.** The VIA1
golden-trace capture tool is structurally incapable of recording a multi-lane access:
`tools/mame_via1_capture.lua:133-142` justifies a `break` after the first hot byte lane with
*"The 68040 always issues byte-wide MMIO accesses to VIA"*, and `:154-160` repeats it
(*"emit only the first lane of any combined mask … Break after the first match"*). So VIA1's
golden trace **cannot** show a word write even if one occurred. The assumption is baked into
the instrument that would otherwise test it.

#### 2.7.2 The precedent that makes those assertions untrustworthy

The same reasoning was applied to ASC and then falsified:

- commit `1b7d875` ("peripheral_bus: sim-time canary for multi-byte ASC writes") argued from a
  real measurement — *"~60K writes to FIFO A in a single 5M-uop boot run … separate single-byte
  AXI transactions, **never a multi-strobe beat**"*;
- commit `9468831` then found that the **Q700 ROM uses `MOVE.W`** for the CD-XA filter table and
  SRC step, so *"only one byte reached the chip … K0 coefficients at odd offsets were never
  written"*. The in-tree comment records the same family of finding for the startup-chime volume
  registers (`peripheral_bus.v:559-566`, `:1595-1599`).
- and again on the SCSI DMA shim, commit `eba8aa2`: a `move.w` to the shim arriving as one beat
  with two hot strobe bits, which must push two bytes per MAME's `dma16_swap_w` — *"the old
  single-pulse generic path would have silently dropped the low byte and under-counted the
  pseudo-DMA transfer **on the OS disk-write path**"*. Note this one was derived from MAME's
  device model, not from a capture, because no capture would have shown it either.

**Two of the three slots anyone actually looked at turned out to take multi-byte writes. The
slots in scope here have never been looked at.** That is the whole justification for SI11.

#### 2.7.3 The one slot with real evidence, and the one with the worst prior

- **IWM/SWIM — weak positive evidence of byte-only.** `tb/vectors/swim_mame_q700_753.csv` is a
  869-event MAME capture of a real **System 7.5.3** boot (no floppy), taken with
  `tools/mame_iwm_capture.lua`, which — unlike the VIA1 tool — emits one row per hot byte lane
  with **no `break`**, so a 2-byte access would appear as two rows sharing one timestamp. There
  are **zero duplicate timestamps** across all 869 events. Caveats: boot only, no media, and the
  header notes the byte always rides mask `0xFF000000`.
- **SONIC — the worst prior, and it is in Fix A's family.** The DP83932 is a genuinely **16-bit**
  part (`rtl/mac/q700_eth_sonic.v:1-15`, modelled byte-wise at `:243-290`). A 16-bit device
  driven by a 68k driver is exactly the shape that produces word stores. It already has a
  word serializer, but only for `size==1 && !addr[0]` (§2.4); everything else falls to the
  generic path. There is no ENET/SONIC/ORWELL capture in the tree at all.
- **ADBINJ carries no risk from this analysis at all** — it is this project's own host-injection
  port, not a Mac device, so no ROM or System 7 traffic reaches it by construction. It is in
  Fix A only for uniformity.

#### 2.7.4 The measurements (required before merge)

Ordered by value. The first is the one that actually answers the question.

**M1 — MAME System 7 boot, size-annotated (primary).** MAME boots System 7 under this
project routinely: `tb/vectors/scsi96_mame_q700_753_tc16.csv` documents a healthy 45 s System
7.5.3 boot under `mame 0.285 -hard HD0-OpenRetroSCSI-7.5.3.hda` producing 275,355 events. Two
tools annotate size:

- `tools/mame_axi_capture.lua` emits `<seq>,<R|W>,<addr_hex>,<size_bytes>,<data_hex>` with
  `size` ∈ {1,2,4} derived by coalescing hot-mask runs (`:17-23`). Wrapped by
  `make tb-axi-lockstep` (`Makefile:1979-2010`) — but **that target passes no `-hard`**
  (`:1990-1995`), so it covers ROM boot only. For System 7 the lua must be run directly with a
  disk image added and `MAME_AXI_TRACE_LIMIT` raised. Answer =
  `awk -F, '$2=="W" && $4>1 && $3 ~ /^50/'`.
- `tools/mame_q700_rtl_overlay.py:55-62` emits, natively and **per slot**,
  `mame-mmio mode=… op=w label=VIA1 size=N addr=… data=… mem_mask=… pc=…` for every wrapped
  Q700 window. Enabled by setting `MAME_RTL_MMIO_TRACE` *without* `MAME_RTL_BRIDGE_SOCKET`. This
  gives a directly per-slot answer and is the preferred form.

Run both against a **System 7.0.1** image (the configuration v1 is deployed booting —
`HD0-OpenRetroSCSI-7.0.1-500M.hda`) and record, per slot, the count of `op=w` with `size > 1`,
together with the `pc` values so any hit can be traced to real driver code.

**M2 — full-RTL ROM boot with a generalized canary (secondary).** `make tb-fpga-top-rom` and
`make tb-fpga-top-rom-monitor-guard` (`Makefile:1832-1846`) boot the real Q700 ROM through the
complete `fpga_top` RTL — the real v1 core driving the real `peripheral_bus.v`. The ASC
informational `$display` at `peripheral_bus.v:1586-1616` already fires on
`wr_lane_strb_count > 3'd1`; **dropping its `wr_slot_q == SLOT_ASC` term makes every simulation
in the repo self-report a multi-hot write to any slot**, at zero cost. Do that first; it makes
M2 free and instruments every other testbench at the same time.

**M3 — no new tooling is needed, and one existing route is dead.** `tb_rom_boot.cpp:2929-3010`
already logs `size = popcount(strb)` for writes under `+data_watch`. Conversely, the RTL half of
the AXI lockstep is currently **compiled out for CPU data writes under `CPU_M68K`**
(`tb/tb_fpga_top_rom.cpp:2344-2422`, a casualty of the socket split), so the MAME side is today
the usable oracle — which is why M1 leads.

#### 2.7.5 How the result changes the decision — pre-committed

Stated in advance so the number cannot be rationalised after the fact.

| Outcome | Consequence |
|---|---|
| Zero hits on every slot, across M1 and M2 | The fix is **latent-only**. It still lands (it is correct, cheap, and closes a real hole before this core drives the same fabric), it carries no live-behaviour risk, and §5.1's hardware gate is a smoke test. |
| Hits on a **byte-granular** slot (Fix A) — SONIC is the likeliest | Live behaviour changes from "one byte written" to "N bytes written". This is the case that plausibly *fixes* an existing latent misbehaviour, and equally the case where anything accidentally depending on the truncation regresses. Hardware gate becomes mandatory-blocking. |
| Hits on a **strided** slot (Fix B) | Live behaviour changes from "last byte" to "first byte" delivered to a stateful device. **Highest-risk outcome in this spec.** Requires the full hardware gate plus a targeted before/after comparison of that device's observable behaviour, and the captured `pc` values must be traced to the driver code responsible. |
| Hits on **SCC** | Additionally forces **SI-OPEN-2** to be answered before merge rather than deferred, because the LONG case would then be live rather than theoretical. |

### 2.8 Test and verification strategy (SI10)

`tb/tb_peripheral_bus.cpp` is the vehicle. It already provides everything needed:

- the driver `axi_write_multi(addr, lane_data, strb)` — used by the ASC multi-byte tests;
- per-slot write-observation models exposing an ordered `writes` vector of `{addr, byte}`;
- the exact template at `:1228-1301` (`test_asc_multi_byte_word_off2`, `_word_off0`,
  `_long_off0`, `_followup_single_byte`);
- a parking convention for a deliberately-red scenario — leave it out of the `RUN()` list and
  reference it once to keep it compiled (`:2799-2810`), the pattern used by
  `test_KNOWNRED_scc_altbase_steals_swim_reg0`;
- a repo-level gate: `tb-peripheral-bus` is in `ALL_TBS` (`Makefile:6577`), and `make tb-all` is
  the repo-root `make test` gate with its own `TB_KNOWN_BROKEN` xfail list (`Makefile:6521-6526`).

**The fails-then-passes pair, concretely.** New scenarios, each of which must be demonstrated
red on unmodified `15e4650` and green after:

*Fix B (wrong byte → right byte), one per strided slot:*

| Scenario | Stimulus | Expectation after fix | Behaviour today |
|---|---|---|---|
| `test_via1_word_write_delivers_addressed_byte` | `AW` = a VIA1 register, `strb=4'b0011`, lane data `{…,0xAA,0xBB}` | **exactly one** `via1` write, byte `0xAA` | one write, byte `0xBB` |
| `test_via2_word_write_delivers_addressed_byte` | as above on VIA2 | one write, `0xAA` | one write, `0xBB` |
| `test_iwm_word_write_delivers_addressed_byte` | as above on IWM | one write, `0xAA` | one write, `0xBB` |
| `test_scsi_reg_word_write_delivers_addressed_byte` | as above in SCSI **register** space (not `addr[8]`) | one write, `0xAA` | one write, `0xBB` |
| `test_scc_word_write_single_pulse_addressed_byte` | as above on SCC | **exactly one** write, `0xAA` — the "exactly one" assertion is the SI8 lock | one write, `0xBB` |
| `test_via1_long_write_delivers_addressed_byte` | `strb=4'b1111` | one write, byte at `AW` | one write, lowest-strobe byte |

*Fix A (dropped bytes → serialized), one per byte-granular slot:*

| Scenario | Stimulus | Expectation after fix | Behaviour today |
|---|---|---|---|
| `test_enet_multi_byte_word_serializes` | `strb=4'b0011` at ENET offset `k` | two writes: `(k,hi)`, `(k+1,lo)` | one write, `lo` at `k` |
| `test_orwell_multi_byte_long_serializes` | `strb=4'b1111` | four writes, ascending, big-endian order | one write |
| `test_adbinj_multi_byte_word_serializes` | `strb=4'b0011` | two writes | one write |
| `test_sonic_multi_byte_non_word_path_serializes` | multi-hot beat that misses the `size==1 && !addr[0]` word predicate | serialized | one write — the §2.4 residual |
| `test_<slot>_multi_byte_followup_single_byte` | a multi-byte burst, then a single-byte write | third write lands correctly | — (FSM state-leak lock, mirroring `:1288-1301`) |

*Non-regression locks (must be green both before and after — they are the SI7 evidence):*

- every currently-registered scenario in the file, unchanged;
- specifically `test_via1_byte_rw`, `test_via2_byte_rw`, `test_scc_decode`, `test_iwm_decode`,
  `test_scsi_decode`, `test_asc_byte_select`, `test_be_lane_byte_matrix`,
  `test_unaligned_byte`, and all four existing `test_asc_multi_byte_*`;
- the three existing serializers' scenarios, proving SI9 held: `test_scsi_dma_shim_word_write`,
  `test_scsi_dma_shim_write_drq_withhold`, `test_enet_sonic_decode`.

**One existing test asserts the buggy behaviour, and it needs a decision — not a blind
deletion.** `test_granularity_per_device` (`:1863`) contains, at `:1901-1946`, a VIA1 word-store
case that deliberately locks in today's byte selection:

```
CHECK_TRUE("VIA1 saw one write from WORD store", via1.writes.size() == 1);
// Priority mux: lowest hot strb bit wins — bit 1 → wdata[15:8] = 0xBB.
CHECK_EQ("VIA1 word-store picks lowest strb byte (0xBB)", via1.writes[0].second, 0xBBu);
```

**Traced by hand against the proposed Fix B, it stays green.** Its stimulus is
`s_awaddr = 0x0F00000` (so `AWADDR[1:0] = 0`, lane 0), `s_wstrb = 4'b0110`, lane data
`0x00AABB00`. Fix B evaluates `wr_addr_strb_hot = wr_lane_strb[3 - 0] = wr_lane_strb[3] = 0`,
so the expression takes its **fallback arm** and yields `wr_strb_byte = 0xBB` — bit-identical to
today. The strobe shape `0110` names bytes 1 and 2 while `AWADDR` names byte 0; it is the
in-group-misaligned form the axi-socket spec's **D30** exists to stop this core from emitting at
all, and it is exactly the malformed case §2.5's fallback arm was written for.

So the test survives, and in fact **becomes the regression lock for the fallback arm** — which
is a better job than the one it was written to do. Two obligations follow:

1. the implementing task must **re-verify that trace by running it**, not by trusting this
   paragraph. If the arithmetic here is wrong the test goes red and the correct response is to
   re-derive, not to edit the expectation;
2. its comment block at `:1901-1906` must be **rewritten**. *"the real Mac doesn't"* is one of
   the three unsourced assertions §2.7.1 catalogues, and *"we document the behaviour"* now
   documents a different behaviour (the malformed-strobe fallback) than the one it names (the
   priority mux as a general policy). Leaving that comment in place after this fix would be
   actively misleading.

**Simulation assertion.** The `translate_off` warning required by §2.5 (multi-hot beat whose
`AWADDR`-named byte has no strobe) is a `$display`, not a `$finish` — the file downgraded
exactly this kind of trap from `$finish` to informational once the case became handled
(`:1601-1604`), and that precedent is followed. It should be added as part of the same
generalization of the ASC canary that §2.7.4's M2 requires, not as separate logic.

---

## 3. Task #226 — reconnecting v1's CPU-liveness probes

### 3.1 Re-verification against live source (SI12, SI13)

Every citation below was opened and read in the live trees while writing this spec.

| Claim | Verified at |
|---|---|
| the tie-offs exist and are `32'd0` | `macqd700-soc/rtl/soc/fpga_top_debug_ctrl.vh:46-47`, with the comment *"Live PC / retire-count taps relocated CPU-side in the socket split"* |
| the probes are bound and live in every shipping build | `macqd700-soc/rtl/soc/fpga_top_debug_vio.vh:433,437` (`.probe_in5(dbg_pc)`, `.probe_in9(dbg_committed)`) |
| host consumers assume they are live | `macqd700-soc/tools/jtag_bringup_tui.py:62,69`; `macqd700-soc/synth/vio_only_probe.tcl:61` |
| the runbook's primary liveness procedure depends on them | `macqd700-soc/docs/bringup_runbook.md:273` *"probe5 = last-committed CPU PC (shows boot progress)"*, `:275` *"probe9 = retired-insn counter"*, `:282-283` *"dbg_pc + dbg_committed tells you whether the core is running, stuck on a specific PC, or looping."* |
| `dbg_pc` is the **IF** PC, not a committed PC | `cpu/rtl/core/fetch/if_stage.v:359` — `assign dbg_pc = pc;` |
| an existing doc already warns about exactly this | `macqd700-soc/docs/rom_boot_bringup.md:439` — *"Resume must use the saved commit-side `pc next=…`, not `dbg_pc`, because the front-end can be far ahead of the last retired instruction."* |
| the wires already exist at v1's wrapper scope | `cpu/rtl/core/m68k_axi_wrapper.v:305` (`dbg_pc`), `:307` (`dbg_committed`), driven at `:712-713` |
| `dbg_committed` *is* a retire counter | `cpu/rtl/core/commit.v:638`, incremented at `:3184,3216,3415,3500,4005` |

So the VIO spec's §6.1 is confirmed in full, including its correction of the historical record.
**SI13**: rebinding `dbg_pc` would restore a probe that reads plausibly and means something
else. That is not a fix.

### 3.2 What should drive the ports (SI14, SI15, SI16)

The task brief asks whether the right answer is a *new* v1-internal tap. It is not — v1 already
computes a genuine committed PC and simply never exports it.

**Candidates, all real, all already at wrapper scope:**

| Signal | Meaning | Granularity | Declared | Currently used? |
|---|---|---|---|---|
| `dbg_pc` | IF-stage fetch PC | — | `m68k_axi_wrapper.v:305` | yes — `debug_ctrl` `OFF_PC` + halt-PC (SI18) |
| `dbg_last_pc` | PC **of** the last retired µop | µop | `commit.v:648`, wrapper `:312` | **no** — sunk at `:1227` |
| `dbg_boundary_pc` | PC **of** the last retired macro-instruction | instruction | `commit.v:651` | yes — `debug_ctrl` boundary machinery, `:617` |
| `dbg_boundary_next_pc` | **next** PC after the last retired macro-instruction (actual target, incl. taken branches) | instruction | `commit.v:652`, wrapper implied | **no** — sunk at `:1227` |
| `dbg_committed` | retired-µop counter | µop | `commit.v:638`, wrapper `:307` | yes — `debug_ctrl` + mem-acc unit |

**SI14/SI15 — the PC port is driven from `dbg_boundary_next_pc`.** Its update sites are
`commit.v:3193` (`<= actual_next` under `rob_is_last_uop`), `:3229` and `:3425`, and its port
comment at `:653-666` states the semantics explicitly: the *actual* next PC, carrying the real
target on a control-flow change, as distinct from the linear-flow `dbg_boundary_linear_next_pc`
next to it. It is free-running — no host-gating, no capture arming.

The reason this beats the two alternatives is **cross-core semantic parity**. The VIO spec's
**V12** locks this core's `last_commit_pc` to the *post-instruction* PC, on the authority of
`CommitTrace.scala:10` (*"POST-instruction PC (next-instruction addr); must match Musashi
`--trace` for lock-step"*). `dbg_boundary_next_pc` is that same quantity in v1.
`dbg_last_pc` and `dbg_boundary_pc` are both *pre*-instruction, and `dbg_last_pc` is
additionally µop-granular, so it can report a PC in the middle of a macro-instruction that no
disassembly lists.

Because `probe_in5`, the dashboard row, the runbook sentence and `jtag_repl.tcl`'s decoder are
**one copy each, shared by whichever core is in the bitstream** (VSOC-4), any of the three
alternatives would make the same probe mean a different thing per build. Parity is the design
constraint here, not a tie-breaker.

**A convenient corollary, verified rather than assumed.** v1's dual-commit override
(`commit.v:4383-4394`, *"Last-uop boundary update overrides head+0's if head+1 is a last-uop …
NBAs, last write wins"*) applies to `dbg_boundary_next_pc` as well as `dbg_boundary_pc`, so on
a dual retire the **younger** instruction wins. That is exactly the VIO spec's own
**"slot 1 wins"** priority rule for `last_commit_pc`. The two cores agree on that edge case
without either having been adjusted for the other.

**And one real wrinkle, recorded rather than smoothed over.** The lane-B override assigns
`dbg_boundary_next_pc <= lb_npc_fallthru` (`:4388`) — the *linear* fall-through — whereas the
lane-A path assigns `actual_next` (`:3193`), the real control-flow target. So on a dual-commit
cycle whose younger instruction is a **taken** branch, v1's reported next PC is the
fall-through, not the target, for one probe read. For a liveness probe this is harmless: the
value is stale by one instruction for at most one retire and self-corrects immediately. It is
recorded because an operator comparing a probe read against a disassembly at a branch could
otherwise be briefly confused, and because a future consumer that needs exactness must know
this is not one. It is **not** a reason to prefer a different signal — `dbg_last_pc` and
`dbg_boundary_pc` have no post-instruction semantics at all, which is a larger divergence.

**SI16 — the counter port is driven from `dbg_committed`, unchanged.** It is already the thing
the runbook names, and exporting it is a wire. Two divergences from this core's `retire_count`
are real and are accepted rather than engineered away:

- **it wraps, this core's saturates.** The VIO spec's §3.3.2 argues saturation matters because
  *"zero means nothing has retired since reset"* must not be reachable by wraparound. On v1 a
  32-bit µop counter wraps after roughly 40 s of execution, so a read of `0x00000000` is
  *very* unlikely to be a wrap but is not *provably* not one. Adding a saturating shadow
  counter to v1 is ~33 FF of new sequential logic in a deployed, timing-closed core to close a
  hazard that a second read one second later resolves. **Not worth it** — the divergence is
  documented instead (SI17).
- **it is host-writable** through `DBG_CTRL_COMMITTED` (`commit.v:2403`), so a JTAG session can
  perturb it. Also documented, not fixed.

**SI-OPEN-4** records the one thing that is genuinely unresolved: whether this core's
`retire_count` increments per µop or per macro-instruction. The VIO spec's expression
(`ct.traceFire(0) + ct.traceFire(1)`) counts `CommitTrace` fires, and this spec did not
establish which granularity that is. It does not change SI16 — both cores' counters are
monotone progress indicators either way — but the shared host-side label under SI17 cannot be
written until it is settled. v1's `dbg_macros` (`commit.v:647`, incremented under
`rob_is_last_uop`) is available if instruction-granularity parity turns out to be wanted.

### 3.3 What must not change, and what must be relabelled (SI17, SI18)

**SI18 — `dbg_pc` keeps its meaning.** Two live consumers read it as the IF PC:
`cpu/rtl/core/debug/debug_ctrl.v:1584` (`OFF_PC: rd_val = dbg_pc;`) and `:2227`
(`halt_hit_pc_r <= dbg_pc;`). Re-pointing `dbg_pc` at a committed PC would silently change what
`OFF_PC` and the halt-PC snapshot report over the `dbg_axi` debug interface. Task #226 **only
adds ports**. Whether `OFF_PC`/`halt_hit_pc_r` are themselves mislabelled is a real, adjacent
question and is **SI-OUT-4**.

**SI17 — relabelling is a deliverable, not a follow-up.** VSOC-4 already requires touching the
three hand-maintained host-side tables. This fix's completion criteria include:

- `docs/bringup_runbook.md:273` — *"probe5 = last-committed CPU PC"* becomes **post-instruction
  committed PC (address of the next instruction to execute)**;
- `docs/bringup_runbook.md:275` — the retire counter is named as **µop-granular, wrapping on
  v1**;
- `docs/bringup_runbook.md:282-283` — the escalation sentence stays, now true;
- `synth/vio_dashboard.tcl` and `tools/jtag_repl.tcl` decoders carry the same wording;
- `docs/hw_debug.md:84,89` and `docs/an9134_50mhz_bringup.md:308,312` — same;
- `tools/jtag_bringup_tui.py:62,69,503` and `synth/vio_only_probe.tcl:61` keep working
  **unchanged** and are regression-checked, per VSOC-4's own requirement.

### 3.4 The ordering constraint the VIO spec's hand-off understates (SI19, SI20)

The VIO spec §6.2 states the CPU-side half is *"OUT OF SCOPE … it stays task #226"* and
describes the remaining v1 work as *"(a) two `output wire [31:0]` ports on
`m68k_axi_wrapper.v` after `:156`, assigned from the existing `:305`/`:307` wires; (b) a
submodule pointer bump. Zero new logic."* Two things in that are not right.

**First, it is not two ports (SI20).** `fpga_top_debug_ctrl.vh` contains exactly **one**
`u_cpu` port-connection list (`:139-205`, then an `` `ifdef ILA_ENABLE `` continuation at
`:206-263`); `` `ifdef CPU_M68K `` at `:139-143` selects only the *module name* between
`m68k_axi_wrapper` and `cpu_stub`. Therefore **every** port that VSOC-2 adds to that list must
exist on **both** modules. Socket group 8, as the VIO spec defines it, is `vio_cpu_snapshot`
(96b), `vio_heartbeat` (32b), `vio_build_id` (32b), `vio_boot_pc` (32b, out), `vio_ctl` (4b,
out) plus the two liveness ports. v1 must declare all of them — driving the two it can
(SI14/SI16) and tying the rest to constants, exactly as `cpu_stub.v` does.

**Second, the two halves are not independently orderable (SI19).** Landing VSOC-2 before v1's
wrapper declares the group makes the `CPU_M68K` build fail at elaboration on unknown ports —
i.e. it breaks the *deployed* configuration, not the stub one. Conversely, adding the ports to
v1 first is completely safe: an output port that nobody connects is legal Verilog and elaborates
with at most a warning. So the dependency is one-directional and the order is forced (§4.2).

This is a refinement of the VIO spec's hand-off, not a contradiction of its scope split — the
split itself (SoC half shared, CPU half per-core) is correct and is confirmed. What changes is
that the two halves must be **sequenced and the SoC half made atomic with the gitlink bump**,
which the hand-off does not say.

---

## 4. Cross-repository coordination (SI21)

### 4.1 Task #225 — single repo, no submodule involvement

Everything is in `macqd700-soc`: `rtl/soc/peripheral_bus.v` and `tb/tb_peripheral_bus.cpp`. No
`cpu` change, no gitlink bump, no ordering constraint against task #226. It can land first,
last, or concurrently.

One coupling worth stating: this fix **also** removes the last downstream obstacle that the
axi-socket-adapter spec's **D30** exact-cover write path would otherwise hit, so landing it
before this core starts driving the fabric is desirable — but nothing in D30 blocks on it, and
nothing here blocks on D30.

### 4.2 Task #226 — two repos, forced order, one atomic commit

| Step | Repo | Content | Why this position |
|---:|---|---|---|
| 1 | `cpu` (v1) | Add socket group 8 to `m68k_axi_wrapper.v`'s port list after `init_done_seen` (`:156`). Drive the committed-PC port from `dbg_boundary_next_pc` and the retire-count port from `dbg_committed`; tie the remaining group-8 members to constants. Remove `dbg_boundary_next_pc` from the unused sink at `:1227` (leave `dbg_last_pc` and `dbg_boundary_a_pc` in it). Commit. | Safe alone — unconnected outputs are legal, so v1 still elaborates in the current SoC, and the current SoC still builds against the new commit. This is what makes the sequence possible. |
| 2 | `macqd700-soc` | **One atomic commit** carrying: the `cpu` gitlink bump to step 1's SHA, VSOC-1 (`cpu_socket.vh` group 8 + `cpu_stub.v` parity) and VSOC-2 (`fpga_top_debug_ctrl.vh:46-47` tie-offs → real wires + the `u_cpu` connections). | Splitting these leaves an intermediate commit where the `CPU_M68K` build does not elaborate. Atomicity is the point; it is not a stylistic preference. |
| 3 | `macqd700-soc` | VSOC-3 (VIO IP config + cache-marker bump) and VSOC-4 (host tables) + the SI17 relabelling. | Owned by the VIO spec; may be the same commit as step 2 or a follower, but must precede any bitstream that is expected to show live probes. |

**Does step 1 require the SoC to bump immediately?** No — that is what makes the order work. But
the reverse is absolute: **step 2 cannot precede step 1.**

**Does task #226 need this core's VIO work to exist first?** No. Group 8's *definition* is the
VIO spec's (VSOC-1); its *v1-side implementation* is this spec's. If VSOC-1 lands before v1 is
ready, the `CPU_M68K` build is broken in the interim — which is the same constraint stated from
the other side, and the reason step 2 is atomic.

### 4.3 The submodule is not in a committable state (SI-OPEN-1)

Discovered while verifying, and blocking for step 1 as written:

- `macqd700-soc`'s **committed** gitlink is `0b7a56a` (`git ls-tree HEAD cpu`), while the
  checked-out submodule tree is at **`4eab9008`** — 7 commits ahead. There is an uncommitted,
  in-flight pointer bump sitting in the working tree.
- the submodule is on a **detached HEAD**, not on the branch `.gitmodules` names. That branch,
  `split/macqd700-soc`, is at `0bb03a26` and does **not** contain either `0b7a56a` or
  `4eab9008`. The declared tracking branch is stale to the point of being decorative.
- the submodule working tree has a **staged, uncommitted** change to `rtl/core/mem/dcache.v`
  (48 insertions) — somebody's in-flight SMC-snoop wiring.

None of that is task #226's to fix, but step 1 cannot be executed cleanly on top of it. The
implementing task must, before writing any RTL: establish which commit is the real v1 base,
create or identify a real branch to commit onto, and get the staged `dcache.v` work out of the
way (committed by its owner, or stashed) rather than sweeping it into a #226 commit. **This must
be confirmed with the user, not decided unilaterally** — the ambiguity is about someone else's
in-flight work.

---

## 5. Risk and regression obligations (SI22)

v1 boots System 7.0.1 to Finder on real hardware **today**. Neither fix may merge on simulation
evidence alone. The obligations below are per-fix and are gates, not suggestions.

### 5.1 Task #225

| # | Obligation | Rationale |
|---:|---|---|
| 1 | `make tb-peripheral-bus` — **red before, green after**, both logs captured. | SI10; this is the direct evidence the bug existed and is gone. |
| 2 | `make tb-all` — no new `FAIL` versus a baseline run on unmodified `15e4650`, honouring `TB_KNOWN_BROKEN`. | The change is in a module ~50 testbenches traverse. A baseline run is required because the suite has pre-existing failures (`Makefile:6591-6614`). |
| 3 | Per-device testbenches specifically: `tb-via1`, `tb-via2`, `tb-scc`, `tb-iwm`, `tb-asc`, `tb-q700-eth-sonic`, `tb-pb-scsi`, `tb-adb-inject`, `tb-turboscsi`. | These are the slots whose write path changes. Covered by `tb-all` but called out so a partial run cannot skip them. |
| 4 | **M1 — the MAME System 7.0.1 size-annotated capture (§2.7.4).** Per-slot counts of `op=w` with `size > 1`, with the `pc` of each hit. | This is the measurement that actually answers reachability. It is a MAME run, not an RTL run, and it is cheap. |
| 5 | **M2 —** generalize the ASC canary (`peripheral_bus.v:1586-1616`) to all slots, then `make tb-fpga-top-rom` and `make tb-fpga-top-rom-monitor-guard`: same milestone as baseline **and** per-slot multi-hot counts. | The only full-RTL, real-ROM, real-core vehicle; simultaneously a regression gate and a second reachability datum. Generalizing the canary first makes every other testbench self-report too, for free. |
| 6 | **On hardware: bitstream, boot System 7.0.1 to Finder.** Exercise ADB (keyboard + mouse), SCSI (mount and read the volume), serial, and sound — the device families behind the changed slots. | Non-negotiable if M1 or M2 reports any hit (§2.7.5). Required as a smoke test even at zero hits: neither measurement covers every driver path, and SONIC in particular has no capture at all. |
| 7 | If M1/M2 report hits on a **strided** slot: a targeted before/after comparison of that device's observable behaviour on hardware, plus tracing the captured `pc` values to the responsible driver code. | Fix B changes *which byte a stateful device receives* on a currently-working machine. Highest-risk path in the spec; it gets its own gate. |
| 8 | If M1/M2 report hits on **SCC**: answer SI-OPEN-2 before merge. | SI8's conservative choice is only defensible while the LONG-span case is theoretical. |
| 9 | No synthesis/timing gate. | The change is combinational byte-select plus an FSM in a 50 MHz peripheral domain, far off any critical path. Stated explicitly so its absence is a decision rather than an omission. |

### 5.2 Task #226

| # | Obligation | Rationale |
|---:|---|---|
| 1 | `cpu` repo: its own full test suite green on the step-1 commit. | Standard, and cheap — step 1 adds no logic. |
| 2 | **Both** SoC configurations elaborate: `CPU=stub` and `CPU=m68k`. | SI19's failure mode is exactly an elaboration break in the `CPU_M68K` configuration only, which a stub-only check would miss. |
| 3 | `make tb-all` on `macqd700-soc` after step 2 — no new `FAIL` versus baseline. | Port-list changes have broken testbench wrappers in this repo before (`Makefile:6601-6607` records two live instances of exactly that: `PINNOTFOUND`/`PINMISSING` against stale wrappers). |
| 4 | **Full synthesis + implementation must still close timing**, compared against the pre-change build. | ~230 new bits cross from `commit.v` to the top level and into a `DONT_TOUCH` VIO instance. v1 is a closed design; new top-level nets re-place and re-route it. This is the one real hardware risk of an otherwise-trivial change. |
| 5 | VIO IP regenerated with the bumped cache marker (VSOC-3), `.ltx` refreshed. | The marker's known count/width-only weakness means a rebind alone is a silent cache hit — the VIO spec's `NOTED-1` states this directly. |
| 6 | **On hardware: `probe_in5` and `probe_in9` read non-zero and both change between two reads while the machine boots.** Then boot System 7.0.1 to Finder. | The whole point of the fix is that these stop being constants. "It synthesised" is not evidence. |
| 7 | `tools/jtag_bringup_tui.py` and `synth/vio_only_probe.tcl` still work **unchanged**. | VSOC-4's own requirement; they name these probes explicitly. |
| 8 | The SI17 relabelling is present in every listed artefact. | A probe restored under a false label is a regression. |

### 5.3 What could still go wrong that these gates do not catch

Stated plainly rather than left implied:

- **Task #225, Fix A, on a device with write side effects the model does not have.** The
  testbench observes `{addr, byte}` pairs against behavioural models. If a real ENET/ORWELL/
  ADBINJ device reacts to *pulse count* in a way its model does not, serialization changes
  behaviour in a way only hardware shows. Mitigated by §5.1 step 5, not eliminated.
- **Task #225, Fix B, on SCC.** SI8 chooses the conservative treatment precisely because the
  right answer for a multi-register-span access is unknown (SI-OPEN-2). The conservative choice
  can still be wrong for the LONG case; it is merely not *more* wrong than today.
- **Task #226, timing.** §5.2 step 4 catches a closure failure but not a marginal one. If v1's
  post-route margin is thin, the honest outcome may be that the probes cost more than they are
  worth on this build — which is a legitimate result to report, not a failure to route around.

---

## 6. Explicitly out of scope

Restating **SI-OUT-1…5** in one place, because the most likely failure mode of this work is
scope drift into an adjacent, larger problem:

1. **This is not a v1 audit.** Two named bugs, already found. A third found during
   implementation is a new task.
2. **Not the SoC-side VIO wiring.** VSOC-1…VSOC-4 belong to
   `2026-08-18-vio-jtag-debug-design.md`. This spec depends on VSOC-1 and constrains its
   ordering (SI19); it does not redesign it.
3. **Not the `axi_i`/`axi_d` adapter.** D1-D30, SOC-1…SOC-3 belong to
   `2026-08-18-axi-socket-adapter-design.md`. Only SOC-4's *prescribed fix* is superseded, and
   only by SI2.
4. **Not v1's `OFF_PC` / halt-PC semantics** (`debug_ctrl.v:1584,2227`). Real, adjacent, and
   deliberately untouched (SI18).
5. **Not the peripheral-bus read path.** The one-`pb_rd`-pulse-and-broadcast behaviour recorded
   as `U4` in the axi-socket spec is a separate issue with a separate character.

Additionally out of scope, though encountered while verifying: the stale
`.gitmodules` branch pointer, the uncommitted gitlink bump, and the staged `dcache.v` work in
the submodule (§4.3). These **block** task #226's step 1 and must be resolved with the user —
but resolving them is not this spec's design content.

---

## 7. Where this spec corrects, sharpens or contradicts its sources

Recorded rather than silently absorbed, matching the practice of both sibling specs.

1. **Citation drift in the axi-socket spec's SOC-4** (minor, corrected). The `peripheral_bus.v`
   self-documenting gap comment is at `:586-590`, not `:587-592`; the ASC FSM is at `:776-849`,
   not `:780-840`. Its `:632-638`, `:993-1002`, `:568-577`, `:959`, `:967` citations are exact.
2. **SOC-4's prescribed fix is wrong** (major, superseded — SI2). *"Serialize … on every `pb_*`
   slot"* would emit repeated writes to the same stateful register on VIA1/VIA2/IWM/SCSI-regs,
   where `AWADDR[1:0]` is not in the address decode. SOC-4's *finding* is confirmed; its
   *prescription* is replaced by the two-family design in §2.3-§2.6.
3. **The defect on strided slots is mischaracterised as byte loss** (sharpening — SI5). It is
   byte *misdelivery*: `wr_strb_byte`'s lowest-hot-bit priority selects the highest byte
   address, i.e. the **last** byte of a big-endian store, so a `move.w` to a VIA register writes
   the second byte rather than the addressed one. That is a different, more concrete bug than
   "silently drops bytes", and it changes what the test must assert.
4. **"The boot path doesn't exercise that" is unsourced, appears three times, and has a losing
   track record** (§2.7.1-§2.7.2). Beyond `peripheral_bus.v:586-590`, the same unbacked claim
   appears in `docs/endianness_audit.md:166-190` and `tb/tb_peripheral_bus.cpp:1901-1906`. The
   ASC serializer exists *because* commit `1b7d875`'s measured version of that claim was
   falsified by `9468831` (the Q700 ROM's CD-XA filter table uses `MOVE.W`); the SCSI DMA shim's
   exists because `eba8aa2` found the same on the OS disk-write path. Hence SI11.
5. **The bug already had a third, independent, unconnected recording** (new — §2.7.1).
   `docs/endianness_audit.md:166-190`, finding **B4**, describes the same `wr_byte` defect and
   was filed as a "latent bug … track as follow-up". Neither SOC-4 nor this session's earlier
   passes noticed it. It is the same bug.
6. **The VIA1 golden-trace tool cannot observe the evidence it would be used to gather** (new —
   §2.7.1). `tools/mame_via1_capture.lua:133-142,154-160` `break`s after the first hot byte
   lane, justified by *"The 68040 always issues byte-wide MMIO accesses to VIA"* — the very
   assumption under test. Any word write to VIA1 is invisible in that trace by construction.
7. **Finding B4's own worked example is wrong** (minor, corrected). B4 states that for
   `MOVE.W #$1234` with `wstrb=4'b1100` the mux yields `wr_lane_data[15:8] = 0x12`. Both halves
   are wrong: with bits 3 and 2 hot, `wr_strb_byte`'s priority chain reaches bit 2 and selects
   `wr_lane_data[23:16]`, which for `wdata[31:16] = 0x1234` is `0x34` — the *second* byte. The
   corrected result is the one SI5 states. B4's file path (`rtl/sys/peripheral_bus.v`) is also
   stale; the file now lives at `rtl/soc/`.
8. **An existing registered test asserts the buggy behaviour** (new — §2.8).
   `test_granularity_per_device` locks VIA1's byte selection at `tb/tb_peripheral_bus.cpp:1901-1946`.
   Hand-tracing shows it *survives* Fix B (its stimulus takes the fallback arm), but that must
   be re-verified by running it, and its comment must be rewritten.
9. **The VIO spec's task-#226 hand-off understates the work and the ordering** (correction —
   SI19, SI20). It is not "two output ports": the single shared `u_cpu` connection list forces
   v1 to declare the entire group 8. And the halves are not independently orderable: VSOC-2
   before the v1 ports breaks elaboration of the deployed `CPU_M68K` configuration. The hand-off
   is otherwise correct, including the scope split itself and every §6.1 citation.
10. **The VIO spec's description of how the `cpu` submodule is pinned is imprecise** (correction
   — §4.3). It states `.gitmodules` *"pins `cpu` … branch `split/macqd700-soc`, at `4eab9008`"*.
   `.gitmodules` does not pin a commit; the gitlink does, and the **committed** gitlink at
   `15e4650` is `0b7a56a`. `4eab9008` is what is *checked out* locally, uncommitted, on a
   detached HEAD; the named branch is at `0bb03a26` and contains neither commit. This matters
   because step 1 of §4.2 needs a real branch to commit onto and there currently isn't one.

One thing that was **not** a contradiction, checked because it looked like one: the VIO spec's
§6.2 says v1's fix is *"zero new logic, `m68k_core.v` untouched"*. That survives verification —
`dbg_boundary_next_pc` and `dbg_committed` are both already at wrapper scope, and
`m68k_core.v:154,156` already carry the driver ports. Substituting `dbg_boundary_next_pc` for
`dbg_pc` (SI15) does not change that: it is also already there, merely sunk as unused at
`m68k_axi_wrapper.v:1227`.

---

## 8. Open questions

These are genuinely unresolved. None blocks writing the implementation plan; each blocks a
specific step within it.

| # | Question | Blocks | Notes |
|---:|---|---|---|
| **SI-OPEN-1** | What is the real v1 base commit and branch to commit task #226 onto, and what happens to the staged `dcache.v` change? | §4.2 step 1 | §4.3. Involves someone else's in-flight work — **must be settled with the user**, not decided unilaterally. |
| **SI-OPEN-2** | What does a real Q700 SCC see on a longword bus cycle spanning two of its registers? | Whether SCC ever graduates from SI8's conservative treatment — and blocks merge outright if M1/M2 report SCC hits | Answerable by MAME cross-validation (`tools/mame_axi_capture.lua`, `docs/axi_lockstep.md`). Until answered, SI8 stands. Note the SCC and SWIM *bridge* wrappers forward only the high byte (`tools/mame_q700_rtl_overlay.py:1058-1060`, `:1204-1206`), so the **native** trace's `size`/`mem_mask` fields — not the bridge — are the ones to read. |
| **SI-OPEN-3** | Does System 7 driver code (as opposed to the ROM boot path) issue multi-hot-strobe stores to these slots? | §5.1 steps 4-8 | **Now answerable and required (SI11/M1)** — this was previously thought to need an infeasible full-RTL System 7 simulation. It does not: MAME boots System 7 routinely here, and both size-annotated capture tools already exist. The only slot with any existing evidence is IWM/SWIM (§2.7.3), and it is weakly negative. |
| **SI-OPEN-4** | Is this core's `retire_count` µop-granular or instruction-granular? | The SI17 label wording only | Does not change SI16. `dbg_macros` (`commit.v:647`) is v1's instruction-granular counter if parity turns out to be wanted. |
| **SI-OPEN-5** | Does exporting ~230 bits of group 8 from v1 still close timing? | §5.2 step 4 | Not answerable by design; only by running implementation. A negative result is a legitimate outcome to report. |
