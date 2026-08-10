# Read-only Fix A upper-bound probe (handoff section 19, second pass).
#
# probe_dcache_iq2.tcl's Fix A model globbed
# *IssueQueuePlugin_logic_selPorts_3_m2sPipe_ready* and matched ZERO nets -- the
# ready wire does not survive synthesis under that name, and the probe's own
# object-count guard caught it rather than reporting a false 0.000 ns. This
# re-measures Fix A with a handle that cannot silently miss: an UPPER BOUND that
# false-paths every D-cache/LS-EU cell to every IssueQueuePlugin cell.
#
# That over-cuts deliberately. A 2-deep skid on the IQ -> LS-EU issue-port ready
# removes only the ready arc; this removes the ready arc AND every other
# LS-to-IQ combinational arc (wakeups included). So whatever this measures,
# the real Fix A is worth NO MORE. If the upper bound is ~0, Fix A is dead.
#
#   DCP=synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp \
#   OUT=synth/probe_dcache_iq3 \
#   vivado -mode batch -nojournal -nolog -source synth/probe_dcache_iq3.tcl
#
# Never writes the design.

set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_dcache_iq3"
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
  set line [format "%-34s WNS %8s  TNS %14s  FEP %8s   %s -> %s" $tag $wns $tns $fep \
    [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
  puts "PROBE $line"
  return $line
}

# The netlist is flattened with LsEuPlugin_logic_sq/ absorbing DcachePlugin and
# part of the IQ (section 18 step 3), so these must match on the LEAF name, not
# on a hierarchy prefix.
proc cut_fixA_upper {} {
  set src [get_cells -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_* || NAME =~ *LsEuPlugin_logic_*}]
  set dst [get_cells -quiet -hierarchical -filter {NAME =~ *IssueQueuePlugin_logic_*}]
  puts "PROBE_CUT fixA_upper src cells: [llength $src]  dst cells: [llength $dst]"
  if {[llength $src] == 0 || [llength $dst] == 0} {
    error "PROBE_CUT fixA_upper matched nothing -- refusing to report a false zero"
  }
  set_false_path -from $src -to $dst
}
proc cut_fixB {} {
  set n [get_nets -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin*earlyProbeSetWriteVec*}]
  puts "PROBE_CUT fixB nets: [llength $n]"
  if {[llength $n] == 0} { error "PROBE_CUT fixB matched nothing" }
  set_false_path -through $n
}
proc cut_frontend {} {
  set an [get_nets -quiet -hierarchical -filter {NAME =~ *FetchAlignPlugin_logic_applyNow*}]
  if {[llength $an] > 0} { set_false_path -through $an }
  set st [get_cells -quiet -hierarchical -filter {NAME =~ FetchAlignPlugin_logic_stalled_reg*}]
  if {[llength $st] > 0} { set_false_path -from $st }
  puts "PROBE_CUT frontend applyNow nets: [llength $an]  stalled cells: [llength $st]"
}

set log {}

# S0: control -- must reproduce -1.472 / -17499.508 / 32408
open_checkpoint $dcp
lappend log [summarise 0_baseline]
close_design

# S1: Fix A upper bound, alone
open_checkpoint $dcp
cut_fixA_upper
lappend log [summarise 1_fixA_upper_bound]
close_design

# S2: Fix A upper bound + Fix B
open_checkpoint $dcp
cut_fixA_upper
cut_fixB
lappend log [summarise 2_fixA_upper_plus_fixB]
close_design

# S3: the absolute ceiling -- Fix A upper bound + Fix B + the whole frontend cone.
# This is every candidate this campaign has on file, applied at once.
open_checkpoint $dcp
cut_fixA_upper
cut_fixB
cut_frontend
lappend log [summarise 3_fixA_upper_fixB_frontend]
close_design

set fh [open $out/whatif_summary.txt w]
puts $fh "scenario                           WNS        TNS             FEP        new worst path"
foreach l $log { puts $fh $l }
close $fh
puts "PROBE_DONE $out"
