# Grounding probe: the `DcachePlugin stS2Payload_paddr` fanout hub.
#
# Handoff section 24.5 named `stS2Payload_paddr[5] -> dataMem_3/ADDRARDADDR[12]`
# as "the single most actionable line", with 363 endpoints tied to the hub.  This
# script answers three questions on the frozen `6b246de` routed checkpoint, in one
# batch, read-only:
#
#   PASS 1  What IS the hub?  Real per-bit fanout from the netlist (direct loads
#           AND transitive endpoint reach), the real consumer list grouped by
#           module and by pin kind, and the sub-(-1.000 ns) population the family
#           actually owns.  Section 15's lesson: the "High Fanout" column in a
#           timing report names the worst net ANYWHERE on the path, not the
#           startpoint's own fanout, so it must be re-derived from the netlist.
#
#   PASS 2  What is it WORTH?  A what-if ladder over increasingly generous cuts,
#           from the narrow arc the RTL suggests (`refillWriteHold` /
#           `refillNeedsStoreDrain` -> `storePort.ready`) up to a deliberate
#           whole-cluster over-cut that bounds every conceivable D-cache/LS-EU
#           restructuring.  Every cut asserts on its matched object count
#           (section 19 step 1: a zero-match set_false_path is a silent no-op).
#
#   PASS 3  Does it matter once the three tied frontend families are gone?  The
#           same ladder replayed on top of the b2+b3+fixA+arc3 scenario that
#           section 24 measured at -1.451.
#
#   DCP=synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp \
#   OUT=synth/probe_sts2hub \
#   vivado -mode batch -nojournal -nolog -source synth/probe_stS2_paddr_hub.tcl

set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_sts2hub"
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
  set line [format "%-28s WNS %8s  TNS %14s  FEP %8s  POP1000 %6s   %s -> %s" \
    $tag $wns $tns $fep [pop] \
    [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
  puts "PROBE $line"
  return $line
}
proc bump {dvar key} {
  upvar 1 $dvar d
  if {[dict exists $d $key]} { dict set d $key [expr {[dict get $d $key] + 1}] } \
  else { dict set d $key 1 }
}
proc dumpdict {fh label d} {
  puts $fh "\n== $label =="
  set rows {}
  dict for {k v} $d { lappend rows [list $v $k] }
  foreach r [lsort -integer -decreasing -index 0 $rows] {
    puts $fh [format "%8d  %s" [lindex $r 0] [lindex $r 1]]
  }
}
proc famof {pin} {
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

# ---------------------------------------------------------------------------
# Object sets
# ---------------------------------------------------------------------------
proc cells_hub {} {
  return [need hub_paddr_cells [get_cells -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_stS2Payload_paddr_reg* && REF_NAME =~ FD*}]]
}

# Cut 1 (NARROW, the RTL-suggested fix): the `refillWriteHold` cone and the
# `refillNeedsStoreDrain` throttle it feeds.  Models registering that term.
proc cut_refillhold {} {
  set n [need refillhold_nets [get_nets -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_refillWriteHold* || \
     NAME =~ *DcachePlugin_logic_refillNeedsStoreDrain*}]]
  set_false_path -through $n
}
# Cut 2: the whole store-port backpressure arc leaving the D-cache.
proc cut_storeready {} {
  set n [need storeready_nets [get_nets -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_storePort_ready* || \
     NAME =~ *LsEuPlugin_logic_sq_io_drain_ready*}]]
  set_false_path -through $n
}
# Cut 3: cut 2 plus the S2 hit/miss discovery terms that also feed it.
proc cut_storepipe {} {
  cut_storeready
  set n [need storepipe_nets [get_nets -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_storePipeHeld* || \
     NAME =~ *DcachePlugin_logic_storeMissDiscovered* || \
     NAME =~ *DcachePlugin_logic_stS1Advance* || \
     NAME =~ *DcachePlugin_logic_s0Advance* || \
     NAME =~ *DcachePlugin_logic_s0Ready*}]]
  set_false_path -through $n
}
# Cut 4 (HUB, upper bound for any change that re-registers the hub itself):
# every path launched by any stS2Payload_paddr flop.
proc cut_hub_from {} {
  set_false_path -from [cells_hub]
}
# Cut 5 (round trip): forbid any D-cache launch from reaching the BRAM address
# pins via LsEu.  Upper bound for "register the LS-EU -> D-cache request port".
proc cut_roundtrip {} {
  set src [need rt_src [get_cells -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_* && REF_NAME =~ FD*}]]
  set dst [need rt_dst [get_cells -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_dataMem_* || NAME =~ *DcachePlugin_logic_tagMem_*}]]
  set_false_path -from $src -to $dst
}
# Cut 6 (CLUSTER over-cut, the hard ceiling): every D-cache/LS-EU flop to every
# D-cache/LS-EU sink.  No restructuring confined to this cluster can beat it.
proc cut_cluster {} {
  set c [need cluster_cells [get_cells -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_* || NAME =~ *LsEuPlugin_logic_*}]]
  set_false_path -from $c -to $c
}

# ---- the three already-measured frontend families (section 24) -------------
proc cut_b2 {} {
  set n [need b2_itlb_nets [get_nets -quiet -hierarchical -filter \
    {NAME =~ *ItlbPlugin_logic_tlb_io_hit* || NAME =~ *ItlbPlugin_logic_tlb/hitVec* || NAME =~ *ItlbPlugin_logic_tlb/_zz_hitVec*}]]
  set_false_path -through $n
}
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
proc cut_fixa {} {
  set src [need fixa_src [get_cells -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_* || NAME =~ *LsEuPlugin_logic_*}]]
  set dst [need fixa_dst [get_cells -quiet -hierarchical -filter {NAME =~ *IssueQueuePlugin_logic_*}]]
  set_false_path -from $src -to $dst
}
proc cut_arc3 {} {
  set c [need p0LiveReg_cells [get_cells -quiet -hierarchical -filter \
    {NAME =~ *FetchAlignPlugin*p0LiveReg_*_reg* && REF_NAME =~ FD*}]]
  set f [need ftqHead_cells [get_cells -quiet -hierarchical -filter \
    {NAME =~ *FetchAlignPlugin*ftqHead_reg* && REF_NAME =~ FD*}]]
  set_false_path -to $c
  set_false_path -from $f
}
proc cut_3arcs {} { cut_b2 ; cut_b3 ; cut_fixa ; cut_arc3 }

# ===========================================================================
# PASS 1 -- characterise the hub on the untouched checkpoint
# ===========================================================================
open_checkpoint $dcp
puts "PROBE_SECTION pass1_characterise"

set hub [cells_hub]
set fh [open $out/hub_fanout.txt w]
puts $fh "== stS2Payload_paddr flops: real per-bit fanout (netlist-derived) =="
puts $fh [format "%-58s %8s %8s %10s" "cell" "loads" "endpts" "worstslack"]
set totloads 0
foreach c [lsort -dictionary [get_property NAME $hub]] {
  set qpin [get_pins -quiet "$c/Q"]
  if {[llength $qpin] == 0} { puts $fh "$c  (no Q pin)" ; continue }
  set net [get_nets -quiet -of_objects $qpin]
  set loads [get_pins -quiet -of_objects $net -filter {DIRECTION == IN}]
  set eps [get_pins -quiet -of_objects [all_fanout -flat -endpoints_only -from $qpin]]
  set ps [get_timing_paths -quiet -from [get_pins "$c/C"] -max_paths 1 -nworst 1 -delay_type max]
  set sl "-"
  if {[llength $ps] > 0} { set sl [get_property SLACK [lindex $ps 0]] }
  incr totloads [llength $loads]
  puts $fh [format "%-58s %8d %8d %10s" $c [llength $loads] \
    [llength [all_fanout -flat -endpoints_only -from $qpin]] $sl]
}
puts $fh "\nTOTAL direct loads across the family: $totloads"

# Direct-load consumer list for the named worst bit, [5]
set c5 [get_cells -quiet -hierarchical -filter \
  {NAME =~ *DcachePlugin_logic_stS2Payload_paddr_reg[5] && REF_NAME =~ FD*}]
if {[llength $c5] > 0} {
  set n5 [get_nets -quiet -of_objects [get_pins "[get_property NAME $c5]/Q"]]
  set l5 [get_pins -quiet -of_objects $n5 -filter {DIRECTION == IN}]
  puts $fh "\n== bit\[5\] DIRECT loads ([llength $l5]) =="
  foreach p [lsort -dictionary [get_property NAME $l5]] { puts $fh "  $p" }
  set ep5 [all_fanout -flat -endpoints_only -from [get_pins "[get_property NAME $c5]/Q"]]
  puts $fh "\n== bit\[5\] transitive ENDPOINT reach: [llength $ep5] =="
  set bym {} ; set byp {}
  foreach p $ep5 {
    set nm [get_property NAME $p]
    bump bym [modof $nm]
    bump byp [lindex [split $nm "/"] end]
  }
  dumpdict $fh "bit\[5\] endpoint reach BY MODULE" $bym
  dumpdict $fh "bit\[5\] endpoint reach BY PIN KIND" $byp
}
close $fh

# The family's share of the failing population, and where its paths terminate.
set paths [get_timing_paths -quiet -max_paths 300000 -nworst 1 \
             -slack_lesser_than -1.000 -delay_type max]
puts "PROBE_POP baseline_sub1000 [llength $paths]"
set nhub 0 ; set byend {} ; set bypin {} ; set bymod {}
foreach p $paths {
  set s [get_property STARTPOINT_PIN $p]
  if {[string match "*stS2Payload_paddr*" $s]} {
    incr nhub
    set e [get_property ENDPOINT_PIN $p]
    bump byend [famof $e]
    bump bypin [lindex [split $e "/"] end]
    bump bymod [modof $e]
  }
}
puts "PROBE_POP hub_owned_sub1000 $nhub"
set fh [open $out/hub_population.txt w]
puts $fh "sub-(-1.000 ns) endpoints total: [llength $paths]"
puts $fh "of which launched by stS2Payload_paddr: $nhub"
dumpdict $fh "hub-launched failing endpoints BY MODULE" $bymod
dumpdict $fh "hub-launched failing endpoints BY PIN KIND" $bypin
dumpdict $fh "hub-launched failing endpoints BY END FAMILY" $byend
close $fh

report_timing -from [get_pins [format "%s/C" [get_property NAME $c5]]] \
  -max_paths 20 -nworst 20 -delay_type max -file $out/timing_from_bit5.rpt
report_timing -max_paths 12 -nworst 12 -delay_type max -file $out/timing_worst12.rpt
close_design

# ===========================================================================
# PASS 2 + 3 -- the what-if ladder
# ===========================================================================
set log {}
foreach scenario {baseline refillhold storeready storepipe hub_from roundtrip cluster \
                  a3 a3_refillhold a3_storepipe a3_hub_from a3_cluster} {
  open_checkpoint $dcp
  switch $scenario {
    baseline       { }
    refillhold     { cut_refillhold }
    storeready     { cut_storeready }
    storepipe      { cut_storepipe }
    hub_from       { cut_hub_from }
    roundtrip      { cut_roundtrip }
    cluster        { cut_cluster }
    a3             { cut_3arcs }
    a3_refillhold  { cut_3arcs ; cut_refillhold }
    a3_storepipe   { cut_3arcs ; cut_storepipe }
    a3_hub_from    { cut_3arcs ; cut_hub_from }
    a3_cluster     { cut_3arcs ; cut_cluster }
  }
  lappend log [summarise $scenario]
  report_timing -max_paths 10 -nworst 10 -delay_type max -file $out/timing_$scenario.rpt
  close_design
}

set fh [open $out/whatif_summary.txt w]
puts $fh "scenario                     WNS        TNS             FEP       POP1000   new worst path"
foreach l $log { puts $fh $l }
close $fh
puts "PROBE_DONE $out"
