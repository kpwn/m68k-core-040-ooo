# Sizing probe for pb_fetch, against the POST-M1-M4 netlist.
#
# Spec section 7.2 item 2: the box is sized from a REAL post-synthesis measurement of
# the NEW netlist, never from the design document and never from
# floorplan_frontend.xdc's baseline numbers. That is the one thing distinguishing M5
# from variant 7 (which sized a box for the netlist this design replaces, got the
# campaign's best iteration yield at +0.685 ns, and still lost on round-0 at -2.422).
#
# The capture filter is COPIED VERBATIM from floorplan_frontend.xdc, including the
# foreign-plugin exclusions and the IS_PRIMITIVE qualifier. Without IS_PRIMITIVE,
# get_cells -hier also returns the hierarchical instances themselves (e.g.
# FetchAlignPlugin_logic_ibuf), and adding one of those re-constrains every leaf under
# it regardless of that leaf's name -- which is how the exclusions get silently undone.
#
# GC-1: this probe is READ-ONLY on an already-synthesised checkpoint. It places
# nothing, routes nothing, and reads no timing.
set dcp "synth/m5_synth.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
open_checkpoint $dcp
puts "FETCHPROBE_DCP $dcp"

set filt {(NAME =~ *IcachePlugin_logic* || NAME =~ *FetchAlignPlugin_logic* || NAME =~ *FtbPlugin_logic* || NAME =~ *GsharePlugin_logic* || NAME =~ *RasPlugin_logic* || NAME =~ *BtbPlugin_logic*) && NAME !~ *DecodeStage* && NAME !~ *RobPlugin* && NAME !~ *IssueQueuePlugin* && NAME !~ *RenameStage* && NAME !~ *DcachePlugin* && NAME !~ *LsEuPlugin* && IS_PRIMITIVE}
set cells [get_cells -hier -filter $filt]

set nl 0; set nf 0; set nc 0; set nm 0; set nram 0; set nbram 0; set no 0
foreach c $cells {
  set r [get_property REF_NAME $c]
  if {[string match "RAMB*" $r]}      { incr nbram } \
  elseif {[string match "RAM*" $r]}   { incr nram } \
  elseif {[string match "LUT*" $r]}   { incr nl } \
  elseif {[string match "FD*" $r]}    { incr nf } \
  elseif {[string match "CARRY*" $r]} { incr nc } \
  elseif {[string match "MUXF*" $r]}  { incr nm } \
  else { incr no }
}
puts "FETCH_CAPTURE cells=[llength $cells] LUT=$nl FF=$nf CARRY=$nc MUXF=$nm LUTRAM=$nram BRAM=$nbram other=$no"

# Minimum slice demand, and the grid that lands in the 55-65% LUT-site working band
# that floorplan_decode_fe.xdc's own sizing note treats as correct -- never the
# 96.78%-occupancy trap floorplan_decode.xdc records.
set lut_demand [expr {$nl + $nc + $nram}]
foreach target {0.55 0.60 0.65} {
  set need_lutsites [expr {int(ceil($lut_demand / $target))}]
  set need_slices   [expr {int(ceil($need_lutsites / 8.0))}]
  puts [format "FETCH_SIZING target_lutocc=%.2f lut_demand=%d need_lutsites=%d need_slices=%d" \
        $target $lut_demand $need_lutsites $need_slices]
}
# FF-site occupancy at each candidate, so the box is not FF-starved either.
puts "FETCH_FF_DEMAND $nf"

# The "other" bucket is not decoration: SRLs and distributed-RAM primitives are
# LUT-resident too, so report its composition rather than leaving the box sized
# against an unexplained residue.
array set otherRef {}
foreach c $cells {
  set r [get_property REF_NAME $c]
  if {[string match "RAMB*" $r] || [string match "RAM*" $r] || [string match "LUT*" $r] ||
      [string match "FD*" $r] || [string match "CARRY*" $r] || [string match "MUXF*" $r]} { continue }
  if {[info exists otherRef($r)]} { incr otherRef($r) } else { set otherRef($r) 1 }
}
foreach r [lsort [array names otherRef]] { puts "FETCH_OTHER_REF $r $otherRef($r)" }
array set ramRef {}
foreach c $cells {
  set r [get_property REF_NAME $c]
  if {![string match "RAM*" $r] || [string match "RAMB*" $r]} { continue }
  if {[info exists ramRef($r)]} { incr ramRef($r) } else { set ramRef($r) 1 }
}
foreach r [lsort [array names ramRef]] { puts "FETCH_LUTRAM_REF $r $ramRef($r)" }

# Where do the IcachePlugin BRAMs actually sit? pb_fetch must INCLUDE those sites
# (spec section 7.2 item 3), or the placer is forced to stretch between a boxed logic
# region and an unboxed memory column.
foreach c [get_cells -hier -filter {NAME =~ *IcachePlugin_logic* && REF_NAME =~ RAMB*}] {
  puts "FETCH_BRAM [get_property NAME $c] [get_property LOC $c]"
}
# All BRAMs captured by the pblock filter, not just IcachePlugin's, because every one
# of them has to have a legal site inside the box or the pblock is unplaceable.
foreach c [get_cells -hier -filter "($filt) && REF_NAME =~ RAMB*"] {
  puts "FETCH_BRAM_ANY [get_property REF_NAME $c] [get_property NAME $c]"
}

# GC-13 SG-5 evidence (Task 8 review, risk R1): fillLo/fillHi carry no ram_style
# directive. Record how they were ACTUALLY inferred, by name, so Task 16's SG-5 check
# has a pre-gate datapoint instead of only commit-message prose. Same for the M1
# lineMem array, whose BRAM inference is the whole point of the Unified Fetch Array.
foreach pat {*fillLo* *fillHi* *lineMem* *ufaBeat* *tagMem*} {
  array unset infRef
  array set infRef {}
  set n 0
  foreach c [get_cells -hier -filter "NAME =~ $pat && IS_PRIMITIVE"] {
    set r [get_property REF_NAME $c]
    if {[info exists infRef($r)]} { incr infRef($r) } else { set infRef($r) 1 }
    incr n
  }
  set parts {}
  foreach r [lsort [array names infRef]] { lappend parts "$r=$infRef($r)" }
  puts "FETCH_MEMINFER $pat total=$n [join $parts { }]"
}

# --- DEVICE GEOMETRY, measured from the part itself -------------------------------
# The pblock must name RAMB18_*/RAMB36_* site ranges, and those live in a DIFFERENT
# coordinate system from SLICE_*. Rather than assume a column pitch, read each site
# family's tile ROW/COLUMN so a SLICE range can be translated into the RAMB ranges
# that physically overlap it.
proc famgeo {pat tag} {
  array set colOf {}
  array set ymin {}
  array set ymax {}
  array set rowOfY {}
  foreach s [get_sites -quiet $pat] {
    set nm [get_property NAME $s]
    if {![regexp {_X(\d+)Y(\d+)$} $nm -> x y]} { continue }
    if {![info exists colOf($x)]} {
      set col -1
      catch { set col [get_property COLUMN [get_tiles -quiet -of_objects $s]] }
      set colOf($x) $col
      set ymin($x) $y
      set ymax($x) $y
    }
    if {$y < $ymin($x)} { set ymin($x) $y }
    if {$y > $ymax($x)} { set ymax($x) $y }
    if {![info exists rowOfY($y)]} {
      set row -1
      catch { set row [get_property ROW [get_tiles -quiet -of_objects $s]] }
      set rowOfY($y) $row
    }
  }
  foreach x [lsort -integer [array names colOf]] {
    puts "DEV_${tag}_COL x=$x tilecol=$colOf($x) ymin=$ymin($x) ymax=$ymax($x)"
  }
  foreach y [lsort -integer [array names rowOfY]] {
    puts "DEV_${tag}_ROW y=$y tilerow=$rowOfY($y)"
  }
}
famgeo SLICE_X*   SLICE
famgeo RAMB18_X*  RAMB18
famgeo RAMB36_X*  RAMB36
puts "FETCHPROBE_DONE"
