# usage: vivado -mode batch -source synth/ooc_redic_socket.tcl -tclargs <verilog> <outdir>
set v  [lindex $argv 0]
set od [lindex $argv 1]
file mkdir $od
read_verilog $v
read_xdc /var/tmp/m68k-fable/redic/synth/clk.xdc
synth_design -top M68kSocketTop -part xcku5p-ffvb676-2-e -mode out_of_context
report_utilization -file $od/util.rpt
report_timing_summary -max_paths 10 -file $od/timing.rpt
set p [get_timing_paths -max_paths 1 -nworst 1 -setup]
puts "RESULT WNS [get_property SLACK $p] LEVELS [get_property LOGIC_LEVELS $p]"
foreach pat {IcachePlugin FetchAlignPlugin ufaWrData rspPredReg rspDataReg bypPred ibuf} {
  set l [llength [get_cells -hier -filter "REF_NAME =~ LUT* && NAME =~ *$pat*"]]
  set f [llength [get_cells -hier -filter "REF_NAME =~ FD* && NAME =~ *$pat*"]]
  set m [llength [get_cells -hier -filter "REF_NAME =~ RAM* && NAME =~ *$pat*"]]
  puts "RESULT cells $pat LUT $l FF $f RAM $m"
}
foreach {tag pat} {predMem *predMem* jobRegs *logic_job* ufaWrData *ufaWrData* rspPredReg *rspPredReg* bypPred *bypPred_reg* ibufEntries *ibuf*entries* lineMem *lineMem*} {
  set cs [get_cells -hier -filter "NAME =~ $pat"]
  if {[llength $cs] == 0} { puts "RESULT path $tag none"; continue }
  set ps [get_timing_paths -max_paths 1 -nworst 1 -setup -to $cs]
  if {[llength $ps] == 0} { puts "RESULT path $tag nopath"; continue }
  puts "RESULT path $tag slack [get_property SLACK $ps] levels [get_property LOGIC_LEVELS $ps] from [get_property STARTPOINT_PIN $ps]"
}
# per-plugin census by cell-name prefix
array unset cnt
foreach c [get_cells -hier -filter {REF_NAME =~ LUT*}] {
  set n [get_property NAME $c]
  regsub {^.*socket_core/} $n "" n
  if {[regexp {^([A-Za-z0-9]+?)(_logic|_|\[|/|$)} $n -> k]} {} else { set k other }
  if {![info exists cnt($k)]} { set cnt($k) 0 }
  incr cnt($k)
}
set fh [open $od/census.txt w]
foreach k [lsort [array names cnt]] { puts $fh "$k $cnt($k)" }
close $fh
