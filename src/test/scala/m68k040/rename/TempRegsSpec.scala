package m68k040.rename

import m68k040.M68kSim
import m68k040.decode.{DecodedUop, DecOp}
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Task 1: int temp arch regs (T0/T1 == arch ids 16/17) must rename/commit cleanly
  * through the int RAT (archDepth 18) and int freelist (archCount 18), with the
  * int reg-id fields widened to 5 bits. The cracker targets these temps. */
class TempRegsSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val dsrc = new DecodeUopSourcePlugin
    val ren  = new RenameStage
    val sink = new RenameUopSinkPlugin
    val cdrv = new RenameCommitDriverPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](dsrc, ren, sink, cdrv)) }
  }

  def pokeUop(
      u:          DecodedUop,
      valid:      Boolean = true,
      op:         DecOp.E = DecOp.MOVE,
      srcAReg:    Int     = 0, srcAValid: Boolean = false,
      srcBReg:    Int     = 0, srcBValid: Boolean = false,
      dstReg:     Int     = 0, dstValid:  Boolean = false,
      useImm:     Boolean = false, imm: Long = 0
  ): Unit = {
    u.valid        #= valid
    u.pc           #= 0
    u.lenWords     #= 0
    u.op           #= op
    u.cluster      #= Cluster.INT
    u.size         #= Size.LONG
    u.memOp        #= m68k040.isa.MemOp.NONE
    u.srcAReg      #= srcAReg; u.srcAValid #= srcAValid
    u.srcBReg      #= srcBReg; u.srcBValid #= srcBValid
    u.dstReg       #= dstReg;  u.dstValid  #= dstValid
    u.useImm       #= useImm;  u.imm       #= imm
    u.readsNzvc    #= false;  u.readsX  #= false
    u.writesNzvc   #= false;  u.writesX #= false
    u.isBranch     #= false
    u.cond         #= 0

    u.unimplemented#= false
  }

  def waitInit(dut: Dut, cd: ClockDomain): Unit = {
    var n = 0
    while (!dut.dsrc.logic.src.ready.toBoolean && n < 200) { cd.waitSampling(); n += 1 }
    assert(dut.dsrc.logic.src.ready.toBoolean, "src.ready never asserted (init did not complete)")
  }

  test("temp arch reg 16 (T0) renames to a distinct pdst and threads dstArch=16") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.sink.logic.out.ready #= true
      dut.dsrc.logic.src.valid #= false
      dut.cdrv.logic.flushIn #= false
      dut.cdrv.logic.cmd.foreach(_.valid #= false)
      cd.waitSampling()
      waitInit(dut, cd)

      // slot0: a load-like uop writing temp T0 (arch 16); slot1: an op reading T0.
      pokeUop(dut.dsrc.logic.src.payload(0), op = DecOp.MOVE, useImm = true, dstReg = 16, dstValid = true)
      pokeUop(dut.dsrc.logic.src.payload(1), op = DecOp.ADD, srcAReg = 1, srcAValid = true,
              srcBReg = 16, srcBValid = true, dstReg = 1, dstValid = true)
      dut.dsrc.logic.src.valid #= true
      dut.dsrc.logic.s1v       #= true
      cd.waitSamplingWhere(dut.sink.logic.out.valid.toBoolean && dut.sink.logic.out.ready.toBoolean)
      val p0dst   = dut.sink.logic.out.payload(0).pdst.toInt
      val dstArch = dut.sink.logic.out.payload(0).dstArch.toInt
      val p1srcB  = dut.sink.logic.out.payload(1).psrcB.toInt
      dut.dsrc.logic.src.valid #= false
      assert(dstArch == 16, s"slot0 dstArch should be 16 (T0), got $dstArch")
      assert(p1srcB == p0dst, s"slot1 psrcB ($p1srcB) should read slot0's T0 pdst ($p0dst) via intra-group RAW")
    }
  }

  test("temp arch reg 17 (T1) renames to a distinct pdst") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.sink.logic.out.ready #= true
      dut.dsrc.logic.src.valid #= false
      dut.cdrv.logic.flushIn #= false
      dut.cdrv.logic.cmd.foreach(_.valid #= false)
      cd.waitSampling()
      waitInit(dut, cd)

      pokeUop(dut.dsrc.logic.src.payload(0), op = DecOp.MOVE, useImm = true, dstReg = 16, dstValid = true)
      pokeUop(dut.dsrc.logic.src.payload(1), op = DecOp.MOVE, useImm = true, dstReg = 17, dstValid = true)
      dut.dsrc.logic.src.valid #= true
      dut.dsrc.logic.s1v       #= true
      cd.waitSamplingWhere(dut.sink.logic.out.valid.toBoolean && dut.sink.logic.out.ready.toBoolean)
      val p0dst    = dut.sink.logic.out.payload(0).pdst.toInt
      val p1dst    = dut.sink.logic.out.payload(1).pdst.toInt
      val d1Arch   = dut.sink.logic.out.payload(1).dstArch.toInt
      dut.dsrc.logic.src.valid #= false
      assert(d1Arch == 17, s"slot1 dstArch should be 17 (T1), got $d1Arch")
      assert(p0dst != p1dst, s"T0 pdst=$p0dst should differ from T1 pdst=$p1dst")
    }
  }
}
