# Read-only ENDPOINT-cone probe (handoff section 19 step 7).
#
# Every what-if ladder this campaign has run (sections 15, 16, 17, 19) retires
# STARTPOINT families, because the top-N census is naturally read that way. The
# section-19 ladder exposed why that framing is wrong on this placement:
#
#   rungs 0,4,5,6,7,8,11  ->  IcachePlugin_logic_lineReg_reg[418]/D     (7 of 13)
#   rungs 2,9,12          ->  FetchAlignPlugin_logic_predictPending_reg/D (3 of 13)
#
# Ten of thirteen rungs share TWO endpoints. Retiring startpoints one at a time
# merely rotates through the fan-in of the same two cones, which is exactly why
# the ladder crawls at ~0.007 ns/rung. This probe measures the ENDPOINT cones
# directly -- the object the ladder kept circling but never priced.
#
#   DCP=synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp \
#   OUT=synth/probe_endpoint_cones \
#   vivado -mode batch -nojournal -nolog -source synth/probe_endpoint_cones.tcl
#
# Never writes the design.

set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_endpoint_cones"
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
  set line [format "%-32s WNS %8s  TNS %14s  FEP %8s   %s -> %s" $tag $wns $tns $fep \
    [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
  puts "PROBE $line"
  return $line
}
# Every cut asserts on its object count: a zero-match set_false_path is a silent
# no-op that reports as a perfect result (section 19 step 1).
proc cut_to {tag pattern} {
  set c [get_cells -quiet -hierarchical -filter "NAME =~ $pattern"]
  puts "PROBE_CUT $tag cells: [llength $c]"
  if {[llength $c] == 0} { error "PROBE_CUT $tag matched nothing" }
  set_false_path -to $c
  return [llength $c]
}

set log {}

# S0: control -- must reproduce -1.472 / -17499.508 / 32408
open_checkpoint $dcp
lappend log [summarise 0_baseline]

# How big are these two cones, and how much of the failing population do they own?
foreach {tag pat} {lineReg *IcachePlugin_logic_lineReg_reg*
                   predictPending *FetchAlignPlugin_logic_predictPending_reg*} {
  set c [get_cells -quiet -hierarchical -filter "NAME =~ $pat"]
  puts "PROBE_SIZE $tag cells: [llength $c]"
  if {[llength $c] > 0} {
    report_timing -to $c -max_paths 20 -unique_pins -nworst 20 -path_type summary \
      -file $out/paths_to_$tag.rpt
  }
}
close_design

# S1: the whole IcachePlugin lineReg endpoint cone
open_checkpoint $dcp
cut_to lineReg {*IcachePlugin_logic_lineReg_reg*}
lappend log [summarise 1_lineReg_cone]
close_design

# S2: the whole FetchAlign predictPending endpoint cone
open_checkpoint $dcp
cut_to predictPending {*FetchAlignPlugin_logic_predictPending_reg*}
lappend log [summarise 2_predictPending_cone]
close_design

# S3: both cones -- ten of the thirteen ladder rungs, retired as cones rather
# than as startpoint families
open_checkpoint $dcp
cut_to lineReg {*IcachePlugin_logic_lineReg_reg*}
cut_to predictPending {*FetchAlignPlugin_logic_predictPending_reg*}
lappend log [summarise 3_both_cones]

# ladder onward, this time BY ENDPOINT rather than by startpoint
set fh [open $out/endpoint_ladder.txt w]
puts $fh "rung  WNS(ns)   delta   worst startpoint -> endpoint (endpoint cone retired each rung)"
set prev ""
for {set i 0} {$i <= 10} {incr i} {
  set p [worstpath]
  if {$p eq ""} { break }
  set s [get_property SLACK $p]
  set sp [get_property STARTPOINT_PIN $p]
  set ep [get_property ENDPOINT_PIN $p]
  set d [expr {$prev eq "" ? 0.0 : $s - $prev}]
  set line [format "%4d  %7.3f  %+6.3f   %s -> %s" $i $s $d $sp $ep]
  puts $fh $line ; puts "PROBE_ELADDER $line"
  set prev $s
  if {$i == 10} { break }
  # retire the whole ENDPOINT family (strip pin and bit index)
  regsub {/[A-Z]+$} $ep {} ek
  regsub {\[[0-9]+\]$} $ek {} ek
  set cells [get_cells -quiet -hierarchical -filter "NAME =~ ${ek}*"]
  if {[llength $cells] == 0} { break }
  set_false_path -to $cells
}
close $fh
close_design

# S4: the 518-load install-select net, priced as its own object.
#
# The archived route report's frontend arc terminates through ONE net carrying
# 0.358-0.491 ns of pure route delay, `IcachePlugin_logic_pfInstallIdx[0]`
# (fanout 518). Read the full routed trail before assuming what it is -- the
# obvious guess (that it is `pfInstallSel`, IcachePlugin:544) is WRONG, because
# `pfInstallVec` is built from four registers (pfValid/pfComplete/pfErr/
# pfPoison) and so `pfInstallSel`'s own fan-in is only ~3 levels deep. The
# actual trail is a demand-fetch chain that arrives at the install decision:
#
#   FetchAlign stalled -> applyNow (fo=143) -> predictTargetReg
#     -> IcachePlugin pfDemandLine -> ItlbPlugin tlb_io_hit (fo=67)      <-- TRANSLATE
#     -> IcachePlugin lookupPaddr -> hitVec / s1Way                      <-- TAG COMPARE
#     -> arHoldId -> missPA
#     -> pfInstallIdx (fo=518, 0.472 ns of route)                        <-- INSTALL SELECT
#     -> lineReg0_in[418] -> lineReg[418]/D
#
# i.e. redirect, translate, tag-compare and prefetch-install all resolve in ONE
# cycle, and the install select fans out to 518 loads at the end of it.
#
# This scenario severs that net. That is strictly BROADER than section 15's
# frontend startpoint cut: `stalled`/`applyNow` are only two of the startpoints
# that funnel through here (Gshare pht and Ftb rspPayload reach it too, via
# predictTargetReg), which is why seven of the thirteen section-19 ladder rungs
# end at lineReg[418] no matter which startpoint is retired.
set log_note "S4 severs the fo=518 install-select net; S5 adds Fix B, i.e. cuts BOTH tied arcs"
open_checkpoint $dcp
set n [get_nets -quiet -hierarchical -filter {NAME =~ *IcachePlugin_logic_pfInstallIdx*}]
puts "PROBE_CUT pfInstallSel nets: [llength $n]"
foreach x $n { puts "PROBE_CUT   pfInstallSel net $x" }
if {[llength $n] == 0} { error "PROBE_CUT pfInstallSel matched nothing" }
set_false_path -through $n
lappend log [summarise 4_pfInstallSel_registered]
close_design

# S5: the registered select TOGETHER with the D-cache/IQ family -- the two
# tied WNS arcs, each cut at its own precise fix point rather than by cone.
open_checkpoint $dcp
set n [get_nets -quiet -hierarchical -filter {NAME =~ *IcachePlugin_logic_pfInstallIdx*}]
if {[llength $n] == 0} { error "PROBE_CUT pfInstallSel matched nothing" }
set_false_path -through $n
set b [get_nets -quiet -hierarchical -filter {NAME =~ *DcachePlugin*earlyProbeSetWriteVec*}]
if {[llength $b] == 0} { error "PROBE_CUT fixB matched nothing" }
set_false_path -through $b
lappend log [summarise 5_pfInstallSel_plus_fixB]
close_design

set fh [open $out/whatif_summary.txt w]
puts $fh "scenario                         WNS        TNS             FEP        new worst path"
foreach l $log { puts $fh $l }
close $fh
puts "PROBE_DONE $out"
