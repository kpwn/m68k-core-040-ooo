read_verilog generated/M68kIqSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kIqSynth -part xcku5p-ffvb676-2-e -mode out_of_context
report_utilization -file synth/M68kIqSynth_util.rpt
report_timing_summary -max_paths 3 -file synth/M68kIqSynth_timing.rpt
set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
puts "RESULT M68kIqSynth WNS $wns FMAX [expr {1000.0/(4.000 - $wns)}]"
