package m68k040.mmu

import m68k040.VerilatorTest
import m68k040.cache.CacheMode
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** `Tlb` returns a CORRUPTED entry when the same VPN is filled twice.
  *
  * `Tlb.scala`'s fill picks its way purely from the per-(bank,set) round-robin
  * `victim` counter and never checks whether the VPN is already resident.  Fill the
  * same VPN twice and two ways of that set end up valid with the same tag, so the
  * lookup's `hitVec` is TWO-hot -- and `io.hitEntry := MuxOH(hitVec, entVec)` is only
  * defined for a ONE-HOT select.  SpinalHDL lowers `MuxOH` to an index select whose
  * index is derived by OR-ing the set bit positions, so ways {0,1,2} select index
  * 0|1|2 = 3: a way that did not match at all.  Its contents are returned upward as a
  * valid, NON-FAULTING translation.
  *
  * A double fill of one VPN is not hypothetical.  `DtlbPlugin`'s `needsMRefresh`
  * (task #210, MC68040 UM S3.3) deliberately re-walks a VPN that is ALREADY RESIDENT
  * when a write hits an entry whose `modified` bit is clear -- and that re-walk ends in
  * the same unconditional `tlb.io.fillValid := True`.  So every read-then-write to a
  * page whose leaf descriptor starts with M=0 duplicates that page's entry.
  *
  * Found from the other end first: `WalkerExcEntryWedgeSpec` caught a full-core run
  * issuing AXI to physical `0xae0cf000` for a VA whose only descriptor says `0x60008`,
  * with no fault reported by the MMU, the D-cache or the walker -- it DECERRed and
  * became a spurious vector-2 access fault.  The wrong PPN changed value between builds
  * (0x128a2 / 0x31de3 / 0xae0cf), which is what reading a never-written way looks like.
  * That run showed VPN 0x60008 being FILLED TWICE, 86 cycles apart, and `dbgHitCount`
  * above 1 on 1299 cycles.
  *
  * PRE-EXISTING, NOT an arm-C regression: `Tlb.scala` was last touched by `e0d2752`
  * (task #210), and `2db5bd3` does not touch it at all.  Both the ITLB and the DTLB
  * instantiate this component, so both are exposed.
  */
class TlbDuplicateFillSpec extends AnyFunSuite {

  private def fill(dut: Tlb, cd: ClockDomain, vpn: Long, ppn: Long): Unit = {
    dut.io.fillVpn #= vpn
    dut.io.fillSup #= false
    dut.io.fillEntry.ppn #= ppn
    dut.io.fillEntry.vpnTag #= 0
    dut.io.fillEntry.writeProt #= false
    dut.io.fillEntry.supervisor #= false
    dut.io.fillEntry.cacheMode #= CacheMode.COPYBACK
    dut.io.fillEntry.modified #= false
    dut.io.fillValid #= true
    cd.waitSampling()
    dut.io.fillValid #= false
    cd.waitSampling()
  }

  private def lookup(dut: Tlb, cd: ClockDomain, vpn: Long): (Boolean, Long, Int) = {
    dut.io.lookupVpn #= vpn
    dut.io.lookupSup #= false
    cd.waitSampling()
    // combinational lookup: sleep(0) settles the mux after the poke
    sleep(0)
    (dut.io.hit.toBoolean, dut.io.hitEntry.ppn.toLong, dut.dbgHitCount.toInt)
  }

  test("refilling a resident VPN replaces its way instead of duplicating it",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Tlb()).doSim("tlb_dup", 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      dut.io.fillValid #= false
      dut.io.invalidateAll #= false
    dut.io.dbgMultiHotClear #= false
      dut.io.lookupVpn #= 0
      dut.io.lookupSup #= false
      cd.waitSampling(2)
      dut.io.invalidateAll #= true
      cd.waitSampling()
      dut.io.invalidateAll #= false
      cd.waitSampling(2)

      val vpn = 0x60008L

      // ---- non-vacuity: ONE fill behaves correctly ----
      fill(dut, cd, vpn, 0x11111L)
      val (hit1, ppn1, n1) = lookup(dut, cd, vpn)
      assert(hit1, "a single fill did not even produce a hit -- the test is broken")
      assert(n1 == 1, s"a single fill already matched $n1 ways -- the test is broken")
      assert(ppn1 == 0x11111L,
        f"a single fill read back 0x$ppn1%05x, expected 0x11111 -- the test is broken")

      // ---- the defect: fill the SAME VPN again ----
      // Chosen so the bitwise merge is unmistakable and cannot be either input:
      // 0x11111 | 0x22222 == 0x33333.
      fill(dut, cd, vpn, 0x22222L)
      val (hit2, ppn2, n2) = lookup(dut, cd, vpn)

      println(f"[tlbdup] after two fills of vpn 0x$vpn%05x: hit=$hit2 ways=$n2 " +
        f"ppn=0x$ppn2%05x  (fills were 0x11111 then 0x22222)")

      assert(n2 == 1,
        f"DUPLICATE TLB ENTRY: $n2 ways match vpn 0x$vpn%05x after filling it twice. " +
        f"Nothing in the fill path checked whether the VPN was already resident, and " +
        f"DtlbPlugin's `needsMRefresh` re-walk refills an ALREADY-RESIDENT VPN by design.")
      assert(ppn2 == 0x22222L,
        f"the second fill should have replaced the entry; read back 0x$ppn2%05x")

      // With exactly ways {0,1} matching, `MuxOH` still happened to return way 1, so a
      // 2-way duplicate is LATENT. It becomes corruption as soon as the matching set is
      // not {0,1}: ways {0,1,2} select index 0|1|2 = 3, a way that never matched. A
      // third fill of the same VPN is all it took.
      fill(dut, cd, vpn, 0x04444L)
      val (hit3, ppn3, n3) = lookup(dut, cd, vpn)
      println(f"[tlbdup] after THREE fills: hit=$hit3 ways=$n3 ppn=0x$ppn3%05x " +
        f"(fills were 0x11111, 0x22222, 0x04444)")
      assert(Set(0x11111L, 0x22222L, 0x04444L).contains(ppn3),
        f"MISTRANSLATION: after three fills of vpn 0x$vpn%05x the lookup returned ppn " +
        f"0x$ppn3%05x, which is NONE of the three values ever written for it. " +
        f"$n3 ways matched, and `MuxOH` selected a way that did not. This is delivered " +
        f"to the LS pipe as a valid, non-faulting translation.")
      assert(n3 == 1, s"three fills of one VPN left $n3 ways matching")
      assert(ppn3 == 0x04444L,
        f"the third fill should have replaced the entry; read back 0x$ppn3%05x")
    }
  }
}
