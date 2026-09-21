# Debug build profiles

`GenSocketTopVerilog` accepts `CPU_DEBUG_PROFILE=full|reduced`, independently
of `CPU_IPC_PROFILE` and `PERF_DETAIL_ENABLE`. The default is `full` for
compatibility. Unknown names fail elaboration.

The SoC uses full debug at 100 MHz for diagnosis, and reduced debug at 200 MHz
for performance/timing builds. Frequency, ILA and other board-level debug IP
are selected by the SoC, not by this CPU setting.

`reduced` omits the PC-range retirement breakpoint lane at elaboration:
the range configuration registers, four 32-bit comparisons, prior-PC/capture
registers, hit counter and sticky stop state are absent. Its enable and stop
inputs to retirement are constant false. Addresses `0x124` through `0x13c`
remain mapped: reads return zero and writes are ignored with normal AXI
completion. Software can verify that the range enable bit does not stick.

Exact-PC breakpoints, exception masks, manual halt/resume, A7-odd halt,
architectural access, reset control and performance counters are unchanged.
`pcRangeEnable` must be passed consistently to `DebugCtrlPlugin` and
`RobPlugin`; `M68kSocketTop` owns this wiring. The disabled ROB also ignores
range configuration at the service boundary, so it cannot accidentally
restrict retirement through a live enable input.

This is a debug implementation option, not an architectural memory-ordering,
exception or retirement-policy change. No timing exceptions are added.
