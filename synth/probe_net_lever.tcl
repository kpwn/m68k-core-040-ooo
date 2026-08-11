# Read-only: what are the two nets named in handoff section 25.4 actually WORTH,
# and is a targeted physical intervention on them reachable?
#
# Section 25.4 named them from the residual path of the `a3_cluster` scenario --
# i.e. AFTER the three frontend arcs AND the entire LSU cluster were false-pathed.
# That does NOT establish that they limit the CURRENT design.  This script asks
# the question directly:
#
#   1. What is the worst path THROUGH each net today?  (If that slack is behind
#      WNS, no intervention on the net can move WNS until everything ahead of it
#      is fixed first.)
#   2. What does the net's own hop delay decompose into -- and what is the
#      theoretical best case if the hop went to zero?
#   3. A MAX_FANOUT-style intervention's ceiling, modelled the only way a static
#      what-if can model it: false-path the net's driver family outright.  That is
#      strictly MORE generous than any replication could be.
#   4. Same for the BUFG-driven control nets: is any of them on a failing path?
#
#   DCP=... OUT=synth/probe_netlever vivado -mode batch -nojournal -nolog \
#     -source synth/probe_net_lever.tcl
#
# Never writes the design.

set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_netlever"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }

open_checkpoint $dcp
file mkdir $out

set base [lindex [get_timing_paths -max_paths 1 -delay_type max] 0]
set basewns [get_property SLACK $base]
puts "CONTROL_WNS $basewns"
puts "CONTROL_START [get_property STARTPOINT_PIN $base]"
puts "CONTROL_END   [get_property ENDPOINT_PIN $base]"

set fp [open $out/net_lever.txt w]
puts $fp "DCP $dcp"
puts $fp "CONTROL_WNS $basewns"
puts $fp "CONTROL_START [get_property STARTPOINT_PIN $base]"
puts $fp "CONTROL_END   [get_property ENDPOINT_PIN $base]"
puts $fp ""

# ---- 1 + 2: the worst path THROUGH each candidate net ----------------------
set cands {
  "FetchAlignPlugin_logic_ibuf/when_PipeStage_l17"
  "DecodeStage_logic_queue/p_1542_in"
  "DecodeStage_logic_queue/when_PipeStage_l17_2"
  "*/when_PipeStage_l17"
  "*/when_PipeStage_l17_1"
  "*/when_PipeStage_l17_2"
  "*/when_PipeStage_l17_3"
}
puts $fp "==== worst path THROUGH each candidate net (baseline WNS $basewns) ===="
puts $fp [format "%-56s %8s %9s %9s  %s" NET FANOUT SLACK "vs WNS" "endpoint"]
foreach c $cands {
  set nets [get_nets -quiet $c]
  if {[llength $nets] == 0} { puts $fp [format "%-56s  ** NOT FOUND **" $c] ; continue }
  foreach n $nets {
    set fo [get_property FLAT_PIN_COUNT $n]
    set p [get_timing_paths -quiet -max_paths 1 -nworst 1 -delay_type max -through $n]
    if {[llength $p] == 0} {
      puts $fp [format "%-56s %8s %9s %9s  %s" $n $fo "-" "-" "no timed path"]
    } else {
      set s [get_property SLACK [lindex $p 0]]
      puts $fp [format "%-56s %8s %9.3f %9.3f  %s" $n $fo $s [expr {$s - $basewns}] \
                 [get_property ENDPOINT_PIN [lindex $p 0]]]
    }
  }
}
puts $fp ""

# ---- 3: the MAX_FANOUT ceiling, modelled as an outright deletion ------------
# Replication can at best remove the net's own delay from every path through it.
# False-pathing every path THROUGH the net is strictly more generous than that
# (it removes the whole path, not just one hop), so the resulting WNS is a hard
# upper bound on what any replication/relative-placement fix could buy.
proc wns {} { return [get_property SLACK [lindex [get_timing_paths -max_paths 1 -delay_type max] 0]] }
proc worst {} {
  set p [lindex [get_timing_paths -max_paths 1 -delay_type max] 0]
  return "[get_property STARTPOINT_PIN $p] -> [get_property ENDPOINT_PIN $p]"
}
puts $fp "==== upper bound on any targeted fix: false-path THROUGH the net ===="
puts $fp [format "%-60s %9s %9s  %s" SCENARIO WNS DELTA "new worst path"]
puts $fp [format "%-60s %9.3f %9.3f  %s" baseline $basewns 0.0 [worst]]

set n1 [get_nets -quiet "FetchAlignPlugin_logic_ibuf/when_PipeStage_l17"]
set n2 [get_nets -quiet "DecodeStage_logic_queue/p_1542_in"]
set n3 [get_nets -quiet "DecodeStage_logic_queue/when_PipeStage_l17_2"]

if {[llength $n1] > 0} {
  set_false_path -through $n1
  set w [wns] ; puts $fp [format "%-60s %9.3f %9.3f  %s" "net1 when_PipeStage_l17 (fo462)" $w [expr {$w-$basewns}] [worst]]
}
if {[llength $n2] > 0} {
  set_false_path -through $n2
  set w [wns] ; puts $fp [format "%-60s %9.3f %9.3f  %s" "+ net2 p_1542_in (fo875)" $w [expr {$w-$basewns}] [worst]]
}
if {[llength $n3] > 0} {
  set_false_path -through $n3
  set w [wns] ; puts $fp [format "%-60s %9.3f %9.3f  %s" "+ net3 when_PipeStage_l17_2 (BUFGCE fo1056)" $w [expr {$w-$basewns}] [worst]]
}
# maximal: every PipeStage slotFree enable in the design
set allpipe [get_nets -quiet "*/when_PipeStage_l17*"]
if {[llength $allpipe] > 0} {
  set_false_path -through $allpipe
  set w [wns]
  puts $fp [format "%-60s %9.3f %9.3f  %s" "+ ALL [llength $allpipe] PipeStage slotFree enables" $w [expr {$w-$basewns}] [worst]]
}
close $fp
puts "WROTE $out/net_lever.txt"
puts "NETLEVER_DONE"
