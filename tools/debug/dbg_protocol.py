#!/usr/bin/env python3
"""Frozen host-side protocol rules for the CPU-side dbg_axi debug/control slave.

These are the rules a host tool (jtag_repl.tcl, the Python GDB bridge) and the
RTL must BOTH obey, expressed once so both sides can be tested against the same
statements. Nothing here models a legacy defect: where the deployed controller
and this core's design spec disagree, the SPEC wins, and the divergence is named
in a comment.

Standard library only. Import as `dbg_protocol`.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import debug_regmap as _m
import regmap as _regmap


class ProtocolError(Exception):
    """A sequence or encoding the contract forbids."""


# AXI response codes. The slave answers OKAY for everything, including unmapped
# offsets -- spec 3.1: "return OKAY/zero for unmapped reads and OKAY/drop for
# unmapped writes".
RESP_OKAY = 0
RESP_SLVERR = 2

# OFF_CONTROL write encoding, spec 3.3.
CTRL_HALT = 1 << 0            # manual halt request LEVEL
CTRL_STEP = 1 << 1            # single-step PULSE
CTRL_SOFT_RST = 1 << 2        # deprecated reset-pulse alias
CTRL_INIT_DONE_OVR = 1 << 3   # init-done override level, SoC-owned
CTRL_COLD_HOLD = 1 << 4       # cold-reset hold level, survives CPU reset
CTRL_COLD_PULSE = 1 << 5      # cold-reset pulse
CTRL_RESERVED6 = 1 << 6       # reserved, reads zero
CTRL_STEP_ARM = 1 << 7        # legacy step-arm observation

# OFF_STATUS read encoding, spec 3.3.
STAT_HALTED = 1 << 0
STAT_EXC_PENDING = 1 << 1
STAT_INIT_DONE = 1 << 2
STAT_RUNNING = 1 << 3
STAT_AUTO_HALT = 1 << 4

# OFF_ARCH_STATUS, spec 7.3: "retains bits BUSY=0, DONE=1, REJECTED=2".
ARCH_BUSY = 1 << 0
ARCH_DONE = 1 << 1
ARCH_REJECTED = 1 << 2

# OFF_DCACHE_OP / OFF_ICACHE_OP status, spec 8.2: "read bit 0 BUSY, bit 1 DONE,
# bits 3:2 selected cache(s)". The append-only location of REJECTED/ERROR is
# spec section 14 item 4 and is STILL OPEN, so nothing here invents a bit for
# them; the rule pinned instead is the one spec 8.2 states outright -- a rejected
# command "must not silently leave BUSY=0/DONE=0".
CACHE_BUSY = 1 << 0
CACHE_DONE = 1 << 1
CACHE_SEL_MASK = 0b1100


def _indexed_offsets(prefix, base, count):
    return [("%s%d" % (prefix, i), base + 4 * i) for i in range(count)]


# The live window is the coherent read source. Writes go through the separate
# shadow window and become architectural only after OFF_ARCH_APPLY completes.
ARCH_LIVE_REGISTERS = dict(
    _indexed_offsets("D", _m.OFF_LIVE_DREG0, 8)
    + _indexed_offsets("A", _m.OFF_LIVE_AREG0, 8)
    + [
        ("USP", _m.OFF_LIVE_USP), ("SSP", _m.OFF_LIVE_SSP),
        ("ISP", _m.OFF_LIVE_ISP), ("SR", _m.OFF_LIVE_SR),
        ("VBR", _m.OFF_LIVE_VBR), ("CACR", _m.OFF_LIVE_CACR),
        ("TC", _m.OFF_LIVE_MMU_TC), ("ITT0", _m.OFF_LIVE_MMU_ITT0),
        ("ITT1", _m.OFF_LIVE_MMU_ITT1), ("DTT0", _m.OFF_LIVE_MMU_DTT0),
        ("DTT1", _m.OFF_LIVE_MMU_DTT1), ("URP", _m.OFF_LIVE_MMU_URP),
        ("SRP", _m.OFF_LIVE_MMU_SRP), ("PC", _m.OFF_LIVE_PC),
        ("SFC", _m.OFF_LIVE_SFC), ("DFC", _m.OFF_LIVE_DFC),
        ("MMUSR", _m.OFF_LIVE_MMUSR),
    ])

ARCH_SHADOW_REGISTERS = dict(
    _indexed_offsets("D", _m.OFF_ARCH_D0, 8)
    + _indexed_offsets("A", _m.OFF_ARCH_A0, 8)
    + [
        ("USP", _m.OFF_ARCH_USP), ("SSP", _m.OFF_ARCH_SSP),
        ("ISP", _m.OFF_ARCH_ISP), ("SR", _m.OFF_ARCH_SR),
        ("VBR", _m.OFF_ARCH_VBR), ("CACR", _m.OFF_ARCH_CACR),
        ("TC", _m.OFF_ARCH_TC), ("ITT0", _m.OFF_ARCH_ITT0),
        ("ITT1", _m.OFF_ARCH_ITT1), ("DTT0", _m.OFF_ARCH_DTT0),
        ("DTT1", _m.OFF_ARCH_DTT1), ("URP", _m.OFF_ARCH_URP),
        ("SRP", _m.OFF_ARCH_SRP), ("PC", _m.OFF_ARCH_PC),
        ("SFC", _m.OFF_ARCH_SFC), ("DFC", _m.OFF_ARCH_DFC),
    ])

_RM = _regmap.load()


def _stage_of(offset):
    """The stage that makes `offset` real, or None when it is not in the map."""
    for reg in _RM.regs:
        if reg.offset == offset:
            return reg.stage
    for blk in _RM.blks:
        for _name, off in blk.elements():
            if off == offset:
                return blk.stage
    for rng in _RM.ranges:
        if rng.base <= offset < rng.base + rng.size:
            return rng.stage
    return None


def expect_read(stage, offset):
    """Expected read value for `offset` in a build of the given `stage`.

    Returns 0 for every unmapped, out-of-window, reserved-forever, or
    not-yet-implemented offset (spec 3.1/3.2). Returns the constant for the two
    offsets whose value is fixed by the contract itself. Returns None when the
    offset IS implemented at this stage but its value depends on live state, so
    the caller must assert something more specific."""
    if offset < 0 or offset >= (1 << _m.DBG_AW) or offset % 4 != 0:
        return 0
    item_stage = _stage_of(offset)
    if item_stage is None or item_stage > stage:
        return 0
    if offset == _m.OFF_VERSION:
        return _m.VERSION_VALUE
    if offset == _m.OFF_FEATURES:
        return _m.features_for_stage(stage)
    return None


def merge_strobes(old, new, strb):
    """Apply AXI byte strobes: per spec 3.1, "honor WSTRB per byte for ordinary
    RW fields". Bytes whose strobe is 0 keep their old value."""
    if strb < 0 or strb > 0xF:
        raise ProtocolError("WSTRB 0x%X is outside 0x0..0xF" % strb)
    out = 0
    for i in range(4):
        byte = (new if (strb >> i) & 1 else old) >> (8 * i)
        out |= (byte & 0xFF) << (8 * i)
    return out


def assert_feature_honesty(stage, features_word):
    """Raise unless every bit set in `features_word` is a defined feature whose
    implementing stage is <= `stage`.

    Spec 3.4: "No feature bit may advertise a tied-off counter, stale shadow,
    placeholder probe, or operation that can be silently dropped." """
    by_bit = {bit: (name, feat_stage) for name, (bit, feat_stage) in _m.FEATURES.items()}
    for bit in range(32):
        if not ((features_word >> bit) & 1):
            continue
        if bit not in by_bit:
            raise ProtocolError("OFF_FEATURES bit %d is not a defined feature" % bit)
        name, feat_stage = by_bit[bit]
        if feat_stage > stage:
            raise ProtocolError(
                "OFF_FEATURES advertises '%s' (bit %d, needs stage %d) from a stage-%d build"
                % (name, bit, feat_stage, stage))


def classify_control_writes(writes):
    """Turn a list of OFF_CONTROL write words into the semantic commands they mean.

    The deployed Tcl `step` writes HALT, then HALT|STEP, then zero. Spec 3.3:
    "The controller must treat this as one step command. The final zero may
    release an ordinary halt but must not cancel the step already accepted." A
    STEP is therefore emitted for the middle write and the trailing zero is a
    plain RESUME -- it never emits a STEP_CANCEL."""
    out = []
    for word in writes:
        if word < 0 or word > 0xFFFFFFFF:
            raise ProtocolError("CONTROL word 0x%X is not 32 bits" % word)
        if word & CTRL_RESERVED6:
            raise ProtocolError("CONTROL bit 6 is reserved and must be written 0")
        emitted = False
        if word & (CTRL_COLD_PULSE | CTRL_SOFT_RST):
            out.append("COLD_RESET_PULSE")
            emitted = True
        if word & CTRL_COLD_HOLD:
            out.append("COLD_RESET_HOLD_SET")
            emitted = True
        if word & CTRL_INIT_DONE_OVR:
            out.append("INIT_DONE_OVERRIDE_SET")
            emitted = True
        if word & CTRL_STEP:
            out.append("STEP")
        elif word & CTRL_HALT:
            out.append("HALT")
        elif not (word & (CTRL_COLD_PULSE | CTRL_SOFT_RST)):
            # An accepted CONTROL write with bit 0 clear IS an explicit resume
            # request (spec 3.3 bit 0, spec 6.4) -- including the trailing zero
            # of the step sequence, which resumes but does not cancel the step.
            out.append("RESUME")
        elif not emitted:
            out.append("RESUME")
    return out


def arch_apply_poll(samples):
    """Reduce a sequence of OFF_ARCH_STATUS reads to DONE or REJECTED.

    Spec 7.3: "DONE means all writes and required invalidations completed.
    REJECTED is never encoded as DONE." """
    if not samples:
        raise ProtocolError("no OFF_ARCH_STATUS samples")
    for word in samples:
        if (word & ARCH_DONE) and (word & ARCH_REJECTED):
            raise ProtocolError("OFF_ARCH_STATUS 0x%X encodes DONE and REJECTED together" % word)
        if word & ARCH_REJECTED:
            return "REJECTED"
        if word & ARCH_DONE:
            return "DONE"
        if not (word & ARCH_BUSY):
            raise ProtocolError(
                "OFF_ARCH_STATUS went idle (0x%X) without DONE or REJECTED" % word)
    raise ProtocolError("OFF_ARCH_STATUS never left BUSY")


def cache_op_poll(samples):
    """Reduce a sequence of OFF_DCACHE_OP/OFF_ICACHE_OP reads to DONE.

    Spec 8.2: a rejected command "must not silently leave BUSY=0/DONE=0", and
    "DONE is set only after the real walk and all writeback responses finish"."""
    if not samples:
        raise ProtocolError("no cache-op status samples")
    for word in samples:
        if (word & CACHE_BUSY) and (word & CACHE_DONE):
            raise ProtocolError("cache status 0x%X asserts BUSY and DONE together" % word)
        if word & CACHE_DONE:
            return "DONE"
        if not (word & CACHE_BUSY):
            raise ProtocolError(
                "cache status went idle (0x%X) with neither BUSY nor DONE -- a rejected "
                "command must be reported, not left silent" % word)
    raise ProtocolError("cache operation never left BUSY")


def arch_dump(read32):
    """Return the complete coherent architectural register view.

    ``read32(offset)`` is supplied by the JTAG/AXI transport. The core must be
    effectively halted; accepting a dump while merely halt-requested would mix
    values from different architectural instants.
    """
    if not (read32(_m.OFF_STATUS) & STAT_HALTED):
        raise ProtocolError("architectural dump requires effective HALTED")
    return {name: read32(off) & 0xFFFFFFFF
            for name, off in ARCH_LIVE_REGISTERS.items()}


def arch_set(read32, write32, updates, max_polls=10000):
    """Stage named register writes, atomically apply them, and stay halted."""
    if not (read32(_m.OFF_STATUS) & STAT_HALTED):
        raise ProtocolError("architectural set requires effective HALTED")
    normalized = {name.upper(): value for name, value in updates.items()}
    unknown = sorted(set(normalized) - set(ARCH_SHADOW_REGISTERS))
    if unknown:
        raise ProtocolError("register is not writable: %s" % ", ".join(unknown))
    for name, value in normalized.items():
        if value < 0 or value > 0xFFFFFFFF:
            raise ProtocolError("%s value is not 32 bits" % name)
        write32(ARCH_SHADOW_REGISTERS[name], value)
    write32(_m.OFF_ARCH_APPLY, 1)
    samples = []
    for _ in range(max_polls):
        sample = read32(_m.OFF_ARCH_STATUS) & 0xFFFFFFFF
        samples.append(sample)
        if sample & (ARCH_DONE | ARCH_REJECTED):
            result = arch_apply_poll(samples)
            if result != "DONE":
                raise ProtocolError("architectural apply was rejected")
            if not (read32(_m.OFF_STATUS) & STAT_HALTED):
                raise ProtocolError("architectural apply unexpectedly resumed the core")
            return
    raise ProtocolError("architectural apply timed out")


def _ring_indices(head, depth):
    """Physical slots ordered oldest to newest for a next-write head."""
    if depth <= 0:
        return []
    return [((head + i) % depth) for i in range(depth)]


def read_pc_history(read32):
    """Read the advertised last-macro-PC ring, oldest to newest."""
    depth = (read32(_m.OFF_CAP_TRACE) >> 16) & 0xFFFF
    head = read32(_m.OFF_PC_TRACE_HEAD) & 0xFFFF
    return [read32(_m.OFF_PC_TRACE_BODY + 4 * i) & 0xFFFFFFFF
            for i in _ring_indices(head, depth)]


def read_branch_history(read32):
    """Read committed branch records, oldest to newest."""
    depth = read32(_m.OFF_CAP_TRACE2) & 0xFFFF
    head = read32(_m.OFF_BRANCH_RING_HEAD) & 0xFFFF
    out = []
    for i in _ring_indices(head, depth):
        base = _m.OFF_BRANCH_RING_BODY + 16 * i
        words = [read32(base + 4 * n) & 0xFFFFFFFF for n in range(4)]
        meta = words[2]
        out.append({"pc": words[0], "next_pc": words[1],
                    "taken": bool(meta & 1),
                    "mispredicted": bool(meta & 2),
                    "branch_type": (meta >> 2) & 3})
    return out


def read_exception_history(read32):
    """Read completed exception-entry records, oldest to newest."""
    depth = read32(_m.OFF_CAP_TRACE) & 0xFFFF
    head = read32(_m.OFF_EXC_RING_HEAD) & 0xFFFF
    out = []
    for i in _ring_indices(head, depth):
        base = _m.OFF_EXC_RING_BODY + 16 * i
        words = [read32(base + 4 * n) & 0xFFFFFFFF for n in range(4)]
        out.append({"vector": words[0] & 0xFF, "exception_pc": words[1],
                    "fault_address": words[2], "handler_pc": words[3]})
    return out


def control_word(halt=False, step=False, soft_rst=False, init_done_override=False,
                 cold_hold=False, cold_pulse=False, step_arm=False):
    """Build an OFF_CONTROL write word from named intents (spec 3.3)."""
    word = 0
    if halt:
        word |= CTRL_HALT
    if step:
        word |= CTRL_STEP
    if soft_rst:
        word |= CTRL_SOFT_RST
    if init_done_override:
        word |= CTRL_INIT_DONE_OVR
    if cold_hold:
        word |= CTRL_COLD_HOLD
    if cold_pulse:
        word |= CTRL_COLD_PULSE
    if step_arm:
        word |= CTRL_STEP_ARM
    return word
