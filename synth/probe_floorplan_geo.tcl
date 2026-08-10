# Read-only path-geography census for the floorplan A/B campaign (handoff section 18).
#
# Census 1: per-plugin LUT / FF / CARRY / MUXF site demand, so a proposed pblock can
#           be sized against LUT SITES (8/slice) and FF sites (16/slice) rather than
#           against a raw "cell" count that mixes the two.
# Census 2: for the worst N paths, the placed coordinates of the startpoint and the
#           endpoint, plus the Manhattan span -- i.e. which cross-region arcs actually
#           dominate the failing population, not just the single WNS path.
# Census 3: the WNS path's logic-vs-route split and per-hop fan-out profile, which
#           is what distinguishes a distance problem (floorplan lever) from a
#           high-fan-out broadcast problem (replication lever).
#
# Usage:
#   DCP=... PATHS=200 vivado -mode batch -nojournal -nolog -source synth/probe_floorplan_geo.tcl

set dcp "synth/archive/6b246de_ftb_framing_retime_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set npaths 300
if {[info exists ::env(PATHS)]} { set npaths $::env(PATHS) }
open_checkpoint $dcp
puts "GEO_DCP $dcp"

set plugins {DecodeStage FetchAlignPlugin IcachePlugin FtbPlugin GsharePlugin RasPlugin BtbPlugin \
             DcachePlugin DtlbPlugin ItlbPlugin LsEuPlugin IssueQueuePlugin RobPlugin RenameStage \
             AluEuPlugin BranchEuPlugin DivEuPlugin RegFilePluginInt RegFilePluginNzvc}

# ---- census 1: site demand ----
puts "== SITE DEMAND (LUT sites = 8/slice, FF sites = 16/slice) =="
foreach p $plugins {
  set cs [get_cells -hier -quiet -filter "NAME =~ *${p}_logic*"]
  set nl 0; set nf 0; set nc 0; set nm 0; set no 0
  foreach c $cs {
    set r [get_property REF_NAME $c]
    if {[string match "LUT*" $r]} { incr nl } \
    elseif {[string match "FD*" $r]} { incr nf } \
    elseif {[string match "CARRY*" $r]} { incr nc } \
    elseif {[string match "MUXF*" $r]} { incr nm } \
    else { incr no }
  }
  puts [format "SITES %-20s total=%-6d LUT=%-6d FF=%-6d CARRY=%-5d MUXF=%-5d other=%-5d  min_slices=%d" \
    $p [llength $cs] $nl $nf $nc $nm $no \
    [expr {int(ceil(($nl+$nc)/8.0)) > int(ceil($nf/16.0)) ? int(ceil(($nl+$nc)/8.0)) : int(ceil($nf/16.0))}]]
}

# ---- census 2: worst-path geography ----
puts "== PATH GEOGRAPHY, worst $npaths unique endpoints =="
proc plugin_of {name plugins} {
  foreach p $plugins {
    if {[string first "${p}_logic" $name] >= 0} { return $p }
  }
  return "OTHER"
}
proc xy {cellname} {
  set c [get_cells -quiet $cellname]
  if {$c eq ""} { return {-1 -1} }
  set loc [get_property LOC $c]
  if {[regexp {SLICE_X(\d+)Y(\d+)} $loc -> x y]} { return [list $x $y] }
  return {-1 -1}
}
array set pairspan {}
array set paircount {}
array set pairslack {}
set paths [get_timing_paths -max_paths $npaths -nworst 1 -setup]
set i 0
foreach pth $paths {
  set sp [get_property STARTPOINT_PIN $pth]
  set ep [get_property ENDPOINT_PIN $pth]
  set sl [get_property SLACK $pth]
  set spc [regsub {/[^/]*$} $sp ""]
  set epc [regsub {/[^/]*$} $ep ""]
  set a [xy $spc]; set b [xy $epc]
  set sPlug [plugin_of $spc $plugins]
  set ePlug [plugin_of $epc $plugins]
  set key "$sPlug -> $ePlug"
  set span [expr {abs([lindex $a 0]-[lindex $b 0]) + abs([lindex $a 1]-[lindex $b 1])}]
  if {[info exists paircount($key)]} {
    incr paircount($key)
    set pairspan($key) [expr {$pairspan($key) + $span}]
    if {$sl < $pairslack($key)} { set pairslack($key) $sl }
  } else {
    set paircount($key) 1; set pairspan($key) $span; set pairslack($key) $sl
  }
  if {$i < 25} {
    puts [format "PATH %3d slack=%7.3f  %-18s X%-3s Y%-3s -> %-18s X%-3s Y%-3s span=%-4d %s -> %s" \
      $i $sl $sPlug [lindex $a 0] [lindex $a 1] $ePlug [lindex $b 0] [lindex $b 1] $span $spc $epc]
  }
  incr i
}
puts "== PLUGIN-PAIR SUMMARY (sorted by count) =="
set rows {}
foreach k [array names paircount] {
  lappend rows [list $paircount($k) [expr {double($pairspan($k))/$paircount($k)}] $pairslack($k) $k]
}
foreach r [lsort -index 0 -integer -decreasing $rows] {
  puts [format "PAIR count=%-4d avg_manhattan=%-7.1f worst_slack=%7.3f  %s" \
    [lindex $r 0] [lindex $r 1] [lindex $r 2] [lindex $r 3]]
}

# ---- census 3: the WNS path, logic/route split + fan-out profile ----
# (`timing_path` has no NODES property in 2025.2 -- use report_design_analysis's
#  fan-out-annotated Logical Path column instead, which is what actually exposed the
#  931-load terminal net that this whole section turns on.)
puts "== WNS PATH LOGIC/ROUTE SPLIT + FAN-OUT PROFILE =="
catch { report_design_analysis -timing -max_paths 3 -file /dev/stdout }
puts "GEO_DONE"
