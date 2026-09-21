#!/usr/bin/env python3
"""Structural checks on the generated Verilog for the AXI socket adapter.

Two properties that simulation cannot prove, and that this plan can plausibly
break:

  1. FREEZE.  `M68kFullCoreSynth`'s top-level port surface is EXACTLY what it was
     at this plan's parent commit -- no port added, removed, renamed, or resized.
     Design spec section 10: "Any change to M68kFullCoreSynth's port surface or
     behaviour ... It stays exactly as it is as the OOC-synth/FMax-gate target
     (section 9.3): no port added, none removed, none tied off." Tasks 3-12 all
     edit files that M68kFullCoreSynth instantiates, and a plugin whose port
     declaration changes shape silently re-pins the whole netlist.

  2. SOCKET CONFORMANCE (--socket, inert until the socket top exists).  The
     elaborated `M68kSocketTop` exports ONLY socket ports (D23), carries NO
     prot/cache/lock/qos/region on either master (D29), presents axi_i at 256 bit
     and axi_d at 128 (D11), and has NO AW/W/B group on axi_i at all.

Standard library only -- this repository has no pytest.  Run:
    python3 tools/socket/check_socket_netlist.py [--regen] [--socket generated/M68kSocketTop.v]

Exit 0 on success, 1 on the first failing class (with a diagnostic on stderr).
"""

import argparse
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FULLCORE = os.path.join(REPO, "generated", "M68kFullCoreSynth.v")
GOLDEN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fullcore_ports.golden")

PORT_RE = re.compile(
    r"^\s*(input|output)\s+(?:wire|reg)?\s*(?:\[\s*(\d+)\s*:\s*0\s*\])?\s*(\w+)\s*,?\s*$")

FAILURES = []


def check(label, cond, detail=""):
    if cond:
        print("PASS  " + label)
    else:
        FAILURES.append(label)
        print("FAIL  " + label + (("  -- " + detail) if detail else ""))


def parse_ports(path, module):
    """name -> (direction, width). Only `module`'s own port list is scanned; the
    scan stops at the first ');' that closes it."""
    out = {}
    started = False
    head = re.compile(r"^\s*module\s+" + re.escape(module) + r"\b")
    with open(path) as fh:
        for line in fh:
            if not started:
                if head.match(line):
                    started = True
                continue
            if re.match(r"^\s*\)\s*;", line):
                break
            m = PORT_RE.match(line)
            if m:
                direction, msb, name = m.group(1), m.group(2), m.group(3)
                out[name] = (direction, int(msb) + 1 if msb is not None else 1)
    return out


def fmt(ports):
    return "".join("%s %s %d\n" % (n, d, w) for n, (d, w) in sorted(ports.items()))


def check_fullcore(regen):
    if not os.path.exists(FULLCORE):
        check("M68kFullCoreSynth's netlist exists at %s" % FULLCORE, False,
              "run: sbt \"runMain m68k040.top.GenFullCoreSynthVerilog\"")
        return False
    ports = parse_ports(FULLCORE, "M68kFullCoreSynth")
    check("M68kFullCoreSynth's port list was parsed", len(ports) > 100,
          "only %d ports found" % len(ports))
    if regen:
        with open(GOLDEN, "w") as fh:
            fh.write("# tools/socket/fullcore_ports.golden -- FROZEN port surface of\n"
                     "# M68kFullCoreSynth, captured at the parent commit of the axi-socket\n"
                     "# adapter plan. Design spec section 10 forbids this plan from changing\n"
                     "# it: no port added, none removed, none renamed, none resized.\n"
                     "# Regenerate ONLY with an explicit, reviewed decision:\n"
                     "#   python3 tools/socket/check_socket_netlist.py --regen\n")
            fh.write(fmt(ports))
        print("REGENERATED %s with %d ports" % (GOLDEN, len(ports)))
        return True
    if not os.path.exists(GOLDEN):
        check("golden port baseline exists at %s" % GOLDEN, False,
              "capture it with --regen")
        return False
    want = {}
    with open(GOLDEN) as fh:
        for line in fh:
            if line.startswith("#") or not line.strip():
                continue
            n, d, w = line.split()
            want[n] = (d, int(w))
    missing = sorted(set(want) - set(ports))
    extra = sorted(set(ports) - set(want))
    changed = sorted(n for n in set(want) & set(ports) if want[n] != ports[n])
    check("no M68kFullCoreSynth port was removed or renamed away", not missing, repr(missing))
    check("no M68kFullCoreSynth port was added", not extra, repr(extra))
    check("no M68kFullCoreSynth port changed direction or width", not changed,
          repr([(n, want[n], ports[n]) for n in changed]))
    # The 37 probe/test anchors named in spec section 9.3 must still be there: they are
    # what keeps synthesis from pruning the retire path on the FMax gate target.
    probes = [n for n in ports if n.startswith("BackendWiringPlugin_logic_traceOut_")
              or n.startswith("BackendWiringPlugin_logic_fireOut_")
              or n in ("BackendWiringPlugin_logic_eu0Res", "BackendWiringPlugin_logic_eu1Res",
                       "FetchAlignPlugin_logic_slot1ValidOut",
                       "IcachePlugin_logic_invalidateAll",
                       "RobPlugin_logic_flush_valid",
                       "FetchAlignPlugin_logic_redirect_valid",
                       "FetchAlignPlugin_logic_redirect_payload",
                       "FetchAlignPlugin_logic_resume_valid",
                       "FetchAlignPlugin_logic_resume_payload")]
    check("the 37 probe/test anchors of spec section 9.3 are all present",
          len(probes) == 37, "found %d" % len(probes))
    return not FAILURES


def check_socket(path, detailed_perf=False):
    if not os.path.exists(path):
        print("SKIP  no socket netlist at %s (expected until Task 13)" % path)
        return True
    ports = parse_ports(path, "M68kSocketTop")
    check("M68kSocketTop's port list was parsed", len(ports) > 20,
          "only %d ports found" % len(ports))

    # D29: the sidebands the socket does not declare must NOT EXIST at the boundary.
    sidebands = sorted(n for n in ports
                       if re.search(r"(prot|cache|lock|qos|region)$", n))
    check("D29: no prot/cache/lock/qos/region port on the socket boundary",
          not sidebands, repr(sidebands))

    # cpu_socket.vh:98-113 -- axi_i is AR/R ONLY.
    i_write = sorted(n for n in ports if re.match(r"^axi_i_(aw|w|b)", n))
    check("axi_i carries no AW/W/B group", not i_write, repr(i_write))

    want_i = {
        "axi_i_arid": ("output", 4), "axi_i_araddr": ("output", 32),
        "axi_i_arlen": ("output", 8), "axi_i_arsize": ("output", 3),
        "axi_i_arburst": ("output", 2), "axi_i_arvalid": ("output", 1),
        "axi_i_arready": ("input", 1), "axi_i_rid": ("input", 4),
        "axi_i_rdata": ("input", 256),  # D11 -- native 256, SOC-1 widens the SoC side
        "axi_i_rresp": ("input", 2), "axi_i_rlast": ("input", 1),
        "axi_i_rvalid": ("input", 1), "axi_i_rready": ("output", 1),
    }
    want_d = {
        "axi_d_awid": ("output", 4), "axi_d_awaddr": ("output", 32),
        "axi_d_awlen": ("output", 8), "axi_d_awsize": ("output", 3),
        "axi_d_awburst": ("output", 2), "axi_d_awvalid": ("output", 1),
        "axi_d_awready": ("input", 1), "axi_d_wdata": ("output", 128),
        "axi_d_wstrb": ("output", 16), "axi_d_wlast": ("output", 1),
        "axi_d_wvalid": ("output", 1), "axi_d_wready": ("input", 1),
        "axi_d_bid": ("input", 4), "axi_d_bresp": ("input", 2),
        "axi_d_bvalid": ("input", 1), "axi_d_bready": ("output", 1),
        "axi_d_arid": ("output", 4), "axi_d_araddr": ("output", 32),
        "axi_d_arlen": ("output", 8), "axi_d_arsize": ("output", 3),
        "axi_d_arburst": ("output", 2), "axi_d_arvalid": ("output", 1),
        "axi_d_arready": ("input", 1), "axi_d_rid": ("input", 4),
        "axi_d_rdata": ("input", 128), "axi_d_rresp": ("input", 2),
        "axi_d_rlast": ("input", 1), "axi_d_rvalid": ("input", 1),
        "axi_d_rready": ("output", 1),
    }
    for name, want in list(want_i.items()) + list(want_d.items()):
        got = ports.get(name)
        check("socket port %s is %s[%d]" % (name, want[0], want[1]), got == want,
              "got %r" % (got,))

    # Clock/reset naming (D21): `clk` and `rst`, not SpinalHDL's default `reset`.
    check("D21: the socket top's reset port is named rst", "rst" in ports,
          "ports named like reset: %r" % sorted(n for n in ports if "rst" in n or "reset" in n))
    check("D21: there is no port named `reset` on the socket top", "reset" not in ports)

    # D23: every port is a member of a cpu_socket.vh group.
    allowed = re.compile(
        r"^(clk|rst|axi_i_\w+|axi_d_\w+|dbg_axi_\w+|cpu_ipl|ipl_ack|"
        r"cpu_cold_reset_pulse|cpu_cold_reset_hold|cpu_ram_window_lg2|cpu_mon_sense|"
        r"init_done_seen|cpu_peripheral_reset)$")
    if detailed_perf:
        check("detailed performance trace is output[95]",
              ports.get("perf_trace") == ("output", 95), repr(ports.get("perf_trace")))
    # Existing SocketTop group-7 ILA exports; keep an explicit schema rather
    # than accepting arbitrary dbg040_* additions at the socket boundary.
    debug_widths = {}
    for width, names in {
        1: "normalIrqGate flushing excIdle iplActive branchRedirect p0First "
           "preciseDrainBusyIn inhibitedLoadBusyIn interruptPending rasPredValid "
           "btbPredHitComb btbUpdValid ftbRspValid ftbRspHit ftbRspFramedOk "
           "ftbCmdValid ftqConfirm",
        4: "ftqHeadBrLen", 6: "ftqCount", 7: "rasCount",
        32: "headPc rasPredTarget btbPredTargetComb btbUpdPc btbUpdTarget "
            "ftbRspTarget ftbCmdWindowPc decodePc ftqHeadBrPc ftqHeadTarget "
            "stallDc stallExc stallGrant stallWalk macroCountLo",
    }.items():
        for name in names.split():
            debug_widths["dbg040_" + name] = ("output", width)
    debug_changed = sorted(n for n, shape in debug_widths.items() if ports.get(n) != shape)
    check("group-7 debug exports retain their explicit direction and width",
          not debug_changed, repr(debug_changed))
    strays = sorted(n for n in ports if not allowed.match(n)
                    and n not in debug_widths
                    and not (detailed_perf and n == "perf_trace"))
    check("D23: the socket top exports ONLY cpu_socket.vh ports", not strays, repr(strays))
    return not FAILURES


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--regen", action="store_true",
                    help="rewrite the golden port list from the current netlist")
    ap.add_argument("--socket", default=os.path.join(REPO, "generated", "M68kSocketTop.v"))
    ap.add_argument("--detailed-perf", action="store_true",
                    help="require the optional 95-bit socket performance trace")
    args = ap.parse_args()
    check_fullcore(args.regen)
    if not args.regen:
        check_socket(args.socket, args.detailed_perf)
    if FAILURES:
        sys.stderr.write("\n%d check(s) FAILED\n" % len(FAILURES))
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
