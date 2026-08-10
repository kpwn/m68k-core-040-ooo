# pb_decode variant that ANNEXES FetchAlignPlugin + RasPlugin into the decode box.
# Added 2026-08-10 by the section-18 floorplan A/B as the literal test of the
# "decode/FetchAlign pblock boundary" datapoint from handoff section 17 step 1:
#
#   DecodeStage _zz_..._fed_payload_specs_0_spec_size_reg[0]   SLICE_X57Y20   INSIDE pb_decode
#   FetchAlignPlugin_logic_predictPending_reg                  SLICE_X24Y99   OUTSIDE pb_decode
#   -> 4.487 ns of pure route, 77.9% of that path's 5.757 ns total.
#
# The hypothesis under test is that pinning DecodeStage to X36..X87 while leaving
# its FetchAlign consumers free stretches the decode<->predict arcs across the box
# edge, and that pulling the consumers inside closes them.
#
# Mutually exclusive with synth/floorplan_decode.xdc — both create pb_decode.
# Selected with FLOORPLAN_MODE=decode_fe.
#
# Sizing (synth/probe_floorplan_filters.tcl, exact 6b246de netlist):
#   captured  37,686 cells = 27,099 LUT + 9,995 FF + 215 CARRY + 269 MUXF
#   grid  SLICE_X36Y0:SLICE_X87Y104 (unchanged) = 5,460 slices = 43,680 LUT sites
#   -> 62.5% LUT-site occupancy, up from 55.2% for the plain decode capture.
# Geometry is deliberately UNCHANGED: section 5 records that a prior X87 -> X103
# widening regressed (-2.447 -> -2.558 ns), so this variant isolates the capture
# change and nothing else.
#
# COUNTER-EVIDENCE recorded up front, because it is strong: the spec_size family is
# worth +0.006 ns on the what-if ladder (section 17), and moving FetchAlign right
# into X36..X87 moves it AWAY from IcachePlugin (centroid X17Y62), which owns all
# 300 worst endpoints via the `stalled -> s1PredEntries` arc.  This variant is
# expected to trade a measured-worthless family for the one that actually binds.
# It is run anyway so the trade is a measurement rather than an argument.
create_pblock pb_decode
resize_pblock pb_decode -add {SLICE_X36Y0:SLICE_X87Y104}
add_cells_to_pblock pb_decode [get_cells -hier -filter {NAME =~ *DecodeStage_logic* || NAME =~ *FetchAlignPlugin_logic* || NAME =~ *RasPlugin_logic*}]
