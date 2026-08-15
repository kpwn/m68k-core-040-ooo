# synth/census.tcl -- FULL post-route failing-endpoint CENSUS on a routed checkpoint.
#
# WHY THIS EXISTS: synth/impl_FullCore.tcl reports `-max_paths 10`, which is useless for
# per-FAMILY analysis. The 2026-08-09 fetch-directed-BTB design spec (§5.4) needed to know
# which module families own the 4512 failing endpoints and could only extrapolate from 10
# reported paths. This script answers that question and is meant to be re-run by any future
# FMax pass -- do not delete it, and do not re-derive it ad hoc.
#
# usage:
#   vivado -mode batch -nojournal -log synth/vivado_census.log \
#          -source synth/census.tcl -tclargs synth/fullcore_routed.dcp synth/census
#
# (synth/impl_FullCore.tcl already writes synth/fullcore_routed.dcp as its last step.)

set dcp    [lindex $argv 0]
set prefix [lindex $argv 1]
open_checkpoint $dcp

puts "########### CENSUS on $dcp ###########"

# ---- 1) the full failing-endpoint list (one worst path per endpoint) ----
report_timing -setup -max_paths 5000 -nworst 1 -slack_lesser_than 0 \
              -file ${prefix}_endpoints.rpt
report_timing_summary -max_paths 10 -file ${prefix}_summary.rpt
report_utilization -file ${prefix}_util.rpt
catch { report_design_analysis -congestion -file ${prefix}_congestion.rpt }

# ---- 2) per-family census: group every failing endpoint by leaf-module prefix ----
set paths [get_timing_paths -setup -max_paths 5000 -nworst 1 -slack_lesser_than 0]
puts "CENSUS_TOTAL_FAILING [llength $paths]"

array unset famCount
array unset famWorst
array unset famSlacks
foreach p $paths {
  set nm  [get_property NAME [get_property ENDPOINT_PIN $p]]
  # leaf-module family key: text before the first "/", with _logic*/_reg* suffixes stripped
  set key [lindex [split $nm "/"] 0]
  regsub {_logic.*$} $key "" key
  regsub {_reg.*$}   $key "" key
  set s [get_property SLACK $p]
  if {![info exists famCount($key)]} {
    set famCount($key) 0 ; set famWorst($key) 99.0 ; set famSlacks($key) {}
  }
  incr famCount($key)
  if {$s < $famWorst($key)} { set famWorst($key) $s }
  lappend famSlacks($key) $s
}

set rows {}
foreach k [array names famCount] {
  set sorted [lsort -real $famSlacks($k)]
  set med    [lindex $sorted [expr {[llength $sorted] / 2}]]
  lappend rows [list $famCount($k) $k $famWorst($k) $med]
}
set fp [open ${prefix}_family.rpt w]
puts $fp [format "%-56s %8s %10s %10s" "FAMILY" "COUNT" "WORST_NS" "MEDIAN_NS"]
foreach r [lsort -integer -decreasing -index 0 $rows] {
  puts $fp [format "%-56s %8d %10.3f %10.3f" \
            [lindex $r 1] [lindex $r 0] [lindex $r 2] [lindex $r 3]]
  puts     [format "CENSUS_FAMILY %-40s %6d  worst %8.3f  median %8.3f" \
            [lindex $r 1] [lindex $r 0] [lindex $r 2] [lindex $r 3]]
}
close $fp

# ---- 3) -through probes for the three families this lever is about ----
proc census_probe {label pat prefix} {
  set cells [get_cells -quiet -hierarchical -filter "NAME =~ $pat"]
  puts "CENSUS_PROBE $label CELLS [llength $cells]"
  if {[llength $cells] == 0} { return }
  set pp [get_timing_paths -quiet -setup -max_paths 5000 -nworst 1 \
                           -slack_lesser_than 0 -through $cells]
  puts "CENSUS_PROBE $label FAILING_ENDPOINTS [llength $pp]"
  if {[llength $pp] > 0} {
    puts "CENSUS_PROBE $label WORST [get_property SLACK [lindex $pp 0]]"
  }
  catch {
    report_timing -setup -max_paths 20 -nworst 1 -through $cells \
                  -file ${prefix}_probe_${label}.rpt
  }
}
# (a) Lever D's spec2 speculative-read cells -- the design spec's claimed #1 failing cone
census_probe spec2_leverD "*BtbPlugin_logic_mem_reg_r4*" $prefix
# (b) the slot-0 BTB port cells (RETAINED by this lever -- the G4 fallback)
census_probe btb_slot0    "*BtbPlugin_logic_mem_reg_r0*" $prefix
# (c) every BTB RAM cell, as the denominator for (a) and (b)
census_probe btb_all      "*BtbPlugin_logic_mem_reg*"    $prefix
# (d) the Aligner preds(L0) -> slot1Ok -> io_shift family flagged in spec §5.3
census_probe aligner_ibuf "*InstructionBuffer*"          $prefix
census_probe fetchalign   "*FetchAlignPlugin*"           $prefix

# -- LS-corridor probes (2026-08-09 LS EU late-split design, spec 6.3) --
# These answer G-L1..G-L4 for the RESOLVE->LAUNCH split. Read-only; safe to re-run.
census_probe ls_s1ctx    "*LsEuPlugin_logic_s1Ctx*"       $prefix
census_probe ls_comp     "*LsEuPlugin_logic_comp*"        $prefix
census_probe ls_llreg    "*LsEuPlugin_logic_llReg*"       $prefix
census_probe ls_sq       "*LsEuPlugin_logic_sq*"          $prefix
census_probe ls_busy     "*LsEuPlugin_logic_busy*"        $prefix
census_probe iq_sel3     "*selPorts_3*"                   $prefix
census_probe dc_tagmem   "*DcachePlugin_logic_tagMem*"    $prefix

# -- COPYBACK SQ->D-cache elastic-pipe probes (2026-08-09, commit 55b5850) --
# The implementation adds a registered S3 result/write boundary plus independent
# StoreQueue send/ack state. These patterns deliberately tolerate synthesis name
# pruning: a zero-cell report is evidence that the named source register did not
# survive under that spelling, not permission to omit the broader module census.
census_probe dc_st_s3       "*DcachePlugin_logic_stS3*"          $prefix
census_probe dc_store_hold  "*DcachePlugin_logic_storePipeHeld*" $prefix
census_probe dc_store_owed  "*DcachePlugin_logic_storeReadOwed*" $prefix
census_probe dc_store_ack   "*DcachePlugin_logic_storeAck*"      $prefix
census_probe sq_send_ptr    "*StoreQueue*sendPtr*"                $prefix
census_probe sq_ack_count   "*StoreQueue*acceptedHalves*"        $prefix

# ---- 4) G-L1: do any failing paths END at these cells? (-to, not -through) ----
proc census_endpoint_probe {label pat} {
  set cells [get_cells -quiet -hierarchical -filter "NAME =~ $pat"]
  if {[llength $cells] == 0} { puts "CENSUS_ENDPOINT $label CELLS 0"; return }
  set pp [get_timing_paths -quiet -setup -max_paths 5000 -nworst 1 \
                           -slack_lesser_than 0 -to $cells]
  puts "CENSUS_ENDPOINT $label CELLS [llength $cells] FAILING_ENDPOINTS [llength $pp]"
}
census_endpoint_probe ls_busy    "*LsEuPlugin_logic_busy*"
census_endpoint_probe ls_s1valid "*LsEuPlugin_logic_s1Valid*"
census_endpoint_probe ls_compv   "*LsEuPlugin_logic_compValid*"
census_endpoint_probe iq_sel3    "*selPorts_3*"
census_endpoint_probe dc_st_s3   "*DcachePlugin_logic_stS3*"
census_endpoint_probe dc_hold    "*DcachePlugin_logic_storePipeHeld*"
census_endpoint_probe sq_send    "*StoreQueue*sendPtr*"
census_endpoint_probe sq_accept  "*StoreQueue*acceptedHalves*"

# ---- 5) G-L3: pblock occupancy headroom (pb_dcache holds every LsEuPlugin_logic* cell) ----
# Vivado 2025.2 silently rejects a multi-pblock list for `-pblocks` under the
# surrounding catch. Emit one report per pblock so a missing file cannot masquerade
# as a successful floorplan gate.
foreach pb [get_pblocks -quiet] {
  set pbCells [llength [get_cells -quiet -of_objects $pb]]
  puts "CENSUS_PBLOCK $pb CELLS $pbCells"
  if {[catch { report_utilization -pblocks $pb -file ${prefix}_${pb}_util.rpt } pbErr]} {
    puts "CENSUS_PBLOCK_ERROR $pb $pbErr"
  }
}
# -- FPU arithmetic-core probes (2026-08-15 FpuCore plan, Task H). These stay silent
# (CELLS 0) until the EU-integration task instantiates FpuCore inside the full core;
# the standalone gate lives in synth/ooc_fpucore.tcl.
census_probe fpu_add    "*FpAddPipe*"      $prefix
census_probe fpu_mul    "*FpMulPipe*"      $prefix
census_probe fpu_round  "*FpRoundPack*"    $prefix
census_probe fpu_iter   "*FpDivSqrtCore*"  $prefix
census_probe fpu_cheap  "*FpCheapPipe*"    $prefix

puts "########### CENSUS COMPLETE ###########"
