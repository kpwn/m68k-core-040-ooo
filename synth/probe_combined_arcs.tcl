# Combined-arc what-if probe (implementation plan 2026-08-10, slice 0).
#
# Models B2 (the ITLB hit-way / F1-F2 boundary), B3 (the speculative
# install/prefetch decoupling) and D-cache/IQ Fix A (the IQ->LS ready skid)
# INDIVIDUALLY and IN COMBINATION on one frozen routed checkpoint.  The point of
# the exercise is the combinations: handoff sections 19-20 already measured each
# cut alone at +0.000 ns, and design spec section 9.4 states that the arcs are
# tied so no single cut can move WNS.
#
# Every cut asserts on its matched-object count.  A zero-match set_false_path is
# a silent no-op indistinguishable from a cut worth nothing (handoff 19 step 1).
#
#   DCP=synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp \
#   OUT=synth/probe_combined_arcs \
#   vivado -mode batch -nojournal -nolog -source synth/probe_combined_arcs.tcl

set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_combined_arcs"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }
file mkdir $out
puts "PROBE_DCP $dcp"

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
  set line [format "%-24s WNS %8s  TNS %14s  FEP %8s  POP1000 %6s   %s -> %s" \
    $tag $wns $tns $fep [pop] \
    [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
  puts "PROBE $line"
  return $line
}

# ---- B2: the ITLB hit-way / F1-F2 boundary ---------------------------------
proc cut_b2 {} {
  set n [get_nets -quiet -hierarchical -filter \
    {NAME =~ *ItlbPlugin_logic_tlb_io_hit* || NAME =~ *ItlbPlugin_logic_tlb/hitVec* || NAME =~ *ItlbPlugin_logic_tlb/_zz_hitVec*}]
  puts "PROBE_CUT b2_itlb_hitway nets: [llength $n]"
  if {[llength $n] == 0} { error "PROBE_CUT b2_itlb_hitway matched nothing -- refusing to report a false zero" }
  set_false_path -through $n
}

# ---- B3: the speculative install / prefetch decoupling ----------------------
# Everything B3 takes off the LIVE demand verdict: the install select and its
# fanout-518 net, the lineReg capture cone, the prefetch-window seed registers
# and the AR arbiter's hold.
proc cut_b3 {} {
  set n [get_nets -quiet -hierarchical -filter \
    {NAME =~ *IcachePlugin_logic_demandFillStart* || \
     NAME =~ *IcachePlugin_logic_pfInstallIdx* || \
     NAME =~ *IcachePlugin_logic_pfInstallAny* || \
     NAME =~ *IcachePlugin_logic_pfInstallSel* || \
     NAME =~ *IcachePlugin_logic_heldDemandMiss* || \
     NAME =~ *IcachePlugin_logic_pfWindowUpdate*}]
  set c [get_cells -quiet -hierarchical -filter {NAME =~ *IcachePlugin_logic_lineReg_reg*}]
  puts "PROBE_CUT b3_install nets: [llength $n]  lineReg cells: [llength $c]"
  if {[llength $n] == 0} { error "PROBE_CUT b3_install matched no nets -- refusing to report a false zero" }
  if {[llength $c] == 0} { error "PROBE_CUT b3_install matched no lineReg cells -- refusing to report a false zero" }
  set_false_path -through $n
  set_false_path -to $c
}

# ---- Fix A: the IQ -> LS-EU ready chain -------------------------------------
# Modelled as the same deliberate OVER-CUT handoff section 19 step 1 adopted
# after the `selPorts_3_m2sPipe_ready` glob matched zero nets: every D-cache /
# LS-EU cell false-pathed to every IssueQueue cell.  The real skid removes only
# the ready arc, so the real Fix A is worth no more than this.
proc cut_fixa {} {
  set src [get_cells -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_* || NAME =~ *LsEuPlugin_logic_*}]
  set dst [get_cells -quiet -hierarchical -filter {NAME =~ *IssueQueuePlugin_logic_*}]
  puts "PROBE_CUT fixa src cells: [llength $src]  dst cells: [llength $dst]"
  if {[llength $src] == 0 || [llength $dst] == 0} { error "PROBE_CUT fixa matched nothing" }
  set_false_path -from $src -to $dst
}

set log {}
foreach scenario {baseline b3 b2 b2_b3 fixa b2_b3_fixa} {
  open_checkpoint $dcp
  switch $scenario {
    baseline    { }
    b3          { cut_b3 }
    b2          { cut_b2 }
    b2_b3       { cut_b2 ; cut_b3 }
    fixa        { cut_fixa }
    b2_b3_fixa  { cut_b2 ; cut_b3 ; cut_fixa }
  }
  lappend log [summarise $scenario]
  close_design
}

set fh [open $out/whatif_summary.txt w]
puts $fh "scenario                 WNS        TNS             FEP       POP1000   new worst path"
foreach l $log { puts $fh $l }
close $fh
puts "PROBE_DONE $out"
