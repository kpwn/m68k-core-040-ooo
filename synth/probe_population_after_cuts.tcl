# Read-only: what the candidate cuts do to the SUB-(-1.000 ns) POPULATION
# (handoff section 20, step 4b).
#
# probe_itlb_hitway.tcl measured what each cut does to WNS (0.000 to +0.012 ns)
# and probe_slack_population.tcl showed why that is nearly meaningless: reaching
# the 200 MHz floor needs 4,890 endpoints moved above -1.000 ns, and 2,620 of
# them (53.6 %) have their worst path THROUGH the ITLB hit-way cone.
#
# So the fair question for the pre-authorised fix is not "what is its WNS" but
# "how many of the 4,890 does it actually retire". An endpoint is only retired
# if it has NO OTHER failing path below -1.000 -- which is exactly what a dense
# slack distribution denies. This measures it directly.
#
#   vivado -mode batch -nojournal -nolog -source synth/probe_population_after_cuts.tcl
#
# Never writes the design.

set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_population_after_cuts"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }
file mkdir $out

proc pop {} {
  return [llength [get_timing_paths -quiet -max_paths 300000 -nworst 1 \
            -slack_lesser_than -1.000 -delay_type max]]
}
proc cut_itlb_hitway {} {
  set n [get_nets -quiet -hierarchical -filter \
    {NAME =~ *ItlbPlugin_logic_tlb_io_hit* || NAME =~ *ItlbPlugin_logic_tlb/hitVec* || NAME =~ *ItlbPlugin_logic_tlb/_zz_hitVec*}]
  puts "PROBE_CUT itlb_hitway nets: [llength $n]"
  if {[llength $n] == 0} { error "PROBE_CUT itlb_hitway matched nothing" }
  set_false_path -through $n
}
proc cut_fixA_upper {} {
  set src [get_cells -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_* || NAME =~ *LsEuPlugin_logic_*}]
  set dst [get_cells -quiet -hierarchical -filter {NAME =~ *IssueQueuePlugin_logic_*}]
  puts "PROBE_CUT fixA_upper src: [llength $src]  dst: [llength $dst]"
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
  puts "PROBE_CUT frontend applyNow: [llength $an]  stalled: [llength $st]"
  if {[llength $an] == 0 || [llength $st] == 0} { error "PROBE_CUT frontend matched nothing" }
  set_false_path -through $an
  set_false_path -from $st
}

set log {}
open_checkpoint $dcp
lappend log "baseline                        sub(-1.000) endpoints: [pop]"
puts "PROBE_POPCUT [lindex $log end]"

cut_itlb_hitway
lappend log "+ itlb hit-way                  sub(-1.000) endpoints: [pop]"
puts "PROBE_POPCUT [lindex $log end]"

cut_fixA_upper
lappend log "+ dcache/IQ (Fix A upper)       sub(-1.000) endpoints: [pop]"
puts "PROBE_POPCUT [lindex $log end]"

cut_fixB
cut_frontend
lappend log "+ Fix B + entire frontend cone  sub(-1.000) endpoints: [pop]"
puts "PROBE_POPCUT [lindex $log end]"

report_timing_summary -no_detailed_paths -quiet -file $out/summary_all_cuts.rpt
close_design

set fh [open $out/population_after_cuts.txt w]
foreach l $log { puts $fh $l }
close $fh
puts "PROBE_DONE $out"
