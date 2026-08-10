# LS-cluster floorplan, REPAIRED — added 2026-08-10 by the section-18 floorplan A/B.
# This file supersedes synth/floorplan_dcache.xdc, which is kept only as a
# diagnostic control (FLOORPLAN_MODE=dcache) because every published measurement
# of it is negative.
#
# WHAT WAS WRONG WITH pb_dcache (root-caused here, 2026-08-10).  Section 5 recorded
# that the legacy box "captured an unrelated ROB carry chain through flattened-name
# matching".  The exact mechanism, read off the netlist:
#
#   LsEuPlugin_logic_sq/RobPlugin_logic_branchTrainMem_reg_0_63_35_41_i_1   (CARRY8)
#   LsEuPlugin_logic_sq/RobPlugin_logic_branchTakenStore_60_i_3             (LUT6)
#   LsEuPlugin_logic_sq/IssueQueuePlugin_logic_aluSlowIntBusy[15]_i_5       (LUT6)
#
# `LsEuPlugin_logic_sq` is the StoreQueue SUBMODULE INSTANCE.  Vivado absorbed ROB
# branch-train and IQ scoreboard logic into that instance, so the bare filter
# `NAME =~ *LsEuPlugin_logic*` matches on the INSTANCE PATH PREFIX and captures
# them too.  Measured contamination on the exact 6b246de netlist:
#
#   pb_dcache 3-way OR filter   45,561 cells / 32,298 LUT   -> 74.3% LUT occupancy
#     of which  7,033 cells say RobPlugin        (incl. the branchTrainMem CARRY8 chain)
#               2,096 cells say IssueQueuePlugin
#   repaired filter (below)     36,431 cells / 23,545 LUT   -> 54.3% at the SAME grid
#
# So the legacy box was dragging a ROB carry chain and an IQ scoreboard slice into
# the LS region and then fighting for sites — 27% of its LUT demand was foreign.
# That is sufficient to explain why `dcache` mode routed at -2.665 ns versus
# -2.094 for `decode` and -2.187 for `none` (section 15 step 7).
#
# `IS_PRIMITIVE` IS LOAD-BEARING — do not drop it.  Excluding the foreign LEAF names
# is not enough on its own, because `get_cells -hier` also returns the HIERARCHICAL
# instance `LsEuPlugin_logic_sq` itself, whose own name carries no foreign substring;
# adding that one object re-constrains every leaf underneath it, ROB carry chain
# included, and silently undoes the repair.  Measured on the 6b246de netlist:
#   filter without IS_PRIMITIVE   36,431 objects (36,427 leaves + 4 hierarchical)
#                                 -> pblock reports 24,214 members, because ~9.4k
#                                    leaves fold into those 4 parents (and come along)
#   filter with    IS_PRIMITIVE   36,427 leaves -> pblock reports 33,613 members
# The LS-owned leaves inside those instances are still captured either way: they are
# named `LsEuPlugin_logic_sq/<...>`, so they match the prefix and are primitives.
# Only the absorbed foreign leaves are dropped, which is precisely the intent.
#
# GEOMETRY.  The legacy box was X36Y110:X87Y214, centred X61.5 Y162.  The measured
# centroid of the three plugins it is supposed to hold is X41.7 Y187.6 — the box was
# ~20 columns right of and ~25 rows below its own population, which is why the LS
# cells spilled.  Measured bboxes: Dcache X4..X84 Y139..Y239 (centroid X42Y210),
# LsEu X4..X95 Y55..Y237 (centroid X36Y163), Dtlb X49..X109 Y136..Y237 (centroid
# X82Y198).  The new grid is centred on that population:
#
#   SLICE_X14Y132:SLICE_X72Y239 = 59 x 108 = 6,372 slices = 50,976 LUT sites
#   -> 46.5% LUT-site, 11.7% FF-site occupancy for the repaired capture.
#
# It also pulls DtlbPlugin (centroid X81.5, 39 columns away from DcachePlugin)
# back toward the D-cache, which is the original `valids -> hrPpn` limiter this
# floorplan was created for in the first place.
#
# HONEST SCOPE.  On the 6b246de checkpoint this region does NOT hold the WNS path
# — all 300 worst endpoints are the frontend `stalled -> s1PredEntries` arc (see
# synth/floorplan_frontend.xdc).  The D-cache/IQ family is ~0.9 ns behind WNS
# (handoff section 16, worth 0.000 ns on the what-if ladder).  This box is
# therefore a TNS/failing-endpoint-breadth candidate, not a WNS candidate, and it
# must be judged on those columns.
create_pblock pb_backend
resize_pblock pb_backend -add {SLICE_X14Y132:SLICE_X72Y239}
add_cells_to_pblock pb_backend [get_cells -hier -filter {(NAME =~ *DcachePlugin_logic* || NAME =~ *DtlbPlugin_logic* || NAME =~ *LsEuPlugin_logic*) && NAME !~ *RobPlugin* && NAME !~ *IssueQueuePlugin* && NAME !~ *RenameStage* && NAME !~ *DecodeStage* && NAME !~ *AluEuPlugin* && NAME !~ *DivEuPlugin* && NAME !~ *BranchEuPlugin* && NAME !~ *FetchAlignPlugin* && NAME !~ *IcachePlugin* && IS_PRIMITIVE}]
