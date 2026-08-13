set source_md5 "UNKNOWN"
catch { set source_md5 [lindex [exec md5sum generated/M68kFullCoreSynth.v] 0] }
set checkpoint_md5_file synth/fullcore_synth.md5
set gate_netlist_md5 $source_md5
set reuse_synth 0
if {[info exists ::env(REUSE_SYNTH_DCP)] && $::env(REUSE_SYNTH_DCP) eq "1" &&
    [file exists synth/fullcore_synth.dcp]} {
  set reuse_synth 1
  open_checkpoint synth/fullcore_synth.dcp
  puts "REUSE_SYNTH_DCP synth/fullcore_synth.dcp"
  if {[file exists $checkpoint_md5_file]} {
    set fp [open $checkpoint_md5_file r]
    set gate_netlist_md5 [string trim [read $fp]]
    close $fp
  } else {
    # Never label a reused checkpoint with the current source file's digest. Older
    # checkpoints predate the sidecar and have to remain explicitly unpinned.
    set gate_netlist_md5 "UNKNOWN_REUSED_DCP"
  }
} else {
  read_verilog generated/M68kFullCoreSynth.v
  read_xdc synth/clk.xdc
  # SYNTH_DIRECTIVE / SYNTH_FLATTEN / SYNTH_RETIMING are the SYNTHESIS axis of the
  # implementation-recipe lever.  Handoff section 18 step 6 lever 4 and section 19
  # step 8 both name a fresh `synth_design` under a non-default recipe as the ONE
  # genuinely untried item on the only lever that has ever produced a positive
  # result in this campaign (post-route physical optimisation, +18.65 MHz).  Every
  # physical number published before section 26 descends from a single frozen
  # default-directive synthesis checkpoint, so this axis had never been varied.
  #
  # Defaults reproduce the historical flow verbatim: leaving all three unset emits
  # exactly `synth_design -top ... -mode out_of_context`, so every pre-section-26
  # row regenerates unchanged.
  # PART is a MEASUREMENT knob only -- the shipping target is and remains the -2
  # speed grade.  Handoff section 26.7 names a speed-grade change as the highest
  # expected-value remaining lever and records that it had never been priced;
  # `xcku5p-ffvb676-3-e` is the same package one grade up, so it is a pure device
  # question with no RTL, floorplan or recipe change.  Do not change the default.
  set part "xcku5p-ffvb676-2-e"
  if {[info exists ::env(PART)]} { set part $::env(PART) }
  puts "TARGET_PART $part"
  set synth_args [list -top M68kFullCoreSynth -part $part -mode out_of_context]
  if {[info exists ::env(SYNTH_DIRECTIVE)] && $::env(SYNTH_DIRECTIVE) ne "default"} {
    lappend synth_args -directive $::env(SYNTH_DIRECTIVE)
  }
  if {[info exists ::env(SYNTH_FLATTEN)]} {
    lappend synth_args -flatten_hierarchy $::env(SYNTH_FLATTEN)
  }
  if {[info exists ::env(SYNTH_RETIMING)] && $::env(SYNTH_RETIMING) eq "1"} {
    lappend synth_args -retiming
  }
  puts "SYNTH_ARGS $synth_args"
  synth_design {*}$synth_args
  opt_design
}
# Preserve the optimized post-synthesis checkpoint separately from placement/routing.
# This makes it possible to distinguish RTL depth from floorplan/route loss on every
# physical gate instead of relying on transient messages in the Vivado console log.
report_timing_summary -max_paths 10 -file synth/fullcore_synth_timing.rpt
report_utilization -file synth/fullcore_synth_util.rpt
if {!$reuse_synth} {
  write_checkpoint -force synth/fullcore_synth.dcp
  set fp [open $checkpoint_md5_file w]
  puts $fp $source_md5
  close $fp
}
set synth_paths [get_timing_paths -max_paths 1 -nworst 1 -setup]
if {[llength $synth_paths] > 0} {
  set synth_wns [get_property SLACK $synth_paths]
  puts "POSTSYNTH_FULLCORE_WNS_NS $synth_wns"
}
# The 2026-08-10 five-ID/token-cut exact-DCP 2x2 shows that the legacy broad
# D-cache pblock is now harmful: decode-only is reproducibly -2.720 ns versus
# -2.804 ns for both and -3.648 ns for D-cache-only. Keep every mode available
# as a diagnostic override, but make the measured current-netlist winner the
# default rather than silently applying the obsolete LS-cluster constraint.
set floorplan_mode "decode"
if {[info exists ::env(SKIP_FLOORPLAN)] && $::env(SKIP_FLOORPLAN) eq "1"} {
  set floorplan_mode "none"
}
if {[info exists ::env(FLOORPLAN_MODE)]} {
  set floorplan_mode $::env(FLOORPLAN_MODE)
}
# FLOORPLAN_MODE is a `+`-separated set of pblock tokens, so the section-18 A/B can
# combine boxes without inventing a new keyword per combination. `both` is kept as a
# historical alias for `decode+dcache`.
#   decode    synth/floorplan_decode.xdc     DecodeStage box, X36Y0:X87Y104        (default)
#   decode_fe synth/floorplan_decode_fe.xdc  same box, capture += FetchAlign + Ras (excl. decode)
#   dcache    synth/floorplan_dcache.xdc     legacy LS box, KNOWN-HARMFUL control
#   backend   synth/floorplan_backend.xdc    repaired LS box, X14Y132:X72Y239
#   frontend  synth/floorplan_frontend.xdc   fetch/predict box, X0Y20:X35Y135
#   fetch     synth/floorplan_fetch.xdc      Unified-Fetch-Array cluster box + BRAM sites
if {$floorplan_mode eq "both"} { set floorplan_mode "decode+dcache" }
set floorplan_tokens [split $floorplan_mode "+"]
array set floorplan_xdc {
  decode    synth/floorplan_decode.xdc
  decode_fe synth/floorplan_decode_fe.xdc
  dcache    synth/floorplan_dcache.xdc
  backend   synth/floorplan_backend.xdc
  frontend  synth/floorplan_frontend.xdc
  fetch     synth/floorplan_fetch.xdc
}
foreach tok $floorplan_tokens {
  if {$tok ne "none" && ![info exists floorplan_xdc($tok)]} {
    error "FLOORPLAN_MODE tokens must be from: none decode decode_fe dcache backend frontend fetch (got '$tok')"
  }
}
if {[lsearch -exact $floorplan_tokens "decode"] >= 0 && [lsearch -exact $floorplan_tokens "decode_fe"] >= 0} {
  error "FLOORPLAN_MODE: decode and decode_fe both create pb_decode; pick one"
}
if {[lsearch -exact $floorplan_tokens "dcache"] >= 0 && [lsearch -exact $floorplan_tokens "backend"] >= 0} {
  error "FLOORPLAN_MODE: dcache and backend are the same region; pick one"
}
if {[lsearch -exact $floorplan_tokens "frontend"] >= 0 && [lsearch -exact $floorplan_tokens "fetch"] >= 0} {
  error "FLOORPLAN_MODE: frontend and fetch box the same cluster; pick one"
}
# decode_fe annexes FetchAlign + Ras into pb_decode, which pb_fetch also claims. Two
# pblocks may not both own a cell, so Vivado would take the second assignment and the
# combination would silently mean something other than either box on its own.
if {[lsearch -exact $floorplan_tokens "decode_fe"] >= 0 && [lsearch -exact $floorplan_tokens "fetch"] >= 0} {
  error "FLOORPLAN_MODE: decode_fe and fetch both claim FetchAlign/Ras cells; pick one"
}
puts "FLOORPLAN_MODE $floorplan_mode"
foreach tok $floorplan_tokens {
  if {$tok eq "none"} { continue }
  read_xdc $floorplan_xdc($tok)
}
# Report the captured population of every pblock that actually got created, so a
# capture-filter regression shows up in the log rather than silently in the route.
foreach pb [get_pblocks -quiet] {
  puts "FLOORPLAN [get_property NAME $pb] grid=[get_property GRID_RANGES $pb] cells=[llength [get_cells -quiet -of_objects $pb]]"
}
# Stale per-pblock reports would otherwise survive a mode change and be misread.
foreach stale {pb_decode pb_dcache pb_backend pb_frontend pb_fetch} {
  if {[llength [get_pblocks -quiet $stale]] == 0} {
    file delete -force synth/fullcore_${stale}_util.rpt
  }
}
# IMPL_STRATEGY selects the placer/phys-opt/router recipe.
#
# The default changed on 2026-08-10 from `default` (place / phys_opt / route, one
# pass each) to `postrouteN`, because a same-DCP sweep over the exact `6b246de`
# netlist measured post-route physical optimisation as worth far more than any
# floorplan.  Convergence curve, all from one run, `decode` floorplan:
#
#   round 0 (= the old `default` flow)   WNS -2.094   164.096 MHz
#   round 1                              WNS -1.623   177.841 MHz   (+13.75)
#   round 2                              WNS -1.552   180.115 MHz
#   round 3                              WNS -1.472   182.749 MHz
#   round 4                              WNS -1.464   183.016 MHz
#   round 5                              WNS -1.463   183.050 MHz
#   round 6                              WNS -1.463   183.050 MHz   (plateau)
#
# POSTROUTE_ROUNDS defaults to 3: that is 0.622 ns of the 0.631 ns the plateau
# offers, for roughly half its wall time.  Set POSTROUTE_ROUNDS=1 for a fast gate
# (still +13.75 MHz) or 6 to sit exactly on the plateau.
#
# `default` is retained verbatim so every physical number published in the handoff
# before section 18 regenerates unchanged -- use IMPL_STRATEGY=default to compare
# against any pre-section-18 row.
#
# Why this and not a floorplan: the 2026-08-10 census found all 300 worst unique
# endpoints to be one arc, `FetchAlignPlugin stalled -> IcachePlugin
# s1PredEntries_*/CE`, at 66% route, 20 logic levels, **0 pblock crossings**, with
# a 931-load terminal net.  Seven pblock variants across both evidenced boundaries
# all regressed.  See handoff section 18.
set impl_strategy "postrouteN"
if {[info exists ::env(IMPL_STRATEGY)]} { set impl_strategy $::env(IMPL_STRATEGY) }
puts "IMPL_STRATEGY $impl_strategy"
# (if/elseif rather than `switch`: "default" is a reserved final pattern in Tcl's
#  switch, so using it as an ordinary strategy name there would silently misfire.)
if {$impl_strategy eq "default"} {
  place_design
  phys_opt_design
  route_design
} elseif {$impl_strategy eq "fanout"} {
  # Targeted high-fan-out replication before the ordinary phys-opt pass.
  place_design
  phys_opt_design -directive AggressiveFanoutOpt
  phys_opt_design
  route_design
} elseif {$impl_strategy eq "postroute"} {
  # Cheapest addition: today's flow plus one post-route phys-opt + reroute.
  place_design
  phys_opt_design
  route_design
  phys_opt_design
  route_design -tns_cleanup
} elseif {$impl_strategy eq "postroute2"} {
  # Does a SECOND post-route phys-opt/reroute round buy anything beyond the first?
  place_design
  phys_opt_design
  route_design
  phys_opt_design
  route_design -tns_cleanup
  phys_opt_design
  route_design -tns_cleanup
} elseif {$impl_strategy eq "postrouteN" || $impl_strategy eq "exploreN" ||
          $impl_strategy eq "postrouteNt" || $impl_strategy eq "postrouteNx"} {
  # Iterated post-route physical optimisation, with the WNS after EVERY round
  # printed so one run yields the whole convergence curve instead of one point.
  # POSTROUTE_ROUNDS (default 3) sets the number of post-route rounds; see the
  # measured curve in the IMPL_STRATEGY comment above.
  set rounds 3
  if {[info exists ::env(POSTROUTE_ROUNDS)]} { set rounds $::env(POSTROUTE_ROUNDS) }
  # `exploreN` changes THREE things at once relative to `postrouteN` (placer
  # directive, phys-opt directive, router directive) and measured WORSE on WNS
  # (-1.642 vs -1.463) but far BETTER on breadth (30,048 vs 32,409 failing
  # endpoints).  That confound is what `postrouteNt` and `postrouteNx` separate:
  #   postrouteNt = postrouteN + ExtraTimingOpt PLACEMENT only (rounds stay plain)
  #   postrouteNx = postrouteN + AggressiveExplore PHYS-OPT only (placement and
  #                 router stay plain)
  # Together with the two incumbents these four runs form a clean 2x2 over the
  # placer and phys-opt axes at a fixed router.  See handoff section 19.
  if {$impl_strategy eq "exploreN"} {
    place_design -directive ExtraTimingOpt
    phys_opt_design -directive AggressiveExplore
    route_design -directive Explore
  } elseif {$impl_strategy eq "postrouteNt"} {
    place_design -directive ExtraTimingOpt
    phys_opt_design
    route_design
  } else {
    place_design
    phys_opt_design
    route_design
  }
  set rp [get_timing_paths -max_paths 1 -nworst 1 -setup]
  puts "POSTROUTE_ROUND 0 WNS [get_property SLACK $rp]"
  for {set r 1} {$r <= $rounds} {incr r} {
    if {$impl_strategy eq "exploreN"} {
      phys_opt_design -directive AggressiveExplore
      route_design -directive Explore -tns_cleanup
    } elseif {$impl_strategy eq "postrouteNx"} {
      phys_opt_design -directive AggressiveExplore
      route_design -tns_cleanup
    } else {
      phys_opt_design
      route_design -tns_cleanup
    }
    set rp [get_timing_paths -max_paths 1 -nworst 1 -setup]
    puts "POSTROUTE_ROUND $r WNS [get_property SLACK $rp]"
  }
} elseif {$impl_strategy eq "explore"} {
  # Full tool-effort recipe, including a post-route phys-opt + incremental reroute.
  place_design -directive ExtraTimingOpt
  phys_opt_design -directive AggressiveExplore
  route_design -directive Explore
  phys_opt_design -directive AggressiveExplore
  route_design -directive Explore -tns_cleanup
} else {
  error "IMPL_STRATEGY must be one of: default fanout postroute postroute2 postrouteN postrouteNt postrouteNx exploreN explore"
}
report_timing_summary -max_paths 10 -file synth/fullcore_route_timing.rpt
report_utilization -file synth/fullcore_route_util.rpt
# ── congestion + attribution reports (always-on; routing congestion is a first-class
# gate metric alongside FMax — the recurring limiters are 58-82% ROUTE-dominated) ──
# 0) netlist provenance: distinguish the source currently on disk from the netlist
# actually opened. They intentionally differ during checkpoint-reuse floorplan controls.
puts "SOURCE_MD5 $source_md5"
puts "NETLIST_MD5 $gate_netlist_md5"
# 1) router congestion windows + per-path logic-vs-route attribution
catch { report_design_analysis -congestion -file synth/fullcore_congestion.rpt }
catch { report_design_analysis -timing -max_paths 10 -file synth/fullcore_path_analysis.rpt }
# 2) top fanout nets (catches un-replicated control broadcasts)
catch { report_high_fanout_nets -max_nets 10 -file synth/fullcore_fanout.rpt }
# 3) per-pblock utilization (floorplan capture + regional spill sanity). Vivado
# rejects a list of pblocks for this report, so emit the two reports separately.
foreach pb [get_pblocks -quiet] {
  set pbn [get_property NAME $pb]
  catch { report_utilization -pblocks $pbn -file synth/fullcore_${pbn}_util.rpt }
}
# 4) module-pair slack matrix: which plugin PAIR limits (top-100 worst endpoints)
catch {
  set fp [open synth/fullcore_slack_matrix.rpt w]
  foreach p [get_timing_paths -max_paths 100 -nworst 1 -setup] {
    puts $fp [format "%.3f  %s -> %s" [get_property SLACK $p] \
      [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
  }
  close $fp
}
# 5) archive the router's congestion dump if it dropped one (else it gets overwritten)
catch { file copy -force iter_100_CongestedCLBsAndNets.txt synth/fullcore_congested_nets.txt }
# 6) reopenable snapshot for offline congestion forensics
catch { write_checkpoint -force synth/fullcore_routed.dcp }
set paths [get_timing_paths -max_paths 1 -nworst 1 -setup]
set wns [get_property SLACK $paths]
puts "########### FULLCORE POST-ROUTE @ 250MHz (xcku5p-ffvb676-2) ###########"
puts "POSTROUTE_FULLCORE_WNS_NS $wns"
set achieved [expr {1000.0/(4.000 - $wns)}]
if {$wns < 0} { puts "POSTROUTE_FULLCORE_RESULT FAILED_AT_250  ACHIEVED_FMAX_MHZ $achieved" } else { puts "POSTROUTE_FULLCORE_RESULT MET_250  FMAX_MHZ $achieved" }
puts "######################################################################"
