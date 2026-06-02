read_verilog generated/M68kPrfSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kPrfSynth -part xcku5p-ffvb676-2-e -mode out_of_context
report_utilization -file synth/M68kPrfSynth_util.rpt
report_timing_summary -max_paths 3 -file synth/M68kPrfSynth_timing.rpt
set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
puts "RESULT M68kPrfSynth WNS $wns FMAX [expr {1000.0/(4.000 - $wns)}]"
