package m68k040.fuzz

import m68k040.{M68kSim, VerilatorTest}
import m68k040.oracle.ProgramAssembler
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Full-core regression for the p163 hardware shape (2026-09-08, cpu040 4aaee2b1):
  *
  *   ROM 0x40832caa `cmpi.w #1,0x62(%a0)` with A0 = 0xffff0000 -> a WORD read of an
  *   unmapped address bus-errors (vec 2, fa=0xffff0062); the exception unit's LONG
  *   vector fetch at VBR+8 then LOADED 0x000026f0 while DRAM held 0x408026f0 -- the
  *   correct LOW word, a ZERO high word (the exc-ring's `cnt` field is the handler
  *   address the sequencer loaded). The next exception (vec 4 at pc 0x26fe) confirms
  *   the core really jumped to the truncated address.
  *
  * This spec plays exactly that sequence on the real FuzzCoreDut (RobPlugin +
  * ExceptionUnit + LsEuPlugin + DcachePlugin, D-side AXI DECERR injection) and
  * observes the SAME quantity the hardware capture reported: RobPlugin's
  * `debugExceptionEntry.payload.handlerPc` (the vector the sequencer loaded) for the
  * vec-2 entry, in BOTH cache postures (CACR.DE=0: every data access INHIBITED, the
  * uncached sub-transaction sequencer path; CACR.DE=1: the cacheable / early-VIPT
  * path). Non-vacuity: the installed handler's HIGH word is required to be nonzero,
  * so a half-word-truncated fetch cannot pass.
  *
  * A directed sequencer-level check rather than Musashi lock-step: the lock-step
  * harness has no D-side DECERR posture, and the hardware evidence is precisely the
  * loaded-vector value, which `handlerPc` reports directly. */
class VectorFetchAfterWordBusErrorSpec extends AnyFunSuite {
  final case class ExcEntry(vector: Int, handlerPc: Long, exceptionPc: Long, faultAddress: Long)
  final case class Shape(entries: Vector[ExcEntry], sentinel: Long, handlerAddr: Long, cycles: Long)

  val BadAddr = 0x7fff0000L   // top nibble 7: undecoded by AxiMemModel.decoded -> DECERR
  val Sentinel = PortedTestRunner.SentinelAddr

  def program(enableDcache: Boolean): String = {
    val cacr = if (enableDcache) "move.l #0x80000000,%d0 ; movec %d0,%cacr ; " else ""
    "move.l #handler,%d0 ; move.l %d0,0x8 ; " +      // vector 2 (access fault) @ 0x8
    "move.l #badh,%d0 ; move.l %d0,0xc ; move.l %d0,0x10 ; move.l %d0,0x20 ; " + // vec 3/4/8 -> badh
    cacr +
    f"movea.l #0x$BadAddr%08x,%%a0 ; " +
    "cmpi.w #1,0x62(%a0) ; " +                       // WORD read -> DECERR -> vec 2
    "loop: bra loop ; " +
    "handler: move.l #0x600d0002,%d1 ; move.l %d1,0xffff0000 ; hstop: bra hstop ; " +
    "badh: move.l #0x0bad0bad,%d1 ; move.l %d1,0xffff0000 ; bstop: bra bstop"
  }

  def runShape(enableDcache: Boolean, simSeed: Int = 1, timeoutCycles: Long = 20000L): Shape = {
    val loadAddr = PortedTestRunner.loadAddr
    val src = program(enableDcache)
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => throw new AssertionError(s"assemble failed: ${err.reason}")
    }
    var out: Shape = null
    PortedTestRunner.compiled.doSim(s"vecfetch_de${if (enableDcache) 1 else 0}", simSeed) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val iAgent = m68k040.sim.AxiMemModel.attachProgramIFetch(
        dut.icache.logic.axi, cd, loadAddr, image.bytes,
        cfg = m68k040.sim.AxiMemModelConfig(injectBusErrors = true))
      val dsideMem = new m68k040.sim.ConstFillSparseMemory(0xff.toByte)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd,
                                                   sharedMem = dsideMem, injectBusErrors = true)
      dmem.setByteWriteObserver((addr, byte) => iAgent.mem.write(addr, byte))
      for (i <- image.bytes.indices) dmem.mem.write(loadAddr + i, image.bytes(i).toByte)
      for (i <- 0 until 4) dmem.mem.write(Sentinel + i, 0.toByte)
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0
      dut.ctrl.logic.srp #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false
      dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.isp #= 0x00030000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00030000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false

      val e = dut.rob.logic.debugExceptionEntry
      val entries = scala.collection.mutable.ArrayBuffer.empty[ExcEntry]
      var sentinel = 0L
      var cyc = 0L
      while (sentinel == 0L && cyc < timeoutCycles) {
        if (e.valid.toBoolean)
          entries += ExcEntry(e.payload.vector.toInt,
                              e.payload.handlerPc.toLong & 0xffffffffL,
                              e.payload.exceptionPc.toLong & 0xffffffffL,
                              e.payload.faultAddress.toLong & 0xffffffffL)
        cd.waitSampling(); cyc += 1
        val b = (0 until 4).map(i => dmem.mem.read(Sentinel + i).toLong & 0xffL)
        sentinel = (b(0) << 24) | (b(1) << 16) | (b(2) << 8) | b(3)
      }
      // The table entry the program installed, read back from D-side memory at the
      // end -- the same quantity the p163 capture read from DRAM at 0x8 via JTAG-AXI.
      val tb = (0 until 4).map(i => dmem.mem.read(0x8L + i).toLong & 0xffL)
      val handlerAddr = (tb(0) << 24) | (tb(1) << 16) | (tb(2) << 8) | tb(3)
      out = Shape(entries.toVector, sentinel, handlerAddr, cyc)
    }
    out
  }

  def check(label: String, s: Shape): Unit = {
    // Non-vacuity: a half-word truncation of the handler address must be observable.
    assert((s.handlerAddr >>> 16) != 0L,
      f"$label: handler @0x${s.handlerAddr}%08x has a zero high word -- the test could not see the p163 shape")
    assert(s.entries.nonEmpty, s"$label: no exception entry observed in ${s.cycles} cycles")
    val first = s.entries.head
    assert(first.vector == 2, s"$label: first exception must be the access fault (vec 2), got $first")
    assert(first.faultAddress == BadAddr + 0x62,
      f"$label: fault address 0x${first.faultAddress}%08x expected 0x${BadAddr + 0x62}%08x")
    assert(first.handlerPc == s.handlerAddr,
      f"$label: the vector fetch LOADED 0x${first.handlerPc}%08x but the table holds 0x${s.handlerAddr}%08x " +
      f"(p163 hardware loaded 0x${s.handlerAddr & 0xffffL}%08x-shaped: correct low word, zero high word)")
    assert(s.entries.size == 1, s"$label: exactly one exception expected, got ${s.entries}")
    assert(s.sentinel == 0x600d0002L,
      f"$label: sentinel 0x${s.sentinel}%08x -- handler did not run cleanly (0x0bad0bad = a follow-on exception)")
  }

  test("WORD bus error then LONG vector fetch: CACR.DE=0 (all data accesses INHIBITED)", VerilatorTest) {
    check("DE=0", runShape(enableDcache = false))
  }
  test("WORD bus error then LONG vector fetch: CACR.DE=1 (cacheable path)", VerilatorTest) {
    check("DE=1", runShape(enableDcache = true))
  }
}
