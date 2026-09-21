# Tcl orchestration test only; actual exception precedence needs Vivado reports.
source [file join [file dirname [info script]] .. synth debug_csr_timing_audit.tcl]
if {[llength $argv] != 1} {error "usage: tclsh tools/test_debug_csr_timing_audit.tcl fresh_parent"}
set parent [lindex $argv 0]
set calls {}
proc open_checkpoint {path} {lappend ::calls [list open $path]}
proc get_cells {args} {
    if {[string first {IS_SEQUENTIAL == 1} $args] < 0} {error "unfiltered cell query"}
    if {[string first {a*Addr} $args] >= 0} {return {ar0 aw0}}
    return {r0 r1}
}
proc report_timing_summary {args} {lappend ::calls [list summary {*}$args]}
proc report_exceptions {args} {lappend ::calls [list exceptions {*}$args]}
proc report_timing {args} {lappend ::calls [list timing {*}$args]}
proc set_multicycle_path {args} {lappend ::calls [list mcp {*}$args]}
debug_csr_timing_audit::run baseline.dcp $parent/good
set mcps [lsearch -all -inline -index 0 $calls mcp]
if {$mcps ne {{mcp 1 -setup -from {ar0 aw0}} {mcp 0 -hold -from {ar0 aw0}} {mcp 1 -setup -to {r0 r1}} {mcp 0 -hold -to {r0 r1}}}} {
    error "wrong strict overlay: $mcps"
}
if {![catch {debug_csr_timing_audit::run baseline.dcp $parent/good}]} {error "overwrote old audit"}
rename get_cells populated_cells
proc get_cells {args} {return {}}
if {![catch {debug_csr_timing_audit::run baseline.dcp $parent/empty} msg] ||
    ![string match {Missing debug*} $msg]} {error "empty query silently accepted"}
puts DEBUG_CSR_TIMING_AUDIT_UNIT_PASS
