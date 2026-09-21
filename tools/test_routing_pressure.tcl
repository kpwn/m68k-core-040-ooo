# Mocked Tcl/unit test only: Vivado API validation still needs a routed DCP.
source [file join [file dirname [info script]] .. synth routing_pressure.tcl]
if {[llength $argv] != 1 || ![file isdirectory [lindex $argv 0]]} {
    error "usage: tclsh tools/test_routing_pressure.tcl <fresh temporary directory>"
}
set scratch [lindex $argv 0]
proc check {expression label} {
    if {![uplevel 1 [list expr $expression]]} {error $label}
}
foreach {name expected} {
    u_cpu/socket_core/IssueQueuePlugin_logic_reg cpu/IssueQueuePlugin
    u_cpu/socket_core/_zz_IssueQueuePlugin_logic_reg cpu/IssueQueuePlugin
    u_cpu/socket_core/LsEuPlugin_logic_sq/DcachePlugin_logic_x cpu/DcachePlugin
    u_cpu/socket_core/RegFilePluginNzvc_logic_ram/cores_0/DebugCtrlPlugin_logic_csr_x cpu/DebugCtrlPlugin
    u_cpu/socket_core/some_unknown_register cpu/UNATTRIBUTED
    u_l2c/g_active.u_ctrl/u_mshr/inst_data_reg u_l2c/g_active.u_ctrl/u_mshr
} {
    check {[routing_pressure::owner $name] eq $expected} "bad owner: $name"
}
check {[routing_pressure::family {ram[3]/data[12]}] eq {ram[*]/data[*]}} "bus grouping"
check {[routing_pressure::family {ram/data_i_12}] eq {ram/data_i_12}} "generated LUTs must remain distinct"
check {[routing_pressure::route_class BUFGCE] eq "global"} "global classification"
check {[routing_pressure::route_class LUT6] eq "fabric"} "fabric classification"

set names [dict create n0 {data[0]} n1 {data[1]} n2 reset n3 tie n4 external n5 multi n6 tie_alias]
set refs [dict create c0 FDRE c1 LUT6 c2 BUFGCE c3 VCC]
set cells [dict create c0 u_cpu/socket_core/DebugCtrlPlugin_logic_a \
    c1 u_cpu/socket_core/LsEuPlugin_logic_sq/DcachePlugin_logic_b \
    c2 reset_buf c3 tie_cell]
set pip_map [dict create n0 {p0 p1 p2} n1 {p3 p4} n2 {p5 p6} n3 {p9} n4 {p7} n5 {p8} n6 {p9}]
set pins [dict create n0 {q0 i0 i1} n1 {q1 i2} n2 {q2 i3 i4 i5} n3 {q3 i6} n4 {i7} n5 {q0 q1 i8} n6 {q3 i9}]
proc open_checkpoint {args} {}
proc get_nets {args} {
    # Canonical query deliberately repeats a physical net to test deduplication.
    if {[lsearch -exact $args -segments] >= 0} {return {n0 n0 n1 n2 n3 n4 n5 n6}}
    return {n0 n1 n2 n3 n4 n5 n6}
}
proc get_property {property objects} {
    if {$property eq "REF_NAME"} {return [dict get $::refs $objects]}
    if {[dict exists $::names $objects]} {return [dict get $::names $objects]}
    return [dict get $::cells $objects]
}
proc get_pins {args} {return [dict get $::pins [lindex $args end]]}
proc filter {objects expression} {
    set pattern [expr {$expression eq "DIRECTION == OUT" ? "q*" : "i*"}]
    return [lsearch -all -inline -glob $objects $pattern]
}
proc get_cells {args} {return c[string range [lindex $args end] 1 end]}
proc get_pips {args} {
    set result {}
    foreach net [lindex $args end] {lappend result {*}[dict get $::pip_map $net]}
    return [lsort -unique $result]
}
proc get_nodes {args} {return [get_pips {*}$args]}
proc get_tiles {args} {return {TILE_A TILE_B}}
routing_pressure::run dummy.dcp $scratch
set fd [open $scratch/summary.txt r]; set summary [read $fd]; close $fd
check {[string match {*canonical_nets=7*} $summary]} "hierarchy alias double counted"
check {[string match {*pip_assignments=10*} $summary]} "PIP reconciliation"
check {[string match {*shared_constant_pips=1*} $summary]} "shared constant tree double counted"
set fd [open $scratch/owners.tsv r]; set owners [read $fd]; close $fd
check {[string match "*cpu/DebugCtrlPlugin\tfabric\t1\t2\t3\t3*" $owners]} "driver attribution"
check {[string match "*UNATTRIBUTED\tmultiple\t1\t1\t1\t1*" $owners]} "multiple sources misattributed"
check {[string match "*soc/TOP\tglobal\t1\t3\t2\t2*" $owners]} "global network mixed into fabric"
check {[string first "\tconstant\t" $owners] < 0} "shared constant tree attributed to an owner"
set fd [open $scratch/nets.tsv r]; set net_rows [read $fd]; close $fd
check {[string first "\tNA\tNA\tNA\tshared_constant" $net_rows] >= 0} "constant footprint misreported as zero"
set fd [open $scratch/families.tsv r]; set families [read $fd]; close $fd
check {[string first "data\[*\]\tfabric\t2\t3\t5\t5" $families] >= 0} "wide bus aggregation"
check {[catch {routing_pressure::run dummy.dcp $scratch} message]} "old scan was overwritten"
check {[string match {Choose a fresh output directory;*} $message]} "wrong preservation diagnostic"

# A broken canonical query must fail instead of publishing believable rankings.
rename get_nets good_get_nets
proc get_nets {args} {
    if {[lsearch -exact $args -segments] >= 0} {return {n0 n1}}
    return [good_get_nets {*}$args]
}
check {[catch {routing_pressure::run dummy.dcp $scratch/bad} message]} "missing route segments were accepted"
check {[string match {PIP reconciliation failed:*} $message]} "unexpected failure: $message"
rename get_nets {}
rename good_get_nets get_nets
dict for {net unused} $pip_map {dict set pip_map $net {}}
check {[catch {routing_pressure::run dummy.dcp $scratch/unrouted} message]} "unrouted design accepted"
check {[string match {No routed PIPs found;*} $message]} "wrong unrouted diagnostic"
puts ROUTING_PRESSURE_UNIT_PASS
