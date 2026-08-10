package m68k040.frontend

import m68k040.{M68kParams, VerilatorTest}
import m68k040.cache.{FetchCmd, FetchRsp}
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.services.{BtbUpdate, BtbUpdateService, FetchService, GshareUpdateService}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

import scala.collection.mutable.ArrayBuffer

/** End-to-end registered-token FTB proof at the FetchAlign boundary.
  *
  * The cache is controlled so command cadence and backpressure are exact rather than
  * inferred from a memory latency window. Each advertised event is counted, and the
  * confirmation case injects the truncated source window followed by the target window;
  * a design which merely toggles applyNow but still issues W+8 or flushes target bytes
  * cannot pass.
  */
class FetchDirectedFtbSpec extends AnyFunSuite {

  class ControlledFetchPlugin extends FiberPlugin with FetchService {
    val logic = during build new Area {
      val cmdPort = Stream(FetchCmd())
      val rspPort = Flow(FetchRsp())
      val cmdOut = master(Stream(FetchCmd()))
      val rspIn = slave(Flow(FetchRsp()))
      cmdOut << cmdPort
      rspPort << rspIn
    }
    override def cmd: Stream[FetchCmd] = logic.cmdPort
    override def rsp: Flow[FetchRsp] = logic.rspPort
  }

  class UpdateDriver extends FiberPlugin with BtbUpdateService {
    val logic = during build new Area {
      val update = Flow(BtbUpdate())
      in(update.valid)
      update.payload.flatten.foreach(in(_))
    }
    override def btbUpdate: Flow[BtbUpdate] = logic.update
  }

  class GshareUpdateDriver extends FiberPlugin {
    val logic = during build new Area {
      val valid = in Bool()
      val index = in UInt(11 bits)
      val taken = in Bool()
      val update = host[GshareUpdateService].gshareUpdate
      update.valid := valid
      update.payload.index := index
      update.payload.taken := taken
    }
  }

  /** Test-side equivalent of BackendWiringPlugin's registered internal redirect. */
  class InternalRedirectDriver extends FiberPlugin {
    val logic = during build new Area {
      val valid = in Bool()
      val payload = in UInt(32 bits)
      val redirect = host[FetchAlignPlugin].logic.mispredictRedirect
      redirect.valid := valid
      redirect.payload := payload
    }
  }

  /** Production-equivalent retained decode-BTB wiring for the collision proof. */
  class DecodeBtbWire extends FiberPlugin {
    val logic = during build new Area {
      val fa = host[FetchAlignPlugin]
      val btb = host[BtbPlugin]
      btb.logic.queryPc := fa.logic.btbQueryPc0
      btb.logic.queryValid := fa.logic.btbQueryValid0
      btb.logic.query2BasePc := fa.logic.btbQueryBasePc1
      btb.logic.query2Sel := fa.logic.btbQuerySel1
      btb.logic.query2Valid := fa.logic.btbQueryValid1
      btb.logic.invalidateAll := False
      fa.logic.btbPredTaken0 := btb.logic.predTakenComb
      fa.logic.btbPredTarget0 := btb.logic.predTargetComb
      fa.logic.btbPredTaken1 := btb.logic.predTaken2Comb
      fa.logic.btbPredTarget1 := btb.logic.predTarget2Comb
    }
  }

  class Dut extends Component {
    val db = new Database
    val host = db on new PluginHost
    val fetch = new ControlledFetchPlugin
    val update = new UpdateDriver
    val btb = new BtbPlugin
    val ftb = new FtbPlugin(entries = 128)
    val gshare = new GsharePlugin
    val gshareUpdate = new GshareUpdateDriver
    val fa = new FetchAlignPlugin(enableFetchDirected = true)
    val internalRedirect = new InternalRedirectDriver
    val btbWire = new DecodeBtbWire
    val probe = new DecodeFeedProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), fetch, update, btb, ftb, gshare, gshareUpdate,
      fa, internalRedirect, btbWire, probe)) }
  }

  private val W = 0x1000L
  private val B = W + 2
  private val T = 0x1040L

  private def idle(dut: Dut): Unit = {
    dut.fetch.logic.cmdOut.ready #= false
    dut.fetch.logic.rspIn.valid #= false
    dut.fetch.logic.rspIn.payload.pc #= 0
    dut.fetch.logic.rspIn.payload.data #= 0
    dut.fetch.logic.rspIn.payload.fault #= false
    dut.fetch.logic.rspIn.payload.atc #= false
    for (p <- dut.fetch.logic.rspIn.payload.pred) {
      p.simple #= true
      p.lenWords #= 1
      p.ambiguousLine #= false
      p.size #= Size.LONG
    }
    dut.update.logic.update.valid #= false
    dut.update.logic.update.payload.pc #= 0
    dut.update.logic.update.payload.taken #= false
    dut.update.logic.update.payload.target #= 0
    dut.update.logic.update.payload.brType #= 0
    dut.update.logic.update.payload.len #= 0
    dut.gshareUpdate.logic.valid #= false
    dut.gshareUpdate.logic.index #= 0
    dut.gshareUpdate.logic.taken #= false
    dut.fa.logic.redirect.valid #= false
    dut.fa.logic.redirect.payload #= 0
    dut.fa.logic.resume.valid #= false
    dut.fa.logic.resume.payload #= 0
    dut.internalRedirect.logic.valid #= false
    dut.internalRedirect.logic.payload #= 0
    dut.probe.logic.feedOut.ready #= false
  }

  private def train(dut: Dut, cd: ClockDomain, brType: Int = 1, len: Int = 1,
                    target: Long = T, pc: Long = B): Unit = {
    val u = dut.update.logic.update
    u.valid #= true
    u.payload.pc #= pc
    u.payload.taken #= true
    u.payload.target #= target
    u.payload.brType #= brType
    u.payload.len #= len
    cd.waitSampling()
    u.valid #= false
    cd.waitSampling(2)
  }

  private def redirect(dut: Dut, cd: ClockDomain): Unit = {
    dut.fa.logic.redirect.valid #= true
    dut.fa.logic.redirect.payload #= W
    cd.waitSampling()
    dut.fa.logic.redirect.valid #= false
  }

  private def driveRsp(dut: Dut, cd: ClockDomain, pc: Long, words: Seq[Int],
                       lens: Seq[Int] = Seq.fill(4)(1), fault: Boolean = false,
                       atc: Boolean = false): Unit = {
    require(words.length == 4)
    require(lens.length == 4)
    val data = words.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (word, lane)) =>
      acc | (BigInt(word & 0xffff) << (lane * 16))
    }
    val r = dut.fetch.logic.rspIn
    r.valid #= true
    r.payload.pc #= pc
    r.payload.data #= data
    r.payload.fault #= fault
    r.payload.atc #= atc
    for ((p, len) <- r.payload.pred.zip(lens)) {
      p.simple #= true
      p.lenWords #= len
      p.ambiguousLine #= false
      p.size #= Size.LONG
    }
    cd.waitSampling()
  }

  private case class Trace(cmds: ArrayBuffer[(Long, Long)], var applies: Int,
                           var pushes: Int, var confirms: Int)

  private def trace(dut: Dut, cd: ClockDomain): Trace = {
    val tr = Trace(ArrayBuffer.empty, 0, 0, 0)
    var cycle = 0L
    cd.onSamplings {
      if (dut.fetch.logic.cmdOut.valid.toBoolean && dut.fetch.logic.cmdOut.ready.toBoolean)
        tr.cmds += cycle -> dut.fetch.logic.cmdOut.payload.pc.toLong
      if (dut.fa.logic.applyNow.toBoolean) {
        assert(dut.fa.logic.resultTokenProof.toBoolean,
          "physical FTB application escaped without the fixed-latency ring-token proof")
        tr.applies += 1
      }
      if (dut.fa.logic.ftqPush.toBoolean) tr.pushes += 1
      if (dut.fa.logic.ftqConfirmFire.toBoolean) tr.confirms += 1
      cycle += 1
    }
    tr
  }

  private def await(cd: ClockDomain, max: Int, clue: String)(p: => Boolean): Unit = {
    var n = 0
    while (!p && n < max) { cd.waitSampling(); n += 1 }
    assert(p, s"$clue after $max cycles")
  }

  private def gshareIndex(pc: Long): Int = {
    var value = pc >>> 1
    var folded = 0
    while (value != 0) {
      folded ^= (value & 0x7ffL).toInt
      value >>>= 11
    }
    folded & 0x7ff
  }

  private def trainNotTaken(dut: Dut, cd: ClockDomain, pc: Long): Unit = {
    val upd = dut.gshareUpdate.logic
    for (_ <- 0 until 2) { // reset value 2 -> 1 -> 0
      upd.valid #= true
      upd.index #= gshareIndex(pc)
      upd.taken #= false
      cd.waitSampling()
    }
    upd.valid #= false
    cd.waitSampling()
  }

  test("registered result replaces W+8 with target in the immediately following command", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      train(dut, cd)
      val tr = trace(dut, cd)
      dut.fetch.logic.cmdOut.ready #= true
      redirect(dut, cd)
      await(cd, 12, "two fetch commands") { tr.cmds.size >= 2 }
      dut.fetch.logic.cmdOut.ready #= false
      cd.waitSampling(2)

      assert(tr.cmds.take(2).map(_._2).toSeq == Seq(W, T),
        s"fetch path was ${tr.cmds.take(2).map(x => f"0x${x._2}%x")}")
      assert(tr.cmds(1)._1 == tr.cmds(0)._1 + 1,
        s"target command was not C+1: ${tr.cmds.take(2)}")
      assert(!tr.cmds.exists(_._2 == W + 8), "sequential W+8 command escaped")
      assert(tr.applies == 1 && tr.pushes == 1,
        s"expected one atomic application/push, got ${tr.applies}/${tr.pushes}")
      assert(dut.fa.logic.ringKeep(0).toInt == 2,
        s"source window was not truncated at branch end: keep=${dut.fa.logic.ringKeep(0).toInt}")
      assert(dut.fa.logic.ftqCount.toInt == 1, "one unconfirmed FTQ entry must remain")
    }
  }

  test("full-ring turnover applies the C+1 plan to the replacement slot", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      val replacement = W + 24
      val target = 0x1800L
      train(dut, cd, target = target, pc = replacement + 2)
      val tr = trace(dut, cd)
      dut.fetch.logic.cmdOut.ready #= true
      redirect(dut, cd)
      await(cd, 12, "three-command full ring") { tr.cmds.size >= 3 }
      cd.waitSampling()
      assert(tr.cmds.take(3).map(_._2).toSeq == Seq(W, W + 8, W + 16),
        s"unexpected full-ring bootstrap: ${tr.cmds.take(3)}")
      assert(dut.fa.logic.ringCount.toInt == 3 &&
             dut.fa.logic.ringHead.toInt == 0 && dut.fa.logic.ringTail.toInt == 0,
        "turnover proof did not reach the full head==tail state")

      // Consume old slot 0 while full. The same edge installs the sequential
      // replacement into slot 0 and launches its predictor lookup. Its C+1 result must
      // update slot 0 even though ringHead has already advanced to slot 1; using the
      // live head instead of the locally delayed issued slot makes this test fail.
      driveRsp(dut, cd, W, Seq(0x7000, 0x7201, 0x7402, 0x7603))
      sleep(1)
      dut.fetch.logic.rspIn.valid #= false
      assert(tr.cmds.size >= 4 && tr.cmds(3)._2 == replacement,
        s"full-ring replacement was not issued exactly once: ${tr.cmds}")
      assert(dut.fa.logic.ringCount.toInt == 3 && dut.fa.logic.ringHead.toInt == 1,
        "consume-and-replace did not preserve occupancy/advance the old head")

      cd.waitSampling()
      sleep(1)
      dut.fetch.logic.cmdOut.ready #= false
      assert(tr.applies == 1 && tr.pushes == 1,
        s"replacement plan did not apply exactly once: ${tr.applies}/${tr.pushes}")
      assert(dut.fa.logic.ringKeep(0).toInt == 2 &&
             dut.fa.logic.ringKeep(1).toInt == 4,
        s"C+1 result updated the wrong ring record: keep0=${dut.fa.logic.ringKeep(0).toInt} " +
          s"keep1=${dut.fa.logic.ringKeep(1).toInt}")
      assert(dut.fa.logic.targetHoldValid.toBoolean &&
             dut.fa.logic.targetHoldPc.toLong == target,
        f"full ring did not hold the exact target 0x$target%x")
    }
  }

  test("result-time cache backpressure holds one exact target without replaying application", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      train(dut, cd)
      val tr = trace(dut, cd)
      dut.fetch.logic.cmdOut.ready #= true
      redirect(dut, cd)
      await(cd, 8, "source command") { tr.cmds.nonEmpty }
      dut.fetch.logic.cmdOut.ready #= false
      await(cd, 8, "held target") { dut.fa.logic.targetHoldValid.toBoolean }
      assert(dut.fa.logic.targetHoldPc.toLong == T)
      assert(dut.fa.logic.targetHoldDrop.toInt == 0)
      cd.waitSampling(3)
      assert(tr.applies == 1 && tr.pushes == 1 && tr.cmds.size == 1,
        s"backpressure replayed an event: apply=${tr.applies} push=${tr.pushes} cmds=${tr.cmds}")
      assert(dut.fa.logic.targetHoldPc.toLong == T, "held target changed under backpressure")

      dut.fetch.logic.cmdOut.ready #= true
      await(cd, 8, "released target command") { tr.cmds.size == 2 }
      dut.fetch.logic.cmdOut.ready #= false
      cd.waitSampling()
      assert(tr.cmds.map(_._2).toSeq == Seq(W, T), s"held target fired incorrectly: ${tr.cmds}")
      assert(!dut.fa.logic.targetHoldValid.toBoolean, "hold did not clear on its sole fire")
      assert(tr.applies == 1 && tr.pushes == 1)
    }
  }

  test("a redirect colliding with the registered result kills the plan atomically", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      train(dut, cd)
      val tr = trace(dut, cd)
      dut.fetch.logic.cmdOut.ready #= true
      redirect(dut, cd)
      await(cd, 8, "source command") { tr.cmds.nonEmpty }

      // The W lookup result is live now. Block the cache command and redirect in that
      // exact result cycle: no target command, truncation, or FTQ entry may survive.
      val recovery = 0x2000L
      dut.fetch.logic.cmdOut.ready #= false
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= recovery
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false
      cd.waitSampling()

      assert(tr.applies == 0 && tr.pushes == 0,
        s"redirect/result collision applied stale state: ${tr.applies}/${tr.pushes}")
      assert(dut.fa.logic.ftqCount.toInt == 0, "redirect must leave the FTQ empty")
      assert(!dut.fa.logic.targetHoldValid.toBoolean, "redirect must not capture a stale target")
      assert(dut.fa.logic.ringKeep.forall(_.toInt == 4),
        s"redirect collision truncated a ring slot: ${dut.fa.logic.ringKeep.map(_.toInt)}")

      dut.fetch.logic.cmdOut.ready #= true
      await(cd, 8, "redirect recovery command") { tr.cmds.size >= 2 }
      dut.fetch.logic.cmdOut.ready #= false
      assert(tr.cmds.take(2).map(_._2).toSeq == Seq(W, recovery),
        s"redirect recovery path was ${tr.cmds.take(2)}")
      assert(!tr.cmds.exists(_._2 == T), "killed FTB target escaped after redirect")
    }
  }

  test("registered internal redirect kills a physically applied held target", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      train(dut, cd)
      val tr = trace(dut, cd)
      dut.fetch.logic.cmdOut.ready #= true
      redirect(dut, cd)
      await(cd, 8, "source command") { tr.cmds.nonEmpty }

      // Unlike the external/test redirect above, the production commit/complex-resume
      // action is registered. It is deliberately kill-after-apply: require the physical
      // plan event while cache backpressure forces its target into the hold path, then
      // require the redirect's later-priority FTQ/hold clear to make it unobservable.
      val recovery = 0x2800L
      var collisionApply = false
      var collisionPush = false
      var collisionFlush = false
      cd.onSamplings {
        if (dut.internalRedirect.logic.valid.toBoolean) {
          collisionApply = collisionApply || dut.fa.logic.applyNow.toBoolean
          collisionPush = collisionPush || dut.fa.logic.ftqPush.toBoolean
          collisionFlush = collisionFlush || dut.fa.logic.ftqFlush.toBoolean
        }
      }
      dut.fetch.logic.cmdOut.ready #= false
      dut.internalRedirect.logic.valid #= true
      dut.internalRedirect.logic.payload #= recovery
      cd.waitSampling()
      dut.internalRedirect.logic.valid #= false
      cd.waitSampling()

      assert(collisionApply && collisionPush && collisionFlush,
        s"internal redirect did not exercise kill-after-apply: " +
          s"apply=$collisionApply push=$collisionPush flush=$collisionFlush")
      assert(tr.applies == 1 && tr.pushes == 1,
        s"physical collision event was lost or duplicated: ${tr.applies}/${tr.pushes}")
      assert(dut.fa.logic.ftqCount.toInt == 0,
        "internal redirect left the physically pushed FTQ entry live")
      assert(!dut.fa.logic.targetHoldValid.toBoolean,
        "internal redirect left the physically captured target live")

      dut.fetch.logic.cmdOut.ready #= true
      await(cd, 8, "internal-redirect recovery command") { tr.cmds.size >= 2 }
      dut.fetch.logic.cmdOut.ready #= false
      assert(tr.cmds.take(2).map(_._2).toSeq == Seq(W, recovery),
        s"held collision target escaped before recovery: ${tr.cmds.take(2)}")
      assert(!tr.cmds.exists(_._2 == T),
        s"physically applied target became an architectural command: ${tr.cmds}")
    }
  }

  test("registered internal redirect stales both old and collision-cycle fetches", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      train(dut, cd)
      val tr = trace(dut, cd)
      val feeds = ArrayBuffer.empty[Long]
      cd.onSamplings {
        if (dut.probe.logic.feedOut.valid.toBoolean && dut.probe.logic.feedOut.ready.toBoolean)
          feeds += dut.probe.logic.feedOut.payload(0).pc.toLong
      }
      dut.probe.logic.feedOut.ready #= true
      dut.fetch.logic.cmdOut.ready #= true
      redirect(dut, cd)
      await(cd, 8, "source command") { tr.cmds.nonEmpty }

      // Ready-high makes the physically selected FTB target T a real AXI command in the
      // redirect cycle. The old W record needs the redirect's blanket stale write; the
      // newborn T record is also born stale. The following recovery command is the only
      // live record. Unique responses below prove both closures rather than merely
      // observing that the FTQ was reset.
      val recovery = 0x3800L
      dut.internalRedirect.logic.valid #= true
      dut.internalRedirect.logic.payload #= recovery
      cd.waitSampling()
      dut.internalRedirect.logic.valid #= false
      await(cd, 8, "collision target and recovery commands") { tr.cmds.size >= 3 }
      dut.fetch.logic.cmdOut.ready #= false
      assert(tr.cmds.take(3).map(_._2).toSeq == Seq(W, T, recovery),
        s"ready-high collision did not issue W/T/recovery exactly: ${tr.cmds.take(3)}")
      assert(tr.applies == 1 && tr.pushes == 1 && dut.fa.logic.ftqCount.toInt == 0,
        s"internal collision bookkeeping leaked: ${tr.applies}/${tr.pushes}, " +
          s"ftq=${dut.fa.logic.ftqCount.toInt}")

      // Make the old W response a fault: stale handling must suppress both its bytes and
      // the synthetic fault packet. T carries different normal bytes. Neither may feed.
      driveRsp(dut, cd, W, Seq(0x4afc, 0x4afc, 0x4afc, 0x4afc), fault = true, atc = true)
      dut.fetch.logic.rspIn.valid #= false
      cd.waitSampling(2)
      driveRsp(dut, cd, T, Seq(0x7001, 0x7202, 0x7403, 0x7604))
      dut.fetch.logic.rspIn.valid #= false
      cd.waitSampling(2)
      assert(feeds.isEmpty, s"stale W/T response reached decode: $feeds")

      driveRsp(dut, cd, recovery, Seq(0x7005, 0x7206, 0x7407, 0x7600))
      dut.fetch.logic.rspIn.valid #= false
      await(cd, 8, "live recovery feed") { feeds.nonEmpty }
      assert(feeds.head == recovery,
        f"first live feed was 0x${feeds.head}%x, expected recovery 0x$recovery%x")
      assert(!feeds.exists(pc => pc == W || pc == T),
        s"wrong-path W/T bytes survived the internal redirect: $feeds")
    }
  }

  test("an unaligned target carries its leading-word drop on the C+1 command", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      val unalignedTarget = 0x1046L
      train(dut, cd, target = unalignedTarget)
      val tr = trace(dut, cd)
      dut.fetch.logic.cmdOut.ready #= true
      redirect(dut, cd)
      await(cd, 12, "source and unaligned-target commands") { tr.cmds.size >= 2 }
      dut.fetch.logic.cmdOut.ready #= false
      cd.waitSampling()

      assert(tr.cmds.take(2).map(_._2).toSeq == Seq(W, unalignedTarget & ~7L),
        s"unaligned target command path was ${tr.cmds.take(2)}")
      assert(tr.cmds(1)._1 == tr.cmds(0)._1 + 1,
        s"unaligned target command was not C+1: ${tr.cmds.take(2)}")
      assert(dut.fa.logic.ringDrop(1).toInt == 3,
        s"same-cycle target drop was ${dut.fa.logic.ringDrop(1).toInt}, expected 3")
      assert(tr.applies == 1 && tr.pushes == 1,
        s"unaligned application replayed: ${tr.applies}/${tr.pushes}")
    }
  }

  test("a redirect kills a target held under cache backpressure", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      train(dut, cd)
      val tr = trace(dut, cd)
      dut.fetch.logic.cmdOut.ready #= true
      redirect(dut, cd)
      await(cd, 8, "source command") { tr.cmds.nonEmpty }
      dut.fetch.logic.cmdOut.ready #= false
      await(cd, 8, "held target") { dut.fa.logic.targetHoldValid.toBoolean }

      val recovery = 0x3000L
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= recovery
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false
      cd.waitSampling()
      assert(!dut.fa.logic.targetHoldValid.toBoolean, "redirect left the target hold live")
      assert(dut.fa.logic.ftqCount.toInt == 0, "redirect left a held plan in the FTQ")

      dut.fetch.logic.cmdOut.ready #= true
      await(cd, 8, "held-target redirect recovery command") { tr.cmds.size >= 2 }
      dut.fetch.logic.cmdOut.ready #= false
      assert(tr.cmds.take(2).map(_._2).toSeq == Seq(W, recovery),
        s"held target survived redirect: ${tr.cmds.take(2)}")
      assert(tr.applies == 1 && tr.pushes == 1,
        s"held plan must have applied exactly once before it was killed: ${tr.applies}/${tr.pushes}")
    }
  }

  test("an older decode prediction kills a coincident physical FTB application", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      val olderWindow = 0x4000L
      val youngerWindow = olderWindow + 8
      val olderTarget = 0x5000L
      val youngerTarget = 0x6000L

      // Keep the older branch in the per-instruction BTB, but replace its window-level
      // FTB entry with a not-taken conditional in word 1. The older-window registered
      // FTB lookup therefore declines while decode still predicts word 0 taken. The
      // next sequential window has the live FTB plan whose result we collide with it.
      train(dut, cd, target = olderTarget, pc = olderWindow)
      train(dut, cd, brType = 0, target = olderWindow + 0x100, pc = olderWindow + 2)
      trainNotTaken(dut, cd, olderWindow + 2)
      train(dut, cd, target = youngerTarget, pc = youngerWindow)

      val tr = trace(dut, cd)
      var collisions = 0
      var detectCycle = -1
      var actionCycle = -1
      var actionFeedFires = 0
      var localCycle = 0
      val collisionTrace = ArrayBuffer.empty[String]
      cd.onSamplings {
        val apply = dut.fa.logic.applyNow.toBoolean
        val detect = dut.fa.logic.predictDetect.toBoolean
        val action = dut.fa.logic.predictFire.toBoolean
        val cmdFire = dut.fetch.logic.cmdOut.valid.toBoolean && dut.fetch.logic.cmdOut.ready.toBoolean
        val feedFire = dut.probe.logic.feedOut.valid.toBoolean && dut.probe.logic.feedOut.ready.toBoolean
        if (apply || detect || action || cmdFire || feedFire)
          collisionTrace += s"c=$localCycle apply=$apply detect=$detect action=$action " +
            s"cmd=${if (cmdFire) f"0x${dut.fetch.logic.cmdOut.payload.pc.toLong}%x" else "-"} " +
            s"feed=${if (feedFire) f"0x${dut.probe.logic.feedOut.payload(0).pc.toLong}%x" else "-"}"
        if (apply && detect) {
          collisions += 1
          detectCycle = localCycle
        }
        if (action) {
          actionCycle = localCycle
          if (feedFire) actionFeedFires += 1
        }
        localCycle += 1
      }
      dut.probe.logic.feedOut.ready #= true
      dut.fetch.logic.cmdOut.ready #= true
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= olderWindow
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false
      await(cd, 8, "older-window command") { tr.cmds.nonEmpty }

      // Land the older window and turn the freed ring slot directly into the younger
      // command. The push invalidates p0Live at this edge; its cmd+1 result then
      // coincides with the older branch's decode-time detector one cycle later. The
      // registered action must kill that physical application at C+1.
      dut.fetch.logic.cmdOut.ready #= true
      driveRsp(dut, cd, olderWindow, Seq(0x6006, 0x7001, 0x7202, 0x7403))
      dut.fetch.logic.rspIn.valid #= false
      assert(tr.cmds.size >= 2 && tr.cmds(1)._2 == youngerWindow,
        s"younger lookup was not launched on response turnover: ${tr.cmds}")
      dut.fetch.logic.cmdOut.ready #= false

      var collisionWait = 0
      while (collisions != 1 && collisionWait < 8) {
        cd.waitSampling()
        collisionWait += 1
      }
      assert(collisions == 1,
        s"decode-local FTB collision absent after 8 cycles: ${collisionTrace.mkString("; ")}")
      cd.waitSampling()
      assert(actionCycle == detectCycle + 1,
        s"fallback detector/action was not exactly C/C+1: ${collisionTrace.mkString("; ")}")
      assert(actionFeedFires == 0,
        s"wrong-path feed escaped in fallback action: ${collisionTrace.mkString("; ")}")
      assert(tr.applies == 1 && tr.pushes == 1,
        s"the physical application was not exercised exactly once: ${tr.applies}/${tr.pushes}")
      // The action is observed before its active edge by the sampling callback. Cross
      // that edge before checking the registered FTQ/hold state it clears.
      cd.waitSampling()
      assert(dut.fa.logic.ftqCount.toInt == 0,
        "decode-local redirect did not kill the coincident FTQ push")
      assert(!dut.fa.logic.targetHoldValid.toBoolean,
        "decode-local redirect left the coincident target hold live")

      dut.fetch.logic.cmdOut.ready #= true
      await(cd, 8, "older prediction recovery command") { tr.cmds.size >= 3 }
      dut.fetch.logic.cmdOut.ready #= false
      assert(tr.cmds(2)._2 == olderTarget,
        f"younger FTB target survived: expected older target 0x$olderTarget%x, got 0x${tr.cmds(2)._2}%x")
      assert(!tr.cmds.exists(_._2 == youngerTarget),
        s"killed younger target escaped as a live command: ${tr.cmds}")
    }
  }

  test("registered decode fallback keeps its target command at C+1", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      val source = 0x7000L
      val target = 0x7046L
      val targetBase = target & ~7L

      // Retain a taken word-0 entry in the instruction BTB while replacing the
      // window-level FTB claim with a declined word-1 conditional. This forces the
      // real decode fallback without disabling the fetch-directed machinery.
      train(dut, cd, target = target, pc = source)
      train(dut, cd, brType = 0, target = source + 0x100, pc = source + 2)
      trainNotTaken(dut, cd, source + 2)

      val tr = trace(dut, cd)
      val feeds = ArrayBuffer.empty[(Long, Long)]
      var localCycle = 0L
      var detectCycle = -1L
      var actionCycle = -1L
      var targetCmdCycle = -1L
      var actionCount = 0
      var actionFeedFires = 0
      cd.onSamplings {
        val detect = dut.fa.logic.predictDetect.toBoolean
        val action = dut.fa.logic.predictFire.toBoolean
        val cmdFire = dut.fetch.logic.cmdOut.valid.toBoolean && dut.fetch.logic.cmdOut.ready.toBoolean
        val feedFire = dut.probe.logic.feedOut.valid.toBoolean && dut.probe.logic.feedOut.ready.toBoolean
        if (detect && detectCycle < 0) detectCycle = localCycle
        if (action) {
          actionCycle = localCycle
          actionCount += 1
          if (feedFire) actionFeedFires += 1
        }
        if (cmdFire && dut.fetch.logic.cmdOut.payload.pc.toLong == targetBase)
          targetCmdCycle = localCycle
        if (feedFire)
          feeds += localCycle -> dut.probe.logic.feedOut.payload(0).pc.toLong
        localCycle += 1
      }

      dut.probe.logic.feedOut.ready #= true
      dut.fetch.logic.cmdOut.ready #= true
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= source
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false
      await(cd, 8, "fallback source command") { tr.cmds.exists(_._2 == source) }
      dut.fetch.logic.cmdOut.ready #= false

      // BRA.S is learned taken by the instruction BTB. The word-1 FTB conditional is
      // trained not-taken, so no fetch-directed target is applied.
      driveRsp(dut, cd, source, Seq(0x6006, 0x7001, 0x7202, 0x7403))
      dut.fetch.logic.rspIn.valid #= false
      await(cd, 10, "live decode fallback detector") { detectCycle >= 0 }

      // The registered action is next cycle. Ready is raised before its edge so the
      // target command must fire in that same C+1 action, not a cycle later.
      dut.fetch.logic.cmdOut.ready #= true
      await(cd, 4, "registered fallback action and target command") {
        actionCount == 1 && targetCmdCycle >= 0
      }
      dut.fetch.logic.cmdOut.ready #= false

      assert(actionCycle == detectCycle + 1,
        s"fallback action was not C+1: detect=$detectCycle action=$actionCycle cmds=${tr.cmds}")
      assert(targetCmdCycle == actionCycle,
        s"fallback target command missed the action cycle: target=$targetCmdCycle action=$actionCycle")
      assert(actionCount == 1, s"fallback action replayed $actionCount times")
      assert(actionFeedFires == 0, "wrong-path packet fired during fallback action")
      assert(tr.applies == 0 && tr.pushes == 0,
        s"declined FTB result unexpectedly applied: ${tr.applies}/${tr.pushes}")

      // The unaligned target's new ring record must be live and carry drop=3. If the
      // blanket stale action accidentally kills it, this response never reaches feed.
      cd.waitSampling(2)
      driveRsp(dut, cd, targetBase, Seq(0x70ee, 0x72ee, 0x74ee, 0x7603))
      dut.fetch.logic.rspIn.valid #= false
      await(cd, 10, "live fallback target feed") { feeds.exists(_._2 == target) }
      assert(!feeds.exists { case (c, _) => c == actionCycle },
        s"action-cycle feed was not blocked: $feeds")
    }
  }

  test("registered decode fallback holds one exact target through cache backpressure", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      val source = 0x7800L
      val target = 0x7846L
      val targetBase = target & ~7L

      train(dut, cd, target = target, pc = source)
      train(dut, cd, brType = 0, target = source + 0x100, pc = source + 2)
      trainNotTaken(dut, cd, source + 2)

      val tr = trace(dut, cd)
      val feeds = ArrayBuffer.empty[Long]
      val heldTargets = ArrayBuffer.empty[Long]
      var actionCount = 0
      var actionFeedFires = 0
      var targetFires = 0
      cd.onSamplings {
        val action = dut.fa.logic.predictFire.toBoolean
        val cmdValid = dut.fetch.logic.cmdOut.valid.toBoolean
        val cmdReady = dut.fetch.logic.cmdOut.ready.toBoolean
        val cmdPc = dut.fetch.logic.cmdOut.payload.pc.toLong
        val feedFire = dut.probe.logic.feedOut.valid.toBoolean && dut.probe.logic.feedOut.ready.toBoolean
        if (action) {
          actionCount += 1
          if (feedFire) actionFeedFires += 1
        }
        if (cmdValid && !cmdReady && cmdPc == targetBase)
          heldTargets += cmdPc
        if (cmdValid && cmdReady && cmdPc == targetBase)
          targetFires += 1
        if (feedFire)
          feeds += dut.probe.logic.feedOut.payload(0).pc.toLong
      }

      dut.probe.logic.feedOut.ready #= true
      dut.fetch.logic.cmdOut.ready #= true
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= source
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false
      await(cd, 8, "backpressured fallback source command") { tr.cmds.exists(_._2 == source) }

      // Hold the cache command boundary closed before the branch can be decoded. The
      // action itself and several following cycles must present the same aligned target.
      dut.fetch.logic.cmdOut.ready #= false
      driveRsp(dut, cd, source, Seq(0x6006, 0x7001, 0x7202, 0x7403))
      dut.fetch.logic.rspIn.valid #= false
      await(cd, 10, "backpressured fallback action") { actionCount == 1 }
      cd.waitSampling(3)
      assert(actionFeedFires == 0, "wrong-path feed escaped during fallback action")
      assert(heldTargets.size >= 3 && heldTargets.forall(_ == targetBase),
        s"fallback target was not held stable under backpressure: $heldTargets")
      assert(targetFires == 0, s"backpressured fallback target fired early $targetFires times")

      dut.fetch.logic.cmdOut.ready #= true
      await(cd, 4, "released fallback target command") { targetFires == 1 }
      dut.fetch.logic.cmdOut.ready #= false
      cd.waitSampling(2)
      assert(actionCount == 1, s"fallback action replayed $actionCount times")
      assert(targetFires == 1, s"fallback target fired $targetFires times")
      assert(tr.cmds.count(_._2 == targetBase) == 1,
        s"fallback target command duplicated: ${tr.cmds}")

      // Response consumption proves the retained drop=3 travelled with the eventual
      // command; a lost drop would emit one of the three 0x??ee words before 0x7846.
      driveRsp(dut, cd, targetBase, Seq(0x70ee, 0x72ee, 0x74ee, 0x7603))
      dut.fetch.logic.rspIn.valid #= false
      await(cd, 10, "backpressured fallback target feed") { feeds.contains(target) }
      assert(!feeds.exists(pc => pc >= targetBase && pc < target),
        s"fallback leading-word drop was lost: $feeds")
    }
  }

  test("registered not-taken direction declines without truncating or redirecting", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      train(dut, cd, brType = 0)
      trainNotTaken(dut, cd, B)
      val tr = trace(dut, cd)
      dut.fetch.logic.cmdOut.ready #= true
      redirect(dut, cd)
      await(cd, 12, "two sequential commands after not-taken decline") { tr.cmds.size >= 2 }
      dut.fetch.logic.cmdOut.ready #= false
      cd.waitSampling(2)

      assert(tr.cmds.take(2).map(_._2).toSeq == Seq(W, W + 8),
        s"not-taken FTB entry redirected fetch: ${tr.cmds.take(2)}")
      assert(tr.applies == 0 && tr.pushes == 0 && dut.fa.logic.ftqCount.toInt == 0,
        s"declined direction mutated state: ${tr.applies}/${tr.pushes}/${dut.fa.logic.ftqCount.toInt}")
      assert(dut.fa.logic.ringKeep(0).toInt == 4, "declined entry truncated its source window")
    }
  }

  test("exact branch confirmation reuses buffered target bytes without a refetch flush", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      train(dut, cd)
      val tr = trace(dut, cd)
      dut.fetch.logic.cmdOut.ready #= true
      redirect(dut, cd)
      await(cd, 12, "source and target commands") { tr.cmds.size >= 2 }
      dut.fetch.logic.cmdOut.ready #= false

      // W contains MOVEQ then BRA.S to T. Words after the branch are deliberately
      // recognizable fall-through poison; ringKeep must prevent them entering the IBuf.
      driveRsp(dut, cd, W, Seq(0x7001, 0x601e, 0x72ee, 0x74ee))
      driveRsp(dut, cd, T, Seq(0x7603, 0x7804, 0x7a05, 0x7c06))
      dut.fetch.logic.rspIn.valid #= false
      cd.waitSampling(2)

      val packets = ArrayBuffer.empty[(Long, Int, Boolean, Long)]
      cd.onSamplings {
        if (dut.probe.logic.feedOut.valid.toBoolean && dut.probe.logic.feedOut.ready.toBoolean)
          packets += ((dut.probe.logic.feedOut.payload(0).pc.toLong,
            dut.probe.logic.feedOut.payload(0).words(0).toInt,
            dut.probe.logic.feedOut.payload(0).predTaken.toBoolean,
            dut.probe.logic.feedOut.payload(0).predTarget.toLong))
      }
      dut.probe.logic.feedOut.ready #= true
      await(cd, 20, "source, branch, and target packets") { packets.size >= 3 }
      dut.probe.logic.feedOut.ready #= false

      assert(packets.take(3).map(_._1).toSeq == Seq(W, B, T),
        s"decode did not splice directly to target: ${packets.take(3)}")
      assert(packets(0)._2 == 0x7001 && packets(1)._2 == 0x601e && packets(2)._2 == 0x7603)
      assert(packets(1)._3 && packets(1)._4 == T,
        s"confirmed branch stamp wrong: ${packets(1)}")
      assert(!packets.exists(p => p._2 == 0x72ee || p._2 == 0x74ee),
        s"post-branch poison escaped truncation: $packets")
      assert(tr.applies == 1 && tr.pushes == 1 && tr.confirms == 1,
        s"event counts apply/push/confirm=${tr.applies}/${tr.pushes}/${tr.confirms}")
      assert(dut.fa.logic.ftqCount.toInt == 0, "confirmation must pop exactly one FTQ entry")
      assert(tr.cmds.take(2).map(_._2).toSeq == Seq(W, T) && !tr.cmds.exists(_._2 == W + 8))
    }
  }

  test("a too-short learned branch length stalls before target bytes and recovers once", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); idle(dut); cd.waitSampling(3)
      train(dut, cd) // learned claim is one word
      val tr = trace(dut, cd)
      var mismatchCount = 0
      var sampleCycle = 0
      val mismatchDetectCycles = ArrayBuffer.empty[Int]
      val mismatchActionCycles = ArrayBuffer.empty[Int]
      val feedFireCycles = ArrayBuffer.empty[Int]
      val cmdFireCycles = ArrayBuffer.empty[Int]
      cd.onSamplings {
        if (dut.fa.logic.ftqMismatchDetect.toBoolean)
          mismatchDetectCycles += sampleCycle
        if (dut.fa.logic.ftqMismatch.toBoolean) {
          mismatchCount += 1
          mismatchActionCycles += sampleCycle
        }
        if (dut.probe.logic.feedOut.valid.toBoolean && dut.probe.logic.feedOut.ready.toBoolean)
          feedFireCycles += sampleCycle
        if (dut.fetch.logic.cmdOut.valid.toBoolean && dut.fetch.logic.cmdOut.ready.toBoolean)
          cmdFireCycles += sampleCycle
        sampleCycle += 1
      }

      dut.fetch.logic.cmdOut.ready #= true
      redirect(dut, cd)
      await(cd, 12, "mismatch source and target commands") { tr.cmds.size >= 2 }
      dut.fetch.logic.cmdOut.ready #= false

      // The real instruction at B is two words, but ringKeep admits only its opword.
      // The next buffered word is target data. Correct behavior is a dwell stall and
      // mismatch; B must not emit from this malformed splice.
      driveRsp(dut, cd, W, Seq(0x7001, 0x6000, 0x003c, 0x72ee), lens = Seq(1, 2, 1, 1))
      driveRsp(dut, cd, T, Seq(0x7603, 0x7804, 0x7a05, 0x7c06))
      dut.fetch.logic.rspIn.valid #= false
      cd.waitSampling(2)

      val emittedBeforeRecovery = ArrayBuffer.empty[Long]
      cd.onSamplings {
        if (dut.probe.logic.feedOut.valid.toBoolean && dut.probe.logic.feedOut.ready.toBoolean)
          emittedBeforeRecovery += dut.probe.logic.feedOut.payload(0).pc.toLong
      }
      dut.probe.logic.feedOut.ready #= true
      await(cd, 20, "framing mismatch") { mismatchCount == 1 }
      cd.waitSampling() // observe the mismatch edge's registered flush/clear effects
      dut.probe.logic.feedOut.ready #= false
      assert(emittedBeforeRecovery.toSeq == Seq(W),
        s"malformed branch/target bytes emitted before recovery: $emittedBeforeRecovery")
      assert(dut.fa.logic.ftqCount.toInt == 0, "mismatch must flush the FTQ")
      assert(dut.fa.logic.ftbSuppress.toBoolean, "mismatch must arm one-shot suppression")
      assert(mismatchDetectCycles.size == 1 && mismatchActionCycles.size == 1,
        s"mismatch detector/action did not each pulse exactly once: " +
          s"detect=$mismatchDetectCycles action=$mismatchActionCycles")
      assert(mismatchActionCycles.head == mismatchDetectCycles.head + 1,
        s"mismatch recovery was not registered C->C+1: " +
          s"detect=$mismatchDetectCycles action=$mismatchActionCycles")
      assert(!feedFireCycles.contains(mismatchActionCycles.head),
        s"decode feed escaped on mismatch action cycle ${mismatchActionCycles.head}")
      assert(!cmdFireCycles.contains(mismatchActionCycles.head),
        s"I-cache command escaped on mismatch action cycle ${mismatchActionCycles.head}")
      val ftbIdx = ((W >> 3) & 127).toInt
      assert(!dut.ftb.logic.valids(ftbIdx).toBoolean, "mismatch must clear the exact FTB entry")

      // Refetch from B's containing window with the complete real instruction. The
      // cleared entry/suppress bit guarantees exactly today's untruncated framing.
      dut.fetch.logic.cmdOut.ready #= true
      await(cd, 10, "sequential recovery fetch") { tr.cmds.size >= 3 }
      dut.fetch.logic.cmdOut.ready #= false
      assert(tr.cmds(2)._2 == W,
        f"recovery command must refetch B's aligned window 0x$W%x, got 0x${tr.cmds(2)._2}%x")
      driveRsp(dut, cd, W, Seq(0x7001, 0x6000, 0x003c, 0x72ee), lens = Seq(1, 2, 1, 1))
      dut.fetch.logic.rspIn.valid #= false
      dut.probe.logic.feedOut.ready #= true
      await(cd, 20, "recovered real branch") {
        dut.probe.logic.feedOut.valid.toBoolean &&
          dut.probe.logic.feedOut.payload(0).pc.toLong == B
      }
      assert(dut.probe.logic.feedOut.payload(0).lenWords.toInt == 2,
        "recovery did not restore the real branch framing")
      cd.waitSampling()
      assert(mismatchCount == 1, s"mismatch replayed $mismatchCount times")
      assert(!dut.fa.logic.ftbSuppress.toBoolean, "normal forward progress must clear suppression")
    }
  }
}
