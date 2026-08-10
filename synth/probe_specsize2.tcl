# Confirming what-if for handoff section 16: is the `spec_size -> predictPending / RAS`
# family worth anything, and how long is the slack tail toward 200 MHz?
# Read-only; opens a routed checkpoint and never writes it.

set dcp "synth/archive/6b246de_ftb_framing_retime_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_specsize"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }

proc famkey {pin} {
  regsub {/[A-Z]+$} $pin {} c
  regsub {\[[0-9]+\]$} $c {} c
  return $c
}
proc say {s} { puts "PROBE $s" }

# Run a ladder of $n rungs, writing to $file; $freeEndpoints = extra -to false paths first.
proc ladder {n file tag} {
  set fp [open $file w]
  puts $fp "rung  WNS      delta   worst startpoint -> endpoint"
  set prev ""
  for {set i 0} {$i < $n} {incr i} {
    set p [lindex [get_timing_paths -max_paths 1 -nworst 1 -delay_type max] 0]
    if {$p eq ""} { break }
    set s [get_property SLACK $p]
    set sp [get_property STARTPOINT_PIN $p]
    set ep [get_property ENDPOINT_PIN $p]
    set d [expr {$prev eq "" ? 0.0 : $s - $prev}]
    set line [format "%4d  %7.3f  %+6.3f   %s -> %s" $i $s $d $sp $ep]
    puts $fp $line
    if {$i % 10 == 0} { say "$tag $line" }
    set prev $s
    set fk [famkey $sp]
    set cells [get_cells -hierarchical -filter "NAME =~ ${fk}*" -quiet]
    if {[llength $cells] == 0} { set cells [get_cells $fk -quiet] }
    if {[llength $cells] == 0} { say "$tag cannot resolve $fk, stop at rung $i"; break }
    set_false_path -from $cells
    if {$s > -1.000} { say "$tag REACHED -1.000 (200 MHz) at rung $i"; break }
  }
  close $fp
  say "$tag FINAL_WNS $prev after [expr {$i}] rungs"
  return $prev
}

# ---- Ladder R: reference, long tail (how far is 200 MHz by family cutting?) ----
open_checkpoint $dcp
say "REF start"
ladder 200 $out/ladder_ref200.txt REF
close_design

# ---- Ladder P: identical, but the whole predictPending/predictTarget/RAS endpoint
#      group is retired FIRST.  Divergence from R = what this family is really worth.
open_checkpoint $dcp
set eps {}
foreach pat {*FetchAlignPlugin_logic_predictPending_reg* *FetchAlignPlugin_logic_predictTargetReg_reg* *RasPlugin_logic_*} {
  set eps [concat $eps [get_cells -hier -filter "NAME =~ $pat" -quiet]]
}
say "P endpoint-group cells [llength $eps]"
set_false_path -to $eps
set p [lindex [get_timing_paths -max_paths 1 -nworst 1 -delay_type max] 0]
say "P WNS-after-endpoint-free [get_property SLACK $p]  [get_property STARTPOINT_PIN $p] -> [get_property ENDPOINT_PIN $p]"
ladder 200 $out/ladder_nopredict200.txt P
say "DONE2"
