# Read-only SLACK-POPULATION census (handoff section 20, step 3).
#
# Every ladder this campaign has run (sections 15/16/17/19 and section 20's own
# ITLB probe) asks "which path is worst". probe_itlb_hitway.tcl's histogram
# showed that is the wrong question:
#
#   slack < -1.400 :     98 endpoints
#   slack < -1.200 :  2,823
#   slack < -1.000 :  4,890      <-- the 200 MHz floor
#   slack < -0.800 :  7,247
#   slack < -0.500 : 15,904
#   slack <  0.000 : 32,408 / 171,230
#
# Reaching WNS >= -1.000 requires improving FOUR THOUSAND EIGHT HUNDRED AND
# NINETY endpoints, not four paths. This probe asks the only question that
# matters after that: are those 4,890 endpoints CONCENTRATED in one structure
# (a structural lever exists) or SPREAD across the design (they are not a
# critical-path problem at all)?
#
#   DCP=synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp \
#   OUT=synth/probe_slack_population \
#   vivado -mode batch -nojournal -nolog -source synth/probe_slack_population.tcl
#
# Never writes the design.

set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_slack_population"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }
file mkdir $out
puts "PROBE_DCP $dcp"

open_checkpoint $dcp

# Classify an endpoint pin name into the plugin/structure that owns it.
proc family {name} {
  foreach k {IcachePlugin FetchAlignPlugin DcachePlugin LsEuPlugin IssueQueuePlugin \
             RobPlugin RenameStage DecodeStage MicroOpQueue AluEuPlugin BranchEuPlugin \
             DivEuPlugin ItlbPlugin DtlbPlugin PrfPlugin GsharePlugin FtbPlugin \
             RasPlugin BtbPlugin TableWalker Tlb} {
    if {[string first $k $name] >= 0} { return $k }
  }
  return OTHER
}

foreach thr {-1.000 -0.500} {
  set paths [get_timing_paths -quiet -max_paths 300000 -nworst 1 \
               -slack_lesser_than $thr -delay_type max]
  puts "PROBE_POP threshold $thr total endpoints: [llength $paths]"

  array unset epf ; array unset spf
  foreach p $paths {
    set e [family [get_property ENDPOINT_PIN $p]]
    set s [family [get_property STARTPOINT_PIN $p]]
    if {[info exists epf($e)]} { incr epf($e) } else { set epf($e) 1 }
    if {[info exists spf($s)]} { incr spf($s) } else { set spf($s) 1 }
  }
  set f [open $out/population_[string map {- m . p} $thr].txt w]
  puts $f "threshold $thr   endpoints [llength $paths]"
  foreach {label arr tag} [list "ENDPOINT" epf EP "STARTPOINT" spf SP] {
    upvar 0 $arr a
    set pairs {}
    foreach k [array names a] { lappend pairs [list $a($k) $k] }
    puts $f "---- by $label family ----"
    foreach p [lsort -integer -decreasing -index 0 $pairs] {
      puts $f [format "%8d  %s" [lindex $p 0] [lindex $p 1]]
      puts "PROBE_POP_$tag $thr [format %8d [lindex $p 0]]  [lindex $p 1]"
    }
  }
  close $f
}

# How many of the sub-(-1.000) endpoints run through the ITLB hit-way cone --
# i.e. how much of the 200 MHz deficit the authorised fix could even touch?
set itlbnets [get_nets -quiet -hierarchical -filter \
  {NAME =~ *ItlbPlugin_logic_tlb_io_hit* || NAME =~ *ItlbPlugin_logic_tlb/hitVec* || NAME =~ *ItlbPlugin_logic_tlb/_zz_hitVec*}]
if {[llength $itlbnets] == 0} { error "PROBE itlb nets matched nothing" }
foreach thr {-1.000 -0.500 0.000} {
  set n [llength [get_timing_paths -quiet -through $itlbnets -max_paths 300000 \
           -nworst 1 -slack_lesser_than $thr -delay_type max]]
  puts "PROBE_ITLB_SHARE endpoints < $thr THROUGH the itlb hit-way cone: $n"
}

# Logic-levels distribution of the sub-(-1.000) population: a deficit made of
# deep-logic paths is retimeable; one made of shallow, route-dominated paths is
# not.
set paths [get_timing_paths -quiet -max_paths 300000 -nworst 1 \
             -slack_lesser_than -1.000 -delay_type max]
array unset ll
set netsum 0.0 ; set logsum 0.0 ; set cnt 0
foreach p $paths {
  set l [get_property LOGIC_LEVELS $p]
  if {[info exists ll($l)]} { incr ll($l) } else { set ll($l) 1 }
  catch {
    set netsum [expr {$netsum + [get_property DATAPATH_NET_DELAY $p]}]
    set logsum [expr {$logsum + [get_property DATAPATH_LOGIC_DELAY $p]}]
    incr cnt
  }
}
set f [open $out/logic_levels.txt w]
foreach k [lsort -integer [array names ll]] {
  puts $f [format "logic_levels %3d : %6d endpoints" $k $ll($k)]
  puts "PROBE_LL [format %3d $k] : $ll($k)"
}
if {$cnt > 0} {
  set line [format "mean over %d sub(-1.000) endpoints: logic %.3f ns  net %.3f ns  net share %.1f%%" \
    $cnt [expr {$logsum/$cnt}] [expr {$netsum/$cnt}] [expr {100.0*$netsum/($netsum+$logsum)}]]
  puts $f $line
  puts "PROBE_DELAYMIX $line"
}
close $f

close_design
puts "PROBE_DONE $out"
