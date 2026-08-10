package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.services.{BtbUpdate, BtbUpdateService, FtbLookupService}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Registered-token FTB contract. Every assertion is tied to an update/query event;
  * changing the lookup to live-PC freshness or dropping the token register fails the
  * dense query section immediately. */
class FtbSpec extends AnyFunSuite {
  class UpdateDriver extends FiberPlugin with BtbUpdateService {
    val logic = during build new Area {
      val upd = Flow(BtbUpdate())
      in(upd.valid)
      upd.payload.flatten.foreach(in(_))
    }
    override def btbUpdate: Flow[BtbUpdate] = logic.upd
  }

  class FtbWire extends FiberPlugin {
    val logic = during build new Area {
      val ftb = host[FtbPlugin]
      val svc = host[FtbLookupService]
      val qValid = in Bool()
      val qPc = in UInt(32 bits)
      val qSlot = in UInt(2 bits)
      val qSeq = in UInt(8 bits)
      val qDrop = in UInt(2 bits)
      svc.lookupCmd.valid := qValid
      svc.lookupCmd.payload.windowPc := qPc
      svc.lookupCmd.payload.drop := qDrop
      svc.lookupCmd.payload.token.ringSlot := qSlot
      svc.lookupCmd.payload.token.seq := qSeq

      val clearValid = in Bool()
      val clearPc = in UInt(32 bits)
      svc.clearOne.valid := clearValid
      svc.clearOne.payload := clearPc
      val invalidate = in Bool()
      ftb.logic.invalidateAll := invalidate

      val rspValid = out Bool(); rspValid := svc.lookupRsp.valid
      val rspPc = out UInt(32 bits); rspPc := svc.lookupRsp.payload.windowPc
      val rspSlot = out UInt(2 bits); rspSlot := svc.lookupRsp.payload.token.ringSlot
      val rspSeq = out UInt(8 bits); rspSeq := svc.lookupRsp.payload.token.seq
      val rspHit = out Bool(); rspHit := svc.lookupRsp.payload.hit
      val rspOff = out UInt(2 bits); rspOff := svc.lookupRsp.payload.brWordOff
      val rspLen = out UInt(4 bits); rspLen := svc.lookupRsp.payload.brLen
      val rspTarget = out UInt(32 bits); rspTarget := svc.lookupRsp.payload.target
      val rspType = out UInt(2 bits); rspType := svc.lookupRsp.payload.brType
      val rspFramed = out Bool(); rspFramed := svc.lookupRsp.payload.framedOk
    }
  }

  class Dut extends Component {
    val db = new Database
    val host = db on new PluginHost
    val drv = new UpdateDriver
    val ftb = new FtbPlugin(entries = 128)
    val wire = new FtbWire
    db.on { host.asHostOf(Seq[FiberPlugin](drv, ftb, wire)) }
  }

  case class Expected(pc: Long, slot: Int, seq: Int, hit: Boolean,
                      off: Int = 0, len: Int = 0, target: Long = 0, brType: Int = 0)

  def init(dut: Dut, cd: ClockDomain): Unit = {
    dut.drv.logic.upd.valid #= false
    dut.wire.logic.qValid #= false
    dut.wire.logic.qPc #= 0
    dut.wire.logic.qSlot #= 0
    dut.wire.logic.qSeq #= 0
    dut.wire.logic.qDrop #= 0
    dut.wire.logic.clearValid #= false
    dut.wire.logic.clearPc #= 0
    dut.wire.logic.invalidate #= false
    cd.waitSampling(2)
  }

  def update(dut: Dut, cd: ClockDomain, pc: Long, len: Int, target: Long,
             taken: Boolean = true, brType: Int = 0,
             invalidate: Boolean = false): Unit = {
    val u = dut.drv.logic.upd
    u.valid #= true
    u.payload.pc #= pc
    u.payload.taken #= taken
    u.payload.target #= target
    u.payload.brType #= brType
    u.payload.len #= len
    dut.wire.logic.invalidate #= invalidate
    cd.waitSampling()
    u.valid #= false
    dut.wire.logic.invalidate #= false
    cd.waitSampling()
  }

  def lookup(dut: Dut, cd: ClockDomain, pc: Long, slot: Int = 0, seq: Int = 0): Expected = {
    val w = dut.wire.logic
    w.qValid #= true; w.qPc #= pc; w.qSlot #= slot; w.qSeq #= seq
    cd.waitSampling()
    sleep(1)
    assert(w.rspValid.toBoolean, "one lookup command must yield one cmd+1 result")
    val got = Expected(w.rspPc.toLong, w.rspSlot.toInt, w.rspSeq.toInt,
      w.rspHit.toBoolean, w.rspOff.toInt, w.rspLen.toInt,
      w.rspTarget.toLong, w.rspType.toInt)
    w.qValid #= false
    cd.waitSampling()
    got
  }

  test("eight changing windows return eight exact next-cycle tokens and payloads", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)

      val pcs = (0 until 8).map(i => 0x1000L + i * 8L)
      // Four hits include a learned not-taken conditional; hit is tag presence while
      // gshare supplies direction later. Odd windows deliberately remain misses.
      for (i <- pcs.indices if (i & 1) == 0)
        update(dut, cd, pcs(i) + (i & 3) * 2L, len = 1,
          target = 0x8000L + i * 4L, taken = i != 2, brType = if (i == 6) 1 else 0)

      val w = dut.wire.logic
      var commands = 0
      var responses = 0
      for (i <- pcs.indices) {
        w.qValid #= true
        w.qPc #= pcs(i)
        w.qSlot #= (i % 3)
        w.qSeq #= (0x40 + i)
        cd.waitSampling()
        sleep(1)
        commands += 1
        assert(w.rspValid.toBoolean, s"missing dense response $i")
        assert(w.rspPc.toLong == pcs(i), s"response $i used a live/changing PC")
        assert(w.rspSlot.toInt == i % 3 && w.rspSeq.toInt == 0x40 + i,
          s"response $i token association changed")
        val shouldHit = (i & 1) == 0
        assert(w.rspHit.toBoolean == shouldHit, s"response $i hit mismatch")
        if (shouldHit) {
          assert(w.rspOff.toInt == (i & 3))
          assert(w.rspLen.toInt == 1)
          assert(w.rspTarget.toLong == 0x8000L + i * 4L)
          assert(w.rspType.toInt == (if (i == 6) 1 else 0))
        }
        responses += 1
      }
      w.qValid #= false
      cd.waitSampling()
      sleep(1)
      assert(!w.rspValid.toBoolean, "dense Flow must end after exactly eight responses")
      assert(commands == 8 && responses == 8)
    }
  }

  test("install bounds, collisions, exact clear, and invalidate priority are fail-closed", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)

      // Starts at word three and spans two words: not wholly in this window.
      update(dut, cd, 0x2006, len = 2, target = 0x3000)
      assert(!lookup(dut, cd, 0x2000).hit, "cross-window branch must not install")

      update(dut, cd, 0x2102, len = 2, target = 0x3100)
      val warm = lookup(dut, cd, 0x2100)
      assert(warm.hit && warm.off == 1 && warm.len == 2 && warm.target == 0x3100)

      // Same direct-map index, different tag: newer window replaces the old one.
      val colliding = 0x2500L // +0x400 retains PC[9:3]
      update(dut, cd, colliding + 4, len = 1, target = 0x3500, brType = 1)
      assert(!lookup(dut, cd, 0x2100).hit, "old colliding tag must miss")
      assert(lookup(dut, cd, colliding).hit, "replacement tag must hit")

      // Wrong word in the same window cannot clear the learned branch.
      dut.wire.logic.clearValid #= true; dut.wire.logic.clearPc #= colliding + 2
      cd.waitSampling(); dut.wire.logic.clearValid #= false; cd.waitSampling()
      assert(lookup(dut, cd, colliding).hit, "clear-one must match branch word offset")
      dut.wire.logic.clearValid #= true; dut.wire.logic.clearPc #= colliding + 4
      cd.waitSampling(); dut.wire.logic.clearValid #= false; cd.waitSampling()
      assert(!lookup(dut, cd, colliding).hit, "exact mismatch clear must invalidate one entry")

      // Same-cycle retire update cannot resurrect an architecturally invalidated FTB.
      update(dut, cd, 0x2800, len = 1, target = 0x3800, invalidate = true)
      assert(!lookup(dut, cd, 0x2800).hit, "invalidate must win update collision")
    }
  }

  /** Amendment §2.1.1 / §9 item 8a. The registered `framedOk` must equal
    * `hit && brLen != 0 && brWordOff + brLen <= 4 && brWordOff >= drop` for the drop the
    * COMMAND carried, over the full offset/length/drop matrix. The FMax cut in
    * `FetchAlignPlugin` consumes this single bit instead of re-deriving the same
    * conjunction from the fetch ring one cycle later, so a wrong verdict here would
    * silently apply or silently decline a prediction. */
  test("framedOk is the exact hit/in-window/at-or-after-drop verdict for the command drop",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      val w = dut.wire.logic

      // Distinct windows so no two learned entries share a direct-map index: the index
      // is pc[9:3], so stepping the window base by 0x8 gives each case its own entry.
      // Only in-window branches are installable at all (that bound is the parent
      // contract), so the length axis is covered by the installable set plus a
      // deliberate non-installable overshoot which must read back as a miss.
      case class Case(base: Long, off: Int, len: Int, installs: Boolean)
      val cases = Seq(
        Case(0x4000, 0, 1, installs = true),
        Case(0x4008, 1, 1, installs = true),
        Case(0x4010, 2, 1, installs = true),
        Case(0x4018, 3, 1, installs = true),
        Case(0x4020, 0, 4, installs = true),   // exactly fills the window
        Case(0x4028, 1, 3, installs = true),   // ends exactly at the window end
        Case(0x4030, 2, 3, installs = false),  // overshoots: 2+3 > 4, never installed
        Case(0x4038, 3, 2, installs = false))  // overshoots: 3+2 > 4, never installed

      for (c <- cases) update(dut, cd, c.base + c.off * 2L, len = c.len, target = 0x9000)

      var trueVerdicts = 0
      var falseVerdicts = 0
      var checked = 0
      for (c <- cases; drop <- 0 to 3) {
        w.qValid #= true; w.qPc #= c.base; w.qSlot #= 0; w.qSeq #= 0; w.qDrop #= drop
        cd.waitSampling(); sleep(1)
        assert(w.rspValid.toBoolean, "one lookup command must yield one cmd+1 result")
        val hit = w.rspHit.toBoolean
        assert(hit == c.installs,
          s"window 0x${c.base.toHexString} off=${c.off} len=${c.len}: hit=$hit, " +
          s"expected ${c.installs}")
        val expected = c.installs && c.len != 0 && (c.off + c.len) <= 4 && c.off >= drop
        val got = w.rspFramed.toBoolean
        assert(got == expected,
          s"window 0x${c.base.toHexString} off=${c.off} len=${c.len} drop=$drop: " +
          s"framedOk=$got, expected $expected")
        // The payload the application cycle would have used must still be intact: the
        // cut removes the recomputation, not the fields.
        if (hit) {
          assert(w.rspOff.toInt == c.off && w.rspLen.toInt == c.len,
            "framing verdict must not disturb the returned offset/length")
        }
        if (got) trueVerdicts += 1 else falseVerdicts += 1
        checked += 1
        w.qValid #= false
        cd.waitSampling()
      }

      assert(checked == cases.size * 4, s"expected ${cases.size * 4} verdicts, got $checked")
      // Non-vacuity: the matrix must genuinely exercise both verdicts, and specifically
      // must contain at least one case that is a real FTB hit, wholly in its window, and
      // still declined purely because the leading-word drop skipped past the branch.
      assert(trueVerdicts > 0 && falseVerdicts > 0,
        s"framing matrix is vacuous: $trueVerdicts true / $falseVerdicts false")
      val dropOnlyDeclines = cases.filter(_.installs).flatMap(c =>
        (0 to 3).map(d => (c, d))).count { case (c, d) => c.off < d }
      assert(dropOnlyDeclines > 0,
        "matrix contains no drop-only decline; the drop axis would be untested")
    }
  }
}
