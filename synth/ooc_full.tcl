read_verilog generated/M68kCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context
report_utilization -file synth/full_util.rpt
set paths [get_timing_paths -max_paths 1 -nworst 1 -setup]
puts "FULL_SYNTH_DONE WNS [get_property SLACK $paths]"
