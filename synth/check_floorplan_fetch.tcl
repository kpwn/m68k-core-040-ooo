# Verification pass for synth/floorplan_fetch.xdc (M5, plan Task 15 step 5), kept in
# synth/ rather than /tmp so Task 16's reviewer can re-run it verbatim. Read-only:
# opens an existing checkpoint, reads XDC, reports. It places nothing and routes
# nothing, and it reads no timing (GC-1).
#
# It answers four questions:
#   1. does the XDC parse and create pb_fetch with the intended grid?
#   2. FETCH_CONTAMINATION -- did the capture filter drag in a foreign plugin? (must be 0)
#   3. FETCH_RECONCILE -- the pblock's cell count is LOWER than the filter's because
#      `get_cells -of_objects [get_pblocks ...]` collapses distributed-RAM/wide-mux
#      macros to their parent. `pblock_not_in_filter` is the direction that would mean
#      a real defect, and must be 0.
#   4. COMBINED_DOUBLE_CLAIMED -- Task 16 gates FLOORPLAN_MODE=decode+fetch, so prove
#      pb_decode and pb_fetch never claim the same cell. (must be 0)
# Output of record: synth/m5_xdc_check.out.
open_checkpoint synth/m5_synth.dcp
read_xdc synth/floorplan_fetch.xdc
foreach pb [get_pblocks -quiet] {
  puts "FLOORPLAN [get_property NAME $pb] grid=[get_property GRID_RANGES $pb] cells=[llength [get_cells -quiet -of_objects $pb]]"
}
# Contamination check: no foreign-plugin cell may be captured.
set bad [get_cells -quiet -of_objects [get_pblocks pb_fetch] -filter {NAME =~ *RobPlugin* || NAME =~ *IssueQueuePlugin* || NAME =~ *DcachePlugin* || NAME =~ *LsEuPlugin* || NAME =~ *DecodeStage* || NAME =~ *RenameStage*}]
puts "FETCH_CONTAMINATION [llength $bad]"
# Reconcile the pblock's own cell count against the capture filter's, so the
# difference is an explained accounting artefact and not a silently dropped subset.
set filt {(NAME =~ *IcachePlugin_logic* || NAME =~ *FetchAlignPlugin_logic* || NAME =~ *FtbPlugin_logic* || NAME =~ *GsharePlugin_logic* || NAME =~ *RasPlugin_logic* || NAME =~ *BtbPlugin_logic*) && NAME !~ *DecodeStage* && NAME !~ *RobPlugin* && NAME !~ *IssueQueuePlugin* && NAME !~ *RenameStage* && NAME !~ *DcachePlugin* && NAME !~ *LsEuPlugin* && IS_PRIMITIVE}
array set inPb {}
foreach c [get_cells -quiet -of_objects [get_pblocks pb_fetch]] { set inPb([get_property NAME $c]) 1 }
array set inFilt {}
foreach c [get_cells -hier -filter $filt] { set inFilt([get_property NAME $c]) 1 }
array set missRef {}; set nmiss 0
foreach n [array names inFilt] {
  if {![info exists inPb($n)]} {
    set r [get_property REF_NAME [get_cells $n]]
    if {[info exists missRef($r)]} { incr missRef($r) } else { set missRef($r) 1 }
    incr nmiss
  }
}
puts "FETCH_RECONCILE filter_not_in_pblock=$nmiss"
foreach r [lsort [array names missRef]] { puts "FETCH_RECONCILE_REF $r $missRef($r)" }
set nextra 0
foreach n [array names inPb] { if {![info exists inFilt($n)]} { incr nextra } }
puts "FETCH_RECONCILE pblock_not_in_filter=$nextra"
# Vivado's OWN site accounting for the box, which is the authoritative occupancy
# number the hand sizing arithmetic has to agree with.
# To STDOUT as well as to a file: synth/*.rpt is gitignored by repo policy, and these
# numbers are the load-bearing sizing evidence for synth/floorplan_fetch.xdc, so they
# have to survive in a committed artifact.
report_utilization -pblocks pb_fetch -file synth/m5_pb_fetch_util.rpt
puts "== report_utilization -pblocks pb_fetch =="
puts [report_utilization -pblocks pb_fetch -return_string]

# Task 16 gates FLOORPLAN_MODE=decode+fetch. Prove the two boxes coexist on this
# netlist: both created, disjoint cell sets, no cell claimed twice.
read_xdc synth/floorplan_decode.xdc
foreach pb [get_pblocks -quiet] {
  puts "COMBINED [get_property NAME $pb] grid=[get_property GRID_RANGES $pb] cells=[llength [get_cells -quiet -of_objects $pb]]"
}
array set inDec {}
foreach c [get_cells -quiet -of_objects [get_pblocks pb_decode]] { set inDec([get_property NAME $c]) 1 }
set dup 0
foreach n [array names inPb] { if {[info exists inDec($n)]} { incr dup } }
puts "COMBINED_DOUBLE_CLAIMED $dup"
puts "FETCH_XDC_CHECK_DONE"
