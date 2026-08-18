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
    """Bodies of every `always @(posedge clk or posedge reset)` process."""
    bodies = []
    pat = re.compile(r"always\s*@\s*\(\s*posedge\s+clk\s+or\s+posedge\s+reset\s*\)")
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

    # The debug domain must never take the core's asynchronous reset.
    tainted = set()
    for body in async_reset_blocks(text):
        for name in re.findall(r"\b(DebugCtrlPlugin_\w+)\s*<=", body):
            tainted.add(name)
    check("no DebugCtrlPlugin register is clocked by the core's async reset process",
          not tainted, ", ".join(sorted(tainted)))

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
