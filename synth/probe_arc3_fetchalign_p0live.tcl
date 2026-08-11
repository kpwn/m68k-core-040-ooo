# Arc-3 grounding probe: FetchAlignPlugin ftqHead -> FetchAlignPlugin p0LiveReg_*
#
# Context (handoff 23.5 / implementation plan 2026-08-10): the combined what-if
# on this same checkpoint measured
#     baseline            -1.472   DcachePlugin stS2Payload_paddr -> IQ sbNzvc_busy
#     cut FetchAlign->Icache only   -1.472  (unchanged)
#     cut D-cache/IQ only           -1.472  FetchAlign stalled -> Icache lineReg
#     cut BOTH                      -1.460  FetchAlign ftqHead -> FetchAlign p0LiveReg_lenWords
# This probe characterises that THIRD family and, decisively, measures what
# becomes worst when all THREE are cut.
#
# Every object query asserts on its matched count.  A zero-match set_false_path
# is a silent no-op indistinguishable from a cut worth nothing (handoff 19 step 1).
#
#   DCP=synth/archive/M0_CONTROL_c776f06_postrouteN3_decode/fullcore_routed.dcp \
#   OUT=synth/probe_arc3 \
#   vivado -mode batch -nojournal -nolog -source synth/probe_arc3_fetchalign_p0live.tcl

set dcp "synth/archive/M0_CONTROL_c776f06_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_arc3"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }
file mkdir $out
puts "PROBE_DCP $dcp"

proc need {what objs} {
  if {[llength $objs] == 0} {
    error "PROBE_ASSERT $what matched nothing -- refusing to report a false zero"
  }
  puts "PROBE_MATCH $what [llength $objs]"
  return $objs
}

proc worstpath {} {
  return [lindex [get_timing_paths -max_paths 1 -nworst 1 -delay_type max] 0]
}

proc pop {} {
  return [llength [get_timing_paths -quiet -max_paths 300000 -nworst 1 \
            -slack_lesser_than -1.000 -delay_type max]]
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
  set line [format "%-26s WNS %8s  TNS %14s  FEP %8s  POP1000 %6s   %s -> %s" \
    $tag $wns $tns $fep [pop] \
    [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
  puts "PROBE $line"
  return $line
}

# ---------------------------------------------------------------------------
# Object sets
# ---------------------------------------------------------------------------
# REF_NAME =~ FD* keeps these to real flops.  Without it the glob also drags in
# the combinational LUTs Vivado names after their load (e.g.
# `..._p0LiveReg_lenWords_reg[0]_i_1`, a MUXF7), which would silently widen
# every cut into an unintended over-cut.
proc cells_ftqhead {} {
  return [need ftqHead_cells [get_cells -quiet -hierarchical -filter \
    {NAME =~ *FetchAlignPlugin*ftqHead_reg* && REF_NAME =~ FD*}]]
}
proc cells_p0live {} {
  return [need p0LiveReg_cells [get_cells -quiet -hierarchical -filter \
    {NAME =~ *FetchAlignPlugin*p0LiveReg_*_reg* && REF_NAME =~ FD*}]]
}

# ---- Arc 3, narrow: exactly the measured family --------------------------
proc cut_arc3_narrow {} {
  set_false_path -from [cells_ftqhead] -to [cells_p0live]
}
# ---- Arc 3, wide: any fix that fully retimes the classify input cone ------
proc cut_arc3_wide {} {
  set_false_path -to [cells_p0live]
}
# ---- Arc 3, ftq-out: everything the async FTQ read reaches ----------------
proc cut_arc3_ftqout {} {
  set_false_path -from [cells_ftqhead]
}

# ---- B2: the ITLB hit-way / F1-F2 boundary (other design, unchanged) ------
proc cut_b2 {} {
  set n [need b2_itlb_nets [get_nets -quiet -hierarchical -filter \
    {NAME =~ *ItlbPlugin_logic_tlb_io_hit* || NAME =~ *ItlbPlugin_logic_tlb/hitVec* || NAME =~ *ItlbPlugin_logic_tlb/_zz_hitVec*}]]
  set_false_path -through $n
}
# ---- B3: the speculative install / prefetch decoupling (other design) -----
proc cut_b3 {} {
  set n [need b3_install_nets [get_nets -quiet -hierarchical -filter \
    {NAME =~ *IcachePlugin_logic_demandFillStart* || \
     NAME =~ *IcachePlugin_logic_pfInstallIdx* || \
     NAME =~ *IcachePlugin_logic_pfInstallAny* || \
     NAME =~ *IcachePlugin_logic_pfInstallSel* || \
     NAME =~ *IcachePlugin_logic_heldDemandMiss* || \
     NAME =~ *IcachePlugin_logic_pfWindowUpdate*}]]
  set c [need b3_lineReg_cells [get_cells -quiet -hierarchical -filter \
    {NAME =~ *IcachePlugin_logic_lineReg_reg*}]]
  set_false_path -through $n
  set_false_path -to $c
}
# ---- Fix A: the IQ -> LS-EU ready chain (other design, deliberate OVER-cut)
proc cut_fixa {} {
  set src [need fixa_src [get_cells -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_* || NAME =~ *LsEuPlugin_logic_*}]]
  set dst [need fixa_dst [get_cells -quiet -hierarchical -filter \
    {NAME =~ *IssueQueuePlugin_logic_*}]]
  set_false_path -from $src -to $dst
}

# ---------------------------------------------------------------------------
# Census helper: group sub-(-1.000) endpoints by start family / end family.
# ---------------------------------------------------------------------------
proc famof {pin} {
  # leaf cell name without bit index, e.g. FetchAlignPlugin_logic_ftqHead_reg
  set leaf [lindex [split $pin "/"] end-1]
  if {$leaf eq ""} { set leaf $pin }
  regsub {\[[0-9]+\]$} $leaf "" leaf
  regsub {_reg(_[0-9]+)?$} $leaf "" leaf
  return $leaf
}
proc modof {pin} {
  foreach tok [split $pin "/_"] {
    if {[string match "*Plugin" $tok]} { return $tok }
  }
  set leaf [lindex [split $pin "/"] end-1]
  return [lindex [split $leaf "_"] 0]
}

proc dumpdict {fh label d} {
  puts $fh "\n== $label =="
  set rows {}
  dict for {k v} $d { lappend rows [list $v $k] }
  foreach r [lsort -integer -decreasing -index 0 $rows] {
    puts $fh [format "%8d  %s" [lindex $r 0] [lindex $r 1]]
  }
}

proc bump {dvar key} {
  upvar 1 $dvar d
  if {[dict exists $d $key]} {
    dict set d $key [expr {[dict get $d $key] + 1}]
  } else {
    dict set d $key 1
  }
}

proc census {tag} {
  global out
  set paths [get_timing_paths -quiet -max_paths 300000 -nworst 1 \
               -slack_lesser_than -1.000 -delay_type max]
  puts "PROBE_CENSUS $tag total_sub1000_endpoints [llength $paths]"
  set byend {} ; set bystart {} ; set bypair {} ; set bymodpair {} ; set bypin {}
  foreach p $paths {
    set s [get_property STARTPOINT_PIN $p]
    set e [get_property ENDPOINT_PIN $p]
    bump byend    [famof $e]
    bump bystart  [famof $s]
    bump bypair   "[famof $s] -> [famof $e]"
    bump bymodpair "[modof $s] -> [modof $e]"
    bump bypin    [lindex [split $e "/"] end]
  }
  set fh [open $out/census_$tag.txt w]
  puts $fh "total sub-(-1.000) endpoints: [llength $paths]"
  dumpdict $fh "BY MODULE PAIR" $bymodpair
  dumpdict $fh "BY ENDPOINT PIN TYPE (CE vs D vs other)" $bypin
  dumpdict $fh "BY START FAMILY" $bystart
  dumpdict $fh "BY END FAMILY" $byend
  dumpdict $fh "BY START->END FAMILY PAIR" $bypair
  close $fh
  # counts specifically owned by the arc-3 family
  set n3 0
  foreach p $paths {
    if {[string match "*p0LiveReg*" [get_property ENDPOINT_PIN $p]]} { incr n3 }
  }
  puts "PROBE_CENSUS $tag p0LiveReg_endpoints $n3"
}

# ---------------------------------------------------------------------------
# Pass 1: baseline characterisation (detail reports)
# ---------------------------------------------------------------------------
set log {}
open_checkpoint $dcp

puts "PROBE_SECTION baseline_detail"
set fh0 [open $out/objects.txt w]
puts $fh0 "== ftqHead cells =="
foreach c [cells_ftqhead] { puts $fh0 [get_property NAME $c] }
puts $fh0 "\n== p0LiveReg cells =="
foreach c [cells_p0live] { puts $fh0 [get_property NAME $c] }
close $fh0

# The measured family, in full trail detail with logic levels and net delays.
report_timing -from [cells_ftqhead] -to [cells_p0live] \
  -max_paths 12 -nworst 12 -delay_type max -input_pins \
  -file $out/timing_ftqhead_to_p0live.rpt
puts "PROBE_RPT timing_ftqhead_to_p0live.rpt"

# Everything ftqHead reaches (is p0LiveReg really its worst destination?).
report_timing -from [cells_ftqhead] -max_paths 25 -nworst 25 -delay_type max \
  -file $out/timing_from_ftqhead.rpt
# Everything reaching p0LiveReg (is ftqHead really its worst source?).
report_timing -to [cells_p0live] -max_paths 25 -nworst 25 -delay_type max \
  -file $out/timing_to_p0live.rpt
# Overall worst 25, for reference.
report_timing -max_paths 25 -nworst 25 -delay_type max -file $out/timing_worst25.rpt

# Machine-readable per-path summary of the family.
set fh1 [open $out/arc3_paths.txt w]
puts $fh1 [format "%-9s %-7s %-9s %-9s %-9s %s" slack levels logic net skew endpoint]
foreach p [get_timing_paths -quiet -from [cells_ftqhead] -to [cells_p0live] \
             -max_paths 40 -nworst 40 -delay_type max] {
  puts $fh1 [format "%-9s %-7s %-9s %-9s %-9s %s -> %s" \
    [get_property SLACK $p] [get_property LOGIC_LEVELS $p] \
    [get_property DATAPATH_LOGIC_DELAY $p] [get_property DATAPATH_NET_DELAY $p] \
    [get_property SKEW $p] \
    [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
}
close $fh1

# Endpoint-pin classification: CE/control cone vs D/data cone, for the whole
# FetchAlign frontend family and for the design's current worst 200 paths.
set fh2 [open $out/endpoint_pin_kinds.txt w]
foreach {label plist} [list \
    "arc3_ftqHead_to_p0Live" [get_timing_paths -quiet -from [cells_ftqhead] -to [cells_p0live] -max_paths 200 -nworst 200 -delay_type max] \
    "from_ftqHead_any"       [get_timing_paths -quiet -from [cells_ftqhead] -max_paths 500 -nworst 500 -delay_type max] \
    "to_p0Live_any"          [get_timing_paths -quiet -to [cells_p0live] -max_paths 500 -nworst 500 -delay_type max] \
    "design_worst200"        [get_timing_paths -quiet -max_paths 200 -nworst 200 -delay_type max]] {
  array unset k ; array set k {}
  foreach p $plist {
    set e [get_property ENDPOINT_PIN $p]
    set pinname [lindex [split $e "/"] end]
    incr k($pinname)
  }
  puts $fh2 "== $label ([llength $plist] paths) =="
  foreach n [array names k] { puts $fh2 [format "  %8d  pin %s" $k($n) $n] }
  puts $fh2 ""
}
close $fh2

lappend log [summarise baseline]
census baseline
close_design

# ---------------------------------------------------------------------------
# Pass 2: the what-if matrix.  The decision-relevant cell is b2_b3_fixa_arc3*.
# ---------------------------------------------------------------------------
# Scenario semantics:
#   arc3_narrow          arc 3 cut ALONE (expected no-op: the other two dominate)
#   b2_b3_fixa           reproduce the published -1.460 / arc-3-is-worst cell
#   ..._arc3n            + exactly the measured arc (ftqHead -> p0LiveReg)
#   ..._ftqout           + registering the FTQ read OUTPUT (ftqHead -> anything).
#                        This is the model of the realistic candidate fix.
#   ..._arc3w            + fully retiming p0LiveReg's whole input cone (upper bound
#                        for any p0Live-side fix, incl. the IBuf-head sources)
#   ..._arc3wftq         both upper bounds together = the absolute ceiling of any
#                        FetchAlign FTQ/p0Live work.  Whatever is worst HERE is the
#                        fourth family, and its slack is the program's real floor.
foreach scenario {arc3_narrow b2_b3_fixa b2_b3_fixa_arc3n b2_b3_fixa_ftqout \
                  b2_b3_fixa_arc3w b2_b3_fixa_arc3wftq} {
  open_checkpoint $dcp
  switch $scenario {
    arc3_narrow          { cut_arc3_narrow }
    b2_b3_fixa           { cut_b2 ; cut_b3 ; cut_fixa }
    b2_b3_fixa_arc3n     { cut_b2 ; cut_b3 ; cut_fixa ; cut_arc3_narrow }
    b2_b3_fixa_ftqout    { cut_b2 ; cut_b3 ; cut_fixa ; cut_arc3_ftqout }
    b2_b3_fixa_arc3w     { cut_b2 ; cut_b3 ; cut_fixa ; cut_arc3_wide }
    b2_b3_fixa_arc3wftq  { cut_b2 ; cut_b3 ; cut_fixa ; cut_arc3_wide ; cut_arc3_ftqout }
  }
  lappend log [summarise $scenario]
  if {[string match "b2_b3_fixa*" $scenario]} {
    report_timing -max_paths 30 -nworst 30 -delay_type max -file $out/timing_worst30_$scenario.rpt
    census $scenario
  }
  close_design
}

set fh [open $out/whatif_summary.txt w]
puts $fh "scenario                   WNS        TNS             FEP       POP1000   new worst path"
foreach l $log { puts $fh $l }
close $fh
puts "PROBE_DONE $out"
