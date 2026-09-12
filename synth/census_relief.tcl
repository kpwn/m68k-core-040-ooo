# synth/census_relief.tcl -- one-off G-T1c helper: re-derive the FMax relief cap.
# Computes the worst slack among failing endpoints NOT reached through the spec2
# (Lever D slot-1 BTB read-port) cells, i.e. what WNS would become if every spec2
# endpoint were perfectly fixed. Not part of the reusable census.tcl gate script --
# ad hoc, run once for the Task 1 G-T1c verdict.
set dcp [lindex $argv 0]
open_checkpoint $dcp

set allPaths [get_timing_paths -setup -max_paths 5000 -nworst 1 -slack_lesser_than 0]
puts "RELIEF_TOTAL_FAILING [llength $allPaths]"

set cells [get_cells -quiet -hierarchical -filter "NAME =~ *BtbPlugin_logic_mem_reg_r4*"]
set spec2Paths [get_timing_paths -quiet -setup -max_paths 5000 -nworst 1 -slack_lesser_than 0 -through $cells]
array set spec2Ep {}
foreach p $spec2Paths {
  set nm [get_property NAME [get_property ENDPOINT_PIN $p]]
  set spec2Ep($nm) 1
}
puts "RELIEF_SPEC2_ENDPOINT_COUNT [array size spec2Ep]"

set worstNonSpec2 99.0
set worstNonSpec2Name ""
set tns 0.0
set tnsNonSpec2 0.0
foreach p $allPaths {
  set nm [get_property NAME [get_property ENDPOINT_PIN $p]]
  set s  [get_property SLACK $p]
  set tns [expr {$tns + $s}]
  if {![info exists spec2Ep($nm)]} {
    set tnsNonSpec2 [expr {$tnsNonSpec2 + $s}]
    if {$s < $worstNonSpec2} { set worstNonSpec2 $s ; set worstNonSpec2Name $nm }
  }
}
puts "RELIEF_TNS_ALL $tns"
puts "RELIEF_TNS_NONSPEC2 $tnsNonSpec2"
puts "RELIEF_WORST_NONSPEC2_SLACK $worstNonSpec2"
puts "RELIEF_WORST_NONSPEC2_ENDPOINT $worstNonSpec2Name"
set reliefFmax [expr {1000.0/(4.000 - $worstNonSpec2)}]
puts "RELIEF_FMAX_MHZ_IF_SPEC2_FIXED $reliefFmax"
