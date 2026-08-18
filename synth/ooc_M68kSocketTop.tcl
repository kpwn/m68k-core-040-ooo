read_verilog generated/M68kSocketTop.v
read_xdc synth/clk.xdc
synth_design -top M68kSocketTop -part xcku5p-ffvb676-2-e -mode out_of_context
report_utilization -file synth/M68kSocketTop_util.rpt
report_timing_summary -max_paths 8 -file synth/M68kSocketTop_timing.rpt
set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
puts "RESULT SocketTop WNS $wns FMAX [expr {1000.0/(4.000 - $wns)}]"
