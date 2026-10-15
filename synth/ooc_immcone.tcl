# usage: vivado -mode batch -source synth/ooc_immcone.tcl -tclargs <Top> <outdir>
set top [lindex $argv 0]
set od  [lindex $argv 1]
file mkdir $od
read_verilog generated/$top.v
read_xdc synth/clk.xdc
synth_design -top $top -part xcku5p-ffvb676-2-e -mode out_of_context
report_utilization -file $od/util.rpt
report_timing_summary -max_paths 10 -file $od/timing.rpt
set p [get_timing_paths -max_paths 1 -nworst 1 -setup]
puts "RESULT $top WNS [get_property SLACK $p] LEVELS [get_property LOGIC_LEVELS $p]"
set ps [get_timing_paths -max_paths 200 -nworst 1 -setup -to [get_cells -hier -filter {NAME =~ *grpData*}]]
set lv {}
foreach q $ps { lappend lv [get_property LOGIC_LEVELS $q] }
puts "RESULT $top grpData-endpoint paths [llength $ps] worstSlack [get_property SLACK [lindex $ps 0]] levels [lsort -integer -decreasing $lv]"
