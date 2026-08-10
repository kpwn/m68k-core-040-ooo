# D-cache / DTLB / LS-EU floorplan: co-locate the LS-cluster (D-cache + DTLB + LS-EU)
# into one compact-but-loose region so the cross-module load/translate nets stay local.
#
# THE LIMITER it attacks (post-route, route-dominated ~73% route):
#   DcachePlugin_logic_valids_*_reg/C  ->  DtlbPlugin_logic_hrPpn_reg/CE   (~4.6ns @4ns)
# The D-cache load-hit `valids` vector (cache/DcachePlugin.scala:149) and the DTLB hit-
# result `hrPpn` register (mmu/DtlbPlugin.scala:124, CE = mmuEnable & req.valid & tlbHit)
# connect THROUGH the LS-EU (execute/LsEuPlugin.scala:278-288), which drives both the
# cache load and the DTLB translate. The two modules place far apart -> die-length route.
# This is a CROSS-MODULE PLACEMENT problem, not a logic cone, so floorplanning is the lever
# (mirrors the front-end pb_decode precedent: a LOOSE box co-locating a smeared structure
# took decode->ring off the critical path, 195->~218).
#
# Read AFTER opt_design (see impl_FullCore.tcl) so the cell filter sees elaborated leaves.
# The original measured sweet spot used the same X span as pb_decode (X36..X75), stacked
# above it at Y110..Y214. Netlist growth subsequently required right-only expansion first
# to X81 (46 columns) and then X87 (52 columns). The checkpoint-2 netlist assigns 5,910
# parent CLBs to only 5,460 X87-box sites and physically occupies 117.62% of the region.
# A paired exact-netlist X103 experiment reduced this to 102.30% but regressed global
# route WNS -2.447 -> -2.558 ns. Keep X87 pending the no-floorplan/split controls.
#
# ITERATIONS (full-core post-route, OOC 4 ns; master baseline -0.514 / 221.5 MHz):
#   v1  X36Y110:X75Y214 (original)   WNS -0.317 / 231.6 MHz   <-- BEST (+10.1 MHz)
#   v2  X28Y110:X83Y214 (wider)      WNS -0.439 / 225.3 MHz   (looser -> less co-location)
#   v3  X36Y70:X75Y174  (shift down) WNS -0.477 / 223.4 MHz   (collides w/ datapath/decode)
# v1's X36..X75 width was the sweet spot for that netlist; later growth made it too tight.
#
# NOTE (capture): `*Plugin_logic*` UNDER-captures post-flatten (Vivado merges leaf names);
# verify the printed pb_dcache cell count is non-trivial -- here the OR-filter over all three
# plugins captures 7313 cells (robust, vs the decode pblock's 1284). The three plugins
# contribute ~944 (Dcache) + 81 (Dtlb) + 352 (LsEu) unique signal names pre-synth.
create_pblock pb_dcache
# LOOSENED twice on 2026-06-13. Line-4/LEA growth first required 40->46 columns
# (X36..X81; 231.6->208.8->211.2 MHz). STOP/RESET growth then raised the captured
# population from ~8467 to ~8606 cells and made 46 columns route at only 191.7 MHz;
# widening right again to X87 (52 columns) recovered 211.7 MHz. Do not shrink back to
# the stale X81 geometry without a paired floorplanned route.
resize_pblock pb_dcache -add {SLICE_X36Y110:SLICE_X87Y214}
add_cells_to_pblock pb_dcache [get_cells -hier -filter {NAME =~ *DcachePlugin_logic* || NAME =~ *DtlbPlugin_logic* || NAME =~ *LsEuPlugin_logic*}]
# (cell-count diagnostic is printed from impl_FullCore.tcl in TCL context — `puts` is not
#  supported inside an xdc read via read_xdc and throws a CRITICAL WARNING.)
