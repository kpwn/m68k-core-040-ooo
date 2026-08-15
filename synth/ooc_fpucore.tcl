# ── OOC post-route gate for the standalone 80-bit FPU arithmetic core (FpuCore) ──
# FpuCore is not yet integrated into M68kFullCoreSynth (the EU-integration task does
# that), so this gates the component on its own against the SAME 4.000 ns (250 MHz)
# constraint and the SAME part as every other slice. Reports WNS *and* TNS *and* the
# failing-endpoint count (WNS alone is not a report), plus LUT/FF/BRAM/DSP and the
# DSP48E2 attribution for FpMulPipe, which simulation cannot prove.
set part "xcku5p-ffvb676-2-e"
if {[info exists ::env(PART)]} { set part $::env(PART) }
puts "TARGET_PART $part"

read_verilog generated/M68kFpuCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kFpuCoreSynth -part $part -mode out_of_context
opt_design
report_utilization -file synth/fpucore_synth_util.rpt
set sp [get_timing_paths -quiet -max_paths 1 -nworst 1 -setup]
if {[llength $sp] > 0} { puts "POSTSYNTH_FPUCORE_WNS_NS [get_property SLACK $sp]" }

place_design
phys_opt_design
route_design

set rounds 3
if {[info exists ::env(POSTROUTE_ROUNDS)]} { set rounds $::env(POSTROUTE_ROUNDS) }
for {set i 0} {$i < $rounds} {incr i} {
  set before [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
  phys_opt_design -directive AggressiveExplore
  set after [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
  puts "POSTROUTE_ROUND $i WNS_BEFORE $before WNS_AFTER $after"
  if {$after <= $before} { break }
}

report_timing_summary -max_paths 20 -file synth/fpucore_routed_timing.rpt
report_utilization -file synth/fpucore_routed_util.rpt

set paths [get_timing_paths -max_paths 1 -nworst 1 -setup]
set wns [get_property SLACK $paths]
set failing [get_timing_paths -quiet -setup -max_paths 100000 -nworst 1 -slack_lesser_than 0]
set tns 0.0
foreach pp $failing { set tns [expr {$tns + [get_property SLACK $pp]}] }
set fmax [expr {1000.0/(4.000 - $wns)}]
puts "=========== FpuCore OOC POST-ROUTE @ 250MHz ($part) ==========="
puts "WNS_NS $wns"
puts "TNS_NS $tns"
puts "FAILING_ENDPOINTS [llength $failing]"
puts "FMAX_MHZ $fmax"
if {$wns < 0} { puts "RESULT FAILED_AT_250 ACHIEVED_FMAX_MHZ $fmax" } else { puts "RESULT MET_250 HEADROOM_FMAX_MHZ $fmax" }

# Resource totals, parsed straight out of the routed netlist rather than the report text.
puts "CELLS_LUT   [llength [get_cells -quiet -hierarchical -filter {PRIMITIVE_GROUP == LUT}]]"
puts "CELLS_FF    [llength [get_cells -quiet -hierarchical -filter {PRIMITIVE_GROUP == FLOP_LATCH}]]"
puts "CELLS_CARRY [llength [get_cells -quiet -hierarchical -filter {REF_NAME == CARRY8}]]"
set dsps [get_cells -quiet -hierarchical -filter {PRIMITIVE_GROUP == ARITHMETIC}]
puts "CELLS_DSP   [llength $dsps]"
set bram [get_cells -quiet -hierarchical -filter {PRIMITIVE_GROUP == BLOCKRAM}]
puts "CELLS_BRAM  [llength $bram]"

# ── DSP48E2 inference attribution + internal-register check (mandatory deliverable) ──
set mulDsp 0
foreach d $dsps {
  set nm [get_property NAME $d]
  if {[string match "*mulPipe*" $nm] || [string match "*FpMulPipe*" $nm]} { incr mulDsp }
  puts "DSP_CELL $nm REF [get_property REF_NAME $d] AREG [get_property -quiet AREG $d] BREG [get_property -quiet BREG $d] MREG [get_property -quiet MREG $d] PREG [get_property -quiet PREG $d]"
}
puts "DSP_ATTRIBUTABLE_TO_FPMULPIPE $mulDsp"
if {[llength $dsps] == 0} {
  puts "DSP_INFERENCE FAILED -- FpMulPipe landed in LUT fabric; see MulCore.scala:49-52"
} else {
  puts "DSP_INFERENCE OK"
}

# ── per-submodule census, same shape as synth/census.tcl ──
proc fpu_census {label pat} {
  set cells [get_cells -quiet -hierarchical -filter "NAME =~ $pat"]
  set lut 0; set ff 0; set dsp 0
  foreach c $cells {
    set g [get_property -quiet PRIMITIVE_GROUP $c]
    if {$g eq "LUT"} { incr lut } elseif {$g eq "FLOP_LATCH"} { incr ff } elseif {$g eq "ARITHMETIC"} { incr dsp }
  }
  puts "FPU_CENSUS $label LUT $lut FF $ff DSP $dsp"
}
fpu_census add   "*addPipe*"
fpu_census mul   "*mulPipe*"
fpu_census cheap "*cheapPipe*"
fpu_census iter  "*iterCore*"
fpu_census round "*roundPack*"

# ── the worst path, named, so a re-gate can tell RTL depth from placement loss ──
foreach pp [get_timing_paths -quiet -max_paths 5 -nworst 1 -setup] {
  puts "WORST_PATH SLACK [get_property SLACK $pp] FROM [get_property STARTPOINT_PIN $pp] TO [get_property ENDPOINT_PIN $pp] LOGIC_LEVELS [get_property LOGIC_LEVELS $pp]"
}
puts "########### FPUCORE GATE COMPLETE ###########"
