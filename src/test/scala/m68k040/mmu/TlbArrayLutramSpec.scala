package m68k040.mmu

import m68k040.VerilatorTest
import m68k040.cache.CacheMode
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** CPU_{D,I}TLB_ARRAY_LUTRAM: the LUTRAM-array Tlb must be output-identical, every
  * cycle, to the flop-array Tlb under the same stimulus (lookup, registered fill incl.
  * back-to-back same-row/same-key fills, replace-in-place, eviction report, FC2 tag,
  * invalidateAll racing a pending fill). */
class TlbArrayLutramSpec extends AnyFunSuite {
  class TapTlb(banks: Int, lut: Boolean)
      extends Tlb(banks = banks, reportEvictions = true, inhibitHint = true, arrayLutram = lut) {
    val tapHitVec = out Bits (Tlb.DefaultWays bits)
    tapHitVec := hitVec.asBits
  }
  class Pair(banks: Int) extends Component {
    val io = new Bundle {
      val lookupVpn = in UInt (Tlb.VpnBits bits)
      val lookupSup = in Bool ()
      val fillValid = in Bool ()
      val fillVpn   = in UInt (Tlb.VpnBits bits)
      val fillSup   = in Bool ()
      val fillEntry = in(TlbEntry())
      val invalidateAll = in Bool ()
      val mismatch  = out Bool ()
      val hit       = out Bool ()
      val evict     = out Bool ()
      val why       = out Bits (8 bits)
    }
    val ref = new TapTlb(banks, lut = false)
    val dut = new TapTlb(banks, lut = true)
    for (t <- Seq(ref, dut)) {
      t.io.lookupVpn := io.lookupVpn
      t.io.lookupSup := io.lookupSup
      t.io.fillValid := io.fillValid
      t.io.fillVpn   := io.fillVpn
      t.io.fillSup   := io.fillSup
      t.io.fillEntry := io.fillEntry
      t.io.invalidateAll := io.invalidateAll
      t.io.dbgMultiHotClear := False
    }
    val evictDiff = ref.io.evictValid && (
      ref.io.evictVpn =/= dut.io.evictVpn || ref.io.evictSup =/= dut.io.evictSup ||
      ref.io.evictEntry.asBits =/= dut.io.evictEntry.asBits)
    io.why := (ref.io.hit =/= dut.io.hit) ##
      // hitEntry is defined only when some way matched (MuxOH is an index mux).
      (ref.tapHitVec.orR && ref.io.hitEntry.asBits =/= dut.io.hitEntry.asBits) ##
      (ref.tapHitVec =/= dut.tapHitVec) ##
      (ref.io.hitInhibited =/= dut.io.hitInhibited) ##
      (ref.io.fillBusy =/= dut.io.fillBusy) ##
      (ref.io.evictValid =/= dut.io.evictValid) ## evictDiff ##
      (ref.io.dbgMultiHotSticky =/= dut.io.dbgMultiHotSticky)
    io.mismatch := io.why.orR
    io.hit   := ref.io.hit
    io.evict := ref.io.evictValid
  }

  for (banks <- Seq(2, 4)) {
    test(s"LUTRAM arrays are output-identical to flop arrays ($banks banks)", VerilatorTest) {
      SimConfig.withVerilator.compile(new Pair(banks)).doSim(s"tlb_lutram_$banks", 7) { dut =>
        val cd = dut.clockDomain
        cd.forkStimulus(10)
        dut.io.lookupVpn #= 0; dut.io.lookupSup #= false
        dut.io.fillValid #= false; dut.io.fillVpn #= 0; dut.io.fillSup #= false
        dut.io.fillEntry.vpnTag #= 0; dut.io.fillEntry.ppn #= 0
        dut.io.fillEntry.writeProt #= false; dut.io.fillEntry.supervisor #= false
        dut.io.fillEntry.cacheMode #= CacheMode.WRITETHROUGH; dut.io.fillEntry.modified #= false
        dut.io.invalidateAll #= false
        cd.waitSampling(5)
        val rng = new scala.util.Random(0x1a7ea + banks)
        val cms = Seq(CacheMode.WRITETHROUGH, CacheMode.COPYBACK, CacheMode.INHIBITED)
        // Small VPN universe: many same-row / same-key / resident collisions.
        val pool = Array.fill(96)(rng.nextInt(1 << 20))
        var lastFill = 0
        var hits, evicts, cycles = 0
        for (cycle <- 0 until 40000) {
          val burst = (cycle / 500) % 3 // 0: sparse fills, 1: back-to-back fills, 2: mostly lookups
          val fv = burst match { case 0 => rng.nextInt(3) == 0; case 1 => rng.nextInt(8) != 0; case _ => rng.nextInt(16) == 0 }
          val vpn = if (rng.nextInt(4) == 0) lastFill else pool(rng.nextInt(pool.length))
          dut.io.fillValid #= fv
          dut.io.fillVpn #= vpn
          dut.io.fillSup #= rng.nextInt(4) == 0
          dut.io.fillEntry.vpnTag #= rng.nextInt(1 << 16)
          dut.io.fillEntry.ppn #= rng.nextInt(1 << 20)
          dut.io.fillEntry.writeProt #= rng.nextBoolean()
          dut.io.fillEntry.supervisor #= rng.nextBoolean()
          dut.io.fillEntry.cacheMode #= cms(rng.nextInt(3))
          dut.io.fillEntry.modified #= rng.nextBoolean()
          if (fv) lastFill = vpn
          dut.io.lookupVpn #= (if (rng.nextInt(3) == 0) lastFill else pool(rng.nextInt(pool.length)))
          dut.io.lookupSup #= rng.nextInt(4) == 0
          dut.io.invalidateAll #= rng.nextInt(1500) == 0
          sleep(1)
          assert(!dut.io.mismatch.toBoolean, s"LUTRAM/flop TLB outputs differ at cycle $cycle why=${dut.io.why.toInt.toBinaryString}")
          if (dut.io.hit.toBoolean) hits += 1
          if (dut.io.evict.toBoolean) evicts += 1
          cycles += 1
          cd.waitSampling()
          sleep(1)
          assert(!dut.io.mismatch.toBoolean, s"LUTRAM/flop TLB outputs differ after edge $cycle why=${dut.io.why.toInt.toBinaryString}")
        }
        println(s"TLB_LUTRAM_COMPARE banks=$banks cycles=$cycles hits=$hits evicts=$evicts")
        assert(hits > 1000 && evicts > 1000)
      }
    }
  }
}
