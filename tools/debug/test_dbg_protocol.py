#!/usr/bin/env python3
"""Host-protocol pinning for the CPU-side dbg_axi slave.

ADAPTED FROM the sibling's offline REPL model,
macqd700-soc/tb/tests/host/fake_jtag_repl.py, which reproduces
rtl/core/debug/debug_ctrl.v "offset-by-offset with its real bit layouts and its
real, sometimes deliberately awkward, quirks". That file is a 1722-line model of
the LEGACY controller including defects this core deliberately fixes (ARCH_APPLY
auto-resuming, BREAK_PC_CTRL's clear-bit-14-vs-read-bit-15 mismatch,
HALT_AFTER storing a delta rather than an absolute target). Copying it wholesale
would import those defects as requirements, so what is imported here is the part
that IS the frozen contract:

  * unmapped/absent offsets read zero and drop writes with an OKAY response;
  * WSTRB is honoured per byte for ordinary RW fields;
  * a feature bit is 1 only when every operation it implies is real;
  * the deployed Tcl step sequence HALT, HALT|STEP, 0 is ONE step command and
    the trailing zero must not cancel it;
  * cache-operation polling never sees a rejected command as BUSY=0/DONE=0;
  * arch-apply polling never sees REJECTED encoded as DONE.

Standard library only. Run: python3 tools/debug/test_dbg_protocol.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dbg_protocol as p       # noqa: E402
import debug_regmap as m       # noqa: E402

FAILURES = []


def check(label, cond, detail=""):
    if cond:
        print("PASS  " + label)
    else:
        FAILURES.append(label)
        print("FAIL  " + label + (("  -- " + detail) if detail else ""))


def raises(label, exc, fn, *args):
    try:
        fn(*args)
    except exc:
        print("PASS  " + label)
        return
    except Exception as other:  # noqa: BLE001
        FAILURES.append(label)
        print("FAIL  " + label + "  -- raised %r, wanted %s" % (other, exc.__name__))
        return
    FAILURES.append(label)
    print("FAIL  " + label + "  -- did not raise %s" % exc.__name__)


def main():
    # ---- unmapped / absent offsets read zero -----------------------------------
    check("an offset outside the frozen map reads 0",
          p.expect_read(1, 0x00FFC) == 0)
    check("a reserved-forever offset reads 0 at every stage",
          p.expect_read(7, m.OFF_WEDGE0) == 0)
    check("a Stage-3 offset reads 0 in a Stage-1 build",
          p.expect_read(1, m.OFF_ARCH_D0) == 0)
    check("a Stage-1 offset has no constant expectation (it is stateful)",
          p.expect_read(1, m.OFF_STATUS) is None)
    check("CAP_TRACE reads 0 before the Stage-3 history tranche",
          p.expect_read(1, m.OFF_CAP_TRACE) == 0)
    check("an out-of-window address is rejected outright",
          p.expect_read(1, 1 << 20) == 0)

    # ---- byte strobes ----------------------------------------------------------
    check("WSTRB=0xF replaces the whole word",
          p.merge_strobes(0xAABBCCDD, 0x11223344, 0xF) == 0x11223344)
    check("WSTRB=0x0 changes nothing",
          p.merge_strobes(0xAABBCCDD, 0x11223344, 0x0) == 0xAABBCCDD)
    check("WSTRB=0x1 writes only byte 0",
          p.merge_strobes(0xAABBCCDD, 0x11223344, 0x1) == 0xAABBCC44)
    check("WSTRB=0x8 writes only byte 3",
          p.merge_strobes(0xAABBCCDD, 0x11223344, 0x8) == 0x11BBCCDD)
    check("WSTRB=0x6 writes the two middle bytes",
          p.merge_strobes(0xAABBCCDD, 0x11223344, 0x6) == 0xAA2233DD)
    raises("an out-of-range strobe is a protocol error", p.ProtocolError,
           p.merge_strobes, 0, 0, 0x10)

    # ---- feature-bit honesty ---------------------------------------------------
    p.assert_feature_honesty(1, m.features_for_stage(1))
    print("PASS  a Stage-1 build may advertise exactly features_for_stage(1)")
    check("features_for_stage(1) is 0x0004000F",
          m.features_for_stage(1) == 0x0004000F,
          "got 0x%08X" % m.features_for_stage(1))
    raises("advertising the watchpoint bit from a Stage-1 build is dishonest",
           p.ProtocolError, p.assert_feature_honesty, 1,
           m.features_for_stage(1) | (1 << m.FEATURES["watchpoints"][0]))
    raises("advertising the dcache_probe bit from a Stage-1 build is dishonest",
           p.ProtocolError, p.assert_feature_honesty, 1,
           m.features_for_stage(1) | (1 << m.FEATURES["dcache_probe"][0]))
    raises("an undefined feature bit is dishonest",
           p.ProtocolError, p.assert_feature_honesty, 7, 1 << 31)

    # ---- the deployed Tcl step sequence ---------------------------------------
    # jtag_repl.tcl writes HALT, then HALT|STEP, then zero. Spec 3.3: "The
    # controller must treat this as one step command. The final zero may release
    # an ordinary halt but must not cancel the step already accepted."
    seq = [p.control_word(halt=True),
           p.control_word(halt=True, step=True),
           0]
    check("the deployed 3-write step sequence is HALT, STEP, RESUME",
          p.classify_control_writes(seq) == ["HALT", "STEP", "RESUME"],
          repr(p.classify_control_writes(seq)))
    check("the sequence contains exactly one STEP",
          p.classify_control_writes(seq).count("STEP") == 1)
    check("a bare halt is HALT only",
          p.classify_control_writes([p.control_word(halt=True)]) == ["HALT"])
    check("a bare zero is RESUME only",
          p.classify_control_writes([0]) == ["RESUME"])
    check("STEP without HALT is still one STEP",
          p.classify_control_writes([p.control_word(step=True)]) == ["STEP"])
    check("cold-reset pulse is classified separately from halt/step",
          p.classify_control_writes([p.control_word(cold_pulse=True)]) == ["COLD_RESET_PULSE"])
    check("the deprecated bit-2 alias classifies as a cold-reset pulse",
          p.classify_control_writes([p.control_word(soft_rst=True)]) == ["COLD_RESET_PULSE"])
    check("cold-reset HOLD is a level, not a pulse",
          p.classify_control_writes([p.control_word(cold_hold=True)])
          == ["COLD_RESET_HOLD_SET", "RESUME"])

    # ---- arch-apply polling ----------------------------------------------------
    check("BUSY then DONE polls to DONE",
          p.arch_apply_poll([p.ARCH_BUSY, p.ARCH_BUSY, p.ARCH_DONE]) == "DONE")
    check("BUSY then REJECTED polls to REJECTED",
          p.arch_apply_poll([p.ARCH_BUSY, p.ARCH_REJECTED]) == "REJECTED")
    check("an immediate REJECTED (no BUSY) is legal",
          p.arch_apply_poll([p.ARCH_REJECTED]) == "REJECTED")
    raises("REJECTED must never be encoded as DONE too", p.ProtocolError,
           p.arch_apply_poll, [p.ARCH_BUSY, p.ARCH_DONE | p.ARCH_REJECTED])
    raises("going idle without DONE or REJECTED is a stuck-status bug",
           p.ProtocolError, p.arch_apply_poll, [p.ARCH_BUSY, 0])

    # ---- cache-operation polling ----------------------------------------------
    check("BUSY then DONE polls to DONE",
          p.cache_op_poll([p.CACHE_BUSY, p.CACHE_BUSY, p.CACHE_DONE]) == "DONE")
    check("the selected-cache field is preserved alongside DONE",
          p.cache_op_poll([p.CACHE_BUSY, p.CACHE_DONE | 0b0100]) == "DONE")
    raises("a command that goes idle with neither BUSY nor DONE is rejected-in-silence",
           p.ProtocolError, p.cache_op_poll, [p.CACHE_BUSY, 0])
    raises("BUSY and DONE together is an illegal encoding", p.ProtocolError,
           p.cache_op_poll, [p.CACHE_BUSY | p.CACHE_DONE])
    raises("polling an empty sample list is a harness error", p.ProtocolError,
           p.cache_op_poll, [])

    # ---- Stage-3 architectural and history host operations --------------------
    regs = {m.OFF_STATUS: p.STAT_HALTED}
    regs.update({off: 0xA0000000 + n
                 for n, off in enumerate(p.ARCH_LIVE_REGISTERS.values())})
    dump = p.arch_dump(lambda off: regs.get(off, 0))
    check("architectural dump reads the complete live register window",
          len(dump) == 33 and dump["D0"] == 0xA0000000 and "MMUSR" in dump)
    raises("architectural dump rejects a running core", p.ProtocolError,
           p.arch_dump, lambda _off: 0)

    writes = []
    status_samples = iter([p.ARCH_BUSY, p.ARCH_DONE])
    def apply_read(off):
        if off == m.OFF_STATUS:
            return p.STAT_HALTED
        if off == m.OFF_ARCH_STATUS:
            return next(status_samples)
        return 0
    p.arch_set(apply_read, lambda off, value: writes.append((off, value)),
               {"d0": 0x11223344, "PC": 0x55667788})
    check("architectural set writes shadows then starts one atomic apply",
          writes == [(m.OFF_ARCH_D0, 0x11223344),
                     (m.OFF_ARCH_PC, 0x55667788),
                     (m.OFF_ARCH_APPLY, 1)])
    raises("architectural set rejects read-only MMUSR", p.ProtocolError,
           p.arch_set, lambda _off: p.STAT_HALTED, lambda _off, _value: None,
           {"MMUSR": 1})

    ring = {m.OFF_CAP_TRACE: (4 << 16) | 2, m.OFF_CAP_TRACE2: 2,
            m.OFF_PC_TRACE_HEAD: 1, m.OFF_BRANCH_RING_HEAD: 1,
            m.OFF_EXC_RING_HEAD: 0}
    for i in range(4):
        ring[m.OFF_PC_TRACE_BODY + 4 * i] = 0x1000 + i
    for i in range(2):
        base = m.OFF_BRANCH_RING_BODY + 16 * i
        ring.update({base: 0x2000 + i, base + 4: 0x3000 + i,
                     base + 8: 0xF0 | i, base + 12: 0})
        base = m.OFF_EXC_RING_BODY + 16 * i
        ring.update({base: 4 + i, base + 4: 0x4000 + i,
                     base + 8: 0x5000 + i, base + 12: 0x6000 + i})
    ring_read = lambda off: ring.get(off, 0)
    check("PC history walks preceding head slots oldest to newest",
          p.read_pc_history(ring_read) == [0x1001, 0x1002, 0x1003, 0x1000])
    branches = p.read_branch_history(ring_read)
    check("branch history decodes committed flow metadata",
          [x["pc"] for x in branches] == [0x2001, 0x2000]
          and branches[1]["branch_type"] == 0 and branches[1]["taken"] is False)
    exceptions = p.read_exception_history(ring_read)
    check("exception history decodes vector, PC, fault, and handler",
          exceptions[1] == {"vector": 5, "exception_pc": 0x4001,
                            "fault_address": 0x5001, "handler_pc": 0x6001})

    # ---- CONTROL / STATUS bit layout (spec 3.3) --------------------------------
    check("CONTROL bit numbering matches spec 3.3",
          (p.CTRL_HALT, p.CTRL_STEP, p.CTRL_SOFT_RST, p.CTRL_INIT_DONE_OVR,
           p.CTRL_COLD_HOLD, p.CTRL_COLD_PULSE, p.CTRL_STEP_ARM)
          == (0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x80))
    check("STATUS bit numbering matches spec 3.3",
          (p.STAT_HALTED, p.STAT_EXC_PENDING, p.STAT_INIT_DONE,
           p.STAT_RUNNING, p.STAT_AUTO_HALT) == (0x01, 0x02, 0x04, 0x08, 0x10))
    check("CONTROL bit 6 is reserved and never set by control_word",
          all((p.control_word(**{k: True}) & 0x40) == 0
              for k in ("halt", "step", "soft_rst", "init_done_override",
                        "cold_hold", "cold_pulse", "step_arm")))

    if FAILURES:
        sys.stderr.write("\n%d check(s) FAILED\n" % len(FAILURES))
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
