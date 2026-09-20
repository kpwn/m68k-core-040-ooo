package m68k040.services

import spinal.core._

/** Sole producer: RobPlugin. None means physically absent, not measured zero. */
trait RobPerfDetailService {
  def robPerfEvents: Option[Bits]
  // {macro retire valid1, valid0, pc1, pc0}; PCs valid only on last-uop retirement.
  def robPerfRetirement: Option[Bits]
}
/** Sole producer: DispatchPlugin. No consumer reaches into producer internals. */
trait DispatchPerfDetailService { def dispatchPerfEvents: Option[Bits] }

object PerfDetail {
  val robNames = Seq("empty", "retire_one_uop", "retire_two_uops", "stall_recovery",
    "stall_halt", "stall_exception", "head_load", "head_store", "head_branch",
    "head_complex", "head_other", "ready_serial", "retire_no_macro",
    "retire_one_macro", "retire_two_macros", "slot1_absent", "slot1_incomplete",
    "slot1_alone", "slot1_other", "mispred_return", "mispred_other",
    "head_mispred_wait", "rob_full")
  val dispatchNames = Seq("no_input", "blocked_rob", "blocked_iq", "accept_one",
    "accept_two", "blocked_both")
  val RobCount = robNames.size
  val DispatchCount = dispatchNames.size

  // Diagnostic allocation tags only; never used by execution or retirement.
  val Other = 0; val Load = 1; val Store = 2; val Branch = 3; val Complex = 4; val Return = 5

  def robEvents(i: RobPerfState): Bits = {
    val e = Bits(RobCount bits); e := 0
    when(!i.nonempty) { e(0) := True }
    .elsewhen(i.retire0) {
      when(i.retire1) { e(2) := True } otherwise { e(1) := True }
    }.elsewhen(i.flushing) { e(3) := True }
    .elsewhen(i.halted) { e(4) := True }
    .elsewhen(i.exceptionActive) { e(5) := True }
    .elsewhen(!i.complete0) {
      switch(i.kind) {
        is(Load) { e(6) := True }
        is(Store) { e(7) := True }
        is(Branch, Return) { e(8) := True }
        is(Complex) { e(9) := True }
        default { e(10) := True }
      }
    } otherwise { e(11) := True }
    val macros = (i.retire0 && i.last0).asUInt.resize(2) +
      (i.retire1 && i.last1).asUInt.resize(2)
    e(12) := i.retire0 && macros === 0
    e(13) := macros === 1
    e(14) := macros === 2
    when(i.retire0 && !i.retire1) {
      when(!i.second) { e(15) := True }
      .elsewhen(!i.complete1) { e(16) := True }
      .elsewhen(i.alone0 || i.alone1) { e(17) := True }
      .otherwise { e(18) := True }
    }
    e(19) := i.redirect && i.kind === Return
    e(20) := i.redirect && i.kind =/= Return
    e(21) := i.nonempty && i.mispredict && !i.retire0
    e(22) := i.full
    e
  }

  def dispatchEvents(valid: Bool, robReady: Bool, iqReady: Bool, second: Bool): Bits = {
    val e = Bits(DispatchCount bits)
    e(0) := !valid
    e(1) := valid && !robReady
    e(2) := valid && robReady && !iqReady
    e(3) := valid && robReady && iqReady && !second
    e(4) := valid && robReady && iqReady && second
    e(5) := valid && !robReady && !iqReady
    e
  }
}

case class RobPerfState() extends Bundle {
  val nonempty, second, full, retire0, retire1 = Bool()
  val complete0, complete1, flushing, halted, exceptionActive = Bool()
  val last0, last1, alone0, alone1, redirect, mispredict = Bool()
  val kind = UInt(3 bits)
}
