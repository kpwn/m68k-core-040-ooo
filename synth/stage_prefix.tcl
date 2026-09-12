read_verilog generated/M68kFullCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kFullCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context
set fh [open synth/prefix_staged.rpt w]
array set lut {}
foreach c [get_cells -hier -filter {REF_NAME =~ LUT*}] {
    set n [get_property NAME $c]
    if {[regexp {^([A-Za-z0-9]+Plugin|[A-Za-z0-9]+Stage)} $n m pfx]} { set key $pfx } else { set key "top:unscoped" }
    if {[info exists lut($key)]} { incr lut($key) } else { set lut($key) 1 }
}
foreach k [lsort [array names lut]] { puts $fh [format "%-38s %d" $k $lut($k)] }
close $fh
puts "STAGEPFX_DONE"
