read_verilog generated/M68kBackendSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kBackendSynth -part xcku5p-ffvb676-2-e -mode out_of_context
report_timing_summary -max_paths 8 -file synth/backend_timing.rpt
report_utilization -file synth/backend_util.rpt
set paths [get_timing_paths -max_paths 1 -nworst 1 -setup]
set wns [get_property SLACK $paths]
puts "=========== BACKEND OOC @ 250MHz (xcku5p-ffvb676-2) ==========="
puts "WNS_NS $wns"
set achieved [expr {1000.0/(4.000 - $wns)}]
if {$wns < 0} { puts "RESULT FAILED_AT_250  ACHIEVED_FMAX_MHZ $achieved" } else { puts "RESULT MET_250  HEADROOM_FMAX_MHZ $achieved" }
puts "==============================================================="
