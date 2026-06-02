read_verilog generated/M68kFullCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kFullCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context
report_utilization -file synth/M68kFullCoreSynth_util.rpt
report_timing_summary -max_paths 8 -file synth/M68kFullCoreSynth_timing.rpt
set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
puts "RESULT FullCore WNS $wns FMAX [expr {1000.0/(4.000 - $wns)}]"
