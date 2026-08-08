# FMax Frontend Lever C — targeted-family probe.
# usage: vivado -mode batch -source synth/probe_leverc.tcl -tclargs <routed.dcp> <tag>
set dcp [lindex $argv 0]
set tag [lindex $argv 1]
open_checkpoint $dcp

proc pslack {label paths} {
  if {[llength $paths] == 0} { puts "PROBE $label  (no paths)"; return }
  set p [lindex $paths 0]
  puts [format "PROBE %-46s slack %8.3f  levels %3d  datapath %6.3f  %s -> %s" \
    $label [get_property SLACK $p] [get_property LOGIC_LEVELS $p] \
    [get_property DATAPATH_DELAY $p] \
    [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
}

puts "########## PROBE TAG $tag ##########"

# overall WNS
pslack "DESIGN WNS" [get_timing_paths -max_paths 1 -nworst 1 -setup]

# ── the TARGETED family: ibuf pred_lenWords -> fed specs dstEa ──
set src [get_pins -quiet -hier -filter {NAME =~ *ibuf*pred_lenWords_reg*/C}]
set dst [get_pins -quiet -hier -filter {NAME =~ *fed_payload_specs_*_dstEa_*_reg*/D}]
puts "PROBE_INFO src_pins [llength $src]  dst_pins [llength $dst]"
if {[llength $src] && [llength $dst]} {
  pslack "TARGETED pred_lenWords -> fed specs dstEa" \
    [get_timing_paths -from $src -to $dst -max_paths 1 -nworst 1 -setup]
}
if {[llength $dst]} {
  pslack "fed specs dstEa (any source)" \
    [get_timing_paths -to $dst -max_paths 1 -nworst 1 -setup]
}

# ── the whole `fed` specs endpoint group ──
set sp [get_pins -quiet -hier -filter {NAME =~ *fed_payload_specs_*_reg*/D}]
if {[llength $sp]} { pslack "fed_payload_specs_* (all)" [get_timing_paths -to $sp -max_paths 1 -nworst 1 -setup] }

# ── half-1 endpoints: the NEW raw register (after) / the fed packets (before) ──
foreach {lbl pat} {
  raw_payload_packets_*   *raw_payload_packets_*_reg*/D
  raw_payload_slot1Valid  *raw_payload_slot1Valid_reg*/D
  fed_payload_packets_*   *fed_payload_packets_*_reg*/D
  fed_payload_slot1Valid  *fed_payload_slot1Valid_reg*/D
} {
  set e [get_pins -quiet -hier -filter "NAME =~ $pat"]
  if {[llength $e]} {
    pslack "$lbl ([llength $e] pins)" [get_timing_paths -to $e -max_paths 1 -nworst 1 -setup]
  } else { puts "PROBE $lbl  (0 pins)" }
}

# ── the headPtr feedback loop (design spec §4.1 prediction for the new WNS holder) ──
set hp [get_pins -quiet -hier -filter {NAME =~ *ibuf*headPtr_reg*/D}]
set hpq [get_pins -quiet -hier -filter {NAME =~ *ibuf*headPtr_reg*/C}]
if {[llength $hp]} { pslack "-> ibuf headPtr (any source)" [get_timing_paths -to $hp -max_paths 1 -nworst 1 -setup] }
if {[llength $hp] && [llength $hpq]} {
  pslack "headPtr -> headPtr (the loop)" [get_timing_paths -from $hpq -to $hp -max_paths 1 -nworst 1 -setup]
}
if {[llength $hp] && [llength $src]} {
  pslack "pred_lenWords -> headPtr" [get_timing_paths -from $src -to $hp -max_paths 1 -nworst 1 -setup]
}

# ── fed.packets -> pushReg (untouched 4th family) ──
set pr [get_pins -quiet -hier -filter {NAME =~ *pushReg*_reg*/D}]
if {[llength $pr]} { pslack "-> pushReg (assemble cone)" [get_timing_paths -to $pr -max_paths 1 -nworst 1 -setup] }

# ── IQ scoreboard busy-clear family (the 6th family that absorbs freed slack) ──
set sb [get_pins -quiet -hier -filter {NAME =~ *IssueQueuePlugin*sb*busy*_reg*/D}]
if {[llength $sb]} { pslack "IQ sb*_busy" [get_timing_paths -to $sb -max_paths 1 -nworst 1 -setup] }

# ── ucPendPkt family (Lever U1's target) ──
set uc [get_pins -quiet -hier -filter {NAME =~ *ucPendPkt_words_0_reg*/C}]
if {[llength $uc]} { pslack "ucPendPkt_words_0 -> *" [get_timing_paths -from $uc -max_paths 1 -nworst 1 -setup] }

# ── top-100 worst endpoints: dump, and check for feed.ready / slotFree derived nets (§4.1) ──
set fh [open "synth/leverc_top100_$tag.rpt" w]
set feedready 0
set idx 0
foreach p [get_timing_paths -max_paths 100 -nworst 1 -setup] {
  incr idx
  set sp0 [get_property STARTPOINT_PIN $p]
  set ep0 [get_property ENDPOINT_PIN $p]
  puts $fh [format "%3d %8.3f  %s -> %s" $idx [get_property SLACK $p] $sp0 $ep0]
  # walk the path's cells looking for a feed.ready / slotFree / raw-ready derived net
  foreach c [get_cells -quiet -of_objects [get_pins -quiet -of_objects $p]] {
    if {[string match -nocase "*feed_ready*" $c] || [string match -nocase "*slotFree*" $c] \
        || [string match -nocase "*rawIn_ready*" $c] || [string match -nocase "*fedIn_ready*" $c]} {
      incr feedready
      puts $fh "      ^^ FEED_READY-DERIVED CELL: $c"
    }
  }
}
close $fh
puts "PROBE feed.ready/slotFree-derived cells found on top-100 paths: $feedready"
puts "########## END PROBE TAG $tag ##########"
