# Read-only grounding probe for the `spec_size -> predictPending / RAS` family.
# See handoff section 16. Never writes the design; opens a routed checkpoint only.
#
#   DCP=synth/archive/6b246de_ftb_framing_retime_decode/fullcore_routed.dcp \
#   OUT=synth/probe_specsize vivado -mode batch -nojournal -source synth/probe_specsize.tcl

set dcp "synth/archive/6b246de_ftb_framing_retime_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_specsize"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }

open_checkpoint $dcp
file mkdir $out

proc famkey {pin} {
  regsub {/[A-Z]+$} $pin {} c
  regsub {\[[0-9]+\]$} $c {} c
  return $c
}
proc wns {} {
  set p [lindex [get_timing_paths -max_paths 1 -nworst 1 -delay_type max] 0]
  if {$p eq ""} { return "none" }
  return [list [get_property SLACK $p] [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
}
proc say {s} { puts "PROBE $s" }

set base [wns]
say "BASELINE WNS [lindex $base 0]  [lindex $base 1] -> [lindex $base 2]"

# ---------------------------------------------------------------- family scope
# All spec_size startpoint registers (both slots, all bits, incl. _zz_ mangling).
set specCells [get_cells -hier -filter {NAME =~ *fed_payload_specs_*_spec_size_reg*} -quiet]
say "SPECSIZE_CELLS [llength $specCells]"
foreach c $specCells { say "  cell $c" }

# 1. worst slack of the WHOLE family, any endpoint
set ps [get_timing_paths -from $specCells -max_paths 4000 -nworst 1 -delay_type max -quiet]
say "SPECSIZE_PATHS [llength $ps]"
if {[llength $ps] > 0} {
  set w [lindex $ps 0]
  say "SPECSIZE_WORST [get_property SLACK $w]  [get_property STARTPOINT_PIN $w] -> [get_property ENDPOINT_PIN $w]"
  report_timing -from $specCells -max_paths 5 -nworst 1 -file $out/specsize_worst5.rpt
  array set dst {}
  foreach p $ps {
    set k [famkey [get_property ENDPOINT_PIN $p]]
    if {[info exists dst($k)]} { incr dst($k) } else { set dst($k) 1 }
  }
  set fp [open $out/specsize_endpoints.txt w]
  foreach k [array names dst] { puts $fp [format "%6d  %s" $dst($k) $k] }
  close $fp
}

# 2. worst slack INTO the predictPending / RAS endpoints, from any startpoint
foreach {tag pat} {
  predictPending  {*FetchAlignPlugin_logic_predictPending_reg*}
  predictTarget   {*FetchAlignPlugin_logic_predictTargetReg_reg*}
  ras_all         {*RasPlugin_logic_*}
} {
  set cs [get_cells -hier -filter "NAME =~ $pat" -quiet]
  if {[llength $cs] == 0} { say "ENDPOINT $tag  NO CELLS"; continue }
  set p [lindex [get_timing_paths -to $cs -max_paths 1 -nworst 1 -delay_type max -quiet] 0]
  if {$p eq ""} { say "ENDPOINT $tag  NO PATHS"; continue }
  say "ENDPOINT $tag ([llength $cs] cells) worst [get_property SLACK $p]  from [get_property STARTPOINT_PIN $p]"
  report_timing -to $cs -max_paths 8 -nworst 1 -file $out/to_$tag.rpt
}

# 3. isolated what-if: retire ONLY this family, from the pristine baseline
set_false_path -from $specCells
set a [wns]
say "WHATIF_A specsize-free WNS [lindex $a 0]  [lindex $a 1] -> [lindex $a 2]"

# 4. ladder toward the plateau: retire worse families one at a time and see
#    where spec_size lands / when it becomes binding.  Restart clean first.
close_design
open_checkpoint $dcp
say "RELOADED"
set prev ""
set fp [open $out/ladder_to_specsize.txt w]
puts $fp "rung  WNS      delta   worst startpoint -> endpoint"
for {set i 0} {$i < 40} {incr i} {
  set p [lindex [get_timing_paths -max_paths 1 -nworst 1 -delay_type max] 0]
  if {$p eq ""} { break }
  set s [get_property SLACK $p]
  set sp [get_property STARTPOINT_PIN $p]
  set ep [get_property ENDPOINT_PIN $p]
  set d [expr {$prev eq "" ? 0.0 : $s - $prev}]
  set line [format "%4d  %7.3f  %+6.3f   %s -> %s" $i $s $d $sp $ep]
  puts $fp $line ; say "LADDER $line"
  set prev $s
  if {[string match "*spec_size*" $sp]} { say "LADDER spec_size BECAME BINDING at rung $i, WNS $s"; }
  set fk [famkey $sp]
  set cells [get_cells -hierarchical -filter "NAME =~ ${fk}*" -quiet]
  if {[llength $cells] == 0} { set cells [get_cells $fk -quiet] }
  if {[llength $cells] == 0} { say "LADDER cannot resolve $fk, stop"; break }
  set_false_path -from $cells
  if {$s > -1.400} { say "LADDER reached -1.400 band, stop"; break }
}
close $fp
say "DONE"
