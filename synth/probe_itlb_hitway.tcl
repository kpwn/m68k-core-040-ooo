# Read-only ITLB hit-way pipelining probe (handoff section 20).
#
# CLOSES A STANDING SPEC OBLIGATION. The binding amendment
# docs/superpowers/specs/2026-08-10-icache-parallel-vipt-design.md sec 2 named
# "the live ITLB-to-hit-context register cone" as the parallel-VIPT design's ONE
# physical risk, REQUIRED it "be reported separately in the next 250-MHz route
# gate", and PRE-AUTHORISED exactly one recovery: "pipeline the ITLB's internal
# hit-way result". That report was never filed and the fix was never measured.
# This is the measurement.
#
# The cone, read off the landed routed checkpoint's path #2 (-1.472, the WNS
# path's tie-mate):
#
#   FetchAlign stalled/C  ->  ftqMem -> cmdWindowPc -> applyNow (fo=143)
#     -> Icache pfDemandLine -> predictTargetReg (fo=177) -> ... @2.063
#     -> Tlb _zz_hitVec_2[1] -> CARRY8 -> hitVec_20 -> ... -> tlb_io_hit @3.145
#     -> Icache lookupPaddr -> s1Way -> Icache hitVec -> arHoldId -> missPA
#     -> pfInstallIdx (fo=518, 0.472 ns of ROUTE) -> lineReg[418]/D @5.481
#
# The ITLB's own internal hit-way segment is 2.063 -> 3.145 = 1.082 ns, 20 % of
# the path. Registering hitVec inside Tlb.scala cuts the path AT tlb_io_hit,
# splitting 5.481 ns into 3.145 + 2.336. Both halves clear 4.000 ns, so
# set_false_path -through the io_hit / io_hitEntry nets is not merely an upper
# bound here -- it is the ACCURATE model of the authorised register insertion.
#
#   DCP=synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp \
#   OUT=synth/probe_itlb_hitway \
#   vivado -mode batch -nojournal -nolog -source synth/probe_itlb_hitway.tcl
#
# Never writes the design.

set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_itlb_hitway"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }
file mkdir $out
puts "PROBE_DCP $dcp"

proc worstpath {} {
  return [lindex [get_timing_paths -max_paths 1 -nworst 1 -delay_type max] 0]
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
  set line [format "%-38s WNS %8s  TNS %14s  FEP %8s   %s -> %s" $tag $wns $tns $fep \
    [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
  puts "PROBE $line"
  return $line
}

# Every cut asserts on its object count. A zero-match set_false_path is a silent
# no-op that reports as a perfect result (handoff section 19 step 1).
proc cut_itlb_hitway {} {
  # The authorised cut point: the Tlb's hit-way outputs (io.hit + io.hitEntry).
  set n [get_nets -quiet -hierarchical -filter \
    {NAME =~ *ItlbPlugin_logic_tlb_io_hit* || NAME =~ *ItlbPlugin_logic_tlb/hitVec* || NAME =~ *ItlbPlugin_logic_tlb/_zz_hitVec*}]
  puts "PROBE_CUT itlb_hitway nets: [llength $n]"
  foreach x $n { puts "PROBE_CUT_NET   $x" }
  if {[llength $n] == 0} { error "PROBE_CUT itlb_hitway matched nothing -- refusing to report a false zero" }
  set_false_path -through $n
}
proc cut_fixA_upper {} {
  set src [get_cells -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_* || NAME =~ *LsEuPlugin_logic_*}]
  set dst [get_cells -quiet -hierarchical -filter {NAME =~ *IssueQueuePlugin_logic_*}]
  puts "PROBE_CUT fixA_upper src cells: [llength $src]  dst cells: [llength $dst]"
  if {[llength $src] == 0 || [llength $dst] == 0} { error "PROBE_CUT fixA_upper matched nothing" }
  set_false_path -from $src -to $dst
}
proc cut_fixB {} {
  set n [get_nets -quiet -hierarchical -filter {NAME =~ *DcachePlugin*earlyProbeSetWriteVec*}]
  puts "PROBE_CUT fixB nets: [llength $n]"
  if {[llength $n] == 0} { error "PROBE_CUT fixB matched nothing" }
  set_false_path -through $n
}
proc cut_frontend {} {
  set an [get_nets -quiet -hierarchical -filter {NAME =~ *FetchAlignPlugin_logic_applyNow*}]
  set st [get_cells -quiet -hierarchical -filter {NAME =~ FetchAlignPlugin_logic_stalled_reg*}]
  puts "PROBE_CUT frontend applyNow nets: [llength $an]  stalled cells: [llength $st]"
  if {[llength $an] == 0 || [llength $st] == 0} { error "PROBE_CUT frontend matched nothing" }
  set_false_path -through $an
  set_false_path -from $st
}

set log {}

# ---------------------------------------------------------------------------
# S0: control -- must reproduce -1.472 / -17499.508 / 32408. Plus the cone
# census: how many failing endpoints actually run THROUGH the ITLB hit-way.
# ---------------------------------------------------------------------------
open_checkpoint $dcp
lappend log [summarise 0_baseline]

puts "PROBE_DISCOVER ---- all ItlbPlugin_logic_tlb_io_* nets ----"
foreach x [get_nets -quiet -hierarchical -filter {NAME =~ *ItlbPlugin_logic_tlb_io_*}] {
  puts "PROBE_DISCOVER_NET $x"
}

set itlbnets [get_nets -quiet -hierarchical -filter \
  {NAME =~ *ItlbPlugin_logic_tlb_io_hit* || NAME =~ *ItlbPlugin_logic_tlb/hitVec* || NAME =~ *ItlbPlugin_logic_tlb/_zz_hitVec*}]
puts "PROBE_SIZE itlb_hitway nets: [llength $itlbnets]"
if {[llength $itlbnets] > 0} {
  report_timing -through $itlbnets -max_paths 20 -unique_pins -nworst 20 \
    -path_type summary -file $out/paths_through_itlb_hitway.rpt
  report_timing -through $itlbnets -max_paths 1 -nworst 1 \
    -file $out/worst_through_itlb_hitway.rpt
  # How much of the FAILING population runs through the cone?
  set fp [get_timing_paths -quiet -through $itlbnets -max_paths 200000 -nworst 1 \
            -slack_lesser_than 0 -delay_type max]
  puts "PROBE_CENSUS failing endpoints THROUGH itlb hitway: [llength $fp]"
}

# The decisive population question for the whole campaign: how many endpoints
# are worse than the 200 MHz floor of -1.000 ns? A point cut can only ever help
# the paths it touches.
foreach thr {-1.400 -1.200 -1.000 -0.800 -0.500 -0.250 0.000} {
  set n [llength [get_timing_paths -quiet -max_paths 300000 -nworst 1 \
           -slack_lesser_than $thr -delay_type max]]
  puts "PROBE_HIST endpoints with slack < $thr : $n"
}
close_design

# ---------------------------------------------------------------------------
# S1: the authorised fix, ALONE. This is the number the spec asked for.
# ---------------------------------------------------------------------------
open_checkpoint $dcp
cut_itlb_hitway
lappend log [summarise 1_itlb_hitway_alone]
close_design

# S2: ITLB hit-way + the D-cache/IQ family (its measured tie-mate at -1.472)
open_checkpoint $dcp
cut_itlb_hitway
cut_fixA_upper
lappend log [summarise 2_itlb_plus_dcacheiq]
close_design

# S3: ITLB hit-way + EVERY candidate this campaign has on file, at once.
open_checkpoint $dcp
cut_itlb_hitway
cut_fixA_upper
cut_fixB
cut_frontend
lappend log [summarise 3_itlb_plus_everything]
close_design

# ---------------------------------------------------------------------------
# S4: the DEEP LADDER. Sections 15/16/17/19 each walked ~13-17 rungs and found a
# plateau. The open question this settles is not "which family is next" but
# "how many families would have to be retired to reach the 200 MHz floor at
# -1.000 ns" -- i.e. whether ANY cut-based approach can close 0.472 ns, or
# whether the deficit is distributional. Each rung retires the worst path's
# ENTIRE family, both ends (startpoint cell AND endpoint cell).
# ---------------------------------------------------------------------------
open_checkpoint $dcp
set fh [open $out/deep_ladder.txt w]
puts $fh "rung   WNS      delta    startpoint -> endpoint"
set prev ""
for {set i 0} {$i < 45} {incr i} {
  set p [worstpath]
  if {$p eq ""} { break }
  set sp [get_property STARTPOINT_PIN $p]
  set ep [get_property ENDPOINT_PIN $p]
  set sl [get_property SLACK $p]
  if {$prev eq ""} { set d "  --  " } else { set d [format "%+.3f" [expr {$sl - $prev}]] }
  set line [format "%4d  %7.3f  %7s   %s -> %s" $i $sl $d $sp $ep]
  puts $fh $line ; flush $fh
  puts "PROBE_LADDER $line"
  set prev $sl
  if {$sl >= -1.000} { puts "PROBE_LADDER REACHED -1.000 AT RUNG $i" ; break }
  set sc [get_cells -quiet -of_objects [get_pins $sp]]
  set ec [get_cells -quiet -of_objects [get_pins $ep]]
  if {$sc ne ""} { set_false_path -from $sc }
  if {$ec ne ""} { set_false_path -to $ec }
}
close $fh
lappend log [summarise 4_deep_ladder_end]
close_design

set fh [open $out/whatif_summary.txt w]
puts $fh "scenario                               WNS        TNS             FEP        new worst path"
foreach l $log { puts $fh $l }
close $fh
puts "PROBE_DONE $out"
