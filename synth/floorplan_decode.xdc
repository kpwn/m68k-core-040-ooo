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
create_pblock pb_decode
resize_pblock pb_decode -add {SLICE_X36Y0:SLICE_X75Y104}
add_cells_to_pblock pb_decode [get_cells -hier -filter {NAME =~ *DecodeStage_logic*}]
puts "FLOORPLAN pb_decode cells: [llength [get_cells -of_objects [get_pblocks pb_decode]]]"
