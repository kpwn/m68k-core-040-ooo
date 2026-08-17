#!/usr/bin/env python3
"""Generate the Scala / Python / Tcl constant files from debug_regmap.def.

    python3 tools/debug/gen_debug_regmap.py            # write the files
    python3 tools/debug/gen_debug_regmap.py --check    # verify they are current

--check is the drift gate: it regenerates every output in memory and diffs it
against what is on disk, exiting 1 (with a unified diff) if any file is stale.
The register map has exactly one source of truth; a hand-edited generated file
is a contract violation, not a convenience.

Standard library only.
"""

import argparse
import difflib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

import regmap  # noqa: E402

BANNER_LINES = (
    "GENERATED FILE -- DO NOT EDIT BY HAND.",
    "Source:     tools/debug/debug_regmap.def",
    "Regenerate: python3 tools/debug/gen_debug_regmap.py",
    "Verify:     python3 tools/debug/gen_debug_regmap.py --check",
)

SCALA_OUT = os.path.join(REPO, "src/main/scala/m68k040/debug/DebugRegMap.scala")
PY_OUT = os.path.join(HERE, "debug_regmap.py")
TCL_OUT = os.path.join(HERE, "debug_regmap.tcl")


def sorted_offsets(rm):
    """[(name, offset)] ordered by offset then name -- a stable emission order."""
    return sorted(rm.offsets().items(), key=lambda kv: (kv[1], kv[0]))


def const_lit(value):
    """Small constants read as decimal (a width of 20 is not '0x14'); anything that is
    really a bit pattern reads as hex."""
    return "%d" % value if value < 256 else "0x%X" % value


def gen_scala(rm):
    out = []
    for line in BANNER_LINES:
        out.append("// " + line)
    out.append("")
    out.append("package m68k040.debug")
    out.append("")
    out.append("/** Frozen offsets, capability bitmap, constants and socket ports of the")
    out.append("  * CPU-side `dbg_axi` debug/control slave. See the design spec")
    out.append("  * `docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md`")
    out.append("  * sections 3.2/3.4 and 15. */")
    out.append("object DebugRegMap {")
    out.append("  // -- constants --------------------------------------------------------")
    for name in sorted(rm.consts):
        value = rm.consts[name]
        if value >= (1 << 31):
            out.append('  val %s: BigInt = BigInt("%08X", 16)' % (name, value))
        else:
            out.append("  val %s: Int = %s" % (name, const_lit(value)))
    out.append("")
    out.append("  // -- offsets ----------------------------------------------------------")
    for name, off in sorted_offsets(rm):
        out.append("  val %s: Int = 0x%05X" % (name, off))
    out.append("")
    out.append("  /** Every reserved offset: (name, offset), ordered by offset. */")
    out.append("  val allOffsets: Seq[(String, Int)] = Seq(")
    for name, off in sorted_offsets(rm):
        out.append('    ("%s", 0x%05X),' % (name, off))
    out.append("  )")
    out.append("")
    out.append("  /** Capability bitmap: (name, bit, stage-that-makes-it-real). */")
    out.append("  val features: Seq[(String, Int, Int)] = Seq(")
    for f in sorted(rm.feats, key=lambda f: f.bit):
        out.append('    ("%s", %d, %d),' % (f.name, f.bit, f.stage))
    out.append("  )")
    out.append("")
    out.append("  /** OR of (1 << bit) for every feature this stage genuinely implements.")
    out.append("    * This IS the spec section 3.4 honesty rule in executable form: a build")
    out.append("    * cannot advertise a bit whose behaviour it has not built. */")
    out.append("  def featuresForStage(stage: Int): BigInt =")
    out.append("    features.filter(_._3 <= stage)")
    out.append("            .foldLeft(BigInt(0))((acc, f) => acc | (BigInt(1) << f._2))")
    out.append("")
    out.append("  /** Socket ports, verbatim from macqd700-soc/rtl/soc/cpu_socket.vh:")
    out.append("    * (name, direction as seen from the CPU, width). */")
    out.append("  val ports: Seq[(String, String, Int)] = Seq(")
    for p in rm.ports:
        out.append('    ("%s", "%s", %d),' % (p.name, p.direction, p.width))
    out.append("  )")
    out.append("}")
    out.append("")
    return "\n".join(out)


def gen_python(rm):
    out = []
    out.append('"""' + BANNER_LINES[0])
    for line in BANNER_LINES[1:]:
        out.append(line)
    out.append('"""')
    out.append("")
    out.append("# -- constants ------------------------------------------------------------")
    for name in sorted(rm.consts):
        out.append("%s = %s" % (name, const_lit(rm.consts[name])))
    out.append("")
    out.append("CONSTS = {")
    for name in sorted(rm.consts):
        out.append('    "%s": %s,' % (name, const_lit(rm.consts[name])))
    out.append("}")
    out.append("")
    out.append("# -- offsets --------------------------------------------------------------")
    for name, off in sorted_offsets(rm):
        out.append("%s = 0x%05X" % (name, off))
    out.append("")
    out.append("ALL_OFFSETS = {")
    for name, off in sorted_offsets(rm):
        out.append('    "%s": 0x%05X,' % (name, off))
    out.append("}")
    out.append("")
    out.append("# -- capability bitmap: name -> (bit, stage-that-makes-it-real) ------------")
    out.append("FEATURES = {")
    for f in sorted(rm.feats, key=lambda f: f.bit):
        out.append('    "%s": (%d, %d),' % (f.name, f.bit, f.stage))
    out.append("}")
    out.append("")
    out.append("# -- socket ports: name -> (direction from the CPU, width) ----------------")
    out.append("PORTS = {")
    for p in rm.ports:
        out.append('    "%s": ("%s", %d),' % (p.name, p.direction, p.width))
    out.append("}")
    out.append("")
    out.append("")
    out.append("def features_for_stage(stage):")
    out.append('    """OR of (1 << bit) for every feature this stage genuinely implements."""')
    out.append("    value = 0")
    out.append("    for _name, (bit, feat_stage) in FEATURES.items():")
    out.append("        if feat_stage <= stage:")
    out.append("            value |= (1 << bit)")
    out.append("    return value")
    out.append("")
    return "\n".join(out)


def gen_tcl(rm):
    out = []
    for line in BANNER_LINES:
        out.append("# " + line)
    out.append("")
    out.append("namespace eval ::dbg {")
    out.append("    variable OFF")
    out.append("    variable CONST")
    out.append("    variable FEAT_BIT")
    out.append("    variable FEAT_STAGE")
    out.append("    variable PORT_DIR")
    out.append("    variable PORT_WIDTH")
    out.append("}")
    out.append("")
    for name in sorted(rm.consts):
        out.append("set ::dbg::CONST(%s) %s" % (name, const_lit(rm.consts[name])))
    out.append("")
    for name, off in sorted_offsets(rm):
        out.append("set ::dbg::OFF(%s) 0x%05X" % (name, off))
    out.append("")
    for f in sorted(rm.feats, key=lambda f: f.bit):
        out.append("set ::dbg::FEAT_BIT(%s) %d" % (f.name, f.bit))
        out.append("set ::dbg::FEAT_STAGE(%s) %d" % (f.name, f.stage))
    out.append("")
    for p in rm.ports:
        out.append("set ::dbg::PORT_DIR(%s) %s" % (p.name, p.direction))
        out.append("set ::dbg::PORT_WIDTH(%s) %d" % (p.name, p.width))
    out.append("")
    out.append("# OR of (1 << bit) for every feature the given stage genuinely implements.")
    out.append("proc ::dbg::features_for_stage {stage} {")
    out.append("    variable FEAT_BIT")
    out.append("    variable FEAT_STAGE")
    out.append("    set value 0")
    out.append("    foreach name [array names FEAT_BIT] {")
    out.append("        if {$FEAT_STAGE($name) <= $stage} {")
    out.append("            set value [expr {$value | (1 << $FEAT_BIT($name))}]")
    out.append("        }")
    out.append("    }")
    out.append("    return $value")
    out.append("}")
    out.append("")
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true",
                    help="verify the committed files are current; do not write")
    args = ap.parse_args()

    rm = regmap.load()
    wanted = ((SCALA_OUT, gen_scala(rm)),
              (PY_OUT, gen_python(rm)),
              (TCL_OUT, gen_tcl(rm)))

    stale = 0
    for path, text in wanted:
        if args.check:
            have = ""
            if os.path.exists(path):
                with open(path) as fh:
                    have = fh.read()
            if have != text:
                stale += 1
                sys.stderr.write("STALE: %s\n" % os.path.relpath(path, REPO))
                for line in difflib.unified_diff(
                        have.splitlines(True), text.splitlines(True),
                        fromfile="on-disk", tofile="generated", n=2):
                    sys.stderr.write(line if line.endswith("\n") else line + "\n")
            else:
                print("CURRENT: %s" % os.path.relpath(path, REPO))
        else:
            directory = os.path.dirname(path)
            if not os.path.isdir(directory):
                os.makedirs(directory)
            with open(path, "w") as fh:
                fh.write(text)
            print("WROTE: %s" % os.path.relpath(path, REPO))

    if args.check and stale:
        sys.stderr.write("\n%d generated file(s) are stale -- run "
                         "python3 tools/debug/gen_debug_regmap.py\n" % stale)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
