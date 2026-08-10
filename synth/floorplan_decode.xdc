# Front-end floorplan: co-locate DecodeStage (feed regs + MicroOpAssembler + the banked
# MicroOpQueue ring) into a compact region so the decode->ring push nets are short hops, not
# die-length routes. Read AFTER opt_design (see impl_FullCore.tcl) so the cell filter sees the
# elaborated leaf cells. Region = the v1 "loose" box that was the sweet spot (a tighter box
# over-constrained the router; see analysis/floorplan, docs/analysis/2026-06-09-floorplan-strategy.md).
#
# NOTE (deferred refinement): `get_cells -hier -filter {NAME =~ *DecodeStage_logic*}` UNDER-
# captures post-flatten (~1.3-5k of ~19.9k cells, varies by netlist) — Vivado flattens most
# leaf names. Even the partial capture co-locates the critical ring/feed regs and contributed
# to taking decode->ring off the post-route critical path (195 -> ~218 with ring banking + P1).
# A robust full capture (hierarchical cell / keep_hierarchy on DecodeStage) is a floorplan
# refinement to fold into the broader floorplan track (alongside the D-cache pblock).
#
# FMax closure round 3/4 (2026-08-08): widened X75 -> X87 (40 -> 52 columns, left edge
# anchored at X36, unchanged). An earlier attempt at exactly this resize (docs/superpowers/
# specs/2026-08-08-fmax-levere-pbdecode-floorplan-design.md) REGRESSED FMax (-6.84MHz) because
# the annexed territory was occupied by RobPlugin (8751 cells)/IcachePlugin, not spare --
# widening evicted them and the eviction cascaded into worse problems elsewhere. "LS/ROB Lever
# C" (commit 4c65909, an unrelated RTL area win kept for its own merits) incidentally shrank
# RobPlugin's placed footprint by ~10.7% (40568 -> 36221 cells). Re-tested with Lever C in the
# tree: the SAME resize now IMPROVES (+8.65MHz on a controlled A/B, WNS -1.559 -> -1.304ns;
# pb_decode spill 872->103; CLB util 113.95%->101.89%; 3 level-5 congestion windows -> 0; the
# ROB-fault family -- Lever C's own original RTL target, which it failed to fix directly --
# improves from floorplan alone). See .superpowers/sdd/progress-fmax-levers-2026-08-08.md for
# the full before/after evidence. Do NOT revert this to X75 without re-running that A/B --
# whether this territory is "occupied" depends on what else is in the tree at the time.
create_pblock pb_decode
resize_pblock pb_decode -add {SLICE_X36Y0:SLICE_X87Y104}
add_cells_to_pblock pb_decode [get_cells -hier -filter {NAME =~ *DecodeStage_logic*}]
