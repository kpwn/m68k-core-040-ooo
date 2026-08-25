set source_md5 "UNKNOWN"
catch { set source_md5 [lindex [exec md5sum generated/M68kFullCoreSynth.v] 0] }
# Sign-off period is a CONSTANT: 5.000ns / 200MHz is the real deployment spec and the
# SIGNOFF_200MHZ_* verdict at the bottom of this file is always computed against it.
# probe_period is the (optionally tighter) period the placer/router actually optimises
# for; see the IMPL_PROBE_PERIOD_NS block below. Hoisted here so both the fresh-synth
# and the REUSE_SYNTH_DCP paths see them.
set signoff_period 5.000
set probe_period $signoff_period
if {[info exists ::env(IMPL_PROBE_PERIOD_NS)]} {
  set probe_period $::env(IMPL_PROBE_PERIOD_NS)
}
set checkpoint_md5_file synth/fullcore_synth.md5
set gate_netlist_md5 $source_md5
set reuse_synth 0
if {[info exists ::env(REUSE_SYNTH_DCP)] && $::env(REUSE_SYNTH_DCP) eq "1" &&
    [file exists synth/fullcore_synth.dcp]} {
  set reuse_synth 1
  open_checkpoint synth/fullcore_synth.dcp
  puts "REUSE_SYNTH_DCP synth/fullcore_synth.dcp"
  # A reused checkpoint carries whatever period it was synthesised under, so the probe
  # has to be re-applied here as well -- otherwise IMPL_PROBE_PERIOD_NS would silently
  # do nothing on the reuse path (place/route would optimise for the checkpoint's own
  # period) while still relabelling the output as a probe run.
  if {$probe_period ne $signoff_period} {
    create_clock -name clk -period $probe_period [get_ports clk]
    puts "IMPL_PROBE_PERIOD_NS $probe_period (signoff stays $signoff_period, reused DCP)"
  }
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
  # IMPL_PROBE_PERIOD_NS -- deliberate OVER-CONSTRAINT probe (see the "Historical note"
  # block at the bottom of this file, which explicitly prescribes restoring this
  # mechanism "if a future build ever reintroduces a tighter probe period").
  #
  # WHY THIS EXISTS AGAIN (2026-08-25). The 5.000ns-primary gate on `9c1f87e3` routed to
  # WNS -0.015ns / 199.402 MHz and then PLATEAUED for six consecutive postroute rounds
  # (rounds 4-9 all -0.015). Total TNS across the whole design was -0.277ns over just 32
  # failing endpoints, all inside a 0.015ns band. That is not a design that has run out
  # of physical headroom -- it is an optimiser that has run out of OBJECTIVE PRESSURE:
  # once `phys_opt_design` has driven WNS to within a hair of the 5.000ns target and TNS
  # is effectively zero, it has nothing left to push against and stops improving. Ledger
  # sec 39 recorded exactly this effect from the other direction: targeting 4.000ns made
  # phys_opt/route converge to a BETTER 200MHz-equivalent result than targeting 5.000ns
  # directly, and the last time this design ever MET 200MHz post-route (commit `2ca0974`,
  # SIGNOFF_200MHZ_WNS_NS +0.009) it was under precisely that flow.
  #
  # MEASURED RESULT (2026-08-25, `9c1f87e3`, same netlist md5 872c3dc5f666463f9842f5cb2a4b4d0c
  # for both arms, uncontended machine, POSTROUTE_ROUNDS=9, floorplan `decode+fetch`).
  # The two arms differ ONLY in this period; identical RTL, identical netlist, identical
  # floorplan, identical strategy:
  #
  #   arm                  postroute plateau        re-analysed at real 5.000ns
  #   5.000ns primary      -0.015ns @5ns (r4-r9)    WNS -0.015  FAILED_AT_200  199.402 MHz
  #   4.000ns probe        -0.958ns @4ns (r4-r9)    WNS +0.042  MET_200        201.694 MHz
  #
  # +0.057ns of real, routed setup slack for a constraint change alone. The mechanism is
  # the one predicted above: under the 5.000ns target phys_opt reached TNS -0.277ns over
  # 32 endpoints spanning a 0.015ns band and stopped -- it had essentially satisfied its
  # objective. Under the 4.000ns target every one of those paths is ~1ns short, so
  # phys_opt keeps working all of them, and the netlist it leaves behind has real margin
  # at 5.000ns. Ledger sec 39's finding therefore REPRODUCES on the current, much-changed
  # netlist; the 2026-08-19 directive's re-validation question is answered YES.
  #
  # Leaving this unset reproduces the current 5.000ns-primary flow BYTE-IDENTICALLY --
  # no probe XDC is generated, so the user directive of 2026-08-19 remains the default
  # and every number published under it regenerates unchanged. It is a measurement knob,
  # not a spec change: SIGNOFF_200MHZ_* below is ALWAYS re-derived at a real 5.000ns
  # constraint against the finished routed netlist, never extrapolated from the probe.
  #
  # The probe has to be delivered as an XDC, NOT as a bare `create_clock` here: in
  # non-project mode `read_verilog`/`read_xdc` only QUEUE their inputs, and the design
  # does not exist until `synth_design` runs, so `create_clock ... [get_ports clk]` at
  # this point dies with "No open design". Swapping the constraint file is also what
  # makes the probe apply to SYNTHESIS as well as place/route, which is what the
  # historical 4.000ns flow did.
  if {$probe_period ne $signoff_period} {
    set probe_xdc synth/clk_probe.xdc
    set pf [open $probe_xdc w]
    puts $pf "# GENERATED by impl_FullCore.tcl for IMPL_PROBE_PERIOD_NS=$probe_period -- do not edit, do not commit."
    puts $pf "create_clock -name clk -period $probe_period \[get_ports clk\]"
    close $pf
    read_xdc $probe_xdc
    puts "IMPL_PROBE_PERIOD_NS $probe_period (signoff stays $signoff_period) via $probe_xdc"
  } else {
    read_xdc synth/clk.xdc
  }
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
puts "########### FULLCORE POST-ROUTE @ ${probe_period}ns (xcku5p-ffvb676-2) ###########"
puts "POSTROUTE_FULLCORE_WNS_NS $wns"
# Achieved FMax is derived from the period actually being optimised for, NOT from a
# hardcoded 5.000 -- mixing those is how `impl_FullCore_perf.tcl` ended up reporting a
# 4.000ns-derived FMax under a 5.000ns clock. Under the default (no probe) this is
# byte-identical to the old `1000.0/(5.000 - $wns)`.
set achieved [expr {1000.0/($probe_period - $wns)}]
set target_mhz [expr {int(1000.0/$probe_period + 0.5)}]
if {$wns < 0} { puts "POSTROUTE_FULLCORE_RESULT FAILED_AT_${target_mhz}  ACHIEVED_FMAX_MHZ $achieved" } else { puts "POSTROUTE_FULLCORE_RESULT MET_${target_mhz}  FMAX_MHZ $achieved" }
puts "######################################################################"

# Historical note (2026-08-18/19): from ~2026-08-14 through this build, the
# PRIMARY implementation constraint here was deliberately held at 4.000ns
# (250MHz) as an "optimization probe" -- targeting the tighter period made
# phys_opt/route converge to a BETTER 200MHz-equivalent result than
# targeting 5.000ns/200MHz directly (ledger sec 39), and a separate
# post-route-only re-check block re-applied 5.000ns against the same fixed,
# already-routed netlist to report the true sign-off number without a
# second optimization pass. User directive 2026-08-19: force the primary
# target to 5.000ns going forward regardless of that finding (either to
# re-validate it against the current, much-changed netlist, or simply to
# build against the honest real spec). synth/clk.xdc now reads 5.000ns
# directly, so the number above (POSTROUTE_FULLCORE_*) IS the real sign-off
# number -- the separate re-check block is gone, since re-applying 5.000ns
# when the netlist was already optimized for 5.000ns would be a no-op.
# If a future build ever reintroduces a tighter probe period, restore an
# analogous post-route re-check block rather than trusting the probe number
# as sign-off.
#
# 2026-08-25: IMPL_PROBE_PERIOD_NS reintroduces exactly that, as an opt-in
# measurement knob, so the prescribed re-check block is restored below. It is
# a pure re-analysis of the ALREADY-ROUTED netlist at the real 5.000ns spec --
# no second place/route/phys-opt pass runs, so the probe cannot flatter the
# sign-off number: the routed netlist is frozen before the clock is relaxed.
# With no probe set, probe_period == signoff_period and this block is skipped,
# leaving `$wns` exactly as the 2026-08-19 directive produced it.
if {$probe_period ne $signoff_period} {
  create_clock -name clk -period $signoff_period [get_ports clk]
  report_timing_summary -max_paths 10 -file synth/fullcore_signoff_timing.rpt
  set signoff_paths [get_timing_paths -max_paths 1 -nworst 1 -setup]
  set wns [get_property SLACK $signoff_paths]
  puts "SIGNOFF_RECHECK re-analysed the routed netlist at ${signoff_period}ns (probe was ${probe_period}ns)"
  # Re-emit the family census at the sign-off constraint too -- the probe-period
  # top-100 is not the same set of paths that actually limit the 200MHz verdict.
  catch {
    set fp [open synth/fullcore_signoff_slack_matrix.rpt w]
    foreach p [get_timing_paths -max_paths 100 -nworst 1 -setup] {
      puts $fp [format "%.3f  %s -> %s" [get_property SLACK $p] \
        [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
    }
    close $fp
  }
}
puts "SIGNOFF_200MHZ_WNS_NS $wns"
if {$wns >= 0} { puts "SIGNOFF_200MHZ_RESULT MET_200" } else { puts "SIGNOFF_200MHZ_RESULT FAILED_AT_200" }
