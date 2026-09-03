read_verilog generated/M68kFullCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kFullCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context

# ---- the gate number (identical query to ooc_M68kFullCoreSynth.tcl) ----
set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
puts "RESULT FullCore WNS $wns FMAX [expr {1000.0/(5.000 - $wns)}]"

# ---- named endpoints (identical query to the A/B run) ----
proc ep_slack {pat name} {
  if {[catch {
    set c [get_cells -hier -filter "NAME =~ $pat"]
    if {[llength $c] == 0} { puts "ENDPOINT $name ABSENT"; return }
    set p [get_timing_paths -to [get_pins -of_objects $c -filter {DIRECTION == IN}] \
             -max_paths 1 -nworst 1 -setup]
    puts "ENDPOINT $name WNS [get_property SLACK $p]"
  } err]} { puts "ENDPOINT $name ERROR $err" }
}
ep_slack {*faultDynMem_reg*} faultDynMem
ep_slack {*LsEuPlugin_logic_s1Index_reg*} s1Index

# ---- "if task #127 were folded": worst path NOT ending on faultDynMem ----
# Answers the standing hypothesis that this netlist's ceiling, once #127 is gone, is set
# by a third family well above the baseline's 178.35. Wrapped in catch: this is a bonus
# measurement and must never be able to fail the gate above.
if {[catch {
  set paths [get_timing_paths -max_paths 600 -unique_pins -setup]
  set seen [dict create]
  set n 0
  foreach p $paths {
    set ep ""
    catch { set ep [get_property NAME [get_property ENDPOINT_PIN $p]] }
    if {$ep eq ""} { continue }
    set fam [regsub -all {_[0-9]+} $ep ""]
    if {![dict exists $seen $fam]} {
      dict set seen $fam 1
      incr n
      puts [format "LADDER %2d %8.3f %s" $n [get_property SLACK $p] $fam]
      if {$n >= 12} { break }
    }
  }
} err]} { puts "LADDER ERROR $err" }
