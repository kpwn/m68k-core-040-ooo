package m68k040.frontend

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.services.{GshareUpdate, GshareWindowService}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Directed GsharePlugin unit test (direction predictor, slice 3).
  *  - the folded XOR index = fold(pc[31:1]) ^ fold(ghr) (computed in Scala, checked vs HW);
  *  - 2-bit counter saturate ±1 (weakly-taken init 2; trains up to 3 / down to 0);
  *  - GHR shift (shiftValid/shiftDir shifts a directional bit in);
  *  - learns-the-alternation: an alternating outcome at ONE PC across TWO GHR contexts
  *    converges the two PHT entries to OPPOSITE directions (the branchy win). */
class GshareSpec extends AnyFunSuite {

  val ghrBits = 16
  // Width follows CPU_GSHARE_ENTRIES (Global.PHT_IDX_W); 2048/11 by default.
  val idxBits = m68k040.Global.PHT_IDX_W
  val phtEntries = 1 << idxBits
  val idxHistBits = scala.math.min(ghrBits, idxBits)

  /** Scala model of the plugin's fold (XOR idxBits-wide chunks of a width-w value). */
  def fold(v: Long, w: Int): Int = {
    var acc = 0
    var lo = 0
    while (lo < w) {
      val hi = scala.math.min(lo + idxBits - 1, w - 1)
      val nb = hi - lo + 1
      val chunk = ((v >> lo) & ((1L << nb) - 1)).toInt
      acc ^= chunk
      lo += idxBits
    }
    acc & ((1 << idxBits) - 1)
  }
  /** index(pc) = fold(pc[31:1]) ^ fold(ghr). */
  def idxOf(pc: Long, ghr: Int): Int = fold((pc >>> 1) & 0x7FFFFFFFL, 31) ^ fold(ghr.toLong & ((1L << idxHistBits) - 1), idxHistBits)

  /** Wiring plugin: hoists the GsharePlugin's plain-wire ports + the (plugin-owned)
    * update Flow to top IO inside a Fiber build (the BtbPlugin convention). The test
    * drives the GsharePlugin's OWN update Flow directly (it IS the GshareUpdateService
    * provider — in the real core the ROB drives it through the same Flow). */
  class GshareWirePlugin extends FiberPlugin {
    val logic = during build new Area {
      val g = host[GsharePlugin]
      val win = host[GshareWindowService]
      val qPc0   = in UInt (32 bits); val qV0 = in Bool ()
      val sV     = in Bool ();        val sD = in Bool ()
      val inval  = in Bool ()
      val uV     = in Bool ()
      val uIdx   = in UInt (idxBits bits); val uTaken = in Bool ()
      val wV     = in Bool ();        val wPc = in UInt (32 bits)
      val wSlot  = in UInt (2 bits);  val wSeq = in UInt (8 bits)
      val fRep   = in Bool ()
      g.logic.queryPc0    := qPc0
      g.logic.queryValid0 := qV0
      g.logic.shiftValid  := sV
      g.logic.shiftDir    := sD
      g.logic.invalidateAll := inval
      g.logic.flushRepair   := fRep
      // Drive the plugin's own update Flow (override its idle default).
      g.logic.upd.valid        := uV
      g.logic.upd.payload.index := uIdx
      g.logic.upd.payload.taken := uTaken
      win.windowCmd.valid := wV
      win.windowCmd.payload.windowPc := wPc
      win.windowCmd.payload.token.ringSlot := wSlot
      win.windowCmd.payload.token.seq := wSeq
      val oTaken = out(Bool());        oTaken := g.logic.phtTaken0
      val oIndex = out(UInt(idxBits bits)); oIndex := g.logic.phtIndex0
      val qPc1   = in UInt (32 bits);  g.logic.queryPc1 := qPc1
      val oTaken1 = out(Bool());        oTaken1 := g.logic.phtTaken1
      val oIndex1 = out(UInt(idxBits bits)); oIndex1 := g.logic.phtIndex1
      val oGhr   = out(UInt(16 bits)); oGhr   := g.logic.ghr.resized
      val oGhrArch = out(UInt(16 bits)); oGhrArch := g.logic.ghrArch.resized
      val wRspV = out(Bool()); wRspV := win.windowRsp.valid
      val wRspSlot = out(UInt(2 bits)); wRspSlot := win.windowRsp.payload.token.ringSlot
      val wRspSeq = out(UInt(8 bits)); wRspSeq := win.windowRsp.payload.token.seq
      val wRspTaken = out(Vec(Bool(), 4)); wRspTaken := win.windowRsp.payload.taken
      val wRspIdx = out(Vec(UInt(idxBits bits), 4)); wRspIdx := win.windowRsp.payload.phtIdx
    }
  }

  // The directed tests read the full 16-bit GHR, so they pin CPU_GHR_TRIM off; the
  // trim equivalence test at the end compares both.
  class GshareDut(interleaved: Boolean = m68k040.top.ShippingCoreConfig.gshareInterleaved,
                  ghrTrim: Boolean = false) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val gsh  = new GsharePlugin(interleaved = interleaved, ghrTrim = ghrTrim)
    val wire = new GshareWirePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), gsh, wire)) }
  }

  def init(dut: GshareDut, cd: ClockDomain): Unit = {
    dut.wire.logic.qPc0 #= 0; dut.wire.logic.qV0 #= false; dut.wire.logic.qPc1 #= 0
    dut.wire.logic.sV #= false; dut.wire.logic.sD #= false; dut.wire.logic.inval #= false
    dut.wire.logic.uV #= false; dut.wire.logic.uIdx #= 0; dut.wire.logic.uTaken #= false
    dut.wire.logic.wV #= false; dut.wire.logic.wPc #= 0
    dut.wire.logic.wSlot #= 0; dut.wire.logic.wSeq #= 0
    dut.wire.logic.fRep #= false
    cd.waitSampling()
  }

  /** Combinational read: drive the query PC, settle, sample {taken, index}. */
  def read(dut: GshareDut, cd: ClockDomain, pc: Long): (Boolean, Int) = {
    dut.wire.logic.qPc0 #= pc; dut.wire.logic.qV0 #= true
    sleep(1)
    val t = dut.wire.logic.oTaken.toBoolean
    val i = dut.wire.logic.oIndex.toInt
    (t, i)
  }

  /** Retire-train pht[index] toward `taken`. */
  def train(dut: GshareDut, cd: ClockDomain, index: Int, taken: Boolean): Unit = {
    dut.wire.logic.uV #= true
    dut.wire.logic.uIdx #= index
    dut.wire.logic.uTaken #= taken
    cd.waitSampling()
    dut.wire.logic.uV #= false
    cd.waitSampling()
  }

  /** Shift one directional bit into the GHR. */
  def shift(dut: GshareDut, cd: ClockDomain, dir: Boolean): Unit = {
    dut.wire.logic.sV #= true; dut.wire.logic.sD #= dir
    cd.waitSampling()
    dut.wire.logic.sV #= false
    cd.waitSampling()
  }

  for (il <- Seq(false, true)) test(s"index = fold(pc) XOR fold(ghr); GHR starts 0 [interleaved=$il]", VerilatorTest) {
    SimConfig.withVerilator.compile(new GshareDut(il)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      assert(dut.wire.logic.oGhr.toInt == 0, "GHR init 0")
      for (pc <- Seq(0x1000L, 0x2046L, 0x12348L, 0xABCDEL)) {
        val (_, idx) = read(dut, cd, pc)
        assert(idx == idxOf(pc, 0), s"pc=0x${pc.toHexString} HW idx $idx != model ${idxOf(pc, 0)}")
      }
    }
  }

  for (il <- Seq(false, true)) test(s"GHR shift folds into the index [interleaved=$il]", VerilatorTest) {
    SimConfig.withVerilator.compile(new GshareDut(il)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      // Shift in 1,0,1 -> GHR = 0b101 = 5.
      shift(dut, cd, true); shift(dut, cd, false); shift(dut, cd, true)
      assert(dut.wire.logic.oGhr.toInt == 5, s"GHR ${dut.wire.logic.oGhr.toInt} != 5")
      val pc = 0x3000L
      val (_, idx) = read(dut, cd, pc)
      assert(idx == idxOf(pc, 5), s"HW idx $idx != model ${idxOf(pc, 5)} (ghr=5)")
    }
  }

  for (il <- Seq(false, true)) test(s"counter init weakly-taken (2); 2->1 flips not-taken; saturates at 0 and 3 [interleaved=$il]", VerilatorTest) {
    SimConfig.withVerilator.compile(new GshareDut(il)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      val pc = 0x5000L
      val idx = idxOf(pc, 0)
      assert(read(dut, cd, pc)._1, "init 2 -> taken")
      train(dut, cd, idx, taken = false)              // 2 -> 1
      assert(!read(dut, cd, pc)._1, "1 -> not taken")
      train(dut, cd, idx, taken = false)              // 1 -> 0
      train(dut, cd, idx, taken = false)              // 0 -> 0 (saturate)
      assert(!read(dut, cd, pc)._1, "0 saturates -> not taken")
      train(dut, cd, idx, taken = true)               // 0 -> 1
      assert(!read(dut, cd, pc)._1, "1 -> not taken")
      train(dut, cd, idx, taken = true)               // 1 -> 2
      assert(read(dut, cd, pc)._1, "2 -> taken")
      train(dut, cd, idx, taken = true)               // 2 -> 3
      train(dut, cd, idx, taken = true)               // 3 -> 3 (saturate)
      assert(read(dut, cd, pc)._1, "3 saturates -> taken")
    }
  }

  for (il <- Seq(false, true)) test(s"learns the alternation: two GHR contexts at one PC converge to opposite dirs [interleaved=$il]", VerilatorTest) {
    SimConfig.withVerilator.compile(new GshareDut(il)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      // A single branch PC whose outcome alternates: after a TAKEN iteration (GHR low
      // bit 1) the next is NOT-taken; after a NOT-taken iteration (GHR low bit 0) the
      // next is TAKEN. Two GHR contexts (ghrA vs ghrB) index two PHT entries; train each
      // toward its (opposite) outcome and confirm the directions diverge.
      val pc = 0x6000L
      val ghrA = 0x0001  // "after taken" context
      val ghrB = 0x0000  // "after not-taken" context
      val idxA = idxOf(pc, ghrA)
      val idxB = idxOf(pc, ghrB)
      assert(idxA != idxB, "the two GHR contexts must map to distinct PHT entries")
      // Train context A strongly NOT-taken, context B strongly TAKEN.
      for (_ <- 0 until 3) { train(dut, cd, idxA, taken = false) }  // -> 0
      for (_ <- 0 until 3) { train(dut, cd, idxB, taken = true) }   // -> 3
      // Read with the GHR set to each context (shift the bits in), confirm opposite dirs.
      // ghrA = 1: shift one '1' from GHR=0.
      shift(dut, cd, true)
      assert(dut.wire.logic.oGhr.toInt == ghrA, "GHR == ghrA")
      assert(!read(dut, cd, pc)._1, "context A (after-taken) predicts NOT-taken")
      // Reset GHR to 0 = ghrB via invalidate.
      dut.wire.logic.inval #= true; cd.waitSampling(); dut.wire.logic.inval #= false; cd.waitSampling()
      assert(dut.wire.logic.oGhr.toInt == ghrB, "GHR == ghrB (0 after invalidate)")
      assert(read(dut, cd, pc)._1, "context B (after-not-taken) predicts TAKEN")
    }
  }

  for (il <- Seq(false, true)) test(s"registered four-way window lookup keeps cmd+1 token and changing-PC association [interleaved=$il]", VerilatorTest) {
    SimConfig.withVerilator.compile(new GshareDut(il)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      val w = dut.wire.logic

      // Establish a nonzero history and train a mix of directions at the exact
      // indices the four-wide reads will address.
      shift(dut, cd, true); shift(dut, cd, false); shift(dut, cd, true)
      val ghr = 5
      val pcs = (0 until 8).map(i => 0x7000L + i * 8L)
      val expectedTaken = Array.fill(8, 4)(true)
      for (i <- pcs.indices; off <- 0 until 4) {
        // Init is taken. Train a deterministic subset once to 1/not-taken.
        if (((i + off) & 1) != 0) {
          train(dut, cd, idxOf(pcs(i) + off * 2L, ghr), taken = false)
          expectedTaken(i)(off) = false
        }
      }

      var cmds = 0
      var rsps = 0
      for (i <- pcs.indices) {
        w.wV #= true; w.wPc #= pcs(i); w.wSlot #= (i % 3); w.wSeq #= (0x90 + i)
        cd.waitSampling(); sleep(1)
        cmds += 1
        assert(w.wRspV.toBoolean, s"missing window response $i")
        assert(w.wRspSlot.toInt == i % 3 && w.wRspSeq.toInt == 0x90 + i,
          s"window response $i lost its token")
        for (off <- 0 until 4) {
          val expectedIdx = idxOf(pcs(i) + off * 2L, ghr)
          assert(w.wRspIdx(off).toInt == expectedIdx,
            s"window $i word $off index ${w.wRspIdx(off).toInt} != $expectedIdx")
          assert(w.wRspTaken(off).toBoolean == expectedTaken(i)(off),
            s"window $i word $off direction mismatch")
        }
        rsps += 1
      }
      w.wV #= false; cd.waitSampling(); sleep(1)
      assert(!w.wRspV.toBoolean, "window Flow must end after exactly eight results")
      assert(cmds == 8 && rsps == 8)
    }
  }

  /** Pulse the ROB's registered commit-flush for one cycle. The repair is deliberately
    * ONE FLOP behind it inside the plugin (no combinational term on the redirect path),
    * so the restored value is only visible from the cycle after that. */
  def flushRepair(dut: GshareDut, cd: ClockDomain): Unit = {
    dut.wire.logic.fRep #= true
    cd.waitSampling()
    dut.wire.logic.fRep #= false
    cd.waitSampling()
    cd.waitSampling()
  }

  for (il <- Seq(false, true)) test(s"commit flush REPAIRS the whole GHR from the architectural history [interleaved=$il]", VerilatorTest) {
    SimConfig.withVerilator.compile(new GshareDut(il)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      // Two correct-path conditionals: the fetch side shifts the PREDICTED bit, the
      // retire side (the update Flow the ROB drives) shifts the RESOLVED bit. Same
      // sequence, so speculative and architectural histories agree.
      shift(dut, cd, true);  train(dut, cd, 0, true)
      shift(dut, cd, false); train(dut, cd, 0, false)
      assert(dut.wire.logic.oGhr.toInt == 2, s"spec GHR ${dut.wire.logic.oGhr.toInt} != 2")
      assert(dut.wire.logic.oGhrArch.toInt == 2, s"arch GHR ${dut.wire.logic.oGhrArch.toInt} != 2")

      // A mispredict: three WRONG-PATH conditionals shift before the branch resolves.
      shift(dut, cd, true); shift(dut, cd, true); shift(dut, cd, true)
      assert(dut.wire.logic.oGhr.toInt == 0x17, s"polluted GHR ${dut.wire.logic.oGhr.toInt} != 0x17")
      assert(dut.wire.logic.oGhrArch.toInt == 2, "arch GHR must be untouched by wrong-path shifts")

      // The commit flush discards every wrong-path entry; the repair overwrites the WHOLE
      // register with the architectural history. Pre-fix this test would still read 0x17
      // and the next lookup would fold a history that never happened.
      flushRepair(dut, cd)
      assert(dut.wire.logic.oGhr.toInt == 2,
        s"GHR ${dut.wire.logic.oGhr.toInt} was not repaired to the architectural 2")

      // The repaired GHR is the one the next lookup folds.
      val (_, idx) = read(dut, cd, 0x4000L)
      assert(idx == idxOf(0x4000L, 2), s"post-repair index $idx != model ${idxOf(0x4000L, 2)}")

      // invalidateAll clears BOTH histories (and beats a coincident repair).
      dut.wire.logic.qV0 #= false
      dut.wire.logic.inval #= true; cd.waitSampling(); dut.wire.logic.inval #= false
      cd.waitSampling()
      assert(dut.wire.logic.oGhr.toInt == 0 && dut.wire.logic.oGhrArch.toInt == 0,
        "invalidateAll must clear the speculative AND the architectural GHR")
    }
  }
  /** Step-A equivalence: the interleaved (row-of-four) PHT must give BIT-IDENTICAL
    * predictions to the flat 2-bit PHT for the same history/PC. Both organisations run
    * the SAME seeded random stimulus (decode queries on both ports, 8-aligned window
    * lookups, retire-time training incl. back-to-back same-row updates, GHR shifts,
    * flush repairs, invalidates); every cycle's outputs are recorded and compared, and
    * the whole table is swept through the decode port at the end. */
  test("interleaved PHT is bit-identical to the flat PHT (random stimulus + full sweep)", VerilatorTest) {
    val flat = equivRun(false, false)
    val inter = equivRun(true, false)
    assert(flat.size == inter.size)
    val firstDiff = flat.indices.find(i => flat(i) != inter(i))
    assert(firstDiff.isEmpty, s"divergence: flat='${firstDiff.map(flat).getOrElse("")}' " +
      s"interleaved='${firstDiff.map(inter).getOrElse("")}'")
    // Non-vacuous: the counters must actually have been trained both ways.
    val sweepBits = flat.filter(_.startsWith("sweep")).map(_.split(" ")(2)).mkString
    assert(sweepBits.contains('0') && sweepBits.contains('1'), "sweep saw only one direction")
    println(s"GSHARE_EQUIV cycles=20000 entries=$phtEntries ${flat.last} sweepNotTaken=${sweepBits.count(_ == '0')}")
  }

  /** CPU_GHR_TRIM: the trimmed GHR (min(16, idxBits) bits) must give bit-identical
    * indices, directions and window responses, and the same low history bits, under the
    * same random shift / retire / repair / invalidate stimulus. */
  for (il <- Seq(false, true)) test(s"trimmed GHR is prediction-identical to the 16-bit GHR [interleaved=$il]", VerilatorTest) {
    val fullRaw = equivRun(il, false)
    val trimRaw = equivRun(il, true)
    // Non-vacuous: the untrimmed GHR must actually have held bits above the fold.
    val hiSeen = fullRaw.find(_.startsWith("hiSeen=")).map(_.stripPrefix("hiSeen=").toInt).getOrElse(0)
    assert(hiSeen > 1000, s"the untrimmed run held history above the fold on only $hiSeen cycles")
    val full = fullRaw.filterNot(_.startsWith("hiSeen="))
    val trim = trimRaw.filterNot(_.startsWith("hiSeen="))
    assert(full.size == trim.size)
    val firstDiff = full.indices.find(i => full(i) != trim(i))
    assert(firstDiff.isEmpty, s"divergence: full='${firstDiff.map(full).getOrElse("")}' " +
      s"trim='${firstDiff.map(trim).getOrElse("")}'")
  }

  /** Shared random-stimulus trace. GHR values are reported as the bits the index folds
    * so a trimmed and an untrimmed DUT are comparable; the final `hiSeen=` line counts
    * cycles whose raw GHR had a bit above the fold (callers strip it before comparing). */
  def equivRun(il: Boolean, trim: Boolean): Seq[String] = {
      val histMask = (1 << idxHistBits) - 1
      val trace = scala.collection.mutable.ArrayBuffer[String]()
      var hiSeen = 0
      SimConfig.withVerilator.compile(new GshareDut(il, trim)).doSim(seed = 1) { dut =>
        val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
        val w = dut.wire.logic
        val rnd = new scala.util.Random(0x6511)
        val pcs = (0 until 64).map(_ => 0x10000L + 2L * rnd.nextInt(0x800))
        val recentIdx = scala.collection.mutable.ArrayBuffer[Int](0)
        for (cyc <- 0 until 20000) {
          w.qPc0 #= pcs(rnd.nextInt(pcs.size)); w.qV0 #= true
          w.qPc1 #= pcs(rnd.nextInt(pcs.size))
          w.sV #= rnd.nextInt(3) == 0; w.sD #= rnd.nextBoolean()
          w.fRep #= rnd.nextInt(97) == 0
          w.inval #= rnd.nextInt(3001) == 0
          val doUpd = rnd.nextInt(2) == 0
          w.uV #= doUpd
          // Train mostly indices that were actually looked up (incl. window lanes of
          // one row) so counters move across their whole range.
          w.uIdx #= (if (rnd.nextInt(4) == 0) rnd.nextInt(phtEntries) else recentIdx(rnd.nextInt(recentIdx.size)))
          w.uTaken #= rnd.nextInt(3) != 0
          w.wV #= rnd.nextBoolean(); w.wPc #= (pcs(rnd.nextInt(pcs.size)) & ~7L)
          w.wSlot #= rnd.nextInt(3); w.wSeq #= rnd.nextInt(256)
          sleep(1)
          val line = s"$cyc t0=${w.oTaken.toBoolean} i0=${w.oIndex.toInt} t1=${w.oTaken1.toBoolean} " +
            s"i1=${w.oIndex1.toInt} ghr=${w.oGhr.toInt & histMask} arch=${w.oGhrArch.toInt & histMask} wv=${w.wRspV.toBoolean} " +
            s"wt=${(0 until 4).map(k => if (w.wRspTaken(k).toBoolean) 1 else 0).mkString} " +
            s"wi=${(0 until 4).map(k => w.wRspIdx(k).toInt).mkString(",")}"
          trace += line
          if ((w.oGhr.toInt & ~histMask) != 0) hiSeen += 1
          recentIdx += w.oIndex.toInt; recentIdx += w.oIndex1.toInt
          if (w.wRspV.toBoolean) (0 until 4).foreach(k => recentIdx += w.wRspIdx(k).toInt)
          if (recentIdx.size > 256) recentIdx.remove(0, recentIdx.size - 256)
          cd.waitSampling()
        }
        // Full-table sweep through the decode port with a frozen history.
        w.sV #= false; w.uV #= false; w.wV #= false; w.fRep #= false; w.inval #= false
        cd.waitSampling(); cd.waitSampling(); cd.waitSampling()
        val sweep = new StringBuilder
        for (k <- 0 until phtEntries) {
          w.qPc0 #= 2L * k; sleep(1)
          sweep.append(if (w.oTaken.toBoolean) '1' else '0')
          if (k % 64 == 63) { trace += s"sweep ${k / 64} ${sweep.toString}"; sweep.clear() }
        }
        trace += s"windows=${trace.count(_.contains("wv=true"))}"
      }
      trace += s"hiSeen=$hiSeen"
      trace.toSeq
  }
}
