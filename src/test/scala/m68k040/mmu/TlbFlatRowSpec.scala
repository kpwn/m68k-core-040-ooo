package m68k040.mmu

import m68k040.VerilatorTest
import m68k040.cache.CacheMode
import m68k040.hw.OneHotSafe
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._

class TlbFlatRowSpec extends AnyFunSuite {
  // The reference intentionally keeps the original nested bank/way/set accesses.
  // It observes the actual storage; no duplicated fill/replacement model can hide
  // a disagreement in which register the production lookup selects.
  class ComparedTlb(bankCount: Int) extends Tlb(banks = bankCount) {
    val oldHits = Vec(Bool(), Tlb.DefaultWays)
    val oldEntries = Vec(TlbEntry(), Tlb.DefaultWays)
    for (w <- 0 until Tlb.DefaultWays) {
      val oldTag = tags(lkBank)(w)(lkSet)
      oldHits(w) := valids(lkBank)(w)(lkSet) && oldTag === lkTag &&
        tagSup(lkBank)(w)(lkSet) === io.lookupSup
      oldEntries(w).vpnTag := oldTag
      oldEntries(w).ppn := ppns(lkBank)(w)(lkSet)
      oldEntries(w).writeProt := wProt(lkBank)(w)(lkSet)
      oldEntries(w).supervisor := sup(lkBank)(w)(lkSet)
      oldEntries(w).cacheMode := cmode(lkBank)(w)(lkSet)
      oldEntries(w).modified := modif(lkBank)(w)(lkSet)
      spinal.core.assert(hitVec(w) === oldHits(w), "flat TLB way match differs from nested lookup")
      spinal.core.assert(entVec(w).asBits === oldEntries(w).asBits,
        "flat TLB payload differs from nested lookup")
    }
    spinal.core.assert(io.hit === OneHotSafe.exactlyOne(oldHits), "flat TLB hit verdict changed")
    spinal.core.assert(io.hitEntry.asBits === MuxOH(oldHits, oldEntries).asBits,
      "flat TLB hit entry changed")
  }

  // The unchanged TLB cannot elaborate singleton banks/sets (its fill/purge
  // paths index singleton Vecs with one-bit indices). Keep this lookup-only
  // experiment on geometries supported by the baseline, without hiding that bug.
  for (bankCount <- Seq(2, 4)) {
    test(s"flat row matches nested lookup for $bankCount banks, all rows and mixed traffic", VerilatorTest) {
      SimConfig.withVerilator.compile(new ComparedTlb(bankCount)).doSim { dut =>
        val cd = dut.clockDomain
        cd.forkStimulus(10)
        dut.io.lookupVpn #= 0
        dut.io.lookupSup #= false
        dut.io.fillValid #= false
        dut.io.fillVpn #= 0
        dut.io.fillSup #= false
        dut.io.fillEntry.vpnTag #= 0
        dut.io.fillEntry.ppn #= 0
        dut.io.fillEntry.writeProt #= false
        dut.io.fillEntry.supervisor #= false
        dut.io.fillEntry.cacheMode #= CacheMode.WRITETHROUGH
        dut.io.fillEntry.modified #= false
        dut.io.invalidateAll #= false
        dut.io.dbgMultiHotClear #= false
        cd.waitSampling(5)
        val rng = new scala.util.Random(0x71b040L + bankCount)
        var checks = 0
        var hitCount = 0
        var missCount = 0
        def entry(vpn: Int): (Int, Boolean, Boolean, CacheMode.E, Boolean) = {
          val ppn = (vpn * 31847 ^ 0x5a39c) & 0xfffff
          (ppn, (vpn & 1) != 0, (vpn & 2) != 0,
            Seq(CacheMode.WRITETHROUGH, CacheMode.COPYBACK, CacheMode.INHIBITED)(vpn % 3),
            (vpn & 8) != 0)
        }
        def driveFill(vpn: Int, fc: Boolean): Unit = {
          val (ppn, wp, sp, cm, md) = entry(vpn)
          dut.io.fillVpn #= vpn
          dut.io.fillSup #= fc
          dut.io.fillEntry.vpnTag #= (vpn >> 3)
          dut.io.fillEntry.ppn #= ppn
          dut.io.fillEntry.writeProt #= wp
          dut.io.fillEntry.supervisor #= sp
          dut.io.fillEntry.cacheMode #= cm
          dut.io.fillEntry.modified #= md
        }
        // Four tags per physical bank/set row; FC2 varies independently.
        for (tag <- 1 to 4; low <- 0 until 8) {
          driveFill((tag << 3) | low, (tag & 1) != 0)
          dut.io.fillValid #= true
          cd.waitSampling()
          sleep(1)
        }
        dut.io.fillValid #= false
        for (tag <- 1 to 4; low <- 0 until 8; matchingFc <- Seq(true, false)) {
          val vpn = (tag << 3) | low
          dut.io.lookupVpn #= vpn
          dut.io.lookupSup #= (if (matchingFc) (tag & 1) != 0 else (tag & 1) == 0)
          cd.waitSampling()
          sleep(1)
          assert(dut.io.hit.toBoolean == matchingFc)
          if (matchingFc) {
            val (ppn, wp, sp, cm, md) = entry(vpn)
            assert(dut.io.hitEntry.ppn.toInt == ppn)
            assert(dut.io.hitEntry.vpnTag.toInt == tag)
            assert(dut.io.hitEntry.writeProt.toBoolean == wp)
            assert(dut.io.hitEntry.supervisor.toBoolean == sp)
            assert(dut.io.hitEntry.cacheMode.toEnum == cm)
            assert(dut.io.hitEntry.modified.toBoolean == md)
            hitCount += 1
          } else missCount += 1
          checks += 1
        }
        for (cycle <- 0 until 6000) {
          val fillVpn = rng.nextInt(1 << 20)
          driveFill(fillVpn, rng.nextBoolean())
          dut.io.fillValid #= (rng.nextInt(4) == 0)
          val lookupVpn = if (cycle % 3 == 0) fillVpn else rng.nextInt(1 << 20)
          dut.io.lookupVpn #= BigInt(lookupVpn)
          dut.io.lookupSup #= rng.nextBoolean()
          dut.io.invalidateAll #= (cycle % 251 == 250)
          dut.io.dbgMultiHotClear #= (cycle % 137 == 0)
          cd.waitSampling()
          sleep(1)
          if (dut.io.hit.toBoolean) hitCount += 1 else missCount += 1
          checks += 1
        }
        assert(hitCount >= 32 && missCount >= 32)
        println(s"TLB_ROW_COMPARE banks=$bankCount checks=$checks hits=$hitCount misses=$missCount")
      }
    }
  }
}
