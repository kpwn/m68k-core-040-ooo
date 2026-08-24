# synth/census_ooc_fullcore.tcl -- SYNTH-STAGE failing-endpoint CENSUS for the standing
# 200MHz OOC gate (synth/ooc_M68kFullCoreSynth.tcl).
#
# WHY THIS EXISTS: the standing gate reports `report_timing_summary -max_paths 8`, which
# cannot distinguish "one root cause replicated across N endpoints" from "N genuinely
# unrelated paths" -- the exact distinction the 2026-08-19 FMax resilience postmortem
# (§5.3) says must be made BEFORE deciding what the next slice targets. The last three
# FMax slices (`d0e617a4`, `6028a6c8`, `ff438195`) each had to re-derive this grouping by
# hand. This script makes it a one-command step.
#
# Unlike synth/census.tcl (which opens a ROUTED checkpoint from the impl flow), this one
# runs the OOC synth itself, so it needs no prior artifacts and matches the gate exactly:
# same netlist, same part, same clock constraint.
#
# usage:
#   vivado -mode batch -source synth/census_ooc_fullcore.tcl
# outputs:
#   synth/census_ooc_summary.rpt   -- report_timing_summary
#   synth/census_ooc_family.rpt    -- failing endpoints grouped by (start reg -> end reg)
#   synth/census_ooc_starts.rpt    -- failing endpoints grouped by STARTPOINT register only

read_verilog generated/M68kFullCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kFullCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context

report_timing_summary -max_paths 8 -file synth/census_ooc_summary.rpt

set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
puts "RESULT FullCore WNS $wns FMAX [expr {1000.0/(5.000 - $wns)}]"

# One worst path per failing endpoint. 20000 is a deliberate over-request: the count
# printed below is the real population, and a number equal to the cap means the cap
# needs raising rather than that the census is complete.
set paths [get_timing_paths -setup -max_paths 20000 -nworst 1 -slack_lesser_than 0]
puts "CENSUS_TOTAL_FAILING_ENDPOINTS [llength $paths]"

# Strip the bit index and Vivado's replication/pin suffixes so that e.g.
# `foo_reg[3]_rep_2/C` and `foo_reg[17]/C` collapse to the one logical register `foo`.
proc regkey {pinName} {
  set k $pinName
  regsub {/[^/]*$} $k "" k           ;# drop the pin (…/C, …/D, …/CE, …/RAMA/WADR3)
  regsub {/RAM[A-D](_D1)?$} $k "" k  ;# LUTRAM sub-cells share one logical register
  regsub {_rep(_[0-9]+)?$} $k "" k   ;# replicated driver copies
  regsub {\[[0-9]+\]} $k "" k        ;# bit index
  regsub {_reg$} $k "" k
  return $k
}

array unset pairCount ; array unset pairWorst
array unset srcCount  ; array unset srcWorst
foreach p $paths {
  set s   [get_property SLACK $p]
  set src [regkey [get_property NAME [get_property STARTPOINT_PIN $p]]]
  set dst [regkey [get_property NAME [get_property ENDPOINT_PIN   $p]]]
  set key "$src  ->  $dst"
  if {![info exists pairCount($key)]} { set pairCount($key) 0 ; set pairWorst($key) 99.0 }
  incr pairCount($key)
  if {$s < $pairWorst($key)} { set pairWorst($key) $s }
  if {![info exists srcCount($src)]} { set srcCount($src) 0 ; set srcWorst($src) 99.0 }
  incr srcCount($src)
  if {$s < $srcWorst($src)} { set srcWorst($src) $s }
}

proc dump {arrCount arrWorst path title} {
  upvar 1 $arrCount cnt
  upvar 1 $arrWorst wst
  set rows {}
  foreach k [array names cnt] { lappend rows [list $cnt($k) $k $wst($k)] }
  set fp [open $path w]
  puts $fp $title
  puts $fp [format "%8s %10s  %s" "COUNT" "WORST_NS" "FAMILY"]
  set n 0
  foreach r [lsort -integer -decreasing -index 0 $rows] {
    puts $fp [format "%8d %10.3f  %s" [lindex $r 0] [lindex $r 2] [lindex $r 1]]
    if {[incr n] <= 15} {
      puts [format "CENSUS %6d  worst %8.3f  %s" [lindex $r 0] [lindex $r 2] [lindex $r 1]]
    }
  }
  puts $fp "TOTAL_FAMILIES [llength $rows]"
  close $fp
  puts "CENSUS_FAMILIES [llength $rows] -> $path"
}

puts "--- by (startpoint -> endpoint) register pair ---"
dump pairCount pairWorst synth/census_ooc_family.rpt \
     "Failing endpoints grouped by (startpoint register -> endpoint register)"
puts "--- by startpoint register ---"
dump srcCount srcWorst synth/census_ooc_starts.rpt \
     "Failing endpoints grouped by startpoint register"

puts "########### CENSUS COMPLETE ###########"
