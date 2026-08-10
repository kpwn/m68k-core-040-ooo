# Read-only grounding probe for the DcachePlugin store-S1 paddr -> IssueQueuePlugin
# sbInt_busy family (handoff section 15 step 5, the second post-frontend family).
#
# Methodology per section 15's lesson: measure what the family COSTS with a
# set_false_path what-if ladder on the routed checkpoint, not what its startpoint
# census population is. Also measures TNS / failing-endpoint worth, because this
# campaign has twice seen TNS reductions convert into placement gains.
#
#   DCP=synth/archive/6b246de_ftb_framing_retime_decode/fullcore_routed.dcp \
#   OUT=synth/probe_dcache_iq \
#   vivado -mode batch -nojournal -source synth/probe_dcache_iq.tcl
#
# Never writes the design.

set dcp "synth/archive/6b246de_ftb_framing_retime_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_dcache_iq"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }

open_checkpoint $dcp
file mkdir $out
puts "PROBE_DCP $dcp"

proc worstpath {} {
  return [lindex [get_timing_paths -max_paths 1 -nworst 1 -delay_type max] 0]
}
proc famkey {pin} {
  regsub {/[A-Z]+$} $pin {} c
  regsub {\[[0-9]+\]$} $c {} c
  return $c
}
# WNS + TNS + failing endpoints, cheaply.
proc summarise {tag} {
  global out
  set f $out/summary_$tag.rpt
  report_timing_summary -no_detailed_paths -quiet -file $f
  set wns "?" ; set tns "?" ; set fep "?"
  set fh [open $f r]
  while {[gets $fh line] >= 0} {
    if {[regexp {^\s+(-?[0-9]+\.[0-9]+)\s+(-?[0-9]+\.[0-9]+)\s+(\d+)\s+(\d+)\s+(-?[0-9]+\.[0-9]+)} $line -> a b c d e]} {
      set wns $a ; set tns $b ; set fep $c ; break
    }
  }
  close $fh
  set p [worstpath]
  set line [format "%-28s WNS %8s  TNS %14s  FEP %8s   %s -> %s" $tag $wns $tns $fep \
    [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
  puts "PROBE $line"
  return $line
}

set log {}

# ---- 0. baseline ------------------------------------------------------------
lappend log [summarise 0_baseline]

# ---- 1. the family's own spread --------------------------------------------
set stCells [get_cells -hierarchical -filter {NAME =~ DcachePlugin_logic_stS1Payload_paddr_reg*}]
set sbCells [get_cells -hierarchical -filter {NAME =~ IssueQueuePlugin_logic_sbInt_busy_reg*}]
puts "PROBE stS1Payload_paddr cells: [llength $stCells]   sbInt_busy cells: [llength $sbCells]"

report_timing -from $stCells -to $sbCells -max_paths 20 -unique_pins -nworst 20 \
  -path_type summary -file $out/paths_st_to_sb.rpt
report_timing -from $stCells -max_paths 40 -unique_pins -nworst 40 \
  -path_type summary -file $out/paths_from_st.rpt
report_timing -to $sbCells -max_paths 40 -unique_pins -nworst 40 \
  -path_type summary -file $out/paths_to_sb.rpt
report_timing -from $stCells -to $sbCells -max_paths 1 -file $out/path_worst_detail.rpt

# census of what reaches sbInt_busy at all, and what stS1 paddr reaches
set ps [get_timing_paths -to $sbCells -max_paths 4000 -nworst 1 -delay_type max]
array set sbsrc {}
foreach p $ps {
  set k [famkey [get_property STARTPOINT_PIN $p]]
  if {[info exists sbsrc($k)]} { incr sbsrc($k) } else { set sbsrc($k) 1 }
}
set fh [open $out/sbInt_busy_startpoint_census.txt w]
puts $fh "startpoints reaching IssueQueuePlugin_logic_sbInt_busy (worst [llength $ps] unique endpoints)"
foreach k [array names sbsrc] { puts $fh [format "%6d  %s" $sbsrc($k) $k] }
close $fh

set ps [get_timing_paths -from $stCells -max_paths 4000 -nworst 1 -delay_type max]
array set stdst {}
foreach p $ps {
  set k [famkey [get_property ENDPOINT_PIN $p]]
  if {[info exists stdst($k)]} { incr stdst($k) } else { set stdst($k) 1 }
}
set fh [open $out/stS1_paddr_endpoint_census.txt w]
puts $fh "endpoints reached from DcachePlugin_logic_stS1Payload_paddr (worst [llength $ps])"
foreach k [array names stdst] { puts $fh [format "%6d  %s" $stdst($k) $k] }
close $fh

# ---- 2. what is the startpoint family worth, at the real baseline? ----------
set_false_path -from $stCells
lappend log [summarise 1_falsepath_from_stS1paddr]

# ---- 3. and the endpoint family on top? ------------------------------------
set_false_path -to $sbCells
lappend log [summarise 2_plus_falsepath_to_sbInt]

# ---- 4. now free the whole frontend cone so the D-cache/IQ region is exposed
#         (section 15 established the frontend ceiling is 0.294 ns) -----------
set anNets [get_nets -hierarchical -filter {NAME =~ *FetchAlignPlugin_logic_applyNow*}]
puts "PROBE applyNow nets: [llength $anNets]"
if {[llength $anNets] > 0} { set_false_path -through $anNets }
set stalledCells [get_cells -hierarchical -filter {NAME =~ FetchAlignPlugin_logic_stalled_reg*}]
if {[llength $stalledCells] > 0} { set_false_path -from $stalledCells }
lappend log [summarise 3_plus_frontend_free]

# ---- 5. ladder onward through whatever the D-cache/IQ region exposes --------
set fh [open $out/ladder.txt w]
puts $fh "rung  WNS(ns)   delta   worst startpoint -> endpoint"
set prev ""
for {set i 0} {$i <= 14} {incr i} {
  set p [worstpath]
  if {$p eq ""} { break }
  set s [get_property SLACK $p]
  set sp [get_property STARTPOINT_PIN $p]
  set ep [get_property ENDPOINT_PIN $p]
  set d [expr {$prev eq "" ? 0.0 : $s - $prev}]
  set line [format "%4d  %7.3f  %+6.3f   %s -> %s" $i $s $d $sp $ep]
  puts $fh $line ; puts "PROBE_LADDER $line"
  set prev $s
  if {$i == 14} { break }
  set fk [famkey $sp]
  set cells [get_cells -hierarchical -filter "NAME =~ ${fk}*"]
  if {[llength $cells] == 0} { set cells [get_cells $fk] }
  set_false_path -from $cells
}
close $fh

set fh [open $out/whatif_summary.txt w]
puts $fh "tag                          WNS        TNS             FEP        new worst path"
foreach l $log { puts $fh $l }
close $fh
puts "PROBE_DONE $out"
