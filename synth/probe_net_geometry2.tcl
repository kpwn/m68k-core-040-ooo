# Read-only: the FULL leaf-level placement picture of the two nets handoff
# section 25.4 named, plus the endpoint-side control that is independent of any
# hierarchical-segment subtlety.
#
# Lesson from the first attempt (handoff section 26): `get_pins -of_objects <net>`
# returns only the pins on THAT hierarchical SEGMENT.  These nets have
# FLAT_PIN_COUNT 463 / 876 but only 4-36 pins on the segment the name resolves to,
# because the signal crosses several preserved hierarchy boundaries.  Neither
# `-segments` nor a bare `-leaf` recovers the rest reliably; the robust route is
# `all_fanout -flat -endpoints_only` from the driver pin, which follows the signal
# across boundaries.
#
#   DCP=... OUT=synth/probe_netgeo2 vivado -mode batch -nojournal -nolog \
#     -source synth/probe_net_geometry2.tcl
#
# Never writes the design.

set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_netgeo2"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }

open_checkpoint $dcp
file mkdir $out
set basewns [get_property SLACK [lindex [get_timing_paths -max_paths 1 -delay_type max] 0]]
puts "CONTROL_WNS $basewns"

proc siteXY {loc} {
  if {[regexp {X(-?[0-9]+)Y(-?[0-9]+)} $loc -> x y]} { return [list $x $y] }
  return {}
}

set fp [open $out/net_geometry2.txt w]
puts $fp "DCP $dcp"
puts $fp "CONTROL_WNS $basewns"

# ---- 1: full leaf fanout geometry via all_fanout ---------------------------
foreach netname {
  "FetchAlignPlugin_logic_ibuf/when_PipeStage_l17"
  "DecodeStage_logic_queue/p_1542_in"
  "DecodeStage_logic_queue/when_PipeStage_l17_2_bufg_place"
} {
  set n [get_nets -quiet $netname]
  puts $fp ""
  puts $fp "===================================================================="
  puts $fp "NET $netname"
  if {[llength $n] == 0} { puts $fp "  ** NOT FOUND **" ; continue }
  set n [lindex $n 0]
  puts $fp "  FLAT_PIN_COUNT [get_property FLAT_PIN_COUNT $n]"

  # driver: the one leaf OUT pin anywhere on the signal
  set dpin [get_pins -quiet -of_objects [get_nets -quiet -segments -of_objects $n] \
                     -filter {DIRECTION == OUT && IS_LEAF}]
  if {[llength $dpin] == 0} {
    set dpin [get_pins -quiet -of_objects $n -filter {DIRECTION == OUT}]
  }
  set dxy {}
  if {[llength $dpin] > 0} {
    set dc [get_cells -quiet -of_objects [lindex $dpin 0]]
    if {[llength $dc] > 0} {
      puts $fp "  DRIVER      [lindex $dc 0]"
      puts $fp "  DRIVER_REF  [get_property REF_NAME [lindex $dc 0]]   LOC [get_property LOC [lindex $dc 0]]"
      set dxy [siteXY [get_property LOC [lindex $dc 0]]]
    }
  }

  # every leaf load, followed across hierarchy boundaries
  set loads [get_pins -quiet -leaf -of_objects $n -filter {DIRECTION == IN}]
  if {[llength $loads] == 0 && [llength $dpin] > 0} {
    set loads [all_fanout -quiet -flat -endpoints_only -from [lindex $dpin 0]]
  }
  puts $fp "  LEAF_LOAD_PINS [llength $loads]"
  set cells [get_cells -quiet -of_objects $loads]
  set minx 9999 ; set maxx -1 ; set miny 9999 ; set maxy -1
  set sumx 0 ; set sumy 0 ; set cnt 0 ; set maxman 0
  array set dhist {}
  foreach c $cells {
    set xy [siteXY [get_property LOC $c]]
    if {[llength $xy] == 0} { continue }
    lassign $xy x y
    if {$x < $minx} {set minx $x} ; if {$x > $maxx} {set maxx $x}
    if {$y < $miny} {set miny $y} ; if {$y > $maxy} {set maxy $y}
    incr sumx $x ; incr sumy $y ; incr cnt
    if {[llength $dxy] == 2} {
      lassign $dxy dx dy
      set man [expr {abs($x-$dx)+abs($y-$dy)}]
      if {$man > $maxman} { set maxman $man }
      set b [expr {int($man/10)*10}]
      if {[info exists dhist($b)]} { incr dhist($b) } else { set dhist($b) 1 }
    }
  }
  if {$cnt > 0} {
    puts $fp "  LOAD_CELLS_PLACED $cnt"
    puts $fp "  LOAD_BBOX   X$minx..X$maxx  Y$miny..Y$maxy  (span [expr {$maxx-$minx}] x [expr {$maxy-$miny}] CLB)"
    puts $fp "  LOAD_CENTR  X[format %.1f [expr {double($sumx)/$cnt}]] Y[format %.1f [expr {double($sumy)/$cnt}]]"
    puts $fp "  MAX_MANHATTAN $maxman"
    foreach b [lsort -integer [array names dhist]] {
      puts $fp [format "     %4d-%4d CLB : %6d" $b [expr {$b+9}] $dhist($b)]
    }
  }
  array unset dhist
}

# ---- 2: the endpoint-side control ------------------------------------------
# Independent of any hierarchical-segment subtlety: what is the worst path INTO
# the endpoint that section 25.4's residual path terminates at?  If that equals
# the -through number, the segment question is moot.
puts $fp ""
puts $fp "==== endpoint-side control (segment-independent) ===="
foreach ep {
  "FetchAlignPlugin_logic_predictPending_reg/D"
  "IssueQueuePlugin_logic_sbNzvc_busy_reg[8]/D"
} {
  set p [get_timing_paths -quiet -max_paths 1 -nworst 1 -delay_type max -to [get_pins -quiet $ep]]
  if {[llength $p] == 0} { puts $fp "  $ep : no path" ; continue }
  set p [lindex $p 0]
  puts $fp [format "  %-52s slack %7.3f  (vs WNS %+.3f)  from %s" \
             $ep [get_property SLACK $p] [expr {[get_property SLACK $p]-$basewns}] \
             [get_property STARTPOINT_PIN $p]]
}

# ---- 3: how tied is the plateau?  distinct startpoint families per slack band
puts $fp ""
puts $fp "==== plateau density: distinct startpoint families per slack band ===="
proc famkey {pin} {
  regsub {/[A-Z]+$} $pin {} c
  regsub {\[[0-9]+\]$} $c {} c
  return $c
}
foreach thr {-1.450 -1.400 -1.300 -1.200 -1.000} {
  set ps [get_timing_paths -quiet -max_paths 200000 -nworst 1 -delay_type max -slack_lesser_than $thr]
  array unset fams
  array set fams {}
  foreach p $ps { set fams([famkey [get_property STARTPOINT_PIN $p]]) 1 }
  puts $fp [format "  slack < %6.3f : %6d endpoints, %5d distinct startpoint families" \
             $thr [llength $ps] [llength [array names fams]]]
}
close $fp
puts "WROTE $out/net_geometry2.txt"
puts "NETGEO2_DONE"
