# Passive instruction-word evidence

Purpose: distinguish an incorrect opcode/PC pair at the accepted fetch/align
boundary from corruption or incorrect attribution later in decode/retirement.
This observes speculative packets, not committed instructions. A match does
not establish that the same instruction eventually retired.

The diagnostic implements spec section 9.4 in
`superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md`. It consumes only
`DecodeFeedService`, adds no Global key, and never controls execution. Packet
fields are registered locally before comparison. Both lanes are observed;
fault packets and non-handshaken packets are excluded. A nonzero SEEN count
distinguishes correct observations from a probe that never saw its target.

## Host use

Use the SoC's mandatory JTAG lease. First read OFF_FEATURES (0xA0) and require
bit25; old bitstreams return zeros for these offsets and cannot measure this.
Inside the existing REPL's `tcl` escape, for the immutable ROM instruction
previously associated with a spurious A-line exception:

```tcl
dbg_wr 0x1800 0
dbg_wr 0x1804 0x4080e2a2
dbg_wr 0x1808 0x2228
dbg_wr 0x1800 3
```

Configuration survives CPU reset; evidence and pending observations clear on
CPU reset. Use the verified boot/reset workflow, not the broken single-step
command. Later disable with `dbg_wr 0x1800 0` and read:

| Offset | Meaning |
|---|---|
| 0x1800 | enabled bit0, captured mismatch bit1, captured lane bit2 |
| 0x180C | captured virtual PC |
| 0x1810 | expected opcode high16, actual opcode low16 |
| 0x1814 | saturating accepted target-PC observation count |

A nonzero mismatch shows that fetch/align delivered unexpected bytes at that
virtual PC, but can still reflect an unexpected mapping or changed memory.
Establish code immutability and the intended translation independently.
No mismatch with SEEN=0 is **no evidence**, not a pass. No mismatch with
SEEN>0 only covers the selected PC in this run; it does not clear the frontend
generally or exclude corruption after this observation point.

## Validation and artifact provenance

`DebugFetchWordCheckSpec` passes 2 tests through actual debug AXI: expected
words, deliberately wrong words, both lanes, invalid slot1, fault exclusion,
non-ready exclusion, first-hit retention, zero-strobe clear, disabled behavior,
CPU reset, config wipe, and absent-feature RAZ/WI. Log:
`/tmp/codex-fetch-check-test.log`.

Full socket elaboration passes (`/tmp/codex-fetch-check-socket-gen.log`), with
an identical top-level port declaration to the prior generated socket.
The required fast gate passes **372 tests, 0 failures, 2 ignored**
(`/tmp/codex-fetch-check-fast-gate-final.log`); host/register-map checks pass.
Hardware timing, resource cost, and behavior remain unverified. The
already-running `vivado200_sonic_pipe` build predates this diagnostic and must
not be represented as containing it. `GenSocketTopVerilog [output-directory]`
now permits a separate diagnostic output directory; its default is unchanged.
