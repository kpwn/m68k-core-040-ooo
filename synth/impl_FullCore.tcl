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
# SUPERSEDED by the UFA/VTL restructure's own gate (ledger §37, 2026-08-13):
# gated on THIS netlist, `decode` measured -1.958 ns / 167.842 MHz -- 14.9 MHz
# WORSE than the pre-restructure baseline -- while `decode+fetch` measured
# -1.117 ns / 195.427 MHz, the accepted result. `decode` is kept as the
# like-for-like reference arm for future re-gates (spec R8), not as a
# recommended default. Keep every mode available as a diagnostic override,
# but default to the measured current-netlist winner.
set floorplan_mode "decode+fetch"
if {[info exists ::env(SKIP_FLOORPLAN)] && $::env(SKIP_FLOORPLAN) eq "1"} {
  set floorplan_mode "none"
}
if {[info exists ::env(FLOORPLAN_MODE)]} {
  set floorplan_mode $::env(FLOORPLAN_MODE)
}
# FLOORPLAN_MODE is a `+`-separated set of pblock tokens, so the section-18 A/B can
# combine boxes without inventing a new keyword per combination. `both` is kept as a
# historical alias for `decode+dcache`.
#   decode    synth/floorplan_decode.xdc     DecodeStage box, X36Y0:X87Y104        (like-for-like reference arm, ledger §37)
#   decode_fe synth/floorplan_decode_fe.xdc  same box, capture += FetchAlign + Ras (excl. decode)
#   dcache    synth/floorplan_dcache.xdc     legacy LS box, KNOWN-HARMFUL control
#   backend   synth/floorplan_backend.xdc    repaired LS box, X14Y132:X72Y239
#   frontend  synth/floorplan_frontend.xdc   fetch/predict box, X0Y20:X35Y135
#   fetch     synth/floorplan_fetch.xdc      Unified-Fetch-Array cluster box + BRAM sites (part of the default combo, decode+fetch)
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
# Same double-claim, same reason: decode_fe's FetchAlign/Ras annexation collides with
# pb_frontend's own (wider) claim on the identical cells. Task 15's review found this
# guard was added for decode_fe+fetch but the pre-existing decode_fe+frontend pair was
# left open with the identical failure mode -- closing it here.
if {[lsearch -exact $floorplan_tokens "decode_fe"] >= 0 && [lsearch -exact $floorplan_tokens "frontend"] >= 0} {
  error "FLOORPLAN_MODE: decode_fe and frontend both claim FetchAlign/Ras cells; pick one"
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
# floorplan.  Convergence curve, all from one run, `decode` floorplan (STALE netlist,
# retained for the historical `default`-vs-`postrouteN` comparison only):
#
#   round 0 (= the old `default` flow)   WNS -2.094   164.096 MHz
#   round 1                              WNS -1.623   177.841 MHz   (+13.75)
#   round 2                              WNS -1.552   180.115 MHz
#   round 3                              WNS -1.472   182.749 MHz
#   round 4                              WNS -1.464   183.016 MHz
#   round 5                              WNS -1.463   183.050 MHz
#   round 6                              WNS -1.463   183.050 MHz   (plateau)
#
# SUPERSEDED (2026-08-14, ledger, `bed9aad`, `decode+fetch` floorplan, current
# netlist): the plateau above does NOT generalise -- on the post-UFA/VTL netlist
# the convergence tail is much longer and the early "half the wall time for
# 98.6% of the gain" trade this section used to justify round=3 is false here:
#
#   round 0    WNS -1.278   ~192.8 MHz         round 7   WNS -0.986   200.56 MHz
#   round 1    WNS -1.157   ~195.6 MHz         round 8   WNS -0.969   200.97 MHz
#   round 2    WNS -1.135   ~196.1 MHz         round 9   WNS -0.964   201.45 MHz  (plateau)
#   round 3    WNS -1.117   195.427 MHz        round 10  WNS -0.964   201.45 MHz
#   round 4    WNS -1.099   ~196.5 MHz         round 11  WNS -0.964   201.45 MHz
#   round 5    WNS -1.114   ~196.1 MHz         round 12  WNS -0.964   201.45 MHz
#   round 6    WNS -1.028   ~198.6 MHz
#
# Non-monotonic (round 5 regresses from round 4, then round 6-9 jump well past
# both) -- this is NOT a smooth convergence, more rounds can still be worth
# trying on a future netlist even after an apparent local plateau. round=3 was
# stopping at 195.427 MHz, 6 MHz short of where round=9 actually plateaus
# (201.450 MHz, confirmed identical across 4 consecutive rounds, 0 errors, hold
# met, same CLB LUT/FF/BRAM as round 3). POSTROUTE_ROUNDS now defaults to 9.
# Re-derive this curve after any RTL or floorplan change that touches the
# frontend/D-cache/IssueQueue clusters -- do not assume it transfers.
#
# `default` is retained verbatim so every physical number published in the handoff
# before section 18 regenerates unchanged -- use IMPL_STRATEGY=default to compare
# against any pre-section-18 row.
#
# Why postrouteN and not `default`: unchanged from the 2026-08-10 census (below).
#
# SUPERSEDED, floorplan half only: the 2026-08-10 census's "no floorplan helps"
# finding was specific to that netlist's limiter, `FetchAlignPlugin stalled ->
# IcachePlugin s1PredEntries_*/CE`. The UFA/VTL restructure (M1-M4) deleted
# `s1PredEntries` outright and consolidated the frontend into a boxable cluster;
# `pb_fetch` (M5, ledger §37) is now worth +27.585 MHz on THIS netlist, and is
# the default floorplan combination above. See handoff section 18 for the
# original (now historical) census, and ledger §37 for the current one.
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
  set rounds 9
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

# SIGN-OFF CHECK against the real target (200 MHz / 5.000 ns), on the SAME
# already-placed-and-routed implementation -- no re-place, no re-route, no
# re-optimization. The 4.000 ns constraint above is an OPTIMIZATION PROBE:
# targeting it directly makes phys_opt/route try harder and converges to a
# BETTER result than targeting 5.000 ns directly does (confirmed 2026-08-14,
# ledger sec 39 -- a matched-round-count 200MHz-target build plateaus
# immediately and never catches up). This block answers the separate,
# simpler question "does the design we actually built meet the real spec",
# by re-checking timing on the fixed physical implementation against the
# real target period. Setup slack scales exactly with period on a fixed
# implementation (required_time = period - const), so this is a legitimate
# sign-off re-check, not a second optimization pass -- do not read a WNS
# from here as if the tool had tried to hit 200 MHz; it didn't need to.
catch {
  create_clock -name clk -period 5.000 [get_ports clk]
  set p200 [get_timing_paths -max_paths 1 -nworst 1 -setup]
  set wns200 [get_property SLACK $p200]
  puts "########### SIGN-OFF @ 200MHz (real target, same routed netlist) ###########"
  puts "SIGNOFF_200MHZ_WNS_NS $wns200"
  if {$wns200 >= 0} { puts "SIGNOFF_200MHZ_RESULT MET_200" } else { puts "SIGNOFF_200MHZ_RESULT FAILED_AT_200" }
  puts "##############################################################################"
  create_clock -name clk -period 4.000 [get_ports clk]
}
