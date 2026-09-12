# Lever C gate wrapper: identical flow to synth/impl_FullCore.tcl, but with Vivado's
# parallel-worker count capped. The unconstrained default forked enough synth workers to
# segfault (OOM) under this machine's concurrent third-party Vivado load. Disclosed as a
# flow deviation in the task report.
set_param general.maxThreads 4
source synth/impl_FullCore.tcl
