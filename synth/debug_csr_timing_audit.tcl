# Read-only physical-design audit of DCPs built with the old debug exceptions.
# Source, then call debug_csr_timing_audit::run checkpoint fresh_output_dir.
# The overlay changes timing analysis in memory only; no DCP/bitstream is written.
namespace eval debug_csr_timing_audit {
    proc run {checkpoint output} {
        if {[file exists $output]} {error "Preserving existing audit: $output"}
        file mkdir $output
        open_checkpoint $checkpoint
        set sources [get_cells -quiet -hierarchical -filter {IS_SEQUENTIAL == 1 && NAME =~ *DebugCtrlPlugin_logic_csr_a*Addr_reg*}]
        set sinks [get_cells -quiet -hierarchical -filter {IS_SEQUENTIAL == 1 && NAME =~ *DebugCtrlPlugin_logic_csr_rData_reg*}]
        if {![llength $sources] || ![llength $sinks]} {
            error "Missing debug address or response registers; cannot certify this audit"
        }
        report_timing_summary -max_paths 10 -report_unconstrained -file $output/timing_original.rpt
        # Latest same-scope multicycle constraints replace the old 4/3 pair.
        # No -reset_path: preserve independent CDC false-path/max-delay rules.
        # Sequential-cell filtering excludes the combinational *_reg*_i_* cells
        # that the original wildcard also matched and Vivado warned about.
        set_multicycle_path 1 -setup -from $sources
        set_multicycle_path 0 -hold -from $sources
        set_multicycle_path 1 -setup -to $sinks
        set_multicycle_path 0 -hold -to $sinks
        report_exceptions -file $output/exceptions_strict.rpt
        report_timing_summary -max_paths 20 -report_unconstrained -file $output/timing_strict.rpt
        report_timing -from $sources -delay_type max -max_paths 20 -file $output/address_setup.rpt
        report_timing -to $sinks -delay_type max -max_paths 20 -file $output/response_setup.rpt
        report_timing -from $sources -delay_type min -max_paths 20 -file $output/address_hold.rpt
        report_timing -to $sinks -delay_type min -max_paths 20 -file $output/response_hold.rpt
        set fd [open $output/receipt.txt w]
        puts $fd "checkpoint=$checkpoint"
        puts $fd "address_registers=[llength $sources]"
        puts $fd "response_registers=[llength $sinks]"
        puts $fd "Review exception precedence and path requirements before accepting strict timing."
        puts $fd "No synthesis, placement, routing, DCP writing or board access."
        close $fd
        puts "DEBUG_CSR_AUDIT_REPORTS_WRITTEN: $output (not a closure claim)"
    }
}
