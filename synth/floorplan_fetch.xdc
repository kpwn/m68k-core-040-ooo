# pb_fetch -- the floorplan half of the Unified Fetch Array co-design (spec section 7).
#
# WHY THIS IS NOT VARIANT 7 AGAIN. Variant 7 pinned 1,900 SPARSE flops with a pblock:
# it produced +0.685 ns of iteration yield -- the best ever measured in this campaign,
# beating baseline's own +0.622 -- and still lost, on round-0 (-2.422 vs -2.094). A
# pblock ASKS the placer for compactness; it cannot manufacture density in a
# 2.3-FF/slice cloud. This design DELETED those flops (M1b deleted s1PredEntries,
# predAccumLo and the predMem LUTRAM array; M2a narrowed the INHIBITED/poisoned
# bypass 256 -> 32 flops; M2b deleted lineReg) and then draws the box around what is
# left, which is denser, smaller, and anchored on fixed BRAM sites.
#
# SIZING -- measured, not assumed. From synth/probe_floorplan_fetch.tcl against THIS
# netlist's own post-synthesis checkpoint (synth/m5_synth.dcp, generated/M68kFullCoreSynth.v
# md5 c4c25c11cc91647c21b310d5d95ffdc6), not against the 6b246de baseline that
# floorplan_frontend.xdc was drawn for:
#   captured (clean)  19,671 cells = 12,831 LUT + 3,243 FF + 107 CARRY + 848 MUXF +
#                     2,615 LUTRAM + 24 BRAM + 3 other (GND/VCC, siteless)
#   grid  SLICE_X0Y20:SLICE_X35Y99  = 2,880 slices = 23,040 LUT sites / 46,080 FF sites
# and Vivado's OWN site accounting for exactly this box, which is what the geometry
# was finally fitted to (report_utilization -pblocks pb_fetch on the same checkpoint
# with this file read in; re-run it with synth/check_floorplan_fetch.tcl, output of
# record synth/m5_xdc_check.out -- synth/*.rpt is gitignored by repo policy, so the
# table is echoed into that log rather than left only in m5_pb_fetch_util.rpt):
#   CLB LUTs          13,667 / 23,040 = 59.32%   (11,687 as logic + 1,980 as distributed
#                                                 RAM, 0 as SRL)
#   CLB Registers      3,243 / 46,080 =  7.04%
#   CARRY8               107 /  2,880 =  3.72%   F7 660 / F8 188
#   Block RAM Tile        22 /     48 = 45.83%   (20 RAMB36E2 + 4 RAMB18E2)
# Target band 55-65% LUT-site, which is the working range floorplan_decode_fe.xdc's
# own sizing note treats as correct; 59.32% is essentially the probe's 0.60 row.
# NEVER the 96.78%-occupancy trap floorplan_decode.xdc records, and NEVER by annexing
# occupied territory (the X87->X103 lesson, -2.447 -> -2.558 ns).
#
# WHY VIVADO'S NUMBER AND NOT THE PROBE'S ARITHMETIC. The probe's FETCH_SIZING rows
# derive LUT demand as LUT + CARRY + LUTRAM primitives, one LUT site each: 15,553,
# which asks for 3,241 slices at 0.60. That heuristic overstates by 13.8%, for two
# measurable reasons visible in the report above -- Vivado's LUT count is adjusted for
# LUT combining (12,831 primitives -> 11,687 sites), and the 2,615 captured LUTRAM
# primitives are mostly MEMBERS of RAM32M/RAM64M8/RAM128X1D/RAM256X1D macros that
# share sites (-> 1,980 sites). A first cut at SLICE_X0Y20:SLICE_X35Y109 (3,240
# slices) measured 52.73%, i.e. BELOW the band, so the box was tightened once, to the
# geometry above, and re-measured. Sized from the netlist, then corrected by the
# netlist -- which is the whole point of spec section 7.2 item 2.
#
# CONTRAST WITH THE BOX IT REPLACES, on the same filter: pb_frontend captured 4,296 FF
# on the 6b246de netlist and this capture is 3,243 (-1,053, -24.5%); its whole
# non-LUT/FF/CARRY/MUXF residue was 5,395 cells and here the LUTRAM+BRAM+other residue
# is 2,642 (-51%). (The 6b246de row was produced by probe_floorplan_filters.tcl, whose
# fe_filter omits IS_PRIMITIVE and lumps LUTRAM/BRAM/SRL into one "other" bucket, so
# the cell TOTALS are not strictly comparable -- the FF column is, because hierarchical
# instances are never FD*.) The old box, kept at its old 4,176 slices, would now hold
# this population at 40.9% LUT-site occupancy: far too loose. pb_fetch is a STRICT
# SUBSET of pb_frontend's SLICE_X0Y20:SLICE_X35Y135 -- the top edge comes in from Y135
# to Y99 and nothing else moves; -31% area. Zero new territory is annexed, which is
# the strongest available guard against the recorded X87->X103 annexation failure
# mode. The top edge is the one that moves because Y20..Y99 keeps the box entirely
# alongside pb_decode's own Y0..Y104 body, so the fetch->decode arcs -- the family
# that actually binds -- get the longest possible shared edge rather than a fetch
# region overhanging decode's top.
#
# BRAM SITES ARE INCLUDED (spec section 7.2 item 3), unlike every prior pblock in this
# tree, which constrained SLICE ranges only. The Unified Fetch Array is why: its
# columns are fixed placement anchors, and boxing the logic without them would force
# the placer to stretch between a boxed region and an unboxed memory column -- the
# exact stretch the 66%-route number is made of. All 24 captured BRAM cells are the
# M1 array itself: IcachePlugin_logic_lineMem_{0..3}_reg_{0..5} = 20 RAMB36E2 +
# 4 RAMB18E2, i.e. 22 tiles.
#
# BRAM RANGES ARE DERIVED FROM MEASURED DEVICE GEOMETRY, not from a column-pitch
# assumption: post-synthesis LOC is empty on every one of those cells (the probe prints
# an empty LOC field for all 24), so there is nothing to read off placement. The probe
# instead reports each site family's tile ROW/COLUMN for xcku5p-ffvb676-2-e:
#   SLICE   X0..X112 (tilecol 53..375), Y0..Y239 (tilerow 247..0, decreasing)
#   RAMB36  X0..X9 at tilecol 59, 89, 122, 158, ... ; Y0..Y47
#   RAMB18  X0..X9 at the same tilecols; Y0..Y95 (two RAMB18 sites per RAMB36 tile)
# so, by tilecol, the BRAM columns physically inside SLICE_X0..X35 are exactly
# RAMB*_X0 (tilecol 59, between SLICE_X2=58 and SLICE_X3=62), X1 (89, between X13=88
# and X14=92) and X2 (122, between X26=121 and X27=125). The next one, X3 at tilecol
# 158, sits between SLICE_X38 and SLICE_X39 -- past pb_decode's left edge, so it is
# excluded. Vertically the probe's ROW map gives RAMB36_Y = SLICE_Y / 5 exactly
# (SLICE_Y20 and RAMB36_Y4 share tilerow 227; SLICE_Y100/RAMB36_Y20 share 144;
# SLICE_Y135/RAMB36_Y27 share 108), and RAMB18_Y = 2*RAMB36_Y {,+1}. The SLICE range's
# Y20..Y99 was chosen to land on those boundaries exactly (Y20 opens RAMB36_Y4, Y99
# closes RAMB36_Y19), so the BRAM ranges below are fully contained by the logic box
# rather than overhanging it:
#   RAMB36_X0Y4:RAMB36_X2Y19  = 3 cols x 16 rows = 48 tiles for 22 tiles of demand
#   RAMB18_X0Y8:RAMB18_X2Y39  = the same 48 tiles' 96 RAMB18 sites
# Vivado's pblock report confirms both counts exactly (48 tiles / 96 RAMB18 available),
# so the coordinate-system translation above is measured, not assumed.
#
# pb_decode IS DELIBERATELY LEFT UNCHANGED at SLICE_X36Y0:SLICE_X87Y104. Two
# independent experiments (the X87->X103 widening, and variant 6's loosening at
# -12.65 MHz) say that box is at a local optimum. Change one thing. pb_fetch's right
# edge stops at X35 for the same reason.
#
# CAPTURE FILTER copied verbatim from floorplan_frontend.xdc: explicit foreign-plugin
# exclusions AND the IS_PRIMITIVE qualifier. Without IS_PRIMITIVE, get_cells -hier
# also returns hierarchical instances (e.g. FetchAlignPlugin_logic_ibuf), and adding
# one re-constrains every leaf under it regardless of name -- which is how the
# exclusions get silently undone, and is the exact defect that made the legacy
# pb_dcache harmful. Contamination verified 0 in synth/m5_xdc_check.out.
#
# READING THE `FLOORPLAN pb_fetch ... cells=` LINE impl_FullCore.tcl prints: it says
# 16,889, not the 19,671 this file's sizing note quotes, and that gap is an accounting
# artefact, not a dropped subset. `get_cells -of_objects [get_pblocks ...]` reports a
# distributed-RAM/wide-mux MACRO once instead of its members, so exactly 2,782 member
# cells (RAMD64E 1,636, RAMD32 600, RAMS32 88, MUXF7 362, MUXF8 96) are covered by
# their parents. m5_xdc_check.out proves the direction that matters: the reverse set --
# cells in the pblock that the filter would NOT have selected -- is 0, and Vivado's own
# pblock utilization still counts all 848 F7/F8 muxes and all the distributed RAM.
create_pblock pb_fetch
resize_pblock pb_fetch -add {SLICE_X0Y20:SLICE_X35Y99}
resize_pblock pb_fetch -add {RAMB18_X0Y8:RAMB18_X2Y39}
resize_pblock pb_fetch -add {RAMB36_X0Y4:RAMB36_X2Y19}
add_cells_to_pblock pb_fetch [get_cells -hier -filter {(NAME =~ *IcachePlugin_logic* || NAME =~ *FetchAlignPlugin_logic* || NAME =~ *FtbPlugin_logic* || NAME =~ *GsharePlugin_logic* || NAME =~ *RasPlugin_logic* || NAME =~ *BtbPlugin_logic*) && NAME !~ *DecodeStage* && NAME !~ *RobPlugin* && NAME !~ *IssueQueuePlugin* && NAME !~ *RenameStage* && NAME !~ *DcachePlugin* && NAME !~ *LsEuPlugin* && IS_PRIMITIVE}]
