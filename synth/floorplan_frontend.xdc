# Front-END (fetch/predict) floorplan — added 2026-08-10 by the section-18 floorplan A/B.
#
# WHY THIS EXISTS.  Every one of the worst 300 unique failing endpoints on the
# `6b246de` routed checkpoint is the SAME arc:
#
#   FetchAlignPlugin_logic_stalled_reg/C  ->  IcachePlugin_logic_s1PredEntries_{0..3}_reg[*]/CE
#     path delay 5.990 ns = 2.019 logic (34%) + 3.971 route (66%), 20 logic levels
#     PBlocks crossed: 0     (report_design_analysis -timing, path #1)
#
# The path crosses NO pblock boundary — it lives entirely in the unfloorplanned
# left strip.  `pb_decode` cannot help it and `pb_dcache` is nowhere near it.
# Measured placement of its two ends (synth/probe_floorplan_census.tcl):
#   stalled_reg          SLICE_X26Y99
#   s1PredEntries_*      X10..X26, Y56..Y96   (930 flops at ~2.3 FF/slice — very sparse)
# and the whole FetchAlign plugin is smeared over X10..X65 while IcachePlugin sits
# at centroid X17Y62.  A 24-48 CLB Manhattan span at 2.3 FF/slice density is what
# the 66% route number is made of.
#
# WHAT THIS BOX DOES.  Co-locate the entire fetch/predict cluster — IcachePlugin,
# FetchAlignPlugin, FtbPlugin, GsharePlugin, RasPlugin, BtbPlugin — into the left
# strip they already gravitate to, so the `stalled` broadcast and the s1PredEntries
# capture-enable fan-out stay inside one compact region instead of stretching to
# meet decode (which pb_decode pins at X36..X87).
#
# SIZING, from synth/probe_floorplan_filters.tcl against the exact 6b246de netlist:
#   captured (clean)  25,229 cells = 14,570 LUT + 4,296 FF + 113 CARRY + 855 MUXF + 5,395 other
#   grid  SLICE_X0Y20:SLICE_X35Y135  = 4,176 slices = 33,408 LUT sites / 66,816 FF sites
#   -> 44.0% LUT-site, 6.4% FF-site occupancy before counting the LUT-resident
#      distributed-RAM/SRL "other" cells; with those it is still under ~60%.
# Deliberately loose: the 2026-08-08 pb_decode lesson is that a tight box
# over-constrains the router, and the checkpoint-2 lesson is that ANNEXING
# OCCUPIED TERRITORY backfires.  This region is where these plugins already are
# (measured bboxes: Icache X1..X50 Y1..Y136, FetchAlign X10..X65 Y20..Y105,
# Ftb X1..X34 Y57..Y135, Gshare X10..X32 Y75..Y103, Ras X10..X59 Y51..Y82), so
# it mostly *tightens* an existing cluster rather than relocating one.
#
# CAPTURE FILTER.  Vivado's post-opt names are instance-path prefixed, so a bare
# `*XPlugin_logic*` match also drags in every cell absorbed into that plugin's
# SUBMODULE instances — e.g. `LsEuPlugin_logic_sq/RobPlugin_logic_branchTrainMem_*`
# is 7,033 ROB cells (carry chains included) matching `*LsEuPlugin_logic*`.  That
# is the exact defect that made the legacy pb_dcache harmful.  Every filter in
# this campaign therefore carries explicit foreign-plugin exclusions AND the
# `IS_PRIMITIVE` qualifier: without the latter, `get_cells -hier` also returns the
# hierarchical instances themselves (here `FetchAlignPlugin_logic_ibuf`, the
# InstructionBuffer), and adding one of those re-constrains every leaf underneath
# it regardless of that leaf's name -- which is how the exclusions get silently
# undone.  See synth/floorplan_backend.xdc for the measured numbers.
#
# SLICE ranges only: BRAM/URAM/DSP cells captured by the filter stay unconstrained
# (same convention as pb_decode, whose ucRomMem BRAM has always placed outside).
create_pblock pb_frontend
resize_pblock pb_frontend -add {SLICE_X0Y20:SLICE_X35Y135}
add_cells_to_pblock pb_frontend [get_cells -hier -filter {(NAME =~ *IcachePlugin_logic* || NAME =~ *FetchAlignPlugin_logic* || NAME =~ *FtbPlugin_logic* || NAME =~ *GsharePlugin_logic* || NAME =~ *RasPlugin_logic* || NAME =~ *BtbPlugin_logic*) && NAME !~ *DecodeStage* && NAME !~ *RobPlugin* && NAME !~ *IssueQueuePlugin* && NAME !~ *RenameStage* && NAME !~ *DcachePlugin* && NAME !~ *LsEuPlugin* && IS_PRIMITIVE}]
