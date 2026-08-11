# Read-only PIN-KIND census of the 200 MHz deficit population.
#
# Handoff section 23.5 established that a new pipeline-stage boundary whose
# captured/forwarded state lands on a CLOCK-ENABLE (/CE) cone is far less
# improvable by the iterated post-route phys_opt/route -tns_cleanup loop than
# one that lands on a DATA (/D) cone: Slice 1 (B3) routed BETTER than baseline
# at post-route round 0 (-1.836 vs -2.094) and then gained only 0.110 ns across
# three rounds against baseline's 0.622 ns, ending -0.254 ns WORSE.  Its new
# worst path terminated on `s1PredEntries_0[122]/CE`.
#
# Handoff section 24.5 reported a pin-kind split (56 % CE) but ONLY for the
# 2,129-endpoint residual population that survives the hypothetical `a3_cluster`
# scenario -- i.e. after the three frontend arcs AND all 42,428 LSU cells have
# been false-pathed.  Section 26.7 records that carrying an `a3_cluster`
# residual into a dispatch as though it described the real design is a
# repeatable error that this campaign has now made twice.
#
# This probe measures the pin-kind split on the UNTOUCHED baseline population --
# the 4,890 endpoints below -1.000 ns that actually stand between this design
# and 200 MHz -- cross-tabbed by (startpoint family -> endpoint family), with
# per-pair logic depth and route share.  That is the table a re-pipelining
# design has to be built from: it says which arcs are deep enough to be worth a
# stage, and which of them would land their new boundary on a CE cone.
#
#   DCP=synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp \
#   OUT=synth/probe_pinkind \
#   vivado -mode batch -nojournal -nolog -source synth/probe_pinkind_population.tcl
#
# Never writes the design.  No what-if is applied: this is a pure census, so
# there is no set_false_path object-count guard to assert (section 19 step 1's
# rule applies to what-ifs, not to censuses).  The baseline reproduction check
# below is the control.

set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_pinkind"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }
file mkdir $out
puts "PROBE_DCP $dcp"

open_checkpoint $dcp

# ---------------------------------------------------------------- control ----
# Reproduce the pinned baseline before any claim is made (section 19 step 1).
set w [get_timing_paths -max_paths 1 -nworst 1 -delay_type max]
puts [format "PROBE_CTRL WNS %.3f" [get_property SLACK $w]]
puts "PROBE_CTRL START [get_property STARTPOINT_PIN $w]"
puts "PROBE_CTRL END   [get_property ENDPOINT_PIN $w]"
puts [format "PROBE_CTRL LEVELS %d  LOGIC %.3f  NET %.3f" \
        [get_property LOGIC_LEVELS $w] \
        [get_property DATAPATH_LOGIC_DELAY $w] \
        [get_property DATAPATH_NET_DELAY $w]]

proc family {name} {
  foreach k {IcachePlugin FetchAlignPlugin DcachePlugin LsEuPlugin IssueQueuePlugin \
             RobPlugin RenameStage DecodeStage MicroOpQueue AluEuPlugin BranchEuPlugin \
             DivEuPlugin ItlbPlugin DtlbPlugin PrfPlugin GsharePlugin FtbPlugin \
             RasPlugin BtbPlugin TableWalker Tlb} {
    if {[string first $k $name] >= 0} { return $k }
  }
  return OTHER
}

# Endpoint pin -> capture kind.  D = data input of a flop (the improvable kind);
# CE = clock enable (the optimisation-resistant kind, section 23.5); R/S = sync
# reset/set; ADDR*/WE*/EN* = block-RAM control.
proc pinkind {pin} {
  set i [string last "/" $pin]
  if {$i < 0} { return OTHER }
  set p [string range $pin [expr {$i+1}] end]
  if {$p eq "D"} { return D }
  if {$p eq "CE"} { return CE }
  if {$p eq "R" || $p eq "S" || $p eq "CLR" || $p eq "PRE"} { return R }
  if {[string match "ADDR*" $p]} { return BRAM_ADDR }
  if {[string match "*WE*" $p] || [string match "*EN*" $p]} { return BRAM_EN }
  if {[string match "DI*" $p]} { return BRAM_DI }
  return $p
}

set thr -1.000
set paths [get_timing_paths -quiet -max_paths 300000 -nworst 1 \
             -slack_lesser_than $thr -delay_type max]
puts "PROBE_POP threshold $thr endpoints [llength $paths]"

array unset kind          ;# pinkind -> count
array unset pairn         ;# "SP->EP" -> count
array unset pairk         ;# "SP->EP|KIND" -> count
array unset pairlv        ;# "SP->EP" -> summed logic levels
array unset pairnet       ;# "SP->EP" -> summed net delay
array unset pairlog       ;# "SP->EP" -> summed logic delay
array unset epkind        ;# "EPFAMILY|KIND" -> count
array unset epn           ;# EPFAMILY -> count

proc bump {arr key {by 1}} {
  upvar 1 $arr a
  if {[info exists a($key)]} { set a($key) [expr {$a($key) + $by}] } else { set a($key) $by }
}

foreach p $paths {
  set epin [get_property ENDPOINT_PIN $p]
  set spin [get_property STARTPOINT_PIN $p]
  set ef [family $epin] ; set sf [family $spin]
  set k [pinkind $epin]
  set pr "$sf->$ef"
  bump kind $k
  bump pairn $pr
  bump pairk "$pr|$k"
  bump epn $ef
  bump epkind "$ef|$k"
  bump pairlv $pr [get_property LOGIC_LEVELS $p]
  catch {
    bump pairnet $pr [get_property DATAPATH_NET_DELAY $p]
    bump pairlog $pr [get_property DATAPATH_LOGIC_DELAY $p]
  }
}

set f [open $out/pinkind.txt w]
puts $f "baseline sub($thr) endpoints: [llength $paths]"
puts $f "---- capture-pin kind over the whole deficit population ----"
foreach k [lsort [array names kind]] {
  set line [format "%-10s %6d  %5.1f%%" $k $kind($k) \
              [expr {100.0*$kind($k)/[llength $paths]}]]
  puts $f $line ; puts "PROBE_KIND $line"
}

puts $f ""
puts $f "---- by endpoint family, with D/CE split ----"
set eps {}
foreach k [array names epn] { lappend eps [list $epn($k) $k] }
foreach e [lsort -integer -decreasing -index 0 $eps] {
  set ef [lindex $e 1] ; set n [lindex $e 0]
  set d 0 ; set ce 0 ; set r 0
  catch { set d  $epkind($ef|D) }
  catch { set ce $epkind($ef|CE) }
  catch { set r  $epkind($ef|R) }
  set line [format "%-20s %6d   D %5d  CE %5d  R %5d  other %5d" \
              $ef $n $d $ce $r [expr {$n-$d-$ce-$r}]]
  puts $f $line ; puts "PROBE_EPFAM $line"
}

puts $f ""
puts $f "---- by (startpoint family -> endpoint family) arc, >=25 endpoints ----"
puts $f [format "%-42s %6s %6s %6s %6s %7s %7s %7s" \
           arc n D CE R meanLvl meanLog meanNet]
set prs {}
foreach k [array names pairn] { lappend prs [list $pairn($k) $k] }
foreach e [lsort -integer -decreasing -index 0 $prs] {
  set pr [lindex $e 1] ; set n [lindex $e 0]
  if {$n < 25} { continue }
  set d 0 ; set ce 0 ; set r 0 ; set nd 0.0 ; set lg 0.0
  catch { set d  $pairk($pr|D) }
  catch { set ce $pairk($pr|CE) }
  catch { set r  $pairk($pr|R) }
  catch { set nd $pairnet($pr) }
  catch { set lg $pairlog($pr) }
  set line [format "%-42s %6d %6d %6d %6d %7.1f %7.3f %7.3f" \
              $pr $n $d $ce $r [expr {1.0*$pairlv($pr)/$n}] \
              [expr {$lg/$n}] [expr {$nd/$n}]]
  puts $f $line ; puts "PROBE_ARC $line"
}
close $f

close_design
puts "PROBE_DONE $out"
