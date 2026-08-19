package m68k040.mmu

import m68k040.VerilatorTest
import m68k040.cache.CacheMode
import m68k040.ls.BehavioralMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4ReadOnly}
import org.scalatest.funsuite.AnyFunSuite

/** Directed tests for the 68040 hardware 3-level table walker.
  *
  * A hand-built 3-level table is poked into a BehavioralMem (big-endian 32-bit
  * descriptors, task #194 — consistent with the walker's lane decode AND with what a
  * real architected `move.l` store would lay down). The walker is driven
  * with a VPN + root pointer + access class and the result is checked:
  *  - a resident walk -> non-identity PPN + perms + cache mode
  *  - a non-resident descriptor -> NON_RESIDENT fault
  *  - a write-protected page + isWrite -> WRITE_PROTECT fault
  *  - a supervisor-only page + user access -> SUPERVISOR fault
  *  - a resident write -> the deferred U/M descriptor write is produced */
class TableWalkerSpec extends AnyFunSuite {

  class Dut extends Component {
    val walker = new TableWalker()
    // expose the walker IO; bridge AXI to a top-level master for the behavioral mem
    val start = in Bool ()
    val vpn        = in UInt (20 bits)
    val rootPtr    = in UInt (32 bits)
    val isWrite    = in Bool ()
    val isSuper    = in Bool ()
    val is8K       = in Bool ()   // task #195
    val busy = out Bool ()
    val done = out Bool ()
    val fault = out Bool ()
    val faultReason = out(MmuFaultReason())
    val ppn = out UInt (20 bits)
    val writeProt = out Bool ()
    val supervisor = out Bool ()
    val inhibited = out Bool ()
    val umValid = out Bool ()
    val umAddr  = out UInt (32 bits)
    val umByte  = out Bits (8 bits)
    val mAxi = master(Axi4(walker.axiCfg))

    walker.io.start := start
    walker.io.req.vpn := vpn
    walker.io.req.rootPtr := rootPtr
    walker.io.req.isWrite := isWrite
    walker.io.req.isSuper := isSuper
    walker.io.req.is8K    := is8K
    busy := walker.io.busy
    done := walker.io.done
    fault := walker.io.rsp.fault
    faultReason := walker.io.rsp.faultReason
    ppn := walker.io.rsp.ppn
    writeProt := walker.io.rsp.writeProt
    supervisor := walker.io.rsp.supervisor
    inhibited := walker.io.rsp.cacheMode === CacheMode.INHIBITED
    umValid := walker.io.rsp.umWrite.valid
    umAddr  := walker.io.rsp.umWrite.addr
    umByte  := walker.io.rsp.umWrite.newByte
    // bridge the walker's read-only AXI to a full Axi4 master for the behavioral mem
    mAxi.ar << walker.io.axi.ar
    mAxi.r  >> walker.io.axi.r
    mAxi.aw.valid := False; mAxi.aw.payload.assignDontCare()
    mAxi.w.valid  := False; mAxi.w.payload.assignDontCare()
    mAxi.b.ready  := True
  }

  // --- table layout constants ---
  val ROOT = 0x10000L
  val PTRT = 0x11000L
  val PAGT = 0x12000L

  // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
  // MSB) — matches TableWalker.selectWord's corrected convention (mirrors real 68k
  // memory / DcacheByteLane.extract's LONG case). Kept the name.
  def pokeWordLE(mem: BehavioralMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)

  // VA fields for a 4 KB page: root(7)|ptr(7)|page(6)|offset(12)
  def rootIdx(va: Long): Int = ((va >> 25) & 0x7f).toInt
  def ptrIdx(va: Long): Int  = ((va >> 18) & 0x7f).toInt
  def pageIdx(va: Long): Int = ((va >> 12) & 0x3f).toInt
  def vpnOf(va: Long): Long  = (va >> 12) & 0xfffff
  // Task #195: VA fields for an 8 KB page: root(7)|ptr(7)|page(5)|offset(13) —
  // MC68040 UM S3.1.2/Fig 3-9. root/ptr indices are IDENTICAL to the 4K functions
  // above (unaffected by page size); only the page index narrows by one bit.
  def pageIdx8K(va: Long): Int = ((va >> 13) & 0x1f).toInt

  /** Build a resident 3-level table for `va` mapping to `ppn`, with the given page
    * descriptor low byte (PDT/W/U/M/...). Returns the page descriptor's byte address. */
  def buildTable(mem: BehavioralMemAgent, va: Long, ppn: Long,
                 pageWp: Boolean = false, pageSuper: Boolean = false,
                 pageInhibited: Boolean = false, pageResident: Boolean = true): Long = {
    // root entry -> pointer table
    val rootDesc = (PTRT & 0xfffffff0L) | 0x3L
    pokeWordLE(mem, ROOT + rootIdx(va) * 4, rootDesc)
    // pointer entry -> page table
    val ptrDesc = (PAGT & 0xfffffff0L) | 0x3L
    pokeWordLE(mem, PTRT + ptrIdx(va) * 4, ptrDesc)
    // page (leaf) descriptor
    var pd = (ppn << 12) & 0xfffff000L
    if (pageResident) pd |= 0x1L else pd |= 0x0L     // PDT 01 resident / 00 invalid
    if (pageWp) pd |= 0x4L                            // W
    if (pageInhibited) pd |= (0x2L << 5)              // CM[1] -> inhibited (bit 6)
    if (pageSuper) pd |= 0x80L                        // S
    val pageAddr = PAGT + pageIdx(va) * 4
    pokeWordLE(mem, pageAddr, pd)
    pageAddr
  }

  /** Task #195: the SAME table build as `buildTable`, but the leaf page descriptor
    * is placed at the 8K-mode page index (5-bit PGI, VA[17:13]) instead of the 4K
    * one — root/pointer levels are identical (index widths don't depend on page
    * size). Returns the page descriptor's byte address. */
  def buildTable8K(mem: BehavioralMemAgent, va: Long, ppn: Long,
                   pageWp: Boolean = false, pageSuper: Boolean = false,
                   pageInhibited: Boolean = false, pageResident: Boolean = true): Long = {
    val rootDesc = (PTRT & 0xfffffff0L) | 0x3L
    pokeWordLE(mem, ROOT + rootIdx(va) * 4, rootDesc)
    val ptrDesc = (PAGT & 0xfffffff0L) | 0x3L
    pokeWordLE(mem, PTRT + ptrIdx(va) * 4, ptrDesc)
    var pd = (ppn << 12) & 0xfffff000L
    if (pageResident) pd |= 0x1L else pd |= 0x0L
    if (pageWp) pd |= 0x4L
    if (pageInhibited) pd |= (0x2L << 5)
    if (pageSuper) pd |= 0x80L
    val pageAddr = PAGT + pageIdx8K(va) * 4
    pokeWordLE(mem, pageAddr, pd)
    pageAddr
  }

  def runWalk(dut: Dut, cd: ClockDomain, va: Long, isWrite: Boolean, isSuper: Boolean,
             is8K: Boolean = false): Unit = {
    dut.vpn     #= vpnOf(va)
    dut.rootPtr #= ROOT
    dut.isWrite #= isWrite
    dut.isSuper #= isSuper
    dut.is8K    #= is8K
    dut.start   #= true
    cd.waitSampling()
    dut.start   #= false
    // wait for done pulse
    var guard = 0
    while (!dut.done.toBoolean && guard < 200) { cd.waitSampling(); guard += 1 }
    assert(dut.done.toBoolean, "walk did not complete")
  }

  test("resident walk -> non-identity PPN + perms + cacheMode; faults; deferred U/M", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.mAxi, cd)
      dut.start #= false; dut.isWrite #= false; dut.isSuper #= false; dut.is8K #= false
      dut.vpn #= 0; dut.rootPtr #= 0
      cd.waitSampling(4)

      // ---- (1) plain resident read walk ----
      val va1 = 0x00802000L
      buildTable(mem, va1, ppn = 0xABCDEL)
      runWalk(dut, cd, va1, isWrite = false, isSuper = false)
      assert(!dut.fault.toBoolean, "resident read must not fault")
      assert(dut.ppn.toLong == 0xABCDEL, f"ppn=0x${dut.ppn.toLong}%x expected 0xABCDE")
      assert(!dut.inhibited.toBoolean, "default page is cacheable")
      // a read walk sets U only (M only on write). U-bit: current low byte was 0x01
      // (PDT resident), new = 0x01|0x08 = 0x09. Deferred write produced.
      assert(dut.umValid.toBoolean, "read walk must queue a U-bit descriptor write")
      assert((dut.umByte.toInt & 0xff) == 0x09, f"U-write byte=0x${dut.umByte.toInt}%x expected 0x09")
      // Task #194: the U/M byte lives at the descriptor's LOW byte, which in real
      // big-endian memory is the descriptor's HIGHEST byte address (base+3).
      assert(dut.umAddr.toLong == (PAGT + pageIdx(va1) * 4 + 3), "U-write addr = page descriptor low byte")

      // ---- (2) inhibited + supervisor page, supervisor read ----
      val va2 = 0x00C04000L
      buildTable(mem, va2, ppn = 0x12300L, pageInhibited = true, pageSuper = true)
      runWalk(dut, cd, va2, isWrite = false, isSuper = true)
      assert(!dut.fault.toBoolean, "supervisor access to supervisor page must not fault")
      assert(dut.ppn.toLong == 0x12300L, f"ppn=0x${dut.ppn.toLong}%x")
      assert(dut.inhibited.toBoolean, "page CM[1] set -> inhibited")
      assert(dut.supervisor.toBoolean, "supervisor bit reported")

      // ---- (3) non-resident page descriptor -> NON_RESIDENT fault ----
      val va3 = 0x01006000L
      buildTable(mem, va3, ppn = 0x55555L, pageResident = false)
      runWalk(dut, cd, va3, isWrite = false, isSuper = false)
      assert(dut.fault.toBoolean, "non-resident page must fault")
      assert(dut.faultReason.toEnum == MmuFaultReason.NON_RESIDENT, "reason = NON_RESIDENT")
      assert(!dut.umValid.toBoolean, "a faulting walk produces no descriptor write")

      // ---- (4) write-protected page + write -> WRITE_PROTECT fault ----
      val va4 = 0x01408000L
      buildTable(mem, va4, ppn = 0x66666L, pageWp = true)
      runWalk(dut, cd, va4, isWrite = true, isSuper = false)
      assert(dut.fault.toBoolean, "write to write-protected page must fault")
      assert(dut.faultReason.toEnum == MmuFaultReason.WRITE_PROTECT, "reason = WRITE_PROTECT")
      // but a READ of the same WP page is fine
      runWalk(dut, cd, va4, isWrite = false, isSuper = false)
      assert(!dut.fault.toBoolean, "read of a write-protected page is allowed")
      assert(dut.writeProt.toBoolean, "writeProt perm reported on the entry")

      // ---- (5) supervisor-only page + user access -> SUPERVISOR fault ----
      val va5 = 0x0180A000L
      buildTable(mem, va5, ppn = 0x77777L, pageSuper = true)
      runWalk(dut, cd, va5, isWrite = false, isSuper = false)
      assert(dut.fault.toBoolean, "user access to supervisor page must fault")
      assert(dut.faultReason.toEnum == MmuFaultReason.SUPERVISOR, "reason = SUPERVISOR")

      // ---- (6) resident write -> deferred U + M descriptor write ----
      val va6 = 0x01C0C000L
      val pAddr = buildTable(mem, va6, ppn = 0x88888L)
      runWalk(dut, cd, va6, isWrite = true, isSuper = false)
      assert(!dut.fault.toBoolean, "resident write must not fault")
      assert(dut.umValid.toBoolean, "write walk queues U+M descriptor write")
      // low byte 0x01 -> set U (0x08) and M (0x10) -> 0x19
      assert((dut.umByte.toInt & 0xff) == 0x19, f"U+M byte=0x${dut.umByte.toInt}%x expected 0x19")
      assert(dut.umAddr.toLong == pAddr + 3, "U/M-write addr = page descriptor low byte")
    }
  }

  // Task #195: TCR.P=1 (8KB pages) — the pointer->page-table offset uses a 5-bit
  // page index (VA[17:13]), NOT the 4K 6-bit one (VA[17:12]). va8k is chosen so the
  // two indices genuinely diverge (8K index=1, 4K-style index=3): a "decoy"
  // descriptor is planted at the WRONG (4K-style) slot with a different PPN, so a
  // regression back to the hardcoded-4K walk would read the decoy and fail loudly
  // instead of silently coincidentally passing.
  test("8K-page walk (TCR.P=1) reads the 5-bit page index, not the 4K 6-bit one", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.mAxi, cd)
      dut.start #= false; dut.isWrite #= false; dut.isSuper #= false; dut.is8K #= false
      dut.vpn #= 0; dut.rootPtr #= 0
      cd.waitSampling(4)

      val va8k = 0x00803000L
      assert(pageIdx8K(va8k) == 1 && pageIdx(va8k) == 3,
        "test fixture assumption: 8K/4K page indices must diverge for this VA")
      // Decoy at the 4K-style (WRONG, for this mode) slot — a walker that ignored
      // is8K would read THIS descriptor and report ppn=0xBADBAD.
      buildTable(mem, va8k, ppn = 0xBADBAL, pageResident = false)
      // Real 8K descriptor at the correct 5-bit-PGI slot.
      buildTable8K(mem, va8k, ppn = 0x9E9E0L)
      runWalk(dut, cd, va8k, isWrite = false, isSuper = false, is8K = true)
      assert(!dut.fault.toBoolean, "resident 8K page must not fault")
      assert(dut.ppn.toLong == 0x9E9E0L,
        f"ppn=0x${dut.ppn.toLong}%x expected 0x9E9E0 (8K page index) -- " +
        f"a value of 0xBADBA would mean the walker used the 4K page index instead")

      // Sanity: is8K=false against the SAME va8k must resolve through the decoy
      // slot instead (proves the Mux genuinely branches both ways, not just
      // "always 8K now").
      runWalk(dut, cd, va8k, isWrite = false, isSuper = false, is8K = false)
      assert(dut.fault.toBoolean, "4K-mode walk of va8k must hit the non-resident decoy")
      assert(dut.faultReason.toEnum == MmuFaultReason.NON_RESIDENT)
    }
  }
}
