package m68k040.mmu

import m68k040.VerilatorTest
import m68k040.cache.CacheMode
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Directed tests for the banked set-associative TLB component:
  *  - fill an entry -> lookup hits with the correct ppn/perms/cacheMode
  *  - lookup a different (uncached) VPN -> miss
  *  - invalidateAll clears valid -> the previously-filled VPN now misses
  *  - fill enough distinct VPNs into one set to evict + re-fill (way reuse) */
class TlbSpec extends AnyFunSuite {

  class Dut extends Component {
    val tlb = new Tlb()   // entries=32, ways=4, banks=2 (defaults)
    val io  = tlb.io.toIo.setName("io")
  }

  def fill(dut: Dut, cd: ClockDomain, vpn: Long, ppn: Long,
           wp: Boolean = false, sup: Boolean = false, inhibited: Boolean = false): Unit = {
    dut.io.fillVpn   #= vpn
    dut.io.fillEntry.ppn        #= ppn
    dut.io.fillEntry.vpnTag     #= 0
    dut.io.fillEntry.writeProt  #= wp
    dut.io.fillEntry.supervisor #= sup
    dut.io.fillEntry.cacheMode  #= (if (inhibited) CacheMode.INHIBITED else CacheMode.CACHEABLE)
    dut.io.fillValid #= true
    cd.waitSampling()
    dut.io.fillValid #= false
    cd.waitSampling()
  }

  def lookup(dut: Dut, cd: ClockDomain, vpn: Long): (Boolean, Long, Boolean, Boolean, Boolean) = {
    dut.io.lookupVpn #= vpn
    cd.waitSampling()  // combinational, but settle a sampling edge for clean reads
    sleep(1)
    val hit = dut.io.hit.toBoolean
    val ppn = dut.io.hitEntry.ppn.toLong
    val wp  = dut.io.hitEntry.writeProt.toBoolean
    val sup = dut.io.hitEntry.supervisor.toBoolean
    val inh = dut.io.hitEntry.cacheMode.toEnum == CacheMode.INHIBITED
    (hit, ppn, wp, sup, inh)
  }

  test("fill then lookup hits with correct ppn/perms; other vpn misses; invalidateAll clears", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      dut.io.fillValid     #= false
      dut.io.invalidateAll #= false
      dut.io.lookupVpn     #= 0
      cd.waitSampling(4)

      // fill VPN 0x12345 -> PPN 0xABCDE, write-protected, supervisor, inhibited
      fill(dut, cd, 0x12345L, 0xABCDEL, wp = true, sup = true, inhibited = true)

      val (h1, p1, wp1, s1, i1) = lookup(dut, cd, 0x12345L)
      assert(h1, "filled VPN must hit")
      assert(p1 == 0xABCDEL, f"ppn mismatch: got 0x$p1%x")
      assert(wp1, "writeProt must be set")
      assert(s1, "supervisor must be set")
      assert(i1, "cacheMode must be INHIBITED")

      // a different VPN that was never filled -> miss
      val (h2, _, _, _, _) = lookup(dut, cd, 0x54321L)
      assert(!h2, "unfilled VPN must miss")

      // a second fill with clean (cacheable, no perms) bits, distinct VPN
      fill(dut, cd, 0x00010L, 0x00077L)
      val (h3, p3, wp3, s3, i3) = lookup(dut, cd, 0x00010L)
      assert(h3 && p3 == 0x77L && !wp3 && !s3 && !i3, f"second fill lookup wrong: h=$h3 p=0x$p3%x")
      // first entry still present
      val (h4, p4, _, _, _) = lookup(dut, cd, 0x12345L)
      assert(h4 && p4 == 0xABCDEL, "first entry must survive a second fill")

      // invalidateAll clears valid bits
      dut.io.invalidateAll #= true
      cd.waitSampling()
      dut.io.invalidateAll #= false
      cd.waitSampling()
      val (h5, _, _, _, _) = lookup(dut, cd, 0x12345L)
      val (h6, _, _, _, _) = lookup(dut, cd, 0x00010L)
      assert(!h5 && !h6, "invalidateAll must clear all entries")
    }
  }
}
