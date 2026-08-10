set reuse_synth 0
if {[info exists ::env(REUSE_SYNTH_DCP)] && $::env(REUSE_SYNTH_DCP) eq "1" &&
    [file exists synth/fullcore_synth.dcp]} {
  set reuse_synth 1
  open_checkpoint synth/fullcore_synth.dcp
  puts "REUSE_SYNTH_DCP synth/fullcore_synth.dcp"
} else {
  read_verilog generated/M68kFullCoreSynth.v
  read_xdc synth/clk.xdc
  synth_design -top M68kFullCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context
  opt_design
}
# Preserve the optimized post-synthesis checkpoint separately from placement/routing.
# This makes it possible to distinguish RTL depth from floorplan/route loss on every
# physical gate instead of relying on transient messages in the Vivado console log.
report_timing_summary -max_paths 10 -file synth/fullcore_synth_timing.rpt
report_utilization -file synth/fullcore_synth_util.rpt
if {!$reuse_synth} {
  write_checkpoint -force synth/fullcore_synth.dcp
}
set synth_paths [get_timing_paths -max_paths 1 -nworst 1 -setup]
if {[llength $synth_paths] > 0} {
  set synth_wns [get_property SLACK $synth_paths]
  puts "POSTSYNTH_FULLCORE_WNS_NS $synth_wns"
}
# Front-end floorplan: co-locate DecodeStage so the decode->ring nets stay local (read after
# opt so the cell filter sees elaborated leaves). Part of the front-end FMax stack (195->~218).
read_xdc synth/floorplan_decode.xdc
# LS-cluster floorplan: co-locate D-cache + DTLB + LS-EU so the valids->hrPpn cross-module
# load/translate nets stay local (read after opt so the cell filter sees elaborated leaves).
# Attacks the post-route limiter DcachePlugin valids/C -> DtlbPlugin hrPpn/CE (73% route).
read_xdc synth/floorplan_dcache.xdc
puts "FLOORPLAN pb_dcache cells: [llength [get_cells -of_objects [get_pblocks pb_dcache]]]"
place_design
phys_opt_design
route_design
report_timing_summary -max_paths 10 -file synth/fullcore_route_timing.rpt
report_utilization -file synth/fullcore_route_util.rpt
# ── congestion + attribution reports (always-on; routing congestion is a first-class
# gate metric alongside FMax — the recurring limiters are 58-82% ROUTE-dominated) ──
# 0) netlist provenance: which _zz_ regen ordering was gated (regen is bistable)
catch { puts "NETLIST_MD5 [lindex [exec md5sum generated/M68kFullCoreSynth.v] 0]" }
# 1) router congestion windows + per-path logic-vs-route attribution
catch { report_design_analysis -congestion -file synth/fullcore_congestion.rpt }
catch { report_design_analysis -timing -max_paths 10 -file synth/fullcore_path_analysis.rpt }
# 2) top fanout nets (catches un-replicated control broadcasts)
catch { report_high_fanout_nets -max_nets 10 -file synth/fullcore_fanout.rpt }
# 3) per-pblock utilization (floorplan capture + regional spill sanity). Vivado
# rejects a list of pblocks for this report, so emit the two reports separately.
catch { report_utilization -pblocks pb_decode -file synth/fullcore_pb_decode_util.rpt }
catch { report_utilization -pblocks pb_dcache -file synth/fullcore_pb_dcache_util.rpt }
# 4) module-pair slack matrix: which plugin PAIR limits (top-100 worst endpoints)
catch {
  set fp [open synth/fullcore_slack_matrix.rpt w]
  foreach p [get_timing_paths -max_paths 100 -nworst 1 -setup] {
    puts $fp [format "%.3f  %s -> %s" [get_property SLACK $p] \
      [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
  }
  close $fp
}
# 5) archive the router's congestion dump if it dropped one (else it gets overwritten)
catch { file copy -force iter_100_CongestedCLBsAndNets.txt synth/fullcore_congested_nets.txt }
# 6) reopenable snapshot for offline congestion forensics
catch { write_checkpoint -force synth/fullcore_routed.dcp }
set paths [get_timing_paths -max_paths 1 -nworst 1 -setup]
set wns [get_property SLACK $paths]
puts "########### FULLCORE POST-ROUTE @ 250MHz (xcku5p-ffvb676-2) ###########"
puts "POSTROUTE_FULLCORE_WNS_NS $wns"
set achieved [expr {1000.0/(4.000 - $wns)}]
if {$wns < 0} { puts "POSTROUTE_FULLCORE_RESULT FAILED_AT_250  ACHIEVED_FMAX_MHZ $achieved" } else { puts "POSTROUTE_FULLCORE_RESULT MET_250  FMAX_MHZ $achieved" }
puts "######################################################################"
