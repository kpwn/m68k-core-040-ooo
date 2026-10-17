# 09 — MMU, socket glue, debug block, and top-level assembly

Source tree: commit `fee95f6f` (the bitstream on the board). Every path below is
relative to the repository root. Unless a path says otherwise, `mmu/`, `socket/`,
`debug/`, `top/` and `verif/` mean `src/main/scala/m68k040/<dir>/`.

**How this document was written.** Every claim comes from the Scala code. Source
comments and `docs/*` were **not** used as evidence, because they are often stale.
Where a comment claims something the code does not do, the document says so. Cycle
counts and area figures that are *derived* (not printed in the code) are marked
**[derived]** or **[estimate]**.

Shipping configuration assumed: `SHIPPING_CONFIG` as given in the build log
(`itlbVictimEntries=0`, `inhibitedFullBarrier=true`, `dcacheHotDoor=true`, …),
`CPU_DEBUG_PROFILE=full`, `PERF_DETAIL_ENABLE` unset (=0), `GenSocketTopVerilog` →
`M68kSocketTop`.

---

## 1. Purpose and position

This slice covers four groups of logic:

| Group | Files | Role |
|---|---|---|
| **MMU** | `mmu/MmuControl.scala`, `mmu/MmuTypes.scala`, `mmu/Tlb.scala`, `mmu/TableWalker.scala`, `mmu/ItlbPlugin.scala`, `mmu/DtlbPlugin.scala`, `mmu/UmWriteQueue.scala`, `mmu/UmWalkBatchQueue.scala`, `mmu/IdentityTranslationPlugin.scala`, `mmu/DIdentityTranslationPlugin.scala` | 68040 paged translation: the TC/URP/SRP/TTR/MMUSR registers, two ATCs (ITLB and DTLB), two hardware table walkers, and deferred U/M descriptor write-back. |
| **Socket glue** | `socket/HaltReason.scala`, `socket/IplAckPlugin.scala`, `socket/PeripheralResetPlugin.scala`, `socket/ResetVectorFsm.scala`, `socket/ResetVectorPlugin.scala` | The reset-vector fetch, the interrupt-acknowledge pulse, the RESET-instruction output, and the halt-reason encoding. |
| **Debug** | `debug/DebugCtrlPlugin.scala`, `debug/DebugRegMap.scala`, `debug/DbgAxiLite.scala`, `debug/VioProbePlugin.scala`, `debug/FetchWordCheck.scala`, `debug/HaltAfterLoadGuard.scala` | A JTAG/AXI-Lite debug CSR block: run control, breakpoints, architectural-state read/write, history rings, performance counters, and forensic probes. |
| **Top level** | `top/SocketTop.scala`, `top/FullCoreSynth.scala`, `top/GenVerilog.scala`, `top/ExecSynthProbes.scala`, `top/SynthProbePlugin.scala`, `verif/TraceReplay.scala` | The plugin lists, the SoC-facing ports, out-of-context (OOC) synthesis targets, and synthesis probes. |

The MMU sits between fetch/LSU and the caches. The ITLB answers `TranslationService`
(read by `IcachePlugin`). The DTLB answers `DTranslationService` (read by `LsEuPlugin`
and the ExceptionUnit's translation path). Both walkers send their descriptor reads
and U/M writes through the **D-cache**: they are clients that `LsEuPlugin` arbitrates.
Neither walker has its own AXI master.

---

## 2. Interfaces

### 2.1 Services and plugin hooks (MMU)

| Producer | Service / hook | Consumers | Notes |
|---|---|---|---|
| `MmuControlPlugin` (`mmu/MmuControl.scala:54`) | `MmuControlService`: `mmuEnable`, `pageSize8K`, `urp`, `srp`, `itt0/1`, `dtt0/1`, `mmusr` (read), and the write Flows `setEnable`, `setPageSize`, `setUrp`, `setSrp`, `setItt0/1`, `setDtt0/1`, `setMmusr` | ITLB, DTLB, ExceptionUnit (MOVEC/PTEST), RobPlugin (debug apply), `LsEuPlugin`, `IcachePlugin` (8 KB PA composition) | Getters go through the `logic` Handle (`MmuControl.scala:90-98`). The write Flows default to idle with `allowOverride` (`:141-152`). |
| `ItlbPlugin` (`mmu/ItlbPlugin.scala:129`) | `TranslationService` (`req`, `rsp`: plain bundles, so the answer is **combinational**) and `ItlbWalkerDcacheClient` (`walkLoadCmd/Rsp`, `walkStore/Ack/Err`, `walkCmodePolicy`) | `IcachePlugin` (`cache/IcachePlugin.scala:187,225-228,342-345,1842`); `LsEuPlugin` walker arbiter (`execute/LsEuPlugin.scala:549-689`) | Plugin-level `var` hooks: `umCommitValid/Id`, `umCommitBValid/BId`, `umFlush`, `flushAll`. |
| `DtlbPlugin` (`mmu/DtlbPlugin.scala:29`) | `DTranslationService` (`req`/`rsp` are Streams, so the answer is **registered**) and `DtlbWalkerDcacheClient` | `LsEuPlugin`, ExceptionUnit via BackendWiring (`top/FullCoreSynth.scala:645-653`) | Extra hooks `umAccessRobId`, `umAccessPreCommitted`. |
| `IdentityTranslationPlugin` / `DIdentityTranslationPlugin` | Same services, VA=PA | Test DUTs and `GenSynthVerilog` only | Not in the shipping plugin list. |

Drivers of the hooks in the shipping build (`BackendWiringPlugin`, `top/FullCoreSynth.scala:532-558`):

- `dtlb.umAccessRobId := lsEu.xlateRobId`
- `dtlb.umAccessPreCommitted := rob.logic.excActive`
- `{dtlb,itlb}.umCommit{,B}{Valid,Id} := rob.retire0/1, h0/h1`
- `{dtlb,itlb}.umFlush := doFlush`
- `{dtlb,itlb}.flushAll := rob.logic.exc.sysFlushAllValid`

`sysFlushAllValid` is pulsed by PFLUSH* (`exception/ExceptionUnit.scala:2715-2718`) and
by a debug architectural apply that touches TC, a TTR, or a root (`rob/RobPlugin.scala:3323-3326`).

The Database/`Global` keys this slice adds: none found. The MMU talks only through the
services above and the plugin `var` hooks, which `BackendWiringPlugin` drives from outside.

### 2.2 Socket and debug services

| Producer | What | Consumer |
|---|---|---|
| `ResetVectorPlugin` (`socket/ResetVectorPlugin.scala:31`) | `logic.sspWriteValid/sspData/haltPulse`; drives `AxiDMergePlugin.logic.rv*` and `FetchAlignPlugin.logic.resetRedirect` | BackendWiring A7 write port (`top/FullCoreSynth.scala:724-731`); halt mux (`:601,610`) |
| `IplAckPlugin` (`socket/IplAckPlugin.scala:51`) | `logic.iplAck`, a top-level `out` named `ipl_ack` when `enable` | SocketTop port; `DebugIrqInjectService.irqInjectAck` (`top/FullCoreSynth.scala:526-529`) |
| `PeripheralResetPlugin` (`socket/PeripheralResetPlugin.scala:47`) | `logic.cpuPeripheralReset`, an `out` named `cpu_peripheral_reset` | SocketTop port |
| `DebugCtrlPlugin` (`debug/DebugCtrlPlugin.scala:59`) | `DebugIrqInjectService`; slave `dbg_axi`; outputs `cpu_cold_reset_pulse/hold`, `cpu_ram_window_lg2`, `cpu_mon_sense`; input `init_done_seen` | It calls `DebugCommitService.request/configure*` (RobPlugin), `FrontendDebugMatchService.configure` (DecodeStage), `DebugSystemStateService.requestApply`, `DebugMemoryService.request` (BackendWiring), and the multi-hot clear and prefetch overrides of Icache/Dcache/ITLB/DTLB (`:2403-2466`) |
| `BackendWiringPlugin` (`top/FullCoreSynth.scala:28`) | `DebugMemoryService` (debug cache maintenance, `:670-701,790-793`) | `DebugCtrlPlugin` |

### 2.3 `M68kSocketTop` external ports (what the SoC sees)

From `top/SocketTop.scala:71-242`; port wiring at `:355-585`.

| Port | Dir | Width | Notes |
|---|---|---|---|
| `clk`, `rst` | in | 1 | `coreCd`: rising edge, **async**, active-high reset (`:74-79`). A second domain, `axiPorCd` (BOOT reset, same clock), holds the read-reset absorbers (`:85-88,355-369`). |
| `axi_i` | master | AR/R only, **256-bit** data, id `AxiIds.ID_W`=4 | The I-cache refill port (`SocketAxiI`, `socket/SocketAxi.scala:28`). It passes through a `StreamPipe.FULL` register slice on AR and R (`:406-407`) and a read-reset absorber (`:420-430`). R data is byte-permuted (`SocketByteOrder.permuteData`, `:427`). |
| `axi_d` | master | full AXI4, **128-bit**, id 4 | Comes from `AxiDMergePlugin` (D-cache + reset-vector fetch). FULL register slices on all five channels (`:408-410`). Read-reset absorber on AR/R only (`:459-466`). W data and strobes, and R data, are byte-permuted (`:445-446,463`). |
| `axi_dh` | master | AR/R, 128-bit, id 2 | Present only when `ShippingCoreConfig.dcacheHotDoor` (true in shipping, `:228-230`). Connected through `SocketHotReadBoundary.connect` (`:374-377`). It has **no** `StreamPipe` slice in this file. |
| `cpu_ipl` | in | 3 | `BackendWiring` passes it through a 2-FF `ASYNC_REG` synchroniser, a "stable for 2 samples" filter, and one more register, then takes the max with the debug-injected level (`top/FullCoreSynth.scala:512-524`). |
| `ipl_ack` | out | 1 | One cycle after an interrupt exception entry is observed at commit (`socket/IplAckPlugin.scala:57-62`). |
| `cpu_peripheral_reset` | out | 1 | High for 518 cycles after a RESET instruction retires (`socket/PeripheralResetPlugin.scala:17,63-71`). |
| `perf_trace` | out | 95 | Only if `PERF_DETAIL_ENABLE=1`. **Absent** in shipping (`:234-235`). |
| `dbg_axi` | slave | AXI4-Lite, 20-bit address, 32-bit data | The debug CSR block (`:236-237,558-577`). |
| `cpu_cold_reset_pulse`, `cpu_cold_reset_hold` | out | 1 each | Debug-controlled cold reset requests (`debug/DebugCtrlPlugin.scala:2116-2120,2383-2384`). |
| `cpu_ram_window_lg2` | out | 6 | Debug register, clamped to 22..30, power-on value 26 (`debug/DebugRegMap.scala:18-20`, `DebugCtrlPlugin.scala:2191-2198`). |
| `cpu_mon_sense` | out | 7 | Debug register, power-on value 6. |
| `init_done_seen` | in | 1 | Sets a sticky bit that STATUS reports (`DebugCtrlPlugin.scala:2325-2328`). |
| `dbg040_*` | out | about 37 signals, about 800 bits in total | Raw core probes (RAS/BTB/FTB/FTQ state, head PC, the stall packs `stallDc/Grant/Exc/Walk`, `macroCountLo`) driven from `socket.core.dbg040` (`:260-320,507-541`). They exist for an ILA. The SoC either consumes them or synthesis trims them. |

Tie-offs inside the top (`:470-498`):

- `iackAvecIn := True` and `iackVectorIn := 0`. Every interrupt is **autovectored**: the socket cannot deliver a vectored IACK.
- The legacy `FetchAlignPlugin.redirect/resume`, `icache.invalidateAll`, and `RobPlugin.flush` inputs are tied off.

---

## 3. Microarchitecture

### 3.1 The 68040 MMU model (`mmu/MmuControl.scala`, `mmu/MmuTypes.scala`)

**Registers** (`MmuControl.scala:113-128`). All are reset to 0 or False:

| Reg | Width | Architectural mapping |
|---|---|---|
| `mmuEnable` | 1 | TC.E (TC bit 15) |
| `pageSize8K` | 1 | TC.P (TC bit 14). All other TC bits are discarded on write and read back as zero (`exception/ExceptionUnit.scala:2589-2590,2622-2625`). |
| `urp`, `srp` | 32 | Stored **unmasked**. No 512-byte alignment is imposed. |
| `itt0/1`, `dtt0/1` | 32 | Stored whole. Only base[31:24], mask[23:16], E[15], S[14:13] and CM[6:5] are interpreted (`MmuTypes.scala:243-263`). |
| `mmusr` | 32 | Written only by PTEST. |

Every register has exactly one `when(set*.valid)` writer (`MmuControl.scala:154-162`).
The Flows are driven from two places:

- the MOVEC commit path in ExceptionUnit (`exception/ExceptionUnit.scala:2622-2645`: Rc 0x003 TC, 0x004-0x007 TTRs, 0x806 URP, 0x807 SRP);
- the debug architectural apply in RobPlugin (`rob/RobPlugin.scala:3312-3322`).

MOVEC reads go through `exception/ExceptionUnit.scala:2582-2601`. Rc 0x805 (MMUSR) is
readable. MOVEC writes to MMUSR fall into the "other Rc: WI" default, so MMUSR is read-only
from MOVEC.

**Virtual address split** (`MmuTypes.scala:44-101`). The root index is VA[31:25] and the
pointer index is VA[24:18], at both page sizes. The page index is VA[17:12] (6 bits) for
4 KB pages and VA[17:13] (5 bits) for 8 KB pages (`pageOffset`, `:99-101`). So a walk
always has **3 levels**: root → pointer → page.

**Descriptor decode** (`MmuTypes.scala:58-85`):

- Table descriptors: resident = UDT bit 1, W = bit 2, U = bit 3. The next-table base is `d[31:4] ## 0000`. It is masked to **16 bytes**, not to the architectural 512/256/128-byte table alignment (`:63`).
- Page descriptors: PDT 00 = invalid, 01/11 = resident, **10 = indirect** (`:66-69`).
- Page attributes: W = bit 2, U = bit 3, M = bit 4, CM = bits 6:5, S = bit 7, PPN = bits 31:12.
- G/U1/U0/UR are not cached. They are used only to compose MMUSR.

**Transparent translation** (`TtMatch.hit`, `MmuTypes.scala:243-252`). A TTR matches when
`E && ((VA[31:24] ^ base) & ~mask) == 0`, and the S field is either `1x` or equal to the
access's supervisor bit. TT0 has priority over TT1 on both sides (`ItlbPlugin.scala:414-415`,
`DtlbPlugin.scala:390-391`). A TTR hit is checked **before**, and independently of,
`mmuEnable` (`ItlbPlugin.scala:1103`, `DtlbPlugin.scala:543`).

**Cache mode** is the 3-valued enum `CacheMode {WRITETHROUGH, COPYBACK, INHIBITED}`
(`cache/IcacheTypes.scala:169-188`). CM `1x` decodes to INHIBITED, `01` to COPYBACK, and
`00` to WRITETHROUGH.

**MMUSR composition** (`MmuSr`, `MmuTypes.scala:124-162`) builds three formats: resident,
not-resident (with a B bit for a descriptor bus error), and transparent (T=R=1, PA=VA).

### 3.2 ATC arrays: `mmu/Tlb.scala`

One `Tlb` component is instantiated per side: ITLB at `ItlbPlugin.scala:185` and DTLB at
`DtlbPlugin.scala:116`. Both use the defaults:

- **Geometry** (`Tlb.scala:30-41`): 32 entries, 4 ways, 2 banks, so 4 sets per bank and 8 rows. VPN[0] selects the bank, VPN[2:1] the set, and the tag is VPN[19:3] (**17 bits**). The lookup is **not a CAM**: one row of 4 ways is read with an 8:1 row mux (`:190-196`) and 4 tags are compared.
- **Tag** = {valid, 17-bit VPN tag, **FC2** (`tagSup`)} (`:169,204`). The page S bit is stored separately as data (`sup`). This lets user and supervisor translations of the same VA coexist.
- **Storage**: everything is in flip-flops. There is no LUTRAM or BRAM. Per entry: tag 17 + PPN 20 + W + S + cmode 2 + M-shadow + tagSup + valid = **44 bits**, so about **1,408 FF per TLB**, plus about 100 FF of fill-pending state (`pend.*`, `:336-347`) and 8 round-robin victim pointers of 2 bits (`:173`) **[derived]**.
- **Lookup** (`:182-236`) is combinational. `io.hit = exactlyOne(hitVec)`: a multi-hot lookup is reported as a **miss** (`:234-235`). `hitEntry = MuxOH(hitVec, entVec)`.
- **Duplicate purge** (`:270-278`): on any multi-hot, every matching way in that row is invalidated.
- **Fill** (`:297-395`) is **registered**. It is captured in the `fillValid` cycle and written from `pend.*` one cycle later, so `io.fillBusy = pend.valid`. On a fill:
  - if the key is already resident, the existing way is replaced in place (`OHMasking.first`);
  - otherwise the round-robin victim of that (bank,set) is used, and the pointer advances only on a real allocation (`:395`);
  - back-to-back fills to the same row or key are bypassed from `pend` (`:351-361`).
- **Invalidate-all** (`:449-451`) clears all valid bits combinationally with priority over fills. A fill offered in a flush cycle is not captured (`:362`).
- **Eviction report** (`:410-446`) is built only when `reportEvictions` is set, which happens only with the ITLB victim buffer. Shipping does not build it.
- **Debug evidence** (`:251-256`): a sticky multi-hot flag, a 4-bit count and the VPN, exported as ports so `DebugCtrlPlugin` can read them.

### 3.3 Table walker: `mmu/TableWalker.scala`

There are **two separate walker instances**, one inside each TLB plugin
(`ItlbPlugin.scala:195`, `DtlbPlugin.scala:126`). There is also a **third, independent
search** used only by PTEST, which lives inside the ExceptionUnit (§3.7). Each walker is
single-outstanding.

FSM `IDLE → RD_ROOT → RD_PTR → RD_PAGE → FINISH → IDLE` (`:216-393`):

- `IDLE` on `start` (`:245-261`): latches `WalkReq` (`vpn`, `rootPtr`, `isWrite`, `isSuper`, `is8K`) and sets `descAddr = rootPtr + rootIdx*4`.
- Each level calls `issueRead()` (`:226-231`). It holds a `DLoadCmd` (LONG, `vaddr=paddr`, `ooOk=False`, `lineOnly=False`) until accepted; `cmdSent` makes it single-shot.
- **Line-straddle split** (`:120-123,185-203`): if `descAddr[3:0] > 12`, the read becomes four BYTE loads that are assembled big-endian. Only the root level can reach this, through an unaligned URP/SRP, because the deeper bases are 16-byte aligned by `tblNextBase`.
- Table levels: a non-resident descriptor ends the walk with a fault. A bus error (`loadRsp.fault`) ends it with `NON_RESIDENT` (`:239-243`). W accumulates down the levels.
- Leaf (`:324-387`):
  - invalid **or indirect** PDT → fault `NON_RESIDENT` (indirect descriptors are **not supported**);
  - write to a W-protected page → `WRITE_PROTECT`;
  - S page accessed from user mode → `SUPERVISOR`.
  - It computes `newByte = low | U | (isWrite ? M : 0)` and sets `umWrite.valid` when the access does not fault and the byte changes (`:351-358`). It records `modified = newByte(4)` (`:376`) and `umWrite.addr = descAddr + 3`, the big-endian low byte (`:383-384`).
- `FINISH` pulses `done` (`:389-392`). The result is held in registers until the next walk.
- `batchMode` (`:53,139-141,207-214,275-279,303-307,366-371`) records root and pointer U bits and a self-reference OR. It is **constructed with the default `false` everywhere**: both instantiations call `new TableWalker()`. With it off, `umBatch` has no consumer.

The descriptor cache mode and the load token are not set by the walker. The `LsEuPlugin`
arbiter overwrites them:

- the cache mode is `CACR.DE ? WRITETHROUGH : INHIBITED` (`execute/LsEuPlugin.scala:681-689`);
- the token is a reserved constant per walker: `WALK_ITLB` or `WALK_DTLB` (`execute/LsEuPlugin.scala:5460-5461`).

### 3.4 ITLB plugin: `mmu/ItlbPlugin.scala`

**Response mux.** The ITLB answers in the same cycle (`:1103-1134`), with priority:

1. ITT hit: ppn=vpn, cache mode INHIBITED or WRITETHROUGH. COPYBACK collapses to WRITETHROUGH on the I-side.
2. `!mmuEnable`: identity, WRITETHROUGH.
3. TLB hit: entry PPN and cmode; fault if a supervisor page is fetched in user mode.
4. `latchMatch`: the single-entry walk-result latch (`:492-508`). It serves faulting walks, which never fill the array, and the cycle during which the fill is still in `pend`.
5. Otherwise `rsp.ready := False`.

`IcachePlugin` folds `xlate.rsp.ready` into `cmdPort.ready`
(`cache/IcachePlugin.scala:1842`), so **an ITLB miss stops fetch** until the walk returns.
`req.vpn` is driven from `cmdPort.payload.pc` (`:226`), so the full path
`fetch PC → tlbKey → row mux → tag compare → exactlyOne → MuxOH → PA` sits inside the
I-cache command cycle. The 8 KB PA is assembled in the I-cache as
`ppn[19:1] ## pc[12:0]` (`IcachePlugin.scala:342`).

**Keys** (`:391-397`):

- The array key is `tlbKeyOf` = vpn, or `vpn >> 1` at 8 KB, so both banks stay usable at 8 KB.
- The walk key is `walkKey`, which masks VPN[0] at 8 KB.

**Miss path:**

- `needWalk` (`:559-563`) = `mmuEnable && req.valid && !tlbHit && !latchMatch && !ttHit && !walker.busy && !walker.done && !fillBusy && !atcFlush`.
- The capture into `missReqReg` (`:649-657`) also requires `!umQueueFull`.
- `missReqReg.valid` is a one-cycle pulse that **is** `walker.io.start` (`:671`). The root is chosen live at start: `Mux(sup, srp, urp)` (`:667`).
- On `done` (`:729-758`): the latch is loaded unless the walk is flush-poisoned. The array is filled only if the walk did not fault and none of these is set: `walkUmPoison`, `walkFlushPoison`, `atcFlush`, `rootChanged`.

**Flush.** `atcFlush` (`:342`) = `flushAll` (PFLUSH*), OR a TC.P change, OR a TC.E change,
OR any URP/SRP write, OR an ITT0/ITT1 write. It:

- invalidates the array (`:426`);
- clears the latch (`:843-845`);
- poisons an in-flight walk (`flushPoisonArm`, `:841-848`).

`umFlush` (pipeline flush) poisons only the U-write (`walkUmPoison`), drops the latch
(`:833-835`), and causes a poisoned latch to expire after one cycle (`:827-828`).
The D-side `walkFlushPoison` and `walkUmPoison` latches clear when the captured miss
starts its walk, before any walker result can be consumed. The flush/squash set arms
have priority on a coincident launch. Clearing at launch removes the combinational
current-request TLB hit/permission cone from these latch inputs while preserving the
per-walk poison lifetime.

**U-bit write-back** (`:853-1068`):

- `UmWriteQueue(4)`. Entries are allocated **pre-committed** (`:882-883`) because a fetch has no robId.
- The drain is a read-modify-write through the walker's own load port: a BYTE re-read, OR with `0x08` (the U bit only; `UmSetMask`, `:944`), then a strobed one-byte `DStoreCmd` on a 16-byte-aligned 128-bit `lineData` (`:1038-1063`).
- While the drain owns the port, walker reads and responses are masked (`:1001,1021,1026`).
- A fault on the drain read drops the entry (`:1032-1037`).

**Victim buffer** (`:441-489,706-727,790-825`): fully associative, FIFO, probed only on an
array miss, and promoted back by a fill. **Off in shipping** (`itlbVictimEntries=0`, from
`top/ShippingCoreConfig.scala:360-370`, which reads env `ITLB_VICTIM`). With 0 entries,
`vic.enabled=false` and no hardware is generated.

### 3.5 DTLB plugin: `mmu/DtlbPlugin.scala`

- **The response is registered** (`:413-430`). `rspValid`/`rspPayload` form a one-deep elastic buffer: `rsp.valid = rspValid && !atcFlush`.
- `req.ready = !missPending && (!rspValid || rsp.ready) && !atcFlush && !fillBusy` (`:428`). **The DTLB blocks while a walk is in progress**: it accepts no request, not even one that would hit, until `walker.done`.
- On `req.fire` (`:543-572`): `directIdent = ttHit || !mmuEnable`.
  - PPN = vpn on direct, otherwise the entry PPN.
  - Cache mode = the DTT's full 3-way CM, WRITETHROUGH when the MMU is off, or the entry cmode.
  - Fault = `!directIdent && ((write && W) || (S && !super))` (`:410-411,520-522,554`).
  - It responds immediately on a direct hit, or on a TLB hit that does **not** need an M refresh.
  - Otherwise it latches `missReqReg` (vpn as walk key, write, sup, is8K, token, robId, preCommitted).
- **M refresh** (`:522`): `needsMRefresh = tlbHit && !fault && write && !entry.modified` forces a re-walk so that M gets set in memory. The re-walk ends in a replace-in-place fill.
- **Walk start**: `walker.io.start := missReqReg.valid && !umQueueFull && !atcFlush` (`:599`). The root is chosen live at start (`:595`).
- **Completion** (`:644-693`): the response is loaded from the walker (PPN, cmode, fault, token). `rspValid` is set unless the walk is flush-poisoned. The array fill uses the same gates as the ITLB.
- **Flush** (`:282,715-724`):
  - `atcFlush` uses the same terms as the ITLB, but with the **DTT** pair instead of the ITT pair.
  - It kills a pending registered response.
  - It drops a `missReqReg` that has not started. **That request then gets no response** (see §7, O-6).
  - It poisons a walk that has started.
- **U/M write-back** (`:735-947`): same structure as the ITLB, with three differences:
  - the mask is `0x18` (U and M) (`:814`);
  - entries carry the **robId** of the access (`:750`) and become drainable only when the ROB retires that robId; `umFlush` (`doFlush`) discards uncommitted entries (`mmu/UmWriteQueue.scala:186-192`);
  - `umAccessPreCommitted = excActive`, so ExceptionUnit translations are born committed.
- The drain's store latches the cache mode at arming (`latchDrainCmode=true`, a constant, `:38,934`).

### 3.6 `UmWriteQueue` and `UmWalkBatchQueue`

`UmWriteQueue(depth=4, retireWidth)` (`mmu/UmWriteQueue.scala:57-253`):

- Four slots, each holding `valid`, `committed`, robId, address, byte, and a commit sequence number.
- Commit notices arrive from retire lanes 0/1, plus lanes 2 and up through `commitExtra`. Duplicate robIds within a cycle are suppressed (`:145-167`).
- The drain offers the **oldest-committed** slot, chosen by an age compare on `nextSeq - commitSeq` (`:195-205`), and holds it until `drainAck` (`:206-221`).
- `full` = all four slots valid (`:141`). That gates walk starts in both TLBs.
- No same-address merge or interlock exists. A sim-only assertion checks that a new byte is a superset of an already-queued byte to the same address (`:240-252`).

`UmWalkBatchQueue` (`mmu/UmWalkBatchQueue.scala`, 320 lines) is **not instantiated anywhere
under `src/main`**. Only `src/test/.../UmWalkBatchQueueSpec.scala` uses it. It is dead in
the shipping build. As a consequence, the root and pointer table descriptors' **U bits are
never written** in shipping: only the leaf page descriptor's U/M byte is (see O-3).

### 3.7 PFLUSH, PTEST and MOVEC

- **Decode** (`decode/OperationDecoder.scala:1251-1290`): opwords `0xF500-0xF51F` with mode ≤ 3 (PFLUSHN(An), PFLUSH(An), PFLUSHAN, PFLUSHA) **all decode to `SysKind.PFLUSHA`**. PTEST (`0xF548/0xF568 + An`) decodes to `SysKind.PTEST`.
- **PFLUSH\***: at commit `S_APPLY` pulses `sysFlushAllValid` (`exception/ExceptionUnit.scala:2715-2718`). This becomes `flushAll` on both TLBs, which runs `atcFlush`: a full invalidate of both arrays, the ITLB latch, the DTLB pending response, and in-flight walks. It does not touch the U/M queues.
- **PTEST** (`exception/ExceptionUnit.scala:2720-2765`, FSM `S_PTREQ`/`S_PTWAIT` at `:2993-3045`):
  - The function code comes from **DFC**. FC2 selects SRP or URP. FC[1:0]=10 selects the ITT pair, otherwise the DTT pair.
  - TTR hit → `MmuSr.transparent`. MMU off → `(VA & pageMask) | R`.
  - Otherwise it does its own three-level search, issuing one LONG load per level through the exception unit's D-cache port. It writes MMUSR as resident, not-resident (W accumulated), or bus-error.
  - It does **not** fill the ATC and does **not** set U/M. PTESTR and PTESTW behave the same.
  - It reuses `MmuDesc.rootOffset/ptrOffset/pageOffset` from `MmuTypes.scala:94-101`. It does **not** reuse the walker's straddle split.
- **MOVEC**: covered in §3.1. A write to TC/URP/SRP/a TTR shows up as `set*.valid` in that cycle, which the TLBs turn into `atcFlush`.

### 3.8 Walker ↔ D-cache arbitration, and store ordering (in `LsEuPlugin`, outside this slice)

This part is outside the slice but determines MMU behaviour.

- **Load side** (`execute/LsEuPlugin.scala:585-622,5512-5541`):
  - Each walker's `walkLoadCmd` goes through an `m2sPipe` (one register stage).
  - Ownership is `ldOwner` ∈ {CORE, ITLB, DTLB}. A grant needs: owner = CORE, no exception load busy, no `quiesceHold`, room in the response FIFO, and no D4 fence (`d4WalkerFence`, live because `inhibitedFullBarrier=true`, `:3955-3974`).
  - **A walker is a candidate only while the core LS pipe is not requesting the load port, or after `walkerAgeLimit` (default **64**, `:133`) cycles of waiting** (`:5514-5525`).
  - ITLB and DTLB alternate round-robin.
  - Ownership returns to CORE once the command is accepted (`:5533-5540`). So **every descriptor level re-arbitrates**.
- **Store side** (`:5557-5571`): a walker store is granted only when there is no outstanding core store, no store firing, and no exception store.
- **Wedge detector** (`:5654-5689`): if a grant is held without progress for `walkerWedgeLimit` = 2^20 cycles, `walkerPortWedge` latches and halts the core with `WALKER_PORT_WEDGE`.
- **Ordering against older stores: none.** `DtlbPlugin` starts a walk with no reference to the store queue (`:599`), and `ldGrantOk` (`LsEuPlugin.scala:5527-5530`) has no SQ or ROB-age term. A speculative DTLB walk can therefore read a descriptor before an older program-order store to that descriptor has executed, committed, or drained. Two RED corpus programs reproduce this: `src/test/resources/m68kooo-ported-tests/asm/walk_vs_older_store_uncommitted.s` and `walk_vs_older_store_slow.s`. This document found **no fix** in the code (see O-1).

### 3.9 Socket glue

- **Reset-vector FSM** (`socket/ResetVectorFsm.scala:75-151`), states `REQ → WAIT → SWEEP_WAIT → APPLY0 → APPLY1 → DONE`:
  - It issues **one AR at physical address 0** (`arAddr := 0`, `:102`) through `AxiDMerge`'s reset-vector owner.
  - It extracts SSP from line bytes 0..3 and PC from bytes 4..7 (`DcacheByteLane.extract`, `:129-130`).
  - It waits for `RegFilePluginInt.logic.initDone` (`ResetVectorPlugin.scala:60`), writes SSP into the committed physical A7 through BackendWiring's A7 write port (`top/FullCoreSynth.scala:726-731`), and then redirects fetch (`ResetVectorPlugin.scala:62-63`).
  - A non-OKAY `rresp` pulses `haltPulse`, which produces HaltReason `RESET_VECTOR`.
  - `DONE` is terminal until the next core reset. The MMU and TTRs are bypassed because the address goes straight to AXI.
- **IPL / IACK**: the synchroniser is described in §2.3. `IplAckPlugin` registers `exc.obsFire && obsIsEntry && obsIsInterrupt` (`IplAckPlugin.scala:57-58`). A sim-only assertion cross-checks it against `commitObs(2)` (`:104-116`). `AUTOVECTOR_BASE=25` and `IACK_AVEC_TIEOFF=true` are constants (`:13-14`).
- **Peripheral reset** (`PeripheralResetPlugin.scala:47-73`): a down-counter loaded to 518 on `exc.resetInstrRetire`. `gateDispatch` must be false (`require`, `:51-54`). This is output only: it does not stall the core.
- **Halt reasons** (`socket/HaltReason.scala:32-89`) are 3-bit codes 0-6. The priority mux is in `top/FullCoreSynth.scala:610-631`: `DCACHE_DIAG > FS_XLATE > RESET_VECTOR > ARBITER_WEDGE > WALKER_PORT_WEDGE > DOUBLE_FAULT`. Every code has a live producer.

### 3.10 Debug block (`debug/*`)

**Clocking and reset.** `DebugCtrlPlugin` runs its CSR area in `dbgCd` (`:150-171,2399`):
same clock, synchronous reset driven by `!por.done`, and the POR counter (`porCycles`=16)
sits in a BOOT-reset domain. As a result, **debug state survives the CPU reset**: CPU reset
events are only counted (`cpuResetCount`, `:2312-2324`). While in POR the block refuses
AXI (`ready` is gated by `!dbgRst`, `:1098-1100`).

**AXI-Lite protocol** (`:1098-1200`):

- AW and W are latched independently and combined in `doWrite`. B is always OKAY.
- Reads: AR → `arPend` → `doRead`, which captures six registered partial read words (`rdCore/Lane/Count/Arch/Live/Head`) → `readStage2`, which ORs them or picks a history/live-integer word → `rPend`. That is at least **3 cycles per read** **[derived]** with one read outstanding.
- Each readable offset owns a one-hot `selected` flop that is set at AR acceptance (`ReadRegion.at`, `:1715-1727`). The data is `andMask` plus an OR tree.
- **Unmapped addresses read 0 with OKAY**. Writes to unmapped or read-only addresses are silently ignored. There is no SLVERR anywhere.

**Build levels.** `stage` sets which features exist. **Shipping `M68kSocketTop` uses
`debugStage=5`** (the constructor default at `top/SocketTop.scala:61`; `GenSocketTopVerilog`
never overrides it). `FullCoreSynth` defaults to stage 2.

| Stage | Adds (code gates) |
|---|---|
| ≥1 | VERSION, BUILD_ID, FEATURES, CONTROL (halt/step/cold-reset), STATUS, RAM window, mon-sense, DBG_RESET_CTL, cycle counter (64-bit), IRQ inject, stall packs, multi-hot evidence, MMU probe counters |
| ≥2 | Run control through `DebugCommitService` (stop, resume, step, clear-sticky), halt-after-N-instructions (64-bit target + epoch), PC/last-PC/macro-count, exception count, **18 performance counters** (`perfBuilt`, `:581-869`) |
| ≥3 | Architectural shadow, 32×32-bit (`archShadow` + `archDirty`), the **apply** sequencer that writes the integer PRF, NZVC/X, and system/MMU registers while halted (`:1370-1400,1550-1673`); live-integer register read through a dedicated PRF read port (`:83-108,1469-1493`); live system/MMU register reads (`readLive`, `:2004-2022`); history rings (`historyDepth`=32) |
| ≥4 | Debug D-cache/I-cache maintenance ops (`OFF_DCACHE_OP/ICACHE_OP`, `:1504-1548`) |
| ≥5 | 4 PC breakpoints with skip-once, matched in `DecodeStage` (`decode/DecodeStage.scala:210-213,3155-3161`); a 256-bit halt-on-exception-vector mask (`:1337-1338`); A7-odd halt control; **PC-range halt** (only if `pcRangeEnable`); fetch-word checker (`FetchWordCheck`) |

**`CPU_DEBUG_PROFILE`** (`top/SocketTop.scala:631-637,672-673`) affects **only**
`pcRangeEnable`: `full` → true, `reduced` → false. The debug stage, the history rings, the
performance counters and the A7-odd lane are the same in both profiles. What `full` adds:

- In `RobPlugin` (`rob/RobPlugin.scala:4024-4059`), `pcRangeLane`:
  - **four 32-bit magnitude comparators** on the retire-slot PCs (`p0.pc`, `p1.pc` against lo/hi);
  - registered hit and lane, two "last PC" registers, three captured PCs, a 16-bit count, and the sticky `req` flop that feeds the retire-stop terms (`:1176,1487,1515,1582,1627,1732`).
- In `DebugCtrlPlugin`: three 32-bit configuration registers and seven read arms (`:1343-1345,1826-1837,2175-2183`).
- **[estimate]** about 250-400 LUT and about 290 FF: 96 FF of configuration, 160 FF of captured PCs, 16 FF of count, a few control flops, roughly 100 LUT for 4 carry-chain compares, plus the last-PC muxes and the read OR-tree inputs.

**History rings** (`:304-356`), built at stage ≥3 with `historyDepth=32`:

- **PC trace**: 32 entries × 32 bits, split into `pcBanks` banks (the macro-retire width, 2 by default) so several retired PCs can be written per cycle.
- **Branch ring**: 32 × 128 bits {pc, nextPc, taken, mispredicted, type}.
- **Exception ring**: 32 × 128 bits {vector, exceptionPc, faultAddress, handlerPc}.
- All are `Mem` with `ram_style="block"`.
- The rings keep writing while the CPU runs. Reads are `readSync`.
- **Tearing mitigation** (`:1067-1096`): reading word 0 of a ring entry snapshots all 128 bits. Words 1-3 of the **same index** are then served from that snapshot. A reader that reads words 1-3 without first reading word 0, or reads them in another order, can still see a torn entry.

**Performance counters** (`:581-869`), at stage ≥2 (live in shipping):

- 64-bit `cycle` and `inst` counters.
- 32-bit counters for: mispredict, flush, branch, mispredict-uncond, mispredict-cond, DC miss, IC miss, IC miss sequential, IC prefetch issued/used/late, DTLB walk, ITLB walk, stall-retire (ROB not empty and no retire), stall-DC, stall-walk (either walker busy).
- A walk is counted on the rising edge of `!walker.dbgPack(0)`, that is, "not IDLE" (`:793-794,822-823`).
- `PERF_CTL` provides run and clear. `detailedPerf` counters are not built in shipping.
- Every event is `RegNext`-tapped (`perfTap`, `:611-612`), so the counters add load but no logic level to source nets.

**Other probes:**

- Stall packs (`:468-488`): AxiDMerge, D-cache, LSU grant, exception, and walkers (`itlb.dbgPack ## dtlb.dbgPack`), all `RegNext`'d.
- Multi-hot evidence for I-cache, D-cache load/probe/store, ITLB and DTLB (`:499-532`).
- MMU probe counters: rekey (TC.P change) and flush-poison per side, saturating (`:936-1009`).
- VIO (`VioProbePlugin`): **not in the `M68kSocketTop` plugin list**. Only `GenFullCoreSynthVioVerilog` enables it (`top/FullCoreSynth.scala:932,1000-1005`).

**Register map.** `debug/DebugRegMap.scala:24-253` defines the offsets.
**Offsets defined but implemented nowhere** (no read or write arm in `DebugCtrlPlugin`, no
reference elsewhere; checked by grepping every `OFF_*` name):

- `REDIRECT_PC`, `REDIRECT_TRIGGER`, `RESET_CAUSE`, `HALT_EXC_VEC`, `PC_MISALIGNED_PC`;
- `WP0_*`, `WP1_*`, `WP_HIT*` (data watchpoints);
- `AT0_*`, `AT1_*`, `AT_SKIP_ONCE`, `AT_HIT*` (A-trap breakpoints);
- `DCACHE_PROBE_*`;
- `WEDGE0-3`;
- `FAULT_SNAP_*`, `RTS_SNAP_*`.

These reads return 0. The `features` table puts watchpoints, A-traps, fault-snap, rts-snap
and trace-trigger at stages 6 or 9 (`:490-517`), so a stage-5 build does not advertise
them, and `dcache_probe` is explicitly listed as unavailable (`DebugCtrlPlugin.scala:217`).
**In this tree, data watchpoints do not exist in RTL.**

`HaltAfterLoadGuard` is a one-line predicate used by `RobPlugin`. `FetchWordCheck`
(`debug/FetchWordCheck.scala`) compares the first decoded word at a configured PC against
an expected value, as a fetch-corruption tripwire. `DbgAxiLite` is a plain bundle.

### 3.11 Top-level assembly

**`M68kSocketTop`** (shipping, `top/SocketTop.scala:90-205`) builds the plugin list. It
mostly uses `ShippingPlugins` factories:

- `MmuControlPlugin`, `FpuControlPlugin`, `InterruptControlPlugin`;
- `ShippingPlugins.itlb()` (= `ItlbPlugin(victimEntries=ShippingCoreConfig.itlbVictimEntries)`, `top/ShippingPlugins.scala:82`) and a default `DtlbPlugin()`;
- `ShippingPlugins.icache`, `ShippingPlugins.dcache(socketMerged=true)`;
- BTB, FTB, RAS, gshare, fetchAlign, decode, rename, dispatch, ROB and IQ (with `loadBypassUnreadyLoad = LS_OOO_ISSUE`, env `LS_OOO_ISSUE`);
- the five EUs, the five register files;
- `AxiDMergePlugin`, `ResetVectorPlugin(enable=true)`, `IplAckPlugin(enable=true)`, `PeripheralResetPlugin(enable=true)`;
- `BackendWiringPlugin(debugStage=5)` (defined in `FullCoreSynth.scala`);
- `DebugCtrlPlugin(stage=5, pcRangeEnable=…)`.

`GenSocketTopVerilog` (`:639-743`) reads `DBG_BUILD_ID`, `PERF_DETAIL_ENABLE`,
`CPU_IPC_PROFILE` (default `throughput-v2`, so ipcThroughput and lateStore are true) and
`CPU_DEBUG_PROFILE` (default `full`), and prints the `SHIPPING_CONFIG` line.

**`BackendWiringPlugin`** (`top/FullCoreSynth.scala:28-794`) is shared by every full-core
target, including shipping. It wires:

- front-end flushes and redirects;
- predictor invalidation (BTB/FTB/RAS/gshare invalidate on any I-cache invalidate);
- EU→ROB completions and IQ wakeups;
- the SQ commit;
- MMU hooks (§2.1), IPL (§2.3), and the halt-reason mux;
- the exception unit's D-cache, DTLB and store paths, and debug maintenance;
- the A7/NZVC/X/FPCC committed-write ports.

It also emits `eu0Res/eu1Res/traceOut/fireOut` as `out` ports of `M68kCore`
(`:779-787`). In `M68kSocketTop` nothing connects to those ports, so synthesis trims them.

**`GenFullCoreSynthVerilog.buildWith`** (`:826-936`) is the OOC "full core" target. It
does **not** use `ShippingPlugins`:

- `LsEuPlugin`, `IssueQueuePlugin` and `FetchAlignPlugin` are built from their own arguments and from env flags `LS_SPEC_WAKE`, `LS_EARLY_AN` and `IQ_LOAD_BYPASS`;
- `IcachePlugin()` and `DcachePlugin()` use defaults;
- there is no `AxiDMergePlugin`, `ResetVectorPlugin`, `IplAckPlugin` or `PeripheralResetPlugin`;
- `DebugCtrlPlugin` uses stage 2 by default.

Variants: `…Stage3/4/5Verilog`, `…VioVerilog` (adds VIO), and `…NoDebugVerilog`
(`debugEnable=false`, `:972-1020`).

**Other generators** (`top/GenVerilog.scala`): `GenVerilog` (a hello core),
`GenSynthVerilog` (front end + ROB + IQ with identity translation), `GenBackendSynthVerilog`,
`GenIqSynthVerilog`, `GenPrfSynthVerilog`, `GenFpuCoreSynthVerilog`.

**Synthesis probes.** `SynthProbePlugin`, `IqSynthProbePlugin` and `PrfSynthProbePlugin`
(`top/SynthProbePlugin.scala`, `top/ExecSynthProbes.scala`) register every boundary input
and output so a partial design keeps its logic and has flop-to-flop timing.
`verif/TraceReplay.scala` is a one-register `CommitTrace` pass-through component.

---

## 4. Configuration knobs

| Knob | Where | Default | Shipping | Effect / notes |
|---|---|---|---|---|
| `Tlb(entries, ways, banks)` | `mmu/Tlb.scala:30-33` | 32 / 4 / 2 | 32 / 4 / 2 (both sides) | `TlbEntry.vpnTag` width is computed from the **defaults** (`:22`), so any other geometry breaks `e.vpnTag := t` (`:206`). See O-10. |
| `ItlbPlugin.victimEntries` | `ItlbPlugin.scala:133`; env `ITLB_VICTIM` (`top/ShippingCoreConfig.scala:360`) | 0 | **0** | 0 = no hardware; 2..64 = victim buffer. |
| `ItlbPlugin.captureGateOnFlush` | `:132` | true | true | Constant. `false` exists only as a negative control for a test. |
| `DtlbPlugin.latchDrainCmode` | `DtlbPlugin.scala:38` | true | true | Constant. Same purpose as above. |
| `TableWalker.batchMode` | `TableWalker.scala:53` | false | **false** | Root/pointer U-bit batching. Not wired up. |
| `UmWriteQueue(depth)` | `ItlbPlugin.scala:854`, `DtlbPlugin.scala:736` | 4 | 4 | |
| `Tlb.reportEvictions` | `Tlb.scala:69` | false | false (`victimEntries>0` only) | |
| `LsEuPlugin.walkerAgeLimit` | `execute/LsEuPlugin.scala:133` | 64 | 64 | Starvation bound for a walker against core loads. |
| `LsEuPlugin.walkerWedgeLimit` | `:134` | 2^20 | 2^20 | Halt detector. |
| `inhibitedFullBarrier` (D4) | ShippingCoreConfig | — | true | Adds `d4WalkerFence` to the walker grants. |
| `M68kSocketTop.debugStage` | `top/SocketTop.scala:61` | 5 | **5** | Not settable from the environment. |
| `pcRangeEnable` | `CPU_DEBUG_PROFILE` | full → true | **true** | The only thing the debug profile controls. |
| `detailedPerf` | `PERF_DETAIL_ENABLE` | 0 | 0 | Detailed ROB/dispatch counters and the `perf_trace` port. |
| `DebugCtrlPlugin.historyDepth` | `:63` | 32 | 32 | 0 or 32 only. |
| `DebugCtrlPlugin.porCycles` | `DebugRegMap.POR_CYCLES_DEFAULT` | 16 | 16 | |
| `PeripheralResetPlugin.holdCycles` | `:17` | 518 | 518 | |
| `IplAckPlugin.enable` / `ResetVectorPlugin.enable` / `PeripheralResetPlugin.enable` | | false | true (SocketTop) | |
| `VioProbePlugin.enable` | | false | not instantiated | |

**Dead or unwired code in this slice (shipping build):**

- `UmWalkBatchQueue` (whole file), `WalkUmBatch`/`WalkUmElement`, and `TableWalker.batchMode` logic: `rBatch*` and `ownBytes` have no consumer, so synthesis removes them.
- `WalkRsp.faultReason` / `MmuFaultReason`: computed by the walker, read by neither TLB plugin (both use only `rsp.fault`).
- `TlbEntry.vpnTag`: driven to 0 on every fill (`ItlbPlugin.scala:688`, `DtlbPlugin.scala:624`) and never stored, because the arrays use `pend.tag`.
- `rootChanged` (one flop per side): the code makes it a strict subset of the `atcFlush`/`walkFlushPoison` mechanism. `rootWrite` feeds `atcFlush`, and that already poisons a started walk.
- ITLB victim-buffer RTL (off).
- `IdentityTranslationPlugin`, `DIdentityTranslationPlugin`, `VioProbePlugin`: not in shipping.
- The `DebugRegMap` offsets listed in §3.10, which have no implementation.
- `BackendWiringPlugin` commit-trace `out` ports: trimmed in SocketTop.

---

## 5. Performance-relevant behaviour

**TLB hit cost.**

- ITLB: zero added cycles, because it is combinational inside the I-cache command cycle.
- DTLB: one registered stage (`rspValid`) between `req.fire` and `rsp.valid`. Whether that cycle overlaps other LSU work is up to `LsEuPlugin`.
- `fillBusy` withholds both TLBs for **one cycle after every fill** (`DtlbPlugin.scala:428`, `ItlbPlugin.scala:560`).

**Walk latency [derived from the code; D-cache hit latency H is outside this slice].**
For the DTLB:

| Step | Cycles |
|---|---|
| `req.fire` → `missReqReg.valid`, which with `umQueueFull=0` is also `walker.start` | 1 |
| `IDLE → RD_ROOT` | 1 |
| Per level: `issueRead` → `m2sPipe` stage → `ldGrantOk` sets `ldOwner` → command accepted → response after H → `descRspValid` → next state | about 3 + H |
| `FINISH` → `donePulse` → `rspValid` | about 3 |

That gives roughly **5 + 3·(3 + H)** cycles if the walker wins arbitration at once, about
20 cycles for H = 2. A user that needs a further hit pays one more cycle (`fillBusy`).

The real cost is usually higher because:

1. **Each level re-arbitrates**, and **a walker loses to any pending core load for up to 64 cycles per level** (`LsEuPlugin.scala:5514-5525`). In the worst case a walk can wait about 192 cycles on arbitration alone.
2. The `d4WalkerFence` blocks grants while an inhibited access is at the ROB head.
3. A D-cache miss on a descriptor costs a full line refill. Descriptors are read WRITETHROUGH (cacheable) when CACR.DE=1, and INHIBITED otherwise.
4. `umQueueFull` delays the start. The queue drains one entry per committed walk, each needing a byte read plus a store grant that waits for `coreStOutstanding == 0`.

**Blocking behaviour.**

- **The DTLB is blocking**: `req.ready` includes `!missPending`. With LS-OoO issue on, a single DTLB miss stalls **every** later translation, hits included, until the walk ends.
- An ITLB miss stalls fetch by design.
- The two walkers can run concurrently, but they share the one D-cache load port.

**Over-invalidation.** Every one of these empties the side's whole ATC: PFLUSHN/PFLUSH(An)
(all decode to PFLUSHA), any URP/SRP write, any TTR write, and a TC.E or TC.P change.

**M refresh.** The first write to a page whose cached entry has M=0 costs a full walk plus a
U/M drain. That holds even when the page is already in the TLB, because `needsMRefresh`
forces it.

**Counters that measure this.** `PERF_DTLB_WALK` and `PERF_ITLB_WALK` (0x010A0/0x010A4)
count walk starts. `PERF_STALL_WALK` (0x010B0) counts cycles in which either walker is
non-IDLE. `STALL_WALK` (0x01028) gives the live state of both walkers' FSM and port.

---

## 6. Area and timing notes

- **TLB storage [derived]**: 2 × (32 × 44) ≈ **2,816 FF** of ATC state, plus about 200 FF of fill-pending state and victim pointers. Both arrays are flops. That is a deliberate choice for a single-cycle combinational ITLB (LUTRAM would also be single-cycle, but would cost a read port per way).
- **Lookup cone [estimate]** per TLB, per way: an 8:1 row mux over 44 bits (about 2 LUT6 + F7/F8 per bit), a 17+1-bit equality, `exactlyOne(4)`, and a 4:1 `MuxOH` over about 26 payload bits. Roughly **400-500 LUT per TLB**.
  - **ITLB**: this cone lies between `IcachePlugin.cmdPort.payload.pc` and `cmdPort.ready` / the PA tag compare. `is8K` (a control register) selects the key at the front of the cone (`tlbKey`), and `mmuEnable`/ITT matching selects the arm at the end.
  - **DTLB**: the cone ends at `rspPayload` and `missReqReg` registers.
- **Fill path**: already registered (`pend.we` one-hot as the array CE, `Tlb.scala:336-391`). The fill decode drives about 70 `pend` D-pins instead of about 1,400 CE pins.
- **Walkers**: about 200 FF each (`reqReg`, `descAddr`, result regs, `rBatch*` before trimming) and a 32-bit adder per level-address computation **[estimate]**.
- **UmWriteQueue**: 4 × (32 address + 8 byte + robId + sequence) ≈ 200 FF per side. The drain path adds a 128-bit `drainBeatReg` and a 16-bit strobe per side (`ItlbPlugin.scala:934-935`, `DtlbPlugin.scala:784-785`). That is **288 FF of mostly-zero data**: only one byte lane is ever non-zero.
- **Debug at stage 5 [estimate, from register declarations]**:

  | Item | FF |
  |---|---|
  | `archShadow` 32×32 | 1,024 |
  | `archApplyShadow` 32×32 | 1,024 |
  | `haltExceptionMask` | 256 |
  | Perf counters (16×32 + 2×64) plus cycle (64) | 704 |
  | MMU counters | 96 |
  | Multi-hot addresses and status | 224 |
  | Stall packs | 160 |
  | Read stage `rd*` and `rData` | 224 |
  | Breakpoints 4×32 in the debug block, plus a **duplicate** 4×32 copy in DecodeStage | 256 |
  | `selected` read-decode flops (one per readable offset, about 180) | about 180 |
  | Halt-after (64 + 8), PC-range (§3.10, about 290), FetchWordCheck (about 200), A7-odd lane (in RobPlugin, always built) | — |
  | **Total** | **about 4,500-5,000 FF** |

  The rest is a few thousand LUT of read OR-trees and increments.
- **Debug memories**: the history rings are declared as BRAM. The two 128-bit × 32 rings need at least 2 RAMB18/RAMB36 each at 128-bit width, and the PC trace adds 1-2 more. That is about 4-6 block RAMs **[estimate; depends on Vivado mapping]**.
- **Debug logic levels.** Almost every debug tap is `RegNext`'d before use (`perfTap`, the stall packs, multi-hot, MMU pulses). The read path is two register stages. So the debug block adds **fanout** to core nets but very little depth. The exceptions that sit on core control cones:
  - the PC-range `req` flop and the A7-odd stop request are ORed into the retire-permit terms (`rob/RobPlugin.scala:1487,1515,1582`);
  - breakpoint matching sits in DecodeStage's push stage (`decode/DecodeStage.scala:3155-3161`), as 4 × 32-bit equality per uop lane;
  - `Tlb`'s multi-hot evidence latch adds load to `hitVec`.

---

## 7. Observations

Each item is factual and cites code. Severity is the author's judgement.

**O-1. A speculative DTLB walk is not ordered against older stores (correctness, open).**
`walker.io.start := missReqReg.valid && !umQueueFull && !atcFlush`
(`mmu/DtlbPlugin.scala:599`). The walker load grant has no SQ or ROB-age term
(`execute/LsEuPlugin.scala:5527-5530`). A younger load's walk can read a descriptor that an
older, not-yet-drained store is rewriting. It then installs the stale translation, and the
load commits through it. The two RED reproducers are in
`src/test/resources/m68kooo-ported-tests/asm/walk_vs_older_store_*.s`. No fix exists in
this tree. A cheap, conservative option: launch DTLB walks only when the access is at, or
next to, the ROB head with an empty SQ. That is the "hot fast, cold slow" approach: walks
are about 12 per kinst, not on the hot path.

**O-2. The U/M descriptor update is a non-atomic read-modify-write (possible lost update; needs verification).**
The drain re-reads the descriptor byte through the load port, ORs U/M into it, and writes
the byte later through a separately arbitrated store grant (`DtlbPlugin.scala:842-939`,
`ItlbPlugin.scala:980-1064`). Between the read response and the store grant,
`stGrantOk` (`LsEuPlugin.scala:5557-5560`) only requires that no core store is outstanding
*at grant time*. This review found nothing that stops a core SQ store to the **same byte**
from draining in that window, for example an OS clearing U/M or setting PDT=00 to unmap a
page. If one does, the drain writes back `stale | U/M` and silently **resurrects or
re-marks** the descriptor. The window is about 2-4 cycles plus arbitration. A real 68040
uses a locked RMW. Check whether an interlock exists in `LsEuPlugin`/SQ code outside this
slice. If not, a cheap fix is to hold SQ drain from drain-read-issue until drain-store-ack.

**O-3. Root and pointer table U bits are never set.** Only `WalkRsp.umWrite` (the leaf byte)
is consumed. `umBatch`, the root/pointer U path, is dead with `batchMode=false`, and
`UmWalkBatchQueue` is not instantiated (`TableWalker.scala:275-279,303-307`; no
`UmWalkBatchQueue` reference in `src/main`). This is architecturally visible to an OS that
ages table descriptors. Mac OS probably does not care.

**O-4. Descriptor-format divergences from the MC68040** (code facts; architectural impact flagged as the author's reading of the PRM):
- (a) Indirect page descriptors (PDT=10) fault as non-resident (`TableWalker.scala:335-337`). PTEST also reports them as not resident.
- (b) The next-table base keeps descriptor bits [8:4] (`MmuDesc.tblNextBase`, `MmuTypes.scala:63`). The 68040 ignores the low 9/8/7 bits of the pointer, page-table and root addresses. An OS that keeps software bits in those positions would get mistranslation.
- (c) URP/SRP are used unmasked (`MmuControl.scala:119-120`, `TableWalker.scala:254-256`). The 4-byte straddle split exists only to survive misaligned roots.
- (d) When the MMU is off and no TTR matches, the D-side cache mode is WRITETHROUGH (`DtlbPlugin.scala:552-553`) and the I-side is WRITETHROUGH too (`ItlbPlugin.scala:1114-1117`). The author believes this matches the 040 default, but has not verified it.
- (e) A permission fault on the leaf sets no U bit (`TableWalker.scala:351-358`).

**O-5. Three implementations of table search.** ITLB walker, DTLB walker, and PTEST's
`S_PTREQ/S_PTWAIT` in ExceptionUnit (`exception/ExceptionUnit.scala:2993-3040`). They share
only the offset helpers. PTEST lacks the straddle split, the bus-error handling is
different, and the `pa` composition for 8 KB is written separately
(`exception/ExceptionUnit.scala:3021-3023` vs `cache/IcachePlugin.scala:342`). Each divergence is a
latent "fourth instance" of the latched-vs-re-derived family.

**O-6. An `atcFlush` drops an accepted DTLB request without answering it.**
`when(atcFlush){ when(missReqReg.valid){ missReqReg.valid:=False; missPending:=False } }`
(`DtlbPlugin.scala:717-723`) drops a request that has already fired (`_req.fire`) and not
yet started, and never sends a response. The code relies on every `atcFlush` source also
squashing the requester:

- MOVEC/PFLUSH retire at the ROB head and redirect;
- the debug apply happens only while halted.

Nothing asserts that. An ExceptionUnit translation (`excXlate*`) in flight across a
debug-apply flush, or a future non-serialising `atcFlush` source, would hang. A one-line
fix: on that path, answer with a forced miss-fault, or keep the request and re-walk with the
new configuration.

**O-7. The DTLB blocks on a miss** (`req.ready` has `!missPending`, `DtlbPlugin.scala:428`).
With `lsOooIssue` and a non-blocking 4-MSHR D-cache in shipping, a DTLB miss now serialises
the whole LS pipe for the length of the walk. *Hit-under-walk* is the cheapest MMU IPC lever:
let `req` fire on a hit while `missPending`. The response buffer is one deep, so it needs a
second `rspPayload` or a hold that ends when the walk does.

**O-8. Walker priority.** A walker is a candidate only when the core LS pipe is not
requesting the load port, or after 64 cycles (`LsEuPlugin.scala:5514-5525`). It
re-arbitrates on every level. On a load-heavy stream a single walk can therefore take about
3 × 64 cycles, and the ITLB walk is stalling **fetch** the whole time. Giving the ITLB walker
priority, or holding `ldOwner` across all three levels, is cheap.

**O-9. Over-invalidation on any root or TTR write, and PFLUSH(An) treated as a full flush.**
This is correct but costs re-walks. Cheaper than it looks only if the OS rarely issues them.
`OFF_MMU_REKEY_COUNT` and the `PERF_*_WALK` counters can measure it on the board.

**O-10. `TlbEntry` width is tied to the default geometry.**
`vpnTag = UInt(Tlb.tagBitsFor(DefaultEntries, DefaultWays, DefaultBanks))`
(`Tlb.scala:22`). Any non-default `Tlb(...)` changes `tagBits`, and `e.vpnTag := t`
(`:206`) then mismatches. TLB-size experiments are latently blocked. Since `vpnTag` is never
used (see §4), deleting the field fixes it.

**O-11. Drain datapath width.** Both drains build a 128-bit `lineData` and a 16-bit strobe
for a single-byte write (`DtlbPlugin.scala:906-929`). That is 288 FF plus a 16-way byte
placement mux per side. Sending `size=BYTE` with 8-bit data, as `DStoreCmd.data` would
allow, removes it. Check first how `useStrb`/`lineData` is consumed by the D-cache, which
is outside this slice.

**O-12. Debug area that a shipping profile could trim** (all estimates):
- (a) `archApplyShadow`, 1,024 FF: a full copy of `archShadow` taken at apply start (`DebugCtrlPlugin.scala:1393,1578`). Applying straight from `archShadow`, while rejecting CSR writes during `archApplyBusy` (which the code already tracks with `archWriteMask`/`archApplyRewrite`), saves about 1 K FF.
- (b) The duplicate breakpoint PC registers in DecodeStage (`decode/DecodeStage.scala:210-213`): 128 FF. These are probably intentional for timing, as a register copy near the comparator.
- (c) `pcRangeEnable` (the `full` profile, about 300 LUT / 290 FF) and the always-built A7-odd lane are rarely needed forensic features. A `reduced` profile already exists for the first.
- (d) Stage 5 is hard-wired in `M68kSocketTop` (`top/SocketTop.scala:61`). There is no environment control, so the debug stage cannot be lowered for a timing experiment without editing code.
- (e) The 256-bit `haltExceptionMask` could be 64 bits (vectors 0-63) at no practical loss.

None of these is on a critical path by construction. They mainly add placement density next
to the ROB/decode, which matters in a design that is congestion-bound.

**O-13. Unimplemented debug register offsets read as zero with OKAY** (§3.10 list). A host
tool cannot tell "feature missing" from "value is 0" except through `OFF_FEATURES`. A missing
offset should return SLVERR. Any tooling that writes `WP0_*` on this bitstream configures
nothing, silently.

**O-14. Ring tearing is only partly fixed.** The snapshot is taken only when word 0 is read
(`DebugCtrlPlugin.scala:1081-1089`). A reader that starts at word 1, or re-reads after
another index, gets live data. A host tool must always read words 0→3 in that order.

**O-15. The OOC `FullCoreSynth` target is not the shipping configuration.** It bypasses
`ShippingPlugins`: different LSU and IQ construction, no `AxiDMerge`, no reset vector, debug
stage 2 (`top/FullCoreSynth.scala:850-933`). Area or timing taken from
`M68kFullCoreSynth` does not describe the bitstream.

**O-16. The read-reset absorber covers reads only.** `AxiReadResetAbsorber` tracks AR and R
(`socket/AxiReadResetAbsorber.scala:46-61`, `top/SocketTop.scala:359-368`). An AW/W/B
transaction in flight across a core reset is not absorbed. Whether `AxiDMerge` tolerates a
stray B after reset is outside this slice; flagged as unverified. `axi_dh` has its own
boundary logic.

**O-17. Every interrupt is autovectored at the socket**
(`iackAvecIn := True`, `top/SocketTop.scala:480-481`). That is fine for the Quadra 700
platform. A vectored source would need a socket change.

**O-18. The ITLB response mux depends on control registers.** `mmuEnable`, the ITT matches
(two 8-bit masked compares) and `latchMatch` (a 20-bit compare) sit in the same
combinational response as the array hit, feeding `cmdPort.ready`
(`ItlbPlugin.scala:1103-1134`). Pre-computing `mmuEnable && !ttHit` one cycle early is not
possible, because the ITT match depends on the current PC. But `ttrAny = itt0.E || itt1.E`
and `mmuEnable` are quasi-static and could be registered into a single mode bit. That would
shave a level from the `rsp.ready` → `cmdPort.ready` cone.
