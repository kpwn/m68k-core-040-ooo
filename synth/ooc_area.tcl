read_verilog generated/M68kFullCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kFullCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context
report_utilization -file synth/area_flat.rpt
report_utilization -hierarchical -hierarchical_depth 2 -file synth/area_hier.rpt
report_timing_summary -max_paths 4 -file synth/area_timing.rpt
set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
set p   [get_timing_paths -max_paths 1 -nworst 1 -setup]
puts "RESULT WNS $wns FMAX [expr {1000.0/(5.000 - $wns)}]"
puts "RESULT SRC [get_property STARTPOINT_PIN $p]"
puts "RESULT DST [get_property ENDPOINT_PIN $p]"
puts "RESULT LEVELS [get_property LOGIC_LEVELS $p]"
