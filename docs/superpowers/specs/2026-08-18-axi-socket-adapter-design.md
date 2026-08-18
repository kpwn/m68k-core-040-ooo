# `axi_i` / `axi_d` socket adapter for the 040 OoO core — macqd700-soc integration design

**Status:** PROPOSED / DESIGN ONLY. All decisions in §0 are locked (prior verified
investigation + explicit user direction); none is implemented. Direct input to a future
`writing-plans` pass.

**Date:** 2026-08-18

**Scope:** making this repository's `axi_i` / `axi_d` AXI master interfaces conform to
`macqd700-soc`'s authoritative CPU-socket contract (`macqd700-soc/rtl/soc/cpu_socket.vh`),
so this core can replace the v1 core currently in that SoC. Also in scope: the interrupt
ack, reset/boot, and reset-instruction seams, because they are part of the same socket
surface and no other plan owns them.

**Primary source:** the verified scoping pass
`~/.claude/projects/-home-qwertyoruiop-m68k-core-040-ooo/memory/axi-socket-adapter-scoping-2026-08-18.md`
(gap table G1-G14, every claim carrying a `file:line` citation against HEAD `bb7774c`).
Citations below were independently re-confirmed while writing this spec; the four places
where this spec **corrects or extends** that source are called out explicitly in §12.

**Compatibility target:** `macqd700-soc/rtl/soc/cpu_socket.vh` §§1-3 and §§5-6, and the
behaviour of the fabric behind it (`axi_xbar.v`, `axi_wide_to_axilite.v`,
`peripheral_bus.v`, `l2c*.v`, `ddr_ctrl`). Compatibility means the SoC boots and runs
against this core with no CPU-specific signal crossing the socket, not that the v1
CPU-side glue (`if_to_axi.v`, `axi_narrow_to_wide.v`) is reproduced.

---

## 0. Decision summary

Every numbered **DECIDED** item below is citable by a future implementation plan without
re-deriving it. Items tagged **NOTED** record a confirmed non-problem or an accepted
residual; items tagged **SOC** must be executed in `macqd700-soc`, not here; items tagged
**OPEN** are genuinely undecided and require explicit sign-off before the implementation
plan may pass the section that depends on them — they are recorded rather than silently
resolved in either direction.

`D24`-`D29` and `OPEN-1` were added by the 2026-08-18 design review of this spec (see the
review-corrections block at the end of §12); `D4`/`D5`/`D6`/`D10`/`D15`/`D20`/`D22`'s
wording was tightened by the same pass.

`D30` and `SOC-4` were added by a later 2026-08-18 pass acting on explicit user direction
that the in-group-misalignment case must **not** be carried as an accepted limitation (see
the second corrections block at the end of §12). `D6`'s bound and `D26`'s content were
rewritten by that pass; `D26` is now a write-path statement only.

| # | Decision |
|---:|---|
| **D1** | Adapt byte order at the socket boundary with a pure wire permutation. Do **not** change the core's native byte-address-invariant lane convention in `DcacheTypes` / `IcacheTypes` / `TableWalker`. (§2) |
| **D2** | The permutation is a **per-32-bit-lane byte reversal** on WDATA/RDATA and a **per-4-bit-nibble reversal** on WSTRB. Inter-word order is untouched. It is an involution, so one function serves both directions and both widths. (§2.2) |
| **D3** | The permutation lives in a new top-level `M68kSocketTop`, applied exactly once per master, only to `w.data` / `w.strb` / `r.data`. Never to address, id, len, size, burst, resp, or last. (§2.3) |
| **D4** | The D-side derives a real `AxSIZE` and a byte-granular address for every **INHIBITED** access, from the access's own size (loads) or size+strobe (stores) — never from an address-range table. (§3) |
| **D5** | The store-side size/address derivation runs on the **core-side** (pre-permutation) strobe, because the byte-offset a run starts at is not permutation-invariant. (§3.3) |
| **D6** | An INHIBITED access is decomposed inside `DcachePlugin`'s INHIBITED path into an **exactly-covering sequence of naturally-aligned AXI sub-transactions** — never more than **three**, and exactly one for every naturally-aligned access. This is an extension beyond the v1 algorithm and is required for parity — see §3.4 for why the naive containment fallback is wrong. The decomposed range is clamped to the containing 16-byte line per D25, and the ≤3 bound is proved from that clamp in §3.4. (Originally scoped to the 4-byte-boundary crossing alone; widened to exact cover by D30.) |
| **D7** | 4 masters → 2. `axi_i` stays I-cache-only. D-cache + ITLB walker + DTLB walker merge onto `axi_d`. The ITLB walker genuinely issues AXI writes, so it categorically cannot ride the read-only `axi_i`. (§4.1) |
| **D8** | The merge is an **owner-tag serializing arbiter**, not a raw-ID demux. Responses route by the arbiter's latched grant owner; the returned AXI ID is forwarded as a fabric hint and is never consulted for routing. (§4.2) |
| **D9** | Read and write grants are **independent** per-direction state machines. A single global token deadlocks against `refillWriteHold`. (§4.4) |
| **D10** | The D-cache's existing internal B-by-ID demux is preserved untouched; for it the arbiter is strictly additive. The walkers have **no** existing ID check, so for them the arbiter is not additive but their *only* protection — see D27. (§4.3) |
| **D11** | `axi_i` stays natively 256-bit, `len=1`/`size=5` (two 32-byte beats = a 64-byte line). No 256→128 downconverter is built in this repo. (§5) |
| **SOC-1** | `macqd700-soc` must widen the `axi_i` socket-side path to 256 bit. The socket's single `CPU_SOCKET_AXI_DW` define must split into per-master `..._AXI_I_DW` (256) and `..._AXI_D_DW` (128). (§5, §11) |
| **D12** | Reset/boot is a **real vector-0 AXI fetch**: one 16-byte read at physical 0x0, SSP from bytes 0-3, PC from bytes 4-7. Not a debug-CSR-supplied PC. (§6) |
| **D13** | The vector-0 read is issued as a fourth read owner on the **`axi_d` merge arbiter**, never as a third socket master — only `XBAR_M_CPU`/`XBAR_M_CPUI` reads get the SoC's ROM overlay aliasing. (§6.2) |
| **D14** | The initial SSP reaches committed A7 through the **existing shared `a7Wr` int-PRF port** at `RenameStage.committedPhysA7`, as a new highest-priority third source. SSP write in cycle N, fetch redirect in cycle N+1. (§6.3) |
| **D15** | A non-OKAY vector-0 response latches the sticky `coreHalted` state, not a vector-2 frame. This is hardware-faithful (a fault during reset exception processing halts a real 68040) and a deliberate divergence from v1. The halt *reason* it reports needs the new channel of D28. (§6.4) |
| **D16** | The reset-vector fetch is behind a constructor parameter, default **off**, mirroring v1's `FETCH_RESET_VECTORS`. The existing sim/lock-step/OOC flows keep the external `redirect` port. (§6.5) |
| **D17** | `ipl_ack` is a 1-cycle pulse per interrupt exception entry actually taken, derived from the ExceptionUnit's own registered `obsIsInterrupt` observation, pinned by a count-equality assertion. (§7.2) |
| **D18** | `iackAvec` is tied to 1 (autovector) and `iackVector` is dropped: the socket has no vector input. (§7.3) |
| **D19** | The core does **not** reimplement a 20 s abandonment timer. The obligation is discharged by the SoC fabric's own bounded-response guarantee plus a stated, assertion-backed core-side invariant. (§8.2) |
| **D20** | The merge arbiter carries its own **bounded-grant** watchdog, closing a new wedge mode this design creates (three owners on one port). This is *not* a reinstatement of the v1 abandonment timer D19 declines. Bound = v1's value copied verbatim (2e9 core-clk), never re-derived. On expiry it latches `coreHalted` with a distinct halt-reason code (via D28's new channel); it never fabricates an AXI response. (§8.3) |
| **D21** | Keep the core's async active-high reset; rename the socket top's port `reset` → `rst`, declare the `ClockDomainConfig` explicitly instead of inheriting the default, and re-run the post-route gate. (§9.1) |
| **D22** | `cpu_peripheral_reset` (68040 `RESET` instruction output) is owned by **this** work, not the debug-ctrl plan. Driven from the commit-time `SysKind.RESET` arm, as a **518-core-clock level** matching v1 verbatim. (§9.4) |
| **SOC-2** | `macqd700-soc` must add `cpu_peripheral_reset` to `cpu_socket.vh` §6, which omits it today. (§9.4, §11) |
| **SOC-3** | `macqd700-soc` must instantiate this core in place of v1, dropping `if_to_axi.v` and `axi_narrow_to_wide.v` from the CPU wrapper. That is the step which removes the 20 s timer — see D19. (§11.2) |
| **D23** | The socket top exports **only** socket ports. The 37 probe/test top-level IOs stay on `M68kFullCoreSynth`, whose port surface and behaviour are unchanged. See also D29 for the AXI sidebands. (§9.3) |
| **NOTED-1** | G9 (AXI in flight during reset): no work needed, the SoC already compensates. (§9.2) |
| **NOTED-2** | G12 (bursts onto lite-only slaves): already error-terminated by the fabric, not corrupted. Not a task. (§9.5) |
| **NOTED-3** | G14 (single-outstanding fabric): the I-cache's five refill IDs and any future multi-MSHR work deliver zero end-to-end benefit until the xbar is reworked. Expectation-setting only. (§9.6) |

**Added by the 2026-08-18 design review of this spec.** Listed separately so the original
D1-D23 numbering and its existing cross-references stay stable.

| # | Decision |
|---:|---|
| **D24** | The **load**-side sizing/address derivation is a function of `(paddr[1:0], size)` alone. `DLoadCmd` carries no strobe and AXI reads have no byte enables, so WSTRB is the authoritative lane selector on **writes only**. (§3.3) |
| **D25** | D6's sub-transaction decomposition **clamps** the derived byte range to the containing 16-byte line: `end = min(off + n, 16)`. The split FSM issues both slots at the *full* original size, so the range it presents is *not* line-contained on arrival and must be clamped rather than trusted. (§3.4) |
| **D26** | **Write path.** WSTRB is a *complete* lane selector: any write this core emits carries, per sub-transaction, exactly the bytes the architectural access names, and no covering-but-over-wide write is ever emitted (D30 removes the last one). Whether the right bytes actually land is therefore a property of the **slave**, not of the core. Investigated against the real fabric: `axi_wide_to_axilite.v` and the `dbg`/`dafb` AXI-Lite faces forward WSTRB verbatim and are correct; the 8-bit `pb_*` peripheral slots **silently drop bytes** on any write with more than one strobe bit hot. That is a real downstream bug, fixed by SOC-4, not a core-side limitation. (§3.3.1, §3.5) |
| **D27** | Both table walkers gain a fail-closed ID guard on their response channels (`b.payload.id === WALK_WRITE`, `r.payload.id === WALK_READ`), matching the D-cache's existing discipline, so the arbiter's safety argument is uniform across all three merged masters. (§4.3) |
| **D28** | A **new** kind-coded halt-reason channel is built from `RobPlugin`'s halt seam outward. D15 and D20 need to report *why* the core halted; today `rob.logic.coreHaltedIn` is a plain undiscriminated `Bool` and the kind codes are `DcachePlugin`-private. This is new scope, and a **second** debug-ctrl-plan touch point beyond `cpu_peripheral_reset`. (§6.4, §8.3, §10) |
| **D29** | The socket-facing `Axi4Config` in `M68kSocketTop` sets `useProt`/`useCache`/`useLock`/`useQos`/`useRegion` **false**, so the sideband signals the socket does not declare do not exist on the socket boundary at all. (§9.3) |
| **OPEN-1** | Whether `cpu_peripheral_reset`'s 518-cycle hold should also **gate dispatch/retire** the way v1's does. v1 gates both; this core's `RESET` is a pure NOP today. Needs explicit sign-off; the implementation plan may not silently pick either answer. (§9.4) |

**Added by the 2026-08-18 user-direction pass.** Numbering continues from D29 / SOC-3 so
every existing cross-reference stays valid.

| # | Decision |
|---:|---|
| **D30** | D6's sequencer emits an **exact naturally-aligned cover** of the (clamped) byte range, not merely a split at the 4-byte group boundary. Every sub-transaction's address is naturally aligned for its own `AxSIZE` **and** its byte extent lies wholly inside the architectural access. The bound is **≤ 3 sub-transactions** for every reachable access, proved in §3.4 from `Size ∈ {BYTE,WORD,LONG}` and the D25 clamp — a *bounded* sequencer, not the "general N-way byte sequencer" an earlier draft claimed a fix would require. A naturally-aligned access still emits exactly one transaction, unchanged. This is the real fix for in-group misalignment (canonically a WORD load at group offset 1) on the **load** path, where AXI4's total lack of read byte-enables makes a downstream-only fix impossible in principle; the **cover** is applied uniformly to stores as well, per D6's existing "state the rule once for both paths" — but the *range* it covers is derived from the strobe run, not from `size`, on a `useStrb` store slot (§3.3.1). (§3.3, §3.4) |
| **SOC-4** | `macqd700-soc`'s `peripheral_bus.v` must serialize a write whose active-lane WSTRB has more than one bit hot into one `pb_wr` pulse per hot bit, for **every** `pb_*` slot — generalizing the ASC-only `wr_asc_*` FSM that already does exactly this. Today the other slots take the single-byte fast path and silently drop every byte but one; the file's own comment records the gap. This is a shared-fabric fix and therefore **also corrects the v1 core's** observed behaviour for the same accesses (v1 routinely emits 2-hot-strobe WORD stores). Deliberately in scope. (§3.3.1, §3.5, §11.2) |

---

## 1. Current state, and what "the socket" actually is

### 1.1 What this core presents today

Verified against the regenerated netlist `generated/M68kFullCoreSynth.v` (2026-08-17) and
the RTL at HEAD:

| Master | Verilog name | Kind | Width | Shape | IDs |
|---|---|---|---:|---|---|
| I-cache | `IcachePlugin_logic_axi` | `Axi4ReadOnly` | 256 | `len=1`, `size=5` (64 B line, 2 beats) | 0-4 (`AxiIds.iRefill`) |
| D-cache | `DcachePlugin_logic_axi` | `Axi4` | 128 | `len=0`, `size=4` (16 B) | AR 0; AW 1/2/4 |
| ITLB walker | `itlbAxi` | `Axi4` | 128 | `len=0`, `size=4` | AR 2, AW 3 |
| DTLB walker | `dtlbAxi` | `Axi4` | 128 | `len=0`, `size=4` | AR 2, AW 3 |

Sources: `IcachePlugin.scala:39,47,2173-2181`; `DcachePlugin.scala:53,95,1198,1239-1242,
1911-1916`; `ItlbPlugin.scala:65,70,257-260`; `DtlbPlugin.scala:69,74,325-328`;
`TableWalker.scala:37,45,108-111`; `AxiIds.scala:35-69`.

There is no arbiter anywhere. `AxiIds.ID_W = 4` already equals `CPU_SOCKET_AXI_IW`;
address width already matches (32 = 32); every burst this core emits is already INCR.

### 1.2 What the socket demands

`cpu_socket.vh:98-142` fixes the two master port groups; `:145-162` the debug slave (out
of scope, see §10); `:164-168` the interrupt seam; `:170-176` the SoC-fabric control
group. `CPU_SOCKET_AXI_DW` defaults to 128 for **both** masters, with "256 reserved"
— that reservation is documentation only: `if_to_axi.v:142-145` and
`axi_narrow_to_wide.v:309-310,329` hardcode 128.

A point the scoping pass did not spell out, and that shapes §5 and §11:
**`axi_narrow_to_wide.v` is instantiated inside the CPU wrapper**
(`m68k_axi_wrapper.v:1108-1130`), not in the SoC fabric. It is v1's private glue. So the
socket's `axi_d` is that module's *wide* side — full 128-bit AXI with byte-granular
addresses and derived `AxSIZE` — and everything that module does (widening, sub-word
sizing, the 20 s watchdog) is a CPU-side obligation this core inherits, not something the
SoC will do on its behalf.

### 1.3 One prior claim retired

`D_PUSH=2` vs `WALK_READ=2` is **not** an ID collision: `D_PUSH` is an AWID and
`WALK_READ` is an ARID (`AxiIds.scala:44,67`), and AXI4 read and write channels have
independent ID spaces. The real collision is that ITLB and DTLB both emit AR=2/AW=3
(`AxiIds.scala:67,69`), which `AxiIds.scala:5-9` already flags in-repo as "a hard bug the
instant those masters are folded". §4.3 explains why D8 makes that a non-issue rather
than something to renumber around.

---

## 2. CRITICAL gap G4 — 32-bit-lane byte-order mismatch

This is a **silent corruption** gap affecting instruction fetch, all data, and page-table
walks. Nothing crashes; every value is simply wrong.

### 2.1 The two conventions, stated precisely

**This core's convention** — byte-address-invariant. For a beat carrying the bytes at
physical addresses `base+0 .. base+N-1`, the byte at offset `o` is `data[8*o +: 8]`. Every
site in the core says so in those words:

- `DcacheTypes.scala:169-171`: "The cache stores the line exactly as the AXI beat
  delivered it (byte o = line[o*8 +: 8]); big-endian semantics live only in how a
  multi-byte LOAD assembles those bytes into the 32-bit result."
- `IcacheTypes.scala:85`: "little-endian window: data[7:0] = byte at pc+0 …
  data[63:56] = byte at pc+7".
- `TableWalker.scala:83-85`: "bytes(i) = the line's byte i (line-base + i)", with
  `selectWord` reassembling the descriptor big-endian from those bytes; and
  `TableWalker.scala:204-208`, which places the U/M byte write at `descAddr + 3`
  precisely because the descriptor's numeric LSB physically lives at the highest byte
  address.

**The SoC's convention** — 32-bit words in ascending address order, big-endian *within*
each 32-bit word. The word at byte offset `4W` occupies `data[32W +: 32]`, and the byte at
offset `4W+j` (j in 0..3) occupies `data[32W + 24 - 8j +: 8]`. Three independent
confirmations:

- `if_to_axi.v:113-145`: "byte at file offset K lands at `mem_b[(K/4)*4 + (3 - K%4)]`,
  i.e. each 32-bit word preserves its internal BE byte order, but the four 4-byte words
  are in ascending-address order across `m_rdata[31:0]..[127:96]`."
- `m68k_mem_lane.vh:14-30,60-80`: `m68k_mem_strb`/`m68k_mem_wdata`/`m68k_mem_rdata` all
  place a byte at offset 0 in `[31:24]` with strobe bit 3.
- `axi_xbar.v:133-141`, describing the S3 VRAM shim, states the transform in exactly the
  terms this design needs: "S3 byte-lane ordering is little-endian within each 32-bit
  word, while the CPU/system bus presents normal 68k big-endian byte lanes. The xbar
  therefore **byte-swaps each 32-bit word and reverses each 4-bit WSTRB nibble** on the S3
  boundary."

`if_to_axi.v:135-141` also records the historical failure mode when this is missing:
"every opword the CPU saw was garbage, so `dbg_committed` counted a random walk through
non-trapping opwords while PC wedged in the low-vector region."

### 2.2 The transform (D2)

Given the two definitions, core byte `o = 4W + j` sits at core bit `32W + 8j`, and the
socket wants it at `32W + 24 - 8j`. The inter-word index `W` is identical on both sides.
Therefore:

```
socketWord(W)  = byteReverse32( coreWord(W) )      for every W
socketStrb[4W + (3-j)] = coreStrb[4W + j]          for every W, j
```

That is: **reverse the four bytes inside each 32-bit lane; reverse each 4-bit strobe
nibble; leave lane order alone.** It is *not* a full 128-bit byte reverse and *not* a
word-order reversal.

Three properties that make this cheap and safe, and that the implementation should rely on
explicitly:

1. **It is an involution.** Applying it twice is the identity, so a single Scala function
   serves core→socket and socket→core. There is no "forward" and "inverse" pair to keep in
   sync.
2. **It is width-parametric.** 128-bit `axi_d` has 4 lanes, 256-bit `axi_i` has 8. The
   function is `for (w <- 0 until width/32) yield byteReverse32(...)`.
3. **It has zero logic depth.** It is a renaming of wires. It cannot appear on a timing
   path and cannot affect the post-route FMax result.

### 2.3 Where it lives (D1, D3)

**D1 rationale.** The alternative — changing the core to natively speak the SoC's lane
convention — would touch `DcacheByteLane.extract`/`merge`, `IcacheTypes.FetchRsp`'s window
convention and every predecode consumer of it, `TableWalker.selectWord` and its U/M
write-address arithmetic, plus every simulation model and lock-step harness that currently
agrees with the existing convention. All of that for a property of one SoC. The boundary
permutation is isolated, self-inverse, zero-depth, and reviewable in one screen.

**D3 placement.** A new top `M68kSocketTop` (new file, `src/main/scala/m68k040/top/
SocketTop.scala`) instantiates `M68kCore` with the same plugin list as
`GenFullCoreSynthVerilog` (`FullCoreSynth.scala:469-514`) plus the socket-only plugins from
§4, §6 and §7, and presents the socket ports with `setName()` verbatim — matching the
naming discipline the debug-ctrl spec already established for `dbg_axi`
(`2026-08-09-debug-ctrl-jtag-repl-design.md` §15.1).

Exactly three signals per master are permuted, and only these:

| Master | Permuted | Untouched |
|---|---|---|
| `axi_i` (256 b) | `r.data` | everything else |
| `axi_d` (128 b) | `w.data`, `w.strb`, `r.data` | `aw.*`/`ar.*` (addr, id, len, size, burst), `b.*`, `r.id`/`r.resp`/`r.last` |

"Untouched" above means *not permuted*; it does not mean *forwarded*. The `prot`/`cache`/
`lock`/`qos`/`region` sidebands the core's `Axi4Config` defaults leave on are not part of the
socket contract and do not cross this boundary at all — see **D29**/§9.3, which is a decision
about the same connection site.

Applying it to an address would be a bug; applying it twice would be a no-op that looks
like a fix. The implementation must carry a comment at the single call site saying so, and
a formal/directed test that a byte written at address A through `axi_d` is the byte the
socket-side model reads at address A.

### 2.4 Consequences that come out right for free

- **Table-walk U/M writes.** `ItlbPlugin.scala:249-267` / `DtlbPlugin.scala:317-335` build
  a single-byte strobe from `drainByteOff` in the core's convention. The nibble reversal
  maps it onto the SoC's strobe position automatically. No walker change.
- **Instruction fetch.** The predecode/align path consumes `FetchRsp.data` in the core's
  convention (`IcacheTypes.scala:85`); the permutation on `axi_i.r.data` restores that
  from the socket's convention with nothing downstream aware.
- **Peripheral reads.** `peripheral_bus.v:68-75` "broadcast[s] the peripheral's 8-bit
  rdata into all 4 byte lanes of the selected 32-bit word", so the byte lands correctly
  regardless of which `j` within the lane the core extracts from. The permutation is still
  required for the *address*→lane relationship to hold at all.

---

## 3. CRITICAL gap G5 — MMIO access sizing

### 3.1 The gap

The D-side always emits `size=4` (16 bytes) and relies on WSTRB alone for lane selection,
including on the INHIBITED (MMIO) path:

- INHIBITED load: `DcachePlugin.scala:1194-1243` — REFILL issues AR at the **16-byte line
  base** with `size=4`, `len=0`. An INHIBITED load always reaches REFILL, because
  `ldS1Cacheable` forces every hit bit low by construction (`DcachePlugin.scala:351-353`).
- INHIBITED store: `DcachePlugin.scala:1839-1841` latches `stAddrReg` as the **line-aligned**
  paddr; `:1911-1916` emits `size=4` with the merged 16-bit strobe.

Two independent failure modes against this SoC:

1. **DDR SLVERRs a byte-addressed `size=2` write** — the sizing rule
   `axi_narrow_to_wide.v:33-51` exists precisely because `ddr_ctrl`'s
   `align_error(size, addr)` correctly rejects a size/address mismatch.
2. **Byte-addressed I/O registers are selected by address, not strobe.**
   `peripheral_bus.v:68-72`: "the xbar's 128-bit data bus carries the 32-bit word in one
   of four 32-bit lanes selected by `addr[3:2]`. For 8-bit Mac peripherals we further pick
   one byte out of the selected 32-bit word using **`addr[1:0]`** on big-endian byte lane."
   `axi_wide_to_axilite.v:94,163` confirms the lane comes from `awaddr[3:2]`/`araddr[3:2]`
   and forwards the full address onward. So a line-aligned address silently hits the wrong
   register, and a 16-byte MMIO read touches four registers at once — triggering
   read-to-clear side effects on three unintended ones. SCC, VIA, IWM, SCSI and ADB all
   live in that space.

### 3.2 What is already available (no new plumbing needed)

Both D-cache command bundles already carry everything the derivation needs:

- `DLoadCmd` (`DcacheTypes.scala:55-61`): full byte-granular `paddr`, `size`, `cacheMode`.
  **No strobe** — see D24/§3.3.2.
- `DStoreCmd` (`DcacheTypes.scala:83-95`): full byte-granular `paddr`, `size`, `useStrb`,
  a 16-bit line-relative `strb`, `cacheMode`.

The information is present at both AXI emission sites and is simply discarded when the
transaction is formed. G5 is therefore a change local to `DcachePlugin`'s two INHIBITED
emission paths, not a datapath change.

### 3.3 The derivation (D4, D5, D24, D26, D30)

The two directions derive the same thing from different inputs, because the two command
bundles carry different information. Both derivations are stated for **one 4-byte group**;
§3.4 (D6/D25/D30) is what guarantees a transaction never spans more than one group and is
always naturally aligned inside it.

#### 3.3.1 Stores — from the core-side strobe

The store's byte range comes from the **core-side** (pre-permutation, byte-offset-indexed)
inputs, in one of two forms:

- ordinary form (`useStrb = false`): the range is `[paddr[3:0], paddr[3:0] + sizeBytes(size))`,
  identical to the load derivation;
- split-slot form (`useStrb = true`, `DcacheTypes.scala:78-89`): the range is the run of set
  bits in the 16-bit line-relative `strb` — `start` is the index of the lowest set bit, `end`
  the index of the highest set bit plus one.

  **`size` is not an input on this path, and the implementation must not fall back to it.**
  A split slot's `size` field does not describe its byte count: `StoreQueue.scala:264` drives
  slot B's `size` as a flat `Size.LONG()` regardless of the slot's true 1-3-byte extent, and
  slot B's `paddr` is the next **line base** (`paddrBs`, from `s1AddrB = (s1Va & ~15) + 16`,
  `LsEuPlugin.scala:448`), so `paddr[3:0] = 0`. The `off`/`n` pair of §3.4's D25 bullet would
  therefore read `[0,4)` for a slot whose real extent is `[0,1)`, `[0,2)` or `[0,3)` — and
  D30 would then cover that over-wide range *exactly*, and exactly wrongly, naming up to three
  peripheral registers the architectural access never touched. The strobe is the **only**
  field on `DStoreCmd` that still carries the true extent; the SQ's own byte counts
  (`nbytesAs`/`nbytesBs`, `StoreQueue.scala:110,149`) are forwarding-private and are never
  placed on the drain payload.

  The run is contiguous by construction — a split slot is a contiguous sub-range of one
  architectural access. Concretely, for an access of `n` bytes at line offset `off`,
  `storeStrbA` (`DcacheTypes.scala:312-320`) sets exactly bytes `off … min(off+n,16)−1` (the
  `pos < 16` guard is what bounds it) and `storeStrbB` (`:335-343`) sets exactly bytes
  `0 … off+n−17`. A hypothetically sparse strobe stays *safe* rather than correct: the
  sequencer would cover the run's convex hull, and each sub-transaction still carries the
  exact strobe bits, so no byte is written that was not strobed.

That range then goes through the **same** D6/D30 sequencer the load path uses (§3.4). Each
emitted sub-transaction is naturally aligned, lies wholly inside the range, and carries the
strobe bits for its own bytes:

| Sub-transaction byte extent | `AxSIZE` | Address presented | WSTRB |
|---|---:|---|---|
| 1 byte | 0 | that byte's full address, unmodified | the one bit |
| 2 bytes, even start | 1 | that pair's address (bit 0 already 0) | the two bits |
| 4 bytes, group-aligned start | 2 | the group base (bits[1:0] already 0) | all four |

There is no "anything else" row: D30's cover is exact, so an over-wide covering transaction
is never emitted. WSTRB remains the authoritative lane selector on the **write** path (D24),
but it is now never the *only* thing keeping a write correct — the address and size are
correct on their own too, which is what makes the emission right on a byte-addressed
peripheral as well as on memory.

**One correction to v1's rule, and why this is more than a legality fix.** v1 lists `0110`
alongside `0011`/`1100` as a two-contiguous-bit `awsize=1` case (`axi_narrow_to_wide.v:43`)
and then clears address bit 0 to satisfy alignment. That pair is inconsistent: with
`awsize=1` at an even address the transaction's active byte lanes are the group's bytes 0-1,
while strobe `0110` asserts byte 2 — a WSTRB bit outside the addressed transfer, which AXI4
forbids. An earlier draft of this spec merely re-routed `0110` to a well-formed `size=2` at
the group base, which is legal and correct on memory but still names the wrong registers on a
byte-addressed device. Under D30 the core does not emit that shape at all: the `[1,3)` range
decomposes into two `size=0` writes at offsets 1 and 2. The v1 illegality is fixed *and* the
behavioural divergence from real 68040 bus cycles is closed.

**What the real fabric does with a multi-bit WSTRB, and why SOC-4 exists.** This matters even
under D30, because an exact cover still emits `size=1` (2 hot strobe bits) and `size=2` (4 hot
bits) sub-transactions for naturally-aligned WORD and LONG accesses, and those are the common
case. Verified in `macqd700-soc` at `15e4650`:

- `axi_wide_to_axilite.v` (the DMA-config window behind xbar S2) selects the 32-bit lane by
  `awaddr[3:2]` and forwards the lane's strobe nibble verbatim to its AXI-Lite master
  (`:100-104`, `:149`). Correct.
- `peripheral_bus.v` likewise forwards `wr_lane_strb` verbatim to the two AXI-Lite faces it
  owns — `dbg_wstrb` (`:959`) and `dafb_wstrb` (`:967`). Correct.
- `peripheral_bus.v`'s 8-bit `pb_*` slots are **not** correct. `pb_wr_active` (`:993-1002`)
  pulses `pb_wr` for exactly **one** cycle per AXI write beat, and the byte it presents is
  chosen by a priority encoder over the strobe nibble — `wr_strb_byte` / `wr_byte`
  (`:632-638`). Only three paths escape that: the ASC multi-byte strobe-walk FSM
  (`:780-840`), the SONIC 16-bit word serializer (`:757-780`), and the SCSI DMA-shim
  serializer (`:855-900`). For VIA1/VIA2/SCC/IWM/ENET/ORWELL/ADBINJ and SCSI register space,
  a write with two strobe bits hot writes **one** byte and silently drops the other. The file
  says so itself (`:587-592`): *"multi-byte writes to e.g. SCC would fall through the priority
  encoder and silently drop bytes, but the boot path doesn't exercise that"*.

So the write case is **not** already handled correctly downstream. It is a real bug in shared
RTL that both cores traverse — v1 emits 2-hot-strobe WORD stores as its normal encoding
(`peripheral_bus.v:568-577` documents `WORD off=2 → strb=4'b0011`), so v1 has been exposed to
it all along. Per the user's direction that the hardware behind the bus is a legitimate fix
target and that a shared fix should be embraced rather than avoided, this is recorded as
**SOC-4** and explicitly scoped to fix v1's behaviour at the same time. The fix is small and
already prototyped in-tree: generalize the existing `wr_asc_*` strobe-walk FSM from the ASC
slot to every `pb_*` slot.

#### 3.3.2 Loads — from `(paddr[1:0], size)` (D24, D30)

`DLoadCmd` (`DcacheTypes.scala:55-61`) has **no strobe field** — it carries `vaddr`, `paddr`,
`size`, `cacheMode`, `token` and nothing else — and, more fundamentally, **AXI4 reads have no
byte enables at all**: `ARSIZE` plus `ARADDR` *is* the entire lane selector on a read. So the
load derivation cannot be strobe-phrased, and the write table's "WSTRB remains authoritative"
escape hatch does not exist here. (v1 *appears* to derive its `arsize` from a strobe, but the
strobe is manufactured inside `dcache.v` by the `m68k_mem_strb`/`arsize_from_wstrb` machinery
purely from the access's own address and size — `axi_narrow_to_wide.v:52-58` — so it is the
same information reaching the same decision by a longer route, not an extra input we lack.)

**This asymmetry is the whole reason the load fix has to be CPU-side.** A covering write
carries its own correction: `size=2` + `WSTRB=0110` still says, on the wire, "bytes 1 and 2
only", so a conforming slave can get it right. A covering *read* carries nothing: `ARSIZE=2`
at the group base is indistinguishable from a genuine longword read, and no slave, however
well written, can recover which two bytes the CPU actually wanted. A downstream-only fix for
the load case is therefore impossible in principle, not merely unimplemented — which is
exactly why D30 puts the fix in the core.

Take a load sub-transaction whose byte range within the group is `[gs, ge)` (group-relative,
`0 ≤ gs < ge ≤ 4`, after the D6/D25/D30 decomposition), with `n = ge − gs`. Because D30's
cover is **exact**, only naturally-aligned extents reach this table at all:

| Group-relative sub-range | `AxSIZE` | Address presented | Bytes actually returned |
|---|---:|---|---|
| `n = 1`, any `gs` | 0 (1 B) | that byte's full address, unmodified | exactly the wanted byte |
| `n = 2`, `gs` even (`[0,2)` or `[2,4)`) | 1 (2 B) | the sub-range's base (bit 0 already 0) | exactly the wanted 2 |
| `n = 4`, `gs = 0` | 2 (4 B) | the group base (bits[1:0] already 0) | exactly the wanted 4 |

The three non-naturally-aligned in-group shapes — `n=2` at `gs=1`, `n=3` at `gs=0`, `n=3` at
`gs=1` — no longer appear as emitted transactions. §3.4 shows they are the *complete* set of
such shapes and that each splits into exactly two rows of this table.

Equivalently, and this is the form to implement — stated over the **sub-range**, since after
decomposition a sub-transaction's `n` is *not* the parent access's `size`:

```
AxSIZE = 0                       when n == 1
AxSIZE = 1                       when n == 2        // base(0) == 0 by construction
AxSIZE = 2                       when n == 4        // base(1 downto 0) == 0 by construction
ARADDR = base, already naturally aligned for AxSIZE
```

where `base` is the sub-range's own start address. `n ∈ {1,2,4}` and the alignment of `base`
are both guaranteed by the §3.4 emitter, so this stage does no address masking at all — an
`ARADDR` whose low `AxSIZE` bits are non-zero is now an assertable *bug*, not a case to
absorb. For a naturally-aligned access that needs no decomposition this collapses to the
`size` field unchanged, which is the common case and costs nothing.

Worked, in the core's own terms (all offsets line-relative, 16-byte line, LONG = 4 bytes):

- `paddr(3:0) = 0x6`, `size = WORD`. Range `[6,8)`, one group (group 1), group-relative
  `[2,4)`, `n = 2`, `gs = 2` even → one sub-transaction, `AxSIZE = 1` at `0x6`.
  Reads exactly bytes 6-7. On an 8-bit peripheral at `0x6` this touches one register.
- `paddr(3:0) = 0x5`, `size = WORD`. Range `[5,7)`, one group (group 1), group-relative
  `[1,3)` — the D26/in-group-misaligned shape. Under D30 it decomposes into **two**
  sub-transactions: `AxSIZE = 0` at `0x5` and `AxSIZE = 0` at `0x6`. Each returns exactly one
  byte; the sequencer merges them into `missLine` at offsets 5 and 6 and the existing
  `missPaddr[3:0]` extraction (`DcachePlugin.scala:498-503`) yields the WORD unchanged.
  **Exactly two peripheral registers are touched, each exactly once** — the two the
  architectural access names. This is what a real 68040 does with the same access, and it is
  the case an earlier draft carried as an accepted limitation.
- `paddr(3:0) = 0x2`, `size = LONG`. Range `[2,6)` — crosses the group boundary at 4, so it
  decomposes into `[2,4)` (`n=2`, `gs=2` → `AxSIZE=1` at `0x2`) and `[4,6)` (`n=2`, `gs=0` →
  `AxSIZE=1` at `0x4`). Two well-formed sub-transactions, no over-read. Unchanged from D6.
- `paddr(3:0) = 0x1`, `size = LONG`. Range `[1,5)` → group split at 4 gives `[1,4)` and
  `[4,5)`; `[1,4)` is the `n=3`, `gs=1` shape and covers exactly as `AxSIZE=0` at `0x1` plus
  `AxSIZE=1` at `0x2`. **Three** sub-transactions in total — `0x1` (1 B), `0x2` (2 B),
  `0x4` (1 B) — summing to exactly the 4 bytes wanted. This is the worst case; §3.4 proves
  nothing reaches four.
- `paddr(3:0) = 0xD`, `size = LONG`. Range `[13,17)` — see §3.4; the clamp bounds it to
  `[13,16)`, group-relative `[1,4)`, which covers as `AxSIZE=0` at `0xD` plus `AxSIZE=1` at
  `0xE`. Two sub-transactions, 3 bytes, no over-read. (Slot B still supplies the 16th byte
  under its own translation, and remains over-wide — §3.5.)

**D5 — why core-side.** This applies to the store derivation, the only one with a strobe as
an input. Reversal within a nibble preserves popcount and preserves contiguity, but it does
*not* preserve the offset at which a run starts. Deriving from the post-permutation strobe
would produce the mirror-image address. The derivation must therefore run inside
`DcachePlugin`, on the core's own byte-offset-indexed strobe, and the §2 permutation must be
applied strictly afterwards, at the socket boundary. These two transforms are
order-dependent and the implementation must say so at both sites. The load derivation is
immune to the ordering hazard for a different reason — its inputs (`paddr`, `size`) are
never permuted at all (D3) — but it lives in the same place for the same structural reason:
it needs the D6 sub-range, which only `DcachePlugin` has.

**Standing-rule conformance.** Nothing in this derivation consults an address range. The
only input that says "this is a device, not memory" is `cacheMode === INHIBITED`, which
comes from the MMU's page/TTR attributes on the access itself
(`DcachePlugin.scala:351-353,663,701`). This satisfies the project's standing rule that the
core may only reason from MMU-configured attributes and contemporaneous bus responses,
never from a cached or assumed SoC decode map.

### 3.4 Misalignment, and why v1's rule alone is not enough (D6, D25, D30)

**This is an extension beyond the brief, made because the naive rule is provably wrong
here.** v1's algorithm is complete *for v1* because v1's LSU never presents an access that
spans a 32-bit word boundary — `m68k_mem_lane.vh`'s `m68k_mem_needs_split` splits those
into two narrow accesses before `axi_narrow_to_wide` ever sees them. This core has no such
pre-split: `LsEuPlugin.scala:440-444` splits only on `s1CrossLine` (16 B) and `s1CrossPage`
(4 KiB). A misaligned longword at offset 1 within a line is a single `DLoadCmd`/`DStoreCmd`
spanning two longwords.

Under v1's popcount rule alone that access falls to its catch-all → `size=2` at the
*lower* longword, which reads/writes bytes 0-3 when the access needs bytes 1-4. On DRAM
the strobe would save the write but the read would be short; on a byte-addressed
peripheral it is simply the wrong register. Real 68040 hardware handles this case
correctly by running multiple sized bus cycles, so a fallback that gets it wrong is a
genuine regression against both v1 and real silicon, not an exotic corner.

**Where the fix cannot go.** Not in `LsEuPlugin`'s existing split predicate:
`s1CrossLine`/`s1CrossPage` are computed at S1 from `s1Va` (`LsEuPlugin.scala:439-444`),
*before* translation resolves, so `cacheMode` is not yet known there. Making the predicate
cacheMode-independent would split every misaligned cacheable access onto the rare two-pass
replay FSM (`LsEuPlugin.scala:1492-1513`) and cost real IPC on the hot path.

**D6 — where it does go.** Inside `DcachePlugin`'s INHIBITED emission, as a small
**bounded sequencer** over a byte range the sequencer computes for itself:

- **The input range must be clamped, not trusted (D25).** With `off = paddr(3 downto 0)` and
  `n = sizeBytes(size)` — the derivation for loads and for `useStrb = false` stores; a
  `useStrb = true` store slot derives its range from the strobe run instead (§3.3.1, and the
  stores bullet below) — the sequencer's range is

  ```
  start = off
  end   = min(off + n, 16)          // D25 — the clamp
  ```

  Because `end ≤ 16` by construction, `[start, end)` lies inside one 16-byte line and
  therefore meets **at most two** 4-byte groups. **That containment holds because of the
  clamp, not because the incoming range was already line-contained** — it is not.

- **Why it is not already line-contained (the load path).** `LsEuPlugin` issues *both* slots
  of a cross-boundary split at the **full original size**: `dcache.loadCmd.payload.size :=
  Mux(useSplitCmd, llReg.size, alignedCmd.size)` (`LsEuPlugin.scala:759`) has no slot-B arm,
  while only the *address* is switched (`llReg.bDone ? llReg.addrB : llReg.vaddr`,
  `:751-755`). Slot A therefore arrives at the D-cache as `(paddr = the original misaligned
  address, size = the original size)` with `off + n > 16`, and slot B as `(paddr = the next
  line base, size = the original size)`. Deriving group indices from an unclamped `off + n`
  would produce a group index of 4 — i.e. **a sub-transaction at line offset 16, outside the
  line**. Worked: `off = 13`, `size = LONG` → `[13,17)` → groups 3 **and 4**; group 4's base
  is `paddr - 13 + 16`, the next line. When the containing line is the **last line of its
  page**, that next line is the first line of the **next physical page** — an address the core
  never translated. And that case is not a corner of a corner: `s1CrossPage` *implies* this
  one. `lineOff = pageOff mod 16`, so `pageOff + n > 4096` forces `lineOff + n > 16`
  (`LsEuPlugin.scala:441-443`) — **every** cross-page access is one of these escaping
  derivations, and every one of them escapes into an untranslated page. This spec would have
  *introduced* a wrong-address bus transaction — the precise class of bug §3 exists to prevent
  — since today's REFILL path only ever emits the containing line base
  (`DcachePlugin.scala:1194-1243`). With the clamp, `[13,17)` becomes `[13,16)` — group 3,
  `L=3`, `gs=1`, i.e. `1@0xD` + `2@0xE` — and slot B independently covers the remainder at
  the next line under its own, separately translated `paddrB`.

- **The emission rule is an exact naturally-aligned cover (D30).** Splitting only at the
  group boundary is not enough: a group-confined range can still be non-representable as one
  transaction, and on the read path there is no strobe to compensate (§3.3.2). The rule is
  therefore the obvious greedy one, applied to `[start, end)`:

  ```
  p = start
  while (p != end) {
    sz = the largest of {4,2,1} with (p % sz == 0) && (p + sz <= end)
    emit (address = p, AxSIZE = log2(sz))
    p += sz
  }
  ```

  `p` is line-relative, and the line base is 16-byte aligned, so `p`'s low bits *are* the
  emitted address's low bits — the alignment test needs no separate address arithmetic.
  Every emitted sub-transaction is naturally aligned for its own size and lies wholly inside
  the access. Nothing outside the architectural byte range is ever read or written.

- **Why it is bounded at three, and why that is a proof rather than an observation.**
  `Size` has exactly three members — `BYTE`, `WORD`, `LONG` (`Isa.scala:32`) — so
  `n ∈ {1,2,4}` and, after the clamp, `end − start ≤ 4`. Take one 4-byte group and a
  group-confined range of length `L` starting at group offset `gs`:

  | `L` | `gs` | Cover |
  |---:|---:|---|
  | 1 | any | `1@gs` — 1 piece |
  | 2 | 0, 2 | `2@gs` — 1 piece |
  | 2 | 1 | `1@1`, `1@2` — 2 pieces |
  | 3 | 0 | `2@0`, `1@2` — 2 pieces |
  | 3 | 1 | `1@1`, `2@2` — 2 pieces |
  | 4 | 0 | `4@0` — 1 piece |

  (Rows absent from the table cannot occur — `L=2` needs `gs ≤ 2`, `L=3` needs `gs ≤ 1`,
  `L=4` needs `gs = 0`, or the range would leave the group.) So a group needs **at most 2**
  pieces, and the three two-piece rows are the *complete* set of in-group ranges that no
  single naturally-aligned AXI transfer covers exactly — the set §3.3.2's table is written
  to exclude. Both
  groups needing 2 pieces is impossible: that would require `L_A = 3` ending at the boundary
  and `L_B = 3` starting at it, a 6-byte range, while `end − start ≤ 4`. Therefore **at most
  3 sub-transactions, always**, with no loop bound to trust and no data-dependent iteration
  count. A naturally-aligned access is one of the single-piece rows and still emits exactly
  one transaction.

- **This is what makes the earlier "general N-way sequencer" objection wrong.** An earlier
  draft declined to fix in-group misalignment on the grounds that doing so needed an
  unbounded byte sequencer. It does not: the reachable case set is the six rows above, the
  cover is greedy and terminates in ≤ 3 steps by construction, and the mechanism is the same
  one D6 already builds for the group-boundary split — a start pointer, a size choice, and a
  merge offset. The incremental cost over the two-sub-beat version is one extra iteration of
  an FSM that already exists.

- **Stores take the same sequencer, but not the same `[start, end)` derivation.** The
  `off = paddr[3:0]` / `n = sizeBytes(size)` pair above is the derivation for **loads and for
  `useStrb = false` stores only**. A `useStrb = true` store slot derives `[start, end)` from
  its strobe run instead, per §3.3.1 — that is normative, and `size` is unusable there
  (`StoreQueue.scala:264` forces slot B's `size` to `Size.LONG` whatever its true 1-3-byte
  extent). What the two forms share is the *cover* (the D30 greedy loop) and the emission
  tables, not the range computation feeding them. Both forms are line-contained, each for its
  own reason, so the D25 clamp never actually bites on a store:

  - `useStrb = false` — `useStrbA := twoAccess` (`LsEuPlugin.scala:797`) and
    `twoAccess = s1CrossLine || s1CrossPage` (`:442-444`), so a non-strobe store is by
    definition one that does not cross the 16-byte line: `off + n ≤ 16` already, and
    `min(off+n,16) = off+n`. The clamp is a genuine no-op.
  - `useStrb = true` — the strobe is 16 bits and **line-relative**, so any run inside it is
    trivially inside the line. More than that, `storeStrbA`'s `pos < 16` guard
    (`DcacheTypes.scala:312-320`) means the clamp has *already been applied at the producer*:
    slot A's run is literally `[off, min(off+n,16))`, the clamped range itself. Slot B's run
    is `[0, off+n−16)` (`:335-343`) — the exact spilled remainder, which is precisely the
    quantity `size` no longer carries.

  This is why the store path has no analogue of §3.5's surviving slot-B load residual: the
  load side loses the remainder (`DLoadCmd` has no strobe, and `LsEuPlugin.scala:759` re-sends
  the full original `size` on slot B), while the store side preserves it in `strbB`. Under
  D30 a store therefore never emits a covering-but-over-wide transaction — the addresses and
  sizes are correct on their own, with WSTRB agreeing rather than compensating.

- **Loads:** issue each sub-transaction in turn (1 to 3 of them) and merge each response into
  `missLine` at its own byte offset before the existing `REPLAY` path runs.
  `loadRspPort.payload.data`'s extraction at `missPaddr[3:0]`
  (`DcachePlugin.scala:498-503`) is then unchanged and correct. A non-OKAY response on **any**
  sub-transaction raises the existing `busFaultResp`.
- **Stores:** issue AW/W/B for each sub-transaction in turn, and assert `storeAck` only after
  the **last** `B` handshake. `storeErrReg` is the OR of all responses. The existing
  fail-closed `=== AxiIds.D_STORE` B demux (`DcachePlugin.scala:1957`) is preserved for
  each.
- **Ordering.** Sub-transactions are issued in ascending address order and strictly
  serialized (one outstanding at a time), which is both what the fabric supports
  (NOTED-3/G14) and what makes the emission match a real 68040's bus-cycle order for a
  misaligned access — relevant on devices with order-sensitive register pairs.
- Cost: zero on the cacheable path (the sequencer is reachable only when
  `missCmode === INHIBITED` / `stS3Inhibited`), zero on any naturally-aligned INHIBITED
  access, and at most two extra bus round trips on an already-slow, already-serialized MMIO
  path in the misaligned case.

### 3.5 Residuals of the D4/D6/D30 design — what is left, and what no longer is

A residual here means: the emitted transaction is legal AXI and correct against memory, but
touches more of a byte-addressed peripheral's address space than the architectural access
asked for. All are INHIBITED-path only.

**Exactly one residual survives D30.**

1. **Slot-B over-read on a cross-line/cross-page load.** Slot B arrives as `(paddr = next
   line base, size = the *original* size n)` — `LsEuPlugin.scala:759` does not narrow it —
   so its clamped range is `[0, n)` while only `[0, off + n − 16)` is architecturally needed.
   Worked: `off = 13`, `size = LONG` → slot B needs byte 0 alone but is derived as `[0,4)` →
   a single `AxSIZE = 2` at the next line's base. Up to `n − 1` bytes are read beyond the
   access. D30 does not help, and this is the reason it is the one survivor: the defect is in
   the *range handed to* the sequencer, not in how the sequencer covers it. An exact cover of
   a wrong range is still wrong. The over-read is contained *within* slot B's own translated
   page (offsets 0-3 of its first line), so it is never a wrong-address transaction — only a
   wider one. A future narrowing could use the slot-B marker the load token already carries
   (`(False ## llReg.bDone ## robId)`, `LsEuPlugin.scala:762-765`) to derive the true
   remainder; that is **not decided here**, because it makes `DcachePlugin` depend on a
   `DLoadToken` bit that is presently an LS-EU-private encoding.

**Closed by D30, recorded so the history is legible.** Three cases an earlier draft carried
here are no longer residuals, because the sequencer's cover is now exact:

- *3-byte store range* (the SQ's explicit-strobe split-slot form, `DcacheTypes.scala:78-89`).
  Was `size=2` + a 3-bit strobe at the group base; now covers exactly, as
  `2@gs + 1@gs+2` or `1@gs + 2@gs+1` (§3.4's table).
- *3-byte load range* (arises from the D25 clamp itself, e.g. `off = 13`, `size = LONG` →
  `[13,16)`). Was a 4-byte read at the group base; now `1@0xD + 2@0xE`. This one mattered
  most, because loads have no strobe to compensate with (D24).
- *In-group misalignment* — canonically a WORD at group offset 1 (`[1,3)`). Was a `size=2`
  read/write at the group base; now two `size=0` transactions at offsets 1 and 2 (§3.3.2's
  worked example). **This is the case the user directed must not be carried as an accepted
  limitation, and D30 is that decision.** Parity-with-v1 was the wrong frame for it: v1's own
  handling (`axi_narrow_to_wide.v:43`, `awsize=1` at the group base with a strobe bit outside
  the addressed transfer) is both wrong *and* malformed, so "parity" meant reproducing a
  defect rather than matching a contract.

**One thing that is not a core residual but is a real defect on the path.** `peripheral_bus.v`
drops all but one byte of any write whose active-lane WSTRB has more than one bit hot, on
every `pb_*` slot except ASC/SONIC-word/SCSI-DMA (`:632-638`, `:993-1002`, `:587-592`; full
derivation in §3.3.1). D30 does not remove the exposure, because a naturally-aligned WORD or
LONG store legitimately emits 2 or 4 strobe bits. It is a downstream bug in shared RTL, it
affects v1 identically and already does today, and it is fixed by **SOC-4**.

The implementation must carry a **simulation assertion that fires (as a warning, not a
failure) on the one surviving residual** — a slot-B sub-transaction whose derived range is
wider than the remainder the parent access actually needs — so that any real occurrence is
loud in the logs rather than silent. The other three cases no longer need a
"this-is-expected-wrong-behaviour" carve-out; for them the assertion is inverted into a hard
one: §13 now *forbids* an INHIBITED sub-transaction whose byte extent leaves the
architectural access, and forbids an `AxADDR` that is not naturally aligned for its own
`AxSIZE`.

### 3.6 What does *not* change

Cacheable refills, evictions, CPUSH writebacks and table-walk descriptor accesses keep
`size=4` at a 16-byte-aligned address. That is legal, is what the DDR path wants
(`axi_narrow_to_wide.v:66-74`: "a burst is by construction a cache-line fill / writeback of
naturally-aligned 4-byte beats"), and touches no device registers by construction — a
cacheable line is by definition not device space.

---

## 4. Master merge, G1 and G3

### 4.1 Topology (D7)

```
  IcachePlugin.axi (256b, RO)  ──[ §2 permute ]──────────────────────►  axi_i

  DcachePlugin.axi (128b) ──┐
  itlbAxi          (128b) ──┤── AxiDMergePlugin ──[ §2 permute ]─────►  axi_d
  dtlbAxi          (128b) ──┤     (owner-tag,
  ResetVectorPlugin (RO)  ──┘      serializing)
```

`axi_i` needs no arbiter: the I-cache is its only user and is already read-only. The ITLB
walker cannot join it — `ItlbPlugin.scala:255-270` genuinely issues AXI writes (the U-bit
descriptor writeback), and `cpu_socket.vh:99` declares `axi_i` AR/R only. That is the
forcing constraint that puts *both* walkers on `axi_d`.

`ResetVectorPlugin` is the fourth read owner; see §6.2 for why it must ride `axi_d` rather
than being a master of its own.

### 4.2 What "owner tag" means concretely (D8)

`AxiDMergePlugin` holds two independent, single-outstanding grant machines:

**Read side**
- `arOwner : Reg(OwnerId)` where `OwnerId ∈ {DCACHE, ITLB, DTLB, RESETVEC}`, and
  `arBusy : Reg(Bool)`.
- While `!arBusy`, a round-robin grant among the requesting owners' `ar.valid`. On
  `ar.fire`: latch the winner into `arOwner`, set `arBusy`.
- AR payload is forwarded **verbatim** — addr, id, len, size, burst — so the fabric's L2
  ID logic (`l2c_ctrl.v:140-142,151`) and any future ID-aware behaviour see exactly what
  the plugin intended.
- `r.valid` fans out to the latched owner only; `axi_d.r.ready` is the mux of the owners'
  own `r.ready` selected by `arOwner`. `arBusy` clears on `r.fire && r.last`.

**Write side**
- `awOwner`, `awBusy`, same shape, over `{DCACHE, ITLB, DTLB}`.
- AW and W are granted as a **pair** and held for the whole transaction. Today every
  D-side write is `len=0`, but the rule is stated as an invariant so a future burst
  writeback cannot interleave two owners' W beats.
- `b.valid` fans out to the latched owner only; `axi_d.b.ready` is the mux of the owners'
  `b.ready`. `awBusy` clears on `b.fire`.

**Why serializing rather than a full ID demux.** The fabric is already provably
single-outstanding per master port — `axi_narrow_to_wide.v:72-75` ("One outstanding
transaction per direction… Both the core LSU and boot_fsm self-serialise, so no queue
here"), and the xbar's own `rs_state[mi]==RS_IDLE` gating and per-slave `sw_owned` AW→B
lock. A full ID demux would require the deliberately-unbuilt V2a.2/V2a.3 infrastructure
(`AxiIds.scala:21-30`) — routing D-side R/B beats by ID with pool-level `r.ready`, and
tagging `DLoadRsp`/`busFaultResp`/`inhibitedResp`/`storeAck` — for zero end-to-end gain on
this fabric, while adding regression surface to the D-side load path. See §9.6.

### 4.3 Why G3's ID collision evaporates (D10, D27)

ITLB and DTLB both emit AR=2 / AW=3 (`AxiIds.scala:67,69`). Under D8 that is inert, for two
independent reasons:

1. **Routing never reads the ID.** Responses go to `arOwner`/`awOwner`, which the arbiter
   latched at grant time. Two owners with identical IDs are indistinguishable *to the
   fabric* but perfectly distinguishable *to the arbiter*.
2. **They can never be simultaneously outstanding anyway.** The arbiter is
   single-outstanding per direction, so at most one walker transaction exists on `axi_d` at
   a time. There is no ambiguity for the ID to resolve.

**D10 — the D-cache's own ID logic stays.** The D-cache demultiplexes its *own three* write
issuers by ID (`D_STORE=1` store write-through at `DcachePlugin.scala:1957`, `D_PUSH=2`
eviction writeback at `:1210`, `D_EVICT=4` maintenance writeback at `:1676`), deliberately
fail-closed
(`DcachePlugin.scala:1948-1956`: "an unrecognized id simply not ack anything — a hung drain,
which is loud and debuggable, instead of a silent spurious ack"). The arbiter delivers `B`
to the D-cache only when the D-cache is the write owner, so those compares keep working
unchanged and keep their fail-closed property. **For the D-cache, the arbiter is strictly
additive: it removes no existing check.**

**D27 — for the walkers it is not additive, and that changes the safety argument.** The
walkers have **no ID check at all** today. Both TLB plugins accept every `B` beat
unconditionally — `walkerAxi.b.ready := True` (`ItlbPlugin.scala:75`, `DtlbPlugin.scala:80`)
with the U/M drain ack taken straight off that handshake (`ItlbPlugin.scala:271`,
`DtlbPlugin.scala:340`) — and `TableWalker` likewise asserts `io.axi.r.ready := True`
whenever it is waiting for a descriptor (`TableWalker.scala:114`). Today that is safe only
because each walker is a *physically separate master* and the only responses reaching it are
its own. After the merge it is the arbiter's owner latch, and nothing else, that stands
between a walker and a response belonging to the D-cache or the reset-vector reader. So for
two of the three merged masters the arbiter is not a redundant second line of defence — it is
the **only** one.

That is an acceptable position (the owner latch is exactly the right mechanism, and §4.4's
assertions pin it), but the asymmetry is not acceptable to leave unstated, because a future
reader would otherwise take "each plugin keeps its own demux" at face value. **D27** therefore
adds the missing guard so the argument is uniform:

- `ItlbPlugin` / `DtlbPlugin`: `walkerAxi.b.ready := (walkerAxi.b.payload.id === AxiIds.WALK_WRITE)`
  in place of the unconditional `True`, with the drain ack unchanged (it is already gated on
  the handshake, so a rejected beat simply does not ack).
- `TableWalker`: qualify the descriptor-wait `io.axi.r.ready := True` with
  `io.axi.r.payload.id === AxiIds.WALK_READ`.

Cost is one 4-bit compare per site and no state. The property gained is the D-cache's own
stated one, verbatim: an unrecognised ID hangs loudly instead of silently acking. Note this
does **not** disambiguate ITLB from DTLB — they share AR=2/AW=3 (`AxiIds.scala:67,69`) and
D8's owner latch is what separates them; D27 only fail-closes the *class* boundary between
walker traffic and everything else.

The one thing the implementation **must not** do is renumber `WALK_READ`/`WALK_WRITE` to
"fix" the collision. Under D8 that would be churn with no correctness content, and it would
invalidate `AxiIds.scala`'s own status as the single source of truth by making it look as
though routing depended on the values.

### 4.4 Why the grants must be independent (D9)

`DcachePlugin.scala:1258` sets `axi.r.ready := !refillWriteHold`: a refill deliberately
holds off accepting its R beat until a colliding same-set store drain's S1/S2 window
closes. That store drain needs the **write** channel. Under a single global grant token,
the D-cache would hold the read grant while waiting for a write it cannot get, and the
write grant would be held by whoever won it — a deadlock that does not exist today because
the masters are physically separate.

With independent per-direction grants there is no cycle. The full liveness argument:

- A walker's write (U/M descriptor drain) depends on nothing else in the core; the fabric
  answers it and the write grant releases.
- A walker's read (descriptor fetch) likewise depends on nothing in the core.
- A D-cache refill's R acceptance may wait on a store drain, which waits on the write
  grant, which a walker may hold — but that walker write completes independently, so the
  wait is bounded.
- Nothing on the write side ever waits on the read side.

The dependency graph is acyclic, so every grant is released in bounded time provided the
fabric answers. §8 covers the case where it does not.

**Required assertions:** at most one read owner and one write owner granted at any time; an
`R` beat never presented to a non-owner; `arBusy`/`awBusy` never clear without the
corresponding `r.last`/`b` fire; a granted owner's `ar.valid`/`aw.valid` never changes
payload while granted.

---

## 5. `axi_i` width, G2

**D11 — the core stays natively 256-bit end-to-end.** No 256→128 downconverter is built in
this repository. This is a locked user decision (2026-08-18), and it governs over the
investigation's own lower-risk lean toward native 128-bit narrowing (which would have
bought a 32→8 predecode-instance cut). That trade-off is recorded here so a future reader
does not mistake the decision for an oversight.

The compatibility burden is therefore entirely on the `macqd700-soc` side. So that the
companion change has an exact target, the contract this core will present at `axi_i` is:

| Field | Value |
|---|---|
| Data width | 256 bit (`IcachePlugin.scala:39`) |
| `arlen` | 1 → two beats (`IcachePlugin.scala:2176`) |
| `arsize` | 5 → 32 bytes/beat; 64-byte line total (`IcachePlugin.scala:2177`) |
| `arburst` | INCR (`Axi4.burst.INCR`) |
| `araddr` | 64-byte aligned line base |
| `arid` | 0-4; 0 = demand, 1-4 = the stream-prefetch window (`AxiIds.scala:49-63`) |
| Outstanding | up to 5 by ID; see §9.6 for why the fabric collapses this to 1 today |
| Channels | AR/R only. No AW/W/B ever. |
| Byte order | per §2.2, after the socket-top permutation |
| Backpressure | `arHoldValid` already holds AR stable across `arready` deassertion (`IcachePlugin.scala:2151-2181`) |

**SOC-1** is the companion change: widen `axi_i`'s socket-side path to 256 bit. Two
sub-items, both out of scope here beyond stating them:

1. `cpu_socket.vh:71-88` declares a **single** `CPU_SOCKET_AXI_DW` used by both masters. It
   must split into `CPU_SOCKET_AXI_I_DW` (256) and `CPU_SOCKET_AXI_D_DW` (128), and
   `cpu_stub.v` must follow, or the standalone SoC build breaks.
2. The fabric path behind `axi_i` — the xbar's `XBAR_M_CPUI` port and whatever sits between
   it and DDR/L2 — must carry 256-bit beats or narrow them SoC-side.

Until SOC-1 lands, this core cannot be dropped into the SoC even with everything else in
this spec implemented. §11 states that dependency explicitly.

---

## 6. Reset and boot, G7

### 6.1 Current state

`FetchAlignPlugin.scala:186-189` initialises `decodePc`/`fetchPc` to plain 0 and holds
`started = False`; `:585` gates `ic.cmd.valid` on `started`, and `started` is set only by a
redirect (`:1198,1219,1237,1283`). So the core natively fetches **nothing** until something
external redirects it. Today that something is the top-level
`FetchAlignPlugin_logic_redirect_*` port, driven by the test harness. No `resetPc` /
`resetVector` concept exists anywhere in `src/main`.

That existing quiescent-until-redirect property is the hook this design uses; it means no
change to `FetchAlignPlugin` is required at all.

### 6.2 Mechanism (D12, D13)

A new `ResetVectorPlugin(enable: Boolean = false)` with a three-state machine on the core
clock:

```
REQ   (entered out of reset when enable)
   -- drive AR on the merge arbiter's RESETVEC read owner:
        addr = 0x0000_0000, len = 0, size = 4 (16 B), burst = INCR,
        id = a new AxiIds.RESET_VEC
   -- on ar.fire --> WAIT

WAIT
   -- r.ready = True
   -- on r.fire && rresp == OKAY:
        ssp = DcacheByteLane.extract(line, 0, Size.LONG)
        pc  = DcacheByteLane.extract(line, 4, Size.LONG)
        --> APPLY
   -- on r.fire && rresp != OKAY --> latch coreHalted (see §6.4), --> DONE

APPLY  (cycle N)     : pulse the SSP write into committed A7
       (cycle N+1)   : pulse FetchAlignPlugin.redirect with pc  --> DONE

DONE   : idle forever; never re-arms without a core reset
```

Both vectors live in the same 16-byte line, so one transaction suffices — the same
observation `if_stage.v:7-19` makes for v1.

`AxiIds.RESET_VEC` is a new named constant in `AxiIds.scala`, per that file's own rule
("never write a numeric AXI ID literal anywhere else in `src/main`",
`AxiIds.scala:32`). Its value is architecturally irrelevant under D8 — routing is by owner
tag — but it must not alias a live D-side ARID, so it goes outside the D-refill reserved
range 0-3 (`AxiIds.scala:38-40`) and the walkers' AR=2: **5**.

Reading the two longwords through `DcacheByteLane.extract` rather than hand-slicing is
deliberate: it makes the reset-vector reader share the core's single definition of
big-endian assembly, so the two can never drift.

**D13 — why `axi_d`, and why not a third master.** `axi_xbar.v:1178-1192`'s
`apply_cpu_overlay` aliases low addresses into the ROM mirror *only* for reads whose master
index is `XBAR_M_CPU` or `XBAR_M_CPUI`. A vector-0 read from any other master would read
raw, uninitialised low DRAM. A separate reset-vector socket master would therefore not
work, and would also violate the socket's two-master contract. Riding the `axi_d` merge
arbiter puts the read on `XBAR_M_CPU`, where the overlay applies.

### 6.3 Getting the SSP into committed A7 (D14)

`FullCoreSynth.scala:400-409` already carries a shared int-PRF write port whose address is
`RenameStage.committedPhysA7` and whose valid is `exc.a7WriteValid || exc.sysRegWriteValid`.
D14 adds `resetVec.sspWriteValid` as a **third, highest-priority** source on that same port:

```
a7Wr.valid   := resetVec.sspWriteValid || exc.a7WriteValid || exc.sysRegWriteValid
a7Wr.address := resetVec.sspWriteValid ? committedPhysA7 : <existing mux>
a7Wr.data    := resetVec.sspWriteValid ? resetVec.sspData : <existing mux>
```

This is safe by construction and by the same argument the existing direct writes rely on
(`FullCoreSynth.scala:403-405`; the "task #176 safe fix pattern" of writing
`committedPhysA7` directly, bypassing rename and the freelist): at the moment the reset
vector lands, no instruction has been fetched, so nothing is renamed, no ROB entry exists,
and the ExceptionUnit is idle. `committedPhysA7` still equals 15. An assertion must pin
that `resetVec.sspWriteValid` and either existing source are never simultaneously valid.

**It reaches ISP for free.** `SystemState.scala:36` initialises `srSys` to `0x27`
(S=1, M=0, I=7, T=0), so A7 *is* the ISP at reset, and `FullCoreSynth.scala:411-414` already
feeds the live PRF read of `committedPhysA7` back into the ExceptionUnit as
`committedA7In`, which drives `ss.writeA7` routed by committed S/M. The initial SSP
therefore lands in `ss.isp` on the following cycle with no extra wiring.

**D14 ordering.** SSP write in cycle N, redirect pulse in cycle N+1. Same-cycle would
almost certainly be fine — the redirect only restarts *fetch*, many cycles before any uop
could read A7 at issue — but "almost certainly fine" is not a property worth having in the
boot path, and the cost is one cycle once per power-on.

### 6.4 Bus error on the vector fetch (D15, D28)

v1 presents `pd_fault` at `pc=0` so commit raises bus-error vector 2
(`if_stage.v:16-18`). **D15 diverges deliberately:** a non-OKAY vector-0 response latches the
sticky `coreHalted` state and the core stops.

**D15 rationale**, in order of weight:

1. **It is what real hardware does.** A bus fault taken during reset exception processing is
   a double bus fault on a real 68040; the part halts. Vector 2 is v1's divergence, not
   ours.
2. **There is nothing to build a frame on.** SSP is exactly the value that just failed to
   arrive and VBR is 0 but the vector table itself is unreadable. A vector-2 entry would
   write an exception frame through a garbage stack pointer and then immediately fault
   again.
3. **It matches this project's established policy** for un-actionable bus errors on paths
   with no architectural recipient (`DcachePlugin.scala:1680-1683`: "Imprecise DIAGNOSTIC
   only, per the design's locked decision that a writeback error is a diagnostic crash and
   not an architectural trap").

**D28 — the halt-*reason* channel D15 needs does not exist and must be built.** "Halts" is
only half a diagnostic; an operator staring at a wedged core has to know *why*. The seam that
would carry that is today a plain, undiscriminated `Bool`: `RobPlugin.scala:373-376` declares
`coreHaltedIn` and latches `coreHalted` from it, and `FullCoreSynth.scala:364` drives it as
`dc.diagFault || exc.fsXlateFault`. The kind codes are `DcachePlugin`-**private** —
`diagFaultPulseKind` and the sticky `diagFaultKind` register (`DcachePlugin.scala:272-288`,
`:1215`, `:1304`, `:1682`) never leave that plugin, and `FullCoreSynth.scala:356-363` says so
in as many words: *"nothing downstream distinguishes WHICH producer fired, so a plain OR is
exactly right"*. That was true with two producers that both meant "diagnostic crash". It stops
being true here: D15 and D20 add producers whose whole point is to be told apart from each
other and from the D-cache's.

So D28 is **new scope**, not a reuse of something existing:

- Widen the halt seam from `Bool` to a `{valid, reason}` pair (or an equivalent one-hot),
  owned by `RobPlugin` alongside `coreHaltedIn`, with a sticky first-wins `reason` register
  so the *first* halt cause survives any later one. `coreHalted`'s existing behaviour
  (`headReady` forced False, `interruptPending` blocked, frontend quiesced) is unchanged;
  this adds an observation, not a control path, so it cannot perturb the halt semantics any
  existing test depends on.
- Allocate reason codes for: the two existing producers (D-cache diagnostic fault, FSAVE/
  FRESTORE translation fault), the D15 reset-vector bus error, and the D20 arbiter
  bounded-grant expiry. The D-cache's private `diagFaultKind` stays private; it is a
  *sub*-code under the D-cache reason, not a peer.
- **Second debug-ctrl touch point.** Wherever this reason becomes CSR-observable it is a
  `dbg_axi` register, and §10 already establishes that the debug-ctrl spec owns that port and
  register map. This spec does **not** design the debug-ctrl side; it flags the seam so the
  debug plan's port/register-map ownership list accounts for it, exactly as §9.4 does for
  `cpu_peripheral_reset`. Until that lands, the reason is observable in simulation
  (`simPublic`, matching `coreHaltedIn`'s existing treatment at `RobPlugin.scala:374`) and
  that is sufficient for every §13 obligation.

### 6.5 Parameterisation (D16)

`enable` defaults **false**. With it false the plugin elaborates to nothing and the external
`redirect` port keeps its current meaning, so every existing lock-step spec, directed test,
and the `M68kFullCoreSynth` OOC/FMax target are untouched. `M68kSocketTop` sets it true.
This mirrors v1's `FETCH_RESET_VECTORS` parameter and the reason v1 has one
(`if_stage.v:20-23`: "the directed-asm test harness … pre-arranges memory for a specific PC
and doesn't care about SSP").

---

## 7. Interrupt acknowledge, G6

### 7.1 The gap, and why it is load-bearing

The core has `iplInPort` / `iackAvecIn` / `iackVectorIn` (`FullCoreSynth.scala:316-321`) but
no ack output. The socket wants `cpu_ipl[2:0]` plus `ipl_ack`, and no vector
(`cpu_socket.vh:164-168`).

Without `ipl_ack` the SoC's `irq_agg` NMI rising-edge latch never clears. `m68k_core.v:119-126`
names the consequence: "Without this hook the external agg's `nmi_pending` latch sticks once
any rising edge fires and IPL=7 is asserted forever → CPU loops on vec-31." This is a small
amount of logic guarding a total-failure mode; it is not optional polish.

### 7.2 Derivation (D17)

The **contract** is: `ipl_ack` pulses high for exactly one core-clock cycle each time an
interrupt exception entry is actually taken — never on a merely-pending or
subsequently-abandoned recognition.

The core's internals that express this:

- `RobPlugin.scala:1447` drives `interruptPending`, self-gated on `excIdle`, so it naturally
  collapses to a single cycle per accepted entry (entry clears `excIdle`).
- `RobPlugin.scala:1319` passes `entryIsInterrupt = interruptPending` into the
  exception-entry trigger, which selects the SR I-mask update and the format-$0 frame.
- `RobPlugin.scala:1622` registers `exc.obsIsInterrupt` — an already-registered observation
  meaning "the entry that just completed was an interrupt entry".

**D17 specifies the contract and the source, and requires the plan to pin the qualifier.**
Derive from `exc.obsIsInterrupt` qualified by its own entry-fire, because that signal
already means *taken*, not *pending*, and is already registered (so it adds no logic depth
to the commit path, per the architecture document's registered-control rule). The
implementation plan must confirm the exact fire qualifier against the RTL of the day and
lock it with an assertion: **over any simulation run, the count of `ipl_ack` pulses equals
the count of interrupt exception entries retired.** Deriving straight from
`RegNext(interruptPending)` is a plausible simpler form, but it depends on the single-cycle
property being *structural* rather than incidental, and that is exactly the kind of thing
that quietly changes; the assertion is what makes either choice safe.

### 7.3 Vector policy (D18)

Tie `iackAvec := True` and drop `iackVector` (tie to 0 and leave it unconnected at the
socket). The socket declares no vector input, so all seven levels take autovectors 25-31.
That is what the Mac hardware actually does and what v1 does — `m68k_core.v:126`'s "CPU
loops on vec-31" is describing the autovector for level 7.

`cpu_ipl` maps directly onto `iplInPort`. The existing `RegNext(...) init 0` registration at
`FullCoreSynth.scala:319-321` is retained in the socket top: it is the correct synchroniser
placement, and it also keeps the IPL compare cone non-foldable, which the OOC flow relies
on.

---

## 8. Watchdog obligation, G13

### 8.1 What the obligation actually is

`axi_narrow_to_wide.v:78-120,254-263` carries a 20 s (2,000,000,000 core-clk) abandonment timeout.
Because that module lives **inside** the v1 CPU wrapper (`m68k_axi_wrapper.v:1108-1130`),
replacing v1 removes it from the socket's inside. The question this section answers is
whether anything must replace it, and the answer is not the same for the two things it
protected.

The module's own header states its layering rule and its intent precisely
(`axi_narrow_to_wide.v:205-228`):

> "…so on any xbar-connected instance the FABRIC always terminates the transaction first
> (with SLVERR if the slave really is dead) and this watchdog never fires at all. It is a
> genuine last resort for a wide side that is not merely slow but structurally absent."

and the invariant, verbatim (`:259-262`):

> "this value MUST exceed every downstream per-slave watchdog. If you raise one of those,
> raise this one too, or the graceful recovery path you just tuned becomes unreachable."

**Read that header carefully — its own numbers are stratified.** The `:205-228` block's
concrete sizing (2^28, justified as 2× the xbar's `WD_LOG2_S1 = 2^27`) was itself
**superseded the following day** by `:245-262`, which raised the value to 2e9 because
`sd_ctrl`'s per-request watchdog is a larger downstream bound than the xbar's. The
*principle* quoted above survives that revision intact and is what §8.2 relies on; only the
number changed, and D20 §8.3 copies the surviving number rather than re-running the
derivation that produced the wrong one twice.

### 8.2 The transaction-abandonment half: discharged, not rebuilt (D19)

The protection this timer provided against *the fabric never answering* is already provided
**outside** the socket, by the fabric itself: `peripheral_bus.v`'s `PB_WATCHDOG_LOG2 = 24`
(~335 ms @ 50 MHz) and `axi_xbar.v`'s `WD_LOG2_S1 = 27` (~671 ms @ 200 MHz), each sized to
exceed the layer below it. On any xbar-connected instance the CPU-side timer is by explicit
design a never-firing last resort. `axi_i`/`axi_d` are xbar-connected.

So D19: the core does not rebuild a 20 s abandonment timer. The obligation is discharged by
two things together:

1. **The fabric's bounded-response guarantee**, above.
2. **A stated, assertion-backed core-side invariant**: every AXI transaction this core
   issues terminates on a response of *any* resp code, and every FSM that waits on a
   response treats SLVERR/DECERR as terminating, never as a retry. This is already true at
   every response site, and the citations are the evidence, not a hope:
   `DcachePlugin.scala:1258-1272` (refill R, non-OKAY → no allocate, latched for REPLAY),
   `:1210-1216` (eviction B, non-OKAY → diagnostic), `:1676-1685` (maintenance B, same),
   `:1957-1959` (store B, `storeErrReg` alongside `storeAckReg`), `TableWalker.scala:114`
   (descriptor R). §12 records that this invariant must be re-checked, not assumed, by any
   future work that adds an AXI response consumer.

This is a genuine argument from the fabric's own documented layering, not an assertion that
the core "can't wedge". It is also conditional, and the condition must travel with the
decision: **if this core is ever integrated behind a fabric without per-slave watchdogs,
D19 lapses and the timer must be built.**

### 8.3 The new half the v1 topology did not have (D20)

The merge arbiter introduces a wedge mode that did not exist before: three owners now share
one port, so an owner that never completes starves the other two indefinitely. Previously
they were physically separate masters and could not affect each other. This half of the
obligation is genuinely owed, and it is owed *because of this design*, not inherited.

**D20:** each direction of `AxiDMergePlugin` carries a bounded-grant watchdog — a counter of
cycles during which a grant is held with **no progress on any channel of that direction**
(any accepted beat restarts it, so a legitimately slow transaction never trips it; this is
the same "without making progress" formulation `axi_narrow_to_wide.v:97-101` uses).

- **Bound: inherit v1's final `TIMEOUT_CYCLES` value verbatim — 2,000,000,000 core-clk
  cycles (`32'h7735_9400`, `axi_narrow_to_wide.v:263`).** Do **not** re-derive it from the
  xbar's `WD_LOG2_S1 = 2^27`. That derivation is the *superseded* 2026-08-02 reasoning
  (`axi_narrow_to_wide.v:205-228`), and `:245-262` records that it was itself wrong: raising
  `sd_ctrl`'s per-request watchdog to ~10.07 s re-inverted the layering, so a 2^28 bound
  (~2.68 s) pre-empted a *graceful, retryable* SCSI recovery with a *fatal* bus error by
  3.75×. The xbar's `WD_LOG2_S1` is not the largest downstream bound. Both counters live on
  core_clk, so the ~2× margin over `sd_ctrl` is clock-independent and 2e9 remains correct at
  200 MHz.
- **The invariant is inherited verbatim** and must be restated in the RTL comment, in the v1
  header's own words (`axi_narrow_to_wide.v:259-262`): *this value MUST exceed every
  downstream per-slave watchdog; if you raise one of those, raise this one too.* The v1
  header records this bug being introduced twice; a third repetition here is avoidable only
  by copying the value rather than the derivation.
- **On expiry it does not fabricate a response.** Synthesising a `B` would be caught by the
  D-cache's fail-closed ID demux in the best case and would silently ack a store that never
  landed in the worst. Synthesising an `R` would inject garbage into a refill. Instead it
  latches the sticky `coreHalted` state with its own **reason code on D28's new halt-reason
  channel** — the same channel §6.4 builds for D15, and the reason the channel has to exist
  at all: an arbiter wedge and a reset-vector bus error are the two halts an operator most
  needs to tell apart, and today's seam cannot express either.
- **Rationale for halting rather than recovering:** an arbiter cannot construct a truthful
  completion on behalf of its owner, and this project's established policy for an
  un-actionable bus condition is a loud diagnosable halt. A 2e9-cycle expiry on this fabric
  means something structural is wrong, not that a peripheral was slow.

Note the asymmetry with v1's choice to return SLVERR: v1 sat between a *host* (JTAG) and the
fabric, where returning an error to an external, restartable master is the right move. This
arbiter sits between core FSMs, where there is no external master to inform.

---

## 9. Remaining items, G8-G12 and G14

### 9.1 G8 — reset kind and name (D21)

The core is async, active-high `reset`: `M68kSpinalConfig.scala:10-14` adds only a
transformation phase and sets no `ClockDomainConfig`, so SpinalHDL's default
(`resetKind = ASYNC`, active HIGH) applies. The socket says synchronous active-high `rst`
(`cpu_socket.vh:97`).

**D21:** keep ASYNC; rename the socket top's port to `rst`; declare the
`ClockDomainConfig` **explicitly** in the socket top rather than inheriting a default that a
future SpinalHDL upgrade could change silently.

This is functionally safe and the divergence is named rather than glossed: the SoC's
`cpu_rst` is already core_clk-synchronous (`fpga_top_cpu.vh:77,108-110`), so its deassertion
is synchronous to the destination clock by construction and there is no recovery/removal
hazard at the async-reset consumers. An async-reset consumer fed by a synchronously
deasserted source is strictly more permissive than a sync-reset one, not less.

The alternative — switching to `resetKind = SYNC` to match the socket's literal wording —
was rejected because it converts every register's reset in the design and is a real,
unquantified risk to the current 197.278 MHz post-route result (task #219, commit
`5aae2c5` — the 201.450 MHz figure from the pre-FPU UFA/VTL campaign was superseded by the
FPU-merge regression and the ongoing FMax-closure-fanout recovery) for zero functional
gain. The
implementation plan must still re-run the post-route gate after the socket top exists, since
the reset net's fanout changes with the new plugins.

### 9.2 G9 — AXI in flight during reset (NOTED-1)

Confirmed no work needed. The SoC already compensates: `fpga_top_cpu.vh:77,108-110` and
`axi_xbar.v:207-222,805-845`. Recorded so a future reader does not re-open it.

### 9.3 G10 — top-level port surface (D23, D29)

**The scoping memory's "177 test-only top-level IOs" is imprecise and is corrected here.**
177 is the *total* top-level port count of `M68kFullCoreSynth`, the great majority of which
are the four AXI masters. The non-AXI ports number **42**, of which 5 are real
(`clk`, `reset`, `iplInPort`, `iackAvecIn`, `iackVectorIn`) and **37** are probe/test:

- `traceOut_0_*` / `traceOut_1_*` — 26 ports (commit-trace anchor)
- `eu0Res`, `eu1Res`, `fireOut_0`, `fireOut_1` — 4 (`SynthProbePlugin` anchors)
- `redirect_valid`/`redirect_payload`, `resume_valid`/`resume_payload` — 4
- `slot1ValidOut`, `IcachePlugin_logic_invalidateAll`, `RobPlugin_logic_flush_valid` — 3

**A second, separate over-export the scoping pass did not name: AXI sideband signals.** The
core's `Axi4Config`s are declared with only `addressWidth`/`dataWidth`/`idWidth`
(`DcachePlugin.scala:53`, `IcachePlugin.scala:39`, `TableWalker.scala:37`), so SpinalHDL's
defaults leave `useProt`/`useCache`/`useLock`/`useQos`/`useRegion` **on**, and the netlist
duly carries them: `generated/M68kFullCoreSynth.v:107-116` exports
`DcachePlugin_logic_axi_aw_payload_{region,lock,cache,qos,prot}` alongside
`{addr,id,len,size,burst}`. `cpu_socket.vh:98-142` declares **none** of them — the socket's
`axi_i`/`axi_d` groups are exactly `{id, addr, len, size, burst, valid, ready}` plus
`{data, strb, last}` / `{id, data, resp, last}`.

Worse than merely undeclared: the core drives them to **`x`**
(`M68kFullCoreSynth.v:174916,175040-175043`, `assign … _prot = 3'bxxx;` and siblings, and the
same for `IcachePlugin_logic_axi_ar_payload_{cache,prot}` at `:107706,107708` — line numbers
in a *generated* file drift on every regeneration, so the signal names are the stable
citation here, not the line numbers). They are
genuine don't-cares — the core has no notion of protection, cacheability-hint or QoS to
express — so anything downstream that read them would be reading X.

**D29:** the socket-facing ports in `M68kSocketTop` are declared with an `Axi4Config` that
sets `useProt = useCache = useLock = useQos = useRegion = false`, so those signals **do not
exist at the socket boundary at all**. The plugins' internal configs are unchanged; the
sidebands terminate at the socket top's connection, which is already the one place per master
where the §2 permutation makes the connection field-by-field rather than a bulk `<>`. This is
chosen over the alternative of driving socket-side constants (`prot = 0b000`, `cache =
0b0000`, …) precisely because the socket declares no such ports: inventing them would export
a wider surface than the contract, contradicting D23's "only socket ports" rule in the same
breath as satisfying it. It also removes X-driven top-level outputs from the socket surface,
which is worth having on its own.

**D23:** `M68kSocketTop` exports only socket ports; none of the 37 crosses it.
`M68kFullCoreSynth`'s **port surface and behaviour are left unchanged** as the OOC-synth /
FMax-gate target, so the probe anchoring that keeps the retire path from being pruned there is
preserved. (Its wiring file is not frozen — D28 widens the halt seam it drives — but no port
is added, removed or tied off, and D16's `enable=false` keeps the behaviour identical; see
§10's last bullet.) In the
socket top the anchor is the socket itself — real AXI masters, IPL in, `ipl_ack` out — so
nothing prunes and no artificial anchoring is needed. The `redirect`/`resume` ports in
particular become internal under D12/D16 rather than being tied off.

### 9.4 G11 — `cpu_peripheral_reset` ownership (D22, SOC-2, OPEN-1)

This is a real socket port: `m68k_axi_wrapper.v:683` binds `m68k_core.v`'s `cpu_reset_out`
(`:128-130`, "68040 RESET instruction external indication. This does not reset the CPU core
itself; the SoC uses it for its warm peripheral reset"), and `fpga_top_sd.vh:80` consumes it.
Both `cpu_socket.vh:170-176` and the in-flight debug-ctrl plan's port list omit it, so the
debug plan's own conformance check would pass while silently missing it.

**D22:** it belongs to **this** work, not the debug-ctrl plan — it is an architectural
instruction side effect, not a debug-CSR bit.

Driver: the commit-time `SysKind.RESET` arm, `ExceptionUnit.scala:1830-1833`, which today is
a documented architectural no-op ("The external reset line is not modeled for lock-step;
RESET is an internal NOP"). The RESET instruction is fully decoded and framed already
(`OperationDecoder.scala:583-592`, `PredecodeWord.scala:548`,
`MicroOpAssembler.scala:2170-2172`), so this is a new output on an existing, exercised arm.

**Width: v1's driver was read, and it is a 518-cycle level — not a pulse.** `commit.v:2081-2101`
loads a down-counter `reset_instr_count` with `RESET_INSTR_CYCLES - 1` when the RESET
instruction retires and holds `cpu_reset_out` high until it expires:
`localparam integer RESET_INSTR_CYCLES = 518;`, commented *"MAME's 68040 model charges 518
clocks for RESET. Holding the core for the same interval also exceeds the 68040 RSTO minimum
of 124 clocks."* **D22 copies 518 verbatim**, for the same reason D20 copies its bound rather
than re-deriving it. There is no pulse case to default around, so the earlier
"512 cycles if v1 emits a bare pulse" fallback is withdrawn — the number is 518 and it is
measured, not assumed.

**OPEN-1 — v1's hold also gates dispatch and retire, and whether to reproduce that is
undecided.** In v1 the same `cpu_reset_out` level is an *internal* stall as well as an
external output, at four separate sites:

| Site | Effect |
|---|---|
| `commit.v:1283` (`can_commit`) | no instruction retires while the level is high |
| `commit.v:1321` (`can_commit_irq`) | no interrupt is taken while the level is high |
| `m68k_core_fetch.vh:852` (`q_dispatch_fire`) | no µop dispatches |
| `m68k_core_fetch.vh:1038` (`rn_ready`) | rename stops accepting |

So on v1 a `RESET` instruction costs the machine 518 cycles of full quiescence, matching the
518 clocks MAME charges. This core's `RESET` is a pure NOP today
(`ExceptionUnit.scala:1830-1833`: *"The external reset line is not modeled for lock-step;
RESET is an internal NOP"*), so implementing D22 as an output alone would assert
`cpu_peripheral_reset` for 518 cycles **while the core keeps executing** — legal for the SoC
(the output is a reset tree input, not a handshake) but a real behavioural divergence from
v1 and from the timing a driver written against a 68040 may assume between `RESET` and its
next peripheral access.

The two answers, neither taken here:

- **Reproduce the hold.** Faithful to v1 and to the 68040's own timing; costs a
  dispatch/retire gate on a commit-side signal, which is a path the FMax campaign has
  repeatedly found sensitive, and it must be proven not to deadlock against a
  precise-drain/exception window that is itself blocking retire.
- **Output only.** Zero risk to the existing pipeline and zero FMax exposure; diverges from
  v1's observable timing and leaves a driver's post-`RESET` delay assumption unenforced.

This needs explicit sign-off before the implementation plan writes the task, and the plan
**must not** pick silently — the seam is small, but the first option touches the retire path.
Lock-step behaviour is unaffected by the *output* either way (it is not architectural state
and Musashi models nothing here); it would be affected by the *hold*, which changes cycle
counts but not architectural results, so the lock-step comparison stays valid under both.

**SOC-2:** add `cpu_peripheral_reset` to `cpu_socket.vh` §6. A small doc fix, worth doing
regardless of what else happens, since the header is currently authoritative-but-wrong.

### 9.5 G12 — no bursting onto lite-only slaves (NOTED-2)

Not a problem, and the fabric — not this core — is what makes it not a problem.
`axi_xbar.v:3192-3193` computes
`is_burst_reject_r = is_lite_only_slv(dec) && (arlen != 0)` (write-side twin at `:2072`),
and a rejected transaction is error-terminated with a well-formed response rather than
misrouted.

That matters more than the scoping memory implied, because this core *can* reach the case:
`axi_i` emits `arlen=1`, so a wild PC into I/O space issues a 2-beat burst at a lite-only
slave. The fabric rejects it, the response is non-OKAY, and `IcachePlugin`'s existing
`axi.r.resp` check (task #211) turns it into a clean I-fetch bus error → vector 2. That is
the correct architectural outcome.

The invariant to *record* rather than enforce: any future D-side bursting work must confirm
its target can only be a burst-capable slave, or accept an error response. Not a task.

### 9.6 G14 — single-outstanding fabric (NOTED-3)

Not a correctness gap. `IcachePlugin.scala:2151-2181`'s `arHoldValid` already holds AR
stable across `arready` backpressure, so a single-outstanding fabric is handled correctly
today.

The expectation to set, so nobody is surprised: the I-cache's five refill IDs
(`AxiIds.scala:49-63`) and any future multi-MSHR work deliver **zero** end-to-end benefit
until the xbar itself is reworked. `axi_narrow_to_wide.v:72-75` and the xbar's
`rs_state[mi]==RS_IDLE` gating make the fabric single-outstanding per master port
regardless of how many IDs the core presents. `AxiIds.scala:21-30` already records the same
conclusion for the D side ("the SoC crossbar is single-outstanding per master port, so D2
delivers nothing end-to-end until that is reworked"). No task here; the merge arbiter's
serializing behaviour (D8) therefore costs nothing measurable.

---

## 10. Explicitly out of scope

- **`dbg_axi`** — the CPU-exported debug/control AXI-Lite slave (`cpu_socket.vh:145-162`).
  Owned entirely by `docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md` and
  its Stage 0/1 plan. Nothing here adds, removes, or reinterprets any part of it. The two
  designs touch at exactly **two** points, both flagged rather than designed here, so neither
  plan can assume the other owns them:
  1. `cpu_peripheral_reset` (§9.4/D22) — claimed by *this* spec; the debug plan's port list
     omits it.
  2. The **halt-reason channel** D28 builds (§6.4). This spec owns building the channel from
     `RobPlugin`'s halt seam outward and allocating its reason codes. Whatever makes it
     readable over `dbg_axi` is a debug-ctrl register and belongs to that plan — but its
     port/register-map ownership list has to account for it, which today it does not, because
     the channel does not exist yet. Nothing in §13 depends on the CSR half.
- **The SoC-fabric control group** (`cpu_cold_reset_pulse`, `cpu_cold_reset_hold`,
  `cpu_ram_window_lg2`, `cpu_mon_sense`, `init_done_seen`, `cpu_socket.vh:170-176`). These
  are debug-CSR-owned outputs; the debug-ctrl spec §15.1 already assigns them.
- **`ILA_ENABLE` group** (`cpu_socket.vh:177-200`). A deliberate exception to the socket's
  own rules, tied to v1's internal signal set. Not reproduced.
- **SoC address decode, ROM overlay policy, DDR/L2 behaviour.** Consumed as facts here,
  never assumed as a map (see the standing-rule conformance note in §3.3).
- **Any change to `M68kFullCoreSynth`'s port surface or behaviour.** It stays exactly as it
  is as the OOC-synth/FMax-gate target (§9.3): no port added, none removed, none tied off.
  Its *wiring file* is not frozen — D28 widens the halt seam it drives at
  `FullCoreSynth.scala:364`, and D27 touches the walkers it instantiates — but every such
  change must be behaviour-identical with the socket-only plugins absent (D16's `enable=false`
  elaborates them away), which the "`enable=false` leaves every existing test bit-identical"
  obligation in §13 is what actually enforces.

---

## 11. Cross-repo coordination

### 11.1 This repository, no external dependency

Implementable and testable today, in dependency order:

1. §2 byte-order permutation + `M68kSocketTop` skeleton (D1-D3), with the socket-facing
   `Axi4Config` narrowed per D29.
2. §6.4 **halt-reason channel** (D28) — small, and both 3 and 5 below report through it, so it
   comes first rather than being retrofitted twice. This is the one item of *new scope* the
   design review added; it is not a reuse of anything existing.
3. §4 merge arbiter (D7-D10), the walkers' fail-closed ID guard (D27), and the bounded-grant
   watchdog (D20, reports via 2).
4. §3 MMIO sizing (D4-D6, D24-D26, D30) — independent of 1 and 3, but its verification wants
   1 in place. D30 is part of the same sequencer as D6 and must not be split off as a
   follow-up: implementing D6 alone would ship the in-group-misalignment defect that D30
   exists to prevent.
5. §6 reset-vector fetch (D12-D16) — depends on 3 for its read owner and on 2 for D15.
6. §7 `ipl_ack` (D17-D18).
7. §9.1 reset naming (D21), §9.3 port surface (D23/D29), §9.4 `cpu_peripheral_reset` (D22).
   **§9.4 cannot be written as a task until OPEN-1 is signed off**, since the two answers have
   different blast radii (output-only vs. a retire-path gate).

All of this can be verified in this repo against a socket-side simulation model that
implements the SoC's byte-lane convention and sizing rules, before any hardware session.

### 11.2 Requires a companion change in `macqd700-soc`

| Item | Change | Blocks |
|---|---|---|
| **SOC-1** | Widen `axi_i`'s socket-side path to 256 bit; split `CPU_SOCKET_AXI_DW` into per-master `CPU_SOCKET_AXI_I_DW` (256) / `CPU_SOCKET_AXI_D_DW` (128); update `cpu_stub.v` to match. | **Hard blocker** for integration. Nothing in §11.1 depends on it, but the SoC cannot be brought up on this core until it lands. |
| **SOC-2** | Add `cpu_peripheral_reset` to `cpu_socket.vh` §6. | Documentation only; not a functional blocker (the wrapper port already exists at `m68k_axi_wrapper.v:683`). |
| **SOC-3** | Instantiate this core in place of v1, dropping `if_to_axi.v` and `axi_narrow_to_wide.v` from the CPU wrapper. | Integration step. Note this is what removes the 20 s timer — see §8 for why that is discharged rather than a regression. |
| **SOC-4** | `peripheral_bus.v`: serialize any write whose active-lane WSTRB has >1 bit hot into one `pb_wr` pulse per hot bit, on **every** `pb_*` slot. Generalize the existing ASC `wr_asc_*` FSM (`:780-840`) rather than writing a new one; keep the single-hot-bit fast path, the SONIC 16-bit and SCSI DMA-shim serializers, and the same-slot rd/wr interlock unchanged. | Not a blocker for bring-up, but a real correctness fix. **Applies to v1 as much as to this core** — v1 emits 2-hot-strobe WORD stores as its normal encoding, so today a `move.w` to a VIA/SCC/IWM/ENET/ORWELL register silently loses a byte. Fixing shared RTL that both cores traverse is the intended outcome here, not a side effect to be minimised. Verify with a `tb_peripheral_bus.cpp` case per slot; the existing ASC multi-byte tests are the template. |

### 11.3 Confirmed *not* needed

- **AXI ID width.** Already matches: `AxiIds.ID_W = 4` = `CPU_SOCKET_AXI_IW`
  (`AxiIds.scala:16-19,35`; `cpu_socket.vh:85`). No fabric change.
- **Address width.** 32 = 32.
- **Burst mode.** INCR everywhere the core emits.
- **`axi_d` width.** 128 on both sides already.
- **G9 reset compensation.** Already present SoC-side.

---

## 12. Corrections and extensions to the source material

Flagged rather than silently resolved, per the brief. None of these is a contradiction
*within* the source; three are imprecisions and one is a genuine gap in the algorithm the
brief asked for.

1. **"G7 … it's the FIRST AXI transaction the SoC sees (arms the xbar's ROM-overlay
   auto-disable, `axi_xbar.v:1166-1175`)."** The vector-0 read does **not** arm the disable.
   `cpu_rom_read_seen` (`axi_xbar.v:1166-1168`) tests `is_rom_addr(mr_araddr[...])` — the
   **raw** address against the ROM *mirror* aperture (`:1103-1106`), not the overlay-applied
   address. A read at 0x0 is *aliased into* ROM by `apply_cpu_overlay` (`:1184-1192`), which
   is what makes it return the reset vectors, but it does not satisfy `is_rom_addr`. The
   overlay disarms later, when ROM code reads the mirror directly. This does not change D12,
   but it does change *why* D13 matters: the read must come from a CPU master index to get
   the aliasing at all, which is the real constraint.

2. **"G10 (177 test-only top-level IOs)."** 177 is the total port count of
   `M68kFullCoreSynth`. The non-AXI ports number 42, of which 37 are probe/test. Corrected
   with the full enumeration in §9.3. The task is smaller than the figure suggested.

3. **"G13 … inherit the obligation (build equivalent protection) vs. prove the core provably
   never wedges (harder to actually establish)."** The source frames this as a binary. §8
   splits it, because the two halves have different answers: the *transaction-abandonment*
   half is already provided outside the socket by the fabric's own layered watchdogs — and
   `axi_narrow_to_wide.v`'s own header says its timer is designed never to fire on an
   xbar-connected instance — while the *arbiter-starvation* half is a new mode this design
   creates and genuinely owes protection for (D20). Neither half required proving the core
   never wedges.

4. **G5's algorithm is incomplete for this core.** The brief specifies v1's
   WSTRB-popcount rule, which is complete *for v1* only because v1's LSU pre-splits at every
   32-bit word boundary (`m68k_mem_lane.vh`'s `m68k_mem_needs_split`). This core splits only
   at 16-byte lines and 4 KiB pages (`LsEuPlugin.scala:439-444`), and — critically — those
   predicates are evaluated at S1, *before* translation resolves `cacheMode`, so they cannot
   simply gain an INHIBITED term. §3.4 (D6, later widened by D30) extends the design with a
   bounded ≤3-sub-transaction exact-cover sequencer inside `DcachePlugin`'s INHIBITED path.
   This is the one place where following the brief literally would have shipped a known-wrong
   result, so it is called out rather than folded in quietly. §3.5 records the single residual
   that even D30 does not fix.

Additionally noted, neither a correction to the source nor a decision:

- **`axi_narrow_to_wide.v` lives inside the CPU wrapper, not the SoC fabric**
  (`m68k_axi_wrapper.v:1108-1130`). The scoping memory treats it as a citation source
  without stating which side of the socket it sits on, and that placement is precisely what
  makes G5 and G13 this repository's problems rather than the SoC's.
- **That module's header contains two generations of sizing rationale, and the earlier one
  reads as current.** `:205-228` derives 2^28 from the xbar's `WD_LOG2_S1`; `:245-262`
  supersedes it with 2e9 because `sd_ctrl` is the larger downstream bound. A reader who
  stops at the first block will reproduce a bug the file documents being introduced twice.
  §8.1 and D20 are written to prevent that here.

### Corrections applied after the 2026-08-18 design review of *this spec*

The review re-derived every claim from the real sources rather than checking internal
consistency, and found one Critical error and six gaps in the spec as first written. All
seven are fixed above; they are listed here so a future reader can tell which parts of this
document were revised and why, and so the same mistakes are not reintroduced.

| # | What was wrong | Where it is now correct |
|---|---|---|
| **C1** | §3.4 asserted that an INHIBITED access is "after `s1CrossLine` splitting, contained in one 16-byte line". **False on the load path** — `LsEuPlugin.scala:759` issues *both* split slots at the full original size, so slot A arrives with `off + n > 16`. Deriving groups from that unclamped range yields a sub-transaction one line past the access — and every cross-page access forces exactly that geometry (`pageOff + n > 4096` implies `lineOff + n > 16`), so that line is the first line of the **next physical page**, an address the core never translated. Implementing the spec literally would have *created* a wrong-address bus transaction that does not exist today. | §3.4's clamp `end = min(off + n, 16)` (**D25**), with the two-sub-transaction bound now derived *from* the clamp instead of from the false premise; the slot-B consequence recorded as §3.5's slot-B residual (numbered case 3 at the time; case 1, and the only one, after D30). |
| **I1** | §3.3 had no load-side derivation and claimed WSTRB was "the authoritative lane selector in all three cases". AXI reads have **no byte enables**, and `DLoadCmd` (`DcacheTypes.scala:55-61`) has no strobe field. | §3.3 split into 3.3.1 (stores, strobe) and 3.3.2 (loads, `(paddr[1:0], size)`, **D24**); the WSTRB claim scoped to writes. |
| **I2** | The "residual 3-byte case" was narrower than reality and in-group misalignment was unrecorded. | §3.5 was made to list four residuals: 3-byte store, 3-byte **load** (no strobe → 4-byte over-read), slot-B over-read, and in-group misalignment as an explicit parity-with-v1 decision (**D26**). **Superseded** — three of those four are now closed by D30 and D26 has been rewritten; see the user-direction block below. |
| **I3** | §4.3 claimed the arbiter is "strictly additive; removes no existing check" and that each plugin's demux is "preserved untouched". True for the D-cache; **false for the walkers**, which accept `b`/`r` unconditionally (`ItlbPlugin.scala:75`, `DtlbPlugin.scala:80`, `TableWalker.scala:114`) and have no ID check at all — for them the arbiter is the *only* protection. | §4.3 states the distinction; **D27** adds the near-free fail-closed guard so the safety argument is uniform. |
| **I4** | D15/D20 reused "the existing sticky `coreHalted` diagnostic … with a new distinct kind code, the same channel the D-cache's `diagFaultPulse` kinds use". No such channel exists: the seam is a plain `Bool` (`RobPlugin.scala:373-376`) and the kind codes never leave `DcachePlugin`. | §6.4's **D28** — build the channel; new scope; second `dbg_axi` touch point, flagged in §10 and in the §11.1 ordering. |
| **I5** | D22 allowed "defaulting to 512 core-clock cycles if v1 emits a bare pulse". v1 emits a **518-cycle level** (`commit.v:2081-2101`) that also **gates dispatch and retire** at four sites. | §9.4 states 518 as measured fact; the pulse fallback is withdrawn; the dispatch-gating question is **OPEN-1**, requiring sign-off. |
| **I6** | Nothing said what happens to `awprot`/`arprot`/`awcache`/`arcache`/`awlock`/`arlock`/`awqos`/`arqos`/`awregion`/`arregion`, which the core emits (as `x`) and `cpu_socket.vh` does not declare. | §9.3's **D29** — narrow the socket-facing `Axi4Config` so they do not exist at the boundary; cross-referenced from §2.3. |

Verified-correct and therefore **not** touched by that pass, recorded so they are not
re-litigated: the §2.2 byte-order transform (independently re-derived from scratch, three
worked examples), D9's arbiter-deadlock argument, the standing no-SoC-address-map-assumption
conformance in §3.3, and the three §12 self-corrections it spot-checked (ROM-overlay,
177-vs-37 ports, the superseded watchdog value).

### Corrections applied after user direction, 2026-08-18

The user rejected D26's "accepted parity-with-v1 limitation" framing on two grounds, both of
which turned out to be load-bearing rather than stylistic:

> *"the hardware behind the bus can be changed; the behaviour observed from the driver
> shouldn't"* — `axi_wide_to_axilite.v` / `peripheral_bus.v` are FPGA-synthesized RTL under
> project control, so "the fabric does X" is not a boundary condition; the invariant to hold
> is what a real device driver observes.
>
> *"but also if we change this we should also change the v1 core"* — a fix belonging in the
> shared downstream adapter benefits both cores' driver-visible behaviour, and that is to be
> embraced as a deliberate companion work item rather than avoided as scope.

Acting on that, the write case and the load case were re-derived separately — they are not
symmetric — and both `macqd700-soc` files were read at `15e4650`. Four things changed:

| # | What was wrong | Where it is now correct |
|---|---|---|
| **U1** | D26 treated writes and loads as one problem. They are not. A write's `size=2` + `WSTRB=0110` carries the full "which bytes are wanted" information to the slave; AXI4 reads have **no** byte-enable field, so a covering read carries none. A downstream-only fix is sufficient in principle for one and impossible in principle for the other. | §3.3.1 (writes, WSTRB is complete → the residual is the slave's) and §3.3.2 (loads, no strobe exists → the fix must be CPU-side). **D26** is now a write-path decision only; **D30** is the load/uniform one. |
| **U2** | "The cost of fixing it is a general N-way byte sequencer" — an overstatement, and the sole justification for accepting the limitation. `Size` has three members (`Isa.scala:32`), so `n ∈ {1,2,4}` and the D25 clamp gives `end − start ≤ 4`; the complete set of non-representable in-group shapes is three, each covering in 2 pieces, and both groups cannot need 2. The exact cover is **≤ 3 sub-transactions, always**. | §3.4's enumeration table and the ≤3 proof; **D30**. The limitation was never worth accepting because the thing being avoided did not exist. |
| **U3** | The write case was assumed to be handled downstream ("WSTRB stays the authoritative lane selector"). It is not. `peripheral_bus.v` pulses `pb_wr` exactly once per beat (`:993-1002`) and picks the byte with a strobe priority encoder (`:632-638`); only ASC, SONIC-word and the SCSI DMA shim serialize. Every other `pb_*` slot **silently drops bytes**, as the file's own comment states (`:587-592`). v1 is exposed to this today. | §3.3.1's fabric investigation and **SOC-4**, scoped explicitly to fix v1's behaviour too. `axi_wide_to_axilite.v:100-104,149` and `peripheral_bus.v:959,967` were checked and *are* correct — the defect is confined to the 8-bit `pb_*` slots. |
| **U4** | §3.5 case 2 claimed a 4-byte over-read on an 8-bit peripheral is "one register's value read **and side-effected up to 4 times**". False. The read path issues **one** `pb_rd` pulse per AXI transaction (`peripheral_bus.v:1433-1436`) and broadcasts the single byte into all four lanes (`:1334`). The real consequence is one register read once — the *wrong* one where the slot's decode uses `addr[1:0]` (SCC `:1514`, ASC `:1573`, ENET/ORWELL/SONIC/SCSI), and coincidentally the right one where it does not (VIA1/VIA2/IWM decode from `addr[12:9]`, `:1463`/`:1470`/`:1617`). | The claim is deleted; §3.5's closed-case list states the corrected mechanism. The over-read was still a genuine defect — just a different one than recorded — and D30 closes it either way. |

Not changed, and deliberately: §3.5's slot-B over-read survives as the one residual, because
D30 fixes how a range is covered, not a range that was wrong when handed over.

---

## 13. Verification obligations

To be turned into concrete tasks by the implementation plan; listed here so the plan cannot
omit a class of check.

**Byte order (§2)**
- A byte written at address A through `axi_d` is the byte a socket-convention model reads at
  address A, for every A mod 16 and every size.
- An instruction line fetched through `axi_i` decodes to the same opwords as the same bytes
  loaded through `axi_d`.
- A page-table descriptor written by a `move.l` is the descriptor `TableWalker.selectWord`
  reads back, and the U/M byte write lands at `descAddr+3`.
- Structural: the permutation appears exactly once per master, and never on an address.

**MMIO sizing (§3)**
- Byte/word/long INHIBITED stores **and loads** at every offset 0-15 produce a legal
  `size`/address pair by AXI's own alignment rule, and every asserted WSTRB bit lies inside
  the addressed transfer (the §3.3.1 `0110` correction — v1 fails this one).
- **D30, exhaustive and now a hard check, not an expected-behaviour one:** for every
  `off` in 0-15 and every `size`, on both directions, the emitted sub-transactions
  (a) are each naturally aligned for their own `AxSIZE`, (b) have byte extents that
  partition the architectural byte range exactly — no gap, no overlap, and **nothing
  outside it** — and (c) number at most three. 48 load cases and 48 store cases; small
  enough to enumerate rather than sample, which is what makes "no byte outside the access
  is ever touched" a proved property rather than a spot check.
- A byte, word **or long** read *or write* of a byte-addressed device register touches
  exactly the registers the access names, each exactly once, and no others — checked against
  a model with read-to-clear side effects on all four registers in the longword. **Scope:
  every offset, aligned or not.** Under D30 this is no longer restricted to naturally-aligned
  accesses; the earlier carve-out existed only because D26 accepted a wrong answer for the
  misaligned ones. The one exception is the slot-B residual below.
- An INHIBITED access crossing a 4-byte boundary produces sub-transactions that are each
  naturally aligned and whose merged result equals the memory content (D6). Directed
  arithmetic checks on the §3.3.2 worked examples: `0x2`/LONG → 2 sub-transactions;
  `0x5`/WORD → 2 (`size=0` at `0x5`, `size=0` at `0x6`); `0x1`/LONG → 3 (`0x1` 1 B, `0x2`
  2 B, `0x4` 1 B); `0xD`/LONG slot A → 2 (`0xD` 1 B, `0xE` 2 B).
- **D25, directed and specifically adversarial:** for every `off` in 12-15 and every size
  that makes `off + n > 16`, on both the cross-line and the cross-page geometry, **no AXI
  address is emitted outside the 16-byte line the sub-transaction belongs to**, and in
  particular none lands in the next physical page from slot A. This is the C1 regression and
  the check that would have caught it.
- **Expected-behaviour (not forbidden) check for the one surviving §3.5 residual:** a
  cross-line load's slot B derives its range as `[0,n)` at the next line's base, wider than
  the `[0, off + n − 16)` actually needed. Asserted as the *recorded* outcome so a future
  change which silently alters it is caught, and paired with the sim warning §3.5 requires.
  The three cases this list used to carry alongside it — 3-byte store, 3-byte load, WORD at
  group offset 1 — are now covered by the hard D30 check above instead, and a test still
  asserting the old outcomes would be asserting the bug.
- The cacheable path emits no extra transactions and its `size` is unchanged.
- **SOC-4, in `macqd700-soc`, not here:** a write with 2 or 4 strobe bits hot to each `pb_*`
  slot delivers every strobed byte to the peripheral, in ascending address order, one
  `pb_wr` pulse each. Regression-locked in `tb_peripheral_bus.cpp` per slot. Run the same
  case against the **v1** wrapper as well — it fails there today, and that failing-then-
  passing pair is the evidence that the shared fix landed for both cores.

**Merge arbiter (§4)**
- All four assertions in §4.4.
- A refill in `refillWriteHold` concurrent with a store drain makes progress (the D9
  deadlock case, directed).
- ITLB and DTLB transactions with identical IDs, back to back, route to the right consumer.
- The D-cache's three write issuers still ack correctly through the arbiter, including the
  fail-closed unknown-ID case.
- **D27:** a `B` or `R` beat carrying a non-walker ID presented to a walker is *not* acked —
  the walker hangs loudly rather than mis-completing a page-table walk. Directed, since no
  legal stimulus produces it once the arbiter is correct; this checks the second line of
  defence exists at all.

**Halt-reason channel (§6.4, D28)**
- Each of the four producers (D-cache diagnostic fault, FSAVE/FRESTORE translation fault,
  D15 reset-vector bus error, D20 bounded-grant expiry) latches its own distinct reason.
- First-wins: a second halt cause after the first does not overwrite the recorded reason.
- `coreHalted`'s existing control behaviour is bit-identical to before the widening
  (regression, not a new property).

**Reset/boot (§6)**
- SSP and PC land from bytes 0-3 / 4-7 of the vector line, big-endian.
- No fetch occurs before the redirect; the first fetch is at the loaded PC.
- Committed A7 reads back the SSP, and `ss.isp` tracks it.
- A non-OKAY vector response halts with the D15 reason code on D28's channel and does not
  attempt a frame.
- `enable=false` leaves every existing test bit-identical.

**Interrupts (§7)**
- The D17 count-equality assertion, run over the full lock-step corpus.
- Autovector selection for levels 1-7.

**Socket port surface and `RESET` (§9.3, §9.4)**
- **D29, structural:** the elaborated `M68kSocketTop` has *no* `prot`/`cache`/`lock`/`qos`/
  `region` port on either master, and its port set is a subset of `cpu_socket.vh` §§2-3, §6.
  Cheap to check by enumerating the generated Verilog's port list, and it catches a future
  `Axi4Config` change silently re-exporting them.
- **D22:** `cpu_peripheral_reset` rises on `RESET` retirement and stays high for exactly 518
  core-clock cycles, then falls; back-to-back `RESET`s re-arm it without a glitch low.
- **OPEN-1:** whichever answer is signed off, the check follows it — either "no µop retires
  during the hold" or "execution is unaffected by the hold". The plan must state which it is
  testing; a test written against the unsigned-off assumption is worse than none.

**Gates** — the project's standing rules apply unchanged: `make SBT=~/sbt/bin/sbt test-fast`,
full lock-step, and an **uncontended** post-route gate for the socket top (§9.1 changes the
reset net's fanout, and FMax on this machine is unreliable under concurrent Vivado/JTAG
sessions — a repeatedly confirmed hazard). `git worktree add` is mandatory for any
before/after comparison.

---

## 14. Resolved open items (2026-08-18)

### 14.1 `OPEN-1` — `cpu_peripheral_reset`'s 518-cycle hold and dispatch/retire gating

**RESOLVED: Option A: output only.**

Decided by explicit user sign-off on 2026-08-18, during the implementation-plan pass
(`docs/superpowers/plans/2026-08-18-axi-socket-adapter-implementation-plan.md`, Task 11).

**Rationale as given, verbatim:** *"wrt RESET you implement something that is as compatible to
68k as possible."*

**How this resolves the two options.** The M68000 family's `RESET` instruction is documented
architecture-wide (68000 through 68040) as asserting only the external reset pin for a fixed
duration; the processor's own internal state and instruction execution are explicitly
unaffected — execution continues normally with the instruction stream through the pulse. v1's
internal dispatch/retire gating during its own hold (`commit.v`'s `can_commit`/`can_commit_irq`
stall, `m68k_core_fetch.vh`'s dispatch/rename stall) is v1's own implementation choice, not a
requirement of the 68k ISA's own documented `RESET` semantics — so it is the answer that is
*less*, not more, 68k-compatible, and is not reproduced here. Option A — `cpu_peripheral_reset`
asserted for the full 518-cycle width while the core's own pipeline keeps executing normally —
is the architecturally faithful behavior.

**What this binds.** Task 12 of the implementation plan implements Option A:
`PeripheralResetPlugin(gateDispatch = false)`. No `RobPlugin.scala` changes (`:585`, `:1447`,
`:1487-1488` stay untouched — those are Option-B-only per Task 12's own file list). Per §13's
`OPEN-1` bullet, the surviving test is *"execution is unaffected by the hold"* — a directed test
that µops continue to retire while `cpu_peripheral_reset` is high; the Option-B "no µop retires
during the hold" test is deleted, not left cancelled.

`D22`'s measured facts are unchanged: the width is **518** core clocks, copied verbatim from
`commit.v:2081-2101`, and the driver is the commit-time `SysKind.RESET` arm at
`ExceptionUnit.scala:1830-1833`. Only the internal-gating question was open; the external pulse
width and shape were never in question.

**v1 note (out of scope here, tracked separately):** this resolution implies v1's own
`commit.v`/`m68k_core_fetch.vh` gating is itself a real divergence from documented 68k `RESET`
semantics, worth a v1 update once that repository's in-flight uncommitted work settles — see
the project's standing "plan updates for v1, too" goal and the existing v1-bug tracking pattern
(tasks #225/#226).
