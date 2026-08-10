# Read-only re-pricing probe for handoff section 19.
#
# Section 16 measured the D-cache/IQ family at 0.000 ns of WNS -- on the OLD
# placement, where a frontend arc was the limiter. Section 18's landed
# IMPL_STRATEGY=postrouteN recipe changed the placement and the same family is
# now the reported WNS path. This probe re-prices section 16 step 5's two
# shelved fixes against the NEW routed checkpoint:
#
#   Fix A  register the IQ -> LS-EU issue-port ready (2-deep skid)
#          modelled as: set_false_path -through the selPorts(3) m2sPipe ready net
#   Fix B  retime earlyProbeSetWriteVec out of earlyProbeHit
#          modelled as: set_false_path -through the earlyProbeSetWriteVec nets
#
# Each scenario re-opens the checkpoint, so the scenarios are independent rather
# than cumulative (section 16's ladder could only measure cumulatively).
#
#   DCP=synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp \
#   OUT=synth/probe_dcache_iq2 \
#   vivado -mode batch -nojournal -source synth/probe_dcache_iq2.tcl
#
# Never writes the design.

set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_dcache_iq2"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }
file mkdir $out
puts "PROBE_DCP $dcp"

proc worstpath {} {
  return [lindex [get_timing_paths -max_paths 1 -nworst 1 -delay_type max] 0]
}
proc famkey {pin} {
  regsub {/[A-Z]+$} $pin {} c
  regsub {\[[0-9]+\]$} $c {} c
  return $c
}
proc summarise {tag} {
  global out
  set f $out/summary_$tag.rpt
  report_timing_summary -no_detailed_paths -quiet -file $f
  set wns "?" ; set tns "?" ; set fep "?"
  set fh [open $f r]
  while {[gets $fh line] >= 0} {
    if {[regexp {^\s+(-?[0-9]+\.[0-9]+)\s+(-?[0-9]+\.[0-9]+)\s+(\d+)\s+(\d+)\s+(-?[0-9]+\.[0-9]+)} $line -> a b c d e]} {
      set wns $a ; set tns $b ; set fep $c ; break
    }
  }
  close $fh
  set p [worstpath]
  set line [format "%-30s WNS %8s  TNS %14s  FEP %8s   %s -> %s" $tag $wns $tns $fep \
    [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
  puts "PROBE $line"
  return $line
}

# ---------------------------------------------------------------- cut models
# Returns the number of objects the cut actually constrained, so a silent
# zero-match glob can never be mistaken for a measured zero-worth result.
proc cut_fixA {} {
  set n [get_nets -quiet -hierarchical -filter \
    {NAME =~ *IssueQueuePlugin_logic_selPorts_3_m2sPipe_ready*}]
  puts "PROBE_CUT fixA nets: [llength $n]"
  foreach x $n { puts "PROBE_CUT   fixA net $x" }
  if {[llength $n] > 0} { set_false_path -through $n }
  return [llength $n]
}
proc cut_fixB {} {
  set n [get_nets -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin*earlyProbeSetWriteVec*}]
  puts "PROBE_CUT fixB nets: [llength $n]"
  foreach x $n { puts "PROBE_CUT   fixB net $x" }
  if {[llength $n] > 0} { set_false_path -through $n }
  return [llength $n]
}
proc cut_frontend {} {
  set an [get_nets -quiet -hierarchical -filter {NAME =~ *FetchAlignPlugin_logic_applyNow*}]
  if {[llength $an] > 0} { set_false_path -through $an }
  set st [get_cells -quiet -hierarchical -filter {NAME =~ FetchAlignPlugin_logic_stalled_reg*}]
  if {[llength $st] > 0} { set_false_path -from $st }
  puts "PROBE_CUT frontend applyNow nets: [llength $an]  stalled cells: [llength $st]"
}

set log {}

# ===================================================== S0: baseline + anatomy
open_checkpoint $dcp
lappend log [summarise 0_baseline]

# The decisive evidence: the full routed detail of the reported WNS path, so the
# RTL mechanism is read off the netlist rather than assumed from section 16.
report_timing -max_paths 3 -nworst 3 -path_type full_clock_expanded \
  -input_pins -file $out/worst_detail.rpt
report_timing -from [get_cells -hierarchical -filter \
    {NAME =~ DcachePlugin_logic_stS2Payload_paddr_reg*}] \
  -max_paths 40 -unique_pins -nworst 40 -path_type summary \
  -file $out/paths_from_stS2.rpt

# Which startpoint families reach the IQ scoreboard, and how far behind is the
# best non-D-cache one? That gap is the ceiling on any D-cache-only cut.
set iqCells [get_cells -quiet -hierarchical -filter \
  {NAME =~ IssueQueuePlugin_logic_sbNzvc_busy_reg* || NAME =~ IssueQueuePlugin_logic_sbInt_busy_reg*}]
puts "PROBE iq scoreboard cells: [llength $iqCells]"
set ps [get_timing_paths -to $iqCells -max_paths 4000 -nworst 1 -delay_type max]
array set src {}
array set best {}
foreach p $ps {
  set k [famkey [get_property STARTPOINT_PIN $p]]
  set s [get_property SLACK $p]
  if {[info exists src($k)]} {
    incr src($k)
    if {$s < $best($k)} { set best($k) $s }
  } else { set src($k) 1 ; set best($k) $s }
}
set fh [open $out/iq_startpoint_census.txt w]
puts $fh "paths  worstSlack  startpoint family   (into sbNzvc_busy / sbInt_busy)"
foreach k [lsort -command {apply {{a b} {expr {0}}}} [array names src]] {}
foreach k [array names src] { puts $fh [format "%6d  %8.3f  %s" $src($k) $best($k) $k] }
close $fh
close_design

# ============================================================ S1: Fix A alone
open_checkpoint $dcp
set nA [cut_fixA]
lappend log [summarise 1_fixA_iq_ready_skid]
close_design

# ============================================================ S2: Fix B alone
open_checkpoint $dcp
set nB [cut_fixB]
lappend log [summarise 2_fixB_earlyProbeSetWrite]
close_design

# ========================================================== S3: Fix A + Fix B
open_checkpoint $dcp
cut_fixA
cut_fixB
lappend log [summarise 3_fixA_plus_fixB]

# ladder onward from the combined cut: what backfills, and how fast
set fh [open $out/ladder_after_AB.txt w]
puts $fh "rung  WNS(ns)   delta   worst startpoint -> endpoint"
set prev ""
for {set i 0} {$i <= 12} {incr i} {
  set p [worstpath]
  if {$p eq ""} { break }
  set s [get_property SLACK $p]
  set sp [get_property STARTPOINT_PIN $p]
  set ep [get_property ENDPOINT_PIN $p]
  set d [expr {$prev eq "" ? 0.0 : $s - $prev}]
  set line [format "%4d  %7.3f  %+6.3f   %s -> %s" $i $s $d $sp $ep]
  puts $fh $line ; puts "PROBE_LADDER $line"
  set prev $s
  if {$i == 12} { break }
  set fk [famkey $sp]
  set cells [get_cells -quiet -hierarchical -filter "NAME =~ ${fk}*"]
  if {[llength $cells] == 0} { set cells [get_cells -quiet $fk] }
  if {[llength $cells] == 0} { break }
  set_false_path -from $cells
}
close $fh
close_design

# ============================ S4: Fix A + Fix B + the whole frontend cone free
open_checkpoint $dcp
cut_fixA
cut_fixB
cut_frontend
lappend log [summarise 4_fixAB_plus_frontend_free]
close_design

set fh [open $out/whatif_summary.txt w]
puts $fh "scenario                       WNS        TNS             FEP        new worst path"
foreach l $log { puts $fh $l }
close $fh
puts "PROBE_DONE $out"
