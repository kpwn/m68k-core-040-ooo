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
residual; items tagged **SOC** must be executed in `macqd700-soc`, not here.

| # | Decision |
|---:|---|
| **D1** | Adapt byte order at the socket boundary with a pure wire permutation. Do **not** change the core's native byte-address-invariant lane convention in `DcacheTypes` / `IcacheTypes` / `TableWalker`. (§2) |
| **D2** | The permutation is a **per-32-bit-lane byte reversal** on WDATA/RDATA and a **per-4-bit-nibble reversal** on WSTRB. Inter-word order is untouched. It is an involution, so one function serves both directions and both widths. (§2.2) |
| **D3** | The permutation lives in a new top-level `M68kSocketTop`, applied exactly once per master, only to `w.data` / `w.strb` / `r.data`. Never to address, id, len, size, burst, resp, or last. (§2.3) |
| **D4** | The D-side derives a real `AxSIZE` and a byte-granular address for every **INHIBITED** access, from the access's own size/strobe — never from an address-range table. (§3) |
| **D5** | The size/address derivation runs on the **core-side** (pre-permutation) strobe, because the byte-offset a run starts at is not permutation-invariant. (§3.3) |
| **D6** | An INHIBITED access whose byte range crosses a 4-byte boundary is decomposed into **two naturally-aligned AXI sub-transactions** inside `DcachePlugin`'s INHIBITED path. This is an extension beyond the v1 algorithm and is required for parity — see §3.4 for why the naive containment fallback is wrong. |
| **D7** | 4 masters → 2. `axi_i` stays I-cache-only. D-cache + ITLB walker + DTLB walker merge onto `axi_d`. The ITLB walker genuinely issues AXI writes, so it categorically cannot ride the read-only `axi_i`. (§4.1) |
| **D8** | The merge is an **owner-tag serializing arbiter**, not a raw-ID demux. Responses route by the arbiter's latched grant owner; the returned AXI ID is forwarded as a fabric hint and is never consulted for routing. (§4.2) |
| **D9** | Read and write grants are **independent** per-direction state machines. A single global token deadlocks against `refillWriteHold`. (§4.4) |
| **D10** | Each plugin's existing internal ID demux (D-cache B-by-ID, walker completion) is preserved untouched; the arbiter is strictly additive. (§4.3) |
| **D11** | `axi_i` stays natively 256-bit, `len=1`/`size=5` (two 32-byte beats = a 64-byte line). No 256→128 downconverter is built in this repo. (§5) |
| **SOC-1** | `macqd700-soc` must widen the `axi_i` socket-side path to 256 bit. The socket's single `CPU_SOCKET_AXI_DW` define must split into per-master `..._AXI_I_DW` (256) and `..._AXI_D_DW` (128). (§5, §11) |
| **D12** | Reset/boot is a **real vector-0 AXI fetch**: one 16-byte read at physical 0x0, SSP from bytes 0-3, PC from bytes 4-7. Not a debug-CSR-supplied PC. (§6) |
| **D13** | The vector-0 read is issued as a fourth read owner on the **`axi_d` merge arbiter**, never as a third socket master — only `XBAR_M_CPU`/`XBAR_M_CPUI` reads get the SoC's ROM overlay aliasing. (§6.2) |
| **D14** | The initial SSP reaches committed A7 through the **existing shared `a7Wr` int-PRF port** at `RenameStage.committedPhysA7`, as a new highest-priority third source. SSP write in cycle N, fetch redirect in cycle N+1. (§6.3) |
| **D15** | A non-OKAY vector-0 response latches the existing sticky `coreHalted` diagnostic, not a vector-2 frame. This is hardware-faithful (a fault during reset exception processing halts a real 68040) and a deliberate divergence from v1. (§6.4) |
| **D16** | The reset-vector fetch is behind a constructor parameter, default **off**, mirroring v1's `FETCH_RESET_VECTORS`. The existing sim/lock-step/OOC flows keep the external `redirect` port. (§6.5) |
| **D17** | `ipl_ack` is a 1-cycle pulse per interrupt exception entry actually taken, derived from the ExceptionUnit's own registered `obsIsInterrupt` observation, pinned by a count-equality assertion. (§7.2) |
| **D18** | `iackAvec` is tied to 1 (autovector) and `iackVector` is dropped: the socket has no vector input. (§7.3) |
| **D19** | The core does **not** reimplement a 20 s abandonment timer. The obligation is discharged by the SoC fabric's own bounded-response guarantee plus a stated, assertion-backed core-side invariant. (§8.2) |
| **D20** | The merge arbiter carries its own **bounded-grant** watchdog, closing a new wedge mode this design creates (three owners on one port). This is *not* a reinstatement of the v1 abandonment timer D19 declines. Bound = v1's value copied verbatim (2e9 core-clk), never re-derived. On expiry it latches `coreHalted` with a distinct kind code; it never fabricates an AXI response. (§8.3) |
| **D21** | Keep the core's async active-high reset; rename the socket top's port `reset` → `rst`, declare the `ClockDomainConfig` explicitly instead of inheriting the default, and re-run the post-route gate. (§9.1) |
| **D22** | `cpu_peripheral_reset` (68040 `RESET` instruction output) is owned by **this** work, not the debug-ctrl plan. Driven from the commit-time `SysKind.RESET` arm. (§9.4) |
| **SOC-2** | `macqd700-soc` must add `cpu_peripheral_reset` to `cpu_socket.vh` §6, which omits it today. (§9.4, §11) |
| **SOC-3** | `macqd700-soc` must instantiate this core in place of v1, dropping `if_to_axi.v` and `axi_narrow_to_wide.v` from the CPU wrapper. That is the step which removes the 20 s timer — see D19. (§11.2) |
| **D23** | The socket top exports **only** socket ports. The 37 probe/test top-level IOs stay on the unchanged `M68kFullCoreSynth` target. (§9.3) |
| **NOTED-1** | G9 (AXI in flight during reset): no work needed, the SoC already compensates. (§9.2) |
| **NOTED-2** | G12 (bursts onto lite-only slaves): already error-terminated by the fabric, not corrupted. Not a task. (§9.5) |
| **NOTED-3** | G14 (single-outstanding fabric): the I-cache's five refill IDs and any future multi-MSHR work deliver zero end-to-end benefit until the xbar is reworked. Expectation-setting only. (§9.6) |

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

- `DLoadCmd` (`DcacheTypes.scala:56-62`): full byte-granular `paddr`, `size`, `cacheMode`.
- `DStoreCmd` (`DcacheTypes.scala:83-96`): full byte-granular `paddr`, `size`, `useStrb`,
  a 16-bit line-relative `strb`, `cacheMode`.

The information is present at both AXI emission sites and is simply discarded when the
transaction is formed. G5 is therefore a change local to `DcachePlugin`'s two INHIBITED
emission paths, not a datapath change.

### 3.3 The derivation (D4, D5)

Mirroring the proven v1 rule (`axi_narrow_to_wide.v:33-51`), on the **core-side**
(pre-permutation) strobe of a single access, restricted to the containing 4-byte group:

| Core-side strobe within the addressed longword | `AxSIZE` | Address presented |
|---|---:|---|
| exactly 1 bit set | 0 (1 B) | the access's full byte address, unmodified |
| exactly 2 contiguous bits set | 1 (2 B) | byte address with bit 0 cleared |
| anything else (3 or 4 bits, or sparse) | 2 (4 B) | byte address with bits[1:0] cleared |

WSTRB remains the authoritative lane selector in all three cases — AXI permits sparse byte
strobes at `size=2`, and `axi_narrow_to_wide.v:46-51` confirms the sparse case is designed
to fall through exactly this way.

**D5 — why core-side.** Reversal within a nibble preserves popcount and preserves
contiguity, but it does *not* preserve the offset at which a run starts. Deriving from the
post-permutation strobe would produce the mirror-image address. The derivation must
therefore run inside `DcachePlugin`, on the core's own byte-offset-indexed strobe, and the
§2 permutation must be applied strictly afterwards, at the socket boundary. These two
transforms are order-dependent and the implementation must say so at both sites.

**Standing-rule conformance.** Nothing in this derivation consults an address range. The
only input that says "this is a device, not memory" is `cacheMode === INHIBITED`, which
comes from the MMU's page/TTR attributes on the access itself
(`DcachePlugin.scala:351-353,663,701`). This satisfies the project's standing rule that the
core may only reason from MMU-configured attributes and contemporaneous bus responses,
never from a cached or assumed SoC decode map.

### 3.4 The 4-byte-boundary case, and why v1's rule alone is not enough (D6)

**This is an extension beyond the brief, made because the naive rule is provably wrong
here.** v1's algorithm is complete *for v1* because v1's LSU never presents an access that
spans a 32-bit word boundary — `m68k_mem_lane.vh`'s `m68k_mem_needs_split` splits those
into two narrow accesses before `axi_narrow_to_wide` ever sees them. This core has no such
pre-split: `LsEuPlugin.scala:440-444` splits only on `s1CrossLine` (16 B) and `s1CrossPage`
(4 KiB). A misaligned longword at offset 1 within a line is a single `DLoadCmd`/`DStoreCmd`
spanning two longwords.

Under the §3.3 table alone that access falls to "anything else" → `size=2` at the
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
**two-sub-beat sequencer**:

- The byte range of an INHIBITED access is, after `s1CrossLine` splitting, contained in one
  16-byte line, so it spans **at most two** 4-byte groups. The sequencer therefore needs at
  most two sub-transactions, never a loop.
- **Loads:** issue sub-transaction A (the lower group's bytes) and, if the range extends
  past that group, sub-transaction B. Merge each response into `missLine` at its own byte
  offset before the existing `REPLAY` path runs. `loadRspPort.payload.data`'s extraction at
  `missPaddr[3:0]` (`DcachePlugin.scala:498-503`) is then unchanged and correct. A non-OKAY
  response on either sub-transaction raises the existing `busFaultResp`.
- **Stores:** issue AW/W/B for sub-transaction A, then B, and assert `storeAck` only after
  the second `B` handshake. `storeErrReg` is the OR of both responses. The existing
  fail-closed `=== AxiIds.D_STORE` B demux (`DcachePlugin.scala:1957`) is preserved for
  each.
- Cost: zero on the cacheable path (the sequencer is reachable only when
  `missCmode === INHIBITED` / `stS3Inhibited`), and one extra bus round trip on an
  already-slow, already-serialized MMIO path.

**Deliberate residual, recorded not buried.** A *3-byte* INHIBITED access (reachable only
through the SQ's explicit-strobe split-slot form, `DcacheTypes.scala:88-92`) still resolves
to a `size=2` sparse-strobe transaction within its group. That is correct for memory and
wrong for a byte-addressed peripheral. No 68k instruction generates a 3-byte access to an
8-bit device in any Mac driver, and real hardware would also need multiple cycles. The
implementation must carry a simulation assertion flagging it rather than leaving it silent.

### 3.5 What does *not* change

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

### 4.3 Why G3's ID collision evaporates (D10)

ITLB and DTLB both emit AR=2 / AW=3 (`AxiIds.scala:67,69`). Under D8 that is inert, for two
independent reasons:

1. **Routing never reads the ID.** Responses go to `arOwner`/`awOwner`, which the arbiter
   latched at grant time. Two owners with identical IDs are indistinguishable *to the
   fabric* but perfectly distinguishable *to the arbiter*.
2. **They can never be simultaneously outstanding anyway.** The arbiter is
   single-outstanding per direction, so at most one walker transaction exists on `axi_d` at
   a time. There is no ambiguity for the ID to resolve.

**D10 — the plugins' own ID logic stays.** The D-cache demultiplexes its *own three* write
issuers by ID (`D_STORE=1` store write-through at `DcachePlugin.scala:1957`, `D_PUSH=2`
eviction writeback at `:1210`, `D_EVICT=4` maintenance writeback at `:1676`), deliberately
fail-closed
(`DcachePlugin.scala:1948-1956`: "an unrecognized id simply not ack anything — a hung drain,
which is loud and debuggable, instead of a silent spurious ack"). The arbiter delivers `B`
to the D-cache only when the D-cache is the write owner, so those compares keep working
unchanged and keep their fail-closed property. The arbiter is strictly additive; it removes
no existing check.

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

### 6.4 Bus error on the vector fetch (D15)

v1 presents `pd_fault` at `pc=0` so commit raises bus-error vector 2
(`if_stage.v:16-18`). **D15 diverges deliberately:** a non-OKAY vector-0 response latches
the existing sticky `coreHalted` diagnostic channel (`FullCoreSynth.scala` `coreHaltedIn`,
the same channel the D-cache's `diagFaultPulse` kinds 0/2/3 use) with a new kind code, and
the core stops.

Rationale, in order of weight:

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
  latches the sticky `coreHalted` diagnostic with its own kind code, exactly as §6.4 and the
  D-cache's `diagFaultPulse` policy do.
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
unquantified risk to the 201.450 MHz post-route result for zero functional gain. The
implementation plan must still re-run the post-route gate after the socket top exists, since
the reset net's fanout changes with the new plugins.

### 9.2 G9 — AXI in flight during reset (NOTED-1)

Confirmed no work needed. The SoC already compensates: `fpga_top_cpu.vh:77,108-110` and
`axi_xbar.v:207-222,805-845`. Recorded so a future reader does not re-open it.

### 9.3 G10 — test-only top-level IOs (D23)

**The scoping memory's "177 test-only top-level IOs" is imprecise and is corrected here.**
177 is the *total* top-level port count of `M68kFullCoreSynth`, the great majority of which
are the four AXI masters. The non-AXI ports number **42**, of which 5 are real
(`clk`, `reset`, `iplInPort`, `iackAvecIn`, `iackVectorIn`) and **37** are probe/test:

- `traceOut_0_*` / `traceOut_1_*` — 26 ports (commit-trace anchor)
- `eu0Res`, `eu1Res`, `fireOut_0`, `fireOut_1` — 4 (`SynthProbePlugin` anchors)
- `redirect_valid`/`redirect_payload`, `resume_valid`/`resume_payload` — 4
- `slot1ValidOut`, `IcachePlugin_logic_invalidateAll`, `RobPlugin_logic_flush_valid` — 3

**D23:** `M68kSocketTop` exports only socket ports; none of the 37 crosses it.
`M68kFullCoreSynth` is left **completely unchanged** as the OOC-synth / FMax-gate target, so
the probe anchoring that keeps the retire path from being pruned there is preserved. In the
socket top the anchor is the socket itself — real AXI masters, IPL in, `ipl_ack` out — so
nothing prunes and no artificial anchoring is needed. The `redirect`/`resume` ports in
particular become internal under D12/D16 rather than being tied off.

### 9.4 G11 — `cpu_peripheral_reset` ownership (D22, SOC-2)

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

Width: a real 68040 asserts RSTO for 512 clocks. The implementation plan must read
`m68k_core.v`'s `cpu_reset_out` driver and **match v1's observed width**, defaulting to 512
core-clock cycles if v1 emits a bare pulse — the SoC consumer is a reset tree, and a
one-cycle pulse into a reset tree is the kind of thing that works in simulation and not on
hardware. Lock-step behaviour is unaffected either way: the output is not architectural
state and Musashi models nothing here.

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
  designs touch at exactly one point — `cpu_peripheral_reset` (§9.4), which this spec claims
  and the debug plan's port list omits — and that claim is made explicitly so neither plan
  can assume the other owns it.
- **The SoC-fabric control group** (`cpu_cold_reset_pulse`, `cpu_cold_reset_hold`,
  `cpu_ram_window_lg2`, `cpu_mon_sense`, `init_done_seen`, `cpu_socket.vh:170-176`). These
  are debug-CSR-owned outputs; the debug-ctrl spec §15.1 already assigns them.
- **`ILA_ENABLE` group** (`cpu_socket.vh:177-200`). A deliberate exception to the socket's
  own rules, tied to v1's internal signal set. Not reproduced.
- **SoC address decode, ROM overlay policy, DDR/L2 behaviour.** Consumed as facts here,
  never assumed as a map (see the standing-rule conformance note in §3.3).
- **Any change to `M68kFullCoreSynth`.** It stays exactly as it is (§9.3).

---

## 11. Cross-repo coordination

### 11.1 This repository, no external dependency

Implementable and testable today, in dependency order:

1. §2 byte-order permutation + `M68kSocketTop` skeleton (D1-D3).
2. §4 merge arbiter (D7-D10) and its bounded-grant watchdog (D20).
3. §3 MMIO sizing (D4-D6) — independent of 1 and 2, but its verification wants 1 in place.
4. §6 reset-vector fetch (D12-D16) — depends on 2 for its read owner.
5. §7 `ipl_ack` (D17-D18).
6. §9.1 reset naming (D21), §9.3 port surface (D23), §9.4 `cpu_peripheral_reset` (D22).

All of this can be verified in this repo against a socket-side simulation model that
implements the SoC's byte-lane convention and sizing rules, before any hardware session.

### 11.2 Requires a companion change in `macqd700-soc`

| Item | Change | Blocks |
|---|---|---|
| **SOC-1** | Widen `axi_i`'s socket-side path to 256 bit; split `CPU_SOCKET_AXI_DW` into per-master `CPU_SOCKET_AXI_I_DW` (256) / `CPU_SOCKET_AXI_D_DW` (128); update `cpu_stub.v` to match. | **Hard blocker** for integration. Nothing in §11.1 depends on it, but the SoC cannot be brought up on this core until it lands. |
| **SOC-2** | Add `cpu_peripheral_reset` to `cpu_socket.vh` §6. | Documentation only; not a functional blocker (the wrapper port already exists at `m68k_axi_wrapper.v:683`). |
| **SOC-3** | Instantiate this core in place of v1, dropping `if_to_axi.v` and `axi_narrow_to_wide.v` from the CPU wrapper. | Integration step. Note this is what removes the 20 s timer — see §8 for why that is discharged rather than a regression. |

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
   simply gain an INHIBITED term. §3.4 (D6) extends the design with a two-sub-beat sequencer
   inside `DcachePlugin`'s INHIBITED path. This is the one place where following the brief
   literally would have shipped a known-wrong result, so it is called out rather than folded
   in quietly. §3.4 also records the residual 3-byte case that even D6 does not fix.

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
- Byte/word/long INHIBITED stores at every offset 0-15 produce a legal `size`/address pair
  by AXI's own alignment rule.
- A byte read of a byte-addressed device register touches that register and no other
  (checked against a model with read-to-clear side effects on all four registers in the
  longword).
- An INHIBITED access crossing a 4-byte boundary produces exactly two sub-transactions, each
  naturally aligned, and the merged result equals the memory content (D6).
- The cacheable path emits no extra transactions and its `size` is unchanged.

**Merge arbiter (§4)**
- All four assertions in §4.4.
- A refill in `refillWriteHold` concurrent with a store drain makes progress (the D9
  deadlock case, directed).
- ITLB and DTLB transactions with identical IDs, back to back, route to the right consumer.
- The D-cache's three write issuers still ack correctly through the arbiter, including the
  fail-closed unknown-ID case.

**Reset/boot (§6)**
- SSP and PC land from bytes 0-3 / 4-7 of the vector line, big-endian.
- No fetch occurs before the redirect; the first fetch is at the loaded PC.
- Committed A7 reads back the SSP, and `ss.isp` tracks it.
- A non-OKAY vector response halts with the D15 kind code and does not attempt a frame.
- `enable=false` leaves every existing test bit-identical.

**Interrupts (§7)**
- The D17 count-equality assertion, run over the full lock-step corpus.
- Autovector selection for levels 1-7.

**Gates** — the project's standing rules apply unchanged: `make SBT=~/sbt/bin/sbt test-fast`,
full lock-step, and an **uncontended** post-route gate for the socket top (§9.1 changes the
reset net's fanout, and FMax on this machine is unreliable under concurrent Vivado/JTAG
sessions — a repeatedly confirmed hazard). `git worktree add` is mandatory for any
before/after comparison.
