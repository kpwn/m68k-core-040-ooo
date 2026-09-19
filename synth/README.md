# Synthesis tooling

Run commands from the repository root. Generated RTL and Vivado outputs are
local build products, not published source.

Full-core elaboration:

```sh
sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
```

The output is generated/M68kFullCoreSynth.v. The integration-facing socket top
is generated with `runMain m68k040.top.GenSocketTopVerilog`; consult SocketTop.scala
for its configuration environment variables. `make verilog` is the minimal
framework demonstration, not either of these full CPU tops.

impl_FullCore.tcl is the standalone implementation flow; clk.xdc holds its
clock constraints. Do not confuse its results with timing of the complete
Quadra SoC. Board release timing/provenance belongs to
https://github.com/kpwn/macqd700-soc .

The ooc_*, probe_*, floorplan_* and census_* scripts are engineering tools.
Some refer to historical checkpoints or hierarchy names; inspect their input
paths before use. A retained what-if summary is historical evidence, not
current timing signoff. Full implementations must be coordinated with other
heavy jobs; do not start them as a side effect of repository checks.

Raw timing ladders, endpoint lists and population dumps are ignored. Keep
concise *summary.txt / *.summary records when useful, with revision provenance.
See ../docs/repository_cleanup.md for recovering removed historical dumps.
