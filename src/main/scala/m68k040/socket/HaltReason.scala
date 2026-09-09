package m68k040.socket

/** Reason codes for the core's sticky halt (design spec D28, section 6.4).
  *
  * ==Why this exists at all==
  * `RobPlugin.scala:373-376` declares the halt seam as a plain, undiscriminated
  * `Bool`, and `FullCoreSynth.scala:356-363` says why that was right at the time:
  * *"nothing downstream distinguishes WHICH producer fired, so a plain OR is exactly
  * right and a priority/first-wins encoding would buy nothing."* That was true with two
  * producers which both meant "diagnostic crash". It stops being true here: D15 (a
  * non-OKAY reset-vector response) and D20 (a merge-arbiter bounded-grant expiry) are
  * exactly the two halts an operator most needs to tell apart, and today's seam cannot
  * express either.
  *
  * ==Scope boundary==
  * This object and `RobPlugin`'s sticky register ARE the channel. Making it readable
  * over `dbg_axi` is a debug-ctrl register and belongs to
  * `docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md`; the VIO spec's
  * `V21` reserves `halt_reason[3:0]` in its bundle for the same reason. Until one of
  * those lands, the reason is observable in simulation (`simPublic`, matching
  * `coreHaltedIn`'s existing treatment) and that is sufficient for every section 13
  * obligation.
  *
  * ==Relationship to `DcachePlugin`'s private kinds==
  * `DcachePlugin`'s `diagFaultKind` (`DcachePlugin.scala:2030-2031`: 0 = WT-beat /
  * INHIBITED-drain, 1 = drain-miss write-allocate refill, 2 = eviction writeback,
  * 3 = CPUSH maintenance writeback) STAYS PRIVATE and stays 3 bits. It is a SUB-code
  * under `DCACHE_DIAG`, not a peer -- spec section 6.4 states this outright. Do not
  * flatten the two encodings together; they answer different questions ("which
  * subsystem halted the core" vs "which of the D-cache's four AXI write issuers saw a
  * non-OKAY response"). */
object HaltReason {
  /** Width of the reason field. Three bits leaves room for three more producers
    * without a re-pin; the VIO spec's reserved `halt_reason[3:0]` is wider still. */
  val W = 3

  /** No halt has been recorded. */
  val NONE = 0
  /** `DcachePlugin`'s async diagnostic-fault channel (`dc.diagFault`) -- a non-OKAY AXI
    * response on a trusted-cacheable-path transaction. Sub-coded by that plugin's own
    * private `diagFaultKind`. */
  val DCACHE_DIAG = 1
  /** A DTLB translation fault taken while the commit-side sequencer is transferring an
    * FSAVE/FRESTORE state frame (`exc.fsXlateFault`). */
  val FS_XLATE = 2
  /** D15: a non-OKAY response to the reset-vector fetch at physical 0. Hardware-faithful
    * -- a bus fault during reset exception processing is a double bus fault on a real
    * 68040 and the part halts. */
  val RESET_VECTOR = 3
  /** D20: the `axi_d` merge arbiter held a grant for its full bounded-grant window with
    * no progress on any channel of that direction. Something structural is wrong; the
    * arbiter never fabricates a response. */
  val ARBITER_WEDGE = 4
  /** W26: a table walker held one of the D-cache client ports for its full bounded window
    * with no progress on any channel. This code has to exist separately from
    * `ARBITER_WEDGE` because a wedge at the walker/D-cache merge point produces NO AXI
    * grant at all -- the `axi_d` arbiter sees nothing, the D-cache's own diagnostic
    * channel sees nothing, and the failure would otherwise be completely unobservable. */
  val WALKER_PORT_WEDGE = 5
  /** A DTLB translation fault taken while the commit-side exception sequencer was
    * stacking an ENTRY frame, fetching the handler VECTOR, or popping an RTE frame
    * (`exc.excXlateFault`).
    *
    * This is the 68040's DOUBLE FAULT. MC68040 UM S8.2.6: a fault taken during the
    * exception processing of a previous fault cannot itself be reported -- there is no
    * stack to report it on and no meaningful PC to resume -- so the processor asserts
    * its halt output and stops until reset. Modelling it as a halt (rather than as a
    * nested exception, or worse, as the silent identity-mapped access this core used
    * before) is the hardware-faithful behaviour and is the same treatment
    * `RESET_VECTOR` already gives a bus fault during reset exception processing. */
  val DOUBLE_FAULT = 6

  def name(code: Int): String = code match {
    case NONE          => "NONE"
    case DCACHE_DIAG   => "DCACHE_DIAG"
    case FS_XLATE      => "FS_XLATE"
    case RESET_VECTOR  => "RESET_VECTOR"
    case ARBITER_WEDGE => "ARBITER_WEDGE"
    case WALKER_PORT_WEDGE => "WALKER_PORT_WEDGE"
    case DOUBLE_FAULT  => "DOUBLE_FAULT"
    case other         => s"UNKNOWN($other)"
  }
}
