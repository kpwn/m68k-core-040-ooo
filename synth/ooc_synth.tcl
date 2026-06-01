read_verilog generated/M68kCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context
report_timing_summary -max_paths 5 -file synth/timing_summary.rpt
report_utilization -file synth/util.rpt
set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
puts "=============================="
puts "TARGET_PERIOD_NS 4.000 (250 MHz)"
puts "WNS_NS $wns"
if {$wns < 0} {
  set achieved [expr {1000.0/(4.000 - $wns)}]
  puts "ACHIEVED_FMAX_MHZ $achieved"
} else {
  set achieved [expr {1000.0/(4.000 - $wns)}]
  puts "MET_TIMING_AT_250 yes; HEADROOM_FMAX_MHZ $achieved"
}
puts "=============================="
