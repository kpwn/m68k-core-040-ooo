# TNS / failing-endpoint deltas for the spec_size -> predictPending / RAS family (handoff sec 17).
set dcp "synth/archive/6b246de_ftb_framing_retime_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_specsize"
open_checkpoint $dcp
proc snap {tag} {
  set ts [report_timing_summary -no_header -no_detailed_paths -return_string]
  set p [lindex [get_timing_paths -max_paths 1 -nworst 1 -delay_type max] 0]
  set wns [get_property SLACK $p]
  set tns "?"; set fep "?"
  foreach ln [split $ts "\n"] {
    if {[regexp {^\s*(-?[0-9]+\.[0-9]+)\s+(-?[0-9]+\.[0-9]+)\s+([0-9]+)\s+([0-9]+)} $ln -> a b c d]} { set tns $b; set fep $c; break }
  }
  puts "PROBE3 $tag WNS $wns TNS $tns FEP $fep  [get_property STARTPOINT_PIN $p] -> [get_property ENDPOINT_PIN $p]"
}
snap baseline
set specCells [get_cells -hier -filter {NAME =~ *fed_payload_specs_*_spec_size_reg*} -quiet]
set_false_path -from $specCells
snap "specsize_from_free([llength $specCells]cells)"
set eps {}
foreach pat {*FetchAlignPlugin_logic_predictPending_reg* *FetchAlignPlugin_logic_predictTargetReg_reg* *RasPlugin_logic_*} {
  set eps [concat $eps [get_cells -hier -filter "NAME =~ $pat" -quiet]]
}
set_false_path -to $eps
snap "plus_predict_ras_to_free([llength $eps]cells)"
puts "PROBE3 DONE"
