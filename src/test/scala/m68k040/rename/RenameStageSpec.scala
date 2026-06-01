package m68k040.rename

import m68k040.decode.{DecodedUop, DecOp}
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class RenameStageSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val dsrc = new DecodeUopSourcePlugin
    val ren  = new RenameStage
    val sink = new RenameUopSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](dsrc, ren, sink)) }
  }

  /** Poke all fields of a DecodedUop slot; unused default to 0/false. */
  def pokeUop(
      u:          DecodedUop,
      valid:      Boolean = true,
      op:         DecOp.E = DecOp.MOVE,
      srcAReg:    Int     = 0, srcAValid: Boolean = false,
      srcBReg:    Int     = 0, srcBValid: Boolean = false,
      dstReg:     Int     = 0, dstValid:  Boolean = false,
      useImm:     Boolean = false, imm: Long = 0,
      readsNzvc:  Boolean = false, readsX:  Boolean = false,
      writesNzvc: Boolean = false, writesX: Boolean = false,
      isBranch:   Boolean = false, cond: Int = 0
  ): Unit = {
    u.valid        #= valid
    u.pc           #= 0
    u.op           #= op
    u.cluster      #= Cluster.INT
    u.size         #= Size.LONG
    u.srcAReg      #= srcAReg; u.srcAValid #= srcAValid
    u.srcBReg      #= srcBReg; u.srcBValid #= srcBValid
    u.dstReg       #= dstReg;  u.dstValid  #= dstValid
    u.useImm       #= useImm;  u.imm       #= imm
    u.readsNzvc    #= readsNzvc;  u.readsX  #= readsX
    u.writesNzvc   #= writesNzvc; u.writesX #= writesX
    u.isBranch     #= isBranch
    u.cond         #= cond
    u.branchDisp   #= 0
    u.unimplemented#= false
  }

  /** Clear (invalidate) a slot. */
  def clearUop(u: DecodedUop): Unit = pokeUop(u, valid = false)

  def waitInit(dut: Dut, cd: ClockDomain): Unit = {
    // Wait for committed-identity + freelist init to complete.
    var n = 0
    while (!dut.dsrc.logic.src.ready.toBoolean && n < 200) { cd.waitSampling(); n += 1 }
    assert(dut.dsrc.logic.src.ready.toBoolean, "src.ready never asserted (init did not complete)")
  }

  test("intra-group RAW: slot1 src reads slot0 dst") {
    SimConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.sink.logic.out.ready #= true
      dut.dsrc.logic.src.valid #= false
      dut.ren.logic.flush #= false
      dut.ren.logic.commit.foreach(_.valid #= false)
      cd.waitSampling()
      waitInit(dut, cd)

      // slot0: MOVE D1 -> D0 ; slot1: MOVE D0 -> D2
      pokeUop(dut.dsrc.logic.src.payload(0), op = DecOp.MOVE, srcAReg = 1, srcAValid = true, dstReg = 0, dstValid = true)
      pokeUop(dut.dsrc.logic.src.payload(1), op = DecOp.MOVE, srcAReg = 0, srcAValid = true, dstReg = 2, dstValid = true)
      dut.dsrc.logic.src.valid #= true
      dut.dsrc.logic.s1v       #= true
      cd.waitSamplingWhere(dut.sink.logic.out.valid.toBoolean && dut.sink.logic.out.ready.toBoolean)
      val p0dst = dut.sink.logic.out.payload(0).pdst.toInt
      val p1srcA = dut.sink.logic.out.payload(1).psrcA.toInt
      val p1dst = dut.sink.logic.out.payload(1).pdst.toInt
      dut.dsrc.logic.src.valid #= false
      assert(p1srcA == p0dst, s"slot1 psrcA=$p1srcA should equal slot0 pdst=$p0dst")
      assert(p0dst != p1dst, s"slot0 pdst=$p0dst should differ from slot1 pdst=$p1dst")
    }
  }

  test("WAW: both write same int reg get distinct phys allocations") {
    SimConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.sink.logic.out.ready #= true
      dut.dsrc.logic.src.valid #= false
      dut.ren.logic.flush #= false
      dut.ren.logic.commit.foreach(_.valid #= false)
      cd.waitSampling()
      waitInit(dut, cd)

      pokeUop(dut.dsrc.logic.src.payload(0), op = DecOp.MOVE, useImm = true, dstReg = 0, dstValid = true)
      pokeUop(dut.dsrc.logic.src.payload(1), op = DecOp.MOVE, useImm = true, dstReg = 0, dstValid = true)
      dut.dsrc.logic.src.valid #= true
      dut.dsrc.logic.s1v       #= true
      cd.waitSamplingWhere(dut.sink.logic.out.valid.toBoolean && dut.sink.logic.out.ready.toBoolean)
      val p0dst = dut.sink.logic.out.payload(0).pdst.toInt
      val p1dst = dut.sink.logic.out.payload(1).pdst.toInt
      dut.dsrc.logic.src.valid #= false
      assert(p0dst != p1dst, s"WAW pdsts must differ: slot0=$p0dst slot1=$p1dst")
    }
  }

  test("flag rename RAW: slot1 NZVC src reads slot0 NZVC dst") {
    SimConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.sink.logic.out.ready #= true
      dut.dsrc.logic.src.valid #= false
      dut.ren.logic.flush #= false
      dut.ren.logic.commit.foreach(_.valid #= false)
      cd.waitSampling()
      waitInit(dut, cd)

      // slot0: ADD D1,D0 (writes NZVC + X) ; slot1: Bcc (reads NZVC)
      pokeUop(dut.dsrc.logic.src.payload(0), op = DecOp.ADD, srcAReg = 1, srcAValid = true,
        srcBReg = 0, srcBValid = true, dstReg = 0, dstValid = true, writesNzvc = true, writesX = true)
      pokeUop(dut.dsrc.logic.src.payload(1), op = DecOp.BRANCH, isBranch = true, readsNzvc = true, cond = 7)
      dut.dsrc.logic.src.valid #= true
      dut.dsrc.logic.s1v       #= true
      cd.waitSamplingWhere(dut.sink.logic.out.valid.toBoolean && dut.sink.logic.out.ready.toBoolean)
      val p0NzvcDst = dut.sink.logic.out.payload(0).pNzvcDst.toInt
      val p1NzvcSrc = dut.sink.logic.out.payload(1).pNzvcSrc.toInt
      dut.dsrc.logic.src.valid #= false
      assert(p1NzvcSrc == p0NzvcDst, s"slot1 pNzvcSrc=$p1NzvcSrc should equal slot0 pNzvcDst=$p0NzvcDst")
    }
  }

  test("commit + flush restores committed int mapping") {
    SimConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.sink.logic.out.ready #= true
      dut.dsrc.logic.src.valid #= false
      dut.ren.logic.flush #= false
      dut.ren.logic.commit.foreach(_.valid #= false)
      cd.waitSampling()
      waitInit(dut, cd)

      // (a) rename MOVE x -> D0, capture P0 = slot0 pdst
      pokeUop(dut.dsrc.logic.src.payload(0), op = DecOp.MOVE, useImm = true, dstReg = 0, dstValid = true)
      clearUop(dut.dsrc.logic.src.payload(1))
      dut.dsrc.logic.src.valid #= true
      dut.dsrc.logic.s1v       #= false
      cd.waitSamplingWhere(dut.sink.logic.out.valid.toBoolean && dut.sink.logic.out.ready.toBoolean)
      val p0 = dut.sink.logic.out.payload(0).pdst.toInt
      dut.dsrc.logic.src.valid #= false
      cd.waitSampling()

      // (b) commit D0 -> P0 to the committed RAT for one cycle
      dut.ren.logic.commit(0).valid #= true
      dut.ren.logic.commit(0).payload.intArch  #= 0
      dut.ren.logic.commit(0).payload.intNew   #= p0
      dut.ren.logic.commit(0).payload.intWrite #= true
      cd.waitSampling()
      dut.ren.logic.commit(0).valid #= false
      cd.waitSampling()

      // (c) flush high one cycle (rollback)
      dut.ren.logic.flush #= true
      cd.waitSampling()
      dut.ren.logic.flush #= false
      cd.waitSampling()

      // wait for freelist re-init after flush
      waitInit(dut, cd)

      // (d) rename MOVE D0 -> D3, assert slot0 psrcA == P0 (committed mapping restored)
      pokeUop(dut.dsrc.logic.src.payload(0), op = DecOp.MOVE, srcAReg = 0, srcAValid = true, dstReg = 3, dstValid = true)
      clearUop(dut.dsrc.logic.src.payload(1))
      dut.dsrc.logic.src.valid #= true
      dut.dsrc.logic.s1v       #= false
      cd.waitSamplingWhere(dut.sink.logic.out.valid.toBoolean && dut.sink.logic.out.ready.toBoolean)
      val srcA = dut.sink.logic.out.payload(0).psrcA.toInt
      dut.dsrc.logic.src.valid #= false
      assert(srcA == p0, s"after commit+flush, D0 should map to committed P0=$p0 but got $srcA")
    }
  }
}
