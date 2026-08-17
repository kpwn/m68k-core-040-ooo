#!/usr/bin/env python3
"""Cross-conformance: tools/debug/debug_regmap.def vs the DEPLOYED sibling.

The compatibility contract this core must honour is not prose -- it is the
register map that macqd700-soc's debug_ctrl.v actually decodes and that
tools/jtag_repl.tcl (modelled offline by tb/tests/host/fake_jtag_repl.py)
actually addresses. This script proves three things:

  1. RESERVATION COMPLETENESS -- every offset the deployed RTL or the deployed
     host model uses is reserved somewhere in our definition. Spec section 3.2:
     "It must reserve them, return zero for absent functions, and never
     repurpose them."
  2. NAMING CONSISTENCY -- where both sides name the same register, they agree
     on its offset.
  3. FEATURE-BIT IDENTITY -- bits 0-18 carry the deployed numbering exactly.
     Spec section 3.4: "Bits 0-18 retain their deployed meanings and are never
     renumbered."

Plus the socket surface check that resolves spec section 14 decision 3.

Standard library only. Run:  python3 tools/debug/test_sibling_conformance.py
Exits 0 on success, 1 on failure, 77 when the sibling checkout is absent.
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import regmap  # noqa: E402

SOC = os.environ.get("MACQD700_SOC", "/home/qwertyoruiop/macqd700-soc")
RTL = os.path.join(SOC, "cpu/rtl/core/debug/debug_ctrl.v")
FAKE = os.path.join(SOC, "tb/tests/host/fake_jtag_repl.py")
SOCKET = os.path.join(SOC, "rtl/soc/cpu_socket.vh")

# Deployed name -> our name, for the handful of registers where the two sides
# spell the same offset differently. Every entry is justified in-line.
NAME_ALIASES = {
    "OFF_BREAK_PC": "OFF_BREAK_PC0",              # RTL predates the 4-slot rename
    "OFF_HALT_EXC_MASK_0": "OFF_HALT_EXC_MASK0",  # RTL uses an underscore before the lane index
    "OFF_HALT_EXC_MASK_7": "OFF_HALT_EXC_MASK7",
    # The deployed map uses OFF_LIVE_A7 for the live ACTIVE A7 at 0x02108, so our live
    # A-register ARRAY cannot be called OFF_LIVE_A -- it would expand to a second,
    # different OFF_LIVE_A7 at 0x0214C. Both live arrays are therefore *REG-suffixed.
    "OFF_LIVE_D_BASE": "OFF_LIVE_DREG0",          # RTL names the array base, we name element 0
    "OFF_LIVE_A_TOP": "OFF_LIVE_AREG7",           # RTL names the array top, we name element 7
    "OFF_LIVE_D0": "OFF_LIVE_DREG0",              # host model's name for the same element
    "OFF_LIVE_A0": "OFF_LIVE_AREG0",
    "OFF_EXC_RING_BASE": "OFF_EXC_RING_BODY",     # RTL "base", spec section 3.2 calls it the ring body
}

FAILURES = []


def check(label, cond, detail=""):
    if cond:
        print("PASS  " + label)
    else:
        FAILURES.append(label)
        print("FAIL  " + label + (("  -- " + detail) if detail else ""))


def parse_rtl_offsets(path):
    """localparam [19:0] OFF_FOO = 20'h000A4;"""
    pat = re.compile(r"localparam\s*\[19:0\]\s*(OFF_[A-Z0-9_]+)\s*=\s*20'h([0-9A-Fa-f_]+)\s*;")
    out = {}
    with open(path) as fh:
        for line in fh:
            m = pat.search(line)
            if m:
                out[m.group(1)] = int(m.group(2).replace("_", ""), 16)
    return out


def parse_rtl_feats(path):
    """localparam integer FEAT_FOO = 7;  -> {"foo": 7}"""
    pat = re.compile(r"localparam\s+integer\s+FEAT_([A-Z0-9_]+)\s*=\s*(\d+)\s*;")
    out = {}
    with open(path) as fh:
        for line in fh:
            m = pat.search(line)
            if m:
                out[m.group(1).lower()] = int(m.group(2))
    return out


def parse_py_offsets(path):
    """OFF_FOO = 0x0A4  (module-level constants in fake_jtag_repl.py)"""
    pat = re.compile(r"^(OFF_[A-Z0-9_]+)\s*=\s*(0x[0-9A-Fa-f]+|\d+)\s*(#.*)?$")
    out = {}
    with open(path) as fh:
        for line in fh:
            m = pat.match(line.strip())
            if m:
                tok = m.group(2)
                out[m.group(1)] = int(tok, 16) if tok.lower().startswith("0x") else int(tok)
    return out


def main():
    missing = [p for p in (RTL, FAKE, SOCKET) if not os.path.exists(p)]
    if missing:
        sys.stderr.write(
            "SKIP: sibling checkout not found (set MACQD700_SOC). Missing: %s\n"
            % ", ".join(missing))
        return 77

    rm = regmap.load()
    ours = rm.offsets()
    our_values = set(ours.values())

    rtl_off = parse_rtl_offsets(RTL)
    py_off = parse_py_offsets(FAKE)
    check("parsed the deployed RTL offset table", len(rtl_off) >= 100,
          "only %d localparams" % len(rtl_off))
    check("parsed the deployed host-model offset table", len(py_off) >= 60,
          "only %d constants" % len(py_off))

    # 1. Reservation completeness.
    unreserved_rtl = sorted(
        "%s=0x%05X" % (n, o) for n, o in rtl_off.items() if o not in our_values)
    check("every DEPLOYED RTL offset is reserved in debug_regmap.def",
          not unreserved_rtl, ", ".join(unreserved_rtl))
    unreserved_py = sorted(
        "%s=0x%05X" % (n, o) for n, o in py_off.items() if o not in our_values)
    check("every DEPLOYED host-model offset is reserved in debug_regmap.def",
          not unreserved_py, ", ".join(unreserved_py))

    # 2. Naming consistency, for names present on both sides.
    mismatches = []
    for src_name, table in (("rtl", rtl_off), ("host", py_off)):
        for name, off in sorted(table.items()):
            ours_name = NAME_ALIASES.get(name, name)
            if ours_name in ours and ours[ours_name] != off:
                mismatches.append("%s %s: deployed 0x%05X vs ours 0x%05X"
                                  % (src_name, name, off, ours[ours_name]))
    check("shared register names agree on their offset", not mismatches,
          "; ".join(mismatches))

    # 3. Feature-bit identity for the deployed bits 0-18.
    rtl_feats = parse_rtl_feats(RTL)
    ours_feats = {f.name: f.bit for f in rm.feats}
    feat_bad = sorted("%s: deployed %d vs ours %r" % (n, b, ours_feats.get(n))
                      for n, b in rtl_feats.items() if ours_feats.get(n) != b)
    check("deployed feature-bit numbering is reproduced exactly",
          not feat_bad, "; ".join(feat_bad))
    check("all 19 deployed feature bits are present",
          len(rtl_feats) == 19, "found %d" % len(rtl_feats))

    # 4. Socket surface -- spec section 14 decision 3.
    with open(SOCKET) as fh:
        socket_text = fh.read()
    absent = sorted(p.name for p in rm.ports if p.name not in socket_text)
    check("every PORT name appears verbatim in cpu_socket.vh", not absent,
          repr(absent))
    check("CPU_SOCKET_DBG_AW is 20 in cpu_socket.vh",
          "`define CPU_SOCKET_DBG_AW 20" in socket_text)
    check("CPU_SOCKET_DBG_DW is 32 in cpu_socket.vh",
          "`define CPU_SOCKET_DBG_DW 32" in socket_text)
    check("our DBG_AW/DBG_DW constants match the socket",
          rm.consts["DBG_AW"] == 20 and rm.consts["DBG_DW"] == 32)

    if FAILURES:
        sys.stderr.write("\n%d check(s) FAILED\n" % len(FAILURES))
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
