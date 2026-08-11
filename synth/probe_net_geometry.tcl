# Read-only PHYSICAL grounding of the two nets handoff section 25.4 named as the
# concrete place a floorplan attempt should aim:
#     FetchAlignPlugin_logic_ibuf/when_PipeStage_l17   fo=462  0.540 ns
#     DecodeStage_logic_queue/p_1542_in                fo=875  0.409 ns
#
# The question this script answers is NOT "how big is the fanout" (already known)
# but "WHERE are the driver and the loads physically, and is the route delay
# DISTANCE or LOAD?".  Section 18 step 2 established the distinction matters: a
# 24-48 CLB span that is still 66 % route is a load problem, and no pblock fixes
# a load problem.
#
#   DCP=synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp \
#   OUT=synth/probe_netgeo \
#   vivado -mode batch -nojournal -nolog -source synth/probe_net_geometry.tcl
#
# Never writes the design.

set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_netgeo"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }

open_checkpoint $dcp
file mkdir $out
puts "NETGEO_DCP $dcp"

# ---- baseline reproduction control (section 19 step 1 discipline) -----------
set wns [get_property SLACK [lindex [get_timing_paths -max_paths 1 -delay_type max] 0]]
puts "CONTROL_WNS $wns"

proc siteXY {loc} {
  # SLICE_X57Y20 -> {57 20}; returns {} if unparseable
  if {[regexp {X(-?[0-9]+)Y(-?[0-9]+)} $loc -> x y]} { return [list $x $y] }
  return {}
}

# Geometry of one net: driver LOC, load LOC bbox/centroid, distance histogram.
proc netgeo {fp netname} {
  set nets [get_nets -quiet $netname]
  if {[llength $nets] == 0} {
    puts $fp "NET $netname  ** NOT FOUND **"
    return
  }
  foreach n $nets {
    set fo [get_property FLAT_PIN_COUNT $n]
    set segs [get_nets -quiet -segments -of_objects $n]
    set drvpin [get_pins -quiet -leaf -of_objects $segs -filter {DIRECTION == OUT}]
    set drvcell {}
    set drvloc {}
    set drvtype {}
    if {[llength $drvpin] > 0} {
      set drvcell [get_cells -quiet -of_objects [lindex $drvpin 0]]
      if {[llength $drvcell] > 0} {
        set drvloc  [get_property LOC [lindex $drvcell 0]]
        set drvtype [get_property REF_NAME [lindex $drvcell 0]]
      }
    }
    puts $fp "===================================================================="
    puts $fp "NET        $n"
    puts $fp "  FLAT_PIN_COUNT $fo   ROUTE_STATUS [get_property ROUTE_STATUS $n]"
    puts $fp "  DRIVER     [lindex $drvcell 0]"
    puts $fp "  DRIVER_REF $drvtype   DRIVER_LOC $drvloc"

    set dxy [siteXY $drvloc]
    # every load cell
    set lpins [get_pins -quiet -leaf -of_objects $segs -filter {DIRECTION == IN}]
    set lcells [get_cells -quiet -of_objects $lpins]
    set n_l [llength $lcells]
    set minx 9999 ; set maxx -1 ; set miny 9999 ; set maxy -1
    set sumx 0 ; set sumy 0 ; set cnt 0
    array set dhist {}
    array set refcount {}
    set maxman 0
    foreach c $lcells {
      set l [get_property LOC $c]
      set xy [siteXY $l]
      if {[llength $xy] == 0} { continue }
      lassign $xy x y
      if {$x < $minx} {set minx $x} ; if {$x > $maxx} {set maxx $x}
      if {$y < $miny} {set miny $y} ; if {$y > $maxy} {set maxy $y}
      incr sumx $x ; incr sumy $y ; incr cnt
      set r [get_property REF_NAME $c]
      if {[info exists refcount($r)]} { incr refcount($r) } else { set refcount($r) 1 }
      if {[llength $dxy] == 2} {
        lassign $dxy dx dy
        set man [expr {abs($x-$dx) + abs($y-$dy)}]
        if {$man > $maxman} { set maxman $man }
        set b [expr {int($man/10)*10}]
        if {[info exists dhist($b)]} { incr dhist($b) } else { set dhist($b) 1 }
      }
    }
    if {$cnt > 0} {
      puts $fp "  LOADS      $n_l cells, $cnt placed"
      puts $fp "  LOAD_BBOX  X$minx..X$maxx  Y$miny..Y$maxy   (span [expr {$maxx-$minx}] x [expr {$maxy-$miny}] CLB)"
      puts $fp "  LOAD_CENTR X[format %.1f [expr {double($sumx)/$cnt}]] Y[format %.1f [expr {double($sumy)/$cnt}]]"
      puts $fp "  MAX_MANHATTAN_DRIVER_TO_LOAD $maxman"
      puts $fp "  LOAD_DIST_HISTOGRAM (Manhattan CLB from driver):"
      foreach b [lsort -integer [array names dhist]] {
        puts $fp [format "     %4d-%4d : %6d" $b [expr {$b+9}] $dhist($b)]
      }
      puts $fp "  LOAD_REF_TYPES:"
      foreach r [lsort [array names refcount]] { puts $fp "     $r : $refcount($r)" }
    }
    array unset dhist ; array unset refcount
  }
}

set fp [open $out/net_geometry.txt w]
puts $fp "DCP $dcp"
puts $fp "CONTROL_WNS $wns"

# The two nets section 25.4 named, plus every sibling PipeStage-l17 enable and
# every p_*_in in the decode queue, matched by wildcard so a renamed replica is
# not missed.
foreach pat {
  "FetchAlignPlugin_logic_ibuf/when_PipeStage_l17"
  "*ibuf/when_PipeStage_l17*"
  "DecodeStage_logic_queue/p_1542_in"
  "DecodeStage_logic_queue/when_PipeStage_l17*"
} {
  puts $fp ""
  puts $fp "#### PATTERN $pat"
  netgeo $fp $pat
}
close $fp
puts "WROTE $out/net_geometry.txt"

# ---- every BUFG-driven net that is NOT a clock ------------------------------
# fullcore_fanout.rpt showed DecodeStage_logic_queue/when_PipeStage_l17_2 with
# Driver Type BUFGCE at fanout 1056.  A control net on the global clock network
# has a large insertion delay; if any such net is on a failing path that is a
# tool decision worth knowing about, and it is disableable
# (`opt_design -bufg_opt_lower_limit` / place_design behaviour).
set fp2 [open $out/bufg_nets.txt w]
set bufgs [get_cells -quiet -hier -filter {REF_NAME =~ BUFG*}]
puts $fp2 "BUFG_CELL_COUNT [llength $bufgs]"
foreach b $bufgs {
  set opin [get_pins -quiet -of_objects $b -filter {DIRECTION == OUT}]
  set onet [get_nets -quiet -of_objects $opin]
  set ipin [get_pins -quiet -of_objects $b -filter {DIRECTION == IN && REF_PIN_NAME == I}]
  set inet [get_nets -quiet -of_objects $ipin]
  set fo 0
  if {[llength $onet] > 0} { set fo [get_property FLAT_PIN_COUNT [lindex $onet 0]] }
  puts $fp2 "BUFG $b  REF [get_property REF_NAME $b]  LOC [get_property LOC $b]"
  puts $fp2 "   IN_NET  [lindex $inet 0]"
  puts $fp2 "   OUT_NET [lindex $onet 0]  fanout $fo"
}
close $fp2
puts "WROTE $out/bufg_nets.txt"

# ---- net-by-net breakdown of the current worst paths ------------------------
# For each of the worst N paths: every net on it, its fanout, its delay, and the
# placed coordinates of the cell at each end -- so "distance vs load" is decided
# per hop rather than for the path as a whole.
set NPATH 6
if {[info exists ::env(NPATH)]} { set NPATH $::env(NPATH) }
set fp3 [open $out/worst_path_hops.txt w]
set paths [get_timing_paths -max_paths $NPATH -nworst 1 -delay_type max]
set pi 0
foreach p $paths {
  incr pi
  puts $fp3 "===================================================================="
  puts $fp3 "PATH #$pi  slack [get_property SLACK $p]  levels [get_property LOGIC_LEVELS $p]"
  puts $fp3 "  START [get_property STARTPOINT_PIN $p]"
  puts $fp3 "  END   [get_property ENDPOINT_PIN $p]"
  set prevxy {}
  set pnets [get_nets -quiet -of_objects $p]
  puts $fp3 [format "  %-70s %8s %8s %-14s %-14s %6s" NET FANOUT DELAY DRIVER_LOC LOAD_BBOX MAXMAN]
  foreach n $pnets {
    set fo [get_property FLAT_PIN_COUNT $n]
    set dly [get_property -quiet DELAY $n]
    set segs [get_nets -quiet -segments -of_objects $n]
    set drvpin [get_pins -quiet -leaf -of_objects $segs -filter {DIRECTION == OUT}]
    set drvloc "-"
    set dxy {}
    if {[llength $drvpin] > 0} {
      set dc [get_cells -quiet -of_objects [lindex $drvpin 0]]
      if {[llength $dc] > 0} { set drvloc [get_property LOC [lindex $dc 0]] ; set dxy [siteXY $drvloc] }
    }
    set lpins [get_pins -quiet -leaf -of_objects $segs -filter {DIRECTION == IN}]
    set lcells [get_cells -quiet -of_objects $lpins]
    set minx 9999 ; set maxx -1 ; set miny 9999 ; set maxy -1 ; set maxman 0
    foreach c $lcells {
      set xy [siteXY [get_property LOC $c]]
      if {[llength $xy] == 0} { continue }
      lassign $xy x y
      if {$x < $minx} {set minx $x} ; if {$x > $maxx} {set maxx $x}
      if {$y < $miny} {set miny $y} ; if {$y > $maxy} {set maxy $y}
      if {[llength $dxy] == 2} {
        lassign $dxy dx dy
        set man [expr {abs($x-$dx)+abs($y-$dy)}]
        if {$man > $maxman} { set maxman $man }
      }
    }
    set bbox "-"
    if {$maxx >= 0} { set bbox "X$minx-$maxx,Y$miny-$maxy" }
    puts $fp3 [format "  %-70s %8s %8s %-14s %-14s %6d" $n $fo $dly $drvloc $bbox $maxman]
  }
}
close $fp3
puts "WROTE $out/worst_path_hops.txt"

# ---- how deep is the 200 MHz deficit, on THIS placement ---------------------
# 200 MHz at a 4.000 ns constraint means every endpoint must reach slack >= -1.000.
set fp4 [open $out/deficit_population.txt w]
foreach thr {-1.000 -1.200 -1.400 -1.450} {
  set ps [get_timing_paths -max_paths 200000 -nworst 1 -delay_type max -slack_lesser_than $thr]
  puts $fp4 "ENDPOINTS_BELOW $thr : [llength $ps]"
}
close $fp4
puts "WROTE $out/deficit_population.txt"
puts "NETGEO_DONE"
