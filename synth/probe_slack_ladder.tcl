# Read-only post-route interrogation: what is each limiting family actually WORTH?
#
# Motivation (handoff section 15, 2026-08-10). A top-100 STARTPOINT census counts
# which register happens to arrive last into a shared cone; it does NOT measure how
# much that register costs. At the `6b246de` checkpoint one register was the
# startpoint of 2,673 of the worst 4,000 unique-endpoint paths and worth 0.020 ns,
# because the inputs immediately behind it backfilled the same cone. Five earlier
# cuts in this campaign were designed off a census alone; this script is the cheap
# (~40 s) check that should precede the next one.
#
#   DCP=synth/archive/<ckpt>/fullcore_routed.dcp \
#   LADDER_STEPS=8 CENSUS_PATHS=4000 OUT=synth/probe_ladder \
#   vivado -mode batch -nojournal -source synth/probe_slack_ladder.tcl
#
# Applies `set_false_path` to the current worst startpoint register, re-reports WNS,
# and repeats. The delta between consecutive rungs is what removing that family is
# worth AT THE CURRENT PLACEMENT -- a fair predictor for a cut that deletes little
# logic, and a lower bound for one that deletes a real cone (the placer then has
# something to exploit; compare `28ec738`/`6b246de`, which each deleted real logic
# and beat their static prediction).
#
# This script never writes the design. It only reads a routed checkpoint.

set dcp "synth/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set steps 8
if {[info exists ::env(LADDER_STEPS)]} { set steps $::env(LADDER_STEPS) }
set censusN 4000
if {[info exists ::env(CENSUS_PATHS)]} { set censusN $::env(CENSUS_PATHS) }
set out "synth/probe_ladder"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }

open_checkpoint $dcp
file mkdir $out
puts "LADDER_DCP $dcp"

proc worstpath {} {
  return [lindex [get_timing_paths -max_paths 1 -nworst 1 -delay_type max] 0]
}
# "FooPlugin_logic_bar_reg[12]/C" -> "FooPlugin_logic_bar_reg" (the family key)
proc famkey {pin} {
  regsub {/[A-Z]+$} $pin {} c
  regsub {\[[0-9]+\]$} $c {} c
  return $c
}

# ---- family census + slack histogram over the worst N unique endpoints -------
set ps [get_timing_paths -max_paths $censusN -nworst 1 -delay_type max]
array set bysrc {} ; array set bydst {} ; array set hist {}
foreach p $ps {
  set sk [famkey [get_property STARTPOINT_PIN $p]]
  set dk [famkey [get_property ENDPOINT_PIN $p]]
  set b [expr {int(floor([get_property SLACK $p]*10.0))/10.0}]
  foreach {arr key} [list bysrc $sk bydst $dk hist $b] {
    upvar 0 $arr a
    if {[info exists a($key)]} { incr a($key) } else { set a($key) 1 }
  }
}
set fp [open $out/family_census.txt w]
puts $fp "==== slack histogram, worst $censusN unique endpoints (0.1 ns buckets) ===="
foreach k [lsort -real [array names hist]] { puts $fp [format "%8.1f  %6d" $k $hist($k)] }
puts $fp "\n==== by STARTPOINT register (sort -rn to rank) ===="
foreach k [array names bysrc] { puts $fp [format "%6d  %s" $bysrc($k) $k] }
puts $fp "\n==== by ENDPOINT register (sort -rn to rank) ===="
foreach k [array names bydst] { puts $fp [format "%6d  %s" $bydst($k) $k] }
close $fp

# ---- the ladder --------------------------------------------------------------
set fp [open $out/ladder.txt w]
puts $fp "rung  WNS(ns)   delta   worst startpoint -> endpoint"
set prev ""
for {set i 0} {$i <= $steps} {incr i} {
  set p [worstpath]
  if {$p eq ""} { break }
  set s [get_property SLACK $p]
  set sp [get_property STARTPOINT_PIN $p]
  set ep [get_property ENDPOINT_PIN $p]
  set d [expr {$prev eq "" ? 0.0 : $s - $prev}]
  set line [format "%4d  %7.3f  %+6.3f   %s -> %s" $i $s $d $sp $ep]
  puts $fp $line ; puts "LADDER $line"
  set prev $s
  if {$i == $steps} { break }
  # retire this family and see what it was worth
  set fk [famkey $sp]
  set cells [get_cells -hierarchical -filter "NAME =~ ${fk}*"]
  if {[llength $cells] == 0} { set cells [get_cells $fk] }
  set_false_path -from $cells
}
close $fp
puts "LADDER_DONE $out/ladder.txt $out/family_census.txt"
