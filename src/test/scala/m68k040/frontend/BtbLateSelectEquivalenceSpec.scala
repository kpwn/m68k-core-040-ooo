package m68k040.frontend

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.services.{BtbUpdate, BtbUpdateService}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import org.scalatest.funsuite.AnyFunSuite

/** THE load-bearing verification for FMax "Lever D" (BTB L0-late-select speculative
  * slot-1 lookup) —
  * `docs/superpowers/specs/2026-08-08-fmax-leverd-btb-late-select-design.md`.
  *
  * Lever D replaced slot 1's single `lookup(decodePc + 2*L0)` (whose RAM read could not
  * START until `L0` resolved, serializing the whole front-end `headPtr` feedback loop)
  * with NINE speculative `lookup(decodePc + 2k)` reads, k = 1..9, off the already-
  * registered `decodePc` alone, plus a late 16:1 mux that consumes `L0` only at the
  * point of use. The claim is that this is a PURE re-association of WHEN `L0` is
  * consumed — that the selected result is BIT-IDENTICAL to what the old single addressed
  * read produced, for every reachable `(decodePc, L0)`. This spec proves exactly that.
  *
  * WHY THIS IS A REAL PROOF, NOT A MODEL-VS-MODEL TAUTOLOGY. `BtbPlugin` still has an
  * UNTOUCHED slot-0 port: `lookup(queryPc, queryValid)` — literally the same `lookup`
  * function, on the same `mem` and the same `valids` register array, that slot 1 used
  * before this lever. So the reference implementation is not a re-derivation in the
  * testbench: it is the OLD RTL, still live in the same elaborated component. We drive
  * the slot-0 port with the OLD address expression (`base + 2*L0`, computed here exactly
  * as `Aligner.align` computed `slot1.pc = headPc + (L0 << 1)`), drive the NEW slot-1
  * (base, sel) interface with `(base, L0)`, and assert all four outputs agree —
  * `predTaken` (the only architecturally live one), plus `predTarget` / `predHit` /
  * `predType` for full interface fidelity.
  *
  * A third, independent Scala shadow model of the BTB's contents+decode is checked too,
  * so a hypothetical fault common to BOTH RTL ports could not hide.
  *
  * COVERAGE. Exhaustive over the space the outputs can actually discriminate:
  *  - every one of the 128 BTB index values as the BASE index (so `base + k` sweeps
  *    every index, including the k-carry wrap 127 -> 0 and the tag increment it implies),
  *  - every `L0` in 0..15 — the full 4-bit select, i.e. all 9 legal values AND all 7
  *    structurally-unreachable ones (which must degrade safely, see below),
  *  - four tag scenarios per index (exact-match, mismatch, the all-ones tag whose
  *    `base + 2k` wraps the 32-bit PC to 0, and tag 0),
  *  - both `query2Valid` polarities,
  *  - three whole-table content rounds: all-entries-valid with all four counter values
  *    and both brTypes represented; a half-populated table (miss-by-invalid at every
  *    other index); and a fully-populated table at a different tag (miss-by-tag at
  *    every index).
  *
  * The `L0` outside 1..9 cases are asserted to the SAFE-DEGRADATION contract rather than
  * to bit-identity: those ways read constant not-taken, and `predTaken2Comb`'s only
  * consumer (`FetchAlignPlugin.slot1WouldPred`) merely DEFERS slot 1 to the next cycle's
  * slot 0, so a False there costs at most a dual-issue slot and is never an architectural
  * commitment. Those values are also structurally unreachable: `slot1Ok` implies
  * `p0.simple` and `p1.simple`, and `PredecodeWord.classify(...).simple` never coincides
  * with `lenWords === 0` (proven exhaustively by `PredecodeSimpleLenSpec`), so
  * `L0 >= 1`, `L1 >= 1` and `L0 + L1 <= WINDOW(10)` force `1 <= L0 <= 9`. */
class BtbLateSelectEquivalenceSpec extends AnyFunSuite {

  /** Test-side BtbUpdate provider (same shape as `BtbPluginSpec`'s). */
  class BtbUpdateDriverPlugin extends FiberPlugin with BtbUpdateService {
    val logic = during build new Area {
      val upd = Flow(BtbUpdate())
      in(upd.valid)
      upd.payload.flatten.foreach(in(_))
    }
    override def btbUpdate: Flow[BtbUpdate] = logic.upd
  }

  /** Hoists BOTH lookup ports to top IO: port 0 = the untouched reference path, port 1 =
    * the new (base, sel) speculative + late-select path. */
  class BtbEquivWirePlugin extends FiberPlugin {
    val logic = during build new Area {
      val btb = host[BtbPlugin]
      // Reference (OLD) path: the unmodified `lookup(pc, valid)`, addressed by the
      // caller with the OLD `base + 2*L0` expression.
      val refPc    = in UInt (32 bits)
      val refValid = in Bool ()
      btb.logic.queryPc    := refPc
      btb.logic.queryValid := refValid
      // New (Lever D) path: base + sel, never a pre-summed address.
      val newBase  = in UInt (32 bits)
      val newSel   = in UInt (4 bits)
      val newValid = in Bool ()
      btb.logic.query2BasePc := newBase
      btb.logic.query2Sel    := newSel
      btb.logic.query2Valid  := newValid
      val inval = in Bool ()
      btb.logic.invalidateAll := inval

      val oRefTaken  = out(Bool());        oRefTaken  := btb.logic.predTakenComb
      val oRefTarget = out(UInt(32 bits)); oRefTarget := btb.logic.predTargetComb
      val oRefHit    = out(Bool());        oRefHit    := btb.logic.predHitComb
      val oRefType   = out(UInt(2 bits));  oRefType   := btb.logic.predTypeComb
      val oNewTaken  = out(Bool());        oNewTaken  := btb.logic.predTaken2Comb
      val oNewTarget = out(UInt(32 bits)); oNewTarget := btb.logic.predTarget2Comb
      val oNewHit    = out(Bool());        oNewHit    := btb.logic.predHit2Comb
      val oNewType   = out(UInt(2 bits));  oNewType   := btb.logic.predType2Comb
    }
  }

  class BtbEquivDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val drv  = new BtbUpdateDriverPlugin
    val btb  = new BtbPlugin
    val wire = new BtbEquivWirePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), drv, btb, wire)) }
  }

  // ---- Scala shadow model of the BTB (mirrors Btb.scala's own decode exactly) ----
  private val ENTRIES = 128
  private val M32     = BigInt(1) << 32
  private def idxOf(pc: BigInt): Int = ((pc >> 1) & (ENTRIES - 1)).toInt
  private def tagOf(pc: BigInt): BigInt = (pc >> 8) & ((BigInt(1) << 24) - 1)

  private class Shadow {
    val valid   = Array.fill(ENTRIES)(false)
    val tag     = Array.fill(ENTRIES)(BigInt(0))
    val target  = Array.fill(ENTRIES)(BigInt(0))
    val brType  = Array.fill(ENTRIES)(0)
    val counter = Array.fill(ENTRIES)(0)

    def invalidateAll(): Unit = java.util.Arrays.fill(valid, false)

    def update(pc: BigInt, taken: Boolean, tgt: BigInt, bt: Int): Unit = {
      val i   = idxOf(pc)
      val t   = tagOf(pc)
      val hit = valid(i) && tag(i) == t
      val ctr =
        // NB `scala.math` explicitly: `spinal.lib._` brings a `math` package into scope.
        if (hit) { if (taken) scala.math.min(counter(i) + 1, 3) else scala.math.max(counter(i) - 1, 0) }
        else if (bt == 1) 3
        else if (taken) 2
        else 1
      valid(i) = true; tag(i) = t; target(i) = tgt; brType(i) = bt; counter(i) = ctr
    }

    /** The OLD `lookup(pc, valid)` semantics, independently re-derived. */
    def lookup(pc: BigInt, v: Boolean): (Boolean, BigInt, Boolean, Int) = {
      val i      = idxOf(pc)
      val rawHit = v && valid(i) && tag(i) == tagOf(pc)
      val taken  = rawHit && (counter(i) >= 2 || brType(i) == 1)
      (taken, target(i), rawHit, brType(i))
    }
  }

  test("Lever D: 9-way speculative + late-L0-select is bit-identical to the addressed read",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new BtbEquivDut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val w  = dut.wire.logic
      val sh = new Shadow

      w.refPc #= 0; w.refValid #= false
      w.newBase #= 0; w.newSel #= 0; w.newValid #= false
      w.inval #= false
      dut.drv.logic.upd.valid #= false
      cd.waitSampling()

      def update(pc: BigInt, taken: Boolean, tgt: BigInt, bt: Int): Unit = {
        dut.drv.logic.upd.valid #= true
        dut.drv.logic.upd.payload.pc #= pc
        dut.drv.logic.upd.payload.taken #= taken
        dut.drv.logic.upd.payload.target #= tgt
        dut.drv.logic.upd.payload.brType #= bt
        dut.drv.logic.upd.payload.len #= 1
        cd.waitSampling()
        dut.drv.logic.upd.valid #= false
        sh.update(pc, taken, tgt, bt)
      }

      def invalidateAll(): Unit = {
        w.inval #= true; cd.waitSampling(); w.inval #= false; cd.waitSampling()
        sh.invalidateAll()
      }

      // ---- counters ----
      var checks       = 0L      // total (base, L0, tag, valid) points examined
      var legalChecks  = 0L      // of those, in the structurally reachable L0 in 1..9
      var oorChecks    = 0L      // L0 outside 1..9 (safe-degradation contract)
      var sawTaken     = 0L
      var sawNotTaken  = 0L
      var sawHit       = 0L
      var sawMiss      = 0L
      val sawCounter   = scala.collection.mutable.Set[Int]()
      val sawBrType    = scala.collection.mutable.Set[Int]()
      val sawSel       = scala.collection.mutable.Set[Int]()
      var shadowChecks = 0L

      /** One equivalence point: reference port at `base + 2*L0`, new port at (base, L0). */
      def check(base: BigInt, l0: Int, v: Boolean): Unit = {
        val oldPc = (base + BigInt(2 * l0)) % M32     // exactly Aligner's headPc + (L0 << 1)
        w.refPc #= oldPc; w.refValid #= v
        w.newBase #= base; w.newSel #= l0; w.newValid #= v
        sleep(1)                                      // settle the async reads (combinational)

        val rT = w.oRefTaken.toBoolean; val nT = w.oNewTaken.toBoolean
        val rH = w.oRefHit.toBoolean;   val nH = w.oNewHit.toBoolean
        val rG = w.oRefTarget.toBigInt; val nG = w.oNewTarget.toBigInt
        val rY = w.oRefType.toBigInt;   val nY = w.oNewType.toBigInt
        checks += 1
        sawSel += l0

        if (l0 >= 1 && l0 <= 9) {
          legalChecks += 1
          assert(rT == nT, f"predTaken mismatch: base=0x$base%08x L0=$l0 v=$v " +
                           f"(pc=0x$oldPc%08x) old=$rT new=$nT")
          assert(rH == nH, f"predHit mismatch: base=0x$base%08x L0=$l0 v=$v " +
                           f"(pc=0x$oldPc%08x) old=$rH new=$nH")
          assert(rG == nG, f"predTarget mismatch: base=0x$base%08x L0=$l0 v=$v " +
                           f"(pc=0x$oldPc%08x) old=0x$rG%08x new=0x$nG%08x")
          assert(rY == nY, f"predType mismatch: base=0x$base%08x L0=$l0 v=$v " +
                           f"(pc=0x$oldPc%08x) old=$rY new=$nY")
          // Independent Scala model of the OLD semantics (catches a fault common to both
          // RTL ports, which the RTL-vs-RTL comparison alone could not see).
          val (sT, sG, sH, sY) = sh.lookup(oldPc, v)
          assert(sT == nT && sH == nH && sG == nG && sY == nY.toInt,
                 f"shadow-model mismatch: base=0x$base%08x L0=$l0 v=$v pc=0x$oldPc%08x " +
                 f"model=($sT,0x$sG%08x,$sH,$sY) rtl=($nT,0x$nG%08x,$nH,$nY)")
          shadowChecks += 1
          if (nT) sawTaken += 1 else sawNotTaken += 1
          if (nH) { sawHit += 1; sawCounter += sh.counter(idxOf(oldPc)); sawBrType += sh.brType(idxOf(oldPc)) }
          else sawMiss += 1
        } else {
          oorChecks += 1
          // Safe-degradation contract for the structurally unreachable selects.
          assert(!nT, f"out-of-range L0=$l0 must read not-taken (base=0x$base%08x v=$v)")
          assert(!nH, f"out-of-range L0=$l0 must read no-hit (base=0x$base%08x v=$v)")
          assert(nG == 0 && nY == 0,
                 f"out-of-range L0=$l0 must read zero target/type (base=0x$base%08x v=$v)")
        }
      }

      // Tag scenarios. TAG_A is what rounds 1/2 populate; TAG_B never appears in the
      // table (pure tag-mismatch misses); TAG_MAX exercises the 32-bit PC wrap on
      // `base + 2k`; TAG_ZERO the low end.
      val TAG_A   = BigInt(0xabcde)
      val TAG_B   = BigInt(0x13579)
      val TAG_MAX = (BigInt(1) << 24) - 1
      val TAG_ZERO = BigInt(0)
      val tags = Seq(TAG_A, TAG_B, TAG_MAX, TAG_ZERO)

      def sweep(): Unit =
        for (idx <- 0 until ENTRIES; tag <- tags) {
          val base = ((tag << 8) | (BigInt(idx) << 1)) % M32
          for (l0 <- 0 until 16; v <- Seq(true, false)) check(base, l0, v)
        }

      // ── Round 1: every entry valid at TAG_A, all four counter values + both brTypes ──
      // Per-entry recipe (allocation seeds: uncond -> 3; taken -> 2; not-taken -> 1):
      //   i%5==0 : cond, ctr=0 (alloc not-taken, then one more not-taken)
      //   i%5==1 : cond, ctr=1 (alloc not-taken)
      //   i%5==2 : cond, ctr=2 (alloc taken)
      //   i%5==3 : cond, ctr=3 (alloc taken, then one more taken)
      //   i%5==4 : uncond, ctr=3
      for (i <- 0 until ENTRIES) {
        val pc  = ((TAG_A << 8) | (BigInt(i) << 1)) % M32
        val tgt = (BigInt(0x10000000) + i * 4) % M32
        i % 5 match {
          case 0 => update(pc, taken = false, tgt, 0); update(pc, taken = false, tgt, 0)
          case 1 => update(pc, taken = false, tgt, 0)
          case 2 => update(pc, taken = true,  tgt, 0)
          case 3 => update(pc, taken = true,  tgt, 0); update(pc, taken = true, tgt, 0)
          case _ => update(pc, taken = true,  tgt, 1)
        }
      }
      cd.waitSampling()
      sweep()
      val afterR1 = checks

      // ── Round 2: half-populated (even indices only) -> miss-by-INVALID at every odd idx
      invalidateAll()
      for (i <- 0 until ENTRIES by 2) {
        val pc  = ((TAG_A << 8) | (BigInt(i) << 1)) % M32
        update(pc, taken = true, (BigInt(0x20000000) + i * 4) % M32, if (i % 4 == 0) 1 else 0)
      }
      cd.waitSampling()
      sweep()
      val afterR2 = checks

      // ── Round 3: fully populated at TAG_MAX -> the TAG_A/TAG_B sweeps become pure
      //    miss-by-TAG at every index, and the TAG_MAX sweep hits with a wrapping PC.
      invalidateAll()
      for (i <- 0 until ENTRIES) {
        val pc  = ((TAG_MAX << 8) | (BigInt(i) << 1)) % M32
        update(pc, taken = i % 2 == 0, (BigInt(0x30000000) + i * 4) % M32, if (i % 3 == 0) 1 else 0)
      }
      cd.waitSampling()
      sweep()

      // ---- report genuine coverage (not just "no assertion failed") ----
      println(s"[Lever D equivalence] total points          = $checks")
      println(s"[Lever D equivalence]   round 1 (all valid) = $afterR1")
      println(s"[Lever D equivalence]   round 2 (half)      = ${afterR2 - afterR1}")
      println(s"[Lever D equivalence]   round 3 (tag-max)   = ${checks - afterR2}")
      println(s"[Lever D equivalence] legal L0 in 1..9      = $legalChecks (bit-identity asserted)")
      println(s"[Lever D equivalence] out-of-range L0       = $oorChecks (safe-degradation asserted)")
      println(s"[Lever D equivalence] shadow-model checks   = $shadowChecks")
      println(s"[Lever D equivalence] outcomes: taken=$sawTaken notTaken=$sawNotTaken " +
              s"hit=$sawHit miss=$sawMiss")
      println(s"[Lever D equivalence] counters exercised on a hit = ${sawCounter.toSeq.sorted.mkString(",")}")
      println(s"[Lever D equivalence] brTypes  exercised on a hit = ${sawBrType.toSeq.sorted.mkString(",")}")
      println(s"[Lever D equivalence] select values exercised     = ${sawSel.size}/16")

      // ---- non-vacuity: the sweep must actually have discriminated something ----
      assert(checks == 3L * ENTRIES * tags.size * 16 * 2,
             s"sweep did not cover the intended space: $checks")
      assert(legalChecks == 3L * ENTRIES * tags.size * 9 * 2, s"legal-L0 count wrong: $legalChecks")
      assert(sawSel.size == 16, "not every 4-bit select value was exercised")
      assert(sawTaken > 0 && sawNotTaken > 0, "sweep never produced both taken and not-taken")
      assert(sawHit > 0 && sawMiss > 0, "sweep never produced both a hit and a miss")
      assert(sawCounter == Set(0, 1, 2, 3), s"not all counter values hit: $sawCounter")
      assert(sawBrType == Set(0, 1), s"not both brTypes hit: $sawBrType")
    }
  }
}
