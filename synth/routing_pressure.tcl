# Read-only routed resource census; source this file, then call:
#   routing_pressure::run /path/route.dcp /path/report-directory
# Run under the shared Vivado mutex. Includes low-fanout nets, unlike a top-N
# fanout report. PIP/node counts are resource proxies, NOT wire length, local
# capacity utilization, or causal attribution of another net's timing failure.
namespace eval routing_pressure {
    proc owner {name} {
        # Synthesis can move DebugCtrl/Dcache cells beneath RAM/IQ/LS hierarchy.
        # Prefer the last surviving plugin prefix; retain the full driver in TSV.
        if {[string match {u_cpu/socket_core/*} $name]} {
            set matches [regexp -all -inline {(?:^|/|_)([A-Za-z0-9]*(?:Plugin|Stage)[A-Za-z0-9]*)_} $name]
            if {[llength $matches]} {return "cpu/[lindex $matches end]"}
            return cpu/UNATTRIBUTED
        }
        set parts [split $name /]
        if {[llength $parts] == 1} {return soc/TOP}
        if {[string match {u_l2c/*} $name]} {
            return [join [lrange $parts 0 [expr {min(2, [llength $parts]-2)}]] /]
        }
        return "soc/[lindex $parts 0]"
    }

    proc route_class {ref} {
        if {[regexp {^(BUFG|BUFH|BUFR|BUFCE)} $ref]} {return global}
        if {$ref in {VCC GND}} {return constant}
        if {$ref eq "EXTERNAL"} {return external}
        if {$ref eq "MULTIPLE"} {return multiple}
        return fabric
    }

    proc family {name} {
        # A heuristic bus grouping, not a recovered RTL declaration. Do not strip
        # generated LUT indices: unrelated logic must not become a fictitious bus.
        regsub -all {\[[0-9]+\]} $name {[*]} name
        return $name
    }

    proc accumulate {dict_name key loads pips nodes} {
        upvar 1 $dict_name totals
        if {![dict exists $totals $key]} {dict set totals $key {0 0 0 0}}
        lassign [dict get $totals $key] n old_loads old_pips old_nodes
        dict set totals $key [list [expr {$n+1}] [expr {$old_loads+$loads}] \
            [expr {$old_pips+$pips}] [expr {$old_nodes+$nodes}]]
    }

    proc write_totals {path key_label totals} {
        set fd [open $path w]
        puts $fd "$key_label\tclass\tnets\tloads\tpips\tnodes"
        set rows {}
        dict for {key values} $totals {
            lappend rows [concat [list [lindex $values 2]] $key $values]
        }
        foreach row [lsort -integer -decreasing -index 0 $rows] {
            puts $fd [join [lrange $row 1 end] \t]
        }
        close $fd
    }

    proc run {checkpoint output {max_seconds 300}} {
        if {[file exists $output/nets.tsv]} {
            error "Choose a fresh output directory; preserving previous scan: $output"
        }
        file mkdir $output
        open_checkpoint $checkpoint
        set started [clock seconds]
        # Canonicalize hierarchy segments so one physical net is not charged to
        # every hierarchy port it crosses. The name set also catches duplicates.
        set nets [get_nets -hierarchical -segments -top_net_of_hierarchical_group]
        set seen [dict create]
        set owners [dict create]
        set drivers [dict create]
        set families [dict create]
        set constant_nets {}
        set constant_loads 0
        set total_pips 0
        set fd [open $output/nets.tsv w]
        puts $fd "net\tdriver\tdriver_ref\towner\tclass\tloads\tpips\tnodes\ttiles\taccounting"
        foreach net $nets {
            if {$max_seconds > 0 && [clock seconds]-$started > $max_seconds} {
                close $fd
                error "Routing scan exceeded ${max_seconds}s after [dict size $seen] nets; partial TSV only, no completed ranking"
            }
            set name [get_property NAME $net]
            if {[dict exists $seen $name]} {continue}
            dict set seen $name 1
            set pins [get_pins -quiet -leaf -of_objects $net]
            set sources {}
            set loads 0
            if {[llength $pins]} {
                set sources [filter $pins {DIRECTION == OUT}]
                set loads [llength [filter $pins {DIRECTION == IN}]]
            }
            if {[llength $sources] == 1} {
                set cell [get_cells -of_objects $sources]
                set driver [get_property NAME $cell]
                set ref [get_property REF_NAME $cell]
                set group [owner $driver]
            } else {
                set driver ""
                set ref [expr {[llength $sources] == 0 ? "EXTERNAL" : "MULTIPLE"}]
                set group UNATTRIBUTED
            }
            set kind [route_class $ref]
            # Vivado returns the SAME physical constant tree for many logically
            # distinct GND/VCC nets (observed on the real routed SoC). Canonical
            # hierarchy names do not deduplicate those trees. Account their
            # routing once, as a shared design resource, never against a cell.
            if {$kind eq "constant"} {
                lappend constant_nets $net
                incr constant_loads $loads
                puts $fd [join [list $name $driver $ref $group $kind $loads NA NA NA shared_constant] \t]
                continue
            }
            set pips [llength [get_pips -quiet -of_objects $net]]
            set nodes [get_nodes -quiet -of_objects $net]
            set node_count [llength $nodes]
            set tiles 0
            if {$node_count} {set tiles [llength [get_tiles -quiet -of_objects $nodes]]}
            incr total_pips $pips
            puts $fd [join [list $name $driver $ref $group $kind $loads $pips $node_count $tiles per_net] \t]
            accumulate owners [list $group $kind] $loads $pips $node_count
            # Do not collapse all undriven/multiple-source nets into a fake cell.
            set driver_key [expr {$driver eq "" ? "NET:$name" : $driver}]
            accumulate drivers [list $driver_key $kind] $loads $pips $node_count
            accumulate families [list [family $name] $kind] $loads $pips $node_count
            if {[dict size $seen] % 10000 == 0} {
                flush $fd
                puts "ROUTING_PRESSURE_PROGRESS: [dict size $seen] nets, $total_pips PIP assignments"
            }
        }
        close $fd
        set constant_pips 0
        if {[llength $constant_nets]} {
            set constant_pips [llength [get_pips -quiet -of_objects $constant_nets]]
        }
        incr total_pips $constant_pips
        if {$total_pips == 0} {error "No routed PIPs found; do not interpret an unrouted checkpoint as zero pressure"}
        # Cross-check against all hierarchy segments: catches alias double counts
        # and a query that silently omits routing below hierarchical boundaries.
        set unique_pips [llength [get_pips -quiet -of_objects [get_nets -hierarchical]]]
        if {$total_pips != $unique_pips} {
            error "PIP reconciliation failed: per-net $total_pips versus unique design $unique_pips; inspect aliases before ranking"
        }
        write_totals $output/owners.tsv owner $owners
        write_totals $output/drivers.tsv driver $drivers
        write_totals $output/families.tsv family $families
        set fd [open $output/summary.txt w]
        puts $fd "checkpoint=$checkpoint"
        puts $fd "canonical_nets=[dict size $seen]"
        puts $fd "pip_assignments=$total_pips"
        puts $fd "shared_constant_nets=[llength $constant_nets]"
        puts $fd "shared_constant_loads=$constant_loads"
        puts $fd "shared_constant_pips=$constant_pips"
        puts $fd "Constant trees are counted once globally, excluded from driver/owner/family rankings; per-net NA is not zero."
        puts $fd "Counts are routed-resource proxies, not wire length or congestion causation."
        puts $fd "Owner/family names are heuristic. Global/constant networks are separate."
        puts $fd "Tile counts are unique per net only; never sum them as distinct occupied tiles."
        close $fd
        puts "ROUTING_PRESSURE_COMPLETE: [dict size $seen] nets; $output"
    }
}
