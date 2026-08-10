# Read-only placement census for the floorplan A/B campaign (handoff section 18).
#
# Purpose: ground a floorplan proposal in ACTUAL placed coordinates rather than a
# guess.  For every candidate capture filter it reports (a) how many cells the
# filter matches, (b) the placed bounding box and centroid of those cells, and
# (c) for the pb_dcache filter specifically, how much of the match is
# over-capture through Vivado's flattened leaf names (cells whose name also
# mentions an unrelated plugin).
#
# Usage:
#   DCP=synth/archive/<ckpt>/fullcore_routed.dcp \
#     vivado -mode batch -nojournal -nolog -source synth/probe_floorplan_census.tcl

set dcp "synth/archive/6b246de_ftb_framing_retime_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
open_checkpoint $dcp
puts "CENSUS_DCP $dcp"

proc bbox {cells label} {
  set n 0
  set minx 100000; set maxx -1; set miny 100000; set maxy -1
  set sx 0; set sy 0
  foreach c $cells {
    set loc [get_property LOC $c]
    if {$loc eq ""} { continue }
    if {![regexp {SLICE_X(\d+)Y(\d+)} $loc -> x y]} { continue }
    incr n
    if {$x < $minx} { set minx $x }
    if {$x > $maxx} { set maxx $x }
    if {$y < $miny} { set miny $y }
    if {$y > $maxy} { set maxy $y }
    set sx [expr {$sx + $x}]; set sy [expr {$sy + $y}]
  }
  if {$n == 0} {
    puts [format "CENSUS %-42s cells=%-7d placed=0" $label [llength $cells]]
    return
  }
  puts [format "CENSUS %-42s cells=%-7d placed=%-7d bbox=X%d..X%d,Y%d..Y%d centroid=X%.1fY%.1f" \
    $label [llength $cells] $n $minx $maxx $miny $maxy \
    [expr {double($sx)/$n}] [expr {double($sy)/$n}]]
}

# ---- 1. the two live capture filters, exactly as the xdc writes them ----
set c_decode [get_cells -hier -filter {NAME =~ *DecodeStage_logic*}]
bbox $c_decode "FILTER *DecodeStage_logic* (pb_decode)"

set c_dcfilt [get_cells -hier -filter {NAME =~ *DcachePlugin_logic* || NAME =~ *DtlbPlugin_logic* || NAME =~ *LsEuPlugin_logic*}]
bbox $c_dcfilt "FILTER pb_dcache 3-way OR"

# ---- 2. per-plugin populations, for the proposed regions ----
foreach p {DecodeStage FetchAlignPlugin IcachePlugin FtbPlugin GsharePlugin RasPlugin \
           DcachePlugin DtlbPlugin LsEuPlugin IssueQueuePlugin RobPlugin RenameStage \
           AluEuPlugin BranchEuPlugin DivEuPlugin StoreQueuePlugin ItlbPlugin PrfPlugin} {
  set cs [get_cells -hier -filter "NAME =~ *${p}*"]
  bbox $cs "PLUGIN $p"
}

# ---- 3. pb_dcache over-capture audit: which OTHER plugin names appear inside a
#         cell that the 3-way OR filter matched?  (flattened-name contamination) ----
puts "OVERCAPTURE_AUDIT pb_dcache 3-way OR filter"
array set foreign {}
foreach c $c_dcfilt {
  set n [get_property NAME $c]
  foreach other {RobPlugin RenameStage IssueQueuePlugin DecodeStage AluEuPlugin \
                 BranchEuPlugin DivEuPlugin StoreQueuePlugin FetchAlignPlugin \
                 IcachePlugin PrfPlugin CommitStage MmuPlugin ItlbPlugin} {
    if {[string first $other $n] >= 0} {
      if {[info exists foreign($other)]} { incr foreign($other) } else { set foreign($other) 1 }
    }
  }
}
foreach k [lsort [array names foreign]] {
  puts [format "  OVERCAPTURE  %-20s %d cells" $k $foreign($k)]
}
# how many are "clean" (mention exactly one of the three intended plugins and no foreign)
set clean 0
foreach c $c_dcfilt {
  set n [get_property NAME $c]
  set dirty 0
  foreach other {RobPlugin RenameStage IssueQueuePlugin DecodeStage AluEuPlugin \
                 BranchEuPlugin DivEuPlugin StoreQueuePlugin FetchAlignPlugin \
                 IcachePlugin PrfPlugin CommitStage MmuPlugin ItlbPlugin} {
    if {[string first $other $n] >= 0} { set dirty 1; break }
  }
  if {!$dirty} { incr clean }
}
puts "  OVERCAPTURE  clean cells = $clean of [llength $c_dcfilt]"

# ---- 4. same audit for the decode filter ----
puts "OVERCAPTURE_AUDIT pb_decode *DecodeStage_logic* filter"
array unset foreign
array set foreign {}
foreach c $c_decode {
  set n [get_property NAME $c]
  foreach other {RobPlugin RenameStage IssueQueuePlugin DcachePlugin AluEuPlugin \
                 BranchEuPlugin DivEuPlugin StoreQueuePlugin FetchAlignPlugin \
                 IcachePlugin PrfPlugin CommitStage LsEuPlugin} {
    if {[string first $other $n] >= 0} {
      if {[info exists foreign($other)]} { incr foreign($other) } else { set foreign($other) 1 }
    }
  }
}
foreach k [lsort [array names foreign]] {
  puts [format "  OVERCAPTURE  %-20s %d cells" $k $foreign($k)]
}

# ---- 5. the two specific endpoints named in handoff sections 16 and 17 ----
foreach nm {*fed_payload_specs_0_spec_size_reg* *predictPending_reg* *predictTargetReg* \
            *stS1Payload_paddr_reg* *sbInt_busy_reg* *s1PredEntries_3_reg*} {
  set cs [get_cells -hier -filter "NAME =~ $nm"]
  bbox $cs "ENDPOINT $nm"
}

# ---- 6. device SLICE extent, so a proposed pblock is legal by construction ----
set slices [get_sites -filter {SITE_TYPE =~ SLICE*}]
set minx 100000; set maxx -1; set miny 100000; set maxy -1
foreach s $slices {
  if {[regexp {SLICE_X(\d+)Y(\d+)} [get_property NAME $s] -> x y]} {
    if {$x < $minx} { set minx $x }
    if {$x > $maxx} { set maxx $x }
    if {$y < $miny} { set miny $y }
    if {$y > $maxy} { set maxy $y }
  }
}
puts "DEVICE_SLICE_EXTENT X${minx}..X${maxx} Y${miny}..Y${maxy}  total_slices=[llength $slices]"

# ---- 7. current pblock occupancy on this routed checkpoint ----
foreach pb [get_pblocks -quiet] {
  puts "PBLOCK [get_property NAME $pb] grid=[get_property GRID_RANGES $pb] cells=[llength [get_cells -quiet -of_objects $pb]]"
}
puts "CENSUS_DONE"
