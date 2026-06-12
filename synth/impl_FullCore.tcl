read_verilog generated/M68kFullCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kFullCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context
opt_design
# Front-end floorplan: co-locate DecodeStage so the decode->ring nets stay local (read after
# opt so the cell filter sees elaborated leaves). Part of the front-end FMax stack (195->~218).
read_xdc synth/floorplan_decode.xdc
# LS-cluster floorplan: co-locate D-cache + DTLB + LS-EU so the valids->hrPpn cross-module
# load/translate nets stay local (read after opt so the cell filter sees elaborated leaves).
# Attacks the post-route limiter DcachePlugin valids/C -> DtlbPlugin hrPpn/CE (73% route).
read_xdc synth/floorplan_dcache.xdc
puts "FLOORPLAN pb_dcache cells: [llength [get_cells -of_objects [get_pblocks pb_dcache]]]"
place_design
phys_opt_design
route_design
report_timing_summary -max_paths 10 -file synth/fullcore_route_timing.rpt
report_utilization -file synth/fullcore_route_util.rpt
set paths [get_timing_paths -max_paths 1 -nworst 1 -setup]
set wns [get_property SLACK $paths]
puts "########### FULLCORE POST-ROUTE @ 250MHz (xcku5p-ffvb676-2) ###########"
puts "POSTROUTE_FULLCORE_WNS_NS $wns"
set achieved [expr {1000.0/(4.000 - $wns)}]
if {$wns < 0} { puts "POSTROUTE_FULLCORE_RESULT FAILED_AT_250  ACHIEVED_FMAX_MHZ $achieved" } else { puts "POSTROUTE_FULLCORE_RESULT MET_250  FMAX_MHZ $achieved" }
puts "######################################################################"
