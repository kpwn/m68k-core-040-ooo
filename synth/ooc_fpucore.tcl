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
# PRIMITIVE_LEVEL != INTERNAL is REQUIRED: a placed DSP48E2 carries eight INTERNAL child
# instances (DSP_A_B_DATA_INST, DSP_MULTIPLIER_INST, DSP_ALU_INST, ...), so a naive
# get_cells -hierarchical reports 9x the real DSP count.
proc leafcount {filt} {
  return [llength [get_cells -quiet -hierarchical -filter "($filt) && PRIMITIVE_LEVEL != INTERNAL"]]
}
puts "CELLS_LUT   [leafcount {PRIMITIVE_TYPE =~ CLB.LUT.*}]"
puts "CELLS_FF    [leafcount {PRIMITIVE_TYPE =~ REGISTER.*}]"
puts "CELLS_CARRY [leafcount {REF_NAME == CARRY8}]"
set dsps [get_cells -quiet -hierarchical -filter {REF_NAME == DSP48E2 && PRIMITIVE_LEVEL != INTERNAL}]
puts "CELLS_DSP   [llength $dsps]"
puts "CELLS_BRAM  [leafcount {PRIMITIVE_TYPE =~ BLOCKRAM.*}]"

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
  set lut  [llength [get_cells -quiet -hierarchical -filter "NAME =~ $pat && PRIMITIVE_TYPE =~ CLB.LUT.* && PRIMITIVE_LEVEL != INTERNAL"]]
  set ff   [llength [get_cells -quiet -hierarchical -filter "NAME =~ $pat && PRIMITIVE_TYPE =~ REGISTER.* && PRIMITIVE_LEVEL != INTERNAL"]]
  set carr [llength [get_cells -quiet -hierarchical -filter "NAME =~ $pat && REF_NAME == CARRY8"]]
  set dsp  [llength [get_cells -quiet -hierarchical -filter "NAME =~ $pat && REF_NAME == DSP48E2 && PRIMITIVE_LEVEL != INTERNAL"]]
  puts "FPU_CENSUS $label LUT $lut FF $ff CARRY8 $carr DSP $dsp"
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
