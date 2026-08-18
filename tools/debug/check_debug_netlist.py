#!/usr/bin/env python3
"""Structural checks on the generated full-core Verilog for the debug slave.

Spec section 12, "Gates for every RTL stage", item 4: "Generated-Verilog lint and
structural checks for multiple drivers, latches, and PRF write collisions." For
Stage 1 the two properties that cannot be proved by simulation alone are:

  1. every socket port exists at the top level, under its exact cpu_socket.vh
     name, with the right direction and width; and
  2. NO debug-domain register is clocked by a process that takes the core's
     asynchronous `reset` -- which is the whole point of the debug reset domain,
     and the thing a careless refactor would silently undo.

Standard library only. Run:
    python3 tools/debug/check_debug_netlist.py [path/to/M68kFullCoreSynth.v]
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import regmap  # noqa: E402

DEFAULT_NETLIST = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "generated", "M68kFullCoreSynth.v")

PORT_RE = re.compile(
    r"^\s*(input|output)\s+(?:wire|reg)\s*(?:\[\s*(\d+)\s*:\s*0\s*\])?\s+(\w+)\s*,?\s*$")

FAILURES = []


def check(label, cond, detail=""):
    if cond:
        print("PASS  " + label)
    else:
        FAILURES.append(label)
        print("FAIL  " + label + (("  -- " + detail) if detail else ""))


def parse_ports(text):
    """name -> (direction, width). Only the top module's port list is scanned; the
    scan stops at the first ');' that closes it."""
    out = {}
    started = False
    for line in text.splitlines():
        if not started:
            if re.match(r"^\s*module\s+M68kFullCoreSynth\b", line):
                started = True
            continue
        if re.match(r"^\s*\)\s*;", line):
            break
        m = PORT_RE.match(line)
        if m:
            direction, msb, name = m.group(1), m.group(2), m.group(3)
            out[name] = (direction, int(msb) + 1 if msb is not None else 1)
    return out


def async_reset_blocks(text):
    """Bodies of every asynchronous-reset process, i.e. every
    `always @(posedge clk or posedge/negedge <anything>)`.

    The reset term is deliberately matched as ANY identifier rather than the literal
    `reset`. Hardcoding `reset` made this check VACUOUS once anything renamed the
    core's top-level reset wire (a real regression that shipped: naming
    `coreCd.isResetActive` inside a plugin Area renames the whole core's `reset`
    port, after which the literal `posedge clk or posedge reset` occurred zero times
    and every scan below trivially found nothing to complain about). The point of the
    check is "no debug register sits in an async-reset process", not "no debug
    register sits in a process whose reset happens to be spelled `reset`."""
    bodies = []
    pat = re.compile(
        r"always\s*@\s*\(\s*posedge\s+clk\s+or\s+(?:posedge|negedge)\s+(\w+)\s*\)")
    token = re.compile(r"\b(begin|end)\b")
    for m in pat.finditer(text):
        i = text.find("begin", m.end())
        if i < 0:
            continue
        depth = 0
        pos = i
        while pos < len(text):
            t = token.search(text, pos)
            if t is None:
                break
            depth += 1 if t.group(1) == "begin" else -1
            pos = t.end()
            if depth == 0:
                break
        bodies.append(text[i:pos])
    return bodies


def initial_blocks(text):
    """Bodies of every `initial begin ... end` block. SpinalHDL 1.14.1's Verilog
    backend renders EVERY resetKind=BOOT (reset-less) register's declaration
    initializer this way -- never as an inline `reg ... = value;` -- confirmed by
    grepping the whole generated netlist: zero inline-initializer declarations
    exist anywhere in it, for any register, only `initial begin` blocks. Both
    forms are the same synthesizable idiom (a Xilinx FF INIT attribute); this
    just matches the one the toolchain actually emits."""
    bodies = []
    pat = re.compile(r"\binitial\s+begin\b")
    token = re.compile(r"\b(begin|end)\b")
    for m in pat.finditer(text):
        pos = m.end()
        depth = 1
        while pos < len(text):
            t = token.search(text, pos)
            if t is None:
                break
            depth += 1 if t.group(1) == "begin" else -1
            pos = t.end()
            if depth == 0:
                break
        bodies.append(text[m.end():pos])
    return bodies


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_NETLIST
    if not os.path.exists(path):
        sys.stderr.write("no netlist at %s -- run:\n"
                         "  sbt \"runMain m68k040.top.GenFullCoreSynthVerilog\"\n" % path)
        return 1
    with open(path) as fh:
        text = fh.read()

    rm = regmap.load()
    ports = parse_ports(text)
    check("the top module's port list was parsed", len(ports) > 100,
          "only %d ports found" % len(ports))

    for p in rm.ports:
        got = ports.get(p.name)
        check("port %s exists" % p.name, got is not None,
              "not in the top-level port list")
        if got is None:
            continue
        want_dir = "input" if p.direction == "IN" else "output"
        check("port %s direction is %s" % (p.name, want_dir), got[0] == want_dir,
              "got %s" % got[0])
        check("port %s width is %d" % (p.name, p.width), got[1] == p.width,
              "got %d" % got[1])

    # The core's top-level reset must still be exported under its exact socket name.
    # SpinalHDL renames the whole core's `reset` port if anything inside a plugin Area
    # binds `ClockDomain.isResetActive` to a named val, which is both an interface break
    # (spec 15.1: socket names export verbatim, no rename shim) and the thing that made
    # the async-reset scan below vacuous. Checked explicitly so that regression class
    # fails HERE, by name, rather than silently disarming the scan.
    rst = ports.get("reset")
    check("the top-level reset port is still named `reset`", rst is not None,
          "no `reset` in the top-level port list -- something renamed the core's reset "
          "wire (likely a named `ClockDomain.isResetActive` inside a plugin Area); "
          "reset-like ports present: %r"
          % sorted(n for n in ports if "rst" in n.lower() or "reset" in n.lower()))
    if rst is not None:
        check("the top-level reset port is a 1-bit input",
              rst == ("input", 1), "got %r" % (rst,))

    # The debug domain must never take the core's asynchronous reset.
    arst_bodies = async_reset_blocks(text)
    # NON-VACUITY GUARD: if the scan finds no async-reset processes at all, the taint
    # check below passes for free and proves nothing. A ~370K-line core built on an
    # async-reset ClockDomain has dozens of them; zero means the matcher, not the
    # netlist, is broken.
    check("async-reset processes were found to scan", len(arst_bodies) > 0,
          "zero `always @(posedge clk or posedge/negedge ...)` blocks matched -- the "
          "taint check below would be vacuous")
    tainted = set()
    for body in arst_bodies:
        for name in re.findall(r"\b(DebugCtrlPlugin_\w+)\s*<=", body):
            tainted.add(name)
    check("no DebugCtrlPlugin register is clocked by the core's async reset process",
          not tainted, "%d of %d async-reset process(es) assign: %s"
                       % (len(tainted), len(arst_bodies), ", ".join(sorted(tainted))))

    # The power-on counter must be reset-LESS, i.e. carry a declaration initializer --
    # either the inline `reg ... = value;` form, or (what this toolchain actually
    # emits, see initial_blocks()'s doc comment) a plain assignment inside an
    # `initial begin ... end` block.
    por_decls = re.findall(
        r"^\s*reg\s*(?:\[[^\]]*\])?\s*(DebugCtrlPlugin_logic_por_\w+)\s*=\s*[^;]+;",
        text, re.M)
    for body in initial_blocks(text):
        por_decls += re.findall(r"\b(DebugCtrlPlugin_logic_por_\w+)\s*=\s*[^;]+;", body)
    check("the debug POR registers carry declaration initializers (resetKind = BOOT)",
          len(por_decls) >= 2, "found %r" % (por_decls,))

    # And they must be assigned from a plain, reset-free clocked process.
    plain = re.findall(r"always\s*@\s*\(\s*posedge\s+clk\s*\)", text)
    check("at least one reset-free clocked process exists for the debug POR domain",
          len(plain) >= 1)

    if FAILURES:
        sys.stderr.write("\n%d check(s) FAILED\n" % len(FAILURES))
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
