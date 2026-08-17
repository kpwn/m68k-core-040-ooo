#!/usr/bin/env python3
"""Parser for tools/debug/debug_regmap.def -- the single source of truth for the
CPU-side dbg_axi register map, capability bitmap, constants, and socket ports.

Standard library only. Import as `regmap` or run directly to dump a summary.
"""

import os


DEF_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "debug_regmap.def")

ACCESS_SET = ("RO", "RW", "W1P", "RAZWI", "MIXED")


def _int(tok):
    """Parse a decimal or 0x-prefixed hexadecimal token."""
    tok = tok.strip()
    if tok == "-":
        return None
    return int(tok, 16) if tok.lower().startswith("0x") else int(tok, 10)


class Reg(object):
    __slots__ = ("name", "offset", "access", "stage", "reset", "desc")

    def __init__(self, name, offset, access, stage, reset, desc):
        self.name, self.offset, self.access = name, offset, access
        self.stage, self.reset, self.desc = stage, reset, desc

    def __repr__(self):
        return "Reg(%s, 0x%05X)" % (self.name, self.offset)


class Blk(object):
    __slots__ = ("name", "base", "count", "stride", "access", "stage", "desc")

    def __init__(self, name, base, count, stride, access, stage, desc):
        self.name, self.base, self.count, self.stride = name, base, count, stride
        self.access, self.stage, self.desc = access, stage, desc

    def elements(self):
        """[(elementName, offset)] -- e.g. OFF_ARCH_D0 .. OFF_ARCH_D7."""
        return [("%s%d" % (self.name, i), self.base + i * self.stride)
                for i in range(self.count)]

    def __repr__(self):
        return "Blk(%s, 0x%05X x%d)" % (self.name, self.base, self.count)


class Range(object):
    __slots__ = ("name", "base", "size", "access", "stage", "desc")

    def __init__(self, name, base, size, access, stage, desc):
        self.name, self.base, self.size = name, base, size
        self.access, self.stage, self.desc = access, stage, desc

    def __repr__(self):
        return "Range(%s, 0x%05X+0x%X)" % (self.name, self.base, self.size)


class Feat(object):
    __slots__ = ("name", "bit", "stage", "desc")

    def __init__(self, name, bit, stage, desc):
        self.name, self.bit, self.stage, self.desc = name, bit, stage, desc

    def __repr__(self):
        return "Feat(%s, bit %d, stage %d)" % (self.name, self.bit, self.stage)


class Port(object):
    __slots__ = ("name", "direction", "width", "desc")

    def __init__(self, name, direction, width, desc):
        self.name, self.direction, self.width, self.desc = name, direction, width, desc

    def __repr__(self):
        return "Port(%s, %s, %d)" % (self.name, self.direction, self.width)


class RegMap(object):
    def __init__(self):
        self.regs = []
        self.blks = []
        self.ranges = []
        self.feats = []
        self.consts = {}
        self.ports = []

    def offsets(self):
        """name -> offset for every REG, every expanded BLK element, and every
        RANGE base. The RANGE body itself is reserved but has one named base."""
        out = {}
        for r in self.regs:
            out[r.name] = r.offset
        for b in self.blks:
            for name, off in b.elements():
                out[name] = off
        for rg in self.ranges:
            out[rg.name] = rg.base
        return out

    def features_for_stage(self, stage):
        """OR of (1 << bit) for every feature whose STAGE is <= `stage`.

        This is the machine-checked implementation of the spec's feature-honesty
        rule (section 3.4): a build declares its stage and CANNOT advertise a bit
        whose behaviour that stage has not implemented."""
        value = 0
        for f in self.feats:
            if f.stage <= stage:
                value |= (1 << f.bit)
        return value

    def reg(self, name):
        for r in self.regs:
            if r.name == name:
                return r
        raise KeyError(name)


def load(path=None):
    rm = RegMap()
    with open(path or DEF_PATH, "r") as fh:
        for lineno, raw in enumerate(fh, 1):
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            tok = line.split()
            kind = tok[0]
            try:
                if kind == "REG":
                    rm.regs.append(Reg(tok[1], _int(tok[2]), tok[3], _int(tok[4]),
                                       _int(tok[5]), " ".join(tok[6:])))
                elif kind == "BLK":
                    rm.blks.append(Blk(tok[1], _int(tok[2]), _int(tok[3]), _int(tok[4]),
                                       tok[5], _int(tok[6]), " ".join(tok[7:])))
                elif kind == "RANGE":
                    rm.ranges.append(Range(tok[1], _int(tok[2]), _int(tok[3]), tok[4],
                                           _int(tok[5]), " ".join(tok[6:])))
                elif kind == "FEAT":
                    rm.feats.append(Feat(tok[1], _int(tok[2]), _int(tok[3]),
                                         " ".join(tok[4:])))
                elif kind == "CONST":
                    rm.consts[tok[1]] = _int(tok[2])
                elif kind == "PORT":
                    rm.ports.append(Port(tok[1], tok[2], _int(tok[3]), " ".join(tok[4:])))
                else:
                    raise ValueError("unknown record kind %r" % kind)
            except (IndexError, ValueError) as exc:
                raise ValueError("%s:%d: %s (in %r)" % (path or DEF_PATH, lineno, exc, line))
    rm.feats.sort(key=lambda f: f.bit)
    return rm


if __name__ == "__main__":
    m = load()
    print("%d REG, %d BLK, %d RANGE, %d FEAT, %d CONST, %d PORT"
          % (len(m.regs), len(m.blks), len(m.ranges), len(m.feats),
             len(m.consts), len(m.ports)))
    print("features_for_stage(1) = 0x%08X" % m.features_for_stage(1))
