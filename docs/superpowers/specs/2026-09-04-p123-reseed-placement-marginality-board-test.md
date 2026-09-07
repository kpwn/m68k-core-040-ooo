# p123-reseed board test: does a different placement clear the `0x4084BECE` wedge?

**Date:** 2026-09-04
**Type:** board/JTAG measurement (no build started, no SD card touched)
**Verdict:** **NO.** The re-placed bitstream wedges identically. Placement variation alone does not fix it.

---

## Question

The boot wedge at `0x4084BECE` was believed **implementation-specific rather than an RTL-logic
bug**, on the evidence that bitstream **p123** wedges and bitstream **p128** does not, on the same
board and card, minutes apart, from the same RTL logic. But p128 also carries an entire ILA (63
probes, 4096 deep, `MARK_DEBUG` pinning), so **placement luck** and **ILA-induced netlist change**
were not separable between those two.

`p123-reseed` separates them: byte-for-byte the p123 RTL source (same `cpu040` pin `7d74ba33`,
`ENABLE_ILA=0`, same DDR4 MIG DCP), re-implemented with `PLACE_DIRECTIVE=Explore` instead of
p123's default `AltSpreadLogic_high`. If placement draw is what decides the wedge, this is a
fresh draw and it might land on the working side.

## What was run

All work under the JTAG lease (`p123-reseed-placement-test`, acquired 07:39Z, released 07:54Z).
`hw_server` untouched, SD card untouched, no Vivado build started — the `/var/tmp/m68k-ooo-vivado.lock`
mutex was verified **held** by an unrelated p131 synth gate throughout, and was not contended.

| # | Bitstream | `build_id` verified live | Reset path | Samples | Result |
|---|-----------|--------------------------|-----------|---------|--------|
| 1 | p123-reseed | `0x66BEA1B7` | FPGA reconfiguration (`load-bit`) | 24 over 2m40s | wedged at `0x4084BECE` |
| 2 | p123-reseed | `0x66BEA1B7` | `vio-hard-reset` (true platform reset, RAM zero pass runs) | 22 over 2m25s | wedged at `0x4084BECE` |
| 3 | p123 (control) | `0x1E0FB901` | FPGA reconfiguration (`load-bit`) | 20 over 2m11s | wedged at `0x4084BECE` |

66 of 66 `pc_live` samples across all three boots landed inside the loop
(`0x4084BECE` / `0x4084BED0` / `0x4084BED2`). Not one sample was outside it.

Vivado's own attach report for the reseed lists **1 JTAG-AXI + 1 MIG + 1 VIO core and no ILA
core**, independently confirming at the hardware level that `ENABLE_ILA=0` held.

### The loop, decoded from live memory

```
4084BECC: 2EC5   move.l %d5,(%sp)+     ; runs once, before the loop
4084BECE: 429F   clr.l  (%sp)+         ; <- A7 never advances past 4
4084BED0: 300F   move.w %sp,%d0
4084BED2: 66FA   bne.s  *-4            ; D0.w = 4 != 0, so it loops forever
```

A ROM low-memory clearing loop that walks `A7` upward clearing longs and exits when `A7`'s low
word wraps to zero. It is stuck on its **first** iteration.

### Architectural state — identical on all three boots

| | boot 1 (reseed) | boot 2 (reseed) | boot 3 (p123 control) |
|---|---|---|---|
| `A7` | `0x00000004` | `0x00000004` | `0x00000004` |
| `ISP` | `0x00000004` | `0x00000004` | `0x00000004` |
| `D0` | `0x04000004` | `0x04000004` | `0x04000004` |
| `SR` | `0x00002710` | `0x00002710` | `0x00002710` |
| `VBR` | `0x40846980` | `0x40846980` | `0x40846980` |
| `exc-ring` | empty (head=0, all zero) | — | empty (head=0, all zero) |
| `inst-count` | 3652383328 | 3089126566 | 3146450872 |

The `exc-ring` being completely empty is worth stating: **this is not an exception loop.** No
fault, no trap, nothing. It is a pure instruction-level fixed point. (`exc_count` reads 0 too,
but `exc_count` is known to lie — the ring is the trustworthy source and it agrees.)

`inst-count` in the billions confirms the CPU is genuinely executing, not frozen.

## Answer

**Reseed still wedges** ⇒ per the pre-agreed interpretation, **placement variation alone does not
fix it**, which points at the ILA's presence (probe insertion / `MARK_DEBUG` pinning) rather than
placement luck.

### What this does and does not establish

Establishes:

- A genuinely different placement of byte-identical RTL — verified applied, and with a
  measurably different result (CPU-clock WNS `+0.912 ns` vs p123's `+1.035 ns`) — reproduces the
  wedge exactly, down to the same `A7` fixed point.
- The board and card reproduce the wedge **today**, so boot 1/2's wedge is not an artifact of a
  degraded setup. The control is valid.

Does **not** establish:

- That placement never matters. This is **n=2** on the non-ILA side. Two placements both wedging
  disfavours a broad placement lottery; it cannot exclude a narrow one.
- That the ILA is *causal*. It narrows the p123↔p128 delta to "something about the ILA build",
  but that build differs by more than placement: `MARK_DEBUG` **inhibits logic optimisation** on
  marked nets, so p128 is not the same netlist as p123. The leading hypothesis is now a netlist
  difference, not a placement or fanout difference.

### The timing story now actively argues against setup marginality

| build | CPU-clock (`fabric_clk100`) WNS | boots? |
|---|---|---|
| p123 | `+1.035 ns` | no |
| **p123-reseed** | **`+0.912 ns`** | **no** |
| p128 | `+0.245 ns` | yes |

Margin is **anti-correlated** with working. The build with the least CPU-clock margin is the only
one that boots. Ordinary setup marginality does not explain this, and the wedge also reproduces
identically at 100, 25 and 12.5 MHz.

### Re-framing worth considering

The premise "implementation-specific, **not** an RTL-logic bug" rested entirely on p123-vs-p128.
With two of two non-ILA implementations now wedging, the more parsimonious reading is the
inverse: **a real RTL bug present in all builds, which p128's ILA happens to mask.** That is a
different investigation from "chase the placement", and probably the better one to fund.

Supporting this, the symptom is very specifically shaped like a known bug class in this project
(the rename-exposure / `A7`-producer forwarding hazards, tasks #176/#194/#200): the loop's `clr.l
(%sp)+` appears to compute its EA from the **stale, pre-update `A7`** (`0`) while writing back
`stale+4 = 4`, so `A7` is pinned at 4 forever. The immediately preceding `move.l %d5,(%sp)+`
postincremented correctly (`0`→`4`), so it is the **back-to-back** `A7` postincrement that fails.

Low-memory readback is consistent with that: `0x0` reads `0x00000000` (cleared) while `0x4` reads
`0xfefefefe` (untouched fill pattern) — i.e. the `clr` is hitting address 0, not address 4.
**Caveat, stated plainly:** `r` goes over JTAG-AXI straight to DDR and bypasses L1D, and `CACR`
reads `0x00008000` (D-cache enabled), so a dirty line could make this view stale. Treat the
memory readback as *suggestive*, not decisive. The architectural fact that `A7` reads 4 across
billions of retired instructions is decisive; *which* EA the `clr` uses is not yet proven on
hardware.

## Corrections to the briefing this test was given

Three, all verified against the tree:

1. **`vio-hard-reset` does give a fresh-DRAM cold boot.** The brief stated neither
   `vio-hard-reset` nor a CPU reset clears DRAM. In RTL, `vio_hard_reset` → `btn3_resetn_db` →
   `platform_reset_req` → `clk_rst.rst_in` → `core_rst_bank[6]` → `boot_warm_q <= 0` → the 256 MiB
   RAM pre-zero pass runs (`rtl/soc/fpga_top_boot_master.vh:145-158`,
   `rtl/soc/fpga_top_clocks.vh:270-272,690-706`). This matches the in-tree correction already
   recorded at `tools/jtag_repl.tcl:3074-3105` (dated 2026-08-27). What *doesn't* zero DRAM is the
   warm `reset` / VIO-bit-3 path. Boots 1 and 3 used full FPGA reconfiguration anyway, which is
   stronger still, so the finding does not depend on this.

2. **The `+8.690 ns` figure is not the `dbg_hub` domain.** It is the **Inter Clock Table**
   `async_default` row for `fabric_clk100 → pb_clk`, all of 2 endpoints
   (`build/vivado/timing_summary.rpt:243`). The design-level headline WNS for the reseed is
   **`+0.059 ns`**, belonging to `mmcm_clkout0` (the 333 MHz MIG/DDR domain) — which is also what
   the build script itself reported (`WNS=0.059ns`). The CPU clock is `fabric_clk100` at
   **`+0.912 ns`** over 281 916 endpoints. The brief was right to warn against quoting the
   headline; the specific attribution to `dbg_hub` was wrong.

3. **Minor:** the message confirming the directive is `[Vivado_Tcl 4-2302] The placer was invoked
   with the 'Explore' directive`, not `[Place 30-611]` (that ID is the multithreading notice).
   The directive itself is confirmed applied: `place_design -directive Explore` at
   `vivado.log:26199-26207`.

## Not run / out of scope

- **No Vivado build of any kind.** A p131 synth gate held the mutex throughout.
- **No third placement directive.** n=2 on the non-ILA side; a further draw would firm up the
  "narrow lottery" exclusion but was not attempted.
- **No p128 re-run.** The p128 side of the comparison is inherited from the earlier session, not
  re-measured here.
- **No cache-flush-then-read** to prove the stale-EA hypothesis. Would need a `CPUSH` or a
  cache-coherent read path; the DDR readback above is the weaker substitute and is caveated.
- **The `0x40899706` 53C96-status-poll wedge did not appear.** The brief flagged that three boots
  earlier today stopped there instead. It did not occur in any of my three boots — all three went
  to `0x4084BECE`. Whatever caused that variant was not present this session; I did not chase it.
- **This says nothing about whether the boot is fixed. It is not.**
