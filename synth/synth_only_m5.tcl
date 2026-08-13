# M5 (Task 15) netlist producer: SYNTHESIS ONLY, so the pb_fetch sizing probe has a
# real post-M1-M4 checkpoint to measure.
#
# GC-1: this run places nothing, routes nothing, and its WNS must not be read,
# recorded or quoted. It exists solely to produce a netlist that
# synth/probe_floorplan_fetch.tcl can size a box against. The single post-route gate
# is Task 16 and only Task 16.
#
# Flow mirrors synth/impl_FullCore.tcl's own synthesis half verbatim (same part, same
# out_of_context mode, same clk.xdc, same opt_design) so the cell population the probe
# measures is the population the gate will actually place.
read_verilog generated/M68kFullCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kFullCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context
opt_design
write_checkpoint -force synth/m5_synth.dcp
report_utilization -file synth/m5_synth_util.rpt
puts "M5_SYNTH_DONE"
