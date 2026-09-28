# Area ledger — 100 MHz lane

Every 100 MHz build appends a row. **Area inside this lane is exact and comparable;
never compare a row here against a 200 MHz build** — synthesis retimes and replicates
harder against a tight constraint, so the same RTL reads lower on LUTs at 100 MHz.
`WNS` is recorded only to show the build closed trivially; it says **nothing** about
200 MHz closure.

Plan (owner, 2026-09-27): **track area as levers land, then do an area-reduction pass
once they are all in.** Reference points for that pass — the last 200 MHz builds were
m20 at 152,093 LUT and `areaonly200` at 147,045, and the area campaign that got there
removed 5,048 LUT with zero IPC cost.

| date | build | fpga_top LUT | socket_core LUT | FF | RAMB36 | WNS | note |
|---|---|---:|---:|---:|---:|---:|---|
| 09-27 | br0 (baseline) | 144831 | 94972 | 90798 | 172 | 0.023 | master+track5, both branch flags OFF |
| 09-27 | br5 | 146001 | 95813 | 90785 | 172 | 0.039 | +computeDirectTargets +deferSlot1Uncond |
| 09-27 | icpf | 147124 | 96979 | 91284 | 172 | 0.050 |  |
| 09-27 | lsw0 | 146118 | 95919 | 90827 | 172 | 0.079 |  |
| 09-27 | lsw1 | 145315 | 95176 | 90864 | 172 | 0.005 | SPEC_LOAD_WAKEUP=1 |
| 09-27 | ras1 | 147008 | 96855 | 90861 | 172 | 0.057 | CPU_RAS_BRANCH_REPAIR=1 |
| 09-27 | allg0 | 146163 | 96275 | 91509 | 172 | 0.023 |  |
| 09-28 | allg1 | 148834 | 98739 | 91668 | 172 | 0.118 | SPEC_LOAD_WAKEUP=1 CPU_RAS_BRANCH_REPAIR=1 CPU_COMPUTE_DIRECT_TARGETS=1 CPU_DEFER_SLOT1_UNCOND=1 |
| 09-28 | sect0 | 146097 | 95935 | 90781 | 168 | 0.041 |  |
| 09-28 | sect1 | 146034 | 95906 | 90622 | 168 | 0.089 | CPU_DCACHE_SECTORED=1 |

## ⚠️ SYNTH-ONLY rows — a SEPARATE lane, never compare these to the table above

The rows above are **post-route** (`utilization_route.rpt`); these are **post-synthesis**
(`utilization_synth.rpt`, `make synth`), taken because the Vivado lock was saturated. Synth
numbers run ~3-5k LUT higher than post-route on this design for identical RTL, so a
synth row and a route row are two different instruments. Synth-vs-synth inside this
sub-table is a valid comparison; synth-vs-route is not. No WNS: a synth-stage WNS says
nothing about anything (see the header).

| date | build | fpga_top LUT | socket_core LUT | fpga_top FF | socket_core FF | LUTRAM | RAMB36 | note |
|---|---|---:|---:|---:|---:|---:|---:|---|
| 09-28 | itlbvic_off | 149922 | 98452 | 90319 | 44415 | 14585 | 168 | `itlbVictimEntries=0` — the shipping default |
| 09-28 | itlbvic_on | 149693 | 98215 | 91774 | 45866 | 14585 | 168 | `ITLB_VICTIM=32` |
| | **delta** | **−229** | **−237** | **+1455** | **+1451** | **0** | **0** | |

**Reading: the ITLB victim buffer costs +1,451 flops in `socket_core` and NO MEASURABLE
LUTs.** The −229/−237 LUT is *negative*, i.e. pure noise well inside the ~800 LUT floor
this flow was measured at, and flops are the resource this device has spare (LUT-bound
78.8%, FFs 24.5%). Zero LUTRAM, zero BRAM, zero DSP. The elaboration estimate was ~1,509
flop bits against +1,451 measured — **within 4%**, which is the first time a flop estimate
on this design has been checked against synthesis and matched.

⛔ **Do NOT read the per-module rows of these two reports against each other.** Both TLBs
instantiate `Tlb`/`TableWalker`; in the OFF arm synthesis shares ONE module between the I
and D sides (`Tlb_41`, `TableWalker_43`), and in the ON arm the ITLB's copy differs so they
become separate modules. The attribution therefore moves wholesale — `ItlbPlugin_logic_tlb`
reads 1025 → 1309 and `ItlbPlugin_logic_walker` reads **276 → 1218 for a file this change
never touched**. Only the `fpga_top` / `socket_core` totals are comparable. This is the same
trap as "identical hardware-building calls are NOT duplicate logic", in reverse.
