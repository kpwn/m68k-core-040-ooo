package m68k040.socket

import m68k040.rob.RobPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.FiberPlugin

object PeripheralResetPlugin {
  /** D22's width, COPIED from v1 rather than re-derived, for the same reason D20's timeout
    * is: `commit.v:2081-2101`, `localparam integer RESET_INSTR_CYCLES = 518;` -- "MAME's
    * 68040 model charges 518 clocks for RESET. Holding the core for the same interval also
    * exceeds the 68040 RSTO minimum of 124 clocks."
    *
    * NOT 512. An earlier draft of the spec allowed "defaulting to 512 core-clock cycles if
    * v1 emits a bare pulse"; v1's driver was then actually READ and it emits a 518-cycle
    * LEVEL, so that fallback is withdrawn. The number is measured, not assumed. */
  val V1_HOLD_CYCLES = 518
}

/** `cpu_peripheral_reset` -- the 68040 `RESET` instruction's external indication (D22).
  *
  * ==Ownership==
  * This belongs to the socket work, not the debug-ctrl plan: it is an architectural
  * INSTRUCTION SIDE EFFECT, not a debug-CSR bit. `m68k_axi_wrapper.v:683` binds it and
  * `fpga_top_sd.vh:80` consumes it. Both `cpu_socket.vh:170-176` and the debug-ctrl plan's
  * port list omit it, which is `SOC-2` -- a documentation fix in the sibling repo.
  *
  * ==OPEN-1: output only (RESOLVED, Option A)==
  * `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md` section 14.1, decided
  * by explicit user sign-off: "wrt RESET you implement something that is as compatible to
  * 68k as possible." The M68000-family `RESET` instruction is documented, architecture-wide,
  * as asserting only the external reset pin for a fixed duration -- the processor's own
  * internal state and instruction execution are explicitly unaffected; execution continues
  * normally through the pulse. v1's internal dispatch/retire gating during its own hold
  * (`commit.v`'s `can_commit`/`can_commit_irq` stall, `m68k_core_fetch.vh`'s dispatch/rename
  * stall) is v1's own implementation choice, not a requirement of the 68k ISA's documented
  * `RESET` semantics -- so it is the answer that is LESS, not more, 68k-compatible, and is
  * not reproduced here.
  *
  * @param gateDispatch the `OPEN-1` answer. REQUIRED, with no default, so this plugin cannot
  *        be instantiated by someone who has not read the signed-off addendum above. Only
  *        `false` (Option A, output-only) is implemented in this build: Option B's
  *        `RobPlugin.scala` quiesce wiring (`:585`/`:1447`/`:1487-1488`'s `periphResetHold`
  *        term) is explicitly out of scope for this task and does not exist in this tree --
  *        passing `true` fails fast at elaboration rather than silently doing nothing.
  */
class PeripheralResetPlugin(val enable: Boolean = false,
                            val holdCycles: Int = PeripheralResetPlugin.V1_HOLD_CYCLES,
                            val gateDispatch: Boolean) extends FiberPlugin {
  require(holdCycles >= 1, s"holdCycles must be >= 1 (got $holdCycles)")
  require(!gateDispatch,
    "OPEN-1 is resolved as Option A (output only) -- see design spec section 14.1. " +
    "gateDispatch=true (Option B, dispatch/retire gating) has no RobPlugin wiring in " +
    "this build; that change is explicitly out of scope for this task.")

  val logic = during build new Area {
    val rob = host[RobPlugin]
    val exc = rob.logic.exc

    // A LEVEL, not a pulse. Reloading on a retirement that arrives mid-hold re-arms without
    // ever dropping low, which is what "back-to-back RESETs re-arm it without a glitch low"
    // means (spec section 13).
    val cnt = Reg(UInt(log2Up(holdCycles + 1) bits)) init 0
    when(exc.resetInstrRetire)  { cnt := U(holdCycles, cnt.getWidth bits) }
      .elsewhen(cnt =/= U(0))   { cnt := cnt - 1 }
    val level = cnt =/= U(0)
    level.simPublic()

    val cpuPeripheralReset =
      if (enable) { val p = out Bool (); p.setName("cpu_peripheral_reset"); p := level; p }
      else level
  }
}
