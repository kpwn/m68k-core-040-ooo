read_verilog generated/M68kBackendSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kBackendSynth -part xcku5p-ffvb676-2-e -mode out_of_context
opt_design
place_design
phys_opt_design
route_design
report_timing_summary -max_paths 10 -file synth/backend_route_timing.rpt
report_utilization -file synth/backend_route_util.rpt
set paths [get_timing_paths -max_paths 1 -nworst 1 -setup]
set wns [get_property SLACK $paths]
puts "########### BACKEND POST-ROUTE @ 250MHz (xcku5p-ffvb676-2) ###########"
puts "POSTROUTE_WNS_NS $wns"
set achieved [expr {1000.0/(4.000 - $wns)}]
if {$wns < 0} { puts "POSTROUTE_RESULT FAILED_AT_250  ACHIEVED_FMAX_MHZ $achieved" } else { puts "POSTROUTE_RESULT MET_250  FMAX_AT_THIS_PERIOD_MHZ $achieved" }
puts "######################################################################"
