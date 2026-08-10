# Read-only sizing probe for the candidate floorplan capture filters (handoff section 18).
# For each proposed pblock filter it reports the exact captured population split by
# LUT / FF / CARRY / MUXF, the minimum slice count that population needs, and the
# resulting occupancy against the proposed grid -- so a geometry is chosen from
# measured demand instead of a guess.  Also samples the flattened-name contamination
# so the repaired pb_dcache filter can be written against real names.

set dcp "synth/archive/6b246de_ftb_framing_retime_decode/fullcore_synth.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
open_checkpoint $dcp
puts "FILTERPROBE_DCP $dcp"

proc demand {cells label grid} {
  set nl 0; set nf 0; set nc 0; set nm 0; set no 0
  foreach c $cells {
    set r [get_property REF_NAME $c]
    if {[string match "LUT*" $r]} { incr nl } \
    elseif {[string match "FD*" $r]} { incr nf } \
    elseif {[string match "CARRY*" $r]} { incr nc } \
    elseif {[string match "MUXF*" $r]} { incr nm } \
    else { incr no }
  }
  set slices 0
  if {[regexp {SLICE_X(\d+)Y(\d+):SLICE_X(\d+)Y(\d+)} $grid -> x0 y0 x1 y1]} {
    set slices [expr {($x1-$x0+1)*($y1-$y0+1)}]
  }
  set lutsites [expr {$slices*8}]
  set ffsites  [expr {$slices*16}]
  puts [format "DEMAND %-26s cells=%-6d LUT=%-6d FF=%-6d CARRY=%-4d MUXF=%-5d other=%-5d | grid %s slices=%d lutocc=%.1f%% ffocc=%.1f%%" \
    $label [llength $cells] $nl $nf $nc $nm $no $grid $slices \
    [expr {$lutsites ? 100.0*($nl+$nc)/$lutsites : 0}] \
    [expr {$ffsites ? 100.0*$nf/$ffsites : 0}]]
}

# --- contamination sample: which real names carry two plugin prefixes? ---
puts "== CONTAMINATION SAMPLE: cells matching the pb_dcache 3-way OR that also say RobPlugin =="
set n 0
foreach c [get_cells -hier -filter {(NAME =~ *DcachePlugin_logic* || NAME =~ *DtlbPlugin_logic* || NAME =~ *LsEuPlugin_logic*) && NAME =~ *RobPlugin*}] {
  if {$n < 12} { puts "  SAMPLE [get_property REF_NAME $c]  [get_property NAME $c]" }
  incr n
}
puts "  CONTAM_ROB_TOTAL $n"
set n 0
foreach c [get_cells -hier -filter {(NAME =~ *DcachePlugin_logic* || NAME =~ *DtlbPlugin_logic* || NAME =~ *LsEuPlugin_logic*) && NAME =~ *IssueQueuePlugin*}] {
  if {$n < 8} { puts "  SAMPLE_IQ [get_property REF_NAME $c]  [get_property NAME $c]" }
  incr n
}
puts "  CONTAM_IQ_TOTAL $n"

puts "== CANDIDATE FILTERS =="

# current pb_decode
demand [get_cells -hier -filter {NAME =~ *DecodeStage_logic*}] \
  "pb_decode (current)" {SLICE_X36Y0:SLICE_X87Y104}

# variant decode_fe: pb_decode + FetchAlign + Ras
demand [get_cells -hier -filter {NAME =~ *DecodeStage_logic* || NAME =~ *FetchAlignPlugin_logic* || NAME =~ *RasPlugin_logic*}] \
  "decode_fe" {SLICE_X36Y0:SLICE_X87Y104}

# variant fe: a dedicated frontend box
set fe_filter {(NAME =~ *IcachePlugin_logic* || NAME =~ *FetchAlignPlugin_logic* || NAME =~ *FtbPlugin_logic* || NAME =~ *GsharePlugin_logic* || NAME =~ *RasPlugin_logic* || NAME =~ *BtbPlugin_logic*) && NAME !~ *DecodeStage* && NAME !~ *RobPlugin* && NAME !~ *IssueQueuePlugin* && NAME !~ *RenameStage* && NAME !~ *DcachePlugin* && NAME !~ *LsEuPlugin*}
demand [get_cells -hier -filter $fe_filter] "pb_frontend clean" {SLICE_X0Y20:SLICE_X35Y135}
demand [get_cells -hier -filter $fe_filter] "pb_frontend clean (wider)" {SLICE_X0Y10:SLICE_X39Y139}

# variant backend: repaired pb_dcache (Dcache + Dtlb + LsEu, contamination removed)
set be_filter {(NAME =~ *DcachePlugin_logic* || NAME =~ *DtlbPlugin_logic* || NAME =~ *LsEuPlugin_logic*) && NAME !~ *RobPlugin* && NAME !~ *IssueQueuePlugin* && NAME !~ *RenameStage* && NAME !~ *DecodeStage* && NAME !~ *AluEuPlugin* && NAME !~ *DivEuPlugin* && NAME !~ *BranchEuPlugin* && NAME !~ *FetchAlignPlugin* && NAME !~ *IcachePlugin*}
demand [get_cells -hier -filter $be_filter] "pb_backend clean (dirty geom)" {SLICE_X36Y110:SLICE_X87Y214}
demand [get_cells -hier -filter $be_filter] "pb_backend clean (new geom)"  {SLICE_X14Y132:SLICE_X72Y239}

# variant backend_iq: + IssueQueuePlugin
set beiq_filter {(NAME =~ *DcachePlugin_logic* || NAME =~ *DtlbPlugin_logic* || NAME =~ *LsEuPlugin_logic* || NAME =~ *IssueQueuePlugin_logic*) && NAME !~ *RobPlugin* && NAME !~ *RenameStage* && NAME !~ *DecodeStage* && NAME !~ *AluEuPlugin* && NAME !~ *DivEuPlugin* && NAME !~ *BranchEuPlugin* && NAME !~ *FetchAlignPlugin* && NAME !~ *IcachePlugin*}
demand [get_cells -hier -filter $beiq_filter] "pb_backend_iq" {SLICE_X20Y110:SLICE_X92Y239}

# the raw (unrepaired) pb_dcache, for the record
demand [get_cells -hier -filter {NAME =~ *DcachePlugin_logic* || NAME =~ *DtlbPlugin_logic* || NAME =~ *LsEuPlugin_logic*}] \
  "pb_dcache (current, dirty)" {SLICE_X36Y110:SLICE_X87Y214}

puts "FILTERPROBE_DONE"
