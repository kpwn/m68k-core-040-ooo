# usage: vivado -mode batch -source synth/ooc_decode.tcl -tclargs <Top> <outdir>
# OOC synth of the DecodeStage probe (top/DecodeSynth.scala) + per-cell dump for attribution.
set top [lindex $argv 0]
set od  [lindex $argv 1]
file mkdir $od
read_verilog generated/$top.v
read_xdc synth/clk.xdc
synth_design -top $top -part xcku5p-ffvb676-2-e -mode out_of_context -directive PerformanceOptimized -keep_equivalent_registers -flatten_hierarchy rebuilt
report_utilization -file $od/util.rpt
report_timing_summary -max_paths 20 -file $od/timing.rpt
set p [get_timing_paths -max_paths 1 -nworst 1 -setup]
puts "RESULT $top WNS [get_property SLACK $p] LEVELS [get_property LOGIC_LEVELS $p]"
set f [open $od/cells.tsv w]
foreach c [get_cells -hier -filter {IS_PRIMITIVE}] { puts $f "[get_property NAME $c]\t[get_property REF_NAME $c]" }
close $f
set f [open $od/paths.tsv w]
foreach q [get_timing_paths -setup -max_paths 2000 -nworst 1] {
  puts $f "[get_property SLACK $q]\t[get_property LOGIC_LEVELS $q]\t[get_property STARTPOINT_PIN $q]\t[get_property ENDPOINT_PIN $q]"
}
close $f
