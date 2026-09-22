package m68k040.execute.iq

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.decode.DecOp
import m68k040.isa.{Cluster, MemOp}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

class IqHotPredicateSpec extends AnyFunSuite {
  class CheckedIq extends IssueQueuePlugin {
    val check = during build new Area {
      val op = in(DecOp())
      val cluster = in(Cluster())
      val mem = in(MemOp())
      val lea, imm, bValid = in Bool()
      val ctx = IqContext()
      ctx.robId := 0
      ctx.uop.allowOverride()
      ctx.uop := ctx.uop.getZero
      ctx.uop.op := op
      ctx.uop.cluster := cluster
      ctx.uop.memOp := mem
      ctx.uop.leaAddr := lea
      ctx.uop.useImm := imm
      ctx.uop.psrcBValid := bValid
      val incoming = IqHot()
      incoming.assignFrom(ctx, False)
      val stored = RegNext(incoming)
      // Exercise actual production projection, storage and predicate helpers.
      val observed = out Bits(3 bits)
      observed := logic.isLs(stored) ## logic.isCplx(stored) ## logic.srcBIsReg(stored)
    }
  }
  class Idle extends FiberPlugin {
    val logic = during build new Area {
      val iq = host[IssueQueueService]
      iq.push.valid := False
      iq.pushSlot1Valid := False
      iq.push.payload := iq.push.payload.getZero
      iq.flushPort := False
      for (k <- 0 until 5) iq.issue(k).ready := True
    }
  }
  class Dut extends Component {
    val db = new Database
    val host = db on new PluginHost
    val iq = new CheckedIq
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), iq, new Idle)) }
  }

  test("registered hot predicates match original formulas exhaustively", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val q = dut.iq.check
      q.op #= DecOp.MOVE; q.cluster #= Cluster.INT; q.mem #= MemOp.NONE
      q.lea #= false; q.imm #= false; q.bValid #= false
      cd.waitSampling(4)
      var checked = 0
      for (op <- DecOp.elements; cluster <- Cluster.elements; mem <- MemOp.elements;
           flags <- 0 until 8) {
        val lea = (flags & 1) != 0
        val imm = (flags & 2) != 0
        val bValid = (flags & 4) != 0
        val ls = cluster == Cluster.LS && (mem != MemOp.NONE || lea)
        val cplx = cluster == Cluster.CPLX
        val exception = Set(DecOp.PACK, DecOp.UNPK, DecOp.BITFIELD, DecOp.BFRESOLVE)(op)
        val bRead = bValid && (!imm || ls || exception)
        val expected = (if (ls) 4 else 0) | (if (cplx) 2 else 0) | (if (bRead) 1 else 0)
        cd.waitFallingEdge()
        q.op #= op; q.cluster #= cluster; q.mem #= mem
        q.lea #= lea; q.imm #= imm; q.bValid #= bValid
        cd.waitRisingEdge(); sleep(1)
        assert(q.observed.toInt == expected,
          s"op=$op cluster=$cluster mem=$mem lea=$lea imm=$imm bValid=$bValid expected=$expected got=${q.observed.toInt}")
        checked += 1
      }
      assert(checked == DecOp.elements.size * Cluster.elements.size * MemOp.elements.size * 8)
      println(s"IQ_HOT_PREDICATES_PASS checked=$checked opcodes=${DecOp.elements.size}")
    }
  }
}
